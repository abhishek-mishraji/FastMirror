package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.handsoffretail.fastmirror.entity.GroupBy;
import com.handsoffretail.fastmirror.entity.Selection;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupedPathMapperImplTest {

    private final Path sourceRoot = Path.of("C:/archive").toAbsolutePath();
    private final Path mirrorRoot = Path.of("C:/mirrored").toAbsolutePath();
    private final GroupedPathMapperImpl mapper = new GroupedPathMapperImpl(sourceRoot, mirrorRoot);

    private static String slashed(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Test
    @DisplayName("GROUP_BY=DATE: <PREFIX>/<yyyy>/<month>/<yyyy-MM-dd>/<name>")
    void groupByDate() {
        final Path target =
                mapper.targetFor(
                        new Selection("CPJ", GroupBy.DATE), Path.of("CPJ432.xml"), LocalDate.of(2026, 10, 15));

        assertEquals("CPJ/2026/october/2026-10-15/CPJ432.xml", slashed(target));
    }

    @Test
    @DisplayName("GROUP_BY=MONTH: <PREFIX>/<yyyy>/<month>/<name>")
    void groupByMonth() {
        final Path target =
                mapper.targetFor(
                        new Selection("CPJ", GroupBy.MONTH), Path.of("CPJ432.xml"), LocalDate.of(2026, 1, 1));

        assertEquals("CPJ/2026/january/CPJ432.xml", slashed(target));
    }

    @Test
    @DisplayName("a subfolder file keeps only its own name below the month folder")
    void subfolderFile() {
        final Path target =
                mapper.targetFor(
                        new Selection("Journals", GroupBy.MONTH),
                        Path.of("Journals", "j.pdf"),
                        LocalDate.of(2026, 12, 31));

        assertEquals("Journals/2026/december/j.pdf", slashed(target));
    }

    @Test
    @DisplayName("all twelve month names are lower-case English, whatever the PC's language")
    void monthNames() {
        final List<String> expected =
                List.of(
                        "january", "february", "march", "april", "may", "june",
                        "july", "august", "september", "october", "november", "december");
        for (final Month month : Month.values()) {
            assertEquals(
                    expected.get(month.ordinal()),
                    GroupedPathMapperImpl.monthName(LocalDate.of(2026, month, 1)));
        }
    }

    @Test
    @DisplayName("the year rolls over between 31 December and 1 January")
    void yearBoundary() {
        final Selection cpj = new Selection("CPJ", GroupBy.DATE);

        assertEquals(
                "CPJ/2026/december/2026-12-31/CPJ1.xml",
                slashed(mapper.targetFor(cpj, Path.of("CPJ1.xml"), LocalDate.of(2026, 12, 31))));
        assertEquals(
                "CPJ/2027/january/2027-01-01/CPJ2.xml",
                slashed(mapper.targetFor(cpj, Path.of("CPJ2.xml"), LocalDate.of(2027, 1, 1))));
    }

    @Test
    @DisplayName("a destination folder that would escape the mirror root is rejected")
    void rejectsEscape() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mapper.targetFor(
                                new Selection("../../outside", GroupBy.MONTH),
                                Path.of("x.pdf"),
                                LocalDate.of(2026, 1, 1)));
    }

    @Test
    @DisplayName("a folder outside the source root is addressed by its name, ignoring case")
    void externalFolder() {
        final Path reports = Path.of("Z:/PassportReports").toAbsolutePath();
        final GroupedPathMapperImpl withReports =
                new GroupedPathMapperImpl(sourceRoot, mirrorRoot, java.util.Map.of("PassportReports", reports));
        final Path pdf = reports.resolve("StoreClose20260924000416.pdf");

        final Path relative = withReports.relativize(pdf);

        assertEquals(Path.of("PassportReports", "StoreClose20260924000416.pdf"), relative);
        assertEquals(pdf, withReports.toSource(relative));
        assertEquals(pdf, withReports.toSource(Path.of("passportreports/storeclose20260924000416.pdf")).getParent()
                .resolve("StoreClose20260924000416.pdf"));
        assertEquals(sourceRoot.resolve("CPJ1.xml"), withReports.toSource(Path.of("CPJ1.xml")));
        assertThrows(IllegalArgumentException.class, () -> withReports.toSource(Path.of("PassportReports", "..", "..", "x")));
    }

    @Test
    @DisplayName("relativize, toSource and toTarget keep their containment checks")
    void containment() {
        assertEquals(Path.of("CPJ1.xml"), mapper.relativize(sourceRoot.resolve("CPJ1.xml")));
        assertEquals(sourceRoot.resolve("CPJ1.xml"), mapper.toSource(Path.of("CPJ1.xml")));
        assertThrows(IllegalArgumentException.class, () -> mapper.relativize(mirrorRoot.resolve("x")));
        assertThrows(IllegalArgumentException.class, () -> mapper.relativize(sourceRoot));
        assertThrows(IllegalArgumentException.class, () -> mapper.toTarget(Path.of("..", "x")));
        assertThrows(IllegalArgumentException.class, () -> mapper.toSource(mirrorRoot));
    }
}
