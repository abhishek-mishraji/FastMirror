package com.handsoffretail.fastmirror.service;

/**
 * The Windows system-tray presence of a running agent (SRS 9.11).
 *
 * <p>Tells a person at the machine three things without opening a log: that FastMirror is running,
 * where it is copying from and to, and how to stop it.
 *
 * <p>Purely a window onto the agent. It never touches a source or mirror file, and the agent works
 * identically without it — as a Windows service there is no desktop to show a tray on, and that is
 * fine.
 */
public interface TrayService {

    /**
     * Shows the tray icon and the startup notification.
     *
     * @return {@code false} if this session has no system tray; the agent carries on regardless
     */
    boolean show();

    /** Removes the icon, so no ghost icon is left behind in the notification area. */
    void close();
}
