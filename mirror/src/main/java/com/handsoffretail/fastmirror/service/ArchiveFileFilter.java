package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.Selection;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Decides which source files are copied, and into which destination folder.
 *
 * <ul>
 *   <li>A file directly in the source root is selected when its first three characters match
 *       {@code INCLUDE_PREFIXES} (ignoring case) and its extension is in
 *       {@code INCLUDE_EXTENSIONS}.
 *   <li>A file directly in an {@code INCLUDE_SUBFOLDERS} folder is selected when its extension is
 *       in {@code SUBFOLDER_EXTENSIONS}. There is no prefix filter there.
 *   <li>Anything deeper, and anything matching {@code EXCLUDE_PATTERNS}, is not selected.
 * </ul>
 *
 * <p>The date check is separate, so a file's date is only worked out once it is known to be wanted.
 *
 * <p>A pure function of the path, the date and the configuration. Performs no I/O.
 */
public interface ArchiveFileFilter {

    /**
     * @param relative the file's path relative to the source root
     * @return where the file goes, or empty if it is not copied
     */
    Optional<Selection> select(Path relative);

    /** Whether a file with this date is on or after {@code COPY_FROM_DATE}, if one is set. */
    boolean withinDateRange(LocalDate fileDate);
}
