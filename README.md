# FastMirror

A Windows agent for a store PC. It copies the POS's files once each one has finished being written, files them by **business date** and **type**, and once a day packs each business day into **one zip per type** in an outbox. The S3 sync agent uploads the outbox to S3.

```
POS folders ──► FastMirror COPY ──► Mirrored_files ──► FastMirror PACKAGE ──► outbox ──► S3 agent ──► S3
(read only)     file by file,        <yyyy>\<month>\     08:00 UTC next day:     finished     uploads, no
                once stable,         <date>\<TYPE>\      one zip per type        zips only    code change
                by business date     files               per business day
```

One build serves every store. The POS type is chosen only in `.env`; ready-made files for both kinds of store are in `packaging\env-examples\`:

| | Verifone Sapphire (Tiki) | Gilbarco Passport (Wolf) |
|---|---|---|
| Source | `C:\Program Files (x86)\CStore Essentials\POS Link\Service\Archive` | `BOOutBox` (XML) + `PassportReports` (PDF) |
| Files per day | ~10 reports + 1 CPJ | ~990 XML + 3 PDFs |
| Business date from | inside the file: `periodEndDate`, `closedTime` | inside the file: `<BusinessDate>`; PDFs: print time in the name + `BUSINESS_DAY_STARTS_AT=03:00` |
| Zips per day | ~10 | 9 (about 1.5 MB in total) |
| `.env` | `verifone-tiki.env` | `passport-wolf.env` |

FastMirror never writes to the POS folders, never opens them except read-only, and never deletes anything from the mirror. The current plan, with every decision behind this design, is [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

---

## One layout everywhere

The mirror, the outbox and S3 all use the same layout: **business date first, then type**.

```
Mirrored_files\2026\october\2026-10-12\PJR\PJR3402610130004181533189.xml
Mirrored_files\2026\october\2026-10-12\PassportReports\StoreClose20261013001017.pdf

outbox\wolf_800_NY-13208\2026\october\2026-10-12\PJR\wolf_800_NY-13208_PJR_2026-10-12_v1.zip

s3://<bucket>/pos-raw/wolf_800_NY-13208/2026/october/2026-10-12/PJR/wolf_800_NY-13208_PJR_2026-10-12_v1.zip
```

- The month is the English name in lower case (`october`); the date is `yyyy-MM-dd`.
- The type folder is the file's 3-letter prefix in capitals (`PJR`, `CPJ`, `MSM`, …), or the folder's name for an extra folder (`PassportReports`).
- In the outbox and S3, the store's folder is `STORE_FOLDER`.

A whole business day is one S3 folder: the back end lists `…/2026-10-12/` and downloads the highest version of each type.

**Inside a zip** are the type folder's files, with their paths as in the mirror, and `manifest.json`:

```json
{
  "store": "wolf_800_NY-13208",
  "type": "PJR",
  "businessDate": "2026-10-12",
  "version": 1,
  "createdAt": "2026-10-13T08:00:04Z",
  "fastMirrorVersion": "3.0.0",
  "fileCount": 913,
  "files": [
    { "path": "2026/october/2026-10-12/PJR/PJR3402610130004181533189.xml", "size": 4384, "modified": "2026-10-13T04:18:33Z", "sha256": "9f2c…" }
  ]
}
```

---

## Copying

### What gets copied

**Files in the source folder itself** are copied when the first three letters of the name are in `INCLUDE_PREFIXES` (case does not matter) and the extension is in `INCLUDE_EXTENSIONS` (default `xml`).

**Files in extra folders** listed in `INCLUDE_SUBFOLDERS` are copied when their extension is in `SUBFOLDER_EXTENSIONS` (default `pdf`). Each entry is a full path (`Z:/PassportReports`), so one agent can copy a POS that writes to two folders. There is no prefix filter for them; leave files out with `EXCLUDE_PATTERNS` (Wolf leaves out `UNREADEMAIL*`). Only files directly inside the folder are copied.

**Same name, same folder:** the newer file replaces the older copy.

### The business date

The date folder comes from one of three places, set with `DATE_SOURCE` (and `SUBFOLDER_DATE_SOURCE` for extra folders):

- `CONTENT` (recommended): the **business date written inside the file**, in the field(s) named by `CONTENT_DATE_FIELDS`, taken exactly as written. A Passport close report written at 00:09 on 1 October for business day 30 September goes to `2026\september\2026-09-30\`.
- `FILENAME`: the **date in the file name**, located with a template such as `???yyyy-MM-dd*` or `*yyyyMMddHHmmss.pdf`.
- `CREATED` (default): the file's **creation time**, turned into a date in `TIME_ZONE` (default `America/New_York`).

| POS | Business date inside the file | `.env` |
|---|---|---|
| Gilbarco Passport (Wolf) | `<BusinessDate>2026-09-22</BusinessDate>` in every report and journal | `CONTENT_DATE_FIELDS=BusinessDate` |
| Verifone Sapphire (Tiki) | `periodEndDate="2025-10-04T23:45:06-04:00"` in reports; `<closedTime>` in the CPJ | `CONTENT_DATE_FIELDS=periodEndDate,closedTime` |

**PDFs** carry no readable date, but their names hold the print time. With `SUBFOLDER_FILENAME_DATE_PATTERN=*yyyyMMddHHmmss.pdf` and `BUSINESS_DAY_STARTS_AT=03:00`, Passport's `StoreClose20260924000416.pdf` (printed 00:04 on the 24th) is filed under the 23rd, beside that day's XML.

A file with no date where one is expected falls back to its creation date and is named once in the log. **`COPY_FROM_DATE`** limits copying to files whose business date is on or after that day.

### When a file is copied

FastMirror checks the source every `SCAN_INTERVAL_SECONDS` (default 60). A file is copied once its **size and modified time have not changed for `STABILITY_MINUTES`** (default 3). It judges this from the file's attributes alone: it never locks a file or opens it exclusively to find out whether it is still being written, because that could make the POS's own write fail.

### Each file is copied once

Every completed copy is recorded in `state\copied.ledger`. A file in the ledger is never copied again, even after it has been removed from the mirror, and even across restarts. Deleting `copied.ledger` makes FastMirror copy everything again, from `COPY_FROM_DATE` onwards.

---

## Day packaging

Turned on with `PACKAGE_ENABLED=true` (on in all the example files).

**When.** Business day D is zipped at **`PACKAGE_AT_UTC` (default 08:00) on D+1**: 04:00 New York in summer, 03:00 in winter, after both stores' closes. FastMirror checks at start-up and then once a minute. Today is never zipped.

**What.** One zip per type folder of the day, so ~9 zips a day at Wolf and ~10 at Tiki. A type with no files that day has no zip.

**Versions.** A file for day D that arrives after D was zipped produces `…_v2.zip` holding **all** of that folder's files; `v1` is never changed. The back end loads the highest version. If nothing changed, nothing new is built.

**Catch-up.** Days missed while the agent was off are zipped at the next start, from `PACKAGE_FROM_DATE` if it is set. After a start, packaging waits until copying has caught up (the stability wait, two scans, and an empty copy queue), so a day still being copied is not zipped half-copied.

**How a zip is built.** It is written into the staging folder (`PACKAGE_STAGING_FOLDER`, default `<outbox>-staging`, on the outbox's drive), read back and checked against its manifest, and only then moved into the outbox in one step. The S3 agent skips only `~$` names, so it must never see a half-written zip, and it never does. The same files always give a byte-identical zip.

**Rules that hold.**

- Packaging reads the mirror only. It never reads the POS folders, never changes the mirror, and never overwrites a zip in the outbox.
- A packaging failure never stops copying. It is logged and retried a minute later.
- A day whose folder has lost files since its last zip is not zipped again incomplete; that is logged as an error.
- After a crash between moving a zip into the outbox and recording it, the zip is found at the next start, verified and recorded, not rebuilt.

Every zip is recorded in `state\package.ledger`: business date, type, version, UTC time, file count and a content fingerprint.

**Keeping the outbox small.** `OUTBOX_KEEP_DAYS=N` removes this store's zips of business days older than N days from the outbox, once a day. `0` (the default) keeps them forever. Nothing is ever deleted from S3.

---

## Time: UTC

Every moment FastMirror writes is **UTC**, marked `Z`: log lines, ledgers, zip manifests and `PACKAGE_AT_UTC`. The PC's own time zone is never used, so a PC set to New York, Kolkata or UTC behaves the same.

**Business dates are never converted.** They are what the POS wrote. A Tiki day closed at 21:40 New York (01:40 UTC the next day) stays on its own day. `TIME_ZONE` has one job: dating files that carry no date of their own, from their creation time.

---

## The rule that matters most

> Nothing FastMirror does may make a POS operation (create, open, write, rename, delete) fail, block, or return an error.

FastMirror opens POS files read-only through a single audited code path. Ordinary Java calls that can make another process's delete or write fail on Windows (`Files.copy`, `FileChannel.transferTo`, memory mapping, file locks) are banned outright. Tests scan the compiled bytecode and fail the build if any of them appear, or if a packaging class could reach a POS folder. Further tests run a simulated POS, including a genuinely separate Windows process, that deletes, renames and appends to files while FastMirror copies them, and assert that **its** operations all succeed.

A copy is never visible half-written either: it is written to a `.fmtmp` staging file beside the target and swapped into place in one step.

---

## Setup guide

### 1. FastMirror

```
C:\HOR\FastMirror\
    FastMirror.exe          no console window
    .env                    configuration
    jre\                    bundled Java 21 runtime; no installation required
    logs\                   created on first run
    state\                  created on first run; copied.ledger and package.ledger
```

1. Copy `FastMirror.exe` and `jre\` from `build\dist\` to the store PC.
2. Copy the store's example, `packaging\env-examples\verifone-tiki.env` or `passport-wolf.env`, beside the exe and rename it `.env`.
3. Check the folders in it: `SOURCE_FOLDER`, `MIRROR_FOLDER`, `INCLUDE_SUBFOLDERS` (Wolf), `OUTBOX_FOLDER`, and `STORE_FOLDER`. Every key is explained in `packaging\env.template`.
4. Double-click `FastMirror.exe`.

A green icon appears in the notification area (it may be under the **^** arrow), with a notification saying where it copies from and to. Hover for the running state; right-click for the status, **Last packaged: 2026-10-14 (9 zips)**, both folders and **Exit FastMirror**. Exit asks first; files that arrive while FastMirror is stopped are copied and packaged when it next starts.

### 2. The S3 agent

The S3 agent (`hor_aws_sync`) needs no code change. In its `.env`:

```properties
WATCH_FOLDER=<the same folder as FastMirror's OUTBOX_FOLDER>
S3_FOLDER=pos-raw
```

It uploads each new zip to `S3_FOLDER` + the zip's path inside the outbox, which is exactly `pos-raw/<STORE_FOLDER>/<yyyy>/<month>/<date>/<TYPE>/<zip>`. That is about 18 S3 calls a day at Wolf (a check and an upload per zip) instead of about 2,000.

### 3. The S3 agent's AWS key

Give each store's key access to its own folder only. Before each upload the agent checks whether the file is already there (`HEAD`); without `GetObject` and `ListBucket`, S3 answers that check with `403` instead of `404`.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::<bucket>/pos-raw/wolf_800_NY-13208/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<bucket>",
      "Condition": { "StringLike": { "s3:prefix": "pos-raw/wolf_800_NY-13208/*" } }
    }
  ]
}
```

### 4. Starting with Windows (optional)

Once started, FastMirror keeps running until you choose Exit, end it in Task Manager, log off, or restart the PC. To start it automatically, create a Task Scheduler task:

- **General:** run only when the user is logged on (the tray icon needs the desktop).
- **Trigger:** At log on, for that user.
- **Action:** Start a program: `C:\HOR\FastMirror\FastMirror.exe`, start in `C:\HOR\FastMirror`.
- **Settings:** if the task fails, restart every 1 minute; do not stop the task if it runs longer than any limit.

Mapped drives such as `Z:` exist only after that user logs on. A task that runs before logon must use the `\\server\share\...` form of the folders.

### What stops FastMirror from starting

It refuses to start, with a message box explaining why and exit code 1, if:

- the mirror, state, outbox or staging folder cannot be created or written;
- two folders overlap (source, mirror, state, extra folders, outbox, staging), or staging is on another drive than the outbox;
- a prefix is not exactly 3 letters or digits, or an `INCLUDE_SUBFOLDERS` entry is not a full path;
- a date, time, time zone or number is not valid, or `STORE_FOLDER` has characters other than letters, digits, `_` `-` `.`;
- `DATE_SOURCE=FILENAME` or `CONTENT` is set without its pattern or fields;
- there is nothing to copy: no prefixes and no extra folders.

### What does not stop it

- A source or extra folder that is not there at start-up (a network drive not yet connected): it logs a warning and starts copying when the folder appears.
- A folder that drops out while running: it logs that once and carries on when it comes back. Nothing is copied twice.
- A failed copy or a failed zip: logged and retried; neither stops the agent.

---

## Reading the log

One file per UTC day under `logs\`, kept for 30 days. Every time in the log is UTC, marked `Z`:

```
2026-10-15 10:03:07Z [INFO] Initial scan: 412 matching file(s) not yet copied; each is copied once unchanged for 3 minute(s)
2026-10-15 10:06:08Z [INFO] Copied: CPJ432.xml -> 2026\october\2026-10-15\CPJ\CPJ432.xml (2048 bytes)
2026-10-16 08:00:04Z [INFO] Packaged 2026/october/2026-10-15/PJR v1: 913 file(s) -> C:\HOR\outbox\wolf_800_NY-13208\2026\october\2026-10-15\PJR\wolf_800_NY-13208_PJR_2026-10-15_v1.zip
2026-10-16 08:00:07Z [INFO] Stats (last 60s): COPIED=4 PACKAGED=9
```

| Counter | Meaning | What to do |
|---|---|---|
| `COPIED` | A new file reached the mirror. | Nothing. |
| `REPLACED` | A file replaced an earlier copy with the same name in the same folder. | Nothing, unless it is unexpectedly frequent. |
| `LOCKED` | Another process held the file exclusively past the retry budget. | Harmless in small numbers; the next scan retries. |
| `VANISHED` | The file was deleted between being found stable and being copied. | Rare. Nothing was copied. |
| `FAILED` | An I/O error. Nothing was published; the next scan retries. | Investigate. Usually disk space or permissions. |
| `SKIPPED_TOO_LARGE` | The file is larger than `MAX_COPY_BYTES`. | Raise the limit if the file is wanted. |
| `PACKAGED` | A zip reached the outbox. | Nothing. About 9 a day at Wolf, 10 at Tiki. |

---

## Operator responsibilities

- **Mirror growth.** FastMirror never deletes from the mirror. A scheduled task deleting day folders older than N days is enough; deleted copies are not copied again, because the ledger remembers them. Do not delete a day before it has been zipped.
- **Outbox growth.** Set `OUTBOX_KEEP_DAYS`, or leave the zips; they are small (about 1.5 MB a day at Wolf).
- **Monitoring.** Watch `FAILED`, `LOCKED` and any `[ERROR]` line about packaging.
- **Do not delete `state\copied.ledger` or `state\package.ledger`** unless you want everything copied, or zipped, again.

Stopping the agent lets in-flight copies and a zip being built finish. A forced kill is also safe: a `.fmtmp` or `.part` staging file may be left behind, and the next start removes it.

---

## Building from source

Requires JDK 21. Gradle comes from the wrapper.

```
gradlew.bat build          compile and run the test suite
gradlew.bat slowTest       the heavy scenarios: 50 MB atomic visibility, 5,000-file backlog, soak
gradlew.bat fatJar         produces mirror\build\libs\FastMirror.jar
```

### Producing `FastMirror.exe`

Three commands, from the project root in `cmd`. Requires [Launch4j](https://launch4j.sourceforge.net/).

```bat
gradlew.bat fatJar

jlink --add-modules java.base,java.desktop --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output build\dist\jre

"C:\Program Files (x86)\Launch4j\launch4jc.exe" packaging\launch4j.xml
```

That leaves `build\dist\FastMirror.exe` and `build\dist\jre\` (about 46 MB). `jlink` refuses to write into an existing directory, so delete `build\dist\jre` before re-running it.

- The module list is exactly what the agent needs: `jdeps --print-module-deps --ignore-missing-deps mirror\build\libs\FastMirror.jar` reports `java.base,java.desktop`. **`java.desktop` is required** for the tray icon.
- The Launch4j config sets no `minVersion` on purpose. With one, the bundled runtime becomes a preference and the exe may start on whatever Java is installed. With none, a missing `jre\` is an immediate, clear error.

### Layout

```
mirror/src/main/java/com/handsoffretail/fastmirror/
    Application.java            composition root: wiring and startup order only
    config/                     .env parsing and startup validation
    entity/                     copy jobs, ledger keys, type folders, manifests, counters
    service/                    interfaces
    service/implementation/     concrete services, all named *Impl
    logging/                    daily rolling UTC log
```

All reading of POS files is confined to `AtomicFileCopierImpl`. Packaging (`ZipDayPackagerImpl`, `DailyPackageSchedulerImpl`) is given only the mirror, outbox and staging folders.

### Test suite

340 tests in the default run, 3 more under `slowTest`. The ones to understand first:

- `ProducerNonInterferenceTest`: the executable form of the rule above. A red run here blocks release regardless of everything else.
- `ArchitectureRulesTest`: the banned calls, and packaging's isolation from the POS folders.
- `PosLinkArchiveIntegrationTest` and `DayPackagingIntegrationTest`: copying and packaging end to end, through the real composition root.
- `WolfRealDataTest` and `TikiRealDataTest`: the real sample files under `OneDrive\HOR-XML`, through the shipped `.env` files. Wolf business day 2026-09-24 gives 8 XML zips + 1 PDF zip (992 XML files, 3 PDFs); each Tiki report and the CPJ get their own zip. Skipped on a PC without the samples.
- `TimeZoneIndependenceTest`: the same results with the PC set to New York, Kolkata or UTC.
