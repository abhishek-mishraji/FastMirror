package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.PackageRecord;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Decides when each business day is zipped, and which version.
 *
 * <p>Business day D is due at {@code PACKAGE_AT_UTC} on D+1. At each check, every type folder of
 * every due day (from {@code PACKAGE_FROM_DATE}) is compared with its last zip: no zip yet builds
 * v1, different content builds the next version with all of the folder's files, and unchanged
 * content does nothing. So days missed while the agent was off are caught up at the next start.
 *
 * <p>A packaging failure is logged and retried at the next check. It never stops copying.
 */
public interface PackageScheduler extends AutoCloseable {

    /** Clears interrupted builds, checks once, then checks every minute on its own thread. */
    void start();

    /**
     * One check, on the calling thread.
     *
     * @return the zips that reached the outbox in this check
     */
    int checkNow();

    /** The latest business day with a zip, and how many zips that day has. */
    Optional<LastPackaged> lastPackaged();

    /** Stops the periodic check, letting a check in progress finish. */
    @Override
    void close();

    /**
     * @param businessDate the latest business day zipped
     * @param zips         how many type folders of that day have a zip
     */
    record LastPackaged(LocalDate businessDate, int zips) {}

    /** The zips built, for the stats line. */
    interface Listener {
        void packaged(PackageRecord record);
    }
}
