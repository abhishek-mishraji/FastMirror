package com.handsoffretail.fastmirror.service;

import java.nio.file.Path;

/**
 * Decides whether a path is excluded from mirroring ({@code EXCLUDE_PATTERNS}).
 *
 * <p>A pure function. An excluded path is never opened, which matters: exclusion is checked before
 * any source access, so a filtered file is never touched at all, not even opened read-only.
 */
public interface PathFilter {

    /** Whether this path matches any configured exclusion pattern. */
    boolean isExcluded(Path path);
}
