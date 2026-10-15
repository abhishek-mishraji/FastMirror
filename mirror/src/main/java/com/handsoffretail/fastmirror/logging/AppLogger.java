package com.handsoffretail.fastmirror.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The file log: one rolling file per day, 30 days retained.
 *
 * <p>Lines are {@code yyyy-MM-dd HH:mm:ss [LEVEL] message}, matching the existing HOR AWS Sync
 * Agent so an operator reads both the same way.
 *
 * <p><strong>Writing is asynchronous, and that is a non-interference requirement rather than a
 * performance nicety.</strong> A copy worker holds an open handle on one of the producer's files
 * while it runs. If logging blocked that worker — a slow volume, a full disk, an antivirus scan —
 * the handle would stay open longer, which is exactly what must never happen: a held handle can make the
 * producer's own delete or rename wait. So callers hand a line to
 * a bounded queue and return immediately, and a single daemon thread does the writing.
 *
 * <p>The queue is bounded. When it is full, lines are <em>dropped</em> and counted rather than
 * blocking the caller. Losing log lines is a nuisance; holding a producer's file open for longer is
 * a defect. The drop count is reported when the log recovers, so the loss is never silent.
 *
 * <p>File contents are never logged. Only paths, sizes, outcomes and timings.
 */
public final class AppLogger implements AutoCloseable {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String FILE_PREFIX = "fastmirror-";
    private static final String FILE_SUFFIX = ".log";

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int RETENTION_DAYS = 30;

    /** Sentinel that tells the writer thread to finish and exit. */
    private static final String POISON = new String("__FASTMIRROR_LOGGER_STOP__");

    private final Path logFolder;
    private final Clock clock;
    private final int retentionDays;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Thread writerThread;

    private volatile boolean running = true;

    // Owned exclusively by the writer thread. No lock needed, and none should be added.
    private BufferedWriter writer;
    private LocalDate openFor;

    public AppLogger(Path logFolder, Clock clock) {
        this(logFolder, clock, RETENTION_DAYS);
    }

    public AppLogger(Path logFolder, Clock clock, int retentionDays) {
        this.logFolder = Objects.requireNonNull(logFolder, "logFolder").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retentionDays = retentionDays;

        try {
            Files.createDirectories(this.logFolder);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not create the log folder " + this.logFolder, e);
        }

        this.writerThread = new Thread(this::drain, "fastmirror-logger");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public void info(String message) {
        submit(LogLevel.INFO, message, null);
    }

    public void warn(String message) {
        submit(LogLevel.WARN, message, null);
    }

    public void warn(String message, Throwable cause) {
        submit(LogLevel.WARN, message, cause);
    }

    public void error(String message) {
        submit(LogLevel.ERROR, message, null);
    }

    public void error(String message, Throwable cause) {
        submit(LogLevel.ERROR, message, cause);
    }

    /** Lines discarded because the queue was full. Zero in normal operation. */
    public long droppedLines() {
        return dropped.get();
    }

    /** The file currently being written, for the startup log line. */
    public Path currentFile() {
        return fileFor(LocalDate.now(clock));
    }

    /**
     * Stops accepting lines, drains what is already queued and closes the file.
     *
     * <p>Called last during shutdown, after the copy workers have finished, so that
     * their final outcomes are on disk.
     */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        queue.offer(POISON);
        try {
            if (!stopped.await(5, TimeUnit.SECONDS)) {
                writerThread.interrupt();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            writerThread.interrupt();
        }
    }

    private void submit(LogLevel level, String message, Throwable cause) {
        if (!running) {
            return;
        }
        final String line = render(level, message, cause);
        if (!queue.offer(line)) {
            dropped.incrementAndGet();
        }
    }

    private String render(LogLevel level, String message, Throwable cause) {
        final StringBuilder text = new StringBuilder(128);
        text.append(LocalDateTime.now(clock).format(TIMESTAMP))
                .append(" [")
                .append(level)
                .append("] ")
                .append(message == null ? "" : message);
        if (cause != null) {
            final StringWriter stack = new StringWriter();
            try (PrintWriter print = new PrintWriter(stack)) {
                cause.printStackTrace(print);
            }
            text.append(System.lineSeparator()).append(stack.toString().stripTrailing());
        }
        return text.toString();
    }

    private void drain() {
        try {
            while (true) {
                final String line = queue.take();
                // Reference equality is the point: only close() can supply this instance.
                if (line == POISON) {
                    break;
                }
                writeLine(line);
            }
            // Drain anything queued between the poison pill and now.
            for (String remaining = queue.poll(); remaining != null; remaining = queue.poll()) {
                if (remaining != POISON) {
                    writeLine(remaining);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeWriter();
            stopped.countDown();
        }
    }

    private void writeLine(String line) {
        try {
            final LocalDate today = LocalDate.now(clock);
            if (writer == null || !today.equals(openFor)) {
                rollTo(today);
            }
            final long lost = dropped.getAndSet(0L);
            if (lost > 0L) {
                writer.write(
                        LocalDateTime.now(clock).format(TIMESTAMP)
                                + " [" + LogLevel.WARN + "] "
                                + lost + " log line(s) dropped: the log queue was full."
                                + " Copy workers were not blocked.");
                writer.newLine();
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (final IOException e) {
            // The log is the only reporting channel there is, so there is nowhere to report this.
            // Fall back to stderr, which is captured when the agent runs from a console and
            // discarded when it runs windowless. Never throw: that would kill the writer thread.
            System.err.println("FastMirror: could not write to the log: " + e);
        }
    }

    private void rollTo(LocalDate date) throws IOException {
        closeWriter();
        final Path file = fileFor(date);
        writer =
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
        openFor = date;
        pruneOldLogs(date);
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (final IOException e) {
            System.err.println("FastMirror: could not close the log file: " + e);
        } finally {
            writer = null;
            openFor = null;
        }
    }

    private Path fileFor(LocalDate date) {
        return logFolder.resolve(FILE_PREFIX + date.format(FILE_DATE) + FILE_SUFFIX);
    }

    /** Deletes logs older than the retention window. Failures here must never stop logging. */
    private void pruneOldLogs(LocalDate today) {
        if (retentionDays <= 0) {
            return;
        }
        final LocalDate oldest = today.minusDays(retentionDays);
        final List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(logFolder, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (final Path entry : entries) {
                final LocalDate date = dateOf(entry);
                if (date != null && date.isBefore(oldest)) {
                    stale.add(entry);
                }
            }
        } catch (final IOException e) {
            System.err.println("FastMirror: could not list the log folder for pruning: " + e);
            return;
        }
        for (final Path file : stale) {
            try {
                Files.deleteIfExists(file);
            } catch (final IOException e) {
                System.err.println("FastMirror: could not delete the old log " + file + ": " + e);
            }
        }
    }

    private static LocalDate dateOf(Path logFile) {
        final String name = logFile.getFileName().toString();
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
            return null;
        }
        final String datePart =
                name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
        try {
            return LocalDate.parse(datePart, FILE_DATE);
        } catch (final RuntimeException e) {
            return null;
        }
    }
}
