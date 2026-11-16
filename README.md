# AuraWave

A personal music player for web and Android. It plays the exact song you picked, finds songs from a line of their lyrics, and builds a home page from what you actually listen to.

> **A native Android rewrite is in progress in [`native/`](native/README.md).** It plays audio-only
> streams in a native player instead of a hidden YouTube embed, and installs beside this app.
> Everything below describes the current web-based app.

## How it finds the right song

Every song is played from **YouTube Music's official audio tracks**, matched by title, artist and duration:

- Search results come straight from YouTube Music, so what you tap is what plays.
- Songs from elsewhere (Spotify imports, older library entries) go through a strict matcher (`shared/match.js`). It rejects same-named songs by other artists and versions you didn't ask for: instrumental, karaoke, slowed, lofi, sped up, 8D, jhankar, covers, live, clips.
- If there's no confident match, the song is **skipped with a message**. It never plays something else in its place.
- If a version is wrong anyway, open the player and tap **Not the right song?** to pick the right one. That choice is remembered.

## Lyrics search

Type any line you remember into Search, for example "hum tere bin ab reh nahi sakte". YouTube Music matches lyrics, and the top result shows the line it matched.

## Recommendations

`src/services/recommender.js` works entirely from your own listening:

1. Every song you touched gets a weight. Likes and full listens add to it, skips subtract, and older activity slowly fades.
2. Your strongest songs (at most two per artist) become seeds. Each seed's YouTube Music radio provides candidates.
3. Candidates are ranked by how many of your seeds point to them and how much you like their artists. Hidden songs, hidden artists and artists you keep skipping are filtered out.
4. The result becomes shelves (a time-of-day mix, artist mixes, "Because you played…", Fresh finds, On repeat, Rediscover). Each song says why it's there.

When your queue ends, autoplay continues with similar songs, minus anything heard this session.

## Project layout

```
api/                 Vercel serverless functions (also run by the Vite dev server)
  _lib/ytmusic.js    YouTube Music client: search, resolve, radio, artist, lyrics
  music/*.js         /api/music/search | resolve | radio | artist | lyrics
  spotify/playlist.js  public Spotify playlist import
  youtube/search.js  legacy endpoint kept for older installed app builds
shared/match.js      song matching rules, used by server and client
src/
  services/          audio engine, catalog (API + caching), library, recommender, lyrics
  state/             player, router, overlays, toasts
  views/             Home, Search, Library, Collection, Artist, Settings, Onboarding
  components/        player UI, lyrics, queue, sheets, shared UI
  styles/app.css     design system (colours follow the playing song's cover)
```

## Develop

```bash
npm install
npm run dev        # http://localhost:5173 (the /api routes run locally too)
npm run build
npm run lint
```

## Deploy and build Android

The Android app calls the deployed backend (`https://aurawave-music.vercel.app` by default; set `VITE_API_BASE` to change it). **Redeploy the `api/` folder to Vercel before installing a new APK**, or the app will be calling the old endpoints.

```bash
npm run build
npx cap sync android
cd android && ./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Set `YTM_REGION` in Vercel (default `IN`) to change which YouTube Music region the catalog comes from.

## Your data

Liked songs, playlists, history and settings are stored on the device. Use **Settings → Back up library** before reinstalling or switching phones. Backups from the previous app version can also be restored there.
