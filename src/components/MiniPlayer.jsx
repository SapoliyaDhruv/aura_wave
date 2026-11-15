import React from 'react';
import { Play, Pause, Heart, Mic2, SkipForward, ArrowDownCircle, Moon } from 'lucide-react';

export default function MiniPlayer({
  track,
  isPlaying,
  currentTime,
  duration,
  onTogglePlay,
  onNext,
  onOpenFullPlayer,
  onOpenLyrics,
  isFavorite,
  onToggleFavorite,
  isDownloaded,
  onToggleDownload,
  onOpenPocketMode
}) {
  if (!track) return null;

  const progressPercent = duration > 0 ? (currentTime / duration) * 100 : 0;
  const primaryColor = track.themeColor || '#1DB954';

  return (
    <div
      className="mini-player"
      id="mini-player"
      style={{ '--track-accent': primaryColor }}
    >
      {/* Top micro progress bar */}
      <div className="mini-progress-track">
        <div
          className="mini-progress-fill"
          style={{ width: `${progressPercent}%` }}
        />
      </div>

      <div className="mini-player-content" onClick={onOpenFullPlayer}>
        <div className="mini-cover-wrapper">
          <img
            src={track.coverUrl}
            alt={track.title}
            className={`mini-cover-img ${isPlaying ? 'spin-record' : ''}`}
          />
        </div>

        <div className="mini-track-info">
          <div className="mini-title-row">
            <span className="mini-title">{track.title}</span>
            {isDownloaded && (
              <span className="mini-download-indicator" title="Downloaded offline">
                <ArrowDownCircle size={14} color="#1DB954" fill="rgba(29, 185, 84, 0.3)" />
              </span>
            )}
          </div>
          <span className="mini-artist">{track.artist}</span>
        </div>
      </div>

      {/* Action buttons */}
      <div className="mini-actions" onClick={e => e.stopPropagation()}>
        <button
          className={`mini-action-btn ${isFavorite ? 'active-fav' : ''}`}
          id="mini-fav-btn"
          onClick={onToggleFavorite}
          aria-label="Favorite track"
        >
          <Heart size={18} fill={isFavorite ? '#ef4444' : 'none'} color={isFavorite ? '#ef4444' : '#a1a1aa'} />
        </button>

        <button
          className="mini-action-btn lyrics-glow-btn"
          id="mini-lyrics-btn"
          onClick={onOpenLyrics}
          aria-label="Open animated lyrics"
          title="Open Synced Animated Lyrics"
        >
          <Mic2 size={17} className="pulsing-mic" />
        </button>

        <button
          className="mini-play-btn"
          id="mini-play-pause-btn"
          onClick={onTogglePlay}
          aria-label={isPlaying ? 'Pause' : 'Play'}
        >
          {isPlaying ? <Pause size={18} /> : <Play size={18} fill="currentColor" />}
        </button>

        <button
          className="mini-action-btn"
          id="mini-next-btn"
          onClick={onNext}
          aria-label="Next track"
        >
          <SkipForward size={18} />
        </button>
      </div>
    </div>
  );
}
