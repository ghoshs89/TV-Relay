# Relay to-do list

Nothing here is started until it's asked for. Newest additions at the top of each section.

The step-by-step plan for the performance, code and UI work is in [PLAN.md](PLAN.md) (done, except the last part of the player split below). The review that followed it is [PLAN-2.md](PLAN-2.md): phases A to D are released as 1.0.33 to 1.0.42 plus a fix in 1.0.77, phase E is still open. [PLAN-3.md](PLAN-3.md) (trailers, API keys from the phone, sync between screens) is released as one build; its outcome, what was left out and the benchmark comparison are at the end of that file.

## To do

| # | Item | Notes |
|---|---|---|
| 1 | **Finish splitting the player code** | Done so far (1.0.30): kids limit timing, audio and subtitle choices, failure panel, pause bar and its arithmetic, reminder banner, number entry and the picture-in-picture ratio are separate classes with tests. `PlayerActivity.kt` went from 1,324 to 1,071 lines. Not done: live channel switching and recovery (`LiveSession`), the pause buffer controller (`TimeshiftController`) and view construction (`PlayerViews`), which hold the shared playback state and are the riskiest to move without a device. Do them after the player checks below pass on a TV. |
| 1a | Player checks on a TV for 1.0.33 to 1.0.77 | The "Check on a device" list below has what to try. Nothing from plan 2 has been seen on a TV: it is compiled, unit tested, started on an emulator and driven by the scripted benchmark, no more. |
| 1b | Sleep timer and Up next card | The sleep timer and "Are you still watching?" are in (1.0.42). Up next card (plan 2 item D6) and a list of the last five channels (D4) are not; Back already flips to the previous channel. |
| 2 | Saved binary index of the library (plan item 4.5) | Only worth it if a TV log shows start-up parsing over about 8 s. Needs a TV log first (plan phase 0). |
| 3 | Read a TV log and fill the measurements table | Plan phase 0 and section 11 were skipped because no log was available. Download logs from the phone companion after a refresh and a few minutes of browsing. |
| 4 | Real baseline profile (plan 2 item A4) | The profile in the repo is a wildcard list. A generated one needs a Macrobenchmark run on an emulator that can load a library; the benchmark job could be extended to produce it. |
| 5 | Sync, later polish (plan 3 H4 and H5) | H4: a hint under a Continue watching card that came from another screen ("Living room TV, 20 minutes ago") and an extra pull when a detail page opens for a title in progress. H5: a WebDAV transport (TrueNAS, Nextcloud) if you would rather keep the data at home. Wait until sync has run on real screens for a while. |
| 6 | Sync, pairing link from a TV | The pairing link can be copied on a phone and pasted on a TV's page. A TV can't hand it to a phone yet. Showing it as a QR code on the TV would close that gap for screens whose Wi-Fi blocks discovery. |
| 7 | Sync, benchmark stretch | The plan wanted the benchmark to run a sync round trip against a local GitHub look-alike. See the outcome in plan 3: what was and wasn't done. |

## Check on a device

Plan 3 (first build that contains it; see Done):

| Item | What to look for |
|---|---|
| Keys from the phone (F) | Settings → Library → TMDB API key (or Rotten Tomatoes scores) → **Send from my phone**. Scan, paste a good key and a bad one. The phone and the TV both say whether each was accepted; the TV window closes after a good key; scores and artwork appear on a movie page. |
| Trailers (G) | On a movie and on a series with a trailer, **Trailer** opens YouTube and Back returns to the same page. On a title without one, **Find a trailer** opens a YouTube search (G6: confirm the search lands on a sensible page on the TV). No button on a kids profile. On a TV without YouTube a QR code appears. |
| Nothing looks different after the storage change (H1) | Remove a Continue watching card, add and remove a favourite, change a setting, restart Relay: everything is as you left it. Kids viewing time still counts and still stops at the limit. |
| Turn on sync (H2) | Make the private repository and token (README, *Sync between screens*). Settings → Sync between screens → Turn on sync, scan, paste. The status says *In sync*; change a favourite and a commit appears in the repository within about a minute. Sync stays off until you do this. |
| Add a second screen (H3) | On the new TV: Settings → Join sync from another screen (or the sign-in page's *I already use Relay on another screen*). On the first: Add a screen. Check: the new TV is listed within a few seconds (does discovery work on your Wi-Fi?), both show the same six digits, Join signs the new TV in by itself and shows the same Continue watching, favourites, keys and languages. |
| Hand-over | Stop a film on one screen, open it on the other: it resumes near the same place. Remove a Continue watching card on one; it doesn't come back from the other. |
| Offline | Switch Wi-Fi off on one screen, change things on both, switch it back on: both sets of changes survive. |
| Lost screen | On a screen, **New household key**; another screen then shows that its key no longer opens the file and offers **Join again from another screen**. |
| Kids time across screens | A kids profile with 60 minutes a day: 30 minutes on each TV ends the day. |
| Speed on a TV | The sync status shows the last time and size in the logs; with Save detailed logs on, check no list or playback stutter lines appear around a sync, especially right after the app opens. |

Plans 1 and 2:

| Item | What to look for |
|---|---|
| Hold OK (1.0.38) | On Home, holding OK on a Continue watching card asks to remove it; in the Live TV list, holding OK on a channel adds or removes the favourite and shows a message. A short press still plays. If holding does nothing on this remote, say which remote it is. |
| Coming back (1.0.38) | After Back from the player or a details page, focus returns to the card or channel you came from, in Home, Movies, Series and Live TV. |
| Zapping (1.0.38) | Up and Down in a live channel show a picture sooner than before. If channels now stutter or stall right after tuning, the smaller start buffer (1 s for live) is too small for the provider; the numbers are in `player/BufferPlan.kt`. |
| Category changes (1.0.38) | Switching category in Movies or Series keeps the old grid dimmed until the new one is ready instead of flashing "Loading…". |
| Posters (1.0.39) | Scrolling a large category stays smooth, and images no longer fade in while scrolling. Colour depth was not reduced, so quality should be unchanged. |
| Jump sizes, Clear all, Sleep timer, Are you still watching? (1.0.41, 1.0.42) | Settings → Playback has the new rows; Clear all on Continue watching asks first; the sleep warning appears a minute before the end and any key adds time; the question appears after three episodes in a row. |

Older checks:

| Item | What to look for |
|---|---|
| TV performance after 1.0.22 | Refresh finishes with a rising percentage; Live TV opens and scrolls without freezing. If not, Download logs from the phone companion. |
| Continue watching on the Google TV home screen | Entries appear for a movie or episode watched past a minute; choosing one resumes it. |
| Show each channel once | HD, FHD and backup versions of a channel merge correctly with this provider's names; Versions lists them. |
| Search the whole TV guide | How long the provider's full guide takes to download, and whether On TV results look right. |
| Live TV after 1.0.24 to 1.0.31 | Zapping with Up/Down, number entry, Back flip to the previous channel, HD/FHD/backup fallback when a channel fails, pause and rewind live, the Versions list. |
| Player after 1.0.30 | A movie resumes where it stopped, next episode plays, remembered audio and subtitles per series, kids limit warning and Time's up panel, failure panel Retry and Next channel, reminder banner (OK to watch, Back to dismiss). |
| Movies and Series lists | Opening a category and My list is quick, Home rails don't jump while scrolling, Search feels instant. |
| Screens that changed in 1.0.28 | Text size and icons read well at 4K from the couch, the channel panel buttons, the Retry bar when a list fails to load, the star badge. |
| Remove and Start over (1.0.29) | Long-press or Menu on a Continue watching card removes it and its Google TV home entry; the current programme in the guide grid offers Watch live or Start over. |

## Done

| Release | What |
|---|---|
| 1.0.77 | Holding Up through a poster grid back to its old speed (found by the benchmark; see plan 2, section 11) |
| 1.0.42 | Sleep timer and "Are you still watching?" (settings, tested rules, pause panel) |
| 1.0.41 | Hold OK favourites a channel; Clear all on Continue watching; jump sizes for Left and Right; playlist byte-order mark fix |
| 1.0.39 | Poster grid and images: own image network pool, cards keyed by id, per-title ratings updates |
| 1.0.38 | Hold OK works on a remote; cheaper focus handling; live starts with a 1 s buffer; saves and history writes off the screen thread; list builds that can be cancelled; stale grid kept while a category loads; benchmark job disk fix |
| 1.0.33 | Frame and list timing in the log; benchmark build and scripted emulator run in CI |
| 1.0.31 | Lint list cleared (every kept exception explained next to the code), SearchSettings split into Search and Settings |
| 1.0.30 | Player split, part 1: kids limits, tracks, failure panel, pause bar, reminder banner, number entry, with tests; README note on the update token |
| 1.0.29 | Remove from Continue watching and Recently watched; Start over from the guide grid |
| 1.0.28 | Clearer text and icons, tidier channel panel, Retry bar when a list fails to load |
| 1.0.27 | Channels show before movies and series finish loading; big lists read at once; less memory |
| 1.0.26 | Movies, Series, Home and Search lists built in the background |
| 1.0.25 | Smoother scrolling in Live TV and the guide |
| 1.0.24 | Live TV lists built off the screen thread, play entries built only when needed |
| 1.0.23 | Repo private again; update token replaced with a read-only one limited to this repo |
| 1.0.22 | Faster loading on TVs, refresh percentage, logs that explain problems, Download logs on the phone companion |
| 1.0.20 | Batches 4 to 7 |
