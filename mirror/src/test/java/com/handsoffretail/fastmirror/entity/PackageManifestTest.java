package com.handsoffretail.fastmirror.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PackageManifestTest {

    private static final TypeFolder REPORTS = new TypeFolder(LocalDate.of(2026, 9, 24), "PassportReports");

    private static PackageManifest manifest(String name) {
        return new PackageManifest(
                "wolf_800_NY-13208",
                REPORTS,
                2,
                Instant.parse("2026-09-25T08:00:04Z"),
                "3.0.0",
                List.of(
                        new PackageManifest.Entry(
                                new PackagedFile(
                                        REPORTS.folderPath() + "/" + name, 141_312, Instant.parse("2026-09-25T04:05:18Z")),
                                "0".repeat(64))));
    }

    @Test
    @DisplayName("what toJson writes, parse reads back exactly, including unusual names")
    void roundTrip() {
        for (final String name : List.of("StoreClose20260925000518.pdf", "Café report #1.pdf", "tab\there.pdf")) {
            final PackageManifest manifest = manifest(name);

            assertEquals(Optional.of(manifest), PackageManifest.parse(manifest.toJson()));
        }
    }

    @Test
    @DisplayName("the layout is fixed and every time ends in Z")
    void layout() {
        final String json = manifest("StoreClose20260925000518.pdf").toJson();

        assertEquals(
                """
                {
                  "store": "wolf_800_NY-13208",
                  "type": "PassportReports",
                  "businessDate": "2026-09-24",
                  "version": 2,
                  "createdAt": "2026-09-25T08:00:04Z",
                  "fastMirrorVersion": "3.0.0",
                  "fileCount": 1,
                  "files": [
                    { "path": "2026/september/2026-09-24/PassportReports/StoreClose20260925000518.pdf", "size": 141312, "modified": "2026-09-25T04:05:18Z", "sha256": "0000000000000000000000000000000000000000000000000000000000000000" }
                  ]
                }
                """,
                json);
    }

    @Test
    @DisplayName("text that is not a whole manifest is refused")
    void refusesFragments() {
        final String json = manifest("a.pdf").toJson();

        assertTrue(PackageManifest.parse("").isEmpty());
        assertTrue(PackageManifest.parse(json.replace("\"fileCount\": 1", "\"fileCount\": 2")).isEmpty());
        assertTrue(PackageManifest.parse(json.substring(0, json.indexOf("\"createdAt\""))).isEmpty());
    }
}
