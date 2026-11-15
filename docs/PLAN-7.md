# Plan 7: Back asks before closing Relay, updates without a token, sharing with a friend

Status: executed; see section 6 for exactly what was done. (Plan 6, all four provider servers, was written and released by another session in the meantime: this is plan 7.)

## 1. Context

The owner asked: check whether the repository is now public; stop the app closing by accident when Back is pressed twice and make it obvious the user is trying to exit; update the app and the documentation; release a fresh APK. The earlier instruction "build only once, at the end" still applies, so the release build on `main` is the one build.

### What the check found

| Question | Finding |
|---|---|
| Is `ghoshs89/TV-Relay` public? | **Yes.** GitHub's unauthenticated API answers `"private": false, "visibility": "public"`. |
| Is a secret in the now-public history? | **No.** A scan for `github_pat_…` strings finds only made-up test values (`MADEUP…`, `ABCDEFG…` in three sync tests). No keystore, `.env` or `signing.properties` was ever committed. The signing key, its password and the update token live only in GitHub Secrets. |
| Is sync still writing to this repository? | **Yes, and it is now public.** 93 "Relay sync" commits, the latest on 15 Nov at 10:47, change `relay-sync.bin` (4,058 bytes, AES-256-GCM). Anyone can download it and its whole history. The contents stay unreadable without the household key, which never leaves your screens; what a stranger can see is that it exists, how big it is and when you use Relay. `ghoshs89/Relay-TV_Sync` still holds only its README, so no screen has moved. |
| Does the public repo break sync? | No. Relay refuses a public repository only when sync is **turned on** (`GitHubApi.repoCheck`); screens that already sync keep going. |
| Does the public repo break updates? | **Not yet, but it will.** Installed builds send the baked-in update token as `Authorization: Bearer …`. GitHub answers 401 to an invalid token even for a public repo, so the moment that token expires or is deleted, every installed build stops updating. A build that sends no token at all keeps working on a public repo. |
| What does the token still protect? | Nothing: read-only on a repository that anyone can read. It sits in every published APK (now publicly downloadable), so it should go. |

### The back button today

`MainActivity` has one `BackHandler`, enabled only while a detail page is open (`nav.stack.size > 1`). At the root screens (Home, profile picker, sign-in) there is no handler, so Android closes the app on the **first** Back press, from any tab. In the player, Back twice within 2.5 seconds leaves the player; the hint is a small three-second line at the top left reading "Press Back again to exit", which does not say what will be left, and a second quick press on Home then closes the app. Nothing asks.

## 2. Changes

### 2.1 Back asks before closing the app (both phones and TVs)

New `ui/ExitDialog.kt`, using the existing `TvDialog`, `PillButton` (including its `danger` style) and `InitialFocus`:

* Title **"Close Relay?"**, one line: "Relay will close. Pick Stay to keep using it."
* Two buttons: **Stay in Relay** (primary, has focus) and **Close Relay** (danger style, red). The dialog's own dismiss (the Back key or a tap outside) means *stay*, so a second accidental Back press can never close the app; closing takes a deliberate OK on **Close Relay**, which calls `Activity.finish()`.
* In `AppRoot` (`MainActivity.kt`), add `BackHandler(enabled = nav.stack.size == 1) { showExit = true }` beside the existing one. It covers Home on every tab, the profile picker and the sign-in screen. Dialogs and the keyboard handle Back themselves first, so they are unaffected. `LibrarySetup` keeps its own handler.
* Nothing changes when a detail page is open: Back still goes to the page underneath.

### 2.2 The player says what Back will do

In `player/PlayerActivity.kt` (`onBack`):

* Replace the small top-left line by a **centred, larger prompt** (a new `leaveHint` view next to `overlay`, 26 sp, accent border): **"Press Back again to leave the player"**. The live-channel case keeps its channel line ("flipped back to <channel>") in the existing overlay and shows the same centred prompt.
* The 2.5 second window and two-press rule stay as they are; only the wording and prominence change. Leaving the player returns to the screen you came from, which is now guarded by 2.1.

### 2.3 Updates work without a token (needed now the repo is public)

* `update/Updater.kt`: send the `Authorization` header only when `BuildConfig.UPDATE_TOKEN` is not blank; `enabled` is always true; download the APK from the release's `browser_download_url` when there is no token (no API rate limit), and from the API asset URL when a token exists (a private fork). Error texts: 403 without a token explains GitHub's request limit; 404 says no release was found. A small pure function for "which headers, which URL" gets a unit test.
* `app/build.gradle.kts`: keep the optional `UPDATE_TOKEN` field (empty by default); no other change.
* `.github/workflows/build.yml`: stop passing `RELAY_UPDATE_TOKEN` into the build and delete the "Check update token" step, so published APKs carry no token. This touches an update-token step that earlier plans left alone; it is needed here, and signing steps are not touched. The repository secret itself is left in place until the owner deletes it (section 4).

### 2.4 Documentation

* `README.md`: replace the "keep this repository private" banner (it says the opposite of the truth now) with a note that the repository is public, holds no logins or keys, and that anyone can download the builds; rewrite *Update token* under Maintaining Relay as "only if the repository is private again", fix *Updates stopped arriving*, add to the sync section that the app's own repository is public so the sync file must never live there, and update the two Back rows in the remote tables ("Back asks before Relay closes").
* `docs/TODO.md`: item A (move sync) becomes urgent and says why; add the revoke order from section 4; add device checks for the exit dialog and for updating without a token.
* `docs/PLAN-6.md`: this plan with an Outcome section written after the work. `docs/HISTORY.md`: one line pointing to it.

### 2.4a Sharing the APK with a friend: syncs are already separate

Checked in the code, no app change needed for separation:

| Question | Finding |
|---|---|
| Is your sync repository, token or household key inside the APK? | No. The only names baked in are the update repository (`ghoshs89/TV-Relay`) and the file name `relay-sync.bin`. The sync repository and token are typed on the phone page on each install (`CompanionServer.kt:398`), and the household key is made on the device when sync is turned on. |
| What happens when your friend turns on sync? | He enters **his own** private repository and token. Relay makes a new household key on his TV. His file lives in his repository, sealed with his key. Your screens never see it and his never see yours. |
| Could his TVs end up in your sync by accident? | Only if you hand him your sync token and household key, which in practice means pairing his TV from one of your screens (matching six digits) or giving him a pairing link. Don't do either. |
| Second guard | Even with your key and token, a screen signed in to a different provider user name is refused ("signed in to a different provider account"). This guard compares the user name only, so it does **not** help if he uses your IPTV login. |
| What he does share with you | Updates: his copy follows the releases of your repository, so he gets every build you publish. With 2.3 that needs no token. |

What the release changes for sharing: the update token is no longer in the APK (2.3), so the build you give him carries nothing of yours. Give him this new build, not 1.0.110 or older.

Documentation: a short README section, **Sharing Relay with someone else**: nothing personal is in the APK; each household signs in with its own provider account; each household that wants sync makes its own private repository and token; never pair another household's screen or send it a pairing link; updates come from this repository.

### 2.5 Tests

* Pure function for the updater's headers and URL choice (token present, blank, whitespace).
* The existing suite (571 tests) must still pass; none are removed.

## 3. Release

One build, as asked. No branch build first; the release run itself runs the tests, lint, signature check, "no benchmark code" check and emulator start before publishing, and publishes only if all pass.

1. Run the unit tests, lint and a release build locally first (the session's SDK is still available).
2. Commit on `claude/loving-hopper-jtzdvb` and push it. Do not touch `relay-sync.bin`: sync commits keep landing on `main` every few minutes and the file is not part of this change.
3. Open a pull request into `main` and merge it, which is the release you asked for. Merge with an explicit message so the in-app update dialog and the GitHub release show plain notes, for example: *"Back now asks before closing Relay. Press Stay (or Back again) to keep watching, Close Relay to leave. In the player the hint is larger and says it leaves the player. Updates no longer need a token now the repository is public."*
4. Watch run 111 or later to completion and report the release number; check `v1.0.N` has its APK.

Risk to say out loud: nothing here can be seen on a screen from this session. The exit dialog and the player prompt are covered only by compilation, the unit tests and the emulator launch check. The new dialog and the full-screen player are the first things to look at on the phone and the TV.

## 4. What you must do afterwards (and in which order)

1. **Install the new build on every screen first.** Screens still on 1.0.110 or older update using the baked-in token; they would stop updating if it were revoked before they get the new build.
2. **Then** delete the update token on GitHub and the `RELAY_UPDATE_TOKEN` secret.
3. **Move sync out of the public repository.** On every screen **Stop syncing on this screen**; turn sync on from one screen with `ghoshs89/Relay-TV_Sync` and its token; join the others; delete the old sync token. Turning sync on again makes a new household key, so the old, publicly visible file versions stay undecryptable. Afterwards I can delete `relay-sync.bin` and its `paths-ignore` line (the old versions stay in the public history; that needs a history rewrite, which I would only do if you ask).
4. The token you pasted into chat for the new sync repository: replace it once sync runs there (*Replace the token* on the phone page).
5. Public means public: the 18 scratch branches and the plan documents are visible too. Delete the branches if you don't want them shown.

## 5. Verification

* Local: `./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease` all green; `strings` on the release APK shows no `github_pat_`.
* CI: the release run passes every step and publishes `v1.0.N` with `Relay-1.0.N.apk`.
* On a screen after installing: from Home on any tab press Back once, **Close Relay?** appears with **Stay in Relay** focused; Back again keeps you in Relay; OK on **Close Relay** closes it. On a film page Back still returns to the list. In the player, Back once shows the centred prompt; Back again returns to where you came from. Settings → check for updates reports "up to date" (or offers an update) with no token error.

## 6. Outcome

Executed on branch `claude/loving-hopper-jtzdvb`; one build, the release run itself.

| Item | Done |
|---|---|
| 2.1 Close Relay? dialog | `ui/ExitDialog.kt`, wired in `AppRoot` for every screen with nothing underneath. **Stay in Relay** is selected; Back or a tap outside means stay; **Close Relay** (red) closes. |
| 2.2 Player prompt | A centred, 26 sp prompt with an accent border, *Press Back again to leave the player*, for the two-second window; the live "back to previous channel" line stays in the corner. |
| 2.3 Updates without a token | `update/UpdateRequest.kt` (tested) decides the header and the download address; `Updater` is always on and sends no token when there is none; CI no longer passes `RELAY_UPDATE_TOKEN` and the "Check update token" step is gone. |
| 2.4 and 2.4a Documentation | README: public-repository note, Back rows, closing prompt, update-token section rewritten, new section *Sharing Relay with someone else*. TODO: sync move marked urgent with the reason, the order for deleting the update token, check lists. |
| Tests | 4 new (`UpdateRequestTest`); whole suite 634 pass (it includes tests another session added); lint has no crash-class issue. A `strings` scan of the release APK finds `github_pat_` only in two pieces of help text and one input-check message, never a token. |

Not seen on a screen: the dialog and the prompt are covered by compilation, the unit tests and the emulator launch check only.

Not done: moving sync (needs your screens), deleting `relay-sync.bin`, deleting the update token (order in TODO.md).
