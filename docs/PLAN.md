# Relay: performance, code and UI plan

Written 2026-11-11 against commit `f9ab6a5` (release 1.0.23). Line numbers refer to that commit; search by function name if they have moved.

Later plans: [PLAN-2.md](PLAN-2.md) (review and TV speed) and [PLAN-3.md](PLAN-3.md) (trailers, keys from the phone, sync between screens). Items from this plan that are still open are tracked in PLAN-3.md section 6 and in [TODO.md](TODO.md).

This plan is meant to be executed by a model that has not seen the earlier work. Read sections 1 and 2 before touching code. Do the phases in order. Each phase ends in one push to `main`, which builds and publishes one release.

## 1. Context

Relay is a personal IPTV player (Kotlin 2.0.21, Jetpack Compose BOM 2025.01, Media3 1.6.1, Coil 2.7) for one household: an Android phone and Sony, TCL and Samsung Google TVs, all 4K panels. The provider playlist is large (tens of thousands of channels, about 100,000 movies). The phone is fast. The TVs are the problem: after 1.0.22 the owner reports refresh and Live TV as "better, still slow".

Decisions already made by the owner:

| Question | Decision |
|---|---|
| How deep should efficiency work go | Tune the current in-memory design. No database. |
| Delivery | Small phases, one release per phase, so the TV can be tested between phases. |
| Scope | Review fixes, plus all open to-do items: split the player code, remove from Continue watching, Start over from the guide grid, lint warnings and the README note. |
| TV state | Better after 1.0.22, still slow. No TV log has been captured yet. |

Source layout (`app/src/main/java/dev/relay/tv/`):

| Folder | Holds |
|---|---|
| `data/` | `Catalog` (library in memory, load and refresh), `CatalogParser`, `Store` (SharedPreferences + JSON), language and version logic, `AppLog`, `StallWatch` |
| `ui/` | Compose screens: `Home`, `Live`, `Guide`, `Library` (Movies and Series), `SearchSettings`, `Details`, shared `Components` |
| `player/` | `PlayerActivity` (1,324 lines, View based), `Playback` (builds play requests), timeshift, recovery |
| `companion/`, `tv/`, `update/` | Phone companion web server, Google TV Watch Next, in-app updater |

## 2. Ground rules

1. **Never commit secrets.** No IPTV logins, no tokens, no keystore. Do not print them in logs. Do not change the signing or update-token steps in `.github/workflows/build.yml`.
2. **A push to `main` is a release.** Any push that changes files outside `**.md`, `docs/**`, `tools/**` builds and publishes `v1.0.N` to every device. Push once per phase, only when the phase is complete. Docs-only pushes do not build.
3. **CI is the compiler.** Android code can only be verified by the GitHub Actions run (unit tests, lint, R8 release build, emulator start-up check). After each push, wait for the run with `gh run list --limit 1` and `gh api repos/ghoshs89/TV-Relay/actions/runs/<id>/jobs`. The repo is private, so use the authenticated `gh` CLI, not anonymous `curl`. If the run fails, fix forward in the same phase.
4. **Pure logic gets unit tests.** Anything that can be written without Android or Compose types goes in a plain Kotlin object or function and gets a test under `app/src/test/java/dev/relay/tv/`. There are 103 tests today; none may be deleted to make a build pass.
5. **No behaviour changes except the ones listed.** Sorting order, language ranking, version merging, restrictions for kids profiles and the refresh safety checks (`RefreshGuard`, staging, strict parse) must give the same results as today. Existing tests encode most of this.
6. **Measure with the log.** `AppLog.i/w/e` lines are always saved; `AppLog.d` only when "Save detailed logs" is on. Every task that claims a speed-up adds or keeps an `AppLog` timing line so the owner's TV log can confirm it.
7. **Text shown to the user** is plain and short. No em dashes. Use the app's existing wording (for example "screen thread" is never shown to users).
8. **After each phase** add a row to the "Done" table in `docs/TODO.md` and tick the phase in section 12 of this file (docs-only commit, can ride with the phase push).
9. **Stop and ask the owner** if a task needs a decision not covered here, or if a TV log contradicts an assumption below.

## 3. Phase 0: read a TV log first (no release)

The owner installs 1.0.23 by hand on a TV, opens Relay, lets a refresh finish, opens Live TV, scrolls "All channels", then uses Settings, phone companion, Download logs and attaches the file.

Read these lines and fill in the table in section 11:

| Log line | Tells you |
|---|---|
| `Device: N cores, RAM … app memory limit … screen WxH at D dpi` | Core count, memory ceiling, real layout width (`W / (D/160)` dp) |
| `Read saved library in N ms: …; <per-file timings>` | JSON parse cost at start-up |
| `Prepared lists in N ms (channel languages … movie languages … sorting … channel index …); applied on screen thread in N ms` | Which indexing step dominates |
| `Downloaded get_*: X MB in Ns` | Network share of a refresh |
| `Checked new lists in N ms` | Second parse during refresh |
| `Picking channels for … took N ms`, `Channel list for … took N ms` | Screen-thread list building in Live TV |
| `StallWatch` warnings | Any other main-thread freeze, with stack frames |
| `Still working after Ns: <step>; memory A of B MB` | Where a long load sits, and memory pressure |

Use the numbers to confirm phase order. If "Read saved library" or "Prepared lists" is over about 8 s on the TV, phase 4 moves ahead of phase 3. If memory in use is above about 80% of the limit, do task 4.3 and 4.4 first within phase 4. If no log is available, proceed in the written order.

## 4. Phase 1: stop doing list work on the screen thread (Live TV)

Goal: opening Live TV, changing category, changing sort and pressing OK on a channel never block the main thread for more than a frame or two, even on "All channels".

### 1.1 Precompute channel sort keys and flags in the index

**Problem.** `LiveBrowse.apply` (`ui/LiveBrowse.kt`) sorts with `sortedBy { sortKey(it) }` and `sortKey` runs `splitChannelName` (regex) plus a regex replace. Kotlin's `sortedBy` calls the selector on every comparison, so 40,000 channels cost about 1.2 million regex runs per sort. The HD filter runs a regex per channel per rebuild. `LiveTab` also counts channels per language with `groupingBy` over every channel during composition (`Live.kt` line 123).

**Change.**
* In `Catalog.buildChannelIndex` (already off the main thread) compute and store per channel id: `sortKey: Map<Int, String>`, `isHd: Set<Int>` (or a `BooleanArray` indexed by list position), and `langCounts: Map<String?, Int>`. Add them to `ChannelIndex`, install them in `install()`, expose read-only accessors (`channelSortKey(id)`, `channelIsHd(id)`, `channelLangCounts`).
* Move `LiveBrowse.sortKey` and the `HD` regex into `data/` as pure functions so the index can call them and tests can cover them. Keep `LiveBrowse.sortKey(ch)` as a thin wrapper if other callers need it.
* Renamed channels: `applyCustom()` rebuilds the index, so keys stay correct. `applyCustom()` currently runs on the caller's thread; make it `suspend` and run `buildChannelIndex` in `Dispatchers.Default`, then `install` on the main thread. Update its callers.

**Accept.** Sorting A to Z and by number gives the same order as before (add a test comparing old and new ordering on a 500-name fixture with tags, numbers and punctuation).

### 1.2 Build the channel list off the main thread

**Problem.** `LiveTab` computes `base` (line 165) and `channels` (line 196) inside `remember { }`, which runs on the main thread during composition. Both are keyed on `store.version`, which changes on every write to the store (favourite toggle, sort change, history written by the player, kids usage). The category rows (`rows`, line 137) are rebuilt on every recomposition, including `ProviderNames.clean` for each category and `providerCats.any { }` per pinned item.

**Change.**
* New file `ui/LiveList.kt` with a pure function, no Compose types:
  ```kotlin
  data class LiveQuery(
      val cat: String, val sort: Int, val filters: Set<String>, val find: String,
      val restrict: Boolean, val hiddenIds: Set<String>, val langOrder: List<String>,
      val merge: Boolean, val favIds: List<Int>, val historyIds: List<Int>, val groupIds: List<Int>?,
  )
  class LiveResult(val query: LiveQuery, val base: Int, val channels: List<Channel>)
  fun buildLiveList(catalog: Catalog, q: LiveQuery, prefs: Map<String, Int>): LiveResult
  ```
  It contains the logic now in the two `remember` blocks plus `LiveBrowse.apply`, using the precomputed keys from 1.1. Deduplicate by channel id (`distinctBy { it.id }`) as the last step; task 2.2 needs unique ids.
* In `LiveTab`, build `LiveQuery` from state, then:
  ```kotlin
  var result by remember { mutableStateOf<LiveResult?>(null) }
  LaunchedEffect(query, catalog.channels, catalog.langVersion, st.preferredVersions) {
      val t = SystemClock.elapsedRealtime()
      val r = withContext(Dispatchers.Default) { buildLiveList(catalog, query, st.preferredVersions) }
      result = r   // LaunchedEffect cancels a stale run when the key changes
      val ms = SystemClock.elapsedRealtime() - t
      if (ms > 150) AppLog.w("Live", "Channel list for ${query.cat} took $ms ms off the screen thread (${r.channels.size} shown)")
  }
  ```
  Keep showing the previous `result` while a new one is computed. When `result?.query?.cat != query.cat`, show a one-line "Loading channels…" state in place of the list (task 5.4 styles it).
* Replace the `version` key with the narrow inputs: `favIds` and `historyIds` come from `store.favs(pid)` and `store.history(pid)` (read `store.version.intValue` once so the composable still subscribes, but pass only the derived id lists into `LiveQuery`, which is a data class, so an unrelated store write produces an equal query and no rebuild). `liveSort` likewise.
* Wrap `rows` in `remember(providerCats, countries, openCountry, pinned, groups, langCounts, langOrder, catalog.channelsByCat)`; precompute cleaned category names once per `providerCats` in a `Map<String, String>`; replace `providerCats.any` with a `Set` lookup.
* `Catalog` fields read from the background (`channels`, `channelsByCat`, `channelById`, language maps) are replaced wholesale, never mutated, so reading them from `Dispatchers.Default` is safe. Mark `channelById`, `movieById`, `seriesById` and the language maps `@Volatile`.

**Accept.** On the owner's TV log there are no `Picking channels` or `Channel list … took` warnings from the main thread, and `StallWatch` is quiet while changing category. Unit tests for `buildLiveList`: each category kind (favourites, history, all, language, own group, provider category), each sort, each filter, find text, merge on and off, restricted profile, hidden category.

### 1.3 Cheap play hand-off from a big list

**Problem.** `Playback.live` (`player/Playback.kt` line 44) maps every channel in the list to a `PlayItem` and calls `catalog.versionsOf` for each one, on the main thread, when OK is pressed. From "All channels" that is tens of thousands of lookups and allocations before the player opens. `Restrictions.admit` (`data/Access.kt`) then filters the whole list again.

**Change.**
* Stop precomputing `alternates` for every item. Compute them only for the item that is about to play: add `fun alternatesFor(streamId: Int): List<String>` on a small object the player can call (uses `catalog.versionsOf`, drops the id itself, applies the same category restriction as `Restrictions.admit` does today). Call it in `PlayerActivity.playLive` when a channel starts and in `tryNextVersion`. Keep `PlayItem.alternates` as the field that holds the result for the current item so `tryNextVersion` is unchanged in spirit.
* Keep the restriction rule in one pure function and keep `RestrictionsTest` "fallback versions" passing (adapt the test to the new entry point; it must still prove a kids profile never falls back into an adult category).
* Build the item list lazily: introduce `class LazyItems(private val channels: List<Channel>, private val make: (Channel) -> PlayItem) : AbstractList<PlayItem>()` with a small cache array, so only items that are zapped to are created. `PlayRequest.items` stays a `List<PlayItem>`. `Restrictions.admit` must not force the whole list when `restrict` is false (it already returns early through `Restrictions.NONE`; verify). When `restrict` is true, filter the `Channel` list by category before wrapping, instead of filtering `PlayItem`s.
* `jumpToNumber` (`PlayerActivity` line 1092) does `req.items.indexOfFirst { it.subtitle == num }`, which would force every item. Give `PlayRequest` an optional `numberIndex: ((Int) -> Int)?` built from the channel list, or search the channel list directly.
* Log `AppLog.d("Player", "Play request built in N ms for M channels")`.

**Accept.** Pressing OK on a channel in "All channels" opens the player with no visible pause. Channel up/down, number entry, the in-player channel list, previous channel and version fallback still work (emulator check passes; owner confirms on TV).

**Release after phase 1.** Suggested notes: "Live TV: faster category changes, sorting and channel start on TVs."

## 5. Phase 2: recompose less while browsing

### 2.1 Per-channel guide state

**Problem.** `Catalog.epgVersion` is one counter bumped on every now/next fetch (`Catalog.kt` line 593). `LiveTab` reads it at the top level (`Live.kt` line 208) and every `ChannelRow` reads it through `nowNext()`. While scrolling, each row that loads its guide recomposes the whole tab and every visible row. `ChannelOverlay.kt` line 56 does the same in the player.

**Change.**
* Replace `epgCache: ConcurrentHashMap` with `mutableStateMapOf<Int, Pair<Long, List<EpgEntry>>>()` (snapshot state map; writes from any thread are safe). `nowNext(id)` and `nowPlaying(id)` then subscribe only to that channel's entry. Keep reading `minute` there.
* Delete the `catalog.epgVersion` reads in `Live.kt` line 208 and `ChannelOverlay.kt` line 56 once those composables get their data through `nowNext`/`nowPlaying`. Keep `epgVersion` only if something still needs "the whole guide was cleared"; `clearGuideCache()` clearing the map already notifies readers.
* Cap the map at 800 channels: when it grows past that, drop the oldest 200 by timestamp. Same cap for `gridCache`.
* `searchGuide` iterates the cache from `Dispatchers.Default`; iterate over `epgCache.toMap()`.

**Accept.** With "Save detailed logs" on, scrolling a list no longer logs repeated list rebuilds; rows fill in their now/next text one by one.

### 2.2 Stable lazy lists

**Change.**
* Give every large lazy list a key and a content type: Live channel list (`itemsIndexed(channels, key = { _, c -> c.id }, contentType = { _, _ -> "ch" })`), category rows (key from `CatRow`), Guide grid rows, Movies and Series grid (`key = { it.id }`), Home rails (`key` from the item's focus key). Ids are unique after the dedupe in 1.2; for Movies and Series dedupe by id where the list is built.
* `ChannelRow`: Kotlin 2.0.21 has strong skipping on by default, so rows skip when their parameters are equal. Make that true: pass `fav: Boolean`, the `Channel`, and remembered callbacks. Replace `onClick = { Playback.live(ctx, channels, i) }` with a callback that takes the channel id and finds the index at click time, so the lambda does not capture `i` and `channels`.
* `focused` (`Live.kt` line 205) is read in the screen's `Row`, so each D-pad move recomposes the three columns. Hold it in a `MutableState<Channel?>` created once, pass the state object to `ChannelPanel`, and read `.value` only inside the panel. Derive "show panel" with `derivedStateOf { isTv || focused.value != null }`.
* `ChannelDetails` reads `System.currentTimeMillis()` several times during composition; read it once per composition into a `val nowSec`.

### 2.3 Guide grid

**Problem.** `GuideGrid` passes the animated scroll value `shown` to every `GuideRow` as a plain `Float` (`Guide.kt` line 160), so every visible row recomposes on every animation frame while the timeline moves. Each row also reads `store.version` (line 201).

**Change.**
* Pass `shown` as `() -> Float` (or the `State<Float>`) and read it only inside the existing `.offset { }` lambdas, which defers the read to layout.
* Replace the `store.version` read with a read of the reminder state the row needs (`ReminderCenter` should expose snapshot state; if it reads the store, wrap the reminder list in one remembered derived value at grid level and pass a `Set` of `(channelId, start)` down).
* Hoist the `SimpleDateFormat` in the time ruler into a file-level `private val`.

### 2.4 Edge fades without an offscreen buffer

**Problem.** `Modifier.fadeEdges` (`Components.kt` line 648) sets `CompositingStrategy.Offscreen`, so every scrolling list (10 call sites) is drawn into an extra full-size buffer each frame. On TV graphics chips that is a known cause of dropped frames.

**Change.** Draw the fade as a gradient from `Palette.bg` to transparent on top of the content in `drawWithContent`, with no `graphicsLayer`. This looks identical where the background is flat `Palette.bg`. Check each of the 10 call sites; where a list sits over an image or a `Palette.surface` panel, pass the right background colour as a parameter. If any site sits over a backdrop image, leave that one on the old implementation behind a `overImage = true` parameter.

### 2.5 Images

**Change** (`RelayApp.newImageLoader` and the logo call sites):
* Turn crossfade off for channel logos (pass `ImageRequest` with `crossfade(false)` for logos, keep it for posters), since a fade per row costs frames during fast scrolling.
* Remember failed logo URLs for the session (a capped `LruCache<String, Boolean>` of 2,000) and show initials straight away for those, so broken provider logo URLs are not retried on every scroll past.
* Log once per session how many logo URLs failed (`AppLog.i("Images", …)`).

**Release after phase 2.** Suggested notes: "Smoother scrolling in Live TV and the guide on TVs."

## 6. Phase 3: Movies, Series, Home and Search

### 3.1 Movies and Series lists

**Problem.** `MoviesTab` and `SeriesTab` (`ui/Library.kt`) build `items` in `remember` on the main thread, keyed on `store.version`. "All A to Z" runs `catalog.movies.sortedBy { it.name.lowercase() }`, which lowercases two names per comparison: millions of string allocations for 100,000 movies. Mixed lists re-sort by language rank on every rebuild.

**Change.**
* In `Catalog.Indexes` add `mvByName` and `srByName` (sort once with a precomputed lowercase key: `map { it to key }.sortedBy { it.second }.map { it.first }` or sort an index array). Expose as `moviesByName`, `seriesByName`.
* Move list building to a pure `buildTitleList(...)` in a new `ui/TitleList.kt` and run it in `Dispatchers.Default` with the same `LaunchedEffect` pattern as 1.2. Narrow the keys: only the favourites list matters from the store, and only for "My list". Cache the last result per `(category, langOrder, restrict, hidden)` in a small map so going back to a category is instant.
* Language-ranked sorts are stable sorts of an already ordered list by a small integer; implement as a bucket pass (one list per rank, then concatenate) instead of `sortedBy`.
* Tests: ordering of each special list matches the current implementation on a fixture.

### 3.2 Home

**Problem.** `HomeTab` reads `store.version` at the top and recomputes every rail list on every recomposition (`Home.kt` lines 300 to 310 and the `NEW_*`/`TOP_*` rows). `Rail` has no item keys. Tile clicks call `indexOf` on the list.

**Change.** Wrap each rail's list in `remember` keyed on its real inputs (`history`, `favs`, `catalog.moviesByAdded`, `restrict`, `hidden`, `catalog.langVersion`). Add `key` support to `Rail`. No visual change.

### 3.3 Search

`SearchTab` already runs off the main thread with a 350 ms delay. Two small changes: normalise each name at most once per search (the matcher currently normalises per token test; check `matcher` and hoist), and skip the search when the query is shorter than 2 characters. Log the search time at `AppLog.d`.

### 3.4 Store writes

`Store.write` encodes JSON and bumps `version` on the calling thread. `record()` re-encodes up to 400 history entries each time the player saves progress, and `addUsage` writes during kids viewing. Check how often `PlayerActivity` calls both (lines 183 and 1263). If more often than once every 15 s, throttle to that. Do not move encoding off the main thread in this phase; the cached value must stay consistent with what was written.

**Release after phase 3.** Suggested notes: "Movies, Series and Home open faster on TVs."

## 7. Phase 4: library load time and memory

Use the phase 0 numbers to order these. All four are independent.

### 4.1 Show channels before movies are ready

**Problem.** `applyParsed` (`Catalog.kt` line 357) indexes channels, movies and series together and installs everything at the end. Live TV is unusable until 100,000 movies have had their language detected and been sorted.

**Change.** Split `Indexes` into three stages run in order on `Dispatchers.Default`: channels (category languages, channel languages, version keys, channel index), then movies, then series. After each stage, switch to the main thread and install that stage's state, bump `langVersion`, and report progress with a specific step name ("Preparing channels", "Preparing movies", "Preparing series"). `loading` stays true until the last stage. Keep the single timing log line, now with a time-to-channels figure: `Channels ready after N ms; all lists after M ms`.

**Accept.** On a cold start Live TV shows channels while the top bar still shows a percentage. Movies and Series show their existing empty or loading state until their stage lands. No screen crashes when `movies` is empty and `channels` is not (check `Home`, `Search`, `Details` resume paths).

### 4.2 Parse the big lists in parallel

`CatalogParser.parse` reads six files one after another. Parse `get_live_streams`, `get_vod_streams` and `get_series` concurrently (three `async` on `Dispatchers.Default`), categories first. Keep `strict` semantics and the per-file timings. Progress reporting currently assumes sequential bytes; switch it to "sum of bytes read across files over total size". Only do this if the phase 0 log shows 4 or more cores and memory headroom; otherwise limit to two at a time. Keep `CatalogParserTest` passing and add one test that parallel and sequential parsing give equal results.

### 4.3 Intern repeated strings

Every `Channel`, `Movie` and `Series` holds its own copy of `categoryId`, `ext` and `year`, though only a few hundred distinct values exist. In `CatalogParser` keep a `HashMap<String, String>` per parse and pass those three fields through it. Log the count of distinct values at `AppLog.d`.

### 4.4 Do not keep text that is only needed on the details page

`Series` keeps `plot` and `genre` for every series in memory (`CatalogParser.kt` around line 100). Find the readers. If they are only on the series details page, which already calls `api.seriesInfo`, drop both fields from the in-memory model and take them from `seriesInfo`. If search or a list uses `genre`, keep `genre` (interned) and drop only `plot`. Log memory in use before and after in the load summary (already present).

### 4.5 Optional, only if phase 0 shows start-up parse over about 8 s: saved index

After a successful `applyParsed`, write a compact binary snapshot of the parsed lists and the language maps (`DataOutputStream`, with a format version and the source files' size and modified time in the header). On start-up, load it instead of parsing JSON when the header matches; fall back to JSON on any mismatch or error and delete the snapshot. This is the largest task in the plan. Ask the owner before starting it, with the measured numbers.

**Release after phase 4.** Suggested notes: "Channels appear sooner while the library loads; lower memory use."

## 8. Phase 5: major UI fixes

These come from reading the layout code, not from seeing the owner's TVs. Items marked **confirm on TV** need the owner to look at one screen before and after.

| # | Fix | Where | Detail |
|---|---|---|---|
| 5.1 | One type scale, bigger minimum on TV | `Components.kt`, all screens | Font sizes are literals from 9 sp to 26 sp across files. Add `object Type` with named sizes (`label`, `body`, `title`, `heading`), and a TV minimum of 12 sp. Replace literals in `ChannelRow`, `ChannelTile`, `PosterCard`, `GuideRow`, `ListRow` first. The 9 to 11 sp labels (CATCH-UP badges, guide times, poster subtitles) are the ones too small at TV distance. **Confirm on TV.** |
| 5.2 | Real icons instead of emoji and symbol characters | `Live.kt`, `Library.kt`, `Guide.kt` | "📌", "🗣", "▸", "▾", "★", "☆", "⟲", "⏰" are drawn by each TV's system font and vary by brand. Give `ListRow`, `PillButton` and the guide cell an optional leading `ImageVector` and use Material icons (add `material-icons-extended` only if R8 keeps the APK growth under 1 MB; otherwise draw the four or five shapes as small `ImageVector`s in a new `ui/Icons.kt`). Keep the text labels. |
| 5.3 | Channel panel fits a 540 dp tall screen | `ChannelDetails` in `Live.kt` | Up to five stacked pill buttons (Favourite, Catch-up, Start over, Versions, Options) leave little room for the programme list. Lay them out two per row, or keep Favourite and Catch-up visible and move the rest into the existing Options dialog. Also show the cleaned channel name (line 520 shows the raw provider name while the list shows the cleaned one). **Confirm on TV.** |
| 5.4 | Loading and empty states in lists | `Live.kt`, `Library.kt` | After phases 1 and 3 lists arrive asynchronously. Show a consistent centred "Loading channels…" or "Loading…" with the small spinner, never a flash of "No channels in this category." |
| 5.5 | Error banner you can act on | `Home.kt` line 255 | `catalog.error` is plain text pinned to the bottom until the next load. Make it a focusable bar with "Retry" (calls `catalog.load(force = true)` in `appScope`) and "Dismiss" (clears `catalog.error`), reachable with D-pad down from the content, and auto-clear on a successful load. |
| 5.6 | Live TV column widths from the real screen | `Live.kt` line 213 | Widths switch only at 900 dp. With the dp width from the phase 0 log, pick category, list and panel widths so the channel list keeps at least 45% of the width. Hide the 52 dp channel-number column when no channel in the list has a number. **Confirm on TV.** |
| 5.7 | Focus returns to the same row | `Live.kt`, `Library.kt` | After 2.2 adds keys, verify that returning from the player or a details page restores focus to the same item (`FocusMemory`), including when the list was rebuilt in the background. Request focus only after `result` for the current query has arrived. `NavFocusTest` covers the pure part; extend it if logic changes. |

**Release after phase 5.** Suggested notes: "Clearer text and icons on TVs, tidier channel panel, retry button on load errors."

## 9. Phase 6: the two small features

### 6.1 Remove items from Continue watching (to-do 1)

* On a Continue watching card, a long press of OK (TV) or long tap (phone) opens a small dialog: "Remove from Continue watching", "Cancel". `Focusable` has no long-press today: add an optional `onLongClick` using `combinedClickable`, and for the D-pad treat a held centre key as a long click (handle `KeyEvent` repeat count in `onPreviewKeyEvent`). Menu key opens the same dialog where the remote has one.
* Remove calls `Store.removeHistory(pid, key)` (exists, unused) and removes the Google TV home entry: add `WatchNext.remove(ctx, pid, key)` beside `WatchNext.update` in `tv/WatchNext.kt`, run on `Dispatchers.IO` in `appScope`.
* Same dialog on Recently watched channels ("Remove from Recently watched").
* Add a hint line under the row title the first time only if it can be done without a new setting; otherwise mention it in the README.
* Test: `removeHistory` drops only the matching key.

### 6.2 Start over from the guide grid (to-do 3)

In `GuideRow` (`Guide.kt`), OK on the current programme plays the channel. When `ch.hasArchive` and the programme is live, show a two-choice dialog instead: "Watch live" and "Start over" (`Playback.catchup(ctx, ch, e)`), with "Watch live" focused first. Channels without catch-up keep today's behaviour.

**Release after phase 6.**

## 10. Phase 7 and 8: player split, lint, README

### 7. Split `PlayerActivity.kt` (to-do 2)

No behaviour change. One commit per extraction so a regression can be bisected, one push at the end. Target shape, each a plain class taking the `ExoPlayer`, a small callback interface and what it needs from the activity:

| New file | Takes over | Lines today (about) |
|---|---|---|
| `player/KidsLimitTracker.kt` | `trackViewing`, `flushUsage`, `stopForLimit` and their fields | 155 to 200 |
| `player/FailurePanel.kt` | `buildFailPanel`, `showFailure`, `diagnose`, `hideFailure`, `retryAfterFailure`, `httpCodeOf` | 450 to 600 |
| `player/LiveSession.kt` | `switchLive`, `playLive`, `reconnectLive`, `tryNextVersion`, `jumpToNumber`, digit entry, previous channel | 701 to 760, 1014 to 1125 |
| `player/TimeshiftController.kt` | `tsItem`, `stopTimeshift`, `tsSeek`, `tsGoLive`, `updateTsBar`, ticker | 761 to 825 |
| `player/TrackPrefs.kt` | `applyTrackPrefs`, `rememberTracks`, `audioOrder`, `textOrder`, subtitle style | 551 to 560, 645 to 670, 859 to 893 |
| `player/PlayerViews.kt` | `buildViews` view construction | 298 to 450 |

`PlayerActivity` keeps lifecycle, the `Player.Listener`, key dispatch and picture-in-picture, and should end under about 450 lines. Move pure decisions (which index is next, whether to fall back, limit verdict timing) into functions with unit tests as they are extracted. The emulator check only proves the app starts; after this phase the owner must test: play live, zap, number entry, pause and rewind live, version fallback, a movie with resume, next episode, kids limit warning, failure panel.

### 8. Lint, README, tidy

* Download the lint report from the latest run's `reports-1.0.N` artifact (`gh run download <id> -n reports-1.0.N`). Fix real issues (unused resources, missing `contentDescription` where an image is the only content of a clickable, API-level checks, obsolete dependencies). For warnings that are deliberate, add them to `lint.xml` or a targeted `@Suppress` with a one-line reason. Target: under 10 remaining, each explained.
* README: the repo is private again and the update token is repo-scoped and read-only. The "Keep this repository private" note is correct; add two lines on the token (fine-grained, Contents: Read-only, this repo only, and that updates stop when it expires). This closes to-do 4.
* Split `ui/SearchSettings.kt` (1,103 lines) into `Search.kt` and `Settings.kt`, moving code only.
* Bump nothing in `build.gradle` unless lint flags it as a security or compatibility issue.

**Release after phase 7, and again after phase 8.**

## 11. Measurements

Filled from the first TV log (onn 4K Plus, 4 cores, 1917 MB RAM, 384 MB app limit, 960 dp wide), downloaded 11 Nov 2026. The last column is filled from the log taken after plan 4 ([PLAN-4.md](PLAN-4.md) section 3, routine M4).

| Measure (TV log, onn 4K Plus) | 1.0.22 (8 Nov) | 1.0.77 (11 Nov) | 1.0.91 (11 Nov) | After plan 4 |
|---|---|---|---|---|
| Layout width, cores, memory limit | 960 dp, 4, 384 MB | same | same | |
| Process start to channels on screen | 73.4 s and 70.0 s (one stage) | 53.0 s | 56.4 s | |
| Process start to all lists ready | same as above | 59.0 s | 62.7 s | |
| Read saved library | 40.7 s and 41.6 s (one file at a time) | 27.2 s | 29.6 s | |
| Prepare lists (slowest step) | 28.5 s (channel versions 14.8 s) | 30.1 s (channel versions 13.4 s) | 32.2 s (channel versions 14.4 s) | |
| Refresh: parse again, then prepare | 56.0 s, 32.8 s | 26.4 s, 29.2 s | no refresh in the log | |
| Memory after load, after refresh (of 384 MB) | 152 to 160 MB, 299 MB | 150 MB, 241 MB | 151 MB | |
| Slowest Live list build | "All channels" 259 ms | not in the log | 4 ms (recent channels; All channels not opened) | |
| Movies list, all titles (off the screen thread) | not logged | not logged | 444 ms | |
| Search (whole-guide search on) | not logged | not logged | 1.2 to 2.2 s | |
| First picture after tuning | not logged | not logged | median 902 ms (773 to 1,333, 7 tunings) | |
| Real screen-thread stalls | none (2 false reports, see plan 4 M2) | none | none | |

Decision recorded: 4.5 (saved library index) go. Reading the saved library takes 29.6 s against the 8 s threshold. Memory after load is 39% of the limit, so memory work is not needed first.

## 12. Phase checklist

| Phase | Content | Size | Released as |
|---|---|---|---|
| 0 | Read a TV log, fill section 11 | S | none (skipped: no TV log was available) |
| 1 | Live TV list work off the screen thread, cheap play hand-off | L | 1.0.24 |
| 2 | Per-channel guide state, stable lists, guide grid, fades, images | M | 1.0.25 |
| 3 | Movies, Series, Home, Search, store write frequency | M | 1.0.26 (3.4: no change needed) |
| 4 | Staged load, parallel parse, string interning, drop unused text (4.5 optional) | M to L | 1.0.27 (4.5 not done) |
| 5 | UI fixes 5.1 to 5.7 | M | 1.0.28 (5.7 already satisfied by the existing design) |
| 6 | Remove from Continue watching, Start over in guide grid | S | 1.0.29 |
| 7 | Split the player | L | 1.0.30 (partly: `PlayerActivity.kt` 1,324 to 1,071 lines; `LiveSession`, `TimeshiftController` and `PlayerViews` not extracted yet) |
| 8 | Lint, README, split SearchSettings | S | 1.0.31 (README note went out with 1.0.30) |

## 13. Risks

| Risk | Guard |
|---|---|
| Background list building shows a stale list for the wrong category | Result carries its `LiveQuery`; UI only shows it when it matches, and `LaunchedEffect` cancels stale work |
| Duplicate ids crash keyed lazy lists | Dedupe by id where each list is built; test with a fixture containing duplicates |
| Lazy play items break number entry or the in-player channel list | Tasks in 1.3 name both call sites; owner tests zapping after phase 1 |
| Kids restrictions weakened by moving the alternates filter | `RestrictionsTest` must still cover fallback versions |
| Staged load exposes half-loaded state | Each stage installs a complete, consistent set for its own kind; screens already handle empty movies |
| Player split changes behaviour | Code moves only, one extraction per commit, manual test list in phase 7 |
| A phase makes a TV worse | One release per phase; the previous APK stays on the Releases page for a manual roll back |
