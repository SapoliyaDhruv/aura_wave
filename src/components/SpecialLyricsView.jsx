import React, { useEffect, useRef, useState, useMemo } from 'react';
import {
  ChevronDown,
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Sparkles,
  Maximize2,
  Minimize2,
  Sliders,
  RotateCcw,
  Volume2,
  Activity,
  Share2,
  Users,
  CheckCircle2,
  X,
  Moon,
  Heart
} from 'lucide-react';
import { storageService } from '../services/storageService';
import { lyricsAutoSync } from '../services/lyricsAutoSync';
import { normalizeTrackName } from '../data/curatedTracks';
import LyricsSyncStudioModal from './LyricsSyncStudioModal';

function formatTime(seconds) {
  if (!seconds || isNaN(seconds) || seconds < 0) return '0:00';
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

export default function SpecialLyricsView({
  track,
  currentTime = 0,
  duration = 0,
  isPlaying = false,
  onSeek,
  onTogglePlay,
  onPrev,
  onNext,
  onClose,
  onOpenPocketMode,
  isMobile = false,
  isFavorite = false,
  onToggleFavorite
}) {
  const containerRef = useRef(null);
  const activeLineRef = useRef(null);
  const [userScrolled, setUserScrolled] = useState(false);
  const scrollTimeoutRef = useRef(null);
  const [isVocalIsolation, setIsVocalIsolation] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [feedbackToast, setFeedbackToast] = useState(null);
  const feedbackTimeoutRef = useRef(null);
  const [liveVaultLyrics, setLiveVaultLyrics] = useState(null);
  const [showOffsetPanel, setShowOffsetPanel] = useState(false);
  const [showSyncStudio, setShowSyncStudio] = useState(false);

  // Listen for global LRCLIB publish confirmation
  useEffect(() => {
    const handlePublished = (e) => {
      showToastMsg('🎉 Lyrics published to global LRCLIB!');
    };
    window.addEventListener('aurawave_lyrics_published', handlePublished);
    return () => window.removeEventListener('aurawave_lyrics_published', handlePublished);
  }, []);

  // Reset live lyrics when track changes
  useEffect(() => {
    setLiveVaultLyrics(null);
  }, [track?.id, track?.title]);

  // Listen for background auto-sync completion to immediately display lyrics
  useEffect(() => {
    const handleSynced = (e) => {
      const detail = e.detail;
      if (track && (detail.trackId === track.id || normalizeTrackName(detail.trackTitle) === normalizeTrackName(track.title))) {
        setLiveVaultLyrics(detail.lines);
        showToastMsg('✨ Synced lyrics ready!');
      }
    };
    window.addEventListener('aurawave_lyrics_synced', handleSynced);
    return () => window.removeEventListener('aurawave_lyrics_synced', handleSynced);
  }, [track]);

  // Proactively fetch & calibrate synced lyrics as soon as lyrics view opens
  useEffect(() => {
    if (track) {
      const existing = lyricsAutoSync.getEffectiveLyrics(track);
      if (existing && existing.length > 2 && typeof existing[0]?.time === 'number' && !String(existing[0]?.text || '').startsWith('Listening to ')) {
        setLiveVaultLyrics(existing);
      } else {
        lyricsAutoSync.recordPlayAndAutoSync(track).then(() => {
          const fresh = lyricsAutoSync.getEffectiveLyrics(track);
          if (fresh && fresh.length > 2 && typeof fresh[0]?.time === 'number' && !String(fresh[0]?.text || '').startsWith('Listening to ')) {
            setLiveVaultLyrics(fresh);
            showToastMsg('✨ Synced lyrics ready!');
          }
        }).catch(() => {});
      }
    }
  }, [track?.id, track?.title]);

  // Sync calibration offset per track (persisted in storageService)
  const [syncOffset, setSyncOffset] = useState(() => {
    return track ? storageService.getLyricsOffset(track.id) : 0;
  });

  useEffect(() => {
    if (track) {
      setSyncOffset(storageService.getLyricsOffset(track.id));
    }
  }, [track?.id]);

  const showToastMsg = (msg) => {
    if (feedbackTimeoutRef.current) clearTimeout(feedbackTimeoutRef.current);
    setFeedbackToast(msg);
    feedbackTimeoutRef.current = setTimeout(() => {
      setFeedbackToast(null);
    }, 2800);
  };

  // Adjust offset manually
  const handleAdjustOffset = (delta) => {
    const newOffset = parseFloat((syncOffset + delta).toFixed(2));
    setSyncOffset(newOffset);
    if (track) {
      storageService.setLyricsOffset(track.id, newOffset);
    }
    showToastMsg(`Timing offset: ${newOffset > 0 ? '+' : ''}${newOffset}s`);
  };

  const effectiveTime = Math.max(0, currentTime + syncOffset);

  // Check if lyrics are pre-baked with timestamps
  const isPreBakedSync = useMemo(() => {
    return Array.isArray(track?.lyrics) && track.lyrics.length > 2 && typeof track.lyrics[0]?.time === 'number';
  }, [track]);

  const lyrics = useMemo(() => {
    if (liveVaultLyrics && liveVaultLyrics.length > 0) return liveVaultLyrics;
    const lines = lyricsAutoSync.getEffectiveLyrics(track);
    if (lines && lines.length > 0) return lines;
    
    // 1st play preview lines while background sync engine aligns vocals
    const t = track?.title || 'This Track';
    const a = track?.artist || 'AuraWave Master';
    return [
      { time: 0, text: `Listening to ${t}` },
      { time: 5, text: `By ${a}` },
      { time: 10, text: 'Auto-Sync Engine is preparing vocal timing in the background...' },
      { time: 16, text: 'Next time you play this song, full synchronized lyrics will appear!' },
      { time: 24, text: 'Feel the rhythm, melodies, and soundstage acoustically flowing' },
      { time: 32, text: `Enjoy ${t} in Hi-Fi master studio sound` }
    ];
  }, [track, liveVaultLyrics]);

  const hasRealSyncedLyrics = useMemo(() => {
    return Array.isArray(lyrics) && lyrics.length > 2 && typeof lyrics[0]?.time === 'number' && !String(lyrics[0]?.text || '').startsWith('Listening to ');
  }, [lyrics]);

  // Check if learned from 2nd-play Auto-Sync Vault
  const isLearnedSync = useMemo(() => {
    if (liveVaultLyrics && liveVaultLyrics.length > 0) return true;
    return lyricsAutoSync.isLearnedSync(track) || hasRealSyncedLyrics;
  }, [track, liveVaultLyrics, hasRealSyncedLyrics]);

  const isFullySynced = isPreBakedSync || isLearnedSync || hasRealSyncedLyrics;

  // Determine active lyric line index based on effectiveTime
  const activeIndex = useMemo(() => {
    if (!lyrics || lyrics.length === 0) return -1;
    let idx = -1;
    for (let i = 0; i < lyrics.length; i++) {
      if (effectiveTime >= lyrics[i].time) {
        idx = i;
      } else {
        break;
      }
    }
    return idx === -1 ? 0 : idx;
  }, [lyrics, effectiveTime]);

  // Smooth auto-scroll: centers active line
  useEffect(() => {
    if (!userScrolled && activeLineRef.current) {
      activeLineRef.current.scrollIntoView({
        behavior: 'smooth',
        block: 'center'
      });
    }
  }, [activeIndex, userScrolled]);

  const handleUserScroll = () => {
    setUserScrolled(true);
    if (scrollTimeoutRef.current) clearTimeout(scrollTimeoutRef.current);
    scrollTimeoutRef.current = setTimeout(() => {
      setUserScrolled(false);
    }, 4500);
  };

  const currentLine = lyrics[activeIndex] || lyrics[0];
  const nextLine = lyrics[activeIndex + 1];
  const lineProgress = useMemo(() => {
    if (!currentLine) return 0;
    const start = currentLine.time;
    const end = nextLine ? nextLine.time : start + 6;
    const elapsed = Math.max(0, effectiveTime - start);
    return Math.min(100, (elapsed / (end - start)) * 100);
  }, [currentLine, nextLine, effectiveTime]);

  // Split active line words for kinetic karaoke word-fill animation
  const activeWords = useMemo(() => {
    if (!currentLine?.text) return [];
    return currentLine.text.trim().split(/\s+/);
  }, [currentLine]);

  const primaryColor = track?.themeColor || '#1DB954';
  const secondaryColor = track?.secondaryColor || '#8b5cf6';
  const safeDuration = (duration && duration > 0) ? duration : (track?.duration || 180);
  const progressPercent = Math.min(100, Math.max(0, (currentTime / safeDuration) * 100));

  return (
    <div
      className={`sp-lyrics-stage modern-live-lyrics-view ${isFullscreen ? 'lyrics-fullscreen' : ''}`}
      id="lyrics-stage-container"
      style={{
        '--track-accent': primaryColor,
        '--track-secondary': secondaryColor
      }}
    >
      {/* Floating feedback toast */}
      {feedbackToast && (
        <div className="lyrics-toast-pill">
          <span>{feedbackToast}</span>
        </div>
      )}

      {/* Atmospheric ambient moving orbs */}
      <div className="lyrics-ambient-backdrop" />
      <div className="lyrics-ambient-orb orb-1" />
      <div className="lyrics-ambient-orb orb-2" />

      {/* Modern Floating Header Bar */}
      <header className="modern-lyrics-top-nav">
        <div className="lyrics-nav-left">
          {onClose && (
            <button
              className="lyrics-round-btn close-action"
              onClick={onClose}
              title="Close Lyrics"
              aria-label="Close Lyrics"
            >
              <ChevronDown size={22} />
            </button>
          )}

          <div className="lyrics-nav-track-thumb-box hide-small-mobile">
            <img
              src={track?.coverUrl || "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80"}
              alt={track?.title || "Track"}
              className="lyrics-nav-thumb-img"
            />
          </div>

          <div className="lyrics-nav-track-meta">
            <h4 className="lyrics-nav-title">{track?.title || 'AuraWave Master'}</h4>
            <div className="lyrics-nav-sub-row">
              <span className="lyrics-nav-artist">{track?.artist || 'Unknown Artist'}</span>
              <span className="lyrics-sync-badge-pill">
                <span className="sync-pulse-dot" />
                <span>{isLearnedSync ? '2nd-Play Synced' : (isFullySynced ? 'Live Synced' : 'Auto-Syncing')}</span>
              </span>
            </div>
          </div>
        </div>

        <div className="lyrics-nav-right">
          {/* Timing Calibration Offset Stepper (Desktop only) */}
          <div className="lyrics-offset-pill-group hide-mobile">
            <button
              className="offset-stepper-btn"
              onClick={() => handleAdjustOffset(-0.5)}
              title="Shift lyrics 0.5s earlier"
            >
              -0.5s
            </button>
            <span className="offset-display-value">
              {syncOffset > 0 ? `+${syncOffset}` : syncOffset}s
            </span>
            <button
              className="offset-stepper-btn"
              onClick={() => handleAdjustOffset(0.5)}
              title="Shift lyrics 0.5s later"
            >
              +0.5s
            </button>
          </div>

          {/* Mobile calibration toggle button */}
          <button
            className={`lyrics-round-btn hide-desktop ${showOffsetPanel ? 'active-timing' : ''}`}
            onClick={() => setShowOffsetPanel(prev => !prev)}
            title="Adjust Timing Offset"
            aria-label="Adjust Timing Offset"
          >
            <Sliders size={16} />
          </button>

          {/* AI Vocal Isolation Toggle (Desktop only) */}
          <button
            className={`lyrics-pill-action-btn hide-mobile ${isVocalIsolation ? 'is-active' : ''}`}
            onClick={() => {
              setIsVocalIsolation(!isVocalIsolation);
              showToastMsg(isVocalIsolation ? 'Vocal Isolation: OFF' : 'Vocal Isolation: ON (AI Enhancer)');
            }}
            title="Toggle Vocal Focus"
          >
            <Activity size={15} />
            <span>Vocal Focus</span>
          </button>

          {/* Fullscreen Toggle (Desktop only) */}
          <button
            className="lyrics-round-btn hide-mobile"
            onClick={() => setIsFullscreen(!isFullscreen)}
            title={isFullscreen ? 'Exit Fullscreen' : 'Fullscreen'}
          >
            {isFullscreen ? <Minimize2 size={17} /> : <Maximize2 size={17} />}
          </button>

          {/* Tap-to-Sync Studio Button */}
          <button
            className="lyrics-pill-action-btn sync-studio-trigger-btn"
            onClick={() => setShowSyncStudio(true)}
            title="Open Tap-to-Sync Studio & LRCLIB Publisher"
          >
            <Sparkles size={14} color="#c084fc" />
            <span className="hide-small-mobile">Sync Studio</span>
          </button>

          {/* Exit / Close Button */}
          {onClose && (
            <button
              className="lyrics-round-btn exit-btn"
              onClick={onClose}
              title="Close Lyrics View"
              aria-label="Close Lyrics View"
            >
              <X size={18} />
            </button>
          )}
        </div>
      </header>

      {/* Floating Offset Drawer (Mobile) */}
      {showOffsetPanel && (
        <div className="mobile-lyrics-offset-drawer">
          <span className="drawer-title">Vocal Timing:</span>
          <div className="lyrics-offset-pill-group">
            <button className="offset-stepper-btn" onClick={() => handleAdjustOffset(-0.5)}>-0.5s</button>
            <span className="offset-display-value">{syncOffset > 0 ? `+${syncOffset}` : syncOffset}s</span>
            <button className="offset-stepper-btn" onClick={() => handleAdjustOffset(0.5)}>+0.5s</button>
          </div>
          <button
            className="offset-reset-chip"
            onClick={() => {
              setSyncOffset(0);
              if (track) storageService.setLyricsOffset(track.id, 0);
              showToastMsg('Timing offset reset to 0s');
            }}
          >
            Reset (0s)
          </button>
        </div>
      )}

      {/* Main Fluid Kinetic Lyrics Canvas */}
      <main
        className="modern-lyrics-canvas"
        ref={containerRef}
        onScroll={handleUserScroll}
      >
        <div className="lyrics-flow-container">
          {/* Notice Banner if in 1st-Play Background Auto-Sync */}
          {!isFullySynced && (
            <div className="lyrics-autosync-soft-pill">
              <Sparkles size={16} color="#c084fc" />
              <span>Calibrating vocal alignment in background</span>
              <button
                className="lyrics-banner-sync-link"
                onClick={() => setShowSyncStudio(true)}
              >
                Tap-to-Sync Studio →
              </button>
            </div>
          )}

          {lyrics.map((line, idx) => {
            const isActive = idx === activeIndex;
            const isPast = idx < activeIndex;
            const isUpcoming = idx > activeIndex;

            if (isActive) {
              return (
                <div
                  key={`line-${idx}`}
                  ref={activeLineRef}
                  className="kinetic-lyric-row active-singing-row"
                  onClick={() => onSeek && onSeek(line.time)}
                >
                  <p className="kinetic-active-sentence">
                    {activeWords.map((word, wIdx) => {
                      const wordProgress = (wIdx / activeWords.length) * 100;
                      const isWordLit = lineProgress >= wordProgress;
                      return (
                        <React.Fragment key={wIdx}>
                          <span className={`karaoke-word ${isWordLit ? 'lit-word' : ''}`}>
                            {word}
                          </span>
                          {' '}
                        </React.Fragment>
                      );
                    })}
                  </p>
                  {nextLine && (
                    <p className="kinetic-upcoming-preview">{nextLine.text}</p>
                  )}
                </div>
              );
            }

            return (
              <div
                key={`line-${idx}`}
                className={`kinetic-lyric-row ${isPast ? 'past-row' : 'upcoming-row'}`}
                onClick={() => onSeek && onSeek(line.time)}
                title="Tap to jump playback here"
              >
                <p className="kinetic-inactive-sentence">{line.text}</p>
              </div>
            );
          })}
        </div>
      </main>

      {/* Modern Floating Playback Controller Dock at Bottom */}
      <footer className="modern-lyrics-floating-dock glass-sheet">
        <div className="lyrics-dock-seeker-row">
          <span className="dock-time">{formatTime(currentTime)}</span>
          <div className="dock-progress-track" onClick={(e) => {
            const rect = e.currentTarget.getBoundingClientRect();
            const clickRatio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
            if (onSeek) onSeek(clickRatio * safeDuration);
          }}>
            <div className="dock-progress-fill" style={{ width: `${progressPercent}%` }} />
          </div>
          <span className="dock-time">{formatTime(safeDuration)}</span>
        </div>

        <div className="lyrics-dock-controls-row">
          {onToggleFavorite && (
            <button
              className={`dock-icon-btn ${isFavorite ? 'is-fav' : ''}`}
              onClick={onToggleFavorite}
              title="Favorite track"
            >
              <Heart size={20} fill={isFavorite ? '#ef4444' : 'none'} color={isFavorite ? '#ef4444' : '#a1a1aa'} />
            </button>
          )}

          {onPrev && (
            <button
              className="dock-icon-btn"
              onClick={onPrev}
              title="Previous Track"
            >
              <SkipBack size={22} fill="currentColor" />
            </button>
          )}

          {onTogglePlay && (
            <button
              className="dock-play-pause-btn"
              onClick={onTogglePlay}
              title={isPlaying ? 'Pause' : 'Play'}
            >
              {isPlaying ? <Pause size={24} fill="currentColor" /> : <Play size={24} fill="currentColor" style={{ marginLeft: '2px' }} />}
            </button>
          )}

          {onNext && (
            <button
              className="dock-icon-btn"
              onClick={onNext}
              title="Next Track"
            >
              <SkipForward size={22} fill="currentColor" />
            </button>
          )}

          {onOpenPocketMode && (
            <button
              className="dock-icon-btn"
              onClick={onOpenPocketMode}
              title="Pocket Mode"
            >
              <Moon size={19} color="#06b6d4" />
            </button>
          )}
        </div>
      </footer>

      {/* Tap-to-Sync Studio & LRCLIB Publisher Modal */}
      <LyricsSyncStudioModal
        isOpen={showSyncStudio}
        onClose={() => setShowSyncStudio(false)}
        track={track}
        currentTime={currentTime}
        duration={duration}
        isPlaying={isPlaying}
        onSeek={onSeek}
        onTogglePlay={onTogglePlay}
        initialPlainLines={lyrics.map(l => l.text)}
      />
    </div>
  );
}
