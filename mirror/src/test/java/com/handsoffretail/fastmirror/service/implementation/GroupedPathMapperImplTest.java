package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.handsoffretail.fastmirror.entity.Selection;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GroupedPathMapperImplTest {

    private final Path sourceRoot = Path.of("C:/archive").toAbsolutePath();
    private final Path mirrorRoot = Path.of("C:/mirrored").toAbsolutePath();
    private final GroupedPathMapperImpl mapper = new GroupedPathMapperImpl(sourceRoot, mirrorRoot);

    private static String slashed(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Test
    @DisplayName("date first, then type: <yyyy>/<month>/<yyyy-MM-dd>/<PREFIX>/<name>")
    void dayFolder() {
        final Path target =
                mapper.targetFor(
                        new Selection("CPJ"), Path.of("CPJ432.xml"), LocalDate.of(2026, 10, 15));

        assertEquals("2026/october/2026-10-15/CPJ/CPJ432.xml", slashed(target));
    }

    @Test
    @DisplayName("an extra-folder file keeps only its own name, in its folder's type folder")
    void subfolderFile() {
        final Path target =
                mapper.targetFor(
                        new Selection("PassportReports"),
                        Path.of("PassportReports", "StoreClose20261013001017.pdf"),
                        LocalDate.of(2026, 10, 12));

        assertEquals(
                "2026/october/2026-10-12/PassportReports/StoreClose20261013001017.pdf", slashed(target));
    }

    @Test
    @DisplayName("root and extra-folder files of one business day share that day's folder")
    void oneFolderPerDay() {
        final LocalDate day = LocalDate.of(2026, 10, 12);

        final Path xml = mapper.targetFor(new Selection("PJR"), Path.of("PJR1.xml"), day);
        final Path pdf =
                mapper.targetFor(new Selection("PassportReports"), Path.of("PassportReports", "S.pdf"), day);

        assertEquals(xml.getParent().getParent(), pdf.getParent().getParent());
        assertEquals("2026/october/2026-10-12", slashed(xml.getParent().getParent()));
    }

    @Test
    @DisplayName("the month rolls over between 30 September and 1 October")
    void monthBoundary() {
        final Selection pjr = new Selection("PJR");

        assertEquals(
                "2026/september/2026-09-30/PJR/a.xml",
                slashed(mapper.targetFor(pjr, Path.of("a.xml"), LocalDate.of(2026, 9, 30))));
        assertEquals(
                "2026/october/2026-10-01/PJR/b.xml",
                slashed(mapper.targetFor(pjr, Path.of("b.xml"), LocalDate.of(2026, 10, 1))));
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
        final Selection cpj = new Selection("CPJ");

        assertEquals(
                "2026/december/2026-12-31/CPJ/CPJ1.xml",
                slashed(mapper.targetFor(cpj, Path.of("CPJ1.xml"), LocalDate.of(2026, 12, 31))));
        assertEquals(
                "2027/january/2027-01-01/CPJ/CPJ2.xml",
                slashed(mapper.targetFor(cpj, Path.of("CPJ2.xml"), LocalDate.of(2027, 1, 1))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", ".", "../x", "../../../../outside", "a/b", "a\\b", "C:"})
    @DisplayName("a type folder that is not one plain folder name is rejected, so nothing escapes")
    void rejectsEscape(String type) {
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.targetFor(new Selection(type), Path.of("x.pdf"), LocalDate.of(2026, 1, 1)));
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
