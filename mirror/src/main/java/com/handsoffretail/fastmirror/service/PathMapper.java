package com.handsoffretail.fastmirror.service;

import java.nio.file.Path;

/**
 * Translates between source paths, mirror paths and the relative path that identifies a file in
 * both trees (SRS 6.2, FR-06).
 *
 * <p>A pure function of its two configured roots. Performs no I/O whatsoever, which is what makes
 * it safe to call on the latency-critical event thread (SRS 9.3).
 */
public interface PathMapper {

    /**
     * The path of {@code sourceFile} relative to the source root.
     *
     * @throws IllegalArgumentException if {@code sourceFile} lies outside the source root (UT-02)
     */
    Path relativize(Path sourceFile);

    /**
     * The mirror path for a relative path.
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
