import React, { useMemo, useState, useRef } from 'react';
import {
  ChevronDown,
  Heart,
  Shuffle,
  SkipBack,
  Play,
  Pause,
  SkipForward,
  Repeat,
  Repeat1,
  Mic2,
  Share2,
  ListMusic,
  Plus,
  ArrowDownCircle,
  Sparkles,
  Moon,
  Timer,
  Sliders,
  MoreVertical,
  X,
  Trash2,
  Music,
  ArrowLeft,
  ChevronRight,
  ChevronLeft
} from 'lucide-react';
import VisualizerCanvas from './VisualizerCanvas';

function formatTime(seconds) {
  if (isNaN(seconds) || seconds < 0) return '0:00';
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

function formatDuration(seconds) {
  if (!seconds || isNaN(seconds)) return '3:15';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

export default function FullScreenPlayer({
  track,
  isPlaying,
  currentTime,
  duration,
  isFavorite,
  isShuffle,
  repeatMode,
  onTogglePlay,
  onSeek,
  onPrev,
  onNext,
  onToggleFavorite,
  onToggleShuffle,
  onToggleRepeat,
  onOpenLyrics,
  onOpenQueue,
  onOpenAddToPlaylist,
  isDownloaded,
  onToggleDownload,
  audioQuality = '320k',
  onOpenQuality,
  onClose,
  onOpenPocketMode,
  onOpenSleepTimer,
  sleepTimerMinutes,
  // Inline queue props
  queue = [],
  onPlayQueueTrack,
  onRemoveFromQueue,
  onClearQueue,
  onShuffleQueue,
}) {
  const [isSeeking, setIsSeeking] = useState(false);
  const [seekVal, setSeekVal] = useState(0);
  const [showOptionsMenu, setShowOptionsMenu] = useState(false);
  const [activeTab, setActiveTab] = useState('player');
  const touchStartX = useRef(null);
  const touchStartY = useRef(null);

  const currentLyricPreview = useMemo(() => {
    if (!track || !track.lyrics || track.lyrics.length === 0) return 'Tap for dynamic animated lyrics';
    let line = track.lyrics[0].text;
    for (const l of track.lyrics) {
      if (currentTime >= l.time) line = l.text;
      else break;
    }
    return line;
  }, [track, currentTime]);

  if (!track) return null;

  const primaryColor = track.themeColor || '#1DB954';
  const secondaryColor = track.secondaryColor || '#8b5cf6';

  const displayTime = isSeeking ? seekVal : currentTime;
  const safeDuration = (duration && duration > 0) ? duration : (track?.duration || 180);
  const progressPercent = Math.min(100, Math.max(0, (displayTime / safeDuration) * 100));

  const handleSeekStart = (e) => {
    setIsSeeking(true);
    setSeekVal(parseFloat(e.target.value));
  };
  const handleSeekChange = (e) => {
    setSeekVal(parseFloat(e.target.value));
  };
  const handleSeekCommit = (e) => {
    if (isSeeking) {
      setIsSeeking(false);
      const commitVal = parseFloat(e.target.value !== undefined ? e.target.value : seekVal);
      onSeek(commitVal);
    }
  };

  const handleTouchStart = (e) => {
    touchStartX.current = e.touches[0].clientX;
    touchStartY.current = e.touches[0].clientY;
  };
  const handleTouchEnd = (e) => {
    if (touchStartX.current === null || touchStartY.current === null) return;
    const dx = e.changedTouches[0].clientX - touchStartX.current;
    const dy = e.changedTouches[0].clientY - touchStartY.current;
    touchStartX.current = null;
    touchStartY.current = null;

    // Only switch tabs on clear, deliberate horizontal swipes (avoid vertical scrolling)
    if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(dy) * 2.5) {
      if (dx < 0) setActiveTab('queue');
      else setActiveTab('player');
    }
  };

  return (
    <div
      className="fullscreen-player-modal modern-now-playing-stage"
      id="fullscreen-player-modal"
      style={{
        '--track-accent': primaryColor,
        '--track-secondary': secondaryColor
      }}
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
    >
      {/* Ambient BG */}
      <div className="player-ambient-bg" />
      <div className="player-ambient-orb orb-primary" />
      <div className="player-ambient-orb orb-secondary" />

      {/* Top Header */}
      <header className="player-top-bar">
        <button
          className="player-collapse-btn"
          id="player-collapse-btn"
          onClick={() => {
            if (activeTab === 'queue') {
              setActiveTab('player');
            } else {
              onClose();
            }
          }}
          aria-label={activeTab === 'queue' ? 'Back to Now Playing' : 'Collapse player'}
        >
          {activeTab === 'queue' ? <ArrowLeft size={22} /> : <ChevronDown size={24} />}
        </button>
        <div className="player-top-meta">
          <span className="player-top-source">
            {activeTab === 'queue' ? 'UP NEXT QUEUE' : 'NOW PLAYING'}
          </span>
          <h4 className="player-top-album">
            {activeTab === 'queue' ? `${queue.length} Tracks In Queue` : (track.album || track.artist || 'AuraWave Master')}
          </h4>
        </div>
        <button className="player-options-btn" id="player-options-btn" onClick={() => setShowOptionsMenu(prev => !prev)} aria-label="More options">
          <MoreVertical size={20} />
        </button>
      </header>

      {/* Tab bar: Now Playing | Up Next */}
      <div className="fsp-tab-bar">
        <button
          className={`fsp-tab-btn ${activeTab === 'player' ? 'active' : ''}`}
          onClick={() => setActiveTab('player')}
        >
          Now Playing
        </button>
        <button
          className={`fsp-tab-btn ${activeTab === 'queue' ? 'active' : ''}`}
          onClick={() => setActiveTab(prev => prev === 'queue' ? 'player' : 'queue')}
        >
          Up Next
          {queue.length > 0 && <span className="fsp-tab-badge">{queue.length}</span>}
        </button>
        <div
          className="fsp-tab-indicator"
          style={{ transform: activeTab === 'queue' ? 'translateX(100%)' : 'translateX(0%)' }}
        />
      </div>

      {/* Options menu */}
      {showOptionsMenu && (
        <div className="player-options-popover" onClick={() => setShowOptionsMenu(false)}>
          <div className="player-options-panel glass-sheet" onClick={e => e.stopPropagation()}>
            <div className="player-options-header">
              <span>Playback Settings</span>
              <button className="options-close-x" onClick={() => setShowOptionsMenu(false)}><X size={18} /></button>
            </div>
            {onOpenQuality && (
              <button className="player-option-item" onClick={() => { setShowOptionsMenu(false); onOpenQuality(); }}>
                <Sliders size={18} color="#1DB954" />
                <div className="option-text-group">
                  <span className="option-title">Audio Quality</span>
                  <span className="option-sub">{audioQuality === '320k' ? '320kbps Hi-Fi Master' : audioQuality}</span>
                </div>
              </button>
            )}
            {onOpenPocketMode && (
              <button className="player-option-item" onClick={() => { setShowOptionsMenu(false); onOpenPocketMode(); }}>
                <Moon size={18} color="#06b6d4" />
                <div className="option-text-group">
                  <span className="option-title">Pocket Mode</span>
                  <span className="option-sub">Lock screen for pocket playback</span>
                </div>
              </button>
            )}
            {onOpenSleepTimer && (
              <button className="player-option-item" onClick={() => { setShowOptionsMenu(false); onOpenSleepTimer(); }}>
                <Timer size={18} color="#a78bfa" />
                <div className="option-text-group">
                  <span className="option-title">Sleep Timer</span>
                  <span className="option-sub">{sleepTimerMinutes ? `Active (${sleepTimerMinutes}m)` : 'Set auto-stop timer'}</span>
                </div>
              </button>
            )}
          </div>
        </div>
      )}

      {/* ---- PLAYER PANEL ---- */}
      <div className={`fsp-panel fsp-player-panel ${activeTab === 'player' ? 'fsp-panel-active' : 'fsp-panel-hidden'}`}>

        <div className="player-artwork-container">
          <div className={`modern-artwork-showcase ${isPlaying ? 'is-playing' : ''}`}>
            <img
              src={track.coverUrl || "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80"}
              alt={track.title}
              className="modern-artwork-img"
              id="player-artwork-img"
            />
            <div className="artwork-gloss-sheen" />
            <div className="artwork-ambient-reflection" />
          </div>
        </div>

        <div className="player-track-details">
          <div className="player-track-info">
            <h2 className="player-track-title">{track.title}</h2>
            <p className="player-track-artist">{track.artist}</p>
          </div>
          <div className="player-track-actions">
            <button
              className={`modern-action-icon-pill ${isDownloaded ? 'is-downloaded' : ''}`}
              onClick={() => onToggleDownload && onToggleDownload(track)}
              aria-label="Download offline"
            >
              <ArrowDownCircle size={21} color={isDownloaded ? '#1DB954' : '#a1a1aa'} fill={isDownloaded ? 'rgba(29, 185, 84, 0.25)' : 'none'} />
            </button>
            {onOpenAddToPlaylist && (
              <button className="modern-action-icon-pill add-pl-action-btn" onClick={() => onOpenAddToPlaylist(track)} aria-label="Add to playlist">
                <Plus size={20} strokeWidth={2.5} />
              </button>
            )}
            <button
              className={`modern-action-icon-pill fav-action-btn ${isFavorite ? 'is-fav' : ''}`}
              id="player-fav-btn"
              onClick={e => { e.stopPropagation(); if (onToggleFavorite) onToggleFavorite(track); }}
              aria-label="Toggle favorite"
            >
              <Heart size={21} fill={isFavorite ? '#ef4444' : 'none'} color={isFavorite ? '#ef4444' : '#a1a1aa'} />
            </button>
          </div>
        </div>

        <div className="player-visualizer-strip">
          <VisualizerCanvas isPlaying={isPlaying} primaryColor={primaryColor} secondaryColor={secondaryColor} height={26} barCount={28} />
        </div>

        <div className="player-scrubber-section">
          <input
            type="range" min="0" max={safeDuration} step="0.1" value={displayTime || 0}
            onMouseDown={handleSeekStart} onTouchStart={handleSeekStart}
            onChange={handleSeekChange}
            onMouseUp={handleSeekCommit} onTouchEnd={handleSeekCommit}
            onKeyUp={e => { if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') onSeek(parseFloat(e.target.value)); }}
            className={`player-slider ${isSeeking ? 'is-dragging' : ''}`}
            id="player-seek-slider"
            aria-label="Seek song position"
            style={{ background: `linear-gradient(to right, ${primaryColor || '#1DB954'} 0%, #1ed760 ${progressPercent}%, rgba(255,255,255,0.16) ${progressPercent}%, rgba(255,255,255,0.16) 100%)` }}
          />
          <div className="player-time-labels">
            <span>{formatTime(displayTime)}</span>
            <span>{formatTime(safeDuration)}</span>
          </div>
        </div>

        <div className="player-controls-row">
          <button className={`control-btn secondary-btn ${isShuffle ? 'active-mode' : ''}`} id="player-shuffle-btn" onClick={onToggleShuffle} aria-label="Toggle shuffle">
            <Shuffle size={20} />
            {isShuffle && <span className="control-active-dot" />}
          </button>
          <button className="control-btn skip-btn" id="player-prev-btn" onClick={onPrev} aria-label="Previous track">
            <SkipBack size={26} fill="currentColor" />
          </button>
          <button className="control-btn play-main-btn modern-glow-play-btn" id="player-play-btn" onClick={onTogglePlay} aria-label={isPlaying ? 'Pause' : 'Play'}>
            {isPlaying ? <Pause size={30} fill="currentColor" /> : <Play size={30} fill="currentColor" style={{ marginLeft: '3px' }} />}
          </button>
          <button className="control-btn skip-btn" id="player-next-btn" onClick={onNext} aria-label="Next track">
            <SkipForward size={26} fill="currentColor" />
          </button>
          <button className={`control-btn secondary-btn ${repeatMode !== 'off' ? 'active-mode' : ''}`} id="player-repeat-btn" onClick={onToggleRepeat} aria-label="Toggle repeat">
            {repeatMode === 'one' ? <Repeat1 size={20} /> : <Repeat size={20} />}
            {repeatMode !== 'off' && <span className="control-active-dot" />}
          </button>
        </div>

        <div className="player-bottom-utility-bar">
          {onOpenSleepTimer && (
            <button className={`player-util-btn ${sleepTimerMinutes ? 'active-sleep' : ''}`} id="player-sleep-timer-btn" onClick={onOpenSleepTimer} aria-label="Sleep timer">
              <Moon size={18} color={sleepTimerMinutes ? '#a78bfa' : 'currentColor'} />
              {sleepTimerMinutes && <span className="player-sleep-mini-tag">{sleepTimerMinutes === 'endOfTrack' ? 'End' : `${sleepTimerMinutes}m`}</span>}
            </button>
          )}
          <div className="player-bottom-utility-right">
            <button className="player-util-btn" id="player-share-btn" onClick={() => { if (navigator.share) navigator.share({ title: track.title, text: `Listening to ${track.title} by ${track.artist} on AuraWave` }); }} aria-label="Share track">
              <Share2 size={18} />
            </button>
            <button
              className={`player-util-btn ${activeTab === 'queue' ? 'active-queue-tab' : ''}`}
              id="player-queue-btn"
              onClick={() => setActiveTab(prev => prev === 'queue' ? 'player' : 'queue')}
              aria-label="Up next queue"
            >
              <ListMusic size={19} />
              {queue.length > 0 && <span className="player-queue-count-dot">{queue.length}</span>}
            </button>
          </div>
        </div>

        {/* Next in Queue Quick Peek Banner - see upcoming song directly on main player */}
        {queue.length > 0 && (
          <div 
            className="fsp-inline-next-peek" 
            onClick={() => setActiveTab('queue')}
            role="button"
            tabIndex={0}
            title="Click to view full queue"
          >
            <div className="fsp-inp-left">
              <div className="fsp-inp-indicator">
                <span className="fsp-inp-pulse-dot" />
                <span className="fsp-inp-label">NEXT UP</span>
              </div>
              <div className="fsp-inp-track-text">
                <span className="fsp-inp-title">{queue[0].title}</span>
                <span className="fsp-inp-artist">• {queue[0].artist}</span>
              </div>
            </div>
            <div className="fsp-inp-right">
              <span className="fsp-inp-count">+{queue.length}</span>
              <ChevronRight size={15} className="fsp-inp-arrow" />
            </div>
          </div>
        )}

        <div className="lyrics-teaser-card modern-lyrics-peek" id="lyrics-teaser-card" onClick={onOpenLyrics}>
          <div className="lyrics-teaser-header">
            <div className="lyrics-teaser-badge">
              <Mic2 size={15} className="pulsing-mic-icon" />
              <span>KINETIC LYRICS</span>
            </div>
            <span className="lyrics-teaser-open">OPEN LYRICS</span>
          </div>
          <p className="lyrics-teaser-line">"{currentLyricPreview}"</p>
        </div>
      </div>

      {/* ---- QUEUE PANEL ---- */}
      <div className={`fsp-panel fsp-queue-panel ${activeTab === 'queue' ? 'fsp-panel-active' : 'fsp-panel-hidden'}`}>

        {/* Quick Return to Player Tab */}
        <div className="fsp-queue-top-back-bar" onClick={() => setActiveTab('player')} role="button" tabIndex={0}>
          <ArrowLeft size={16} />
          <span>Back to Now Playing ({track.title})</span>
        </div>

        {/* Now Playing mini-card at top */}
        <div className="fsp-queue-now-playing-card">
          <img src={track.coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'} alt={track.title} className="fsp-qnp-cover" />
          <div className="fsp-qnp-info">
            <span className="fsp-qnp-label">NOW PLAYING</span>
            <span className="fsp-qnp-title">{track.title}</span>
            <span className="fsp-qnp-artist">{track.artist}</span>
          </div>
          <div className={`fsp-qnp-eq ${isPlaying ? 'playing' : ''}`}>
            <span /><span /><span /><span />
          </div>
        </div>

        {/* NEXT IN QUEUE header row */}
        <div className="fsp-next-header-row">
          <div>
            <span className="fsp-next-label">NEXT IN QUEUE</span>
            <span className="fsp-next-count">{queue.length} tracks</span>
          </div>
          <div className="fsp-queue-actions-row">
            {queue.length > 1 && onShuffleQueue && (
              <button className="fsp-q-pill" onClick={onShuffleQueue}>
                <Shuffle size={11} />
                <span>Shuffle</span>
              </button>
            )}
            {queue.length > 0 && onClearQueue && (
              <button className="fsp-q-pill fsp-q-pill-clear" onClick={onClearQueue}>
                <Trash2 size={11} />
                <span>Clear</span>
              </button>
            )}
          </div>
        </div>

        {/* Queue tracks */}
        {queue.length === 0 ? (
          <div className="fsp-queue-empty-state">
            <div className="fsp-empty-icon-ring">
              <Music size={28} color="#52525b" />
            </div>
            <p className="fsp-empty-title">Queue is empty</p>
            <p className="fsp-empty-sub">Songs auto-fill from similar artists & genre</p>
          </div>
        ) : (
          <div
            className="fsp-queue-scroll"
            id="fsp-queue-scroll"
            onTouchStart={e => e.stopPropagation()}
            onTouchMove={e => e.stopPropagation()}
            onTouchEnd={e => e.stopPropagation()}
          >
            {queue.map((qTrack, idx) => (
              <div
                key={`fspq-${qTrack.id || qTrack.title}-${idx}`}
                className="fsp-q-row"
                onClick={() => {
                  if (onPlayQueueTrack) onPlayQueueTrack(qTrack, idx);
                  setActiveTab('player');
                }}
                role="button"
                tabIndex={0}
              >
                <span className="fsp-q-idx">
                  <span className="fsp-q-num">{idx + 1}</span>
                  <Play size={11} fill="#1DB954" color="#1DB954" className="fsp-q-play-icon" />
                </span>
                <img src={qTrack.coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'} alt={qTrack.title} className="fsp-q-cover" loading="lazy" />
                <div className="fsp-q-info">
                  <span className="fsp-q-title">{qTrack.title}</span>
                  <div className="fsp-q-meta-line">
                    <span className="fsp-q-artist">{qTrack.artist}</span>
                    {qTrack.genre && <span className="fsp-q-genre-pill">{qTrack.genre}</span>}
                  </div>
                </div>
                <span className="fsp-q-dur">{formatDuration(qTrack.duration)}</span>
                {onRemoveFromQueue && (
                  <button
                    className="fsp-q-remove"
                    onClick={e => { e.stopPropagation(); onRemoveFromQueue(idx); }}
                    aria-label="Remove"
                  >
                    <X size={13} />
                  </button>
                )}
              </div>
            ))}
          </div>
        )}

        <div className="fsp-queue-footer-bar">
          <button 
            type="button"
            className="fsp-q-return-player-btn"
            onClick={() => setActiveTab('player')}
          >
            <ChevronLeft size={15} />
            <span>Back to Now Playing</span>
          </button>
          <div className="fsp-q-footer-hint">
            <Sparkles size={11} color="#1DB954" />
            <span>Autoplay keeps similar music flowing</span>
          </div>
        </div>
      </div>
    </div>
  );
}
