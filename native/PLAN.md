# AuraWave: plan for the complete app

Agreed 16 November 2026. All nine phases are built (version 3.0.0-beta1); none of it has run on a phone yet.

## What the app is

A music player for Android that plays YouTube Music's audio directly, with podcasts and an
optional video view. Music comes first on every screen. It is a sideloaded APK for you and a
small circle, not a Play Store app.

Three rules keep it from becoming cluttered:

1. **Four tabs, no more:** Home, Search, Library, and (once podcasts exist) Podcasts.
2. **Extra controls live where they are used.** Sound, timer, speed and video sit behind one
   button in the player, not on the main screens.
3. **Nothing fake.** No "Hi-Fi" labels on normal audio, no decorative visualiser, no social feed,
   no comments, no Shorts.

Status words used below: **Done** (ran on the emulator), **Built** (compiles, not yet run on a
phone), **Planned**.

## Features

### Playback
| Feature | Status |
|---|---|
| Audio-only streams in a native player, disk cache for replays | Done |
| Notification, lock screen, headset and Bluetooth buttons, background play | Done |
| Autoplay similar songs when the queue runs low | Done |
| Skip songs that cannot play; stop after three failures in a row | Built |
| Pause for calls and other apps, resume after short interruptions | Built |
| Gapless playback between songs | Built |
| Remember the queue and position when the app is closed | Built |
| "Not the right song?" picker that remembers your choice | Built |
| Audio quality choice, separately for Wi-Fi and mobile data | Built |

### Sound (one "Sound" sheet in the player)
| Feature | Notes | Status |
|---|---|---|
| Equaliser with presets and bass boost | Uses Android's built-in audio effects | Built |
| Volume levelling | Evens out loud and quiet songs using the loudness value YouTube provides | Built |
| Skip silence | Removes silent intros and gaps | Built |
| Speed and pitch | Mostly for podcasts; available for songs too | Built |
| Fade in and out on play, pause and skip | Cheap to build | Built |
| True crossfade between songs | A short-lived second player plays the outgoing song's last seconds; off by default (Sound sheet, 1 to 12 s) | Built |

### Timers
| Feature | Status |
|---|---|
| Sleep timer: after a set time, at the end of this song, or after N songs | Built |
| Volume fades out over the last 30 seconds instead of cutting off | Built |
| For podcasts: stop at the end of the episode | Built |

### Queue
| Feature | Status |
|---|---|
| See the queue, jump to a song, play next, add to queue | Done |
| Drag to reorder, swipe to remove, clear upcoming | Built |
| Save the queue as a playlist | Built |

### Library
| Feature | Status |
|---|---|
| Liked songs, listening history | Built |
| Playlists: create, rename, reorder, delete, add from any song's menu | Built |
| Saved albums and followed artists | Built |
| Listening stats: top songs and artists by week, month and year; time listened | Built |
| Back up and restore the library as a file | Built |
| Import your backup from the old app (liked songs, playlists) | Built |
| Import a public Spotify playlist; import a YouTube playlist by link | Built |
| Music files already on the phone | Not planned (decision 3) |

### Finding music
| Feature | Status |
|---|---|
| Search songs, artists and videos; search by a line of lyrics; suggestions while typing | Done |
| Search filters for albums, playlists and podcasts; recent searches | Built |
| Artist page with top songs | Done |
| Artist albums and singles, similar artists; album and playlist pages | Built |
| Home: recently played, "Because you played…" | Done |
| Home: your recommender from the old app (time-of-day mix, artist mixes, On repeat, Rediscover) | Built |
| Explore: charts, moods and genres, new releases | Built |

### Lyrics
| Feature | Status |
|---|---|
| Synced lyrics that follow the song, tap a line to jump; plain lyrics as fallback | Built |
| Nudge the timing per song when lyrics run early or late | Built |

### Downloads
| Feature | Status |
|---|---|
| Download a song, album or playlist for offline play | Built |
| Auto-download liked songs; Wi-Fi only option; storage limit | Built |

### Podcasts
| Feature | Status |
|---|---|
| Search and browse shows, episode lists, follow a show | Built |
| Resume each episode where you stopped; mark as played | Built |
| Skip back 10 s and forward 30 s; speed control | Built |
| "New episodes" from shows you follow | Built |

### Video
| Feature | Status |
|---|---|
| Song/Video switch in the player for anything that has a video; audio mode downloads no video | Built |

### Account (optional)
| Feature | Status |
|---|---|
| Sign in to YouTube Music inside the app; the login stays on the phone | Built |
| Sync liked songs, playlists and library both ways | Built |
| Send plays to your YouTube history so recommendations there improve | Built |

The app works fully without signing in. Signing in breaks YouTube's terms, so it stays opt-in.

### Phone integration
| Feature | Status |
|---|---|
| New icon: app, themed and notification versions | Done |
| Android Auto | Built |
| Home-screen widget: a 4×2 card with a large cover, or a one-row strip when resized, in the song's colour | Built |
| Play/pause from the notification after a reboot, without opening the app | Built |

### Look and feel
| Feature | Status |
|---|---|
| Accent colour taken from the playing song's cover | Done |
| The old app's typefaces (Bricolage Grotesque, Figtree) | Built |
| Swipe the mini player to skip; swipe the player down to close | Built |
| Motion: the cover grows from the mini player into the player, pages slide in and out, play and pause morph, the heart pops, the sung lyric line eases in | Built |
| Waveform seek bar, as in Namida | Not built: possible later for downloaded songs |

## Order of work

| Phase | What | Why here | Status |
|---|---|---|---|
| 1 | Core playback, search, player | | Done |
| 2 | Library, lyrics; then playlists, backup and old-app import, settings, sleep timer | Makes the app usable every day | Built |
| 3 | Recommender on Home, Explore, album and playlist pages, search filters | Reaches what the old app offers | Built |
| 4 | Sound sheet, queue editing, quality by network, gapless | The features you asked for by name | Built |
| 5 | Downloads | | Built |
| 6 | Podcasts | | Built |
| 7 | Sign-in and sync | After the library is stable, so sync has something solid to merge into | Built |
| 8 | Song/Video switch | | Built |
| 9 | Android Auto, widget, gestures, typefaces, signed release build | | Built |

Each phase ends with an APK you install and try on your phone. The emulator cannot be used on
this machine, so your phone is the test for anything on screen.

## Switching over and removing the old app

The old React app and the Vercel backend stay until the end of phase 3. Until then the old app
is the only one with playlists, Spotify import, backup and your recommender, and its code is
what those features are ported from.

At the switch:

1. Export a backup from the old app and import it into the new one.
2. Use only the new app for a week.
3. Delete the React app, the Capacitor `android/` project and the Vercel functions from the
   repository, and move `native/` to the top level.

## Decisions (answered 16 November 2026)

- **Order:** kept as above.
- **Crossfade:** "use whichever gives the better experience": true crossfade is built as a setting (off by default), with gapless playback and short fades on play, pause and skip as the default.
- **Music files on the phone:** streaming only.
- **Missing daily features:** none named yet; add them here when they come up.

## Original questions

1. **Order.** Is the order above right, or should podcasts or sign-in come earlier?
2. **Crossfade.** True crossfade costs about as much as the rest of the Sound sheet together.
   Fades on play, pause and skip come cheaply. Build true crossfade, or leave it out?
3. **Music files on the phone.** Should the app also play local files, or stay streaming-only?
4. **Features you use every day.** You said there are advanced features you use regularly.
   Which ones are missing from this plan?
