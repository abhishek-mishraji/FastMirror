# Software Requirements Specification (SRS)

## Project: FastMirror — Windows Fast Folder Mirror Agent

**Version:** 3.0
**Date:** 2026-10-11
**Supersedes:** 2.0 (2026-09-20)
**Platform:** Windows 10 / 11
**Language:** Java 21
**Build System:** Gradle (Kotlin DSL, standalone project)
**Application Type:** Background file mirroring agent
**Runtime dependencies:** JDK only (no third-party libraries)
**Status:** Specification — not yet implemented

### Changes in 3.0 (2026-10-11) — POS Link Archive

v3 retargets FastMirror from the Gemcom instant-capture case to the CStore Essentials **POS Link Archive** folder, where files persist. Where this section conflicts with the rest of the document, this section wins. Design and decisions: [IMPLEMENTATION_PLAN_POSLINK.md](IMPLEMENTATION_PLAN_POSLINK.md).

| Change | Replaces |
|---|---|
| **Stable-then-copy.** A scan every `SCAN_INTERVAL_SECONDS` (default 60) copies a file once its size and modified time have been unchanged for `STABILITY_MINUTES` (default 3). The file is judged by attributes only; it is never locked or opened exclusively. | The watcher, event thread, 500 ms sweep and capture-latency goal (§9.2, §9.3, §9.7, FR-01…FR-03, FR-13…FR-15, NFR-01, C-5, C-6) |
| **Selection.** Root files by 3-character prefix (`INCLUDE_PREFIXES`, case-insensitive) and extension (`INCLUDE_EXTENSIONS`, default `xml`). Files directly in `INCLUDE_SUBFOLDERS` by `SUBFOLDER_EXTENSIONS` (default `pdf`), no prefix filter. `COPY_FROM_DATE` limits by creation date. | Copy-everything-except-excludes (FR-18 still applies on top) |
| **Grouped layout.** Root: `<PREFIX>/<yyyy>/<month>/[<yyyy-MM-dd>/]<name>` per `GROUP_BY=DATE\|MONTH`. Subfolder: `<subfolder>/<yyyy>/<month>/<name>`. Date = the file's creation time, local zone; month = lower-case English name. | Structure-preserving relative paths (FR-06, §6.2 `PathMapper`) |
| **Copy ledger.** `state/copied.ledger` (outside source and mirror) records every completed copy by path, creation time and size; recorded files are never copied again. Compacted at startup. | Size/timestamp difference detection and `CONTENT_VERIFY` (§9.4) |
| **No partial publication.** A read that fails part-way publishes nothing and is retried on the next scan. `PARTIAL`, `UNCHANGED` and `PUBLISH_PARTIAL` are removed. | PD-2, FR-27, §9.5 partial path |
| Filename-reuse detection removed; a new file with the same name and destination overwrites the old copy. | FR-31, R-10, U-1 |
| Retired keys `SWEEP_INTERVAL_MS`, `CONTENT_VERIFY`, `PUBLISH_PARTIAL`, `MIRROR_EMPTY_DIRECTORIES` warn and are ignored. | §7.1 |

Unchanged: the non-interference rules NI-1…NI-13 and their enforcement (AT-02, AT-03, IT-18…IT-20), atomic publish via staging files, the never-delete-from-mirror rule, the log format, the tray icon and packaging.

### Changes in 2.0

| Change | Sections |
|---|---|
| **Producer non-interference promoted to the prime directive.** A new binding rule set (NI-1…NI-13) forbids every operation that could return an error to, or block, the producer. | §1.1, §2.1, §5.1, §8 (FR-26…FR-30), §10 (NFR-10) |
| New platform constraints on Windows share modes, memory mapping and `CopyFileEx`. | C-8, C-9, C-10, C-11 |
| **Partial data is now published, not discarded.** A transfer that fails part-way publishes the bytes already captured instead of deleting them. New `PARTIAL` outcome and `PUBLISH_PARTIAL` key. | §7.1, §9.5, §9.8, §9.9, §11 |
| Build changed from "second subproject of `hor_aws_sync`" to a standalone Gradle project, deployed as `FastMirror.exe` with a JRE folder beside it. | §14.1, §14.2, NFR-08 |
| New acceptance tests for non-interference and partial publication. | IT-18…IT-21, UT-17, UT-18, AT-02, AT-03 |

---

# 1. Purpose

FastMirror is a standalone Windows background agent whose only job is to copy files from a **source folder** to a **mirror folder** as fast as technically possible, preserving the exact relative directory structure.

It exists because a producer application (Gemcom) on one particular store PC creates XML files, writes to them, and deletes them **within approximately one second**. Any consumer that waits before reading such a file loses it permanently.

FastMirror is deliberately a *dumb, fast copier*. It does not upload anything, does not talk to any cloud provider, and does not delete anything. It converts a volatile source folder into a stable mirror folder that other tools — such as the existing HOR AWS Sync Agent — can consume at their own pace.

```text
C:\Gemcom\bin\out\          (volatile: files live ~1 second)
        |
        |  FastMirror  — copy immediately, preserve structure
        v
C:\temp\                    (stable: files persist)
        |
        |  (separate, existing, unmodified agent)
        v
   Amazon S3
```

## 1.1 Prime directive — the producer must never notice FastMirror

FastMirror is an **observer**. The producer application is the system of record and its correct operation outranks every other requirement in this document, including the capture of any individual file.

> **PD-1.** No action taken by FastMirror shall cause any operation performed by the producer — create, open, write, flush, rename, delete or any other — to fail, to block, to be delayed, or to return an error of any kind.

Where this specification and the prime directive appear to conflict, the prime directive wins. If a file cannot be captured without risking interference, **the file is lost and that is the correct outcome.** The `VANISHED` counter (§9.8) exists so that such losses are measured rather than traded away.

This is the reason for the detailed platform rules in §5.1: on Windows it is entirely possible for a reader to make another process's `DeleteFile` or `WriteFile` fail, and it happens through ordinary, innocent-looking Java calls — `Files.copy`, `FileChannel.transferTo`, memory mapping. Those calls are therefore prohibited by name.

## 1.2 Second priority — capture whatever bytes exist

Subject to PD-1, FastMirror captures as much data as it can. Explicitly:

> **PD-2.** When FastMirror has read some bytes of a file and cannot read the rest — because the producer deleted it, truncated it, or the volume raised an I/O error — the bytes already captured are **published to the mirror**, not discarded.

Some data beats no data. A partially captured file is evidence; a deleted staging file is nothing. §9.5 and §9.8 implement this.

---

# 2. Scope

## 2.1 In scope

| | |
|---|---|
| Watch a configured source folder recursively | Yes |
| Copy new files to the mirror folder immediately | Yes |
| Re-copy a file when its content changes | Yes |
| Preserve the exact relative folder structure | Yes |
| Copy files that are still being written | Yes — intentionally |
| Publish partially captured files rather than discarding them | Yes — §1.2, §9.5 |
| **Guarantee zero interference with the producer** | **Yes — the prime directive, §1.1, §5.1** |
| Survive files that are deleted ~1 second after creation | Best effort, see §12 |
| Run unattended in the background on Windows | Yes |

## 2.2 Out of scope — explicitly NOT built

These are listed so the implementer does not add them:

- **No cloud storage of any kind.** No Amazon S3, no other provider, no network I/O whatsoever.
- **No deletion propagation.** A file deleted from the source is **never** deleted from the mirror.
- **No rename or move detection.** A renamed source file simply appears in the mirror under both names.
- **No bidirectional sync.** The mirror is write-only from FastMirror's perspective; changes made in the mirror by other tools are never copied back.
- **No content transformation.** Files are copied byte-for-byte. No XML parsing, validation, reformatting or filtering of content.
- **No retention or cleanup of the mirror folder.** FastMirror never removes anything from the mirror. Retention is the operator's responsibility (§14.3).
- **No modification of the existing HOR AWS Sync Agent.** FastMirror is a separate program with its own process, configuration and log.
- **No exclusive access, no locking, and no write access of any kind to the source.** See §5.1.

## 2.3 Relationship to the existing agent

FastMirror and the HOR AWS Sync Agent are **two independent processes**. They share no code and no configuration file. The only contract between them is the mirror folder on disk:

- FastMirror's `MIRROR_FOLDER` is configured to be the same directory as the sync agent's `WATCH_FOLDER`.
- FastMirror guarantees that a file appears in the mirror folder **atomically, and as complete as it was captured** (§9.5), so the sync agent never observes a target that FastMirror is part-way through writing.

No change of any kind is required in the existing agent.

---

# 3. Definitions

| Term | Meaning |
|---|---|
| **Producer** | The external application (Gemcom) that creates, writes and deletes files in the source folder. Never modified, never coordinated with, never blocked. |
| **Source folder** | The directory tree being watched. Configured as `SOURCE_FOLDER`. Read-only to FastMirror. |
| **Mirror folder** | The destination directory tree. Configured as `MIRROR_FOLDER`. Written only by FastMirror. |
| **Relative path** | A file's path relative to the source root, e.g. `store001\report.xml`. |
| **Target file** | `MIRROR_FOLDER` + relative path. |
| **Staging file** | A temporary file `<target>.<attempt>.fmtmp` used during a copy and renamed into place on completion. The attempt counter makes the name unique per copy attempt, so no two attempts can interfere (§9.5). |
| **Copy job** | One unit of work: copy one source file to its target. |
| **Capture latency** | Milliseconds from receiving a filesystem event to the source file handle being successfully opened. |
| **Vanished** | The source file no longer existed when FastMirror first tried to open it. Nothing was captured. |
| **Partial capture** | Some bytes were read and the read could not complete. The captured prefix is published (§1.2). |
| **Share mode** | The Windows `CreateFile` parameter declaring what other processes may do to a file while this handle is open. The mechanism behind the prime directive (C-8). |

---

# 4. Operating assumptions

These are stated so the implementer understands what the design must tolerate. They are drawn from the observed behaviour of the producer on the target PC.

| ID | Assumption |
|---|---|
| A-1 | Files are created, written and deleted within approximately 1000 ms. |
| A-2 | Files are small — typically a few kilobytes of XML. Large files are possible but rare. |
| A-3 | The producer may hold the file open with restrictive share flags while writing. |
| A-4 | A `CREATE` event may be delivered after the file has already been modified or deleted. |
| A-5 | The source may contain subdirectories; structure must be preserved. |
| A-6 | The mirror folder is on a local NTFS volume with sufficient free space. |
| A-7 | FastMirror runs with read access to the source and full access to the mirror. |
| A-8 | The producer's error handling cannot be relied upon. Any error FastMirror causes it must be assumed to result in lost business data. This is why §1.1 is absolute. |

---

# 5. Windows and Java platform constraints

The implementer must understand these before writing code, because the design depends on them.

**C-1 — `WatchService` notifies after the fact.** An `ENTRY_CREATE` event means "a file with this name was created at some point in the past." It carries no handle and no content. By delivery time the file may already be different or gone. Every event handler must re-validate and must tolerate absence.

**C-2 — An open handle survives deletion.** This is the single most important platform fact for this design. On Windows, if another process deletes a file while FastMirror holds an open handle, the file enters *delete-pending* state: the directory entry disappears and new opens fail, but **FastMirror's existing handle continues to read the real data until it is closed.** Therefore the race is only on the **first `open()`**, not on the read that follows it. Once the handle is obtained, the copy is safe.

**C-3 — Exclusive locks cause open failures.** If the producer holds the file without `FILE_SHARE_READ`, `FileChannel.open(..., READ)` throws `AccessDeniedException` or another `FileSystemException`. This is transient and typically clears within tens of milliseconds. Note the direction: the error is raised **to FastMirror**, never to the producer. That is the acceptable direction of failure.

**C-4 — Deleted means gone.** Once the last handle closes and the delete completes, the name is removed and `NoSuchFileException` is thrown. There is no recovery mechanism in Java or in any other language. See §12.

**C-5 — `ExtendedWatchEventModifier.FILE_TREE`** (`com.sun.nio.file`, exported by the `jdk.unsupported` module) registers one watch for an entire tree on Windows. It is required; registering each directory separately is slower and races against directory creation.

**C-6 — The watch buffer can overflow.** Under a burst, Windows drops notifications and Java delivers `OVERFLOW`. This must be handled by rescanning (§9.7), not ignored.

**C-7 — `ATOMIC_MOVE` within one directory is a directory-entry swap.** It is instantaneous and a reader that already opened the old target keeps reading coherent old content. This is why it is used for publishing (§9.5).

**C-8 — Share modes decide whether the producer gets an error.** Every Windows `CreateFile` declares a share mode. A reader that does not grant `FILE_SHARE_DELETE` makes the producer's `DeleteFile` fail with `ERROR_SHARING_VIOLATION`; one that does not grant `FILE_SHARE_WRITE` makes the producer's `WriteFile` fail. The JDK's Windows NIO implementation (`FileChannel.open` / `Files.newByteChannel`) opens with `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`, which is exactly what the prime directive requires and is also what makes C-2 work.

> Java exposes no API for setting the share mode. This behaviour is a JDK implementation detail, not a published contract. It is therefore **verified empirically by IT-18**, which fails the build if a JDK upgrade ever changes it.

**C-9 — Memory mapping is a version-dependent hazard, not a guaranteed failure.** `ERROR_USER_MAPPED_FILE` is the documented Windows error for operating on a file with an active mapped section, and on older Windows it makes a delete fail outright.

> **Measured on the target class of machine (Windows 11 build 26200, JDK 21.0.10), it does not.** Both an in-process `Files.delete` and an external `cmd /c del` succeeded against a memory-mapped file, because modern Windows deletes by unlinking the name. Version 1.0 of this specification asserted the opposite; that assertion was wrong for this platform and has been corrected. The measurement is re-taken on every build by the platform-record test in §13.2 rather than assumed in either direction.
>
> This correction **does not relax NI-4.** Mapping remains forbidden because the behaviour is Windows-version and filesystem dependent, because it is documented to fail elsewhere and over SMB, because `FileChannel.transferTo` on Windows memory-maps the source in chunks and so inherits the same uncertainty, and because an explicit bounded read/write loop is equally fast, costs nothing to write and is required anyway by NFR-04's bounded-memory rule. Where the prime directive is concerned, an avoidable version-dependent risk is not worth taking for zero benefit.

**C-9a — What was actually measured.** With FastMirror holding a source file open exactly as NI-1 specifies, a separate Windows process (`cmd.exe`) successfully deleted it, renamed it and appended to it, each returning exit code 0, while FastMirror's handle continued to read the original bytes. This is the empirical basis for PD-1 and C-2 and is verified by IT-18c, IT-19c and IT-20c.

**C-10 — `Files.copy` does not use a share mode we control.** `java.nio.file.Files.copy(Path, Path, ...)` on Windows delegates to the Win32 copy API, whose source share mode is not under the caller's control and is more restrictive than NIO's. It must not be used on a source file. See NI-3.

**C-11 — The watch handle pins the watched directory.** Registering a `WatchService` on `SOURCE_FOLDER` holds an open handle on that directory. Files inside it delete freely and are unaffected, but the **source directory itself** may not be renamable or deletable by another process while FastMirror runs. This is an accepted, documented side effect (R-8) because the producer does not delete its own output directory.

## 5.1 Producer non-interference rules — binding

These implement PD-1. Each is a hard prohibition; a violation is a defect of the highest severity regardless of whether it was observed to cause a failure. The architecture tests (§13.3) and code review both enforce them.

| ID | Rule | Rationale |
|---|---|---|
| NI-1 | The source shall be opened **only** via `FileChannel.open(path, StandardOpenOption.READ)` or `Files.newByteChannel(path, READ)`. No other open call is permitted. | C-8: these grant `FILE_SHARE_DELETE` and `FILE_SHARE_WRITE`. |
| NI-2 | `READ` shall be the only open option. `WRITE`, `APPEND`, `CREATE`, `CREATE_NEW`, `TRUNCATE_EXISTING`, `DELETE_ON_CLOSE` and `SYNC` are prohibited on the source. | Any write intent contends with the producer. |
| NI-3 | `Files.copy(...)` shall never be called with a source-folder path as its source. | C-10. |
| NI-4 | `FileChannel.map`, `MappedByteBuffer`, `FileChannel.transferTo` and `FileChannel.transferFrom` shall never be applied to a source channel. Transfers use an explicit bounded `ByteBuffer` read/write loop. | C-9. A mapped section is a version-dependent hazard to the producer's delete, is documented to fail on other Windows versions and over SMB, and buys nothing: an explicit loop is equally fast and is required by NFR-04 regardless. |
| NI-5 | `FileChannel.lock` / `tryLock` shall never be called on a source file. | An OS-level lock blocks the producer directly. |
| NI-6 | `java.io.FileInputStream`, `FileOutputStream` and `RandomAccessFile` shall never be used on a source file. | Share semantics are less predictable than NIO's; NI-1 is the only sanctioned path. |
| NI-7 | FastMirror shall never write, create, delete, rename, move or truncate anything inside `SOURCE_FOLDER`. | Restates FR-16 as a non-interference rule. |
| NI-8 | FastMirror shall never modify any attribute of a source file or directory — timestamps, ACLs, read-only flag, archive bit or any other. | An attribute write requires opening for write access. |
| NI-9 | Source handles shall be held for the shortest possible time and closed in a `finally` block or try-with-resources. No handle shall be retained across copy jobs or cached. | Shortens the window in which any C-9-class effect could apply, and bounds handle count (NFR-06). |
| NI-10 | The number of source handles open at any instant shall not exceed `COPY_WORKERS`. | Bounded, predictable pressure on the producer's files. |
| NI-11 | FastMirror shall never retry an open more aggressively than the configured backoff, and shall never retry a `VANISHED` file at all. | An uncontrolled retry storm is itself a form of interference (I/O contention). |
| NI-12 | Sweeps (§9.7) shall read directory listings and file attributes only. A sweep shall never open a file's contents except by submitting a normal copy job. | Keeps all content access on the single audited path of §9.5. |
| NI-13 | Every failure originating from the source — locked, vanished, access denied, I/O error — shall be absorbed, logged and counted by FastMirror. No failure shall ever be propagated, retried destructively, or escalated in a way that touches the producer. | The error direction of C-3 must be preserved. |

---

# 6. Architecture requirements

| ID | Rule |
|---|---|
| AR-1 | SOLID. Single Responsibility and Dependency Inversion in particular. |
| AR-2 | Interfaces live in their parent package; concrete classes live in a child `implementation/` package. |
| AR-3 | Concrete class names end with `Impl`. |
| AR-4 | Constructor dependency injection only. No field injection, no static singletons, no service locator. |
| AR-5 | All injected dependencies are stored in `private final` fields and null-checked with `Objects.requireNonNull`. |
| AR-6 | `Application.java` is the composition root and contains **no business logic** — only construction, wiring and startup ordering. |
| AR-7 | No unnecessary abstractions. The service interfaces listed in §6.1 are mandated by this specification and are not subject to this rule; no *further* interfaces are introduced without cause. |
| AR-8 | No third-party runtime dependencies. JDK only. JUnit 5 is permitted for tests; no other test library. |
| AR-9 | All time-dependent and sleep-dependent logic is injected (`Clock`, `Sleeper`) so tests are deterministic and do not rely on wall-clock sleeps. `System.nanoTime()` may be used directly for capture-latency **measurement**, which never drives control flow. |
| AR-10 | All source-file content access is confined to a single class, `AtomicFileCopierImpl`. No other class may open a source file. This makes §5.1 reviewable in one place and testable by AT-02. |

## 6.1 Package layout

```text
mirror/src/main/java/com/handsoffretail/fastmirror/
    Application.java                      composition root

    config/
        MirrorConfig.java                 immutable configuration
        EnvFileReader.java                .env parsing
        ConfigException.java              startup validation failure

    entity/
        FileEvent.java                    record(kind, path, eventNanos)
        FileEventKind.java                enum CREATED, MODIFIED, DELETED, OVERFLOW
        CopyOutcome.java                  enum, see 9.8
        CopyResult.java                   record(outcome, bytes, captureLatencyMs)
        CopyStats.java                    immutable counter snapshot

    service/
        SourceWatcherService.java
        MirrorService.java
        CopyQueue.java
        FileCopier.java
        PathMapper.java
        DifferenceDetector.java
        SweepService.java
        PathFilter.java
        Sleeper.java
        StatsReporter.java

    service/implementation/
        WatchServiceSourceWatcherImpl.java
        MirrorServiceImpl.java
        CopyQueueImpl.java
        AtomicFileCopierImpl.java          <- the only class that opens a source file (AR-10)
        RelativePathMapperImpl.java
        SizeAndTimeDifferenceDetectorImpl.java
        TreeSweepServiceImpl.java
        GlobPathFilterImpl.java
        ThreadSleeperImpl.java
        LoggingStatsReporterImpl.java

    logging/
        AppLogger.java
        LogLevel.java
```

## 6.2 Responsibility of each service

| Type | Responsibility |
|---|---|
| `SourceWatcherService` | Register the watch, translate `WatchEvent`s into `FileEvent`s, deliver them to `MirrorService`. Knows nothing about copying. Performs no file I/O. |
| `MirrorService` | Decide what to do with a `FileEvent`. Applies the path filter, resolves the target, submits to `CopyQueue`. Contains the event-handling policy. |
| `CopyQueue` | Deduplicate and schedule copy jobs across a worker pool. Owns the running/dirty state machine (§9.6). |
| `FileCopier` | Perform one copy: open with retry, transfer, set timestamp, publish atomically. Returns a `CopyResult`. The sole custodian of §5.1. |
| `PathMapper` | Map a source path to its target path and validate containment. Pure function, no I/O. |
| `DifferenceDetector` | Decide whether the target needs to be replaced. Reads attributes only. |
| `SweepService` | Walk the source tree and submit anything that differs. Used at startup, on overflow and on a timer. |
| `PathFilter` | Decide whether a path is excluded. Pure function. |
| `StatsReporter` | Emit periodic counter summaries. |

---

# 7. Configuration

Configuration is read at startup from a `.env` file located beside the executable, falling back to the parent directory, then to OS environment variables — the same resolution order the existing agent uses. Configuration is immutable after startup; there is no hot reload.

## 7.1 Keys

| Key | Required | Default | Valid range | Meaning |
|---|---|---|---|---|
| `SOURCE_FOLDER` | Yes | — | existing directory | Folder to watch, e.g. `C:/Gemcom/bin/out` |
| `MIRROR_FOLDER` | Yes | — | directory path | Folder to copy into, e.g. `C:/temp`. Created at startup if absent. |
| `COPY_WORKERS` | No | `4` | 1–32 | Copy worker threads. Also the hard cap on concurrently open source handles (NI-10). |
| `OPEN_RETRY_ATTEMPTS` | No | `5` | 0–20 | Extra attempts to open a locked source file |
| `OPEN_RETRY_INITIAL_MS` | No | `5` | 1–1000 | First retry delay; doubles each attempt |
| `SWEEP_INTERVAL_MS` | No | `500` | 0–600000 | Periodic full-tree sweep. `0` disables the sweep. |
| `CONTENT_VERIFY` | No | `false` | `true`/`false` | Compare SHA-256 as well as size and timestamp (§9.4) |
| `PUBLISH_PARTIAL` | No | `true` | `true`/`false` | Publish bytes captured before a transfer failure (§1.2, §9.5). `false` restores v1.0 discard behaviour. |
| `MIRROR_EMPTY_DIRECTORIES` | No | `false` | `true`/`false` | Create source directories in the mirror even when they hold no files |
| `MAX_COPY_BYTES` | No | `0` | 0–… | Skip files larger than this. `0` = no limit. |
| `EXCLUDE_PATTERNS` | No | `~$*,*.tmp,*.fmtmp` | comma-separated globs | Filename globs never copied |
| `STATS_INTERVAL_MS` | No | `60000` | 1000–3600000 | How often the counter summary is logged |
| `LOG_FOLDER` | No | `logs` beside the exe | directory path | Log destination |

## 7.2 Example `.env`

```properties
SOURCE_FOLDER=C:/Gemcom/bin/out
MIRROR_FOLDER=C:/temp

# Optional tuning. Leave a key out to keep the default shown here.
# COPY_WORKERS=4
# OPEN_RETRY_ATTEMPTS=5
# OPEN_RETRY_INITIAL_MS=5
# SWEEP_INTERVAL_MS=500
# CONTENT_VERIFY=false
# PUBLISH_PARTIAL=true
# MIRROR_EMPTY_DIRECTORIES=false
# MAX_COPY_BYTES=0
# EXCLUDE_PATTERNS=~$*,*.tmp,*.fmtmp
# STATS_INTERVAL_MS=60000
```

## 7.3 Startup validation

Each rule below must fail fast with a clear, actionable message and exit code `1`.

| ID | Rule |
|---|---|
| CV-1 | `SOURCE_FOLDER` and `MIRROR_FOLDER` are present and non-blank. |
| CV-2 | `SOURCE_FOLDER` exists and is a directory. |
| CV-3 | `MIRROR_FOLDER` exists, or can be created. |
| CV-4 | Both paths resolve via `toRealPath()` and are stored absolute and normalized. |
| CV-5 | **`MIRROR_FOLDER` is not inside `SOURCE_FOLDER`, and `SOURCE_FOLDER` is not inside `MIRROR_FOLDER`, and they are not equal.** Overlap would cause an infinite copy loop. |
| CV-6 | `SOURCE_FOLDER` is readable. Readability is verified by **listing the directory only** — never by opening, creating or deleting a file inside it (NI-7). `MIRROR_FOLDER` is verified writable by creating and deleting a probe file, which is permitted because the mirror is ours. |
| CV-7 | Every numeric key parses and falls within its stated range; every boolean key is exactly `true` or `false`. |

---

# 8. Functional requirements

| ID | Requirement | Priority |
|---|---|---|
| FR-01 | The agent shall watch `SOURCE_FOLDER` and all its subdirectories recursively using a single `FILE_TREE` watch registration. | Must |
| FR-02 | On `ENTRY_CREATE` for a regular file, the agent shall attempt to open and copy that file immediately, with no deliberate delay of any kind. | Must |
| FR-03 | On `ENTRY_MODIFY` for a regular file, the agent shall re-evaluate the file and re-copy it if it differs from the target (§9.4). | Must |
| FR-04 | On `ENTRY_DELETE`, the agent shall take **no action**. The target file is retained. | Must |
| FR-05 | The agent shall copy a file even when the producer is still writing to it. A partial copy is acceptable and expected; later events supersede it. | Must |
| FR-06 | The target path shall be `MIRROR_FOLDER` resolved against the source file's path relative to `SOURCE_FOLDER`, preserving the directory structure exactly. | Must |
| FR-07 | Missing parent directories under `MIRROR_FOLDER` shall be created before the copy. | Must |
| FR-08 | A copy shall be published into the mirror atomically: written to `<target>.fmtmp`, then moved into place with `ATOMIC_MOVE`. A consumer must never observe a target that FastMirror is part-way through writing. | Must |
| FR-09 | After a successful copy, the target's last-modified time shall be set to the source's last-modified time. | Must |
| FR-10 | If opening the source fails with a sharing or access error, the agent shall retry with exponential backoff per `OPEN_RETRY_ATTEMPTS` / `OPEN_RETRY_INITIAL_MS` before giving up. | Must |
| FR-11 | If the source file does not exist at open time, the agent shall record outcome `VANISHED`, log it with the capture latency, and continue. It shall not retry and shall not treat this as a fatal error. | Must |
| FR-12 | The agent shall not enqueue a duplicate copy job for a path that is already queued. If a path changes while its copy is running, exactly one further copy shall run afterwards (§9.6). | Must |
| FR-13 | At startup, the agent shall start the watcher **before** performing a full sweep, so that no event occurring during the sweep is missed. | Must |
| FR-14 | The agent shall perform a full sweep of the source tree every `SWEEP_INTERVAL_MS`, copying anything that differs, as a safety net for missed events. Setting the value to `0` disables it. | Must |
| FR-15 | On an `OVERFLOW` event, the agent shall trigger an immediate full sweep. | Must |
| FR-16 | The agent shall never delete, rename, move or modify any file inside `SOURCE_FOLDER`, nor change any of its attributes. | Must |
| FR-17 | The agent shall never delete any file inside `MIRROR_FOLDER` other than its own `.fmtmp` staging files. | Must |
| FR-18 | Filenames matching `EXCLUDE_PATTERNS` shall be ignored for all events and sweeps, and shall never be opened. | Must |
| FR-19 | Files larger than `MAX_COPY_BYTES` (when non-zero) shall be skipped and logged once per occurrence. | Should |
| FR-20 | The agent shall log every copy with its outcome, byte count and capture latency in milliseconds. | Must |
| FR-21 | The agent shall log a counter summary every `STATS_INTERVAL_MS`. | Should |
| FR-22 | On startup, the agent shall delete any stale `.fmtmp` files found under `MIRROR_FOLDER`. | Must |
| FR-23 | If the watch becomes invalid or the watch service fails, the agent shall log the failure and terminate with a non-zero exit code rather than continue silently blind. | Must |
| FR-24 | The agent shall run with no console window and shall write all output to a log file. | Must |
| FR-25 | If `MIRROR_EMPTY_DIRECTORIES` is `true`, source directories shall be created in the mirror even when they contain no files. | Could |
| **FR-26** | **The agent shall open source files only as specified in NI-1 and NI-2, and shall comply with every rule in §5.1.** | **Must** |
| **FR-27** | **When a transfer fails after at least one byte has been read and `PUBLISH_PARTIAL` is `true`, the agent shall publish the captured prefix to the target and record outcome `PARTIAL`.** | **Must** |
| **FR-28** | **When a transfer fails with zero bytes read, nothing shall be published; the outcome shall be `FAILED`, or `VANISHED` if the cause was the file's disappearance before the open.** | **Must** |
| **FR-29** | **No exception arising from a source file shall escape a copy job. Every copy job shall terminate in exactly one `CopyOutcome`.** | **Must** |
| **FR-30** | **The agent shall hold no more than `COPY_WORKERS` source handles concurrently and shall close each in a `finally` block or try-with-resources.** | **Must** |
| **FR-32** | **When started from the desktop, the agent shall show a system-tray icon and a Windows notification stating that it is running and naming the source and mirror folders (§9.11).** | **Should** |
| **FR-33** | **The tray icon's hover text and right-click menu shall show that the agent is running, the two folders, and the number of files copied since start, refreshed at least every 5 seconds.** | **Should** |
| **FR-34** | **The tray menu shall offer Exit, which asks for confirmation and then stops the agent through the normal shutdown sequence (§9.10) before ending the process.** | **Should** |
| **FR-35** | **Where no system tray is available — for example when running as a Windows service — the agent shall run normally without one and log that fact. The tray must never be a reason the agent fails to start.** | **Must** |
| **FR-31** | **When a path is created again after having been deleted, and the mirror still holds the earlier version, the agent shall log one `WARN` line naming that path. This measures filename reuse (U-1, R-10) and is not an error. A bare `ENTRY_CREATE` is explicitly *not* sufficient: Windows reports an ordinary new file as CREATE followed by MODIFY, and treating that as reuse produced false alarms on a running agent. Since operators are asked to act on these lines, a false alarm is worse than no detection.** | **Should** |

---

# 9. Detailed design

## 9.1 Thread model

| Thread | Count | Purpose |
|---|---|---|
| `fastmirror-watcher` | 1 | Blocks on `WatchService.take()`, translates events, puts them on an unbounded in-memory event queue. Performs no I/O beyond the watch itself. |
| `fastmirror-events` | 1 | Drains the event queue, applies filter rules, submits copy jobs. Must never block on a copy. |
| `fastmirror-copy-N` | `COPY_WORKERS` | Execute copy jobs. The only threads that ever touch source file contents. |
| `fastmirror-sweep` | 1 (daemon, scheduled) | Periodic full-tree sweep. |
| `fastmirror-stats` | 1 (daemon, scheduled) | Periodic counter logging. |

Separating the watcher thread from the event thread ensures the Windows notification buffer is drained as quickly as possible, which reduces `OVERFLOW`. Separating the event thread from the copy workers ensures a large or slow copy never delays detection of the next file.

The fixed worker count is also the non-interference budget: it bounds how many of the producer's files FastMirror can have open at once (NI-10).

All shared state must be thread-safe. The copy state map shall be a `ConcurrentHashMap` mutated only through atomic `compute`-style operations; counters shall be `LongAdder`.

## 9.2 Startup sequence

```text
1. Initialize file logging.
2. Install a default uncaught-exception handler.
3. Load and validate configuration (§7.3).
4. Delete stale *.fmtmp files under MIRROR_FOLDER.
5. Construct all services (composition root).
6. Start the copy worker pool.
7. Start the watcher and event threads.        <-- before the sweep (FR-13)
8. Run a full sweep of SOURCE_FOLDER.
9. Start the periodic sweep and stats schedulers.
10. Log "FastMirror started: <source> -> <mirror>".
11. Block the main thread until shutdown.
```

## 9.3 Event handling

```text
onEvent(kind, path, eventNanos):

    if kind == OVERFLOW:
        submit full sweep
        return

    if kind == DELETED:
        return                                  # FR-04, no action ever

    if pathFilter.isExcluded(path):
        return                                  # FR-18: never even opened

    if path is a directory:                     # attribute read only
        if MIRROR_EMPTY_DIRECTORIES: create the matching mirror directory
        return                                  # files inside arrive as their own events

    relative = pathMapper.relativize(path)
    copyQueue.submit(relative, eventNanos)
```

The event handler does **not** call `DifferenceDetector`. The difference check involves I/O on both the source and the target, and performing it on the single event thread would add latency to the one path that must stay fast. The check is performed by the copy worker instead (§9.6). The sweep, which is not latency-critical, does check before submitting.

The directory test uses `Files.readAttributes(..., NOFOLLOW_LINKS)`, an attribute read, never an open (NI-8, NI-12). If it throws because the entry has vanished, the event is dropped.

## 9.4 Difference rule

A target must be replaced when any of the following is true:

```text
needsCopy(source, target):

    if target does not exist:                       return true
    if size(source) != size(target):                return true
    if mtime(source) != mtime(target):              return true
    if CONTENT_VERIFY and sha256 differs:           return true
    return false
```

The timestamp comparison is meaningful because FR-09 makes the target's timestamp equal to the source's after every copy. A source that is modified again necessarily receives a new timestamp, or a new size, or both.

`CONTENT_VERIFY` exists for the pathological case of a rewrite that changes neither size nor timestamp. It reads and hashes both files on every check and is therefore off by default. When enabled, the source-side hash is obtained through `FileCopier`'s sanctioned open path (NI-1, AR-10), never by a separate reader.

If the source cannot be stat-ed because it has vanished, `needsCopy` returns `false` — there is nothing left to copy.

A target published as `PARTIAL` is by construction shorter than its source, so the size comparison causes the next event or sweep to re-copy it automatically. No special-casing is required for partials to converge.

## 9.5 Copy algorithm

This is the core of the agent and must be implemented exactly as described. Every line is either a performance requirement or a non-interference requirement.

```text
copy(relative, eventTimeNanos):

    source  = SOURCE_FOLDER.resolve(relative)
    target  = MIRROR_FOLDER.resolve(relative)
    staging = target + "." + nextAttemptNumber() + ".fmtmp"   # unique per attempt

    createDirectories(target.parent)          # cached; mirror side only

    # ---- the race is won or lost on this line ----
    channel = openWithRetry(source)           # READ only, NIO only (NI-1, NI-2)
        attempt 0:  FileChannel.open(source, READ)
        on NoSuchFileException           -> return VANISHED
        on AccessDenied/FileSystemException:
            if attempts remain: sleep(delay); delay *= 2; retry
            else                         -> return LOCKED
    captureLatencyMs = (nanoTime() - eventTimeNanos) / 1_000_000
    # ---- from here the copy is safe even if the source is deleted (C-2) ----
    # ---- and the producer can still write to and delete the file (C-8) ----

    try:
        attributes  = readAttributes(source)          # best effort, read-only
        sourceMtime = attributes.lastModifiedTime()   or  now() if unreadable

        size = channel.size()                          # from the open channel
        if MAX_COPY_BYTES > 0 and size > MAX_COPY_BYTES:
            return SKIPPED_TOO_LARGE                   # nothing staged yet

        targetExisted = exists(target)
        bytesCopied   = 0
        failure       = null

        open staging for WRITE/CREATE_NEW:
            buffer = ByteBuffer 64 KB                  # NI-4: no map, no transferTo
            loop:
                try:  n = channel.read(buffer)
                catch IOException e:  failure = e; break
                if n < 0: break
                write buffer fully to staging
                bytesCopied += n
        close staging

        if failure != null and bytesCopied == 0:
            return FAILED                              # FR-28; staging removed in finally
        if failure != null and not PUBLISH_PARTIAL:
            return FAILED

        setLastModifiedTime(staging, sourceMtime)

        publish(staging, target)                       # retries; see below

        if failure != null:  return PARTIAL             # FR-27
        return targetExisted ? REPLACED : COPIED

    catch any Throwable t:
        log ERROR; return FAILED                        # FR-29: nothing escapes

    finally:
        close(channel)                                  # NI-9
        deleteIfExists(staging)                         # no-op after a successful move
```

Mandatory details:

- **Open before anything else.** Attribute reads, size checks and directory creation must not delay the `open()` call any more than necessary. Directory creation is listed first only because the staging file cannot otherwise be created; implementations cache the set of already-created directories to avoid repeating the syscall.
- **Read `size()` from the open channel**, not from `Files.size(source)`, so the value describes the snapshot being copied. It is used only for the `MAX_COPY_BYTES` test; the transfer loop reads until end-of-stream, so a file that grows during the copy is still captured as far as it goes.
- **The transfer loop is explicit.** A bounded 64 KB `ByteBuffer` is read from the source channel and written to the staging channel. `transferTo`, `transferFrom`, `map` and `Files.copy` are prohibited by NI-3 and NI-4. On Windows `transferTo` memory-maps the source in chunks, and `Files.copy` uses a share mode that is not ours to choose. Neither was measured to break the producer on the current platform (C-9), but both introduce a version-dependent risk to the prime directive in exchange for no benefit, so neither is used.
- **A read failure does not lose the prefix.** The loop breaks, the staging file is closed, and if any bytes were captured they are published (FR-27). This is §1.2.
- **The staging file must be in the same directory as the target**, so the move is a same-volume directory-entry swap and therefore atomic.
- **The staging file name must be unique per attempt.** A single shared `<target>.fmtmp` name is correct only while no two attempts for one path ever overlap. A running agent showed that assumption failing — a staging file disappeared between being written and being timestamped — so each attempt now owns a name no other attempt can produce. The `.fmtmp` suffix is kept so FR-18 and FR-22 still match.
- **Publishing retries.** `move(staging, target)` fails with `AccessDeniedException` while another process holds the target open, and in production the HOR AWS Sync Agent is doing exactly that: reading files out of the mirror to upload them. A scanner or indexer can do the same. The publish therefore retries up to 10 times with a doubling backoff capped at 250 ms, falling back to `REPLACE_EXISTING` if `ATOMIC_MOVE` is unsupported, before giving up with `FAILED`. Retrying here is safe in a way retrying a source open is not: the target belongs to FastMirror, so no retry pressure reaches the producer (NI-11 constrains the source side only).
- **The staging file must be deleted on every failure path.** A `finally` block is required.
- **Capture latency** = `(time of successful open − eventTimeNanos)` in milliseconds, recorded for logging and statistics.
- **Nothing escapes.** The outer `catch Throwable` guarantees FR-29: a copy job always ends in exactly one outcome and never kills a worker thread.

## 9.6 Copy queue and deduplication

The queue prevents duplicate and overlapping work for the same path. It uses the same three-state model as the existing agent's `FileSyncQueueImpl`, which is proven in production:

```text
state(relative) ∈ { QUEUED, RUNNING, RUNNING_DIRTY }

submit(relative, eventNanos):
    QUEUED        if absent  -> dispatch to a worker
    QUEUED        if QUEUED  -> no action (keep the EARLIEST pending eventNanos)
    RUNNING_DIRTY if RUNNING -> remember that another copy is needed, with this eventNanos

worker(relative):
    mark RUNNING (unless already RUNNING_DIRTY)
    if not needsCopy(source, target):  outcome = UNCHANGED
    else:                              outcome = copy(...)
    record outcome in counters
    if state == RUNNING_DIRTY:  set QUEUED and dispatch again
    else:                       remove the entry
```

The queue entry carries the earliest pending `eventNanos`, so capture latency is measured from the first event that asked for the copy rather than the last.

Consequences, all of which are required behaviour:

- A burst of `MODIFY` events for one file collapses into at most one running copy plus one follow-up copy.
- The follow-up copy always observes the newest content, so the mirror converges on the final state of the source.
- The map cannot grow without bound, because entries are removed once no further work is pending.
- Because at most one job per path is ever running, and at most `COPY_WORKERS` jobs run at once, NI-10 holds by construction.

## 9.7 Sweep

```text
sweep():
    walk SOURCE_FOLDER depth-first, tolerating per-entry I/O errors
    for each regular file:
        if pathFilter.isExcluded: continue
        relative = relativize(file)
        if needsCopy(source, target): copyQueue.submit(relative, nanoTime())
    log "Sweep complete: N file(s) submitted" only when N > 0
```

The sweep reads directory entries and attributes only; it never opens file contents (NI-12). It must not abort on an unreadable file or directory; it logs and continues. It runs at startup (FR-13), on `OVERFLOW` (FR-15) and on the `SWEEP_INTERVAL_MS` timer (FR-14).

The sweep is a **safety net, not the primary mechanism**. With source files living about one second, a 500 ms sweep catches only a fraction of them on its own. Event-driven copying is what makes the agent work; the sweep exists to cover dropped notifications and the startup window.

## 9.8 Copy outcomes

```java
enum CopyOutcome {
    COPIED,             // target did not exist; now created, fully captured
    REPLACED,           // target existed and differed; now updated, fully captured
    PARTIAL,            // transfer failed part-way; the captured prefix was published
    UNCHANGED,          // target already matched; nothing written
    VANISHED,           // source was gone at open time - nothing captured, unrecoverable
    LOCKED,             // source still locked after all retries
    SKIPPED_TOO_LARGE,  // exceeded MAX_COPY_BYTES
    FAILED              // I/O error with nothing captured, or publish failure
}
```

Every outcome increments a counter. The periodic summary (FR-21) reports all non-zero counters, for example:

```text
Stats (last 60s): COPIED=412 REPLACED=38 PARTIAL=1 UNCHANGED=7 VANISHED=2 LOCKED=0 FAILED=0
                  capture latency avg=2.6ms max=11ms
```

Two counters carry operational meaning:

- **`VANISHED`** is the count of files genuinely lost to the race in §12 — nothing at all was captured.
- **`PARTIAL`** is the count of files captured incompletely. Under C-2 this should be near zero; a persistently non-zero value means reads really are failing mid-transfer and deserves investigation.

## 9.9 Logging

- One rolling log file per day under `LOG_FOLDER`; retain 30 days.
- Lines are `yyyy-MM-dd HH:mm:ss [LEVEL] message`, matching the existing agent so operators read both the same way.
- `INFO` per copy: `Copied: <relative> (<bytes> bytes, captured in <n>ms)`.
- `WARN` for `PARTIAL`: `Partial capture published: <relative> (<bytes> of <expected> bytes, captured in <n>ms) - <cause>`.
- `WARN` for `LOCKED` and `SKIPPED_TOO_LARGE`.
- `WARN` for `VANISHED`: `Source vanished before capture (<n>ms): <relative>`.
- `ERROR` for `FAILED` and for watch failures, including the exception.
- The log must never print file *contents*.
- Logging is buffered or asynchronous such that a slow or full log volume never stalls a copy worker, and therefore never extends the time a source handle is held (NI-9).

## 9.10 Shutdown

On `Ctrl+C`, service stop or tray exit: stop accepting new events, close the watch service, shut down the schedulers, allow in-flight copies up to 5 seconds to finish, close every source handle, delete any remaining `.fmtmp` files, flush the log, exit `0`.

Source handles are closed as part of normal job completion; the 5-second grace exists so that no handle is abandoned in a way that could linger against a producer file.

---

## 9.11 System tray

When started from the desktop, the agent shows a notification-area icon (a green disc with a white arrow):

| Where | What it shows |
|---|---|
| Notification at startup | **FastMirror is running** — Copying files from `<source>` to `<mirror>` |
| Hover | `FastMirror: running`, both folders, files copied so far |
| Right-click menu | `Running - N files copied` (plus `N failed (see log)` if any), `From:`, `To:`, **Exit FastMirror** |
| Double-click | Shows the startup notification again — a quick "is it still running?" check |

**Exit asks for confirmation.** Stopping the agent means Gemcom's files stop being captured, and since they live about a second, anything produced while it is stopped is lost for good. A stray click must not be able to do that. Once confirmed, Exit runs the normal shutdown sequence (§9.10) on its own thread — so in-flight copies finish — then ends the process and removes the icon.

The tray is a window onto the agent and nothing more: it never touches a source or mirror file, reads the counters without locking, and runs on its own threads, so it cannot slow the copy path. It is created only in `main`, never in `Application.start`, so tests and headless runs never create one. As a Windows service there is no desktop and therefore no tray; that is expected (FR-35).

Using the tray requires the `java.desktop` module in the bundled runtime (§14.1).

---

# 10. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-01 | Median capture latency, measured from event receipt to successful source open, shall be under 10 ms on the target hardware. |
| NFR-02 | The agent shall sustain at least 50 file creations per second without `OVERFLOW`. |
| NFR-03 | Idle CPU usage shall be under 1% with `SWEEP_INTERVAL_MS=500` on a source tree of up to 10,000 files. |
| NFR-04 | Heap usage shall remain under 256 MB. Copies shall stream through a bounded 64 KB buffer and must never load a whole file into memory or memory-map it. |
| NFR-05 | The agent shall run continuously for at least 30 days without restart, leaking no memory, file handles or threads. |
| NFR-06 | Every file handle shall be opened in try-with-resources or closed in a `finally` block. Handle count must be stable under sustained load and must never exceed `COPY_WORKERS` for source files. |
| NFR-07 | No unhandled exception on any thread may terminate the agent silently; the default uncaught-exception handler logs with a full stack trace. |
| NFR-08 | The agent shall be deployable as a folder containing `FastMirror.exe`, a `.env` file and a bundled `jre\` directory, runnable by double-clicking the exe on a machine with **no JRE installed and no installation step**. |
| NFR-09 | No credentials or secrets of any kind are required or stored by this agent. |
| **NFR-10** | **Under any load and any failure condition, the producer shall observe zero errors, zero sharing violations and no measurable slowdown attributable to FastMirror. Verified by IT-18, IT-19 and IT-20. A failure here blocks release.** |

---

# 11. Failure behaviour matrix

The implementer must make each of these behave exactly as stated.

| Scenario | Required behaviour |
|---|---|
| Source vanishes before the first `open()` | `VANISHED`. Log with latency. Counter incremented. No retry. No crash. Nothing published. |
| Source vanishes **during** the transfer | The open handle keeps serving data (C-2); the copy normally completes. If the OS does raise an error, the bytes captured so far are published as `PARTIAL` (FR-27). The producer's delete succeeds either way (C-8). |
| Source is locked | Retry with backoff. If still locked, `LOCKED`, log, counter. The next sweep will retry it if the file still exists. The error never reaches the producer (C-3). |
| Source is still being written | Copy the bytes available now and publish them. Subsequent `MODIFY` events re-copy. The mirror converges. Intended behaviour, not an error. |
| Source is renamed | The old name is untouched in the mirror; the new name arrives as a `CREATE` and is copied. The mirror ends up holding both. Accepted (§2.2). |
| Source is atomically replaced | An open handle continues to read the pre-swap content, which is coherent. The replacement raises its own event and is copied afterwards. |
| **Producer deletes a file FastMirror has open** | **The delete succeeds immediately with no error**, because the handle grants `FILE_SHARE_DELETE` (C-8). FastMirror finishes reading from the delete-pending file. Verified by IT-18. |
| **Producer writes to a file FastMirror has open** | **The write succeeds**, because the handle grants `FILE_SHARE_WRITE`. Verified by IT-19. |
| **Producer renames a file FastMirror has open** | **The rename succeeds.** Verified by IT-20. |
| Many `MODIFY` events in a burst | Collapsed by the queue into at most one running plus one pending copy (§9.6). |
| Same filename created again later | Treated as a normal change; the target is replaced. The previous content is not preserved — the mirror keeps only the latest version of each path. |
| Target already correct | `UNCHANGED`. No write, no timestamp change, so downstream consumers see no event. |
| Partial target, source still present | The size mismatch (§9.4) causes the next event or sweep to re-copy it in full. Converges. |
| Partial target, source already gone | The partial content is all that exists anywhere. It is retained. See R-7. |
| A consumer holds the mirrored target open | The publish retries with backoff for up to about a second. This is expected, not exceptional: the sync agent reads the mirror in order to upload it. If the hold outlasts the retries, `FAILED` and the sweep tries again later. |
| Mirror volume full | `FAILED`, staging file deleted, error logged with the path. The agent keeps running and keeps retrying via the sweep. The producer is unaffected. |
| Mirror folder deleted while running | Recreated on the next copy. If recreation fails, `FAILED` and log. |
| Source folder deleted while running | The watch becomes invalid; per FR-23 log and exit non-zero so a service manager can restart the agent. |
| `OVERFLOW` | Immediate full sweep (FR-15). |
| Agent crashes mid-copy | Only a `.fmtmp` file is left behind; the target is untouched and still holds its last complete version. All source handles are released by the OS on process exit, so the producer is unaffected. The startup sweep deletes the staging file and re-copies if the source still differs. |
| Agent restarts | No persisted state is needed. The startup sweep compares source against mirror on disk and copies the differences. |
| Both agents running (FastMirror + sync agent) | Independent. Atomic publish (FR-08) guarantees the sync agent never sees a target FastMirror is mid-way through writing. |

---

# 12. Known and accepted limitation

**This must be stated in the project README and understood by the operator.**

FastMirror learns that a file exists only *after* it has been created. Between the producer's write and FastMirror's first `open()` there is an irreducible window consisting of Windows notification delivery plus event dispatch — typically **1 to 10 milliseconds**.

If the producer deletes a file inside that window, the bytes are unrecoverable. No design in Java, or in any other language on Windows, can retrieve a file that no longer exists and was never opened.

Closing this window further is possible only through techniques that violate the prime directive — holding handles open speculatively, installing a filesystem filter driver, or denying the producer delete access. **None of those are acceptable.** The window stays.

What this specification achieves is reducing the exposure window from the ~10 seconds a stability-checking consumer would require down to single-digit milliseconds — roughly a thousandfold improvement — and, once the handle is open, making the copy immune to deletion entirely (C-2) while leaving the producer completely undisturbed (C-8).

The `VANISHED` counter (§9.8) exists specifically so this limitation can be **measured in production rather than assumed**. If it stays at zero over a representative period, the race never materialises on this hardware. If it does not, that number is the evidence needed to decide whether further work is justified.

---

# 13. Acceptance tests

Tests use JUnit 5 and a JUnit `@TempDir`. Timing-sensitive behaviour must be driven through the injected `Clock` and `Sleeper` (AR-9); tests must not depend on real wall-clock sleeps for their assertions. No third-party test library is permitted (AR-8).

## 13.1 Unit tests

| ID | Test | Expected |
|---|---|---|
| UT-01 | `PathMapper` maps `<src>\a\b\c.xml` | `<mirror>\a\b\c.xml` |
| UT-02 | `PathMapper` rejects a path outside the source root | throws |
| UT-03 | `DifferenceDetector`: target missing | `true` |
| UT-04 | `DifferenceDetector`: same size, same mtime | `false` |
| UT-05 | `DifferenceDetector`: same size, different mtime | `true` |
| UT-06 | `DifferenceDetector`: different size | `true` |
| UT-07 | `DifferenceDetector` with `CONTENT_VERIFY`: same size and mtime, different bytes | `true` |
| UT-08 | `PathFilter` excludes `~$doc.xml`, `x.tmp`, `y.fmtmp`; allows `PJR9.xml` | as stated |
| UT-09 | Config rejects a mirror folder nested inside the source folder | throws with an overlap message |
| UT-10 | Config rejects `SWEEP_INTERVAL_MS=-1` | throws with the valid range |
| UT-11 | Config applies every documented default when keys are absent | matches §7.1 |
| UT-12 | `CopyQueue`: submit twice while idle | one job runs |
| UT-13 | `CopyQueue`: submit while running | exactly one follow-up job runs |
| UT-14 | `CopyQueue`: 100 concurrent submits for one path | no lost update; the map is empty at quiescence |
| UT-15 | `FileCopier` sets the target mtime equal to the source mtime | equal |
| UT-16 | `FileCopier` leaves no `.fmtmp` behind on success or on failure | none present |
| **UT-17** | `FileCopier` reading a source channel that throws after N bytes, `PUBLISH_PARTIAL=true` | target contains exactly the first N bytes; outcome `PARTIAL` |
| **UT-18** | Same, but zero bytes readable | nothing published; outcome `FAILED`; no `.fmtmp` left |

## 13.2 Integration tests

| ID | Scenario | Expected |
|---|---|---|
| IT-01 | **Normal file.** Create a 4 KB file and leave it. | Copied byte-identically, structure preserved, `COPIED`. |
| IT-02 | **Created then modified repeatedly.** Create, then append 5 times with pauses. | Mirror finally byte-matches the source; at most 6 copies; no `FAILED`. |
| IT-03 | **Disappears after ~1 second.** Create, wait 1000 ms, delete. | File present and complete in the mirror; still present after the delete; no `VANISHED`. |
| IT-04 | **Disappears very quickly.** Create and delete with no pause, repeated 100 times. | No crash, no `FAILED`, no leaked handles. Every outcome is `COPIED`, `PARTIAL` or `VANISHED`. Report the ratio — this is the race measurement. |
| IT-05 | **Still being written at CREATE.** Open, write 1 MB slowly in chunks, close. | Mirror ends byte-identical to the source after the final event. Intermediate partial copies are acceptable. |
| IT-06 | **Temporarily locked.** Hold the file with no share flags for 50 ms, then release. | Copy succeeds via retry; outcome `COPIED`, not `LOCKED`. |
| IT-07 | **Locked beyond the retry budget.** Hold for 5 seconds. | Outcome `LOCKED`, logged; a later sweep copies it successfully. |
| IT-08 | **Renamed immediately after creation.** Create `a.xml`, rename to `b.xml`. | Mirror contains both `a.xml` and `b.xml`; neither is deleted. |
| IT-09 | **Nested structure.** Create `s1\s2\r.xml`. | `<mirror>\s1\s2\r.xml` exists with identical bytes. |
| IT-10 | **Source deleted, mirror retained.** Create, allow the copy, delete the source. | Target still present and unchanged. No delete is ever issued against the mirror. |
| IT-11 | **Restart recovery.** Populate the source, stop the agent, add and change files, restart. | The startup sweep brings the mirror fully into line. |
| IT-12 | **Stale staging cleanup.** Place a `x.xml.fmtmp` in the mirror, start the agent. | The staging file is deleted at startup. |
| IT-13 | **Atomic visibility.** Copy a 50 MB file while a reader polls the target every millisecond. | The reader never observes a size other than 0 (absent) or the full size. |
| IT-14 | **Overflow.** Create 5,000 files as fast as possible. | Every file reaches the mirror, whether by event or by the overflow sweep. |
| IT-15 | **Burst throughput.** 50 files per second for 60 seconds. | All mirrored; capture latency median under 10 ms (NFR-01). |
| IT-16 | **Long run.** Periodic activity over an extended period. | Stable handle count, thread count and heap (NFR-05, NFR-06). |
| IT-17 | **Interoperation.** Point the existing HOR AWS Sync Agent's `WATCH_FOLDER` at `MIRROR_FOLDER` and run both. | Every file reaches S3; the sync agent's log contains no `Deleted path has no file snapshot` and no partial-file errors. Manual, on the target PC. |
| **IT-18** | **Producer delete is never blocked.** A simulated producer creates a file; the test waits until FastMirror has it open; the producer then deletes it and records the result. Repeated 200 times. | **Every delete returns success with no exception.** Zero sharing violations, zero `ERROR_USER_MAPPED_FILE`, zero `AccessDeniedException` in the producer. The mirror holds the bytes. This test is the executable form of PD-1 and C-8. |
| **IT-19** | **Producer write is never blocked.** A simulated producer holds a file open and appends every 5 ms for 10 seconds while FastMirror repeatedly copies it. | Every producer write succeeds. No exception in the producer thread. Write latency shows no outliers attributable to FastMirror. |
| **IT-20** | **Producer rename is never blocked.** The producer creates and immediately renames files, 200 times, while FastMirror copies them. | Every rename succeeds. No sharing violation. |
| **IT-21** | **Partial publication end to end.** A source whose read fails after N bytes. | The target exists and contains exactly the first N bytes; outcome `PARTIAL`; a later sweep with a healthy source restores the full file. |
| **IT-18c** | **External process delete.** A separate Windows process (`cmd /c del`) deletes a file FastMirror holds open. | Exit code 0. This removes the doubt that the JDK's own delete might take a code path the producer does not. |
| **IT-19c** | **External process write.** `cmd /c echo >>` appends to a file FastMirror holds open. | Exit code 0. |
| **IT-20c** | **External process rename.** `cmd /c ren` renames a file FastMirror holds open. | Exit code 0. |
| **PR-01** | **Platform record.** An external delete against a memory-mapped file. | No assertion. The result is printed and recorded so C-9 is re-measured on every build instead of assumed. |

## 13.3 Architecture and rule tests

| ID | Test | Expected |
|---|---|---|
| AT-01 | Every `*Impl` class resides in an `implementation` package, and every concrete service class is named `*Impl` (AR-2, AR-3). | passes |
| AT-02 | **No class other than `AtomicFileCopierImpl` references `FileChannel`, `Files.newByteChannel`, `FileInputStream`, `RandomAccessFile` or `Files.copy` (AR-10, NI-1, NI-3, NI-6).** | passes |
| AT-03 | **No class anywhere references `FileChannel.map`, `MappedByteBuffer`, `transferTo`, `transferFrom`, `FileChannel.lock` or `tryLock` (NI-4, NI-5).** | passes |
| AT-04 | `Application` declares no method other than `main` and private wiring helpers (AR-6). | passes |

`AT-02` and `AT-03` are the automated enforcement of §5.1. They are hand-written constant-pool scans over `build/classes`, using only the JDK (AR-8).

---

# 14. Deployment

## 14.1 Build

FastMirror is a standalone Gradle project. The `mirror/` subdirectory is self-contained so that it can later be dropped into the `hor_aws_sync` repository as a second subproject with a single `include("mirror")` line, should that be wanted.

```kotlin
// settings.gradle.kts
rootProject.name = "fastmirror"
include("mirror")
```

`mirror/build.gradle.kts` applies the `application` plugin, sets the Java 21 toolchain, declares **no** runtime dependencies, sets `mainClass` to `com.handsoffretail.fastmirror.Application`, and registers a `fatJar` task. A Launch4j configuration wraps the fat jar as `FastMirror.exe` in GUI mode (no console window, FR-24), configured to use the `jre\` directory beside the exe as its runtime.

The bundled runtime is produced with `jlink` and must contain exactly the modules `jdeps` reports for the jar: **`java.base,java.desktop,jdk.unsupported`**. `jdk.unsupported` provides the recursive directory watch (C-5) and `java.desktop` the system tray (§9.11); without either, the agent fails at startup.

## 14.2 Installation on the store PC

```text
C:\HOR\FastMirror\
    FastMirror.exe          Launch4j wrapper, GUI mode, no console
    .env                    configuration
    jre\                    bundled Java 21 runtime; no installation required
    logs\                   created on first run
```

Double-clicking `FastMirror.exe` starts the agent with no console window. For unattended operation, register it as a Windows service or a Task Scheduler task set to run at startup, restart on failure, and run whether or not a user is logged on.

## 14.3 Operator responsibilities

- **Mirror folder growth.** FastMirror never deletes from the mirror (FR-17). The operator must apply a retention policy — a scheduled task deleting files older than N days is sufficient. If the mirror is also the sync agent's watch folder, be aware that such deletions produce `Local delete observed — S3 object retained` lines in the sync agent's log; the S3 objects themselves are never removed.
- **Monitoring.** Watch the `VANISHED`, `PARTIAL` and `FAILED` counters in the periodic summary. A persistently non-zero `VANISHED` is the signal described in §12; a non-zero `PARTIAL` means reads are failing mid-transfer and should be investigated.
- **Filename reuse.** Watch for the FR-31 `WARN` lines. Their presence means the producer reuses filenames with different content, which the current design does not preserve (R-10, U-1). Report them rather than ignoring them.
- **Disk space.** Size the mirror volume for the retention period times the daily file volume.

---

# 15. Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-1 | A file is deleted inside the 1–10 ms capture window | Low | File lost permanently | Unavoidable (§12). Measured via `VANISHED`. |
| R-2 | Mirror folder fills the disk | Medium | Copies fail | Operator retention policy (§14.3); `FAILED` is logged and alertable. |
| R-3 | Partial copies published when the producer writes slowly | Medium | A downstream consumer briefly sees incomplete XML | Superseded by later copies; the mirror converges. Raise `CONTENT_VERIFY` or add a producer-side write-then-rename convention if it proves a problem. |
| R-4 | Notification overflow under an extreme burst | Low | Delayed copies | Dedicated watcher thread plus overflow-triggered sweep. |
| R-5 | Source and mirror configured to overlap | Low | Infinite copy loop | Rejected at startup by CV-5. |
| R-6 | Clock skew between source and mirror volumes | Very low | Spurious re-copies | Harmless; a re-copy is idempotent. |
| **R-7** | A `PARTIAL` overwrites a previously complete target and the source is then deleted, leaving only the truncated version | Very low | Loss of the complete copy of one file | Requires a genuine mid-transfer I/O error, which C-2 makes near-impossible. Accepted deliberately: the alternative (discarding) loses the data in every case rather than one rare case. `PUBLISH_PARTIAL=false` reverts to the stricter behaviour. Counted and logged at `WARN`. |
| **R-8** | The watch handle prevents another process from renaming or deleting `SOURCE_FOLDER` itself | Low | An administrator cannot rename the folder while the agent runs | Documented (C-11). The producer does not do this. Stop the agent first. |
| **R-9** | A future JDK or Windows update changes the share mode or delete semantics and starts blocking the producer | Very low | Prime-directive violation | IT-18, IT-19, IT-20 and their external-process variants IT-18c/19c/20c run on every build and fail loudly. Do not upgrade the bundled JRE, or roll out to a materially different Windows build, without a green run. |
| **R-10** | The producer reuses filenames with different content, so a mirrored file is overwritten before the sync agent has uploaded it | **Unknown — see U-1** | Silent loss of a complete file | FR-31 logs every such replacement so the behaviour is measured on the target PC. If it occurs, the mitigation is versioned mirror filenames (`<name>.<timestamp>.<ext>`), which is a change to §9.5 and to the sync agent's expectations and must be agreed before being built. |

## 15.1 Open field unknowns

These are properties of the target environment that are not yet known. Each has a safe default already specified; each is listed so it is revisited with real data rather than forgotten.

| ID | Unknown | Current assumption | How it gets resolved |
|---|---|---|---|
| U-1 | Does the producer reuse filenames with differing content? | Assumed unique. §11 accepts that the mirror keeps only the latest version of each path. | FR-31 logs each path that is deleted and then created again while the mirror still holds the earlier version. Verified on a running agent to produce no false alarms across 100 ordinary new files, and to fire exactly once for a genuine delete-then-recreate. A 24-hour soak (DoD 6) answers the question. If reuse is real, R-10's mitigation is designed then. |
| U-2 | Does the source tree contain subdirectories? | Assumed it may. Recursive watch and structure preservation are built regardless (FR-01, FR-06); the cost of supporting the flat case is zero. | Observed on the target PC. No action either way. |
| U-3 | Is the HOR AWS Sync Agent running and watching `MIRROR_FOLDER`? | Not yet. `MIRROR_FOLDER` defaults to `C:/temp` per §7.2. | Confirmed by the operator before deployment. IT-17 is marked unverified until then; nothing in the build depends on it. |

---

# 16. Traceability

| Requirement | Design | Test |
|---|---|---|
| PD-1, FR-26, NFR-10 | §1.1, §5.1, §9.5, AR-10 | IT-18, IT-19, IT-20, IT-18c, IT-19c, IT-20c, AT-02, AT-03 |
| PD-2, FR-27, FR-28 | §1.2, §9.5 | UT-17, UT-18, IT-21 |
| FR-01, FR-02 | §9.1, §9.3 | IT-01, IT-15 |
| FR-03, FR-12 | §9.6 | IT-02, UT-12–UT-14 |
| FR-04, FR-16, FR-17 | §9.3, NI-7 | IT-08, IT-10 |
| FR-05 | §9.5 | IT-05 |
| FR-06, FR-07 | §9.5, `PathMapper` | IT-09, UT-01, UT-02 |
| FR-08 | §9.5 | IT-13, UT-16 |
| FR-09 | §9.5 | UT-15 |
| FR-10, FR-11 | §9.5 `openWithRetry` | IT-06, IT-07, IT-04 |
| FR-13, FR-14, FR-15 | §9.2, §9.7 | IT-11, IT-14 |
| FR-22 | §9.2 | IT-12 |
| FR-29, FR-30 | §9.5 `catch Throwable`, `finally` | IT-04, IT-16 |
| CV-5 | §7.3 | UT-09 |
| AR-2, AR-3, AR-6, AR-10 | §6 | AT-01, AT-04 |
| NFR-01 | §9.1, §9.5 | IT-15 |
| NFR-05, NFR-06 | §9.5 `finally`, NI-9, NI-10 | IT-16 |
| §12 limitation | §9.8 counters | IT-04 |

---

# 17. Definition of done

1. All `Must` requirements in §8 implemented.
2. All unit tests (§13.1), integration tests (§13.2) and architecture tests (§13.3) passing.
3. **IT-18, IT-19, IT-20 and the external-process variants IT-18c, IT-19c and IT-20c green.** The prime directive is verified, not assumed. A red run here blocks release regardless of everything else.
4. `Application.java` contains wiring only; a reviewer can confirm no business logic at a glance.
5. `FastMirror.exe` plus `jre\` and `.env` runs by double-click on a clean Windows 11 machine with no JRE installed and no console window.
6. A 24-hour soak run against the live Gemcom folder on the target PC, with the `VANISHED`, `PARTIAL` and `FAILED` counts recorded in the project's progress document, and confirmation from the operator that Gemcom logged no new errors during it.
7. IT-17 verified end to end with the existing sync agent, with no change of any kind made to that agent's source.
