package com.handsoffretail.fastmirror.entity;

/** Where a file's date, which picks its destination folder, comes from. */
public enum DateSource {

    /** The file's creation time, converted to {@code TIME_ZONE}. */
    CREATED,

    /**
     * The date written in the file name, found with a {@code FILENAME_DATE_PATTERN}. A file whose
     * name has no date falls back to {@link #CREATED}.
     */
    FILENAME,

    /**
     * The business date written inside the file, in one of the {@code CONTENT_DATE_FIELDS}, taken
     * exactly as written. A file with none of those fields falls back to {@link #CREATED}.
     */
    CONTENT
}
