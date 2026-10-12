package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyLedger;
import com.handsoffretail.fastmirror.service.FileCopier;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyQueueImplTest {

    @TempDir Path logFolder;

    private AppLogger logger;
    private LoggingStatsReporterImpl stats;
    private InMemoryLedger ledger;
    private CopyQueueImpl queue;

    /** A ledger that lives only in memory. */
    private static final class InMemoryLedger implements CopyLedger {
        private final Set<LedgerKey> keys = ConcurrentHashMap.newKeySet();

        @Override
        public boolean contains(LedgerKey key) {
            return keys.contains(key);
        }

        @Override
        public boolean record(LedgerKey key) {
            keys.add(key);
            return true;
        }

        @Override
        public int size() {
            return keys.size();
        }
    }

    @BeforeEach
    void setUp() {
        logger = new AppLogger(logFolder, Clock.systemDefaultZone());
        stats = new LoggingStatsReporterImpl(logger, 60_000L);
        ledger = new InMemoryLedger();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown(Duration.ofSeconds(5));
        }
        logger.close();
    }

    private CopyQueueImpl queueWith(FileCopier copier, int workers) {
        queue = new CopyQueueImpl(copier, ledger, stats, logger, workers);
        return queue;
    }

    private static FileCopier copier(Function<CopyJob, CopyResult> behaviour) {
        return new FileCopier() {
            @Override
            public CopyResult copy(CopyJob job) {
                return behaviour.apply(job);
            }

            @Override
            public java.util.Optional<byte[]> readHead(Path relative, int maxBytes) {
                return java.util.Optional.empty();
            }
        };
    }

    private static CopyJob job(String name) {
        final Path relative = Path.of(name);
        return new CopyJob(relative, Path.of("CPJ", name), LedgerKey.of(relative, 1L, 10L));
    }

    @Test
    @DisplayName("a successful copy is recorded in the ledger and counted")
    void successIsRecorded() throws Exception {
        queueWith(copier(job -> CopyResult.completed(CopyOutcome.COPIED, 10L)), 2);

        queue.submit(job("CPJ1.xml"));

        assertTrue(queue.awaitIdle(Duration.ofSeconds(5)));
        assertTrue(ledger.contains(job("CPJ1.xml").key()));
        assertEquals(1L, stats.lifetime().count(CopyOutcome.COPIED));
    }

    @Test
    @DisplayName("an overwrite is recorded in the ledger too")
    void replacedIsRecorded() throws Exception {
        queueWith(copier(job -> CopyResult.completed(CopyOutcome.REPLACED, 10L)), 1);

        queue.submit(job("CPJ1.xml"));

        assertTrue(queue.awaitIdle(Duration.ofSeconds(5)));
        assertTrue(ledger.contains(job("CPJ1.xml").key()));
    }

    @Test
    @DisplayName("LOCKED, VANISHED and FAILED are counted but never recorded, so a later scan retries")
    void failuresAreNotRecorded() throws Exception {
        final List<CopyOutcome> outcomes =
                List.of(CopyOutcome.LOCKED, CopyOutcome.VANISHED, CopyOutcome.FAILED, CopyOutcome.SKIPPED_TOO_LARGE);
        final AtomicInteger next = new AtomicInteger();
        queueWith(copier(job -> CopyResult.of(outcomes.get(next.getAndIncrement()))), 1);

        for (int i = 0; i < outcomes.size(); i++) {
            queue.submit(job("CPJ" + i + ".xml"));
            assertTrue(queue.awaitIdle(Duration.ofSeconds(5)));
        }

        assertEquals(0, ledger.size());
        for (final CopyOutcome outcome : outcomes) {
            assertEquals(1L, stats.lifetime().count(outcome), outcome.name());
        }
    }

    @Test
    @DisplayName("a file already queued or running is not queued again")
    void duplicateSubmitsAreIgnored() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger copies = new AtomicInteger();
        queueWith(
                copier(
                        job -> {
                            copies.incrementAndGet();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return CopyResult.completed(CopyOutcome.COPIED, 10L);
                        }),
                4);

        for (int i = 0; i < 20; i++) {
            queue.submit(job("CPJ1.xml"));
        }
        assertEquals(1, queue.pending());
        release.countDown();

        assertTrue(queue.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(1, copies.get());
    }

    @Test
    @DisplayName("the ledger entry is written before the job leaves the in-flight set")
    void ledgerBeforeRelease() throws Exception {
        final List<Boolean> recordedWhilePending = new CopyOnWriteArrayList<>();
        final CopyLedger watching =
                new CopyLedger() {
                    @Override
                    public boolean contains(LedgerKey key) {
                        return ledger.contains(key);
                    }

                    @Override
                    public boolean record(LedgerKey key) {
                        recordedWhilePending.add(queue.pending() == 1);
                        return ledger.record(key);
                    }

                    @Override
                    public int size() {
                        return ledger.size();
                    }
                };
        queue =
                new CopyQueueImpl(
                        copier(job -> CopyResult.completed(CopyOutcome.COPIED, 10L)),
                        watching,
                        stats,
                        logger,
                        1);

        queue.submit(job("CPJ1.xml"));

        assertTrue(queue.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(List.of(true), recordedWhilePending);
    }

    @Test
    @DisplayName("a copier that throws does not kill the worker or the queue")
    void throwingCopierIsAbsorbed() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        queueWith(
                copier(
                        job -> {
                            if (calls.getAndIncrement() == 0) {
                                throw new IllegalStateException("boom");
                            }
                            return CopyResult.completed(CopyOutcome.COPIED, 10L);
                        }),
                1);

        queue.submit(job("CPJ1.xml"));
        assertTrue(queue.awaitIdle(Duration.ofSeconds(5)));
        queue.submit(job("CPJ2.xml"));
        assertTrue(queue.awaitIdle(Duration.ofSeconds(5)));

        assertEquals(1L, stats.lifetime().count(CopyOutcome.FAILED));
        assertEquals(1L, stats.lifetime().count(CopyOutcome.COPIED));
        assertFalse(ledger.contains(job("CPJ1.xml").key()));
        assertTrue(ledger.contains(job("CPJ2.xml").key()));
    }

    @Test
    @DisplayName("no more than COPY_WORKERS copies run at once")
    void concurrencyIsBounded() throws Exception {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        queueWith(
                copier(
                        job -> {
                            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                            try {
                                Thread.sleep(20L);
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            running.decrementAndGet();
                            return CopyResult.completed(CopyOutcome.COPIED, 10L);
                        }),
                3);

        for (int i = 0; i < 30; i++) {
            queue.submit(job("CPJ" + i + ".xml"));
        }

        assertTrue(queue.awaitIdle(Duration.ofSeconds(10)));
        assertTrue(peak.get() <= 3, "peak concurrency was " + peak.get());
        assertEquals(30, ledger.size());
    }

    @Test
    @DisplayName("submissions after shutdown are ignored rather than throwing")
    void submitAfterShutdownIsIgnored() throws Exception {
        queueWith(copier(job -> CopyResult.completed(CopyOutcome.COPIED, 10L)), 1);
        queue.shutdown(Duration.ofSeconds(1));

        queue.submit(job("CPJ1.xml"));

        assertEquals(0, queue.pending());
        assertEquals(0, ledger.size());
    }
}
