package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handsoffretail.fastmirror.entity.PackageRecord;
import com.handsoffretail.fastmirror.entity.TypeFolder;
import com.handsoffretail.fastmirror.logging.AppLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePackageLedgerImplTest {

    @TempDir Path state;
    @TempDir Path logs;

    private static final TypeFolder PJR = new TypeFolder(LocalDate.of(2026, 10, 12), "PJR");

    private static PackageRecord record(int version) {
        return new PackageRecord(PJR, version, Instant.parse("2026-10-13T08:00:04Z"), 913, "ab" + version);
    }

    @Test
    @DisplayName("records survive a restart, and the highest version is the latest")
    void survivesRestart() throws Exception {
        try (AppLogger logger = new AppLogger(logs, Clock.systemUTC())) {
            final FilePackageLedgerImpl ledger = FilePackageLedgerImpl.open(state, logger);
            assertEquals(Optional.empty(), ledger.latest(PJR));

            assertTrue(ledger.record(record(1)));
            assertTrue(ledger.record(record(2)));

            final FilePackageLedgerImpl reopened = FilePackageLedgerImpl.open(state, logger);
            assertEquals(Optional.of(record(2)), reopened.latest(PJR));
            assertEquals(List.of(record(1), record(2)), reopened.all());
        }
    }

    @Test
    @DisplayName("each line is date, type, version, UTC time, file count and fingerprint")
    void lineFormat() throws Exception {
        try (AppLogger logger = new AppLogger(logs, Clock.systemUTC())) {
            FilePackageLedgerImpl.open(state, logger).record(record(1));
        }

        assertEquals(
                List.of("2026-10-12\tPJR\t1\t2026-10-13T08:00:04Z\t913\tab1"),
                Files.readAllLines(state.resolve(FilePackageLedgerImpl.FILE_NAME), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a torn last line from a power cut is skipped")
    void tornLine() throws Exception {
        try (AppLogger logger = new AppLogger(logs, Clock.systemUTC())) {
            FilePackageLedgerImpl.open(state, logger).record(record(1));
            Files.writeString(
                    state.resolve(FilePackageLedgerImpl.FILE_NAME), "2026-10-12\tPJR\t2\t2026-10",
                    StandardOpenOption.APPEND);

            assertEquals(Optional.of(record(1)), FilePackageLedgerImpl.open(state, logger).latest(PJR));
        }
    }
}
