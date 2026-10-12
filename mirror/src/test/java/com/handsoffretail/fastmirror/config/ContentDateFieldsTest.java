package com.handsoffretail.fastmirror.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Header layouts copied from the real Wolf (Gilbarco Passport) and Tiki (Verifone) sample files. */
class ContentDateFieldsTest {

    /** Wolf MCM3402609230009421533209.xml: written 23 Sept, business day 22 Sept. */
    static final String WOLF_REPORT =
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<NAXML-MovementReport version=\"3.4\">\n"
                    + "<TransmissionHeader>\n<StoreLocationID>13</StoreLocationID>\n</TransmissionHeader>\n"
                    + "<MerchandiseCodeMovement>\n<MovementHeader>\n<PrimaryReportPeriod>98</PrimaryReportPeriod>\n"
                    + "<BusinessDate>2026-09-22</BusinessDate>\n<BeginDate>2026-09-22</BeginDate>\n"
                    + "<EndDate>2026-09-23</EndDate>\n</MovementHeader>\n";

    /** Wolf PJR3402609230004181533189.xml: a sale at 00:04 on 23 Sept, business day 22 Sept. */
    static final String WOLF_JOURNAL =
            "<JournalHeader><BeginDate>2026-09-19</BeginDate><EndDate>2100-01-01</EndDate></JournalHeader>"
                    + "<SaleEvent><EventStartDate>2026-09-23</EventStartDate>"
                    + "<BusinessDate>2026-09-22</BusinessDate><ReceiptDate>2026-09-23</ReceiptDate>";

    /** Tiki SUM2025-10-04-252.xml: the period ended 4 Oct at 23:45 store time. */
    static final String TIKI_REPORT =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><pd:summaryPd xmlns:vs=\"urn:vfi-sapphire:vs.2001-10-01\">"
                    + "<vs:period sysid=\"2\" periodType=\"day\" name=\"Day\" periodSeqNum=\"252\""
                    + " periodBeginDate=\"2025-10-03T23:45:08-04:00\" periodEndDate=\"2025-10-04T23:45:06-04:00\" />";

    /** Tiki CPJ2026-09-13-590.xml: the first {@code <date>} is the day before. */
    static final String TIKI_DAY_JOURNAL =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><transSet periodID=\"2\" periodname=\"Day\""
                    + " longId=\"2026-09-13T21:40:45-04:00\" shortId=\"590\">"
                    + "<openedTime>2026-09-12T21:40:31-04:00</openedTime>"
                    + "<closedTime>2026-09-13T21:40:45-04:00</closedTime>"
                    + "<trans type=\"journal\"><trHeader><date>2026-09-12T21:42:24-04:00</date>";

    private static final ContentDateFields WOLF = ContentDateFields.parse("BusinessDate");
    private static final ContentDateFields TIKI = ContentDateFields.parse("periodEndDate,closedTime");

    private static Optional<LocalDate> extract(ContentDateFields fields, String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return fields.extract(bytes, bytes.length);
    }

    @Test
    @DisplayName("Wolf report: BusinessDate, not the next-day EndDate or file name")
    void wolfReport() {
        assertEquals(Optional.of(LocalDate.of(2026, 9, 22)), extract(WOLF, WOLF_REPORT));
    }

    @Test
    @DisplayName("Wolf journal: BusinessDate, although EventStartDate (the next day) comes first")
    void wolfJournal() {
        assertEquals(Optional.of(LocalDate.of(2026, 9, 22)), extract(WOLF, WOLF_JOURNAL));
    }

    @Test
    @DisplayName("Tiki report: periodEndDate, a namespaced attribute, taken exactly as written")
    void tikiReport() {
        // 23:45 at -04:00 is still the 4th in the store; converting zones would make it the 5th.
        assertEquals(Optional.of(LocalDate.of(2025, 10, 4)), extract(TIKI, TIKI_REPORT));
    }

    @Test
    @DisplayName("Tiki CPJ: closedTime, not openedTime or the first <date> (both the day before)")
    void tikiDayJournal() {
        assertEquals(Optional.of(LocalDate.of(2026, 9, 13)), extract(TIKI, TIKI_DAY_JOURNAL));
    }

    @Test
    @DisplayName("fields are tried in the configured order")
    void order() {
        final ContentDateFields openedFirst = ContentDateFields.parse("openedTime,closedTime");

        assertEquals(Optional.of(LocalDate.of(2026, 9, 12)), extract(openedFirst, TIKI_DAY_JOURNAL));
    }

    @Test
    @DisplayName("names must match exactly: periodEndDate does not match periodEndDateX or a prefix of it")
    void exactNames() {
        final ContentDateFields fields = ContentDateFields.parse("EndDate");

        assertEquals(Optional.empty(), extract(fields, TIKI_REPORT), "periodEndDate is not EndDate");
        assertEquals(
                Optional.empty(),
                extract(ContentDateFields.parse("closedTim"), TIKI_DAY_JOURNAL));
    }

    @Test
    @DisplayName("a file without the field, such as a Wolf acknowledgement, gives no date")
    void noField() {
        final String ack = "<ACKHEADER><REFERENCEID>????</REFERENCEID><STATUSCODE>Success</STATUSCODE></ACKHEADER>";

        assertEquals(Optional.empty(), extract(WOLF, ack));
    }

    @Test
    @DisplayName("an impossible date is skipped in favour of the next occurrence")
    void invalidDateSkipped() {
        final String text = "<BusinessDate>2026-13-40</BusinessDate><BusinessDate>2026-09-22</BusinessDate>";

        assertEquals(Optional.of(LocalDate.of(2026, 9, 22)), extract(WOLF, text));
    }

    @Test
    @DisplayName("only the bytes given as valid are searched")
    void respectsLength() {
        final byte[] bytes = WOLF_REPORT.getBytes(StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), WOLF.extract(bytes, 100));
    }

    @Test
    @DisplayName("names are trimmed and de-duplicated")
    void parsing() {
        assertEquals(List.of("periodEndDate", "closedTime"), ContentDateFields.parse(" periodEndDate , closedTime,periodEndDate ").names());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " , ", "<BusinessDate>", "Business Date", "\"closedTime\"", "1date"})
    @DisplayName("unusable field lists are rejected")
    void rejects(String raw) {
        assertThrows(IllegalArgumentException.class, () -> ContentDateFields.parse(raw));
    }
}
