package com.handsoffretail.fastmirror.entity;

import java.util.Objects;

/**
 * Why a source file is copied, and therefore where it goes.
 *
 * @param typeFolder the type folder inside the business day's folder: the upper-case prefix
 *                   for a root file ({@code CPJ}), or the folder name for a file from an
 *                   extra folder ({@code PassportReports})
 */
public record Selection(String typeFolder) {

    public Selection {
        Objects.requireNonNull(typeFolder, "typeFolder");
        if (typeFolder.isBlank()) {
            throw new IllegalArgumentException("typeFolder must not be blank");
        }
    }
}
