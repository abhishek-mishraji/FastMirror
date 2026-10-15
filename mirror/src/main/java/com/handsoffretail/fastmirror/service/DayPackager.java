package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.FolderContent;
import com.handsoffretail.fastmirror.entity.PackageManifest;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Builds the zip of one type folder of one business day.
 *
 * <p>Reads the mirror only. It never reads the POS folders, never changes the mirror, and never
 * changes a zip already in the outbox. A zip appears in the outbox only when it is complete and
 * verified: it is built in the staging folder and moved into the outbox in one step.
 */
public interface DayPackager {

    /** Every business day's type folders in the mirror, oldest first. Reads folder names only. */
    List<TypeFolder> typeFolders() throws IOException;

    /**
     * What a type folder holds now, from file attributes only. FastMirror's own {@code *.fmtmp}
     * staging files, which are still being written, are left out.
     */
    FolderContent list(TypeFolder folder) throws IOException;

    /**
     * The type folder's own modified time. Adding, removing or replacing a file inside changes it,
     * so an unchanged time means an unchanged folder, without listing its files.
     */
    Instant modified(TypeFolder folder) throws IOException;

    /**
     * Zips {@code content} as version {@code version} and moves it into the outbox.
     *
     * @param createdAt the time written into the manifest; the same files and the same
     *                  {@code createdAt} always give a byte-identical zip
     * @return the zip's manifest
     * @throws IOException if any file cannot be read or changed while it was being zipped; then
     *                     nothing reaches the outbox and the staging file is removed
     * @throws IllegalStateException if the outbox already holds that version
     */
    PackageManifest build(FolderContent content, int version, Instant createdAt) throws IOException;

    /** Where version {@code version} of {@code folder} sits in the outbox. */
    Path outboxZip(TypeFolder folder, int version);

    /**
     * The manifest of a zip already in the outbox, once every file in it has been checked against
     * that manifest; empty if the zip is damaged or not FastMirror's.
     */
    Optional<PackageManifest> verify(Path zip);

    /** Removes staging files left by a build that was interrupted, such as by a power cut. */
    void clearStaging() throws IOException;
}
