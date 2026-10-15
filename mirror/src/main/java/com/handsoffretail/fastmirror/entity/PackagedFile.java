package com.handsoffretail.fastmirror.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * One mirror file as it goes into a zip.
 *
 * @param path     the zip entry name: the path below the store folder, {@code /}-separated, such as
 *                 {@code 2026/october/2026-10-12/PJR/PJR3402610130004181533189.xml}
 * @param size     its size in bytes
 * @param modified its modified time, which the copier carries over from the POS's file
 */
public record PackagedFile(String path, long size, Instant modified) {

    public PackagedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(modified, "modified");
        if (path.isBlank() || size < 0) {
            throw new IllegalArgumentException("Invalid packaged file: " + path + " (" + size + ")");
        }
    }

    /** The file's own name, the last part of its path. */
    public String name() {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
