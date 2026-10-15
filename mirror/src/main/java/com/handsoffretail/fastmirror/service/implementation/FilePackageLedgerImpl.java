package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.entity.PackageRecord;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.PackageLedger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The package ledger as a text file, {@code package.ledger}, one line per zip (see
 * {@link PackageRecord}). Lines are appended with {@code SYNC}. A torn last line from a power cut is
 * skipped on load; the zip it described is still in the outbox and is found and recorded again.
 */
public final class FilePackageLedgerImpl implements PackageLedger {

    public static final String FILE_NAME = "package.ledger";

    private final Path file;
    private final AppLogger logger;
    private final List<PackageRecord> records = new ArrayList<>();
    private final Map<TypeFolder, PackageRecord> latest = new HashMap<>();

    private FilePackageLedgerImpl(Path file, AppLogger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Loads the ledger in {@code stateFolder}, creating it on first use. */
    public static FilePackageLedgerImpl open(Path stateFolder, AppLogger logger) throws IOException {
        Objects.requireNonNull(stateFolder, "stateFolder");
        Objects.requireNonNull(logger, "logger");
        final FilePackageLedgerImpl ledger =
                new FilePackageLedgerImpl(stateFolder.resolve(FILE_NAME), logger);
        ledger.load();
        return ledger;
    }

    @Override
    public synchronized Optional<PackageRecord> latest(TypeFolder folder) {
        return Optional.ofNullable(latest.get(Objects.requireNonNull(folder, "folder")));
    }

    @Override
    public synchronized boolean record(PackageRecord record) {
        Objects.requireNonNull(record, "record");
        try {
            Files.write(
                    file,
                    (record.toLine() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.SYNC);
        } catch (final IOException e) {
            logger.error(
                    "Could not record a finished zip in " + file + ". The zip is in the outbox and"
                            + " is recorded at the next start: " + record.folder() + " v" + record.version(),
                    e);
            return false;
        }
        remember(record);
        return true;
    }

    @Override
    public synchronized List<PackageRecord> all() {
        return List.copyOf(records);
    }

    /** The ledger file, for the startup log line. */
    public Path file() {
        return file;
    }

    private void load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            logger.info("No package ledger yet; starting a new one at " + file);
            return;
        }
        int malformed = 0;
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            final Optional<PackageRecord> record = PackageRecord.parse(line);
            if (record.isPresent()) {
                remember(record.get());
            } else {
                malformed++;
            }
        }
        logger.info(
                "Package ledger loaded: " + records.size() + " zip(s) recorded"
                        + (malformed > 0 ? ", " + malformed + " unreadable line(s) skipped" : ""));
    }

    private void remember(PackageRecord record) {
        records.add(record);
        latest.merge(
                record.folder(),
                record,
                (earlier, later) -> later.version() >= earlier.version() ? later : earlier);
    }
}
