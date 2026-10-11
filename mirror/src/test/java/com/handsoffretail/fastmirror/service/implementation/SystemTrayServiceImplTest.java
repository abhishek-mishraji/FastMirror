package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyOutcome;
import com.handsoffretail.fastmirror.entity.CopyStats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The text the tray shows. The icon and menu themselves need a real desktop and are verified by
 * running the exe; what the operator actually reads is verified here.
 */
class SystemTrayServiceImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;

    private static CopyStats stats(long copied, long replaced, long failed) {
        final Map<CopyOutcome, Long> counts = new EnumMap<>(CopyOutcome.class);
        counts.put(CopyOutcome.COPIED, copied);
        counts.put(CopyOutcome.REPLACED, replaced);
        counts.put(CopyOutcome.FAILED, failed);
        return new CopyStats(counts);
    }

    private MirrorConfig config(Path source, Path mirror) {
        final Map<String, String> values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, source.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirror.toString());
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, "CPJ");
        return MirrorConfig.load(values::get, baseDirectory);
    }

    @Test
    @DisplayName("the status line counts every file that reached the mirror")
    void statusLineCountsCopiedAndReplaced() {
        assertEquals(
                "Running - 1,200 files copied",
                SystemTrayServiceImpl.statusLine(stats(1_000L, 200L, 0L)));
    }

    @Test
    @DisplayName("the status line reads naturally for a single file and for none")
    void statusLineSingularAndZero() {
        assertEquals("Running - 1 file copied", SystemTrayServiceImpl.statusLine(stats(1L, 0L, 0L)));
        assertEquals("Running - 0 files copied", SystemTrayServiceImpl.statusLine(stats(0L, 0L, 0L)));
    }

    @Test
    @DisplayName("failures are surfaced in the menu, with a pointer to the log")
    void statusLineShowsFailures() {
        assertEquals(
                "Running - 50 files copied, 3 failed (see log)",
                SystemTrayServiceImpl.statusLine(stats(50L, 0L, 3L)));
    }

    @Test
    @DisplayName("the hover text names both folders and the running state")
    void tooltipNamesBothFolders() {
        final String tooltip =
                SystemTrayServiceImpl.tooltip(config(sourceRoot, mirrorRoot), stats(7L, 0L, 0L));

        assertTrue(tooltip.startsWith("FastMirror: running"), tooltip);
        assertTrue(tooltip.contains(" -> "), tooltip);
        assertTrue(tooltip.endsWith("7 copied") || tooltip.endsWith("..."), tooltip);
        assertTrue(tooltip.length() <= 127, "Windows truncates tray tooltips at 127 characters");
    }

    @Test
    @DisplayName("very long folder paths are shortened to fit the Windows tooltip limit")
    void tooltipIsTruncatedForLongPaths() throws Exception {
        final Path deepSource =
                Files.createDirectories(
                        sourceRoot.resolve("a-very-long-folder-name-for-a-store-pc")
                                .resolve("another-very-long-folder-name")
                                .resolve("out"));

        final String tooltip =
                SystemTrayServiceImpl.tooltip(config(deepSource, mirrorRoot), stats(1L, 0L, 0L));

        assertTrue(tooltip.length() <= 127, "was " + tooltip.length() + ": " + tooltip);
        assertTrue(tooltip.endsWith("..."), tooltip);
    }
}
