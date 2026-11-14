# FireIPTV — Plan

Updated Nov 14, 2026 · @Burim

> This file is the working plan. The older [plan doc](https://claude.ai/artifact/Fq1h3cLnd4iF97zX1tu5Ye) holds the full history of phases 0–4.

## What it is

A native Kotlin live-TV player for one Fire TV Stick 4K (2nd gen, Fire OS 8.1 / Android 11, 5 GB free). It plays your own M3U or Xtream playlist, ships no content, and is sideloaded, not published.

**Rules every change follows**

- **Data:** nothing downloads unless you are watching or you press a button. No TV guide, no background refresh, no preloading channels.
- **Reliable first:** a stream that drops recovers by itself; when it can't, the error says why in plain words.
- **Light:** small APK (3.7 MB), flat UI, logo cache capped at 50 MB.
- **Out of scope:** TV guide/EPG, catch-up, movies and series, parental PIN, audio track choice, picture zoom, starting on the last channel, Appstore, Vega OS.

## Where we are

**On the stick: 0.3.0.** 0.3.1 was skipped: the freeze fix ships together with the 0.4 work as 0.4.0.

| Area | Done |
| --- | --- |
| Playlists | M3U by URL, Xtream login (live only, about 0.7 MB per refresh instead of 400 MB), refresh on request, "Refreshed N days ago" |
| Browse | Groups column + paged channel list (28,502 channels, 427 groups), Favourites, Recently watched (20), your own categories, pinned groups, search (accents ignored) |
| Player | First frame in about 0.7 s (TS, emulator), Up/Down zap, side list on Left/Right, banner with resolution and data rate, MP2 audio via FFmpeg, sleep timer on ⏩, "Still watching?" after 3 h |
| Recovery | Errors retry 3× (1, 3, 6 s); offline waits and resumes by itself; stalls, provider-closed streams and frozen pictures reopen the channel (dev); account-aware refusal messages |
| Settings | Playlists, Account, Groups (hide), Fast/Stable start, logos On/Off, data used (today, this month), Updates, Log |
| Updates | Tag `v<version>` on `main` → CI builds a signed APK → the app finds it (once a day, on open) |

**Measured data use:** HD 1.1 GB/h, SD 0.68 GB/h, 2–3 MB extra per channel start, 0 while idle.

## Road to 1.0

Each step is a release that goes to the stick through Settings → Updates. A step is done when it works on the stick, not just on the emulator.

### 0.3.1 — Freeze fix on the stick

- [x] Reopen the channel when the provider ends the stream, when the picture stands still for 6 s while "playing", or when data only trickles in (shares the 3 retries)
- [ ] Released inside 0.4.0 (0.3.1 skipped). Watch real channels for a few days; the Log shows "the picture froze … reopened" when it kicks in

### 0.4 — Use less data

- [x] **Still watching?** After 3 hours with no key press, ask once; stop the stream if nobody answers within a minute. Once stopped, the TV may dim and sleep
- [x] **Sleep timer:** off / 30 / 60 / 90 min, stepped with Fast-forward (⏩) and shown on the banner; the stream stops when it runs out
- [x] **Data counter** in Settings: today and this month, from the system's byte count for the app (no extra requests)
- [x] **Stream info** on the banner: resolution and the rate data arrives at, e.g. "1080p · 8.3 Mb/s"
- [ ] Release 0.4.0 (with the freeze fix) and check it on the stick

### 0.5 — When channels fail

- [ ] **Try the sister channel:** when a channel fails, offer its other copies (HD / FHD / SD of the same name) with one press
- [ ] **Hide single channels:** long-press → Hide; hidden channels leave lists and zapping, stay hidden after a refresh, and can be shown again from Settings

### 0.6 — Everyday comfort

- [ ] **Previous channel:** one key (Rewind) jumps between the last two channels
- [ ] **Reorder favourites and your categories:** move a channel up or down from its long-press menu
- [ ] **Backup and restore:** save favourites, categories, hidden groups and channels, and settings to a file, and load them back after a reinstall

### 0.9 — Measured on the stick

Needs the stick reachable over adb (see Open items).

- [ ] **Gate 1:** zap to first frame ≤ 1.5 s
- [ ] **Gate 2:** full playlist import ≤ 20 s
- [ ] **Gate 3:** no jank scrolling lists, no ANRs, memory stays low during import
- [ ] Baseline Profile for faster start and scrolling
- [ ] Lint in CI, after clearing the existing warnings

### 1.0 — Full release

- [ ] Two weeks of daily use with nothing new in the Log that is the app's fault
- [ ] README as a short user guide: install, remote keys, settings, data use
- [ ] Release 1.0 on `main`; from then on, only bug fixes and small requests

## How we work

- **Branches:** work on `dev` (CI builds and tests every push). Merge `dev` → `main` by pull request only for a release.
- **Release:** bump `appVersion` in `app/build.gradle.kts`, merge to `main`, push tag `v<version>`. CI publishes `FireIPTV-<version>.apk` and the stick finds it.
- **Signing key:** `~/.android/fireiptv-release.jks` (password in `~/.gradle/gradle.properties`, also four `FIREIPTV_*` GitHub secrets). Keep a backup: without it, updates can't install over the app.
- **Testing:** emulators for daily work (`FireOS8_API30_TV` matches the stick; test release builds on `FireOS16_API36_TV`). A dead channel is the provider's fault unless the app handles it badly.

```bash
./gradlew assembleDebug                         # build
./gradlew :core:parser:test                     # fast JVM tests
./gradlew connectedDebugAndroidTest             # instrumented tests on a running emulator
emulator -avd FireOS8_API30_TV &                # TV emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb connect <stick-ip>:5555                     # the real stick
```

## Open items

- [ ] **Stick over adb:** the stick is on the second router (192.168.10.x), this PC on the ISP router (192.168.0.x). Put the stick on the ISP Wi-Fi for measuring, or forward port 5555 on the second router. Needed for 0.9.
- [ ] **Backup of the signing key** somewhere off this PC.
