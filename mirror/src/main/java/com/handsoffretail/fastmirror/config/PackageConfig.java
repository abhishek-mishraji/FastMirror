package com.handsoffretail.fastmirror.config;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Day packaging: once business day D is over, each of its type folders in the mirror is zipped into
 * the outbox, where the S3 agent picks it up.
 *
 * <p>Present in {@link MirrorConfig} only when {@code PACKAGE_ENABLED=true}.
 *
 * @param storeFolder     the store's folder in the outbox and in S3, and the first part of every
 *                        zip's name, such as {@code wolf_800_NY-13208}
 * @param outboxFolder    where finished zips appear; the S3 agent's {@code WATCH_FOLDER}
 * @param stagingFolder   where a zip is built before it is moved into the outbox; on the same drive
 *                        as the outbox, so that the move is one atomic step
 * @param packageAtUtc    day D is packaged at this UTC time on D+1
 * @param packageFromDate the oldest business day packaged; {@code null} packages every day found
 * @param outboxKeepDays  zips older than this many days are removed from the outbox; {@code 0}
 *                        keeps them forever. Never affects S3.
 */
public record PackageConfig(
        String storeFolder,
        Path outboxFolder,
        Path stagingFolder,
        LocalTime packageAtUtc,
        LocalDate packageFromDate,
        int outboxKeepDays) {

    public PackageConfig {
        Objects.requireNonNull(storeFolder, "storeFolder");
        Objects.requireNonNull(outboxFolder, "outboxFolder");
        Objects.requireNonNull(stagingFolder, "stagingFolder");
        Objects.requireNonNull(packageAtUtc, "packageAtUtc");
        if (outboxKeepDays < 0) {
            throw new IllegalArgumentException("outboxKeepDays must not be negative");
        }
    }

    /** The store's own folder inside the outbox: {@code <outbox>/<store>}. */
    public Path storeOutbox() {
        return outboxFolder.resolve(storeFolder);
    }
}
