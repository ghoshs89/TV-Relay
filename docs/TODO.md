# Relay to-do list

Nothing here is started until it's asked for. Newest additions at the top of each section.

The step-by-step plan for the performance, code and UI work, including items 1 to 5 below, is in [PLAN.md](PLAN.md).

## To do

| # | Item | Notes |
|---|---|---|
| 1 | **Remove items from Continue watching on Home** | A way to drop a movie or episode you don't intend to finish (for example long-press or Menu on the card → Remove). Should also clear its saved position and its entry on the Google TV home screen. `Store.removeHistory` exists but nothing calls it yet. Possibly the same for Recently watched channels. |
| 2 | **Split the player code** (left over from batch 3) | `PlayerActivity.kt` is 1,324 lines and holds playback, live zapping, pause & rewind, failure handling and diagnosis, version fallback, kids time limits, the overlay and key handling. Split into focused parts (for example live channel switching, failure and recovery, kids limits, on-screen panels) with no change in behaviour, so each part can be tested on its own. The emulator check in the build guards against a broken split. |
| 3 | Start over from the guide grid | Batch 6 added Start over to the channel panel only. The plan also had it on the current programme in the guide grid. |
| 4 | README wording on repo visibility | The repo is private again (since 1.0.23), so the note is correct. Remaining: add two lines about the update token (plan phase 8). |
| 5 | Review the 57 non-critical lint warnings | None stop the build; worth a pass to clear the real ones. |

## Check on a device

| Item | What to look for |
|---|---|
| TV performance after 1.0.22 | Refresh finishes with a rising percentage; Live TV opens and scrolls without freezing. If not, Download logs from the phone companion. |
| Continue watching on the Google TV home screen | Entries appear for a movie or episode watched past a minute; choosing one resumes it. |
| Show each channel once | HD, FHD and backup versions of a channel merge correctly with this provider's names; Versions lists them. |
| Search the whole TV guide | How long the provider's full guide takes to download, and whether On TV results look right. |

## Done

| Release | What |
|---|---|
| 1.0.23 | Repo private again; update token replaced with a read-only one limited to this repo |
| 1.0.22 | Faster loading on TVs, refresh percentage, logs that explain problems, Download logs on the phone companion |
| 1.0.20 | Batches 4 to 7 |
