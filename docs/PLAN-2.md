# Relay plan 2: TV speed, response and quality of life

Written after reviewing the code released as 1.0.24 to 1.0.31. Nothing in the app was changed for this review. It follows the same rules as `docs/PLAN.md` (section 2): one release per phase, no behaviour change beyond what a task lists, pure logic gets unit tests, CI is the only Android compiler, no secrets in the repo.

Plan 3 ([PLAN-3.md](PLAN-3.md)) carries on from here: its section 6 lists what is still open from plans 1 and 2 (phase E, D4, D6, A1, A4, 4.5), and [TODO.md](TODO.md) has the current state.

## 1. What the review could and could not check

| Source | What it shows | Limit |
|---|---|---|
| CI emulator run of 1.0.31 | Installs, cold start in 843 ms (1.0.30: 778 ms), still running after 25 s, no crash | The emulator has no account, so it stops at the sign-in screen. It never loads a library, scrolls a list or plays anything. It says nothing about TV speed. |
| Emulator in this workspace | Not possible | No Android SDK and no hardware virtualisation here. |
| Reading the code | Everything below | Findings marked **verify** are likely from the code but not proven on a device. |

So the honest position: phases 1 to 8 are compiled, unit tested and start cleanly, but there is still no measurement from a TV, and the emulator check cannot give one as it stands. Phase A fixes that first, because every later phase needs numbers.

## 2. Findings

Severity: **High** = likely visible on a TV or a feature that may not work; **Medium** = costs frames or time on a slow processor; **Low** = tidy-up.

| # | Sev | Where | Finding |
|---|---|---|---|
| F1 | High, verify | `ui/Components.kt` `Focusable` | Long-press removal (1.0.29) uses `combinedClickable`. With Compose BOM 2025.01 (foundation 1.7) holding OK on a remote is, as far as I know, not turned into a long click; only touch is. The only remote path is the Menu key, which most Google TV remotes don't have. Removal from Continue watching may be unreachable on the TVs. |
| F2 | High | `PlayerActivity.playLive`, `data/Store.kt` `write` | Every channel change writes history on the screen thread: up to 400 entries encoded to JSON, at the exact moment the TV is tuning. `saveProgress` does the same every 10 s during a movie. Every write also bumps the single `store.version`, which makes every screen that reads it recompose. |
| F3 | High | `PlayerActivity.buildPlayer` | `bufferForPlaybackMs` is 2,000 for everything. Live channels wait for 2 s of video before showing a picture, which sets a floor on zapping speed. |
| F4 | Medium | `ui/Components.kt` `Focusable` | Each focusable item starts two coroutines (`LaunchedEffect(focused)`, `LaunchedEffect(restoreTick, enabled)`), an animation state, an interaction source and a focus requester. During a fast scroll every new row or poster pays this. On a slow TV this is the main per-item cost left. |
| F5 | Medium | `data/LiveList.kt`, `data/TitleList.kt`, `ui/Live.kt` Find | List builders never check for cancellation, and Find in Live TV rebuilds on every letter with no pause. Typing 5 letters over "All channels" starts 5 full builds; the 4 stale ones run to the end on a 2 or 4 core TV. |
| F6 | Medium | `RelayApp.newImageLoader` | Images share the API's OkHttp client (5 requests per host). A screen of posters from the provider's host can hold back guide and stream calls. Posters crossfade (extra frames while scrolling) and decode at full colour depth. |
| F7 | Medium | `ui/Library.kt` `Browser` grid | The poster grid has no `key` or `contentType`, so cells are not reused by kind and focus is matched by position. The category query (`titleQuery`, `pinnedVod.toSet()`) is rebuilt on every recomposition; each card parses its title twice and formats its subtitle on every recomposition. |
| F8 | Medium | `data/Ratings.kt` `saved` | One version counter for all scores. Each score that arrives recomposes every visible poster. |
| F9 | Medium | `ui/Home.kt` `resume` | Resuming an episode fetches the series first and shows nothing while it waits. On a slow connection the TV looks frozen after pressing OK. |
| F10 | Medium | `ui/TitleList.kt`, `ui/Library.kt` | Changing category replaces the grid with "Loading…" until the new list is built, then builds a new grid. Keeping the old grid dimmed would avoid the flash and the rebuild of the layout. |
| F11 | Low | `ui/Guide.kt` Start over | OK on the current programme of a catch-up channel now opens a dialog first. Watching live from the grid takes two presses where it took one. Check that "Watch live" has the first focus so a double press works. |
| F12 | Low | `player/Recovery.kt` `looksLikePlaylist` | Bytes are read as ISO-8859-1, so a UTF-8 byte-order mark is never trimmed. A playlist starting with one is treated as a transport stream; the pause buffer then fails over to direct play. |
| F13 | Low | `.github/smoke-test.sh` | The check proves the app starts, nothing else. See phase A. |

Things reviewed and found fine: the staged library load, per-row guide loading, the Live TV list hand-off, the title list cache, the player classes split out in 1.0.30.

## 3. Phase A: measure before changing (no app behaviour change)

Goal: numbers from a TV, and an emulator check that exercises the real screens.

A1. **Read a TV log** (left over as plan 1 phase 0). *Done in plan 4: see the table in PLAN.md section 11.* Owner: Settings, Diagnostics, turn on detailed logs, refresh the library, browse Live TV, Movies and the guide for five minutes, zap ten channels, then Download logs from the phone page. Fill section 11 of `docs/PLAN.md`. Decide 4.5 (saved library index) from "Read saved library" time: only build it if that is over about 8 s.

A2. **Log what is missing.** Add timing lines (always-saved level) for: time from OK on a channel to first picture; time from changing category to the list shown; history write time when over 8 ms; frames slower than 32 ms per minute while a list is scrolling (via `Choreographer` frame callback, only while detailed logs are on).

A3. **Make the emulator check useful.**
* Add `tools/fake-provider/`: a small local server that answers the Xtream calls with a generated library (for example 20,000 channels, 60,000 movies, 12,000 series, guide data) and serves one short looping test stream. No real provider data.
* Add a debug-only way to start signed in to that server (an intent extra read only when `BuildConfig.DEBUG` or a dedicated `benchmark` build type; never in the release APK).
* New CI job on an Android TV emulator image (API 30 and API 34, 2 cores, 2 GB) that signs in, waits for the load, then drives the remote with `adb shell input keyevent`: scroll Live TV 200 rows, open Movies, change category five times, open the guide grid, play a channel and zap ten times.
* Collect `dumpsys gfxinfo dev.relay.tv framestats` and Relay's own log, print a summary table in the job output, and fail on a crash or ANR. Do not fail on frame times at first; record them for three builds, then set a limit at the median plus 25%.
* Caveat to keep in the README: the emulator draws in software, so its numbers show regressions between builds, not what a TV will do.

A4. **Baseline profile.** `baseline-prof.txt` is a single wildcard for Relay's own classes. With A3 in place, generate a real profile from the scripted run (Macrobenchmark `BaselineProfileRule` on the API 34 emulator) so start-up and first scroll are compiled ahead of time on the TVs. Keep the wildcard as a fallback if generation proves unreliable in CI.

Release: none for A1; A2 to A4 ship as one release.

## 4. Phase B: response to the remote

B1. **Long press that works on a remote (F1).** First confirm on a TV. If holding OK does nothing: in `Focusable`, when `onLongClick` is set, handle it in `onPreviewKeyEvent`: on OK or Enter key-down with `nativeKeyEvent.repeatCount > 0` (or `isLongPress`) call `onLongClick` once and swallow the matching key-up so it doesn't also click. Keep `combinedClickable` for touch. Put the decision in a pure function (key, action, repeat count, already fired) with unit tests. Add a hint under the Continue watching title: "Hold OK to remove".

B2. **Cheaper focusable items (F4).** Replace `LaunchedEffect(focused)` with `Modifier.onFocusChanged`. Replace the per-item restore effect with one restorer per screen: `FocusMemory` keeps a map from key to requester for composed items (registered in a `DisposableEffect` only when `key != null`), and `restore()` asks the one matching requester. Create the scale animation only for items that can be focused now (`enabled`), and use `graphicsLayer` with a lambda so a focus change redraws without recomposing. Check with A3 that scrolled frames get faster and that focus still returns to the same item after Back from the player and from details.

B3. **Faster channel change (F3).** Use a separate `DefaultLoadControl` setting for live: `bufferForPlaybackMs` 1,000 and `bufferForPlaybackAfterRebufferMs` 3,000; leave movies at 2,000 and 4,000. If stalls rise in the log (A2), make it a setting with "Faster zapping" and "Steadier" choices. Measure OK-to-picture before and after.

B4. **History off the screen thread (F2).**
* Live: record a channel in history only after it has played for 5 s, so channels passed while zapping are not written (also keeps Recently watched clean).
* `Store.write`: keep the in-memory value update and version bump on the caller's thread, move the JSON encode and `prefs.edit()` to a single background writer that keeps only the latest value per key, and flush it in `onStop`. Test: two quick writes to one key save the second; a flush writes everything pending.
* Split the version counter: one for settings and profiles, one for favourites, one for history. Screens read only the ones they use. Progress saves during playback then recompose nothing.

B5. **Lists that give up when they are no longer wanted (F5).** Pass a `() -> Unit` cancel check (calls `ensureActive()`) into `buildLiveList` and `buildTitleList` and call it every 2,000 items. Wait 250 ms after the last letter before rebuilding for Find. Tests: a cancelled build throws and leaves the previous result; results are unchanged when not cancelled.

B6. **Visible response to every OK press (F9, F10).** Episode resume from Home shows "Opening…" on the card at once and a message if the series can't be fetched within 8 s. Category change keeps the previous grid on screen, dimmed, until the new list is ready.

B7. **Guide grid Start over (F11).** Confirm first focus is "Watch live". Consider: OK watches live as before, and holding OK (after B1) offers Start over. Owner's choice; default to keeping the dialog with first focus on "Watch live".

Release: B1 and B2 together, B3 and B4 together, B5 to B7 together.

## 5. Phase C: drawing and images

C1. **Images (F6).** Give Coil its own OkHttp dispatcher (8 total, 4 per host) built from the same client so cache and user agent stay. Turn crossfade off for grid posters on TVs (keep it for details backdrops). Allow RGB 565 for posters and logos without transparency needs (`allowRgb565(true)`), which halves decode memory and upload time. Check poster requests carry the cell size so nothing is decoded larger than it is shown.

C2. **Poster grid (F7, F8).** Add `contentType` to the Movies and Series grids and a `key` once the list builder guarantees unique ids (dedupe there, with a test fixture containing duplicates). Remember the query inputs (`pinnedVod.toSet()`, hidden category set). Parse the title once and pass the parts into `PosterCard`; remember the subtitle string per item. Give ratings a per-title state (or a version per key) so one arriving score redraws one poster.

C3. **Recheck after A3.** Use the frame numbers to decide whether more is needed (for example fixed-height poster cells so the grid never re-measures a row when a two-line title arrives). Do nothing here without a measurement that points at it.

Release: one.

## 6. Phase D: quality of life

Small features that fit how the TVs are used. Each is independent; the owner can drop any.

| # | Item | Notes |
|---|---|---|
| D1 | Last channel on start | Optional setting: opening Relay on a TV goes straight to the last live channel, as a set-top box does. |
| D2 | Sleep timer in the player | 30, 60, 90 minutes or end of programme; a 60 s warning with "Keep watching". Reuses the limit warning overlay. |
| D3 | "Are you still watching?" | After 3 auto-played episodes or 4 h of live with no key press, pause and ask. Saves the TV and the connection overnight. |
| D4 | Previous channel list | Back flips to the last channel today; holding Back (or a row in the channel overlay) shows the last 5. |
| D5 | Favourite from the list | Hold OK on a channel row toggles Favourite without opening the panel (after B1). |
| D6 | Up next card | In the last 20 s of an episode show the next title with a countdown and "Play now" / "Cancel" instead of switching silently. |
| D7 | Clear a whole row | "Clear Continue watching" at the end of the row, with a confirmation. |
| D8 | Skip back and forward sizes | Setting for the Left and Right jump in movies (10/30 s default; 5/15/30/60). |
| D9 | Byte-order mark fix (F12) | Decode the first bytes as UTF-8 and trim the mark; test with and without it. |

Release: two or three items per release.

## 7. Phase E: finish the player split (left over from plan 1, phase 7)

Only after the owner has run the player checks in `docs/TODO.md` on a TV for 1.0.30 or later, and after A3 gives a scripted play-and-zap run in CI.

| New file | Takes over | Tests |
|---|---|---|
| `player/LiveSession.kt` | `switchLive`, `playLive`, `reconnectLive`, `tryNextVersion`, `jumpToNumber`, previous channel, the live retry counters | Pure "what next" function for errors and stalls: given live or movie, tries so far, servers left, versions left, whether it ever played, return retry, next server, next version, reconnect or fail. Table-driven tests copied from today's behaviour. |
| `player/TimeshiftController.kt` | buffer start and stop, `tsSeek`, `tsGoLive`, the ticker, key handling while shifted | Uses `TimeshiftMath` (already tested). |
| `player/PlayerViews.kt` | `buildViews` | None; views only. |

One commit per extraction, one push at the end. Target for `PlayerActivity.kt`: under about 500 lines (lifecycle, listener, key dispatch, picture-in-picture).

## 8. Still open from plan 1

| Item | Where it sits now |
|---|---|
| Phase 0, read a TV log and fill the measurements table | A1 |
| 4.5, saved binary index of the library | Decided by A1 |
| Player split, second half | Phase E |
| Device checks for 1.0.24 to 1.0.31 | `docs/TODO.md`, "Check on a device"; do these before phase B so new changes aren't blamed for old ones |
| Byte-order mark in playlist detection | D9 |
| Versions button lost its count; series plot and genre come only from series info | Known changes from plan 1; say if either should be put back |

## 9. Order and size

| Phase | Content | Size | Needs the owner |
|---|---|---|---|
| A | Measurements, scripted emulator run, baseline profile | L | A TV log (A1) |
| B | Long press, cheaper focus, faster zapping, history writes, cancellable lists, feedback | L | Confirm F1 on a TV; judge zapping |
| C | Images and poster grid | M | Look at poster quality with RGB 565 |
| D | Quality of life items | M | Pick which ones |
| E | Rest of the player split | L | Player checks done first |

## 10. Risks

| Risk | Guard |
|---|---|
| Shorter live buffer causes more stalls on weak streams | Log stalls per hour before and after; setting to go back |
| Background history writer loses the last position if the app is killed | Flush in `onStop` and after each `saveProgress(finished = true)`; test the flush |
| Focus restore rewrite breaks "return to where I was" | Scripted run in A3 checks focus after Back from player and details; owner check on a TV |
| RGB 565 banding on gradients in posters | TV only, posters only; owner compares; one-line revert |
| Fake provider or benchmark sign-in leaks into the release build | Debug or benchmark build type only; CI greps the release APK for the benchmark class name and fails if present |
| Emulator frame numbers are noisy | Record three builds before setting a limit; compare medians |

## 11. Outcome

Executed in one session, one push to `main` per phase. Everything below was compiled, unit tested and started on an emulator by CI; none of it has been seen on a TV.

| Phase | Release | What went in |
|---|---|---|
| A | 1.0.33 | Frame timing and list timing in the log (`FrameWatch`, `FrameStats`), time to first picture, a `benchmark` build type that never ships (the release build fails if it contains benchmark code), a made-up provider (`tools/fake-provider`), a scripted emulator run (`tools/bench`) as a separate CI job that never blocks a release |
| B | 1.0.38 | F1 hold OK works on a remote (`OkPressTracker`, `LongPressGuard`), F4 cheaper `Focusable` (no per-item coroutines) and `FocusRestorer`, F3 live starts after 1 s of video (`BufferPlan`), F2 history and settings writes off the screen thread (`WriteBehind`, split version counters, flush on pause and stop), F5 list builds that can be cancelled and a 250 ms pause in Find, F9 "Opening…" message and an 8 s limit when resuming an episode, F10 the old grid stays up, dimmed, while a category loads. Also the benchmark job disk fix |
| C | 1.0.39 | F6 images get their own network pool (8 requests, 4 per host) and no crossfade in the grid, F7 cards keyed by id, titles parsed once, F8 per-title ratings counters. The RGB 565 idea was dropped on purpose: on Android 8 and later the loader uses hardware bitmaps, so it would change nothing |
| D | 1.0.41 | D5 hold OK favourites a channel in Live TV, D7 Clear all on Continue watching, D8 jump sizes for Left and Right, D9 byte-order mark fix in `looksLikePlaylist` |
| D (part 2) | 1.0.42 | D2 sleep timer, D3 "Are you still watching?" (`SleepTimer`, `StillWatching`, with tests) |
| Fix | 1.0.77 | Holding Up through a long poster grid had become several times slower in 1.0.38 (found by the benchmark, see below). The focus highlight is read from the interaction source again and its animation starts from an effect |

Left out: **A1** (needs a log from the TV), **A4** (a generated baseline profile needs a Macrobenchmark run; the wildcard list stays), **D4** (Back already flips to the previous channel; a list of the last five is not built), **D6** (Up next card: more player UI than can be checked without a TV), and all of **Phase E**. Phase E is gated on the player checks on a TV, which have not happened, and the benchmark drives lists and one live channel, not the retry paths the split would touch. Do it after the checks in `docs/TODO.md` pass.

What the benchmark can and cannot say: it ran on a 2-core emulator with a software renderer on a shared build server, so it is useful for telling whether a change made a screen better or worse and useless for saying how a TV feels. A few things only a person with the remote can judge: whether hold OK is recognised on this remote, whether focus lands where it should after Back, whether the 1 s live buffer is enough for this provider, and whether the dimmed grid reads as "loading".

### Benchmark numbers

The same script (`tools/bench/bench.sh`: open Home, Live TV, play a channel, zap, come back, then hold Down or Up in the channel list, the Movies grid and the Series grid) was run four times on the phase A code and four times on 1.0.77, on the CI emulator (2 cores, software rendering). Medians; the range is in brackets in the CI logs. Run to run noise is large, so only differences that show in every run are listed.

| What | Phase A code (1.0.33) | 1.0.77 |
|---|---|---|
| First picture after tuning a live channel | 1.86 s (1.84 to 1.88) | 0.93 s (0.92 to 1.10) |
| Holding Up in the channel list, 95th percentile frame | 194 ms | 34 ms |
| Starting a channel (the frames while the history write used to happen), 95th percentile | 176 ms | 137 ms |
| Holding Up through the Movies grid, frames over 100 ms in 60 presses | 4 | 2 |
| Holding Down in the Movies and Series grids, 95th percentile | 57 and 27 ms | 60 and 29 ms (no difference) |
| Coming back from the player to the channel list, 95th percentile | 88 ms | 258 ms (worse: the list now scrolls back to the channel you left, which costs one heavy frame) |
| Slow, one key at a time scrolling in the channel list, 95th percentile | 26 ms | 48 ms (within the run to run spread, 17 to 91) |

How the regression was found: 1.0.38 to 1.0.42 showed 28 frames over 100 ms in the Movies Up stretch (range 17 to 56) against 4 before. Builds of each step and of single changes were run three times each. The result: phase B alone already had it; the grid keys and dimming (phase C and B) made no difference; putting back the old `Focusable` fixed it; and so did either of two smaller changes (reading focus from the interaction source, or driving the scale animation by `animateFloatAsState`), while removing the focus registration did not. The cause is starting a coroutine from inside the focus callback. Reading focus from the interaction source and animating from an effect was the best of the variants and is what 1.0.77 does.

The lesson for later changes: this kind of difference does not show in the unit tests or the launch check, only when keys are held, so the benchmark is worth running (via **Run workflow** on a branch) before releasing changes to `Focusable`, the grids or the lists.
