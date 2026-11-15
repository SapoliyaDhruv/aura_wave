import React, { useMemo } from 'react';
import {
  ArrowDownCircle,
  Play,
  Pause,
  Trash2,
  HardDrive,
  Wifi,
  WifiOff,
  Music,
  Plus,
  Sparkles,
  Check
} from 'lucide-react';
import { normalizeTrackName, normalizeArtistName } from '../data/curatedTracks';

function formatDuration(seconds) {
  if (isNaN(seconds) || seconds < 0) return '3:00';
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

export default function DownloadedView({
  downloadedTracks = [],
  currentTrackId,
  isPlaying,
  onPlayTrack,
  onTogglePlay,
  onRemoveDownload,
  isOfflineMode,
  onToggleOfflineMode,
  storageStats = { count: 0, formattedSize: '0 MB' },
  onOpenSearch,
  onDownloadAllFavorites,
  isMobile = false
}) {
  // Strictly deduplicate by canonical song title and artist
  const uniqueTracks = useMemo(() => {
    const seen = new Set();
    return (downloadedTracks || []).filter(t => {
      const key = `${normalizeTrackName(t.title)}:::${normalizeArtistName(t.artist)}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [downloadedTracks]);

  const isPlayingDownloaded = isPlaying && uniqueTracks.some(t => 
    t.id === currentTrackId || (currentTrackId && normalizeTrackName(t.title) === normalizeTrackName(currentTrackId))
  );

  const handlePlayAll = () => {
    if (uniqueTracks.length > 0) {
      if (isPlayingDownloaded) {
        onTogglePlay();
      } else {
        onPlayTrack(uniqueTracks[0], uniqueTracks.slice(1));
      }
    }
  };

  // Approximate % of 1GB vault used
  const usedMB = parseFloat(storageStats.formattedSize) || (uniqueTracks.length * 3.5);
  const storagePct = Math.min(100, Math.max(2, (usedMB / 1024) * 100));

  return (
    <div className={`sp-downloaded-page ${isMobile ? 'is-mobile-view' : ''}`} id="downloaded-songs-view">
      {/* Spotify-style Hero Header */}
      <div className="sp-dl-hero">
        <div className="sp-dl-hero-icon-box">
          <ArrowDownCircle size={isMobile ? 36 : 72} color="#ffffff" strokeWidth={1.8} />
        </div>

        <div className="sp-dl-hero-meta">
          <div className="sp-dl-tag-row">
            <span className="sp-dl-tag">PREMIUM OFFLINE VAULT</span>
            <span className="sp-dl-hifi-tag">320KBPS FLAC</span>
          </div>
          <h1 className="sp-dl-title">Downloaded Songs</h1>
          <p className="sp-dl-sub">
            Cached directly in your device storage • Playable with 0 internet connection or mobile data
          </p>

          <div className="sp-dl-stats-row">
            <span className="sp-dl-stat-pill">
              <HardDrive size={13} color="#1DB954" />
              {uniqueTracks.length} tracks ({storageStats.formattedSize})
            </span>

            {/* Offline Mode Switch */}
            <button
              className={`sp-dl-offline-toggle ${isOfflineMode ? 'active-forced' : ''}`}
              onClick={() => onToggleOfflineMode(!isOfflineMode)}
              title="Toggle Offline Mode"
              type="button"
            >
              {isOfflineMode ? (
                <>
                  <WifiOff size={13} color="#eab308" />
                  <span>Offline Mode Enabled</span>
                </>
              ) : (
                <>
                  <Wifi size={13} color="#1DB954" />
                  <span>Online (Auto-Caching Active)</span>
                </>
              )}
            </button>
          </div>

          {/* Visual Storage Meter */}
          <div className="sp-storage-meter-wrap">
            <div className="sp-storage-labels">
              <span>Vault Storage: {storageStats.formattedSize}</span>
              <span>1 GB Allocated</span>
            </div>
            <div className="sp-storage-track">
              <div
                className="sp-storage-bar-fill"
                style={{ width: `${storagePct}%` }}
              />
            </div>
          </div>
        </div>
      </div>

      {/* Action Row */}
      <div className="sp-dl-action-bar">
        {uniqueTracks.length > 0 && (
          <button
            className="sp-dl-play-all-btn"
            onClick={handlePlayAll}
            title={isPlayingDownloaded ? 'Pause' : 'Play All Offline Songs'}
            type="button"
          >
            {isPlayingDownloaded ? (
              <Pause size={isMobile ? 20 : 24} fill="#000000" color="#000000" />
            ) : (
              <Play size={isMobile ? 20 : 24} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
            )}
          </button>
        )}

        {onDownloadAllFavorites && (
          <button
            className="sp-dl-bulk-btn"
            onClick={onDownloadAllFavorites}
            title="Download all your Liked Songs for offline listening"
            type="button"
          >
            <ArrowDownCircle size={15} color="#1DB954" />
            <span>Download All Liked</span>
          </button>
        )}

        <div className="sp-dl-status-note">
          <span>Every played song is automatically cached locally for zero-data playback</span>
        </div>
      </div>

      {/* Track List */}
      {uniqueTracks.length === 0 ? (
        <div className="sp-dl-empty-state">
          <div className="sp-dl-empty-icon">
            <Music size={44} color="#a1a1aa" />
          </div>
          <h3 className="sp-dl-empty-title">No offline songs downloaded yet</h3>
          <p className="sp-dl-empty-desc">
            Songs you listen to or tap the download icon on will be securely saved here on your device for instant offline listening.
          </p>
          {onOpenSearch && (
            <button className="sp-dl-browse-btn" onClick={onOpenSearch} type="button">
              Search & Play Songs
            </button>
          )}
        </div>
      ) : isMobile ? (
        /* Mobile-First Responsive Track Card List */
        <div className="sp-dl-mobile-list" id="sp-dl-mobile-list">
          {uniqueTracks.map((track, idx) => {
            const isCurrent = currentTrackId === track.id || (currentTrackId && normalizeTrackName(track.title) === normalizeTrackName(currentTrackId));
            return (
              <div
                key={track.id || idx}
                className={`sp-dl-mobile-card ${isCurrent ? 'active-track' : ''}`}
                onClick={() => {
                  if (isCurrent) {
                    onTogglePlay();
                  } else {
                    onPlayTrack(track, uniqueTracks.slice(idx + 1));
                  }
                }}
                id={`mobile-offline-track-${track.id || idx}`}
              >
                <div className="sp-dl-card-left">
                  <div className="sp-dl-card-thumb-wrap">
                    <img src={track.coverUrl} alt={track.title} className="sp-dl-card-thumb" />
                    {isCurrent && isPlaying ? (
                      <div className="sp-card-eq-overlay">
                        <span /><span /><span />
                      </div>
                    ) : (
                      <div className="sp-mobile-play-hint">
                        <Play size={12} fill="#ffffff" color="#ffffff" />
                      </div>
                    )}
                  </div>

                  <div className="sp-dl-card-info">
                    <h4 className={`sp-dl-card-title ${isCurrent ? 'green-accent' : ''}`}>
                      {track.title}
                    </h4>
                    <p className="sp-dl-card-artist">{track.artist}</p>
                    <div className="sp-dl-card-tag-row">
                      <span className="sp-dl-offline-chip">
                        <ArrowDownCircle size={10} color="#1DB954" fill="rgba(29, 185, 84, 0.25)" />
                        <span>Saved Offline</span>
                      </span>
                    </div>
                  </div>
                </div>

                <div className="sp-dl-card-right" onClick={e => e.stopPropagation()}>
                  <span className="sp-dl-card-duration">{formatDuration(track.duration)}</span>
                  <button
                    className="sp-dl-card-trash-btn"
                    onClick={() => onRemoveDownload(track.id)}
                    title="Remove from offline downloads"
                    aria-label="Remove download"
                    type="button"
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        /* Desktop Tabular View */
        <div className="sp-table-container">
          <div className="sp-table-header">
            <span className="sp-th-num">#</span>
            <span className="sp-th-title">TITLE</span>
            <span className="sp-th-album">ALBUM</span>
            <span className="sp-th-status">STATUS</span>
            <span className="sp-th-dur">DURATION</span>
          </div>

          <div className="sp-table-body">
            {uniqueTracks.map((track, idx) => {
              const isCurrent = currentTrackId === track.id;
              return (
                <div
                  key={track.id || idx}
                  className={`sp-table-row ${isCurrent ? 'active-playing-row' : ''}`}
                  onClick={() => {
                    if (isCurrent) {
                      onTogglePlay();
                    } else {
                      onPlayTrack(track, uniqueTracks.slice(idx + 1));
                    }
                  }}
                  id={`offline-track-${track.id || idx}`}
                >
                  <div className="sp-td-num">
                    {isCurrent && isPlaying ? (
                      <div className="sp-mini-sound-wave">
                        <span /><span /><span />
                      </div>
                    ) : (
                      <span className="sp-num-text">{idx + 1}</span>
                    )}
                    <button
                      className="sp-row-hover-play"
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        if (isCurrent) {
                          onTogglePlay();
                        } else {
                          onPlayTrack(track, uniqueTracks.slice(idx + 1));
                        }
                      }}
                    >
                      {isCurrent && isPlaying ? (
                        <Pause size={14} fill="currentColor" />
                      ) : (
                        <Play size={14} fill="currentColor" />
                      )}
                    </button>
                  </div>

                  <div className="sp-td-main">
                    <img src={track.coverUrl} alt={track.title} className="sp-td-cover" />
                    <div className="sp-td-info">
                      <span className={`sp-td-title ${isCurrent ? 'green-accent' : ''}`}>
                        {track.title}
                      </span>
                      <span className="sp-td-artist">{track.artist}</span>
                    </div>
                  </div>

                  <div className="sp-td-album">
                    <span>{track.album || 'Single'}</span>
                  </div>

                  <div className="sp-td-status">
                    <span className="sp-downloaded-badge" title="Saved locally in offline storage">
                      <ArrowDownCircle size={15} color="#1DB954" fill="rgba(29, 185, 84, 0.25)" />
                      <span>Downloaded</span>
                    </span>
                  </div>

                  <div className="sp-td-dur" onClick={e => e.stopPropagation()}>
                    <span className="sp-dur-text">{formatDuration(track.duration)}</span>
                    <button
                      className="sp-remove-dl-btn"
                      onClick={() => onRemoveDownload(track.id)}
                      title="Remove from offline downloads"
                      type="button"
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
