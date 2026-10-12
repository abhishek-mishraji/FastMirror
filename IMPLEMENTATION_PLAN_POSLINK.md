# FastMirror v3 — POS Link Archive: Implementation Plan

**Against:** FastMirror v2 (commit `50d8350`) and the requirements agreed on 2026-10-11
**Status:** implemented 2026-10-11 (uncommitted). `gradlew build`: 273 tests pass. `gradlew slowTest`: 3 tests pass. See section 6 for where the build differed from this plan.
**Scope:** one agent per store PC, each with its own `.env`. The agent now serves only the POS Link Archive.

## Locked decisions (from the Q&A)

| # | Decision |
|---|---|
| 1 | One agent per store PC, each with its own `.env`. No multi-source support in one process. |
| 2 | Only the POS Link Archive is supported. The Gemcom instant-copy machinery is **removed**: watcher, event thread, 500 ms sweep, filename-reuse warning, capture-latency stats. |
| 3 | A file is copied only after its **size and last-modified time have been unchanged for `STABILITY_MINUTES` (default 3)**, observed by a scan every `SCAN_INTERVAL_SECONDS` (default 60). The agent does not lock the file or open it exclusively; POS Link can never be disturbed. |
| 4 | Root files: selected by **3-letter prefix** (first 3 characters of the name, **case-insensitive**) and by **extension** (`INCLUDE_EXTENSIONS`, default `xml`). |
| 5 | Root destination: `<PREFIX>/<yyyy>/<month>/<yyyy-MM-dd>/<original name>` with `GROUP_BY=DATE`, or `<PREFIX>/<yyyy>/<month>/<original name>` with `GROUP_BY=MONTH`. No weekly grouping. |
| 6 | Month folder: lowercase English full name (`january` … `december`). Day folder: `yyyy-MM-dd`. Original file name kept. |
| 7 | The date that decides the folder is the file's **original creation time** as Windows reports it, converted to **`TIME_ZONE`** (default `America/New_York`, added 2026-10-11 because the agent may run on a VM or against a network drive in another zone). If POS Link moves a file into the Archive and Windows keeps its original creation time, that original date is used. |
| 8 | Subfolders: `INCLUDE_SUBFOLDERS` key added now, empty by default. Files in an included subfolder are selected by `SUBFOLDER_EXTENSIONS` (default `pdf`), **with no prefix filter**, and are stored as `<subfolder>/<yyyy>/<month>/<original name>`, always grouped by month. |
| 9 | A file with the same name in the same destination folder is **overwritten**. |
| 10 | On first start, every matching file already in the Archive is copied, unless `COPY_FROM_DATE` limits the copy to files created on or after that date. |
| 11 | A **persistent "already copied" record** (the ledger) means a file is never copied twice, even after the S3 agent or a retention job removes it from the destination, and even across restarts. |
| 12 | Archive files do not change after they are written, so a file in the ledger is never copied again. |
| 13 | The non-interference rules are kept as they are. The source is opened read-only through the one sanctioned code path, the banned-API tests stay, and nothing is ever written or deleted in the source folder. |

### Defaults (accepted 2026-10-11)

| # | Default | Why |
|---|---|---|
| D-1 | `GROUP_BY` defaults to `DATE`. | It was listed first in your examples. |
| D-2 | `INCLUDE_PREFIXES` empty → root files are not copied, and a WARN is logged at startup. If `INCLUDE_SUBFOLDERS` is also empty, the agent refuses to start, because it would have nothing to do. | You said to ignore the empty case; this keeps an empty list harmless. |
| D-3 | The prefix folder is always upper case (`cpj432.xml` → `CPJ/…`), so mixed-case names never split into two folders. | Matching ignores case. |
| D-4 | Only files **directly** in an included subfolder are copied. Folders nested inside it are ignored. The destination folder uses the subfolder name exactly as it appears in the source. | Nothing nested was described. |
| D-5 | A subfolder listed in `INCLUDE_SUBFOLDERS` that does not exist logs one WARN and is checked again on every scan. It does not stop the agent. | It may be created later by POS Link. |
| D-6 | A partly-read file is **never published**. The failure is logged, nothing is recorded in the ledger, and the next scan tries again. `PUBLISH_PARTIAL` is removed. | A truncated file would be uploaded to S3 and then marked done forever. |
| D-7 | The ledger is `state\copied.ledger`, beside the exe, configurable with `STATE_FOLDER`. It must not be inside the source or the destination folder. | The S3 agent must not upload it, and nothing may be written in the source. |
| D-8 | Old v2 keys found in `.env` (`SWEEP_INTERVAL_MS`, `CONTENT_VERIFY`, `PUBLISH_PARTIAL`, `MIRROR_EMPTY_DIRECTORIES`) log one WARN ("no longer used, ignored") instead of stopping the agent. | Existing `.env` files keep working. |
| D-9 | A backlog file also waits `STABILITY_MINUTES` after the agent starts before it is copied. The first copies therefore appear about 3 minutes after startup. | Last-modified time on Windows can lag while a writer holds a file open, so a file is only trusted once it has been watched not changing. |

---

## 1. Example `.env`

```properties
SOURCE_FOLDER=C:/Program Files (x86)/CStore Essentials/POS Link/Service/Archive
MIRROR_FOLDER=C:/Users/Owner/Documents/HandsOff-Software/Tiki_8730_IN-47872/Mirrored_files

# Root files: which prefixes and extensions to copy (prefix match ignores case)
INCLUDE_PREFIXES=CPJ,SUM,MCM,ISM,MSM,HRS,FGM,FCF,LYT,TPM
INCLUDE_EXTENSIONS=xml

# DATE  -> CPJ/2026/october/2026-10-15/CPJ432.xml
# MONTH -> CPJ/2026/october/CPJ432.xml
GROUP_BY=DATE

# Subfolders of SOURCE_FOLDER to copy as well (comma-separated, empty = none).
# Their files are stored as <subfolder>/<yyyy>/<month>/<name>
INCLUDE_SUBFOLDERS=
SUBFOLDER_EXTENSIONS=pdf

# Copy only files created on or after this date (yyyy-MM-dd). Empty = everything.
COPY_FROM_DATE=

# A file is copied only after it has been unchanged this long
STABILITY_MINUTES=3
SCAN_INTERVAL_SECONDS=60

# Kept from v2: COPY_WORKERS, OPEN_RETRY_ATTEMPTS, OPEN_RETRY_INITIAL_MS,
#               MAX_COPY_BYTES, EXCLUDE_PATTERNS, STATS_INTERVAL_MS, LOG_FOLDER
# New:          STATE_FOLDER=state
```

### Resulting layout (GROUP_BY=DATE, INCLUDE_SUBFOLDERS=Journals)

```
Mirrored_files\
    CPJ\2026\october\2026-10-15\CPJ432.xml
    CPJ\2026\october\2026-10-16\CPJ433.xml
    HRS\2026\october\2026-10-15\HRS007.xml
    Journals\2026\october\journal_1015.pdf
```

---

## 2. New pipeline

```
every SCAN_INTERVAL_SECONDS
  ArchiveScanService ── list root (not recursive) + each INCLUDE_SUBFOLDERS (not recursive)
        │  attribute reads only, never opens a source file
        ▼
  ArchiveFileFilter ── prefix / extension / EXCLUDE_PATTERNS / COPY_FROM_DATE (creation date)
        ▼
  CopyLedger.contains(key)? ── yes → skip
        ▼
  StabilityTracker ── same size + mtime for ≥ STABILITY_MINUTES? ── no → wait for next scan
        ▼
  GroupedPathMapper ── target = <PREFIX|subfolder>/<yyyy>/<month>/[<yyyy-MM-dd>/]<name>
        ▼
  CopyQueue (COPY_WORKERS threads, one job per file at a time)
        ▼
  AtomicFileCopier ── read-only open → staging .fmtmp → atomic replace of target
        ▼
  CopyLedger.record(key) ── appended and flushed to disk only after the target is in place
```

**Ledger key** = source path relative to the Archive + creation time + size. If POS Link ever deletes `CPJ432.xml` and creates a new one, the creation time differs, so the new file counts as a new file and is copied (and overwrites if it lands in the same folder).

**Crash safety:** a crash after the target is published but before the ledger line is written makes the file copy again on restart, which overwrites the same target with the same bytes. A crash can never leave a file marked as copied when it was not.

---

## 3. Phases

One commit per phase. `gradlew.bat build` must pass at the end of each.

### Phase 1 — configuration
- `MirrorConfig`: add `includePrefixes` (each exactly 3 letters, stored upper case), `includeExtensions`, `groupBy` (enum `DATE|MONTH`), `includeSubfolders` (plain names; no `\`, `/` or `..`), `subfolderExtensions`, `copyFromDate` (`LocalDate`, nullable), `stabilityMinutes` (1–1440), `scanIntervalSeconds` (10–3600) and `stateFolder`.
- Remove `sweepIntervalMs`, `contentVerify`, `publishPartial` and `mirrorEmptyDirectories`, with a WARN when they are present (D-8).
- Validation: D-2, D-7, a well-formed date, and an extension list that is not empty.
- `MirrorConfigTest`: one test per new rule and per error message.

### Phase 2 — selection and destination (pure logic, no I/O)
- `service/ArchiveFileFilter` + `ArchiveFileFilterImpl`: decides from (relative path, creation time) whether a file is a root candidate, a subfolder candidate, or ignored. Folds in the existing `EXCLUDE_PATTERNS`, which always include `*.fmtmp`. Replaces `GlobPathFilterImpl`.
- `service/PathMapper` reworked + `GroupedPathMapperImpl`: `toTarget(relative, creationTime)`. Uses `Month.getDisplayName(FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT)` and the PC's time zone. Keeps the existing check that no target path can escape the mirror root. Replaces `RelativePathMapperImpl`.
- Tests:
  - case-insensitive prefix matching, and prefixes that are not selected
  - non-xml files at the root, non-pdf files in a subfolder
  - all 12 month names, DATE and MONTH layouts
  - 31 Dec → 1 Jan, and `COPY_FROM_DATE` inclusive at its boundary
  - nested folders ignored (D-4), and `..` traversal rejected

### Phase 3 — the ledger
- `service/CopyLedger` + `FileCopyLedgerImpl`: loads `state\copied.ledger` into memory at startup. `record` appends one line and forces it to disk.
- **Compaction at startup:** rewrites the ledger without entries whose source file no longer exists. They can never be copied again, so keeping them only grows the file. The rewrite goes through a temp file and an atomic move.
- Tolerates a half-written last line (power cut).
- Tests: record then reload, torn last line, compaction, and the same name with a new creation time.

### Phase 4 — stability and scanning
- `service/StabilityTracker` + `StabilityTrackerImpl`: per file, remembers the size and mtime last seen and the instant they were first seen unchanged, using an injected `Clock`. A file is ready once it has been unchanged for `STABILITY_MINUTES` or longer. Any change restarts its clock; files that disappear are forgotten.
- `service/ScanService` + `ArchiveScanServiceImpl`: one scheduled thread. Lists the folders, then filter → ledger → tracker → submit. Attribute reads only.
- Replaces `SweepService` / `TreeSweepServiceImpl`.
- Tests:
  - exactly at the 3-minute boundary, and a size change or an mtime change restarts the wait
  - a file that keeps growing is never submitted
  - a file already in the ledger is never submitted
  - folders other than the included subfolders are not scanned
  - a missing subfolder (D-5)

### Phase 5 — copy engine and queue
- `AtomicFileCopierImpl`: takes a `CopyJob(relativeSource, target, ledgerKey)`.
  - **Kept:** the read-only open, the bounded copy loop, the open retry with backoff, the publish retry, the atomic replace, `MAX_COPY_BYTES`.
  - **Removed:** partial publishing (D-6), `contentsDiffer`, the name-reuse warning, the capture-latency measurement.
  - Writes the ledger entry only after a successful publish.
- `CopyQueueImpl`: keyed by ledger key. Drops the event timestamp, CREATE flag and RUNNING_DIRTY follow-up copy, which only made sense for files that change.
- `CopyOutcome`: drop `UNCHANGED`. `PARTIAL` becomes a `FAILED` that is retried.
- Update `AtomicFileCopierImplTest` and `CopyQueueImplTest`.

### Phase 6 — removal and wiring
- **Delete:**
  - `SourceWatcherService` + `WatchServiceSourceWatcherImpl`
  - `MirrorService` + `MirrorServiceImpl`
  - `FileEvent` + `FileEventKind`
  - `DifferenceDetector` + `SizeAndTimeDifferenceDetectorImpl`
  - the tests of all of the above
- **`Application`, new startup order:**
  1. logger
  2. config
  3. remove stale `.fmtmp` staging files
  4. load and compact the ledger
  5. build the services
  6. first scan, which starts the stability clocks
  7. schedule the scans and the stats
  8. tray icon
- **Shutdown:** stop scans → let in-flight copies finish → close the ledger.
- **Stats line:** `COPIED / REPLACED / FAILED / LOCKED / VANISHED / SKIPPED_TOO_LARGE`, plus `waiting-for-stability=N` and `ledger=N`.
- **Tray:** same icon and Exit; the tooltip shows the source, the destination and the files copied.
- **`ArchitectureRulesTest`:**
  - AT-02 allow-list gains `FileCopyLedgerImpl`, which may only open files under `STATE_FOLDER`.
  - AT-02b, AT-03 and AT-03b are unchanged.

### Phase 7 — integration tests
These use `AgentHarness` with an injected clock, so "3 minutes" passes instantly.

| ID | Scenario | Expected |
|---|---|---|
| PL-01 | Archive with 10 prefixes of backlog, 3 selected | Only the 3 selected prefixes are copied, into the right date folders, after the stability wait. |
| PL-02 | File appended to every 30 s for 5 min | Not copied until 3 min after the last write. |
| PL-03 | Destination file deleted after copy (the S3 agent) | Never copied again. |
| PL-04 | Agent restarted | Nothing already in the ledger is copied again. |
| PL-05 | `COPY_FROM_DATE=2026-10-01`, files created in Sept and Oct | Only the October files are copied. |
| PL-06 | `GROUP_BY=MONTH` | `CPJ/2026/october/CPJ432.xml`. |
| PL-07 | `INCLUDE_SUBFOLDERS=Journals`, with a pdf and an xml in it | Only the pdf is copied, to `Journals/2026/october/`. |
| PL-08 | Same name, new creation time, same destination folder | The target is overwritten with the new content. |
| PL-09 | `cpj432.xml`, `CPJ432.XML`, `report.txt`, `ABC1.xml` | The first two are copied to `CPJ/…`; the other two are ignored. |
| PL-10 | Source locked when stable | `LOCKED`; copied on a later scan; no ledger entry until then. |
| PL-11 | Crash between publish and ledger write | The file is copied again on restart and the target is the same. |

- Keep **IT-18/19/20** (the producer's delete, write and rename all succeed while the copier holds the file open), adapted to the new harness.
- Rewrite or drop the old IT-01…IT-16 and the slow tests that only made sense for instant capture: 1-second files, overflow, latency.

### Phase 8 — packaging and documentation
- `packaging/env.template` and `.env.example`: section 1 with comments.
- `README.md`: rewrite the purpose and limitation sections for the Archive case. Keep the install, tray and log sections, and the operator duties (disk space, and what the ledger is). Note that deleting `state\copied.ledger` makes the agent copy everything again.
- `SRS_FastMirror.md`: add a v3.0 change section describing what was removed and what was added.

---

## 4. Known caveats to accept

1. **Creation time and how POS Link writes.** If POS Link *moves* a file into Archive from elsewhere on the same disk, Windows keeps the original creation time, and that date picks the folder. If it *copies* the file in, the creation time is the arrival time. Either way the folder is the creation date as Windows reports it. A `dir /T:C` on the store PC will show which case applies.
2. **First copies arrive about 3 minutes after startup** (D-9).
3. **The ledger is the source of truth.** Deleting it makes the agent copy everything again, from `COPY_FROM_DATE` onwards. That is the way to force a full re-copy.
4. **A file that stops changing for 3 minutes in the middle of being written** would be copied in its incomplete state and marked done. You accepted this risk by choosing "unchanged 3 min" over the lock test. Raise `STABILITY_MINUTES` if POS Link ever pauses that long mid-write.

## 5. Estimated size

About 1,000 lines of production code removed and about 700 added; about 1,500 test lines rewritten. Eight commits.

## 6. Where the build differed from this plan

1. **AT-02 needed no allow-list change.** The ledger writes with `Files.write(..., APPEND, SYNC)` and compacts with a temp file plus an atomic move. It never touches the banned file-channel APIs, so the "only the copier opens files" rule stands unchanged.
2. **"Waiting" and "ledger size" are reported on the scan log line, not the stats line.** Example: `Scan: 12 file(s) stable and queued for copy, 3 still waiting, 412 copied so far`. This keeps the stats reporter free of references to the scan and the ledger.
3. **Files over `MAX_COPY_BYTES` are filtered out by the scan**, with one warning per file. Without this they would be resubmitted and re-warned every minute. The copier still checks the limit as a backstop.
4. **The `.env` template now sets `INCLUDE_PREFIXES`**, and the agent requires prefixes or subfolders (D-2). The old two-key minimum no longer applies.
5. **`jdk.unsupported` is no longer needed in the bundled runtime.** `jdeps` reports `java.base,java.desktop` only. README and `launch4j.xml` are updated.
6. **Slow tests:** IT-13 (atomic visibility, 50 MB), IT-14 (5,000-file backlog, then no re-copy after a restart) and IT-16 (soak) were kept and adapted. IT-15 measured instant-capture latency and was dropped.
7. **Phases were not committed one by one.** Everything was committed as one commit on branch `poslink-archive`.
8. **`TIME_ZONE` added after review** (default `America/New_York`). The PC's own time zone no longer affects date folders or `COPY_FROM_DATE`. Log timestamps still use the PC's clock.
9. **Date from the file name, configurable per store** (added 2026-10-11). `DATE_SOURCE=FILENAME` with a template such as `FILENAME_DATE_PATTERN=???yyyy-MM-dd*` reads the business date from names like `CPJ2026-09-13-590.xml`, so late files land in their own month. A name without a date falls back to the creation date with one warning per file. Subfolder files have their own `SUBFOLDER_DATE_SOURCE` and `SUBFOLDER_FILENAME_DATE_PATTERN`. `COPY_FROM_DATE` uses the same date.
10. **The agent keeps running until stopped by hand.** A missing source folder at startup is a warning, not an error. Source outages are logged once and ridden out. A real `.env` mistake shows a message box before the agent exits.
11. **Business date from inside the file** (`DATE_SOURCE=CONTENT`, 2026-10-12). One code path for both POS types; the store is chosen by `.env` alone (`packaging/env-examples/`). Passport: `CONTENT_DATE_FIELDS=BusinessDate` (its file names carry the write date, a day late after a midnight close). Verifone: `periodEndDate,closedTime` (CPJ's first `<date>` is the day before). Dates are taken as written, never converted. The start of a stable file is read once via the copier's read-only open (the only class that opens source files) and the result is remembered, so files outside `COPY_FROM_DATE` are not re-read every scan. Verified against all 24 real sample files.
12. **A second source folder, and PDFs dated by print time** (2026-10-12). Passport writes XML to `Z:\BOOutBox` and PDFs to `Z:\PassportReports`. `INCLUDE_SUBFOLDERS` now accepts a full path, so one agent copies both; files there are identified as `<folder name>/<file>` throughout (selection, layout, ledger), and the ledger keeps entries whose folder is offline. PDFs have no readable date inside, so `SUBFOLDER_FILENAME_DATE_PATTERN=*yyyyMMddHHmmss.pdf` reads the print time and `BUSINESS_DAY_STARTS_AT=03:00` moves after-midnight prints to the previous day (never applied to content dates). UNREADEMAIL is left out with `EXCLUDE_PATTERNS`. One agent rather than two, because two agents sharing a mirror would delete each other's staging files at startup. Verified on all 28 real PDF names: the 7 StoreClose land on business days 22-28 Sept as in the reconcile table.
