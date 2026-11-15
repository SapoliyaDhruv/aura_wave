import React from 'react';
import { Play, Pause } from 'lucide-react';
import { DEFAULT_RECENTLY_PLAYED } from '../data/featuredContent';

export default function RecentlyPlayedGrid({
  tracks = [],
  currentTrackId,
  isPlaying,
  onPlayTrack,
  onViewAll
}) {
  const displayTracks = (tracks && tracks.length >= 6 ? tracks.slice(0, 6) : DEFAULT_RECENTLY_PLAYED);

  return (
    <section className="sp-shelf recently-played-shelf" id="home-recently-played-shelf">
      <div className="sp-shelf-header">
        <div className="sp-shelf-header-left">
          <h2 className="sp-shelf-title">
            <span className="shelf-icon">🕒</span> Recently Played
          </h2>
        </div>
        {onViewAll && (
          <button className="sp-shelf-view-all-btn" onClick={onViewAll}>
            <span>View all</span>
            <span className="arrow-sym">&gt;</span>
          </button>
        )}
      </div>

      <div className="recently-played-grid-2x3">
        {displayTracks.map((track, idx) => {
          const isCurrent = currentTrackId === track.id;
          return (
            <div
              key={track.id || idx}
              className={`rp-card ${isCurrent ? 'active' : ''}`}
              onClick={() => onPlayTrack(track)}
            >
              <div className="rp-thumb-wrap">
                <img src={track.coverUrl} alt={track.title} className="rp-thumb" />
                <div className={`rp-hover-play ${isCurrent && isPlaying ? 'playing' : ''}`}>
                  {isCurrent && isPlaying ? (
                    <Pause size={18} fill="#000000" color="#000000" />
                  ) : (
                    <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                  )}
                </div>
              </div>

              <div className="rp-info">
                <span className="rp-title">{track.title}</span>
                <span className="rp-artist">{track.artist}</span>
              </div>

              {/* Lossless / Offline Verified Green Checkmark */}
              <div className="rp-check-badge" title="Verified Master Track">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="10" stroke="#1DB954" strokeWidth="2" fill="rgba(29, 185, 84, 0.15)" />
                  <path d="M8 12.5l2.5 2.5 5.5-5.5" stroke="#1DB954" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
