# Lyrimuse for Android

An Android port of [Lyrimuse](https://github.com/Yudaotor/lyrimuse) (macOS menu-bar lyrics app).
The original is Swift/SwiftUI + a Go engine — none of it runs on Android, so this is a native
reimplementation of its core: **automatic word-by-word synced lyrics for whatever is playing,
plus a floating overlay** like the desktop one.

Install: `Lyrimuse.apk` (debug-signed) → `adb install -r Lyrimuse.apk`, or copy it to the phone
and allow "install unknown apps".

## What it does

- **Reads the current song from any music app** over Android's `MediaSession` API
  (Spotify, YouTube Music, Apple Music, NetEase Cloud Music, QQ Music, Poweramp, VLC, …) via a
  `NotificationListenerService`. No player-specific code, the same "read whatever Now Playing is".
- **Word-by-word karaoke highlighting.** Parses three timed formats: NetEase **YRC** (real word
  timings), enhanced/A2 **LRC** (`[mm:ss.xx]<mm:ss.xx>word…`) and plain **LRC**. Line-level
  sources still get a smooth per-character fill; the source label says which you got.
- **Two lyric sources, scored and ranked:** NetEase Cloud Music (search + lyrics, gives YRC with
  word timings and community translations) and LRCLIB (global, synced + plain). Word-level beats
  line-level; duration and title/artist fuzzy matching pick the right recording.
- **Translations** under each line when the source has them (toggle).
- **Floating overlay** over other apps: current line + next line, word fill, draggable,
  tap = open the app, long-press = hide. Same idea as Lyrimuse's desktop overlay.
- **Full karaoke window**: scrolling lyrics, translations, progress, prev/play/next, tap any lyric
  line to seek there.
- **Offline**: every matched song is cached on disk; replay works with no network.
- **Per-song-free global controls**: sync offset (±2 s), text size, translation toggle.

## Setup on the phone (one time)

1. Open the app → **Grant access** → enable Lyrimuse under *Notification access*.
   This is what lets it see other apps' media sessions (Android's equivalent of the macOS
   Automation permission the original asks for).
2. **Allow overlay** if you want the floating bar (*Display over other apps*).
3. Play something in any player. Lyrics match automatically.

## Build

No Gradle needed (the build here was done on a very slow link, so the toolchain is minimal):

```bash
# once
cp /path/to/android.jar tools/android.jar        # platform 33+ android.jar
brew install --cask android-commandlinetools     # gives d8
# aapt2: from build-tools, or tools/aapt2
curl -L -o tools/apksig.jar \
  https://maven.aliyun.com/repository/google/com/android/tools/build/apksig/8.5.2/apksig-8.5.2.jar

./tools/build.sh
```

`build.sh` runs `aapt2 compile/link → javac → d8 → zip → apksig (v1+v2+v3)` and writes
`Lyrimuse.apk`. `local.properties` / Gradle files are kept so the project also opens in
Android Studio, but the shipped APK was built with the script above.

## Layout

| Path | Role |
| --- | --- |
| `PlayerService` | foreground service; polls active MediaSessions, tracks a precise playback clock, triggers lyric matching |
| `MediaListenerService` | notification listener (the permission gate for MediaSession access) |
| `Lyrics` | model + YRC / enhanced-LRC / LRC parsers, JSON cache format |
| `LyricsRepository` | NetEase + LRCLIB lookup, scoring, translation merge, cache |
| `KaraokeView` | scrolling karaoke renderer with word/character fill, tap-to-seek |
| `OverlayService` / `OverlayView` | floating always-on-top lyric bar |
| `MainActivity` / `SettingsActivity` | UI, permissions, preferences |

## Not ported

These are macOS-only in the original and have no Android equivalent here: Apple Music/Touch Bar
surfaces, menu-bar panel, browser-player automation, Last.fm/ListenBrainz scrobbling, Discord
presence, the now-playing web page, AMLL/QQ/Kugou/Musixmatch/Apple lyric sources, 17-language
system translation (only source-provided translations are shown).

## Licence

Lyrimuse is GPL-3.0; this port follows the same licence. It is an independent reimplementation,
not a copy of the macOS source.
