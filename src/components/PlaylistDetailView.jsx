import React, { useState, useMemo } from 'react';
import { ArrowLeft, Play, Pause, Shuffle, Trash2, Edit2, Check, X, Music, Clock, Heart, MoreHorizontal, Sparkles } from 'lucide-react';

function formatDuration(sec) {
  if (!sec || isNaN(sec)) return '3:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

export default function PlaylistDetailView({
  playlist,
  tracks = [],
  currentTrackId,
  isPlaying,
  onPlayTrack,
  onBack,
  onDeletePlaylist,
  onRenamePlaylist,
  onRemoveTrackFromPlaylist,
  favorites = [],
  onToggleFavorite
}) {
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState(playlist?.name || '');

  if (!playlist) return null;

  // Resolve playlist tracks
  const playlistTracks = useMemo(() => {
    if (!playlist.tracks || !Array.isArray(playlist.tracks)) return [];
    return playlist.tracks.map(pt => {
      if (typeof pt === 'string') {
        const found = tracks.find(t => t.id === pt);
        return found || { id: pt, title: 'Unknown Song', artist: 'Unknown Artist', duration: 180 };
      }
      return pt;
    });
  }, [playlist.tracks, tracks]);

  const displayTracks = playlistTracks.length > 0 ? playlistTracks : (playlist.tracks || []);

  const totalDurationSec = useMemo(() => {
    return displayTracks.reduce((acc, t) => acc + (t.duration || 180), 0);
  }, [displayTracks]);

  const formattedTotalTime = useMemo(() => {
    const mins = Math.floor(totalDurationSec / 60);
    if (mins >= 60) {
      const hrs = Math.floor(mins / 60);
      const remMins = mins % 60;
      return `${hrs} hr ${remMins} min`;
    }
    return `${mins} min`;
  }, [totalDurationSec]);

  const isCurrentPlaylistPlaying = useMemo(() => {
    return isPlaying && displayTracks.some(t => t.id === currentTrackId);
  }, [isPlaying, displayTracks, currentTrackId]);

  const handlePlayAll = () => {
    if (displayTracks.length > 0) {
      onPlayTrack(displayTracks[0], displayTracks.slice(1));
    }
  };

  const handleShufflePlay = () => {
    if (displayTracks.length > 0) {
      const shuffled = [...displayTracks].sort(() => Math.random() - 0.5);
      onPlayTrack(shuffled[0], shuffled.slice(1));
    }
  };

  const handleSaveRename = () => {
    if (editName.trim() && onRenamePlaylist) {
      onRenamePlaylist(playlist.id, editName.trim());
    }
    setIsEditing(false);
  };

  const defaultCover = 'https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=600&auto=format&fit=crop&q=80';

  return (
    <div className="playlist-detail-view spotify-playlist-theme" id="playlist-detail-view">
      {/* Top Header Navigation */}
      <div className="detail-top-bar">
        <button className="icon-btn-back" onClick={onBack} id="detail-back-btn" aria-label="Back">
          <ArrowLeft size={22} />
        </button>
        <span className="detail-top-title">{playlist.name}</span>

        {/* Delete Playlist Button (if custom playlist) */}
        {onDeletePlaylist && playlist.isCustom && (
          <button
            className="detail-top-action-btn delete-btn"
            onClick={() => {
              if (window.confirm(`Delete playlist "${playlist.name}"?`)) {
                onDeletePlaylist(playlist.id);
                onBack();
              }
            }}
            title="Delete Playlist"
          >
            <Trash2 size={18} />
          </button>
        )}
      </div>

      {/* Hero Header with Dynamic Vibrant Gradient */}
      <div className="playlist-hero-modern">
        <div className="detail-cover-wrapper-modern">
          <img
            src={playlist.cover || defaultCover}
            alt={playlist.name}
            className="detail-cover-img-modern"
            onError={(e) => { e.target.src = defaultCover; }}
          />
        </div>
        <div className="detail-meta-modern">
          <span className="detail-type-tag">PUBLIC PLAYLIST</span>

          {isEditing ? (
            <div className="playlist-edit-row">
              <input
                type="text"
                value={editName}
                onChange={e => setEditName(e.target.value)}
                className="edit-name-input"
                autoFocus
              />
              <button className="edit-save-btn" onClick={handleSaveRename}>
                <Check size={16} />
              </button>
              <button className="edit-cancel-btn" onClick={() => setIsEditing(false)}>
                <X size={16} />
              </button>
            </div>
          ) : (
            <div className="playlist-title-row-modern">
              <h1 className="detail-title-modern">{playlist.name}</h1>
              {playlist.isCustom && (
                <button
                  className="icon-btn edit-title-btn"
                  onClick={() => {
                    setEditName(playlist.name);
                    setIsEditing(true);
                  }}
                  title="Rename Playlist"
                >
                  <Edit2 size={16} />
                </button>
              )}
            </div>
          )}

          <p className="detail-desc-modern">{playlist.description || 'Personalized lossless audio playlist'}</p>

          <div className="detail-stats-modern">
            <span className="creator-badge">AuraWave</span>
            <span className="stats-dot">•</span>
            <span className="track-count-badge">{displayTracks.length} songs</span>
            {totalDurationSec > 0 && (
              <>
                <span className="stats-dot">•</span>
                <span className="duration-badge">{formattedTotalTime}</span>
              </>
            )}
            <span className="stats-dot">•</span>
            <span className="lossless-badge">HI-FI LOSSLESS</span>
          </div>
        </div>
      </div>

      {/* Spotify-standard Action Bar */}
      <div className="playlist-action-bar-modern">
        <button
          className="sp-play-big-circle-btn"
          id="playlist-play-all-btn"
          onClick={handlePlayAll}
          aria-label={isCurrentPlaylistPlaying ? 'Pause Playlist' : 'Play Playlist'}
          disabled={displayTracks.length === 0}
        >
          {isCurrentPlaylistPlaying ? (
            <Pause size={24} fill="#000000" color="#000000" />
          ) : (
            <Play size={24} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
          )}
        </button>

        <button
          className="sp-shuffle-btn-modern"
          onClick={handleShufflePlay}
          id="playlist-shuffle-btn"
          disabled={displayTracks.length === 0}
          title="Shuffle Play"
        >
          <Shuffle size={20} />
        </button>
      </div>

      {/* Spotify Table Header */}
      <div className="sp-table-header-row">
        <span className="col-idx">#</span>
        <span className="col-title">Title</span>
        <span className="col-album hide-mobile">Album</span>
        <span className="col-dur">
          <Clock size={15} />
        </span>
        <span className="col-action"></span>
      </div>

      {/* Tracklist Rows */}
      <div className="playlist-tracks-list-modern">
        {displayTracks.length === 0 ? (
          <div className="playlist-empty-state-modern">
            <Music size={44} color="#71717a" />
            <h3 className="empty-title">This playlist is empty</h3>
            <p className="empty-desc">Search any song and tap (+) to add it directly to this playlist!</p>
          </div>
        ) : (
          displayTracks.map((track, idx) => {
            const isCurrent = currentTrackId === track.id;
            const isFav = favorites.includes(track.id);
            const cover = track.coverUrl || defaultCover;

            return (
              <div
                key={track.id || `pl-track-${idx}`}
                className={`sp-playlist-row ${isCurrent ? 'active-track' : ''}`}
                onClick={() => onPlayTrack(track, displayTracks.slice(idx + 1))}
                id={`pl-track-${track.id || idx}`}
              >
                {/* Index / Play hover / Equalizer */}
                <span className="sp-row-idx">
                  {isCurrent && isPlaying ? (
                    <div className="sp-row-eq">
                      <span /><span /><span />
                    </div>
                  ) : (
                    <>
                      <span className="sp-row-num">{idx + 1}</span>
                      <Play size={13} fill="#ffffff" color="#ffffff" className="sp-row-hover-play" />
                    </>
                  )}
                </span>

                {/* Track Thumbnail & Title Info */}
                <div className="sp-row-main-info">
                  <img
                    src={cover}
                    alt={track.title}
                    className="sp-row-cover"
                    loading="lazy"
                    onError={(e) => { e.target.src = defaultCover; }}
                  />
                  <div className="sp-row-text-col">
                    <span className={`sp-row-title ${isCurrent ? 'active-text' : ''}`}>
                      {track.title}
                    </span>
                    <span className="sp-row-artist">{track.artist}</span>
                  </div>
                </div>

                {/* Album (Desktop) */}
                <span className="sp-row-album hide-mobile">
                  {track.album || 'Single'}
                </span>

                {/* Favorite Heart Button */}
                {onToggleFavorite && (
                  <button
                    type="button"
                    className={`sp-row-fav-btn ${isFav ? 'is-fav' : ''}`}
                    onClick={(e) => {
                      e.stopPropagation();
                      onToggleFavorite(track);
                    }}
                    title={isFav ? 'Remove from Liked Songs' : 'Save to Liked Songs'}
                  >
                    <Heart size={15} fill={isFav ? '#ef4444' : 'none'} color={isFav ? '#ef4444' : 'currentColor'} />
                  </button>
                )}

                {/* Duration */}
                <span className="sp-row-dur">
                  {formatDuration(track.duration)}
                </span>

                {/* Remove track action for custom playlists */}
                {playlist.isCustom && onRemoveTrackFromPlaylist && (
                  <button
                    type="button"
                    className="sp-row-delete-btn"
                    onClick={(e) => {
                      e.stopPropagation();
                      onRemoveTrackFromPlaylist(playlist.id, track.id);
                    }}
                    title="Remove from playlist"
                  >
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
