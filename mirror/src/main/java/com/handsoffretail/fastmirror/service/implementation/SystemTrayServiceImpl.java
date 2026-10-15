package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyStats;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.StatsReporter;
import com.handsoffretail.fastmirror.service.TrayService;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

/**
 * A notification-area icon built on {@link SystemTray}.
 *
 * <ul>
 *   <li>On start, a Windows notification: FastMirror is running, copying from the source folder to
 *       the mirror folder.
 *   <li>Hovering shows the same, plus how many files have been copied.
 *   <li>Right-click gives a menu with a live status line, the two folders, and Exit.
 *   <li>Double-click shows the notification again, as a quick "is it still running?" check.
 * </ul>
 *
 * <p>Exit asks for confirmation. Stopping the agent means new Archive files stop reaching the
 * mirror, and therefore S3, until it is started again. A stray click in the notification area
 * should not be able to do that.
 *
 * <p>Nothing here can slow the copy path. The tray has its own refresh thread and the AWT event
 * thread, reads the counters without locking (they are {@code LongAdder}s), and never touches a
 * file.
 */
public final class SystemTrayServiceImpl implements TrayService {

    private static final String PRODUCT = "FastMirror";

    /** How often the hover text and status line are refreshed. */
    private static final long REFRESH_SECONDS = 5L;

    /** Windows truncates notification-area tooltips at 127 characters. */
    private static final int TOOLTIP_LIMIT = 127;

    /** The longest {@link #close()} waits for the icon to be removed. */
    private static final long CLOSE_WAIT_MS = 2_000L;

    private static final Color RUNNING_GREEN = new Color(0x1E, 0x8E, 0x3E);

    private final MirrorConfig config;
    private final StatsReporter stats;
    private final AppLogger logger;
    private final Runnable onExit;

    private final AtomicBoolean exitRequested = new AtomicBoolean();

    private volatile TrayIcon icon;
    private volatile MenuItem statusItem;
    private volatile ScheduledExecutorService refresher;

    /**
     * @param onExit what Exit does once confirmed: stop the agent and end the process. Runs on its
     *               own thread, never on the AWT event thread, so the menu does not freeze while
     *               in-flight copies are allowed to finish.
     */
    public SystemTrayServiceImpl(
            MirrorConfig config, StatsReporter stats, AppLogger logger, Runnable onExit) {
        this.config = Objects.requireNonNull(config, "config");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.onExit = Objects.requireNonNull(onExit, "onExit");
    }

    @Override
    public boolean show() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            logger.info("No system tray in this session; running without a tray icon");
            return false;
        }
        try {
            EventQueue.invokeAndWait(this::install);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final InvocationTargetException e) {
            logger.warn("Could not create the tray icon; running without one: " + e.getCause());
            return false;
        }
        if (icon == null) {
            return false;
        }

        refresher =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            final Thread thread = new Thread(runnable, "fastmirror-tray");
                            thread.setDaemon(true);
                            return thread;
                        });
        refresher.scheduleWithFixedDelay(
                () -> EventQueue.invokeLater(this::refresh),
                REFRESH_SECONDS,
                REFRESH_SECONDS,
                TimeUnit.SECONDS);
        return true;
    }

    /**
     * Removes the icon, waiting at most {@link #CLOSE_WAIT_MS}.
     *
     * <p>The wait is bounded because this can be reached from a shutdown hook. By then the JDK's
     * own AWT shutdown hook may already have stopped the Windows message thread, and removing a
     * tray icon needs that thread: an unbounded wait here once hung the process for good after
     * Exit, keeping {@code FastMirror.exe} locked. If the icon cannot be removed in time, Windows
     * clears it once it notices the owning process has gone.
     */
    @Override
    public void close() {
        final ScheduledExecutorService scheduler = refresher;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        final TrayIcon current = icon;
        if (current == null) {
            return;
        }
        icon = null;
        final Runnable remove = () -> SystemTray.getSystemTray().remove(current);
        if (EventQueue.isDispatchThread()) {
            remove.run();
            return;
        }
        final CountDownLatch removed = new CountDownLatch(1);
        EventQueue.invokeLater(
                () -> {
                    try {
                        remove.run();
                    } finally {
                        removed.countDown();
                    }
                });
        try {
            removed.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- AWT event thread only

    private void install() {
        final PopupMenu menu = new PopupMenu();

        final MenuItem status = new MenuItem(statusLine(stats.lifetime()));
        status.setEnabled(false);
        menu.add(status);

        final MenuItem from = new MenuItem("From:  " + config.sourceFolder());
        from.setEnabled(false);
        menu.add(from);

        final MenuItem to = new MenuItem("To:      " + config.mirrorFolder());
        to.setEnabled(false);
        menu.add(to);

        menu.addSeparator();

        final MenuItem exit = new MenuItem("Exit " + PRODUCT);
        exit.addActionListener(event -> confirmExit());
        menu.add(exit);

        final TrayIcon trayIcon = new TrayIcon(drawIcon(), tooltip(stats.lifetime()), menu);
        trayIcon.setImageAutoSize(true);
        // On Windows this fires on double-click.
        trayIcon.addActionListener(event -> announce(trayIcon));

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (final AWTException e) {
            logger.warn("The system tray refused the icon; running without one: " + e.getMessage());
            return;
        }

        statusItem = status;
        icon = trayIcon;
        announce(trayIcon);
    }

    private void announce(TrayIcon trayIcon) {
        trayIcon.displayMessage(
                PRODUCT + " is running",
                "Copying files\nfrom  " + config.sourceFolder() + "\nto      " + config.mirrorFolder(),
                TrayIcon.MessageType.INFO);
    }

    private void refresh() {
        final TrayIcon current = icon;
        final MenuItem status = statusItem;
        if (current == null || status == null) {
            return;
        }
        final CopyStats snapshot = stats.lifetime();
        status.setLabel(statusLine(snapshot));
        current.setToolTip(tooltip(snapshot));
    }

    private void confirmExit() {
        if (exitRequested.get()) {
            return;
        }
        // An invisible always-on-top owner: without one, a dialog opened from the notification area
        // can appear behind other windows and look as if Exit did nothing.
        final JFrame owner = new JFrame();
        owner.setUndecorated(true);
        owner.setAlwaysOnTop(true);
        owner.setLocationRelativeTo(null);
        owner.setVisible(true);
        final int answer;
        try {
            answer =
                    JOptionPane.showConfirmDialog(
                            owner,
                            "Stop " + PRODUCT + "?\n\n"
                                    + "New files in\n    " + config.sourceFolder() + "\n"
                                    + "will not be copied to\n    " + config.mirrorFolder() + "\n"
                                    + "until " + PRODUCT + " is started again. Nothing is lost:\n"
                                    + "they are copied then.",
                            PRODUCT,
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
        } finally {
            owner.dispose();
        }
        if (answer != JOptionPane.YES_OPTION || !exitRequested.compareAndSet(false, true)) {
            return;
        }

        logger.info("Exit requested from the tray icon");
        // Remove the icon now, on this thread, while Windows messaging still works. Leaving it to
        // the shutdown hook raced the JDK's AWT shutdown and could hang the process forever.
        close();
        final Thread exitThread = new Thread(onExit, "fastmirror-exit");
        exitThread.setDaemon(false);
        exitThread.start();
    }

    // ---------------------------------------------------------------- pure formatting

    /** The status line at the top of the menu, e.g. {@code Running - 1,204 files copied}. */
    static String statusLine(CopyStats snapshot) {
        final StringBuilder line =
                new StringBuilder("Running - ")
                        .append(String.format(Locale.ROOT, "%,d", copied(snapshot)))
                        .append(copied(snapshot) == 1L ? " file copied" : " files copied");
        final long failed = snapshot.count(CopyOutcome.FAILED);
        if (failed > 0L) {
            line.append(String.format(Locale.ROOT, ", %,d failed (see log)", failed));
        }
        return line.toString();
    }

    /** Hover text, kept inside the Windows 127-character limit. */
    static String tooltip(MirrorConfig config, CopyStats snapshot) {
        final String text =
                PRODUCT + ": running\n"
                        + config.sourceFolder() + " -> " + config.mirrorFolder() + "\n"
                        + String.format(Locale.ROOT, "%,d copied", copied(snapshot));
        return text.length() <= TOOLTIP_LIMIT ? text : text.substring(0, TOOLTIP_LIMIT - 3) + "...";
    }

    private String tooltip(CopyStats snapshot) {
        return tooltip(config, snapshot);
    }

    /** Everything that put a complete file in the mirror. */
    private static long copied(CopyStats snapshot) {
        return snapshot.count(CopyOutcome.COPIED) + snapshot.count(CopyOutcome.REPLACED);
    }

    /** A green disc with a white arrow: "copying". Drawn in code, so there is no icon file to ship. */
    private static Image drawIcon() {
        final int size = 64;
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(RUNNING_GREEN);
            g.fillOval(2, 2, size - 4, size - 4);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(16, 32, 44, 32);
            g.drawLine(34, 21, 46, 32);
            g.drawLine(34, 43, 46, 32);
        } finally {
            g.dispose();
        }
        return image;
    }
}
