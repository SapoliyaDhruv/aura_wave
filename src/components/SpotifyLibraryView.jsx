import React, { useState, useMemo } from 'react';
import {
  Plus,
  Search,
  ArrowUpDown,
  Grid,
  List as ListIcon,
  Heart,
  ArrowDownCircle,
  Play,
  Pin,
  Music,
  FolderPlus,
  Radio,
  SlidersHorizontal,
  X
} from 'lucide-react';

export default function SpotifyLibraryView({
  playlists = [],
  favorites = [],
  downloadedTracks = [],
  storageStats = { count: 0, formattedSize: '0 MB' },
  onSelectPlaylist,
  onOpenCreatePlaylist,
  onOpenImportSpotify,
  onOpenOffline,
  likedSongsPlaylist,
  isMobile = false
}) {
  const [filterType, setFilterType] = useState('all'); // 'all' | 'playlists' | 'downloaded' | 'by-you'
  const [sortBy, setSortBy] = useState('recents'); // 'recents' | 'alphabetical' | 'count'
  const [searchQuery, setSearchQuery] = useState('');
  const [viewMode, setViewMode] = useState('list'); // 'list' | 'grid'

  // Filtered & Sorted Playlists
  const processedPlaylists = useMemo(() => {
    let list = [...playlists];

    // Filter by type
    if (filterType === 'by-you') {
      list = list.filter(p => p.isCustom && !p.isImported);
    } else if (filterType === 'downloaded') {
      // In downloaded view, only show playlists that have tracks or go to offline
    }

    // Filter by search query
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase().trim();
      list = list.filter(p =>
        (p.name || '').toLowerCase().includes(q) ||
        (p.description || '').toLowerCase().includes(q)
      );
    }

    // Sort
    if (sortBy === 'alphabetical') {
      list.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    } else if (sortBy === 'count') {
      list.sort((a, b) => (b.tracks?.length || 0) - (a.tracks?.length || 0));
    }

    return list;
  }, [playlists, filterType, searchQuery, sortBy]);

  const defaultCover = 'https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=600&auto=format&fit=crop&q=80';

  return (
    <div className={`spotify-library-container ${isMobile ? 'mobile' : 'desktop'}`} id="spotify-library-view">
      {/* Header Row */}
      <div className="sp-lib-header-row">
        <div className="sp-lib-title-group">
          <h1 className="sp-lib-heading">Your Library</h1>
          <span className="sp-lib-badge">SAVED ON DEVICE</span>
        </div>

        <div className="sp-lib-header-actions">
          <button
            type="button"
            className="sp-lib-btn-primary"
            onClick={onOpenCreatePlaylist}
            title="Create Custom Playlist"
          >
            <Plus size={16} />
            <span>New Playlist</span>
          </button>

          {onOpenImportSpotify && (
            <button
              type="button"
              className="sp-lib-btn-spotify"
              onClick={onOpenImportSpotify}
              title="Import Playlist from Spotify"
            >
              <span>Import Spotify</span>
            </button>
          )}
        </div>
      </div>

      {/* Filter Chips Bar (Spotify Style) */}
      <div className="sp-lib-chips-row">
        <button
          type="button"
          className={`sp-lib-chip ${filterType === 'all' ? 'active' : ''}`}
          onClick={() => setFilterType('all')}
        >
          <span>All</span>
        </button>
        <button
          type="button"
          className={`sp-lib-chip ${filterType === 'playlists' ? 'active' : ''}`}
          onClick={() => setFilterType('playlists')}
        >
          <span>Playlists</span>
        </button>
        <button
          type="button"
          className={`sp-lib-chip ${filterType === 'by-you' ? 'active' : ''}`}
          onClick={() => setFilterType('by-you')}
        >
          <span>By You</span>
        </button>
        <button
          type="button"
          className={`sp-lib-chip ${filterType === 'downloaded' ? 'active' : ''}`}
          onClick={() => onOpenOffline ? onOpenOffline() : setFilterType('downloaded')}
        >
          <span>Downloaded ({downloadedTracks.length})</span>
        </button>
      </div>

      {/* Search & Sort Controls Bar */}
      <div className="sp-lib-controls-bar">
        <div className="sp-lib-search-box">
          <Search size={15} className="sp-lib-search-icon" />
          <input
            type="text"
            placeholder="Search in Your Library"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="sp-lib-search-input"
          />
          {searchQuery && (
            <button
              type="button"
              className="sp-lib-search-clear"
              onClick={() => setSearchQuery('')}
            >
              <X size={14} />
            </button>
          )}
        </div>

        <div className="sp-lib-sort-actions">
          <div className="sp-lib-sort-dropdown">
            <span className="sort-label">Sort by:</span>
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value)}
              className="sp-lib-select"
            >
              <option value="recents">Recents</option>
              <option value="alphabetical">Alphabetical</option>
              <option value="count">Song Count</option>
            </select>
          </div>

          <div className="sp-lib-view-toggle">
            <button
              type="button"
              className={`view-btn ${viewMode === 'list' ? 'active' : ''}`}
              onClick={() => setViewMode('list')}
              title="List view"
            >
              <ListIcon size={16} />
            </button>
            <button
              type="button"
              className={`view-btn ${viewMode === 'grid' ? 'active' : ''}`}
              onClick={() => setViewMode('grid')}
              title="Grid view"
            >
              <Grid size={16} />
            </button>
          </div>
        </div>
      </div>

      {/* ================================================================= */}
      {/* 1. LIST VIEW (Spotify Standard)                                   */}
      {/* ================================================================= */}
      {viewMode === 'list' ? (
        <div className="sp-lib-list-container">
          {/* PINNED: Liked Songs Card */}
          {filterType !== 'by-you' && (
            <div
              className="sp-lib-list-row pinned-row"
              onClick={() => onSelectPlaylist(likedSongsPlaylist)}
            >
              <div className="sp-lib-liked-cover-box">
                <Heart size={24} fill="#ffffff" color="#ffffff" />
              </div>
              <div className="sp-lib-row-info">
                <div className="sp-lib-row-title-line">
                  <span className="sp-lib-row-title">Liked Songs</span>
                  <span className="sp-lib-pinned-badge" title="Pinned to top">
                    <Pin size={11} fill="#1DB954" color="#1DB954" />
                    <span>Pinned</span>
                  </span>
                </div>
                <span className="sp-lib-row-sub">
                  Playlist • {favorites.length} songs
                </span>
              </div>
              <div className="sp-lib-row-play">
                <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
              </div>
            </div>
          )}

          {/* PINNED: Downloaded Music Card */}
          {filterType !== 'by-you' && (
            <div
              className="sp-lib-list-row pinned-row"
              onClick={onOpenOffline}
            >
              <div className="sp-lib-dl-cover-box">
                <ArrowDownCircle size={26} color="#ffffff" />
              </div>
              <div className="sp-lib-row-info">
                <div className="sp-lib-row-title-line">
                  <span className="sp-lib-row-title">Downloaded Music</span>
                  <span className="sp-lib-offline-badge">
                    <span>Offline Vault</span>
                  </span>
                </div>
                <span className="sp-lib-row-sub">
                  Playlist • {downloadedTracks.length} songs • {storageStats.formattedSize || '0 MB'} • Zero Internet
                </span>
              </div>
              <div className="sp-lib-row-play">
                <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
              </div>
            </div>
          )}

          {/* Playlists */}
          {processedPlaylists.map(pl => {
            const trackCount = pl.tracks?.length || 0;
            const subtitle = `Playlist • ${trackCount} songs${pl.isImported ? ' • Spotify' : (pl.isCustom ? ' • By You' : '')}`;
            const cover = pl.cover || defaultCover;

            return (
              <div
                key={pl.id}
                className="sp-lib-list-row"
                onClick={() => onSelectPlaylist(pl)}
              >
                <img
                  src={cover}
                  alt={pl.name}
                  className="sp-lib-row-cover"
                  loading="lazy"
                  onError={(e) => { e.target.src = defaultCover; }}
                />
                <div className="sp-lib-row-info">
                  <span className="sp-lib-row-title">{pl.name}</span>
                  <span className="sp-lib-row-sub">{subtitle}</span>
                </div>
                <div className="sp-lib-row-play">
                  <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                </div>
              </div>
            );
          })}

          {processedPlaylists.length === 0 && filterType === 'by-you' && (
            <div className="sp-lib-empty-box">
              <FolderPlus size={36} color="#71717a" />
              <h4 className="empty-heading">No playlists created yet</h4>
              <p className="empty-text">Tap "New Playlist" above to create your first personal music collection.</p>
              <button
                type="button"
                className="sp-lib-btn-primary"
                onClick={onOpenCreatePlaylist}
                style={{ marginTop: '12px' }}
              >
                <Plus size={16} />
                <span>Create Playlist</span>
              </button>
            </div>
          )}
        </div>
      ) : (
        /* ================================================================= */
        /* 2. GRID VIEW (Spotify Card Grid)                                  */
        /* ================================================================= */
        <div className="sp-lib-grid-container">
          {/* Liked Songs Grid Card */}
          {filterType !== 'by-you' && (
            <div
              className="sp-lib-grid-card liked-card"
              onClick={() => onSelectPlaylist(likedSongsPlaylist)}
            >
              <div className="sp-lib-grid-cover-wrap liked-cover">
                <Heart size={38} fill="#ffffff" color="#ffffff" />
                <div className="sp-lib-grid-hover-play">
                  <Play size={20} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                </div>
              </div>
              <div className="sp-lib-grid-meta">
                <span className="grid-title">Liked Songs</span>
                <span className="grid-desc">{favorites.length} songs</span>
              </div>
            </div>
          )}

          {/* Downloaded Grid Card */}
          {filterType !== 'by-you' && (
            <div
              className="sp-lib-grid-card dl-card"
              onClick={onOpenOffline}
            >
              <div className="sp-lib-grid-cover-wrap dl-cover">
                <ArrowDownCircle size={40} color="#ffffff" />
                <div className="sp-lib-grid-hover-play">
                  <Play size={20} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                </div>
              </div>
              <div className="sp-lib-grid-meta">
                <span className="grid-title">Downloaded</span>
                <span className="grid-desc">{downloadedTracks.length} tracks • Offline</span>
              </div>
            </div>
          )}

          {/* Playlists */}
          {processedPlaylists.map(pl => {
            const trackCount = pl.tracks?.length || 0;
            const cover = pl.cover || defaultCover;

            return (
              <div
                key={pl.id}
                className="sp-lib-grid-card"
                onClick={() => onSelectPlaylist(pl)}
              >
                <div className="sp-lib-grid-cover-wrap">
                  <img
                    src={cover}
                    alt={pl.name}
                    className="sp-lib-grid-img"
                    loading="lazy"
                    onError={(e) => { e.target.src = defaultCover; }}
                  />
                  <div className="sp-lib-grid-hover-play">
                    <Play size={20} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                  </div>
                </div>
                <div className="sp-lib-grid-meta">
                  <span className="grid-title">{pl.name}</span>
                  <span className="grid-desc">Playlist • {trackCount} songs</span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

