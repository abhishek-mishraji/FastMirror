package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.LedgerKey;

/**
 * The persistent record of every file already copied.
 *
 * <p>It is what stops a file being copied twice. The destination cannot be used for that, because
 * the S3 upload agent or a retention job may remove files from it, and the Archive still holds the
 * originals. The ledger survives restarts. Deleting it makes the agent copy everything again.
 */
public interface CopyLedger {

    /** Whether this exact file has already been copied. */
    boolean contains(LedgerKey key);

    /**
     * Records a completed copy and forces it to disk.
     *
     * @return {@code false} if it could not be written. The copy is still in place; the file will
     *         simply be copied again after a restart.
     */
    boolean record(LedgerKey key);

    /** How many files are recorded. */
    int size();
}
