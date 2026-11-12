# Plan 4: faster library start-up, and the TV measurements

Covers two items from `docs/TODO.md`:

* **Item 8:** read the first real TV log and fill the measurements table (plan 1 phase 0, plan 2 A1).
* **Item 3:** a saved binary copy of the prepared library, so a cold start doesn't re-read 133 MB of JSON and redo 26 s of channel work (plan 1 item 4.5).

Written to be executed by a model that has not seen the earlier work. Read sections 1 and 2 first; they explain where the time goes, and every later step depends on that.

## 1. Where the time goes today (from the owner's log, 11 Nov, onn 4K Plus, Relay 1.0.91)

Device: 4 cores, 1917 MB RAM, app memory limit 384 MB, 1920x1080 at 320 dpi (960x540 dp). Library: 56,019 channels, 165,672 movies, 38,104 series, 1,510 categories; saved JSON 132.7 MB.

A cold start (process start, nothing in memory) after the 1.0.91 update:

| Step | Time | Share | Code |
|---|---|---|---|
| Process start to "Opening the saved library" | 0.9 s | 1% | `RelayApp.onCreate`, `Catalog.load` |
| Read the six JSON files (three at once) | 29.6 s | 47% | `CatalogParser.parseConcurrently`; the movie file alone is 29.4 s |
| Channel languages | 7.9 s | 13% | `ChannelStage.chLang`, `LanguageDetector.ofChannel` |
| Channel versions (key and quality) | 14.4 s | 23% | `ChannelStage.versionKeys` and `quality`, `ChannelVersions.key` and `quality` |
| Channel index (groups, sort keys, HD flags) | 3.5 s | 6% | `buildChannelIndex` |
| **Channels on screen** | **56.4 s after process start** | | `install(chIndex)` |
| Movie languages and lists | 5.1 s | 8% | `MovieStage` |
| Series languages and lists | 1.2 s | 2% | `SeriesStage` |
| **All lists ready** | **62.7 s after process start** | | |

Memory in use after the load: 151 MB of 384 MB (39%). During the refresh that followed the 1.0.77 update it reached 241 MB; a refresh on 1.0.22 reached 299 MB (78%).

When the saved library is over 6 hours old, the cold start is followed at once by a refresh: download (about 7 s, the three big lists in parallel), parse again ("Checked new lists", 26.4 s), and prepare again (channels after 23.5 s, all after 29.2 s). The user can browse during this, but three cores are busy for about a minute.

What this means for the design:

1. Reading JSON is half of the wait. A binary copy removes most of it.
2. The channel stage is the other 40%, and almost all of it is per-channel text work (regular expressions, cleaning names, uppercase copies, scanning keyword tables) whose result depends only on the channel's name and its category's language. Those results can be saved with the binary copy, and, on a refresh, reused for every channel whose name and category didn't change.
3. Two cheap code faults make that text work slower than it needs to be: `LanguageDetector.ofChannel` and `ofCategory` compile a `Regex` on every call (`Languages.kt`), and each channel's name is cleaned by `ProviderNames.clean` three times (language, version key, quality).
4. Memory has room (39% after load), so memory work is not needed first. The new code must not raise the peak.

Cold starts happen after every update, after a reboot, and when Android removes Relay from memory. Every one of the three cold starts in the log (1.0.22, 1.0.77 and 1.0.91) took 53 to 73 seconds to show channels.

## 2. Decisions taken, with defaults (ask the owner only if a default turns out not to fit)

| # | Question | Default |
|---|---|---|
| 2.1 | Extra storage | The binary copy adds roughly 40 to 60 MB beside the 133 MB of JSON, plus the same again briefly while a new copy is written. Accepted. The JSON stays: it is the source of truth and the fallback. |
| 2.2 | When the copy is ignored | Any doubt means JSON: a different format version, different detection logic, a JSON file whose size or date changed, a checksum mismatch, or any read error. The copy is then deleted and rewritten after the JSON load. |
| 2.3 | First start after an update that changes detection logic | Slow once (the JSON path), then fast. Accepted. |
| 2.4 | Overdue refresh at start-up | Starts 20 seconds after all lists are ready instead of immediately, so the first minute of browsing isn't fighting the refresh for the processor. "Refresh channels and library" in Settings still starts at once. |
| 2.5 | Releases | One, at the end, like plan 3. |

## 3. Phase M (item 8): fill the measurements, fix the stall false alarm

### M1. Fill the table

Replace the table in `docs/PLAN.md` section 11 (its columns, "after P1" to "after P4", no longer match how the work was released) with this one, filled from the log. Mark A1 done in `docs/PLAN-2.md` with a pointer here.

| Measure (TV log, onn 4K Plus) | 1.0.22 (8 Nov) | 1.0.77 (11 Nov) | 1.0.91 (11 Nov) | After plan 4 |
|---|---|---|---|---|
| Layout width, cores, memory limit | 960 dp, 4, 384 MB | same | same | |
| Process start to channels on screen | 73.4 s and 70.0 s | 53.0 s | 56.4 s | |
| Process start to all lists ready | same as above (one stage) | 59.0 s | 62.7 s | |
| Read saved library | 40.7 s and 41.6 s (one file at a time) | 27.2 s | 29.6 s | |
| Prepare lists (slowest step) | 28.5 s (channel versions 14.8 s) | 30.1 s (channel versions 13.4 s) | 32.2 s (channel versions 14.4 s) | |
| Refresh: parse again, then prepare | 56.0 s, 32.8 s | 26.4 s, 29.2 s | no refresh in the log | |
| Memory after load, after refresh (of 384 MB) | 152 to 160 MB, 299 MB | 150 MB, 241 MB | 151 MB | |
| Slowest Live list build | "All channels" 259 ms | not in the log | 4 ms (recent channels; All channels not opened) | |
| Movies list, all titles (off the screen thread) | not logged | not logged | 444 ms | |
| Search (whole-guide search on) | not logged | not logged | 1.2 to 2.2 s | |
| First picture after tuning | not logged | not logged | median 902 ms (773 to 1,333, 7 tunings) | |
| Real screen-thread stalls | none (2 false reports, see M2) | none | none | |

Write under the table: "4.5 decided: go. Reading the saved library takes 29.6 s against the 8 s threshold. Memory after load is 39% of the limit, so memory work is not needed first."

### M2. Stall watch: no false alarm after Android freezes the app

The log has "Screen thread not responding for 110514s" and "168650s". These are not freezes the viewer saw: Android 14 freezes cached apps, and the watcher thread was frozen with everything else, so on wake-up it compares against a beat from two days earlier.

Change in `data/StallWatch.kt`: record when the watcher itself last ran. If more than `STALL_MS + CHECK_MS` passed between two of its own iterations, the whole process was paused; reset `lastBeat` to now and skip that check. Put the decision in a pure function, `StallWatch.verdict(prevLoop, now, lastBeat): Verdict` (`Ok`, `Stalled(ms)`, `ProcessPaused`), with tests: a normal beat, a 5 s stall, a 2 day pause, and a stall that starts right after a pause.

### M3. Log lines needed for the "after" column

1. In `Catalog.applyParsed` (or its replacement), add the time since process start (`SystemClock.uptimeMillis() - Process.getStartUptimeMillis()`, API 24) to the "Channels ready" and "all lists" lines: `Channels ready after 1840 ms (5.2 s after start)`.
2. The snapshot lines from phase B (B6).
3. Add to `RelayApp.stateDump` one line: `Library snapshot: used, 48.2 MB, saved 11 Nov 21:51` or `Library snapshot: not used (logic changed)`.

### M4. The owner's measurement routine (written into TODO.md "Check on a device")

So the before and after numbers are comparable, the owner runs the same routine once after the release:

1. Settings → Diagnostics → Save detailed logs on.
2. Unplug the TV for 10 seconds (a real cold start), or install the update. Open Relay, wait until channels show.
3. Open Live TV → All channels, scroll for 20 seconds. Open the guide. Open Movies, then Series. Search for a channel name. Tune three channels.
4. Next day, when the library is more than 6 hours old, open Relay from cold again and leave it on Home for 2 minutes (catches the refresh).
5. Settings → Phone companion → Download logs, and attach.

## 4. Phase A: make the per-item text work cheaper (no behaviour change)

Goal: the same answers, faster. This helps the JSON path (first start after an update), every refresh, and the snapshot writer. It is bounded: no new matching algorithm in this plan.

A1. **Compile once.** In `Languages.kt`, move the two `Regex(...)` built inside `ofCategory` and `ofChannel` to private vals. Same patterns, character for character.

A2. **Clean each name once.** `ProviderNames.clean(name)` runs inside `LanguageDetector.words`, `ChannelVersions.key` and `ChannelVersions.words`. Add internal overloads that take the already-cleaned name (`ofChannelCleaned(raw, clean, catLang)`, `keyCleaned(clean)`, `qualityOf(raw, clean)`) and a single `ChannelText.derive(name, catLang): ChannelFacts` returning language, version key, quality, sort key and HD flag together. `ChannelStage` and `buildChannelIndex` call `derive`. The public one-argument functions stay and delegate, so other callers are untouched.

A3. **Golden test before anything else changes.** Copy today's `LanguageDetector.ofChannel`, `ofTitle`, `ofCategory`, `ChannelVersions.key`, `quality`, and `ChannelKeys.sortKey`, `isHd` into the test sources as `LegacyText` (frozen, never edited). `ChannelFactsGoldenTest` builds a corpus of at least 20,000 names: every name already in `LanguagesTest`, `VersionsTest`, `ChannelKeysTest`, `NamesTest`, the generator from `tools/fake-provider/fake_provider.py` ported to Kotlin (same word lists and styles, fixed seed), plus fuzzed variants (superscripts, brackets, pipes, mixed case, "ᴿᴬᵂ" tags, empty strings, emoji). Every name times a few category languages, including null. The new `derive` must equal the legacy functions on every entry. This test is the safety net for phases A, B and C.

A4. **Measure locally.** A plain JVM timing (not a unit test assertion) over a 56,000-name corpus, legacy against new, printed in the commit message. The emulator benchmark in phase D gives the device-side figure.

Accept: golden test passes; the local timing for the channel stage drops (expected at least a third; if it doesn't, keep A1 and A2 anyway, they are correct, and say so in the outcome).

## 5. Phase B (item 3): the saved binary copy of the prepared library

### B1. What is saved

One file, `filesDir/catalog/library.snap` (inside the catalog folder, so `Catalog.clear()` already deletes it). Everything the three stages produce that costs time, nothing that depends on the user's edits:

| Section | Contents |
|---|---|
| Header | Magic `RLYL`, format version (1), `LibrarySnapshot.LOGIC` version, time written; for each of the six JSON files its byte length and last-modified time; item counts; a table of sections with offset, length and CRC32 |
| Categories | The three category lists (id, name, adult flag) |
| Channels | Per channel, in provider order: id, number, name, logo, category (index into a string table), guide id, archive days, and the derived facts from A2: language (index into a small table, or none), version key, quality, sort key, HD flag |
| Movies | Per movie: id, name, poster, category, rating (the exact `Double` bits), file type, added, TMDB id, year, language. Then three orders as index arrays: newest, best rated (only rated titles), A to Z |
| Series | The same as movies, with cover in place of poster |

Not saved: renames and channel groups (applied after loading, as today), learned title languages (`learned_langs.json` stays and is applied at lookup, as today), anything per profile, the guide.

Encoding rules (keep them in one class, `data/SnapshotIo.kt`, pure, JVM-testable):

1. Unsigned and zig-zag varints for every integer; `Long` for `added`; `Double.toRawBits` for ratings so they round-trip exactly.
2. Strings as varint byte length then UTF-8 bytes; length 0 means null and 1 means the empty string, so a null and an empty name stay different. Do not use `DataOutputStream.writeUTF` (64 KB limit and modified UTF-8).
3. Repeated values (category ids, file types, years, language codes) go through a per-section string table and are written as indexes. When reading, each table value is created once, so titles share one copy exactly as the `Interner` in `CatalogParser` does today. Memory after load must not grow.
4. Each section is written through a `CheckedOutputStream` (CRC32) and its CRC is stored in the header.

### B2. Reading

`LibrarySnapshot.read(dir, jsonFiles): SnapshotResult` returns either the parsed lists plus the three stages' derived data, or `NotUsed(reason)`.

1. Read and check the header first: magic, format, `LOGIC`, the six JSON lengths and dates. Any mismatch returns `NotUsed` without reading further.
2. Read the channel, movie and series sections at the same time (three threads, the same limit `parseConcurrently` uses), each through its own `FileInputStream` positioned at its offset, a 256 KB `BufferedInputStream` and a `CheckedInputStream`. Strings are decoded from a reusable byte buffer, not one allocation per field. Never read a whole section into one array: the movie section is tens of MB, and that would raise the memory peak.
3. At the end of each section compare the CRC. Mismatch, short file, or any exception: `NotUsed("damaged")`, delete the file, fall back to JSON.
4. Channels are handed over as soon as their section is done, so Live TV can show while movies are still being read (the staged install from plan 1 phase 4.1 keeps working).

### B3. Installing from the snapshot

Refactor, without changing behaviour on the JSON path:

1. `ChannelStage`, `MovieStage` and `SeriesStage` become plain holders with two ways in: `from(parsed)` (compute, as today, using A2's `derive`) and `fromSnapshot(...)` (fields read from the file).
2. `buildChannelIndex` takes the saved per-channel facts (version key, quality, sort key, HD flag) and recomputes them only for renamed channels, as it already does for key and quality. Grouping and ranking still run here; they are hash map work and should take well under a second.
3. `applyParsed(p, from)` becomes `install(stages, from)` plus the two producers. Both paths go through the same install code, the same progress steps and the same log lines.
4. In `Catalog.load`: when `isEmpty && cacheComplete()`, try the snapshot first; on `NotUsed`, log the reason and run today's JSON path.

### B4. Writing

1. After a library is installed from JSON (a cold start that couldn't use the snapshot, or a refresh), the install hands an immutable `SnapshotSource` (the lists and derived maps it just installed) to `LibrarySnapshot.writeLater`.
2. The write runs on `Dispatchers.IO` at background thread priority (`Process.THREAD_PRIORITY_BACKGROUND` set and restored in `try/finally`), starting 5 seconds after the last stage is installed, so it doesn't compete with the first screen.
3. Write to `library.snap.tmp`, `fd.sync()`, then rename over `library.snap`. Any error: delete the temp file, log it, keep going (the JSON path still works).
4. Skip the write, with a log line, if free space in `filesDir` is under three times the last snapshot's size (or 200 MB the first time).
5. A newer load or refresh cancels a pending write (a generation counter, as `customGen` does for renames).
6. Streaming only: the writer walks the lists already in memory. It must not copy them.

### B5. `LOGIC` version: never trust stale derived values

The saved languages, version keys, quality, sort keys and HD flags are only valid for the code that computed them. `LibrarySnapshot.LOGIC` is an integer constant. `SnapshotLogicTest` runs `ChannelText.derive` and `LanguageDetector.ofTitle` over a fixed sample of 2,000 corpus names (from A3) and hashes the outputs; the expected hash is written in the test next to the `LOGIC` value it belongs to. If anyone changes detection logic and the hash changes, the test fails with the message "Detection logic changed: bump LibrarySnapshot.LOGIC and update the hash in this test". That way a stale snapshot can't survive an update that changed the rules.

### B6. Log lines

* `Library from saved copy in 4210 ms (channels 2380 ms, movies 3950 ms, series 1120 ms; 48.2 MB)`
* `Saved copy not used: <reason>` where reason is one of: missing, older format, detection logic changed, library files changed, damaged.
* `Saved a copy of the library: 48.2 MB in 6.8 s` or `Couldn't save a copy of the library | <exception>`.
* The "Channels ready" and "all lists" lines from M3 stay the same on both paths, so the measurements table compares like with like.

### B7. Tests (pure JVM, run locally with the scratchpad `kc/check.sh` before CI)

1. `SnapshotIoTest`: varints at boundaries (0, 127, 128, Int.MAX, negative zig-zag), strings (null, empty, ASCII, Hindi and Bengali text, emoji, superscripts, a 100 KB string), doubles (0.0, -0.0, NaN, 7.25).
2. `LibrarySnapshotTest`: write then read a generated library (2,000 channels, 5,000 movies, 1,000 series with the corpus names) and require equality of every list, every derived map and every order with the JSON path's result (`from(parsed)`).
3. The same, with: one JSON file's length changed; date changed; `LOGIC` changed; format changed; file truncated at 10 random points; one byte flipped in each section. Every case returns `NotUsed` with the right reason and never throws.
4. Shared strings: after reading, all movies in one category hold the identical (`===`) category id string.
5. A test that the orders saved (newest, best rated, A to Z) equal the orders `MovieStage` computes, including ties (ties keep library order today; they must after reading too).

Accept: tests pass locally and on CI; an install from the snapshot produces the same lists, languages and counts as the JSON path; the language report in Settings → Diagnostics is identical before and after (the owner can compare; a test compares it for the generated library).

## 6. Phase C: cheaper refreshes

### C1. Reuse derived values for unchanged items

On a refresh, the new channel list is mostly the same channels with the same names. Before computing the channel stage, build `previous: Map<Int, ChannelFacts>` from the installed library (with the name and category language each fact was computed from). For each new channel, if the id exists in `previous` with the same raw name and the same category language, reuse the facts; otherwise call `derive`. Same for movie and series languages (key: id, raw name, category language).

Log: `Reused 55,812 of 56,019 channel facts; worked out 207`.

Test `RefreshReuseTest`: for a generated library and a randomly changed copy (names edited, categories moved, items added and removed, a category's language changed), the reused result equals a full recompute.

### C2. Overdue refresh waits 20 seconds after start-up

In `Catalog.load(force = false)`, when the library was just installed from saved data and is due, wait 20 seconds (cancellable) before `refresh`. `force = true` (the user asked) starts at once. Log `Library is 9 h old; refreshing in 20 s`. Test the decision as a pure function (`Freshness.startDelay(installedJustNow, due, force)`).

Accept: on a refresh the "Channels ready" figure drops by most of the channel-stage time (expected from about 23 s to a few seconds on the TV when few channels changed).

## 7. Phase D: benchmark

Extend `tools/bench/bench.sh` (benchmark build only, no app change):

1. After the existing run, force-stop Relay and start it again. This second start must use the saved copy. Record from logcat: "Library from saved copy in N ms", the "Channels ready" and "all lists" lines with time since start.
2. Delete `library.snap` (`adb shell run-as dev.relay.tv rm files/catalog/library.snap`), force-stop, start again: the JSON path, for comparison in the same run.
3. `report.py` prints a "Cold start" block: JSON path and saved copy, channels and all lists, in ms.
4. The existing frame stretches keep running unchanged, so plan 2's comparison still applies.

Gate before the release: on the emulator, channels on screen with the saved copy must be at least four times faster than the JSON path in the same run, on all three branch runs. If not, stop and report the numbers to the owner instead of releasing.

## 8. Targets on the TV (checked with the M4 routine after the release)

| Measure | Now (1.0.91) | Target |
|---|---|---|
| Process start to channels on screen, saved copy used | 56.4 s | 10 s or less |
| Process start to all lists, saved copy used | 62.7 s | 15 s or less |
| First start after an update that changed detection logic | 56.4 s | No worse than now; a bit better from phase A |
| Refresh: channels ready after the new lists are parsed | 23.5 s | 5 s or less when few channels changed |
| Memory after load | 151 MB of 384 | No more than 160 MB |
| Memory peak during refresh | 241 MB (1.0.77) | No higher |

The parse of a freshly downloaded list (about 26 s) stays, because new JSON has to be read once. It runs in the background after C2's delay.

## 9. Risks

| Risk | Guard |
|---|---|
| Saved languages or version keys go stale after a logic change | `LOGIC` version and the hash test (B5) |
| A snapshot that doesn't match the JSON | Header carries each JSON file's length and date; any mismatch means JSON (B2) |
| A half-written or corrupted file | Temp file, `sync`, rename; CRC per section; any read error means JSON and the file is deleted |
| Memory peak rises | Streaming read and write, no whole-section arrays, shared strings (B1.3, B2.2, B4.6); memory lines compared before and after |
| Running out of storage | Free-space check before writing (B4.4) |
| Writing slows the first minute on a TV | Background priority, 5 s delay, cancellable (B4.2) |
| Lists differ subtly (order of ties, nulls against empty strings) | Equality tests against the JSON path (B7.2, B7.5); null and empty encoded differently (B1.2) |
| Refresh reuse gives a different answer from a recompute | `RefreshReuseTest` (C1) and the golden test (A3) |

## 10. Order, commits and release

Branch `plan4`. One commit per phase, in this order:

1. **M** (docs table, StallWatch fix, log lines).
2. **A** (derive and golden test).
3. **B** (snapshot).
4. **C** (refresh reuse and delay).
5. **D** (benchmark).
6. **Docs**, then a final commit whose message body is the release notes.

Rules, as in plan 3:

1. **One release.** `main` is pushed once at the end. Branch pushes build nothing.
2. **Compile checks.** CI is the only Android compiler: run the workflow on `plan4` after A, after B and after D (each uses a build number; the release number jumps, which is cosmetic). Pure code is compiled and tested locally first.
3. **Benchmark.** Three runs on `plan4` and three on a copy of `main`. Compare the frame stretches (no stretch consistently worse) and pass the cold-start gate in section 7.
4. **Docs in the same push.**
   * `README.md`: one sentence in the Features "Reliability" row about the saved copy, a Troubleshooting line ("if lists look wrong after an update, Settings → Refresh channels and library rewrites the saved copy"), and Project layout if new files are added.
   * `docs/PLAN.md`: the section 11 table (M1).
   * `docs/PLAN-2.md`: A1 marked done.
   * `docs/TODO.md`: items 3 and 8 moved to Done, the M4 routine under "Check on a device", and the "After plan 4" column left to fill from the owner's next log.
   * `docs/PLAN-4.md`: this plan plus an Outcome section with the release number, deviations and the benchmark table.
5. Tests for every pure function in the same commit as the function. Tests are never deleted to make a build pass.
6. The release APK must contain no benchmark code (the existing CI guard). Signing and update-token steps in `build.yml` are not touched.
7. Stop and ask only if a default in section 2 turns out not to fit, or the section 7 gate fails.

Suggested release notes: "Faster start: Relay keeps a ready-to-use copy of your library, so after a restart or an update the channels appear in seconds instead of about a minute. The first start after some updates still takes the long way once. Library refreshes do less work and wait 20 seconds after start-up, so the first minute of browsing is smoother."

## 11. Not in this plan

* The 26 s parse of a freshly downloaded list (would need a different download format from the provider, which Xtream servers don't offer).
* A faster keyword matcher for channel languages (only if phase A's numbers show the channel stage is still slow on the JSON path).
* Running the refresh parse at lower priority while the viewer is browsing (worth a look once the C2 delay has been tried on the TV).
