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
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Runs a real FastMirror agent against temporary folders.
 *
 * <p>Deliberately starts the agent through {@link Application#start}, the same wiring and the same
 * startup ordering production uses. A harness that built its own object graph could drift out of
 * step with the composition root and still pass, which would make the integration tests worthless
 * exactly when they matter.
 */
public final class AgentHarness implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final Path sourceRoot;
    private final Path mirrorRoot;
    private final AppLogger logger;
    private final Application.Handle handle;

    private AgentHarness(Path sourceRoot, Path mirrorRoot, AppLogger logger, Application.Handle handle) {
        this.sourceRoot = sourceRoot;
        this.mirrorRoot = mirrorRoot;
        this.logger = logger;
        this.handle = handle;
    }

    public static AgentHarness start(Path sourceRoot, Path mirrorRoot, Path logFolder) {
        return start(sourceRoot, mirrorRoot, logFolder, Map.of());
    }

    public static AgentHarness start(
            Path sourceRoot, Path mirrorRoot, Path logFolder, Map<String, String> overrides) {
        final Map<String, String> values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
        values.putAll(overrides);

        final Clock clock = Clock.systemDefaultZone();
        final MirrorConfig config = MirrorConfig.load(values::get, logFolder);
        final AppLogger logger = new AppLogger(logFolder, clock);
        final Application.Handle handle = Application.start(config, logger, clock);
        return new AgentHarness(sourceRoot, mirrorRoot, logger, handle);
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public Path mirrorRoot() {
        return mirrorRoot;
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

    public void sweepNow() {
        handle.sweepNow();
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

    public void awaitExists(String relative) {
        await(() -> Files.isRegularFile(target(relative)), relative + " to appear in the mirror");
    }

    /** Waits until the copy queue has no work left. */
    public void awaitIdle() {
        try {
            handle.queue().awaitIdle(DEFAULT_TIMEOUT);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Files present directly under the mirror root, sorted. */
    public List<String> mirrorNames() throws IOException {
        try (var stream = Files.list(mirrorRoot)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
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

    @Override
    public void close() {
        handle.stop();
        logger.close();
    }
}
