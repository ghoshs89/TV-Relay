# Relay

A personal IPTV player for Google TV, Android TV and Fire TV. Xtream Codes login, live TV with guide and catch-up, movies, series, profiles, and optional TMDB artwork. No content is included; it plays the account you sign in with.

Keep this repository private. Your IPTV login is never stored in the repo; you enter it on each TV.

## Signing key (one time setup)

Android only installs an update over an existing app if both are signed with the same key. The key is kept in GitHub Secrets, never in the repo.

In the repo on GitHub: **Settings → Secrets and variables → Actions → New repository secret**, and add:

| Name | Value |
|---|---|
| `RELAY_KEYSTORE_B64` | the base64 text of your `relay-release.jks` |
| `RELAY_KEYSTORE_PASSWORD` | the keystore password |

Then re-run the latest build. Without these secrets the build still works but uses a temporary key, so each new build would need the old one uninstalled first. Keep a copy of the `.jks` file and password somewhere safe; losing them means reinstalling the app once.

## In-app updates (one time setup)

Every build on `main` is published as a GitHub Release. Relay checks for a newer release when it opens and every 30 minutes while it's running, shows **Update ready** in the top bar, and installs it after one confirmation. You can also check from Settings → Check for app updates.

Because the repo is private, the app needs a read-only token:

1. GitHub → your avatar → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Name: `Relay updater`. Expiration: the longest offered. Repository access: **Only select repositories → TV-Relay**.
3. Permissions → Repository permissions → **Contents: Read-only**. Nothing else.
4. Generate, copy the token, and add it in this repo as the Actions secret **`RELAY_UPDATE_TOKEN`**.

The build checks the token works before embedding it. The token can only read this repo, which contains no secrets. When it nears expiry, create a new one, replace the secret, and let one build install; if it has already expired, sideload that build once with adb.

Android also needs Relay allowed to install apps, once per TV. Either run `adb shell appops set dev.relay.tv REQUEST_INSTALL_PACKAGES allow` after installing, or follow the prompt Relay shows the first time it updates (Settings → Apps → Security & restrictions → Unknown sources → Relay).

## Build

Every push to `main` builds an APK in GitHub Actions.

1. Open the repo's **Actions** tab, select the latest **Build APK** run.
2. Download the `Relay-1.0.N` artifact (a zip containing the APK).

You can also trigger a build manually from the Actions tab with **Run workflow**.

## Install on a Google TV

**Over the network with adb (easiest from a computer):**

1. On the TV: Settings → System → About → click **Android TV OS build** 7 times to unlock Developer options.
2. Settings → System → Developer options → turn on **USB debugging** (on Google TV this also allows network debugging).
3. Find the TV's IP in Settings → Network & Internet.
4. On your computer:
   ```
   adb connect 192.168.x.x:5555
   adb install -r Relay-1.0.N.apk
   ```
   Accept the prompt on the TV the first time. On newer Google TV models, if `adb connect` is refused, turn on **Wireless debugging** in Developer options and use `adb pair` with the code it shows first.

**Without a computer:** install *Send Files to TV* on your phone and TV, send the APK, then open it on the TV with a file manager (allow it to install unknown apps when asked).

Updates: install the newer APK the same way. Because it's signed with the same key, your login, profiles and history are kept.

## First run

1. Enter a playlist name, the primary server URL, any backup server URLs (comma separated), username and password.
2. Relay checks the login, then downloads your channel and library lists. Large playlists can take a minute the first time; after that it loads from cache and refreshes every 6 hours in the background.
3. If the primary server stops answering, Relay tries the backups automatically, both for browsing and during playback.

If your provider gives you separate URLs for VPN and non-VPN use, put the ones that match how the TV connects in the server fields. You can change them later in Settings → Change servers or login.

## Remote controls

| Where | Button | Action |
|---|---|---|
| Live player | Up / Down | Previous / next channel in the list you opened it from |
| Live player | Channel + / − | Next / previous channel |
| Movie / episode player | Left / Right | Back 10 s / forward 30 s |
| Any player | Center | Show controls (audio, subtitles, speed under the gear and CC icons) |
| Live TV list | Right | Move to the channel panel: favourite, guide, catch-up |

## Troubleshooting logs

Settings → Diagnostics. Recent activity is always kept in memory (500 lines, no disk). **Save detailed logs** also writes to disk for 24 hours, then turns itself off. Disk use is capped at about 1 MB (two rotating 512 KB files plus one crash report). Usernames, passwords and tokens are removed from every line.

**Share logs** opens the share sheet on phones. On a TV, pull the folder shown in Settings with `adb pull /sdcard/Android/data/dev.relay.tv/files/logs/`.

## Optional: TMDB

Settings → TMDB API key. A free key from themoviedb.org (Settings → API, the 32 character "API Key") adds better backdrops, ratings, genres and cast on detail pages. Everything works without it using your provider's own metadata.

## Project layout

```
app/src/main/java/dev/relay/tv/
  RelayApp.kt          singletons: HTTP client, store, API, catalog
  MainActivity.kt      navigation root
  data/                Xtream client (host failover), catalog cache, local store, TMDB
  ui/                  Compose screens for TV: login, profiles, home, live, library, details, search, settings
  player/              Media3 player activity, playback requests
```

Libraries: Jetpack Compose, Media3 ExoPlayer (+ Jellyfin's FFmpeg audio decoder for AC3/E-AC3/DTS), OkHttp, Coil, kotlinx.serialization. The FFmpeg decoder is GPL-3.0; this build is for personal use.
