# Relay to-do list

Nothing here is started until it's asked for. Newest additions at the top of each section.

Plans 1 to 4 are finished; what they did, measured and learned is in [HISTORY.md](HISTORY.md). [PLAN-6.md](PLAN-6.md) (all of the provider's servers, with or without a VPN) is released; its outcome is at the end of that file. [PLAN-5.md](PLAN-5.md) is the plan before it: a review of the app and its documents, and the work to make Relay good on both fast phones and slower TVs. Its section 8 says which parts are built (on a branch, not released yet) and which are not.

## For you to do (nobody else can)

| # | What | Notes |
|---|---|---|
| A | **Move sync out of this repository** | Sync is pointed at this repository (`relay-sync.bin` is here). That gives every screen a token that can write to the repository whose `main` branch builds and installs on every device. The new private repository `ghoshs89/Relay-TV_Sync` exists. Still to do: on every screen **Stop syncing on this screen**; on one screen turn sync on with `ghoshs89/Relay-TV_Sync` and its token; join the others; delete the old token on GitHub. Then `relay-sync.bin` and its line in `.github/workflows/build.yml` can go (to-do 1 below). The token for the new repository was pasted into a chat: once sync runs there, replace it (*Replace the token* on the phone page, then **Join again** on the other screens) and delete it. |
| B | **Device checks, on the TV and on the phone** | The lists under "Check on a device". Nothing since 1.0.24 has been looked at on a TV, and no phone has ever been measured. |
| C | Delete the scratch branches on GitHub | `p2`, `p2base`, `p2r`, `p2s`, `p3base`, `p4base`, `p4base-big`, `pB`, `pC`, `pE1`, `pE2`, `pFA`, `pFB`, `pFC`, `plan3`, `plan4`, `plan4-big`, `plan4-exec`. |

## To do

| # | Item | Notes |
|---|---|---|
| 1 | Remove `relay-sync.bin` and its `paths-ignore` line | Only after A. Changing `build.yml` makes a release, so ship it with other work. |
| 2 | Guide grid: draw only the programmes on screen (plan 5, U4) | Each row builds every programme in an 11 hour window. Needs a benchmark run before and after; the guide stretches now exist. |
| 3 | Player correctness (plan 5, B1 to B3) | One automatic retry for a film after a network drop; re-tune live after coming back to the app; don't resume a film the viewer had paused. After the player checks in B. |
| 4 | **Finish splitting the player code** | Done so far (1.0.30): kids limit timing, audio and subtitle choices, failure panel, pause bar and its arithmetic, reminder banner, number entry and the picture-in-picture ratio are separate classes with tests. `PlayerActivity.kt` is about 1,200 lines. Not done: live channel switching and recovery (`LiveSession`), the pause buffer controller (`TimeshiftController`) and view construction (`PlayerViews`). After the player checks in B. |
| 5 | Up next card and last five channels | Plan 2 items D6 and D4. With to-do 4. |
| 6 | Phone layout on short screens (plan 5, P7) and double-tap to skip (P8) | P7 after a look at the phone: a phone on its side is only about 360 to 410 dp tall. |
| 7 | Media session on phones (plan 5, Q5) | Lock-screen, headset and picture-in-picture controls. Needs one more Media3 library. |
| 8 | Tune the fast tier from a phone log | The phone's shorter waits in `data/DeviceTier.kt` are first guesses. |
| 9 | Real baseline profile (plan 2 item A4) | The profile in the repo is a wildcard list. A generated one needs a Macrobenchmark run. |
| 10 | Plan 4 leftovers | The first start after an update was 3 to 35% slower on the emulator (cause not found); the saved copy's channel read. Both need the TV log. |
| 11 | Benchmark: a phone-shaped emulator pass and a `detail-open-and-back` stretch | Touch flings were added to the existing run in plan 5; a separate phone profile was not. |
| 12 | Sync, later polish | Pairing link shown as a QR code on a TV; a hint under a Continue watching card that came from another screen; an extra pull when a detail page opens; WebDAV if you would rather keep the data at home. |

## Check on a device

Plan 6 (1.0.112), on a TV and a phone you use with a VPN:

| Step | What to look for |
|---|---|
| 1 | Phone page → paste the provider's whole message → Send to TV. Settings → Account lists four servers: two "Without VPN", two "With VPN". |
| 2 | VPN off: tune a channel. Settings → Account shows a "Without VPN" server in use and the VPN line says Off. |
| 3 | VPN on: tune another channel. It starts within about 5 seconds; a "With VPN" server is in use and the VPN line says On. |
| 4 | If either step is slow, send the log: its header lists every server with its mark and state. |

Plan 5 (first build that contains it), on the **phone**. Turn on Settings → Diagnostics → **Save detailed logs** first, and send the log afterwards (**Share logs** works on phones):

| Item | What to look for |
|---|---|
| Player fills the screen | No status bar or gesture bar over the video. Nothing (channel name, stream info, Channels button, pause bar) hidden behind the camera cut-out. Turn the phone to the other landscape side: still right. A swipe from the edge shows the bars for a moment. |
| After a dialog | Open the audio or subtitle menu in the player and close it: the bars go away again. |
| Picture-in-picture | Leave with Home while a film plays, come back: full screen again, bars hidden. |
| Headphones | Pull out or switch off headphones during playback: it pauses. |
| Touch sizes | The Live TV toolbar chips, the A to Z letters and the buttons in the channel panel are easy to hit with a thumb. Say if the taller toolbar leaves too few channel rows. |
| Overlay sizes | The channel name, stream info and failure panel look the same size as on the TV, not smaller. |
| Flings | Fling Live TV → All channels, Movies, Home and the guide grid. Posters fade in on a fast phone. |
| Typing | Search and Find in Live TV answer sooner after the last letter than on the TV. |
| The log | The top has a *Device tier:* line; it should say *fast* on a recent phone. *Frames* lines end with *late for a 120 Hz screen* when frames were late. |

Plan 5, on the **TV** (nothing should feel different except where listed):

| Item | What to look for |
|---|---|
| The log | *Device tier: standard* with the same numbers as before (lists at once 3, guide wait 350/120 ms, images 8/4, refresh after 20 s). |
| A to Z | Live TV → All channels → Sort: A–Z. No pause when the list appears; the letter strip jumps to the right place. |
| Opening a title | Open a film (with a TMDB key), press Back: the grid is as you left it, not dimmed and rebuilt. |
| Home | Rows *New in Hindi* and the like appear without holding up scrolling. |
| Search | The second and later searches are quicker than the first. Memory in the log after a search: say if it is much above 180 MB. |
| Pinned categories | Pin a category, open a channel from it, press Back: focus returns to the pinned row, not the one under its country. |
| Player | Overlays, margins and the failure panel look exactly as before. |

Plan 4 (first build that contains it; see Done). Run this routine once after installing the release, so the numbers are comparable with the table in [HISTORY.md](HISTORY.md) section 2. Run it on the TV, and once on the phone too:

| Step | What to do and look for |
|---|---|
| 1 | Settings → Diagnostics → **Save detailed logs** on. |
| 2 | Install the update (the first start after it takes the long way once, about a minute, and writes the saved copy a few seconds after the lists are ready). Then unplug the TV for 10 seconds, so the next start is a real cold one. Open Relay and wait until channels show; this start should take seconds, not a minute. |
| 3 | Open Live TV → All channels and scroll for 20 seconds. Open the guide. Open Movies, then Series. Search for a channel name. Tune three channels. Nothing should stutter more than before. |
| 4 | Next day, when the library is more than 6 hours old, open Relay from cold again and leave it on Home for 2 minutes. This catches the refresh: it starts about 20 seconds after launch and should leave the first minute of browsing smooth. |
| 5 | Settings → Backup & phone, scan the QR code, press **Download logs** on the phone and attach the file. The top of the log has a *Library snapshot:* line (used, or not used and why) and the library lines say *Library from saved copy in … ms*. |

Fill the "After plan 4" column of the table in [HISTORY.md](HISTORY.md) section 2 from that log. Targets: channels on screen 10 s or less after process start (was 56.4 s), all lists 15 s or less (was 62.7 s), memory after load 160 MB or less (was 151 MB), refresh peak no higher than 241 MB.

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
| 1.0.113 | Three fixes from the 12 Nov review and TV log: a screen that joins sync no longer adds a second profile of the same name (its own "Me" becomes the household's, with its favourites and history); a failed request about one channel or title no longer makes Relay treat the whole server as down for 10 minutes; the provider's guide is downloaded once at start-up, not twice. Profiles already doubled are not merged: delete the spare one in Settings → Profiles. |
| 1.0.112 | Plan 6: every one of the provider's servers can be marked for use with or without a VPN (read from a pasted message, set on the phone page, or changed in Settings → Account); Relay tries the servers that suit the connection first and notices a VPN going on or off; a server that fails is tried last for 10 minutes and the next server is tried after 5 seconds instead of 15; marking a server never reloads the library. The benchmark job now runs only when started by hand, to save Actions minutes. See [PLAN-6.md](PLAN-6.md) |
| 1.0.110 | Plan 5, in part: a device tier so recent phones use shorter waits and wider limits while TVs keep theirs; the player fills the screen on phones, keeps clear of the camera cut-out and pauses when headphones disconnect; touch-size buttons; frame counting that knows the screen's refresh rate; the A to Z strip, Home language rows and learned languages off the screen thread; quicker repeat searches; guide, Search and touch stretches in the benchmark. Outcome in [PLAN-5.md](PLAN-5.md) section 8 |
| 1.0.108 | Plan 4: Relay keeps a ready-to-use copy of the prepared library, so a restart or an update shows channels in seconds instead of about a minute (the first start after an update that changed how names are read still takes the long way once); channel text work cleans each name once; refreshes reuse what they already know about unchanged items and wait 20 seconds after start-up; the screen-freeze watcher no longer reports the app being frozen by Android as a stall; the TV log read and the measurements table filled (TODO rows 2 and 3). Summary in [HISTORY.md](HISTORY.md) |
| 1.0.91 | Plan 3: API keys sent from the phone, Trailer and Find a trailer buttons, optional sync between screens (profiles, favourites, history, kids' time, shared settings; encrypted file in your own private GitHub repository; pairing by matching six digits). Summary in [HISTORY.md](HISTORY.md) |
| 1.0.77 | Holding Up through a poster grid back to its old speed (found by the benchmark; see [HISTORY.md](HISTORY.md) rule 4) |
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

## Ideas (parked)

| Idea | Notes |
|---|---|
| Stremio add-ons with Real-Debrid and TorBox | Parked by the owner on 12 Nov 2026. Sketch: "More sources" on movie and episode pages from the owner's own add-on URLs (direct links only), then the debrid clouds as a row, then add-on catalogs. Needs IMDb ids (through the TMDB lookup), an audio-codec answer for DTS and TrueHD files, and add-on URLs treated as secrets. |
