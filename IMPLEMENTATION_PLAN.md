# FastMirror — Implementation Plan

**Against:** `SRS_FastMirror.md` v2.0
**Date:** 2026-09-20
**Status:** implemented; phases 0-11 complete
**Toolchain on this machine:** Temurin JDK 21.0.10, Gradle 9.7.0 — both verified present

## Locked decisions

| # | Decision |
|---|---|
| 1 | Standalone Gradle project rooted at `C:\Users\abhia\OneDrive\Fast_Mirror`, with a self-contained `mirror/` subproject. |
| 2 | `java.time.Clock` injected for wall-clock time; `System.nanoTime()` used directly for capture-latency measurement only (AR-9). |
| 3 | Packaging is Launch4j (GUI mode, no console) + a `jre\` folder shipped beside `FastMirror.exe`. Double-click to run, matching the existing AWS agent. |
| 4 | §6.1 is followed literally — every listed service gets its interface. |
| 5 | `git init`, one commit per phase, reviewable incrementally. |
| 6 | `PUBLISH_PARTIAL` defaults to `true`: captured bytes are published, never discarded (PD-2). |
| 7 | **Producer non-interference is the prime directive** and outranks everything else, including capturing any individual file. |

---

## 1. Guiding constraints

Three rules shape every file in this plan.

**The producer must never see an error (PD-1, §5.1).** All source access is funnelled through one class, `AtomicFileCopierImpl`, which opens read-only through NIO and nothing else. `Files.copy`, `transferTo`, `map` and `FileChannel.lock` are banned by name because each of them can make the producer's own delete or write fail on Windows. Two hand-written architecture tests scan the compiled bytecode and fail the build if any other class ever touches those APIs. This is not documentation — it is enforced.

**The `open()` call is the whole product.** Everything before it is latency that costs files; everything after it is safe because a Windows handle survives deletion. So the watcher thread does nothing but drain the notification buffer, the event thread does no I/O, and the difference check happens on the worker, not on the hot path.

**Never discard captured bytes.** If a read fails part-way, the prefix is published and counted as `PARTIAL`.

---

## 2. Target layout

```
Fast_Mirror/
    SRS_FastMirror.md
    IMPLEMENTATION_PLAN.md
    README.md                          states the §12 limitation and the prime directive
    .gitignore
    .env                               local dev config (gitignored)
    gradlew.bat  gradlew  gradle/wrapper/
    settings.gradle.kts                rootProject.name = "fastmirror"; include("mirror")
    build.gradle.kts                   empty root
    mirror/
        build.gradle.kts
        src/main/java/com/handsoffretail/fastmirror/...       (§6.1 exactly)
        src/test/java/com/handsoffretail/fastmirror/...
    packaging/
        launch4j.xml                   Launch4j config
        env.template                   shipped .env template
```

`mirror/` stays self-contained so it can later be dropped into `hor_aws_sync` with one `include` line.

---

## 3. Phases

Bottom-up: pure logic first, I/O next, threads last, composition root last of all. Every phase ends compiling, with its tests green, and one git commit.

### Phase 0 — scaffolding
`git init` · `gradle init` (java-application, Kotlin DSL, JUnit Jupiter, Java 21) · rename the generated module to `mirror` · delete the sample classes · write `.gitignore` and the root `settings.gradle.kts`.

`mirror/build.gradle.kts`:
- `application` plugin, Java 21 toolchain
- **zero** runtime dependencies (AR-8); `testImplementation` JUnit 5 only
- `mainClass = "com.handsoffretail.fastmirror.Application"`
- `tasks.test { useJUnitPlatform() }` with a `slow` tag excluded from the default run (IT-13/14/15/16 are heavy)
- `fatJar` task: `Jar` with the `Main-Class` manifest attribute

**Verification before anything is built on it:** a throwaway class that references `com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE` and compiles. `jdk.unsupported` should export it with no `--add-exports`; if I'm wrong, the flag goes into the build file now rather than being discovered in Phase F.

**Exit:** `gradlew.bat build` green. → commit `phase-0: gradle scaffolding`

### Phase 1 — entities and pure logic
| File | Notes |
|---|---|
| `entity/FileEventKind.java` | CREATED, MODIFIED, DELETED, OVERFLOW |
| `entity/FileEvent.java` | `record(kind, path, eventNanos)` |
| `entity/CopyOutcome.java` | the 8 values of §9.8, including `PARTIAL` |
| `entity/CopyResult.java` | `record(outcome, bytesCopied, captureLatencyMs)` |
| `entity/CopyStats.java` | immutable snapshot: per-outcome counts, latency avg/max |
| `service/PathMapper.java` + `implementation/RelativePathMapperImpl.java` | pure; `relativize`, `toTarget`, containment check |
| `service/PathFilter.java` + `implementation/GlobPathFilterImpl.java` | `PathMatcher` globs on the filename only |
| `service/Sleeper.java` + `implementation/ThreadSleeperImpl.java` | AR-9 |

**Tests:** UT-01, UT-02, UT-08. → commit `phase-1: entities and pure services`

### Phase 2 — configuration
| File | Notes |
|---|---|
| `config/ConfigException.java` | actionable message, drives exit code 1 |
| `config/EnvFileReader.java` | `.env` beside the exe → parent dir → OS env; `#` comments, `KEY=VALUE`, trimmed, blanks ignored |
| `config/MirrorConfig.java` | immutable record + `load(...)` factory running CV-1…CV-7 |

All 13 keys of §7.1 with exact defaults and ranges. **CV-6 verifies source readability by listing the directory only** — no probe file is ever created in the source (NI-7). The mirror-side probe file is fine; the mirror is ours.

**Tests:** UT-09, UT-10, UT-11, plus a boundary test per numeric key and a `SOURCE_FOLDER` untouched-after-validation assertion. → commit `phase-2: configuration and startup validation`

### Phase 3 — logging
`logging/LogLevel.java`, `logging/AppLogger.java` — JDK-only, daily rolling file, 30-day retention, `yyyy-MM-dd HH:mm:ss [LEVEL] message`, injected `Clock`, never logs file contents. Writes are buffered on a queue so a slow log volume can never stall a copy worker and therefore never extends how long a source handle is held (NI-9).

**Tests:** format, rollover across a simulated date change, retention pruning. → commit `phase-3: logging`

### Phase 4 — the copy core
The most important phase. `AtomicFileCopierImpl` is the only class in the codebase permitted to open a source file.

| File | Notes |
|---|---|
| `service/DifferenceDetector.java` + `SizeAndTimeDifferenceDetectorImpl.java` | §9.4; attribute reads only; vanished source → `false` |
| `service/FileCopier.java` + `AtomicFileCopierImpl.java` | §9.5 verbatim |

Implemented exactly as §9.5 specifies:
- `openWithRetry` first — `FileChannel.open(source, READ)` and nothing else (NI-1, NI-2)
- `NoSuchFileException` → `VANISHED` immediately, no retry (NI-11)
- `AccessDeniedException` / `FileSystemException` → backoff `OPEN_RETRY_INITIAL_MS`, doubling, `OPEN_RETRY_ATTEMPTS` times → `LOCKED`
- capture latency stamped the instant the open succeeds
- `size()` from the open channel, used only for the `MAX_COPY_BYTES` test
- **explicit 64 KB `ByteBuffer` read/write loop** — no `transferTo`, no `map`, no `Files.copy` (NI-4, C-9)
- read failure → break, close staging, publish the prefix if any bytes were captured (FR-27); zero bytes → `FAILED`, nothing published (FR-28)
- `setLastModifiedTime(staging, sourceMtime)` before the move (FR-09)
- `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING`
- outer `catch Throwable` → `FAILED`, so nothing ever escapes a copy job (FR-29)
- `finally` closes the channel and deletes the staging file on every path (NI-9, NFR-06)
- a `ConcurrentHashMap.newKeySet()` cache of created mirror directories

To make UT-17/UT-18 possible without faking a disk failure, the copier reads through a small internal seam — a supplier that returns the open `ReadableByteChannel` — which tests substitute with a channel that throws after N bytes. Production wiring passes the real `FileChannel.open`. This adds no interface to §6.1 and no indirection at runtime.

**Tests:** UT-03…UT-07, UT-15, UT-16, UT-17, UT-18. → commit `phase-4: copy core with partial publication`

### Phase 5 — copy queue
`service/CopyQueue.java` + `CopyQueueImpl.java` — the QUEUED / RUNNING / RUNNING_DIRTY machine of §9.6 on a `ConcurrentHashMap` mutated only via `compute`. The entry carries the **earliest** pending `eventNanos`. Worker: `needsCopy` → `copy` → record outcome → re-dispatch if dirty, else remove. Fixed pool of `COPY_WORKERS`, which is what makes NI-10 true by construction.

**Tests:** UT-12, UT-13, UT-14. → commit `phase-5: copy queue`

### Phase 6 — watcher, policy, sweep, stats
| File | Notes |
|---|---|
| `WatchServiceSourceWatcherImpl.java` | one `FILE_TREE` registration (C-5); `take()` loop on `fastmirror-watcher`; stamps `nanoTime()` per event; pushes to an unbounded `LinkedBlockingQueue`; **no other I/O**; watch failure → FR-23 exit |
| `MirrorServiceImpl.java` | `fastmirror-events` thread; §9.3 exactly — OVERFLOW → sweep, DELETED → return, filter, directory check via `readAttributes`, submit. **No `needsCopy` here** |
| `TreeSweepServiceImpl.java` | depth-first walk, per-entry error tolerance, attribute reads only (NI-12), `needsCopy` before submitting, logs only when N > 0 |
| `LoggingStatsReporterImpl.java` | `LongAdder` counters, §9.8 summary line every `STATS_INTERVAL_MS` |

**Tests:** unit-level tests for the event policy with a stub queue; sweep behaviour over a temp tree. → commit `phase-6: watcher, event policy, sweep, stats`

### Phase 7 — composition root
`Application.java` — wiring only (AR-6), in the exact order of §9.2 steps 1–11, including stale `.fmtmp` cleanup (FR-22) and watcher-before-sweep (FR-13). Shutdown hook implements §9.10. Default uncaught-exception handler per NFR-07.

**Exit:** the agent runs against two temp folders and mirrors a file. → commit `phase-7: composition root`

### Phase 8 — the prime-directive tests
Written and run before the rest of the integration suite, because if these fail the design is wrong and everything after is wasted.

| Test | Harness |
|---|---|
| IT-18 | A producer thread creates a file, waits until FastMirror has it open, then deletes it and **records whether its own delete threw**. 200 iterations. Any exception in the producer fails the test. |
| IT-19 | A producer holds a file open and appends every 5 ms for 10 s while FastMirror repeatedly copies it. Every write must succeed; write latencies are recorded and checked for FastMirror-attributable outliers. |
| IT-20 | Producer creates and immediately renames, 200 times. Every rename must succeed. |

These turn C-8 from an assumption into a measured fact on your actual JDK and machine (R-9). → commit `phase-8: producer non-interference tests`

### Phase 9 — remaining integration tests
IT-01 … IT-17 and IT-21, with `@TempDir` and a harness that runs a live `Application` against temp folders.

- IT-04 reports the COPIED/PARTIAL/VANISHED ratio — that is the §12 measurement, not a pass/fail threshold.
- IT-06/IT-07 need a genuine exclusive lock; I will confirm the lock helper actually blocks a reader on Windows before relying on it.
- IT-13/14/15/16 carry the `slow` tag.
- **IT-16 (long soak) and IT-17 (interop) cannot complete here** — they need the store PC and the live sync agent. IT-16 is written parameterised and runs a few minutes locally; IT-17 is documented as a manual procedure in the README.

→ commit `phase-9: integration tests`

### Phase 10 — architecture and rule enforcement
`ArchitectureRulesTest` (AT-01, AT-04) and `NonInterferenceRulesTest` (AT-02, AT-03). Both walk `build/classes` and scan each class file's constant pool for banned method and type references, using only the JDK (AR-8). AT-02 allows `AtomicFileCopierImpl` and nothing else.

→ commit `phase-10: architecture and non-interference enforcement`

### Phase 11 — packaging and documentation
`fatJar` wiring, `packaging/launch4j.xml` (GUI mode, `jre\` beside the exe), `env.template`, and `README.md` carrying the §12 limitation, the prime directive, the operator duties of §14.3 and the manual IT-17 procedure.

I can produce the exe and verify it launches headless here. **Verifying it on a clean no-JRE Windows 11 machine is yours** (DoD item 5), as is the 24-hour soak (item 6) and IT-17 (item 7).

→ commit `phase-11: packaging and documentation`

---

## 4. Estimated size

| | Main | Test |
|---|---|---|
| Classes | ~30 | ~22 |

Phases 1–5 are the bulk of the low-risk work. Phase 4 holds nearly all the difficulty and all of the non-interference risk. Phase 6 is where the concurrency care goes.

---

## 5. Field unknowns carried into the build

Three properties of the target PC are not yet known. None blocks implementation; each has a safe default and a way of being answered with real data. Tracked as U-1…U-3 in SRS §15.1.

| | Unknown | What I build now |
|---|---|---|
| U-1 | Whether the producer reuses filenames with different content | Build for unique names, as specified. Add **FR-31**: log a `WARN` whenever a copy replaces a target whose content actually differed. That line is the detector — a 24-hour soak answers the question instead of us guessing. If reuse turns out to be real, the fix is versioned mirror filenames, which changes what the sync agent sees and needs your agreement first (R-10). |
| U-2 | Whether the source tree has subdirectories | Recursive watch and structure preservation are built either way; the flat case costs nothing extra. No decision needed. |
| U-3 | Whether the sync agent watches `MIRROR_FOLDER` yet | `MIRROR_FOLDER` defaults to `C:/temp`. IT-17 is handed over marked unverified. Nothing in the build depends on it. |

U-1 is the one with teeth: if names repeat and the sync agent is slower than the producer, a complete file could be overwritten in the mirror before it is uploaded. FR-31 exists so we find that out from a log rather than from missing data in S3.

## 6. What I will not claim

- I will report real test output, including failures, rather than asserting success.
- The three items in §17 that require the store PC or the live sync agent will be handed over explicitly as unverified, not quietly marked done.
- If IT-18 ever fails, I will stop and bring it to you rather than working around it. A workaround there would mean breaking the producer.


---

## 7. Outcome

All twelve phases are committed, one commit each. Clean build, no compiler warnings, **163 tests in the default suite and 4 in `slowTest`, all passing**.

### Measured on this machine (Windows 11 build 26200, JDK 21.0.10)

| | |
|---|---|
| Capture latency (IT-15, 3,000 files at 50/s) | **mean 4.63 ms, max 19 ms** — NFR-01 budget is 10 ms |
| Atomic visibility (IT-13, 50 MB) | reader saw only *absent* or *complete*, never a partial size |
| Overflow (IT-14) | 5,000 files, all mirrored, `FAILED=0` |
| Soak (IT-16, 30 s) | 1,789 cycles, threads 14 → 14, `FAILED=0` |
| Producer interference (IT-18/19/20 + external-process variants) | **zero** failed producer operations across 800+ delete, rename and write operations |

### Two things the build found rather than assumed

**The SRS was wrong about memory mapping.** C-9 in v2.0 claimed a mapped section makes the producer's delete fail with `ERROR_USER_MAPPED_FILE`. On this platform it does not — modern Windows deletes by unlinking the name, and both in-process and external deletes succeed against a mapped file. The specification now records what was measured. NI-4 still forbids mapping, on the surviving rationale that the behaviour is version-dependent, is documented to fail elsewhere, and buys nothing.

**A transient publish failure exists.** One `FAILED` appeared once in ~1,800 soak cycles and did not reproduce. Windows can briefly refuse a rename while a scanner holds the target open, so the publish step now retries three times with a short backoff. Retrying is safe there in a way that retrying a source open is not: the target is inside the mirror folder, which is ours.

The rule tests were also verified to be capable of failing: a class deliberately violating NI-1, NI-3 and NI-4 was compiled in, three architecture tests went red, and they were green again once it was removed.

### Handed over unverified

These need the store PC and cannot be completed here:

1. **IT-17** — interoperation with the live HOR AWS Sync Agent.
2. **The 24-hour soak** against the real Gemcom folder (`gradlew slowTest -Dfastmirror.soak.seconds=86400`), with the `VANISHED`, `PARTIAL` and `FAILED` counts recorded.
3. **Clean-machine launch** — `FastMirror.exe` plus `jre\` on a Windows 11 box with no JRE. The jar was verified to run standalone from a deployment folder; producing the `.exe` needs Launch4j, which is not installed here.
