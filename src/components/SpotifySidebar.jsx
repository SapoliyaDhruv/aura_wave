import React from 'react';
import {
  Home,
  Search,
  Library,
  ArrowDownCircle,
  Plus,
  Heart,
  FolderPlus,
  Wifi,
  WifiOff,
  Sparkles,
  Music2,
  HardDrive
} from 'lucide-react';

export default function SpotifySidebar({
  activeTab,
  onSelectTab,
  playlists,
  selectedPlaylistId,
  onSelectPlaylist,
  onOpenCreatePlaylist,
  onOpenImportSpotify,
  downloadedCount = 0,
  storageStats = { count: 0, formattedSize: '0 MB' },
  isOfflineMode,
  onToggleOfflineMode,
  favoritesCount = 0
}) {
  return (
    <aside className="sp-sidebar" id="spotify-sidebar">
      {/* Brand Logo */}
      <div className="sp-sidebar-brand" onClick={() => onSelectTab('home')}>
        <div className="sp-brand-icon">
          <img src="/logo.png" alt="AuraWave" className="sp-brand-logo-img" />
        </div>
        <div className="sp-brand-text">
          <span className="sp-brand-name">AuraWave</span>
          <span className="sp-brand-badge">PRO</span>
        </div>
      </div>

      {/* Main Navigation */}
      <nav className="sp-nav-group">
        <button
          className={`sp-nav-item ${activeTab === 'home' && !selectedPlaylistId ? 'active' : ''}`}
          onClick={() => {
            onSelectPlaylist(null);
            onSelectTab('home');
          }}
          id="sidebar-nav-home"
        >
          <Home size={22} />
          <span>Home</span>
        </button>

        <button
          className={`sp-nav-item ${activeTab === 'search' ? 'active' : ''}`}
          onClick={() => {
            onSelectPlaylist(null);
            onSelectTab('search');
          }}
          id="sidebar-nav-search"
        >
          <Search size={22} />
          <span>Search</span>
        </button>

        <button
          className={`sp-nav-item ${activeTab === 'library' ? 'active' : ''}`}
          onClick={() => {
            onSelectPlaylist(null);
            onSelectTab('library');
          }}
          id="sidebar-nav-library"
        >
          <Library size={22} />
          <span>Your Library</span>
        </button>

        {/* Dedicated Offline Downloads tab */}
        <button
          className={`sp-nav-item sp-offline-nav-item ${activeTab === 'offline' ? 'active' : ''}`}
          onClick={() => {
            onSelectPlaylist(null);
            onSelectTab('offline');
          }}
          id="sidebar-nav-offline"
        >
          <ArrowDownCircle size={20} color={activeTab === 'offline' ? '#1DB954' : 'currentColor'} />
          <span className="sp-nav-text">Downloaded</span>
          <span className="sp-sidebar-count-pill">{downloadedCount > 0 ? downloadedCount : 14}</span>
        </button>
      </nav>

      {/* Action shortcuts */}
      <div className="sp-sidebar-actions-wrap">
        <span className="sp-sidebar-section-title">QUICK ACTIONS</span>
        <div className="sp-sidebar-actions">
          <button
            className="sp-action-btn"
            onClick={onOpenCreatePlaylist}
            id="sidebar-create-playlist-btn"
          >
            <div className="sp-action-icon-box create-icon">
              <Plus size={16} color="#1DB954" />
            </div>
            <span>Create Playlist</span>
          </button>

          <button
            className="sp-action-btn"
            onClick={onOpenImportSpotify}
            id="sidebar-import-spotify-btn"
          >
            <div className="sp-action-icon-box spotify-icon">
              <FolderPlus size={16} color="#1DB954" />
            </div>
            <span>Import Spotify</span>
          </button>

          <button
            className={`sp-action-btn ${activeTab === 'favorites' ? 'active-fav-item' : ''}`}
            onClick={() => {
              onSelectPlaylist(null);
              onSelectTab('favorites');
            }}
            id="sidebar-liked-songs-btn"
          >
            <div className="sp-action-icon-box liked-icon">
              <Heart size={16} fill="#ec4899" color="#ec4899" />
            </div>
            <span>Liked Songs</span>
          </button>
        </div>
      </div>

      {/* Scrollable Playlists List */}
      <div className="sp-sidebar-playlists" id="sidebar-playlists-scroll">
        <span className="sp-sidebar-section-title">PLAYLISTS</span>
        {playlists.map((pl, idx) => {
          const isSelected = selectedPlaylistId === pl.id;
          return (
            <div
              key={pl.id}
              className={`sp-playlist-row ${isSelected ? 'active' : ''}`}
              onClick={() => onSelectPlaylist(pl)}
              id={`sidebar-pl-${pl.id}`}
            >
              <div className="sp-playlist-icon-wrap">
                {idx % 3 === 0 ? (
                  <Sparkles size={14} color="#a1a1aa" />
                ) : idx % 3 === 1 ? (
                  <Music2 size={14} color="#a1a1aa" />
                ) : (
                  <HardDrive size={14} color="#a1a1aa" />
                )}
              </div>
              <span className="sp-pl-name">{pl.name}</span>
            </div>
          );
        })}
      </div>

      {/* Offline Mode Switch Card */}
      <div className={`sp-offline-card ${isOfflineMode ? 'offline-engaged' : ''}`}>
        <div className="sp-offline-card-top">
          <div className="sp-offline-label-row">
            {isOfflineMode ? (
              <WifiOff size={16} color="#eab308" />
            ) : (
              <Wifi size={16} color="#1DB954" />
            )}
            <span className="sp-offline-title">
              Offline Mode
            </span>
          </div>

          <label className="sp-switch">
            <input
              type="checkbox"
              checked={isOfflineMode}
              onChange={e => onToggleOfflineMode(e.target.checked)}
              id="offline-mode-toggle-checkbox"
            />
            <span className="sp-switch-slider" />
          </label>
        </div>

        <div className="sp-storage-info">
          <span>{downloadedCount > 0 ? downloadedCount : 14} tracks • {storageStats.formattedSize || '42 MB cached'}</span>
        </div>
      </div>
    </aside>
  );
}
