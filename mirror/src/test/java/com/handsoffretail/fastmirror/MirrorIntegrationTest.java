package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.support.AgentHarness;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** IT-01 to IT-07 and IT-21: the everyday scenarios, fast enough for the default test run. */
class MirrorIntegrationTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;

    private static byte[] payload(int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    @Test
    @DisplayName("IT-01: a normal 4 KB file is mirrored byte-for-byte")
    void it01_normalFile() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final byte[] content = payload(4096);
            agent.write("PJR9.xml", content);

            agent.awaitMirrored("PJR9.xml", content);
            assertArrayEquals(content, Files.readAllBytes(agent.target("PJR9.xml")));
        }
    }

    @Test
    @DisplayName("IT-03: a file that lives about a second is captured before it is deleted")
    void it03_livesOneSecond() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final byte[] content = payload(2048);
            final Path source = agent.write("one-second.xml", content);

            agent.awaitMirrored("one-second.xml", content);
            Thread.sleep(1_000L);
            Files.delete(source);
            Thread.sleep(200L);

            assertArrayEquals(
                    content,
                    Files.readAllBytes(agent.target("one-second.xml")),
                    "the mirrored copy survives the source being deleted");
            assertEquals(
                    0L,
                    agent.count(CopyOutcome.VANISHED),
                    "a file that lives a whole second should never be lost to the race");
        }
    }

    @Test
    @DisplayName("IT-04: 100 create-delete cycles with no pause never crash and never FAIL")
    void it04_vanishingFiles() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            for (int i = 0; i < 100; i++) {
                final Path file = sourceRoot.resolve("flash-" + i + ".xml");
                Files.write(file, payload(512));
                Files.deleteIfExists(file);
            }

            agent.awaitIdle();

            assertEquals(0L, agent.count(CopyOutcome.FAILED), "no copy may end in FAILED");
            final long captured = agent.count(CopyOutcome.COPIED) + agent.count(CopyOutcome.PARTIAL);
            final long lost = agent.count(CopyOutcome.VANISHED);

            System.out.println(
                    "IT-04 race measurement over 100 no-pause cycles: captured=" + captured
                            + " vanished=" + lost
                            + " locked=" + agent.count(CopyOutcome.LOCKED)
                            + "  (this is the SRS 12 limitation, measured not assumed)");

            assertTrue(captured + lost > 0, "the agent must have attempted the files");
        }
    }

    @Test
    @DisplayName("IT-05: a file still being written converges on its final content")
    void it05_stillBeingWritten() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final Path source = sourceRoot.resolve("slow-write.xml");
            final byte[] chunk = payload(64 * 1024);

            try (FileChannel producer =
                    FileChannel.open(
                            source,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND)) {
                for (int i = 0; i < 16; i++) {
                    producer.write(ByteBuffer.wrap(chunk));
                    producer.force(false);
                    Thread.sleep(20L);
                }
            }

            final byte[] finalContent = Files.readAllBytes(source);
            assertEquals(16 * 64 * 1024, finalContent.length);

            agent.awaitMirrored("slow-write.xml", finalContent);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("IT-06: a source locked for 50 ms is copied once the lock clears")
    void it06_temporarilyLocked() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final byte[] content = payload(1024);
            final Path source = agent.write("briefly-locked.xml", content);

            // A genuinely exclusive open (FileShare.None) from another process: the same condition
            // C-3 describes, and something Java cannot express on its own.
            holdExclusively(source, 50).waitFor(30, TimeUnit.SECONDS);

            agent.awaitMirrored("briefly-locked.xml", content);
            assertEquals(0L, agent.count(CopyOutcome.FAILED));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("IT-07: a source locked beyond the retry budget is copied by a later sweep")
    void it07_lockedBeyondRetryBudget() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final byte[] content = payload(1024);
            final Path source = agent.write("long-locked.xml", content);

            final Process holder = holdExclusively(source, 1_500);
            holder.waitFor(30, TimeUnit.SECONDS);

            // The retry budget is ~155 ms by default, so the first attempts give up. The periodic
            // sweep is the safety net that eventually gets it.
            agent.awaitMirrored("long-locked.xml", content);
            assertEquals(0L, agent.count(CopyOutcome.FAILED), "a lock is never a FAILED");
        }
    }

    @Test
    @DisplayName("IT-21: a partial target is restored in full once the source can be read again")
    void it21_partialConvergesOnTheNextSweep() throws Exception {
        final byte[] full = payload(8192);
        Files.write(sourceRoot.resolve("recovering.xml"), full);
        // Simulate a partial capture left behind by an earlier run.
        Files.write(mirrorRoot.resolve("recovering.xml"), java.util.Arrays.copyOf(full, 1000));

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            agent.awaitMirrored("recovering.xml", full);
        }
    }

    @Test
    @DisplayName("the mirror never contains a staging file once the agent is idle")
    void noStagingFilesRemain() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            for (int i = 0; i < 25; i++) {
                agent.write("bulk-" + i + ".xml", payload(4096));
            }
            agent.awaitIdle();
            Thread.sleep(100L);

            assertTrue(
                    agent.mirrorNames().stream().noneMatch(name -> name.endsWith(".fmtmp")),
                    "UT-16 at agent level: " + agent.mirrorNames());
        }
    }

    @Test
    @DisplayName("IT-08 variant: content changing under the same name replaces the mirrored copy")
    void sameNameNewContentReplaces() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final Path source = agent.write("reused.xml", "first version");
            agent.awaitMirrored("reused.xml", "first version");

            Files.delete(source);
            Thread.sleep(50L);
            agent.write("reused.xml", "second version");

            agent.awaitMirrored("reused.xml", "second version");
            assertTrue(
                    agent.count(CopyOutcome.REPLACED) >= 1,
                    "FR-31: this is the filename-reuse case the log line reports");
        }
    }

    /**
     * Opens a file with {@code FileShare.None} from a separate process for the given number of
     * milliseconds. Java has no way to express an exclusive share mode, so this uses .NET through
     * PowerShell to reproduce the condition of C-3 faithfully.
     */
    private static Process holdExclusively(Path file, int millis) throws Exception {
        final String script =
                "$f=[System.IO.File]::Open('" + file.toString().replace("'", "''")
                        + "','Open','ReadWrite','None');"
                        + "Start-Sleep -Milliseconds " + millis + ";"
                        + "$f.Close()";
        return new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start();
    }
}
