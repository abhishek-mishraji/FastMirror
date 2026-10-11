package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyLedger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ledger as a text file, {@code copied.ledger}, one line per copied file.
 *
 * <p>Each line is {@code <creation epoch ms> TAB <size> TAB <relative path>}. Lines are appended
 * with {@code SYNC}, so a recorded copy survives a power cut. A torn last line from a power cut
 * during the append is skipped on load, which at worst means that one file is copied again.
 *
 * <p>At startup the ledger is compacted: entries whose source file no longer exists are dropped.
 * They can never be copied again, so keeping them would only grow the file forever. The rewrite
 * goes through a temporary file and an atomic move, so a crash mid-compaction leaves the old ledger
 * intact.
 *
 * <p>This class never opens a source file. Compaction checks for existence, which is an attribute
 * read, and all writes go to the state folder.
 */
public final class FileCopyLedgerImpl implements CopyLedger {

    public static final String FILE_NAME = "copied.ledger";

    private static final String TEMP_SUFFIX = ".tmp";

    private final Path file;
    private final AppLogger logger;
    private final Set<LedgerKey> entries = ConcurrentHashMap.newKeySet();

    private FileCopyLedgerImpl(Path file, AppLogger logger) {
        this.file = file;
        this.logger = logger;
    }

    /**
     * Loads and compacts the ledger in {@code stateFolder}, creating it on first use.
     *
     * @param sourceRoot the source folder, used to drop entries for files that no longer exist
     * @throws IOException if the ledger exists but cannot be read
     */
    public static FileCopyLedgerImpl open(Path stateFolder, Path sourceRoot, AppLogger logger)
            throws IOException {
        Objects.requireNonNull(stateFolder, "stateFolder");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(logger, "logger");

        final FileCopyLedgerImpl ledger =
                new FileCopyLedgerImpl(stateFolder.resolve(FILE_NAME), logger);
        ledger.load(sourceRoot);
        return ledger;
    }

    @Override
    public boolean contains(LedgerKey key) {
        return entries.contains(Objects.requireNonNull(key, "key"));
    }

    @Override
    public synchronized boolean record(LedgerKey key) {
        Objects.requireNonNull(key, "key");
        if (entries.contains(key)) {
            return true;
        }
        try {
            Files.write(
                    file,
                    (key.toLine() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.SYNC);
        } catch (final IOException e) {
            logger.error(
                    "Could not record a completed copy in " + file + ". The file is copied, but it"
                            + " will be copied again after a restart: " + key.relativePath(),
                    e);
            return false;
        }
        entries.add(key);
        return true;
    }

    @Override
    public int size() {
        return entries.size();
    }

    /** The ledger file, for the startup log line. */
    public Path file() {
        return file;
    }

    private void load(Path sourceRoot) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            logger.info("No copy ledger yet; starting a new one at " + file);
            return;
        }

        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        final Set<LedgerKey> loaded = new LinkedHashSet<>();
        int malformed = 0;
        for (final String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            final Optional<LedgerKey> key = LedgerKey.parse(line);
            if (key.isPresent()) {
                loaded.add(key.get());
            } else {
                malformed++;
            }
        }

        // If the source folder cannot be seen at all, every entry would look stale. Dropping them
        // would make the agent copy everything again once the folder came back, so keep them.
        final List<LedgerKey> kept = new ArrayList<>(loaded.size());
        if (Files.isDirectory(sourceRoot)) {
            for (final LedgerKey key : loaded) {
                if (Files.exists(sourceRoot.resolve(key.relativePath()), LinkOption.NOFOLLOW_LINKS)) {
                    kept.add(key);
                }
            }
        } else {
            kept.addAll(loaded);
        }

        entries.addAll(kept);
        final int dropped = loaded.size() - kept.size();

        if (dropped > 0 || malformed > 0 || kept.size() != lines.size()) {
            rewrite(kept);
        }

        logger.info(
                "Copy ledger loaded: " + kept.size() + " file(s) already copied"
                        + (dropped > 0 ? ", " + dropped + " entr(ies) for files no longer in the source removed" : "")
                        + (malformed > 0 ? ", " + malformed + " unreadable line(s) skipped" : ""));
    }

    private void rewrite(List<LedgerKey> kept) throws IOException {
        final StringBuilder content = new StringBuilder(kept.size() * 48);
        for (final LedgerKey key : kept) {
            content.append(key.toLine()).append(System.lineSeparator());
        }
        final Path temp = file.resolveSibling(FILE_NAME + TEMP_SUFFIX);
        Files.write(
                temp,
                content.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
