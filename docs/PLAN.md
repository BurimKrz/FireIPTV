# Fire TV IPTV Player — Implementation Plan

Oct 22, 2026 · @Burim

> Exported from the [plan doc](https://claude.ai/artifact/Fq1h3cLnd4iF97zX1tu5Ye) on 2026-10-23. The doc is the working copy; this file is a snapshot for reading in the repo.

## Overview

We are building a native Kotlin IPTV player for Fire OS (Android-based Fire TV) that plays a user's own M3U playlist and XMLTV guide, with channel zaps under 1 second. The app ships no content. This doc turns the build plan into ordered phases we can work through, starting from the dev machine setup done today.

**Target.** Personal use on one device: an Amazon Fire TV Stick 4K (2nd gen) on Fire OS 8.1 (Android 11), with 5 GB of free storage. The app is sideloaded over ADB, not published. Vega OS sticks are out of scope because they can't run Android apps.

**Storage.** With 5 GB free, the app keeps its footprint small: the logo disk cache is capped at 50 MB, and the EPG keeps only now − catch-up days to now + 3 days.

**Data use and reliability come first** (decided 2026-10-22). Nothing downloads unless you are watching or you press Refresh. There is no TV guide, no background refresh, and no preloading of channels you might zap to. Video is nearly all the data the app uses, and that is the stream itself.

**v1 scope:** live TV from an M3U/M3U8 URL or an Xtream login (detected from a get.php link), groups, favourites, recents, search, parental PIN, manual refresh, logos cached on disk.

**Not in v1:** TV guide (XMLTV/EPG), now/next, catch-up, movies and series, background refresh, channel preloading, Vega OS build, recording/DVR, multi-view, phone companion app, Amazon Appstore submission.

| Success metric | Target (4K Max) |
| --- | --- |
| Zap to first frame, HLS | ≤ 1.0 s |
| Zap to first frame, MPEG-TS | ≤ 1.5 s |
| 50k-channel import | ≤ 10 s total, first channels in ≤ 2 s |
| Peak memory during import | ≤ 250 MB |
| Crash-free sessions | ≥ 99.5%, no ANRs |
| Channel list scroll | 60 fps, focus never lost |

## Tech stack and architecture

We use first-party Jetpack plus Media3, pinned to the latest stable releases on Google Maven and Maven Central as of today. All versions live in one version catalog (`gradle/libs.versions.toml`), so upgrades are one-line changes.

| Layer | Choice | Version |
| --- | --- | --- |
| Build | Gradle wrapper + Android Gradle Plugin (built-in Kotlin) | Gradle 9.8.2, AGP 9.4.1 |
| Language | Kotlin, coroutines + Flow | Kotlin 2.4.21, coroutines 1.11.0 |
| UI | Jetpack Compose + androidx.tv:tv-material | Compose BOM 2026.09.00, tv-material 1.1.0 |
| Player | Media3 ExoPlayer (exoplayer, hls, dash, datasource-okhttp, ui, session) | 1.11.1 |
| Networking | OkHttp, one shared client | 5.5.1 |
| Database | Room on SQLite, FTS4 for search | 2.8.5 |
| Paging | Paging 3 | 3.5.3 |
| DI | Hilt (KSP) | Hilt 2.60.1, KSP 2.3.12 |
| Background jobs | WorkManager | 2.12.0 |
| Images | Coil 3 | 3.6.3 |
| Settings | DataStore Preferences | 1.3.0 |
| XML | android.util.JsonReader (built in), streams Xtream API responses | — |
| Perf | Macrobenchmark + Baseline Profiles | 1.5.0 |

**SDK levels.** minSdk 25 (Fire OS 6/7 floor), compileSdk and targetSdk 36 (Fire OS 16 = Android 16). Edge-to-edge and predictive back are handled from the first build.

**Not used:** RxJava, Leanback, libVLC (only a phase-3 fallback if real streams need it), WorkManager and XMLTV parsing (no background refresh, no guide).

**Architecture.** One activity, three layers: Compose screens observe ViewModels, ViewModels call repositories, and only the data layer touches network, disk or the decoder. Parsers stream into Room in batches while the UI reads from it, so lists fill in during import. Playback bypasses the database.

**Gradle modules**

| Module | Holds |
| --- | --- |
| `:app` | Activity, navigation, Hilt setup, manifest + banner |
| `:feature:browse` | Home and channel browser |
| `:feature:player` | Full-screen player, zap banner, mini guide |
| `:feature:guide` | Dropped: no TV guide in this build |
| `:feature:settings` | Playlists, EPG, buffer preset, PIN |
| `:core:data` | Repositories, Room, DAOs, sync workers |
| `:core:parser` | Pure-Kotlin M3U and XMLTV parsers, JVM unit tests |
| `:core:player` | PlayerManager, media-source factory, load-control presets |
| `:core:ui` | TV theme, focus helpers, shared components |
| `:benchmark` | Macrobenchmark and Baseline Profile generator |

## Development environment

The dev machine is ready: the project in `~/Projets/FireIPTV` builds a debug APK with `./gradlew assembleDebug` in about 10 s warm. Ubuntu 22.04, 12 cores, 31 GB RAM, KVM acceleration working.

| Component | State | Notes |
| --- | --- | --- |
| JDK | OpenJDK 17.0.20 (already installed) | Meets AGP 9 minimum |
| Android SDK | `~/Android/Sdk`, `ANDROID_HOME` set in `~/.bashrc` | cmdline-tools, platform-tools 37.0.1, build-tools 37.0.0 |
| SDK platforms | 34, 35, 36, 36.1, **37.2 (added)** | Latest AndroidX needs compileSdk 37 |
| Android Studio | Snap, 2026.2.1 (already installed) | Open the project folder directly |
| Gradle | Wrapper 9.8.2 committed in the repo | No system Gradle needed |
| Emulator | 36.6.11, KVM usable | Headless runs with `-no-window` |
| AVD `FireOS8_API30_TV` | **Added**, Android TV API 30, 1080p, 2 GB | Matches your stick: Fire OS 8.1 is Android 11 |
| AVD `Television_1080p` | Existing, Android TV API 34 | Stands in for Fire OS 14 |
| AVD `FireOS16_API36_TV` | **Added**, Android TV API 36 x86\_64 | Stands in for Fire OS 16: back button, edge-to-edge |
| Git | Repo initialised on `main` | `.gitignore` for Gradle and IDE files |

**Project skeleton.** Version catalog with every library from the stack table, all nine modules from the plan (the `:benchmark` module and CI are the rest of phase 0), and a root build script that sets the SDK levels for every module. `:app` has the Fire TV manifest: `LEANBACK_LAUNCHER`, a 320×180 banner, leanback and touchscreen marked not required, landscape, `INTERNET` + `ACCESS_NETWORK_STATE`, predictive back enabled. It shows a placeholder screen with tv-material.

**SDK levels as built:** minSdk 25, compileSdk 37.2, targetSdk 36.

**Daily commands**

```bash
./gradlew assembleDebug                      # build
./gradlew :core:parser:test                  # fast JVM parser tests
emulator -avd FireOS8_API30_TV &             # start a TV emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb connect <fire-tv-ip>:5555                # real stick over Wi-Fi
```

**On a real Fire TV:** Settings → My Fire TV → About → click the device name 7 times to unlock Developer options. Then turn on ADB debugging and allow apps from unknown sources. The IP is under About → Network.

## Build phases

About 12 weeks to Appstore submission for one developer full time. Playback comes first because it decides whether the app is worth finishing. Each gate is measured on your Fire TV Stick 4K (2nd gen) against the plan's low-end targets, which leaves headroom. A missed gate means we fix it before the next phase starts; we don't lower the target.

**Roadmap** (the doc shows this as a chart)

| Phase | Weeks | Ends with |
| --- | --- | --- |
| 0 Setup | 1 | — |
| 1 Playback | 2–4 | Gate 1: zap ≤ 1.5 s |
| 2 Browse and UX | 5–7 | Gate 2: 50k-channel import ≤ 20 s |
| 3 Hardening | 8–10 | — |
| 4 Release | 11–12 | Gate 3: release build meets the performance budget |

Phase 0 is mostly done today; the task lists below track what remains in each phase.

**Phase 0: Setup (week 1)**

- [x] Dev machine: JDK, SDK, compileSdk 37.2, emulators for Fire OS 8, 14 and 16
- [x] Gradle project, version catalog, nine modules, Fire TV manifest
- [x] Debug APK installs and launches on the API 30 TV emulator (cold start 0.96 s)
- [ ] `:benchmark` module: Macrobenchmark + Baseline Profile generator. Moved to just before Gate 1, because emulator timings don't predict the stick
- [ ] Optional for personal use: CI on GitHub Actions running `assembleDebug`, `:core:parser:test` and lint on every push
- [ ] Real devices: put your Fire TV Stick 4K (2nd gen) in developer mode and connect with `adb connect`
- [ ] Test fixtures: a 50k-channel M3U, a 200 MB XMLTV, and a self-hosted test HLS + TS stream

**Phase 1: Playback (weeks 2–4)**

1. `:core:parser`: streaming M3U parser using Okio line reads and a hand-written attribute scanner, with JVM tests over fixture files, including the junk cases (BOM, CRLF, no header, blank names). Done on 2026-10-22: 5 tests pass, and 50k channels parse in 0.15 s on the dev PC. The name starts after the first comma outside quotes, not the last, so names with commas survive.
2. `:core:data`: Room schema (playlist, channel, channel\_fts, group, favorite, recent), WAL mode, 1,000-row batch inserts, staging-swap import. Done on 2026-10-22: 6 instrumented tests pass on the emulator (50k import in 1.7 s, refresh swap, 304, failed refresh keeps the old list, gzip). A first import writes in place so lists fill in as it runs; a refresh uses staging. Favourite, recent and programme tables move to phase 2 with the features that use them. User-Agent and Referer are plain columns instead of headersJson.
3. Playlist add-by-URL screen and import progress shown as a Flow. Done on 2026-10-22: add screen, playlists screen with refresh and two-press delete, tested with an 11,362-channel public playlist. A URL can be sent over adb with --es playlist\_url, so nobody types URLs with the remote. Real provider test (Xtream get.php, m3u\_plus): 20 s before the first byte, 400 MB body, 28,519 live channels kept and 1,430,672 movie/series entries left out, about 5 min on the emulator (download-bound), 82 MB app memory, 7.5 MB database. Playlist reads now wait up to 2 min; names and on-screen URLs never show credentials.
4. `:feature:browse`: groups column plus channel list on Paging 3, `focusRestorer`, `@Immutable` row models. Done on 2026-10-23: the browser is now home, tested on your provider's 28,502 channels in 427 groups. Resting on a group for 250 ms shows its channels; OK on a group jumps into its list. Rows load only name and number, and a new (playlist, sortOrder) index means the all-channels list never sorts 28k rows (database v2, auto-migrated). Scrolling 300 rows on the emulator: under 1% janky frames, focus never lost. No logos yet (phase 2), and the TV guide field is gone from the add screen.
5. `:core:player`: one long-lived ExoPlayer, tuned `DefaultLoadControl`, OkHttp data source with per-channel headers, HLS and TS source picking. Done on 2026-10-23 together with steps 6 and 7, tested on your provider (MPEG-TS) on the emulator: first frame in 534–1,331 ms, median about 690 ms, under the 1.5 s TS gate. Only the stream is downloaded: about 5.4 MB per 10 s on an HD channel, and 0 KB once Home is pressed. A link without .m3u8 that turns out to be HLS is retried as HLS; connection errors and HTTP refusals retry twice (1 s, 3 s), then show a plain-language error, and OK tries again. Stream URLs carry your login, so logs name the channel, never the URL.
6. `:feature:player` (done 2026-10-23): full-screen player, 250 ms debounced up/down zap, instant banner, `setKeepContentOnPlayerReset(true)`.
7. Done 2026-10-23: zap-time logging via `AnalyticsListener.onRenderedFirstFrame`.

**Added 2026-10-23: stall watchdog.** One zap in about 15 on your provider connected but got no data (the 1-connection account still counting the previous stream, most likely), which would have meant a spinner until the 15 s read timeout. A channel that hasn't started within 8 s is now reopened, using the same two retries as errors. Covered by an instrumented test with a server that answers and then sends nothing.

**Added to phase 1: Xtream API import** (moved up from phase 3). A get.php link is detected and imported through player\_api.php: account check, live categories, live streams only. That replaces a 400 MB get.php download with a few MB, and gives clear errors for a wrong login or an expired account. Network calls retry with backoff, so an import started before Wi-Fi is up doesn't fail. Done on 2026-10-22 and tested on your provider: 28,502 live channels in 427 groups for about 0.7 MB per refresh (was 400 MB and 5 min), plus 5 new instrumented tests. Streams use MPEG-TS, which needs less data than HLS. The account allows 1 connection, so the player must close the old stream before opening the next one.

**Gate 1: zap ≤ 1.5 s** (HLS, on your stick).

**Phase 2: Browse and UX (weeks 5–7, lighter without the guide)**

1. Favourites and recents, kept across refreshes by matching tvg-id, then name + URL. Done on 2026-10-23, matched by stream URL, then by name: on your provider only 8,174 of 28,502 channels have a tvg-id and those share 5,380 ids (HD, FHD and backup copies), while all stream URLs are unique and stays the same across refreshes. Long-press OK or Menu (☰) adds or removes a favourite, in the list and while watching (★ in the banner). A channel counts as watched once it actually plays; the newest 30 are kept. A favourite whose channel disappears is hidden and comes back with it. 5 new instrumented tests (17 in total), database v3 auto-migrated.
2. Home rows: continue watching, favourites, groups. Done on 2026-10-23 as two entries at the top of the groups column, Favourites and Recently watched, instead of rows: same remote moves, no extra screen. Zapping from Favourites goes through the favourites; from Recently watched it goes through the channel's group, because recents reorder as you watch.
3. FTS search over channel names (local, no network). Done on 2026-10-23: a Search button next to Playlists, results as you type (250 ms pause), each with its group and ★, capped at 300. Words match as prefixes in any order ("news bbc"), and the index now folds case and accents ("kosove" finds "KOSOVË"; database v4, index rebuilt on upgrade in about 1 s on the emulator for 28,502 channels). OK plays a result and Up/Down zaps its group. 3 new instrumented tests (20 in total). Fixed on the way: text fields kept D-pad Up/Down, trapping the remote in them once the keyboard closed (search and add-playlist screens).
4. `:feature:settings`: playlists, buffer preset, hidden groups, logos on/off. Done on 2026-10-23 except logos on/off, which comes with logos in step 5. The Playlists button is now Settings, with three rows. Playlists opens the existing screen. Groups shows or hides each of the 427 groups (Show all and Hide all ask for a second press); hidden groups leave home, All channels and its zapping, search still finds them, and they stay hidden after a refresh (matched by title). Channel start switches between Fast (starts after 1 s of video, as before) and Stable (2.5 s, fewer pauses on weak Wi-Fi), applied from the next channel. 1 new instrumented test (21 in total).
5. Logo loading with Coil: display-size decode, 50 MB disk cache kept for months, so each logo downloads once. Done on 2026-10-23: logos in channel lists, search results and the player banner, plus Channel logos On/Off in Settings (off: no image requests at all). A cached logo is shown without asking the server again (checked in Coil's source). An uncached one is fetched only once its list has stopped moving for 400 ms and it has been on screen for 300 ms. Measured on the emulator with your playlist (28,431 of 28,502 channels have a logo, about 25 KB each): first screen 45 KB, reopening 0, holding Down through 300 rows 245 KB (2.3 MB before the list-idle rule). Bug found on the way: Coil ignored a request that differed only in its network setting, so no logo ever loaded; fixed.

**Added 2026-10-23: account details in Settings.** The provider's "Account Information" category and channel (a video of the account's details, also spelled "Accout") are left out of every import, so they no longer appear in lists, search or zapping. Settings → Account shows the same details from the Xtream API instead: username, status, expiry with days left, connections in use of the maximum, trial, created date. It costs about 1 KB, only when that screen opens or Refresh is pressed, and uses no stream connection. Shown only for Xtream logins. 2 new instrumented tests (23 in total).

**Gate 2: 50k-channel import ≤ 20 s** (on your stick).

**Phase 3: Hardening (weeks 8–10)**

1. Error recovery: behind-live-window re-prepare, 3× backoff retry, reconnect after Wi-Fi drops, decoder fallback, clear error screens. Done on 2026-10-23: errors retry three times (1, 3, 6 s). When the device is offline the player stops retrying and shows "No internet connection", then reopens the channel by itself when Android reports a network again: tested by cutting the emulator's network mid-zap, the channel was back 0.7 s after the network. Behind-live-window, decoder fallback and plain-language errors were already in from phase 1.
2. Account status: expired or over its connection limit, shown before playback fails. Done on 2026-10-23: when the provider refuses a stream (401, 403, 429, 456, 458, 509, 512, 513), the player asks the account status once (about 1 KB) and says why: not active, expired on a date, or N of N connections in use. It doesn't claim "another device", because panels keep counting a closed stream for a while. Home warns when the account expires within 7 days, from the expiry saved at each refresh (no extra request). Also: channel numbers now come from the provider (Xtream "num"), so leaving out or hiding channels never renumbers the rest.
3. Parental PIN. Dropped on 2026-10-23: not wanted; hiding groups in Settings covers unwanted content.
4. Data-use check: measure MB per hour of viewing and per refresh, and confirm nothing downloads while idle. Done on 2026-10-23 on the emulator with your provider: BBC 1 HD 1.1 GB per hour, BBC 1 SD 0.68 GB per hour, 2–3 MB extra when a channel opens (the provider's initial burst), refresh 0.7 MB, Account screen 1 KB, a logo 25 KB once. Home screen, background and stopped playback measured at or below the emulator's own background traffic (21 KB per 2 min with the app stopped), so the app itself downloads nothing then. Table in the README.
5. Optional: libVLC fallback only if real streams need it. Not needed so far: every channel that failed in testing was refused by the provider, not unplayable.

**Phase 4: Release (weeks 11–12)**

1. Performance pass against the full budget table on your stick, Baseline Profile updated.
2. Release signing key, kept outside the repo, so every update installs over the last one without losing playlists or favourites.
3. Signed, R8-shrunk release APK installed with `adb install -r`.
4. A week of daily use, fixing what comes up.

**Added to phase 4: updates from GitHub** (asked 2026-10-23). Once the release build is on the TV, a new version reaches it by pushing to GitHub: the app shows that an update exists and installs it when you say so.

1. Release signing key stored as a GitHub Actions secret, so APKs built on GitHub install over the one on the TV. Android refuses an update signed with a different key, and uninstalling first would erase playlists and favourites.
2. GitHub Actions workflow: pushing a version tag such as `v0.2.0` builds the signed release APK, takes versionCode from the tag, and publishes a GitHub release with the APK attached.
3. Update check when the app opens, at most once a day, plus a "Check for updates" button in settings. It is one small request (a few KB) for the latest release; nothing checks in the background, in line with the data-use rule.
4. When a newer version exists, home shows "Update 0.2.0 available" with an Install button. Nothing downloads until you press it.
5. Install: download the APK (about 15 MB) once, check its signature and version, and hand it to Android's installer. The first time, Fire OS asks you to allow FireIPTV to install apps; after that each update is one confirmation, and playlists and favourites are kept.

**Gate 3: the release build meets the performance budget on your stick.** Dropping the Appstore work (store assets, Amazon testing, review) shortens this phase.

## Testing and deployment

Emulators are for daily work. Every performance gate is signed off on your real stick, because Android TV emulators can't reproduce Fire OS decoders or low-RAM behaviour.

**Test layers**

| Layer | What | Where it runs |
| --- | --- | --- |
| Unit | M3U/XMLTV parsers over fixture files, matching rules, time parsing | JVM, `:core:parser:test`, every push |
| Integration | Room import of 50k rows, staging swap, FTS queries | Instrumented test on emulator |
| UI | D-pad focus paths, Back behaviour on Fire OS 14 and 16 | Compose UI tests on TV emulators |
| Performance | Startup, scroll jank, zap time, import time | Macrobenchmark on a connected Fire TV |
| Debug guards | StrictMode, LeakCanary | All debug builds |

**Device:** your Fire TV Stick 4K (2nd gen), Fire OS 8.1. The API 34 and API 36 TV emulators cover newer Fire OS versions in case you upgrade later.

**Deploying to a stick**

1. Enable Developer options and ADB debugging on the stick (see Development environment).
2. `adb connect <ip>:5555` and accept the prompt on the TV.
3. `./gradlew installDebug`, or `adb install -r` the APK.
4. Read the logs with `adb logcat --pid=$(adb shell pidof com.fireiptv.player)`.
5. Capture zap and import traces with Perfetto (`adb shell perfetto`) when chasing a gate.

## Risks and open questions

The biggest risks are platform policy and shrinking reach, not code.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Amazon flags the app as a piracy tool | Disabled on every Fire OS device | Amazon disables flagged apps even when sideloaded: no bundled lists, only your own legal playlists |
| New sticks run Vega OS only | Shrinking share of new buyers | Target Fire OS now; re-evaluate a React Native Vega app after v1 metrics |
| Fire OS 16 behaviour changes | Broken back navigation, layout | Compose BackHandler, edge-to-edge, test on the API 36 emulator now |
| Odd streams (non-IDR TS, wrong MIME, expiring tokens) | Black screen or slow start | TS extractor flags, MIME probe, per-channel headers, libVLC fallback in phase 3 |
| Huge playlists/EPGs filling 5 GB of free storage or RAM | OOM, ANR | Streaming parsers, batch inserts, windowed EPG, 50 MB logo cache cap, memory gates |
| Xtream provider rate limits | Login failures | Cache, one request at a time, respect Retry-After |

**Open questions**

- [x] Name and app ID: keeping "FireIPTV" / `com.fireiptv.player`, since this is personal use.
- [x] Device: Fire TV Stick 4K (2nd gen), Fire OS 8.1, 5 GB free.
- [ ] Your stick's IP address, once ADB debugging is on.
- [ ] Repo hosting: done, private repo BurimKrz/FireIPTV. For in-app updates the app must read releases without a login. Recommended: a small public repo that holds only the release APKs, filled by the workflow; the code stays private. The alternatives are making this repo public, or putting a GitHub token in the app, which anyone with the APK could pull out.
- [ ] Which legal test streams do we use for fixtures?

**Next up:** phase 4, release build: a signing key kept outside the repo, then an R8-shrunk release APK tested on the emulator. Gate 1 (zap time on your Fire TV Stick) waits for the stick and this PC to be on the same network: the stick is on 192.168.10.x, the PC on 192.168.0.x.
