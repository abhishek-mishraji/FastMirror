package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.support.AgentHarness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The heavy scenarios.
 *
 * <p>Tagged {@code slow} and excluded from the default test run. Execute with
 * {@code gradlew slowTest}.
 */
@Tag("slow")
class SlowScenarioTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;
    @TempDir Path staging;

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 15, 10, 0);

    @Test
    @DisplayName("IT-13: a reader polling the target never observes a partially written file")
    void it13_atomicVisibility() throws Exception {
        final int size = 50 * 1024 * 1024;
        final byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i % 251);
        }

        final Path prepared = staging.resolve("CPJbig.xml");
        Files.write(prepared, content);

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            Files.move(prepared, sourceRoot.resolve("CPJbig.xml"), StandardCopyOption.ATOMIC_MOVE);
            AgentHarness.setCreated(sourceRoot.resolve("CPJbig.xml"), CREATED);
            agent.scanNow();
            agent.advancePastStability();

            final Path target = agent.target("2026/october/2026-10-15/CPJ/CPJbig.xml");
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

            agent.scanNow();
            agent.awaitMirrored("2026/october/2026-10-15/CPJ/CPJbig.xml", content);

            polling.set(false);
            reader.join(5_000L);

            assertEquals(
                    Set.of(0L, (long) size),
                    observedSizes,
                    "a consumer may only ever see the file absent or complete, saw: " + observedSizes);
        }
    }

    @Test
    @DisplayName("IT-14: a backlog of 5,000 files is copied once, and not again after a restart")
    void it14_largeBacklog() throws Exception {
        final int fileCount = 5_000;
        for (int i = 0; i < fileCount; i++) {
            final Path file = sourceRoot.resolve("CPJ" + i + ".xml");
            Files.writeString(file, "row " + i, StandardCharsets.UTF_8);
            AgentHarness.setCreated(file, CREATED.plusMinutes(i % 1440));
        }

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            agent.advancePastStability();
            agent.scanNow();
            AgentHarness.await(
                    () -> countMirrored() == fileCount,
                    "all " + fileCount + " files to reach the mirror",
                    Duration.ofMinutes(3));
            agent.awaitIdle();

            assertEquals(fileCount, agent.count(CopyOutcome.COPIED));
            assertEquals(0L, agent.count(CopyOutcome.FAILED));
            assertEquals(fileCount, Files.readAllLines(agent.ledgerFile()).size());
        }

        try (AgentHarness restarted = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            restarted.copyStableFiles();

            assertEquals(0L, restarted.count(CopyOutcome.COPIED) + restarted.count(CopyOutcome.REPLACED));
        }
    }

    @Test
    @DisplayName("IT-16: a sustained run leaves the thread count stable")
    void it16_longRun() throws Exception {
        // Parameterised so the same test covers the local run and a longer soak:
        //   gradlew slowTest -Dfastmirror.soak.seconds=3600
        final int seconds = Integer.getInteger("fastmirror.soak.seconds", 30);

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            // Warm up first: the worker pool creates its threads lazily.
            for (int i = 0; i < 20; i++) {
                agent.write("CPJwarm" + i + ".xml", "warmup", CREATED);
            }
            agent.copyStableFiles();
            Thread.sleep(200L);

            final int threadsAtStart = Thread.activeCount();
            final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            int cycle = 0;

            while (System.nanoTime() < deadline) {
                agent.write("CPJsoak" + cycle + ".xml", "cycle " + cycle, CREATED);
                if (cycle % 10 == 0) {
                    agent.copyStableFiles();
                }
                cycle++;
                Thread.sleep(10L);
            }
            agent.copyStableFiles();
            final int threadsAtEnd = Thread.activeCount();

            System.out.println(
                    "IT-16 soak over " + seconds + "s: cycles=" + cycle
                            + " threads " + threadsAtStart + " -> " + threadsAtEnd
                            + " COPIED=" + agent.count(CopyOutcome.COPIED)
                            + " LOCKED=" + agent.count(CopyOutcome.LOCKED)
                            + " FAILED=" + agent.count(CopyOutcome.FAILED));

            assertEquals(
                    0L,
                    agent.count(CopyOutcome.FAILED),
                    "Agent log: " + String.join(" | ", agent.problemLines()));
            assertEquals(cycle + 20L, agent.count(CopyOutcome.COPIED));
            assertTrue(
                    threadsAtEnd <= threadsAtStart + 2,
                    "thread count should be stable, went from " + threadsAtStart + " to " + threadsAtEnd);
        }
    }

    private long countMirrored() {
        try (Stream<Path> stream = Files.walk(mirrorRoot)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".xml")).count();
        } catch (final IOException e) {
            return -1L;
        }
    }
}
