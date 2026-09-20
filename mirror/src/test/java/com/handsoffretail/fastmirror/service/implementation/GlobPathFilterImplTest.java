package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.service.PathFilter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GlobPathFilterImplTest {

    /** The documented default of SRS 7.1. */
    private static final List<String> DEFAULTS = List.of("~$*", "*.tmp", "*.fmtmp");

    private final PathFilter filter = new GlobPathFilterImpl(DEFAULTS);

    @ParameterizedTest
    @ValueSource(strings = {"~$doc.xml", "x.tmp", "y.fmtmp"})
    @DisplayName("UT-08: the default patterns exclude office lock files and temp files")
    void ut08_excludesDefaults(String fileName) {
        assertTrue(filter.isExcluded(Path.of("C:", "src", fileName)), fileName + " should be excluded");
    }

    @Test
    @DisplayName("UT-08: a real payload file is not excluded")
    void ut08_allowsPayload() {
        assertFalse(filter.isExcluded(Path.of("C:", "src", "PJR9.xml")));
    }

    @Test
    @DisplayName("patterns match at any depth, because they match the file name only")
    void matchesAtAnyDepth() {
        assertTrue(filter.isExcluded(Path.of("C:", "src", "a", "b", "deep.tmp")));
        assertFalse(filter.isExcluded(Path.of("C:", "src", "tmp", "real.xml")));
    }

    @Test
    @DisplayName("a directory named like an excluded file is still excluded, and never opened")
    void matchesDirectoryNames() {
        assertTrue(filter.isExcluded(Path.of("C:", "src", "scratch.tmp")));
    }

    @Test
    @DisplayName("an empty pattern list excludes nothing")
    void emptyListExcludesNothing() {
        final PathFilter none = new GlobPathFilterImpl(List.of());

        assertFalse(none.isExcluded(Path.of("C:", "src", "x.tmp")));
    }

    @Test
    @DisplayName("blank and null patterns are ignored rather than matching everything")
    void ignoresBlankPatterns() {
        final GlobPathFilterImpl lenient =
                new GlobPathFilterImpl(java.util.Arrays.asList("  ", null, "*.tmp"));

        assertFalse(lenient.isExcluded(Path.of("C:", "src", "PJR9.xml")));
        assertTrue(lenient.isExcluded(Path.of("C:", "src", "x.tmp")));
        assertTrue(lenient.patterns().contains("*.tmp"));
    }

    @Test
    @DisplayName("an invalid glob fails fast at construction, not silently at match time")
    void rejectsInvalidGlob() {
        assertThrows(IllegalArgumentException.class, () -> new GlobPathFilterImpl(List.of("[")));
    }
}
