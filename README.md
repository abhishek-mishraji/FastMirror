# FastMirror

A Windows background agent that copies selected files out of the **POS Link Archive folder** into a **mirror folder**, sorted by file type and date, once each file has finished being written.

```
C:\Program Files (x86)\CStore Essentials\POS Link\Service\Archive\
    CPJ432.xml  HRS007.xml  SUM12.xml  ...
        |
        |  FastMirror: waits until each file is stable, copies the selected ones,
        |  sorts them by prefix and creation date, and remembers what it copied
        v
...\Mirrored_files\
    CPJ\2026\october\2026-10-15\CPJ432.xml
    HRS\2026\october\2026-10-15\HRS007.xml
        |
        |  (separate, existing agent)
        v
   Amazon S3
```

There is one FastMirror per store PC, each with its own `.env`. FastMirror does not upload anything, does not talk to any cloud provider, never writes to the Archive folder, and **never deletes anything from the mirror**.

---

## What gets copied, and where

**Files in the Archive folder itself** are copied when:

- the first three letters of the name are in `INCLUDE_PREFIXES` (case does not matter, so `cpj432.xml` counts as `CPJ`), and
- the extension is in `INCLUDE_EXTENSIONS` (default `xml`).

They go to `<PREFIX>\<year>\<month>\<date>\<name>`. The month is the English name in lower case (`october`) and the date is `2026-10-15`. The prefix folder is always upper case.

**Files in other folders** listed in `INCLUDE_SUBFOLDERS` (none by default) are copied when their extension is in `SUBFOLDER_EXTENSIONS` (default `pdf`). Each entry is a full path to a folder anywhere (`Z:/PassportReports`), so one agent can copy a POS that writes to two folders. There is no prefix filter for them; leave files out with `EXCLUDE_PATTERNS`. They go to `<folder name>\<year>\<month>\<date>\<name>`. Only files directly inside the folder are copied; folders nested deeper are ignored.

**The date** that picks the folder comes from one of three places, set per store with `DATE_SOURCE`. The code is the same for every store; only `.env` differs. Ready-made files are in `packaging/env-examples/`.

- `CONTENT` (recommended): the **business date written inside the file**, in the field(s) named by `CONTENT_DATE_FIELDS`, taken exactly as written. A report written at 00:09 on 1 October for business day 30 September goes to `september\2026-09-30`.
- `FILENAME`: the **date written in the file name**, located with `FILENAME_DATE_PATTERN`.
- `CREATED` (default): the file's **creation time**, converted to **`TIME_ZONE`** (default `America/New_York`).

| POS | Business date inside the file | `.env` |
|---|---|---|
| Gilbarco Passport (Wolf) | `<BusinessDate>2026-09-22</BusinessDate>` in every report and journal | `CONTENT_DATE_FIELDS=BusinessDate` |
| Verifone Sapphire (Tiki) | `periodEndDate="2025-10-04T23:45:06-04:00"` in reports; `<closedTime>` in the CPJ day journal | `CONTENT_DATE_FIELDS=periodEndDate,closedTime` |

Passport names its files by when they were written, so a close just after midnight carries the next day's date in its name; only the content has the business date. For the CPJ, `closedTime` is named because its first `<date>` is the day before. Files with no business date inside (Passport acknowledgements and system messages, Verifone PJR live slices) fall back to their creation date and are named once in the log. The date is read once per file, from the first `CONTENT_SCAN_BYTES` (64 KB), through the same read-only open as the copy, once the file is stable.

**PDFs and other files without a date inside** can be dated by the print time in their name plus `BUSINESS_DAY_STARTS_AT`. Passport's `StoreClose20260924000416.pdf` was printed at 00:04 on the 24th for business day 23 September; with `SUBFOLDER_FILENAME_DATE_PATTERN=*yyyyMMddHHmmss.pdf` and `BUSINESS_DAY_STARTS_AT=03:00` it is filed under the 23rd, and a 1 October 00:10 StoreClose stays in September. Checked against all 21 StoreClose and ShiftClose PDFs in the Wolf sample.

`FILENAME` remains for stores whose names carry the business date:

`FILENAME_DATE_PATTERN` is a template lined up against a real file name: `yyyy` or `yy` for the year, `MM` the month, `dd` the day, `?` any one character, `*` any number of characters.

| Store's file names | Pattern | Date |
|---|---|---|
| `CPJ2026-09-13-590.xml` | `???yyyy-MM-dd*` | 2026-09-13 |
| `FGM3402609230009241533201.xml` | `??????yyMMdd*` | 2026-09-23 |

A file whose name does not fit the pattern, or whose digits are not a real date, falls back to its creation date and is named once in the log. Subfolder files have their own `SUBFOLDER_DATE_SOURCE` and `SUBFOLDER_FILENAME_DATE_PATTERN`.

**`COPY_FROM_DATE`** (for example `2026-01-01`) limits copying to files whose date, as above, is on or after that day. Left empty, everything already in the Archive is copied when the agent first starts.

**Same name, same folder:** the newer file overwrites the older copy.

## When a file is copied

FastMirror checks the Archive every `SCAN_INTERVAL_SECONDS` (default 60). A file is copied only once its **size and modified time have not changed for `STABILITY_MINUTES`** (default 3), so a file POS Link is still writing is left alone.

FastMirror judges this from the file's attributes alone. It never locks the file or opens it exclusively to check whether someone is still writing it, because that could make POS Link's own write fail. The trade-off: a writer that pauses for longer than `STABILITY_MINUTES` in the middle of a file would get a partial file copied. Raise `STABILITY_MINUTES` if that ever happens.

Files already in the Archive when the agent starts also wait out the stability period, so the first copies appear about 3 minutes after startup.

## Each file is copied once

Every completed copy is recorded in `state\copied.ledger` beside the exe. A file in the ledger is never copied again, even after the S3 agent or a retention job removes it from the mirror, and even across restarts.

- A file is recognised by its name, creation time and size. If POS Link deletes a file and later creates a new one with the same name, that is a new file and is copied.
- **Deleting `copied.ledger` makes FastMirror copy everything again**, from `COPY_FROM_DATE` onwards. That is how to force a full re-copy.
- At startup, entries for files no longer in the Archive are removed, so the ledger does not grow forever.
- A copy that fails or is interrupted is not recorded, and is retried on the next scan.

## The rule that matters most

> No action taken by FastMirror may cause a POS Link operation (create, open, write, rename, delete) to fail, block, or return an error.

FastMirror opens Archive files read-only through a single audited code path, and a set of ordinary Java calls is banned outright because each of them can make another process's delete or write fail on Windows (`Files.copy`, `FileChannel.transferTo`, memory mapping, file locks). Tests scan the compiled bytecode and fail the build if any of those appear. Further tests run a simulated producer, including a genuinely separate Windows process, that deletes, renames and appends to files while FastMirror is copying them, and assert that **its** operations all succeed.

A copy is never visible half-written: it is written to a `.fmtmp` staging file beside the target and swapped into place in one step, so the S3 agent sees either nothing or the complete file.

---

## Installation

```
C:\HOR\FastMirror\
    FastMirror.exe          no console window
    .env                    configuration
    jre\                    bundled Java 21 runtime; no installation required
    logs\                   created on first run
    state\                  created on first run; holds copied.ledger
```

Copy the folder to the machine, edit `.env`, and double-click `FastMirror.exe`. No Java installation is needed.

### The tray icon

When it starts, FastMirror puts a green icon in the notification area (bottom-right of the taskbar; it may be under the **^** overflow arrow) and shows a notification saying where it is copying from and to.

- **Hover** over the icon: running state, both folders, files copied so far.
- **Right-click**: the same details, and **Exit FastMirror**.
- **Double-click**: shows the notification again.

Exit asks for confirmation first. Nothing is lost by stopping: files that arrive while FastMirror is stopped are copied when it next starts. After you confirm, it lets in-flight copies finish, then closes.

Run as a Windows service there is no desktop, so there is no icon. The agent works exactly the same and notes it in the log.

For unattended operation, register it as a Windows service or a Task Scheduler task set to run at startup, restart on failure, and run whether or not a user is logged on.

### Configuration

Three keys are required:

```properties
SOURCE_FOLDER=C:/Program Files (x86)/CStore Essentials/POS Link/Service/Archive
MIRROR_FOLDER=C:/Users/Owner/Documents/HandsOff-Software/Tiki_8730_IN-47872/Mirrored_files
INCLUDE_PREFIXES=CPJ,SUM,MCM,ISM,MSM,HRS,FGM,FCF,LYT,TPM
```

Everything else has a working default. See `packaging/env.template` for the full list with explanations. Configuration is read once at startup; there is no hot reload.

FastMirror refuses to start, with a message box explaining why and exit code 1, if:

- the mirror or state folder cannot be created or written;
- two of the three folders overlap;
- a prefix is not exactly 3 letters or digits;
- an `INCLUDE_SUBFOLDERS` entry is not a full path;
- `COPY_FROM_DATE` is not a `yyyy-MM-dd` date;
- `TIME_ZONE` is not a time zone name such as `America/New_York`;
- `DATE_SOURCE=FILENAME` is set without a usable `FILENAME_DATE_PATTERN` (the same for the subfolder keys);
- a number is out of range;
- there is nothing to copy: no prefixes and no subfolders.

If `INCLUDE_PREFIXES` is empty but `INCLUDE_SUBFOLDERS` is set, only subfolders are copied, and a warning says so.

**Once started, FastMirror keeps running until you choose Exit from the tray, end it in Task Manager, log off, or restart the PC.** It never stops by itself:

- If the source folder is not there at startup (a network drive or VM share not yet connected), it starts anyway, logs a warning, and begins copying when the folder appears.
- If the source folder drops out while running, it logs that once, keeps checking every scan, and carries on when the folder comes back. Nothing is copied twice.
- A failed copy is logged and retried on the next scan; it never stops the agent.

---

## Reading the log

One file per day under `logs\`, kept for 30 days:

```
2026-10-15 10:03:07 [INFO] Initial scan: 412 matching file(s) not yet copied; each is copied once unchanged for 3 minute(s)
2026-10-15 10:06:08 [INFO] Copied: CPJ432.xml -> CPJ\2026\october\2026-10-15\CPJ432.xml (2048 bytes)
2026-10-15 10:06:09 [INFO] Scan: 412 file(s) stable and queued for copy, 0 still waiting, 412 copied so far
2026-10-15 10:07:07 [INFO] Stats (last 60s): COPIED=412
```

The counters:

| Counter | Meaning | What to do |
|---|---|---|
| `COPIED` | A new file reached the mirror. | Nothing. |
| `REPLACED` | A file overwrote an earlier copy with the same name in the same folder. | Nothing, unless it is unexpectedly frequent. |
| `LOCKED` | Another process held the file exclusively past the retry budget. | Harmless in small numbers; the next scan retries. |
| `VANISHED` | The file was deleted between being found stable and being copied. | Rare. Nothing was copied. |
| `FAILED` | An I/O error. Nothing was published; the next scan retries. | Investigate. Usually disk space or permissions. |
| `SKIPPED_TOO_LARGE` | The file is larger than `MAX_COPY_BYTES`. | Raise the limit if the file is wanted. |

---

## Operator responsibilities

- **Mirror folder growth.** FastMirror never deletes from the mirror. If the S3 agent does not remove uploaded files, apply a retention policy; a scheduled task deleting files older than N days is enough. Deleted copies are not re-copied, because the ledger remembers them.
- **Disk space.** Size the mirror volume for the retention period times the daily file volume.
- **Monitoring.** Watch `FAILED` and `LOCKED`.
- **Do not delete `state\copied.ledger`** unless you want everything copied again.

Stopping the agent with Ctrl+C or a service stop lets in-flight copies finish and cleans up. A forced kill is also safe: at worst a `.fmtmp` staging file is left behind, and the next startup removes it. The mirror is never left holding a half-written file either way. A copy whose ledger entry was not yet written is simply copied again, to the same place.

---

## Building from source

Requires JDK 21. Gradle comes from the wrapper.

```
gradlew.bat build          compile and run the test suite
gradlew.bat slowTest       the heavy scenarios: 50 MB atomic visibility, 5,000-file backlog, soak
gradlew.bat fatJar         produces mirror\build\libs\FastMirror.jar
```

### Producing `FastMirror.exe`

Three commands, from the project root in `cmd`. Requires [Launch4j](https://launch4j.sourceforge.net/) installed.

```bat
gradlew.bat fatJar

jlink --add-modules java.base,java.desktop --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output build\dist\jre

"C:\Program Files (x86)\Launch4j\launch4jc.exe" packaging\launch4j.xml
```

That leaves `build\dist\FastMirror.exe` and `build\dist\jre\` (about 46 MB). Copy both, plus a `.env` built from `packaging\env.template`, to the target machine.

`jlink` refuses to write into an existing directory, so delete `build\dist\jre` before re-running it.

About that module list, both verified rather than assumed:

- It is exactly what the agent needs — `jdeps --print-module-deps --ignore-missing-deps mirror\build\libs\FastMirror.jar` reports `java.base,java.desktop` and nothing else. (v2 also needed `jdk.unsupported` for its file watcher; v3 has no watcher.)
- **`java.desktop` is mandatory** for the tray icon.

The Launch4j config deliberately sets no `minVersion`. Setting one turns the bundled runtime into a mere preference and lets the exe fall back to whatever Java is installed — which was observed here: with `jre\` renamed away, the exe happily started on an installed JDK. On a store PC that would mean a mis-copied `jre\` folder silently running the agent on the wrong Java. With no version range the bundled runtime is required, and its absence is an immediate error instead of a subtle one.

### Layout

```
mirror/src/main/java/com/handsoffretail/fastmirror/
    Application.java            composition root: wiring and startup order only
    config/                     .env parsing and startup validation
    entity/                     copy jobs, ledger keys, outcomes, counters
    service/                    interfaces
    service/implementation/     concrete services, all named *Impl
    logging/                    daily rolling file log
```

All reading of Archive files is confined to `AtomicFileCopierImpl`. That is deliberate: it puts every rule about not disturbing POS Link in one small reviewable place, and lets the architecture tests enforce it mechanically. The scan only reads directory listings and file attributes.

### Test suite

273 tests in the default run, 3 more under `slowTest`. The ones to understand first are in `ProducerNonInterferenceTest` — they are the executable form of the rule above, and a red run there blocks release regardless of everything else. `PosLinkArchiveIntegrationTest` covers the selection, layout, stability and ledger behaviour end to end.

Two items cannot be verified off the store PC and are handed over unverified:

- Interoperation with the live S3 upload agent watching the mirror folder.
- A long soak against the real Archive folder. Run it with `gradlew slowTest -Dfastmirror.soak.seconds=86400`, and record the `FAILED` and `LOCKED` counts.

---

Current plan: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) (date-first layout, UTC, day packaging). Earlier specifications and plans are in the git history.
