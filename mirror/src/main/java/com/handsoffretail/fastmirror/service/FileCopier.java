package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.CopyResult;
import java.nio.file.Path;

/**
 * Performs one copy: open the source with retry, transfer, stamp the timestamp, publish atomically
 * (SRS 9.5).
 *
 * <p><strong>The sole custodian of SRS 5.1.</strong> Its implementation is the only class in the
 * codebase permitted to open a file inside the source folder (AR-10), which is what makes the
 * producer non-interference rules reviewable in one place and enforceable by AT-02.
 */
public interface FileCopier {

    /**
     * Copy one file, absorbing every failure into a {@link CopyResult} (FR-29).
     *
     * @param relative            the path of the file relative to both roots
     * @param eventNanos          the {@link System#nanoTime()} stamp of the event that asked for
     *                            this copy, used to measure capture latency
     * @param followedCreateEvent whether the work was triggered by a CREATE rather than a MODIFY or
     *                            a sweep. Combined with the prior existence of the target, this is
     *                            what distinguishes a producer rewriting a file from a producer
     *                            reusing a file name for new content (FR-31, U-1).
     * @return the outcome; never {@code null}, and this method never throws
     */
    CopyResult copy(Path relative, long eventNanos, boolean followedCreateEvent);

    /**
     * Whether the source and the target hold different bytes.
     *
     * <p>Used only when {@code CONTENT_VERIFY} is enabled (SRS 9.4). It lives on the copier rather
     * than on {@code DifferenceDetector} because reading the source is the copier's exclusive
     * privilege (AR-10); a detector that opened the source itself would be a second, unaudited path
     * to the producer's files.
     *
     * @return {@code true} if they differ or either could not be read, {@code false} if identical
     */
    boolean contentsDiffer(Path relative);
}
