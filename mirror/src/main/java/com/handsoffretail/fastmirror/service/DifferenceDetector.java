package com.handsoffretail.fastmirror.service;

import java.nio.file.Path;

/**
 * Decides whether the mirrored copy of a file is out of date (SRS 9.4).
 *
 * <p>Reads file attributes only. It never opens file contents itself: when {@code CONTENT_VERIFY}
 * is enabled it delegates to {@link FileCopier#contentsDiffer(Path)}, keeping all source access on
 * the single audited path (AR-10, NI-12).
 *
 * <p>Called by the copy worker rather than by the event thread, because the attribute reads it
 * performs would add latency to the one path that has to stay fast (SRS 9.3).
 */
public interface DifferenceDetector {

    /**
     * Whether the target must be replaced.
     *
     * @return {@code false} if the source has vanished — there is nothing left to copy
     */
    boolean needsCopy(Path relative);
}
