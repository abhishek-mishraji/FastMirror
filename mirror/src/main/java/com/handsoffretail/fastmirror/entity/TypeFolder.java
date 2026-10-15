package com.handsoffretail.fastmirror.entity;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;

/**
 * One type's files for one business day: the mirror folder {@code <yyyy>/<month>/<yyyy-MM-dd>/<TYPE>},
 * which is exactly what one zip holds.
 *
 * @param businessDate the business day
 * @param type         the type folder's name, such as {@code PJR} or {@code PassportReports}
 */
public record TypeFolder(LocalDate businessDate, String type) {

    public TypeFolder {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(type, "type");
        if (type.isBlank() || type.equals(".") || type.equals("..") || type.contains("/")
                || type.contains("\\") || type.contains(":")) {
            throw new IllegalArgumentException("Not a type folder name: " + type);
        }
    }

    /** {@code <yyyy>/<month>/<yyyy-MM-dd>}, relative to the store folder, as {@code /}-separated text. */
    public String dayPath() {
        return businessDate.getYear() + "/" + monthName(businessDate) + "/" + businessDate;
    }

    /** {@code <yyyy>/<month>/<yyyy-MM-dd>/<TYPE>}, relative to the store folder. */
    public String folderPath() {
        return dayPath() + "/" + type;
    }

    /** The same folder below {@code root}: the mirror, or the store's folder in the outbox. */
    public Path resolveIn(Path root) {
        return root.resolve(String.valueOf(businessDate.getYear()))
                .resolve(monthName(businessDate))
                .resolve(businessDate.toString())
                .resolve(type);
    }

    /** {@code <store>_<TYPE>_<yyyy-MM-dd>_v<n>.zip}. */
    public String zipName(String store, int version) {
        return store + "_" + type + "_" + businessDate + "_v" + version + ".zip";
    }

    /** {@code january} ... {@code december}, independent of the PC's language settings. */
    public static String monthName(LocalDate date) {
        return date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return folderPath();
    }
}
