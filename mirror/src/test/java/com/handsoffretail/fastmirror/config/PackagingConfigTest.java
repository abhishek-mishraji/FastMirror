package com.handsoffretail.fastmirror.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The packaging keys: read and checked only when PACKAGE_ENABLED=true. */
class PackagingConfigTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;
    @TempDir Path outputs;

    private Map<String, String> values;

    @BeforeEach
    void setUp() {
        values = new HashMap<>();
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, "PJR");
        values.put(MirrorConfig.KEY_PACKAGE_ENABLED, "true");
        values.put(MirrorConfig.KEY_STORE_FOLDER, "wolf_800_NY-13208");
        values.put(MirrorConfig.KEY_OUTBOX_FOLDER, outputs.resolve("outbox").toString());
    }

    private MirrorConfig load() {
        return MirrorConfig.load(values::get, baseDirectory);
    }

    private String failure() {
        return assertThrows(ConfigException.class, this::load).getMessage();
    }

    @Test
    @DisplayName("off by default, and nothing about it is read or checked while off")
    void offByDefault() {
        values.remove(MirrorConfig.KEY_PACKAGE_ENABLED);
        values.put(MirrorConfig.KEY_STORE_FOLDER, "not a valid name!");
        values.put(MirrorConfig.KEY_OUTBOX_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_PACKAGE_AT_UTC, "soon");

        final MirrorConfig config = load();

        assertFalse(config.packagingEnabled());
        assertNull(config.packaging());

        values.put(MirrorConfig.KEY_PACKAGE_ENABLED, "FALSE");
        assertFalse(load().packagingEnabled());
    }

    @Test
    @DisplayName("on: defaults for staging, 08:00 UTC, no from-date, keep forever")
    void defaults() throws IOException {
        final PackageConfig packaging = load().packaging();

        final Path outbox = outputs.resolve("outbox").toRealPath();
        assertEquals("wolf_800_NY-13208", packaging.storeFolder());
        assertEquals(outbox, packaging.outboxFolder());
        assertEquals(outputs.resolve("outbox-staging").toRealPath(), packaging.stagingFolder());
        assertEquals(LocalTime.of(8, 0), packaging.packageAtUtc());
        assertNull(packaging.packageFromDate());
        assertEquals(0, packaging.outboxKeepDays());
        assertEquals(outbox.resolve("wolf_800_NY-13208"), packaging.storeOutbox());
    }

    @Test
    @DisplayName("every packaging key is read")
    void everyKey() throws IOException {
        values.put(MirrorConfig.KEY_PACKAGE_STAGING_FOLDER, outputs.resolve("build").toString());
        values.put(MirrorConfig.KEY_PACKAGE_AT_UTC, "09:30");
        values.put(MirrorConfig.KEY_PACKAGE_FROM_DATE, "2026-10-01");
        values.put(MirrorConfig.KEY_OUTBOX_KEEP_DAYS, "30");
        values.put(MirrorConfig.KEY_PACKAGE_ENABLED, "True");

        final PackageConfig packaging = load().packaging();

        assertEquals(outputs.resolve("build").toRealPath(), packaging.stagingFolder());
        assertEquals(LocalTime.of(9, 30), packaging.packageAtUtc());
        assertEquals(LocalDate.of(2026, 10, 1), packaging.packageFromDate());
        assertEquals(30, packaging.outboxKeepDays());
    }

    @Test
    @DisplayName("PACKAGE_ENABLED must be true or false")
    void enabledIsABoolean() {
        values.put(MirrorConfig.KEY_PACKAGE_ENABLED, "yes");

        assertTrue(failure().contains("true or false"));
    }

    @Test
    @DisplayName("STORE_FOLDER and OUTBOX_FOLDER are required when on")
    void requiredWhenOn() {
        values.remove(MirrorConfig.KEY_STORE_FOLDER);
        assertTrue(failure().contains(MirrorConfig.KEY_STORE_FOLDER));

        values.put(MirrorConfig.KEY_STORE_FOLDER, "tiki");
        values.remove(MirrorConfig.KEY_OUTBOX_FOLDER);
        assertTrue(failure().contains(MirrorConfig.KEY_OUTBOX_FOLDER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wolf 800", "wolf/800", "wolf\\800", "..", ".", "wolf:800", "wölf", "a*b"})
    @DisplayName("STORE_FOLDER allows only letters, digits, _ - and .")
    void storeFolderIsSafe(String store) {
        values.put(MirrorConfig.KEY_STORE_FOLDER, store);

        assertTrue(failure().contains(MirrorConfig.KEY_STORE_FOLDER));
    }

    @Test
    @DisplayName("the outbox may not overlap the source, the mirror or the state folder")
    void outboxApart() {
        values.put(MirrorConfig.KEY_OUTBOX_FOLDER, sourceRoot.resolve("outbox").toString());
        assertTrue(failure().contains(MirrorConfig.KEY_SOURCE_FOLDER));

        values.put(MirrorConfig.KEY_OUTBOX_FOLDER, mirrorRoot.resolve("outbox").toString());
        assertTrue(failure().contains(MirrorConfig.KEY_MIRROR_FOLDER));

        values.put(MirrorConfig.KEY_OUTBOX_FOLDER, mirrorRoot.getParent().toString());
        assertTrue(failure().contains(MirrorConfig.KEY_OUTBOX_FOLDER));

        values.put(MirrorConfig.KEY_OUTBOX_FOLDER, baseDirectory.resolve("state").resolve("out").toString());
        assertTrue(failure().contains(MirrorConfig.KEY_STATE_FOLDER));
    }

    @Test
    @DisplayName("the staging folder may not be inside the outbox, nor overlap the mirror")
    void stagingApart() {
        values.put(
                MirrorConfig.KEY_PACKAGE_STAGING_FOLDER,
                outputs.resolve("outbox").resolve("staging").toString());
        assertTrue(failure().contains("half-built"));

        values.put(MirrorConfig.KEY_PACKAGE_STAGING_FOLDER, mirrorRoot.resolve("staging").toString());
        assertTrue(failure().contains(MirrorConfig.KEY_MIRROR_FOLDER));
    }

    @Test
    @DisplayName("staging on another drive is refused before anything is created there")
    void stagingOnTheOutboxDrive() {
        final Path root = outputs.getRoot();
        final String otherDrive = root.toString().toUpperCase().startsWith("Q") ? "R:/staging" : "Q:/staging";
        values.put(MirrorConfig.KEY_PACKAGE_STAGING_FOLDER, otherDrive);

        assertTrue(failure().contains("same drive"));
    }

    @Test
    @DisplayName("an extra folder may not overlap the outbox")
    void extraFolderApart() throws IOException {
        final Path reports = Files.createDirectories(outputs.resolve("outbox").resolve("PassportReports"));
        values.put(MirrorConfig.KEY_INCLUDE_SUBFOLDERS, reports.toString());

        assertTrue(failure().contains(MirrorConfig.KEY_INCLUDE_SUBFOLDERS));
    }

    @Test
    @DisplayName("PACKAGE_AT_UTC is HH:mm, PACKAGE_FROM_DATE yyyy-MM-dd, OUTBOX_KEEP_DAYS 0 or more")
    void formats() {
        values.put(MirrorConfig.KEY_PACKAGE_AT_UTC, "8am");
        assertTrue(failure().contains("HH:mm"));
        values.remove(MirrorConfig.KEY_PACKAGE_AT_UTC);

        values.put(MirrorConfig.KEY_PACKAGE_FROM_DATE, "15/10/2026");
        assertTrue(failure().contains("yyyy-MM-dd"));
        values.remove(MirrorConfig.KEY_PACKAGE_FROM_DATE);

        values.put(MirrorConfig.KEY_OUTBOX_KEEP_DAYS, "-1");
        assertTrue(failure().contains(MirrorConfig.KEY_OUTBOX_KEEP_DAYS));
    }

    @Test
    @DisplayName("the write probe leaves nothing behind, and is never a name the S3 agent uploads")
    void probesLeaveNothing() throws IOException {
        load();

        try (Stream<Path> outbox = Files.list(outputs.resolve("outbox"));
                Stream<Path> staging = Files.list(outputs.resolve("outbox-staging"))) {
            assertEquals(List.of(), outbox.toList());
            assertEquals(List.of(), staging.toList());
        }
    }
}
