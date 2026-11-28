# Relay: what the plans did, and what they found out

Plans 1 to 4 are finished and their full text is in the repository's history (`git log -- docs/PLAN.md docs/PLAN-2.md docs/PLAN-3.md docs/PLAN-4.md`). This file keeps the parts that still matter: the measurements, the benchmark results, the rules that came out of the work, and where each plan differed from what it set out to do. Later plans (5 to 7 and the work after them) are likewise only in the repository's history, and are summed up in the table below; what is open is in [TODO.md](TODO.md).

## 1. The plans in one table

| Plan | About | Released as | Left out (now tracked in TODO.md) |
|---|---|---|---|
| 1 | Live TV, Movies, Series and Home lists built off the screen thread; staged library load; type scale, icons, channel panel; remove from Continue watching; Start over; player split, part 1; lint | 1.0.24 to 1.0.31 | Player split, second half |
| 2 | Frame and list timing in the log; the scripted benchmark; hold OK on a remote; cheaper focus; 1 s live start buffer; saves off the screen thread; cancellable list builds; image pool; sleep timer; "Are you still watching?" | 1.0.33 to 1.0.42, fix in 1.0.77 | Generated baseline profile, last five channels, Up next card, player split |
| 3 | API keys from the phone; Trailer buttons; sync between screens (mergeable data, encrypted file in a private GitHub repository, pairing) | 1.0.91 | Hand-over hint, WebDAV, pairing link from a TV, sync benchmark stretch |
| 4 | Saved ready-to-use copy of the prepared library; channel text work done once per name; cheaper refreshes; stall watch false alarm; first TV log read | 1.0.108 | Phase A's cost on the first start after an update; the saved copy's channel read |
| 5 | Review of the app; a device tier so recent phones get shorter waits while TVs keep theirs; the player filling the screen on phones, touch sizes, headphones pausing; screen-thread fixes (A to Z strip, Home language rows, learned languages); quicker repeat searches; frame counting by refresh rate; guide, Search and touch stretches in the benchmark | 1.0.110 | Guide grid drawing only what is on screen, player correctness, media session, phone-shaped benchmark pass |
| 6 | All of the provider's servers, marked for use with or without a VPN and tried in the right order (written and released by another session) | 1.0.112 | |
| 7 | **Close Relay?** on Back; a clear "leave the player" prompt; updates without a token now the repository is public; README section on sharing the APK | 1.0.114 | Moving sync out of the public repository (needs the owner's screens) |
| Play on TV | A phone of the household hands a channel, film or episode to a TV that has Relay open (1.0.115); then catch-up, Start over and series pages (1.0.116). Sealed with a key from the household sync key; the TV plays from its own library under its own profile | 1.0.115, 1.0.116 | Google Cast, parked (see TODO.md) |
| Trailers and summaries | Trailers as a QR code on TVs; long summaries reachable with the remote and readable in full; "Read all" leftovers removed | 1.0.117 | |
| Safety release | From the Gemini review and ChatGPT's notes on it (26 Nov): a joining screen's untouched settings no longer overwrite the household's; each phone page serves only what its screen opened it for, and only under the TV's own address; one folder per pause-buffer recording, and trimming that never leaves a rewind in a deleted piece; picture-in-picture settings kept current; the play list let go when the player closes; the release workflow split so the signing key and write access never meet the emulator, with every action pinned | 1.0.126 | Nine larger findings, to-do 14 |
| Android reliability | Profile cleanup follows accepted sync changes; kids profile restored on screen recreation and gated by parent PIN; paused movies stay paused on return; backup avatar validation; guide rollover preserves focus; app-repo sync configurations paused | 1.0.127 to 1.0.132 | |
| Playback and Settings | Active title shown while paused; Settings grouped into seven categories; item-level sync merges so local edits don't undo remote deletions; provider request cancellation closes active HTTP reads; `.ts` addresses returning HLS play as HLS; emulator screenshot gallery and browsing study | 1.0.135 | |
| Update channel toggle | Per-screen **Update channel** toggle in About & diagnostics (Stable only vs Stable and beta pre-releases) and CI pre-release publishing | 1.0.139 | |
| Watch next and the Modern look | Built from our own code after studying NuvioTV's layout (GPL-3.0, so nothing copied), with Gemini's review of the plan: left sidebar (TV) and bottom bar (phone), Modern Home with a still hero, Up next and per-profile recommendations (TMDB recommendations matched to the provider's library, or a local ranking), dismissals synced as records older screens keep, a 7-day TMDB disk cache, new detail pages, an end-of-episode card, and trailers opened in SmartTube, Morphe, ReVanced or YouTube | Pre-release (number after publishing) | In-app or background trailers (against YouTube's terms); three benchmark passes per build (the bench job's 30-minute limit fits one) |
| Hardening, filters & telemetry | Content-type chips (Sports, News, Movies, Kids, Documentary, Music and genres) and quick language chips; serialized sync runner and main-thread store access; live background stop/re-tune and reconnect counter preservation; updater package/signature verification and dismissible install dialog; parental category checks across Home, detail, reminder and version views; PBKDF2 v2 PIN verification with 5-try cooldown; active pairing lock; 256 MB XMLTV cap; cleartext traffic disabled on external APIs; opt-in anonymous usage stats, direct crash/log uploads (`#CODE`), and Cloudflare Worker + D1 dashboard | 1.0.143 (pre-release), 1.0.144 | Optional future wire bump for pairing commitment and v2 default PIN hash |

### Notes on recent releases

**Playback and Settings (1.0.135):** [Relay 1.0.135](https://github.com/ghoshs89/TV-Relay/releases/tag/v1.0.135) passed 692 unit tests and the Android emulator suite. The signed upgrade from 1.0.126 preserved the test profile, favourite, watch entry, resume position, and settings, and the household sync migration out of the app repository was completed. [Release verification](https://github.com/ghoshs89/TV-Relay/actions/runs/45914019573)

**Android reliability:** Passed 666 unit tests and four emulator tests covering the store, kids profile recreation, playback and guide listings, with zero critical lint findings and verified upgrade from v1.0.117. [Validation run](https://github.com/ghoshs89/TV-Relay/actions/runs/45399176619)

## 2. Measurements from the TV

onn 4K Plus: 4 cores, 1917 MB RAM, 384 MB app limit, 1920x1080 at 320 dpi (960x540 dp), 60 Hz. Library: 56,019 channels, 165,672 movies, 38,104 series, 1,510 categories; saved JSON 132.7 MB.

| Measure | 1.0.22 (8 Nov) | 1.0.77 (11 Nov) | 1.0.91 (11 Nov) | After plan 4 (fill from the next log) | After plan 5 |
|---|---|---|---|---|---|
| Process start to channels on screen | 73.4 s and 70.0 s | 53.0 s | 56.4 s | | |
| Process start to all lists ready | same (one stage) | 59.0 s | 62.7 s | | |
| Read saved library | 40.7 s and 41.6 s | 27.2 s | 29.6 s | | |
| Prepare lists (slowest step) | 28.5 s (channel versions 14.8 s) | 30.1 s (13.4 s) | 32.2 s (14.4 s) | | |
| Refresh: parse again, then prepare | 56.0 s, 32.8 s | 26.4 s, 29.2 s | none in the log | | |
| Memory after load, after refresh (of 384 MB) | 152 to 160 MB, 299 MB | 150 MB, 241 MB | 151 MB | | |
| Slowest Live list build | "All channels" 259 ms | not logged | 4 ms (All channels not opened) | | |
| Movies list, all titles (off the screen thread) | not logged | not logged | 444 ms | | |
| Search (whole-guide search on) | not logged | not logged | 1.2 to 2.2 s | | |
| First picture after tuning | not logged | not logged | median 902 ms (773 to 1,333) | | |
| Real screen-thread stalls | none (2 false reports) | none | none | | |

Where a cold start went on 1.0.91: reading the six JSON files 29.6 s (47%), channel languages 7.9 s, channel versions 14.4 s, channel index 3.5 s, movies 5.1 s, series 1.2 s.

Targets set by plan 4 for the TV, still to be checked with the routine in TODO.md: channels on screen 10 s or less with the saved copy (the emulator runs suggest 11 to 15 s), all lists 15 s or less, memory after load 160 MB or less, refresh peak no higher than 241 MB.

**No phone has ever been measured.** Plan 5 adds the means (frame counting that knows the screen's refresh rate, touch stretches in the benchmark); the phone column starts with the first phone log.

## 3. Benchmark results (emulator on a build server: compare builds, never read as TV or phone speed)

Plan 2, the same script on the phase A code and on 1.0.77, medians of four runs:

| What | 1.0.33 | 1.0.77 |
|---|---|---|
| First picture after tuning a live channel | 1.86 s | 0.93 s |
| Holding Up in the channel list, 95th percentile frame | 194 ms | 34 ms |
| Starting a channel, 95th percentile | 176 ms | 137 ms |
| Holding Up through the Movies grid, frames over 100 ms in 60 presses | 4 | 2 |
| Coming back from the player to the channel list, 95th percentile | 88 ms | 258 ms (the list scrolls back to the channel you left; one heavy frame) |

Plan 3 against 1.0.77 (sync off): no stretch consistently worse; first picture 946 against 948 ms.

Plan 4, cold start on the same emulator, saved copy against JSON path:

| Library | Channels on screen, saved copy | JSON path | Speed-up |
|---|---|---|---|
| 12,000 channels, 40,000 movies, 8,000 series | 1.8 to 2.3 s | 5.2 to 5.9 s | 2.4x to 2.9x |
| 56,000 channels, 166,000 movies, 38,000 series | 2.2 to 2.9 s | 10.9 to 11.2 s | 3.9x to 5.0x |

Plan 4 also found the channel stage on the JSON path 3 to 35% slower than `main` on the emulator, where a desktop JVM showed it 30 to 40% faster. The cause was not found.

Watch next pre-release against 1.0.144 (one run each, 28 Nov, same emulator image; runs 145 and 146). The sidebar changes what the first key presses of some stretches do, so read these as "no regression", not as a speed-up:

| What | 1.0.144 | Watch next branch |
|---|---|---|
| Home browse, 95th percentile frame | 186 ms | 44 ms |
| Holding Down in Live TV, 95th percentile | 53 ms | 12 ms |
| Holding Up through Movies, 95th percentile | 221 ms | 52 ms |
| Home fling, 95th percentile | 20 ms | 7 ms |
| First picture after tuning | 1,261 ms | 1,260 ms |
| Janky frames, whole run | 32.6% | 9.4% |
| Native heap at the end | 53 MB | 68 MB |
| Cold start, channels on screen (saved copy) | 1.4 s | 0.7 s |

## 4. Rules that came out of the work

1. **A push to `main` that changes the app is a release** to every device. Docs and tools alone do not build. Work on a branch; check it with **Run workflow** (publishes nothing, uses up a build number).
2. **CI was the only Android compiler** for plans 1 to 4. Since plan 5 a session can build locally when its environment allows `dl.google.com` (the Android SDK and Google's Maven files): `./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`.
3. **Pure logic gets unit tests in the same commit.** Tests are never deleted to make a build pass.
4. **Holding a key is its own test.** The 1.0.38 slowdown (frames over 100 ms in the Movies grid went from 4 to 28 over 60 presses) passed every unit test and the launch check. It came from starting a coroutine inside the focus callback in `Focusable`. Anything that touches `Focusable`, the grids or the lists is run through the benchmark on a branch, three runs against `main`, before release.
5. **Any doubt about the saved copy of the library means JSON.** Its header carries each JSON file's size and date, each section a checksum, and `LibrarySnapshot.LOGIC` (with a hash test) changes whenever the rules that read names change.
6. **Sync is off until it is set up**, keeps safety copies before a first sync and before applying a merged file, and never blocks playback or start-up.
7. **No secrets** in the repository, commit messages, logs, backups or the benchmark build. Signing and update-token steps in `build.yml` are not touched.
8. **Text shown to the viewer** is plain and short, with the app's existing wording.

## 5. Where the plans differed from what they set out to do

| Plan | Said | Did | Why |
|---|---|---|---|
| 1 | Player split into six classes, `PlayerActivity` under 450 lines | Three classes moved out (1,324 to 1,071 lines; later features brought it to about 1,540) | The rest holds the shared playback state and was too risky without a device |
| 2 | RGB 565 for posters | Dropped | Android 8 and later use hardware bitmaps; it would change nothing |
| 3 | Removals leave a record when they happen | Each screen remembers a fingerprint of every record at the last sync; what has disappeared since is a removal | Catches removals made by code that knows nothing about sync |
| 3 | Pairing with a 6-digit code as the key | The screens swap public keys (ECDH, P-256); the six digits only prove nobody is in the middle | A 6-digit key can be guessed offline |
| 3 | A new token on one screen spreads to the others | Each screen keeps its own token; **Replace the token** on the phone page or **Join again from another screen** | A screen with an expired token can't read the file that would carry the new one |
| 3 | Backup format 3 | Unchanged (format 2); sync keeps its own safety copies | Nothing new needed to be in the backup |
| 4 | Release only if the saved copy is 4 times faster on the default benchmark library | Released on the owner's say after the numbers above | The gate was a poor test at that size: 0.8 s of every start is process start-up |
| Safety release | Fix the review's findings (critical and high) in one release | Fixed the ones confirmed in the code; request cancelling shipped in 1.0.135, and sync serialization, pairing session lock, stricter provider parsing, async store/guide reads, kids Home filtering, XMLTV size cap and PBKDF2 v2 PIN verification followed in the hardening release | Kept each release focused while revoking the old sync token straight after a backup |

## 6. Known gaps in what has been checked

* Most of plans 2 to 5 has not been seen on a TV or a phone by the people who wrote it: it is compiled, unit tested, started on an emulator and driven by the scripted benchmark. The owner has since confirmed **Close Relay?** (1.0.114) and **Play on TV** (1.0.115, 21 Nov) on a real TV and phone. The "Check on a device" lists in TODO.md are the remaining check.
* The benchmark covers lists, grids, one live channel and (since plan 5) the guide grid, Search and touch flings. It does not cover the player's retry paths, sync, or pairing.
