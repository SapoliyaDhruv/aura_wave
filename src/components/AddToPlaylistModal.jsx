import React, { useState } from 'react';
import { X, Check, Plus, FolderPlus, Music, Search, Sparkles } from 'lucide-react';

export default function AddToPlaylistModal({
  isOpen,
  onClose,
  track,
  playlists = [],
  onAddToPlaylist,
  onOpenCreatePlaylist,
  onOpenCreateNew
}) {
  const [filterQuery, setFilterQuery] = useState('');

  if (!isOpen || !track) return null;

  const handleCreateNew = () => {
    onClose();
    if (onOpenCreateNew) {
      onOpenCreateNew();
    } else if (onOpenCreatePlaylist) {
      onOpenCreatePlaylist();
    }
  };

  const filteredPlaylists = playlists.filter(pl =>
    pl.name?.toLowerCase().includes(filterQuery.toLowerCase())
  );

  return (
    <div className="modal-backdrop-blur add-playlist-backdrop" onClick={onClose} id="add-to-playlist-backdrop">
      <div
        className="add-to-playlist-modal-card"
        onClick={e => e.stopPropagation()}
        id="add-to-playlist-modal"
      >
        {/* Dynamic ambient backdrop aura */}
        <div
          className="add-playlist-ambient-glow"
          style={{
            background: `radial-gradient(circle at 50% 0%, ${track.themeColor || '#1DB954'} 0%, transparent 70%)`
          }}
        />

        {/* Modal Header */}
        <div className="add-playlist-header">
          <div className="add-playlist-header-left">
            <div className="add-playlist-badge-icon">
              <FolderPlus size={18} color="#1DB954" />
            </div>
            <div>
              <h3 className="add-playlist-title">Add to Playlist</h3>
              <span className="add-playlist-subtitle">Choose a destination for this track</span>
            </div>
          </div>
          <button
            className="add-playlist-close-btn"
            onClick={onClose}
            id="add-pl-close-btn"
            aria-label="Close modal"
          >
            <X size={18} />
          </button>
        </div>

        {/* Track Preview Mini Banner */}
        <div className="add-track-preview-banner">
          <div className="preview-cover-wrap">
            <img
              src={track.coverUrl || "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80"}
              alt={track.title}
              className="preview-cover-img"
            />
            <div className="preview-eq-bars">
              <span className="eq-bar" />
              <span className="eq-bar" />
              <span className="eq-bar" />
            </div>
          </div>
          <div className="preview-meta">
            <h4 className="preview-title">{track.title}</h4>
            <p className="preview-artist">{track.artist}</p>
          </div>
        </div>

        {/* Quick Search if more than 3 playlists */}
        {playlists.length > 3 && (
          <div className="add-playlist-search-wrap">
            <Search size={15} className="add-search-icon" />
            <input
              type="text"
              placeholder="Search playlists..."
              value={filterQuery}
              onChange={(e) => setFilterQuery(e.target.value)}
              className="add-playlist-search-input"
            />
          </div>
        )}

        {/* Create New Playlist Card Button */}
        <button
          type="button"
          className="add-create-new-card"
          onClick={handleCreateNew}
          id="add-pl-create-new-btn"
        >
          <div className="create-new-plus-icon-box">
            <Plus size={20} strokeWidth={2.5} />
          </div>
          <div className="create-new-text-group">
            <span className="create-new-main-text">Create New Playlist</span>
            <span className="create-new-sub-text">Add to a freshly created mixtape</span>
          </div>
          <Sparkles size={16} className="create-new-sparkle" />
        </button>

        {/* Playlists List */}
        <div className="add-playlists-scroll-list">
          {filteredPlaylists.length === 0 ? (
            <div className="add-playlist-empty-state">
              <Music size={28} color="#71717a" />
              <p>No playlists found</p>
            </div>
          ) : (
            filteredPlaylists.map(pl => {
              const hasTrack = Array.isArray(pl.tracks) && pl.tracks.some(t => {
                if (typeof t === 'string') return t === track.id;
                return t.id === track.id || (t.title === track.title && t.artist === track.artist);
              });

              return (
                <div
                  key={pl.id}
                  className={`modern-playlist-row ${hasTrack ? 'is-already-added' : ''}`}
                  onClick={() => {
                    if (!hasTrack) {
                      onAddToPlaylist(pl.id, track);
                      onClose();
                    }
                  }}
                  role="button"
                  tabIndex={0}
                >
                  <div className="pl-row-cover-wrap">
                    <img
                      src={pl.cover || "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80"}
                      alt={pl.name}
                      className="pl-row-cover"
                    />
                  </div>

                  <div className="pl-row-info">
                    <span className="pl-row-name">{pl.name}</span>
                    <span className="pl-row-count-badge">
                      {pl.tracks?.length || 0} tracks
                    </span>
                  </div>

                  <div className="pl-row-action-pill">
                    {hasTrack ? (
                      <span className="pl-added-chip">
                        <Check size={14} strokeWidth={2.5} />
                        <span>Added</span>
                      </span>
                    ) : (
                      <span className="pl-add-plus-btn" title="Add track to playlist">
                        <Plus size={18} strokeWidth={2.5} />
                      </span>
                    )}
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
