package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.PackageRecord;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import java.util.List;
import java.util.Optional;

/** The zips that reached the outbox, kept in {@code state/package.ledger} across restarts. */
public interface PackageLedger {

    /** The highest version recorded for this folder, if any. */
    Optional<PackageRecord> latest(TypeFolder folder);

    /**
     * Records a zip that is in the outbox.
     *
     * @return {@code false} if it could not be written; the zip is found and recorded again at
     *         the next start
     */
    boolean record(PackageRecord record);

    /** Every record, in the order written. */
    List<PackageRecord> all();
}
