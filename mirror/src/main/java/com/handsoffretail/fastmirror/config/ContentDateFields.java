package com.handsoffretail.fastmirror.config;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names of the XML fields that hold a file's business date, tried in order.
 *
 * <p>Each name matches an element or an attribute, with or without a namespace prefix:
 *
 * <pre>
 *   &lt;BusinessDate&gt;2026-09-22&lt;/BusinessDate&gt;                     Gilbarco Passport (Wolf)
 *   &lt;vs:period ... periodEndDate="2025-10-04T23:45:06-04:00"/&gt;    Verifone reports (Tiki)
 *   &lt;closedTime&gt;2026-09-13T21:40:45-04:00&lt;/closedTime&gt;         Verifone day journal, CPJ (Tiki)
 * </pre>
 *
 * <p>The first name in the list that is found wins, and its first occurrence in the file is used.
 * The date is the first {@code yyyy-MM-dd} of the value <strong>exactly as written</strong>: no
 * time-zone conversion, because {@code 2025-10-04T23:45:06-04:00} is the store's 4 October and
 * converting it would make it the 5th.
 *
 * <p>Order matters where a file holds more than one candidate. A Verifone CPJ's first {@code <date>}
 * is the start of the period, the day before; that is why the field to name for it is
 * {@code closedTime}.
 */
public final class ContentDateFields {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    /** An optional namespace prefix such as {@code vs:}. */
    private static final String PREFIX = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?";

    private final List<String> names;
    private final List<Pattern> patterns;

    private ContentDateFields(List<String> names, List<Pattern> patterns) {
        this.names = List.copyOf(names);
        this.patterns = List.copyOf(patterns);
    }

    /**
     * @param raw a comma-separated list such as {@code periodEndDate,closedTime}
     * @throws IllegalArgumentException with an operator-readable reason if the list is unusable
     */
    public static ContentDateFields parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        final List<String> names = new ArrayList<>();
        final List<Pattern> patterns = new ArrayList<>();
        for (final String part : raw.split(",")) {
            final String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "'" + name + "' is not an XML field name. Use the bare name, such as"
                                + " BusinessDate or periodEndDate, without < > or quotes");
            }
            if (names.contains(name)) {
                continue;
            }
            names.add(name);
            final String quoted = Pattern.quote(name);
            patterns.add(
                    Pattern.compile(
                            // <BusinessDate>2026-09-22  or  <ns:closedTime attr="x">2026-...
                            "<" + PREFIX + quoted + "(?:\\s[^>]*)?>\\s*(\\d{4}-\\d{2}-\\d{2})"
                                    // periodEndDate="2025-10-04T...  or  ns:attr='...'
                                    + "|[\\s:]" + quoted + "\\s*=\\s*[\"']\\s*(\\d{4}-\\d{2}-\\d{2})"));
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("the list is empty");
        }
        return new ContentDateFields(names, patterns);
    }

    /**
     * The business date in the start of a file.
     *
     * @param head   the first bytes of the file
     * @param length how many of them are valid
     * @return the date of the first listed field found, or empty if none is present or none holds a
     *         real date
     */
    public Optional<LocalDate> extract(byte[] head, int length) {
        Objects.requireNonNull(head, "head");
        // Field names and dates are plain ASCII, so ISO-8859-1 reads them correctly from both
        // ISO-8859-1 (Passport) and UTF-8 (Verifone) files, even if the read ended mid-character.
        final String text = new String(head, 0, Math.min(length, head.length), StandardCharsets.ISO_8859_1);
        for (final Pattern pattern : patterns) {
            final Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                final String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                try {
                    return Optional.of(LocalDate.parse(value));
                } catch (final DateTimeException e) {
                    // Not a real date at this occurrence; try the next one.
                }
            }
        }
        return Optional.empty();
    }

    public List<String> names() {
        return names;
    }

    @Override
    public String toString() {
        return String.join(",", names);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContentDateFields that && names.equals(that.names);
    }

    @Override
    public int hashCode() {
        return names.hashCode();
    }
}
