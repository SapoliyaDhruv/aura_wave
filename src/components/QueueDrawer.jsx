import React, { useEffect } from 'react';
import { X, Play, Trash2, ArrowUp, ArrowDown, ListMusic, Music, Shuffle, Sparkles } from 'lucide-react';

function formatDuration(seconds) {
  if (!seconds || isNaN(seconds)) return '3:15';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

export default function QueueDrawer({
  isOpen,
  onClose,
  currentTrack,
  queue = [],
  isPlaying = false,
  onPlayQueueTrack,
  onPlayFromQueue,
  onRemoveFromQueue,
  onReorderQueue,
  onClearQueue,
  onShuffleQueue,
  onPopulateRecommendations
}) {
  // Close on Escape key press
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const handlePlay = onPlayQueueTrack || onPlayFromQueue || (() => {});

  const handleMoveUp = (e, index) => {
    e.stopPropagation();
    if (index > 0 && onReorderQueue) {
      onReorderQueue(index, index - 1);
    }
  };

  const handleMoveDown = (e, index) => {
    e.stopPropagation();
    if (index < queue.length - 1 && onReorderQueue) {
      onReorderQueue(index, index + 1);
    }
  };

  const handleRemove = (e, index) => {
    e.stopPropagation();
    if (onRemoveFromQueue) {
      onRemoveFromQueue(index);
    }
  };

  const genreBadge = currentTrack?.genre
    ? `${currentTrack.genre.toUpperCase()} AUTOPLAY`
    : 'SMART QUEUE';

  return (
    <div className="queue-drawer-overlay open" onClick={onClose} id="queue-drawer-backdrop">
      <aside
        className="queue-drawer-panel"
        onClick={e => e.stopPropagation()}
        id="queue-drawer"
        aria-label="Playback Queue"
      >
        {/* Drawer Header */}
        <div className="queue-header">
          <div className="queue-header-left">
            <div className="queue-icon-badge">
              <ListMusic size={19} color="#1DB954" />
            </div>
            <div className="queue-title-meta">
              <div className="queue-title-row">
                <h2 className="queue-main-title">Queue</h2>
                <span className="queue-count-pill">{queue.length} tracks</span>
              </div>
              <div className="queue-radio-badge">
                <Sparkles size={11} className="radio-pulse-icon" />
                <span>{genreBadge}</span>
              </div>
            </div>
          </div>

          <div className="queue-header-actions">
            {queue.length > 1 && onShuffleQueue && (
              <button
                className="queue-action-pill-btn"
                onClick={onShuffleQueue}
                title="Shuffle Upcoming Queue"
                aria-label="Shuffle Queue"
              >
                <Shuffle size={13} />
                <span>Shuffle</span>
              </button>
            )}

            {queue.length > 0 && onClearQueue && (
              <button
                className="queue-action-pill-btn clear-btn"
                onClick={onClearQueue}
                title="Clear all upcoming tracks"
                aria-label="Clear Queue"
              >
                <Trash2 size={13} />
                <span>Clear</span>
              </button>
            )}

            <button
              className="queue-close-btn"
              onClick={onClose}
              title="Close Queue (Esc)"
              aria-label="Close queue"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Currently Playing Hero Section */}
        {currentTrack && (
          <section className="queue-section queue-now-playing-wrap">
            <div className="queue-section-header">
              <span className="queue-section-label">NOW PLAYING</span>
              <div className="queue-live-indicator">
                <span className="queue-live-dot" />
                <span className="queue-live-text">{isPlaying ? 'PLAYING' : 'PAUSED'}</span>
              </div>
            </div>

            <div className="queue-now-playing-card">
              <div className="queue-now-playing-cover-wrap">
                <img
                  src={currentTrack.coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'}
                  alt={currentTrack.title}
                  className="queue-now-playing-cover"
                />
                <div className="queue-now-playing-glow" />
              </div>

              <div className="queue-now-playing-info">
                <span className="queue-now-playing-title" title={currentTrack.title}>
                  {currentTrack.title}
                </span>
                <span className="queue-now-playing-artist" title={currentTrack.artist}>
                  {currentTrack.artist}
                </span>
                <div className="queue-now-playing-meta">
                  <span className="queue-genre-tag">{currentTrack.genre || 'Music'}</span>
                  {currentTrack.duration > 0 && (
                    <span className="queue-duration-tag">{formatDuration(currentTrack.duration)}</span>
                  )}
                </div>
              </div>

              <div className={`queue-eq-bars ${isPlaying ? 'playing' : 'paused'}`}>
                <span /><span /><span /><span />
              </div>
            </div>
          </section>
        )}

        {/* Up Next List Section */}
        <section className="queue-section queue-upcoming-wrap">
          <div className="queue-section-header">
            <div>
              <span className="queue-section-label">NEXT IN QUEUE</span>
              <span className="queue-section-hint">Auto-matched to artist & genre</span>
            </div>
          </div>

          {queue.length === 0 ? (
            <div className="queue-empty-box">
              <div className="queue-empty-icon-wrap">
                <Music size={32} color="#52525b" />
              </div>
              <h4 className="queue-empty-title">Queue is empty</h4>
              <p className="queue-empty-desc">
                No songs are currently lined up. Play an artist or load genre recommendations!
              </p>
              {onPopulateRecommendations && (
                <button
                  className="queue-populate-btn"
                  onClick={onPopulateRecommendations}
                >
                  <Sparkles size={14} />
                  <span>Fill With Similar Songs</span>
                </button>
              )}
            </div>
          ) : (
            <div
              className="queue-tracks-scroll custom-scrollbar"
              id="queue-scroll-container"
              onTouchStart={e => e.stopPropagation()}
              onTouchMove={e => e.stopPropagation()}
            >
              {queue.map((track, idx) => (
                <div
                  key={`${track.id || track.title}-${idx}`}
                  className="queue-track-row"
                  onClick={() => handlePlay(track, idx)}
                  title={`Click to play "${track.title}"`}
                  role="button"
                  tabIndex={0}
                  onKeyDown={e => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      handlePlay(track, idx);
                    }
                  }}
                >
                  {/* Left: Number & Hover Play Icon */}
                  <div className="queue-row-index-wrap">
                    <span className="queue-row-num">{idx + 1}</span>
                    <span className="queue-row-play-icon">
                      <Play size={13} fill="#1DB954" color="#1DB954" />
                    </span>
                  </div>

                  {/* Album Artwork */}
                  <img
                    src={track.coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'}
                    alt={track.title}
                    className="queue-row-cover"
                    loading="lazy"
                  />

                  {/* Track Info */}
                  <div className="queue-row-info">
                    <span className="queue-row-title" title={track.title}>
                      {track.title}
                    </span>
                    <span className="queue-row-artist" title={track.artist}>
                      {track.artist}
                    </span>
                  </div>

                  {/* Duration Badge */}
                  <span className="queue-row-duration">
                    {formatDuration(track.duration)}
                  </span>

                  {/* Action Buttons: Move Up, Move Down, Remove */}
                  <div className="queue-row-actions">
                    <button
                      className="queue-row-btn"
                      disabled={idx === 0}
                      onClick={e => handleMoveUp(e, idx)}
                      title="Move up"
                      aria-label="Move track up"
                    >
                      <ArrowUp size={13} />
                    </button>
                    <button
                      className="queue-row-btn"
                      disabled={idx === queue.length - 1}
                      onClick={e => handleMoveDown(e, idx)}
                      title="Move down"
                      aria-label="Move track down"
                    >
                      <ArrowDown size={13} />
                    </button>
                    <button
                      className="queue-row-btn delete-btn"
                      onClick={e => handleRemove(e, idx)}
                      title="Remove from queue"
                      aria-label="Remove from queue"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* Footer info pill */}
        <div className="queue-footer">
          <Sparkles size={12} color="#1DB954" />
          <span>Autoplay keeps similar tracks flowing automatically</span>
        </div>
      </aside>
    </div>
  );
}
