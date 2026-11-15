import React from 'react';
import {
  ChevronLeft,
  ChevronRight,
  Search,
  Sparkles,
  Wifi,
  WifiOff,
  User,
  HardDrive,
  Sliders
} from 'lucide-react';

export default function SpotifyTopBar({
  activeTab,
  onGoBack,
  canGoBack,
  onGoForward,
  canGoForward,
  onSelectTab,
  isOfflineMode,
  onToggleOfflineMode,
  audioQuality = '320k',
  onOpenQualityModal,
  storageStats = { count: 0, formattedSize: '0 MB' },
  onOpenTasteModal,
  activeProfile
}) {
  return (
    <header className="sp-topbar" id="spotify-topbar">
      {/* Left: History buttons */}
      <div className="sp-topbar-left">
        <button
          className="sp-history-btn"
          onClick={onGoBack}
          disabled={!canGoBack}
          title="Go back"
          aria-label="Previous page"
        >
          <ChevronLeft size={22} />
        </button>
        <button
          className="sp-history-btn"
          onClick={onGoForward}
          disabled={!canGoForward}
          title="Go forward"
          aria-label="Next page"
        >
          <ChevronRight size={22} />
        </button>
      </div>

      {/* Center: Spotify-Style Quick Search Trigger */}
      <div className="sp-topbar-center">
        <button
          type="button"
          className={`sp-topbar-search-trigger ${activeTab === 'search' ? 'active' : ''}`}
          onClick={() => onSelectTab && onSelectTab('search')}
          title="Search songs, artists, or genres"
          id="topbar-search-quick-btn"
        >
          <Search size={16} className="sp-topbar-search-icon" />
          <span className="sp-topbar-search-text">What do you want to play?</span>
        </button>
      </div>

      {/* Right: Profile Avatar */}
      <div className="sp-topbar-right">
        {/* Offline indicator only if offline */}
        {isOfflineMode && (
          <button
            className="sp-status-pill offline-pill"
            onClick={() => onToggleOfflineMode(!isOfflineMode)}
            title="In Offline Mode (tap to go online)"
            id="topbar-offline-pill"
          >
            <span className="sp-status-dot" />
            <span>Offline ({storageStats.count} Saved)</span>
          </button>
        )}

        {/* User Profile Avatar with custom emoji & glowing pill */}
        <button
          type="button"
          className="sp-profile-btn"
          onClick={onOpenTasteModal}
          title={`${activeProfile?.name || 'Your Profile'} • Taste, Backup & Sync`}
        >
          <div className="sp-avatar-circle-glow">
            <div className="sp-profile-avatar-pill">
              {activeProfile?.avatar ? (
                <span style={{ fontSize: '15px', lineHeight: 1 }}>{activeProfile.avatar}</span>
              ) : (
                <User size={15} color="#ffffff" />
              )}
            </div>
            <span className="sp-profile-top-name">
              {activeProfile?.name && activeProfile.name !== 'Music Lover' ? activeProfile.name : 'Profile'}
            </span>
          </div>
        </button>
      </div>
    </header>
  );
}
