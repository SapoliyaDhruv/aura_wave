# AuraWave (native Android)

The Kotlin rewrite of AuraWave. It plays YouTube Music's audio-only streams directly in a
native player, instead of hiding a YouTube video embed inside a WebView like the web-based
app in the repository root.

It installs as `com.aurawave.player`, next to the old app (`com.aurawave.music`), so both
can be on the phone until this one has caught up.

## Build and install

```bash
cd native
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and Android SDK platform 36. `local.properties` must point `sdk.dir` at the SDK.

## Try it on an emulator

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

Plain `./gradlew :innertube:test` runs only the offline tests.

## Layout

```
innertube/   Plain Kotlin, no Android. Runs and is tested on the desktop.
  InnerTube.kt        YouTube Music catalogue: search, suggestions, radio, artists, lyrics, resolve
  StreamResolver.kt   video id -> audio-only stream URL (via NewPipeExtractor)
  Match.kt            "is this the recording the listener asked for?" Ported from shared/match.js;
                      MatchParityTest checks it against that file's answers.
app/         The Android app (Jetpack Compose + Media3).
  playback/PlaybackService.kt   owns the player: notification, audio focus, autoplay, skipping broken songs
  playback/StreamDataSource.kt  looks up stream URLs on demand, plays from the disk cache, recovers expired URLs
  playback/PlayerConnection.kt  what the screens use to watch and control the player
  ui/                           Home, Search, Artist, Now Playing, theme
```

## Licence note

NewPipeExtractor is GPL-3.0. An app that includes it must be distributed under the GPL too.
