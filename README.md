# FireIPTV

A live-TV player for Amazon Fire TV. It plays your own M3U playlist or Xtream login; it ships no channels and no TV guide, and nothing uses the internet unless you are watching or press a button.

Made for one Fire TV Stick 4K (2nd gen, Fire OS 8.1) and installed by sideloading, not through the Appstore. Version 1.0.0.

## Install

Once the app is on the stick, new versions come through **Settings → Updates**. The first install:

1. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
2. On the stick: **Settings → My Fire TV → Developer options → Install unknown apps**, allow Downloader. (No Developer options? **Settings → My Fire TV → About**, click the device name 7 times.)
3. In Downloader, open the APK link from the [latest release](https://github.com/BurimKrz/FireIPTV/releases/latest), e.g. `https://github.com/BurimKrz/FireIPTV/releases/download/v1.0.0/FireIPTV-1.0.0.apk`, and choose **Install**.

The app appears as **FireIPTV** under **Apps**.

## First start

The first screen asks for your provider's Xtream login: **Server** (e.g. `http://provider.com:8080`, `http://` may be left out), **Username** and **Password** (hidden; **Show password** to check it). The Xtream API sends live channels only, about 0.7 MB instead of the full list with movies and series. For any other playlist, **Use an M3U link instead** takes a link; a provider's `get.php` link there is read as an Xtream login too.

Reinstalled? Press **Restore from backup** on that screen instead: it brings back the playlist, favourites, your categories, hidden groups and channels, and settings (see [Backup](#backup)).

Typing with the remote is slow; from a PC with adb you can send the provider's `get.php` link, which fills in the login (or an M3U link, which opens the M3U form):

```bash
adb shell "am start -n com.fireiptv.player/.MainActivity --es playlist_url 'http://example.com/get.php?username=...&password=...'"
```

## Remote keys

**Home**

| Key | Does |
| --- | --- |
| Up / Down in the groups column | All channels, Favourites, Recently watched, pinned groups, your categories, the provider's groups |
| OK on a group | Into its channels |
| OK on a channel | Plays it |
| Menu (☰) on a channel | Favourite on or off (★) |
| Long-press OK on a channel | Favourites, your categories (✓), **New category…**, **Hide channel**; in Favourites or a category also **Move up** / **Move down** |
| Menu (☰) on a group or category | Pins it to the top, or unpins it |
| Long-press OK on a group or category | Pin; for your own categories also Rename and Delete (press twice) |
| **Search** (top right) | Searches every channel by name as you type, hidden ones included; case and accents don't matter ("kosove" finds "KOSOVË") |
| Back | Press twice to leave the app |

**Watching**

| Key | Does |
| --- | --- |
| Up / Down, CH+ / CH- | Previous / next channel in the list you came from (hidden channels are skipped) |
| Left / Right | The list at the side; Up/Down and OK pick a channel, Back closes it |
| OK | Shows the banner (number, name, resolution, data rate) |
| Menu (☰) | Favourite on or off |
| Play/Pause | Stops the stream (no data used), or starts it again |
| Fast-forward (⏩) | Sleep timer: off, 30, 60, 90 minutes |
| Back | Back to the list, on the channel you watched |

**When a channel fails**

The player retries by itself (three times) and reopens a stream that freezes or stops. If it still can't play, it says why: the channel is offline, the account expired, or all its connections are in use (another device or app is watching on the same login). If the channel has other versions (HD, SD, a [BK] backup), they are listed under the message: Up/Down to choose, OK to play. No internet? It waits and starts again when the connection is back.

After 3 hours without a key press it asks "Still watching?" and stops the stream if nobody answers, so a TV left on doesn't download all night.

## Settings

| Row | Does |
| --- | --- |
| Playlists | Refresh (only when you press it), delete, add |
| Account | Status, expiry date and connections, from an Xtream provider |
| Groups | Hide groups you never watch; search still finds their channels |
| Hidden channels | Channels you hid one by one; OK shows one again |
| Channel start | Fast (starts sooner) or Stable (pauses less on weak Wi-Fi) |
| Channel logos | On (each downloads once, 50 MB cache) or Off (no logo downloads) |
| Data used | Today and this month, counted by Fire OS |
| Back up / Restore | See below |
| Updates | Checks GitHub, downloads and installs a new version |
| Log | Crashes, failed channels and automatic retries (links hidden) |

### Backup

**Back up** saves favourites, your categories, hidden groups and channels, pinned groups, settings and the playlist login to **Pictures/FireIPTV-backup.png** on the stick. It is an image (a small card saying "FireIPTV backup") because an image is the only kind of file Fire OS lets the app read again after it was uninstalled; the data rides inside it. The file holds your playlist login, so it stays on the stick.

**Restore** adds the backup to what is there. After a reinstall, use **Restore from backup** on the first screen and allow "photos and media" when asked: the playlist is added, imported, and everything else follows.

## Data use

Measured with an Xtream provider (MPEG-TS):

| What | Data |
| --- | --- |
| Watching HD | about 1.1 GB per hour |
| Watching SD | about 0.7 GB per hour |
| Opening a channel | 2–3 MB extra at the start |
| Refreshing the playlist | about 0.7 MB |
| A channel logo, the first time | about 25 KB |
| Update check, at most once a day when the app opens | a few KB |
| Home, Settings, stopped, in the background | nothing |

Video is almost all of it: choosing a channel's SD version is the one setting that changes the total much.

## Left out on purpose

TV guide (EPG), catch-up, movies and series, parental PIN (hide groups instead), background refresh, audio track choice, picture zoom.

---

## For development

**Build.** JDK 17 and the Android SDK (platform 37.2, build-tools 37); `local.properties` with `sdk.dir=...`.

```bash
./gradlew assembleDebug                          # debug build (~22 MB, debug key)
./gradlew assembleRelease                        # release build (~3.7 MB, R8, release key)
./gradlew :core:parser:test lintDebug            # JVM tests and lint, as CI runs them
./gradlew connectedDebugAndroidTest              # database, import, player tests on an emulator
emulator -avd FireOS8_API30_TV &                 # Android TV API 30 = Fire OS 8
```

Debug and release builds have different keys: switching a device between them needs an uninstall (Back up first). Public test playlist: `https://iptv-org.github.io/iptv/index.m3u`.

**Release key.** `~/.android/fireiptv-release.jks`, with its path and passwords as `fireiptv.*` lines in `~/.gradle/gradle.properties`. Keep a copy off this PC: without it, no update can install over the app on the TV.

**Branches and releases.** Work happens on `dev`; CI (`.github/workflows/ci.yml`) builds, lints and tests every push and pull request. A release: raise `appVersion` in `app/build.gradle.kts`, merge `dev` into `main` by pull request, then `git tag v<version> && git push origin v<version>`. `release.yml` builds the signed APK from four secrets (`FIREIPTV_KEYSTORE_BASE64`, `FIREIPTV_KEYSTORE_PASSWORD`, `FIREIPTV_KEY_ALIAS`, `FIREIPTV_KEY_PASSWORD`) and publishes `FireIPTV-<version>.apk`; the app finds it. "base64: invalid input" means the first secret was pasted wrong.

**MP2 audio** comes from a prebuilt FFmpeg decoder in `core/ffmpeg/src/main/jniLibs`; rebuild it with `tools/build-ffmpeg.sh` only after a Media3 update (see `core/ffmpeg/README.md`).

| Module | Holds |
| --- | --- |
| `:app` | Activity, navigation, manifest, Baseline Profile |
| `:feature:browse` | Home, search, long-press menus |
| `:feature:player` | Full-screen player, zapping, banner, other versions on failure |
| `:feature:settings` | Settings screens, add playlist, backup and restore buttons |
| `:core:data` | Room database, M3U and Xtream import, repositories, backup, updates |
| `:core:parser` | Streaming M3U parser |
| `:core:player` | ExoPlayer setup, retries, stall and freeze watchdogs |
| `:core:ffmpeg` | MP2 audio decoder |
| `:core:log` | The on-device log behind Settings → Log |
| `:core:ui` | TV theme, logos, focus helpers |
