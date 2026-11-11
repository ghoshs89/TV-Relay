# Relay to-do list

Nothing here is started until it's asked for. Newest additions at the top of each section.

The step-by-step plan for the performance, code and UI work is in [PLAN.md](PLAN.md). Its five to-do items (remove from Continue watching, split the player, Start over, README note, lint) are done, except the last part of the player split below.

## To do

| # | Item | Notes |
|---|---|---|
| 1 | **Finish splitting the player code** | Done so far (1.0.30): kids limit timing, audio and subtitle choices, failure panel, pause bar and its arithmetic, reminder banner, number entry and the picture-in-picture ratio are separate classes with tests. `PlayerActivity.kt` went from 1,324 to 1,071 lines. Not done: live channel switching and recovery (`LiveSession`), the pause buffer controller (`TimeshiftController`) and view construction (`PlayerViews`), which hold the shared playback state and are the riskiest to move without a device. Do them after the player checks below pass on a TV. |
| 2 | Saved binary index of the library (plan item 4.5) | Only worth it if a TV log shows start-up parsing over about 8 s. Needs a TV log first (plan phase 0). |
| 3 | Read a TV log and fill the measurements table | Plan phase 0 and section 11 were skipped because no log was available. Download logs from the phone companion after a refresh and a few minutes of browsing. |
| 4 | Byte-order mark in `TimeshiftSupport.looksLikePlaylist` | The check decodes bytes as ISO-8859-1, so a UTF-8 byte-order mark never matches the character it trims, and a playlist that starts with one would be treated as a transport stream. Harmless so far (pause buffer falls back to direct play); a one-line fix with a test if it ever shows up. |

## Check on a device

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
