package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.PathMapper;
import com.handsoffretail.fastmirror.service.Sleeper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The copy engine, and the only class in FastMirror that opens a file inside the source folder.
 *
 * <h2>Why this class is written the way it is</h2>
 *
 * <p>Every unusual choice below exists for one rule: POS Link must never receive an error because
 * FastMirror is reading its files.
 *
 * <ul>
 *   <li><strong>The source is opened read-only through NIO and nothing else.</strong> The JDK's
 *       Windows NIO implementation opens with {@code FILE_SHARE_READ | FILE_SHARE_WRITE |
 *       FILE_SHARE_DELETE}. Those three flags are the whole reason the producer's write, rename and
 *       delete keep succeeding while we read.
 *   <li><strong>The transfer is an explicit bounded-buffer loop.</strong> {@code transferTo} and
 *       {@code map} are forbidden: on Windows the JDK implements {@code transferTo} by
 *       memory-mapping the source, and a mapped section is a documented hazard to another process's
 *       delete. {@code Files.copy} is forbidden for a related reason: its share mode is not ours to
 *       choose.
 *   <li><strong>The handle is held for as little time as possible and always closed.</strong>
 *   <li><strong>Nothing escapes.</strong> Every failure becomes a {@link CopyOutcome}. A copy job
 *       must never kill a worker thread.
 * </ul>
 *
 * <p>A read that fails part-way publishes nothing. The file is not recorded in the ledger, so the
 * next scan tries again; a truncated file uploaded to S3 and then marked done would be worse.
 *
 * <p>These are not stylistic preferences. The architecture tests scan the compiled bytecode and fail
 * the build if the forbidden APIs appear anywhere, and ProducerNonInterferenceTest verifies the
 * resulting behaviour against a simulated producer.
 */
public final class AtomicFileCopierImpl implements FileCopier {

    /** Copies stream through a bounded buffer and never load a file into memory. */
    private static final int BUFFER_BYTES = 64 * 1024;

    /** An upper bound on the doubling backoff, so a pathological config cannot stall a worker. */
    private static final long MAX_RETRY_DELAY_MS = 1_000L;

    /**
     * Retries for the publish step only.
     *
     * <p>Generous on purpose, and safe to be generous: the target lives in the mirror folder, which
     * belongs to FastMirror, so retrying here puts no pressure whatsoever on the producer.
     *
     * <p>This is not a theoretical hardening. Replacing a target fails with
     * {@code AccessDeniedException} while another process holds that file open, and in production
     * the S3 upload agent is doing exactly that - reading files out of the mirror folder in order to
     * upload them. A scanner or indexer can do the same.
     */
    private static final int PUBLISH_RETRY_ATTEMPTS = 10;

    private static final long PUBLISH_RETRY_INITIAL_MS = 10L;

    private static final long PUBLISH_RETRY_MAX_MS = 250L;

    private final MirrorConfig config;
    private final PathMapper mapper;
    private final Sleeper sleeper;
    private final AppLogger logger;
    private final Clock clock;
    private final SourceOpener opener;

    /** Mirror directories already created, so the syscall is not repeated for every file. */
    private final Set<Path> createdDirectories = ConcurrentHashMap.newKeySet();

    /** Makes every staging file name unique to one copy attempt. */
    private final AtomicLong stagingSequence = new AtomicLong();

    /**
     * How a source file is opened.
     *
     * <p>Exists so that tests can inject a channel that fails part-way through a read without a real
     * disk fault. Production always uses {@link #READ_ONLY_NIO}, and no other implementation may be
     * wired in {@code Application}.
     */
    @FunctionalInterface
    public interface SourceOpener {
        SeekableByteChannel open(Path source) throws IOException;
    }

    /**
     * The one sanctioned way to open a producer file.
     *
     * <p>{@code READ} is the only option. Adding any write intent here would contend with the
     * producer for its own file.
     */
    public static final SourceOpener READ_ONLY_NIO =
            source -> FileChannel.open(source, StandardOpenOption.READ);

    public AtomicFileCopierImpl(
            MirrorConfig config,
            PathMapper mapper,
            Sleeper sleeper,
            AppLogger logger,
            Clock clock) {
        this(config, mapper, sleeper, logger, clock, READ_ONLY_NIO);
    }

    public AtomicFileCopierImpl(
            MirrorConfig config,
            PathMapper mapper,
            Sleeper sleeper,
            AppLogger logger,
            Clock clock,
            SourceOpener opener) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public CopyResult copy(CopyJob job) {
        Objects.requireNonNull(job, "job");
        final Path relative = job.relativeSource();

        final Path source;
        final Path target;
        try {
            source = mapper.toSource(relative);
            target = mapper.toTarget(job.relativeTarget());
        } catch (final RuntimeException e) {
            logger.error("Refusing to copy an invalid path: " + relative + " -> " + job.relativeTarget(), e);
            return CopyResult.failed(e);
        }
        final Path staging = stagingFor(target);

        try {
            ensureParentExists(target);
        } catch (final IOException e) {
            logger.error("Could not create the mirror directory for " + job.relativeTarget(), e);
            return CopyResult.failed(e);
        }

        final SeekableByteChannel channel;
        try {
            final OpenAttempt attempt = openWithRetry(source, relative);
            if (attempt.result() != null) {
                return attempt.result();
            }
            channel = attempt.channel();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return CopyResult.of(CopyOutcome.FAILED);
        }

        try (SeekableByteChannel in = channel) {
            return transfer(in, job, source, target, staging);
        } catch (final Throwable t) {
            // A copy job always ends in exactly one outcome and never kills its worker.
            logger.error("Unexpected failure copying " + relative, t);
            return CopyResult.failed(t);
        } finally {
            deleteStaging(staging);
        }
    }

    @Override
    public Optional<byte[]> readHead(Path relative, int maxBytes) {
        Objects.requireNonNull(relative, "relative");
        final Path source;
        try {
            source = mapper.toSource(relative);
        } catch (final RuntimeException e) {
            return Optional.empty();
        }
        // One attempt, no retry: a locked or vanished file is simply read on a later scan, so there
        // is no reason to press on a file the producer is busy with.
        try (SeekableByteChannel in = opener.open(source)) {
            final ByteBuffer buffer = ByteBuffer.allocate(Math.max(0, maxBytes));
            while (buffer.hasRemaining() && in.read(buffer) >= 0) {
                // A bounded read of the first maxBytes and nothing more.
            }
            return Optional.of(Arrays.copyOf(buffer.array(), buffer.position()));
        } catch (final IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ open

    /** The outcome of the open phase: either a channel, or a terminal result. */
    private record OpenAttempt(SeekableByteChannel channel, CopyResult result) {}

    private OpenAttempt openWithRetry(Path source, Path relative) throws InterruptedException {
        long delayMs = config.openRetryInitialMs();
        IOException lastFailure = null;

        for (int attempt = 0; attempt <= config.openRetryAttempts(); attempt++) {
            try {
                return new OpenAttempt(opener.open(source), null);
            } catch (final NoSuchFileException e) {
                // The name is gone. No retry, ever - retrying a name that does not exist is pure I/O
                // pressure on a folder the producer is actively using.
                logger.warn("Source vanished before it could be copied: " + relative);
                return new OpenAttempt(null, CopyResult.of(CopyOutcome.VANISHED));
            } catch (final IOException e) {
                // Typically a sharing violation because the producer holds the file without
                // FILE_SHARE_READ. Transient, and it fails towards us, never towards the producer.
                lastFailure = e;
                if (attempt == config.openRetryAttempts()) {
                    break;
                }
                sleeper.sleep(delayMs);
                delayMs = Math.min(delayMs * 2L, MAX_RETRY_DELAY_MS);
            }
        }

        logger.warn(
                "Source still locked after " + config.openRetryAttempts()
                        + " retries; the next scan will try again: " + relative
                        + (lastFailure == null ? "" : " - " + lastFailure));
        return new OpenAttempt(null, new CopyResult(CopyOutcome.LOCKED, 0L, lastFailure));
    }

    // ------------------------------------------------------------------ transfer and publish

    private CopyResult transfer(
            SeekableByteChannel in, CopyJob job, Path source, Path target, Path staging)
            throws IOException, InterruptedException {

        final Path relative = job.relativeSource();
        final FileTime sourceMtime = readMtime(source);

        final long declaredSize = in.size();
        if (!config.withinSizeLimit(declaredSize)) {
            logger.warn(
                    "Skipped (" + declaredSize + " bytes exceeds MAX_COPY_BYTES="
                            + config.maxCopyBytes() + "): " + relative);
            return CopyResult.of(CopyOutcome.SKIPPED_TOO_LARGE);
        }

        final boolean targetExisted = Files.exists(target, LinkOption.NOFOLLOW_LINKS);

        long bytesCopied = 0L;

        try (SeekableByteChannel out =
                Files.newByteChannel(
                        staging,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {

            // An explicit bounded loop. Never transferTo, never map - either would pin the source
            // with a mapped section.
            final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
            while (true) {
                final int read;
                try {
                    read = in.read(buffer);
                } catch (final IOException e) {
                    // Publishing the captured prefix would put a truncated file in S3 and mark it
                    // done for good. Nothing is published; the next scan tries again.
                    logger.error(
                            "Copy failed after " + bytesCopied + " of " + declaredSize
                                    + " byte(s); nothing published, the next scan will retry: "
                                    + relative,
                            e);
                    return CopyResult.failed(e);
                }
                if (read < 0) {
                    break;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                buffer.clear();
                bytesCopied += read;
            }
        }

        try {
            if (sourceMtime != null) {
                Files.setLastModifiedTime(staging, sourceMtime);
            }
            publish(staging, target);
        } catch (final NoSuchFileException e) {
            // Our own staging file went missing. With per-attempt names this can only be an outside
            // actor, such as an antivirus scanner. Nothing was published and the next scan will
            // try again.
            logger.warn(
                    "Staging file disappeared before it could be published, copy abandoned: "
                            + relative + " (" + e.getMessage() + ")");
            return CopyResult.failed(e);
        }

        logger.info(
                "Copied: " + relative + " -> " + job.relativeTarget() + " (" + bytesCopied + " bytes"
                        + (targetExisted ? ", replaced an existing file" : "") + ")");
        return CopyResult.completed(
                targetExisted ? CopyOutcome.REPLACED : CopyOutcome.COPIED, bytesCopied);
    }

    /**
     * Makes the copy visible in one indivisible step.
     *
     * <p>The staging file sits in the target's own directory, so this is a same-volume
     * directory-entry swap. The S3 upload agent therefore never observes a target that FastMirror
     * is part-way through writing. A file of the same name already there is replaced.
     */
    private void publish(Path staging, Path target) throws IOException, InterruptedException {
        IOException lastFailure = null;
        long delayMs = PUBLISH_RETRY_INITIAL_MS;
        for (int attempt = 0; attempt <= PUBLISH_RETRY_ATTEMPTS; attempt++) {
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (final NoSuchFileException e) {
                // Our own staging file is gone; retrying cannot help.
                throw e;
            } catch (final IOException e) {
                // Almost always AccessDeniedException because another process has the target open
                // - the upload agent, or a scanner. Transient by nature: back off and try again.
                lastFailure = e;
                if (attempt < PUBLISH_RETRY_ATTEMPTS) {
                    sleeper.sleep(delayMs);
                    delayMs = Math.min(delayMs * 2L, PUBLISH_RETRY_MAX_MS);
                }
            }
        }
        throw lastFailure;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A staging path for one copy attempt.
     *
     * <p>Always in the target's own directory, so the publish is a same-volume directory-entry swap
     * and therefore atomic. The name carries a per-attempt counter, so no two attempts can ever
     * share or delete each other's staging file. The {@code .fmtmp} suffix is preserved, so the
     * startup cleanup still matches.
     */
    private Path stagingFor(Path target) {
        return target.resolveSibling(
                target.getFileName()
                        + "."
                        + Long.toUnsignedString(stagingSequence.incrementAndGet(), 36)
                        + MirrorConfig.STAGING_SUFFIX);
    }

    private void ensureParentExists(Path target) throws IOException {
        final Path parent = target.getParent();
        if (parent == null || createdDirectories.contains(parent)) {
            return;
        }
        Files.createDirectories(parent);
        createdDirectories.add(parent);
    }

    private void deleteStaging(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (final IOException e) {
            // Harmless: the startup cleanup removes stale staging files.
            logger.warn("Could not remove the staging file " + staging + ": " + e);
        }
    }

    /**
     * Best-effort read of the source timestamp.
     *
     * <p>Attribute reads are permitted; attribute writes on the source are not. A delete-pending
     * source can make this throw, which is not a reason to abandon a copy that is already
     * succeeding, so the current time is used instead.
     */
    private FileTime readMtime(Path source) {
        try {
            final BasicFileAttributes attributes =
                    Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.lastModifiedTime();
        } catch (final IOException e) {
            return FileTime.from(clock.instant());
        }
    }
}
