# Plan 6: all four provider servers, VPN aware, in one build

For the model that executes this. Read it all before starting. The owner says when to execute; until then nothing here is done.

## 0. Scope and hard rules

**In scope:** Relay uses all of the provider's servers well, including the ones meant for use with a VPN, and switches between them quickly when one is down or when a VPN is turned on or off.

**Out of scope (owner's decisions):** Stremio, Real-Debrid and TorBox (parked in a new "Ideas" section at the end of `docs/TODO.md`); any change to version numbering (releases stay `1.0.<run number>`).

**GitHub Actions minutes are nearly used up for the month (about 1,950 of 2,000 on 12 Nov).** A release run on `main` costs about 20 to 24 billed minutes today: the `build` job 9 to 11 minutes and the `bench` job 11 to 13. So:

1. **Exactly one workflow run for this plan**: the push to `main` at the end. No `gh workflow run` compile checks, no branch dispatches, no re-runs "to see". Pushing a branch other than `main` does not start the workflow (the push trigger is `main` only), so branch pushes are free and fine for backup.
2. Phase W (below) stops the `bench` job from running on pushes, which brings that one run down to about 10 to 11 minutes.
3. If the one run fails, do not push again straight away. Read the log (`gh api repos/ghoshs89/TV-Relay/actions/jobs/<job id>/logs`; reading logs costs no minutes), fix, check locally as far as possible, then push once more. That second run is the last one this month; if it would not be clearly fixed, stop and tell the owner.
4. Everything else from earlier plans still applies: one commit per phase, the final commit's message body is the release notes, tests for every pure function in the same commit, never delete tests, no secrets anywhere (the provider's hosts, username and password never go into the repo, tests or docs; tests use `example.com` style hosts), don't touch the signing or update-token steps in `build.yml`, attribution trailers on every commit.

**No Android compile is possible in the session container** (no Android SDK, and the network can't reach Google's Maven). So:

* Put every rule in pure Kotlin files with no Android imports and check them with the local harness (`kc/check.sh`, JUnit 4) before committing.
* Keep the Android-facing code small and in the style of the code around it; re-read each Android edit against the APIs it calls (names, nullability, API level 24).
* `ProviderText` uses `android.net.Uri`, which returns null in unit tests here (`unitTests.isReturnDefaultValues = true`). Put the new label logic in a separate pure object so it can be tested.

## 1. What exists today (main at 6904363, release 1.0.110)

* `Account(name, hosts: List<String>, username, password)` in `data/Models.kt`. Persisted in `Store`, and synced between screens as one value (`Store` sync apply: `accountChanged = go(snapshot.account, target.account, cur.account)`).
* `XtreamApi.hostsOrdered()`: the last server that answered (`api.host`, persisted as `store.activeHost`) first, then the rest in saved order. `withHosts` tries them in turn; `streamUrls`, `catchupUrls` and timeshift URLs list every server in that order, and the player's `RecorderPolicy` moves through them.
* `ProviderText.parse` pulls every bare host, the username and the password out of a pasted provider message, in message order. The TV form (`ui/Onboarding.kt`) and the phone page (`companion/CompanionServer.kt` form, `companion/CompanionUi.kt`) both use it. So all four hosts can already be saved: paste the whole message, or list the extra hosts under Backup servers.
* OkHttp: `connectTimeout(15 s)`, `readTimeout(60 s)` for everything (`RelayApp.kt`). A dead server costs up to 15 s before the next is tried, on every call until one answers.
* `ACCESS_NETWORK_STATE` is already in the manifest.
* Sync side effect to watch: when the account value changes on another screen, `SyncRunner.afterApply` clears the catalog and reloads the whole library (about a minute on the TV). The same happens on the phone page when hosts change (`CompanionUi.kt`: `changed = ... || prevAcc?.hosts != acc.hosts`).

**The gap:** the provider says two hosts are for use without a VPN and two with one. Relay doesn't know which is which. With a VPN on, it tries the no-VPN hosts first and waits up to 15 s on each before reaching a VPN host. The same happens after the VPN is turned off.

## 2. Design

### 2.1 Which network a server is for

```kotlin
@Serializable enum class HostNet { @SerialName("any") ANY, @SerialName("no_vpn") NO_VPN, @SerialName("vpn") VPN }

@Serializable
data class Account(
    val name: String,
    val hosts: List<String>,
    val username: String,
    val password: String,
    val hostNets: Map<String, HostNet> = emptyMap(),   // host -> network; missing means ANY
)
```

* `hosts` stays exactly as it is, so the stored account, the sync file and older Relay versions all keep working (`Jsons.json` has `ignoreUnknownKeys = true`, so an older screen ignores `hostNets`). `hostNets` keys are normalised hosts (`XtreamApi.normalizeHost`); entries for hosts no longer in `hosts` are dropped when saving.
* `fun Account.sameLogin(o: Account?): Boolean` = same username, password and the same set of hosts. Use it wherever "the login changed" decides a library reload:
  * `SyncRunner.afterApply`: if the account changed but `sameLogin` holds, only set `app.api.account = app.store.account`. No catalog clear, no reload, and keep `api.host` unless the new tags make it unsuitable (ordering handles that).
  * `CompanionUi.kt` and `Onboarding.kt` save paths: a tag-only change saves the account and updates `api.account`, without signing in again or reloading.

### 2.2 Reading the tags from a pasted message (pure)

New `data/HostLabels.kt`, no Android imports:

```kotlin
object HostLabels {
    /** [labels]: for each host in message order, the text between the previous URL (or the start) and this URL. */
    fun tags(labels: List<String>): List<HostNet>
}
```

Rules, in order, on each label lowercased with runs of spaces collapsed:

1. Contains "without vpn", "no vpn", "non vpn", "non-vpn", "w/o vpn" or "not using vpn" → `NO_VPN`.
2. Otherwise contains the word "vpn" → `VPN`.
3. Otherwise `ANY`.
4. Then, across the message: if **any host is tagged `VPN`**, every host left at `ANY` becomes `NO_VPN`. (A provider that marks some servers "for VPN" means the unmarked ones are its normal servers.) If no host is tagged `VPN`, leave the `ANY` ones alone.

For a message shaped like the owner's (main server, "backup … without vpn", "if you use vpn please use …", "backup … with vpn") this gives `NO_VPN, NO_VPN, VPN, VPN`.

`ProviderText.Result` gains `nets: Map<String, HostNet>`. `ProviderText.parse` records each URL match's label (text from the end of the previous match, or from the start of the text, to the start of this match) and calls `HostLabels.tags`. A host that appears twice keeps its first tag.

### 2.3 Order of servers (pure)

New `data/HostOrder.kt`:

```kotlin
object HostOrder {
    /**
     * [vpnOn]: null when unknown (treated as "every server suits").
     * [coolingUntil]: host -> time until which it is skipped after failing.
     */
    fun order(
        hosts: List<String>, nets: Map<String, HostNet>, vpnOn: Boolean?,
        sticky: String?, coolingUntil: Map<String, Long>, now: Long,
    ): List<String>
}
```

1. A host **suits** the current network when its tag is `ANY`, or `VPN` while a VPN is on, or `NO_VPN` while none is on. With `vpnOn == null`, every host suits.
2. The result is: the suitable hosts, then the unsuitable ones (a last resort, since a "with VPN" server may still answer without one). Within each group, the saved order is kept, except that the sticky host goes first (if it is in the group) and hosts that are cooling down go to the end of their group.
3. Every host appears exactly once. The list is never empty when `hosts` isn't. Unknown hosts in `sticky` or `coolingUntil` are ignored.

`XtreamApi.hostsOrdered()` becomes a call to `HostOrder.order(acc.hosts, acc.hostNets, network.vpnOn, host, health.coolingUntil(), clock())`. Every caller (`withHosts`, `streamUrls`, `catchupUrls`, timeshift, the player) gets the new order without further changes.

### 2.4 Server health (pure, in memory only)

New `data/HostHealth.kt` with an injected clock:

* `failed(host, error)`: the host cools down for 10 minutes (`COOL_MS = 600_000`). It also records the error's short text and the time.
* `answered(host, ms)`: clears the cool-down and records how long the answer took.
* `coolingUntil(): Map<String, Long>`; `snapshot(): List<HostStatus>` for Settings and the log.
* Not persisted; a fresh start forgets it. Only `withHosts` (the API calls) feeds it. Stream failures do not: one channel failing says little about the server, and the player already moves through servers on its own.

### 2.5 Faster failover

In `withHosts`: while more than one host is left to try, use a client with `connectTimeout(5 s)` (built once with `http.newBuilder()`, same pool). The last host keeps the normal 15 s, so a slow network still gets a fair chance. Read timeouts are unchanged (lists are large).

### 2.6 Knowing whether a VPN is on (Android, kept small)

New `net/VpnWatch.kt`:

* `ConnectivityManager.registerDefaultNetworkCallback` (API 24). In `onCapabilitiesChanged(network, caps)`, `vpnOn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)`. In `onLost`, `vpnOn = null`. Start with a one-off read of `getNetworkCapabilities(activeNetwork)`.
* This is the app's own default network, so a VPN app that leaves Relay out (split tunnelling) correctly counts as "no VPN" for Relay.
* When `vpnOn` changes from one known value to the other: log `Network: VPN on` / `VPN off`, call `app.http.connectionPool.evictAll()` (sockets opened on the old network are useless), and post nothing else; the next call uses the new order. If the player is playing, leave it alone. Its own recovery moves through the servers in the new order if the stream drops.
* Registered once in `RelayApp.onCreate`, never unregistered (process-lifetime). Wrap registration in `runCatching`: some TV builds throw `SecurityException` here; then `vpnOn` stays null and the behaviour is today's.

### 2.7 Screens

* **Settings → Servers** (new, behind the same parent-PIN gate as "Change servers or login"): one row per saved server showing the host, its network ("Any network", "Without VPN", "With VPN"), and its state (in use, answered in N ms, failed N min ago: reason, or cooling down). Pressing a row cycles its network tag and saves (a tag-only change, see 2.1). A line at the top: "VPN: on / off / unknown". The existing "Server in use" info line adds the tag.
* **TV sign-in form** (`Onboarding.kt`): unchanged fields. When filled from a pasted message, the note says how many servers were found and how many are for VPN. Tags from the paste are saved with the account.
* **Phone page**: the paste box already fills everything. After a paste, show the found servers as a list with a select per server (Any / Without VPN / With VPN), prefilled from the paste. Submit sends the tags with the hosts. `CompanionUi` builds `hostNets` from them.
* **Log header** (`RelayApp` state dump): `Servers saved: 4 (2 without VPN, 2 with VPN); VPN: on; in use <host>`, then one line per server from `HostHealth.snapshot()`. Hosts are already logged elsewhere; the username and password stay redacted as now.

## 3. Phases (one commit each)

| Phase | Files | Done when |
|---|---|---|
| W: one cheap run | `.github/workflows/build.yml`: add a boolean `workflow_dispatch` input `bench` (default false); `bench` job gets `if: github.event_name == 'workflow_dispatch' && inputs.bench`. Nothing else in the file changes. README "Benchmark" paragraph says how to start one by hand. | YAML parses (`python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/workflows/build.yml`); diff touches only those lines. |
| H1: tags | `Models.kt` (`HostNet`, `Account.hostNets`, `sameLogin`), `HostLabels.kt`, `ProviderText.kt`; tests `HostLabelsTest`, `AccountLoginTest` (`sameLogin` only; serialization round trip with and without `hostNets` goes in a CI-only test, `AccountJsonTest`) | Local harness passes `HostLabelsTest` and `AccountLoginTest`. |
| H2: order and health | `HostOrder.kt`, `HostHealth.kt`, `XtreamApi.kt` (`hostsOrdered`, `withHosts` records health and uses the short connect timeout), `net/VpnWatch.kt`, `RelayApp.kt` (create and register `VpnWatch`, give `XtreamApi` the watch, health and clock); tests `HostOrderTest`, `HostHealthTest` | Local harness passes both. `HostOrderTest` covers: VPN on / off / unknown; sticky suitable and unsuitable; cooling hosts; all hosts cooling; unknown sticky; empty tags (today's behaviour exactly: sticky first, then saved order); one host. |
| H3: no needless reloads | `SyncRunner.afterApply`, `CompanionUi.kt`, `Onboarding.kt` use `sameLogin` | Re-read: a tag-only change never calls `catalog.clear()` or `catalog.load()`. |
| H4: screens | `ui/Settings.kt` (Servers screen and info line), `ui/State.kt` (route), `companion/CompanionServer.kt` (per-server select, plain HTML and JS as the page already uses), `CompanionUi.kt`, `Onboarding.kt` note, `RelayApp.kt` log header | Re-read against neighbouring screens; D-pad focus order on the Servers list follows the existing `Setting` rows. |
| Docs | `README.md` (Servers: paste all of your provider's servers; the VPN tags; Settings → Servers; one sentence in Troubleshooting: "If channels take long to start with a VPN on, check Settings → Servers: each server should say whether it is for use with a VPN"), `docs/TODO.md` (Done row; a new "Ideas" section at the end with Stremio, Real-Debrid and TorBox, parked by the owner for later), this file's Outcome section (release number, deviations, what was not verified) | The final commit's message body is the release notes (section 5). |

Then fetch `main`, rebase onto it if it moved (another session merged PR #1 on 12 Nov, so it can move), and push **once**: `git push origin HEAD:main`. Verify the release with `gh api repos/ghoshs89/TV-Relay/releases --jq '.[0]'` (tag, APK asset, notes). The release number is the next workflow run number; read it from `gh run list` after the push and put it in the docs with a docs-only commit afterwards if the prediction was wrong (docs-only pushes to `main` don't start the workflow).

## 4. Checks without CI

1. Local harness for every pure file and test above (plus the existing pure tests that touch `ProviderText`'s neighbours, so nothing regresses).
2. A written self-review before the push, in the Outcome section: every Android API used (`registerDefaultNetworkCallback`, `NetworkCapabilities.hasTransport`, `TRANSPORT_VPN`, `connectionPool.evictAll`) with its import and minimum API level; every changed `@Serializable` class; every call site of `hostsOrdered`.
3. Lint: `registerDefaultNetworkCallback` needs `ACCESS_NETWORK_STATE`, which the manifest has. No new strings that lint flags (hard-coded text follows the existing pattern in these files).

## 5. Release notes (final commit body)

"Relay now knows which of your provider's servers are meant for use with a VPN. Paste the provider's message (or list the VPN servers on the phone page) and it saves all of them, marks the VPN ones, and picks the right server whether your VPN is on or off. A server that isn't answering is skipped for 10 minutes, and the next one is tried after 5 seconds instead of 15. Settings → Account shows each server and lets you change how it is marked."

## 6. Device check for the owner (after release)

1. Phone page → paste the provider's whole message → check the four servers show "Without VPN" twice and "With VPN" twice → Save.
2. With the VPN off, open Live TV and tune a channel. Settings → Servers shows a "Without VPN" server in use.
3. Turn the VPN on, tune another channel. It should start within about 5 seconds, and Settings → Servers should show a "With VPN" server in use.
4. Send the log from Settings → Diagnostics if either step is slow.

## 7. Stop and ask only if

* The one run fails and the fix is not clear from its log.
* `main` changed in a way that conflicts with these files beyond a simple rebase.

## 8. Outcome

**Released as 1.0.111** with one workflow run (the push to `main`). No compile checks or benchmark runs were started for this plan.

| Phase | Commit | What |
|---|---|---|
| W | a6acb73 | The `bench` job runs only from Run workflow with **Also run the benchmark** ticked |
| H1 | fbf1564 | `HostNet`, `Account.hostNets`, `sameLogin`, `HostLabels`, marks from `ProviderText` |
| H2 | cd4401b | `HostOrder`, `HostHealth`, `ServerText`, `VpnWatch`, ordering and 5 s connect in `XtreamApi`, the log header |
| H3, H4 | fbea242 | Server rows in Settings → Account, marks in the TV sign-in form, the phone page's VPN field, no library reload for a mark-only change |
| Docs | last commit | README, `TODO.md`, this section |

### Deviations from this plan

* **This file was written as `PLAN-5.md`**, which overwrote the `PLAN-5.md` another session had merged to `main` the same day (PR #1). That file is restored unchanged and this plan is `PLAN-6.md`.
* `Account.hostNets` holds plain strings (`"no_vpn"`, `"vpn"`), not the enum, so a value from a newer Relay can never make the account unreadable, and it is left out of the JSON when empty (`@EncodeDefault(NEVER)`), so an account without marks saves and syncs byte for byte as before.
* `HostOrder.order` takes `netOf: (String) -> HostNet` instead of a map.
* No separate Settings → Servers screen: the server rows sit in Settings → Account under "Server in use", in saved order so a row doesn't move when pressed, with a VPN line above them. Pressing a row cycles its mark behind the parent PIN.
* The phone page has one optional field, "Servers for use with a VPN", instead of a select per server (that would have needed the message parser again in JavaScript). Listed servers become "with VPN" and the rest "without"; left empty, the marks come from the pasted message, else from before (`HostLabels.merge`).
* H3 and H4 are one commit: they change the same sign-in code.
* `HostHealth.describe` cuts an error to 60 characters (not 80) so the Settings row stays readable.
* The previous Done row "not released (branch `claude/loving-hopper-jtzdvb`)" in `TODO.md` now says 1.0.110, the release built from that merge.

### Checks

* Locally (JUnit 4, pure Kotlin): `HostLabelsTest` (9), `AccountLoginTest` (6), `HostOrderTest` (14), `HostHealthTest` (5), `ServerTextTest` (7): 41 tests, all pass. `AccountJsonTest` (4) needs the serialization plugin and runs on CI only.
* Self-review of the Android side: `ConnectivityManager.registerDefaultNetworkCallback` (API 24, the minimum; `ACCESS_NETWORK_STATE` already declared), `getNetworkCapabilities(activeNetwork)` (API 23), `NetworkCapabilities.TRANSPORT_VPN` (API 21), `OkHttpClient.connectionPool.evictAll()`; both `ConnectivityManager` calls wrapped in `runCatching`. Changed `@Serializable` class: `Account` only (new field has a default; `ignoreUnknownKeys` covers older screens). Every caller of `hostsOrdered` (`withHosts`, `streamUrls`, `xmltvUrl`, `catchupUrls`, timeshift, the log, Settings) goes through `HostOrder` with no other change.
* Not verified: nothing here has run on a device or an emulator before the release run; the release run's emulator smoke test is the first. The device check is in `TODO.md` under "Check on a device".
