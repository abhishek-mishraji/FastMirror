package com.handsoffretail.fastmirror.config;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the date - and optionally the time - sits in a file name, written as a template lined up
 * against the name.
 *
 * <ul>
 *   <li>{@code yyyy} - 4-digit year; {@code yy} - 2-digit year, taken as 20yy
 *   <li>{@code MM} - 2-digit month; {@code dd} - 2-digit day
 *   <li>{@code HH} - 2-digit hour (00-23); {@code mm} - minute; {@code ss} - second (optional;
 *       with a time, {@code BUSINESS_DAY_STARTS_AT} can move an after-midnight file to the day
 *       before)
 *   <li>{@code ?} - any one character; {@code *} - any number of characters
 *   <li>anything else must appear literally (ignoring case)
 * </ul>
 *
 * <p>The template must match the whole file name. Examples:
 *
 * <pre>
 *   ???yyyy-MM-dd*         CPJ2026-09-13-590.xml           -> 2026-09-13
 *   ??????yyMMdd*          FGM3402609230009241533201.xml   -> 2026-09-23
 *   *yyyyMMddHHmmss.pdf    StoreClose20260924000416.pdf    -> 2026-09-24 00:04:16
 * </pre>
 *
 * <p>A single {@code y}, {@code M}, {@code d}, {@code H}, {@code m} or {@code s} is ordinary text,
 * so {@code *.pdf} works; to match a pair of those letters literally, use {@code ??}. Runs of the
 * wrong length, such as {@code yyy}, are rejected, and so is a template missing the year, the month
 * or the day, or giving minutes without hours. Silently matching the wrong characters would put
 * files in the wrong folder.
 */
public final class FileNameDatePattern {

    /**
     * What a file name says.
     *
     * @param date the date
     * @param time the time of day, or {@code null} if the template has no {@code HH}
     */
    public record Stamp(LocalDate date, LocalTime time) {

        public Stamp {
            Objects.requireNonNull(date, "date");
        }
    }

    private final String template;
    private final Pattern regex;
    private final boolean twoDigitYear;
    private final boolean hasHour;
    private final boolean hasMinute;
    private final boolean hasSecond;

    private FileNameDatePattern(
            String template,
            Pattern regex,
            boolean twoDigitYear,
            boolean hasHour,
            boolean hasMinute,
            boolean hasSecond) {
        this.template = template;
        this.regex = regex;
        this.twoDigitYear = twoDigitYear;
        this.hasHour = hasHour;
        this.hasMinute = hasMinute;
        this.hasSecond = hasSecond;
    }

    /**
     * @throws IllegalArgumentException with an operator-readable reason if the template is unusable
     */
    public static FileNameDatePattern parse(String template) {
        Objects.requireNonNull(template, "template");
        final String trimmed = template.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("the pattern is empty");
        }

        final StringBuilder regex = new StringBuilder("^");
        int years = 0;
        int months = 0;
        int days = 0;
        int hours = 0;
        int minutes = 0;
        int seconds = 0;
        boolean twoDigitYear = false;

        int i = 0;
        while (i < trimmed.length()) {
            final char c = trimmed.charAt(i);
            int run = 1;
            while (i + run < trimmed.length() && trimmed.charAt(i + run) == c) {
                run++;
            }

            if (c == 'y' && (run == 4 || run == 2)) {
                regex.append(run == 4 ? "(?<year>\\d{4})" : "(?<year>\\d{2})");
                twoDigitYear = run == 2;
                years++;
            } else if (c == 'M' && run == 2) {
                regex.append("(?<month>\\d{2})");
                months++;
            } else if (c == 'd' && run == 2) {
                regex.append("(?<day>\\d{2})");
                days++;
            } else if (c == 'H' && run == 2) {
                regex.append("(?<hour>\\d{2})");
                hours++;
            } else if (c == 'm' && run == 2) {
                regex.append("(?<minute>\\d{2})");
                minutes++;
            } else if (c == 's' && run == 2) {
                regex.append("(?<second>\\d{2})");
                seconds++;
            } else if ("yMdHms".indexOf(c) >= 0 && run > 1) {
                throw new IllegalArgumentException(
                        "'" + trimmed.substring(i, i + run) + "' at position " + (i + 1)
                                + " is not a date or time field. Use yyyy or yy, MM, dd, HH, mm, ss,"
                                + " and ? for any other single character");
            } else {
                // A single letter is ordinary text, as in ".pdf"; ? and * are wildcards.
                for (int k = 0; k < run; k++) {
                    if (c == '?') {
                        regex.append('.');
                    } else if (c == '*') {
                        regex.append(".*");
                    } else {
                        regex.append(Pattern.quote(String.valueOf(c)));
                    }
                }
            }
            i += run;
        }
        regex.append('$');

        if (years != 1 || months != 1 || days != 1) {
            throw new IllegalArgumentException(
                    "the pattern must contain the year (yyyy or yy), MM and dd exactly once each");
        }
        if (hours > 1 || minutes > 1 || seconds > 1) {
            throw new IllegalArgumentException("HH, mm and ss may each appear only once");
        }
        if ((minutes == 1 || seconds == 1) && hours == 0) {
            throw new IllegalArgumentException("mm or ss needs HH as well");
        }
        // Group names cannot repeat, so a duplicated field already failed the counts above.
        return new FileNameDatePattern(
                trimmed,
                Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE),
                twoDigitYear,
                hours == 1,
                minutes == 1,
                seconds == 1);
    }

    /**
     * The date in {@code fileName}, or empty if the name does not fit the template or the digits are
     * not a real date (such as month 13).
     */
    public Optional<LocalDate> extract(String fileName) {
        return extractStamp(fileName).map(Stamp::date);
    }

    /**
     * The date and, if the template has {@code HH}, the time in {@code fileName}; empty if the name
     * does not fit or the digits are not a real date and time.
     */
    public Optional<Stamp> extractStamp(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        final Matcher matcher = regex.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(matcher.group("year"));
        if (twoDigitYear) {
            year += 2000;
        }
        try {
            final LocalDate date =
                    LocalDate.of(
                            year,
                            Integer.parseInt(matcher.group("month")),
                            Integer.parseInt(matcher.group("day")));
            LocalTime time = null;
            if (hasHour) {
                time =
                        LocalTime.of(
                                Integer.parseInt(matcher.group("hour")),
                                hasMinute ? Integer.parseInt(matcher.group("minute")) : 0,
                                hasSecond ? Integer.parseInt(matcher.group("second")) : 0);
            }
            return Optional.of(new Stamp(date, time));
        } catch (final DateTimeException e) {
            return Optional.empty();
        }
    }

    /** Whether the template reads a time of day. */
    public boolean hasTime() {
        return hasHour;
    }

    /** The template as written in .env. */
    public String template() {
        return template;
    }

    @Override
    public String toString() {
        return template;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FileNameDatePattern that && template.equals(that.template);
    }

    @Override
    public int hashCode() {
        return template.hashCode();
    }
}
