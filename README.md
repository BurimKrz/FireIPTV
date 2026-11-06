# FireIPTV

A live-TV player for Amazon Fire TV (Fire OS, Android-based). It plays your own M3U playlist or Xtream login. The app ships no channels, no TV guide and does no background downloads: nothing uses the network unless you are watching or press Refresh.

Built for personal use on a Fire TV Stick 4K (2nd gen, Fire OS 8.1) and installed by sideloading, not through the Appstore.

The build plan, phases and what's done so far: [docs/PLAN.md](docs/PLAN.md).

## Status

Version 0.3.0.

| Works now | Left out on purpose |
| --- | --- |
| Add a playlist by URL (M3U or Xtream `get.php` link) | TV guide (EPG) |
| Xtream import of live channels only (~0.7 MB per refresh) | Movies and series from the provider |
| Playlists screen: refresh, delete | Background refresh |
| Home: groups + channel list, D-pad navigation | Parental PIN (hide groups instead) |
| Full-screen live TV (MPEG-TS and HLS), zapping with Up/Down | |
| Sound in MP2 too (most Albanian channels), through a small FFmpeg decoder | |
| Your own categories, and pinning any category to the top | |
| Channel list at the side while watching (Left/Right) | |
| Crash and error log, readable on the TV (Settings → Log) | |
| Favourites and recently watched, kept across refreshes | |
| Search by channel name, on the TV (no network) | |
| Settings: hide groups, channel start Fast/Stable | |
| Channel logos, downloaded once and kept (can be turned off) | |
| Account details from an Xtream login (Settings → Account) | |
| Updates from GitHub releases, installed from Settings → Updates | |

## Yes, you can install it as an APK

Build the release APK, the one to put on the TV:

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk  (~3.7 MB)
```

It is shrunk with R8 and signed with your own release key, so every later release installs over it and keeps your playlists, favourites and settings. APK files are not committed to this repo (`*.apk` is in `.gitignore`).

**The release key.** It lives outside the repo:

| File | Holds |
| --- | --- |
| `~/.android/fireiptv-release.jks` | the key |
| `~/.gradle/gradle.properties` | its path and password (`fireiptv.*` lines) |

**Back up both somewhere safe** (a USB stick, a password manager). If the key is lost, a new build can't update the app on the TV: it would have to be uninstalled first, which erases your playlists and favourites. Without those lines, `assembleRelease` still builds, but unsigned.

## Branches and releases

Work happens on `dev`. Every push there, and every pull request, runs `.github/workflows/ci.yml` (debug and release build, parser tests). Changes reach `main` through a pull request.

A release is a version tag on `main`:

1. Raise `appVersion` in `app/build.gradle.kts` (the `versionCode` follows from it) and merge to `main`.
2. `git tag v0.2.2 && git push origin v0.2.2` on `main`. `.github/workflows/release.yml` builds the signed APK and publishes it as a GitHub release, `FireIPTV-<version>.apk`. It stops if the tag and `appVersion` disagree.

The workflow signs with the same key, from four repository secrets: `FIREIPTV_KEYSTORE_BASE64` (`base64 -w0 ~/.android/fireiptv-release.jks`), `FIREIPTV_KEYSTORE_PASSWORD`, `FIREIPTV_KEY_ALIAS` and `FIREIPTV_KEY_PASSWORD`. If a run fails with "base64: invalid input", the first one was pasted wrong.

The app checks the latest release at most once a day when it opens (one request of a few KB), or now with OK on Settings → Updates. When a newer one is out, home shows "Settings · Update available", and OK in Settings → Updates downloads the APK, checks it's signed with the same key, and opens the system installer. The first time, Fire OS asks to allow FireIPTV to install apps.

**MP2 audio** comes from `core/ffmpeg/src/main/jniLibs`, prebuilt and committed. Rebuild it with `tools/build-ffmpeg.sh` (NDK 28, about 1 GB of sources fetched into `~/build-ffmpeg`) only after a media3 version bump. See `core/ffmpeg/README.md`.

## Debug builds

`./gradlew assembleDebug` builds a development version (~22 MB, debug-signed, with a "Leaks" memory checker icon). Debug and release builds have different keys, so switching a device from one to the other means uninstalling first.

## Build requirements

- JDK 17
- Android SDK with platform 37.2 (`compileSdk`), build-tools 37, platform-tools (`adb`)
- `local.properties` pointing at the SDK: `sdk.dir=/home/<you>/Android/Sdk` (Android Studio writes this for you)

Gradle comes with the repo (`./gradlew`); nothing else to install.

## Install on the Fire TV Stick

Once the app is on the stick, updates come through Settings → Updates. A first install is one of these:

### Option A: Downloader with the GitHub release (easiest)

1. Install **Downloader** (by AFTVnews) from the Amazon Appstore, and allow it under **Settings → My Fire TV → Developer options → Install unknown apps**.
2. In Downloader, enter the APK link from the [latest release](https://github.com/BurimKrz/FireIPTV/releases/latest), e.g. `https://github.com/BurimKrz/FireIPTV/releases/download/v0.2.1/FireIPTV-0.2.1.apk`, and choose **Install**.

### Option B: over Wi-Fi with adb

1. On the stick: **Settings → My Fire TV → About**, click the device name 7 times to unlock **Developer options**.
2. **Settings → My Fire TV → Developer options**: turn on **ADB debugging** and **Apps from unknown sources**.
3. Find the stick's IP under **Settings → My Fire TV → About → Network**.
4. On the PC (same network):

   ```bash
   adb connect <stick-ip>:5555        # accept the prompt on the TV the first time
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

5. The app appears as **FireIPTV** under **Apps** on the stick. `adb install -r` again to update; your playlists are kept.

### Option C: a build from this PC, through Downloader

1. Set up Downloader as in option A.
2. On the PC, serve the APK on your home network:

   ```bash
   cd app/build/outputs/apk/release && python3 -m http.server 8000
   ```

3. In Downloader, open `http://<pc-ip>:8000/app-release.apk` and choose **Install**. Stop the server afterwards (Ctrl+C).

## First start

The first screen asks for a playlist URL. Typing a long URL with the remote is slow, so you can send it from the PC instead:

```bash
adb shell "am start -n com.fireiptv.player/.MainActivity --es playlist_url 'http://example.com/get.php?username=...&password=...'"
```

The app opens on the add screen with the URL filled in and the **Import** button focused; press OK. An Xtream `get.php` link is detected automatically and imported through the Xtream API (live channels only). The provider's "Account Information" channel is left out of the lists; the same details are under Settings → Account.

> Never commit your playlist URL: it contains your username and password. Keep it in `docs/` (git-ignored).

## Using the app

**Home**

| Remote | Does |
| --- | --- |
| Up / Down in the groups column | Moves through All channels, Favourites, Recently watched and the groups; resting on one for a moment shows its channels |
| OK on a group | Jumps into its channel list |
| OK on a channel | Plays it full screen |
| Menu (☰) on a channel | Adds it to Favourites, or removes it (★ marks favourites) |
| Long-press OK on a channel | Opens a menu: Favourites, a ✓ for each of your own categories, and **New category…** to make one with this channel in it |
| Menu (☰) on a group or category | Pins it to the top (★), under Recently watched, or unpins it |
| Long-press OK on a group or category | Pin or unpin; for your own categories also Rename and Delete (press twice) |
| Left from the channel list | Back to the group you came from |
| **Search** button (top right) | Search channel names as you type; case and accents don't matter ("kosove" finds "KOSOVË") |
| **Settings** button (top right) | Playlists (refresh, delete, add), Account (status, expiry, connections), Groups (hide the ones you never watch), Channel start (Fast or Stable), Channel logos (On or Off), Updates, Log |
| Back on home | Leaves the app |

The groups column lists All channels, Favourites and Recently watched, then pinned groups and categories, then your own categories, then the provider's groups. Your categories, like favourites, follow a channel through a refresh. "Refreshed N days ago" next to the playlist name says how old the list is; it only refreshes when you press Refresh in Settings → Playlists.

**Search**

| Remote | Does |
| --- | --- |
| Type on the on-screen keyboard | Results update as you type; each shows its group |
| Back (closes the keyboard), then Down | Moves into the results |
| OK on a result | Plays it; Up/Down then zaps through its group |
| Menu (☰) | Adds it to Favourites, or removes it |
| Long-press OK | The same menu as on home: Favourites and your categories |

**Watching**

| Remote | Does |
| --- | --- |
| Down / Up (or CH+ / CH-) | Next / previous channel in the list you opened it from (a channel opened from Recently watched zaps through its group); the banner moves at once, the stream switches when you stop pressing |
| OK | Shows or hides the channel banner; on an error, tries again |
| Menu (☰) | Adds the channel to Favourites, or removes it |
| Left or Right | Opens the list you're watching from at the side, on the current channel: Up/Down move through it, OK plays one, Left/Right or Back closes it |
| Play/Pause | Stops the stream (no data used) / starts it again |
| Back | Returns to the list, on the channel you were watching |

If the internet drops, the player shows "No internet connection" and starts the channel again by itself when it's back. If the provider refuses a channel, the message says why (account expired, or all connections in use). A channel that freezes is reopened by itself; if that doesn't help, the player says so instead of spinning. Crashes, failed channels and automatic retries are listed under Settings → Log (the newest 100, links hidden).

Refresh only runs when you press it. Nothing updates in the background, and the stream closes as soon as you leave the player or press Home. Each channel logo downloads once (about 25 KB) and is kept in a 50 MB cache; logos for rows you scroll past are not fetched, and turning logos off in Settings stops all logo downloads.

## Data use

Measured on 2026-10-23 with an Xtream provider (MPEG-TS streams):

| What | Data |
| --- | --- |
| Watching an HD channel | about 1.1 GB per hour |
| Watching the SD version of the same channel | about 0.7 GB per hour |
| Opening a channel | 2–3 MB extra at the start (the provider sends a few seconds at once) |
| Refreshing the playlist | about 0.7 MB |
| Opening Settings → Account | about 1 KB |
| A channel logo, the first time it is shown | about 25 KB, then never again |
| Update check, at most once a day when the app opens | a few KB |
| Installing an update, only when you press OK | about 3.7 MB |
| Home screen, Settings, app in the background, playback stopped | nothing |

Video is almost all of it. Picking a channel's SD version is the one choice that changes the total much.

## Run on the emulator (development)

```bash
emulator -avd FireOS8_API30_TV &          # Android TV API 30 = Fire OS 8
adb wait-for-device
./gradlew installDebug
adb shell am start -n com.fireiptv.player/.MainActivity
```

A public test playlist (about 11,000 channels): `https://iptv-org.github.io/iptv/index.m3u`.

## Tests

```bash
./gradlew :core:parser:test                       # M3U parser, JVM, seconds
./gradlew :core:data:connectedDebugAndroidTest    # import, database, update JSON; needs an emulator or device
./gradlew :core:player:connectedDebugAndroidTest  # stall watchdog; needs an emulator or device
```

## Project layout

| Module | Holds |
| --- | --- |
| `:app` | Activity, navigation, Hilt setup, Fire TV manifest |
| `:feature:browse` | Home: groups and channel list |
| `:feature:settings` | Settings: playlists, account, groups, start mode, logos, updates |
| `:feature:player` | Full-screen player, zapping, channel banner |
| `:core:data` | Room database, playlist import (M3U + Xtream), repositories, update check and download |
| `:core:parser` | Streaming M3U parser |
| `:core:player` | `PlayerManager`: ExoPlayer, stream type picking, retries, stall watchdog, zap timing |
| `:core:ffmpeg` | FFmpeg audio decoder for MP2 (media3 sources + prebuilt native library) |
| `:core:log` | `AppLog`: crashes, player errors and retries kept on the device for Settings → Log |
| `:core:ui` | TV theme |
| `tools/` | `build-ffmpeg.sh` |
| `.github/workflows/` | `ci.yml` (dev and pull requests), `release.yml` (version tags) |
