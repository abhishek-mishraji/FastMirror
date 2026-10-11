package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.CopyResult;

/**
 * Performs one copy: open the source with retry, transfer, stamp the timestamp, publish atomically.
 *
 * <p><strong>The sole custodian of the non-interference rules.</strong> Its implementation is the
 * only class in the codebase permitted to open a file inside the source folder, which is what makes
 * those rules reviewable in one place and enforceable by AT-02.
 */
public interface FileCopier {

    /**
     * Copy one file, absorbing every failure into a {@link CopyResult}.
     *
     * @return the outcome; never {@code null}, and this method never throws
     */
    CopyResult copy(CopyJob job);
}
