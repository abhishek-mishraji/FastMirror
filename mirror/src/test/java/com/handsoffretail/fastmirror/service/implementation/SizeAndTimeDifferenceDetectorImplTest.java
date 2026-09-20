package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.DifferenceDetector;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.PathMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SizeAndTimeDifferenceDetectorImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path logFolder;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
    private static final FileTime MTIME = FileTime.from(Instant.parse("2026-09-20T09:00:00Z"));
    private static final FileTime LATER = FileTime.from(Instant.parse("2026-09-20T10:00:00Z"));

    private AppLogger logger;
    private PathMapper mapper;
    private FileCopier copier;

    @BeforeEach
    void setUp() {
        logger = new AppLogger(logFolder, CLOCK);
        mapper = new RelativePathMapperImpl(sourceRoot, mirrorRoot);

        final Map<String, String> values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
        final MirrorConfig config = MirrorConfig.load(values::get, logFolder);

        copier = new AtomicFileCopierImpl(config, mapper, millis -> {}, logger, CLOCK);
    }

    @AfterEach
    void tearDown() {
        logger.close();
    }

    private DifferenceDetector detector(boolean contentVerify) {
        return new SizeAndTimeDifferenceDetectorImpl(mapper, copier, contentVerify);
    }

    private void writeBoth(String content, FileTime sourceTime, FileTime targetTime)
            throws IOException {
        writeSource(content, sourceTime);
        writeTarget(content, targetTime);
    }

    private void writeSource(String content, FileTime time) throws IOException {
        final Path file = sourceRoot.resolve("f.xml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, time);
    }

    private void writeTarget(String content, FileTime time) throws IOException {
        final Path file = mirrorRoot.resolve("f.xml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, time);
    }

    @Test
    @DisplayName("UT-03: a missing target needs a copy")
    void ut03_missingTarget() throws Exception {
        writeSource("payload", MTIME);

        assertTrue(detector(false).needsCopy(Path.of("f.xml")));
    }

    @Test
    @DisplayName("UT-04: identical size and timestamp needs no copy")
    void ut04_identical() throws Exception {
        writeBoth("payload", MTIME, MTIME);

        assertFalse(detector(false).needsCopy(Path.of("f.xml")));
    }

    @Test
    @DisplayName("UT-05: same size but a different timestamp needs a copy")
    void ut05_differentTimestamp() throws Exception {
        writeBoth("payload", LATER, MTIME);

        assertTrue(detector(false).needsCopy(Path.of("f.xml")));
    }

    @Test
    @DisplayName("UT-06: a different size needs a copy")
    void ut06_differentSize() throws Exception {
        writeSource("a much longer payload", MTIME);
        writeTarget("short", MTIME);

        assertTrue(detector(false).needsCopy(Path.of("f.xml")));
    }

    @Test
    @DisplayName("UT-07: CONTENT_VERIFY catches a rewrite with the same size and timestamp")
    void ut07_contentVerifyCatchesSilentRewrite() throws Exception {
        writeSource("AAAAAAAA", MTIME);
        writeTarget("BBBBBBBB", MTIME);

        assertFalse(
                detector(false).needsCopy(Path.of("f.xml")),
                "without CONTENT_VERIFY this rewrite is invisible, which is why the option exists");
        assertTrue(detector(true).needsCopy(Path.of("f.xml")));
    }

    @Test
    @DisplayName("CONTENT_VERIFY does not re-copy genuinely identical files")
    void contentVerifyAcceptsIdenticalFiles() throws Exception {
        writeBoth("identical payload", MTIME, MTIME);

        assertFalse(detector(true).needsCopy(Path.of("f.xml")));
    }

    @Test
    @DisplayName("a vanished source needs no copy: there is nothing left to capture")
    void vanishedSourceNeedsNoCopy() {
        assertFalse(detector(false).needsCopy(Path.of("ghost.xml")));
    }

    @Test
    @DisplayName("a directory is never treated as a copyable file")
    void directoryIsNotCopyable() throws Exception {
        Files.createDirectories(sourceRoot.resolve("subdir"));

        assertFalse(detector(false).needsCopy(Path.of("subdir")));
    }

    @Test
    @DisplayName("a partial target is shorter than its source, so it re-queues itself")
    void partialTargetConverges() throws Exception {
        writeSource("the complete payload", MTIME);
        writeTarget("the complete", MTIME);

        assertTrue(
                detector(false).needsCopy(Path.of("f.xml")),
                "a published partial must be picked up again by size alone");
    }

    @Test
    @DisplayName("after a real copy the detector reports no further work, closing the loop")
    void afterCopyNoFurtherWorkIsNeeded() throws Exception {
        writeSource("payload for the round trip", MTIME);

        final CopyResult result = copier.copy(Path.of("f.xml"), System.nanoTime(), true);

        assertTrue(result.outcome().wroteData());
        assertFalse(
                detector(false).needsCopy(Path.of("f.xml")),
                "FR-09 stamping the target's mtime is what makes the timestamp check meaningful");
    }
}
