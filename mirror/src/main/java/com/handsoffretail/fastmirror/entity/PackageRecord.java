package com.handsoffretail.fastmirror.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One line of {@code package.ledger}: a zip that reached the outbox.
 *
 * <p>The line is {@code <business date> TAB <type> TAB <version> TAB <created at, UTC> TAB <file count>
 * TAB <fingerprint>}, for example
 * {@code 2026-10-12	PJR	1	2026-10-13T08:00:04Z	913	9f2c...}.
 *
 * @param folder      the type folder that was zipped
 * @param version     1 for the first zip of that folder, then 2, 3, ... for late files
 * @param createdAt   when the zip was built, UTC
 * @param fileCount   how many files it holds, not counting {@code manifest.json}
 * @param fingerprint {@link FolderContent#fingerprint()} of what it holds
 */
public record PackageRecord(
        TypeFolder folder, int version, Instant createdAt, int fileCount, String fingerprint) {

    private static final Pattern TAB = Pattern.compile("\t");

    public PackageRecord {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (version < 1 || fileCount < 0) {
            throw new IllegalArgumentException("Invalid package record: v" + version + ", " + fileCount);
        }
    }

    public String toLine() {
        return folder.businessDate() + "\t" + folder.type() + "\t" + version + "\t" + createdAt + "\t"
                + fileCount + "\t" + fingerprint;
    }

    /** The record on a ledger line, or empty for a torn or foreign line. */
    public static Optional<PackageRecord> parse(String line) {
        final String[] parts = TAB.split(line.strip(), -1);
        if (parts.length != 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new PackageRecord(
                            new TypeFolder(LocalDate.parse(parts[0]), parts[1]),
                            Integer.parseInt(parts[2]),
                            Instant.parse(parts[3]),
                            Integer.parseInt(parts[4]),
                            parts[5]));
        } catch (final DateTimeParseException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
