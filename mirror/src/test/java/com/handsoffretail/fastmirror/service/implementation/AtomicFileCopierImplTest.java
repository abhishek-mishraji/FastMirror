package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyResult;
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
        mapper = new RelativePathMapperImpl(sourceRoot, mirrorRoot);
        sleeper = new RecordingSleeper();
        values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
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

        final CopyResult result = copier().copy(Path.of("PJR9.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.COPIED, result.outcome());
        assertEquals(4096L, result.bytesCopied());
        assertArrayEquals(content, Files.readAllBytes(mirrorRoot.resolve("PJR9.xml")));
    }

    @Test
    @DisplayName("an existing target is reported as REPLACED")
    void replacesExistingTarget() throws Exception {
        Files.write(mirrorRoot.resolve("a.xml"), "old".getBytes(StandardCharsets.UTF_8));
        writeSource("a.xml", "new content".getBytes(StandardCharsets.UTF_8));

        final CopyResult result = copier().copy(Path.of("a.xml"), System.nanoTime(), false);

        assertEquals(CopyOutcome.REPLACED, result.outcome());
        assertEquals(
                "new content",
                Files.readString(mirrorRoot.resolve("a.xml"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("nested structure is preserved and parent directories are created")
    void preservesNestedStructure() throws Exception {
        writeSource("s1/s2/r.xml", bytes(128));

        final CopyResult result = copier().copy(Path.of("s1", "s2", "r.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.COPIED, result.outcome());
        assertTrue(Files.isRegularFile(mirrorRoot.resolve("s1").resolve("s2").resolve("r.xml")));
    }

    @Test
    @DisplayName("UT-15: the target's last-modified time equals the source's")
    void ut15_stampsSourceTimestamp() throws Exception {
        final Path source = writeSource("t.xml", bytes(64));
        final FileTime expected = FileTime.from(Instant.parse("2026-09-19T08:30:15Z"));
        Files.setLastModifiedTime(source, expected);

        copier().copy(Path.of("t.xml"), System.nanoTime(), true);

        assertEquals(expected, Files.getLastModifiedTime(mirrorRoot.resolve("t.xml")));
    }

    @Test
    @DisplayName("UT-16: no staging file is left behind after a successful copy")
    void ut16_noStagingAfterSuccess() throws Exception {
        writeSource("clean.xml", bytes(2048));

        copier().copy(Path.of("clean.xml"), System.nanoTime(), true);

        assertEquals(List.of("clean.xml"), mirrorNames());
    }

    @Test
    @DisplayName("a file larger than MAX_COPY_BYTES is skipped and nothing is written")
    void skipsOversizedFile() throws Exception {
        values.put(MirrorConfig.KEY_MAX_COPY_BYTES, "100");
        writeSource("big.xml", bytes(101));

        final CopyResult result = copier().copy(Path.of("big.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.SKIPPED_TOO_LARGE, result.outcome());
        assertTrue(mirrorNames().isEmpty(), "nothing should be published, not even a staging file");
    }

    // ------------------------------------------------------------------ vanished and locked

    @Test
    @DisplayName("FR-11: a source that is already gone is VANISHED, with no retry")
    void vanishedSourceIsNotRetried() {
        final CopyResult result = copier().copy(Path.of("ghost.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.VANISHED, result.outcome());
        assertTrue(result.wasOpened() == false || result.captureLatencyMs() >= 0L);
        assertTrue(sleeper.delays.isEmpty(), "NI-11: a vanished file must never be retried");
    }

    @Test
    @DisplayName("FR-10: an unopenable source is retried with doubling backoff, then LOCKED")
    void lockedSourceRetriesWithBackoff() throws Exception {
        writeSource("locked.xml", bytes(16));
        values.put(MirrorConfig.KEY_OPEN_RETRY_ATTEMPTS, "3");
        values.put(MirrorConfig.KEY_OPEN_RETRY_INITIAL_MS, "5");

        final CopyResult result =
                copierWith(
                                source -> {
                                    throw new java.nio.file.AccessDeniedException(source.toString());
                                })
                        .copy(Path.of("locked.xml"), System.nanoTime(), true);

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
                        .copy(Path.of("flaky.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.COPIED, result.outcome());
        assertArrayEquals(content, Files.readAllBytes(mirrorRoot.resolve("flaky.xml")));
    }

    // ------------------------------------------------------------------ UT-17 / UT-18 partial

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
    @DisplayName("UT-17: a read that fails part-way publishes exactly the bytes captured")
    void ut17_publishesCapturedPrefix() throws Exception {
        final byte[] content = bytes(10_000);
        writeSource("partial.xml", content);

        final CopyResult result =
                copierWith(source -> new FailingChannel(content, 3_000))
                        .copy(Path.of("partial.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.PARTIAL, result.outcome());
        assertEquals(3_000L, result.bytesCopied());
        assertEquals(10_000L, result.expectedBytes());

        final byte[] published = Files.readAllBytes(mirrorRoot.resolve("partial.xml"));
        assertEquals(3_000, published.length);
        assertArrayEquals(java.util.Arrays.copyOf(content, 3_000), published);
    }

    @Test
    @DisplayName("UT-17b: the partial is published atomically, leaving no staging file")
    void ut17b_partialLeavesNoStaging() throws Exception {
        final byte[] content = bytes(8_192);
        writeSource("partial2.xml", content);

        copierWith(source -> new FailingChannel(content, 1_000))
                .copy(Path.of("partial2.xml"), System.nanoTime(), true);

        assertEquals(List.of("partial2.xml"), mirrorNames());
    }

    @Test
    @DisplayName("UT-18: a read that fails with nothing captured publishes nothing")
    void ut18_zeroBytesPublishesNothing() throws Exception {
        final byte[] content = bytes(5_000);
        writeSource("nothing.xml", content);

        final CopyResult result =
                copierWith(source -> new FailingChannel(content, 0))
                        .copy(Path.of("nothing.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.FAILED, result.outcome());
        assertEquals(0L, result.bytesCopied());
        assertTrue(mirrorNames().isEmpty(), "UT-16: no staging file may be left behind on failure");
    }

    @Test
    @DisplayName("UT-18b: a zero-byte failure never overwrites a good existing target")
    void ut18b_doesNotDestroyGoodTarget() throws Exception {
        Files.write(mirrorRoot.resolve("keep.xml"), "the good copy".getBytes(StandardCharsets.UTF_8));
        final byte[] content = bytes(5_000);
        writeSource("keep.xml", content);

        copierWith(source -> new FailingChannel(content, 0))
                .copy(Path.of("keep.xml"), System.nanoTime(), false);

        assertEquals(
                "the good copy",
                Files.readString(mirrorRoot.resolve("keep.xml"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("PUBLISH_PARTIAL=false restores the stricter discard behaviour")
    void publishPartialFalseDiscards() throws Exception {
        values.put(MirrorConfig.KEY_PUBLISH_PARTIAL, "false");
        final byte[] content = bytes(4_000);
        writeSource("discard.xml", content);

        final CopyResult result =
                copierWith(source -> new FailingChannel(content, 1_500))
                        .copy(Path.of("discard.xml"), System.nanoTime(), true);

        assertEquals(CopyOutcome.FAILED, result.outcome());
        assertTrue(mirrorNames().isEmpty(), "nothing should be published when partials are disabled");
    }

    // ------------------------------------------------------------------ CONTENT_VERIFY

    @Test
    @DisplayName("contentsDiffer compares the actual bytes")
    void contentsDifferComparesBytes() throws Exception {
        writeSource("h.xml", "identical".getBytes(StandardCharsets.UTF_8));
        Files.write(mirrorRoot.resolve("h.xml"), "identical".getBytes(StandardCharsets.UTF_8));

        assertFalse(copier().contentsDiffer(Path.of("h.xml")));

        Files.write(mirrorRoot.resolve("h.xml"), "different".getBytes(StandardCharsets.UTF_8));

        assertTrue(copier().contentsDiffer(Path.of("h.xml")));
    }

    @Test
    @DisplayName("contentsDiffer treats an unreadable side as different, so the copy is attempted")
    void contentsDifferOnMissingTarget() throws Exception {
        writeSource("only-source.xml", bytes(10));

        assertTrue(copier().contentsDiffer(Path.of("only-source.xml")));
    }
}
