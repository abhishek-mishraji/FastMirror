package com.handsoffretail.fastmirror.entity;

import java.util.Objects;

/**
 * Why a source file is copied, and therefore where it goes.
 *
 * @param destinationFolder the first folder below the mirror root: the upper-case prefix for a root
 *                          file ({@code CPJ}), or the subfolder name for a subfolder file
 *                          ({@code Journals})
 * @param groupBy           the grouping below {@code <yyyy>/<month>}
 */
public record Selection(String destinationFolder, GroupBy groupBy) {

    public Selection {
        Objects.requireNonNull(destinationFolder, "destinationFolder");
        Objects.requireNonNull(groupBy, "groupBy");
        if (destinationFolder.isBlank()) {
            throw new IllegalArgumentException("destinationFolder must not be blank");
        }
    }
}
