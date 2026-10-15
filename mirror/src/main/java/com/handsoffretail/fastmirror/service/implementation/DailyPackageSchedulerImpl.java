package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.PackageConfig;
import com.handsoffretail.fastmirror.entity.FolderContent;
import com.handsoffretail.fastmirror.entity.PackageManifest;
import com.handsoffretail.fastmirror.entity.PackageRecord;
import com.handsoffretail.fastmirror.entity.PackagedFile;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.DayPackager;
import com.handsoffretail.fastmirror.service.PackageLedger;
import com.handsoffretail.fastmirror.service.PackageScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Checks the mirror at start-up and then once a minute, and zips every due type folder whose
 * content has no zip yet.
 *
 * <p>Checking stays cheap as the mirror grows: a folder whose own modified time has not changed
 * since it was last found up to date is not listed again.
 */
public final class DailyPackageSchedulerImpl implements PackageScheduler {

    private static final Duration INTERVAL = Duration.ofMinutes(1);

    private final DayPackager packager;
    private final PackageLedger ledger;
    private final PackageConfig config;
    private final Clock clock;
    private final AppLogger logger;
    private final Listener listener;
    private final Instant notBefore;
    private final BooleanSupplier copyIdle;

    /** Set once copying has caught up after start-up; from then on packaging is never held back. */
    private boolean caughtUp;

    /** The UTC date of the last outbox clean-up, so it runs once a day. */
    private LocalDate cleanedOn;

    /** Folders found up to date, with the folder's modified time when they were. */
    private final Map<TypeFolder, Instant> upToDate = new HashMap<>();

    /** Folders whose problem has been logged, so it is logged once rather than every minute. */
    private final Set<TypeFolder> reported = new HashSet<>();

    private ScheduledExecutorService executor;

    /** A scheduler that never waits for copying, for use where nothing is being copied. */
    public DailyPackageSchedulerImpl(
            DayPackager packager,
            PackageLedger ledger,
            PackageConfig config,
            Clock clock,
            AppLogger logger,
            Listener listener) {
        this(packager, ledger, config, clock, logger, listener, Instant.MIN, () -> true);
    }

    /**
     * @param notBefore no packaging before this moment: start-up plus the stability wait plus two
     *                  scans, by when every file that was already waiting has been found stable
     *                  and handed to the copier
     * @param copyIdle  whether the copier has nothing queued or running; packaging starts at the
     *                  first check after {@code notBefore} when this holds, so a day being copied
     *                  after the agent was off is not zipped half-copied
     */
    public DailyPackageSchedulerImpl(
            DayPackager packager,
            PackageLedger ledger,
            PackageConfig config,
            Clock clock,
            AppLogger logger,
            Listener listener,
            Instant notBefore,
            BooleanSupplier copyIdle) {
        this.packager = Objects.requireNonNull(packager, "packager");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
        this.copyIdle = Objects.requireNonNull(copyIdle, "copyIdle");
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        try {
            packager.clearStaging();
        } catch (final IOException e) {
            logger.warn("Could not clear interrupted zips from " + config.stagingFolder() + ": " + e.getMessage());
        }
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            final Thread thread = new Thread(runnable, "fastmirror-packager");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(
                this::checkSafely, 0L, INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized int checkNow() {
        final Instant now = clock.instant();
        if (!caughtUp) {
            if (now.isBefore(notBefore) || !copyIdle.getAsBoolean()) {
                return 0;
            }
            caughtUp = true;
            logger.info("Copying has caught up; packaging is running (each day at "
                    + config.packageAtUtc() + " UTC the next day)");
        }
        removeOldZips(now);
        final List<TypeFolder> folders;
        try {
            folders = packager.typeFolders();
        } catch (final IOException e) {
            logger.warn("Could not list the mirror for packaging; trying again in a minute: " + e.getMessage());
            return 0;
        }
        int built = 0;
        for (final TypeFolder folder : folders) {
            if (isDue(folder, now)) {
                built += packageIfChanged(folder);
            }
        }
        return built;
    }

    @Override
    public Optional<LastPackaged> lastPackaged() {
        final List<PackageRecord> records = ledger.all();
        return records.stream()
                .map(record -> record.folder().businessDate())
                .max(Comparator.naturalOrder())
                .map(
                        latest ->
                                new LastPackaged(
                                        latest,
                                        (int) records.stream()
                                                .map(PackageRecord::folder)
                                                .filter(folder -> folder.businessDate().equals(latest))
                                                .distinct()
                                                .count()));
    }

    @Override
    public void close() {
        final ScheduledExecutorService running;
        synchronized (this) {
            running = executor;
            executor = null;
        }
        if (running == null) {
            return;
        }
        running.shutdown();
        try {
            if (!running.awaitTermination(30, TimeUnit.SECONDS)) {
                running.shutdownNow();
            }
        } catch (final InterruptedException e) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** OUTBOX_KEEP_DAYS: once a UTC day, zips of business days older than that leave the outbox. */
    private void removeOldZips(Instant now) {
        final LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (config.outboxKeepDays() <= 0 || today.equals(cleanedOn)) {
            return;
        }
        cleanedOn = today;
        try {
            final int removed = packager.removeZipsBefore(today.minusDays(config.outboxKeepDays()));
            if (removed > 0) {
                logger.info(
                        "Outbox clean-up: removed " + removed + " zip(s) of business days before "
                                + today.minusDays(config.outboxKeepDays()) + " (OUTBOX_KEEP_DAYS="
                                + config.outboxKeepDays() + "); S3 is not affected");
            }
        } catch (final IOException e) {
            logger.warn("Outbox clean-up failed; trying again tomorrow: " + e.getMessage());
        }
    }

    /** Day D is due at PACKAGE_AT_UTC on D+1, and only from PACKAGE_FROM_DATE. */
    private boolean isDue(TypeFolder folder, Instant now) {
        final LocalDate day = folder.businessDate();
        if (config.packageFromDate() != null && day.isBefore(config.packageFromDate())) {
            return false;
        }
        final Instant due = day.plusDays(1).atTime(config.packageAtUtc()).toInstant(ZoneOffset.UTC);
        return !now.isBefore(due);
    }

    private void checkSafely() {
        try {
            final int built = checkNow();
            if (built > 0) {
                logger.info("Packaging: " + built + " zip(s) moved into the outbox");
            }
        } catch (final RuntimeException | Error e) {
            // Never let one bad check end the schedule, and never let it reach the copier.
            logger.error("Packaging check failed; trying again in a minute", e);
        }
    }

    /** @return 1 if a zip reached the outbox, otherwise 0 */
    private int packageIfChanged(TypeFolder folder) {
        try {
            final Instant modified = packager.modified(folder);
            if (modified.equals(upToDate.get(folder))) {
                return 0;
            }
            final FolderContent content = packager.list(folder);
            final int built = packageContent(content);
            // Either way the folder is not looked at again until something in it changes.
            upToDate.put(folder, modified);
            if (built >= 0) {
                reported.remove(folder);
            }
            return Math.max(built, 0);
        } catch (final IOException | RuntimeException e) {
            if (reported.add(folder)) {
                logger.error(
                        "Could not package " + folder + "; trying again every minute: " + e.getMessage(), e);
            }
            return 0;
        }
    }

    /**
     * @return 1 if a zip was built, 0 if the folder is up to date, -1 if it must not be zipped as
     *         it is (and has been reported)
     */
    private int packageContent(FolderContent content) throws IOException {
        final TypeFolder folder = content.folder();
        if (content.files().isEmpty()) {
            return 0; // no zip for a type with no files
        }
        final String fingerprint = content.fingerprint();
        Optional<PackageRecord> latest = ledger.latest(folder);

        // A zip in the outbox that the ledger does not know: a crash came between the move and
        // the ledger line. Verify it and record it; it is never rebuilt.
        int next = latest.map(record -> record.version() + 1).orElse(1);
        while (Files.exists(packager.outboxZip(folder, next))) {
            final Path zip = packager.outboxZip(folder, next);
            final Optional<PackageManifest> found = packager.verify(zip);
            if (found.isEmpty()) {
                report(folder, "The outbox holds " + zip + ", which is not a complete FastMirror zip."
                        + " Move it away and FastMirror builds that version again.");
                return -1;
            }
            final PackageRecord recovered = recordOf(found.get());
            ledger.record(recovered);
            logger.info("Found " + zip.getFileName() + " in the outbox and recorded it; not rebuilt");
            latest = Optional.of(recovered);
            next++;
        }

        if (latest.isPresent() && latest.get().fingerprint().equals(fingerprint)) {
            return 0;
        }
        if (latest.isPresent() && lostFiles(latest.get(), content)) {
            report(folder, "Files zipped in " + folder.zipName(config.storeFolder(), latest.get().version())
                    + " are no longer in the mirror folder, so " + folder + " is not zipped again:"
                    + " a zip without them would be incomplete. Restore the files to package it.");
            return -1;
        }

        final PackageManifest manifest = packager.build(content, next, clock.instant());
        final PackageRecord record = recordOf(manifest);
        ledger.record(record);
        listener.packaged(record);
        logger.info(
                "Packaged " + folder + " v" + next + ": " + manifest.files().size() + " file(s) -> "
                        + packager.outboxZip(folder, next));
        return 1;
    }

    /** Whether files of the last zip are missing now, which would make a new version incomplete. */
    private boolean lostFiles(PackageRecord latest, FolderContent content) {
        final Set<String> now =
                content.files().stream().map(PackagedFile::path).collect(Collectors.toSet());
        final Optional<PackageManifest> previous =
                packager.verify(packager.outboxZip(latest.folder(), latest.version()));
        if (previous.isPresent()) {
            return !previous.get().packagedFiles().stream().map(PackagedFile::path).allMatch(now::contains);
        }
        // The zip has been cleared from the outbox; its file count is all that is left.
        return now.size() < latest.fileCount();
    }

    private PackageRecord recordOf(PackageManifest manifest) {
        return new PackageRecord(
                manifest.folder(),
                manifest.version(),
                manifest.createdAt(),
                manifest.files().size(),
                FolderContent.fingerprint(manifest.packagedFiles()));
    }

    private void report(TypeFolder folder, String message) {
        if (reported.add(folder)) {
            logger.error(message);
        }
    }
}
