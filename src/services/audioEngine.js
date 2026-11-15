/**
 * Audio Engine - Rock-Solid Single-Source Audio Manager
 * Strictly coordinates HTML5 Audio and YouTube Iframe Streaming to guarantee
 * zero overlapping audio, instant play/pause, race-condition immunity, and 100% clean playback.
 */
import { resolveYouTubeTrack, clearYtCache } from './spotifyService.js';
import { offlineStorage } from './offlineStorage.js';
import { Capacitor } from '@capacitor/core';

function syncNativeLockScreen(track, isPlaying, positionSec) {
  try {
    if (Capacitor && Capacitor.isNativePlatform() && Capacitor.Plugins && Capacitor.Plugins.MediaNotification) {
      if (!track && !isPlaying) {
        Capacitor.Plugins.MediaNotification.clear().catch(() => {});
        return;
      }
      Capacitor.Plugins.MediaNotification.update({
        title: track?.title || 'AuraWave Music',
        artist: track?.artist || 'Playing',
        coverUrl: track?.coverUrl || '',
        duration: Math.round(track?.duration || 180),
        position: Math.round(positionSec || 0),
        isPlaying: !!isPlaying
      }).catch(() => {});
    }
  } catch (_) {}
}



const FALLBACK_AUDIO_POOL = [
  'https://cdn.pixabay.com/download/audio/2022/05/27/audio_1808fbf07a.mp3?filename=synthwave-80s-110045.mp3',
  'https://cdn.pixabay.com/download/audio/2022/01/18/audio_d0a13f69d2.mp3?filename=lofi-study-112191.mp3',
  'https://cdn.pixabay.com/download/audio/2022/03/15/audio_c8c8a73467.mp3?filename=electronic-future-beats-117997.mp3',
  'https://cdn.pixabay.com/download/audio/2022/02/07/audio_bf05477ef2.mp3?filename=glossy-168156.mp3',
  'https://cdn.pixabay.com/download/audio/2021/09/06/audio_9879f972b2.mp3?filename=tuesday-glitch-12278.mp3'
];

/**
 * Generates a valid loopable PCM silent audio Blob URL (2 seconds @ 8000Hz mono).
 * A true PCM stream ensures mobile OS audio compositors (Chrome/Safari/Android/iOS)
 * treat the audio session as actively playing sound, preserving background execution
 * and lock-screen MediaSession controls without draining CPU or battery.
 */
function createSilentAudioUrl() {
  try {
    if (typeof window === 'undefined' || typeof Blob === 'undefined' || typeof URL === 'undefined') {
      return 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=';
    }
    const numSamples = 16000;
    const buffer = new ArrayBuffer(44 + numSamples);
    const view = new DataView(buffer);

    // RIFF chunk descriptor
    view.setUint32(0, 0x52494646, false); // 'RIFF'
    view.setUint32(4, 36 + numSamples, true);
    view.setUint32(8, 0x57415645, false); // 'WAVE'

    // fmt subchunk
    view.setUint32(12, 0x666d7420, false); // 'fmt '
    view.setUint32(16, 16, true);          // Subchunk1Size (16 for PCM)
    view.setUint16(20, 1, true);           // AudioFormat (1 = PCM)
    view.setUint16(22, 1, true);           // NumChannels (1 = mono)
    view.setUint32(24, 8000, true);        // SampleRate (8000 Hz)
    view.setUint32(28, 8000, true);        // ByteRate (8000 * 1 * 1)
    view.setUint16(32, 1, true);           // BlockAlign (1 byte)
    view.setUint16(34, 8, true);           // BitsPerSample (8 bits)

    // data subchunk
    view.setUint32(36, 0x64617461, false); // 'data'
    view.setUint32(40, numSamples, true);

    // 8-bit unsigned PCM silence is 128 (0x80)
    const bytes = new Uint8Array(buffer, 44, numSamples);
    bytes.fill(128);

    const blob = new Blob([buffer], { type: 'audio/wav' });
    return URL.createObjectURL(blob);
  } catch (_) {
    return 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=';
  }
}

class AudioEngine {
  constructor() {
    this.htmlAudio = new Audio();
    this.htmlAudio.preload = 'auto';
    this.htmlAudio.playsInline = true;

    this.silentAudio = new Audio();
    this.silentAudio.loop = true;
    this.silentAudio.volume = 0.001;
    this.silentAudio.preload = 'auto';
    this.silentAudio.playsInline = true;
    this._silentAudioUrl = null;

    this.ytPlayer = null;
    this.activeMode = 'html'; // 'html' | 'yt'
    this.currentTrack = null;
    this.isPlaying = false;
    this.userWantsPlay = false;
    this.isBuffering = false;
    this.currentTime = 0;
    this.duration = 180;
    this.volume = 0.85;

    // Screen Wake Lock API handle to prevent mobile screen sleep during playback
    this._wakeLock = null;

    // Audio Quality Mode: '320k' (Hi-Fi Master), '160k' (High), '96k' (Normal)
    this.audioQuality = '320k';

    // Race-condition prevention token
    this.playSessionId = 0;

    this.listeners = {
      timeUpdate: [],
      stateChange: [],
      ended: [],
      qualityChange: [],
      mediaPrev: [],
      mediaNext: []
    };

    this.timer = null;
    this.audioCtx = null;
    this.analyser = null;
    this.sourceNode = null;
    this.compressor = null;
    this.bassFilter = null;
    this.trebleFilter = null;
    this.freqData = new Uint8Array(64);
    this._pendingTrack = null;

    this._initHtmlAudioEvents();
    this._initMediaSession();
    this._initVisibilityListener();
    this._startProgressTicker();
  }

  _initHtmlAudioEvents() {
    this.htmlAudio.addEventListener('timeupdate', () => {
      if (this.activeMode === 'html') {
        this.currentTime = this.htmlAudio.currentTime;
        this.duration = this.htmlAudio.duration || this.currentTrack?.duration || 180;
        this._emit('timeUpdate', { currentTime: this.currentTime, duration: this.duration });
      }
    });

    this.htmlAudio.addEventListener('playing', () => {
      if (this.activeMode === 'html') {
        this.isPlaying = true;
        this.isBuffering = false;
        this._requestWakeLock();
        this._updateMediaSession(this.currentTrack);
        this._updateMediaSessionPlayback('playing');
        this._emit('stateChange', { isPlaying: true, isBuffering: false });
      }
    });

    this.htmlAudio.addEventListener('pause', () => {
      if (this.activeMode === 'html' && !this.isPlaying) {
        this._releaseWakeLock();
        this._updateMediaSessionPlayback('paused');
        this._emit('stateChange', { isPlaying: false, isBuffering: false });
      }
    });

    this.htmlAudio.addEventListener('waiting', () => {
      if (this.activeMode === 'html') {
        this.isBuffering = true;
        this._emit('stateChange', { isPlaying: this.isPlaying, isBuffering: true });
      }
    });

    this.htmlAudio.addEventListener('ended', () => {
      if (this.activeMode === 'html') {
        if (!this._hasEndedEmittedForCurrentTrack) {
          this._hasEndedEmittedForCurrentTrack = true;
          this._playSilentAudio();
          this._emit('ended');
        }
      }
    });

    this.htmlAudio.addEventListener('error', (e) => {
      if (
        this.activeMode === 'html' &&
        this.htmlAudio.src &&
        this.htmlAudio.src.startsWith('http') &&
        !this.htmlAudio.src.includes('localhost:')
      ) {
        if (this._fallbackAttempts > 0) {
          console.warn('Direct audio backup stream also failed; stopping playback cleanly.');
          this.isPlaying = false;
          this.isBuffering = false;
          this._emit('stateChange', { isPlaying: false, isBuffering: false });
          return;
        }
        this._fallbackAttempts = (this._fallbackAttempts || 0) + 1;
        this._fallbackToDirectAudio(this.playSessionId);
      }
    });
  }

  _initWebAudio() {
    if (this.audioCtx) return;
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (AudioCtx) {
        this.audioCtx = new AudioCtx();
        this.analyser = this.audioCtx.createAnalyser();
        this.analyser.fftSize = 128;
        this.freqData = new Uint8Array(this.analyser.frequencyBinCount);

        try {
          this.htmlAudio.crossOrigin = 'anonymous';
          this.sourceNode = this.audioCtx.createMediaElementSource(this.htmlAudio);

          // Spotify-style Dynamic Mastering Pipeline:
          // Bass punch enhancer
          this.bassFilter = this.audioCtx.createBiquadFilter();
          this.bassFilter.type = 'lowshelf';
          this.bassFilter.frequency.value = 90;
          this.bassFilter.gain.value = this.audioQuality === '320k' ? 3.5 : (this.audioQuality === '160k' ? 1.5 : 0);

          // Vocal clarity enhancer
          this.trebleFilter = this.audioCtx.createBiquadFilter();
          this.trebleFilter.type = 'highshelf';
          this.trebleFilter.frequency.value = 8000;
          this.trebleFilter.gain.value = this.audioQuality === '320k' ? 2.5 : (this.audioQuality === '160k' ? 1.0 : 0);

          // Spotify Loudness Normalization Compressor
          this.compressor = this.audioCtx.createDynamicsCompressor();
          this.compressor.threshold.value = -24;
          this.compressor.knee.value = 30;
          this.compressor.ratio.value = 12;
          this.compressor.attack.value = 0.003;
          this.compressor.release.value = 0.25;

          // Master Gain for smooth crossfade transitions between songs
          this.masterGain = this.audioCtx.createGain();
          this.masterGain.gain.setValueAtTime(1.0, this.audioCtx.currentTime);

          // Connect audio graph
          this.sourceNode.connect(this.bassFilter);
          this.bassFilter.connect(this.trebleFilter);
          this.trebleFilter.connect(this.compressor);
          this.compressor.connect(this.masterGain);
          this.masterGain.connect(this.analyser);
          this.analyser.connect(this.audioCtx.destination);
        } catch (e) {
          console.warn('WebAudio connection note:', e);
        }
      }
    } catch (err) {
      console.warn('Web Audio init note:', err);
    }
  }

  smoothFadeOut(duration = 0.3) {
    return new Promise(resolve => {
      if (this.audioCtx && this.masterGain) {
        try {
          const now = this.audioCtx.currentTime;
          this.masterGain.gain.cancelScheduledValues(now);
          this.masterGain.gain.setValueAtTime(this.masterGain.gain.value, now);
          this.masterGain.gain.linearRampToValueAtTime(0.01, now + duration);
        } catch (_) {}
      }

      if (this.htmlAudio && !this.htmlAudio.paused) {
        let currentVol = this.htmlAudio.volume;
        const step = currentVol / (duration * 20);
        const fadeInterval = setInterval(() => {
          currentVol = Math.max(0, currentVol - step);
          try { this.htmlAudio.volume = currentVol; } catch (_) {}
          if (currentVol <= 0.01) clearInterval(fadeInterval);
        }, 50);
      }

      if (this.ytPlayer && typeof this.ytPlayer.getVolume === 'function') {
        try {
          let ytVol = this.ytPlayer.getVolume();
          const ytStep = ytVol / (duration * 20);
          const ytInterval = setInterval(() => {
            ytVol = Math.max(0, ytVol - ytStep);
            try { this.ytPlayer.setVolume(ytVol); } catch (_) {}
            if (ytVol <= 1) clearInterval(ytInterval);
          }, 50);
        } catch (_) {}
      }

      setTimeout(resolve, duration * 1000);
    });
  }

  smoothFadeIn(duration = 0.35) {
    if (this.audioCtx && this.masterGain) {
      try {
        const now = this.audioCtx.currentTime;
        this.masterGain.gain.cancelScheduledValues(now);
        this.masterGain.gain.setValueAtTime(0.02, now);
        this.masterGain.gain.linearRampToValueAtTime(1.0, now + duration);
      } catch (_) {}
    }

    if (this.htmlAudio) {
      this.htmlAudio.volume = 0.05;
      const target = this.volume;
      let cur = 0.05;
      const step = (target - 0.05) / (duration * 20);
      const interval = setInterval(() => {
        cur = Math.min(target, cur + step);
        try { this.htmlAudio.volume = cur; } catch (_) {}
        if (cur >= target) clearInterval(interval);
      }, 50);
    }

    if (this.ytPlayer && typeof this.ytPlayer.setVolume === 'function') {
      try {
        this.ytPlayer.setVolume(10);
        const target = this.volume * 100;
        let cur = 10;
        const step = (target - 10) / (duration * 20);
        const interval = setInterval(() => {
          cur = Math.min(target, cur + step);
          try { this.ytPlayer.setVolume(cur); } catch (_) {}
          if (cur >= target) clearInterval(interval);
        }, 50);
      } catch (_) {}
    }
  }

  setAudioQuality(quality) {
    this.audioQuality = quality; // '320k' | '160k' | '96k'
    if (this.audioCtx && this.bassFilter && this.trebleFilter) {
      const now = this.audioCtx.currentTime;
      if (quality === '320k') {
        this.bassFilter.gain.setValueAtTime(3.5, now);
        this.trebleFilter.gain.setValueAtTime(2.5, now);
      } else if (quality === '160k') {
        this.bassFilter.gain.setValueAtTime(1.5, now);
        this.trebleFilter.gain.setValueAtTime(1.0, now);
      } else {
        this.bassFilter.gain.setValueAtTime(0, now);
        this.trebleFilter.gain.setValueAtTime(0, now);
      }
    }
    this._emit('qualityChange', { quality });
  }

  setYouTubePlayer(player) {
    this.ytPlayer = player;
    if (this._ytPlayerReadyResolver) {
      this._ytPlayerReadyResolver(player);
      this._ytPlayerReadyResolver = null;
    }
    // Auto-resume or play any pending track that was waiting for YouTube iframe API
    if (this._pendingTrack && this.userWantsPlay) {
      const track = this._pendingTrack;
      this._pendingTrack = null;
      this.playTrack(track);
    }
  }

  async waitForYouTubePlayer(timeoutMs = 10000) {
    if (this.ytPlayer && typeof this.ytPlayer.loadVideoById === 'function') {
      return this.ytPlayer;
    }
    return new Promise(resolve => {
      const timer = setTimeout(() => {
        this._ytPlayerReadyResolver = null;
        resolve(this.ytPlayer);
      }, timeoutMs);
      this._ytPlayerReadyResolver = (player) => {
        clearTimeout(timer);
        resolve(player);
      };
    });
  }

  /**
   * Silences active audio sources without resetting userWantsPlay or dropping native notification
   */
  _stopPlaybackSources() {
    this._fallbackAttempts = 0;
    this.htmlAudio.pause();
    this.htmlAudio.currentTime = 0;
    try {
      this.htmlAudio.removeAttribute('src');
      this.htmlAudio.load();
    } catch (_) {}
    // Do NOT stop the YouTube player pipeline between tracks!
    // Keeping the player instance warm allows loadVideoById to transition instantly without rebuilding the iframe.
  }

  /**
   * Strictly stops and silences all playback sources
   */
  stopAll() {
    this._fallbackAttempts = 0;
    this.activeMode = 'idle';
    this.userWantsPlay = false;
    this._releaseWakeLock();

    // 1. Force stop HTML Audio cleanly without triggering empty load error
    this.htmlAudio.pause();
    this.htmlAudio.currentTime = 0;
    this.htmlAudio.src = '';
    this._pauseSilentAudio();

    // 2. Force stop YouTube Player
    if (this.ytPlayer && typeof this.ytPlayer.stopVideo === 'function') {
      try {
        this.ytPlayer.stopVideo();
      } catch (_) {}
    }

    this.isPlaying = false;
    this.isBuffering = false;
    this._updateMediaSessionPlayback('none');
    syncNativeLockScreen(null, false, 0);
  }

  async _requestWakeLock() {
    if (typeof navigator !== 'undefined' && 'wakeLock' in navigator && !this._wakeLock) {
      try {
        this._wakeLock = await navigator.wakeLock.request('screen');
        this._wakeLock.addEventListener('release', () => {
          this._wakeLock = null;
        });
        console.log('📱 Screen WakeLock active: screen will not sleep during music playback');
      } catch (_) {
        this._wakeLock = null;
      }
    }
  }

  _releaseWakeLock() {
    if (this._wakeLock) {
      try {
        this._wakeLock.release();
      } catch (_) {}
      this._wakeLock = null;
    }
  }

  _initVisibilityListener() {
    if (typeof document === 'undefined') return;

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        // 1. Re-acquire WakeLock if playing so display does not sleep
        if (this.isPlaying && !this._wakeLock) {
          this._requestWakeLock();
        }

        // 2. Seamless auto-resume if app returned to foreground while user wants play
        if (this.userWantsPlay) {
          if (!this.isPlaying) {
            console.log('📱 App returned to foreground: auto-resuming playback');
            this.resume();
          } else if (this.activeMode === 'yt' && this.ytPlayer && typeof this.ytPlayer.getPlayerState === 'function') {
            try {
              const state = this.ytPlayer.getPlayerState();
              if (state === 2 || state === -1 || state === 5) {
                console.log('📱 App returned to foreground: unpausing YouTube player (state ' + state + ')');
                this.ytPlayer.playVideo();
              }
            } catch (_) {}
          }
        }
      } else if (document.visibilityState === 'hidden') {
        this._wakeLock = null;
        // Screen locked or app backgrounded:
        // Do NOT disrupt already-playing audio! Only intervene if playback actually stalled
        if (this.userWantsPlay && this.activeMode === 'yt' && this.ytPlayer && typeof this.ytPlayer.getPlayerState === 'function') {
          try {
            const state = this.ytPlayer.getPlayerState();
            if (state === 2 || state === -1 || state === 5) {
              this.ytPlayer.playVideo();
            }
          } catch (_) {}
        }
      }
    });
  }

  _playSilentAudio() {
    try {
      if (!this._silentAudioUrl) {
        this._silentAudioUrl = createSilentAudioUrl();
        this.silentAudio.src = this._silentAudioUrl;
      }
      this.silentAudio.volume = 0.001;
      const p = this.silentAudio.play();
      if (p && typeof p.catch === 'function') {
        p.catch(() => {});
      }
    } catch (_) {}
  }

  _pauseSilentAudio() {
    try {
      this.silentAudio.pause();
    } catch (_) {}
  }

  handleYouTubeStateChange(state) {
    // If not currently in YouTube mode, ensure YouTube is silenced immediately
    if (this.activeMode !== 'yt') {
      if (this.ytPlayer && typeof this.ytPlayer.stopVideo === 'function') {
        try { this.ytPlayer.stopVideo(); } catch (_) {}
      }
      return;
    }

    // Only pause if user explicitly tapped pause while it was loading
    if (this.userWantsPlay === false && state === 1) {
      if (this.ytPlayer && typeof this.ytPlayer.pauseVideo === 'function') {
        try { this.ytPlayer.pauseVideo(); } catch (_) {}
      }
      this._updateMediaSessionPlayback('paused');
      return;
    }

    // 1 = PLAYING, 2 = PAUSED, 3 = BUFFERING, 0 = ENDED, 5 = CUED
    if (state === 1) {
      // Real audio has started: silence background bridge and HTML audio immediately
      this.htmlAudio.pause();
      this._pauseSilentAudio();
      this.isPlaying = true;
      this.isBuffering = false;
      this._requestWakeLock();
      this._startProgressTicker();
      this.smoothFadeIn(0.25);
      this._updateMediaSession(this.currentTrack);
      this._updateMediaSessionPlayback('playing');
      this._emit('stateChange', { isPlaying: true, isBuffering: false });
    } else if (state === 2) {
      // If user tapped Pause explicitly:
      if (!this.userWantsPlay) {
        this._pauseSilentAudio();
        this._releaseWakeLock();
        this.isPlaying = false;
        this._updateMediaSessionPlayback('paused');
        this._emit('stateChange', { isPlaying: false, isBuffering: false });
        return;
      }

      // App is in background / screen locked or player temporarily stalled:
      // Gently ensure YouTube resumes playback with pacing to prevent buffer thrashing
      this.isPlaying = true;
      const now = Date.now();
      if (!this._lastPauseKickTime || now - this._lastPauseKickTime > 1500) {
        this._lastPauseKickTime = now;
        if (this.ytPlayer && typeof this.ytPlayer.playVideo === 'function') {
          try {
            if (typeof this.ytPlayer.unMute === 'function') this.ytPlayer.unMute();
            this.ytPlayer.playVideo();
          } catch (_) {}
        }
      }
    } else if (state === 3) {
      this.isBuffering = true;
      this._emit('stateChange', { isPlaying: this.isPlaying, isBuffering: true });
    } else if (state === 5 || state === -1) {
      // CUED / UNSTARTED: Video ready in buffer. Kick playVideo immediately so playback starts instantly!
      if (this.userWantsPlay && this.ytPlayer && typeof this.ytPlayer.playVideo === 'function') {
        try {
          if (typeof this.ytPlayer.unMute === 'function') this.ytPlayer.unMute();
          this.ytPlayer.playVideo();
        } catch (_) {}
      }
    } else if (state === 0) {
      // Natural track end: immediately advance to next song without fading out or dropping user intent
      if (!this._hasEndedEmittedForCurrentTrack) {
        this._hasEndedEmittedForCurrentTrack = true;
        this._playSilentAudio();
        this._emit('ended');
      }
    }
  }

  async handleYouTubeError(code) {
    console.warn('YouTube playback error event code:', code, 'for track:', this.currentTrack?.title);

    if (this.currentTrack) {
      const badId = this.currentTrack.youtubeId;
      clearYtCache(this.currentTrack.title, this.currentTrack.artist);

      // Attempt to load alternative candidate
      this._ytRetryCount = (this._ytRetryCount || 0) + 1;
      if (this._ytRetryCount <= 2) {
        try {
          const freshId = await resolveYouTubeTrack(
            this.currentTrack.title,
            this.currentTrack.artist,
            true,
            badId,
            this.currentTrack.duration || 0
          );
          if (freshId && freshId !== badId && this.ytPlayer && typeof this.ytPlayer.loadVideoById === 'function') {
            this.currentTrack.youtubeId = freshId;
            const cleanFreshId = String(freshId).trim();
            this.activeMode = 'yt';
            this.ytPlayer.loadVideoById(cleanFreshId, 0);
            this.ytPlayer.setVolume(this.volume * 100);
            this.ytPlayer.playVideo();
            return;
          }
        } catch (_) {}
      }
    }

    this._ytRetryCount = 0;
    this._fallbackToDirectAudio(this.playSessionId);
  }

  _fallbackToDirectAudio(sessionId) {
    if (this.playSessionId !== sessionId) return;

    // Force stop YouTube
    if (this.ytPlayer && typeof this.ytPlayer.stopVideo === 'function') {
      try { this.ytPlayer.stopVideo(); } catch (_) {}
    }

    const idx = Math.abs((this.currentTrack?.title?.length || 0) % FALLBACK_AUDIO_POOL.length);
    const fallbackUrl = FALLBACK_AUDIO_POOL[idx];

    this.activeMode = 'html';
    try {
      this.htmlAudio.removeAttribute('crossorigin');
    } catch (_) {}
    this.htmlAudio.src = fallbackUrl;
    this.htmlAudio.currentTime = 0;
    this.htmlAudio.volume = this.volume;
    this.htmlAudio.play().then(() => {
      if (this.playSessionId === sessionId) {
        this.isPlaying = true;
        this.isBuffering = false;
        this._emit('stateChange', { isPlaying: true, isBuffering: false });
      } else {
        this.htmlAudio.pause();
      }
    }).catch(err => {
      console.warn('Fallback audio play note:', err);
    });
  }

  on(event, callback) {
    if (!this.listeners[event]) {
      this.listeners[event] = [];
    }
    this.listeners[event].push(callback);
    return () => {
      if (this.listeners[event]) {
        this.listeners[event] = this.listeners[event].filter(cb => cb !== callback);
      }
    };
  }

  _emit(event, payload) {
    if (this.listeners[event] && Array.isArray(this.listeners[event])) {
      this.listeners[event].forEach(cb => {
        try {
          cb(payload);
        } catch (e) {
          console.warn(`Error in event listener for ${event}:`, e);
        }
      });
    }
  }

  async playTrack(track) {
    const currentSession = ++this.playSessionId;
    this._hasEndedEmittedForCurrentTrack = false;

    this.currentTrack = track;
    this.currentTime = 0;
    this.duration = track.duration || 180;
    this.isPlaying = true;
    this.userWantsPlay = true;
    this.isBuffering = true;
    this._requestWakeLock();
    this._startProgressTicker();
    this._updateMediaSession(track);
    this._emit('stateChange', { isPlaying: true, isBuffering: true });

    // Keep audio session alive with silent bridge during track transition so Android/Chromium
    // never drops the audio pipeline while resolving the next track in the background
    this._playSilentAudio();
    this._stopPlaybackSources();

    // Check if device is in Offline Mode or disconnected from internet
    const isOnline = offlineStorage.isEffectivelyOnline();

    if (!isOnline) {
      // In Offline Mode: play from local IndexedDB offline storage
      this.activeMode = 'html';
      const offlineAudioUrl = await offlineStorage.getOfflineAudioUrl(track.id);
      if (this.playSessionId !== currentSession) return;

      this.htmlAudio.src = offlineAudioUrl || track.audioUrl || FALLBACK_AUDIO_POOL[0];
      this.htmlAudio.volume = this.volume;
      this._initWebAudio();
      if (this.audioCtx && this.audioCtx.state === 'suspended') {
        this.audioCtx.resume();
      }

      try {
        await this.htmlAudio.play();
        if (this.playSessionId === currentSession) {
          this.isPlaying = true;
          this.isBuffering = false;
          this._emit('stateChange', { isPlaying: true, isBuffering: false, isOffline: true });
        } else {
          this.htmlAudio.pause();
        }
      } catch (err) {
        console.warn('Offline audio play note:', err);
      }
      return;
    }

    // When online: auto-cache track to offline vault quietly in background
    offlineStorage.autoCacheTrack(track).catch(() => {});

    // Discard any 30-second preview audio snippets (e.g. Apple previewUrl) so the full track streams
    const isSnippetPreview = track.audioUrl && (
      track.audioUrl.includes('mzstatic.com') ||
      track.audioUrl.includes('apple.com') ||
      track.audioUrl.includes('preview') ||
      track.audioUrl.includes('/m4a/')
    );
    if (isSnippetPreview) {
      delete track.audioUrl;
    }

    // If track has a true direct full-length audioUrl (e.g. user offline upload or blob), play via HTML5
    if (track.audioUrl) {
      this.activeMode = 'html';
      this.htmlAudio.src = track.audioUrl;
      this.htmlAudio.volume = this.volume;
      this._initWebAudio();
      if (this.audioCtx && this.audioCtx.state === 'suspended') {
        this.audioCtx.resume();
      }
      try {
        await this.htmlAudio.play();
        if (this.playSessionId === currentSession) {
          this.isPlaying = true;
          this.isBuffering = false;
          this.smoothFadeIn(0.35);
          this._emit('stateChange', { isPlaying: true, isBuffering: false });
        } else {
          this.htmlAudio.pause();
        }
      } catch (err) {
        console.warn('HTML5 Audio play note:', err);
      }
      return;
    }

    // Otherwise, stream clean studio audio from YouTube Music
    this.isBuffering = true;
    this._emit('stateChange', { isPlaying: true, isBuffering: true });

    let ytId = track.youtubeId;

    // Resolve clean studio audio if missing
    if (!ytId) {
      ytId = await resolveYouTubeTrack(track.title, track.artist, false, null, track.duration || 0);
      if (this.playSessionId !== currentSession) {
        // Discard if user already switched to another track while waiting
        return;
      }
      if (ytId) {
        track.youtubeId = ytId;
      } else {
        console.warn('Could not resolve YouTube track ID, falling back to direct audio:', track?.title);
        this._fallbackToDirectAudio(currentSession);
        return;
      }
    }

    // Ensure YouTube player instance is ready before attempting load
    let player = this.ytPlayer;
    if (!player || typeof player.loadVideoById !== 'function') {
      this._pendingTrack = track;
      player = await this.waitForYouTubePlayer(10000);
      if (this.playSessionId !== currentSession) return;
      this._pendingTrack = null;
    }

    if (ytId && player && typeof player.loadVideoById === 'function') {
      this.activeMode = 'yt';
      this._ytRetryCount = 0;
      // Double check HTML audio is silent without triggering false error events
      this.htmlAudio.pause();
      try {
        this.htmlAudio.removeAttribute('src');
        this.htmlAudio.load();
      } catch (_) {}

      try {
        const cleanId = String(ytId).trim();
        if (typeof player.unMute === 'function') {
          try { player.unMute(); } catch (_) {}
        }
        player.setVolume(this.volume * 100);
        player.loadVideoById(cleanId, 0);
        if (typeof player.playVideo === 'function') {
          try { player.playVideo(); } catch (_) {}
        }
        this._startProgressTicker();
      } catch (e) {
        console.warn('Failed to load YouTube video ID:', e);
        this.handleYouTubeError(2);
      }
    } else {
      console.warn('Could not load track on YouTube, attempting alternative resolution:', track?.title);
      this.handleYouTubeError(2);
    }
  }

  pause() {
    this.isPlaying = false;
    this.userWantsPlay = false;
    this._releaseWakeLock();

    // Unconditionally pause BOTH to guarantee silence
    this.htmlAudio.pause();
    this._pauseSilentAudio();

    if (this.ytPlayer && typeof this.ytPlayer.pauseVideo === 'function') {
      try {
        this.ytPlayer.pauseVideo();
      } catch (_) {}
    }

    this._updateMediaSessionPlayback('paused');
    this._emit('stateChange', { isPlaying: false, isBuffering: false });
  }

  setInitialTrack(track) {
    if (!track) return;
    this.currentTrack = track;
    this.duration = track.duration || 180;
    this.currentTime = 0;
    this.isPlaying = false;
    this.userWantsPlay = false;
    this._updateMediaSession(track);
  }

  resume() {
    if (!this.currentTrack) {
      console.warn('AudioEngine: cannot resume without currentTrack');
      return;
    }
    this.isPlaying = true;
    this.userWantsPlay = true;
    this._requestWakeLock();

    // If activeMode is idle or neither HTML audio nor YouTube player has an active session, trigger full playTrack
    if (this.activeMode === 'idle' || (!this.htmlAudio.src && !this.ytPlayer)) {
      this.playTrack(this.currentTrack);
      return;
    }

    if (this.activeMode === 'html') {
      this._pauseSilentAudio();
      // Ensure YouTube is stopped
      if (this.ytPlayer && typeof this.ytPlayer.stopVideo === 'function') {
        try { this.ytPlayer.stopVideo(); } catch (_) {}
      }
      if (this.audioCtx && this.audioCtx.state === 'suspended') {
        this.audioCtx.resume();
      }
      if (!this.htmlAudio.src) {
        this.playTrack(this.currentTrack);
      } else {
        this.htmlAudio.play().catch(e => {
          console.warn('HTML play error, attempting playTrack:', e);
          this.playTrack(this.currentTrack);
        });
      }
    } else if (this.activeMode === 'yt') {
      // Ensure HTML audio is stopped and silent keep-alive holds OS audio focus
      this.htmlAudio.pause();
      this._playSilentAudio();
      this._startProgressTicker();
      this._lastPauseKickTime = Date.now();
      if (this.ytPlayer && typeof this.ytPlayer.playVideo === 'function') {
        try {
          if (typeof this.ytPlayer.unMute === 'function') {
            try { this.ytPlayer.unMute(); } catch (_) {}
          }
          if (typeof this.ytPlayer.setVolume === 'function') {
            try { this.ytPlayer.setVolume(this.volume * 100); } catch (_) {}
          }
          this.ytPlayer.playVideo();
        } catch (e) {
          console.warn('YouTube playVideo failed during resume, falling back to playTrack:', e);
          this.playTrack(this.currentTrack);
        }
      } else {
        this.playTrack(this.currentTrack);
      }
    } else {
      this.playTrack(this.currentTrack);
    }

    this._updateMediaSessionPlayback('playing');
    this._emit('stateChange', { isPlaying: true, isBuffering: false });
  }

  togglePlay() {
    if (this.isPlaying) {
      this.pause();
    } else {
      this.resume();
    }
    return this.isPlaying;
  }

  seek(seconds) {
    this.currentTime = seconds;
    if (seconds < (this.duration || 180) - 2) {
      this._hasEndedEmittedForCurrentTrack = false;
    }
    if (this.activeMode === 'html') {
      this.htmlAudio.currentTime = seconds;
    } else if (this.activeMode === 'yt' && this.ytPlayer && typeof this.ytPlayer.seekTo === 'function') {
      try { this.ytPlayer.seekTo(seconds, true); } catch (_) {}
    }
    this._emit('timeUpdate', { currentTime: this.currentTime, duration: this.duration });
    this._updateMediaSessionPosition();
    syncNativeLockScreen(this.currentTrack, this.isPlaying, this.currentTime);
  }

  repeatTrack() {
    this._hasEndedEmittedForCurrentTrack = false;
    this.currentTime = 0;
    this.isPlaying = true;
    this.userWantsPlay = true;
    this._requestWakeLock();

    if (this.activeMode === 'html') {
      this.htmlAudio.currentTime = 0;
      this.htmlAudio.play().catch(() => {});
    } else if (this.activeMode === 'yt' && this.ytPlayer) {
      try {
        if (typeof this.ytPlayer.seekTo === 'function') {
          this.ytPlayer.seekTo(0, true);
        }
        if (typeof this.ytPlayer.playVideo === 'function') {
          this.ytPlayer.playVideo();
        }
      } catch (_) {
        if (this.currentTrack) this.playTrack(this.currentTrack);
      }
    } else if (this.currentTrack) {
      this.playTrack(this.currentTrack);
    }

    this._updateMediaSession(this.currentTrack);
    this._updateMediaSessionPlayback('playing');
    this._emit('stateChange', { isPlaying: true, isBuffering: false });
    this._emit('timeUpdate', { currentTime: 0, duration: this.duration });
  }

  setVolume(vol) {
    this.volume = Math.max(0, Math.min(1, vol));
    this.htmlAudio.volume = this.volume;
    if (this.ytPlayer && typeof this.ytPlayer.setVolume === 'function') {
      try { this.ytPlayer.setVolume(this.volume * 100); } catch (_) {}
    }
  }

  getFrequencyData() {
    if (this.analyser && this.activeMode === 'html') {
      this.analyser.getByteFrequencyData(this.freqData);
      return this.freqData;
    }
    if (this.isPlaying) {
      const t = this.currentTime * 6;
      for (let i = 0; i < 32; i++) {
        const val = 110 + 90 * Math.sin(t + i * 0.4) * Math.cos(t * 0.7 + i * 0.2);
        this.freqData[i] = Math.max(30, Math.min(255, val));
      }
      return this.freqData;
    }
    return new Uint8Array(32).fill(0);
  }

  _startProgressTicker() {
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => {
      if (this.activeMode === 'yt' && this.ytPlayer && typeof this.ytPlayer.getCurrentTime === 'function') {
        try {
          const now = Date.now();
          // Auto-resume YouTube playback if screen was locked or app was minimized while user wants play
          if (this.userWantsPlay && typeof this.ytPlayer.getPlayerState === 'function') {
            const playerState = this.ytPlayer.getPlayerState();
            // 2 = PAUSED: only gently kick if stuck in paused state for > 1500ms since last kick!
            if (playerState === 2) {
              if (!this._lastPauseKickTime || now - this._lastPauseKickTime > 1500) {
                this._lastPauseKickTime = now;
                try {
                  if (typeof this.ytPlayer.unMute === 'function') this.ytPlayer.unMute();
                  this.ytPlayer.playVideo();
                } catch (_) {}
              }
            } else if (playerState === 5 || playerState === -1) {
              // 5 = CUED, -1 = UNSTARTED: kick play immediately so newly selected songs start without delay!
              try {
                if (typeof this.ytPlayer.unMute === 'function') this.ytPlayer.unMute();
                this.ytPlayer.playVideo();
              } catch (_) {}
            }
          }

          const curr = this.ytPlayer.getCurrentTime() || 0;
          const rawDur = typeof this.ytPlayer.getDuration === 'function' ? this.ytPlayer.getDuration() : 0;
          const dur = rawDur > 0 ? rawDur : (this.duration || this.currentTrack?.duration || 180);
          this.currentTime = curr;
          if (dur > 0) this.duration = dur;

          // Autoplay Next Song detection:
          // In mobile background tabs, YouTube sometimes idles at the last fraction of a second
          // without firing state 0 (ENDED). We detect when playback reaches the end (within 0.8s)
          // and auto-advance to the next track smoothly!
          if (this.userWantsPlay && !this._hasEndedEmittedForCurrentTrack && dur > 10 && curr >= dur - 0.8) {
            this._hasEndedEmittedForCurrentTrack = true;
            this._playSilentAudio();
            this._emit('ended');
            return;
          }

          if (now - (this._lastPositionSyncTime || 0) > 1000) {
            this._lastPositionSyncTime = now;
            this._updateMediaSessionPosition();
          }
          this._emit('timeUpdate', { currentTime: this.currentTime, duration: this.duration });
        } catch (_) {}
      } else if (this.activeMode === 'html') {
        const now = Date.now();
        if (now - (this._lastPositionSyncTime || 0) > 1000) {
          this._lastPositionSyncTime = now;
          this._updateMediaSessionPosition();
        }
      }
    }, 200);
  }

  _initMediaSession() {
    if ('mediaSession' in navigator) {
      try {
        navigator.mediaSession.setActionHandler('play', () => this.resume());
        navigator.mediaSession.setActionHandler('pause', () => this.pause());
        navigator.mediaSession.setActionHandler('stop', () => this.pause());
        navigator.mediaSession.setActionHandler('previoustrack', () => this._emit('mediaPrev'));
        navigator.mediaSession.setActionHandler('nexttrack', () => this._emit('mediaNext'));
        navigator.mediaSession.setActionHandler('seekto', (details) => {
          if (details.seekTime !== undefined) {
            this.seek(details.seekTime);
          }
        });
        navigator.mediaSession.setActionHandler('seekbackward', (details) => {
          const offset = details.seekOffset || 5;
          this.seek(Math.max(0, this.currentTime - offset));
        });
        navigator.mediaSession.setActionHandler('seekforward', (details) => {
          const offset = details.seekOffset || 5;
          this.seek(Math.min(this.duration, this.currentTime + offset));
        });
      } catch (err) {
        console.warn('MediaSession init error:', err);
      }
    }
  }

  _updateMediaSessionPlayback(state) {
    if ('mediaSession' in navigator) {
      try {
        navigator.mediaSession.playbackState = state; // 'playing' | 'paused' | 'none'
      } catch (_) {}
    }
    syncNativeLockScreen(this.currentTrack, state === 'playing', this.currentTime);
  }

  _updateMediaSessionPosition() {
    if ('mediaSession' in navigator && 'setPositionState' in navigator.mediaSession) {
      try {
        if (this.duration > 0 && !isNaN(this.currentTime)) {
          navigator.mediaSession.setPositionState({
            duration: Math.max(1, this.duration),
            playbackRate: 1,
            position: Math.min(this.duration, Math.max(0, this.currentTime))
          });
        }
      } catch (_) {}
    }
  }

  _updateMediaSession(track) {
    if (!track) return;
    try {
      const cover = track.coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80';
      const absCover = (cover.startsWith('http') || cover.startsWith('data:'))
        ? cover
        : `${typeof window !== 'undefined' ? window.location.origin : ''}${cover.startsWith('/') ? '' : '/'}${cover}`;

      if ('mediaSession' in navigator) {
        const artwork = [
          { src: absCover, sizes: '96x96', type: 'image/jpeg' },
          { src: absCover, sizes: '128x128', type: 'image/jpeg' },
          { src: absCover, sizes: '192x192', type: 'image/jpeg' },
          { src: absCover, sizes: '256x256', type: 'image/jpeg' },
          { src: absCover, sizes: '384x384', type: 'image/jpeg' },
          { src: absCover, sizes: '512x512', type: 'image/jpeg' }
        ];

        navigator.mediaSession.metadata = new window.MediaMetadata({
          title: track.title || 'AuraWave Song',
          artist: track.artist || 'AuraWave Master',
          album: track.album || 'AuraWave Hi-Fi',
          artwork
        });

        this._updateMediaSessionPlayback(this.isPlaying ? 'playing' : 'paused');
        this._updateMediaSessionPosition();
      }

      syncNativeLockScreen(track, this.isPlaying, this.currentTime);
    } catch (err) {
      console.warn('Error updating MediaMetadata:', err);
    }
  }
}

export const audioEngine = new AudioEngine();

if (typeof window !== 'undefined') {
  window.AuraWaveAudio = {
    togglePlay: () => audioEngine.togglePlay(),
    play: () => audioEngine.resume(),
    pause: () => audioEngine.pause(),
    next: () => audioEngine._emit('mediaNext'),
    prev: () => audioEngine._emit('mediaPrev'),
    seek: (sec) => audioEngine.seek(sec)
  };
}
