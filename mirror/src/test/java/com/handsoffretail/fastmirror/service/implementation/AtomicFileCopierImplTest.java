package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.PathMapper;
import com.handsoffretail.fastmirror.service.Sleeper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileCopierImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    private AppLogger logger;
    private PathMapper mapper;
    private Map<String, String> values;

    /** Records the delays asked for, so backoff is asserted without any real sleeping (AR-9). */
    private static final class RecordingSleeper implements Sleeper {
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            delays.add(millis);
        }
    }

    private RecordingSleeper sleeper;

    @BeforeEach
    void setUp() {
        logger = new AppLogger(logFolder, CLOCK);
        mapper = new GroupedPathMapperImpl(sourceRoot, mirrorRoot);
        sleeper = new RecordingSleeper();
        values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, "CPJ");
    }

    @AfterEach
    void tearDown() {
        logger.close();
    }

    private MirrorConfig config() {
        return MirrorConfig.load(values::get, logFolder);
    }

    private FileCopier copier() {
        return new AtomicFileCopierImpl(config(), mapper, sleeper, logger, CLOCK);
    }

    private FileCopier copierWith(AtomicFileCopierImpl.SourceOpener opener) {
        return new AtomicFileCopierImpl(config(), mapper, sleeper, logger, CLOCK, opener);
    }

    private Path writeSource(String relative, byte[] content) throws IOException {
        final Path file = sourceRoot.resolve(relative);
        Files.createDirectories(file.getParent() == null ? sourceRoot : file.getParent());
        Files.write(file, content);
        return file;
    }

    private static byte[] bytes(int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    /** A job whose target has the same relative path as its source, to keep assertions simple. */
    private static CopyJob job(Path relative) {
        return new CopyJob(relative, relative, LedgerKey.of(relative, 0L, 0L));
    }

    private static CopyJob job(String relative) {
        return job(Path.of(relative));
    }

    private List<String> mirrorNames() throws IOException {
        final List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mirrorRoot)) {
            for (final Path entry : stream) {
                names.add(entry.getFileName().toString());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ happy paths

    @Test
    @DisplayName("a new file is copied byte-for-byte and reported as COPIED")
    void copiesNewFile() throws Exception {
        final byte[] content = bytes(4096);
        writeSource("PJR9.xml", content);

        final CopyResult result = copier().copy(job(Path.of("PJR9.xml")));

        assertEquals(CopyOutcome.COPIED, result.outcome());
        assertEquals(4096L, result.bytesCopied());
        assertArrayEquals(content, Files.readAllBytes(mirrorRoot.resolve("PJR9.xml")));
    }

    @Test
    @DisplayName("an existing target is reported as REPLACED")
    void replacesExistingTarget() throws Exception {
        Files.write(mirrorRoot.resolve("a.xml"), "old".getBytes(StandardCharsets.UTF_8));
        writeSource("a.xml", "new content".getBytes(StandardCharsets.UTF_8));

        final CopyResult result = copier().copy(job(Path.of("a.xml")));

        assertEquals(CopyOutcome.REPLACED, result.outcome());
        assertEquals(
                "new content",
                Files.readString(mirrorRoot.resolve("a.xml"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("nested structure is preserved and parent directories are created")
    void preservesNestedStructure() throws Exception {
        writeSource("s1/s2/r.xml", bytes(128));

        final CopyResult result = copier().copy(job(Path.of("s1", "s2", "r.xml")));

        assertEquals(CopyOutcome.COPIED, result.outcome());
        assertTrue(Files.isRegularFile(mirrorRoot.resolve("s1").resolve("s2").resolve("r.xml")));
    }

    @Test
    @DisplayName("the target's last-modified time equals the source's")
    void ut15_stampsSourceTimestamp() throws Exception {
        final Path source = writeSource("t.xml", bytes(64));
        final FileTime expected = FileTime.from(Instant.parse("2026-09-19T08:30:15Z"));
        Files.setLastModifiedTime(source, expected);

        copier().copy(job(Path.of("t.xml")));

        assertEquals(expected, Files.getLastModifiedTime(mirrorRoot.resolve("t.xml")));
    }

    @Test
    @DisplayName("no staging file is left behind after a successful copy")
    void ut16_noStagingAfterSuccess() throws Exception {
        writeSource("clean.xml", bytes(2048));

        copier().copy(job(Path.of("clean.xml")));

        assertEquals(List.of("clean.xml"), mirrorNames());
    }

    @Test
    @DisplayName("a file larger than MAX_COPY_BYTES is skipped and nothing is written")
    void skipsOversizedFile() throws Exception {
        values.put(MirrorConfig.KEY_MAX_COPY_BYTES, "100");
        writeSource("big.xml", bytes(101));

        final CopyResult result = copier().copy(job(Path.of("big.xml")));

        assertEquals(CopyOutcome.SKIPPED_TOO_LARGE, result.outcome());
        assertTrue(mirrorNames().isEmpty(), "nothing should be published, not even a staging file");
    }

    // ------------------------------------------------------------------ vanished and locked

    @Test
    @DisplayName("a source that is already gone is VANISHED, with no retry")
    void vanishedSourceIsNotRetried() {
        final CopyResult result = copier().copy(job(Path.of("ghost.xml")));

        assertEquals(CopyOutcome.VANISHED, result.outcome());
        assertTrue(sleeper.delays.isEmpty(), "a vanished file must never be retried");
    }

    @Test
    @DisplayName("an unopenable source is retried with doubling backoff, then LOCKED")
    void lockedSourceRetriesWithBackoff() throws Exception {
        writeSource("locked.xml", bytes(16));
        values.put(MirrorConfig.KEY_OPEN_RETRY_ATTEMPTS, "3");
        values.put(MirrorConfig.KEY_OPEN_RETRY_INITIAL_MS, "5");

        final CopyResult result =
                copierWith(
                                source -> {
                                    throw new java.nio.file.AccessDeniedException(source.toString());
                                })
                        .copy(job(Path.of("locked.xml")));

        assertEquals(CopyOutcome.LOCKED, result.outcome());
        assertEquals(List.of(5L, 10L, 20L), sleeper.delays, "backoff should double per attempt");
    }

    @Test
    @DisplayName("a source that unlocks on the third attempt is copied, not reported LOCKED")
    void retriesUntilTheSourceOpens() throws Exception {
        final byte[] content = bytes(32);
        writeSource("flaky.xml", content);
        final int[] attempts = {0};

        final CopyResult result =
                copierWith(
                                source -> {
                                    if (attempts[0]++ < 2) {
                                        throw new java.nio.file.AccessDeniedException(source.toString());
                                    }
                                    return Files.newByteChannel(source, java.nio.file.StandardOpenOption.READ);
                                })
                        .copy(job(Path.of("flaky.xml")));

        assertEquals(CopyOutcome.COPIED, result.outcome());
        assertArrayEquals(content, Files.readAllBytes(mirrorRoot.resolve("flaky.xml")));
    }

    // ------------------------------------------------------------------ failed reads

    /** A channel that serves {@code failAfter} bytes and then fails, simulating a mid-read fault. */
    private static final class FailingChannel implements SeekableByteChannel {
        private final byte[] data;
        private final int failAfter;
        private int position;
        private boolean open = true;

        FailingChannel(byte[] data, int failAfter) {
            this.data = data;
            this.failAfter = failAfter;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            if (position >= failAfter) {
                throw new IOException("simulated read failure at byte " + position);
            }
            final int count = Math.min(destination.remaining(), failAfter - position);
            destination.put(data, position, count);
            position += count;
            return count;
        }

        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            this.position = (int) newPosition;
            return this;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    @Test
    @DisplayName("a read that fails part-way publishes nothing and reports FAILED")
    void failedReadPublishesNothing() throws Exception {
        final byte[] content = bytes(10_000);
        writeSource("partial.xml", content);

        final CopyResult result =
                copierWith(source -> new FailingChannel(content, 3_000)).copy(job("partial.xml"));

        assertEquals(CopyOutcome.FAILED, result.outcome());
        assertEquals(0L, result.bytesCopied());
        assertTrue(
                mirrorNames().isEmpty(),
                "a truncated file must never be published, and no staging file may be left");
    }

    @Test
    @DisplayName("a read that fails with nothing captured publishes nothing")
    void zeroBytesPublishesNothing() throws Exception {
        final byte[] content = bytes(5_000);
        writeSource("nothing.xml", content);

        final CopyResult result =
                copierWith(source -> new FailingChannel(content, 0)).copy(job("nothing.xml"));

        assertEquals(CopyOutcome.FAILED, result.outcome());
        assertTrue(mirrorNames().isEmpty(), "no staging file may be left behind on failure");
    }

    @Test
    @DisplayName("a failed read never overwrites a good existing target")
    void failedReadDoesNotDestroyGoodTarget() throws Exception {
        Files.write(mirrorRoot.resolve("keep.xml"), "the good copy".getBytes(StandardCharsets.UTF_8));
        final byte[] content = bytes(5_000);
        writeSource("keep.xml", content);

        copierWith(source -> new FailingChannel(content, 2_000)).copy(job("keep.xml"));

        assertEquals(
                "the good copy",
                Files.readString(mirrorRoot.resolve("keep.xml"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the target goes to the job's grouped folder, which is created on demand")
    void copiesIntoGroupedFolder() throws Exception {
        final byte[] content = bytes(100);
        writeSource("CPJ432.xml", content);
        final Path target = Path.of("CPJ", "2026", "october", "2026-10-15", "CPJ432.xml");

        final CopyResult result =
                copier().copy(new CopyJob(Path.of("CPJ432.xml"), target, LedgerKey.of(Path.of("CPJ432.xml"), 1L, 100L)));

        assertEquals(CopyOutcome.COPIED, result.outcome());
        assertArrayEquals(content, Files.readAllBytes(mirrorRoot.resolve(target)));
    }

    // ------------------------------------------------------------------ concurrency and consumers

    @Test
    @DisplayName("concurrent copies of one path never destroy each other's staging file")
    void concurrentCopiesOfOnePathDoNotInterfere() throws Exception {
        // Regression test. A shared <target>.fmtmp staging name was correct only while no two
        // attempts for the same path ever overlapped. Running the real agent showed that failing:
        // a staging file vanished between being written and being timestamped, and the copy ended
        // in FAILED. Each attempt now owns a staging file no other attempt can name.
        final byte[] content = bytes(256 * 1024);
        writeSource("contended.xml", content);

        final FileCopier copier = copier();
        final int threads = 8;
        final int copiesPerThread = 12;
        final java.util.concurrent.CountDownLatch startLine = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        final List<CopyResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            final Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    startLine.await(10, java.util.concurrent.TimeUnit.SECONDS);
                                    for (int c = 0; c < copiesPerThread; c++) {
                                        results.add(
                                                copier.copy(job(Path.of("contended.xml"))));
                                    }
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    done.countDown();
                                }
                            });
            worker.setDaemon(true);
            worker.start();
        }

        startLine.countDown();
        assertTrue(done.await(60, java.util.concurrent.TimeUnit.SECONDS), "all copies should finish");

        assertEquals(threads * copiesPerThread, results.size());
        final List<String> failures =
                results.stream()
                        .filter(result -> result.outcome() == CopyOutcome.FAILED)
                        .map(result -> String.valueOf(result.cause()))
                        .toList();
        assertEquals(
                List.of(),
                failures,
                "no copy may fail through interference between attempts");
        assertArrayEquals(
                content,
                Files.readAllBytes(mirrorRoot.resolve("contended.xml")),
                "the target must be complete, never torn by an overlapping attempt");
        assertEquals(List.of("contended.xml"), mirrorNames(), "no staging file may be left behind");
    }

    @Test
    @DisplayName("a consumer holding the target open does not cost us the copy")
    void publishSurvivesAConsumerHoldingTheTarget() throws Exception {
        // The real scenario, not a hypothetical: in production the S3 upload agent reads files
        // out of the mirror folder to upload them, so it can have a target open at the exact moment
        // FastMirror tries to replace it. Windows fails that move with AccessDeniedException.
        // Losing a copy over a collision we fully expect would be a poor trade, so publish retries.
        final byte[] first = bytes(1024);
        final byte[] second = bytes(2048);
        writeSource("consumed.xml", first);
        final FileCopier copier = copier();
        assertEquals(CopyOutcome.COPIED, copier.copy(job(Path.of("consumed.xml"))).outcome());

        // A separate process opens the mirrored file with FileShare.None, as a consumer might.
        final Path target = mirrorRoot.resolve("consumed.xml");
        final String script =
                "$f=[System.IO.File]::Open('" + target.toString().replace("'", "''")
                        + "','Open','Read','None'); Start-Sleep -Milliseconds 400; $f.Close()";
        final Process holder =
                new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                        .redirectErrorStream(true)
                        .start();
        Thread.sleep(120L);

        writeSource("consumed.xml", second);
        final CopyResult result = copier.copy(job(Path.of("consumed.xml")));
        holder.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(
                CopyOutcome.REPLACED,
                result.outcome(),
                "the copy must survive the consumer's hold, cause: " + result.cause());
        assertArrayEquals(second, Files.readAllBytes(target));
        assertEquals(List.of("consumed.xml"), mirrorNames(), "no staging file left behind");
    }
}
