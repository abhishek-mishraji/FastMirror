package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.PackageConfig;
import com.handsoffretail.fastmirror.entity.FolderContent;
import com.handsoffretail.fastmirror.entity.PackageManifest;
import com.handsoffretail.fastmirror.entity.PackagedFile;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipDayPackagerImplTest {

    private static final String STORE = "wolf_800_NY-13208";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);
    private static final TypeFolder PJR = new TypeFolder(DAY, "PJR");
    private static final Instant CREATED = Instant.parse("2026-10-13T08:00:04Z");

    @TempDir Path mirror;
    @TempDir Path outputs;

    private ZipDayPackagerImpl packager;

    @BeforeEach
    void setUp() throws IOException {
        packager = packagerIn(outputs);
    }

    private ZipDayPackagerImpl packagerIn(Path folder) throws IOException {
        final Path outbox = Files.createDirectories(folder.resolve("outbox"));
        final Path staging = Files.createDirectories(folder.resolve("outbox-staging"));
        return new ZipDayPackagerImpl(
                mirror, new PackageConfig(STORE, outbox, staging, LocalTime.of(8, 0), null, 0));
    }

    private Path put(TypeFolder folder, String name, String content) throws IOException {
        return put(folder, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private Path put(TypeFolder folder, String name, byte[] content) throws IOException {
        final Path file = Files.createDirectories(folder.resolveIn(mirror)).resolve(name);
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-10-13T04:18:33Z")));
        return file;
    }

    /** Every entry of a zip, by name, with its bytes. */
    private static Map<String, byte[]> unzip(Path zip) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                entries.put(entry.getName(), bytes.toByteArray());
            }
        }
        return entries;
    }

    private static List<Path> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    @DisplayName("finds every <yyyy>/<month>/<date>/<TYPE> folder, oldest first, and nothing else")
    void findsTypeFolders() throws IOException {
        put(new TypeFolder(LocalDate.of(2026, 10, 13), "PJR"), "b.xml", "b");
        put(PJR, "a.xml", "a");
        put(new TypeFolder(DAY, "PassportReports"), "S.pdf", "pdf");
        Files.createDirectories(mirror.resolve("CPJ/2026/october/2026-10-12")); // an old layout
        Files.createDirectories(mirror.resolve("2026/november/2026-10-14/PJR")); // month disagrees
        Files.createDirectories(mirror.resolve("2026/october/notes/PJR"));
        Files.writeString(mirror.resolve("2026/october/2026-10-12/loose.txt"), "not in a type folder");

        assertEquals(
                List.of(
                        new TypeFolder(DAY, "PJR"),
                        new TypeFolder(DAY, "PassportReports"),
                        new TypeFolder(LocalDate.of(2026, 10, 13), "PJR")),
                packager.typeFolders());
    }

    @Test
    @DisplayName("a listing holds the folder's files with size and time, without the copier's .fmtmp")
    void listsFiles() throws IOException {
        put(PJR, "PJR2.xml", "two");
        put(PJR, "PJR1.xml", "one!");
        put(PJR, "PJR3.xml.1.fmtmp", "still being written");

        final FolderContent content = packager.list(PJR);

        assertEquals(
                List.of(
                        new PackagedFile("2026/october/2026-10-12/PJR/PJR1.xml", 4, Instant.parse("2026-10-13T04:18:33Z")),
                        new PackagedFile("2026/october/2026-10-12/PJR/PJR2.xml", 3, Instant.parse("2026-10-13T04:18:33Z"))),
                content.files());
    }

    @Test
    @DisplayName("the zip lands at <outbox>/<store>/<yyyy>/<month>/<date>/<TYPE>/ with exactly the folder's files")
    void buildsTheZip() throws IOException {
        put(PJR, "PJR1.xml", "<SaleEvent/>");
        put(PJR, "PJR2.xml", "<SaleEvent>two</SaleEvent>");

        final PackageManifest manifest = packager.build(packager.list(PJR), 1, CREATED);

        final Path zip =
                outputs.resolve("outbox/wolf_800_NY-13208/2026/october/2026-10-12/PJR/wolf_800_NY-13208_PJR_2026-10-12_v1.zip");
        assertEquals(zip, packager.outboxZip(PJR, 1));
        assertEquals(List.of(zip), filesUnder(outputs.resolve("outbox")));
        assertEquals(List.of(), filesUnder(outputs.resolve("outbox-staging")), "staging is left empty");

        final Map<String, byte[]> entries = unzip(zip);
        assertEquals(
                List.of(
                        "2026/october/2026-10-12/PJR/PJR1.xml",
                        "2026/october/2026-10-12/PJR/PJR2.xml",
                        "manifest.json"),
                List.copyOf(entries.keySet()));
        assertEquals("<SaleEvent/>", new String(entries.get("2026/october/2026-10-12/PJR/PJR1.xml"), StandardCharsets.UTF_8));

        final String json = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertEquals(manifest, PackageManifest.parse(json).orElseThrow());
        assertTrue(json.contains("\"store\": \"wolf_800_NY-13208\""), json);
        assertTrue(json.contains("\"type\": \"PJR\""), json);
        assertTrue(json.contains("\"businessDate\": \"2026-10-12\""), json);
        assertTrue(json.contains("\"version\": 1,"), json);
        assertTrue(json.contains("\"createdAt\": \"2026-10-13T08:00:04Z\""), json);
        assertTrue(json.contains("\"fileCount\": 2,"), json);
        assertTrue(
                json.contains("\"sha256\": \"" + sha256("<SaleEvent/>") + "\""),
                "each file's hash is its content's: " + json);
    }

    @Test
    @DisplayName("the same files give a byte-identical zip, whatever the PC's time zone")
    void deterministic() throws IOException {
        put(PJR, "PJR1.xml", "<SaleEvent/>");
        put(PJR, "PJR2.xml", "two");
        packager.build(packager.list(PJR), 1, CREATED);
        final byte[] first = Files.readAllBytes(packager.outboxZip(PJR, 1));

        final TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        try {
            final ZipDayPackagerImpl elsewhere = packagerIn(outputs.resolve("again"));
            elsewhere.build(elsewhere.list(PJR), 1, CREATED);

            assertArrayEquals(first, Files.readAllBytes(elsewhere.outboxZip(PJR, 1)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("the outbox never shows a zip that is not complete")
    void neverHalfVisible() throws Exception {
        final Random random = new Random(7);
        for (int i = 0; i < 40; i++) {
            final byte[] content = new byte[512 * 1024];
            random.nextBytes(content); // incompressible, so the zip takes a while to write
            put(PJR, "PJR" + i + ".xml", content);
        }
        final FolderContent content = packager.list(PJR);
        final Path zip = packager.outboxZip(PJR, 1);
        final ConcurrentLinkedQueue<Long> seen = new ConcurrentLinkedQueue<>();
        final AtomicBoolean done = new AtomicBoolean();
        final Thread watcher =
                new Thread(
                        () -> {
                            while (!done.get()) {
                                try {
                                    if (Files.exists(zip)) {
                                        seen.add(Files.size(zip));
                                    }
                                } catch (final IOException e) {
                                    // racing the move; try again
                                }
                            }
                        });
        watcher.start();
        try {
            packager.build(content, 1, CREATED);
        } finally {
            done.set(true);
            watcher.join();
        }

        final long size = Files.size(zip);
        assertTrue(seen.stream().allMatch(observed -> observed == size), "saw a partial zip: " + seen);
    }

    @Test
    @DisplayName("a zip found in the outbox is verified from its own manifest, without a rebuild")
    void verifiesAnExistingZip() throws IOException {
        put(PJR, "PJR1.xml", "<SaleEvent/>");
        final PackageManifest built = packager.build(packager.list(PJR), 1, CREATED);
        final Path zip = packager.outboxZip(PJR, 1);
        final byte[] before = Files.readAllBytes(zip);
        final FileTime modified = Files.getLastModifiedTime(zip);

        assertEquals(built, packager.verify(zip).orElseThrow());
        assertArrayEquals(before, Files.readAllBytes(zip));
        assertEquals(modified, Files.getLastModifiedTime(zip), "verifying never touches the zip");
    }

    @Test
    @DisplayName("a damaged zip, or one under the wrong name, does not verify")
    void rejectsDamagedZips() throws IOException {
        put(PJR, "PJR1.xml", "<SaleEvent/>");
        packager.build(packager.list(PJR), 1, CREATED);
        final Path zip = packager.outboxZip(PJR, 1);

        final Path truncated = outputs.resolve("v2").resolve(zip.getFileName());
        Files.createDirectories(truncated.getParent());
        final byte[] bytes = Files.readAllBytes(zip);
        Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertFalse(packager.verify(truncated).isPresent());

        final Path renamed = outputs.resolve("wolf_800_NY-13208_PJR_2026-10-12_v2.zip");
        Files.write(renamed, bytes);
        assertFalse(packager.verify(renamed).isPresent());
    }

    @Test
    @DisplayName("a file vanishing mid-build fails cleanly: nothing in the outbox, nothing in staging")
    void vanishingFile() throws IOException {
        put(PJR, "PJR1.xml", "one");
        final Path doomed = put(PJR, "PJR2.xml", "two");
        final FolderContent content = packager.list(PJR);
        Files.delete(doomed);

        assertThrows(IOException.class, () -> packager.build(content, 1, CREATED));

        assertEquals(List.of(), filesUnder(outputs.resolve("outbox")));
        assertEquals(List.of(), filesUnder(outputs.resolve("outbox-staging")));
    }

    @Test
    @DisplayName("a file replaced after the listing fails the build rather than mislabel the zip")
    void replacedFile() throws IOException {
        final Path file = put(PJR, "PJR1.xml", "one");
        final FolderContent content = packager.list(PJR);
        Files.writeString(file, "a longer replacement");

        final IOException thrown = assertThrows(IOException.class, () -> packager.build(content, 1, CREATED));

        assertTrue(thrown.getMessage().contains("Changed while being zipped"), thrown.getMessage());
        assertEquals(List.of(), filesUnder(outputs.resolve("outbox")));
    }

    @Test
    @DisplayName("a version already in the outbox is never overwritten")
    void neverOverwrites() throws IOException {
        put(PJR, "PJR1.xml", "one");
        packager.build(packager.list(PJR), 1, CREATED);
        final byte[] before = Files.readAllBytes(packager.outboxZip(PJR, 1));
        put(PJR, "PJR2.xml", "late");

        assertThrows(IllegalStateException.class, () -> packager.build(packager.list(PJR), 1, CREATED));
        assertArrayEquals(before, Files.readAllBytes(packager.outboxZip(PJR, 1)));
    }

    @Test
    @DisplayName("staging files left by an interrupted build are cleared")
    void clearsStaging() throws IOException {
        Files.writeString(outputs.resolve("outbox-staging/x_PJR_2026-10-12_v1.zip.part"), "half");
        Files.writeString(outputs.resolve("outbox-staging/keep.txt"), "not ours");

        packager.clearStaging();

        final List<String> left = new ArrayList<>();
        filesUnder(outputs.resolve("outbox-staging")).forEach(path -> left.add(path.getFileName().toString()));
        assertEquals(List.of("keep.txt"), left);
    }

    private static String sha256(String text) {
        return java.util.HexFormat.of()
                .formatHex(FolderContent.sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
