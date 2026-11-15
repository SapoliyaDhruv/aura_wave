import React, { useState } from 'react';
import {
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Shuffle,
  Repeat,
  Repeat1,
  Heart,
  ArrowDownCircle,
  Mic2,
  ListMusic,
  Sparkles,
  Volume2,
  VolumeX,
  Maximize2,
  Moon,
  Timer,
  Keyboard
} from 'lucide-react';

function formatDuration(seconds) {
  if (isNaN(seconds) || seconds < 0) return '0:00';
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

export default function SpotifyBottomBar({
  track,
  isPlaying,
  currentTime,
  duration,
  onTogglePlay,
  onSeek,
  onPrev,
  onNext,
  isFavorite,
  onToggleFavorite,
  isDownloaded,
  onToggleDownload,
  isShuffle,
  onToggleShuffle,
  repeatMode,
  onToggleRepeat,
  onOpenLyrics,
  showLyricsView,
  onOpenQueue,
  showQueueModal,
  onOpenFullPlayer,
  audioQuality = '320k',
  onOpenQualityModal,
  volume = 0.85,
  onVolumeChange,
  onOpenPocketMode,
  onOpenSleepTimer,
  sleepTimerRemaining,
  sleepTimerMinutes,
  onOpenShortcuts
}) {
  const [isSeeking, setIsSeeking] = useState(false);
  const [seekVal, setSeekVal] = useState(0);

  if (!track) return null;

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

  return (
    <footer className="sp-bottom-player" id="spotify-bottom-player">
      {/* Left: Track Info & Quick Actions */}
      <div className="sp-player-left">
        <div
          className="sp-player-thumb-wrap"
          onClick={onOpenFullPlayer}
          title="Click to open Fullscreen Player"
        >
          <img src={track.coverUrl} alt={track.title} className="sp-player-thumb" />
        </div>

        <div className="sp-player-meta">
          <div className="sp-player-title-row">
            <span
              className="sp-player-title"
              onClick={onOpenFullPlayer}
              title={track.title}
            >
              {track.title}
            </span>
            {isDownloaded && (
              <span
                className="sp-download-icon-pill"
                title="Downloaded for offline playback"
              >
                <ArrowDownCircle
                  size={14}
                  color="#1DB954"
                  fill="rgba(29, 185, 84, 0.3)"
                />
              </span>
            )}
          </div>
          <span className="sp-player-artist" title={track.artist}>
            {track.artist}
          </span>
        </div>

        <div className="sp-player-left-actions">
          {/* Favorite Button */}
          <button
            className={`sp-icon-btn ${isFavorite ? 'active-fav' : ''}`}
            onClick={(e) => {
              e.stopPropagation();
              if (onToggleFavorite) onToggleFavorite(track);
            }}
            title={isFavorite ? 'Remove from Liked Songs' : 'Save to Liked Songs'}
            aria-label="Like song"
          >
            <Heart
              size={18}
              fill={isFavorite ? '#ef4444' : 'none'}
              color={isFavorite ? '#ef4444' : '#a1a1aa'}
            />
          </button>

          {/* Downloaded / Verified Checkmark */}
          <button
            className="sp-icon-btn active-verified"
            onClick={() => onToggleDownload(track)}
            title={isDownloaded ? 'Downloaded to offline vault' : 'Click to download offline'}
            aria-label="Offline status"
          >
            <div className="sp-verified-check-circle">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="10" stroke="#1DB954" strokeWidth="2" fill="rgba(29, 185, 84, 0.15)" />
                <path d="M8 12.5l2.5 2.5 5.5-5.5" stroke="#1DB954" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </div>
          </button>
        </div>
      </div>

      {/* Center: Controls & Scrubber */}
      <div className="sp-player-center">
        <div className="sp-controls-row">
          <button
            className={`sp-control-icon-btn ${isShuffle ? 'active-accent' : ''}`}
            onClick={onToggleShuffle}
            title="Enable Shuffle"
            aria-label="Shuffle"
          >
            <Shuffle size={16} />
          </button>

          <button
            className="sp-control-icon-btn"
            onClick={onPrev}
            title="Previous"
            aria-label="Previous track"
          >
            <SkipBack size={20} fill="currentColor" />
          </button>

          {/* Big Circular Spotify Play/Pause Button */}
          <button
            className="sp-main-play-btn"
            onClick={onTogglePlay}
            title={isPlaying ? 'Pause' : 'Play'}
            aria-label={isPlaying ? 'Pause' : 'Play'}
            id="spotify-main-play-pause-btn"
          >
            {isPlaying ? (
              <Pause size={20} fill="#000000" color="#000000" />
            ) : (
              <Play size={20} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
            )}
          </button>

          <button
            className="sp-control-icon-btn"
            onClick={onNext}
            title="Next"
            aria-label="Next track"
          >
            <SkipForward size={20} fill="currentColor" />
          </button>

          <button
            className={`sp-control-icon-btn ${repeatMode !== 'off' ? 'active-accent' : ''}`}
            onClick={onToggleRepeat}
            title={`Repeat mode: ${repeatMode}`}
            aria-label="Repeat"
          >
            {repeatMode === 'one' ? <Repeat1 size={17} /> : <Repeat size={16} />}
          </button>
        </div>

        {/* Progress Scrubber */}
        <div className="sp-scrubber-row">
          <span className="sp-time-label">{formatDuration(displayTime)}</span>
          <div className="sp-slider-wrapper">
            <input
              type="range"
              min="0"
              max={safeDuration}
              step="0.1"
              value={displayTime || 0}
              onMouseDown={handleSeekStart}
              onTouchStart={handleSeekStart}
              onChange={handleSeekChange}
              onMouseUp={handleSeekCommit}
              onTouchEnd={handleSeekCommit}
              onKeyUp={e => {
                if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                  onSeek(parseFloat(e.target.value));
                }
              }}
              className={`sp-slider ${isSeeking ? 'is-dragging' : ''}`}
              id="spotify-scrubber-slider"
              aria-label="Seek song position"
              style={{
                '--progress-pct': `${progressPercent}%`,
                background: `linear-gradient(to right, #1DB954 0%, #1ed760 ${progressPercent}%, #3e3e3e ${progressPercent}%, #3e3e3e 100%)`
              }}
            />
          </div>
          <span className="sp-time-label">{formatDuration(safeDuration)}</span>
        </div>
      </div>

      {/* Right: Lyrics, Queue, HI-FI 320k Badge, Sleep Timer, Pocket Mode, Volume & Fullscreen */}
      <div className="sp-player-right">
        {/* Synced Lyrics Drawer Button */}
        <button
          className={`sp-icon-btn ${showLyricsView ? 'active-green' : ''}`}
          onClick={onOpenLyrics}
          title="Synced Kinetic Lyrics (L)"
          aria-label="Open lyrics"
          id="spotify-lyrics-btn"
        >
          <Mic2 size={18} color={showLyricsView ? '#1DB954' : 'currentColor'} />
        </button>

        {/* Queue Drawer Button */}
        <button
          className={`sp-icon-btn ${showQueueModal ? 'active-green' : ''}`}
          onClick={onOpenQueue}
          title="Up Next Queue (Q)"
          aria-label="Open queue"
          id="spotify-queue-btn"
        >
          <ListMusic size={19} color={showQueueModal ? '#1DB954' : 'currentColor'} />
        </button>


        {/* Sleep Timer */}
        {onOpenSleepTimer && (
          <button
            className={`sp-icon-btn ${sleepTimerMinutes ? 'active-green sp-timer-btn-badge' : ''}`}
            onClick={onOpenSleepTimer}
            title={sleepTimerMinutes ? `Sleep Timer Active (${Math.ceil((sleepTimerRemaining || 0) / 60)}m left)` : 'Sleep Timer (T)'}
            aria-label="Sleep timer"
            id="spotify-sleep-timer-btn"
          >
            <Moon size={18} color={sleepTimerMinutes ? '#a78bfa' : 'currentColor'} />
            {sleepTimerMinutes && (
              <span className="sp-timer-mini-indicator">
                {sleepTimerMinutes === 'endOfTrack' ? 'Track' : `${Math.ceil((sleepTimerRemaining || 0) / 60)}m`}
              </span>
            )}
          </button>
        )}

        {/* Pocket Mode / Fullscreen */}
        {onOpenPocketMode && (
          <button
            className="sp-icon-btn"
            onClick={onOpenPocketMode}
            title="Pocket Mode (AMOLED Screen Saver)"
            aria-label="Pocket Mode"
            id="spotify-pocket-btn"
          >
            <Maximize2 size={16} />
          </button>
        )}

        {/* Volume Slider */}
        <div className="sp-volume-group">
          <button
            className="sp-icon-btn"
            onClick={() => onVolumeChange(volume > 0 ? 0 : 0.85)}
            title={volume === 0 ? 'Unmute' : 'Mute'}
            aria-label="Toggle mute"
          >
            {volume === 0 ? <VolumeX size={18} /> : <Volume2 size={18} />}
          </button>
          <input
            type="range"
            min="0"
            max="1"
            step="0.01"
            value={volume}
            onChange={e => onVolumeChange(parseFloat(e.target.value))}
            className="sp-volume-slider"
            id="spotify-volume-slider"
            aria-label="Volume slider"
            style={{
              background: `linear-gradient(to right, #1DB954 ${volume * 100}%, #4d4d4d ${volume * 100}%)`
            }}
          />
        </div>
      </div>
    </footer>
  );
}
