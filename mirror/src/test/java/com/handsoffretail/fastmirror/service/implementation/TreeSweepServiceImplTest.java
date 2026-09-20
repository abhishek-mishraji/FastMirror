package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.DifferenceDetector;
import com.handsoffretail.fastmirror.service.SweepService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeSweepServiceImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    private AppLogger logger;
    private RecordingQueue queue;
    private Map<String, String> values;

    private static final class RecordingQueue implements CopyQueue {
        private final List<Path> submitted = new CopyOnWriteArrayList<>();

        @Override
        public void submit(Path relative, long eventNanos, boolean nameReuseCandidate) {
            assertFalse(
                    nameReuseCandidate,
                    "a sweep sees no events, so it can never observe a delete-then-create");
            submitted.add(relative);
        }

        @Override
        public int pending() {
            return 0;
        }

        @Override
        public boolean awaitIdle(Duration timeout) {
            return true;
        }

        @Override
        public void shutdown(Duration grace) {
            // nothing to do
        }
    }

    @BeforeEach
    void setUp() {
        logger = new AppLogger(logFolder, CLOCK);
        queue = new RecordingQueue();
        values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
    }

    @AfterEach
    void tearDown() {
        logger.close();
    }

    private SweepService sweeper(DifferenceDetector detector) {
        final MirrorConfig config = MirrorConfig.load(values::get, logFolder);
        return new TreeSweepServiceImpl(
                config,
                new RelativePathMapperImpl(sourceRoot, mirrorRoot),
                new GlobPathFilterImpl(config.excludePatterns()),
                detector,
                queue,
                logger);
    }

    private static final DifferenceDetector ALWAYS = relative -> true;
    private static final DifferenceDetector NEVER = relative -> false;

    private void createSourceFile(String name) throws IOException {
        final Path file = sourceRoot.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "payload");
    }

    @Test
    @DisplayName("every differing file in the tree is submitted, at any depth")
    void submitsDifferingFiles() throws Exception {
        createSourceFile("a.xml");
        createSourceFile("s1/b.xml");
        createSourceFile("s1/s2/c.xml");

        sweeper(ALWAYS).sweep();

        assertEquals(
                Set.of(Path.of("a.xml"), Path.of("s1", "b.xml"), Path.of("s1", "s2", "c.xml")),
                Set.copyOf(queue.submitted));
    }

    @Test
    @DisplayName("files that already match are not submitted")
    void skipsMatchingFiles() throws Exception {
        createSourceFile("a.xml");

        sweeper(NEVER).sweep();

        assertTrue(queue.submitted.isEmpty());
    }

    @Test
    @DisplayName("FR-18: excluded files are skipped by the sweep too")
    void skipsExcludedFiles() throws Exception {
        createSourceFile("keep.xml");
        createSourceFile("skip.tmp");
        createSourceFile("stale.xml.fmtmp");

        sweeper(ALWAYS).sweep();

        assertEquals(List.of(Path.of("keep.xml")), queue.submitted);
    }

    @Test
    @DisplayName("an empty tree submits nothing and does not fail")
    void emptyTreeIsFine() {
        sweeper(ALWAYS).sweep();

        assertTrue(queue.submitted.isEmpty());
    }

    @Test
    @DisplayName("directories are not submitted as files")
    void directoriesAreNotSubmitted() throws Exception {
        Files.createDirectories(sourceRoot.resolve("empty-dir"));

        sweeper(ALWAYS).sweep();

        assertTrue(queue.submitted.isEmpty());
    }

    @Test
    @DisplayName("FR-25: with MIRROR_EMPTY_DIRECTORIES the sweep creates empty directories")
    void mirrorsEmptyDirectories() throws Exception {
        values.put(MirrorConfig.KEY_MIRROR_EMPTY_DIRECTORIES, "true");
        Files.createDirectories(sourceRoot.resolve("s1").resolve("s2"));

        sweeper(ALWAYS).sweep();

        assertTrue(Files.isDirectory(mirrorRoot.resolve("s1").resolve("s2")));
    }

    @Test
    @DisplayName("a detector that throws for one file does not abandon the sweep")
    void toleratesPerEntryFailures() throws Exception {
        createSourceFile("bad.xml");
        createSourceFile("good.xml");

        final DifferenceDetector flaky =
                relative -> {
                    if (relative.toString().contains("bad")) {
                        throw new IllegalStateException("simulated");
                    }
                    return true;
                };

        sweeper(flaky).sweep();

        assertEquals(List.of(Path.of("good.xml")), queue.submitted);
    }

    @Test
    @DisplayName("requestSweep coalesces: an OVERFLOW storm does not queue redundant tree walks")
    void requestSweepCoalesces() throws Exception {
        createSourceFile("a.xml");
        final SweepService sweeper = sweeper(ALWAYS);
        try {
            for (int i = 0; i < 50; i++) {
                sweeper.requestSweep();
            }

            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && queue.submitted.isEmpty()) {
                Thread.sleep(5L);
            }
            Thread.sleep(100L);

            assertTrue(queue.submitted.size() >= 1, "at least one sweep should have run");
            assertTrue(
                    queue.submitted.size() < 50,
                    "50 requests must not become 50 tree walks over the producer's folder,"
                            + " actual submissions: " + queue.submitted.size());
        } finally {
            sweeper.stop();
        }
    }

    @Test
    @DisplayName("a disabled periodic sweep starts no timer")
    void disabledSweepStartsNothing() throws Exception {
        values.put(MirrorConfig.KEY_SWEEP_INTERVAL_MS, "0");
        createSourceFile("a.xml");

        final SweepService sweeper = sweeper(ALWAYS);
        try {
            sweeper.start();
            Thread.sleep(100L);

            assertTrue(queue.submitted.isEmpty(), "no periodic sweep should have run");
        } finally {
            sweeper.stop();
        }
    }
}
