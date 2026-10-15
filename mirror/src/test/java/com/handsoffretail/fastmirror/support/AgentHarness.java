package com.handsoffretail.fastmirror.support;

import com.handsoffretail.fastmirror.Application;
import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyStats;
import com.handsoffretail.fastmirror.logging.AppLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Runs a real FastMirror agent against temporary folders, with a clock the test controls.
 *
 * <p>Deliberately starts the agent through {@link Application#start}, the same wiring and the same
 * startup ordering production uses. A harness that built its own object graph could drift out of
 * step with the composition root and still pass, which would make the integration tests worthless
 * exactly when they matter.
 *
 * <p>The scheduled scan runs at most every 10 seconds of real time, so tests drive scanning
 * themselves with {@link #scanNow()} and move time on with {@link #advance(Duration)}.
 */
public final class AgentHarness implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    /** The prefixes a harness copies unless a test says otherwise. */
    public static final String DEFAULT_PREFIXES = "CPJ,HRS";

    private final Path sourceRoot;
    private final Path mirrorRoot;
    private final Path baseDirectory;
    private final MirrorConfig config;
    private final MutableClock clock;
    private final AppLogger logger;
    private final Application.Handle handle;

    private AgentHarness(
            Path sourceRoot,
            Path mirrorRoot,
            Path baseDirectory,
            MirrorConfig config,
            MutableClock clock,
            AppLogger logger,
            Application.Handle handle) {
        this.sourceRoot = sourceRoot;
        this.mirrorRoot = mirrorRoot;
        this.baseDirectory = baseDirectory;
        this.config = config;
        this.clock = clock;
        this.logger = logger;
        this.handle = handle;
    }

    public static AgentHarness start(Path sourceRoot, Path mirrorRoot, Path baseDirectory) {
        return start(sourceRoot, mirrorRoot, baseDirectory, Map.of());
    }

    public static AgentHarness start(
            Path sourceRoot, Path mirrorRoot, Path baseDirectory, Map<String, String> overrides) {
        return start(sourceRoot, mirrorRoot, baseDirectory, overrides, MutableClock.systemNow());
    }

    /**
     * @param baseDirectory where the agent's {@code logs} and {@code state} folders go, standing in
     *                      for the folder the exe lives in
     */
    public static AgentHarness start(
            Path sourceRoot,
            Path mirrorRoot,
            Path baseDirectory,
            Map<String, String> overrides,
            MutableClock clock) {
        final Map<String, String> values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, DEFAULT_PREFIXES);
        values.putAll(overrides);

        final MirrorConfig config = MirrorConfig.load(values::get, baseDirectory);
        final AppLogger logger = new AppLogger(config.logFolder(), clock);
        final Application.Handle handle = Application.start(config, logger, clock);
        return new AgentHarness(sourceRoot, mirrorRoot, baseDirectory, config, clock, logger, handle);
    }

    public MirrorConfig config() {
        return config;
    }

    public MutableClock clock() {
        return clock;
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public Path mirrorRoot() {
        return mirrorRoot;
    }

    public Path ledgerFile() {
        return config.stateFolder().resolve("copied.ledger");
    }

    public Path source(String relative) {
        return sourceRoot.resolve(relative);
    }

    public Path target(String relative) {
        return mirrorRoot.resolve(relative);
    }

    public CopyStats stats() {
        return handle.stats().lifetime();
    }

    public long count(CopyOutcome outcome) {
        return stats().count(outcome);
    }

    public void scanNow() {
        handle.scanNow();
    }

    /** Runs one packaging check on the calling thread; the zips moved into the outbox. */
    public int packageNow() {
        return handle.packageNow();
    }

    public void advance(Duration duration) {
        clock.advance(duration);
    }

    /** Moves the clock past the stability period. */
    public void advancePastStability() {
        clock.advance(Duration.ofMinutes(config.stabilityMinutes()).plusSeconds(1));
    }

    /**
     * The normal cycle for files that are not changing: scan (starts or confirms their stability
     * clocks), wait out the stability period, scan again (submits them), and wait for the copies.
     */
    public void copyStableFiles() {
        scanNow();
        advancePastStability();
        scanNow();
        awaitIdle();
    }

    /** Creates a source file, returning its path. */
    public Path write(String relative, byte[] content) throws IOException {
        final Path file = sourceRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        return file;
    }

    public Path write(String relative, String content) throws IOException {
        return write(relative, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a source file and sets its creation time, which decides its destination folder. */
    public Path write(String relative, String content, LocalDateTime created) throws IOException {
        final Path file = write(relative, content);
        setCreated(file, created);
        return file;
    }

    /** Sets a creation time given as a wall-clock time in the default TIME_ZONE, New York. */
    public static void setCreated(Path file, LocalDateTime created) throws IOException {
        setCreated(file, created.atZone(ZoneId.of(MirrorConfig.DEFAULT_TIME_ZONE)).toInstant());
    }

    public static void setCreated(Path file, java.time.Instant created) throws IOException {
        Files.setAttribute(file, "basic:creationTime", FileTime.from(created));
    }

    /** Waits until the mirror holds exactly these bytes for this path. */
    public void awaitMirrored(String relative, byte[] expected) {
        final Path target = target(relative);
        await(
                () -> {
                    try {
                        return Files.isRegularFile(target)
                                && Arrays.equals(expected, Files.readAllBytes(target));
                    } catch (final IOException e) {
                        return false;
                    }
                },
                "the mirror to hold the expected content for " + relative);
    }

    public void awaitMirrored(String relative, String expected) {
        awaitMirrored(relative, expected.getBytes(StandardCharsets.UTF_8));
    }

    /** Waits until the copy queue has no work left. */
    public void awaitIdle() {
        try {
            handle.queue().awaitIdle(DEFAULT_TIMEOUT);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Every file anywhere under the mirror root, as {@code /}-separated relative paths, sorted. */
    public List<String> mirroredFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(mirrorRoot)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> mirrorRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /**
     * WARN and ERROR lines from the agent's own log.
     *
     * <p>When a scenario test fails on a counter, the counter alone does not say why. This makes
     * the agent's own account of the failure part of the test output.
     */
    public List<String> problemLines() {
        try (Stream<Path> stream = Files.list(config.logFolder())) {
            final List<Path> logs =
                    stream.filter(path -> path.getFileName().toString().endsWith(".log")).toList();
            final List<String> lines = new ArrayList<>();
            for (final Path log : logs) {
                for (final String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                    if (line.contains("[WARN]") || line.contains("[ERROR]")) {
                        lines.add(line);
                    }
                }
            }
            return lines;
        } catch (final IOException e) {
            return List.of("could not read the agent log: " + e);
        }
    }

    public static void await(BooleanSupplier condition, String description) {
        await(condition, description, DEFAULT_TIMEOUT);
    }

    public static void await(BooleanSupplier condition, String description, Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + description, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    /** Restarts nothing; exposed so a test can start a second agent on the same folders. */
    public Path baseDirectory() {
        return baseDirectory;
    }

    @Override
    public void close() {
        handle.stop();
        logger.close();
    }
}
