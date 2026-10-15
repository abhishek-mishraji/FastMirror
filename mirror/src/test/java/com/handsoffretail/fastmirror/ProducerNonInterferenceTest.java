package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.service.implementation.AtomicFileCopierImpl;
import com.handsoffretail.fastmirror.support.AgentHarness;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * IT-18, IT-19, IT-20 — the executable form of the first rule: never disturb the POS.
 *
 * <p>Every assertion here is made from the <em>producer's</em> point of view: the question is never
 * "did FastMirror capture the file" but "did the producer's own call succeed". If any of these go
 * red, the design is wrong and no amount of captured data makes up for it.
 *
 * <p>These tests also turn the JDK's share flags from a claim about its internals into a measured fact on this
 * machine and this JDK. Java offers no API for setting Windows share flags, so the only honest way
 * to depend on that behaviour is to verify it and re-verify it on every build.
 */
class ProducerNonInterferenceTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;

    private static final int CYCLES = 200;

    /** Anything the simulated producer was not supposed to see. */
    private final List<String> producerFailures = new CopyOnWriteArrayList<>();

    private void producerDid(String description, ThrowingAction action) {
        try {
            action.run();
        } catch (final Throwable t) {
            producerFailures.add(description + " -> " + t);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ IT-18: delete

    @Test
    @DisplayName("IT-18: the producer's delete succeeds while FastMirror holds the file open")
    void it18_deleteSucceedsWhileHeldOpen() throws Exception {
        final Path file = sourceRoot.resolve("held.xml");
        Files.writeString(file, "payload", StandardCharsets.UTF_8);

        // Exactly how production opens a producer file.
        try (SeekableByteChannel held = AtomicFileCopierImpl.READ_ONLY_NIO.open(file)) {

            // The producer deletes the file out from under us. This must simply work.
            assertDoesNotThrow(() -> Files.delete(file), "the producer's delete must not fail");
            assertFalse(Files.exists(file), "the name is gone from the directory");

            // Our already-open handle keeps serving the real data. This is the fact the
            // entire design rests on - the race is only on the first open, never on the read.
            final ByteBuffer buffer = ByteBuffer.allocate(64);
            final int read = held.read(buffer);
            buffer.flip();
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            assertEquals(7, read);
            assertEquals("payload", new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("IT-18b: 200 create-then-delete cycles produce no producer error at all")
    void it18b_rapidCreateDeleteNeverFailsTheProducer() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            for (int i = 0; i < CYCLES; i++) {
                final Path file = sourceRoot.resolve("CPJburst-" + i + ".xml");
                final int index = i;
                producerDid(
                        "create burst-" + index,
                        () -> Files.writeString(file, "row " + index, StandardCharsets.UTF_8));
                // Stable files from earlier cycles are copied while later ones are deleted.
                agent.scanNow();
                agent.advancePastStability();
                agent.scanNow();
                producerDid("delete burst-" + index, () -> Files.deleteIfExists(file));
            }

            agent.awaitIdle();

            assertEquals(
                    List.of(),
                    producerFailures,
                    "not one producer operation may fail because FastMirror was reading");
            assertEquals(
                    0L,
                    agent.count(CopyOutcome.FAILED),
                    "Agent log: " + String.join(" | ", agent.problemLines()));

            System.out.println(
                    "IT-18b over " + CYCLES + " create-copy-delete cycles: "
                            + "COPIED=" + agent.count(CopyOutcome.COPIED)
                            + " VANISHED=" + agent.count(CopyOutcome.VANISHED)
                            + " LOCKED=" + agent.count(CopyOutcome.LOCKED));
        }
    }

    // ------------------------------------------------------------------ IT-19: write

    @Test
    @DisplayName("IT-19: the producer's writes succeed while FastMirror holds the file open")
    void it19_writesSucceedWhileHeldOpen() throws Exception {
        final Path file = sourceRoot.resolve("being-written.xml");
        Files.writeString(file, "first", StandardCharsets.UTF_8);

        try (SeekableByteChannel held = AtomicFileCopierImpl.READ_ONLY_NIO.open(file)) {
            assertTrue(held.isOpen(), "precondition: FastMirror is holding the file open");

            for (int i = 0; i < 50; i++) {
                final int index = i;
                producerDid(
                        "append " + index,
                        () -> {
                            try (FileChannel producer =
                                    FileChannel.open(
                                            file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                                producer.write(
                                        ByteBuffer.wrap(
                                                ("-row" + index).getBytes(StandardCharsets.UTF_8)));
                            }
                        });
            }
        }

        assertEquals(List.of(), producerFailures, "the producer must keep writing freely");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("-row49"));
    }

    @Test
    @DisplayName("IT-19b: continuous appends succeed while the agent repeatedly copies the file")
    void it19b_continuousAppendsDuringLiveCopying() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final Path file =
                    agent.write("CPJstream.xml", "start", java.time.LocalDateTime.of(2026, 10, 15, 10, 0));

            for (int i = 0; i < 100; i++) {
                final int index = i;
                // Each pass makes the file look stable and starts a copy, then appends to it
                // while that copy may still be holding it open.
                agent.scanNow();
                agent.advancePastStability();
                agent.scanNow();
                producerDid(
                        "append " + index,
                        () -> Files.writeString(
                                file,
                                "-row" + index,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND));
            }

            agent.copyStableFiles();

            assertEquals(List.of(), producerFailures, "the producer must keep working");
            final String finalContent = Files.readString(file, StandardCharsets.UTF_8);
            agent.awaitMirrored("2026/october/2026-10-15/CPJ/CPJstream.xml", finalContent);
        }
    }

    // ------------------------------------------------------------------ IT-20: rename

    @Test
    @DisplayName("IT-20: the producer's rename succeeds while FastMirror holds the file open")
    void it20_renameSucceedsWhileHeldOpen() throws Exception {
        final Path file = sourceRoot.resolve("before.xml");
        Files.writeString(file, "payload", StandardCharsets.UTF_8);

        try (SeekableByteChannel held = AtomicFileCopierImpl.READ_ONLY_NIO.open(file)) {
            assertTrue(held.isOpen(), "precondition: FastMirror is holding the file open");

            // A rename needs DELETE access to the source name, so this fails too unless
            // FILE_SHARE_DELETE was granted.
            assertDoesNotThrow(() -> Files.move(file, sourceRoot.resolve("after.xml")));
        }

        assertTrue(Files.exists(sourceRoot.resolve("after.xml")));
        assertFalse(Files.exists(sourceRoot.resolve("before.xml")));
    }

    @Test
    @DisplayName("IT-20b: 200 create-then-rename cycles produce no producer error")
    void it20b_rapidRenameNeverFailsTheProducer() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            for (int i = 0; i < CYCLES; i++) {
                final Path created = sourceRoot.resolve("CPJnew-" + i + ".xml");
                final Path renamed = sourceRoot.resolve("CPJdone-" + i + ".xml");
                final int index = i;
                producerDid(
                        "create new-" + index,
                        () -> Files.writeString(created, "row " + index, StandardCharsets.UTF_8));
                agent.scanNow();
                agent.advancePastStability();
                agent.scanNow();
                producerDid("rename new-" + index, () -> Files.move(created, renamed));
            }

            agent.awaitIdle();

            assertEquals(List.of(), producerFailures, "a rename must never hit a sharing violation");
            assertEquals(0L, agent.count(CopyOutcome.FAILED));
        }
    }

    // ------------------------------------------------------------------ external producer

    /**
     * Runs a real Windows command and returns its exit code.
     *
     * <p>The tests above use Java's own file operations as a stand-in for the producer, which is
     * convenient but imperfect: the JDK could be taking a code path that POS Link does not. These
     * tests remove that doubt by having a genuinely separate Windows process perform the delete,
     * rename and write. If a separate process can do it, the producer can do it.
     */
    private int runWindowsCommand(String... command) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("The external producer command hung: " + String.join(" ", command));
        }
        final int exitCode = process.exitValue();
        if (exitCode != 0) {
            producerOutput.add(String.join(" ", command) + " -> exit " + exitCode + " " + output.trim());
        }
        return exitCode;
    }

    private final List<String> producerOutput = new CopyOnWriteArrayList<>();

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("IT-18c: a separate Windows process can delete the file FastMirror holds open")
    void it18c_externalProcessDeleteSucceeds() throws Exception {
        final Path file = sourceRoot.resolve("external-delete.xml");
        Files.writeString(file, "payload", StandardCharsets.UTF_8);

        try (SeekableByteChannel held = AtomicFileCopierImpl.READ_ONLY_NIO.open(file)) {
            assertTrue(held.isOpen(), "precondition: FastMirror is holding the file open");

            final int exitCode = runWindowsCommand("cmd", "/c", "del", "/q", file.toString());

            assertEquals(0, exitCode, "an external delete must succeed. " + producerOutput);
            assertFalse(Files.exists(file), "the producer's delete really took effect");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("IT-19c: a separate Windows process can append to the file FastMirror holds open")
    void it19c_externalProcessAppendSucceeds() throws Exception {
        final Path file = sourceRoot.resolve("external-append.xml");
        Files.writeString(file, "payload", StandardCharsets.UTF_8);

        try (SeekableByteChannel held = AtomicFileCopierImpl.READ_ONLY_NIO.open(file)) {
            assertTrue(held.isOpen());

            final int exitCode = runWindowsCommand("cmd", "/c", "echo more>>" + file);

            assertEquals(0, exitCode, "an external write must succeed. " + producerOutput);
            assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("more"));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("IT-20c: a separate Windows process can rename the file FastMirror holds open")
    void it20c_externalProcessRenameSucceeds() throws Exception {
        final Path file = sourceRoot.resolve("external-rename.xml");
        Files.writeString(file, "payload", StandardCharsets.UTF_8);

        try (SeekableByteChannel held = AtomicFileCopierImpl.READ_ONLY_NIO.open(file)) {
            assertTrue(held.isOpen());

            final int exitCode = runWindowsCommand("cmd", "/c", "ren", file.toString(), "external-renamed.xml");

            assertEquals(0, exitCode, "an external rename must succeed. " + producerOutput);
            assertTrue(Files.exists(sourceRoot.resolve("external-renamed.xml")));
        }
    }

    /**
     * Records what memory-mapping actually does on this platform.
     *
     * <p>An earlier specification asserted that a mapped section makes the producer's delete fail with
     * {@code ERROR_USER_MAPPED_FILE}. On this machine — Windows 11 build 26200, JDK 21 — it does
     * not: modern Windows deletes by unlinking the name, and both an in-process and an external
     * delete succeed against a mapped file. That claim was dropped.
     *
     * <p>The architecture tests still forbid mapping and {@code transferTo}, for the reasons that survive the
     * correction: the behaviour is Windows-version-dependent and documented to fail elsewhere, an
     * explicit bounded loop costs nothing, and mapping conflicts with the bounded-memory rule.
     * This test exists so that the measurement is on record and is re-taken on every build rather
     * than being assumed in either direction.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("platform record: what a mapped section does to an external delete here")
    void platformRecord_mappedSectionAndExternalDelete() throws Exception {
        final Path directory = Files.createTempDirectory("fastmirror-platform-record");
        final Path file = directory.resolve("mapped.xml");
        Files.writeString(file, "payload", StandardCharsets.UTF_8);

        final int exitCode;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            final MappedByteBuffer mapped =
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            exitCode = runWindowsCommand("cmd", "/c", "del", "/q", file.toString());
            // Keep the mapping alive across the delete; otherwise the test measures nothing.
            java.lang.ref.Reference.reachabilityFence(mapped);
        }

        System.out.println(
                "Platform record: external delete against a memory-mapped file returned exit "
                        + exitCode + " on " + System.getProperty("os.name") + " "
                        + System.getProperty("os.version") + ", JDK "
                        + System.getProperty("java.version")
                        + ".");

        // Deliberately no assertion on the platform's behaviour: this records a fact, it does not
        // legislate one. The mapping ban does not depend on the outcome either way.
        System.gc();
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        } catch (final IOException e) {
            // Left for the OS to clean up. Not a test failure.
        }
    }
}
