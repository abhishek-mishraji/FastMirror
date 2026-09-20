package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The copy engine, and the only class in FastMirror that opens a file inside the source folder
 * (AR-10). Implements SRS 9.5 exactly.
 *
 * <h2>Why this class is written the way it is</h2>
 *
 * <p>Every unusual choice below exists to satisfy the prime directive (PD-1): the producer must
 * never receive an error because FastMirror is reading its files.
 *
 * <ul>
 *   <li><strong>The source is opened read-only through NIO and nothing else</strong> (NI-1, NI-2).
 *       The JDK's Windows NIO implementation opens with {@code FILE_SHARE_READ |
 *       FILE_SHARE_WRITE | FILE_SHARE_DELETE} (C-8). Those three flags are the whole reason the
 *       producer's write and delete keep succeeding while we read, and the reason an open handle
 *       keeps serving data after a delete (C-2).
 *   <li><strong>The transfer is an explicit bounded-buffer loop</strong> (NI-4). {@code transferTo}
 *       and {@code map} are forbidden: on Windows the JDK implements {@code transferTo} by
 *       memory-mapping the source, and a mapped section is a documented hazard to another process's
 *       delete ({@code ERROR_USER_MAPPED_FILE}). It was measured <em>not</em> to block a delete on
 *       the current platform (C-9), but the behaviour is Windows-version dependent and an explicit
 *       loop costs nothing, so the risk is simply not taken. {@code Files.copy} is forbidden for a
 *       related reason (C-10): its share mode is not ours to choose.
 *   <li><strong>The handle is held for as little time as possible and always closed</strong>
 *       (NI-9). Nothing slow happens between open and close — logging is asynchronous precisely so
 *       that it cannot extend this window.
 *   <li><strong>Nothing escapes.</strong> Every failure becomes a {@link CopyOutcome} (FR-29). A
 *       copy job must never kill a worker thread, and must never be retried in a way that puts
 *       more pressure on the producer's files than the configured backoff (NI-11, NI-13).
 * </ul>
 *
 * <p>These are not stylistic preferences. AT-02 and AT-03 scan the compiled bytecode and fail the
 * build if the forbidden APIs appear anywhere, and IT-18/IT-19/IT-20 verify the resulting behaviour
 * against a simulated producer.
 */
public final class AtomicFileCopierImpl implements FileCopier {

    /** NFR-04: copies stream through a bounded buffer and never load a file into memory. */
    private static final int BUFFER_BYTES = 64 * 1024;

    /** An upper bound on the doubling backoff, so a pathological config cannot stall a worker. */
    private static final long MAX_RETRY_DELAY_MS = 1_000L;

    /**
     * Retries for the publish step only.
     *
     * <p>Generous on purpose, and safe to be generous: the target lives in the mirror folder, which
     * belongs to FastMirror, so retrying here puts no pressure whatsoever on the producer. NI-11
     * constrains the source side only.
     *
     * <p>This is not a theoretical hardening. Replacing a target fails with
     * {@code AccessDeniedException} while another process holds that file open, and in production
     * the HOR AWS Sync Agent is doing exactly that - reading files out of the mirror folder in
     * order to upload them. A scanner or indexer can do the same. Giving up after a few
     * milliseconds would turn an ordinary, expected collision into a lost copy.
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

    /** Paths already seen to be replaced, so FR-31 logs once per path rather than per copy. */
    private final Set<Path> reportedReuse = ConcurrentHashMap.newKeySet();

    /** Makes every staging file name unique to one copy attempt. */
    private final AtomicLong stagingSequence = new AtomicLong();

    /**
     * How a source file is opened.
     *
     * <p>Exists so that tests can inject a channel that fails part-way through a read, which is the
     * only practical way to exercise the partial-capture path (UT-17, UT-18) without a real disk
     * fault. Production always uses {@link #READ_ONLY_NIO}, and no other implementation may be
     * wired in {@code Application}.
     */
    @FunctionalInterface
    public interface SourceOpener {
        SeekableByteChannel open(Path source) throws IOException;
    }

    /**
     * The one sanctioned way to open a producer file (NI-1, NI-2).
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
    public CopyResult copy(Path relative, long eventNanos, boolean nameReuseCandidate) {
        Objects.requireNonNull(relative, "relative");

        final Path source;
        final Path target;
        try {
            source = mapper.toSource(relative);
            target = mapper.toTarget(relative);
        } catch (final RuntimeException e) {
            logger.error("Refusing to copy an invalid relative path: " + relative, e);
            return CopyResult.failed(-1L, e);
        }
        final Path staging = stagingFor(target);

        try {
            ensureParentExists(target);
        } catch (final IOException e) {
            logger.error("Could not create the mirror directory for " + relative, e);
            return CopyResult.failed(-1L, e);
        }

        // ------------------------------------------------------------------ the race is here
        final SeekableByteChannel channel;
        final long latencyMs;
        try {
            final OpenAttempt attempt = openWithRetry(source, relative, eventNanos);
            if (attempt.result() != null) {
                return attempt.result();
            }
            channel = attempt.channel();
            latencyMs = attempt.latencyMs();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return CopyResult.of(CopyOutcome.FAILED);
        }
        // ---- past this line the copy survives deletion of the source (C-2), and the producer
        // ---- can still write to and delete that file without error (C-8).

        try (SeekableByteChannel in = channel) {
            return transfer(in, relative, source, target, staging, latencyMs, nameReuseCandidate);
        } catch (final Throwable t) {
            // FR-29: a copy job always ends in exactly one outcome and never kills its worker.
            logger.error("Unexpected failure copying " + relative, t);
            return CopyResult.failed(latencyMs, t);
        } finally {
            deleteStaging(staging);
        }
    }

    @Override
    public boolean contentsDiffer(Path relative) {
        final Path source = mapper.toSource(relative);
        final Path target = mapper.toTarget(relative);
        final String sourceHash = hashSource(source);
        final String targetHash = hashTarget(target);
        if (sourceHash == null || targetHash == null) {
            // Unreadable on either side: assume different, so the copy is attempted rather than
            // silently skipped. A failed copy is visible; a skipped one is not.
            return true;
        }
        return !sourceHash.equals(targetHash);
    }

    // ------------------------------------------------------------------ open

    /** The outcome of the open phase: either a channel, or a terminal result. */
    private record OpenAttempt(SeekableByteChannel channel, long latencyMs, CopyResult result) {}

    private OpenAttempt openWithRetry(Path source, Path relative, long eventNanos)
            throws InterruptedException {
        long delayMs = config.openRetryInitialMs();
        IOException lastFailure = null;

        for (int attempt = 0; attempt <= config.openRetryAttempts(); attempt++) {
            try {
                final SeekableByteChannel channel = opener.open(source);
                return new OpenAttempt(channel, elapsedMs(eventNanos), null);
            } catch (final NoSuchFileException e) {
                // C-4: the name is gone. Nothing was captured and nothing can be. No retry, ever
                // (NI-11) - retrying a name that does not exist is pure I/O pressure on a folder
                // the producer is actively using.
                final long latencyMs = elapsedMs(eventNanos);
                logger.warn("Source vanished before capture (" + latencyMs + "ms): " + relative);
                return new OpenAttempt(null, latencyMs, CopyResult.of(CopyOutcome.VANISHED, latencyMs));
            } catch (final IOException e) {
                // C-3: typically a sharing violation because the producer holds the file without
                // FILE_SHARE_READ, or ERROR_DELETE_PENDING for a file being deleted behind another
                // handle. Both are transient and both fail towards us, never towards the producer.
                lastFailure = e;
                if (attempt == config.openRetryAttempts()) {
                    break;
                }
                sleeper.sleep(delayMs);
                delayMs = Math.min(delayMs * 2L, MAX_RETRY_DELAY_MS);
            }
        }

        final long latencyMs = elapsedMs(eventNanos);
        logger.warn(
                "Source still locked after " + config.openRetryAttempts()
                        + " retries (" + latencyMs + "ms): " + relative
                        + (lastFailure == null ? "" : " - " + lastFailure));
        return new OpenAttempt(
                null,
                latencyMs,
                new CopyResult(CopyOutcome.LOCKED, 0L, -1L, latencyMs, lastFailure));
    }

    // ------------------------------------------------------------------ transfer and publish

    private CopyResult transfer(
            SeekableByteChannel in,
            Path relative,
            Path source,
            Path target,
            Path staging,
            long latencyMs,
            boolean nameReuseCandidate)
            throws IOException, InterruptedException {

        final FileTime sourceMtime = readMtime(source);

        final long declaredSize = in.size();
        if (!config.withinSizeLimit(declaredSize)) {
            logger.warn(
                    "Skipped (" + declaredSize + " bytes exceeds MAX_COPY_BYTES="
                            + config.maxCopyBytes() + "): " + relative);
            return CopyResult.of(CopyOutcome.SKIPPED_TOO_LARGE, latencyMs);
        }

        final boolean targetExisted = Files.exists(target, LinkOption.NOFOLLOW_LINKS);

        long bytesCopied = 0L;
        IOException transferFailure = null;

        try (SeekableByteChannel out =
                Files.newByteChannel(
                        staging,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {

            // NI-4: an explicit bounded loop. Never transferTo, never map - either would pin the
            // source with a mapped section and make the producer's delete fail.
            final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
            while (true) {
                final int read;
                try {
                    read = in.read(buffer);
                } catch (final IOException e) {
                    // The read failed part-way. Whatever we already have is still worth keeping
                    // (PD-2) - it is the only copy of those bytes that will ever exist if the
                    // source is on its way out.
                    transferFailure = e;
                    break;
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

        if (transferFailure != null && bytesCopied == 0L) {
            // FR-28: nothing was captured, so there is nothing to publish. Publishing an empty
            // file here would replace a good target with nothing.
            logger.error("Copy failed with nothing captured: " + relative, transferFailure);
            return CopyResult.failed(latencyMs, transferFailure);
        }

        if (transferFailure != null && !config.publishPartial()) {
            logger.error(
                    "Copy failed after " + bytesCopied + " byte(s); PUBLISH_PARTIAL is false so the"
                            + " captured prefix was discarded: " + relative,
                    transferFailure);
            return CopyResult.failed(latencyMs, transferFailure);
        }

        try {
            if (sourceMtime != null) {
                Files.setLastModifiedTime(staging, sourceMtime);
            }
            publish(staging, target);
        } catch (final NoSuchFileException e) {
            // Our own staging file went missing. With per-attempt names this can only be an
            // outside actor, such as an antivirus scanner. Nothing was published, the target still
            // holds its previous content, and the sweep will try again - so this is a WARN about
            // one lost copy, not an ERROR about a broken agent.
            logger.warn(
                    "Staging file disappeared before it could be published, copy abandoned: "
                            + relative + " (" + e.getMessage() + ")");
            return CopyResult.failed(latencyMs, e);
        }

        if (transferFailure != null) {
            // FR-27 / PD-2.
            logger.warn(
                    "Partial capture published: " + relative + " (" + bytesCopied + " of "
                            + declaredSize + " bytes, captured in " + latencyMs + "ms) - "
                            + transferFailure,
                    transferFailure);
            return CopyResult.partial(bytesCopied, declaredSize, latencyMs, transferFailure);
        }

        if (targetExisted && nameReuseCandidate) {
            reportPossibleNameReuse(relative);
        }

        logger.info(
                "Copied: " + relative + " (" + bytesCopied + " bytes, captured in "
                        + latencyMs + "ms)");
        return CopyResult.completed(
                targetExisted ? CopyOutcome.REPLACED : CopyOutcome.COPIED, bytesCopied, latencyMs);
    }

    /**
     * Makes the copy visible in one indivisible step (FR-08, C-7).
     *
     * <p>The staging file sits in the target's own directory, so this is a same-volume
     * directory-entry swap. A consumer — the HOR AWS Sync Agent, in production — therefore never
     * observes a target that FastMirror is part-way through writing.
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
                // - the sync agent uploading it, or a scanner. Transient by nature: back off and
                // try again rather than lose a copy over a collision we expect to happen.
                lastFailure = e;
                if (attempt < PUBLISH_RETRY_ATTEMPTS) {
                    sleeper.sleep(delayMs);
                    delayMs = Math.min(delayMs * 2L, PUBLISH_RETRY_MAX_MS);
                }
            }
        }
        throw lastFailure;
    }

    /**
     * FR-31: a path that was deleted and then created again, while the mirror still holds the
     * earlier version, means the producer reused a file name for new content - which the current
     * design does not preserve (R-10, U-1).
     *
     * <p>Logged once per path so a busy folder does not drown the log. This is a measurement, not
     * an error: it exists so the question "does Gemcom reuse filenames?" is answered from a log
     * rather than from missing data in S3.
     */
    private void reportPossibleNameReuse(Path relative) {
        if (reportedReuse.add(relative)) {
            logger.warn(
                    "Filename reused: a newly created source file replaced an already mirrored"
                            + " file at the same path: " + relative
                            + ". The previous mirrored content is gone. See SRS R-10.");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A staging path for one copy attempt.
     *
     * <p>Always in the target's own directory, so the publish is a same-volume directory-entry swap
     * and therefore atomic (C-7).
     *
     * <p>The name carries a per-attempt counter. A shared {@code <target>.fmtmp} name would be
     * correct only as long as no two attempts for the same path ever overlap, and a running agent
     * showed that assumption failing: a staging file disappeared between being written and being
     * timestamped, because another attempt's cleanup removed it. Rather than rely on the queue's
     * mutual exclusion holding under every interleaving, each attempt now owns a file no other
     * attempt can name. The {@code .fmtmp} suffix is preserved, so the exclusion patterns (FR-18)
     * and the startup cleanup (FR-22) still match.
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
            // Harmless: the startup sweep removes stale staging files (FR-22), and the name is
            // excluded from copying (FR-18).
            logger.warn("Could not remove the staging file " + staging + ": " + e);
        }
    }

    /**
     * Best-effort read of the source timestamp.
     *
     * <p>Attribute reads are permitted (NI-8 forbids attribute <em>writes</em>). A delete-pending
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

    private String hashSource(Path source) {
        try (SeekableByteChannel in = opener.open(source)) {
            return hash(in);
        } catch (final IOException e) {
            return null;
        }
    }

    private String hashTarget(Path target) {
        try (SeekableByteChannel in = Files.newByteChannel(target, StandardOpenOption.READ)) {
            return hash(in);
        } catch (final IOException e) {
            return null;
        }
    }

    private static String hash(SeekableByteChannel in) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
        final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
        while (in.read(buffer) >= 0) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long elapsedMs(long eventNanos) {
        // AR-9: nanoTime is measurement only and never drives control flow.
        final long elapsed = System.nanoTime() - eventNanos;
        return elapsed < 0L ? 0L : elapsed / 1_000_000L;
    }
}
