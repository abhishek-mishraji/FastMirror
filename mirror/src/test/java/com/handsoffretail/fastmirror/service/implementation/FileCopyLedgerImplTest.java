package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.logging.AppLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCopyLedgerImplTest {

    @TempDir Path stateFolder;
    @TempDir Path sourceRoot;
    @TempDir Path logFolder;

    private AppLogger logger;

    @BeforeEach
    void setUp() {
        logger = new AppLogger(logFolder, Clock.systemDefaultZone());
    }

    @AfterEach
    void tearDown() {
        logger.close();
    }

    private FileCopyLedgerImpl open() throws Exception {
        return FileCopyLedgerImpl.open(stateFolder, sourceRoot, logger);
    }

    private LedgerKey keyFor(String name, long created, long size) throws Exception {
        Files.writeString(sourceRoot.resolve(name), "x");
        return LedgerKey.of(Path.of(name), created, size);
    }

    private Path ledgerFile() {
        return stateFolder.resolve(FileCopyLedgerImpl.FILE_NAME);
    }

    @Test
    @DisplayName("a recorded copy survives a reload")
    void recordAndReload() throws Exception {
        final LedgerKey key = keyFor("CPJ1.xml", 1_000L, 42L);
        final FileCopyLedgerImpl ledger = open();
        assertFalse(ledger.contains(key));

        assertTrue(ledger.record(key));
        assertTrue(ledger.contains(key));

        final FileCopyLedgerImpl reloaded = open();
        assertTrue(reloaded.contains(key));
        assertEquals(1, reloaded.size());
    }

    @Test
    @DisplayName("recording the same file twice writes one line")
    void recordIsIdempotent() throws Exception {
        final LedgerKey key = keyFor("CPJ1.xml", 1_000L, 42L);
        final FileCopyLedgerImpl ledger = open();

        ledger.record(key);
        ledger.record(key);

        assertEquals(1, Files.readAllLines(ledgerFile()).size());
    }

    @Test
    @DisplayName("the same name with a new creation time or size is a different file")
    void keyIncludesCreationAndSize() throws Exception {
        final LedgerKey original = keyFor("CPJ1.xml", 1_000L, 42L);
        final FileCopyLedgerImpl ledger = open();
        ledger.record(original);

        assertFalse(ledger.contains(LedgerKey.of(Path.of("CPJ1.xml"), 2_000L, 42L)));
        assertFalse(ledger.contains(LedgerKey.of(Path.of("CPJ1.xml"), 1_000L, 43L)));
        assertTrue(ledger.contains(LedgerKey.of(Path.of("cpj1.XML"), 1_000L, 42L)), "case-insensitive");
    }

    @Test
    @DisplayName("a torn last line from a power cut is skipped, and the rest is kept")
    void tornLastLine() throws Exception {
        final LedgerKey key = keyFor("CPJ1.xml", 1_000L, 42L);
        Files.writeString(
                ledgerFile(), key.toLine() + System.lineSeparator() + "17000\t4", StandardCharsets.UTF_8);

        final FileCopyLedgerImpl ledger = open();

        assertTrue(ledger.contains(key));
        assertEquals(1, ledger.size());
        assertEquals(List.of(key.toLine()), Files.readAllLines(ledgerFile()), "rewritten without the torn line");
    }

    @Test
    @DisplayName("compaction drops entries for files no longer in the source")
    void compaction() throws Exception {
        final LedgerKey kept = keyFor("CPJ1.xml", 1_000L, 42L);
        final LedgerKey gone = keyFor("CPJ2.xml", 1_000L, 42L);
        final FileCopyLedgerImpl ledger = open();
        ledger.record(kept);
        ledger.record(gone);
        Files.delete(sourceRoot.resolve("CPJ2.xml"));

        final FileCopyLedgerImpl compacted = open();

        assertTrue(compacted.contains(kept));
        assertFalse(compacted.contains(gone));
        assertEquals(List.of(kept.toLine()), Files.readAllLines(ledgerFile()));
        assertFalse(Files.exists(stateFolder.resolve(FileCopyLedgerImpl.FILE_NAME + ".tmp")));
    }

    @Test
    @DisplayName("an unreachable source folder never empties the ledger")
    void unreachableSourceKeepsEverything() throws Exception {
        final LedgerKey key = keyFor("CPJ1.xml", 1_000L, 42L);
        open().record(key);

        final FileCopyLedgerImpl ledger =
                FileCopyLedgerImpl.open(stateFolder, sourceRoot.resolve("missing"), logger);

        assertTrue(ledger.contains(key));
    }

    @Test
    @DisplayName("ledger lines round-trip, and malformed lines are rejected")
    void lineFormat() {
        final LedgerKey key = LedgerKey.of(Path.of("Journals", "J 1.pdf"), -5L, 0L);

        assertEquals("journals/j 1.pdf", key.relativePath());
        assertEquals(Optional.of(key), LedgerKey.parse(key.toLine()));
        assertEquals(Optional.empty(), LedgerKey.parse(""));
        assertEquals(Optional.empty(), LedgerKey.parse("1\t2\t"));
        assertEquals(Optional.empty(), LedgerKey.parse("x\t2\tname"));
        assertEquals(Optional.empty(), LedgerKey.parse("1\t-2\tname"));
    }
}
