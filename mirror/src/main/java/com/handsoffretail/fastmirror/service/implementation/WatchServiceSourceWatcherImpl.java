package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.FileEvent;
import com.handsoffretail.fastmirror.entity.FileEventKind;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.MirrorService;
import com.handsoffretail.fastmirror.service.SourceWatcherService;
import com.sun.nio.file.ExtendedWatchEventModifier;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Registers one recursive watch on the source tree and translates notifications (FR-01, C-5).
 *
 * <p><strong>This thread does nothing but drain the notification buffer.</strong> Windows holds
 * pending notifications in a fixed-size buffer; when it fills, notifications are silently dropped
 * and Java reports OVERFLOW (C-6). Every millisecond spent here is a millisecond of buffer pressure,
 * so translation is trivial and the resulting event is handed straight to the event thread.
 *
 * <p>{@link ExtendedWatchEventModifier#FILE_TREE} registers the whole tree in one call. Registering
 * each directory separately would be slower and would race against directory creation — a
 * subdirectory created and filled before its watch was registered would be missed entirely.
 *
 * <p>The watch holds an open handle on the source directory, which means that directory itself
 * cannot be renamed or deleted by another process while FastMirror runs (C-11, R-8). Files inside it
 * are completely unaffected, which is what matters for the prime directive.
 */
public final class WatchServiceSourceWatcherImpl implements SourceWatcherService {

    private final MirrorConfig config;
    private final MirrorService sink;
    private final AppLogger logger;
    private final Consumer<Throwable> fatalHandler;

    private WatchService watchService;
    private Thread watcherThread;
    private volatile boolean running;

    /**
     * @param fatalHandler invoked when the watch fails or becomes invalid. FastMirror must not keep
     *                     running blind (FR-23); in production this terminates the process with a
     *                     non-zero code so a service manager restarts it.
     */
    public WatchServiceSourceWatcherImpl(
            MirrorConfig config,
            MirrorService sink,
            AppLogger logger,
            Consumer<Throwable> fatalHandler) {
        this.config = Objects.requireNonNull(config, "config");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.fatalHandler = Objects.requireNonNull(fatalHandler, "fatalHandler");
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            config.sourceFolder()
                    .register(
                            watchService,
                            new WatchEvent.Kind<?>[] {
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE
                            },
                            ExtendedWatchEventModifier.FILE_TREE);
        } catch (final IOException | UnsupportedOperationException e) {
            throw new IllegalStateException(
                    "Could not watch " + config.sourceFolder() + ": " + e.getMessage(), e);
        }

        running = true;
        watcherThread = new Thread(this::watch, "fastmirror-watcher");
        watcherThread.setDaemon(false);
        watcherThread.start();
    }

    @Override
    public void stop() {
        running = false;
        final WatchService service = watchService;
        if (service != null) {
            try {
                service.close();
            } catch (final IOException e) {
                logger.warn("Could not close the watch service: " + e);
            }
        }
        final Thread thread = watcherThread;
        if (thread != null) {
            try {
                thread.join(5_000L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void watch() {
        while (running) {
            final WatchKey key;
            try {
                key = watchService.take();
            } catch (final ClosedWatchServiceException e) {
                return; // Normal shutdown.
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Stamped once, as close to receipt as possible: this is the zero point of the capture
            // latency measurement (SRS 9.5).
            final long eventNanos = System.nanoTime();

            try {
                for (final WatchEvent<?> event : key.pollEvents()) {
                    translate(key, event, eventNanos);
                }
            } catch (final Throwable t) {
                logger.error("Failed to translate a watch event", t);
            }

            if (!key.reset()) {
                // The watched directory is gone or the key is no longer valid. Continuing would
                // mean running blind, which is worse than stopping (FR-23).
                final IllegalStateException failure =
                        new IllegalStateException(
                                "The watch on " + config.sourceFolder()
                                        + " is no longer valid. The folder may have been deleted"
                                        + " or renamed.");
                logger.error("Watch failed", failure);
                running = false;
                fatalHandler.accept(failure);
                return;
            }
        }
    }

    private void translate(WatchKey key, WatchEvent<?> event, long eventNanos) {
        final WatchEvent.Kind<?> kind = event.kind();

        if (kind == StandardWatchEventKinds.OVERFLOW) {
            sink.accept(FileEvent.overflow(eventNanos));
            return;
        }

        final Object context = event.context();
        if (!(context instanceof Path relative)) {
            return;
        }

        final Path base =
                key.watchable() instanceof Path watched ? watched : config.sourceFolder();
        final Path absolute = base.resolve(relative);

        final FileEventKind translated;
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            translated = FileEventKind.CREATED;
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            translated = FileEventKind.MODIFIED;
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            translated = FileEventKind.DELETED;
        } else {
            return;
        }

        sink.accept(new FileEvent(translated, absolute, eventNanos));
    }
}
