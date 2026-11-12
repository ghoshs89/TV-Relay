# Relay plan 3: trailers, keys from the phone, and keeping screens in sync

Written against the code released as 1.0.77. Nothing in the app was changed for this plan. It follows the same rules as `docs/PLAN.md` section 2 and `docs/PLAN-2.md`, with one change asked for by the owner: **one release for the whole plan, built once at the end** (section 8). Otherwise: no behaviour change beyond what a task lists, pure logic gets unit tests, CI is the only Android compiler (fix forward if red), no secrets in the repo or in logs, signing and update-token steps in `build.yml` stay as they are.

One addition from plan 2: any change to `Focusable`, the grids or the lists is run through the benchmark job on a branch (Actions, **Run workflow**) and compared with `main` before it is pushed. That is how the 1.0.38 slowdown was found.

## 1. What the code has today

| Area | Today | Consequence for this plan |
|---|---|---|
| Trailers | Nothing. `XtreamApi.vodInfo` and `seriesInfo` do not read the provider's `youtube_trailer` field. `Tmdb.lookup` asks for `credits` only. | Two sources can be added cheaply, both on calls the detail pages already make. No new requests while browsing lists. |
| API keys | `Settings.tmdbKey` and `Settings.ratingsKey` (the Rotten Tomatoes scores come from MDBList, so that is the key). Entered with the on-screen keyboard in Settings, Library. Checked with `Tmdb.validate` and `Ratings.validate`. | The check functions exist. Only a new way in is needed. |
| Phone companion | `CompanionServer`: a small web server on the device, address with a random token, shown as a QR code (`CompanionPanel`, `QrCode`). Routes: sign-in form, library editor, logs, backup download, restore. | The natural place for a "paste your keys" form. ZXing is already a dependency for drawing QR codes. |
| Backup | `BackupFile` (format 2) holds profiles, favourites, history, settings, channel groups and renames, sort choices, series track choices, reminders. Keys, login and PINs are stripped on purpose. Restore replaces everything. | A good inventory of what sync must carry. But replace-everything is not sync: two screens used on the same evening would overwrite each other. |
| Store | `SharedPreferences` with JSON values per key (`fav_<profile>`, `hist_<profile>`, `settings`, `trk_<series>`, ...), write-behind since 1.0.38. Favourites have no timestamps. Removing a history entry leaves no trace. Kids usage is one counter per profile per day, on this device only. | Merging needs three things the data does not have yet: when each item changed, a record of removals, and a device id. That is phase H1 and it is the real work in sync. |

## 2. Decisions taken, and the ones that are yours

Each has a default so the plan can run without waiting. Say so if you want a different one.

| # | Question | Default in this plan | Alternative |
|---|---|---|---|
| Q1 | Where does synced data live? | A private GitHub repo of your own (for example `relay-sync`), one encrypted file, written through the GitHub API with a token that can only touch that repo. Works at home and away, costs nothing, nothing to host, and you already manage a token for the updater. | A file on your TrueNAS over WebDAV. Fully private and no third party, but only reachable at home unless the phone is on your VPN. The plan builds the engine so this is a second small transport, not a rewrite (H5). |
| Q2 | Does the provider login travel with sync? | Yes, encrypted. A new TV then needs one pairing step and nothing typed. | No: sync carries everything except the login, and a new TV still signs in through the phone page first. |
| Q3 | Do profile PINs and the parent PIN travel? | Yes, encrypted, so a kids profile is locked on every screen. | Keep PINs per device as the backup does today. |
| Q4 | Trailers on kids profiles? | Hidden. A trailer opens the YouTube app, which is outside Relay's kids limits and parent PIN. | Show them everywhere. |
| Q5 | Which settings are shared and which stay per screen? | The table in H1.4. Short version: keys, languages, library layout, guide and playback choices are shared; anything about a particular TV (lip-sync, refresh-rate matching, subtitle size, theme, keyboard, pause buffer size, start screen, sleep timer) stays local. | Move individual rows across. |

Why not the other sync designs:

| Design | Why it is not the default |
|---|---|
| Screens talk to each other directly on Wi-Fi | Relay only runs while it is open. Two TVs are rarely open at the same moment, so progress from the living room would reach the bedroom only if both happened to be on. The phone would have to act as a courier. It is used here only for pairing a new screen. |
| Google Drive or a Google account | Needs a Google Cloud project, a consent screen and a sign-in flow on each TV. Much more setup than a token, and a second account system inside the app. |
| A hosted database (Supabase and similar) | Works, but free projects pause after a week without use and need a manual restart, and it adds a service to look after for one small file. |

## 3. Phase F: API keys from the phone (one commit)

Goal: nobody types a key on a TV again. Small, independent, and worth shipping first because sync (phase H) later carries the keys to every other screen.

| # | Task | Detail |
|---|---|---|
| F1 | Keys form on the phone page | New section "Keys for scores and artwork" in `CompanionServer.page()`: two password-style fields (TMDB, MDBList), each with a "Where do I get this?" link (`https://www.themoviedb.org/settings/api`, `https://mdblist.com/preferences/`), and one Save button. New route `POST /<token>/keys`. Empty field means "leave as it is"; a separate "Remove" checkbox per key clears it. |
| F2 | Check before saving | The route trims the value (see F4), runs `Tmdb.validate` / `Ratings.validate` off the main thread, saves through `store.settings = s.copy(...)` on the main thread only if the check passes, and answers the phone with a plain result per key: "TMDB key saved", "MDBList key was not accepted (check that you copied all of it)", "Could not reach TMDB, try again". The TV shows a toast with the same words. |
| F3 | A way to reach it from Settings | In Settings, Library, the two key rows open a small dialog with two choices: **Send from my phone** (starts the companion if it is not running and shows `CompanionPanel` with the QR code; closes itself and shows the result when a key arrives) and **Type it here** (today's editor, unchanged). The dialog is parent-locked exactly as the rows are today (`kidsLocked`). |
| F4 | Forgiving input (pure, tested) | New `data/KeyText.kt`: `clean(raw)` strips spaces, line breaks, quotes and a leading `api_key=` or `apikey=`; `kind(raw)` tells a TMDB v3 key (32 hex characters) from a TMDB v4 "read access token" (three dot-separated parts starting with `eyJ`), because people copy the wrong one. Accept both: `Tmdb` sends a v4 token as `Authorization: Bearer` and a v3 key as `api_key=` as today. Tests: both kinds, padded and quoted input, a pasted URL fragment, rubbish. |
| F5 | Nothing leaks | The keys form never echoes a saved key back to the phone (it shows "Set" or "Not set"). Confirm `AppLog.secrets` already covers both keys and add a test or assertion that a log line containing a key is masked. The companion log line says "Keys received from phone" and nothing more. |
| F6 | README | Settings table and Optional extras: "scan the code and paste your keys from your phone". |

Tests: `KeyTextTest`. Check on a device: scan from Settings, paste a good key and a bad key, see both messages on phone and TV; a kids profile cannot open the dialog.

## 4. Phase G: trailers (one commit)

Goal: a **Trailer** button on movie and series detail pages when a trailer is known, and a way to look for one when it is not.

How a trailer is played: Relay hands the video to the YouTube app. Playing YouTube inside Relay's own player would mean extracting the stream addresses, which breaks YouTube's terms and stops working every few months, so it is not planned. An embedded web player was considered and rejected: remote control support inside a web view on a TV is poor and many trailers refuse to play embedded.

| # | Task | Detail |
|---|---|---|
| G1 | Read the provider's trailer | `XtreamApi.vodInfo` and `seriesInfo` read `info.youtube_trailer` into a new nullable `trailer` field on `MovieInfo` and `SeriesInfo`. Providers put an id, a full address or junk in it. |
| G2 | Read TMDB's trailers | `Tmdb.lookup` asks for `append_to_response=credits,videos` with `include_video_language=<title language>,en,null` and keeps the `videos.results` list (site, type, official, language, key, published date) on `Tmdb.Info`. Same request as today, slightly larger answer. Cached in memory as today. |
| G3 | Choosing one (pure, tested) | New `data/Trailers.kt`. `youtubeId(raw)`: accepts an 11-character id, `watch?v=`, `youtu.be/`, `embed/` and `shorts/` forms, rejects everything else. `pick(providerRaw, tmdbVideos, titleLanguage)`: YouTube only; Trailer before Teaser, nothing else; official first; the title's own language first (so a Hindi or Bengali film gets its own trailer when TMDB has one), then English, then any; newest first among equals; the provider's id is used when TMDB has none, and preferred when both agree. Returns the id or null. Table-driven tests for each rule and for junk input. |
| G4 | The button | `ui/Details.kt`, both screens: a **Trailer** `PillButton` beside Play when `pick` returns an id. It appears when the information arrives and must not shift focus or the layout when it does (reserve its place or add it at the end of the row). Hidden on kids profiles (Q4). |
| G5 | Opening it | New `ui/TrailerLauncher.kt`: `startActivity(Intent(ACTION_VIEW, "https://www.youtube.com/watch?v=<id>"))`, caught for `ActivityNotFoundException`. No manifest `<queries>` entry is needed because the result is not inspected beforehand. If nothing can open it (a TV without YouTube), show a dialog with the existing `QrCode` of the address: "Scan to watch the trailer on your phone". Log "Trailer opened for <title>" or "No app could open the trailer". Back from YouTube returns to the detail page; `FocusRestorer` puts focus back on the button. |
| G6 | When no trailer is known | Only when a TMDB key or provider info produced nothing: a quieter **Find a trailer** button that opens a YouTube search for `"<clean title> <year> trailer"` (`https://www.youtube.com/results?search_query=...`, same launcher and same fallback). `Tmdb.cleanTitle` already produces the clean title. Verify on one TV that the YouTube TV app opens the search and not its home page; if it does not, drop G6 rather than ship a button that lands on the wrong screen. |
| G7 | README | Features and Remote controls: Trailer button; note that it opens YouTube. |

Not in this phase: trailers playing by themselves on the detail page or on Home (autoplay), and trailers for single episodes.

Tests: `TrailersTest`. Check on a device: a title with a TMDB trailer, one with only a provider trailer, one with neither, on a Sony and on the phone; Back returns to the same page with focus on the button.

## 5. Phase H: keeping screens in sync

### 5.1 What "in sync" will mean

Every screen that has joined keeps the same copy of the things in the table below. A change on one screen reaches the others the next time they open, come back to the front, or within about ten minutes if they are already open. It is not instant and it does not need to be: the cases that matter are "stopped a film in the living room, carry on in the bedroom" and "added a key or a favourite once, have it everywhere".

The data is one small encrypted file in your store (Q1). Screens never talk to each other except once, to pair. Without a network, or with the store unreachable, each screen carries on by itself and catches up later. Nothing in sync can block playback or start-up.

### 5.2 What is shared

| Data | Shared? | How two screens' changes are combined |
|---|---|---|
| Watch history and positions | Yes, per profile | Per title: the entry with the later `updated` time wins. A removal is remembered (see H1.2) so the title does not come back from another screen. Capped at 400 per profile after merging, as today. |
| Favourites / My list | Yes, per profile | Per item: added or removed, whichever happened later. |
| TMDB and MDBList keys | Yes | Part of shared settings. |
| Shared settings (H1.4) | Yes | Per setting: the later change wins. Changing audio languages on one screen and hidden categories on another keeps both. |
| Profiles (name, colour, kids flag, limits, languages, Home rows) | Yes | Per profile, later change wins; a deleted profile is remembered as deleted. |
| PINs (profile and parent) | Yes (Q3) | Later change wins. |
| Kids viewing time today | Yes | Added up across screens, so 60 minutes a day means 60 minutes in total (H1.5). "Allow 30 more minutes" also travels. |
| Channel groups, renames, hidden channels | Yes | Whole object, later change wins (edited rarely, usually on one screen). |
| Pinned categories, sort choice per list, chosen channel versions | Yes | Later change wins, per key. |
| Audio and subtitle choice per series | Yes | Per series, later change wins. |
| Reminders | Yes | Per reminder, added or removed, later wins. Each screen still shows its own banner. |
| Provider login | Yes (Q2) | Later change wins. Changing servers on one screen updates the rest, which then reload the library. |
| Per-screen settings (H1.4), logs, caches, the library itself, last channel, scroll positions | No | Stay on the device. Each screen downloads its own library as today. |

Two households or two provider accounts never mix: the file is tied to the account (a hash of server name and user name) and a screen signed in to something else refuses to merge and says so.

### 5.3 H1: data that can be merged (one commit, no network yet)

This phase changes how data is stored and nothing the viewer can see. Sync itself stays off until it is set up (section 8, rule 4), so a storage mistake shows up on one screen before anything travels between devices.

| # | Task | Detail |
|---|---|---|
| H1.1 | Device id and clock | `Store.deviceId`: a random id made once. `data/SyncClock.kt` (pure): `next(nowMs)` returns a time that never goes backwards and is always later than the last one issued or seen from another screen, so a TV with a wrong clock cannot make its changes win or lose for ever. Tests. |
| H1.2 | Removal records | New store key `gone`: a map from item (`hist:<profile>:<key>`, `fav:<profile>:<kind>:<id>`, `profile:<id>`, `rem:<channel>:<start>`) to the time it was removed. Written by `removeHistory`, `clearContinue`, `toggleFav` (when removing), `deleteProfile` and reminder removal. Pruned after 180 days. `HistoryEdits` and the new `FavEdits` take and return the records so the rules stay pure. Tests: remove then re-add, re-add then remove, pruning. |
| H1.3 | Times on favourites | `FavItem` gets `at: Long = 0` (when it was added). Existing favourites get the time of the upgrade. Order on screen stays as it is today. |
| H1.4 | Shared and per-screen settings | `data/SettingsSplit.kt` (pure): the list of shared field names, and `stamp(old, new, stamps, now)` which records the change time of every shared field that differs. `Store.settings` setter calls it and saves the stamps under `settings_at`. Shared: `tmdbKey`, `ratingsKey`, `audioLang`, `audioLangs`, `subtitleLang`, `autoNext`, `hideAdult`, `xmltvUrl`, `epgOffsetMin`, `posterScores`, `langOrder`, `langFeatured`, `hiddenCats`, `pinnedVod`, `pinnedSeries`, `mergeVariants`, `preferredVersions`, `fullGuideSearch`, `watchNext`, `skipBackSec`, `skipForwardSec`, `askStillWatching`, `setupDone`. Per screen: `liveExt`, `userAgent`, `logUntil`, `matchFrameRate`, `showStreamInfo`, `audioDelayMs`, `subSize`, `subYellow`, `subBox`, `neutralTheme`, `startOnLastChannel`, `keyboard`, `timeshift`, `timeshiftMinutes`, `startScreen`, `sleepMinutes`. A test fails if a field of `Settings` is in neither list, so a future setting cannot be forgotten. |
| H1.5 | Kids time per device | `use_<profile>` becomes a small map `{date, byDevice: {deviceId: ms}}`; `usedToday` is the sum. `addUsage` adds to this device's entry. `allow_<profile>` stays a time. `KidsLimits` and `KidsLimitTracker` are untouched because they only see the total. Tests for the sum, the date roll-over and reading the old one-number format. |
| H1.6 | The merge itself (pure, the heart of the plan) | `data/SyncDoc.kt`: a serializable `SyncDoc` (format number, account tag, and one section per row of the table in 5.2) and `SyncMerge.merge(local, remote): SyncDoc`. Requirements, each with tests: merging is the same in either order; merging a document with itself changes nothing; merging twice gives the same as once; a removal beats an older add and loses to a newer one; the 400-entry cap is applied after merging; unknown fields from a newer app version are carried through untouched and never dropped; a document for another account is refused. Table-driven tests plus a randomised test that builds two diverged copies from a common start and checks both merge orders give identical results. |
| H1.7 | In and out of the store | `Store.exportSync(): SyncDoc` and `Store.applySync(doc)`: apply writes only the keys that differ, in one `SharedPreferences` commit as `importJson` does, clears the in-memory cache for those keys and bumps `version` / `historyVersion` once. If the login changed, it signals the app to reload the library exactly as "Change servers or login" does today. If PIN or kids settings changed while a kids profile is active, the session's restrictions are recalculated. |
| H1.8 | Backup stays compatible | `BackupFile` goes to format 3 only to carry the new times and removal records; format 2 and 1 files still restore. A restore counts as a change made now on this device, so after sync is on it spreads to the other screens. The restore confirmation says so once sync exists (H3). |

Size check to do in this phase and write into the log once: the export for the real data set (profiles, 400 history entries each, favourites) before and after gzip. It must stay under about 700 KB compressed for the GitHub transport; history entries carry image addresses, so if it is close, store images by reference to the title id instead.

### 5.4 H2: the sync engine and the store (one commit)

| # | Task | Detail |
|---|---|---|
| H2.1 | Sealing the file (pure, tested) | `data/SyncSeal.kt`: gzip, then AES-256-GCM with a random 12-byte nonce per write, using a 32-byte household key that is made on the first screen and never leaves your devices. The store holds only ciphertext. A wrong key or a damaged file gives a clear error and never overwrites local data. Uses `javax.crypto`, which is available from Android 7 (minSdk 24); no new dependency. Tests with fixed vectors, a wrong key and a truncated file. |
| H2.2 | Transport interface | `sync/SyncTransport.kt`: `read(): Remote?` (bytes plus a version tag) and `write(bytes, ifVersion): WriteResult` (Saved, Changed meanwhile, Failed). Everything above it is independent of where the file lives. A `FakeTransport` in tests. |
| H2.3 | GitHub transport | `sync/GitHubTransport.kt` with the app's OkHttp client: `GET /repos/{owner}/{repo}/contents/relay-sync.bin` (the `sha` is the version tag; a 404 means first use) and `PUT` with that `sha`, which GitHub rejects with 409 or 422 when another screen wrote in between. The token is a fine-grained one limited to that one repo with Contents read and write. It is stored on the device like the provider password, added to `AppLog.secrets`, and never built into the APK. |
| H2.4 | The loop (pure core, tested with the fake) | `sync/SyncEngine.kt`: read, unseal, merge with the local export, apply locally if the merge changed anything, write back if the merge differs from what was read; on "changed meanwhile" start again, at most 3 times. Outcomes: UpToDate, Pulled, Pushed, Both, Offline, WrongKey, WrongAccount, StoreProblem(message). Tests: two fake screens taking turns converge; a write collision is retried and both changes survive; offline changes nothing; wrong key changes nothing. |
| H2.5 | When it runs | `sync/SyncRunner.kt`, one background coroutine, never more than one run at a time: at app start after the store is ready (not blocking the first screen), when the app comes to the front, after the player closes, 30 s after the last change to favourites, shared settings, profiles or history removals, every 10 minutes while the app is open, and a best-effort run when the app stops. Playback progress is saved locally every 10 s as today but pushed only when the player closes or stops, so a film does not produce a write every 10 seconds. After a failure it waits 1, 5, then 15 minutes. A run is skipped while a library refresh is parsing on a slow TV (reuse the existing loading flag). |
| H2.6 | Turning it on, first screen | Phone page, new section "Sync between screens": repository (`owner/name`) and token fields, same paste-from-phone idea as phase F. The TV checks it can read and write the repo, makes the household key, does the first push and shows "Sync is on. 1 screen." Settings gets a **Sync** section (parent-locked): status row ("In sync, 2 minutes ago", "Waiting for the network", "The sync token was refused", "This screen is signed in to a different provider account"), **Sync now**, **Add a screen** (H3), **Stop syncing on this screen** (keeps local data, forgets token and key), and a count of screens seen (each screen writes its name and last-seen time into the file). |
| H2.7 | Logs | One line per run with the outcome, bytes and time; counts of what merged ("3 history, 1 favourite, 2 settings from Living room TV"). Never the token, the key, the login or file contents. |
| H2.8 | README | A "Sync between screens" section: making the private repo and the token (same steps as the updater token, different permission), what is shared, what is not, what happens offline, and how to stop. |

Limits that follow from GitHub as the store, to state in the README: about ten writes an hour per active screen at most, far under GitHub's limits; every write is a commit, so the repo's history grows by a few megabytes a year and can be reset by deleting and recreating the repo (screens re-upload); if the token expires, screens say so and carry on locally until a new one is pasted on any one screen, which then spreads it (the token is inside the encrypted file too).

### 5.5 H3: adding a screen without typing (one commit)

A TV has no camera, so it cannot scan a code. Two ways in, both without typing on the TV:

| # | Task | Detail |
|---|---|---|
| H3.1 | Pairing bundle (pure, tested) | `sync/Pairing.kt`: the bundle is repo, token, household key and account tag, encoded as one text link `relay-pair:<base64>` . `encode` / `decode` with a version number and a checksum; tests for round trip and damaged input. |
| H3.2 | Join from a nearby screen (main way) | New TV: Settings, Sync, **Join** (also offered on the very first sign-in screen as "I already use Relay on another screen"). It starts the companion server, advertises itself on the home network with Android's `NsdManager` as `_relay-pair._tcp`, and shows a 6-digit code. Existing screen (phone or another TV): Sync, **Add a screen** lists the screens that are waiting; choose one, check that the code it shows matches the one on the new TV, confirm. The existing screen posts the bundle to the new screen's companion address `POST /<token>/pair`, encrypted with a key derived from the 6-digit code and the companion token so that only the screen showing that code can read it. The new screen stores it, runs a first sync, and (Q2) is signed in with the library loading. Everything is done with the D-pad. `NsdManager` needs no extra permission on current Android versions; verify on the TVs' Android versions and on a phone with Android 14 or later during this phase. |
| H3.3 | Join by phone link (fallback when discovery fails, for example on a guest or mesh network that blocks it) | Existing phone: Sync, Add a screen, **Copy pairing link**. New TV shows its usual companion QR code; the phone's camera opens the TV's page in the browser; a "Join sync" box takes the pasted link. No camera permission or scanner library is added to Relay. |
| H3.4 | Leaving and losing a screen | **Stop syncing on this screen** removes the screen from the list in the file. **Remove a screen** on any other screen is only cosmetic (the removed device still holds the key), so the README says what really locks a lost device out: make a new token on GitHub and choose **New household key**, which re-seals the file; the other screens then need pairing again. |
| H3.5 | Restore and sync together | Restoring a backup on one synced screen spreads to the rest; the confirmation dialog says so and offers "Restore on this screen only (stop syncing first)". |

### 5.6 H4 and H5: after it has run for a while (optional, later)

| # | Task | Detail |
|---|---|---|
| H4.1 | Carry on where another screen stopped | Home's Continue watching already reflects merged history. Add the small hint "Living room TV, 20 minutes ago" under a card whose latest position came from another screen. Needs a `device` field on `WatchEntry`. |
| H4.2 | Quicker hand-over | An extra pull when a detail page opens for a title in progress, so resuming on the second TV a minute after stopping on the first picks up the right position. |
| H5 | TrueNAS / WebDAV transport | `sync/WebDavTransport.kt`: `GET` and `PUT` with `ETag` and `If-Match` against any WebDAV address (TrueNAS, Nextcloud). Same engine, same pairing, chosen on the phone page. Only if you want the data at home (Q1). |

### 5.7 How sync is tested without two TVs

1. Everything that decides an outcome is pure and unit tested: the clock, removal records, the settings split, the merge (including the randomised both-orders test), sealing, the engine against a fake store, the pairing bundle.
2. The benchmark job gets one more stretch: two installs cannot run on one emulator at once, so the fake provider script is extended to also serve the two GitHub endpoints from a local file; the benchmark build points sync at it, the script changes a favourite and a setting through the companion API, forces a sync, wipes the app's data, pairs again from a saved bundle and checks in the log that the favourite and the setting came back. This proves the whole path end to end on a real Android build.
3. The device checks in section 7.

### 5.8 Risks

| Risk | Guard |
|---|---|
| A merge mistake damages history or favourites on every screen at once | Sync is off until set up; start with one screen. Before the first sync on a screen, and before applying any merged file from a newer app version, the screen writes a local backup file (kept as the last 3). The merge never deletes without a removal record. |
| An old app version meets a newer file | Format number in the file; unknown fields are carried through; a file with a higher major format is read-only for the old app, which says "Update Relay on this screen to keep syncing". |
| Clock on a TV is wrong | `SyncClock` (H1.1). |
| Token or key leaks through logs or backups | Both in `AppLog.secrets`; never in the backup file; a unit test feeds them through the log masker. |
| Sync slows a TV | Runs off the screen thread, skipped during library parsing, one run at a time, measured in the log (time and bytes). The benchmark is run before and after H2. |
| The login travels (Q2) | Only inside the sealed file; the key exists only on your screens. If that is still not wanted, Q2's alternative removes it with a one-line change in `exportSync`. |

## 6. Left over from plans 1 and 2

Unchanged in substance; listed so there is one place to look.

| Item | State | When |
|---|---|---|
| Device checks for 1.0.24 to 1.0.77 | Not done. `docs/TODO.md`, "Check on a device". Hold OK on the remote, focus after Back, the 1 s live buffer, the dimmed grid. | Before phase F if possible, so new work is not blamed for old changes. Needs you and a TV. |
| A1: read a TV log and fill the measurements table | Not done; needs a log from a TV (turn on **Save detailed logs**, use the app, share the logs). | Any time. Decides the next item. |
| Plan 1 item 4.5: saved binary index of the library | Not started; only worth it if the TV log shows start-up parsing over about 8 s. | After A1. |
| A4: generated baseline profile | Not done; the wildcard list is in place. The benchmark job can be extended with a Macrobenchmark module to produce one. | Low priority; after phase G. |
| D4: list of the last five channels | Not done; Back already flips to the previous channel. | With phase E, since it lives in the same code. |
| D6: Up next card with countdown | Not done; more player screen work than can be checked without a TV. | After the device checks; pairs naturally with phase E. |
| Phase E: finish the player split (`LiveSession`, `TimeshiftController`, `PlayerViews`) | Not started; gated on the player checks on a TV. | After the device checks. Before H4 if both are wanted, because H4 touches the player's save path. |
| Known changes from plan 1 | The Versions button lost its count; series plot and genre come only from series info. | Say if either should come back; each is small. |
| Benchmark job | Works. Does not cover the player's retry paths or anything in this plan except the sync stretch in 5.7. | Extended in H2. |
| Scratch branches on GitHub | `p2`, `p2r`, `p2s`, `p2base`, `pB`, `pC`, `pE1`, `pE2`, `pFA`, `pFB`, `pFC` could not be deleted from the session. | Delete by hand at any time. |

## 7. Order, size and what needs you

Phases F to H3 go out together as one release (section 8). H4, H5 and the player items are separate, later work.

| Step | Content | Size | Commit on `plan3` | Needs you |
|---|---|---|---|---|
| 0 | Device checks from plans 1 and 2 | | | A TV and the remote |
| F | Keys from the phone | S | 1 | Paste the two keys once |
| G | Trailers | S to M | 1 | Q4; one look at G6 on a TV |
| H1 | Mergeable data, no network | L | 1 | Nothing |
| H2 | Engine, GitHub store, first screen | L | 1 | Q1, Q2, Q3; make the private repo and token |
| H3 | Adding screens | M | 1 | Pair the TVs; say whether discovery works on your Wi-Fi |
| H4, H5 | Hand-over polish, WebDAV | S each | 1 each | Only if wanted |
| E, D4, D6 | Player split, last channels, Up next | L | 2 to 3 | After step 0 |

Check on a device, for `docs/TODO.md`, once the release is installed:

| Phase | What to try |
|---|---|
| F | Scan from Settings, paste a good and a bad key; messages on phone and TV; scores and artwork appear. |
| G | Trailer on a movie and a series; Back returns to the same page; no button on a kids profile. |
| H1 | Nothing should look different. Remove a Continue watching card, add and remove a favourite, change a setting; restart; all as left. Kids time still counts. |
| H2 | Turn sync on from the phone page; status row says in sync; change a favourite, see a commit appear in the repo within a minute. |
| H3 | Join the second TV from the first; it signs in by itself and shows the same Continue watching, favourites, keys and languages. Stop a film on one, open it on the other. Remove a card on one, confirm it does not come back on the other. Switch Wi-Fi off on one, use both, switch it back on, confirm both sets of changes survive. |

## 8. Rules for whoever executes this

1. **One release.** All work happens on a branch (`plan3`), one commit per phase in the order F, G, H1, H2, H3. Pushing a branch does not build anything. `main` is pushed once, at the very end, and that single push produces the single release.
2. **Compile checks without releases.** CI is the only Android compiler, so the branch is checked with **Run workflow** on `plan3` (this publishes nothing). Keep these few: one after G, one after H1, one after H3, plus re-runs only when one is red. Each run uses up a build number, so the release number will jump by that many; that is cosmetic. Pure logic is compiled and tested locally first so most mistakes never reach CI.
3. **Benchmark before the final push.** Run the benchmark on `plan3` and on `main` (3 runs each) and compare, as in plan 2. No push to `main` if a stretch is consistently worse.
4. **What one release costs, and the guard for it.** Plan 2 advice was to let H1 (the storage change) run alone for a few days before sync is switched on. With one release that pause is gone. In its place: sync stays **off until it is set up from the phone page**, so installing the release changes storage only; before a screen's first sync, and before any merged file is applied, the screen writes a local backup file (last 3 kept); and the recommended first use is one screen for a few days, then pair the second.
5. **Documentation, in the same final push.** `README.md`: Features, Remote controls (Trailer), Settings worth knowing (keys from the phone, Sync), a new "Sync between screens" section (making the private repo and token, what is shared, what stays per screen, offline behaviour, stopping, a lost device), Optional extras (keys by QR code), Troubleshooting (sync status messages), Backup (format 3, restore and sync), Project layout (`sync/`). `docs/PLAN-3.md`: this plan plus an Outcome section with the release number, what was left out and the benchmark table. `docs/TODO.md`: new "Check on a device" rows from section 7, Done table, and the leftovers of section 6 with their current state. `docs/PLAN.md` and `docs/PLAN-2.md`: a one-line pointer to plan 3 where they list open items. In-app text that describes these features (Settings subtitles, the phone page) is checked against the README wording.
6. Every new decision function listed as "pure, tested" gets its tests in the same commit. Tests are never deleted to make a build pass.
7. No token, key, login or PIN in the repo, in a commit message, in a log line, in the backup file or in the benchmark build. The benchmark build uses made-up values only.
8. The release APK must still contain no benchmark code (the existing CI guard). Signing and update-token steps in `build.yml` are not touched.
9. Stop and ask only for the questions in section 2 if their defaults turn out not to fit, or for something this plan does not cover.

## 9. Outcome

Released as **1.0.91**, from one push to `main` carrying the commits below. Release numbers 78 to 90 were used by branch checks and benchmark runs (they publish nothing), so the number jumps.

| Commit | Phase | What |
|---|---|---|
| `8a64d35` | F | Keys from the phone: a keys form on the companion page, checked before saving, with a **Send from my phone** choice on both key rows |
| `5bb03ae` | G | **Trailer** button (provider or TMDB, in the title's own language first) and **Find a trailer** (YouTube search), hidden on kids profiles |
| `660fef4` | H1 | Mergeable data: change times, the shared/per-screen settings split, daily kids' time summed across screens, the sync clock, records and the merge |
| `1d6190b` | H2 | The engine, encrypted file, GitHub storage, runner, turning sync on from the phone page, Settings status |
| `450af3b` | H3 | Joining a screen (nearby discovery, matching six digits, pairing link), remove a screen, New household key, restore warning |
| `3abc669` | H3 | **Join again** appears when the key or token stops working |
| `5e184ab` and the commit that adds this section | | README, TODO, plan pointers, this outcome |

Tests: 15 test files were added or extended (about 2,700 lines), including a randomised test that applies the same changes to two screens in both orders and requires the same result, and engine tests against a fake store and a fake GitHub (conflict, offline, wrong key, wrong account, too large, forgetting a screen, new key). The tests that need the serialization compiler plugin (`SyncCodecTest`, `SettingsSplitTest`) cannot be compiled in the workspace the plan was executed in, so they ran only on CI, where every branch run passed unit tests, lint, the release build, the signature check, the "no benchmark code in the release APK" check and the emulator launch.

### What differs from the plan

| Plan said | What was done | Why |
|---|---|---|
| H1.2: removals leave a `gone` record when they happen | Each screen remembers a fingerprint of every record as of the last sync. A record that has disappeared since is a removal and is written as a tombstone (dropped after 180 days) | Removals made anywhere, including code that knows nothing about sync, are found without touching each of them; fewer places to forget one |
| Backup format 3 | The backup file is unchanged (format 2). Sync keeps its own safety copies (`before-sync-<time>.json`, the last three) using the same export | Nothing new needed to be in the backup; one less format to keep readable |
| Pairing with a 6-digit code | The two screens swap public keys and agree on a secret (ECDH, P-256); the six digits shown on both are derived from both keys and only prove nobody sits in the middle. The bundle is sealed with the agreed key | A six-digit code used as a key can be guessed offline by anyone who recorded the exchange |
| A new token on one screen spreads to the others | Not possible: with an expired token a screen can't read the file that would carry the new one. Each screen keeps its own token; **Replace the token** on the phone page, or **Join again from another screen**, which carries the current token | |
| Benchmark stretch (5.7, item 2): a local look-alike of the GitHub endpoints and a wipe-and-pair run | **Not built.** The benchmark runs with sync off, so it shows what the code changes cost when sync is not in use, not what a sync costs | It needs a GitHub look-alike in `tools/fake-provider` and a way to wipe and re-pair an emulator; the engine's behaviour is covered by the fake-store tests. The first real round trip is a device check (TODO.md). Each sync logs its time and size (for example `Sync: Pulled in 420 ms`) |
| (not in the plan) | **Join again from another screen** in Settings when the status says the key or token no longer works | Without it, a screen left behind by New household key had no way back |

### Still open

* Pairing link from a TV to a phone (TODO.md item 6): the copy-and-paste fallback works from a phone to a TV only.
* H4 and H5 (hand-over hint, WebDAV), phase E, D4, D6, A1, A4, plan 1 item 4.5, and the device checks for 1.0.24 to 1.0.91: unchanged, in TODO.md.
* Scratch branches `p2`, `p2r`, `p2s`, `p2base`, `pB`, `pC`, `pE1`, `pE2`, `pFA`, `pFB`, `pFC`, and now `p3base` and `plan3` can't be deleted from the workspace; delete them on GitHub when convenient.

### Benchmark: `main` (1.0.77) against the plan 3 branch

The same script as plan 2, on the CI emulator, five runs of the old code and three of the new. Medians, with the range in brackets. Sync was off in all of them. One extra run hung while making the test video and was cancelled, and the benchmark job of the final compile check was cancelled by GitHub's 30-minute limit at the same step, so both were repeated.

| What | `main` (1.0.77) | Plan 3 branch |
|---|---|---|
| First picture after tuning | 948 ms (916 to 1071) | 946 ms (921 to 996) |
| Start-up, 95th percentile frame | 1136 ms (829 to 1720) | 845 ms (812 to 848) |
| Browsing Home, 95th percentile | 204 ms (134 to 293) | 154 ms (153 to 169) |
| Holding Down in the channel list, 95th percentile | 39 ms (26 to 41) | 27 ms (23 to 32) |
| Holding Up in the channel list, 95th percentile | 24 ms (21 to 38) | 21 ms (11 to 23) |
| Coming back from the player, 95th percentile | 144 ms (64 to 223) | 107 ms (48 to 271) |
| Holding Down in the Movies grid, 95th percentile | 56 ms (41 to 60) | 52 ms (43 to 71) |
| Holding Up in the Movies grid, frames over 100 ms | 5 (1 to 8) | 5 (1 to 9) |
| Holding Up in the Movies grid, 95th percentile | 113 ms (28 to 458) | 205 ms (24 to 228) |
| Holding Down in the Series grid, 95th percentile | 28 ms (24 to 39) | 25 ms (23 to 28) |
| Standing still on the channel list, 95th percentile | 25 ms (22 to 92) | 55 ms (23 to 76) |

Two rows look worse in the middle value: Movies Up (95th percentile) and standing still on the channel list. In both the ranges overlap completely, the number of frames over 100 ms is the same (Movies Up) or zero (standing still), and the worst single frame in both is no worse (448 ms against 325 ms, and 97 ms against 81 ms). The old code spans as wide a range on its own, so this is read as run to run noise and not as a regression. No stretch was consistently worse, so the release went out. The same two rows are the ones to look at in a TV log, since the emulator uses a software renderer.
