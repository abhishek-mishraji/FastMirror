# FastMirror

A Windows background agent that copies files from a **source folder** to a **mirror folder** as fast as technically possible, preserving the directory structure exactly.

It exists because the Gemcom application on one store PC creates XML files, writes them and deletes them **within about one second**. Anything that waits before reading such a file loses it. FastMirror turns that volatile folder into a stable one that other tools — such as the existing HOR AWS Sync Agent — can consume at their own pace.

```
C:\Gemcom\bin\out\          volatile: files live ~1 second
        |
        |  FastMirror — copy immediately, preserve structure
        v
C:\temp\                    stable: files persist
        |
        |  (separate, existing, unmodified agent)
        v
   Amazon S3
```

FastMirror does not upload anything, does not talk to any cloud provider, and **never deletes anything from the mirror**.

---

## The two rules that matter

### 1. The producer must never notice FastMirror

This outranks everything else, including capturing any individual file.

> No action taken by FastMirror may cause a Gemcom operation — create, open, write, flush, rename, delete — to fail, block, or return an error.

If a file cannot be captured without risking interference, **the file is lost and that is the correct outcome.**

This is not a promise on paper. FastMirror opens source files read-only through a single audited code path, and a set of ordinary Java calls is banned outright because each of them can make another process's delete or write fail on Windows (`Files.copy`, `FileChannel.transferTo`, memory mapping, file locks). Two tests scan the compiled bytecode and fail the build if any of those appear. Six more tests run a simulated producer — including a genuinely separate Windows process — that deletes, renames and appends to files while FastMirror holds them open, and assert that **its** operations all succeed.

Measured on Windows 11 build 26200 with JDK 21: every one of those operations returned success, while FastMirror's open handle kept reading the original bytes.

### 2. Some data beats no data

If a read fails part-way through, the bytes already captured are **published**, not thrown away. The outcome is recorded as `PARTIAL` and counted separately so you can see whether it ever actually happens. Set `PUBLISH_PARTIAL=false` to restore the stricter behaviour.

---

## Known and accepted limitation

**Read this before deploying.**

FastMirror learns that a file exists only *after* it has been created. Between Gemcom's write and FastMirror's first `open()` there is an irreducible window — Windows notification delivery plus event dispatch — of typically **1 to 10 milliseconds**.

**If Gemcom deletes a file inside that window, the bytes are unrecoverable.** No design in Java, or in any other language on Windows, can retrieve a file that no longer exists and was never opened.

Closing the window further is only possible through techniques that violate rule 1 — holding handles open speculatively, installing a filesystem filter driver, or denying Gemcom delete access. None of those are acceptable, so the window stays.

What this design achieves is reducing the exposure from the ~10 seconds a stability-checking consumer needs down to single-digit milliseconds, and making the copy immune to deletion **once the handle is open**.

The `VANISHED` counter in the log is how this is measured in production rather than assumed. If it stays at zero over a representative period, the race never materialises on your hardware. If it does not, that number is the evidence needed to decide whether further work is justified.

---

## Installation

```
C:\HOR\FastMirror\
    FastMirror.exe          no console window
    .env                    configuration
    jre\                    bundled Java 21 runtime; no installation required
    logs\                   created on first run
```

Copy the folder to the machine, edit `.env`, and double-click `FastMirror.exe`. No Java installation is needed.

For unattended operation, register it as a Windows service or a Task Scheduler task set to run at startup, restart on failure, and run whether or not a user is logged on.

### Configuration

Only two keys are required:

```properties
SOURCE_FOLDER=C:/Gemcom/bin/out
MIRROR_FOLDER=C:/temp
```

Everything else has a working default. See `packaging/env.template` for the full list with explanations. Configuration is read once at startup; there is no hot reload.

FastMirror refuses to start, with an explanatory message and exit code 1, if the source folder does not exist, the mirror folder cannot be created or written, the two folders overlap (which would cause an infinite copy loop), or any numeric value is out of range.

---

## Reading the log

One file per day under `logs\`, kept for 30 days, in the same format as the AWS Sync Agent:

```
2026-09-20 19:37:07 [INFO] Copied: PJR9.xml (24 bytes, captured in 14ms)
2026-09-20 19:37:07 [INFO] Stats (last 60s): COPIED=412 REPLACED=38 VANISHED=2  capture latency avg=2.6ms max=11ms
```

The counters worth watching:

| Counter | Meaning | What to do |
|---|---|---|
| `VANISHED` | The file was already gone when FastMirror first tried to open it. Nothing was captured. | This is the limitation above, measured. Report a persistently non-zero value. |
| `PARTIAL` | A read failed part-way; what was captured has been published. | Should be near zero. Investigate if it is not. |
| `FAILED` | An I/O error that captured nothing. | Investigate. Usually disk space or permissions. |
| `LOCKED` | Another process held the file exclusively past the retry budget. | Harmless in small numbers; the sweep retries. |

You may also see:

```
[WARN] Filename reused: a newly created source file replaced an already mirrored file at the same path: PJR9.xml
```

This means Gemcom deleted a file and then created another one under the same name, while the mirror still held the earlier version. FastMirror keeps only the latest version of each path, so if the AWS agent had not yet uploaded the earlier one, it is gone. The check is deliberately narrow — an ordinary new file never triggers it — so these lines are worth taking at face value. **If these lines appear, report them** — it is a known open question (SRS U-1 / R-10) with a designed fix that has not been built because we do not yet know whether it is needed.

---

## Operator responsibilities

- **Mirror folder growth.** FastMirror never deletes from the mirror. Apply a retention policy — a scheduled task deleting files older than N days is enough. If the mirror is also the AWS agent's watch folder, those deletions produce `Local delete observed — S3 object retained` lines in its log; the S3 objects themselves are never removed.
- **Disk space.** Size the mirror volume for the retention period times the daily file volume.
- **Monitoring.** Watch `VANISHED`, `PARTIAL` and `FAILED`, and the filename-reuse warning above.

Stopping the agent with Ctrl+C or a service stop lets in-flight copies finish and cleans up. A forced kill is also safe: at worst a `.fmtmp` staging file is left behind, and the next startup removes it. The mirror is never left holding a half-written file either way.

---

## Building from source

Requires JDK 21. Gradle comes from the wrapper.

```
gradlew.bat build          compile and run the test suite
gradlew.bat slowTest       the heavy scenarios: throughput, overflow, 50 MB atomic visibility, soak
gradlew.bat fatJar         produces mirror\build\libs\FastMirror.jar
```

### Producing `FastMirror.exe`

Three commands, from the project root in `cmd`. Requires [Launch4j](https://launch4j.sourceforge.net/) installed.

```bat
gradlew.bat fatJar

jlink --add-modules java.base,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output build\dist\jre

"C:\Program Files (x86)\Launch4j\launch4jc.exe" packaging\launch4j.xml
```

That leaves `build\dist\FastMirror.exe` and `build\dist\jre\` (about 30 MB). Copy both, plus a `.env` built from `packaging\env.template`, to the target machine.

`jlink` refuses to write into an existing directory, so delete `build\dist\jre` before re-running it.

Two things about that module list, both verified rather than assumed:

- It is exactly what the agent needs — `jdeps --print-module-deps --ignore-missing-deps mirroruild\libs\FastMirror.jar` reports `java.base,jdk.unsupported` and nothing else.
- **`jdk.unsupported` is mandatory.** It exports the API used to register the recursive directory watch. Drop it and the agent fails at startup.

The Launch4j config deliberately sets no `minVersion`. Setting one turns the bundled runtime into a mere preference and lets the exe fall back to whatever Java is installed — which was observed here: with `jre\` renamed away, the exe happily started on an installed JDK. On a store PC that would mean a mis-copied `jre\` folder silently running the agent on the wrong Java. With no version range the bundled runtime is required, and its absence is an immediate error instead of a subtle one.

### Layout

```
mirror/src/main/java/com/handsoffretail/fastmirror/
    Application.java            composition root: wiring and startup order only
    config/                     .env parsing and startup validation
    entity/                     events, outcomes, counters
    service/                    interfaces
    service/implementation/     concrete services, all named *Impl
    logging/                    daily rolling file log
```

All access to source files is confined to `AtomicFileCopierImpl`. That is deliberate: it puts every rule about not disturbing the producer in one small reviewable place, and lets the architecture tests enforce it mechanically.

### Test suite

163 tests in the default run, 4 more under `slowTest`. The ones to understand first are in `ProducerNonInterferenceTest` — they are the executable form of rule 1, and a red run there blocks release regardless of everything else.

Two items cannot be verified off the store PC and are handed over unverified:

- **IT-17**, interoperation with the live AWS Sync Agent.
- **The 24-hour soak** against the real Gemcom folder. Run it with `gradlew slowTest -Dfastmirror.soak.seconds=86400`, and record the `VANISHED`, `PARTIAL` and `FAILED` counts.

---

Full specification: [SRS_FastMirror.md](SRS_FastMirror.md). Build plan and phase history: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).
