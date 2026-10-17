package com.handsoffretail.fastmirror;

import com.handsoffretail.fastmirror.config.ConfigException;
import com.handsoffretail.fastmirror.config.ContentDateFields;
import com.handsoffretail.fastmirror.config.EnvFileReader;
import com.handsoffretail.fastmirror.config.FileNameDatePattern;
import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.config.PackageConfig;
import com.handsoffretail.fastmirror.entity.DateSource;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.ArchiveFileFilter;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.FileDateResolver;
import com.handsoffretail.fastmirror.service.PackageScheduler;
import com.handsoffretail.fastmirror.service.PathMapper;
import com.handsoffretail.fastmirror.service.ScanService;
import com.handsoffretail.fastmirror.service.StabilityTracker;
import com.handsoffretail.fastmirror.service.StatsReporter;
import com.handsoffretail.fastmirror.service.TrayService;
import com.handsoffretail.fastmirror.service.implementation.ArchiveFileFilterImpl;
import com.handsoffretail.fastmirror.service.implementation.ArchiveScanServiceImpl;
import com.handsoffretail.fastmirror.service.implementation.AtomicFileCopierImpl;
import com.handsoffretail.fastmirror.service.implementation.CopyQueueImpl;
import com.handsoffretail.fastmirror.service.implementation.DailyPackageSchedulerImpl;
import com.handsoffretail.fastmirror.service.implementation.FileCopyLedgerImpl;
import com.handsoffretail.fastmirror.service.implementation.FileDateResolverImpl;
import com.handsoffretail.fastmirror.service.implementation.FilePackageLedgerImpl;
import com.handsoffretail.fastmirror.service.implementation.GlobPathFilterImpl;
import com.handsoffretail.fastmirror.service.implementation.GroupedPathMapperImpl;
import com.handsoffretail.fastmirror.service.implementation.LoggingStatsReporterImpl;
import com.handsoffretail.fastmirror.service.implementation.StabilityTrackerImpl;
import com.handsoffretail.fastmirror.service.implementation.SystemTrayServiceImpl;
import com.handsoffretail.fastmirror.service.implementation.ThreadSleeperImpl;
import com.handsoffretail.fastmirror.service.implementation.ZipDayPackagerImpl;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

/**
 * The composition root: construction, wiring and startup ordering, and nothing else.
 *
 * <p>Every decision about <em>what</em> FastMirror does lives in a service. This class only decides
 * what is built, in what order it starts, and in what order it stops. Nothing here touches a file
 * in the source folder.
 */
public final class Application {

    private static final int EXIT_CONFIG_ERROR = 1;

    private static final Duration COPY_SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private Application() {
        // Not instantiable: this class is a wiring script, not an object.
    }

    public static void main(String[] args) {
        final Path baseDirectory = baseDirectory();
        final EnvFileReader env = new EnvFileReader(baseDirectory);
        // Every moment is UTC; the PC's own time zone is never consulted.
        final Clock clock = Clock.systemUTC();

        // 1. Logging first, so that every later failure has somewhere to be reported. The agent
        //    runs windowless in production, so a stack trace on a console nobody sees is the same
        //    as no report at all.
        final AppLogger logger = new AppLogger(resolveLogFolder(env, baseDirectory), clock);

        // 2. Nothing may die silently on any thread.
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> logger.error("Uncaught exception on " + thread.getName(), error));

        // 3. Configuration, validated, then the ledger and everything else.
        final MirrorConfig config;
        final Handle handle;
        try {
            config = MirrorConfig.load(env::get, baseDirectory);
            handle = start(config, logger, clock);
        } catch (final ConfigException e) {
            logger.error("Configuration error: " + e.getMessage());
            System.err.println("FastMirror configuration error: " + e.getMessage());
            logger.close();
            showStartupError(e.getMessage());
            System.exit(EXIT_CONFIG_ERROR);
            return;
        }

        // 10. The tray icon: a startup notification saying where files are copied from and to, a
        //     visible sign the agent is running, and an Exit button. Wired here in main rather
        //     than in start(), so tests and headless runs never create one. Exit stops the agent
        //     and ends the process; the shutdown hook below then removes the icon.
        final TrayService tray =
                new SystemTrayServiceImpl(
                        config,
                        handle.stats(),
                        logger,
                        () -> {
                            handle.stop();
                            System.exit(0);
                        },
                        handle.packaging()
                                .<Supplier<Optional<PackageScheduler.LastPackaged>>>map(
                                        packaging -> packaging::lastPackaged)
                                .orElse(null));

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    handle.stop();
                                    tray.close();
                                },
                                "fastmirror-shutdown"));

        tray.show();

        handle.awaitStop();
    }

    /**
     * Builds the object graph and starts it.
     *
     * <p>Exposed so that integration tests exercise the real wiring and the real startup order
     * rather than a parallel copy of it that could drift out of step.
     *
     * @param clock wall-clock time, which drives the stability wait and the creation-date grouping;
     *              tests pass a clock they can move forward
     * @throws ConfigException if the copy ledger cannot be read
     */
    public static Handle start(MirrorConfig config, AppLogger logger, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(clock, "clock");

        for (final String warning : config.warnings()) {
            logger.warn("Configuration: " + warning);
        }

        // 4. Remove staging files left behind by a previous run.
        deleteStaleStagingFiles(config, logger);

        // 5. The ledger of files already copied, compacted.
        final PathMapper mapper =
                new GroupedPathMapperImpl(
                        config.sourceFolder(), config.mirrorFolder(), config.externalSubfolders());
        final FileCopyLedgerImpl ledger;
        try {
            ledger = FileCopyLedgerImpl.open(config.stateFolder(), mapper::toSource, logger);
        } catch (final IOException e) {
            throw new ConfigException(
                    "The copy ledger in " + config.stateFolder() + " could not be read ("
                            + e.getMessage() + "). Check the folder's permissions.", e);
        }

        // 6. Construct. Constructor injection only; every dependency is final.
        final ArchiveFileFilter filter =
                new ArchiveFileFilterImpl(config, new GlobPathFilterImpl(config.excludePatterns()));
        final StatsReporter stats = new LoggingStatsReporterImpl(logger, config.statsIntervalMs());
        final FileCopier copier =
                new AtomicFileCopierImpl(config, mapper, new ThreadSleeperImpl(), logger, clock);
        final CopyQueue queue = new CopyQueueImpl(copier, ledger, stats, logger, config.copyWorkers());
        final FileDateResolver dates = new FileDateResolverImpl(config, copier, logger);
        final StabilityTracker tracker =
                new StabilityTrackerImpl(Duration.ofMinutes(config.stabilityMinutes()), clock);
        final ScanService scanner =
                new ArchiveScanServiceImpl(config, mapper, filter, dates, tracker, ledger, queue, logger);

        // 7. Day packaging, when on. It reads only the mirror, on its own thread, so a packaging
        //    failure never holds up copying. It starts once copying has caught up after start-up,
        //    so that a day still being copied is not zipped half-copied.
        PackageScheduler packaging = null;
        if (config.packagingEnabled()) {
            final PackageConfig packageConfig = config.packaging();
            final FilePackageLedgerImpl packageLedger;
            try {
                packageLedger = FilePackageLedgerImpl.open(config.stateFolder(), logger);
            } catch (final IOException e) {
                throw new ConfigException(
                        "The package ledger in " + config.stateFolder() + " could not be read ("
                                + e.getMessage() + "). Check the folder's permissions.", e);
            }
            packaging =
                    new DailyPackageSchedulerImpl(
                            new ZipDayPackagerImpl(config.mirrorFolder(), packageConfig),
                            packageLedger,
                            packageConfig,
                            clock,
                            logger,
                            record -> stats.recordPackaged(),
                            clock.instant()
                                    .plus(Duration.ofMinutes(config.stabilityMinutes()))
                                    .plusSeconds(2L * config.scanIntervalSeconds()),
                            () -> queue.pending() == 0);
        }

        final Handle handle = new Handle(config, logger, queue, scanner, stats, packaging);

        // 8. The first scan starts every file's stability clock. Nothing is copied before the
        //    stability period has passed, not even files that were already there.
        scanner.scan();

        // 9. Periodic scanning, packaging and reporting.
        scanner.start();
        if (packaging != null) {
            packaging.start();
        }
        handle.startStatsReporting();

        // 10.
        if (config.packagingEnabled()) {
            final PackageConfig packageConfig = config.packaging();
            logger.info(
                    "Packaging on: each business day zipped by type at " + packageConfig.packageAtUtc()
                            + " UTC the next day, into " + packageConfig.storeOutbox()
                            + " (staging " + packageConfig.stagingFolder()
                            + (packageConfig.packageFromDate() == null
                                    ? ""
                                    : ", from " + packageConfig.packageFromDate())
                            + (packageConfig.outboxKeepDays() == 0
                                    ? ", zips kept"
                                    : ", zips kept " + packageConfig.outboxKeepDays() + " days")
                            + ")");
        }
        logger.info(
                "FastMirror started: " + config.sourceFolder() + " -> " + config.mirrorFolder()
                        + " (prefixes=" + String.join(",", config.includePrefixes())
                        + " ." + String.join(",.", config.includeExtensions())
                        + (config.includeSubfolders().isEmpty()
                                ? ""
                                : ", subfolders=" + String.join(",", config.includeSubfolders())
                                        + " ." + String.join(",.", config.subfolderExtensions()))
                        + (config.copyFromDate() == null ? "" : ", from=" + config.copyFromDate())
                        + ", date from " + describeDateSource(config)
                        + ", stable after " + config.stabilityMinutes() + " min"
                        + ", scan every " + config.scanIntervalSeconds() + "s"
                        + ", workers=" + config.copyWorkers()
                        + ", ledger=" + ledger.file() + ")");

        return handle;
    }

    /** The running agent: what to stop, and in what order. */
    public static final class Handle {

        private final MirrorConfig config;
        private final AppLogger logger;
        private final CopyQueue queue;
        private final ScanService scanner;
        private final StatsReporter stats;
        private final PackageScheduler packaging;
        private final ScheduledExecutorService statsScheduler;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopping = new AtomicBoolean();

        private Handle(
                MirrorConfig config,
                AppLogger logger,
                CopyQueue queue,
                ScanService scanner,
                StatsReporter stats,
                PackageScheduler packaging) {
            this.config = config;
            this.logger = logger;
            this.queue = queue;
            this.scanner = scanner;
            this.stats = stats;
            this.packaging = packaging;
            this.statsScheduler =
                    Executors.newSingleThreadScheduledExecutor(
                            runnable -> {
                                final Thread thread = new Thread(runnable, "fastmirror-stats");
                                thread.setDaemon(true);
                                return thread;
                            });
        }

        private void startStatsReporting() {
            statsScheduler.scheduleWithFixedDelay(
                    this::reportQuietly,
                    config.statsIntervalMs(),
                    config.statsIntervalMs(),
                    TimeUnit.MILLISECONDS);
        }

        private void reportQuietly() {
            try {
                stats.report();
            } catch (final Throwable t) {
                logger.error("Stats reporting failed", t);
            }
        }

        /**
         * Blocks until the agent stops, then ends the process.
         *
         * <p>The exit is unconditional. The tray icon starts AWT threads that are not daemons, so
         * without it a stopped agent would leave the process alive with nothing running.
         */
        public void awaitStop() {
            try {
                stopped.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }

        /** The queue, so integration tests can wait for quiescence rather than for a clock. */
        public CopyQueue queue() {
            return queue;
        }

        /** The counters, so integration tests can read the outcomes. */
        public StatsReporter stats() {
            return stats;
        }

        /** Runs one scan on the calling thread. */
        public void scanNow() {
            scanner.scan();
        }

        /** Day packaging, if it is on. */
        public Optional<PackageScheduler> packaging() {
            return Optional.ofNullable(packaging);
        }

        /**
         * Runs one packaging check on the calling thread.
         *
         * @return the zips moved into the outbox; 0 when packaging is off
         */
        public int packageNow() {
            return packaging == null ? 0 : packaging.checkNow();
        }

        /** Stops everything in the reverse of the order it started. */
        public void stop() {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            try {
                statsScheduler.shutdownNow();
                if (packaging != null) {
                    // A zip being built is finished, so staging holds no half-built zip.
                    packaging.close();
                }
                scanner.stop();

                // In-flight copies get a grace period. Each one holds a handle on a producer file,
                // and letting it finish releases that handle in an orderly way.
                queue.shutdown(COPY_SHUTDOWN_GRACE);

                deleteStaleStagingFiles(config, logger);
                logger.info(LoggingStatsReporterImpl.format(stats.lifetime(), "lifetime"));
                logger.info("FastMirror stopped");
            } catch (final Throwable t) {
                logger.error("Failure during shutdown", t);
            } finally {
                logger.close();
                stopped.countDown();
            }
        }
    }

    // ---------------------------------------------------------------- private wiring helpers

    private static String describeDateSource(MirrorConfig config) {
        final String root =
                describe(
                        config.dateSource(),
                        config.fileNameDatePattern(),
                        config.contentDateFields(),
                        config)
                + (config.createdDatePrefixes().isEmpty()
                        ? ""
                        : ", " + String.join(",", config.createdDatePrefixes()) + ": "
                                + describe(DateSource.CREATED, null, null, config));
        if (config.includeSubfolders().isEmpty()) {
            return root;
        }
        return root + ", subfolders: "
                + describe(
                        config.subfolderDateSource(),
                        config.subfolderFileNameDatePattern(),
                        config.subfolderContentDateFields(),
                        config);
    }

    private static String describe(
            DateSource source,
            FileNameDatePattern pattern,
            ContentDateFields fields,
            MirrorConfig config) {
        return switch (source) {
            case CREATED -> "creation time (" + config.timeZone() + ")";
            case FILENAME -> "file name " + pattern;
            case CONTENT -> "content field " + fields;
        };
    }

    /** The only files FastMirror ever deletes are its own staging files. */
    private static void deleteStaleStagingFiles(MirrorConfig config, AppLogger logger) {
        final int[] removed = {0};
        try {
            Files.walkFileTree(
                    config.mirrorFolder(),
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            if (file.getFileName().toString().endsWith(MirrorConfig.STAGING_SUFFIX)) {
                                try {
                                    Files.deleteIfExists(file);
                                    removed[0]++;
                                } catch (final IOException e) {
                                    logger.warn("Could not remove the stale staging file " + file);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException failure) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (final IOException e) {
            logger.warn("Could not scan the mirror folder for stale staging files: " + e);
        }
        if (removed[0] > 0) {
            logger.info("Removed " + removed[0] + " stale staging file(s)");
        }
    }

    /**
     * The exe has no console, so without this a mistake in .env would make the agent vanish with
     * the reason only in the log. Skipped where there is no desktop, such as a Windows service.
     */
    private static void showStartupError(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            final JFrame owner = new JFrame();
            owner.setUndecorated(true);
            owner.setAlwaysOnTop(true);
            owner.setLocationRelativeTo(null);
            owner.setVisible(true);
            try {
                JOptionPane.showMessageDialog(
                        owner,
                        "FastMirror could not start.\n\n" + message
                                + "\n\nCorrect .env beside FastMirror.exe and start it again.",
                        "FastMirror",
                        JOptionPane.ERROR_MESSAGE);
            } finally {
                owner.dispose();
            }
        } catch (final RuntimeException e) {
            // No usable desktop after all; the log line already says what went wrong.
        }
    }

    private static Path resolveLogFolder(EnvFileReader env, Path baseDirectory) {
        final String configured = env.get(MirrorConfig.KEY_LOG_FOLDER);
        final String folder =
                configured == null || configured.isBlank()
                        ? MirrorConfig.DEFAULT_LOG_FOLDER
                        : configured.trim();
        return baseDirectory.resolve(folder).toAbsolutePath().normalize();
    }

    /** The directory the executable lives in, which is where {@code .env} and {@code logs} sit. */
    private static Path baseDirectory() {
        try {
            final Path location =
                    Path.of(
                            Application.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            final Path directory = Files.isDirectory(location) ? location : location.getParent();
            if (directory != null) {
                return directory.toAbsolutePath().normalize();
            }
        } catch (final URISyntaxException | RuntimeException e) {
            // Fall through: running from an IDE or an unusual classloader.
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
