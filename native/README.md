# AuraWave (native Android)

The Kotlin rewrite of AuraWave. It plays YouTube Music's audio-only streams directly in a
native player, instead of hiding a YouTube video embed inside a WebView like the web-based
app in the repository root.

It installs as `com.aurawave.player`, next to the old app (`com.aurawave.music`), so both
can be on the phone until this one has caught up.

## Build and install

```bash
cd native
./gradlew :app:assembleRelease     # signed, minified: app/build/outputs/apk/release/app-release.apk (~4 MB)
./gradlew :app:assembleDebug       # unminified, debug-signed: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17 and Android SDK platform 36. `local.properties` must point `sdk.dir` at the SDK.

### The release key

Release builds are signed with the key in `native/signing/` (`aurawave-release.jks` and
`keystore.properties`, which holds its passwords). That folder is not in git. **Back it up**:
Android installs an update only over an app signed with the same key, so without it the next
version can only be installed after uninstalling, which deletes the library on the phone.
Without the folder, release builds fall back to the debug key.

A debug and a release build cannot update each other (different keys). Pick one per phone.

## Try it on an emulator

The emulator needs several GB of memory of its own. On a machine with 8 GB or less, do not run it
together with a Gradle build; test on a phone instead.

With an emulator (AVD) named `aurawave_full` created from an Android 15 system image:

```bash
~/Android/Sdk/emulator/emulator -avd aurawave_full -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aurawave.player/.MainActivity
tools/emu-ui.py texts            # what is on screen, with positions
tools/emu-ui.py tap "Search"     # tap an element by its text
adb exec-out screencap -p > shot.png
adb shell dumpsys media_session | grep -A12 com.aurawave.player   # is it really playing?
```

Use a `default` or `google_apis` system image. The slim `aosp_atd` images return black screenshots.

## When songs stop playing

YouTube changes how streams are served every few months. Check, in this order:

```bash
./gradlew :innertube:test -Plive      # talks to the real YouTube: search, radio, a playable stream
```

- **`audioStreamIsPlayable` fails**: stream extraction broke. Bump `newpipe` in
  `gradle/libs.versions.toml` to the latest [NewPipeExtractor release](https://github.com/TeamNewPipe/NewPipeExtractor/releases)
  and run the test again.
- **The search / radio tests fail**: YouTube Music changed its response shape. The parsing is in
  `innertube/src/main/kotlin/com/aurawave/innertube/InnerTube.kt`.

Plain `./gradlew :innertube:test` runs only the offline tests. `./gradlew :app:testDebugUnitTest`
checks the backup file format and the rules of the liked-songs sync.

## Database

The library is a Room database (`data/db/AuraDatabase.kt`). Every released schema is kept in
`app/schemas/`, and each version change is an `AutoMigration`, so updating the app never loses
the library. Add a table or column: bump `version`, add `AutoMigration(from, to)`, build once to
export the new schema, and commit it.

## Layout

```
innertube/   Plain Kotlin, no Android. Runs and is tested on the desktop.
  InnerTube.kt        YouTube Music catalogue: search, suggestions, radio, artists, lyrics, resolve
  StreamResolver.kt   video id -> audio-only stream URL (via NewPipeExtractor)
  Match.kt            "is this the recording the listener asked for?" Ported from shared/match.js;
                      MatchParityTest checks it against that file's answers.
  Spotify.kt          public Spotify playlists, read on the phone
  Loudness.kt         each song's measured loudness, for volume levelling
  Account.kt          the optional YouTube Music login (cookies + SAPISIDHASH)
app/         The Android app (Jetpack Compose + Media3).
  playback/PlaybackService.kt   owns the player: notification, audio focus, autoplay, skipping broken songs, sleep timer,
                                queue memory, podcast resume, widget, Android Auto (MediaTree.kt)
  playback/StreamDataSource.kt  downloads first, then the disk cache, then the network; per-network quality
  playback/SoundEngine.kt       equaliser, bass, levelling; the player volume is the product of several factors
  playback/Fades.kt             fades on play/pause/skip, and crossfade (a short-lived second player)
  playback/Downloads.kt         offline songs (Media3 DownloadManager, own folder, foreground service)
  playback/PlayerConnection.kt  what the screens use to watch and control the player
  playback/PlayerWidget.kt      the home-screen widget: a 4×2 card, or a one-row strip when resized
  data/Library.kt               likes, history, playlists, saved albums, followed artists and shows, episode progress
  data/Recommender.kt           the Home feed (ported from the old app's recommender.js)
  data/Backup.kt                backup/restore; also reads the old app's backup files
  data/Account.kt               YouTube Music sync
  ui/                           the screens
  ui/Motion.kt                  the app's animations: the cover travelling into the player, page changes, play/pause, the heart
```

## Licence note

NewPipeExtractor is GPL-3.0. An app that includes it must be distributed under the GPL too.
The Bricolage Grotesque and Figtree fonts are under the SIL Open Font License; see `licenses/`.
