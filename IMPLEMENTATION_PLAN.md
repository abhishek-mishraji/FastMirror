# FastMirror — Implementation Plan (store side)

**Date:** 2026-10-15
**Status:** fresh plan; replaces every earlier FastMirror plan and specification
**Scope:** the store PC: FastMirror, plus how the S3 sync agent is set up next to it. The back end (S3 → PostgreSQL → dashboard) is designed in `HOR-Software\automation\DESIGN.md`.

---

## 1. What FastMirror does

```
POS folders ──► FastMirror COPY ──► Mirrored_files ──► FastMirror PACKAGE ──► outbox ──► S3 agent ──► S3
(read only)     file by file,        <store>\<yyyy>\     08:00 UTC next day:     finished     uploads, no
                once stable,         <month>\<date>\     one zip per type        zips only    code change
                by business date     <TYPE>\files        per business day
```

1. **Copy.** It watches the POS folders and copies each selected file once it has stopped changing. Each copy is filed under the file's **business date** and **type**. The POS is never disturbed: files are opened read-only, through one audited code path.
2. **Package.** At a fixed UTC time each morning, it zips the previous business day, **one zip per file type**, into an outbox. Each zip is built in a staging folder and moved into the outbox in one step.
3. **Hand over.** The S3 sync agent (`hor_aws_sync`) watches the outbox and uploads each new zip. That needs only its `.env`, no code change.

The same build serves both POS types. The store type is chosen only in `.env`:

| | Verifone Sapphire (Tiki) | Gilbarco Passport (Wolf) |
|---|---|---|
| Source | `C:\Program Files (x86)\CStore Essentials\POS Link\Service\Archive` | `Z:\BOOutBox` (XML) + `Z:\PassportReports` (PDF) |
| Files per day | ~10 reports + 1 CPJ (8.6 MB) | ~952 XML (5.55 MB) + 3 PDFs (0.42 MB) |
| Business date from | inside the file: `periodEndDate`, `closedTime` | inside the file: `<BusinessDate>`; PDFs: print time in the name + `BUSINESS_DAY_STARTS_AT=03:00` |
| Zips per day | ~10 | ~9 (about 1.5 MB in total) |

---

## 2. Decisions (all agreed)

### Time
| # | Decision |
|---|---|
| T1 | **Every moment is UTC**, written with `Z` (`2026-10-16T08:00:04Z`): log lines, ledgers, zip manifests, the packaging schedule. The PC's own time zone is never used. |
| T2 | **Business dates stay exactly as the POS writes them.** They are calendar labels, not moments, so they are never converted. A Tiki day closed at 21:40 New York (01:40 UTC next day) stays on its own day. |
| T3 | **Times printed in file names** (Passport PDFs) are the store's wall clock. They are used only with `BUSINESS_DAY_STARTS_AT` to find the business date. |
| T4 | **`TIME_ZONE` has one job:** dating files that carry no date of their own, from their creation time. |

### Layout
| # | Decision |
|---|---|
| L1 | **One layout everywhere: date first, then type.** It is the same in the mirror, the outbox and S3: `<store>\<yyyy>\<month>\<yyyy-MM-dd>\<TYPE>\`. The month is the lower-case English name (`october`). |
| L2 | **Every file goes into its business day's folder, PDFs included.** There is no month-only grouping any more. |
| L3 | **The type folder** is the file's prefix in capitals for root files (`PJR`, `CPJ`, `MSM`, …), or the folder name for an extra folder (`PassportReports`). |
| L4 | **The store folder** in the mirror is the last part of `MIRROR_FOLDER`. In the outbox and S3 it is `STORE_FOLDER`. |

### Packaging
| # | Decision |
|---|---|
| P1 | **FastMirror zips; the S3 agent only uploads** whatever finished zip appears in the outbox. |
| P2 | **One zip per type per business day** = exactly one mirror folder `<date>\<TYPE>\`. No zip for a type with no files that day. |
| P3 | **Business day D is zipped at `PACKAGE_AT_UTC` (default `08:00`) on D+1.** That is 04:00 New York in summer and 03:00 in winter, after both stores' closes. |
| P4 | **Late file → a complete new version.** A file for D arriving after its zip was built produces `…_v2.zip` with **all** of that folder's files. The back end loads the highest version. |
| P5 | **Missed days are caught up automatically** at the next start, from `PACKAGE_FROM_DATE` if set. |
| P6 | **Zip name:** `<STORE_FOLDER>_<TYPE>_<yyyy-MM-dd>_v<n>.zip`, with `manifest.json` inside. |
| P7 | **Zips are deterministic.** The same files always give a byte-identical zip, so a rebuild after a crash never looks like a change to the S3 agent. |
| P8 | **Excluded files are never zipped,** because they were never copied (e.g. `UNREADEMAIL*`). |
| P9 | **Outbox zips are kept** until `OUTBOX_KEEP_DAYS` says otherwise (`0` = forever). Deleting locally never deletes from S3. |

### Removed (development phase: nothing in production depends on it)
| # | Removed | Why |
|---|---|---|
| R1 | `GROUP_BY` (and month-only folders) | Packaging needs day folders, and one layout is simpler |
| R2 | Warnings about retired v2 keys (`SWEEP_INTERVAL_MS`, `PUBLISH_PARTIAL`, …) | No v2 install exists |
| R3 | Plain-name `INCLUDE_SUBFOLDERS` (`Journals`) | Only full paths (`Z:/PassportReports`) are needed; Tiki has no extra folders |
| R4 | The old Gemcom specification and the three earlier plans | Replaced by this document; the history is in git |

**Everything else stays:**
- copying with the stability wait and the copy ledger;
- the three date sources (`CONTENT`, `FILENAME`, `CREATED`) and `BUSINESS_DAY_STARTS_AT`;
- full-path extra folders;
- keep-running through source outages;
- the tray icon and its Exit fix;
- the read-only safety rules and their tests.

---

## 3. Target layout

```
Mirrored_files\wolf_800_NY-13208\2026\october\2026-10-12\PJR\PJR3402610130004181533189.xml
Mirrored_files\wolf_800_NY-13208\2026\october\2026-10-12\MSM\MSM3402610130009441533211.xml
Mirrored_files\wolf_800_NY-13208\2026\october\2026-10-12\PassportReports\StoreClose20261013001017.pdf

outbox\wolf_800_NY-13208\2026\october\2026-10-12\PJR\wolf_800_NY-13208_PJR_2026-10-12_v1.zip
outbox\wolf_800_NY-13208\2026\october\2026-10-12\MSM\wolf_800_NY-13208_MSM_2026-10-12_v1.zip

pos-raw/wolf_800_NY-13208/2026/october/2026-10-12/PJR/wolf_800_NY-13208_PJR_2026-10-12_v1.zip
```

**Inside a zip:**

```
wolf_800_NY-13208_PJR_2026-10-12_v1.zip
├── manifest.json
└── 2026/october/2026-10-12/PJR/PJR3402610130004181533189.xml      (paths as in the mirror)
```

```json
{
  "store": "wolf_800_NY-13208",
  "type": "PJR",
  "businessDate": "2026-10-12",
  "version": 1,
  "createdAt": "2026-10-13T08:00:04Z",
  "fastMirrorVersion": "3.x",
  "fileCount": 913,
  "files": [
    { "path": "2026/october/2026-10-12/PJR/PJR3402610130004181533189.xml", "size": 4384, "sha256": "9f2c…" }
  ]
}
```

**How the back end fetches:**

| Request | S3 calls |
|---|---|
| A whole day | list `…/2026-10-12/`, then download the highest version of each type: 1 `LIST` + ~9 `GET` |
| One type for one day | list `…/2026-10-12/PJR/`, then download the highest version: 1 `LIST` + 1 `GET` |

---

## 4. Configuration (`.env`), final key list

| Group | Key | Default | Notes |
|---|---|---|---|
| **Folders** | `SOURCE_FOLDER` | required | the POS folder; read only |
| | `MIRROR_FOLDER` | required | ends with the store folder, e.g. `…\Mirrored_files\wolf_800_NY-13208` |
| | `INCLUDE_SUBFOLDERS` | empty | **full paths only**, e.g. `Z:/PassportReports` |
| | `STATE_FOLDER` / `LOG_FOLDER` | `state` / `logs` | beside the exe |
| **What to copy** | `INCLUDE_PREFIXES` | required* | 3-character prefixes, ignoring case |
| | `INCLUDE_EXTENSIONS` / `SUBFOLDER_EXTENSIONS` | `xml` / `pdf` | |
| | `EXCLUDE_PATTERNS` | `~$*,*.tmp,*.fmtmp` | e.g. add `UNREADEMAIL*` |
| | `MAX_COPY_BYTES` | `0` | 0 = no limit |
| | `COPY_FROM_DATE` | empty | by business date |
| **Business date** | `DATE_SOURCE` / `SUBFOLDER_DATE_SOURCE` | `CREATED` | `CONTENT`, `FILENAME` or `CREATED` |
| | `CONTENT_DATE_FIELDS` / `SUBFOLDER_CONTENT_DATE_FIELDS` | — | Wolf `BusinessDate`; Tiki `periodEndDate,closedTime` |
| | `FILENAME_DATE_PATTERN` / `SUBFOLDER_FILENAME_DATE_PATTERN` | — | e.g. `*yyyyMMddHHmmss.pdf` |
| | `CONTENT_SCAN_BYTES` | `65536` | |
| | `BUSINESS_DAY_STARTS_AT` | `00:00` | store wall clock, for name and creation times |
| | `TIME_ZONE` | `America/New_York` | only for creation-time dates (T4) |
| **Copy timing** | `STABILITY_MINUTES` / `SCAN_INTERVAL_SECONDS` | `3` / `60` | |
| | `COPY_WORKERS`, `OPEN_RETRY_ATTEMPTS`, `OPEN_RETRY_INITIAL_MS`, `STATS_INTERVAL_MS` | 4, 5, 5, 60000 | tuning |
| **Packaging (new)** | `PACKAGE_ENABLED` | `false` | on in both example files |
| | `STORE_FOLDER` | required when on | letters, digits, `_` `-` `.` |
| | `OUTBOX_FOLDER` | required when on | the S3 agent's `WATCH_FOLDER` |
| | `PACKAGE_STAGING_FOLDER` | `<OUTBOX_FOLDER>-staging` | must be on the outbox's drive |
| | `PACKAGE_AT_UTC` | `08:00` | |
| | `PACKAGE_FROM_DATE` | empty | oldest day for catch-up |
| | `OUTBOX_KEEP_DAYS` | `0` | 0 = keep forever |

\* `INCLUDE_PREFIXES` may be empty only if `INCLUDE_SUBFOLDERS` is set.

**S3 agent `.env`:**

```properties
WATCH_FOLDER=<same as OUTBOX_FOLDER>
S3_FOLDER=pos-raw
```

**The S3 agent's AWS key** needs `PutObject` and `GetObject` on `pos-raw/<STORE_FOLDER>/*`, plus `ListBucket` limited to that prefix. Its check before each upload (`HEAD`) is refused with `403` instead of answered with `404` when it lacks Get and List.

---

## 5. How packaging works

### 5.1 When

Packaging runs at start-up and then once a minute. It checks every day folder D in the mirror (D ≥ `PACKAGE_FROM_DATE`) whose due time has passed: **now (UTC) ≥ D+1 at `PACKAGE_AT_UTC`**. For each type folder T inside D:

| Situation | Action |
|---|---|
| no package yet | build `v1` |
| folder content differs from the last package's manifest | build `v(n+1)` |
| no change | nothing |

Today is never zipped.

### 5.2 How one zip is built

1. **List the folder,** skipping the copier's `*.fmtmp` files, which are still being written.
2. **Write the zip into staging,** reading **mirror files only**:
   - entries sorted by name;
   - each entry's time = the file's modified time;
   - add `manifest.json` (business date, type, version, files with size and SHA-256, `createdAt` in UTC).
3. **Re-read the zip** and verify it against the manifest.
4. **Force it to disk.**
5. **Move it into the outbox atomically.** The S3 agent ignores only `~$` names, so a zip must never appear half-written.
6. **Record it** in `state\package.ledger`.

**After a crash between steps 5 and 6,** the zip is found already in the outbox at start-up, verified and recorded, not rebuilt.

### 5.3 Rules that cannot be broken

- **Packaging never reads the POS folders,** never changes the mirror, and never edits a zip already in the outbox.
- **A packaging failure never stops copying.** It is logged and retried at the next check.
- **A day whose folder lost files** (for example to a mirror clean-up) is never zipped incomplete. It is logged as an error instead.

---

## 6. Phases

One commit per phase. `gradlew build` passes at the end of every phase. Every phase keeps the read-only safety tests green.

### Phase 0 — clean-up
- **Delete** the old documents (R4). **Done** with this plan.
- **Remove `GROUP_BY` (R1)** from config, path mapper, tests and examples.
- **Remove the retired-key warnings (R2).**
- **Remove plain-name `INCLUDE_SUBFOLDERS` (R3).** A plain name becomes a config error with an example of the full-path form.
- **Code comments:** replace references to the deleted specification ("SRS 9.5", "NI-4", "PD-1", in 14 files) with the plain reason they stood for.
- **README:** short, current, pointing to this plan.

### Phase 1 — UTC for every moment
- **Logs:** `2026-10-16 08:00:04Z [INFO] …`; one log file per UTC day.
- **The tray icon** shows UTC times.
- **Every clock in FastMirror is UTC.** The PC's own zone is never consulted, and `TIME_ZONE` is kept only for T4.
- **Tests:**
  - with the PC set to New York, Kolkata or UTC, logs and dates give identical results;
  - a Tiki CPJ closed at 21:40 New York keeps its own business date.

### Phase 2 — date-first layout
- **Path mapper:** `<yyyy>\<month>\<yyyy-MM-dd>\<TYPE>\<file>` for all files, extra folders included.
- **Tests:**
  - update every existing path expectation;
  - a Wolf StoreClose printed 00:10 on the 13th lands in `…\2026-10-12\PassportReports\`;
  - root and extra-folder files share the same day folder;
  - month-end and year-end;
  - traversal safety kept.

### Phase 3 — packaging configuration
- **New keys** (section 4).
- **Checks:**
  - `STORE_FOLDER` uses safe characters;
  - the outbox and staging folders don't overlap the source, mirror or state folders;
  - staging is on the outbox's drive;
  - `PACKAGE_AT_UTC` is a valid `HH:mm`;
  - `PACKAGE_FROM_DATE` is a valid date.
- **Nothing is checked when `PACKAGE_ENABLED=false`.**

### Phase 4 — the zip builder
- **`DayPackager`:** section 5.2.
- **Tests:**
  - same input → byte-identical zip;
  - the manifest matches the content;
  - nothing appears in the outbox until it's complete;
  - a crash after the move is recovered without a rebuild;
  - a file vanishing mid-build fails cleanly.

### Phase 5 — scheduling and versions
- **`PackageScheduler`:** section 5.1, with an injected UTC clock.
- **Tests:**
  - 15 Oct is not zipped at 16 Oct 07:59Z and is zipped at 08:00Z;
  - today is never zipped;
  - 3 days off → 3 days caught up;
  - `PACKAGE_FROM_DATE` is respected;
  - a late file → `v2` with all files;
  - no change → no new version;
  - a restart → nothing zipped twice.

### Phase 6 — wiring and end-to-end
- **Wiring:** packaging started from `Application` after copying; failures isolated from copying.
- **Architecture test:** packaging classes never open a path under the source folders.
- **End-to-end tests** through the real composition root, for Wolf-style XML + PDFs and Tiki-style files: the clock is moved to 08:00Z the next day, the expected zips appear at the expected outbox paths, and unzipping each gives exactly its folder's files.
- **Real-data test:** every Wolf file whose business date is 2026-09-24 → 8 XML zips + 1 PDF zip. The XML comes from `XML_0923_0924` and `XML_0925_0926`, because the close reports are written after midnight on the 25th. The PDFs are the 24 Sept 13:15 ShiftClose plus the 25 Sept 00:05 ShiftClose and StoreClose. Each zip's file count must equal the number of files carrying that business date and type.
- **`OUTBOX_KEEP_DAYS` clean-up.**
- **Tray and stats:** the tray shows "Last packaged: 2026-10-14 (9 zips)"; the stats line counts `PACKAGED=n`.

### Phase 7 — examples, guide and release
- **`.env` examples:** `env.template`, `verifone-tiki.env`, `passport-wolf.env`, all with packaging on.
- **Setup guide in the README:**
  - FastMirror;
  - the S3 agent's two `.env` lines;
  - the AWS key's permissions;
  - start-up with Task Scheduler.
- **Release:**
  - `HOR-Software\automation\DESIGN.md` section 2 rewritten to this layout;
  - `architecture.mmd` box 3 updated;
  - `FastMirror.exe` rebuilt;
  - a test run on this PC with a copy of real Wolf and Tiki files.

---

## 7. Acceptance criteria

The store side is done when all of these hold:

1. **Wolf:** from the real files, FastMirror produces **8 XML zips + 1 PDF zip** for business day 2026-09-24. That includes the close reports and the StoreClose written after midnight on the 25th. Each zip's manifest lists exactly the files in its mirror folder.
2. **Tiki:** from the sample files, each report and the CPJ land in their own day/type folder and their own zip.
3. **A day is zipped at its due time** (08:00Z the next day), never earlier, and exactly once unless a late file arrives (`v2`).
4. **The S3 agent,** with only `WATCH_FOLDER` and `S3_FOLDER` set, uploads every zip to `pos-raw/<store>/<yyyy>/<month>/<date>/<TYPE>/…`, with no half-written uploads.
5. **Every time** in logs, ledgers and manifests ends in `Z`; every business date equals the POS's own.
6. **The POS is never disturbed:** the read-only safety tests pass, and no packaging class touches a source folder.
7. **`gradlew build` passes** with all tests.
