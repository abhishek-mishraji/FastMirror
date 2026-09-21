package com.handsoffretail.fastmirror;

import com.handsoffretail.fastmirror.config.ConfigException;
import com.handsoffretail.fastmirror.config.EnvFileReader;
import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.CopyQueue;
import com.handsoffretail.fastmirror.service.DifferenceDetector;
import com.handsoffretail.fastmirror.service.FileCopier;
import com.handsoffretail.fastmirror.service.MirrorService;
import com.handsoffretail.fastmirror.service.PathFilter;
import com.handsoffretail.fastmirror.service.PathMapper;
import com.handsoffretail.fastmirror.service.SourceWatcherService;
import com.handsoffretail.fastmirror.service.StatsReporter;
import com.handsoffretail.fastmirror.service.SweepService;
import com.handsoffretail.fastmirror.service.TrayService;
import com.handsoffretail.fastmirror.service.implementation.AtomicFileCopierImpl;
import com.handsoffretail.fastmirror.service.implementation.CopyQueueImpl;
import com.handsoffretail.fastmirror.service.implementation.GlobPathFilterImpl;
import com.handsoffretail.fastmirror.service.implementation.LoggingStatsReporterImpl;
import com.handsoffretail.fastmirror.service.implementation.MirrorServiceImpl;
import com.handsoffretail.fastmirror.service.implementation.RelativePathMapperImpl;
import com.handsoffretail.fastmirror.service.implementation.SizeAndTimeDifferenceDetectorImpl;
import com.handsoffretail.fastmirror.service.implementation.SystemTrayServiceImpl;
import com.handsoffretail.fastmirror.service.implementation.ThreadSleeperImpl;
import com.handsoffretail.fastmirror.service.implementation.TreeSweepServiceImpl;
import com.handsoffretail.fastmirror.service.implementation.WatchServiceSourceWatcherImpl;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The composition root (AR-6): construction, wiring and startup ordering, and nothing else.
 *
 * <p>Every decision about <em>what</em> FastMirror does lives in a service. This class only decides
 * what is built, in what order it starts, and in what order it stops. A reviewer should be able to
 * confirm at a glance that no rule of SRS 5.1 could possibly be violated here, because nothing here
 * touches a file in the source folder.
 *
 * <p>The startup order is load-bearing, not cosmetic. The watcher starts <em>before</em> the first
 * sweep (FR-13) so that a file created while the sweep is walking still raises an event; the other
 * way round, files appearing in that window would be missed by both.
 */
public final class Application {

    private static final int EXIT_CONFIG_ERROR = 1;
    private static final int EXIT_WATCH_FAILURE = 2;

    private static final Duration COPY_SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private Application() {
        // Not instantiable: this class is a wiring script, not an object.
    }

    public static void main(String[] args) {
        final Path baseDirectory = baseDirectory();
        final EnvFileReader env = new EnvFileReader(baseDirectory);
        final Clock clock = Clock.systemDefaultZone();

        // 1. Logging first, so that every later failure has somewhere to be reported. The agent
        //    runs windowless in production (FR-24), so a stack trace on a console nobody sees is
        //    the same as no report at all.
        final AppLogger logger = new AppLogger(resolveLogFolder(env, baseDirectory), clock);

        // 2. Nothing may die silently on any thread (NFR-07).
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> logger.error("Uncaught exception on " + thread.getName(), error));

        // 3. Configuration, validated (SRS 7.3).
        final MirrorConfig config;
        try {
            config = MirrorConfig.load(env::get, baseDirectory);
        } catch (final ConfigException e) {
            logger.error("Configuration error: " + e.getMessage());
            System.err.println("FastMirror configuration error: " + e.getMessage());
            logger.close();
            System.exit(EXIT_CONFIG_ERROR);
            return;
        }

        final Handle handle = start(config, logger, clock);

        // 11. The tray icon: a startup notification saying where files are copied from and to, a
        //     visible sign the agent is running, and an Exit button (SRS 9.11). Wired here in main
        //     rather than in start(), so tests and headless runs never create one. Exit stops the
        //     agent and ends the process; the shutdown hook below then removes the icon.
        final TrayService tray =
                new SystemTrayServiceImpl(
                        config,
                        handle.stats(),
                        logger,
                        () -> {
                            handle.stop();
                            System.exit(0);
                        });

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
     * Builds the object graph and starts it in the order of SRS 9.2.
     *
     * <p>Exposed so that integration tests exercise the real wiring and the real startup order
     * rather than a parallel copy of it that could drift out of step.
     */
    public static Handle start(MirrorConfig config, AppLogger logger, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(clock, "clock");

        // 4. Remove staging files left behind by a previous run (FR-22).
        deleteStaleStagingFiles(config, logger);

        // 5. Construct. Constructor injection only; every dependency is final (AR-4, AR-5).
        final PathMapper mapper = new RelativePathMapperImpl(config.sourceFolder(), config.mirrorFolder());
        final PathFilter filter = new GlobPathFilterImpl(config.excludePatterns());
        final StatsReporter stats = new LoggingStatsReporterImpl(logger, config.statsIntervalMs());
        final FileCopier copier =
                new AtomicFileCopierImpl(config, mapper, new ThreadSleeperImpl(), logger, clock);
        final DifferenceDetector detector =
                new SizeAndTimeDifferenceDetectorImpl(mapper, copier, config.contentVerify());

        // 6. The worker pool exists as soon as the queue does.
        final CopyQueue queue =
                new CopyQueueImpl(detector, copier, stats, logger, config.copyWorkers());

        final SweepService sweeper =
                new TreeSweepServiceImpl(config, mapper, filter, detector, queue, logger);
        final MirrorService mirrorService =
                new MirrorServiceImpl(config, mapper, filter, queue, sweeper, logger);

        final Handle handle = new Handle(config, logger, queue, sweeper, mirrorService, stats);

        final SourceWatcherService watcher =
                new WatchServiceSourceWatcherImpl(
                        config, mirrorService, logger, failure -> handle.fail(EXIT_WATCH_FAILURE));
        handle.attach(watcher);

        // 7. Watcher and event thread BEFORE the sweep (FR-13), so nothing created during the
        //    sweep falls between the two.
        mirrorService.start();
        watcher.start();

        // 8. Bring the mirror into line with whatever is already on disk.
        sweeper.sweep();

        // 9. Periodic safety net and periodic reporting.
        sweeper.start();
        handle.startStatsReporting();

        // 10.
        logger.info(
                "FastMirror started: " + config.sourceFolder() + " -> " + config.mirrorFolder()
                        + " (workers=" + config.copyWorkers()
                        + ", sweep=" + (config.sweepEnabled() ? config.sweepIntervalMs() + "ms" : "off")
                        + ", publishPartial=" + config.publishPartial() + ")");

        return handle;
    }

    /** The running agent: what to stop, and in what order (SRS 9.10). */
    public static final class Handle {

        private final MirrorConfig config;
        private final AppLogger logger;
        private final CopyQueue queue;
        private final SweepService sweeper;
        private final MirrorService mirrorService;
        private final StatsReporter stats;
        private final ScheduledExecutorService statsScheduler;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopping = new AtomicBoolean();

        private volatile SourceWatcherService watcher;
        private volatile int exitCode;

        private Handle(
                MirrorConfig config,
                AppLogger logger,
                CopyQueue queue,
                SweepService sweeper,
                MirrorService mirrorService,
                StatsReporter stats) {
            this.config = config;
            this.logger = logger;
            this.queue = queue;
            this.sweeper = sweeper;
            this.mirrorService = mirrorService;
            this.stats = stats;
            this.statsScheduler =
                    Executors.newSingleThreadScheduledExecutor(
                            runnable -> {
                                final Thread thread = new Thread(runnable, "fastmirror-stats");
                                thread.setDaemon(true);
                                return thread;
                            });
        }

        private void attach(SourceWatcherService watcher) {
            this.watcher = watcher;
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
            System.exit(exitCode);
        }

        /** The queue, so integration tests can wait for quiescence rather than for a clock. */
        public CopyQueue queue() {
            return queue;
        }

        /** The counters, so integration tests and the soak run can read the outcomes. */
        public StatsReporter stats() {
            return stats;
        }

        /** Runs one sweep on the calling thread. */
        public void sweepNow() {
            sweeper.sweep();
        }

        private void fail(int code) {
            exitCode = code;
            stop();
        }

        /** Stops everything in the reverse of the order it started (SRS 9.10). */
        public void stop() {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            try {
                statsScheduler.shutdownNow();
                if (watcher != null) {
                    watcher.stop();
                }
                mirrorService.stop();
                sweeper.stop();

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

    /** FR-17 and FR-22: the only files FastMirror ever deletes are its own staging files. */
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
