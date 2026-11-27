<p align="center">
  <img src="docs/banner.png" alt="Relay" width="480">
</p>

<p align="center">
  A personal IPTV player for Android TV, Google TV and Android phones.<br>
  Live TV with a guide and catch-up, movies, series, and profiles for the whole family.
</p>

<p align="center">
  <a href="https://github.com/ghoshs89/TV-Relay/releases/latest"><b>Download the latest APK</b></a>
  &nbsp;·&nbsp; <a href="#install">Install</a>
  &nbsp;·&nbsp; <a href="#remote-controls">Remote</a>
  &nbsp;·&nbsp; <a href="#settings-worth-knowing">Settings</a>
  &nbsp;·&nbsp; <a href="#sync-between-screens">Sync</a>
  &nbsp;·&nbsp; <a href="#troubleshooting">Help</a>
</p>

<p align="center">
  <a href="https://github.com/ghoshs89/TV-Relay/actions/workflows/build.yml"><img src="https://github.com/ghoshs89/TV-Relay/actions/workflows/build.yml/badge.svg" alt="Build status"></a>
</p>

> [!NOTE]
> Relay stores your provider login on your device. Anyone with access to this repository can download the APK from **Releases**. Automatic updates work without a token when the app repository is public. See [update access](#update-access) if you make it private.
>
> Use a separate private GitHub repository for household sync. That keeps the token on each screen separate from the source code and releases. See [Sync between screens](#sync-between-screens).

Relay signs in to an Xtream Codes account and plays what that account provides. No content comes with the app.

<table>
  <tr>
    <td width="50%"><a href="docs/screenshots/home.jpg"><img src="docs/screenshots/home.jpg" alt="Continue watching and recently watched channels"></a><br><sub><b>Home</b> · continue watching and recently watched channels</sub></td>
    <td width="50%"><a href="docs/screenshots/live-tv.jpg"><img src="docs/screenshots/live-tv.jpg" alt="Channel categories and the channel list"></a><br><sub><b>Live TV</b> · channel categories and the channel list</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/screenshots/guide.jpg"><img src="docs/screenshots/guide.jpg" alt="Live programme listings on a timeline"></a><br><sub><b>Guide</b> · live programme listings on a timeline</sub></td>
    <td width="50%"><a href="docs/screenshots/settings.jpg"><img src="docs/screenshots/settings.jpg" alt="Seven categories for common and advanced controls"></a><br><sub><b>Settings</b> · seven categories for common and advanced controls</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/screenshots/movies.jpg"><img src="docs/screenshots/movies.jpg" alt="Recently added movies and the category menu"></a><br><sub><b>Movies</b> · recently added movies and the category menu</sub></td>
    <td width="50%"><a href="docs/screenshots/series.jpg"><img src="docs/screenshots/series.jpg" alt="Recently added series and the category menu"></a><br><sub><b>Series</b> · recently added series and the category menu</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/screenshots/player-paused.jpg"><img src="docs/screenshots/player-paused.jpg" alt="The title and release year above the video"></a><br><sub><b>Paused on TV</b> · the title and release year above the video</sub></td>
    <td width="50%"><a href="docs/screenshots/phone-paused.jpg"><img src="docs/screenshots/phone-paused.jpg" alt="The title and touch controls in landscape"></a><br><sub><b>Paused on phone</b> · the title and touch controls in landscape</sub></td>
  </tr>
</table>

<sub>Captured on Android TV and Android phone emulators with a demo playlist.</sub>

## Features

<table>
  <tr><td><b>Watch</b></td><td>Live TV with pause and rewind, catch-up, movies and series with resume, the current title while paused, next episode, and picture-in-picture on phones</td></tr>
  <tr><td><b>Your languages</b></td><td>English, Hindi and Bengali first by default, then South Indian and Bangladesh; every other language still there, lower down. Order, Home rows and audio follow each profile</td></tr>
  <tr><td><b>Find</b></td><td>Timeline guide with now and next in every list, search that also finds programmes on TV, content-type chips (Sports, News, Movies, Kids, Documentary, Music, Entertainment and genres), categories by country and by language, hide or pin categories, favourites, channel groups and renames</td></tr>
  <tr><td><b>Family</b></td><td>Profiles, kids profiles that can't reach adult titles by any route, daily viewing time and bedtime for kids, a parent PIN for anything that changes access</td></tr>
  <tr><td><b>Reliability</b></td><td>One entry per channel with automatic fallback to its other versions, backup servers (the ones meant for use with or without a VPN tried first to match), stalled streams reconnect on their own, failures explained in plain words, a library refresh that can't wipe your saved lists, and a ready-to-use copy of the library so channels appear within seconds after a restart</td></tr>
  <tr><td><b>Picture and sound</b></td><td>Refresh-rate matching, Dolby and DTS audio, lip-sync adjustment, subtitle style, stream info on start</td></tr>
  <tr><td><b>Several screens</b></td><td>Optional sync keeps profiles, favourites, watch history, kids' viewing time and shared settings the same on every TV and phone you own, through a private GitHub repository of your own, encrypted before it leaves the screen. <b>Play on TV</b> hands what the phone is playing (a channel, a film, an episode, catch-up or Start over) to a TV at home</td></tr>
  <tr><td><b>Extras</b></td><td>Rotten Tomatoes scores (IMDb when there's none), TMDB artwork, trailers (a QR code to watch on your phone when you're at the TV, YouTube on a phone), API keys sent from your phone, programme reminders, Continue watching on the Google TV home screen, a phone page for editing your library, backup and restore, in-app updates (stable and beta channels), and optional direct crash and diagnostic report uploads</td></tr>
</table>

## Install

1. Download `Relay-1.0.N.apk` from [Releases](https://github.com/ghoshs89/TV-Relay/releases/latest).
2. Install it once by hand (below). After that, Relay updates itself: it checks when it opens and every 30 minutes, shows **Update ready** in the top bar, and installs after one confirmation.

<details>
<summary><b>On a TV, from a computer (adb)</b></summary>

1. On the TV: Settings → System → About → select **Android TV OS build** seven times to unlock Developer options.
2. Settings → System → Developer options → turn on **USB debugging**.
3. Find the TV's IP address in Settings → Network & Internet.
4. On the computer:
   ```
   adb connect 192.168.x.x:5555
   adb install -r Relay-1.0.N.apk
   adb shell appops set dev.relay.tv REQUEST_INSTALL_PACKAGES allow
   ```
   Accept the prompt on the TV the first time. The last line lets Relay install its own updates.

   If `adb connect` is refused on a newer Google TV, turn on **Wireless debugging** instead. Run `adb pair IP:PAIRING_PORT` with the code it shows, then `adb connect IP:PORT`. The two ports are different; use the ones on the TV's screen.

</details>

<details>
<summary><b>On a TV, without a computer</b></summary>

Install *Send Files to TV* on your phone and the TV, send the APK, and open it on the TV with a file manager. Allow installs from that app when asked. The first time Relay updates itself, Android asks you to allow Relay to install apps as well.

</details>

<details>
<summary><b>On a phone</b></summary>

Open the APK from the Releases page and allow your browser to install apps when Android asks.

</details>

## First run

1. Enter a name for the playlist, the server address, any backup servers (comma separated), your username and password. A phone can fill this in for you: scan the QR code on the sign-in screen.
2. Relay checks the login and downloads your channels, movies and series. A large playlist takes about a minute the first time; after that it opens from its saved copy.
3. Pick or create a profile.
4. Set up your library (once, skippable): put your languages in order and untick Live TV countries you never watch. Both can be changed later in Settings → Library & guide.

If your provider gives separate addresses for use with and without a VPN, enter all of them. Pasting the provider's whole message (on the sign-in screen or the phone page) marks which are for a VPN; the phone page also has a field to list them yourself. Relay then tries the servers that suit the device's connection first and keeps the others as a last resort, and it notices when a VPN is turned on or off. Settings → Account & sync lists every server with its mark and state; press one to change its mark.

## Remote controls

**Live TV**

| Button | Action |
|---|---|
| Up / Down, Channel − / + | Previous / next channel in the list you opened |
| OK or Left | Channel list over the video |
| Right or Menu | Player controls: audio and subtitle tracks |
| 0 to 9 | Go to a channel number |
| Play/Pause | Pause or resume live TV; Relay keeps recording behind the scenes (OK also resumes when paused) |
| Left / Right while paused or behind live, Rewind / Fast-Forward | Back / forward by your jump intervals (10 s and 30 s to begin with); going forward past the end returns to live |
| Back | Previous channel; then a large prompt says *Press Back again to leave the player*, and a second Back within about two seconds leaves it |
| Hold OK (in the channel list) | Add or remove the channel from favourites |

**Movies and episodes**

| Button | Action |
|---|---|
| Play/Pause | Pause or resume playback |
| Left / Right, Rewind / Fast-Forward | Back / forward by the sizes set in Settings → Playback & subtitles (10 s and 30 s to begin with) |
| OK | Show the player controls: audio, subtitles and speed |
| Back | Hide the controls; otherwise a large prompt says *Press Back again to leave the player* and a second Back within about two seconds leaves it. Your position is saved. |

Pause a video to see its title above the picture. Movies also show the year, episodes show the series and episode details, and catch-up shows the programme and channel. Live TV uses the programme at the paused position when guide data is available and shows the channel name otherwise. The title stays visible after the controls fade and clears when playback resumes.

When you put Relay in the background, a movie or episode you paused stays paused when you return, and playback resumes if the video was playing. A live channel stops its stream in the background so your connection is freed, and tunes in again when you return (on phones, picture-in-picture keeps playing while the screen stays on).

**On a movie or series page:**

- **Trailer** appears when your provider or TMDB knows a trailer (a TMDB key finds more, including trailers in the film's own language, for example Hindi or Bengali). When none is known, **Find a trailer** looks for one on YouTube instead.
- **On a TV**, both show a **QR code**: scan it with your phone's camera and the trailer plays in YouTube on your phone, while the TV stays on the page. Back closes the code.
- **On a phone**, both open the YouTube app, and Back returns to the same page. A phone without YouTube shows the QR code instead, for another phone.
- Neither button appears on kids profiles, because YouTube is outside Relay's kids limits and parent PIN.
- **The summary** shows a few lines. When there is more, press **Down** from the buttons to reach it (it says **More**) and **OK** to read all of it; Up and Down scroll a long one. Leftovers that some providers copy from other sites, such as "… Read all" or "See full summary »", are removed: they were never links.

**Closing Relay:** on the main tabs, the profile picker and the sign-in screen, Back asks **Close Relay?** with **Stay in Relay** selected. Back again, or choosing Stay, keeps Relay open; the app closes only when you choose **Close Relay**. On a film or series page Back goes to the list underneath. Inside a Settings category, Back returns to the category menu.

**On Home:** hold OK on a Continue watching card or a Recently watched channel to remove it (Menu works too). The **Clear all** button at the end of the Continue watching row empties it after a confirmation.

**In the guide grid:** the time window advances every half hour while your selected programme stays in view for as long as it remains in the window. OK on a past show plays it from catch-up, on a current show tunes in, and on a future show sets a reminder. In the Live TV list, **Right** opens the channel panel: favourite, guide, catch-up, rename and groups.

## Settings worth knowing

Open **Settings** and choose a category. Back closes the open dialog or returns to the category menu. On TV, press OK to reopen the focused category.

| Category | What you can change |
|---|---|
| **Playback & subtitles** | Audio and subtitle languages, subtitle size and colour, autoplay, skip intervals, sleep timer, pause and rewind for live TV, and **Start on last channel**. **Advanced playback** contains stream format, user agent, TV refresh-rate matching, stream information, and lip-sync. |
| **Library & guide** | Preferred languages, Home rows, pinned and hidden categories, channel groups, guide source and offset, programme reminders, and optional artwork and ratings keys. |
| **Profiles & parental controls** | Switch or manage profiles, set PINs, hide adult categories, and set viewing limits and bedtime. An active kids profile reopens when Relay restarts. Use **Switch profile** and enter the parent PIN to leave it. |
| **Account & sync** | Provider status, servers, refresh the playlist, sign-in, and household sync and screen management. |
| **Appearance** | Theme, keyboard, and which screen opens at startup. |
| **Backup & phone** | Phone companion and local file backup or restore. Relay asks for confirmation before a restore when sync is on. |
| **About & diagnostics** | Version, **Check for app updates**, **Update channel** (stable only or include beta pre-releases), **Privacy & usage** (anonymous usage stats toggle), **Send diagnostic report**, activity logs, and the library and language report. |

The [library browsing study](docs/BROWSING.md) compares two possible ways to group large Live TV, Movies and Series libraries.

<details>
<summary><b>How languages work</b></summary>

Relay sorts everything into five groups: **English**, **Hindi**, **Bengali** (Indian Bengali only), **South Indian** (Tamil, Telugu, Malayalam and Kannada, including Hindi-dubbed South films) and **Bangladesh**. Everything else is *Other*, and titles Relay can't place are *Unclassified*.

- It reads title tags (`HI - `, `SO-IN - `), category names (`HINDI MOVIES`, `BANGLADESH`), well-known channel names (Star Jalsha, Channel i, Sun TV) and country tags. With a TMDB key, a title's original language is learned the first time you open it.
- Your order decides what comes first in lists, search and the guide. Nothing is filtered out; other languages sit lower down.
- Languages marked **Home row** get a *New in …* row on Home. Live TV has a **By language** section, with Bangladesh directly under Bengali.
- Live TV, Movies and Series also show quick filter chips above the list or grid: **Sports**, **News**, **Movies**, **Kids**, **Documentary**, **Music** and **Entertainment** in Live TV, and genre plus language chips in Movies and Series. Choosing *All* shows everything again.
- Each profile can have its own order (Settings → Library & guide → My languages); profiles without one use the household order chosen during setup.
- If a lot lands in Unclassified, the Library and language report lists the tags and categories it didn't recognise.

</details>

<details>
<summary><b>How Relay stays up when your provider doesn't</b></summary>

- **Backup servers** are tried automatically, both for browsing and during playback. A server that fails is tried last for the next 10 minutes, and while other servers are left to try Relay gives each 5 seconds to connect instead of 15. Servers marked for use with a VPN go first while one is on, the others while it is off.
- **Several versions of a channel** (HD, FHD, HEVC, backup) show as one entry. Your chosen version plays first, otherwise the best quality. If it fails to start, or keeps stalling, Relay switches to the next version on its own. **Versions** in the channel panel lists them all and lets you pick the main one.
- **Stalled streams:** live TV reconnects after 20 seconds of buffering and movies after 30, resuming at the same position. After three tries a channel shows **Retry** and **Next channel** instead of buffering forever.
- **When something fails**, the player says why in plain words after checking your account: login rejected, subscription expired, all your connections in use, the server refusing the stream, or a format the TV can't decode.
- **Pause & rewind** records MPEG-TS channels only; HLS channels play directly, and a `.ts` address that returns an HLS playlist is played as HLS automatically. If a channel doesn't work with the buffer, Relay plays it directly instead.
- **Library refresh** happens once your lists are six hours old, including while Relay stays open. A refresh is checked before it replaces anything: an error page, a cut-off list, or a sudden empty list is rejected and your saved library is kept.

</details>

<details>
<summary><b>Backups and the phone companion</b></summary>

A backup holds profiles, favourites, history, channel groups and renames, sort choices, per-series audio and subtitle choices, reminders and settings. It never contains your login, any PIN, or API keys.

A restore validates the whole file before replacing your data. A damaged backup, including an invalid avatar colour, or a backup from a newer Relay shows an error. A successful restore replaces those items on the device. Export a backup first if you might want the current setup back. PINs already set on the device are kept, and Relay lists any profiles that need a PIN set again. Backups from older versions still restore, without their PINs.

**Restoring while sync is on:** Relay asks first, because the restored items count as changes made on this screen and spread to your other screens. To restore on this screen only, stop syncing on it first, restore, and join again afterwards. Before the first sync and before a merged file from your other screens is applied, Relay saves a safety copy of this screen's data (the last three are kept), so a sync that does something unexpected can be undone by restoring that copy.

Treat backup files as personal: they include your viewing history, and an external guide address can contain your provider login if the provider builds it that way.

The phone companion (Settings → Backup & phone) is a page a phone on the same Wi-Fi opens by scanning a QR code. It can sign the TV in, **send API keys** (they are checked before they are saved, and a saved key is never shown on the page), turn on **sync** or replace its token, download a backup and restore one, and **edit your library** with the phone's keyboard: hide, show or pin categories and whole countries, reorder or remove favourite channels, and rename channels. It exists only while that screen is open, and its address includes a random code.

Other screens open the same page for one job only, and it refuses anything else:

| Opened from | The page offers |
|---|---|
| Settings → Backup & phone → **Phone companion** (parent PIN when one is set) | Everything above |
| The sign-in screen | Sign-in (plus the pairing link for *I already use Relay on another screen*) |
| **Send from my phone** on a key row (parent PIN when one is set) | API keys only |
| **Turn on sync** (parent PIN when one is set) | Sync set-up only |
| **Join sync from another screen** (parent PIN in Settings when one is set) | Pairing on the home network and the pairing link only |

Both the phone companion page and the Play on TV receiver answer only requests addressed to the TV's own address and port, and reject cross-site browser posts, so a web page open on another device can't reach them.

</details>

<details>
<summary><b>Guide search, start over and the home screen</b></summary>

- **Search** shows an *On TV* row: programmes on now (OK to watch) and later (OK sets a reminder). By default it covers channels whose guide Relay has already loaded. **Search the whole TV guide** downloads your provider's full guide for your languages and favourite channels (up to 1,500) every 12 hours. An external XMLTV guide, if set, is searched instead.
- **Start over** in the channel panel plays the current programme from its beginning on channels with catch-up.
- **Continue watching on the home screen** (Google TV and Android TV): movies and episodes you're partway through appear on the TV's own home screen. Choosing one opens Relay and resumes it on the same profile. Kids profiles and adult categories never appear there.

</details>

## Sync between screens

Optional. With sync on, every TV and phone you have shows the same profiles, favourites, Continue watching, kids' viewing time and shared settings. Stop a film on one screen and the next one offers to resume it. Nothing is on until you set it up, and a screen that is offline just carries on by itself and catches up later.

Relay does not run a server for this. The data lives in **one encrypted file in a private GitHub repository of your own**. Each screen reads and writes that file with a token that can reach only that repository. The file is encrypted on the screen before it leaves (AES-256-GCM), with a household key that stays on your screens and never goes to GitHub, so GitHub holds a file it cannot read.

<details>
<summary><b>What you need to make once (about five minutes, on a computer or a phone)</b></summary>

1. On GitHub, make a **private** repository, for example `relay-sync`. Tick **Add a README file** so it isn't empty. **Do not use this app's own repository:** every screen keeps the sync token, and a token that can write here could change the app that all your screens install.
2. GitHub → your avatar → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
3. Name it `Relay sync`, pick the longest expiry offered, and under Repository access choose **Only select repositories** and pick the sync repository.
4. Repository permissions → **Contents: Read and write**. Nothing else. Relay's own updates need no token.
5. Copy the token (it starts with `github_pat_`). GitHub shows it once.

</details>

<details>
<summary><b>Move sync to a separate private repository</b></summary>

If Relay shows **Sync paused** because it was set up with the app repository, your saved profiles, favourites and history are still on this screen. The household migration is complete for this project. Use these steps if you recover an older backup or still have a screen pointing at the app repository.

1. Export a backup from the screen with the latest viewing history and favourites.
2. On GitHub, delete the old sync token straight away (the one that can write to the app repository). Screens stop syncing; nothing on them is lost.
3. On each screen, go to **Settings → Account & sync → Stop syncing on this screen**, and update Relay.
4. Create a separate private sync repository and a fresh token scoped only to that repository. Turn sync on using the backed-up screen as the first screen.
5. Join the other screens. Check that a favourite and a movie resume position reach a second screen.

</details>

**Turn it on (first screen):** Settings → Account & sync → **Turn on sync** (parent PIN if you have one). Scan the QR code with your phone, open the page, find *Sync between screens*, type the repository as `your-name/relay-sync`, paste the token and press **Turn on sync**. Relay checks that the repository is private and that the token can write to it, makes the household key, and does the first sync. The window closes when it is on, and Settings shows the status and the list of screens.

**Add another screen:**

1. On the new screen open Settings → Account & sync → **Join sync from another screen**. On a fresh install the sign-in page has **I already use Relay on another screen** instead; a screen that joins signs in by itself.
2. On a screen that already syncs open Settings → Account & sync → **Add a screen** and choose the waiting screen from the list.
3. Both screens now show the same six digits. If they match, press **Yes, they match** on the first screen, then **Join** on the new one. If they differ, press **No** and stop: someone else on your network is getting in the way. The details sent between the screens are sealed with a key worked out from the two screens' own keys, so nothing useful travels in the clear.

The new screen keeps a safety copy of what it had before it joins, then brings its favourites and history together with the others'.

If your Wi-Fi stops the screens from finding each other (some routers block this between devices), and the screen that already syncs is a **phone**: on the phone choose **Add a screen** → **Copy pairing link**, scan the new TV's QR code with the same phone, paste the link into the *Join sync* box on that page and press **Join** on the TV. Anyone who has the link can join your sync, so don't share it. A TV can't pass the pairing link to a phone yet, so this fallback needs the first screen to be a phone.

<details>
<summary><b>What is shared and what stays on each screen</b></summary>

| Shared | Combined how |
|---|---|
| Watch history and positions, per profile | The later entry for each title wins. Removing a card on one screen doesn't come back from another |
| Favourites, per profile | Each add or remove counts separately; the later one wins |
| Profiles: name, colour, kids flag, limits, languages, Home rows, **profile PINs and the parent PIN** | The later change wins, per profile. A deleted profile stays deleted |
| Kids' viewing time today, and "Allow 30 more minutes" | Added up across screens, so 60 minutes a day means 60 minutes in the whole house |
| Your provider login | The later change wins. Changing servers on one screen reloads the library on the others |
| TMDB and MDBList keys, audio and subtitle languages, language order and Home languages, hidden and pinned categories, show each channel once, guide address and offset, search the whole guide, scores on posters, hide adult titles, Continue watching on the TV home screen, play next episode, jump sizes, "Are you still watching?" and whether setup is done | The later change wins, one setting at a time. Changing the audio language on one screen and the hidden categories on another keeps both |
| Channel groups, renames, chosen channel versions, sort choices, audio and subtitle choice per series, reminders | The later change wins, per item |

| Stays on each screen | Why |
|---|---|
| Lip-sync, refresh-rate matching, stream info, subtitle size, colour and background, theme, keyboard, pause buffer, start screen, start on last channel, sleep timer, live stream format, user agent, update channel, anonymous usage stats, detailed logs | They depend on the TV and the screen |
| The playlist itself, images, logs, last channel, scroll positions | Each screen downloads and keeps its own |

Two households never mix: the file is tied to the provider account, and a screen signed in to a different account refuses to merge and says so.

</details>

<details>
<summary><b>How and when it runs</b></summary>

- A screen syncs a few seconds after Relay opens, when it comes back to the front, about 30 seconds after you change something, when a film or channel closes, and every 10 minutes while it is open. **Sync now** in Settings does it straight away.
- Relay writes only when something changed, and an idle screen touches the file about once every six hours to show it is still there.
- Two screens changing different things at the same time both win. Two screens changing the same thing: the later change wins, and screens' clocks are compared safely, so a TV whose clock is slow or fast can't win or lose for ever.
- Playback position reaches the other screens when the player closes, not every few seconds.
- **Offline:** nothing breaks. Each screen keeps its own copy, retries after 1, 5 and then 15 minutes, and merges when the network is back. Both screens' changes survive.
- Sync never blocks playback or start-up, and it waits while a slow TV is still reading its library.
- Every change is a commit in your repository, so its history slowly grows (a few kilobytes per change). If you ever want a clean start: stop syncing on every screen, delete the repository, make a new one and a new token, turn sync on from one screen and join the others.

</details>

<details>
<summary><b>Removing a screen, a lost screen, a new token, stopping</b></summary>

- **Remove a screen** (Settings → Account & sync) only takes it off the list. A screen that still has the key puts itself back the next time it syncs.
- **A lost or sold screen:** on a screen you still have, choose **New household key**. The file is sealed again, and a screen with the old key can no longer open it. Because the old token can still change the file even though it can't read it, also make a new GitHub token (as above) and delete the old one in GitHub. Paste the new token on this screen: Settings → Backup & phone → Phone companion, scan the code, and use *Replace the token* under *Sync between screens*. Then, on each of your other screens, choose **Join again from another screen** (it appears once the status says the key or token no longer works) and add it from this one; the new token travels with the pairing.
- **The token expired or was removed:** the status says *The sync token was refused*. Make a new token as above and paste it under *Replace the token* on the phone page (Settings → Backup & phone → Phone companion). The token is kept on each screen, not in the shared file, so every screen needs it; the quick way is **Join again from another screen** on the others.
- **Stop syncing on this screen** keeps everything on it, forgets the repository, token and key, and takes it off the list. The others keep going. Signing out on a screen that syncs stops sync there too, because the login would otherwise come back from the other screens.
- The token and key are kept in Relay's private storage on each screen, and are removed from every log line. They aren't in backups.

</details>

## Play on TV

On a phone, **Play on TV** starts what you're watching on one of your TVs instead: a live channel, a film or an episode at the same minute (with the next episodes queued after it), or a catch-up show or a programme you started with **Start over**, also at the same minute. The phone then stops, so your provider sees one connection, not two.

- **Where:** the **Play on TV** button at the top of the player (tap the video to show it), on a film's page, on a series page (it sends the episode in the Continue button), and in a Live TV channel's panel. **Hold** an episode card on a series page, or a programme in a channel's Catch-up list, to send that one instead.
- **What it needs:** the phone and the TV both use sync (they're in the same household), they're on the same Wi-Fi, and **Relay is open on the TV**, on any screen. Relay can't start itself on a TV that is off or showing another app.
- **What travels:** only which channel, film, episode or recorded programme to play and where to start, sealed with a key worked out from your household's sync key. No stream address, login or password leaves the phone. The TV plays the item from its own library with its own player, so pause and rewind, backup servers and channel versions all work as usual.
- **Profiles:** the TV plays on the profile open on the TV. A kids profile on the TV refuses anything it couldn't open itself, and so does a profile that has reached its viewing time or bedtime. If the TV is on the profile picker, pick a profile there first.
- **Other households:** a TV only accepts requests sealed with its own household's key, so a friend's phone, or anything else on your Wi-Fi, can't start things on your TVs.

## Sharing Relay with someone else

You can give the APK to a friend or relative. Nothing of yours is in it: no login, no key, no token, and no sync details. What they get is the same app you downloaded from Releases.

- **Their own provider account.** They sign in with their own server, user name and password. Relay does not copy your login to anyone.
- **Their own sync, if they want it.** Sync lives in a private GitHub repository chosen by whoever turns it on, with a token and a household key made on their screens. Your sync and theirs share nothing: different repository, different token, different key. They follow *Sync between screens* with a repository of their own.
- **Never join their screens to yours.** Adding a screen from one of your screens (matching six digits, or a pairing link) gives it your repository, token and key, and it then reads and writes your history. Pair only your own screens.
- **Updates.** The app updates itself from this repository's Releases, so a shared copy follows the builds you publish. If you would rather it didn't, they can switch to a build from their own copy of the repository.
- **A shared provider login.** Sync refuses to merge two screens that are signed in to different provider user names, but it cannot tell two people apart who use the *same* login. Don't share one login between households that sync.

## Optional extras

<details>
<summary><b>Rotten Tomatoes scores</b></summary>

Settings → Library & guide → Rotten Tomatoes scores. Add a free MDBList key (mdblist.com: sign in, then Preferences → API Access). Choose **Send from my phone**, scan the code and paste the key on the page; a key is checked before it is saved and you see the result on both screens. Or choose **Type it here**. Movie and series pages then show the critics and audience scores when Rotten Tomatoes has them; titles without a score show nothing extra.

- Lookups happen only when you open a title, after the page appears, and never delay Play.
- Scores are saved and checked again after a day for this year's releases, a week for older titles, and three days for titles with no score.
- The free key allows 1,000 lookups a day. Relay stops at 900 and shows saved scores until the next day.
- **Scores on posters** (on by default) shows the critics score on posters of titles you've already opened. It never makes extra lookups.
- Titles are matched by their TMDB ID. Without one, Relay searches by title and year and shows a score only when there's exactly one match.

</details>

<details>
<summary><b>TMDB artwork and details</b></summary>

Settings → Library & guide → TMDB API key, then **Send from my phone** (or **Type it here**). A free key from themoviedb.org (Settings → API) adds better backdrops, ratings, genres, cast and trailers to movie and series pages, and a full summary where the provider's is cut short (many copy theirs from IMDb, which shortens them). Without it, Relay uses your provider's own information. Either the 32-character "API Key" or the longer "API Read Access Token" works, and spaces, quotes or a leading `api_key=` in what you paste are ignored.

</details>

## Troubleshooting

**Logs & diagnostic reports:** Settings → About & diagnostics. Important lines (library loads with timings, downloads, errors, playback problems, and the screen freezing) are always saved, so they survive a force-close. **Save detailed logs** adds every detail line for 24 hours, then turns itself off; disk use is capped at about 1 MB. Usernames, passwords, tokens and API keys are removed from every line. Shared logs start with the device's memory and processor, the library size and the main settings.

When a telemetry worker is configured in a build (`RELAY_TELEMETRY_URL`), **Send diagnostic report** uploads the scrubbed log bundle (capped at 256 KB) directly over HTTPS and returns a short 6-character code (such as `#K7P2M9`) — and on non-kids profiles, if the app closed unexpectedly last time, a one-tap prompt offers to send the crash report on the next launch. Your provider login, tokens, API keys, and **provider and external guide server hostnames** are automatically stripped before anything leaves the screen. You can also turn **Share anonymous usage stats** on or off under **Privacy & usage**; when on, it counts only a random screen ID, Relay version, TV/phone model, daily watch minutes, and crash counts — never logins, server addresses, IP addresses, or which channels or titles you watch.

To get the logs off a **TV**, open the phone companion (Settings → Backup & phone), scan the QR code and press **Download logs** on the phone. **Share logs** works directly on phones. With adb: `adb pull /sdcard/Android/data/dev.relay.tv/files/logs/`.

**Refreshing the playlist:** the top bar shows how far along it is, and Settings → Account & sync shows the step (downloading, checking, preparing). The percentage is based on how big each list was last time, so the very first download shows megabytes instead. A manual refresh keeps going when you switch tabs. If the refresh is interrupted or fails, Relay continues to show its last complete library. Use **Refresh channels and library** to try again.

**Slow to start with a VPN on (or off):** Settings → Account & sync lists your servers. Each should say whether it is for use with a VPN, and the VPN line should match how the device connects. Press a server to change its mark. The log's header lists the servers too.

**Saved copy of the library:** Relay keeps a ready-to-use copy of your prepared library next to the downloaded lists, so after a restart or an update the channels appear in seconds instead of about a minute. It is written a few seconds after the library is prepared from a download or refresh, and it is ignored (and rewritten) whenever anything about it looks wrong, for example after some updates, so the first start after those takes the long way once. Settings → Account & sync → Refresh channels and library rewrites the saved copy. The logs from Settings → About & diagnostics say at the top whether it was used and, if not, why.

**Updates stopped arriving:** Settings → About & diagnostics (or the **Update** button in the top bar) shows why. **Update channel** lets each screen stay on **Stable only** (the default) or opt in to **Stable and beta pre-releases**. GitHub limits how often an unnamed visitor may ask (60 an hour per network), so several screens on one network checking at once can be told to wait; it clears by itself. If the repository is private, sign in to GitHub, download the latest APK, and install it over your existing copy. See [update access](#update-access).

**Sync status** (Settings → Account & sync): *Sync paused* means this screen still points at the app repository. Follow the [household migration steps](#sync-between-screens). *In sync, N minutes ago* is normal. *Waiting for the network* means the last try couldn't reach GitHub; it retries by itself. *The sync token was refused* or *can't be found with this token*: the token expired, was removed, or doesn't cover the repository; make a new one (Contents: read and write) and paste it under *Replace the token* on the phone page. *This screen's sync key doesn't open the file*: the household key was changed; choose **Join again from another screen**. *This screen is signed in to a different provider account*: sync is for one account; sign in with the same one or stop syncing here. *Update Relay on this screen to keep syncing*: another screen has a newer Relay that writes a newer file format; install the update. A message about the repository being public or the token being unable to write appears when sync is turned on; fix the repository or token and try again.

**Phones and TVs:** Relay decides once, when it starts, whether the device is a recent phone (six or more processor cores, plenty of memory, not a TV) or anything else. A recent phone reads more of the library at once, waits less before loading guide text and searching, and fades posters in; a TV keeps the gentler settings it has always had. Buttons are thumb-sized on touch screens only. The first lines of the logs say which was chosen (*Device tier: fast* or *standard*) and the numbers in force.

**Play on TV says no TV was found:** open Relay on the TV (any screen), check the phone and the TV are on the same Wi-Fi (some guest and mesh networks keep devices apart), and that both show sync turned on in Settings → Account & sync.

**A channel won't play:** try Settings → Playback & subtitles → Advanced playback → Live stream format (MPEG-TS or HLS). If a provider returns an HLS playlist from an MPEG-TS address, Relay plays it as HLS. Pause and rewind uses the MPEG-TS buffer on compatible channels.

## Maintaining Relay

<details>
<summary><b>Signing key</b></summary>

Android installs an update only if it's signed with the same key as the installed app. The key lives in GitHub Secrets, never in the repo. In **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `RELAY_KEYSTORE_B64` | The base64 text of `relay-release.jks` |
| `RELAY_KEYSTORE_PASSWORD` | The keystore password |

Every build checks that the APK is signed with this exact key and fails otherwise.

> [!WARNING]
> Keep a copy of the `.jks` file and its password somewhere safe. Losing them means uninstalling and reinstalling Relay on every device.

</details>

<details id="update-access">
<summary><b>Update access for public and private repositories</b></summary>

The standard release uses GitHub's public Releases endpoint. When the app repository is **public**, open **Settings → About & diagnostics → Check for app updates** (no token is needed). When a fork of the repository is **private**, either download the latest APK from Releases and install it over the existing app, or build with `RELAY_UPDATE_REPO` and a fine-grained `RELAY_UPDATE_TOKEN` (`Contents: Read` on that repository only). Android keeps your data when the APK has the same signing key. Household sync uses its own private repository in either case.

</details>

<details>
<summary><b>Optional anonymous usage & log-upload worker</b></summary>

Relay keeps zero secrets in its public APK. If you want anonymous active-screen / watch-time rollups and one-click crash/log uploads with short `#CODE` IDs, deploy [the Cloudflare Worker](tools/telemetry-worker/README.md) on Cloudflare's free tier and set the GitHub Actions repository variable `RELAY_TELEMETRY_URL` (`Settings → Secrets and variables → Actions → Variables`). Your dashboard password (`OWNER_TOKEN`) and optional private GitHub Issue token live strictly inside Cloudflare Worker secrets.

</details>

<details>
<summary><b>Builds and releases</b></summary>

Every push to `main` that changes the app builds it in GitHub Actions and publishes it as release `v1.0.N`. Changes that touch only documentation or tools (`README.md`, `docs/`, `tools/`) don't make a release. You can also use **Run workflow** in the Actions tab: a run on a feature branch produces a downloadable build artifact, and ticking **Publish as a GitHub pre-release** publishes it as a GitHub Pre-release without notifying **Stable only** users. A run on `main` publishes a full release.

The release pipeline in `.github/workflows/build.yml` uses three jobs (plus two optional manual jobs for gallery screenshots and benchmarks):

| Job | Can write to the repository | Has the signing key | Does |
|---|---|---|---|
| **build** | No | Only until the APK is signed, then deleted | Unit tests, lint, the signed APK, its signature check and SHA-256 |
| **device-check** | No | No | Installs that exact APK on an emulator (below) |
| **publish** (`main` or pre-release) | Yes | No | Checks the APK is byte for byte the one built and signed with the Relay key, then makes the release |

So the emulator and the test scripts never run where the signing key or a write token is. Every action the workflow uses is pinned to an exact commit, with its version in a comment beside it; update both together.

Before anything is published, the build runs the unit tests and lint. A failing test, or a lint issue that would crash on a device (such as an Android call newer than the TV supports), stops the build. The emulator runs the profile, sync, playback and guide regressions against a synthetic provider. It also checks that the signed, shrunk APK starts from a fresh install and upgrades v1.0.126 with its saved profile, favourite, history, resume position and settings intact. Test and lint reports are attached to each run as `reports-1.0.N`.

To refresh the README gallery, start **Actions → Build APK → Run workflow** on a feature branch and select **Capture the README gallery on Android TV and phone emulators**. The gallery jobs run the screenshot test against the fake provider and attach the JPEGs as **gallery-tv** and **gallery-phone**.

A **benchmark** job can run beside the build. It costs about as many GitHub Actions minutes as the build itself, so releases skip it; start it by hand under Actions → Build APK → Run workflow with **Also run the benchmark** ticked. In it, a benchmark variant of the app (`assembleBenchmark`, never published, and the build fails if the release APK contains any of it) is driven on an emulator against a made-up provider (`tools/fake-provider`, 12,000 channels, 40,000 movies, 8,000 series) by `tools/bench/bench.sh`. The job log ends with frame times per screen, list build times and time to first picture. The run covers Home, Live TV, a live channel, the Movies and Series grids, the guide grid, Search, and touch flings. These numbers are for comparing one build with another and spotting a regression. They are not how fast a TV is: the emulator runs on a shared build server with a software renderer, and a TV's processor is slower in different ways. The job never blocks or delays a release, and a release does not wait for one. To see the same numbers from a real device, turn on **Save detailed logs** under About & diagnostics for a day, use the app as usual, then share the logs: frame timing, list build times and time to first picture are in them.

To build locally (JDK 17 and the Android SDK):

```
./gradlew :app:testReleaseUnitTest
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
```

Without the release key (the `RELAY_KEYSTORE_PATH` and `RELAY_KEYSTORE_PASSWORD` environment variables, or a local `signing.properties`), a local build is signed with a debug key and can't update an installed copy of Relay.

</details>

<details>
<summary><b>App icon and TV banner</b></summary>

The launcher icon, themed icon, legacy icons and TV banner are all generated from one drawing by `tools/icon/build_icon.py`. Run it from the repo root (instructions at the top of the script), then commit the changed files under `app/src/main/res` and `docs/banner.png`.

</details>

<details>
<summary><b>Project layout</b></summary>

```
app/src/main/java/dev/relay/tv/
  RelayApp.kt       app-wide singletons: HTTP client, store, API, catalog,
                    ratings, telemetry
  MainActivity.kt   navigation root and splash
  data/             Xtream client with server failover (order by VPN, skip
                    servers that just failed), catalog, local store,
                    backups, restrictions, languages, content-type chips,
                    channel versions, a saved copy of the prepared library for
                    fast start-up, kids limits, TMDB and Rotten Tomatoes,
                    trailers, mergeable records and encryption for sync
  ui/               Compose screens for TV and phone
  player/           Media3 player, per-session pause-buffer recorder, stream recovery
  companion/        scoped phone companion web page, library editor, and the small
                    home-network HTTP helpers it shares with Play on TV
  sync/             sync engine, GitHub storage, pairing, status text,
                    and Play on TV (the TV's receiver and the phone's sender)
  telemetry/        opt-in anonymous usage stats and scrubbed crash/log uploads
  tv/               Google TV home screen (Continue watching)
  update/           in-app updater (stable and pre-release channels)
  net/              whether the device's connection goes through a VPN
app/src/test/       unit tests
app/src/androidTest/  emulator tests: reliability, settings navigation, updater,
                    and documentation screenshots
tools/icon/         icon and banner generator
tools/fake-provider/  made-up Xtream provider for the benchmark and emulator tests
tools/bench/        benchmark driver and report
tools/telemetry-worker/  optional Cloudflare Worker + D1 dashboard for usage and reports
app/src/benchmark/  sign-in to the made-up provider, benchmark build only
.github/            build, release, screenshot and benchmark workflow, lint gate,
                    emulator reliability and screenshot runners
docs/               banner and screenshots, BROWSING.md (library grouping study),
                    TODO.md (what is open and what to check on a device),
                    HISTORY.md (what the earlier plans did and found)
```

</details>

## Architecture

Relay is one Android app (a single process) that talks to your provider, a few optional services and your other screens. It has no server of its own. The drawings below show the parts, how a stream is found and played, how screens stay in sync, and how the library gets on screen at start-up. Folder names are under `app/src/main/java/dev/relay/tv/`.

### The parts

```mermaid
flowchart TB
    PHONE(["Phone browser<br/>QR code, home Wi-Fi"])
    GTV(["Google TV home screen<br/>Continue watching"])

    subgraph APP["Relay app, one process"]
        UI["ui/<br/>Compose screens: Home, Live TV, Guide,<br/>Movies, Series, Search, Settings"]
        COMP["companion/<br/>web page server for the phone"]
        WN["tv/<br/>WatchNext"]
        PLAYER["player/<br/>Media3 player, stream recovery,<br/>pause buffer"]
        CATALOG["data/Catalog<br/>the library in memory"]
        RATINGS["data/Ratings, Tmdb,<br/>MdbList, Trailers"]
        STORE["data/Store<br/>profiles, favourites, history,<br/>settings, login"]
        API["data/XtreamApi<br/>provider calls, server order"]
        SYNC["sync/<br/>merge engine, pairing,<br/>Play on TV receiver"]
        UPD["update/<br/>in-app updater"]
        TEL["telemetry/<br/>usage rollups &amp;<br/>diagnostic uploads"]
    end

    DISK[("App storage<br/>library lists and saved copy,<br/>settings, guide, logs, pause buffer")]
    PROV(["IPTV provider<br/>several servers"])
    META(["TMDB, MDBList"])
    SYNCFILE(["Encrypted sync file<br/>in a GitHub repo"])
    REL(["GitHub Releases<br/>signed APKs"])
    WORKER(["Cloudflare Worker<br/>optional stats &amp; reports"])
    SCREENS(["Other Relay screens"])

    PHONE -->|"sign-in, keys, sync, backup,<br/>library edits, logs"| COMP
    UI -->|"play"| PLAYER
    UI -->|"lists"| CATALOG
    UI --> RATINGS
    UI --> STORE
    COMP --> STORE
    COMP --> CATALOG
    WN --> STORE
    PLAYER -->|"stream URLs"| API
    PLAYER -->|"watch position"| STORE
    PLAYER -.->|"play time"| TEL
    PLAYER ==>|"video and audio"| PROV
    CATALOG -->|"download, refresh"| API
    CATALOG --> DISK
    STORE --> DISK
    STORE --> TEL
    API ==>|"lists, guide, login"| PROV
    RATINGS --> META
    SYNC <--> STORE
    SYNC <-->|"read, merge, write"| SYNCFILE
    SCREENS <--> SYNCFILE
    SCREENS -->|"Play on TV<br/>home Wi-Fi, sealed"| SYNC
    SYNC -->|"play on this TV"| PLAYER
    UPD -->|"check, download"| REL
    TEL -.->|"opt-in / manual upload"| WORKER
    WN -->|"in-progress titles"| GTV
```

| Part | What it does |
|---|---|
| `RelayApp` | Creates the shared pieces once per process: the HTTP client, `Store`, `XtreamApi`, `Catalog`, ratings, `TelemetryTracker`, the VPN watch and the sync runner. |
| `data/Catalog` | Holds the whole library in memory and prepares it in stages (channels first, then movies and series), so Live TV is usable before everything is ready. |
| `data/XtreamApi` | All calls to the provider, and the list of stream URLs for the player. |
| `data/Store` | Everything personal: profiles, favourites, history, settings and the login. Sync, backups and the phone page all read and write it. |
| `player/` | A separate activity around Media3, with bundled FFmpeg audio decoders, per-session pause-buffer recording and automatic HLS-over-`.ts` playback. |
| `sync/` | Keeps screens the same through one encrypted file; also pairing a new screen, and Play on TV: on a TV with Relay open it listens on the home network for a phone of the same household. |
| `companion/` | A scoped web server on the TV for the phone: sign-in, API keys, sync set-up, backup and restore, library editing and log download. Its plain HTTP helpers (`LanHttp`) are shared with the Play on TV receiver. |
| `telemetry/` | Sends opt-in daily usage rollups and user-triggered, scrubbed crash and diagnostic log uploads to the optional Cloudflare Worker (`tools/telemetry-worker/`). |
| `update/` | Compares the installed build number with the latest GitHub Release (or pre-release, when opted in), verifies the downloaded package and signing key, and installs the APK. |
| `tv/` | Puts movies and episodes in progress on the Google TV home screen; choosing one opens Relay at `relay://resume`. |

### Finding a server and playing a stream

```mermaid
flowchart TB
    PICK["A channel, movie or episode is chosen"] --> URLS["XtreamApi builds one stream URL per server"]
    VPN["net/VpnWatch<br/>is a VPN on?"] --> ORDER
    HEALTH["HostHealth<br/>which servers answered or failed"] --> ORDER
    MARKS["Server marks in the login<br/>with VPN, without VPN, any"] --> ORDER
    ORDER["HostOrder<br/>suitable servers first, last good one leading,<br/>recently failed ones last"] --> URLS
    URLS --> PLAY["PlayerActivity<br/>Media3 ExoPlayer"]
    PLAY --> OK{"Picture?"}
    OK -->|"yes"| WATCH["Playing<br/>position saved to Store"]
    OK -->|"no, or it stalls"| RECOVER["Recovery<br/>reconnect, then the next server"]
    RECOVER --> PLAY
    WATCH -->|"live TV"| SHIFT["Timeshift<br/>records to the cache so live TV can be paused"]
    WATCH -->|"movies and episodes on a TV"| NEXT["WatchNext<br/>Continue watching on Google TV"]
```

### Sync between screens

```mermaid
sequenceDiagram
    participant A as Screen A
    participant F as Sync file (GitHub repo)
    participant B as Screen B
    Note over A,B: Joining: screen B gets the repo, token and key from screen A,<br/>by network discovery on the home network or a pairing link
    A->>A: A favourite or watched title changes in Store
    A->>F: Read the file (encrypted, AES-256-GCM)
    A->>A: Merge item by item: the newest change wins, removals are remembered
    A->>F: Write the merged file, only if nobody else wrote meanwhile
    B->>F: Read the file on its next sync
    B->>B: Merge, then apply what it lacked to its own Store
    B->>F: Write back only if it had something the file lacked
```

### Play on TV

```mermaid
sequenceDiagram
    participant P as Phone (Relay)
    participant T as TV (Relay open, same household)
    Note over P,T: Both hold the household sync key. The TV announces itself on the home network<br/>with a tag worked out from that key; the phone lists only its own household's TVs.
    P->>P: Build the request: channel, film, episode or catch-up, and where to start
    P->>T: Send it sealed (AES-GCM, a key worked out from the household key)
    T->>T: Open it, check it is recent and not a repeat
    T->>T: Look the item up in its own library, check the profile open on the TV
    T->>T: Play it with its own player and its own login
    T-->>P: "Playing on Living room TV", or why not
    P->>P: Stop playing, so the provider sees one connection
```

No stream address, user name or password is in the request: the TV builds the address from its own login.

### How the library gets on screen

```mermaid
flowchart TB
    START(["App start"]) --> SNAP{"Saved copy of the library<br/>present and valid?"}
    SNAP -->|"yes"| READ["Read the saved copy:<br/>library and prepared facts"]
    SNAP -->|"no: missing, damaged,<br/>lists or rules changed"| JSON{"Provider lists<br/>saved as JSON?"}
    JSON -->|"no"| DL["Download six lists<br/>from the provider"]
    JSON -->|"yes"| PARSE["Read and check the lists"]
    DL --> PARSE
    PARSE --> DERIVE["Work out per item: language,<br/>channel versions, quality, sort keys"]
    DERIVE --> SHOW["Channels on screen,<br/>then movies and series"]
    READ --> SHOW
    DERIVE -.->|"a few seconds later"| WRITE["Write a new saved copy"]
    SHOW --> OLD{"Library older<br/>than 6 hours?"}
    OLD -->|"no"| DONE(["Done"])
    OLD -->|"yes, 20 s after start"| REFRESH["Download again in the background"]
    REFRESH --> REUSE["Check the new lists and reuse<br/>the facts for unchanged items"]
    REUSE --> SWAP["Swap the new lists in"]
    SWAP -.-> WRITE
```

## Credits

Built by [Soumalya Ghosh](https://github.com/ghoshs89) with contributions from [Aindrila Ghosh](https://github.com/aindrila-ghosh), alongside AI development assistance from [OpenAI ChatGPT](https://chatgpt.com/) ([Codex](https://openai.com/codex/)), [Google Gemini](https://gemini.google.com/) and [Anthropic Claude](https://claude.ai/).

Built with Jetpack Compose, Media3 ExoPlayer, the Jellyfin FFmpeg audio decoder for Dolby and DTS (GPL-3.0), OkHttp, Coil and kotlinx.serialization. The wordmark uses Space Grotesk (SIL Open Font License).

This product uses the TMDB API but is not endorsed or certified by TMDB. Rotten Tomatoes scores are provided through MDBList; Rotten Tomatoes is a trademark of its owner.

Relay is for personal use and comes with no licence to redistribute.
