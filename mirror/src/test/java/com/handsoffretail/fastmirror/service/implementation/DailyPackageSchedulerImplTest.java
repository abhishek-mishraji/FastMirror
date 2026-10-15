package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.PackageConfig;
import com.handsoffretail.fastmirror.entity.PackageManifest;
import com.handsoffretail.fastmirror.entity.PackageRecord;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.PackageScheduler;
import com.handsoffretail.fastmirror.support.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyPackageSchedulerImplTest {

    private static final String STORE = "wolf_800_NY-13208";
    private static final LocalDate OCT_15 = LocalDate.of(2026, 10, 15);

    @TempDir Path mirror;
    @TempDir Path outputs;
    @TempDir Path state;
    @TempDir Path logs;

    private MutableClock clock;
    private AppLogger logger;
    private final List<PackageRecord> packaged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-16T07:59:59Z"), ZoneOffset.UTC);
        logger = new AppLogger(logs, clock);
    }

    @AfterEach
    void tearDown() {
        logger.close();
    }

    private PackageConfig config(LocalDate from) throws IOException {
        return new PackageConfig(
                STORE,
                Files.createDirectories(outputs.resolve("outbox")),
                Files.createDirectories(outputs.resolve("outbox-staging")),
                LocalTime.of(8, 0),
                from,
                0);
    }

    private DailyPackageSchedulerImpl scheduler() throws IOException {
        return scheduler(null);
    }

    /** A fresh scheduler over the same mirror, outbox and state, as after a restart. */
    private DailyPackageSchedulerImpl scheduler(LocalDate from) throws IOException {
        final PackageConfig config = config(from);
        return new DailyPackageSchedulerImpl(
                new ZipDayPackagerImpl(mirror, config),
                FilePackageLedgerImpl.open(state, logger),
                config,
                clock,
                logger,
                packaged::add);
    }

    private Path put(LocalDate day, String type, String name) throws IOException {
        final Path file =
                Files.createDirectories(new TypeFolder(day, type).resolveIn(mirror)).resolve(name);
        Files.writeString(file, name + " content");
        return file;
    }

    private List<String> zips() throws IOException {
        try (Stream<Path> walk = Files.walk(outputs.resolve("outbox"))) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private void at(String instant) {
        clock.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    @Test
    @DisplayName("15 Oct is not zipped at 16 Oct 07:59:59Z, and is at 08:00Z, one zip per type")
    void dueAtEightUtcNextDay() throws IOException {
        put(OCT_15, "PJR", "PJR1.xml");
        put(OCT_15, "PJR", "PJR2.xml");
        put(OCT_15, "MSM", "MSM1.xml");
        final DailyPackageSchedulerImpl scheduler = scheduler();

        assertEquals(0, scheduler.checkNow());
        assertEquals(List.of(), zips());

        at("2026-10-16T08:00:00Z");
        assertEquals(2, scheduler.checkNow());
        assertEquals(
                List.of("wolf_800_NY-13208_MSM_2026-10-15_v1.zip", "wolf_800_NY-13208_PJR_2026-10-15_v1.zip"),
                zips());
        assertEquals(2, packaged.size());
        assertEquals(
                Optional.of(new PackageScheduler.LastPackaged(OCT_15, 2)), scheduler.lastPackaged());
    }

    @Test
    @DisplayName("today is never zipped")
    void neverToday() throws IOException {
        put(LocalDate.of(2026, 10, 16), "PJR", "PJR1.xml");
        at("2026-10-16T23:59:59Z");

        assertEquals(0, scheduler().checkNow());
        assertEquals(List.of(), zips());
    }

    @Test
    @DisplayName("three days off: all three are caught up at the next start")
    void catchesUp() throws IOException {
        put(LocalDate.of(2026, 10, 12), "PJR", "a.xml");
        put(LocalDate.of(2026, 10, 13), "PJR", "b.xml");
        put(LocalDate.of(2026, 10, 14), "PJR", "c.xml");
        at("2026-10-15T09:00:00Z");

        assertEquals(3, scheduler().checkNow());
        assertEquals(
                List.of(
                        "wolf_800_NY-13208_PJR_2026-10-12_v1.zip",
                        "wolf_800_NY-13208_PJR_2026-10-13_v1.zip",
                        "wolf_800_NY-13208_PJR_2026-10-14_v1.zip"),
                zips());
    }

    @Test
    @DisplayName("PACKAGE_FROM_DATE: older days are never zipped")
    void fromDate() throws IOException {
        put(LocalDate.of(2026, 10, 12), "PJR", "a.xml");
        put(LocalDate.of(2026, 10, 13), "PJR", "b.xml");
        at("2026-10-15T09:00:00Z");

        assertEquals(1, scheduler(LocalDate.of(2026, 10, 13)).checkNow());
        assertEquals(List.of("wolf_800_NY-13208_PJR_2026-10-13_v1.zip"), zips());
    }

    @Test
    @DisplayName("a late file makes v2 with all the folder's files; no change makes nothing")
    void lateFileMakesV2() throws IOException {
        put(OCT_15, "PJR", "PJR1.xml");
        at("2026-10-16T08:00:00Z");
        final DailyPackageSchedulerImpl scheduler = scheduler();
        assertEquals(1, scheduler.checkNow());
        assertEquals(0, scheduler.checkNow(), "no change, no new version");

        put(OCT_15, "PJR", "PJR2.xml");
        clock.advance(Duration.ofMinutes(1));
        assertEquals(1, scheduler.checkNow());
        assertEquals(0, scheduler.checkNow());

        assertEquals(
                List.of("wolf_800_NY-13208_PJR_2026-10-15_v1.zip", "wolf_800_NY-13208_PJR_2026-10-15_v2.zip"),
                zips());
        final ZipDayPackagerImpl packager = new ZipDayPackagerImpl(mirror, config(null));
        final PackageManifest v2 = packager.verify(packager.outboxZip(new TypeFolder(OCT_15, "PJR"), 2)).orElseThrow();
        assertEquals(2, v2.files().size(), "v2 holds every file, not only the late one");
        assertEquals(2, v2.version());
    }

    @Test
    @DisplayName("a restart zips nothing twice")
    void restart() throws IOException {
        put(OCT_15, "PJR", "PJR1.xml");
        put(OCT_15, "PassportReports", "StoreClose20261016001017.pdf");
        at("2026-10-16T08:00:00Z");
        assertEquals(2, scheduler().checkNow());

        assertEquals(0, scheduler().checkNow());
        assertEquals(2, zips().size());
    }

    @Test
    @DisplayName("a zip moved into the outbox just before a crash is recorded, not rebuilt")
    void recoversAfterCrash() throws IOException {
        put(OCT_15, "PJR", "PJR1.xml");
        final TypeFolder pjr = new TypeFolder(OCT_15, "PJR");
        final ZipDayPackagerImpl packager = new ZipDayPackagerImpl(mirror, config(null));
        // Built and moved, but the ledger line was never written.
        packager.build(packager.list(pjr), 1, Instant.parse("2026-10-16T08:00:04Z"));
        final Path zip = packager.outboxZip(pjr, 1);
        final byte[] before = Files.readAllBytes(zip);
        final FileTime modified = Files.getLastModifiedTime(zip);
        at("2026-10-16T08:05:00Z");

        final DailyPackageSchedulerImpl scheduler = scheduler();
        assertEquals(0, scheduler.checkNow());

        assertEquals(List.of("wolf_800_NY-13208_PJR_2026-10-15_v1.zip"), zips());
        assertArrayEquals(before, Files.readAllBytes(zip));
        assertEquals(modified, Files.getLastModifiedTime(zip));
        assertEquals(
                Instant.parse("2026-10-16T08:00:04Z"),
                FilePackageLedgerImpl.open(state, logger).latest(pjr).orElseThrow().createdAt());
    }

    @Test
    @DisplayName("a folder that lost files is never zipped incomplete")
    void lostFiles() throws IOException {
        put(OCT_15, "PJR", "PJR1.xml");
        final Path gone = put(OCT_15, "PJR", "PJR2.xml");
        at("2026-10-16T08:00:00Z");
        final DailyPackageSchedulerImpl scheduler = scheduler();
        assertEquals(1, scheduler.checkNow());

        Files.delete(gone);
        put(OCT_15, "PJR", "PJR3.xml");
        assertEquals(0, scheduler.checkNow());
        assertEquals(0, scheduler.checkNow());

        assertEquals(List.of("wolf_800_NY-13208_PJR_2026-10-15_v1.zip"), zips());
        logger.close();
        final List<String> errors =
                Files.readAllLines(logs.resolve("fastmirror-2026-10-16.log"), StandardCharsets.UTF_8).stream()
                        .filter(line -> line.contains("[ERROR]"))
                        .toList();
        assertEquals(1, errors.size(), "reported once, not every minute: " + errors);
        assertTrue(errors.get(0).contains("no longer in the mirror"), errors.get(0));
    }

    @Test
    @DisplayName("OUTBOX_KEEP_DAYS removes older days' zips from the outbox, once a day, and never rebuilds them")
    void keepDays() throws IOException {
        put(LocalDate.of(2026, 10, 1), "PJR", "old.xml");
        put(OCT_15, "PJR", "new.xml");
        at("2026-10-16T08:00:00Z");
        final PackageConfig keep7 =
                new PackageConfig(
                        STORE,
                        Files.createDirectories(outputs.resolve("outbox")),
                        Files.createDirectories(outputs.resolve("outbox-staging")),
                        LocalTime.of(8, 0),
                        null,
                        7);
        Files.writeString(outputs.resolve("outbox").resolve("not-ours.txt"), "left alone");
        final DailyPackageSchedulerImpl scheduler =
                new DailyPackageSchedulerImpl(
                        new ZipDayPackagerImpl(mirror, keep7),
                        FilePackageLedgerImpl.open(state, logger),
                        keep7,
                        clock,
                        logger,
                        packaged::add);
        assertEquals(2, scheduler.checkNow());

        clock.advance(Duration.ofDays(1));
        assertEquals(0, scheduler.checkNow());

        assertEquals(List.of("not-ours.txt", "wolf_800_NY-13208_PJR_2026-10-15_v1.zip"), zips());
        assertFalse(
                Files.exists(outputs.resolve("outbox").resolve(STORE).resolve("2026").resolve("october").resolve("2026-10-01")),
                "the emptied day folder is removed too");
    }

    @Test
    @DisplayName("nothing is packaged until copying has caught up after start-up")
    void waitsForCopying() throws IOException {
        put(OCT_15, "PJR", "PJR1.xml");
        at("2026-10-16T09:00:00Z");
        final boolean[] idle = {false};
        final PackageConfig config = config(null);
        final DailyPackageSchedulerImpl scheduler =
                new DailyPackageSchedulerImpl(
                        new ZipDayPackagerImpl(mirror, config),
                        FilePackageLedgerImpl.open(state, logger),
                        config,
                        clock,
                        logger,
                        packaged::add,
                        Instant.parse("2026-10-16T09:05:00Z"),
                        () -> idle[0]);

        idle[0] = true;
        assertEquals(0, scheduler.checkNow(), "before the stability wait and two scans");
        at("2026-10-16T09:05:00Z");
        idle[0] = false;
        assertEquals(0, scheduler.checkNow(), "while the copier is still busy");
        idle[0] = true;
        assertEquals(1, scheduler.checkNow());
    }

    @Test
    @DisplayName("an empty type folder makes no zip")
    void emptyFolder() throws IOException {
        Files.createDirectories(new TypeFolder(OCT_15, "PJR").resolveIn(mirror));
        at("2026-10-16T08:00:00Z");

        assertEquals(0, scheduler().checkNow());
        assertFalse(scheduler().lastPackaged().isPresent());
    }

    @Test
    @DisplayName("start clears interrupted builds and runs a first check at once")
    void startChecksAtOnce() throws Exception {
        put(OCT_15, "PJR", "PJR1.xml");
        Files.writeString(
                Files.createDirectories(outputs.resolve("outbox-staging")).resolve("x.zip.part"), "half");
        at("2026-10-16T08:00:00Z");

        try (DailyPackageSchedulerImpl scheduler = scheduler()) {
            scheduler.start();
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (zips().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        assertEquals(List.of("wolf_800_NY-13208_PJR_2026-10-15_v1.zip"), zips());
        try (Stream<Path> staging = Files.list(outputs.resolve("outbox-staging"))) {
            assertEquals(0L, staging.count());
        }
    }
}
