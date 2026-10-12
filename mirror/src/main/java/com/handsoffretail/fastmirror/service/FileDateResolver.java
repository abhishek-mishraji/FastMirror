package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.LedgerKey;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Decides the date of a file: the date that picks its {@code <yyyy>/<month>/[<date>]} folders and is
 * compared with {@code COPY_FROM_DATE}.
 *
 * <p>Root files and subfolder files are configured separately ({@code DATE_SOURCE} and
 * {@code SUBFOLDER_DATE_SOURCE}):
 *
 * <ul>
 *   <li>{@code CREATED} - the creation time, converted to {@code TIME_ZONE};
 *   <li>{@code FILENAME} - the date in the name, found with the configured pattern;
 *   <li>{@code CONTENT} - the business date inside the file, in one of the configured fields,
 *       exactly as written.
 * </ul>
 *
 * <p>A file without a date where one is expected falls back to its creation date, with one warning.
 *
 * <p>Reading a file's content is costly compared with the other two, so it happens once per file -
 * when the file is stable and about to be copied - and the answer is remembered. That is why there
 * are two methods: one that never reads, and one that may.
 */
public interface FileDateResolver {

    /**
     * The date if it can be known without reading the file: always for {@code CREATED} and
     * {@code FILENAME}, and for {@code CONTENT} once the file has been read before.
     */
    Optional<LocalDate> knownDate(Path relative, LedgerKey key, FileTime creationTime);

    /**
     * The date, reading the start of the file if needed.
     *
     * @return empty only if the file could not be read right now (locked or gone); a later scan
     *         tries again
     */
    Optional<LocalDate> dateOf(Path relative, LedgerKey key, FileTime creationTime);

    /** Forgets remembered dates of files no longer waiting to be copied. */
    void retainOnly(Set<LedgerKey> keys);
}
