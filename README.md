# FireIPTV

A live-TV player for Amazon Fire TV (Fire OS, Android-based). It plays your own M3U playlist or Xtream login. The app ships no channels, no TV guide and does no background downloads: nothing uses the network unless you are watching or press Refresh.

Built for personal use on a Fire TV Stick 4K (2nd gen, Fire OS 8.1) and installed by sideloading, not through the Appstore.

The build plan, phases and what's done so far: [docs/PLAN.md](docs/PLAN.md).

## Status

| Works now | Not yet |
| --- | --- |
| Add a playlist by URL (M3U or Xtream `get.php` link) | Parental PIN |
| Xtream import of live channels only (~0.7 MB per refresh) | |
| Playlists screen: refresh, delete | |
| Home: groups + channel list, D-pad navigation | |
| Full-screen live TV (MPEG-TS and HLS), zapping with Up/Down | |
| Favourites and recently watched, kept across refreshes | |
| Search by channel name, on the TV (no network) | |
| Settings: hide groups, channel start Fast/Stable | |
| Channel logos, downloaded once and kept (can be turned off) | |

## Yes, you can install it as an APK

Every build produces an APK file you can put on the stick:

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk  (~15 MB)
```

APK files are not committed to this repo (`*.apk` is in `.gitignore`), so build one on your PC first.

This is a **debug** APK, which is fine for personal use. It is signed with this PC's debug key, so later updates install over it only when they're built on the same PC. A build from another machine needs an uninstall first, and that erases your playlists. A properly signed release APK with its own key comes in phase 4.

Debug builds also install a second "Leaks" icon (LeakCanary, a memory-leak detector). Release builds won't have it.

## Build requirements

- JDK 17
- Android SDK with platform 37.2 (`compileSdk`), build-tools 37, platform-tools (`adb`)
- `local.properties` pointing at the SDK: `sdk.dir=/home/<you>/Android/Sdk` (Android Studio writes this for you)

Gradle comes with the repo (`./gradlew`); nothing else to install.

## Install on the Fire TV Stick

### Option A: over Wi-Fi with adb (recommended)

1. On the stick: **Settings → My Fire TV → About**, click the device name 7 times to unlock **Developer options**.
2. **Settings → My Fire TV → Developer options**: turn on **ADB debugging** and **Apps from unknown sources**.
3. Find the stick's IP under **Settings → My Fire TV → About → Network**.
4. On the PC (same network):

   ```bash
   adb connect <stick-ip>:5555        # accept the prompt on the TV the first time
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. The app appears as **FireIPTV** under **Apps** on the stick. `adb install -r` again to update; your playlists are kept.

### Option B: without adb, using the Downloader app

1. Install **Downloader** (by AFTVnews) from the Amazon Appstore on the stick.
2. **Settings → My Fire TV → Developer options → Install unknown apps**: allow Downloader.
3. On the PC, serve the APK on your home network:

   ```bash
   cd app/build/outputs/apk/debug && python3 -m http.server 8000
   ```

4. In Downloader, open `http://<pc-ip>:8000/app-debug.apk` and choose **Install**. Stop the server afterwards (Ctrl+C).

## First start

The first screen asks for a playlist URL. Typing a long URL with the remote is slow, so you can send it from the PC instead:

```bash
adb shell "am start -n com.fireiptv.player/.MainActivity --es playlist_url 'http://example.com/get.php?username=...&password=...'"
```

The app opens on the add screen with the URL filled in and the **Import** button focused; press OK. An Xtream `get.php` link is detected automatically and imported through the Xtream API (live channels only).

> Never commit your playlist URL: it contains your username and password. Keep it in `docs/` (git-ignored).

## Using the app

| Remote | Does |
| --- | --- |
**Home**

| Remote | Does |
| --- | --- |
| Up / Down in the groups column | Moves through All channels, Favourites, Recently watched and the groups; resting on one for a moment shows its channels |
| OK on a group | Jumps into its channel list |
| OK on a channel | Plays it full screen |
| Long-press OK, or Menu (☰), on a channel | Adds it to Favourites, or removes it (★ marks favourites) |
| Left from the channel list | Back to the group you came from |
| **Search** button (top right) | Search channel names as you type; case and accents don't matter ("kosove" finds "KOSOVË") |
| **Settings** button (top right) | Playlists (refresh, delete, add), Groups (hide the ones you never watch), Channel start (Fast or Stable), Channel logos (On or Off) |
| Back on home | Leaves the app |

**Search**

| Remote | Does |
| --- | --- |
| Type on the on-screen keyboard | Results update as you type; each shows its group |
| Back (closes the keyboard), then Down | Moves into the results |
| OK on a result | Plays it; Up/Down then zaps through its group |
| Long-press OK, or Menu (☰) | Adds it to Favourites, or removes it |

**Watching**

| Remote | Does |
| --- | --- |
| Down / Up (or CH+ / CH-) | Next / previous channel in the list you opened it from (a channel opened from Recently watched zaps through its group); the banner moves at once, the stream switches when you stop pressing |
| OK | Shows or hides the channel banner; on an error, tries again |
| Menu (☰) | Adds the channel to Favourites, or removes it |
| Play/Pause | Stops the stream (no data used) / starts it again |
| Back | Returns to the list, on the channel you were watching |

Refresh only runs when you press it. Nothing updates in the background, and the stream closes as soon as you leave the player or press Home. Each channel logo downloads once (about 25 KB) and is kept in a 50 MB cache; logos for rows you scroll past are not fetched, and turning logos off in Settings stops all logo downloads.

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
./gradlew :core:parser:test                     # M3U parser, JVM, seconds
./gradlew :core:data:connectedDebugAndroidTest  # import + database, needs an emulator or device
```

## Project layout

| Module | Holds |
| --- | --- |
| `:app` | Activity, navigation, Hilt setup, Fire TV manifest |
| `:feature:browse` | Home: groups and channel list |
| `:feature:settings` | Add-playlist and playlists screens |
| `:feature:player` | Full-screen player, zapping, channel banner |
| `:core:data` | Room database, playlist import (M3U + Xtream), repositories |
| `:core:parser` | Streaming M3U parser |
| `:core:player` | `PlayerManager`: ExoPlayer, stream type picking, retries, zap timing |
| `:core:ui` | TV theme |
