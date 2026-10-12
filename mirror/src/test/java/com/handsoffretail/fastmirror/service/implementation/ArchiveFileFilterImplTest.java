package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.GroupBy;
import com.handsoffretail.fastmirror.entity.Selection;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchiveFileFilterImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    private Map<String, String> values;

    @BeforeEach
    void setUp() {
        values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, "CPJ,HRS");
    }

    private ArchiveFileFilterImpl filter() {
        final MirrorConfig config = MirrorConfig.load(values::get, baseDirectory);
        return new ArchiveFileFilterImpl(config, new GlobPathFilterImpl(config.excludePatterns()));
    }

    private Optional<Selection> select(String relative) {
        return filter().select(Path.of(relative));
    }

    @Test
    @DisplayName("a root file with a selected prefix goes to its upper-case prefix folder")
    void selectedPrefix() {
        assertEquals(Optional.of(new Selection("CPJ", GroupBy.DATE)), select("CPJ432.xml"));
        assertEquals(Optional.of(new Selection("HRS", GroupBy.DATE)), select("hrs7.xml"));
        assertEquals(Optional.of(new Selection("CPJ", GroupBy.DATE)), select("cPj1.XmL"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SUM1.xml", // prefix not selected
                "CPJ1.txt", // wrong extension
                "CPJ1", // no extension
                "CPJ1.", // empty extension
                "CP.xml", // shorter than a prefix plus extension
                ".xml", // no name
                "CPJ1.tmp", // excluded pattern, and wrong extension
                "CPJ1.xml.1.fmtmp", // FastMirror's own staging name
                "~$CPJ1.xml" // excluded office lock file
            })
    @DisplayName("root files that do not match are not selected")
    void unselectedRootFiles(String name) {
        assertEquals(Optional.empty(), select(name));
    }

    @Test
    @DisplayName("GROUP_BY applies to root files")
    void groupByMonthForRoot() {
        values.put(MirrorConfig.KEY_GROUP_BY, "month");

        assertEquals(Optional.of(new Selection("CPJ", GroupBy.MONTH)), select("CPJ1.xml"));
    }

    @Test
    @DisplayName("the extension list can be changed")
    void configurableExtensions() {
        values.put(MirrorConfig.KEY_INCLUDE_EXTENSIONS, "xml, .txt");

        assertTrue(select("CPJ1.txt").isPresent());
        assertTrue(select("CPJ1.xml").isPresent());
    }

    @Test
    @DisplayName("an included subfolder selects pdf files with no prefix filter, always by month")
    void subfolderFiles() {
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, "Journals");

        assertEquals(
                Optional.of(new Selection("Journals", GroupBy.MONTH)),
                select("Journals/anything at all.pdf"));
        assertEquals(
                Optional.of(new Selection("journals", GroupBy.MONTH)),
                select("journals/upper.PDF"),
                "the folder name is taken as it appears in the source");
        assertEquals(Optional.empty(), select("Journals/CPJ1.xml"), "xml is not a subfolder extension");
        assertEquals(Optional.empty(), select("Journals/deeper/x.pdf"), "nested folders are ignored");
        assertEquals(Optional.empty(), select("Other/x.pdf"), "a folder not listed is ignored");
    }

    @Test
    @DisplayName("files in subfolders are ignored when no subfolder is included")
    void noSubfoldersByDefault() {
        assertEquals(Optional.empty(), select("Journals/x.pdf"));
        assertEquals(Optional.empty(), select("Journals/CPJ1.xml"));
    }

    @Test
    @DisplayName("COPY_FROM_DATE is inclusive, and everything is in range when it is not set")
    void copyFromDate() {
        assertTrue(filter().withinDateRange(LocalDate.of(1990, 1, 1)));

        values.put(MirrorConfig.KEY_COPY_FROM_DATE, "2026-10-15");
        final ArchiveFileFilterImpl filter = filter();

        assertTrue(filter.withinDateRange(DAY));
        assertTrue(filter.withinDateRange(DAY.plusYears(1)));
        assertFalse(filter.withinDateRange(DAY.minusDays(1)));
    }
}
