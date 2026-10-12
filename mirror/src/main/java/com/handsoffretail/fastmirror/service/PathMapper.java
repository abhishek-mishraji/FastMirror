package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.Selection;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Translates between source paths, relative paths and the grouped destination layout.
 *
 * <p>A pure function of its two configured roots. Performs no I/O whatsoever.
 */
public interface PathMapper {

    /**
     * The path of {@code sourceFile} relative to the source root.
     *
     * @throws IllegalArgumentException if {@code sourceFile} lies outside the source root
     */
    Path relativize(Path sourceFile);

    /**
     * The destination of a selected file, relative to the mirror root:
     * {@code <folder>/<yyyy>/<month>/<yyyy-MM-dd>/<name>} when grouped by date, or
     * {@code <folder>/<yyyy>/<month>/<name>} when grouped by month. The month is the lower-case
     * English name, such as {@code october}.
     *
     * @throws IllegalArgumentException if the result would escape the mirror root
     */
    Path targetFor(Selection selection, Path relativeSource, LocalDate fileDate);

    /**
     * The mirror path for a relative target path.
     *
     * @throws IllegalArgumentException if {@code relative} is absolute or escapes the mirror root
     */
    Path toTarget(Path relative);

    /**
     * The source path for a relative path.
     *
     * @throws IllegalArgumentException if {@code relative} is absolute or escapes the source root
     */
    Path toSource(Path relative);

    /** The configured source root, absolute and normalized. */
    Path sourceRoot();

    /** The configured mirror root, absolute and normalized. */
    Path mirrorRoot();
}
