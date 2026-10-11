package com.handsoffretail.fastmirror.entity;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One stable source file, ready to be copied.
 *
 * @param relativeSource the file's path relative to the source root, e.g. {@code CPJ432.xml}
 * @param relativeTarget where it goes, relative to the mirror root, e.g.
 *                       {@code CPJ/2026/october/2026-10-15/CPJ432.xml}
 * @param key            the ledger entry recorded once the copy is in place
 */
public record CopyJob(Path relativeSource, Path relativeTarget, LedgerKey key) {

    public CopyJob {
        Objects.requireNonNull(relativeSource, "relativeSource");
        Objects.requireNonNull(relativeTarget, "relativeTarget");
        Objects.requireNonNull(key, "key");
    }
}
