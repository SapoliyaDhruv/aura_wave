import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Play,
  Pause,
  Plus,
  Heart,
  Sparkles,
  Music,
  Flame,
  Mic2,
  Clock,
  Compass,
  FolderPlus,
  Smartphone,
  Monitor,
  RefreshCw,
  History,
  ListMusic,
  ArrowDownCircle,
  Wifi,
  WifiOff,
  User,
  Sliders,
  Maximize2
} from 'lucide-react';
import { CURATED_TRACKS, SAMPLE_PLAYLISTS, findCuratedTrack, normalizeTrackName, normalizeArtistName } from './data/curatedTracks';
import { audioEngine } from './services/audioEngine';
import { fetchLiveLyrics } from './services/lyricsService';
import { storageService, isDummyTrack } from './services/storageService';
import { offlineStorage } from './services/offlineStorage';
import { recommendationService } from './services/recommendationService';
import { prewarmTrackResolution, importSpotifyPlaylist } from './services/spotifyService';
import { syncService } from './services/syncService';
import { lyricsAutoSync } from './services/lyricsAutoSync';
import { profileService } from './services/profileService';
import { App as CapApp } from '@capacitor/app';

// Component Imports
import MiniPlayer from './components/MiniPlayer';
import FullScreenPlayer from './components/FullScreenPlayer';
import SpecialLyricsView from './components/SpecialLyricsView';
import BottomNav from './components/BottomNav';
import ImportSpotifyModal from './components/ImportSpotifyModal';
import CreatePlaylistModal from './components/CreatePlaylistModal';
import AddToPlaylistModal from './components/AddToPlaylistModal';
import QueueDrawer from './components/QueueDrawer';
import SearchModal from './components/SearchModal';
import PlaylistDetailView from './components/PlaylistDetailView';
import YouTubeBridge from './components/YouTubeBridge';
import PocketLockOverlay from './components/PocketLockOverlay';

// New Spotify Architecture Components
import SpotifySidebar from './components/SpotifySidebar';
import SpotifyTopBar from './components/SpotifyTopBar';
import SpotifyBottomBar from './components/SpotifyBottomBar';
import DownloadedView from './components/DownloadedView';
import SpotifyLibraryView from './components/SpotifyLibraryView';
import AudioQualityModal from './components/AudioQualityModal';
import CategoryNavBar from './components/CategoryNavBar';
import TasteProfileModal from './components/TasteProfileModal';
import NewUserOnboardingModal from './components/NewUserOnboardingModal';
import SleepTimerModal from './components/SleepTimerModal';
import KeyboardShortcutsModal from './components/KeyboardShortcutsModal';
import { HOME_NAV_CATEGORIES } from './data/categories';
import SonicHeroBanner from './components/SonicHeroBanner';
import RecentlyPlayedGrid from './components/RecentlyPlayedGrid';
import RecommendedShelf from './components/RecommendedShelf';
import ScrollableShelfRow from './components/ScrollableShelfRow';
import DynamicSplashScreen from './components/DynamicSplashScreen';
import {
  HERO_SONIC_JOURNEY,
  DEFAULT_RECENTLY_PLAYED,
  RECOMMENDED_FOR_YOU_ITEMS
} from './data/featuredContent';

export default function App() {
  // Persistent Playlists & Favorites from storageService
  const [playlists, setPlaylists] = useState(() => {
    return storageService.getPlaylists(SAMPLE_PLAYLISTS);
  });
  const [favorites, setFavorites] = useState(() => {
    return storageService.getFavorites();
  });
  const [recentlyPlayed, setRecentlyPlayed] = useState(() => {
    return storageService.getRecentlyPlayed();
  });

  // Track catalog - combines curated tracks and all imported playlist tracks
  const [tracks, setTracks] = useState(() => {
    const list = [...CURATED_TRACKS];
    const initialPlaylists = storageService.getPlaylists(SAMPLE_PLAYLISTS);
    initialPlaylists.forEach(pl => {
      if (Array.isArray(pl.tracks)) {
        pl.tracks.forEach(t => {
          if (typeof t === 'object' && t.id && !isDummyTrack(t) && !list.some(existing => existing.id === t.id)) {
            list.push(t);
          }
        });
      }
    });
    return list;
  });

  const [currentTrack, setCurrentTrack] = useState(() => {
    const recent = storageService.getRecentlyPlayed().filter(t => !isDummyTrack(t));
    return recent.length > 0 ? recent[0] : CURATED_TRACKS[0];
  });

  // Live Up Next Queue - populated with intelligent relevant recommendations
  const [queue, setQueue] = useState(() => {
    const initialTrack = storageService.getRecentlyPlayed().filter(t => !isDummyTrack(t))[0] || CURATED_TRACKS[0];
    return recommendationService.getRelevantUpcomingTracks(initialTrack, CURATED_TRACKS, 15);
  });

  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(184);
  const [volume, setVolume] = useState(0.85);

  // Offline Mode & Downloaded Tracks
  const [downloadedTracks, setDownloadedTracks] = useState([]);
  const [storageStats, setStorageStats] = useState({ count: 0, formattedSize: '0 MB' });
  const [isOfflineMode, setIsOfflineMode] = useState(!offlineStorage.isEffectivelyOnline());

  // Audio Quality Mode ('320k' | '160k' | '96k')
  const [audioQuality, setAudioQuality] = useState('320k');
  const [showQualityModal, setShowQualityModal] = useState(false);

  // Personalized Recommendations & Listening Frequency State
  const [frequentlyPlayed, setFrequentlyPlayed] = useState(() => {
    return storageService.getFrequentlyPlayed(10, CURATED_TRACKS);
  });
  const [likesRecs, setLikesRecs] = useState(() => {
    return recommendationService.getRecommendationsBasedOnLikes(
      storageService.getFavorites(),
      CURATED_TRACKS
    );
  });
  const [tasteProfile, setTasteProfile] = useState(() => {
    return storageService.getTasteProfile(CURATED_TRACKS);
  });
  const [activeProfile, setActiveProfile] = useState(() => profileService.getActiveProfile());
  const [profileMix, setProfileMix] = useState(() => {
    const prof = profileService.getActiveProfile();
    return recommendationService.getProfilePersonalizedMix(prof, CURATED_TRACKS, storageService.getFavorites());
  });
  const [showTasteModal, setShowTasteModal] = useState(false);
  const [showOnboarding, setShowOnboarding] = useState(false);

  const [personalizedRecs, setPersonalizedRecs] = useState({
    tag: 'MADE FOR YOU',
    reason: 'Personalized based on your musical profile',
    tracks: CURATED_TRACKS
  });
  const [isRefreshingRecs, setIsRefreshingRecs] = useState(false);

  // Navigation & Modals
  const [activeTab, setActiveTab] = useState('home'); // 'home' | 'search' | 'library' | 'offline' | 'favorites'
  const [selectedPlaylist, setSelectedPlaylist] = useState(null);
  const [showFullPlayer, setShowFullPlayer] = useState(false);
  const [showLyricsView, setShowLyricsView] = useState(false);
  const [showPocketMode, setShowPocketMode] = useState(false);
  const [showSpotifyModal, setShowSpotifyModal] = useState(false);
  const [showCreatePlaylistModal, setShowCreatePlaylistModal] = useState(false);
  const [showAddToPlaylistModal, setShowAddToPlaylistModal] = useState(false);
  const [trackForPlaylist, setTrackForPlaylist] = useState(null);
  const [showQueueModal, setShowQueueModal] = useState(false);
  const [showSleepModal, setShowSleepModal] = useState(false);
  const [sleepTimerMinutes, setSleepTimerMinutes] = useState(null); // number | 'endOfTrack' | null
  const [sleepTimerRemaining, setSleepTimerRemaining] = useState(0); // in seconds
  const [showShortcutsModal, setShowShortcutsModal] = useState(false);
  const [showSplash, setShowSplash] = useState(true);
  const lastVolumeRef = useRef(0.85);

  // Automatic responsive device & screen detection: true on phones & tablets (<= 820px), false on desktop
  const [isMobile, setIsMobile] = useState(() => {
    if (typeof window !== 'undefined') {
      return window.innerWidth <= 820;
    }
    return false;
  });

  useEffect(() => {
    if (typeof window === 'undefined') return;

    const mediaQuery = window.matchMedia('(max-width: 820px)');
    const updateDeviceMode = () => {
      setIsMobile(window.innerWidth <= 820 || mediaQuery.matches);
    };

    updateDeviceMode();

    if (mediaQuery.addEventListener) {
      mediaQuery.addEventListener('change', updateDeviceMode);
    } else {
      mediaQuery.addListener(updateDeviceMode);
    }

    window.addEventListener('resize', updateDeviceMode);
    window.addEventListener('orientationchange', updateDeviceMode);

    return () => {
      if (mediaQuery.removeEventListener) {
        mediaQuery.removeEventListener('change', updateDeviceMode);
      } else {
        mediaQuery.removeListener(updateDeviceMode);
      }
      window.removeEventListener('resize', updateDeviceMode);
      window.removeEventListener('orientationchange', updateDeviceMode);
    };
  }, []);

  const [toastMessage, setToastMessage] = useState('');

  // Player controls
  const [isShuffle, setIsShuffle] = useState(false);
  const [repeatMode, setRepeatMode] = useState('off');
  const [homeCategory, setHomeCategory] = useState('all');

  // True playback history stack & rapid click tracker for previous song navigation
  const playHistoryRef = useRef([]);
  const lastPrevClickRef = useRef(0);
  const activePlaylistContextRef = useRef(null);

  const showToast = useCallback((msg) => {
    setToastMessage(msg);
    setTimeout(() => {
      setToastMessage('');
    }, 2800);
  }, []);

  // Load offline tracks and storage stats on mount
  useEffect(() => {
    offlineStorage.getDownloadedTracks().then(list => {
      setDownloadedTracks(list);
    });
    offlineStorage.getStorageStats().then(stats => {
      setStorageStats(stats);
    });

    const unsubOffline = offlineStorage.on('change', async () => {
      const updatedList = await offlineStorage.getDownloadedTracks();
      setDownloadedTracks(updatedList);
      const updatedStats = await offlineStorage.getStorageStats();
      setStorageStats(updatedStats);
    });

    const unsubNetwork = offlineStorage.on('networkChange', ({ isOnline, forced }) => {
      setIsOfflineMode(!isOnline);
      if (!isOnline) {
        showToast(forced ? '⚡ Offline Mode Active • Playing from device storage' : '⚠️ Disconnected from internet • Offline Mode Active');
      } else {
        showToast('🌐 Online • High-Quality Streaming & Auto-Cache Active');
      }
    });

    return () => {
      unsubOffline();
      unsubNetwork();
    };
  }, [showToast]);

  // First-time user welcome onboarding: automatically trigger 20s setup if not yet configured
  useEffect(() => {
    if (!profileService.isOnboardingCompleted()) {
      const timer = setTimeout(() => {
        setShowOnboarding(true);
      }, 700);
      return () => clearTimeout(timer);
    }
  }, []);

  // Cross-Device Sync on mount: check URL for ?syncCode= or restore synced state
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      const params = new URLSearchParams(window.location.search);
      const urlSyncCode = params.get('syncCode');
      if (urlSyncCode) {
        syncService.pullFromCloud(urlSyncCode).then(res => {
          if (res.ok) {
            showToast(`✨ Linked & Synced with Device (${urlSyncCode})!`);
            setFavorites(storageService.getFavorites());
            setPlaylists(storageService.getPlaylists());
            setRecentlyPlayed(storageService.getRecentHistory(30));
            // Clean URL query param without refreshing
            const cleanUrl = window.location.pathname;
            window.history.replaceState({}, document.title, cleanUrl);
          }
        });
      } else {
        // Initial sync pull for current device sync code
        const code = syncService.getSyncCode();
        syncService.pullFromCloud(code).then(res => {
          if (res.ok) {
            setFavorites(storageService.getFavorites());
            setPlaylists(storageService.getPlaylists());
            setRecentlyPlayed(storageService.getRecentHistory(30));
          } else {
            // Push local data so cloud has current baseline
            syncService.pushToCloud(code).catch(() => {});
          }
        }).catch(() => {});
      }

      // Continuous 2-Way Sync (Spotify Style):
      // When user switches back to the tab/app, or every 45s, seamlessly pull changes from the other device!
      const syncCheck = () => {
        if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
          const code = syncService.getSyncCode();
          syncService.pullFromCloud(code).then(res => {
            if (res.ok) {
              setFavorites(storageService.getFavorites());
              setPlaylists(storageService.getPlaylists());
              setRecentlyPlayed(storageService.getRecentHistory(30));
            }
          }).catch(() => {});
        }
      };

      let syncDebounceTimer = null;
      const debouncedSyncCheck = () => {
        if (syncDebounceTimer) clearTimeout(syncDebounceTimer);
        syncDebounceTimer = setTimeout(syncCheck, 3000);
      };

      document.addEventListener('visibilitychange', debouncedSyncCheck);
      window.addEventListener('focus', debouncedSyncCheck);
      const syncInterval = setInterval(syncCheck, 45000);

      // Auto-repair corrupt imported Spotify playlists if they have the fake fallback tracks
      try {
        const currentPls = storageService.getPlaylists([]);
        currentPls.forEach(pl => {
          const matchId = (pl.id || '').match(/imported-([a-zA-Z0-9]{22})/);
          const isCorruptFallback = Array.isArray(pl.tracks) && pl.tracks.some(t => 
            (t.title === 'Espresso' && t.artist === 'Sabrina Carpenter') ||
            (t.title === 'Blinding Lights' && t.artist === 'The Weeknd')
          );
          if (matchId && isCorruptFallback && pl.name !== "Today's Top Hits") {
            importSpotifyPlaylist(matchId[1]).then(repaired => {
              if (repaired && repaired.tracks && repaired.tracks.length > 0) {
                const allPls = storageService.savePlaylist(repaired);
                setPlaylists(allPls);
                setTracks(prev => {
                  const existingIds = new Set(prev.map(t => t.id));
                  const additions = repaired.tracks.filter(t => !existingIds.has(t.id));
                  return [...prev, ...additions];
                });
                setSelectedPlaylist(curr => (curr && (curr.id === pl.id || curr.name === pl.name) ? repaired : curr));
              }
            }).catch(() => {});
          }
        });
      } catch (_) {}

      return () => {
        if (syncDebounceTimer) clearTimeout(syncDebounceTimer);
        document.removeEventListener('visibilitychange', debouncedSyncCheck);
        window.removeEventListener('focus', debouncedSyncCheck);
        clearInterval(syncInterval);
      };
    } catch (_) {}
  }, [showToast]);

  // Sleep Timer Countdown & Auto-Pause Logic
  const sleepTimerMinutesRef = useRef(sleepTimerMinutes);
  sleepTimerMinutesRef.current = sleepTimerMinutes;

  useEffect(() => {
    if (!sleepTimerMinutes || sleepTimerMinutes === 'endOfTrack' || sleepTimerRemaining <= 0) {
      return;
    }

    const timer = setInterval(() => {
      setSleepTimerRemaining(prev => {
        if (prev <= 1) {
          clearInterval(timer);
          setSleepTimerMinutes(null);
          audioEngine.smoothFadeOut(0.6).then(() => {
            audioEngine.pause();
            setIsPlaying(false);
          });
          showToast('🌙 Sleep timer finished. Music paused. Sweet dreams!');
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, [sleepTimerMinutes, sleepTimerRemaining, showToast]);

  const handleSetSleepTimer = useCallback((minutes) => {
    if (minutes === 'endOfTrack') {
      setSleepTimerMinutes('endOfTrack');
      setSleepTimerRemaining(0);
      showToast('🌙 Sleep timer set: Music will pause at end of current track');
    } else {
      setSleepTimerMinutes(minutes);
      setSleepTimerRemaining(minutes * 60);
      showToast(`🌙 Sleep timer set for ${minutes} minutes`);
    }
  }, [showToast]);

  const handleCancelSleepTimer = useCallback(() => {
    setSleepTimerMinutes(null);
    setSleepTimerRemaining(0);
    showToast('🌙 Sleep timer turned off');
  }, [showToast]);

  // Load and refresh personalized recommendations
  const refreshRecommendations = useCallback(async () => {
    setIsRefreshingRecs(true);
    try {
      const recs = await recommendationService.getPersonalizedRecommendations(
        recentlyPlayed,
        favorites,
        tracks
      );
      setPersonalizedRecs(recs);
      if (recs.frequentlyPlayed) {
        setFrequentlyPlayed(recs.frequentlyPlayed);
      }
      if (recs.basedOnLikes) {
        setLikesRecs(recs.basedOnLikes);
      }
      // Calculate 100% on-device personalized mix from active profile
      const prof = profileService.getActiveProfile();
      const pMix = recommendationService.getProfilePersonalizedMix(prof, tracks, favorites);
      setProfileMix(pMix);

      setTasteProfile(storageService.getTasteProfile(tracks));
    } catch (e) {
      console.warn('Failed to generate recommendations:', e);
    } finally {
      setIsRefreshingRecs(false);
    }
  }, [recentlyPlayed, favorites, tracks]);

  // Dynamic filtered tracks when a category navigation pill is selected
  const filteredHomeTracks = useMemo(() => {
    if (homeCategory === 'all') return [];
    const cat = homeCategory.toLowerCase();
    return tracks.filter(t => {
      if (isDummyTrack(t)) return false;
      const tGenre = (t.genre || '').toLowerCase();
      const tLang = (t.language || '').toLowerCase();
      const tTitle = (t.title || '').toLowerCase();
      const tArtist = (t.artist || '').toLowerCase();

      if (cat === 'trending') {
        return tTitle.includes('kesariya') || tTitle.includes('chaleya') || tTitle.includes('pyaar') || tTitle.includes('blinding') || tTitle.includes('starboy') || tTitle.includes('fe!n');
      }
      if (cat === 'bollywood') {
        return tLang === 'hindi' && tGenre !== 'hiphop';
      }
      if (cat === 'punjabi') {
        return tLang === 'punjabi' || tArtist.includes('diljit') || tArtist.includes('karan aujla') || tArtist.includes('sidhu') || tGenre === 'punjabi';
      }
      if (cat === 'hiphop') {
        return tGenre === 'hiphop' || tTitle.includes('hustle') || tArtist.includes('naam sujal') || tArtist.includes('paradox') || tArtist.includes('kendrick') || tArtist.includes('travis');
      }
      if (cat === 'lofi') {
        return tGenre === 'lofi' || tGenre === 'acoustic' || tTitle.includes('lo-fi') || tArtist.includes('anuv jain') || tArtist.includes('prateek');
      }
      if (cat === 'pop') {
        return tLang === 'english' || tGenre === 'pop';
      }
      if (cat === 'romantic') {
        return tGenre === 'romantic' || tTitle.includes('kesariya') || tTitle.includes('apna bana le') || tTitle.includes('tum se hi') || tTitle.includes('perfect') || tTitle.includes('golden');
      }
      if (cat === 'workout') {
        return tGenre === 'hiphop' || tGenre === 'electronic' || tTitle.includes('starboy') || tTitle.includes('fe!n') || tTitle.includes('not like us');
      }
      return true;
    });
  }, [homeCategory, tracks]);

  useEffect(() => {
    refreshRecommendations();
  }, [favorites.length, recentlyPlayed.length, refreshRecommendations]);

  // Play Track Helper - Immediate zero-latency playback with background lyrics fetching & auto-caching
  const handlePlayTrack = useCallback((trackToPlay, upcomingTracks = null, isFromHistory = false) => {
    if (!trackToPlay) return;

    // Spotify-style: If clicking on the track that is ALREADY active and playing/running, toggle Play / Pause!
    const isSameTrack = currentTrack && (
      currentTrack.id === trackToPlay.id ||
      (normalizeTrackName(currentTrack.title) === normalizeTrackName(trackToPlay.title) &&
       normalizeArtistName(currentTrack.artist) === normalizeArtistName(trackToPlay.artist))
    );

    // CRITICAL BUG FIX: Only toggle pause/resume if audioEngine is actually actively running this track!
    // On page load, currentTrack is set in React state, but audioEngine hasn't played or loaded it yet.
    if (isSameTrack && audioEngine.currentTrack && (audioEngine.isPlaying || audioEngine.currentTime > 0)) {
      if (audioEngine.isPlaying) {
        audioEngine.pause();
        showToast(`⏸ Paused: ${currentTrack.title}`);
      } else {
        audioEngine.resume();
        showToast(`▶ Resumed: ${currentTrack.title}`);
      }
      return;
    }

    // Save outgoing song to history stack so clicking "Previous" returns right back to it!
    if (!isFromHistory && currentTrack && currentTrack.id !== trackToPlay.id) {
      playHistoryRef.current.push(currentTrack);
      if (playHistoryRef.current.length > 50) {
        playHistoryRef.current.shift();
      }
    }

    // Resolve latest version from CURATED_TRACKS to guarantee full synced lyrics & accurate genre
    const freshMaster = findCuratedTrack(trackToPlay);
    let track = freshMaster
      ? {
          ...freshMaster,
          ...trackToPlay,
          youtubeId: trackToPlay.youtubeId || freshMaster.youtubeId,
          lyrics: (freshMaster.lyrics && freshMaster.lyrics.length > 0) ? freshMaster.lyrics : trackToPlay.lyrics,
          duration: freshMaster.duration || trackToPlay.duration
        }
      : { ...trackToPlay };

    // 2nd-Play Lyrics Model: Check if this song was learned and synced in our vault!
    const learnedLyrics = lyricsAutoSync.getEffectiveLyrics(track);
    if (learnedLyrics && learnedLyrics.length > 0 && typeof learnedLyrics[0]?.time === 'number') {
      track.lyrics = learnedLyrics;
      track.isSyncedLyrics = true;
    }

    // Trigger auto-sync engine: if 1st play and unsynced, learns timestamps in background!
    lyricsAutoSync.recordPlayAndAutoSync(track);

    showToast(`▶ Playing: ${track.title}`);

    // Update play frequency and recently played in persistent storage
    const playResult = storageService.recordTrackPlay(track);
    if (playResult && playResult.updatedHistory) {
      setRecentlyPlayed(playResult.updatedHistory);
    } else {
      const updatedHistory = storageService.addRecentlyPlayed(track);
      if (updatedHistory) setRecentlyPlayed(updatedHistory);
    }
    setFrequentlyPlayed(storageService.getFrequentlyPlayed(10, tracks));
    setTasteProfile(storageService.getTasteProfile(tracks));
    recommendationService.recordTrackPlayed(track);

    // Queue Management: If upcoming tracks are provided from a playlist/mix, queue those.
    // Otherwise, generate relevant recommendations matching current track's language/genre!
    let nextToWarm = null;
    if (Array.isArray(upcomingTracks) && upcomingTracks.length > 0) {
      activePlaylistContextRef.current = upcomingTracks;
      const cleanUpcoming = upcomingTracks.filter(
        t => !isDummyTrack(t) && t.id !== track.id && t.title.toLowerCase().trim() !== track.title.toLowerCase().trim()
      );
      setQueue(cleanUpcoming);
      nextToWarm = cleanUpcoming[0];
    } else {
      const relevantUpcoming = recommendationService.getRelevantUpcomingTracks(track, tracks, 15);
      setQueue(relevantUpcoming);
      nextToWarm = relevantUpcoming[0];

      // Asynchronously enrich queue with fresh, genre-accurate tracks from artist or genre
      recommendationService.fetchSmartUpcomingTracks(track, tracks, 15).then(freshQueue => {
        if (freshQueue && freshQueue.length > 0) {
          setQueue(freshQueue);
        }
      }).catch(() => {});
    }

    if (nextToWarm) {
      prewarmTrackResolution(nextToWarm);
    }

    // Immediately start playing and update active UI with zero lag
    setCurrentTrack(track);
    setCurrentTime(0);
    if (track.duration > 0) setDuration(track.duration);
    setIsPlaying(true);
    audioEngine.playTrack(track);

    // If lyrics are not yet synced, try fetching live lyrics from web
    if (!track.isSyncedLyrics && (!track.lyrics || track.lyrics.length === 0)) {
      (async () => {
        try {
          const liveLyricsResult = await fetchLiveLyrics(track.artist, track.title, track.duration);
          if (liveLyricsResult) {
            setCurrentTrack(curr => {
              if (curr && (curr.id === track.id || normalizeTrackName(curr.title) === normalizeTrackName(track.title))) {
                if (liveLyricsResult.synced && liveLyricsResult.lines && liveLyricsResult.lines.length > 0) {
                  return { ...curr, lyrics: liveLyricsResult.lines, isSyncedLyrics: true, plainLyrics: null };
                } else if (liveLyricsResult.plainLines && liveLyricsResult.plainLines.length > 0) {
                  return { ...curr, plainLyrics: liveLyricsResult.plainLines, isSyncedLyrics: false };
                }
              }
              return curr;
            });
          }
        } catch (_) {}
      })();
    }
  }, [currentTrack, tracks, showToast]);

  // Prime audio engine on mount with initial track metadata (without starting playback)
  useEffect(() => {
    if (currentTrack) {
      audioEngine.setInitialTrack(currentTrack);
    }
  }, []);

  const handleTogglePlay = useCallback(() => {
    if (audioEngine.isPlaying) {
      audioEngine.pause();
      setIsPlaying(false);
      showToast('⏸ Paused');
    } else {
      if (currentTrack) {
        // If audioEngine has never actually loaded or played this track yet, run handlePlayTrack!
        if (!audioEngine.currentTrack || audioEngine.currentTrack.id !== currentTrack.id || (!audioEngine.htmlAudio.src && (!audioEngine.ytPlayer || audioEngine.activeMode !== 'yt'))) {
          handlePlayTrack(currentTrack);
        } else {
          audioEngine.resume();
          setIsPlaying(true);
          showToast(`▶ Playing: ${currentTrack.title}`);
        }
      } else if (tracks.length > 0) {
        handlePlayTrack(tracks[0]);
      }
    }
  }, [currentTrack, tracks, handlePlayTrack, showToast]);

  // Next Track with Queue Priority, Skip-Dwell Feedback, and Intelligent Autoplay
  const handleNextTrack = useCallback(() => {
    if (repeatMode === 'one' && currentTrack) {
      audioEngine.repeatTrack();
      setIsPlaying(true);
      setCurrentTime(0);
      showToast(`🔂 Repeating: ${currentTrack.title}`);
      return;
    }

    // Skip-Dwell Feedback Loop: Real-time user behavioral signal
    if (currentTrack) {
      if (currentTime < 5) {
        // Immediate Skip (< 5s): Penalize artist & mood, suppress from immediate upcoming radio
        recommendationService.recordEarlySkip(currentTrack, currentTime);
      } else if (currentTime > 45 || (duration > 0 && currentTime / duration > 0.65)) {
        // High Engagement / Deep Listen: Boost artist & mood affinity
        recommendationService.recordTrackCompleted(currentTrack, currentTime);
      }
    }

    // 1. Check if queue has upcoming tracks
    if (queue.length > 0) {
      const nextUp = queue[0];
      const remaining = queue.slice(1);
      setQueue(remaining);
      handlePlayTrack(nextUp, remaining);
      return;
    }

    // 2. Playlist Infinite Autoplay Continuity: if playlist just ended, continue in the exact same vibe!
    if (activePlaylistContextRef.current && activePlaylistContextRef.current.length > 0) {
      const continuity = recommendationService.getPlaylistContinuityRecommendations(
        activePlaylistContextRef.current,
        currentTrack,
        tracks,
        15
      );
      if (continuity.length > 0) {
        showToast('✨ Infinite Autoplay: Continuing in playlist vibe');
        handlePlayTrack(continuity[0], continuity.slice(1));
        return;
      }
    }

    // 3. Fallback to smart auto-play next matching language/genre song
    if (currentTrack) {
      const relevant = recommendationService.getRelevantUpcomingTracks(currentTrack, tracks, 15);
      if (relevant.length > 0) {
        showToast('✨ Radio Autoplay: Similar tracks radio');
        handlePlayTrack(relevant[0], relevant.slice(1));
        return;
      }
    }

    // 3. Fallback to catalog order
    const validTracks = tracks.filter(t => !isDummyTrack(t));
    const currentIndex = validTracks.findIndex(t => t.id === currentTrack?.id);
    let nextIndex = 0;

    if (isShuffle) {
      nextIndex = Math.floor(Math.random() * validTracks.length);
    } else {
      nextIndex = (currentIndex + 1) % validTracks.length;
    }

    if (validTracks[nextIndex]) {
      handlePlayTrack(validTracks[nextIndex]);
    }
  }, [queue, tracks, currentTrack, isShuffle, repeatMode, handlePlayTrack]);

  // Prev Track - Accurate History Stack with Spotify-standard double-tap rewind
  const handlePrevTrack = useCallback(() => {
    const now = Date.now();
    const timeSinceLastClick = now - lastPrevClickRef.current;
    lastPrevClickRef.current = now;

    // If song has played for > 3 seconds, first tap rewinds to 0:00 (unless clicked again within 1.5s)
    if (currentTime > 3 && timeSinceLastClick > 1500) {
      audioEngine.seek(0);
      setCurrentTime(0);
      showToast('⏪ Rewound to start');
      return;
    }

    // 1. Check true playback history stack first
    if (playHistoryRef.current.length > 0) {
      const prevTrack = playHistoryRef.current.pop();

      // Put current track back onto upcoming queue so hitting "Next" returns to it
      if (currentTrack) {
        setQueue(prevQueue => {
          const filtered = prevQueue.filter(
            t => t.id !== currentTrack.id && t.title.toLowerCase().trim() !== currentTrack.title.toLowerCase().trim()
          );
          return [currentTrack, ...filtered];
        });
      }

      showToast(`⏮ Previous: ${prevTrack.title}`);
      handlePlayTrack(prevTrack, null, true);
      return;
    }

    // 2. Fallback: check recently played list from persistent storage
    const recent = storageService.getRecentlyPlayed().filter(t => !isDummyTrack(t));
    if (recent.length > 1 && currentTrack) {
      const currentIdx = recent.findIndex(
        t => t.id === currentTrack.id || t.title.toLowerCase().trim() === currentTrack.title.toLowerCase().trim()
      );
      const targetRecent = (currentIdx >= 0 && currentIdx + 1 < recent.length)
        ? recent[currentIdx + 1]
        : recent[1];

      if (targetRecent && targetRecent.id !== currentTrack.id) {
        if (currentTrack) {
          setQueue(prevQueue => [currentTrack, ...prevQueue.filter(t => t.id !== currentTrack.id)]);
        }
        showToast(`⏮ Previous: ${targetRecent.title}`);
        handlePlayTrack(targetRecent, null, true);
        return;
      }
    }

    // 3. Final fallback: previous track in active catalog
    const validTracks = tracks.filter(t => !isDummyTrack(t));
    if (validTracks.length > 0) {
      const currentIndex = validTracks.findIndex(t => t.id === currentTrack?.id);
      const prevIndex = (currentIndex - 1 + validTracks.length) % validTracks.length;
      if (validTracks[prevIndex]) {
        if (currentTrack) {
          setQueue(prevQueue => [currentTrack, ...prevQueue.filter(t => t.id !== currentTrack.id)]);
        }
        showToast(`⏮ Previous: ${validTracks[prevIndex].title}`);
        handlePlayTrack(validTracks[prevIndex], null, true);
      }
    }
  }, [currentTime, currentTrack, tracks, handlePlayTrack, showToast]);

  // References for MediaSession and AudioEngine to prevent stale closures
  const handleNextTrackRef = useRef(handleNextTrack);
  handleNextTrackRef.current = handleNextTrack;
  const handlePrevTrackRef = useRef(handlePrevTrack);
  handlePrevTrackRef.current = handlePrevTrack;
  const handleTogglePlayRef = useRef(handleTogglePlay);
  handleTogglePlayRef.current = handleTogglePlay;

  // AudioEngine event listeners & Hardware Media Key Controls
  useEffect(() => {
    const unsubTime = audioEngine.on('timeUpdate', ({ currentTime, duration }) => {
      setCurrentTime(currentTime);
      if (duration > 0) setDuration(duration);
    });

    const unsubState = audioEngine.on('stateChange', ({ isPlaying }) => {
      setIsPlaying(isPlaying);
    });

    const unsubEnded = audioEngine.on('ended', () => {
      if (currentTrack) {
        recommendationService.recordTrackCompleted(currentTrack, duration);
      }
      if (sleepTimerMinutesRef.current === 'endOfTrack') {
        audioEngine.pause();
        setIsPlaying(false);
        setSleepTimerMinutes(null);
        setSleepTimerRemaining(0);
        showToast('🌙 Current track ended. Sleep timer paused playback.');
        return;
      }
      handleNextTrackRef.current();
    });

    const unsubQuality = audioEngine.on('qualityChange', ({ quality }) => {
      setAudioQuality(quality);
    });

    const unsubMediaPrev = audioEngine.on('mediaPrev', () => {
      handlePrevTrackRef.current();
    });

    const unsubMediaNext = audioEngine.on('mediaNext', () => {
      handleNextTrackRef.current();
    });

    // Directly bind window navigator.mediaSession for instant earphone hardware button triggers
    if (typeof window !== 'undefined' && 'mediaSession' in navigator) {
      try {
        navigator.mediaSession.setActionHandler('play', () => {
          if (!audioEngine.isPlaying) {
            handleTogglePlayRef.current();
          }
        });
        navigator.mediaSession.setActionHandler('pause', () => {
          if (audioEngine.isPlaying) {
            handleTogglePlayRef.current();
          }
        });
        navigator.mediaSession.setActionHandler('stop', () => {
          if (audioEngine.isPlaying) {
            handleTogglePlayRef.current();
          }
        });
        navigator.mediaSession.setActionHandler('previoustrack', () => {
          handlePrevTrackRef.current();
        });
        navigator.mediaSession.setActionHandler('nexttrack', () => {
          handleNextTrackRef.current();
        });
      } catch (_) {}
    }

    if (typeof window !== 'undefined') {
      window.AuraWaveAudio = {
        togglePlay: () => {
          if (audioEngine.isPlaying) {
            audioEngine.pause();
          } else {
            audioEngine.resume();
          }
        },
        play: () => {
          audioEngine.resume();
        },
        pause: () => {
          audioEngine.pause();
        },
        next: () => {
          if (handleNextTrackRef.current) handleNextTrackRef.current();
          else audioEngine._emit('mediaNext');
        },
        prev: () => {
          if (handlePrevTrackRef.current) handlePrevTrackRef.current();
          else audioEngine._emit('mediaPrev');
        },
        seek: (seconds) => {
          audioEngine.seek(seconds);
        }
      };
    }

    return () => {
      unsubTime();
      unsubState();
      unsubEnded();
      unsubQuality();
      unsubMediaPrev();
      unsubMediaNext();
    };
  }, []);

  const handleSeek = (seconds) => {
    setCurrentTime(seconds);
    audioEngine.seek(seconds);
  };

  const handleToggleFavorite = async (trackToFav) => {
    // If trackToFav is a DOM event or missing, cleanly fallback to currentTrack
    const targetTrack = (trackToFav && typeof trackToFav === 'object' && trackToFav.id && !trackToFav.nativeEvent && !trackToFav._reactName)
      ? trackToFav
      : currentTrack;
    if (!targetTrack || !targetTrack.id) return;
    const isCurrentlyFav = favorites.includes(targetTrack.id);
    const next = isCurrentlyFav
      ? favorites.filter(id => id !== targetTrack.id)
      : [...favorites, targetTrack.id];
    setFavorites(next);
    storageService.saveFavorites(next);
    storageService.saveFavoriteMeta(targetTrack);

    if (!isCurrentlyFav) {
      showToast(`❤️ Added to Liked Songs: "${targetTrack.title}"`);
      // Default: Downloads are OFF by default. Only auto-cache if user explicitly enabled it!
      if (offlineStorage.isAutoDownloadEnabled()) {
        try {
          await offlineStorage.downloadTrack(targetTrack);
          const updated = await offlineStorage.getDownloadedTracks();
          setDownloadedTracks(updated);
          const stats = await offlineStorage.getStorageStats();
          setStorageStats(stats);
        } catch (_) {}
      }
    } else {
      showToast(`Removed from Liked Songs: "${targetTrack.title}"`);
    }

    // Auto-sync state to cloud for cross-device web & mobile sync
    syncService.pushToCloud().catch(() => {});

    setLikesRecs(recommendationService.getRecommendationsBasedOnLikes(next, tracks));
    setTasteProfile(storageService.getTasteProfile(tracks));
  };

  // Toggle 1-tap Spotify Offline Download
  const handleToggleDownload = async (track) => {
    if (!track) return;
    const isDownloaded = await offlineStorage.isTrackDownloaded(track);

    if (isDownloaded) {
      await offlineStorage.removeDownloadedTrack(track.id || track.title);
      showToast(`Removed from offline downloads: "${track.title}"`);
    } else {
      showToast(`💾 Downloading for offline: "${track.title}"...`);
      await offlineStorage.downloadTrack(track);
      showToast(`✅ Saved offline: "${track.title}" (Playable with 0 internet)`);
    }

    const updated = await offlineStorage.getDownloadedTracks();
    setDownloadedTracks(updated);
    const stats = await offlineStorage.getStorageStats();
    setStorageStats(stats);
  };

  // Toggle Offline Mode
  const handleToggleOfflineMode = (forced) => {
    offlineStorage.setOfflineModeForced(forced);
    setIsOfflineMode(forced);
  };

  // Volume Change
  const handleVolumeChange = (newVol) => {
    setVolume(newVol);
    audioEngine.setVolume(newVol);
  };

  // Bulk download all liked songs for offline listening
  const handleDownloadAllFavorites = async () => {
    if (favorites.length === 0) {
      showToast('No liked songs to download yet!');
      return;
    }
    const favoriteTracks = tracks.filter(t => favorites.includes(t.id));
    showToast(`💾 Starting offline download for ${favoriteTracks.length} liked songs...`);
    for (const tr of favoriteTracks) {
      const already = await offlineStorage.isTrackDownloaded(tr.id);
      if (!already) {
        await offlineStorage.downloadTrack(tr);
      }
    }
    const updated = await offlineStorage.getDownloadedTracks();
    setDownloadedTracks(updated);
    const updatedStats = await offlineStorage.getStorageStats();
    setStorageStats(updatedStats);
    showToast(`✅ Successfully downloaded ${favoriteTracks.length} liked songs!`);
  };

  // Global Desktop Keyboard Shortcuts (Space, MediaPlayPause, ArrowLeft/Right, ArrowUp/Down, M, L, F)
  useEffect(() => {
    const handleKeyDown = (e) => {
      const tag = e.target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable) {
        return;
      }

      // Check for MediaPlayPause / Space / MediaStop keys
      const isPlayPauseKey =
        e.code === 'MediaPlayPause' ||
        e.key === 'MediaPlayPause' ||
        e.key === 'PlayPause' ||
        e.code === 'MediaPlay' ||
        e.key === 'MediaPlay' ||
        e.code === 'MediaPause' ||
        e.key === 'MediaPause' ||
        e.code === 'MediaStop' ||
        e.key === 'MediaStop' ||
        e.code === 'Space';

      if (isPlayPauseKey) {
        e.preventDefault();
        e.stopPropagation();

        // If an interactive button is focused, blur it to prevent native browser button click re-triggering
        if (document.activeElement && typeof document.activeElement.blur === 'function') {
          document.activeElement.blur();
        }

        handleTogglePlay();
        return;
      }

      // Hardware Next Track Key or Ctrl+ArrowRight
      if (e.code === 'MediaTrackNext' || e.key === 'MediaTrackNext' || (e.code === 'ArrowRight' && (e.ctrlKey || e.metaKey))) {
        e.preventDefault();
        handleNextTrack();
        return;
      }

      // Hardware Previous Track Key or Ctrl+ArrowLeft
      if (e.code === 'MediaTrackPrevious' || e.key === 'MediaTrackPrevious' || (e.code === 'ArrowLeft' && (e.ctrlKey || e.metaKey))) {
        e.preventDefault();
        handlePrevTrack();
        return;
      }

      if (e.code === 'ArrowLeft') {
        e.preventDefault();
        const target = Math.max(0, currentTime - 5);
        handleSeek(target);
        showToast('⏪ -5s');
      } else if (e.code === 'ArrowRight') {
        e.preventDefault();
        const target = Math.min(duration || 180, currentTime + 5);
        handleSeek(target);
        showToast('⏩ +5s');
      } else if (e.code === 'ArrowUp') {
        e.preventDefault();
        const nextVol = Math.min(1, Math.round((volume + 0.05) * 100) / 100);
        handleVolumeChange(nextVol);
        showToast(`🔊 Volume: ${Math.round(nextVol * 100)}%`);
      } else if (e.code === 'ArrowDown') {
        e.preventDefault();
        const nextVol = Math.max(0, Math.round((volume - 0.05) * 100) / 100);
        handleVolumeChange(nextVol);
        showToast(`🔉 Volume: ${Math.round(nextVol * 100)}%`);
      } else if (e.key === 'm' || e.key === 'M') {
        e.preventDefault();
        if (volume > 0) {
          lastVolumeRef.current = volume;
          handleVolumeChange(0);
          showToast('🔇 Muted');
        } else {
          const restored = lastVolumeRef.current || 0.85;
          handleVolumeChange(restored);
          showToast(`🔊 Unmuted (${Math.round(restored * 100)}%)`);
        }
      } else if (e.key === 'l' || e.key === 'L') {
        e.preventDefault();
        if (currentTrack) handleToggleFavorite(currentTrack);
      } else if (e.key === 't' || e.key === 'T') {
        e.preventDefault();
        setShowSleepModal(prev => !prev);
      } else if (e.key === '?' || (e.shiftKey && e.key === '/')) {
        e.preventDefault();
        setShowShortcutsModal(prev => !prev);
      } else if (e.key === 'f' || e.key === 'F') {
        e.preventDefault();
        setShowFullPlayer(prev => !prev);
      } else if (e.key === 'q' || e.key === 'Q') {
        e.preventDefault();
        setShowQueueModal(prev => !prev);
      }
    };

    window.addEventListener('keydown', handleKeyDown, true);
    return () => window.removeEventListener('keydown', handleKeyDown, true);
  }, [currentTime, duration, volume, currentTrack, handleTogglePlay, handleSeek, handleVolumeChange, handleNextTrack, handlePrevTrack, handleToggleFavorite, showToast]);

  // Queue Operations
  const handleAddToQueue = (track) => {
    setQueue(prev => [...prev, track]);
    showToast(`Added to queue: "${track.title}"`);
  };

  const handleRemoveFromQueue = (index) => {
    setQueue(prev => prev.filter((_, i) => i !== index));
  };

  const handleReorderQueue = (fromIdx, toIdx) => {
    setQueue(prev => {
      const copy = [...prev];
      const [moved] = copy.splice(fromIdx, 1);
      copy.splice(toIdx, 0, moved);
      return copy;
    });
  };

  const handleClearQueue = () => {
    setQueue([]);
    showToast('Queue cleared');
  };

  const handleShuffleQueue = () => {
    setQueue(prev => {
      const copy = [...prev];
      for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
      }
      return copy;
    });
    showToast('🔀 Queue shuffled');
  };

  const handlePopulateRecommendations = () => {
    if (currentTrack) {
      const fresh = recommendationService.getRelevantUpcomingTracks(currentTrack, tracks, 15);
      setQueue(fresh);
      showToast(`✨ Generated ${fresh.length} recommendations`);
    }
  };

  // Playlist Management
  const handleCreatePlaylist = (name, description, cover) => {
    const { newPlaylist, allPlaylists } = storageService.createPlaylist(name, description, cover);
    setPlaylists(allPlaylists);
    setSelectedPlaylist(newPlaylist);
    showToast(`Playlist "${newPlaylist.name}" created!`);
    syncService.pushToCloud().catch(() => {});
  };

  const handleDeletePlaylist = (playlistId) => {
    const updated = storageService.deletePlaylist(playlistId);
    setPlaylists(updated);
    if (selectedPlaylist?.id === playlistId) {
      setSelectedPlaylist(null);
    }
    showToast('Playlist deleted');
    syncService.pushToCloud().catch(() => {});
  };

  const handleRenamePlaylist = (playlistId, newName) => {
    const updated = storageService.renamePlaylist(playlistId, newName);
    setPlaylists(updated);
    if (selectedPlaylist?.id === playlistId) {
      setSelectedPlaylist(prev => ({ ...prev, name: newName }));
    }
    showToast('Playlist renamed');
    syncService.pushToCloud().catch(() => {});
  };

  const handleAddToPlaylist = (playlistId, track) => {
    const { updated, added } = storageService.addTrackToPlaylist(playlistId, track);
    if (added) {
      setPlaylists(updated);
      showToast(`Added "${track.title}" to playlist`);
      syncService.pushToCloud().catch(() => {});
    } else {
      showToast('Already in playlist');
    }
  };

  const handleRemoveTrackFromPlaylist = (playlistId, trackId) => {
    const updated = storageService.removeTrackFromPlaylist(playlistId, trackId);
    setPlaylists(updated);
    if (selectedPlaylist?.id === playlistId) {
      setSelectedPlaylist(prev => ({
        ...prev,
        tracks: prev.tracks.filter(t => (typeof t === 'string' ? t !== trackId : t.id !== trackId))
      }));
    }
    showToast('Removed song from playlist');
    syncService.pushToCloud().catch(() => {});
  };

  // Import Spotify Playlist Success with persistent storage
  const handleSpotifyImportSuccess = (newPlaylist) => {
    const safePlaylist = {
      ...newPlaylist,
      isCustom: true,
      isImported: true,
      trackCount: newPlaylist.tracks?.length || 0
    };

    const allPlaylists = storageService.savePlaylist(safePlaylist);
    setPlaylists(allPlaylists);

    setTracks(prev => {
      const existingIds = new Set(prev.map(t => t.id));
      const additions = (safePlaylist.tracks || []).filter(t => !existingIds.has(t.id));
      return [...prev, ...additions];
    });

    // Auto-navigate to the imported playlist in library
    setSelectedPlaylist(safePlaylist);
    setActiveTab('library');
    setShowSpotifyModal(false);

    if (safePlaylist.tracks && safePlaylist.tracks.length > 0) {
      handlePlayTrack(safePlaylist.tracks[0]);
    }

    showToast(`✨ Imported "${safePlaylist.name}" (${safePlaylist.trackCount} songs)`);
    syncService.pushToCloud().catch(() => {});
  };

  // Mobile / Android Hardware Back Button & Hierarchy Navigation Handler
  const lastBackPressRef = useRef(0);
  useEffect(() => {
    const handleBackButtonNavigation = () => {
      // 1. Highest priority: Close open modals in reverse order of depth
      if (showLyricsView) {
        setShowLyricsView(false);
        return true;
      }
      if (showFullPlayer) {
        setShowFullPlayer(false);
        return true;
      }
      if (showPocketMode) {
        setShowPocketMode(false);
        return true;
      }
      if (showSpotifyModal) {
        setShowSpotifyModal(false);
        return true;
      }
      if (showCreatePlaylistModal) {
        setShowCreatePlaylistModal(false);
        return true;
      }
      if (showAddToPlaylistModal) {
        setShowAddToPlaylistModal(false);
        return true;
      }
      if (showQueueModal) {
        setShowQueueModal(false);
        return true;
      }
      if (showSleepModal) {
        setShowSleepModal(false);
        return true;
      }
      if (showQualityModal) {
        setShowQualityModal(false);
        return true;
      }
      if (showTasteModal) {
        setShowTasteModal(false);
        return true;
      }
      if (showShortcutsModal) {
        setShowShortcutsModal(false);
        return true;
      }
      if (showOnboarding) {
        setShowOnboarding(false);
        return true;
      }

      // 2. Medium priority: If currently viewing a playlist detail, return to library list
      if (selectedPlaylist) {
        setSelectedPlaylist(null);
        return true;
      }

      // 3. Low priority: If in search, library, offline, or favorites, return to home tab
      if (activeTab !== 'home') {
        setActiveTab('home');
        return true;
      }

      // 4. Base state (Home tab with no modals): Double tap back within 2s to exit
      const now = Date.now();
      if (now - lastBackPressRef.current < 2000) {
        try {
          CapApp.exitApp();
        } catch (_) {}
      } else {
        lastBackPressRef.current = now;
        showToast('Press back again to exit AuraWave');
      }
      return false;
    };

    // A. Native Capacitor Android Back Button Listener
    let backListenerHandle = null;
    try {
      CapApp.addListener('backButton', () => {
        handleBackButtonNavigation();
      }).then(handle => {
        backListenerHandle = handle;
      }).catch(() => {});
    } catch (_) {}

    // B. Browser popstate navigation
    const handlePopState = (e) => {
      e.preventDefault();
      const handled = handleBackButtonNavigation();
      if (handled) {
        window.history.pushState(null, '', window.location.pathname);
      }
    };

    window.history.pushState(null, '', window.location.pathname);
    window.addEventListener('popstate', handlePopState);

    return () => {
      if (backListenerHandle && backListenerHandle.remove) {
        backListenerHandle.remove();
      }
      window.removeEventListener('popstate', handlePopState);
    };
  }, [
    showLyricsView,
    showFullPlayer,
    showPocketMode,
    showSpotifyModal,
    showCreatePlaylistModal,
    showAddToPlaylistModal,
    showQueueModal,
    showSleepModal,
    showQualityModal,
    showTasteModal,
    showShortcutsModal,
    showOnboarding,
    selectedPlaylist,
    activeTab,
    showToast
  ]);

  // Browser-grade Forward / Back Navigation History for TopBar
  const [navHistory, setNavHistory] = useState(() => [{ tab: 'home', playlist: null }]);
  const [navHistoryIdx, setNavHistoryIdx] = useState(0);

  const navigateTo = useCallback((tabId, playlist = null) => {
    setActiveTab(tabId);
    setSelectedPlaylist(playlist);
    setNavHistory(prev => {
      const nextStack = prev.slice(0, navHistoryIdx + 1);
      nextStack.push({ tab: tabId, playlist });
      return nextStack;
    });
    setNavHistoryIdx(prev => prev + 1);
  }, [navHistoryIdx]);

  const handleGoBack = useCallback(() => {
    if (navHistoryIdx > 0) {
      const nextIdx = navHistoryIdx - 1;
      const target = navHistory[nextIdx];
      setNavHistoryIdx(nextIdx);
      setActiveTab(target.tab);
      setSelectedPlaylist(target.playlist);
    } else if (selectedPlaylist) {
      setSelectedPlaylist(null);
    } else if (activeTab !== 'home') {
      setActiveTab('home');
    }
  }, [navHistoryIdx, navHistory, selectedPlaylist, activeTab]);

  const handleGoForward = useCallback(() => {
    if (navHistoryIdx < navHistory.length - 1) {
      const nextIdx = navHistoryIdx + 1;
      const target = navHistory[nextIdx];
      setNavHistoryIdx(nextIdx);
      setActiveTab(target.tab);
      setSelectedPlaylist(target.playlist);
    }
  }, [navHistoryIdx, navHistory]);

  const handleSelectTab = useCallback((tabId) => {
    if (tabId === 'lyrics') {
      setShowLyricsView(true);
      return;
    }
    navigateTo(tabId, null);
  }, [navigateTo]);

  const handleSelectPlaylist = useCallback((pl) => {
    navigateTo(activeTab, pl);
  }, [navigateTo, activeTab]);

  // Personalized Daily Mixes
  const dailyMixes = useMemo(() => {
    return recommendationService.getDailyMixes(recentlyPlayed, tracks);
  }, [recentlyPlayed, tracks]);

  const greeting = useMemo(() => {
    const hour = new Date().getHours();
    if (hour < 12) return 'Good morning';
    if (hour < 18) return 'Good afternoon';
    return 'Good evening';
  }, []);

  // Quick 6 cards for Spotify 2x3 grid
  const quickGridTracks = useMemo(() => {
    const combined = [...recentlyPlayed];
    tracks.forEach(t => {
      if (!combined.some(c => c.id === t.id)) combined.push(t);
    });
    return combined.slice(0, 6);
  }, [recentlyPlayed, tracks]);

  const isCurrentTrackDownloaded = useMemo(() => {
    if (!currentTrack) return false;
    return downloadedTracks.some(t => 
      t.id === currentTrack.id || 
      (normalizeTrackName(t.title) === normalizeTrackName(currentTrack.title) &&
       normalizeArtistName(t.artist) === normalizeArtistName(currentTrack.artist))
    );
  }, [currentTrack, downloadedTracks]);

  const downloadedTrackIds = useMemo(() => {
    const ids = [];
    downloadedTracks.forEach(t => {
      if (t.id) ids.push(t.id);
      if (t.title) ids.push(normalizeTrackName(t.title));
    });
    return ids;
  }, [downloadedTracks]);

  // Liked Songs playlist object for detail view
  const likedSongsPlaylist = useMemo(() => {
    const likedTracks = tracks.filter(t => favorites.includes(t.id));
    return {
      id: 'liked-songs-virtual',
      name: 'Liked Songs',
      description: 'Your favorite tracks • High fidelity playback',
      cover: 'https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=600&auto=format&fit=crop&q=80',
      tracks: likedTracks,
      isCustom: false
    };
  }, [tracks, favorites]);

  return (
    <div className={`sp-app-container ${isMobile ? 'mobile-mode' : 'desktop-spotify-mode'}`}>
      {/* Animated Dynamic Splash / Flash Screen */}
      {showSplash && (
        <DynamicSplashScreen
          onComplete={() => setShowSplash(false)}
          brandName="AuraWave"
          brandTag="PRO HI-FI"
          autoDismissTime={2200}
        />
      )}

      <YouTubeBridge />

      {/* Floating Quick Toast Notification */}
      {toastMessage && (
        <div className="floating-toast" id="app-floating-toast">
          <span>{toastMessage}</span>
        </div>
      )}

      {/* ====================================================================
          DESKTOP SPOTIFY LAYOUT (Full Screen, Sidebar, Top Bar, Bottom Bar)
          ==================================================================== */}
      {!isMobile ? (
        <div className="sp-desktop-app" id="spotify-desktop-app">
          <div className="sp-desktop-body">
            {/* Left Sidebar */}
            <SpotifySidebar
              activeTab={activeTab}
              onSelectTab={handleSelectTab}
              playlists={playlists}
              selectedPlaylistId={selectedPlaylist?.id}
              onSelectPlaylist={handleSelectPlaylist}
              onOpenCreatePlaylist={() => setShowCreatePlaylistModal(true)}
              onOpenImportSpotify={() => setShowSpotifyModal(true)}
              downloadedCount={downloadedTracks.length}
              storageStats={storageStats}
              isOfflineMode={isOfflineMode}
              onToggleOfflineMode={handleToggleOfflineMode}
              favoritesCount={favorites.length}
            />

            {/* Main Stage Window */}
            <main className="sp-main-content" id="spotify-main-content">
              {/* Sticky Top Bar with Forward/Back History and Quick Search */}
              <SpotifyTopBar
                activeTab={activeTab}
                onGoBack={handleGoBack}
                canGoBack={navHistoryIdx > 0 || !!selectedPlaylist || activeTab !== 'home'}
                onGoForward={handleGoForward}
                canGoForward={navHistoryIdx < navHistory.length - 1}
                onSelectTab={handleSelectTab}
                isOfflineMode={isOfflineMode}
                onToggleOfflineMode={handleToggleOfflineMode}
                audioQuality={audioQuality}
                onOpenQualityModal={() => setShowQualityModal(true)}
                storageStats={storageStats}
                onOpenTasteModal={() => setShowTasteModal(true)}
                activeProfile={activeProfile}
              />

              {/* Dynamic View Body */}
              <div className="sp-content-body">
                {showLyricsView ? (
                  <SpecialLyricsView
                    track={currentTrack}
                    currentTime={currentTime}
                    duration={duration}
                    isPlaying={isPlaying}
                    onSeek={handleSeek}
                    onTogglePlay={handleTogglePlay}
                    onPrev={handlePrevTrack}
                    onNext={handleNextTrack}
                    onClose={() => setShowLyricsView(false)}
                    onOpenPocketMode={() => setShowPocketMode(true)}
                    isMobile={isMobile}
                    isFavorite={favorites.includes(currentTrack?.id)}
                    onToggleFavorite={handleToggleFavorite}
                  />
                ) : selectedPlaylist ? (
                  <PlaylistDetailView
                    playlist={selectedPlaylist}
                    tracks={tracks}
                    currentTrackId={currentTrack?.id}
                    isPlaying={isPlaying}
                    onPlayTrack={handlePlayTrack}
                    onBack={() => setSelectedPlaylist(null)}
                    onDeletePlaylist={handleDeletePlaylist}
                    onRenamePlaylist={handleRenamePlaylist}
                    onRemoveTrackFromPlaylist={handleRemoveTrackFromPlaylist}
                    favorites={favorites}
                    onToggleFavorite={handleToggleFavorite}
                  />
                ) : activeTab === 'search' ? (
                  <SearchModal
                    isMobile={false}
                    onSelectTrack={track => {
                      setTracks(prev => {
                        if (!prev.some(t => t.id === track.id || (t.title === track.title && t.artist === track.artist))) {
                          return [track, ...prev];
                        }
                        return prev;
                      });
                      handlePlayTrack(track);
                    }}
                    currentTrackId={currentTrack?.id}
                    currentTrackTitle={currentTrack?.title}
                    currentTrackArtist={currentTrack?.artist}
                    currentTrack={currentTrack}
                    isPlaying={isPlaying}
                    onOpenAddToPlaylist={track => {
                      setTrackForPlaylist(track);
                      setShowAddToPlaylistModal(true);
                    }}
                    onAddToQueue={handleAddToQueue}
                    onToggleDownload={handleToggleDownload}
                    downloadedTrackIds={downloadedTrackIds}
                    favorites={favorites}
                    onToggleFavorite={handleToggleFavorite}
                  />
                ) : activeTab === 'offline' ? (
                  <DownloadedView
                    isMobile={false}
                    downloadedTracks={downloadedTracks}
                    currentTrackId={currentTrack?.id}
                    isPlaying={isPlaying}
                    onPlayTrack={handlePlayTrack}
                    onTogglePlay={handleTogglePlay}
                    onRemoveDownload={async (id) => {
                      await offlineStorage.removeDownloadedTrack(id);
                      const updated = await offlineStorage.getDownloadedTracks();
                      setDownloadedTracks(updated);
                      const stats = await offlineStorage.getStorageStats();
                      setStorageStats(stats);
                    }}
                    isOfflineMode={isOfflineMode}
                    onToggleOfflineMode={handleToggleOfflineMode}
                    storageStats={storageStats}
                    onOpenSearch={() => setActiveTab('search')}
                    onDownloadAllFavorites={handleDownloadAllFavorites}
                  />
                ) : activeTab === 'favorites' ? (
                  <PlaylistDetailView
                    playlist={likedSongsPlaylist}
                    tracks={tracks}
                    currentTrackId={currentTrack?.id}
                    isPlaying={isPlaying}
                    onPlayTrack={handlePlayTrack}
                    onBack={() => setActiveTab('home')}
                  />
                ) : activeTab === 'library' ? (
                  <SpotifyLibraryView
                    playlists={playlists}
                    favorites={favorites}
                    downloadedTracks={downloadedTracks}
                    storageStats={storageStats}
                    onSelectPlaylist={handleSelectPlaylist}
                    onOpenCreatePlaylist={() => setShowCreatePlaylistModal(true)}
                    onOpenImportSpotify={() => setShowSpotifyModal(true)}
                    onOpenOffline={() => setActiveTab('offline')}
                    likedSongsPlaylist={likedSongsPlaylist}
                    isMobile={false}
                  />
                ) : (
                  /* Home Tab */
                  <div className="sp-home-view">
                    {/* Horizontal Scrollable Category Filter Navigation Bar */}
                    <CategoryNavBar
                      categories={HOME_NAV_CATEGORIES}
                      activeCategory={homeCategory}
                      onSelectCategory={setHomeCategory}
                    />

                    {/* Filtered Category Shelf (Shown when a category is active) */}
                    {homeCategory !== 'all' && (
                      <section className="sp-shelf sp-category-shelf-highlight">
                        <div className="sp-shelf-header">
                          <div className="sp-shelf-header-left">
                            <h2 className="sp-shelf-title">
                              {HOME_NAV_CATEGORIES.find(c => c.id === homeCategory)?.label}
                            </h2>
                            <span className="sp-count-pill">{filteredHomeTracks.length} songs</span>
                          </div>
                          <div className="sp-shelf-header-actions">
                            {filteredHomeTracks.length > 0 && (
                              <button
                                type="button"
                                className="sp-shelf-play-all-btn"
                                onClick={() => handlePlayTrack(filteredHomeTracks[0], filteredHomeTracks.slice(1))}
                                title="Play all songs in this category"
                              >
                                <Play size={14} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                                <span>Play All</span>
                              </button>
                            )}
                            <button
                              type="button"
                              className="sp-shelf-clear-filter-btn"
                              onClick={() => setHomeCategory('all')}
                              title="Show all songs"
                            >
                              All Hits ✕
                            </button>
                          </div>
                        </div>
                        <ScrollableShelfRow id="filter-shelf-row">
                          {filteredHomeTracks.map((track, idx) => {
                            const isCurrent = currentTrack?.id === track.id;
                            return (
                              <div
                                key={`filter-shelf-${track.id || idx}`}
                                className={`sp-card ${isCurrent ? 'active' : ''}`}
                                onClick={() => handlePlayTrack(track, filteredHomeTracks.slice(idx + 1))}
                              >
                                <div className="sp-card-cover-wrap">
                                  <img src={track.coverUrl} alt={track.title} className="sp-card-cover" />
                                  <div className={`sp-card-hover-play ${isCurrent && isPlaying ? 'playing' : ''}`}>
                                    {isCurrent && isPlaying ? (
                                      <Pause size={22} fill="#000000" color="#000000" />
                                    ) : (
                                      <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                                    )}
                                  </div>
                                  {isCurrent && isPlaying && (
                                    <div className="sp-card-eq-overlay">
                                      <span /><span /><span />
                                    </div>
                                  )}
                                </div>
                                <span className={`sp-card-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</span>
                                <span className="sp-card-desc">{track.artist}</span>
                              </div>
                            );
                          })}
                        </ScrollableShelfRow>
                      </section>
                    )}

                    {/* 1. Curated Sonic Journeys Hero Banner (Matching Screenshot 1) */}
                    <SonicHeroBanner
                      onPlayAuraMix={() => {
                        const targetTrack = tracks.find(t => t.id === 'track-kesariya') || tracks[0];
                        handlePlayTrack(targetTrack, tracks.slice(1));
                      }}
                      onSaveToLibrary={() => {
                        showToast('✨ Curated Sonic Journeys saved to Your Library!');
                      }}
                      currentTrack={currentTrack}
                      isPlaying={isPlaying}
                      onTogglePlay={handleTogglePlay}
                      tracks={tracks}
                      queue={queue}
                    />

                    {/* 2. Recently Played 2x3 Grid with Verified Badges (Matching Screenshot 1) */}
                    <RecentlyPlayedGrid
                      tracks={recentlyPlayed.length >= 6 ? recentlyPlayed : DEFAULT_RECENTLY_PLAYED}
                      currentTrackId={currentTrack?.id}
                      isPlaying={isPlaying}
                      onPlayTrack={handlePlayTrack}
                      onViewAll={() => setActiveTab('library')}
                    />

                    {/* 3. Recommended For You Carousel Shelf with Format Badges (Matching Screenshot 1) */}
                    <RecommendedShelf
                      onPlayItem={(item) => {
                        handleSelectTab('search');
                      }}
                    />

                    {/* Dedicated Shelf: 🔥 Frequently Played • On Repeat */}
                    {frequentlyPlayed.length > 0 && (
                      <section className="sp-shelf">
                        <div className="sp-shelf-header">
                          <div>
                            <h2 className="sp-shelf-title">🔥 Frequently Played • On Repeat</h2>
                            <p className="sp-card-desc" style={{ margin: '4px 0 0 0' }}>The songs you stream again and again</p>
                          </div>
                          <button
                            className="sp-shelf-play-all-btn"
                            onClick={() => handlePlayTrack(frequentlyPlayed[0], frequentlyPlayed.slice(1))}
                            title="Play All On Repeat"
                          >
                            <Play size={13} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                            <span>Play All</span>
                          </button>
                        </div>
                        <ScrollableShelfRow id="freq-shelf-row">
                          {frequentlyPlayed.slice(0, 6).map((track, idx) => {
                            const isCurrent = currentTrack?.id === track.id;
                            const isFav = favorites.includes(track.id);
                            const playCount = track.playCount || storageService.getTrackPlayCount(track);
                            return (
                              <div
                                key={`freq-shelf-${track.id || idx}`}
                                className={`sp-card ${isCurrent ? 'active' : ''}`}
                                onClick={() => handlePlayTrack(track, frequentlyPlayed.slice(idx + 1))}
                              >
                                <div className="sp-card-cover-wrap">
                                  <img src={track.coverUrl} alt={track.title} className="sp-card-cover" />
                                  {playCount > 1 && (
                                    <div className="sp-card-playcount-badge" title={`Played ${playCount} times`}>
                                      <Flame size={11} />
                                      <span>{playCount}x</span>
                                    </div>
                                  )}
                                  <div className={`sp-card-hover-play ${isCurrent && isPlaying ? 'playing' : ''}`}>
                                    {isCurrent && isPlaying ? (
                                      <Pause size={22} fill="#000000" color="#000000" />
                                    ) : (
                                      <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                                    )}
                                  </div>
                                  {isCurrent && isPlaying && (
                                    <div className="sp-card-eq-overlay">
                                      <span /><span /><span />
                                    </div>
                                  )}
                                  <button
                                    type="button"
                                    className={`sp-card-heart-btn ${isFav ? 'is-fav' : ''}`}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      handleToggleFavorite(track);
                                    }}
                                    title={isFav ? 'Liked' : 'Like song'}
                                  >
                                    <Heart size={15} fill={isFav ? '#ef4444' : 'none'} color={isFav ? '#ef4444' : 'currentColor'} />
                                  </button>
                                </div>
                                <span className={`sp-card-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</span>
                                <span className="sp-card-desc">{track.artist}</span>
                              </div>
                            );
                          })}
                        </ScrollableShelfRow>
                      </section>
                    )}

                    {/* Dedicated Shelf: ✨ Made for [Name] (100% On-Device Personalized Mix) */}
                    {profileMix && profileMix.tracks && profileMix.tracks.length > 0 && (
                      <section className="sp-shelf">
                        <div className="sp-shelf-header">
                          <div>
                            <h2 className="sp-shelf-title">✨ {profileMix.tag}</h2>
                            <p className="sp-card-desc" style={{ margin: '4px 0 0 0' }}>{profileMix.reason}</p>
                          </div>
                          <button
                            className="sp-shelf-play-all-btn"
                            onClick={() => handlePlayTrack(profileMix.tracks[0], profileMix.tracks.slice(1))}
                            title="Play Your Personalized Mix"
                          >
                            <Play size={13} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                            <span>Play All</span>
                          </button>
                        </div>
                        <ScrollableShelfRow id="profile-shelf-row">
                          {profileMix.tracks.slice(0, 8).map((track, idx) => {
                            const isCurrent = currentTrack?.id === track.id;
                            const isFav = favorites.includes(track.id);
                            return (
                              <div
                                key={`profile-shelf-${track.id || idx}`}
                                className={`sp-card ${isCurrent ? 'active' : ''}`}
                                onClick={() => handlePlayTrack(track, profileMix.tracks.slice(idx + 1))}
                              >
                                <div className="sp-card-cover-wrap">
                                  <img src={track.coverUrl} alt={track.title} className="sp-card-cover" />
                                  <div className="sp-card-like-badge" title="Tailored to your taste">
                                    <Sparkles size={10} color="#a855f7" />
                                    <span>For You</span>
                                  </div>
                                  <div className={`sp-card-hover-play ${isCurrent && isPlaying ? 'playing' : ''}`}>
                                    {isCurrent && isPlaying ? (
                                      <Pause size={22} fill="#000000" color="#000000" />
                                    ) : (
                                      <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                                    )}
                                  </div>
                                  {isCurrent && isPlaying && (
                                    <div className="sp-card-eq-overlay">
                                      <span /><span /><span />
                                    </div>
                                  )}
                                  <button
                                    type="button"
                                    className={`sp-card-heart-btn ${isFav ? 'is-fav' : ''}`}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      handleToggleFavorite(track);
                                    }}
                                    title={isFav ? 'Liked' : 'Like song'}
                                  >
                                    <Heart size={15} fill={isFav ? '#ef4444' : 'none'} color={isFav ? '#ef4444' : 'currentColor'} />
                                  </button>
                                </div>
                                <span className={`sp-card-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</span>
                                <span className="sp-card-desc">{track.artist}</span>
                              </div>
                            );
                          })}
                        </ScrollableShelfRow>
                      </section>
                    )}

                    {/* Dedicated Shelf: ❤️ Based on Your Likes */}
                    {likesRecs && likesRecs.tracks && likesRecs.tracks.length > 0 && (
                      <section className="sp-shelf">
                        <div className="sp-shelf-header">
                          <div>
                            <h2 className="sp-shelf-title">❤️ {likesRecs.tag || 'Based on Your Likes'}</h2>
                            <p className="sp-card-desc" style={{ margin: '4px 0 0 0' }}>{likesRecs.reason || 'Curated to match your favorite artists & tracks'}</p>
                          </div>
                          <button
                            className="sp-shelf-play-all-btn"
                            onClick={() => handlePlayTrack(likesRecs.tracks[0], likesRecs.tracks.slice(1))}
                            title="Play All From Your Likes"
                          >
                            <Play size={13} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                            <span>Play All</span>
                          </button>
                        </div>
                        <ScrollableShelfRow id="likes-shelf-row">
                          {likesRecs.tracks.slice(0, 6).map((track, idx) => {
                            const isCurrent = currentTrack?.id === track.id;
                            const isFav = favorites.includes(track.id);
                            return (
                              <div
                                key={`likes-shelf-${track.id || idx}`}
                                className={`sp-card ${isCurrent ? 'active' : ''}`}
                                onClick={() => handlePlayTrack(track, likesRecs.tracks.slice(idx + 1))}
                              >
                                <div className="sp-card-cover-wrap">
                                  <img src={track.coverUrl} alt={track.title} className="sp-card-cover" />
                                  <div className="sp-card-like-badge" title="Recommended from your likes">
                                    <Heart size={10} fill="#ec4899" />
                                    <span>For You</span>
                                  </div>
                                  <div className={`sp-card-hover-play ${isCurrent && isPlaying ? 'playing' : ''}`}>
                                    {isCurrent && isPlaying ? (
                                      <Pause size={22} fill="#000000" color="#000000" />
                                    ) : (
                                      <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                                    )}
                                  </div>
                                  {isCurrent && isPlaying && (
                                    <div className="sp-card-eq-overlay">
                                      <span /><span /><span />
                                    </div>
                                  )}
                                  <button
                                    type="button"
                                    className={`sp-card-heart-btn ${isFav ? 'is-fav' : ''}`}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      handleToggleFavorite(track);
                                    }}
                                    title={isFav ? 'Liked' : 'Like song'}
                                  >
                                    <Heart size={15} fill={isFav ? '#ef4444' : 'none'} color={isFav ? '#ef4444' : 'currentColor'} />
                                  </button>
                                </div>
                                <span className={`sp-card-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</span>
                                <span className="sp-card-desc">{track.artist}</span>
                              </div>
                            );
                          })}
                        </ScrollableShelfRow>
                      </section>
                    )}

                    {/* Dedicated Shelf: 🕒 Jump Back In (Recently Played) */}
                    {recentlyPlayed.length > 0 && (
                      <section className="sp-shelf">
                        <div className="sp-shelf-header">
                          <div>
                            <h2 className="sp-shelf-title">🕒 Jump Back In</h2>
                            <p className="sp-card-desc" style={{ margin: '4px 0 0 0' }}>Resume your recent listening flow</p>
                          </div>
                          <button
                            className="sp-shelf-see-all"
                            onClick={() => setActiveTab('library')}
                          >
                            Show All ({recentlyPlayed.length})
                          </button>
                        </div>
                        <ScrollableShelfRow id="recent-shelf-row">
                          {recentlyPlayed.slice(0, 6).map((track, idx) => {
                            const isCurrent = currentTrack?.id === track.id;
                            const isFav = favorites.includes(track.id);
                            return (
                              <div
                                key={`recent-shelf-${track.id || idx}`}
                                className={`sp-card ${isCurrent ? 'active' : ''}`}
                                onClick={() => handlePlayTrack(track, recentlyPlayed.slice(idx + 1), true)}
                              >
                                <div className="sp-card-cover-wrap">
                                  <img src={track.coverUrl} alt={track.title} className="sp-card-cover" />
                                  <div className={`sp-card-hover-play ${isCurrent && isPlaying ? 'playing' : ''}`}>
                                    {isCurrent && isPlaying ? (
                                      <Pause size={22} fill="#000000" color="#000000" />
                                    ) : (
                                      <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                                    )}
                                  </div>
                                  {isCurrent && isPlaying && (
                                    <div className="sp-card-eq-overlay">
                                      <span /><span /><span />
                                    </div>
                                  )}
                                  <button
                                    type="button"
                                    className={`sp-card-heart-btn ${isFav ? 'is-fav' : ''}`}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      handleToggleFavorite(track);
                                    }}
                                    title={isFav ? 'Liked' : 'Like song'}
                                  >
                                    <Heart size={15} fill={isFav ? '#ef4444' : 'none'} color={isFav ? '#ef4444' : 'currentColor'} />
                                  </button>
                                </div>
                                <span className={`sp-card-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</span>
                                <span className="sp-card-desc">{track.artist}</span>
                              </div>
                            );
                          })}
                        </ScrollableShelfRow>
                      </section>
                    )}

                    {/* Downloaded for Offline Shelf (Visible if downloads exist) */}
                    {downloadedTracks.length > 0 && (
                      <section className="sp-shelf">
                        <div className="sp-shelf-header">
                          <h2 className="sp-shelf-title">Downloaded for Offline (Zero Data)</h2>
                          <button
                            className="sp-shelf-see-all"
                            onClick={() => setActiveTab('offline')}
                          >
                            Show All ({downloadedTracks.length})
                          </button>
                        </div>
                        <ScrollableShelfRow id="dl-shelf-row">
                          {downloadedTracks.slice(0, 5).map((track, idx) => (
                            <div
                              key={`dl-shelf-${track.id || idx}`}
                              className="sp-card"
                              onClick={() => handlePlayTrack(track)}
                            >
                              <div className="sp-card-cover-wrap">
                                <img src={track.coverUrl} alt={track.title} className="sp-card-cover" />
                                <div className="sp-card-hover-play">
                                  <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                                </div>
                              </div>
                              <span className="sp-card-title">{track.title}</span>
                              <span className="sp-card-desc">{track.artist}</span>
                            </div>
                          ))}
                        </ScrollableShelfRow>
                      </section>
                    )}

                    {/* Made For You Shelf */}
                    <section className="sp-shelf">
                      <div className="sp-shelf-header">
                        <h2 className="sp-shelf-title">Made For You</h2>
                        <button
                          className="sp-shelf-see-all"
                          onClick={refreshRecommendations}
                        >
                          Refresh
                        </button>
                      </div>
                      <div className="sp-cards-row">
                        {(personalizedRecs.tracks || []).slice(0, 5).map((track, idx) => (
                          <div
                            key={`rec-shelf-${track.id || idx}`}
                            className="sp-card"
                            onClick={() => handlePlayTrack(track)}
                          >
                            <div className="sp-card-cover-wrap">
                              <img src={track.coverUrl} alt={track.title} className="sp-card-cover" />
                              <div className="sp-card-hover-play">
                                <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                              </div>
                            </div>
                            <span className="sp-card-title">{track.title}</span>
                            <span className="sp-card-desc">{track.artist}</span>
                          </div>
                        ))}
                      </div>
                    </section>
                  </div>
                )}
              </div>
            </main>
          </div>

          {/* Persistent Spotify Bottom Player Bar across 100vw */}
          {!showFullPlayer && (
            <SpotifyBottomBar
              track={currentTrack}
              isPlaying={isPlaying}
              currentTime={currentTime}
              duration={duration}
              onTogglePlay={handleTogglePlay}
              onSeek={handleSeek}
              onPrev={handlePrevTrack}
              onNext={handleNextTrack}
              isFavorite={favorites.includes(currentTrack?.id)}
              onToggleFavorite={handleToggleFavorite}
              isDownloaded={isCurrentTrackDownloaded}
              onToggleDownload={handleToggleDownload}
              isShuffle={isShuffle}
              onToggleShuffle={() => setIsShuffle(!isShuffle)}
              repeatMode={repeatMode}
              onToggleRepeat={() => {
                setRepeatMode(prev => (prev === 'off' ? 'all' : prev === 'all' ? 'one' : 'off'));
              }}
              onOpenLyrics={() => setShowLyricsView(true)}
              showLyricsView={showLyricsView}
              onOpenQueue={() => setShowQueueModal(true)}
              onOpenFullPlayer={() => setShowFullPlayer(true)}
              audioQuality={audioQuality}
              onOpenQualityModal={() => setShowQualityModal(true)}
              volume={volume}
              onVolumeChange={handleVolumeChange}
              onOpenPocketMode={() => setShowPocketMode(true)}
              onOpenSleepTimer={() => setShowSleepModal(true)}
              sleepTimerRemaining={sleepTimerRemaining}
              sleepTimerMinutes={sleepTimerMinutes}
              onOpenShortcuts={() => setShowShortcutsModal(true)}
            />
          )}
        </div>
      ) : (
        /* ====================================================================
           NATIVE RESPONSIVE MOBILE SPOTIFY LAYOUT (Edge-to-Edge)
           ==================================================================== */
        <div className="sp-mobile-app" id="spotify-mobile-app">
          {/* Native Spotify Mobile Header Bar */}
          <header className="mobile-spotify-header" id="mobile-spotify-header">
            <div className="mobile-header-user-row">
              <div className="mobile-header-left">
                <button
                  type="button"
                  className="mobile-avatar-circle"
                  title={`${activeProfile?.name || 'Your Profile'} • Taste & Backup`}
                  onClick={() => setShowTasteModal(true)}
                  style={{ background: 'rgba(255, 255, 255, 0.1)', border: 'none', cursor: 'pointer', padding: 0 }}
                >
                  <span style={{ fontSize: '15px', lineHeight: 1 }}>{activeProfile?.avatar || '🎧'}</span>
                </button>
                <div
                  className="mobile-brand-pill"
                  onClick={() => setShowSplash(true)}
                  title="Replay Dynamic Intro"
                  style={{ cursor: 'pointer' }}
                >
                  <span className="mobile-brand-name">AuraWave</span>
                  <span className="mobile-pro-badge">PRO</span>
                </div>
              </div>

              <div className="mobile-header-right">
                {/* Offline mode indicator badge */}
                <button
                  type="button"
                  className={`mobile-status-badge ${isOfflineMode ? 'offline' : 'online'}`}
                  onClick={() => handleToggleOfflineMode(!isOfflineMode)}
                  title={isOfflineMode ? 'Offline Mode active (tap to go online)' : 'Online Mode'}
                >
                  {isOfflineMode ? <WifiOff size={13} color="#eab308" /> : <Wifi size={13} color="#1DB954" />}
                  <span>{isOfflineMode ? 'Offline' : 'Online'}</span>
                </button>
              </div>
            </div>
          </header>

          {/* Main Content Viewport */}
          <main className="app-main-viewport" id="app-main-viewport">
              {showLyricsView ? (
                <SpecialLyricsView
                  track={currentTrack}
                  currentTime={currentTime}
                  duration={duration}
                  isPlaying={isPlaying}
                  onSeek={handleSeek}
                  onTogglePlay={handleTogglePlay}
                  onPrev={handlePrevTrack}
                  onNext={handleNextTrack}
                  onClose={() => setShowLyricsView(false)}
                  onOpenPocketMode={() => setShowPocketMode(true)}
                  isMobile={true}
                  isFavorite={favorites.includes(currentTrack?.id)}
                  onToggleFavorite={handleToggleFavorite}
                />
              ) : selectedPlaylist ? (
                <PlaylistDetailView
                  playlist={selectedPlaylist}
                  tracks={tracks}
                  currentTrackId={currentTrack?.id}
                  isPlaying={isPlaying}
                  onPlayTrack={handlePlayTrack}
                  onBack={() => setSelectedPlaylist(null)}
                  onDeletePlaylist={handleDeletePlaylist}
                  onRenamePlaylist={handleRenamePlaylist}
                  onRemoveTrackFromPlaylist={handleRemoveTrackFromPlaylist}
                  favorites={favorites}
                  onToggleFavorite={handleToggleFavorite}
                />
              ) : activeTab === 'search' ? (
                <SearchModal
                  isMobile={true}
                  onSelectTrack={track => {
                    setTracks(prev => {
                      if (!prev.some(t => t.id === track.id || (t.title === track.title && t.artist === track.artist))) {
                        return [track, ...prev];
                      }
                      return prev;
                    });
                    handlePlayTrack(track);
                  }}
                  currentTrackId={currentTrack?.id}
                  currentTrackTitle={currentTrack?.title}
                  currentTrackArtist={currentTrack?.artist}
                  currentTrack={currentTrack}
                  isPlaying={isPlaying}
                  onOpenAddToPlaylist={track => {
                    setTrackForPlaylist(track);
                    setShowAddToPlaylistModal(true);
                  }}
                  onAddToQueue={handleAddToQueue}
                  onToggleDownload={handleToggleDownload}
                  downloadedTrackIds={downloadedTrackIds}
                  favorites={favorites}
                  onToggleFavorite={handleToggleFavorite}
                />
              ) : activeTab === 'offline' ? (
                <DownloadedView
                  isMobile={true}
                  downloadedTracks={downloadedTracks}
                  currentTrackId={currentTrack?.id}
                  isPlaying={isPlaying}
                  onPlayTrack={handlePlayTrack}
                  onTogglePlay={handleTogglePlay}
                  onRemoveDownload={async (id) => {
                    await offlineStorage.removeDownloadedTrack(id);
                    const updated = await offlineStorage.getDownloadedTracks();
                    setDownloadedTracks(updated);
                    const stats = await offlineStorage.getStorageStats();
                    setStorageStats(stats);
                  }}
                  isOfflineMode={isOfflineMode}
                  onToggleOfflineMode={handleToggleOfflineMode}
                  storageStats={storageStats}
                  onOpenSearch={() => setActiveTab('search')}
                  onDownloadAllFavorites={handleDownloadAllFavorites}
                />
              ) : activeTab === 'library' ? (
                <SpotifyLibraryView
                  playlists={playlists}
                  favorites={favorites}
                  downloadedTracks={downloadedTracks}
                  storageStats={storageStats}
                  onSelectPlaylist={handleSelectPlaylist}
                  onOpenCreatePlaylist={() => setShowCreatePlaylistModal(true)}
                  onOpenImportSpotify={() => setShowSpotifyModal(true)}
                  onOpenOffline={() => setActiveTab('offline')}
                  likedSongsPlaylist={likedSongsPlaylist}
                  isMobile={true}
                />
              ) : (
                /* Mobile Home */
                <div className="tab-home-view">
                  <div className="home-top-header">
                    <div className="user-greeting-row">
                      <div>
                        <span className="app-badge">AURAWAVE • AD-FREE</span>
                        <h1 className="greeting-text">{greeting}</h1>
                      </div>
                      <div className="header-action-buttons">
                        <button
                          className="header-icon-pill-btn"
                          onClick={() => setShowCreatePlaylistModal(true)}
                        >
                          <Plus size={16} />
                          <span>Playlist</span>
                        </button>
                        <button
                          className="spotify-import-cta-btn"
                          onClick={() => setShowSpotifyModal(true)}
                        >
                          <span>Import</span>
                        </button>
                      </div>
                    </div>
                  </div>


                  {/* 1. Mobile Curated Sonic Journeys Hero Banner */}
                  <SonicHeroBanner
                    onPlayAuraMix={() => {
                      const targetTrack = tracks.find(t => t.id === 'track-kesariya') || tracks[0];
                      handlePlayTrack(targetTrack, tracks.slice(1));
                    }}
                    onSaveToLibrary={() => {
                      showToast('✨ Curated Sonic Journeys saved to Your Library!');
                    }}
                    currentTrack={currentTrack}
                    isPlaying={isPlaying}
                    onTogglePlay={handleTogglePlay}
                    tracks={tracks}
                    queue={queue}
                  />

                  {/* 2. Mobile Recently Played 2x3 Grid with Verified Badges */}
                  <RecentlyPlayedGrid
                    tracks={recentlyPlayed.length >= 6 ? recentlyPlayed : DEFAULT_RECENTLY_PLAYED}
                    currentTrackId={currentTrack?.id}
                    isPlaying={isPlaying}
                    onPlayTrack={handlePlayTrack}
                    onViewAll={() => setActiveTab('library')}
                  />

                  {/* 3. Mobile Recommended For You Carousel Shelf with Format Badges */}
                  <RecommendedShelf
                    onPlayItem={(item) => {
                      handleSelectTab('search');
                    }}
                  />

                  {/* Dedicated Mobile Shelf: 🔥 Frequently Played • On Repeat */}
                  {frequentlyPlayed.length > 0 && (
                    <section className="home-section">
                      <div className="section-header-row">
                        <div className="section-title-wrap">
                          <h2 className="section-title">🔥 On Repeat • Heavy Rotation</h2>
                          <span className="sp-count-pill">{frequentlyPlayed.length} songs</span>
                        </div>
                      </div>
                      <div className="playlists-horizontal-scroll">
                        {frequentlyPlayed.slice(0, 8).map((track, idx) => {
                          const isCurrent = currentTrack?.id === track.id;
                          const playCount = track.playCount || storageService.getTrackPlayCount(track);
                          return (
                            <div
                              key={`mob-freq-${track.id || idx}`}
                              className={`playlist-card ${isCurrent ? 'active' : ''}`}
                              onClick={() => handlePlayTrack(track, frequentlyPlayed.slice(idx + 1))}
                            >
                              <div className="pl-cover-wrap">
                                <img src={track.coverUrl} alt={track.title} className="pl-cover" />
                                {playCount > 1 && (
                                  <div className="sp-card-playcount-badge" style={{ top: '6px', right: '6px', padding: '2px 6px', fontSize: '10px' }}>
                                    <Flame size={10} />
                                    <span>{playCount}x</span>
                                  </div>
                                )}
                                {isCurrent && isPlaying && (
                                  <div className="sp-card-eq-overlay">
                                    <span /><span /><span />
                                  </div>
                                )}
                              </div>
                              <h4 className={`pl-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</h4>
                              <p className="pl-desc">{track.artist}</p>
                            </div>
                          );
                        })}
                      </div>
                    </section>
                  )}

                  {/* Dedicated Mobile Shelf: ✨ Made for [Name] */}
                  {profileMix && profileMix.tracks && profileMix.tracks.length > 0 && (
                    <section className="home-section">
                      <div className="section-header-row">
                        <div className="section-title-wrap">
                          <h2 className="section-title">✨ {profileMix.tag}</h2>
                          <span className="sp-count-pill">For You</span>
                        </div>
                      </div>
                      <div className="playlists-horizontal-scroll">
                        {profileMix.tracks.slice(0, 8).map((track, idx) => {
                          const isCurrent = currentTrack?.id === track.id;
                          return (
                            <div
                              key={`mob-profile-${track.id || idx}`}
                              className={`playlist-card ${isCurrent ? 'active' : ''}`}
                              onClick={() => handlePlayTrack(track, profileMix.tracks.slice(idx + 1))}
                            >
                              <div className="pl-cover-wrap">
                                <img src={track.coverUrl} alt={track.title} className="pl-cover" />
                                <div className={`pl-card-play-overlay ${isCurrent && isPlaying ? 'playing' : ''}`}>
                                  {isCurrent && isPlaying ? (
                                    <Pause size={18} fill="#000000" color="#000000" />
                                  ) : (
                                    <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                                  )}
                                </div>
                                {isCurrent && isPlaying && (
                                  <div className="sp-card-eq-overlay">
                                    <span /><span /><span />
                                  </div>
                                )}
                              </div>
                              <h4 className={`pl-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</h4>
                              <p className="pl-desc">{track.artist}</p>
                            </div>
                          );
                        })}
                      </div>
                    </section>
                  )}

                  {/* Dedicated Mobile Shelf: ❤️ Based on Your Likes */}
                  {likesRecs && likesRecs.tracks && likesRecs.tracks.length > 0 && (
                    <section className="home-section">
                      <div className="section-header-row">
                        <div className="section-title-wrap">
                          <h2 className="section-title">❤️ {likesRecs.tag || 'Based on Your Likes'}</h2>
                          <span className="sp-count-pill">For You</span>
                        </div>
                      </div>
                      <div className="playlists-horizontal-scroll">
                        {likesRecs.tracks.slice(0, 8).map((track, idx) => {
                          const isCurrent = currentTrack?.id === track.id;
                          return (
                            <div
                              key={`mob-likes-${track.id || idx}`}
                              className={`playlist-card ${isCurrent ? 'active' : ''}`}
                              onClick={() => handlePlayTrack(track, likesRecs.tracks.slice(idx + 1))}
                            >
                              <div className="pl-cover-wrap">
                                <img src={track.coverUrl} alt={track.title} className="pl-cover" />
                                <div className="sp-card-like-badge" style={{ top: '6px', right: '6px', padding: '2px 6px', fontSize: '10px' }}>
                                  <Heart size={9} fill="#ec4899" />
                                  <span>Liked Taste</span>
                                </div>
                                {isCurrent && isPlaying && (
                                  <div className="sp-card-eq-overlay">
                                    <span /><span /><span />
                                  </div>
                                )}
                              </div>
                              <h4 className={`pl-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</h4>
                              <p className="pl-desc">{track.artist}</p>
                            </div>
                          );
                        })}
                      </div>
                    </section>
                  )}

                  {/* Dedicated Mobile Shelf: 🕒 Jump Back In (Recently Played) */}
                  {recentlyPlayed.length > 0 && (
                    <section className="home-section">
                      <div className="section-header-row">
                        <div className="section-title-wrap">
                          <h2 className="section-title">🕒 Jump Back In</h2>
                          <span className="sp-count-pill">Recent</span>
                        </div>
                      </div>
                      <div className="playlists-horizontal-scroll">
                        {recentlyPlayed.slice(0, 8).map((track, idx) => {
                          const isCurrent = currentTrack?.id === track.id;
                          return (
                            <div
                              key={`mob-recent-${track.id || idx}`}
                              className={`playlist-card ${isCurrent ? 'active' : ''}`}
                              onClick={() => handlePlayTrack(track, recentlyPlayed.slice(idx + 1), true)}
                            >
                              <div className="pl-cover-wrap">
                                <img src={track.coverUrl} alt={track.title} className="pl-cover" />
                                {isCurrent && isPlaying && (
                                  <div className="sp-card-eq-overlay">
                                    <span /><span /><span />
                                  </div>
                                )}
                              </div>
                              <h4 className={`pl-title ${isCurrent ? 'active-title' : ''}`}>{track.title}</h4>
                              <p className="pl-desc">{track.artist}</p>
                            </div>
                          );
                        })}
                      </div>
                    </section>
                  )}
                </div>
              )}
            </main>

            {/* Mobile Mini-Player (Hidden when Fullscreen or Lyrics view is open) */}
            {currentTrack && !showFullPlayer && !showLyricsView && (
              <MiniPlayer
                track={currentTrack}
                isPlaying={isPlaying}
                currentTime={currentTime}
                duration={duration}
                onTogglePlay={handleTogglePlay}
                onNext={handleNextTrack}
                onOpenFullPlayer={() => setShowFullPlayer(true)}
                onOpenLyrics={() => setShowLyricsView(true)}
                isFavorite={favorites.includes(currentTrack.id)}
                onToggleFavorite={handleToggleFavorite}
                isDownloaded={isCurrentTrackDownloaded}
                onToggleDownload={handleToggleDownload}
                onOpenPocketMode={() => setShowPocketMode(true)}
              />
            )}

            {/* Mobile Bottom Navigation Bar (Hidden when Fullscreen or Lyrics view is open) */}
            {!showFullPlayer && !showLyricsView && (
              <BottomNav
                activeTab={activeTab}
                onSelectTab={handleSelectTab}
                downloadedCount={downloadedTracks.length}
              />
            )}
        </div>
      )}

      {/* ====================================================================
          SHARED MODALS & OVERLAYS
          ==================================================================== */}

      {/* Audio Quality Modal (320kbps Hi-Fi Master) */}
      <AudioQualityModal
        isOpen={showQualityModal}
        onClose={() => setShowQualityModal(false)}
        currentQuality={audioQuality}
        onSelectQuality={(qId) => {
          setAudioQuality(qId);
          showToast(`✨ Quality set to: ${qId === '320k' ? '320kbps Hi-Fi Master' : (qId === '160k' ? '160kbps High' : '96kbps Normal')}`);
        }}
      />


      {/* Full Screen Player Modal */}
      {showFullPlayer && currentTrack && (
        <FullScreenPlayer
          track={currentTrack}
          isPlaying={isPlaying}
          currentTime={currentTime}
          duration={duration}
          isFavorite={favorites.includes(currentTrack.id)}
          isShuffle={isShuffle}
          repeatMode={repeatMode}
          onTogglePlay={handleTogglePlay}
          onSeek={handleSeek}
          onPrev={handlePrevTrack}
          onNext={handleNextTrack}
          onToggleFavorite={handleToggleFavorite}
          onToggleShuffle={() => setIsShuffle(!isShuffle)}
          onToggleRepeat={() => {
            setRepeatMode(prev => (prev === 'off' ? 'all' : prev === 'all' ? 'one' : 'off'));
          }}
          onOpenLyrics={() => {
            setShowFullPlayer(false);
            setShowLyricsView(true);
          }}
          onOpenAddToPlaylist={track => {
            setTrackForPlaylist(track);
            setShowAddToPlaylistModal(true);
          }}
          isDownloaded={isCurrentTrackDownloaded}
          onToggleDownload={handleToggleDownload}
          audioQuality={audioQuality}
          onOpenQuality={() => setShowQualityModal(true)}
          onClose={() => setShowFullPlayer(false)}
          onOpenPocketMode={() => setShowPocketMode(true)}
          onOpenSleepTimer={() => setShowSleepModal(true)}
          sleepTimerMinutes={sleepTimerMinutes}
          queue={queue}
          onPlayQueueTrack={(qTrack, idx) => {
            const remaining = queue.filter((_, i) => i !== idx);
            handlePlayTrack(qTrack, remaining);
          }}
          onRemoveFromQueue={handleRemoveFromQueue}
          onClearQueue={handleClearQueue}
          onShuffleQueue={handleShuffleQueue}
        />
      )}

      {/* Import Spotify Playlist Modal */}
      <ImportSpotifyModal
        isOpen={showSpotifyModal}
        onClose={() => setShowSpotifyModal(false)}
        onImportSuccess={handleSpotifyImportSuccess}
      />

      {/* Create Custom Playlist Modal */}
      <CreatePlaylistModal
        isOpen={showCreatePlaylistModal}
        onClose={() => setShowCreatePlaylistModal(false)}
        onCreate={handleCreatePlaylist}
      />

      {/* Add Song to Playlist Modal */}
      <AddToPlaylistModal
        isOpen={showAddToPlaylistModal}
        onClose={() => {
          setShowAddToPlaylistModal(false);
          setTrackForPlaylist(null);
        }}
        track={trackForPlaylist}
        playlists={playlists}
        onAddToPlaylist={handleAddToPlaylist}
        onOpenCreateNew={() => {
          setShowAddToPlaylistModal(false);
          setShowCreatePlaylistModal(true);
        }}
      />

      {/* Up Next Queue Drawer */}
      <QueueDrawer
        isOpen={showQueueModal}
        onClose={() => setShowQueueModal(false)}
        queue={queue}
        currentTrack={currentTrack}
        isPlaying={isPlaying}
        onPlayQueueTrack={(track, idx) => {
          const remaining = queue.filter((_, i) => i !== idx);
          handlePlayTrack(track, remaining);
        }}
        onPlayFromQueue={(track, idx) => {
          const remaining = queue.filter((_, i) => i !== idx);
          handlePlayTrack(track, remaining);
        }}
        onRemoveFromQueue={handleRemoveFromQueue}
        onReorderQueue={handleReorderQueue}
        onClearQueue={handleClearQueue}
        onShuffleQueue={handleShuffleQueue}
        onPopulateRecommendations={handlePopulateRecommendations}
      />

      {/* OLED Pocket Mode (Fake Screen-Off for 100% Uninterrupted Background Playback) */}
      <PocketLockOverlay
        isOpen={showPocketMode}
        onClose={() => setShowPocketMode(false)}
        track={currentTrack}
        isPlaying={isPlaying}
        currentTime={currentTime}
        duration={duration}
        onTogglePlay={handleTogglePlay}
        onPrev={handlePrevTrack}
        onNext={handleNextTrack}
      />

      {/* User Music Taste Profile & Listening Stats Modal */}
      <TasteProfileModal
        isOpen={showTasteModal}
        onClose={() => setShowTasteModal(false)}
        tasteProfile={tasteProfile}
        frequentlyPlayed={frequentlyPlayed}
        onPlayTrack={handlePlayTrack}
        showToast={showToast}
        onDataChanged={() => {
          setFavorites(storageService.getFavorites());
          setPlaylists(storageService.getPlaylists());
          setRecentlyPlayed(storageService.getRecentHistory(30));
          refreshRecommendations();
        }}
        onProfileUpdated={(updated) => {
          setActiveProfile(updated);
          refreshRecommendations();
        }}
      />

      {/* First-time User Welcome Onboarding Modal */}
      <NewUserOnboardingModal
        isOpen={showOnboarding}
        onClose={() => setShowOnboarding(false)}
        onComplete={(newProfile) => {
          setActiveProfile(newProfile);
          refreshRecommendations();
          setShowOnboarding(false);
        }}
        showToast={showToast}
      />

      {/* Sleep Timer Modal */}
      <SleepTimerModal
        isOpen={showSleepModal}
        onClose={() => setShowSleepModal(false)}
        activeTimerMinutes={sleepTimerMinutes}
        remainingSeconds={sleepTimerRemaining}
        onSetTimer={handleSetSleepTimer}
        onCancelTimer={handleCancelSleepTimer}
      />

      {/* Keyboard Shortcuts Cheat Sheet Modal */}
      <KeyboardShortcutsModal
        isOpen={showShortcutsModal}
        onClose={() => setShowShortcutsModal(false)}
      />
    </div>
  );
}
