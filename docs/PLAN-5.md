# Relay plan 5: review, repo clean-up and the next round of work

Written after reviewing commit `291b511` (release 1.0.108). Phases 1 to 5 were then executed in part on one branch with one build at the end; **section 8 says exactly what was done and what was not**. Earlier plans are summed up in [HISTORY.md](HISTORY.md); what is open is in [TODO.md](TODO.md).

## 1. Context

The owner asked for a detailed review of the app and its documents (accuracy and UI efficiency), a list of files that can be deleted, and one combined list of what is still open across the four plans, ranked by how much it would improve the app.

What the review covered and what it did not:

| Covered (read in full) | Covered (spot checks) | Not read |
|---|---|---|
| `README.md`, `docs/TODO.md`, `docs/PLAN.md`, `PLAN-2.md`, `PLAN-3.md`, `PLAN-4.md` | `Store.kt`, `Search.kt`, `Details.kt`, `RatingsUi.kt`, `Recovery.kt`, `BufferPlan.kt`, `bench.sh`, `smoke-test.sh` | `Settings.kt`, `sync/*`, `companion/*`, `Onboarding.kt`, `LibrarySnapshot.kt`, `SnapshotIo.kt`, the tests |
| `ui/Components.kt`, `Live.kt`, `LiveBrowse.kt`, `Library.kt`, `Home.kt`, `Guide.kt`, `State.kt`, `Logos.kt`, `LongPress.kt` | README numbers against constants (stall times, retry counts, score limit, live buffer, update interval): all match | |
| `data/Catalog.kt`, `LiveList.kt`, `player/PlayerActivity.kt`, `RelayApp.kt`, `build.yml`, `build.gradle.kts`, manifest | | |

**Goal set by the owner for this plan:** everything works well on both device classes the household uses: phones with the newest processors, and TVs with slightly older, slower ones. Section 2.0 turns that into targets and a design rule; every phase in section 5 says what it does for each class and how each is checked.

Every finding below comes from reading code. None was measured or reproduced on a device or emulator in this session. Items marked **likely** are inferred from the code path and the library size in the owner's TV log (56,019 channels, 165,672 movies, 38,104 series).

## 2. Findings

### 2.0 Two device classes: what "works great" means, and the rule for getting there

What the code does today: it has one behaviour for both classes, tuned for the slow TV. The only places it tells the classes apart are the keyboard choice, the Live TV side panel, picture-in-picture, refresh-rate matching and the pause bar's touch buttons (`rememberIsTv`, `PlayerActivity.isTv`). Work is split across cores by one rule (`if cores >= 4 then 3 else 2`, in `CatalogParser.kt:78` and `Catalog.kt:286`), so an 8-core phone and a 4-core TV are treated the same. All measurements so far are from one TV and a 2-core emulator driven by key presses; **nothing has ever been measured on a phone or with touch**.

| | TV (onn 4K Plus class: 4 slow cores, 2 GB, 384 MB app limit, 60 Hz, remote) | Phone (newest processors: 8 fast cores, 8 GB or more, 90 to 120 Hz, touch) |
|---|---|---|
| What goes wrong on this class | Anything on the screen thread shows as a freeze; per-item cost while holding a direction key; long start-up; memory during a refresh | Almost never raw speed. Instead: dropped frames at 120 Hz that the logs don't count, delays that were added to protect the TV, small touch targets, the player not using the whole screen, missing phone integrations |
| Targets | Channels on screen 10 s or less after a cold start; no screen-thread stall over 100 ms in any list, grid or the guide; 95th percentile frame under 34 ms while holding a key; first picture 1 s or less; memory peak no higher than 241 MB | Cold start to channels 3 s or less with the saved copy; 95th percentile frame within two display frames (17 ms at 120 Hz) during a fling; list and search results inside 150 ms of the last touch; every touch target 48 dp or more; video fills the screen with no system bars |
| How it is checked | TV log (M4 routine), benchmark key stretches | Phone log with the same routine, new touch stretches in the benchmark on a phone-shaped emulator |

**Design rule (new, pure, tested): one `DeviceTier`, decided once at start.** A small object in `data/DeviceTier.kt` built from core count, the app memory limit, `isLowRamDevice`, TV or not, and the display refresh rate. It hands out the handful of numbers that today are fixed constants. The TV tier returns exactly today's values, so TV behaviour cannot change by accident; the phone tier relaxes them.

| Knob | Today (both classes) | TV tier | Phone tier | Where |
|---|---|---|---|---|
| Lists parsed or read at once | 3 (2 under 4 cores) | same | cores minus 2, at most 6 | `CatalogParser.kt:78`, `Catalog.kt:286` |
| Wait before a channel row asks for its guide | 350 ms | same | 100 ms | `Live.kt:455` |
| Wait before a guide grid row loads | 120 ms | same | 40 ms | `Guide.kt:219` |
| Guide requests at once (rows, grid) | 3 and 4 | same | 6 and 8 | `Catalog.kt:1043,1105` |
| Pause after typing: Find, Search | 250 ms, 350 ms | same | 120 ms, 150 ms | `Live.kt:209`, `Search.kt:75` |
| Image requests at once (total, per server) | 8 and 4 | same | 16 and 6 | `RelayApp.kt:153` |
| Poster fade-in in grids | off | off | on (cheap on a fast GPU, looks better under touch) | `RelayApp.kt:159` |
| Overdue refresh waits after start-up | 20 s | same | 5 s | `Freshness.startDelay` |
| Saved-copy write delay and priority | 5 s, background | same | 2 s, background | `Catalog.kt:346` |
| "Slow frame" in logs | over 32 ms | same | over two display frames | `FrameStats.kt` |

Rules for the tier: no knob changes what is shown, only when; every knob has a test that the TV tier equals today's constant; the tier and its inputs are written in the log header so a log says which values were in force. Anything that is simply wrong on both classes (section 2.1) is fixed for both, not tiered.

### 2.1 UI efficiency (both classes; worst on the TV)

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| U1 | High, likely | `ui/Live.kt:311` | The A to Z letter strip calls `LiveBrowse.sortKey(ch)` for every channel in the list, on the screen thread, inside `remember(channels)`. That is the regex version (`ChannelKeys.sortKey`), not the precomputed key in `Catalog.sortKeys`. With "All channels" sorted A to Z this is about 56,000 regex runs in one frame. | Build the letter index inside `buildLiveList` (it already has the keys when sort is 2) and carry it on `LiveResult`. |
| U2 | High, likely | `data/Catalog.kt:771` `learnLanguage`, called from `ui/Details.kt:82,195` | Opening any title with a TMDB key calls `learned.put(key, g) != g`, which is true the first time even when the learned language equals the one already guessed. It then bumps `langVersion` and writes `learned_langs.json` (up to 5,000 entries) on the calling thread. `langVersion` is a key for: the Live TV list build, the countries list, every Home language row, and `titleGeneration`, which throws away all cached Movies and Series lists. So one detail page visit makes the grid behind it rebuild (444 ms for all titles on the TV). | Bump only when the group really differs from `mvLang`/`srLang` or the earlier learned value. Write the file on `Dispatchers.IO`. |
| U3 | High, likely | `ui/Home.kt:459,517` `newIn` | Each "New in <language>" row is worked out on the screen thread. It walks `moviesByAdded` and `seriesByAdded` until 30 matches; for a small language group that is all 204,000 titles. Each test calls `movieLang(id)`, which builds a new string (`"m:$id"`) per title. It reruns on every `langVersion` change (three during a load, plus U2). | Precompute newest per language in `MovieStage`/`SeriesStage` (index lists, cheap), or build the rows with the same background pattern as `rememberTitleList`. Key `learned` by kind and int id so lookups allocate nothing. |
| U4 | Medium, likely | `ui/Guide.kt:297` | Each guide row composes every programme in an 11 hour window as a `Focusable` (each with an interaction source, an `Animatable`, a `LaunchedEffect` and a focus registration). About 8 rows on screen times 20 to 40 programmes is several hundred focusables, when about 5 per row are visible. This is the same per item cost that plan 2 (F4) found to matter, and the benchmark has no guide stretch, so it has never been measured. | Compose only programmes that intersect the visible time range plus one screen either side (derive from `offsetMin` in half hour steps). Add a guide stretch to the benchmark first. |
| U5 | Medium | `ui/Live.kt:449` `ChannelRow` | Every row that scrolls into view starts a coroutine with `delay(350)`, then `loaded++` recomposes it. The progress bar reads the clock during composition. | Acceptable as is; revisit only if the benchmark's `live-hold-down` stretch points here. Low priority. |
| U6 | Medium | `ui/Search.kt:74`, `data/SearchText.kt` | Search took 1.2 to 2.2 s on the TV (PLAN-4 section 1). Every query normalises all 260,000 names again. | Cache normalised names per library generation (built lazily on first search, off the screen thread). Memory cost about 10 to 15 MB; check against the 384 MB limit (151 MB in use). |
| U7 | Low | `data/Catalog.kt:848` `buildChannelIndex` | Copies the version key and quality maps (56,000 entries each) even when there are no renames. Part of the 1.5 s saved copy channel read noted in PLAN-4. | Use the raw maps directly when `renames` is empty, as `sortKeys` and `hd` already do. |
| U8 | Low | `ui/Library.kt:80,128,229` | Poster subtitle string is formatted on every recomposition; the category column has no item keys. | Remember the subtitle per item; add `key = { it.id }`. |

Reviewed and found sound: `Focusable` after the 1.0.77 fix, `fadeEdges` without an offscreen layer, the Live list build off the screen thread with cancellation, lazy play items, staged install, the image loader's own request pool, per title rating state.

### 2.2 Accuracy (behaviour)

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| B1 | Medium | `player/PlayerActivity.kt:812` `handleError` | A movie or episode on an account with one server gets no automatic retry: the first playback error of any kind goes straight to the failure panel. Live gets three reconnects. A short network drop mid film stops playback. | One automatic retry at the same position for network class errors before showing the panel. Put the decision in the pure "what next" function phase E already plans. |
| B2 | Medium | `PlayerActivity.kt:1120,1131` | Leaving the player (Home key) on a live channel without the pause buffer only pauses it. Coming back calls `player.play()` on a stale connection, so the viewer waits for the 20 s stall watchdog. With the pause buffer it re tunes at once. | In `onStart`, re tune live in both cases. |
| B3 | Low | `PlayerActivity.kt:1126` | `onStart` calls `player.play()` even if the viewer had paused, or while the "Are you still watching?" panel is up. | Resume only if it was playing when stopped and no panel is showing. |
| B4 | Low | `ui/Live.kt:265` | A pinned category and the same category under its country share the focus key `lc:<id>` (their lazy keys differ). Focus memory can return to the wrong one of the two. | Use `row.lazyKey` as the focus key. |
| B5 | Low | `ui/Home.kt:182` | "Start on last channel" builds its list from the whole category without the restriction filter that `jumpToNumber` applies. The channel itself came from this profile's history, so exposure is limited to Up/Down inside that category. | Filter through `catalog.restrictions(...)`. |
| B6 | Info | `data/Catalog.kt:784` | When more than 5,000 learned languages exist, an arbitrary 5,000 are kept (hash order, not oldest first). | Leave, or keep insertion order. |

### 2.2a Phone-specific findings

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| P1 | High, verify on the phone | `player/PlayerActivity.kt`, `res/values/themes.xml` | The player never hides the system bars. It relies on `android:windowFullscreen`, which is deprecated, and with target SDK 35 Android 15 draws apps edge to edge. No code calls the insets controller, and nothing handles the camera cut-out. Likely result on a new phone: status or gesture bar over the video, and overlays (channel name, stream info, Channels button, pause bar) placed with fixed pixel margins that can sit under the cut-out. | Immersive mode through `WindowInsetsControllerCompat` (hide bars, swipe to show), cut-out mode set on purpose, and overlay margins taken from the safe insets. No effect on TVs. |
| P2 | Medium | `player/PlayerActivity.kt`, overlays built in code | Overlay sizes and margins are raw pixels (`setPadding(36, 18, …)`, `setMargins(48, 40, …)`). On a 1080p TV (density 2) and a phone (density 2.6 to 3.5) they come out at different physical sizes; on a dense phone the margins shrink to about 14 dp. | Convert to dp once through a helper. Pure layout; check on both. |
| P3 | Medium | `ui/Live.kt:417` `Chip`, `Components.kt` `PillButton(compact)`, the A to Z strip | Touch targets are about 32 dp tall (13 sp text plus 7 or 8 dp padding); the A to Z letters are narrower still. Fine for a remote, small for a thumb; the guideline is 48 dp. | A minimum touch size on the phone tier only (`Modifier.sizeIn(minHeight = 48.dp)` through `Focusable`, visual size unchanged on TV). |
| P4 | Medium | `PlayerActivity.buildPlayer` | No `setHandleAudioBecomingNoisy(true)`: pulling out or disconnecting headphones keeps the sound going through the phone's speaker. No media session, so Bluetooth headset buttons, the lock screen and the picture-in-picture window have no play or pause control. | Turn on becoming-noisy handling (one line, both classes). Add a Media3 `MediaSession` and picture-in-picture actions (needs the `media3-session` dependency; decide in Q5). |
| P5 | Medium | `data/FrameStats.kt:37`, `FrameWatch.kt:52` | Frame counting assumes 60 Hz: "slow" means over 33.4 ms. On a 120 Hz phone a frame that takes 20 ms is two missed refreshes and is logged as fine. So even with detailed logs on, the phone would report itself smooth. | Thresholds from the display's refresh period (tier knob above); log the refresh rate with each summary. |
| P6 | Medium | `tools/bench/bench.sh`, `build.yml` bench job | The benchmark is D-pad only, on one small emulator. No fling, no tap, no phone-shaped screen. Touch scrolling takes a different path from key scrolling (no focus changes, continuous motion, many more rows per second), so the 1.0.38 kind of regression could happen on phones and never be seen. | A second benchmark pass on a phone profile with `adb shell input swipe` flings in Live TV, Movies, Home and the guide, reported separately. |
| P7 | Low | `ui/Library.kt:257`, `Live.kt:243`, `Guide.kt` | Layout switches at one width (900 dp) and posters are a fixed 150 dp. A phone in landscape is about 800 to 930 dp wide and only 360 to 410 dp tall, so the top bar, toolbar and letter strip leave four or five channel rows. | On short screens (height under 480 dp): fold the Live TV toolbar and letter strip into one row, and let the top bar scroll away with the list. Verify on the phone before building. |
| P8 | Low | `player/` | No touch gestures in the player beyond the stock controller (no double-tap to skip, no drag to seek on the pause bar). | Optional, after P1 to P4: double-tap left and right uses the existing jump sizes. |

### 2.3 Security and set-up

| # | Sev | Finding | Fix |
|---|---|---|---|
| S1 | High | `relay-sync.bin` is in this repository, with two "Relay sync" commits, and `build.yml` was changed (`9ad01ef`) to ignore it. So sync is pointed at the app's own repository. The README tells the owner to use a separate private repository. The consequence: every screen holds a token with Contents write on the repository whose `main` branch builds and auto installs on every device. A leaked or extracted sync token can push app code that the TVs then install. | Make the separate `relay-sync` repository as the README says, turn sync on there from one screen, re join the others, then delete `relay-sync.bin`, remove its line from `paths-ignore`, and delete the old token. Owner action; no app change. |
| S1, who does what | | Owner, in this order: (1) create the private `relay-sync` repository with a README; (2) make a fine-grained token for it, Contents read and write only; (3) on every screen **Stop syncing on this screen**, then turn sync on from one screen with the new repository and token and join the others; (4) delete the old token on GitHub. The executor cannot do these: its GitHub access covers only `ghoshs89/tv-relay`, tokens are made in the owner's account, and step 3 happens on the home Wi-Fi. Executor, only after step 3: (5) delete `relay-sync.bin`, remove its `paths-ignore` line, fix the README and TODO. Deleting the file earlier makes a still-syncing screen upload it again. | Step 5 changes `build.yml`, which is not ignored, so merging it to `main` publishes a release; ship it with phase 2 to avoid an empty one. No data is lost: each screen keeps its own copy and the first one uploads it. |
| S1, state on 12 Nov | | The new repository exists: **`ghoshs89/Relay-TV_Sync`**, private, pushed to once (so it is not empty). Note the owner name is `ghoshs89`; the name given in chat, `ghosh89`, has a letter missing and would be refused by the phone page. The owner has made a fine-grained token for it. **The token is deliberately not written here or anywhere in the repository** (ground rule: no secrets in files, commits or logs). It could not be tested from the executor's session, so it is unverified. On 12 Nov the new repository held only its README, so no screen had moved yet: the app itself checks it when sync is turned on (repository private, token can write) and says so on the phone and the TV. | Because the token was pasted into a chat, treat it as exposed: after sync is running on the new repository, make a replacement token, paste it with *Replace the token* on the phone page, re-join the other screens, and delete this one. Steps 1 and 2 of S1 are done; 3 and 4 remain with the owner, 5 with the executor after 3. |
| S2 | Info | Dead code is not stripped: `proguard-rules.pro` keeps all of `dev.relay.tv.**`. | Fine at this size; just means unused functions (section 3.3) ship. |

### 2.4 Document accuracy

| Doc | Line | Problem |
|---|---|---|
| `docs/TODO.md` | row 1 | Says `PlayerActivity.kt` is 1,071 lines. It is 1,168 now (sleep timer and still watching were added to it). |
| `docs/TODO.md` | row 1a | "1.0.33 to 1.0.77" should run to 1.0.108. Row numbers jump (1, 1a, 1b, 4, 5, 6, 7), and PLAN-4 refers to rows by numbers that no longer exist. |
| `docs/PLAN.md` section 11 and `PLAN-4.md` section 3 | tables | The same measurements table twice; the "After plan 4" column is empty in both. Keep one. |
| `docs/PLAN.md` | section 1 | "about 100,000 movies": the log says 165,672. |
| `docs/PLAN-3.md` | sections 6 and 9 | Scratch branch lists are out of date (five more now, section 3.2). |
| `README.md` | Sync section | Describes a separate repository; the real set-up differs (S1). |
| `README.md` | Project layout | `docs/` is described as banner and screenshots only; `app/src/benchmark` and `.github/` are not listed. |
| `PLAN.md`, `PLAN-2.md` | throughout | Line numbers and file names refer to code that has since moved (`SearchSettings.kt`, `ui/LiveList.kt`). Harmless as history, misleading as instructions. |

README behaviour claims that were checked against code all hold.

## 3. What can be deleted

### 3.1 Files

| File | Delete? | Condition |
|---|---|---|
| `relay-sync.bin` | Yes | After sync is moved to its own repository (S1). Deleting it first would make the screens treat the file as new and upload again. Also remove `'relay-sync.bin'` from `paths-ignore` in `.github/workflows/build.yml`. |
| `docs/PLAN.md`, `docs/PLAN-2.md`, `docs/PLAN-3.md`, `docs/PLAN-4.md` | Yes, replaced by one file | All four are executed. Fold what still matters into one `docs/HISTORY.md` (about 150 lines): the measurements table, the benchmark tables, the 1.0.38 regression lesson, deviations from plan 3 and 4. 1,124 lines become about 150. Owner's choice (section 6, Q2). |
| `gradlew.bat` | Optional | Only if nobody builds on Windows. Saves nothing that matters; default is keep. |

Not deletable, though they may look it: `app/src/test/.../LegacyText.kt` and `TextCorpus.kt` (the golden test's frozen reference), `app/src/main/baseline-prof.txt`, `tools/bench/uidump.py` and `uitap.py` (called by `bench.sh`), `tools/icon/build_icon.py`, `mipmap-*/ic_launcher_round.png` (manifest `roundIcon`), `docs/banner.png` and `docs/screenshots/*` (README).

### 3.2 Remote branches (18, all scratch)

`p2`, `p2base`, `p2r`, `p2s`, `p3base`, `p4base`, `p4base-big`, `pB`, `pC`, `pE1`, `pE2`, `pFA`, `pFB`, `pFC`, `plan3`, `plan4`, `plan4-big`, `plan4-exec`. Earlier sessions could not delete them; do it on GitHub (Branches page). `claude/loving-hopper-jtzdvb` goes when this work is merged or dropped.

### 3.3 Dead code inside kept files

`Catalog.gridEpg`, `Catalog.archive`, `Catalog.groupChannels`, `Freshness.CHECK_EVERY_MS`, `Langs.DEFAULT_FEATURED`. No caller in main or benchmark code. Check tests before removing.

## 4. Everything still open, from all four plans, in one list

| # | Item | From | Blocked by | Value |
|---|---|---|---|---|
| O1 | Device checks: M4 routine, plan 3 checks, plans 1 and 2 checks | TODO "Check on a device" | Owner and a TV | Highest. 85 releases of player, focus, sync and start up work have never been seen on a TV. Fills the empty "After plan 4" column. |
| O2 | Phase A cost on first start after an update (3 to 35 % slower on the emulator, cause not found) | PLAN-4 outcome | O1 log | Medium, once per update |
| O3 | Saved copy channel read (about 1.5 s of 2.2 to 2.9 s) | PLAN-4 outcome | none (U7 is part of it) | Medium |
| O4 | Player split, second half: `LiveSession`, `TimeshiftController`, `PlayerViews` | PLAN phase 7, PLAN-2 phase E, TODO 1 | O1 player checks | Medium for the viewer, high for safe future player work |
| O5 | Up next card with countdown | PLAN-2 D6, TODO 1b | O1 | Medium, visible |
| O6 | Last five channels list | PLAN-2 D4 | pairs with O4 | Low |
| O7 | Generated baseline profile | PLAN-2 A4, TODO 4 | Macrobenchmark module in CI | Medium on TVs (start up and first scroll) |
| O8 | Sync: pairing link shown as QR on a TV | PLAN-3, TODO 6 | none | Medium if Wi-Fi blocks discovery, else low |
| O9 | Sync: hand over hint and extra pull on detail open | PLAN-3 H4, TODO 5 | sync used on real screens | Low to medium |
| O10 | Sync: WebDAV transport | PLAN-3 H5 | owner wants it | Low |
| O11 | Sync benchmark stretch against a GitHub look alike | PLAN-3 5.7, TODO 7 | none | Low (engine is covered by tests) |
| O12 | Refresh parse at lower priority while browsing | PLAN-4 section 11 | O1 log | Low to medium |
| O13 | Frame time limit in the benchmark (median plus 25 %) | PLAN-2 A3 | three stable runs | Low |
| O14 | Versions button count; series plot and genre in lists | PLAN-2 section 8 | owner says if wanted | Low |

## 5. Recommended work, in order

Ranked by impact on a TV against effort and risk. One release per phase unless the owner prefers one at the end. Rules of plans 1 to 4 still apply: pure logic gets tests, CI is the compiler, benchmark on a branch (three runs against `main`) before anything that touches lists, grids or `Focusable`, no secrets, signing and update token steps untouched.

| Phase | Content | Gain on the TV | Gain on the phone | Size | Risk | Needs the owner |
|---|---|---|---|---|---|---|
| **0** | Owner actions, no code: O1 device checks and a log **from the TV and from the phone** (same M4 routine on each); S1 move sync to its own repository; delete the 18 branches | First real numbers since plan 4 | First numbers ever | | none | Yes |
| **1** | Measure both classes: P5 refresh-aware frame counting; P6 phone pass in the benchmark (flings and taps on a phone profile); new key stretches `guide-open`, `guide-hold-down`, `guide-hold-right`, `search-type`, `detail-open-and-back`; device tier inputs in the log header. No behaviour change | Guide grid and search measured for the first time | Everything measured for the first time | M | Low (tools and logging) | No |
| **2** | Screen-thread fixes for both: U1, U2, U3, U7, U8, B4, B5 | Removes three stalls that hit every session on the 56,000 channel library | Same stalls are shorter there but still drop frames at 120 Hz | S to M | Low. U1 and U3 move work into builders that already have tests | No |
| **3** | `DeviceTier` (section 2.0): the object, its tests, and the knobs wired one at a time | None by design: TV tier equals today's constants, proved by tests | Faster start (more lists at once), quicker guide text, search and Find, earlier refresh | M | Low for the TV; medium for the phone tier values, which are first guesses to be tuned from the phase 1 numbers | Phone log after release |
| **4** | Phone player and touch: P1 immersive and cut-out, P2 dp sizes, P4 becoming-noisy (and media session if Q5 is yes), P3 touch targets, then P7 if the phone check confirms it | None; guarded by `isTv` or harmless there | The player and the toolbars feel made for a phone | M | Medium: window and inset code differs by Android version; check on Android 12, 14 and 15 or later | The phone |
| **5** | Guide grid and search: U4, U6, each kept only if its phase 1 stretch improves on both the key and the touch pass | Largest remaining per-item cost | Smoother flings in the guide; search inside the 150 ms target | M | Medium (focus inside the guide grid) | Look at the guide on both |
| **6** | Player correctness: B1, B2, B3, as a pure "what next" function with table tests (the same function phase E needs) | Fewer dead ends in playback | Same; B2 and B3 matter most on phones, which leave and return to apps constantly | S to M | Medium; player code cannot be checked without a device | O1 player checks first |
| **7** | O4 player split, then O5 Up next card and O6; P8 double-tap skip rides along | Visible features, safer future work | Same | L | High without device checks | O1 done |
| **8** | O7 baseline profile generated from both benchmark passes; O2 and O3 using the TV log; O12 if the log shows the refresh hurting browsing | Start-up and first scroll compiled ahead of time; matters most here | Smaller gain | M | Low | Logs from phase 0 |
| **9** | Sync polish: O8 QR from a TV, then O9 | | | S to M | Low | Sync running on two screens |
| **Docs** | With phase 2: fix the TODO and README lines in 2.4, fold the four plans into `docs/HISTORY.md` (if Q2 is yes), renumber TODO, remove dead code in 3.3, add a phone column to the measurements table and a phone list to "Check on a device" | | | S | none | Q2 |

Why this order: phase 1 comes first because half of the goal (phones) has no measurements at all, and tuning the tier without them would be guessing. Phase 2 fixes what is wrong on both classes and needs nobody's time but the executor's. Phase 3 is the mechanism that lets the phone go faster without touching the TV. Phase 4 is the largest visible gain on phones and cannot affect TVs. Phases 6 and 7 touch the player's core paths, which every plan so far has gated on device checks that have not happened; that gate stays. Not recommended now: O10, O11, O13, O14.

**Guard that applies to every phase:** a change is released only if no benchmark stretch is consistently worse on *either* pass (key pass standing in for the TV, touch pass for the phone), three runs each against `main`. A gain on one class never pays for a loss on the other; where the two pull apart, the difference goes into `DeviceTier`.

### Files each phase touches

| Phase | Files |
|---|---|
| 1 | `data/FrameStats.kt`, `data/FrameWatch.kt`, `FrameStatsTest`, `RelayApp.kt` (log header), `tools/bench/bench.sh` (touch pass and new stretches), `tools/bench/report.py`, `.github/workflows/build.yml` bench job only (second emulator profile; signing and update-token steps untouched) |
| 2 | `data/LiveList.kt` (letter index on `LiveResult`), `ui/Live.kt`, `data/Catalog.kt` (`learnLanguage`, learned map keys, newest per language in the stages, `buildChannelIndex`), `data/LibraryDerive.kt`, `ui/Home.kt`, `ui/Library.kt`; tests `LiveListTest`, `RefreshReuseTest`, `LibrarySnapshotTest` (if the saved copy gains the per language lists, bump its format; `LOGIC` is unaffected) |
| 3 | new `data/DeviceTier.kt` and `DeviceTierTest`; call sites listed in the knob table of section 2.0 |
| 4 | `player/PlayerActivity.kt`, `player/TimeshiftBar.kt`, `player/FailurePanel.kt`, `player/ReminderBanner.kt` (dp helper, insets), `res/values/themes.xml`, `ui/Components.kt` (`Focusable` minimum touch size), `ui/Live.kt`; `app/build.gradle.kts` only if Q5 adds `media3-session` |
| 5 | `ui/Guide.kt`, `data/SearchText.kt`, `ui/Search.kt` |
| 6 | `player/Recovery.kt` (new pure decision), `player/PlayerActivity.kt`, `RecoveryTest` |
| 7 | new `player/LiveSession.kt`, `TimeshiftController.kt`, `PlayerViews.kt`; `PlayerActivity.kt` |
| 8 | new Macrobenchmark module, `build.yml` bench job, `app/src/main/baseline-prof.txt` |
| 9 | `ui/SyncUi.kt`, `sync/Pairing.kt`, `companion/CompanionServer.kt` |

Existing code to reuse: `rememberTitleList` and `TitleListCache` (background list pattern for U3), `rankSorted` and `filterChecked` in `LiveList.kt`, `QrCode` (O8), `StallWatchdog` and `PlaybackDiagnosis` (B1), `FrameWatch`/`FrameStats` (new benchmark stretches).

## 6. Decisions for the owner

| # | Question | Default |
|---|---|---|
| Q1 | Is sync meant to live in the app repository? | No: move it (S1). |
| Q2 | Fold the four executed plans into one short history file and delete them? | Yes. |
| Q3 | One release per phase, or one at the end as in plans 3 and 4? | Per phase for 1 to 6 (small, easy to roll back), one for phase 7. |
| Q4 | Start phases 1 to 3 before the device checks? | Yes; they do not touch the player's core paths. Phases 6 and 7 wait. |
| Q5 | Add a media session (lock screen, headset buttons, picture-in-picture controls on phones)? It needs one more Media3 library in the APK. | Yes. |
| Q6 | Which phone, and which Android version, is the reference? The plan assumes a recent flagship on Android 14 or later with a 120 Hz screen. | As assumed; say if it differs, since P1 depends on the Android version. |

## 7. Verification (when the work is executed)

1. Pure changes: unit tests locally, then **Run workflow** on the branch (publishes nothing): tests, lint, release build, signature, no benchmark code, emulator start.
2. Benchmark, two passes, three runs each on the branch against three on `main`: the key pass (existing emulator, stands in for the TV) and the touch pass (phone profile, flings and taps). No stretch may be consistently worse on either. Limit of this check: both passes run on a build server with a software renderer, so they show regressions between builds, not how either device feels.
3. `DeviceTier`: a test per knob that the TV tier equals today's constant; a test that the tier chosen for the onn 4K Plus inputs from the log (4 cores, 384 MB, TV, 60 Hz) is the TV tier and for a flagship phone's inputs is the phone tier; the tier line present in the log header on both devices.
4. On the TV after phase 2: no "Channel list for … took" warnings tied to opening a detail page; the Movies list is not rebuilt after each detail visit; `StallWatch` quiet when switching Live TV to A to Z on All channels. After phase 3: start-up, list and first-picture numbers unchanged from the phase 0 log (the tier must be invisible there).
5. On the phone, same M4 routine with detailed logs on, plus: fling Live TV, Movies, Home and the guide; type in Search and Find; open a film, check no system bar or cut-out covers the video or the overlays, rotate between the two landscape directions, leave with Home (picture-in-picture) and come back; unplug or disconnect headphones during playback (P4); tap every toolbar chip and A to Z letter with a thumb (P3). Compare frame summaries before and after phase 3.
6. Both devices: sort All channels A to Z; open a movie and press Back; scroll Home with a small language row enabled; scroll the guide grid for 20 seconds.
7. Player phases: the "Check on a device" player list in `docs/TODO.md`, plus pulling the network for 5 seconds during a movie (B1) and leaving then returning on a live channel with the pause buffer off (B2), on the TV and on the phone.

## 8. Outcome

Executed on branch `claude/loving-hopper-jtzdvb`, one build at the end, as the owner asked. Nothing was pushed to `main`, so nothing was released.

### What was done

| Phase | Item | What changed |
|---|---|---|
| 1 | P5 | `FrameStats` counts frames later than two display frames, from the screen's real refresh rate; the log line says so on screens faster than 60 Hz and is unchanged at 60 Hz. The device line in the log now has the refresh rate. |
| 1 | P6, in part | `tools/bench/bench.sh` gained stretches after the existing ones: `guide-open`, `guide-hold-down`, `guide-hold-right`, `guide-fling`, `live-fling`, `movies-fling`, `home-fling`, `search-type`. They run on the existing emulator. **Not done:** a separate phone-shaped emulator pass, and `detail-open-and-back`. The new stretches were written without an emulator to try them on; their first real run is the build at the end. |
| 2 | U1 | The A to Z letter strip is built with the list, off the screen thread (`letterIndex` in `data/LiveList.kt`, on `LiveResult.letters`). |
| 2 | U2 | `Catalog.learnLanguage` redraws lists only when a title really moves to another language group, and saves its file off the screen thread (`data/LearnedLangs.kt`). |
| 2 | U3 | "New in <language>" rows on Home are built off the screen thread and kept while scrolled away; looking up a learned language no longer builds a text key per title. |
| 2 | U7 | `buildChannelIndex` copies nothing when there are no renames. |
| 2 | U8, in part | Poster subtitles are remembered per card. Keys on the category column were **not** added: a provider that repeats a category id would crash a keyed list. |
| 2 | B4, B5 | Pinned categories have their own focus key; "Start on last channel" obeys the profile's restrictions. |
| 3 | DeviceTier | `data/DeviceTier.kt` with every knob in the table of section 2.0 wired. `DeviceTierTest` pins the standard tier to the numbers TVs used before. One addition to the table: a phone that is not fast enough for the fast tier still gets touch-size buttons. The tier and its numbers are in the log header. |
| 4 | P1 | The player hides the system bars on phones, keeps clear of a camera cut-out (black there, like a letterbox), and puts the bars away again after a dialog. |
| 4 | P2 | Player overlay padding and margins scale with screen density (`tvPx`); on a 1080p TV the numbers are exactly what they were. |
| 4 | P3 | Buttons, list rows and toolbar chips are at least 48 dp tall on touch screens. On TVs nothing changes. |
| 4 | P4, in part | Playback pauses when headphones disconnect, on phones only (on a TV a sound bar switching inputs can look the same). **No media session:** the player already pauses when it leaves the screen, so lock-screen and headset controls would have nothing to control except picture-in-picture; left for a later decision (Q5). |
| 5 | U6 | Search keeps the library's names in ready-to-compare form (`NormalizedNames`), prepared on the first search after a library loads. Cost: one more copy of every name in memory, estimated 15 to 25 MB on the owner's library, not measured. |
| Docs | | Plans 1 to 4 folded into `HISTORY.md`; this file; `TODO.md` rewritten with a phone check list; README corrections; five unused functions and constants removed. |

### What was not done, and why

| Item | Why |
|---|---|
| S1 step 5 (delete `relay-sync.bin`, remove its `paths-ignore` line) | The screens had not moved to `ghoshs89/Relay-TV_Sync` (it held only a README). Deleting the file first would make a screen upload it again. Do it after sync runs on the new repository. |
| U4 (guide grid composes only what is visible) | The plan keeps it only if a benchmark stretch improves, which needs builds before and after; with one build that comparison was not possible. The stretches to measure it now exist. |
| P7 (short-screen layout), P8 (double-tap skip) | P7 waits for a look at the phone, as planned. P8 was optional. |
| Phases 6 and 7 (player correctness B1 to B3, player split, Up next, last five channels) | Gated on the device checks, which have not happened. |
| Phase 8 (generated baseline profile, plan 4 leftovers), phase 9 (sync polish) | Not started. |
| Benchmark comparison, three runs against `main` on both passes | One build was asked for. The guard in section 5 is therefore **not met** for this change set: frame numbers from the single run can be read against the tables in HISTORY.md, but one run does not separate a regression from noise. |

### How it was checked

* Built and tested on the session's machine (Android SDK 35, Gradle 8.11.1): 571 unit tests pass (547 before, 24 new: `DeviceTierTest`, `LearnedLangsTest`, `NormalizedNamesTest`, and additions to `LiveListTest`, `FrameStatsTest`, `FreshnessTest`), lint has no crash-class issue, the release and benchmark APKs build and shrink.
* Not checked: anything on a screen. No emulator ran in the session, and no TV or phone has seen these changes. The immersive player (P1) depends on the Android version and is the change most in need of a look on the phone.

### Fast-tier numbers are first guesses

The phone tier's waits and limits were chosen by judgement, not from measurements, because there are none from a phone yet. The first phone log (routine in TODO.md) is what should set them.
