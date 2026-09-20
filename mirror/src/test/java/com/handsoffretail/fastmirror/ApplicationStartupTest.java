package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.support.AgentHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the startup sequence of SRS 9.2 through the real composition root. */
class ApplicationStartupTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;

    @Test
    @DisplayName("the agent starts, mirrors a live file and stops cleanly")
    void startsAndMirrors() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            agent.write("PJR9.xml", "the payload");

            agent.awaitMirrored("PJR9.xml", "the payload");
        }
    }

    @Test
    @DisplayName("the startup sweep brings pre-existing files into the mirror")
    void startupSweepCatchesExistingFiles() throws Exception {
        Files.writeString(sourceRoot.resolve("already-here.xml"), "from before startup");

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            agent.awaitMirrored("already-here.xml", "from before startup");
        }
    }

    @Test
    @DisplayName("IT-12: stale staging files are removed at startup")
    void it12_removesStaleStagingFiles() throws Exception {
        Files.writeString(mirrorRoot.resolve("x.xml.fmtmp"), "left over from a crash");
        Files.writeString(mirrorRoot.resolve("keep.xml"), "a real mirrored file");

        try (AgentHarness ignored = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            assertFalse(Files.exists(mirrorRoot.resolve("x.xml.fmtmp")), "FR-22");
            assertTrue(Files.exists(mirrorRoot.resolve("keep.xml")), "FR-17: nothing else is deleted");
        }
    }

    @Test
    @DisplayName("IT-09: nested structure is preserved end to end")
    void it09_preservesNestedStructure() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            agent.write("s1/s2/r.xml", "nested payload");

            agent.awaitMirrored("s1/s2/r.xml", "nested payload");
        }
    }

    @Test
    @DisplayName("IT-10: deleting the source leaves the mirrored copy untouched")
    void it10_sourceDeleteDoesNotTouchMirror() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final Path source = agent.write("transient.xml", "captured");
            agent.awaitMirrored("transient.xml", "captured");

            Files.delete(source);
            Thread.sleep(200L);

            assertEquals(
                    "captured",
                    Files.readString(agent.target("transient.xml"), StandardCharsets.UTF_8),
                    "FR-04/FR-17: a source delete is never propagated");
        }
    }

    @Test
    @DisplayName("IT-08: a rename leaves both names in the mirror")
    void it08_renameLeavesBothNames() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final Path a = agent.write("a.xml", "payload");
            agent.awaitMirrored("a.xml", "payload");

            Files.move(a, sourceRoot.resolve("b.xml"));

            agent.awaitMirrored("b.xml", "payload");
            assertTrue(Files.exists(agent.target("a.xml")), "the old name is never removed");
        }
    }

    @Test
    @DisplayName("IT-02: repeated modifications converge on the final content")
    void it02_repeatedModificationsConverge() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            final Path source = agent.write("growing.xml", "chunk0");
            agent.awaitExists("growing.xml");

            final StringBuilder expected = new StringBuilder("chunk0");
            for (int i = 1; i <= 5; i++) {
                final String chunk = "-chunk" + i;
                expected.append(chunk);
                Files.writeString(source, expected.toString());
                Thread.sleep(40L);
            }

            agent.awaitMirrored("growing.xml", expected.toString());
            assertEquals(0L, agent.count(CopyOutcome.FAILED), "no copy should have failed");
        }
    }

    @Test
    @DisplayName("FR-18: excluded files never reach the mirror")
    void excludedFilesAreNotMirrored() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            agent.write("real.xml", "keep me");
            agent.write("scratch.tmp", "ignore me");

            agent.awaitMirrored("real.xml", "keep me");
            agent.awaitIdle();

            assertFalse(Files.exists(agent.target("scratch.tmp")));
        }
    }

    @Test
    @DisplayName("IT-11: a restart brings the mirror back into line via the startup sweep")
    void it11_restartRecovery() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            agent.write("first.xml", "one");
            agent.awaitMirrored("first.xml", "one");
        }

        // The agent is down. Files change behind its back.
        Files.writeString(sourceRoot.resolve("second.xml"), "two");
        Files.writeString(sourceRoot.resolve("first.xml"), "one modified");

        try (AgentHarness restarted = AgentHarness.start(sourceRoot, mirrorRoot, logFolder)) {
            restarted.awaitMirrored("second.xml", "two");
            restarted.awaitMirrored("first.xml", "one modified");
        }
    }

    @Test
    @DisplayName("a disabled sweep still mirrors via events alone")
    void worksWithSweepDisabled() throws Exception {
        try (AgentHarness agent =
                AgentHarness.start(
                        sourceRoot, mirrorRoot, logFolder, Map.of("SWEEP_INTERVAL_MS", "0"))) {
            agent.write("event-only.xml", "delivered by the watcher");

            agent.awaitMirrored("event-only.xml", "delivered by the watcher");
        }
    }

    @Test
    @DisplayName("stop() is idempotent and leaves no staging files behind")
    void stopIsIdempotent() throws Exception {
        final AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, logFolder);
        agent.write("x.xml", "payload");
        agent.awaitMirrored("x.xml", "payload");

        agent.close();
        agent.close();

        assertEquals(java.util.List.of("x.xml"), agent.mirrorNames());
    }
}
