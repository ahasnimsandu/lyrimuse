# Lyriu — Android lyrics player (port of Lyrimuse)

An Android port of [Lyrimuse](https://github.com/Yudaotor/lyrimuse) (macOS menu-bar lyrics app).
The original is Swift/SwiftUI + a Go engine — none of it runs on Android, so this is a native
reimplementation of its core: **automatic word-by-word synced lyrics for whatever is playing,
plus a floating overlay** like the desktop one.

Install: `Lyriu.apk` (debug-signed) → `adb install -r Lyriu.apk`, or copy it to the phone
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
- **Floating overlay** over other apps: current line + next line, word-level fill,
  optional translation, draggable with its position remembered across restarts,
  tap = open the app, long-press = hide. Same idea as Lyrimuse's desktop overlay.
- **Full karaoke window**: scrolling lyrics, translations, progress, prev/play/next, tap any lyric
  line to seek there.
- **Offline**: every matched song is cached on disk; replay works with no network.
- **Per-song sync**: a global ±2 s offset plus a separate shift remembered for the exact
  recording (album and length included), because every lyric file drifts differently.
- **Updates without a store**: once every few hours the app compares itself with the
  project's GitHub releases and offers the newer APK.
- **Notification controls**: the shade shows the current lyric line with previous/play/next.

## Look and feel

Apple-Music-style playback screen: the cover art is blurred into a full-screen backdrop with an
accent glow sampled from the artwork, the cover sits on top, and lyrics scroll with the active
line growing while it fills word by word in the artwork's accent colour. Controls are Material
vector icons (no emoji) on frosted-glass cards, with the accent colour carried through the play
button and progress bar.

Settings: keep screen on, tap-to-seek, lyric text size, sync offset, translations, NetEase/LRCLIB
source toggles, accent-from-artwork, floating bar on/off with opacity and text size, cache size and
clear, notification/overlay permission shortcuts.

## Finding lyrics manually & live detection

Tap **Find lyrics** on the player to open the finder. It shows a live-detection panel (which app is
playing, what is attached, line vs word timing), an **Auto re-detect** switch, a *Run automatic match
again* button, and a search box prefilled with the current song. Searching queries NetEase and
LRCLIB concurrently and lists every version found with a `WORD`/`LINE` badge and line count — tap
one to attach it to the current song (it is cached, so it survives song changes and restarts).

When a song has no lyrics at all, the app re-runs detection a few times (15 s apart, max 4) while
the song plays, instead of staying empty.

**Re-match** drops only the current song's cached file and fetches it again; the rest of the
offline library is untouched.

## Lyric engines

| Engine | Coverage | Timing |
| --- | --- | --- |
| **NetEase Cloud Music** | largest CJK + international catalogue, translations (tlyric) | **word-by-word** where available |
| **LRCLIB** | open database, strong for Western pop/rock | synced lines / plain |
| **QQ Music** | large CJK catalogue, translations | synced lines |

All enabled engines are queried in parallel, both for automatic matching and manual search; word-level
results win, then synced lines. Each engine can be switched off in Settings.

## Language & scrolling

Lyrics are **English-only** by default: no Chinese, Japanese, Korean, Cyrillic, Thai or Arabic line
reaches the screen. Korean originals are shown as their romanised reading (Revised Romanization), a
foreign original is replaced by its English translation when one exists, a Chinese translation under
an English line is dropped, and a line with no English version is left out of the page. A song with no
English lyrics at all reports *No lyrics found*.

Settings → **Lyric language** changes that default: *English only*, *Original + translation* (every
script as published) or *Romanised (Korean)* (original scripts, with Hangul written as Revised
Romanization). The language is part of the offline cache key, so switching it re-fetches the current
song instead of showing a file filtered for the other mode.

Sync is adjustable twice: the global **Sync offset** shifts every song, and **This song only** stores a
separate shift for the exact recording (keyed by title, artist, album and length), since each lyric
file drifts differently.

Drag the lyrics to browse them: auto-follow pauses while you scroll and for a couple of seconds
afterwards, then eases back to the live line instead of snapping. Tapping a line seeks there, and the
progress bar itself is a scrubber. The easing is time-based, so it feels the same on 60 Hz and 120 Hz
screens, and the fill runs at 60 fps from the playback clock so it stays smooth between player
updates. Pausing the song freezes the lyrics exactly where they are.

## Typography

Lyrics are set in an elegant **serif** by default (Settings → *Lyrics font* also offers Modern, Light
and Condensed; the heavy/mono faces were removed). No fake-bold shadows, so nothing looks 3D.
Text size, offset, translations and tap-to-seek sit next to it.

## Colour

Everything is dark-first, and the palette is sampled from the current cover: the dominant vibrant
hue (not the grey average) drives the play button, progress bar, sung-lyric accent, source label,
card tint, backdrop glow and the status/navigation bars. Accent sampling can be turned off in
Settings, which falls back to warm champagne. Artwork is only replaced when the track actually
changes, so the cover never re-fades and the backdrop never re-blurs in the middle of a song.

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
`Lyriu.apk`.

`./tools/test.sh` runs the plain-JVM tests for the parsers, language modes and cache keys (no
emulator, no Gradle); CI runs them before packaging. The app version comes from an exact
`android-v*` tag when there is one, otherwise from `version.txt`, and `versionCode` is derived
from it (1.7.0 → 10700) so sideloaded updates stay upgradeable. `local.properties` / Gradle files are kept so the project also opens in
Android Studio, but the shipped APK was built with the script above.

## Signing

`tools/lyrimuse-signing.jks` is committed on purpose: CI and local builds sign with the same
key (alias `lyrimuse`, password `lyrimuse`) so sideloaded updates install over each other.
It is a public sideload key — anyone can sign an update for this app id, so do not use it for
a Play Store release; generate a private keystore and pass `KEYSTORE`, `KEYSTORE_PASS`,
`KEYSTORE_ALIAS` to `build.sh` instead.

APKs signed before this key was introduced need one uninstall before upgrading.

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
| `Updates` | GitHub release check for the next APK |
| `tools/test.sh` | JVM tests for parsers, language modes and cache keys |

## Not ported

These are macOS-only in the original and have no Android equivalent here: Apple Music/Touch Bar
surfaces, menu-bar panel, browser-player automation, Last.fm/ListenBrainz scrobbling, Discord
presence, the now-playing web page, AMLL/QQ/Kugou/Musixmatch/Apple lyric sources, 17-language
system translation (only source-provided translations are shown).

## Licence

Lyrimuse is GPL-3.0; this port follows the same licence. It is an independent reimplementation,
not a copy of the macOS source.
