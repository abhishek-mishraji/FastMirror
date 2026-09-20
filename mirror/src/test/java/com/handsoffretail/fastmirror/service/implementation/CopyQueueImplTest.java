package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.CopyStats;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.DifferenceDetector;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.StatsReporter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyQueueImplTest {

    @TempDir Path logFolder;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private AppLogger logger;
    private GateCopier copier;
    private RecordingStats stats;

    /** Counts calls per path, and can hold one path open until the test releases it. */
    private static final class GateCopier implements FileCopier {
        private final ConcurrentHashMap<Path, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile Path gatedPath;

        void gate(Path path) {
            this.gatedPath = path;
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the gated copy should have started");
        }

        void release() {
            release.countDown();
        }

        int callsFor(Path path) {
            final AtomicInteger count = calls.get(path);
            return count == null ? 0 : count.get();
        }

        @Override
        public CopyResult copy(Path relative, long eventNanos, boolean nameReuseCandidate) {
            calls.computeIfAbsent(relative, key -> new AtomicInteger()).incrementAndGet();
            if (relative.equals(gatedPath)) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return CopyResult.completed(CopyOutcome.COPIED, 10L, 1L);
        }

        @Override
        public boolean contentsDiffer(Path relative) {
            return true;
        }
    }

    private static final class AlwaysStale implements DifferenceDetector {
        @Override
        public boolean needsCopy(Path relative) {
            return true;
        }
    }

    private static final class RecordingStats implements StatsReporter {
        private final List<CopyResult> results = new CopyOnWriteArrayList<>();

        @Override
        public void record(CopyResult result) {
            results.add(result);
        }

        @Override
        public void report() {
            // not exercised here
        }

        @Override
        public CopyStats snapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CopyStats lifetime() {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        logger = new AppLogger(logFolder, CLOCK);
        copier = new GateCopier();
        stats = new RecordingStats();
    }

    @AfterEach
    void tearDown() {
        logger.close();
    }

    private CopyQueueImpl queue(int workers) {
        return new CopyQueueImpl(new AlwaysStale(), copier, stats, logger, workers);
    }

    @Test
    @DisplayName("UT-12: two submits while the path is still queued produce exactly one copy")
    void ut12_duplicateSubmitsCollapse() throws Exception {
        final Path blocker = Path.of("blocker.xml");
        final Path target = Path.of("target.xml");
        copier.gate(blocker);

        final CopyQueueImpl queue = queue(1);
        try {
            // Occupy the single worker so the next path cannot start.
            queue.submit(blocker, System.nanoTime(), true);
            copier.awaitEntered();

            queue.submit(target, System.nanoTime(), true);
            queue.submit(target, System.nanoTime(), false);
            assertEquals(2, queue.pending(), "both paths should be tracked while one is blocked");

            copier.release();
            assertTrue(queue.awaitIdle(IDLE_TIMEOUT), "the queue should drain");

            assertEquals(1, copier.callsFor(target), "the second submit must fold into the first");
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("UT-13: a submit during a running copy produces exactly one follow-up copy")
    void ut13_submitWhileRunningRunsOnceMore() throws Exception {
        final Path target = Path.of("busy.xml");
        copier.gate(target);

        final CopyQueueImpl queue = queue(2);
        try {
            queue.submit(target, System.nanoTime(), true);
            copier.awaitEntered();

            // Three more changes arrive while the first copy is in flight.
            queue.submit(target, System.nanoTime(), false);
            queue.submit(target, System.nanoTime(), false);
            queue.submit(target, System.nanoTime(), false);

            copier.release();
            assertTrue(queue.awaitIdle(IDLE_TIMEOUT), "the queue should drain");

            assertEquals(
                    2,
                    copier.callsFor(target),
                    "one running copy plus exactly one follow-up, however many events arrived");
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("UT-14: 100 concurrent submits for one path lose no update and leave the map empty")
    void ut14_concurrentSubmitsAreSafe() throws Exception {
        final Path target = Path.of("hot.xml");
        final CopyQueueImpl queue = queue(4);
        final int submitters = 100;
        final CountDownLatch startLine = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(submitters);

        try {
            for (int i = 0; i < submitters; i++) {
                final Thread thread =
                        new Thread(
                                () -> {
                                    try {
                                        startLine.await(10, TimeUnit.SECONDS);
                                        queue.submit(target, System.nanoTime(), false);
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        finished.countDown();
                                    }
                                });
                thread.setDaemon(true);
                thread.start();
            }

            startLine.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS), "all submitters should return");
            assertTrue(queue.awaitIdle(IDLE_TIMEOUT), "the queue should reach quiescence");

            assertEquals(0, queue.pending(), "the state map must be empty at quiescence");
            assertTrue(copier.callsFor(target) >= 1, "at least one copy must have run");
            assertTrue(
                    copier.callsFor(target) <= submitters,
                    "deduplication should collapse the burst, not amplify it");
            assertEquals(
                    copier.callsFor(target),
                    stats.results.size(),
                    "every job that ran must be counted exactly once");
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("an unchanged file is counted but never copied")
    void unchangedFileIsNotCopied() throws Exception {
        final CopyQueueImpl queue =
                new CopyQueueImpl(relative -> false, copier, stats, logger, 2);
        try {
            queue.submit(Path.of("same.xml"), System.nanoTime(), false);
            assertTrue(queue.awaitIdle(IDLE_TIMEOUT));

            assertEquals(0, copier.callsFor(Path.of("same.xml")));
            assertEquals(1, stats.results.size());
            assertEquals(CopyOutcome.UNCHANGED, stats.results.get(0).outcome());
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("FR-29: a copier that throws does not kill the worker or the queue")
    void throwingCopierIsAbsorbed() throws Exception {
        final FileCopier exploding =
                new FileCopier() {
                    @Override
                    public CopyResult copy(Path relative, long eventNanos, boolean create) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public boolean contentsDiffer(Path relative) {
                        return true;
                    }
                };

        final CopyQueueImpl queue =
                new CopyQueueImpl(new AlwaysStale(), exploding, stats, logger, 2);
        try {
            queue.submit(Path.of("bad.xml"), System.nanoTime(), false);
            assertTrue(queue.awaitIdle(IDLE_TIMEOUT));

            assertEquals(1, stats.results.size());
            assertEquals(CopyOutcome.FAILED, stats.results.get(0).outcome());

            // The queue must still work afterwards.
            queue.submit(Path.of("next.xml"), System.nanoTime(), false);
            assertTrue(queue.awaitIdle(IDLE_TIMEOUT));
            assertEquals(2, stats.results.size());
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("NI-10: two jobs for the same path never run at the same time")
    void neverRunsTwoJobsForOnePathConcurrently() throws Exception {
        // The invariant the whole copier design leans on. If it ever broke, two attempts would be
        // writing one target at once, and a concurrency bug would show up as a corrupted mirror
        // rather than as a failed test.
        final java.util.concurrent.atomic.AtomicInteger concurrent = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxConcurrent = new java.util.concurrent.atomic.AtomicInteger();

        final FileCopier watching =
                new FileCopier() {
                    @Override
                    public CopyResult copy(Path relative, long eventNanos, boolean create) {
                        final int now = concurrent.incrementAndGet();
                        maxConcurrent.accumulateAndGet(now, Math::max);
                        try {
                            Thread.sleep(2L);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            concurrent.decrementAndGet();
                        }
                        return CopyResult.completed(CopyOutcome.COPIED, 1L, 0L);
                    }

                    @Override
                    public boolean contentsDiffer(Path relative) {
                        return true;
                    }
                };

        final Path hot = Path.of("one-path.xml");
        final CopyQueueImpl queue = new CopyQueueImpl(new AlwaysStale(), watching, stats, logger, 8);
        try {
            for (int i = 0; i < 500; i++) {
                queue.submit(hot, System.nanoTime(), false);
            }
            assertTrue(queue.awaitIdle(Duration.ofSeconds(30)));

            assertEquals(
                    1,
                    maxConcurrent.get(),
                    "at most one job per path may be in flight at any instant");
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("submissions after shutdown are ignored rather than throwing")
    void submitAfterShutdownIsIgnored() throws Exception {
        final CopyQueueImpl queue = queue(1);
        queue.shutdown(Duration.ofSeconds(5));

        queue.submit(Path.of("late.xml"), System.nanoTime(), false);

        assertEquals(0, queue.pending());
        assertEquals(0, copier.callsFor(Path.of("late.xml")));
    }

    @Test
    @DisplayName("capture latency is measured from the earliest event that asked for the copy")
    void keepsEarliestEventStamp() throws Exception {
        final Path blocker = Path.of("blocker.xml");
        final Path target = Path.of("stamped.xml");
        copier.gate(blocker);

        final long[] seenEventNanos = {0L};
        final FileCopier recording =
                new FileCopier() {
                    @Override
                    public CopyResult copy(Path relative, long eventNanos, boolean create) {
                        if (relative.equals(blocker)) {
                            return copier.copy(relative, eventNanos, create);
                        }
                        seenEventNanos[0] = eventNanos;
                        return CopyResult.completed(CopyOutcome.COPIED, 1L, 0L);
                    }

                    @Override
                    public boolean contentsDiffer(Path relative) {
                        return true;
                    }
                };

        final CopyQueueImpl queue =
                new CopyQueueImpl(new AlwaysStale(), recording, stats, logger, 1);
        try {
            queue.submit(blocker, System.nanoTime(), true);
            copier.awaitEntered();

            final long earliest = System.nanoTime();
            queue.submit(target, earliest, true);
            queue.submit(target, System.nanoTime() + 1_000_000L, false);

            copier.release();
            assertTrue(queue.awaitIdle(IDLE_TIMEOUT));

            assertEquals(
                    earliest,
                    seenEventNanos[0],
                    "the first event is when the mirror fell behind, so that is the stamp to keep");
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }
}
