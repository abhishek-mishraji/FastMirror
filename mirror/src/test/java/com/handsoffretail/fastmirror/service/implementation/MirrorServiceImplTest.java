package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.FileEvent;
import com.handsoffretail.fastmirror.entity.FileEventKind;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyQueue;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MirrorServiceImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    private AppLogger logger;
    private RecordingQueue queue;
    private CountingSweep sweep;
    private MirrorServiceImpl service;
    private Map<String, String> values;

    private record Submission(Path relative, boolean createSeen) {}

    private static final class RecordingQueue implements CopyQueue {
        private final List<Submission> submissions = new CopyOnWriteArrayList<>();

        @Override
        public void submit(Path relative, long eventNanos, boolean followedCreateEvent) {
            submissions.add(new Submission(relative, followedCreateEvent));
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

    private static final class CountingSweep implements SweepService {
        private final AtomicInteger requests = new AtomicInteger();

        @Override
        public void sweep() {
            requests.incrementAndGet();
        }

        @Override
        public void requestSweep() {
            requests.incrementAndGet();
        }

        @Override
        public void start() {
            // nothing to do
        }

        @Override
        public void stop() {
            // nothing to do
        }
    }

    @BeforeEach
    void setUp() {
        logger = new AppLogger(logFolder, CLOCK);
        queue = new RecordingQueue();
        sweep = new CountingSweep();
        values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
        logger.close();
    }

    private void startService() {
        final MirrorConfig config = MirrorConfig.load(values::get, logFolder);
        service =
                new MirrorServiceImpl(
                        config,
                        new RelativePathMapperImpl(sourceRoot, mirrorRoot),
                        new GlobPathFilterImpl(config.excludePatterns()),
                        queue,
                        sweep,
                        logger);
        service.start();
    }

    /** Waits for the event thread to catch up without asserting anything about timing. */
    private static void await(BooleanSupplier condition, String description) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }

    /** Lets the event thread finish the events queued so far. */
    private void quiesce() throws Exception {
        await(() -> service.queued() == 0, "the event queue to drain");
        Thread.sleep(50L);
    }

    private Path createSourceFile(String name) throws IOException {
        final Path file = sourceRoot.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "payload");
        return file;
    }

    @Test
    @DisplayName("FR-02: a CREATE for a regular file is submitted, flagged as a create")
    void createIsSubmitted() throws Exception {
        startService();
        final Path file = createSourceFile("PJR9.xml");

        service.accept(new FileEvent(FileEventKind.CREATED, file, System.nanoTime()));
        await(() -> !queue.submissions.isEmpty(), "the file to be submitted");

        assertEquals(Path.of("PJR9.xml"), queue.submissions.get(0).relative());
        assertTrue(queue.submissions.get(0).createSeen(), "FR-31 depends on this flag");
    }

    @Test
    @DisplayName("FR-03: a MODIFY is submitted and is not flagged as a create")
    void modifyIsSubmitted() throws Exception {
        startService();
        final Path file = createSourceFile("PJR9.xml");

        service.accept(new FileEvent(FileEventKind.MODIFIED, file, System.nanoTime()));
        await(() -> !queue.submissions.isEmpty(), "the file to be submitted");

        assertFalse(queue.submissions.get(0).createSeen());
    }

    @Test
    @DisplayName("FR-04: a DELETE does nothing at all")
    void deleteIsIgnored() throws Exception {
        startService();
        final Path file = createSourceFile("gone.xml");

        service.accept(new FileEvent(FileEventKind.DELETED, file, System.nanoTime()));
        quiesce();

        assertTrue(queue.submissions.isEmpty(), "the mirror keeps what it has, always");
        assertEquals(0, sweep.requests.get());
    }

    @Test
    @DisplayName("FR-15: an OVERFLOW triggers a full sweep")
    void overflowTriggersSweep() throws Exception {
        startService();

        service.accept(FileEvent.overflow(System.nanoTime()));
        await(() -> sweep.requests.get() == 1, "a sweep to be requested");

        assertTrue(queue.submissions.isEmpty());
    }

    @Test
    @DisplayName("FR-18: an excluded file is never submitted, so it is never opened")
    void excludedFileIsIgnored() throws Exception {
        startService();
        final Path file = createSourceFile("draft.tmp");

        service.accept(new FileEvent(FileEventKind.CREATED, file, System.nanoTime()));
        quiesce();

        assertTrue(queue.submissions.isEmpty());
    }

    @Test
    @DisplayName("a directory event submits nothing; its files arrive as their own events")
    void directoryIsNotSubmitted() throws Exception {
        startService();
        final Path directory = Files.createDirectories(sourceRoot.resolve("store001"));

        service.accept(new FileEvent(FileEventKind.CREATED, directory, System.nanoTime()));
        quiesce();

        assertTrue(queue.submissions.isEmpty());
        assertFalse(
                Files.exists(mirrorRoot.resolve("store001")),
                "MIRROR_EMPTY_DIRECTORIES is false by default");
    }

    @Test
    @DisplayName("FR-25: with MIRROR_EMPTY_DIRECTORIES the directory is created in the mirror")
    void emptyDirectoryIsMirroredWhenEnabled() throws Exception {
        values.put(MirrorConfig.KEY_MIRROR_EMPTY_DIRECTORIES, "true");
        startService();
        final Path directory = Files.createDirectories(sourceRoot.resolve("store001"));

        service.accept(new FileEvent(FileEventKind.CREATED, directory, System.nanoTime()));
        await(() -> Files.isDirectory(mirrorRoot.resolve("store001")), "the mirror directory");

        assertTrue(queue.submissions.isEmpty());
    }

    @Test
    @DisplayName("an event for a file that has already vanished is dropped without a wasted open")
    void vanishedFileIsDropped() throws Exception {
        startService();

        service.accept(
                new FileEvent(FileEventKind.CREATED, sourceRoot.resolve("ghost.xml"), System.nanoTime()));
        quiesce();

        assertTrue(queue.submissions.isEmpty());
    }

    @Test
    @DisplayName("NFR-07: a bad event does not kill the event thread")
    void badEventDoesNotStopTheThread() throws Exception {
        startService();

        service.accept(
                new FileEvent(FileEventKind.CREATED, Path.of("C:", "elsewhere", "x.xml"), System.nanoTime()));
        quiesce();

        final Path good = createSourceFile("good.xml");
        service.accept(new FileEvent(FileEventKind.CREATED, good, System.nanoTime()));
        await(() -> !queue.submissions.isEmpty(), "the event thread to still be alive");

        assertEquals(Path.of("good.xml"), queue.submissions.get(0).relative());
    }

    @Test
    @DisplayName("nested files keep their relative path")
    void nestedFileKeepsRelativePath() throws Exception {
        startService();
        final Path file = createSourceFile("s1/s2/r.xml");

        service.accept(new FileEvent(FileEventKind.CREATED, file, System.nanoTime()));
        await(() -> !queue.submissions.isEmpty(), "the nested file to be submitted");

        assertEquals(Path.of("s1", "s2", "r.xml"), queue.submissions.get(0).relative());
    }

    @Test
    @DisplayName("events submitted after stop() are ignored")
    void ignoresEventsAfterStop() throws Exception {
        startService();
        final Path file = createSourceFile("late.xml");
        service.stop();

        service.accept(new FileEvent(FileEventKind.CREATED, file, System.nanoTime()));
        Thread.sleep(50L);

        assertTrue(queue.submissions.isEmpty());
    }
}
