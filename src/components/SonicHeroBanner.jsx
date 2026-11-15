import React from 'react';
import { Play, Pause, Plus, MoreHorizontal } from 'lucide-react';
import { HERO_SONIC_JOURNEY } from '../data/featuredContent';

export default function SonicHeroBanner({
  onPlayAuraMix,
  onSaveToLibrary,
  currentTrack,
  isPlaying,
  onTogglePlay,
  tracks = [],
  queue = []
}) {
  const { title, subLabel, description, badges, syncedWidget } = HERO_SONIC_JOURNEY;

  // Format seconds into m:ss
  const formatDuration = (sec) => {
    if (!sec || isNaN(sec)) return '';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  // Truly Dynamic Synced Widget Data
  const hasActiveTrack = Boolean(currentTrack && currentTrack.title);
  const dynamicCover = hasActiveTrack ? (currentTrack.coverUrl || syncedWidget.cover) : syncedWidget.cover;
  const dynamicLabel = hasActiveTrack ? (isPlaying ? 'NOW PLAYING' : 'AUDIO READY') : 'CURATED MIX';
  const dynamicTitle = hasActiveTrack ? currentTrack.title : (syncedWidget.title || 'Curated Sonic Journey');
  
  // Real dynamic metadata: NEVER static "46 min" or fake hardcoded tracks
  const dynamicMeta = hasActiveTrack
    ? [currentTrack.artist, formatDuration(currentTrack.duration)].filter(Boolean).join(' • ')
    : (tracks && tracks.length > 0 ? `${tracks.length} Lossless Tracks` : 'Master Hi-Fi Quality');

  const handleWidgetClick = () => {
    if (hasActiveTrack && onTogglePlay) {
      onTogglePlay();
    } else if (onPlayAuraMix) {
      onPlayAuraMix();
    }
  };

  return (
    <div className="sonic-hero-card" id="curated-sonic-journeys-banner">
      <div className="sonic-hero-content">
        {/* Top Badges Row */}
        <div className="sonic-hero-badge-row">
          {badges.map((b, idx) => (
            <span key={idx} className={`sonic-badge ${b.class}`}>
              {b.label}
            </span>
          ))}
        </div>

        {/* Sub-label & Main Heading */}
        <span className="sonic-hero-sub">{subLabel}</span>
        <h1 className="sonic-hero-title">{title}</h1>
        <p className="sonic-hero-desc">{description}</p>

        {/* Action Buttons */}
        <div className="sonic-hero-actions">
          <button
            className="sonic-btn-primary"
            onClick={onPlayAuraMix}
            title="Play Aura Mix"
          >
            {isPlaying && hasActiveTrack ? (
              <Pause size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
            ) : (
              <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
            )}
            <span>{isPlaying && hasActiveTrack ? 'Pause Mix' : 'Play Aura Mix'}</span>
          </button>

          <button
            className="sonic-btn-secondary"
            onClick={onSaveToLibrary}
            title="Save to Library"
          >
            <Plus size={16} />
            <span>Save to Library</span>
          </button>

          <button
            className="sonic-btn-more"
            title="More Options"
          >
            <MoreHorizontal size={18} />
          </button>
        </div>
      </div>

      {/* Embedded Right Widget: Dynamic Now Playing / Synced */}
      <div
        className="sonic-now-synced-widget"
        onClick={handleWidgetClick}
        title={hasActiveTrack ? (isPlaying ? 'Tap to Pause' : 'Tap to Play') : 'Play Curated Mix'}
        style={{ cursor: 'pointer' }}
      >
        <img
          src={dynamicCover}
          alt={dynamicTitle}
          className="sonic-synced-thumb"
        />
        <div className="sonic-synced-meta">
          <span className="sonic-synced-label">{dynamicLabel}</span>
          <span className="sonic-synced-title">{dynamicTitle}</span>
          <span className="sonic-synced-sub">{dynamicMeta}</span>
        </div>
        <div className={`sonic-synced-eq ${isPlaying ? 'is-playing' : ''}`}>
          <span /><span /><span /><span />
        </div>
      </div>
    </div>
  );
}
