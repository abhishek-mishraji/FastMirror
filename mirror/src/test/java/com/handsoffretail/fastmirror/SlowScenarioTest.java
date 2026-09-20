package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyStats;
import com.handsoffretail.fastmirror.support.AgentHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IT-13 to IT-16 — the heavy scenarios.
 *
 * <p>Tagged {@code slow} and excluded from the default test run. Execute with
 * {@code gradlew slowTest}.
 */
@Tag("slow")
class SlowScenarioTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;
    @TempDir Path staging;

    @Test
    @DisplayName("IT-13: a reader polling the target never observes a partially written file")
    void it13_atomicVisibility() throws Exception {
        final int size = 50 * 1024 * 1024;
        final byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i % 251);
        }

        // Build the file outside the watched tree, then move it in atomically, so the agent sees a
        // complete file. Otherwise the intermediate copies FR-05 permits would be indistinguishable
        // from the non-atomic publishing this test is looking for.
        final Path prepared = staging.resolve("big.bin");
        Files.write(prepared, content);

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final Path target = agent.target("big.bin");
            final Set<Long> observedSizes = new ConcurrentSkipListSet<>();
            final AtomicBoolean polling = new AtomicBoolean(true);

            final Thread reader =
                    new Thread(
                            () -> {
                                while (polling.get()) {
                                    try {
                                        observedSizes.add(
                                                Files.isRegularFile(target) ? Files.size(target) : 0L);
                                        Thread.sleep(1L);
                                    } catch (final IOException e) {
                                        // The target may be swapped between the check and the read.
                                        observedSizes.add(0L);
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                            },
                            "it13-reader");
            reader.setDaemon(true);
            reader.start();

            Files.move(prepared, sourceRoot.resolve("big.bin"), StandardCopyOption.ATOMIC_MOVE);
            agent.awaitMirrored("big.bin", content);

            polling.set(false);
            reader.join(5_000L);

            assertEquals(
                    Set.of(0L, (long) size),
                    observedSizes,
                    "FR-08: a consumer may only ever see the file absent or complete, saw: "
                            + observedSizes);
        }
    }

    @Test
    @DisplayName("IT-14: 5,000 files created as fast as possible all reach the mirror")
    void it14_overflow() throws Exception {
        final int fileCount = 5_000;

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            for (int i = 0; i < fileCount; i++) {
                Files.writeString(sourceRoot.resolve("burst-" + i + ".xml"), "row " + i);
            }

            // Whatever the watcher dropped, the sweep is the safety net (FR-15).
            AgentHarness.await(
                    () -> countMirrored() == fileCount,
                    "all " + fileCount + " files to reach the mirror",
                    java.time.Duration.ofMinutes(3));

            assertEquals(fileCount, countMirrored());
            assertEquals(0L, agent.count(CopyOutcome.FAILED));
        }
    }

    @Test
    @DisplayName("IT-15: 50 files per second for 60 seconds, all mirrored within the latency budget")
    void it15_burstThroughput() throws Exception {
        final int perSecond = 50;
        final int seconds = 60;
        final int total = perSecond * seconds;

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final long start = System.nanoTime();
            for (int second = 0; second < seconds; second++) {
                for (int i = 0; i < perSecond; i++) {
                    Files.writeString(
                            sourceRoot.resolve("rate-" + second + "-" + i + ".xml"), "payload");
                }
                final long targetNanos = start + (second + 1) * 1_000_000_000L;
                final long sleepMs = (targetNanos - System.nanoTime()) / 1_000_000L;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }

            AgentHarness.await(
                    () -> countMirrored() == total,
                    "all " + total + " files to reach the mirror",
                    java.time.Duration.ofMinutes(3));

            final CopyStats stats = agent.stats();
            System.out.println(
                    "IT-15: " + total + " files, capture latency avg="
                            + String.format(java.util.Locale.ROOT, "%.2f", stats.averageLatencyMs())
                            + "ms max=" + stats.maxLatencyMs() + "ms over "
                            + stats.observations() + " opens");

            assertEquals(total, countMirrored());
            assertEquals(0L, agent.count(CopyOutcome.FAILED));
            // NFR-01 states a median. The reporter tracks mean and max, and a mean under the
            // budget is the stricter claim when outliers exist, so it is what is asserted here.
            assertTrue(
                    stats.averageLatencyMs() < 10.0,
                    "NFR-01: mean capture latency was " + stats.averageLatencyMs() + "ms");
        }
    }

    @Test
    @DisplayName("IT-16: a sustained run leaves thread and handle counts stable")
    void it16_longRun() throws Exception {
        // Parameterised so the same test covers the local run and the 24-hour soak on the store PC:
        //   gradlew slowTest -Dfastmirror.soak.seconds=86400
        final int seconds = Integer.getInteger("fastmirror.soak.seconds", 30);

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            // Warm up before taking the baseline. Executors.newFixedThreadPool creates its worker
            // threads lazily, so a baseline taken at startup would count zero copy workers and then
            // "grow" by COPY_WORKERS on first load - bounded pool growth, not a leak. The question
            // NFR-05 actually asks is whether the count is stable once the pool is populated.
            for (int i = 0; i < 20; i++) {
                Files.writeString(sourceRoot.resolve("warmup-" + i + ".xml"), "warmup");
            }
            agent.awaitIdle();
            Thread.sleep(200L);

            final int threadsAtStart = Thread.activeCount();
            final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            int cycle = 0;

            while (System.nanoTime() < deadline) {
                final Path file = sourceRoot.resolve("soak-" + (cycle % 50) + ".xml");
                Files.writeString(file, "cycle " + cycle);
                if (cycle % 3 == 0) {
                    Files.deleteIfExists(file);
                }
                cycle++;
                Thread.sleep(10L);
            }

            agent.awaitIdle();
            final int threadsAtEnd = Thread.activeCount();

            System.out.println(
                    "IT-16 soak over " + seconds + "s: cycles=" + cycle
                            + " threads " + threadsAtStart + " -> " + threadsAtEnd
                            + " COPIED=" + agent.count(CopyOutcome.COPIED)
                            + " REPLACED=" + agent.count(CopyOutcome.REPLACED)
                            + " VANISHED=" + agent.count(CopyOutcome.VANISHED)
                            + " PARTIAL=" + agent.count(CopyOutcome.PARTIAL)
                            + " LOCKED=" + agent.count(CopyOutcome.LOCKED)
                            + " FAILED=" + agent.count(CopyOutcome.FAILED));

            assertEquals(
                    0L,
                    agent.count(CopyOutcome.FAILED),
                    "a FAILED means bytes were lost for a reason other than the race. Agent log: "
                            + String.join(" | ", agent.problemLines()));
            assertTrue(
                    threadsAtEnd <= threadsAtStart + 2,
                    "NFR-05: thread count should be stable, went from "
                            + threadsAtStart + " to " + threadsAtEnd);
        }
    }

    private long countMirrored() {
        try (var stream = Files.list(mirrorRoot)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".xml")).count();
        } catch (final IOException e) {
            return -1L;
        }
    }
}
