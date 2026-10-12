package com.handsoffretail.fastmirror.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileNameDatePatternTest {

    private static Optional<LocalDate> extract(String template, String name) {
        return FileNameDatePattern.parse(template).extract(name);
    }

    @Test
    @DisplayName("store 1: CPJ2026-09-13-590.xml with ???yyyy-MM-dd*")
    void storeOneLayout() {
        assertEquals(
                Optional.of(LocalDate.of(2026, 9, 13)),
                extract("???yyyy-MM-dd*", "CPJ2026-09-13-590.xml"));
    }

    @Test
    @DisplayName("store 2: FGM3402609230009241533201.xml with ??????yyMMdd*")
    void storeTwoLayout() {
        assertEquals(
                Optional.of(LocalDate.of(2026, 9, 23)),
                extract("??????yyMMdd*", "FGM3402609230009241533201.xml"));
    }

    @ParameterizedTest
    @CsvSource({
        "yyyyMMdd*, 20261231_x.pdf, 2026-12-31",
        "*_dd-MM-yyyy.pdf, journal_05-01-2027.pdf, 2027-01-05",
        "???_yyyy_MM_dd.xml, HRS_2026_02_28.XML, 2026-02-28"
    })
    @DisplayName("other layouts, literal parts ignoring case")
    void otherLayouts(String template, String name, LocalDate expected) {
        assertEquals(Optional.of(expected), extract(template, name));
    }

    @Test
    @DisplayName("Passport PDFs: *yyyyMMddHHmmss.pdf reads the print date and time")
    void passportPdfTimestamp() {
        final FileNameDatePattern pattern = FileNameDatePattern.parse("*yyyyMMddHHmmss.pdf");

        assertEquals(
                Optional.of(new FileNameDatePattern.Stamp(LocalDate.of(2026, 9, 24), java.time.LocalTime.of(0, 4, 16))),
                pattern.extractStamp("StoreClose20260924000416.pdf"));
        assertEquals(
                Optional.of(new FileNameDatePattern.Stamp(LocalDate.of(2026, 9, 23), java.time.LocalTime.of(12, 28, 15))),
                pattern.extractStamp("ShiftClose20260923122815.pdf"));
        assertTrue(pattern.hasTime());
        assertEquals(Optional.empty(), pattern.extractStamp("StoreClose20260924250416.pdf"), "hour 25");
    }

    @Test
    @DisplayName("a template without HH has no time")
    void noTime() {
        final FileNameDatePattern pattern = FileNameDatePattern.parse("???yyyy-MM-dd*");

        assertEquals(
                Optional.of(new FileNameDatePattern.Stamp(LocalDate.of(2026, 9, 13), null)),
                pattern.extractStamp("CPJ2026-09-13-590.xml"));
    }

    @ParameterizedTest
    @CsvSource({
        "???yyyy-MM-dd*, CPJ_summary.xml", // no date at all
        "???yyyy-MM-dd*, CPJ2026-13-01-1.xml", // month 13
        "???yyyy-MM-dd*, CPJ2026-02-30-1.xml", // 30 February
        "???yyyy-MM-dd*, CP2026-09-13-590.xml", // date one character early
        "???_yyyy_MM_dd.xml, HRS_2026_02_28.xml.bak" // the template must match the whole name
    })
    @DisplayName("names that do not fit, or are not real dates, give no date")
    void noDate(String template, String name) {
        assertEquals(Optional.empty(), extract(template, name));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "", // empty
                "???yyyy-MM*", // no day
                "???MM-dd*", // no year
                "yyyyMMddMM*", // month twice
                "???yyy-MM-dd*", // a 3-letter year
                "???yyyy-mm-dd*", // lower-case mm: a typo, leaving no month
                "???yyyy-MM-d*", // a single d is text, leaving no day
                "???yyyy-MMM-dd*", // a 3-letter month
                "*yyyyMMddmmss*", // minutes without hours
                "*yyyyMMddHHHH*", // a 4-letter hour
                "*yyyyMMddHHmmHH*" // hour twice
            })
    @DisplayName("unusable templates are rejected with a reason")
    void rejectsBadTemplates(String template) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> FileNameDatePattern.parse(template));
        assertTrue(!thrown.getMessage().isBlank());
    }
}
