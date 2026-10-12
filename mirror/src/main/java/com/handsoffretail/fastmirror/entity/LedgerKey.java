package com.handsoffretail.fastmirror.entity;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Identifies one source file in the copy ledger.
 *
 * <p>The relative path alone is not enough: if the producer deletes a file and later creates a new
 * one under the same name, that is new content and must be copied. The creation time tells the two
 * apart. The size is included so that a file which does change after being copied, contrary to
 * expectation, is copied again rather than silently left stale.
 *
 * <p>The path is stored with {@code /} separators and in lower case, because Windows file names are
 * case-insensitive and a case-only rename is not new content.
 *
 * @param relativePath   the path relative to the source root, normalized as described above
 * @param creationMillis the source file's creation time, in epoch milliseconds
 * @param size           the source file's size in bytes
 */
public record LedgerKey(String relativePath, long creationMillis, long size) {

    private static final char FIELD_SEPARATOR = '\t';

    public LedgerKey {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        if (relativePath.indexOf(FIELD_SEPARATOR) >= 0 || relativePath.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("relativePath contains a reserved character");
        }
    }

    /** The key for a source file. */
    public static LedgerKey of(Path relative, long creationMillis, long size) {
        Objects.requireNonNull(relative, "relative");
        final String normalized =
                relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return new LedgerKey(normalized, creationMillis, size);
    }

    /** One ledger line, without the line terminator. */
    public String toLine() {
        return creationMillis + String.valueOf(FIELD_SEPARATOR) + size + FIELD_SEPARATOR + relativePath;
    }

    /**
     * Parses a ledger line.
     *
     * @return the key, or empty if the line is malformed - typically the last line of a ledger whose
     *         write was cut short by a power failure
     */
    public static Optional<LedgerKey> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        final int first = line.indexOf(FIELD_SEPARATOR);
        final int second = first < 0 ? -1 : line.indexOf(FIELD_SEPARATOR, first + 1);
        if (first <= 0 || second <= first + 1 || second == line.length() - 1) {
            return Optional.empty();
        }
        try {
            final long creation = Long.parseLong(line.substring(0, first));
            final long size = Long.parseLong(line.substring(first + 1, second));
            if (size < 0L) {
                return Optional.empty();
            }
            return Optional.of(new LedgerKey(line.substring(second + 1), creation, size));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
