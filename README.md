# 🌊 AuraWave Music

<p align="center">
  <strong>Next-Generation Web & Mobile Music Streaming, Smart Recommendations, and Lyrics Player</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19.3-61dafb?style=flat-square&logo=react" alt="React 19" />
  <img src="https://img.shields.io/badge/Vite-8.3-646cff?style=flat-square&logo=vite" alt="Vite" />
  <img src="https://img.shields.io/badge/Capacitor-8.5-119eff?style=flat-square&logo=capacitor" alt="Capacitor" />
  <img src="https://img.shields.io/badge/Android-APK%20v1.2-3ddc84?style=flat-square&logo=android" alt="Android" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License" />
</p>

---

## 🌟 Overview

**AuraWave Music** is a high-performance, Spotify-grade music streaming application built for both modern Web and native Android. It combines word-by-word synced lyrics, dynamic acoustic vibe transition engines, authentic artist similarity networks, background playback services, and an offline download cache.

---

## ✨ Key Features

### 🧠 1. Intelligent Recommendation & Endless Radio Engine
- **Acoustic Waveform Analysis (Echo Nest Style)**: Automatically tags and evaluates track features: `energy` (low/medium/high), `mood` (romantic, heartbreak, party, hype, chill, groove), `estimatedBpm`, and `valence` to ensure smooth musical transitions (e.g., preventing abrupt jumps between club dance tracks and heartbreak ballads).
- **Skip-Dwell Feedback Loop**: Real-time behavioral signal tracking. Skips under 5 seconds temporarily suppress the artist and penalize the vibe for that session; deep listening (>45s or >65%) boosts artist affinity.
- **Dynamic Online Radio Ingestion**: Automatically streams 20–50 fresh tracks on the fly for any artist from global music catalogs, eliminating the "cold catalog" limit.
- **Session Anti-Fatigue Memory**: In-memory session tracking ensures songs heard during the current listening session never repeat in the Up Next queue.
- **Artist Similarity Network**: Deep relational cluster mapping (e.g., Sonu Nigam ↔ KK ↔ Mohit Chauhan ↔ Atif Aslam ↔ Pritam; Karan Aujla ↔ Shubh ↔ Diljit ↔ AP Dhillon).
- **Infinite Playlist Autoplay**: When a playlist finishes, the engine calculates dominant genre and mood vectors to seamlessly transition into matching endless radio.

### 🎵 2. Studio Master Playback & Search Precision
- **100+ Master Tracks Catalog**: Complete with rich metadata, high-resolution album artwork, and verified clean studio audio streams (Bollywood Romance, Heartbreak Soul, Punjabi, Desi Hip-Hop, and Global Pop).
- **Phonetic & Alias Search Resolver**: Handles colloquial spellings and query variations (e.g., `sonio` ranks Sonu Nigam & Shreya Ghoshal's iconic *Soniyo* as #1; `shelume` resolves to Tai Verdes' *sheluvme*).
- **Clean Stream Resolver**: Aggressively filters out karaoke, instrumental, backing tracks, and covers so playback always resolves to original vocal studio masters.

### 📱 3. Native Android Mobile Experience
- **Capacitor 8.5 Bridge**: Native Android package (`com.aurawave.music`) with AndroidX Media integration.
- **Background Audio Service**: Uninterrupted playback when screen is locked or switching between apps via `MusicPlaybackService`.
- **Lock Screen MediaSession**: Full metadata updates with dynamic high-resolution artwork (96px to 512px) and notification controls.
- **Offline Mode & Download Cache**: Save and play downloaded music anywhere with zero internet connectivity.

### 🎨 4. Modern Spotify-Style UI / UX
- **Your Library View**: Searchable, filter chips (`All`, `Playlists`, `By You`, `Downloaded`), sort controls (`Recents`, `Alphabetical`, `Song Count`), list/grid layout toggle, and pinned Liked Songs.
- **Playlist Detail View**: 42x42 cover art, hover play states, animated 3-bar green equalizer, like hearts, and total duration stats.
- **Word-by-Word Synced Lyrics**: Real-time karaōke-style animated lyrics with dynamic gradient highlights.
- **Adaptive Layout**: Responsive desktop sidebar + mini player with smooth mobile drawer navigation.

---

## 🛠️ Tech Stack

- **Frontend**: React 19, Vite 8, Lucide React
- **Mobile Runtime**: Capacitor Android 8.5
- **Audio Engine**: Custom HTML5 / Web Audio / YouTube IFrame Player API hybrid engine
- **Styling**: Modern CSS3 with hardware-accelerated animations & glassmorphism
- **Storage**: Browser LocalStorage & IndexedDB (via Capacitor Storage & Native Bridge)

---

## 🚀 Getting Started

### Prerequisites
- [Node.js](https://nodejs.org/) (v18 or higher)
- [npm](https://www.npmjs.com/) or [pnpm](https://pnpm.io/)
- [Android Studio](https://developer.android.com/studio) (for Android native builds)

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/SapoliyaDhruv/aura_wave.git
   cd aura_wave
   ```

2. **Install dependencies**:
   ```bash
   npm install
   ```

3. **Start development server**:
   ```bash
   npm run dev
   ```
   Open [http://localhost:5173](http://localhost:5173) in your browser.

4. **Build production web assets**:
   ```bash
   npm run build
   ```

---

## 📱 Building the Android APK

1. **Sync web build with Android native project**:
   ```bash
   npx cap sync android
   ```

2. **Compile the APK via Gradle**:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```

   The generated APK will be available at:
   `android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.
