package com.handsoffretail.fastmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.support.AgentHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises startup and shutdown through the real composition root. */
class ApplicationStartupTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;

    @Test
    @DisplayName("the agent starts, copies a stable file and stops cleanly")
    void startsAndCopies() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            agent.write("CPJ9.xml", "the payload", LocalDateTime.of(2026, 3, 9, 12, 0));
            agent.copyStableFiles();

            agent.awaitMirrored("2026/march/2026-03-09/CPJ/CPJ9.xml", "the payload");
        }
    }

    @Test
    @DisplayName("files already in the source at startup are copied once stable, not immediately")
    void startupScanStartsTheStabilityClock() throws Exception {
        Files.writeString(sourceRoot.resolve("CPJ1.xml"), "from before startup");
        AgentHarness.setCreated(sourceRoot.resolve("CPJ1.xml"), LocalDateTime.of(2026, 10, 1, 9, 0));

        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            agent.awaitIdle();
            assertEquals(List.of(), agent.mirroredFiles(), "the startup scan only starts the wait");

            agent.advancePastStability();
            agent.scanNow();
            agent.awaitIdle();

            agent.awaitMirrored("2026/october/2026-10-01/CPJ/CPJ1.xml", "from before startup");
        }
    }

    @Test
    @DisplayName("stale staging files are removed at startup, and nothing else is")
    @SuppressWarnings("try") // the agent is started for its side effect on the mirror folder
    void removesStaleStagingFiles() throws Exception {
        Files.writeString(mirrorRoot.resolve("x.xml.1.fmtmp"), "left over from a crash");
        Files.writeString(mirrorRoot.resolve("keep.xml"), "a real mirrored file");

        try (AgentHarness ignored = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            assertFalse(Files.exists(mirrorRoot.resolve("x.xml.1.fmtmp")));
            assertTrue(Files.exists(mirrorRoot.resolve("keep.xml")), "nothing else is ever deleted");
        }
    }

    @Test
    @DisplayName("the ledger lives in the state folder, outside the source and the mirror")
    void ledgerLivesInStateFolder() throws Exception {
        try (AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory)) {
            agent.write("CPJ1.xml", "payload", LocalDateTime.of(2026, 10, 15, 10, 0));
            agent.copyStableFiles();

            final List<String> lines = Files.readAllLines(agent.ledgerFile(), StandardCharsets.UTF_8);
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).endsWith("\tcpj1.xml"), lines.get(0));
            assertTrue(agent.ledgerFile().startsWith(baseDirectory.toRealPath()));
        }
    }

    @Test
    @DisplayName("stop() is idempotent and leaves no staging files behind")
    void stopIsIdempotent() throws Exception {
        final AgentHarness agent = AgentHarness.start(sourceRoot, mirrorRoot, baseDirectory);
        agent.write("CPJ1.xml", "payload", LocalDateTime.of(2026, 10, 15, 10, 0));
        agent.copyStableFiles();

        agent.close();
        agent.close();

        assertEquals(List.of("2026/october/2026-10-15/CPJ/CPJ1.xml"), agent.mirroredFiles());
    }
}
