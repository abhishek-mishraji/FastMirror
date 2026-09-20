package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.FileEvent;
import com.handsoffretail.fastmirror.entity.FileEventKind;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.MirrorService;
import com.handsoffretail.fastmirror.service.PathFilter;
import com.handsoffretail.fastmirror.service.PathMapper;
import com.handsoffretail.fastmirror.service.SweepService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The event-handling policy of SRS 9.3, running on the single {@code fastmirror-events} thread.
 *
 * <p>Two things this class deliberately does <em>not</em> do, both for latency reasons:
 *
 * <ul>
 *   <li>It never calls {@code DifferenceDetector}. That check reads attributes on both the source
 *       and the target, and doing it here would delay the path from "a file appeared" to "we have a
 *       handle on it" — the only path where milliseconds cost files. The copy worker does it
 *       instead.
 *   <li>It never blocks on a copy. Work is handed to the queue and the thread goes straight back to
 *       draining events.
 * </ul>
 *
 * <p>The one piece of I/O it does perform is an attribute read to tell a file from a directory,
 * which is cheap and unavoidable — and is a read, never a write (NI-8).
 */
public final class MirrorServiceImpl implements MirrorService {

    /** Ends the event thread. Reference identity is the signal, so no real event can impersonate it. */
    private static final FileEvent STOP = FileEvent.overflow(0L);

    private final MirrorConfig config;
    private final PathMapper mapper;
    private final PathFilter filter;
    private final CopyQueue queue;
    private final SweepService sweepService;
    private final AppLogger logger;

    private final BlockingQueue<FileEvent> events = new LinkedBlockingQueue<>();

    /**
     * Paths seen deleted, so that a later CREATE for the same path can be recognised as the
     * producer reusing a file name rather than as an ordinary new file (FR-31, U-1).
     *
     * <p>A plain CREATE is not a usable signal on its own. Windows reports an ordinary new file as
     * CREATE followed by MODIFY, and a live agent duly warned about "reuse" for files that had only
     * ever been written once. Since the README asks the operator to report these lines, a false
     * alarm is worse than no detection at all.
     *
     * <p>Entries are removed as soon as they are consumed, so the set tracks only names that are
     * currently gone from the source - bounded by how many files the producer has deleted and not
     * yet recreated.
     */
    private final java.util.Set<Path> deletedPaths = ConcurrentHashMap.newKeySet();

    private volatile boolean accepting = true;
    private Thread eventThread;

    public MirrorServiceImpl(
            MirrorConfig config,
            PathMapper mapper,
            PathFilter filter,
            CopyQueue queue,
            SweepService sweepService,
            AppLogger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.sweepService = Objects.requireNonNull(sweepService, "sweepService");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void accept(FileEvent event) {
        Objects.requireNonNull(event, "event");
        if (!accepting) {
            return;
        }
        // Unbounded and non-blocking: the caller is the thread keeping the Windows notification
        // buffer drained, and it must never wait for us (C-6).
        events.add(event);
    }

    @Override
    public void start() {
        if (eventThread != null) {
            return;
        }
        eventThread = new Thread(this::drain, "fastmirror-events");
        eventThread.setDaemon(false);
        eventThread.start();
    }

    @Override
    public void stop() {
        accepting = false;
        events.add(STOP);
        final Thread thread = eventThread;
        if (thread != null) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Events pending on the internal queue, for tests and diagnostics. */
    public int queued() {
        return events.size();
    }

    private void drain() {
        try {
            while (true) {
                final FileEvent event = events.take();
                if (event == STOP) {
                    break;
                }
                handleQuietly(event);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleQuietly(FileEvent event) {
        try {
            handle(event);
        } catch (final Throwable t) {
            // NFR-07: one bad event must never take the event thread down, which would leave the
            // agent running but blind.
            logger.error("Failed to handle event " + event.kind() + " " + event.path(), t);
        }
    }

    private void handle(FileEvent event) {
        if (event.kind() == FileEventKind.OVERFLOW) {
            // C-6: Windows dropped notifications, so we no longer know what changed. The only
            // correct response is to re-derive the truth from the tree (FR-15).
            logger.warn("Watch buffer overflowed; requesting a full sweep");
            sweepService.requestSweep();
            return;
        }

        if (event.kind() == FileEventKind.DELETED) {
            // FR-04: no copying action, ever. The mirror keeps what it has; that is the entire
            // point of the agent. The name is remembered only so that FR-31 can tell a recreated
            // file apart from a new one. No I/O, so the event thread stays fast.
            rememberDeleted(event.path());
            return;
        }

        final Path path = event.path();
        if (filter.isExcluded(path)) {
            // FR-18: an excluded file is never opened at all.
            return;
        }

        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (final IOException e) {
            // Already gone, or not readable. The copy worker would find the same thing; dropping
            // the event here saves it a wasted open against the producer's folder.
            return;
        }

        if (attributes.isDirectory()) {
            if (config.mirrorEmptyDirectories()) {
                mirrorDirectory(path);
            }
            // Files inside arrive as their own events, so there is nothing else to do.
            return;
        }

        if (!attributes.isRegularFile()) {
            return;
        }

        final Path relative;
        try {
            relative = mapper.relativize(path);
        } catch (final RuntimeException e) {
            logger.warn("Ignoring an event for a path outside the source root: " + path);
            return;
        }

        final boolean nameReuseCandidate =
                event.kind() == FileEventKind.CREATED && deletedPaths.remove(relative);

        queue.submit(relative, event.eventNanos(), nameReuseCandidate);
    }

    private void rememberDeleted(Path path) {
        try {
            deletedPaths.add(mapper.relativize(path));
        } catch (final RuntimeException e) {
            // Outside the source root, or the root itself. Nothing to remember.
        }
    }

    private void mirrorDirectory(Path directory) {
        try {
            Files.createDirectories(mapper.toTarget(mapper.relativize(directory)));
        } catch (final IOException | RuntimeException e) {
            logger.warn("Could not mirror the directory " + directory + ": " + e);
        }
    }
}
