import React, { useState, useRef } from 'react';
import {
  X,
  Trophy,
  Flame,
  Heart,
  Music,
  Play,
  Sparkles,
  TrendingUp,
  BarChart3,
  Headphones,
  Copy,
  Check,
  ArrowDownCircle,
  Cloud,
  RefreshCw,
  Link2,
  Download,
  Upload,
  User,
  ShieldCheck,
  Edit3,
  Settings,
  FolderArchive,
  Volume2
} from 'lucide-react';
import { syncService } from '../services/syncService';
import { offlineStorage } from '../services/offlineStorage';
import {
  profileService,
  AVAILABLE_AVATARS,
  AVAILABLE_LANGUAGES,
  POPULAR_ARTISTS
} from '../services/profileService';

export default function TasteProfileModal({
  isOpen,
  onClose,
  tasteProfile,
  frequentlyPlayed = [],
  onPlayTrack,
  showToast,
  onDataChanged,
  onProfileUpdated
}) {
  if (!isOpen) return null;

  // Active Tab: 'taste' | 'backup' | 'stats' | 'settings'
  const [activeTab, setActiveTab] = useState('taste');

  // Profile State
  const [activeProfile, setActiveProfile] = useState(() => profileService.getActiveProfile());
  const [editName, setEditName] = useState(() => activeProfile.name || 'Music Lover');
  const [isEditingName, setIsEditingName] = useState(false);
  const [selectedAvatar, setSelectedAvatar] = useState(() => activeProfile.avatar || '🎧');
  const [selectedLangs, setSelectedLangs] = useState(() => activeProfile.preferredLanguages || ['Hindi', 'Punjabi']);
  const [selectedArtists, setSelectedArtists] = useState(() => activeProfile.favoriteArtists || ['Arijit Singh', 'AP Dhillon']);

  // Cloud Sync state
  const [syncCode, setSyncCode] = useState(() => syncService.getSyncCode());
  const [partnerCode, setPartnerCode] = useState('');
  const [copiedLink, setCopiedLink] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncSuccessMsg, setSyncSuccessMsg] = useState('');

  // File Backup state
  const fileInputRef = useRef(null);
  const [isRestoringFile, setIsRestoringFile] = useState(false);

  // Auto-Download state (defaults to OFF)
  const [autoDownload, setAutoDownload] = useState(() => offlineStorage.isAutoDownloadEnabled());
  const [audioQuality, setAudioQuality] = useState(() => localStorage.getItem('aurawave_audio_quality') || '320k');

  const {
    totalPlays = 0,
    uniqueSongsPlayed = 0,
    topArtist = 'Arijit Singh',
    topGenre = 'Bollywood',
    topArtists = [],
    topGenres = [],
    mostPlayedTrack = null,
    favoritesCount = 0
  } = tasteProfile || {};

  const maxGenreScore = topGenres.length > 0 ? Math.max(...topGenres.map(g => g.score), 1) : 1;

  // Toggle Language
  const handleToggleLang = (langName) => {
    setSelectedLangs(prev => {
      if (prev.includes(langName)) {
        if (prev.length === 1) return prev;
        return prev.filter(l => l !== langName);
      }
      return [...prev, langName];
    });
  };

  // Toggle Artist
  const handleToggleArtist = (artistName) => {
    setSelectedArtists(prev => {
      if (prev.includes(artistName)) {
        if (prev.length === 1) return prev;
        return prev.filter(a => a !== artistName);
      }
      return [...prev, artistName];
    });
  };

  // Save Profile
  const handleSaveProfile = () => {
    const finalName = editName.trim() || 'Music Lover';
    const updated = {
      ...activeProfile,
      name: finalName,
      avatar: selectedAvatar,
      preferredLanguages: selectedLangs,
      favoriteArtists: selectedArtists,
      isConfigured: true,
      updatedAt: Date.now()
    };
    profileService.saveActiveProfile(updated);
    setActiveProfile(updated);
    setIsEditingName(false);
    if (showToast) showToast(`✨ Profile saved! Suggestions updated for ${finalName}`);
    if (onProfileUpdated) onProfileUpdated(updated);
    if (onDataChanged) onDataChanged();
  };

  // Option 1: Cloud Sync
  const handleCopyLink = () => {
    const link = syncService.generateShareableLink();
    if (navigator.clipboard) {
      navigator.clipboard.writeText(link).then(() => {
        setCopiedLink(true);
        if (showToast) showToast('📋 Sync link copied! Open on mobile to pair.');
        setTimeout(() => setCopiedLink(false), 3000);
      });
    } else {
      setCopiedLink(true);
      setTimeout(() => setCopiedLink(false), 3000);
    }
  };

  const handlePushCurrentState = async () => {
    setIsSyncing(true);
    setSyncSuccessMsg('');
    const res = await syncService.pushToCloud(syncCode);
    setIsSyncing(false);
    if (res.ok) {
      setSyncSuccessMsg('Cloud backup synced! Code: ' + syncCode);
      if (showToast) showToast('☁️ Saved to Cloud Sync successfully!');
    } else {
      setSyncSuccessMsg('Saved locally.');
    }
  };

  const handlePairDevice = async (e) => {
    e.preventDefault();
    if (!partnerCode.trim()) return;
    setIsSyncing(true);
    setSyncSuccessMsg('');
    const res = await syncService.pullFromCloud(partnerCode.trim());
    setIsSyncing(false);
    if (res.ok) {
      const newCode = partnerCode.trim().toUpperCase();
      setSyncCode(newCode);
      setSyncSuccessMsg(`✨ Successfully restored library with code ${newCode}!`);
      if (showToast) showToast(`✨ Restored library from code ${newCode}!`);
      if (onDataChanged) onDataChanged();
      const fresh = profileService.getActiveProfile();
      setActiveProfile(fresh);
      setEditName(fresh.name || 'Music Lover');
      setSelectedAvatar(fresh.avatar || '🎧');
      if (onProfileUpdated) onProfileUpdated(fresh);
    } else {
      setSyncSuccessMsg(res.error || 'Could not find data for this code.');
      if (showToast) showToast('❌ Sync code not found or invalid');
    }
  };

  // Option 2: File Export & Import
  const handleExportFile = async () => {
    const res = await profileService.downloadBackupFile();
    if (res.ok) {
      if (res.method === 'share') {
        if (showToast) showToast('📤 Backup exported! Save to Downloads, Drive or Files.');
      } else if (res.method === 'download' || res.method === 'datauri') {
        if (showToast) showToast('💾 Backup file saved to your device Downloads folder!');
      } else if (res.method === 'cancelled') {
        // User cancelled native dialog
      }
    } else {
      const copied = await profileService.copyBackupToClipboard();
      if (copied && showToast) {
        showToast('📋 Backup copied to clipboard! Paste into a text file to save.');
      } else if (showToast) {
        showToast('❌ Failed to export backup file');
      }
    }
  };

  const handleCopyBackupJson = async () => {
    const success = await profileService.copyBackupToClipboard();
    if (success) {
      if (showToast) showToast('📋 Full Backup JSON copied to clipboard!');
    } else {
      if (showToast) showToast('❌ Failed to copy backup to clipboard');
    }
  };

  const handleImportFileClick = () => {
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
      fileInputRef.current.click();
    }
  };

  const handleFileChange = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setIsRestoringFile(true);
    const result = await profileService.readBackupFile(file);
    setIsRestoringFile(false);
    if (result.ok) {
      if (showToast) showToast('✨ Library & Profile successfully restored from backup file!');
      if (onDataChanged) onDataChanged();
      const freshProf = profileService.getActiveProfile();
      setActiveProfile(freshProf);
      setEditName(freshProf.name || 'Music Lover');
      setSelectedAvatar(freshProf.avatar || '🎧');
      setSelectedLangs(freshProf.preferredLanguages || []);
      setSelectedArtists(freshProf.favoriteArtists || []);
      setSyncCode(syncService.getSyncCode());
      if (onProfileUpdated) onProfileUpdated(freshProf);
    } else {
      if (showToast) showToast(`❌ Error: ${result.error || 'Invalid backup file'}`);
    }
  };

  // Auto-Download Toggle
  const handleToggleAutoDownload = () => {
    const next = !autoDownload;
    setAutoDownload(next);
    offlineStorage.setAutoDownloadEnabled(next);
    if (showToast) {
      showToast(next ? '💾 Auto-download ENABLED for played songs' : '💾 Auto-download OFF (Default) • Saving storage');
    }
  };

  const handleSelectQuality = (q) => {
    setAudioQuality(q);
    localStorage.setItem('aurawave_audio_quality', q);
    if (showToast) showToast(`🔊 Audio streaming quality set to ${q.toUpperCase()}`);
  };

  return (
    <div className="modal-backdrop-blur profile-modal-backdrop" onClick={onClose} id="taste-profile-modal-overlay">
      <div
        className="taste-profile-modal-content modern-profile-stage"
        onClick={e => e.stopPropagation()}
        id="taste-profile-modal"
      >
        {/* Dynamic ambient backdrop aura */}
        <div className="profile-stage-ambient-aura" />

        {/* Mobile drag handle for native sheet feel */}
        <div className="mobile-sheet-drag-handle" />

        {/* ============================================================ */}
        {/* HERO HEADER: Profile Identity & Quick Stats                  */}
        {/* ============================================================ */}
        <div className="profile-hero-banner">
          <div className="profile-hero-top-actions">
            <button
              type="button"
              className="profile-hero-header-save-btn"
              onClick={handleSaveProfile}
              title="Save Profile & Refresh Suggestions"
            >
              <Check size={14} />
              <span>Save</span>
            </button>
            <button
              type="button"
              className="profile-modal-close-icon"
              onClick={onClose}
              aria-label="Close Profile"
            >
              <X size={18} />
            </button>
          </div>

          <div className="profile-hero-main-row">
            <div className="profile-hero-avatar-wrap">
              <span className="profile-hero-avatar-emoji">{selectedAvatar}</span>
              <div className="profile-hero-avatar-ring" />
              <div className="avatar-sparkle-dot" />
            </div>

            <div className="profile-hero-meta">
              <div className="profile-hero-title-row">
                {isEditingName ? (
                  <div className="profile-inline-edit-wrap">
                    <input
                      type="text"
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                      className="profile-inline-name-input"
                      maxLength={24}
                      autoFocus
                    />
                    <button
                      type="button"
                      className="profile-inline-save-btn"
                      onClick={handleSaveProfile}
                    >
                      <Check size={14} />
                      <span>Save</span>
                    </button>
                  </div>
                ) : (
                  <div className="profile-name-display-wrap">
                    <h2 className="profile-hero-name">{activeProfile.name || 'Music Lover'}</h2>
                    <button
                      type="button"
                      className="profile-edit-name-icon-btn"
                      onClick={() => setIsEditingName(true)}
                      title="Edit display name"
                    >
                      <Edit3 size={15} />
                    </button>
                  </div>
                )}
              </div>

              <div className="profile-status-badges-row">
                <div className="profile-privacy-status-pill">
                  <ShieldCheck size={13} color="#4ade80" />
                  <span>100% On-Device Private</span>
                </div>
                <div className="profile-vip-pill">
                  <Sparkles size={12} color="#c084fc" />
                  <span>Hi-Fi VIP</span>
                </div>
              </div>
            </div>
          </div>

          {/* Quick Metric Pills */}
          <div className="profile-metrics-strip">
            <div className="profile-metric-item">
              <div className="metric-icon-bubble heart">
                <Heart size={13} fill="#ef4444" color="#ef4444" />
              </div>
              <div className="metric-val-group">
                <span className="metric-val">{favoritesCount}</span>
                <span className="metric-label">Liked</span>
              </div>
            </div>

            <div className="metric-sep" />

            <div className="profile-metric-item">
              <div className="metric-icon-bubble green">
                <Headphones size={13} color="#1DB954" />
              </div>
              <div className="metric-val-group">
                <span className="metric-val">{totalPlays}</span>
                <span className="metric-label">Streams</span>
              </div>
            </div>

            <div className="metric-sep" />

            <div className="profile-metric-item">
              <div className="metric-icon-bubble blue">
                <Cloud size={13} color="#60a5fa" />
              </div>
              <div className="metric-val-group">
                <span className="metric-val code-font">{syncCode}</span>
                <span className="metric-label">Sync Key</span>
              </div>
            </div>
          </div>
        </div>

        {/* ============================================================ */}
        {/* MODERN SEGMENTED TABS                                        */}
        {/* ============================================================ */}
        <div className="profile-nav-tabs-segmented">
          <button
            type="button"
            className={`segmented-tab ${activeTab === 'taste' ? 'active' : ''}`}
            onClick={() => setActiveTab('taste')}
          >
            <Music size={15} />
            <span>Taste & Identity</span>
          </button>
          <button
            type="button"
            className={`segmented-tab ${activeTab === 'backup' ? 'active' : ''}`}
            onClick={() => setActiveTab('backup')}
          >
            <FolderArchive size={15} />
            <span>Cloud & Backup</span>
          </button>
          <button
            type="button"
            className={`segmented-tab ${activeTab === 'stats' ? 'active' : ''}`}
            onClick={() => setActiveTab('stats')}
          >
            <BarChart3 size={15} />
            <span>Listening Stats</span>
          </button>
          <button
            type="button"
            className={`segmented-tab ${activeTab === 'settings' ? 'active' : ''}`}
            onClick={() => setActiveTab('settings')}
          >
            <Settings size={15} />
            <span>Preferences</span>
          </button>
        </div>

        {/* Hidden file input for file restoration */}
        <input
          type="file"
          ref={fileInputRef}
          style={{ display: 'none' }}
          accept=".json,.aura"
          onChange={handleFileChange}
        />

        {/* ============================================================ */}
        {/* TAB 1: TASTE & IDENTITY                                      */}
        {/* ============================================================ */}
        {activeTab === 'taste' && (
          <div className="redesigned-tab-pane">
            {/* Avatar Selector */}
            <div className="redesigned-section-box">
              <div className="redesigned-box-header">
                <div>
                  <span className="section-mini-tag">AVATAR VIBE</span>
                  <h4 className="section-heading">Choose Your Identity</h4>
                </div>
                <span className="selection-badge">{selectedAvatar} Selected</span>
              </div>
              <div className="avatars-scroll-grid">
                {AVAILABLE_AVATARS.map(avatar => (
                  <button
                    key={avatar}
                    type="button"
                    className={`avatar-pill-btn ${selectedAvatar === avatar ? 'selected' : ''}`}
                    onClick={() => {
                      setSelectedAvatar(avatar);
                      profileService.saveActiveProfile({ ...activeProfile, avatar });
                      if (onProfileUpdated) onProfileUpdated({ ...activeProfile, avatar });
                    }}
                  >
                    <span>{avatar}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* Preferred Languages */}
            <div className="redesigned-section-box">
              <div className="redesigned-box-header">
                <div className="header-left-col">
                  <span className="section-mini-tag">LANGUAGES & CULTURES</span>
                  <h4 className="section-heading">Sound Languages</h4>
                  <p className="section-subtext">Populates Home recommendations with music from these languages</p>
                </div>
                <span className="selection-badge">{selectedLangs.length} Selected</span>
              </div>
              <div className="taste-chips-grid">
                {AVAILABLE_LANGUAGES.map(lang => {
                  const isSelected = selectedLangs.includes(lang.name);
                  return (
                    <button
                      key={lang.id}
                      type="button"
                      className={`taste-chip-card ${isSelected ? 'active' : ''}`}
                      onClick={() => handleToggleLang(lang.name)}
                    >
                      <span className="chip-flag">{lang.flag}</span>
                      <span className="chip-name">{lang.name}</span>
                      {isSelected && <Check size={14} className="chip-check-icon" />}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Favorite Artists */}
            <div className="redesigned-section-box">
              <div className="redesigned-box-header">
                <div className="header-left-col">
                  <span className="section-mini-tag">FAVORITE ARTISTS</span>
                  <h4 className="section-heading">Artists You Follow</h4>
                  <p className="section-subtext">Powers your "Made for You" mix and smart autoplay flow</p>
                </div>
                <span className="selection-badge">{selectedArtists.length} Selected</span>
              </div>
              <div className="artists-chips-grid">
                {POPULAR_ARTISTS.map(artist => {
                  const isSelected = selectedArtists.includes(artist.name);
                  return (
                    <button
                      key={artist.name}
                      type="button"
                      className={`artist-chip-card ${isSelected ? 'active' : ''}`}
                      onClick={() => handleToggleArtist(artist.name)}
                    >
                      <div className="artist-chip-info">
                        <span className="artist-name">{artist.name}</span>
                        <span className="artist-genre">{artist.genre}</span>
                      </div>
                      <div className="artist-check-dot">
                        {isSelected && <Check size={13} color="#ffffff" />}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Sticky Save Action */}
            <div className="redesigned-save-row">
              <button
                type="button"
                className="redesigned-save-btn"
                onClick={handleSaveProfile}
              >
                <Sparkles size={16} />
                <span>Save Taste & Refresh Home Mix</span>
              </button>
            </div>
          </div>
        )}

        {/* ============================================================ */}
        {/* TAB 2: CLOUD & BACKUP                                        */}
        {/* ============================================================ */}
        {activeTab === 'backup' && (
          <div className="redesigned-tab-pane">
            <div className="recovery-notice-banner">
              <div className="recovery-notice-icon">
                <ShieldCheck size={20} color="#38bdf8" />
              </div>
              <div>
                <h4 className="recovery-notice-title">Disaster Recovery Guarantee</h4>
                <p className="recovery-notice-desc">
                  Even if you delete the APK, switch phones, or clear cache, restore everything in seconds with <strong>zero server data collection</strong>.
                </p>
              </div>
            </div>

            {/* OPTION 1: Cloud Recovery Code */}
            <div className="recovery-card option-cloud">
              <div className="recovery-card-header">
                <div className="recovery-header-icon-wrap cloud">
                  <Cloud size={20} color="#60a5fa" />
                </div>
                <div>
                  <div className="recovery-pill-row">
                    <span className="recovery-badge cloud">OPTION 1 • INSTANT CLOUD SYNC</span>
                  </div>
                  <h4 className="recovery-title">6-Character Recovery Code</h4>
                  <p className="recovery-desc">Encrypted cloud sync between your Web browser and Mobile App.</p>
                </div>
              </div>

              <div className="recovery-code-display-box">
                <div className="code-text-col">
                  <span className="code-label">YOUR PERMANENT RECOVERY CODE</span>
                  <span className="code-value">{syncCode}</span>
                </div>
                <div className="code-buttons-col">
                  <button
                    type="button"
                    className="recovery-action-btn primary"
                    onClick={handleCopyLink}
                  >
                    {copiedLink ? <Check size={15} /> : <Copy size={15} />}
                    <span>{copiedLink ? 'Link Copied!' : 'Copy Mobile Link'}</span>
                  </button>
                  <button
                    type="button"
                    className="recovery-action-btn secondary"
                    onClick={handlePushCurrentState}
                    disabled={isSyncing}
                  >
                    <RefreshCw size={15} className={isSyncing ? 'sync-spin' : ''} />
                    <span>{isSyncing ? 'Syncing...' : 'Backup Now'}</span>
                  </button>
                </div>
              </div>

              <form className="recovery-restore-form" onSubmit={handlePairDevice}>
                <input
                  type="text"
                  placeholder="Enter code to restore (e.g. AURA-8492)"
                  value={partnerCode}
                  onChange={(e) => setPartnerCode(e.target.value.toUpperCase())}
                  className="recovery-code-input"
                  maxLength={12}
                />
                <button
                  type="submit"
                  className="recovery-restore-btn"
                  disabled={!partnerCode.trim() || isSyncing}
                >
                  <Link2 size={15} />
                  <span>Restore via Code</span>
                </button>
              </form>

              {syncSuccessMsg && (
                <div className="recovery-feedback-pill">
                  <span>{syncSuccessMsg}</span>
                </div>
              )}
            </div>

            {/* OPTION 2: 1-Tap Offline File Export & Import */}
            <div className="recovery-card option-file">
              <div className="recovery-card-header">
                <div className="recovery-header-icon-wrap green">
                  <FolderArchive size={20} color="#4ade80" />
                </div>
                <div>
                  <div className="recovery-pill-row">
                    <span className="recovery-badge green">OPTION 2 • 100% OFFLINE</span>
                  </div>
                  <h4 className="recovery-title">1-Tap Offline Backup File (APK Deletion Proof)</h4>
                  <p className="recovery-desc">
                    Saves a backup to your phone's <strong>Downloads</strong> folder. Files in Downloads are <strong>NEVER deleted</strong> when you uninstall an APK.
                  </p>
                </div>
              </div>

              <div className="file-actions-grid">
                <button
                  type="button"
                  className="file-card-action-btn export"
                  onClick={handleExportFile}
                >
                  <Download size={18} />
                  <div className="btn-text-wrap">
                    <span className="btn-main">Export Backup File (.json)</span>
                    <span className="btn-sub">Save to device downloads</span>
                  </div>
                </button>

                <button
                  type="button"
                  className="file-card-action-btn import"
                  onClick={handleImportFileClick}
                  disabled={isRestoringFile}
                >
                  <Upload size={18} />
                  <div className="btn-text-wrap">
                    <span className="btn-main">{isRestoringFile ? 'Restoring...' : 'Restore from File'}</span>
                    <span className="btn-sub">Import saved .json backup</span>
                  </div>
                </button>

                <button
                  type="button"
                  className="file-card-action-btn copy-json"
                  onClick={handleCopyBackupJson}
                >
                  <Copy size={18} />
                  <div className="btn-text-wrap">
                    <span className="btn-main">Copy Backup to Clipboard</span>
                    <span className="btn-sub">Raw JSON backup text</span>
                  </div>
                </button>
              </div>

              <div className="recovery-pro-tip">
                <span>💡 <strong>Pro Tip:</strong> Reinstalled the APK? Tap "Restore from File" and pick your backup from the Downloads folder to restore 100% of your liked songs and playlists in 1 second!</span>
              </div>
            </div>
          </div>
        )}

        {/* ============================================================ */}
        {/* TAB 3: LISTENING STATS                                       */}
        {/* ============================================================ */}
        {activeTab === 'stats' && (
          <div className="redesigned-tab-pane">
            <div className="stats-metric-cards-grid">
              <div className="stat-card">
                <div className="stat-icon-wrap green">
                  <Headphones size={20} color="#1DB954" />
                </div>
                <div className="stat-meta">
                  <span className="stat-num">{totalPlays}</span>
                  <span className="stat-label">Total Plays</span>
                </div>
              </div>

              <div className="stat-card">
                <div className="stat-icon-wrap purple">
                  <Music size={20} color="#a78bfa" />
                </div>
                <div className="stat-meta">
                  <span className="stat-num capitalize">{topGenre}</span>
                  <span className="stat-label">Top Genre</span>
                </div>
              </div>

              <div className="stat-card">
                <div className="stat-icon-wrap red">
                  <Heart size={20} color="#ef4444" fill="#ef4444" />
                </div>
                <div className="stat-meta">
                  <span className="stat-num">{favoritesCount}</span>
                  <span className="stat-label">Liked Songs</span>
                </div>
              </div>

              <div className="stat-card">
                <div className="stat-icon-wrap cyan">
                  <TrendingUp size={20} color="#06b6d4" />
                </div>
                <div className="stat-meta">
                  <span className="stat-num">{uniqueSongsPlayed}</span>
                  <span className="stat-label">Unique Tracks</span>
                </div>
              </div>
            </div>

            {/* Champion Song */}
            {mostPlayedTrack && (
              <div className="champion-track-banner">
                <div className="champion-top-tag">
                  <Flame size={13} color="#f97316" />
                  <span>YOUR #1 MOST STREAMED TRACK</span>
                </div>
                <div className="champion-body">
                  <img
                    src={mostPlayedTrack.coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'}
                    alt={mostPlayedTrack.title}
                    className="champion-cover"
                  />
                  <div className="champion-info">
                    <h4 className="champion-title">{mostPlayedTrack.title}</h4>
                    <p className="champion-artist">{mostPlayedTrack.artist}</p>
                    <span className="champion-stream-count">
                      Streamed {mostPlayedTrack.playCount || 1} times
                    </span>
                  </div>
                  <button
                    type="button"
                    className="champion-play-btn"
                    onClick={() => {
                      onPlayTrack(mostPlayedTrack);
                      onClose();
                    }}
                    title="Play Track"
                  >
                    <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                    <span>Play Now</span>
                  </button>
                </div>
              </div>
            )}

            {/* Genre Breakdown */}
            {topGenres.length > 0 && (
              <div className="redesigned-section-box">
                <div className="redesigned-box-header">
                  <h4 className="section-heading">Genre Breakdown</h4>
                </div>
                <div className="genre-bars-list">
                  {topGenres.slice(0, 4).map((g, idx) => {
                    const percent = Math.round((g.score / maxGenreScore) * 100);
                    return (
                      <div key={idx} className="genre-bar-row">
                        <div className="genre-bar-meta">
                          <span className="genre-name capitalize">{g.name}</span>
                          <span className="genre-count">{g.score} streams</span>
                        </div>
                        <div className="genre-progress-track">
                          <div
                            className="genre-progress-fill"
                            style={{
                              width: `${percent}%`,
                              background: idx === 0 ? 'linear-gradient(90deg, #1DB954, #10b981)' :
                                          idx === 1 ? 'linear-gradient(90deg, #8b5cf6, #a855f7)' :
                                          idx === 2 ? 'linear-gradient(90deg, #ec4899, #f43f5e)' :
                                                      'linear-gradient(90deg, #06b6d4, #3b82f6)'
                            }}
                          />
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Play Mix Button */}
            {frequentlyPlayed.length > 0 && (
              <div className="stats-play-mix-footer">
                <button
                  type="button"
                  className="stats-mix-btn"
                  onClick={() => {
                    onPlayTrack(frequentlyPlayed[0], frequentlyPlayed.slice(1));
                    onClose();
                  }}
                >
                  <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                  <span>Play Your "On Repeat" Mix ({frequentlyPlayed.length} Tracks)</span>
                </button>
              </div>
            )}
          </div>
        )}

        {/* ============================================================ */}
        {/* TAB 4: SETTINGS                                              */}
        {/* ============================================================ */}
        {activeTab === 'settings' && (
          <div className="redesigned-tab-pane">
            {/* Auto-Download Card */}
            <div className="settings-item-card">
              <div className="settings-item-left">
                <div className="settings-icon-wrap">
                  <ArrowDownCircle size={22} color={autoDownload ? '#1DB954' : '#a1a1aa'} />
                </div>
                <div>
                  <div className="settings-title-row">
                    <h4 className="settings-item-title">Auto-Download Played Songs</h4>
                    <span className={`download-status-badge ${autoDownload ? 'on' : 'off'}`}>
                      {autoDownload ? 'ON' : 'Default: OFF'}
                    </span>
                  </div>
                  <p className="settings-item-desc">
                    {autoDownload
                      ? 'Music you listen to is automatically cached offline.'
                      : 'By default, music is only saved offline when you explicitly tap the Download button.'}
                  </p>
                </div>
              </div>
              <button
                type="button"
                className={`settings-toggle-switch ${autoDownload ? 'is-on' : ''}`}
                onClick={handleToggleAutoDownload}
                role="switch"
                aria-checked={autoDownload}
              >
                <span className="toggle-thumb" />
              </button>
            </div>

            {/* Audio Quality Selection */}
            <div className="settings-item-card col">
              <div className="settings-quality-head">
                <div className="settings-icon-wrap">
                  <Volume2 size={22} color="#a855f7" />
                </div>
                <div>
                  <h4 className="settings-item-title">Streaming Audio Quality</h4>
                  <p className="settings-item-desc">Higher bitrate provides master studio sound</p>
                </div>
              </div>
              <div className="quality-pills-row">
                {[
                  { id: '128k', label: '128k', desc: 'Data Saver' },
                  { id: '256k', label: '256k', desc: 'High Quality' },
                  { id: '320k', label: '320k', desc: 'Lossless Master' }
                ].map(q => (
                  <button
                    key={q.id}
                    type="button"
                    className={`quality-pill-btn ${audioQuality === q.id ? 'active' : ''}`}
                    onClick={() => handleSelectQuality(q.id)}
                  >
                    <span className="q-label">{q.label}</span>
                    <span className="q-desc">{q.desc}</span>
                    {audioQuality === q.id && <Check size={14} className="q-check" />}
                  </button>
                ))}
              </div>
            </div>

            {/* Zero-Knowledge Privacy Details */}
            <div className="settings-privacy-box">
              <ShieldCheck size={20} color="#38bdf8" />
              <div>
                <h5 className="privacy-box-title">Zero Developer Tracking Architecture</h5>
                <p className="privacy-box-desc">
                  AuraWave does not sell data, use tracking cookies, or collect your listening history in any developer database. All profiles, custom playlists, and taste analytics live 100% on your device.
                </p>
              </div>
            </div>
          </div>
        )}

        {/* Persistent Sticky Save Bar across all views */}
        <div className="profile-global-sticky-save">
          <button
            type="button"
            className="profile-global-save-btn"
            onClick={handleSaveProfile}
          >
            <Sparkles size={16} />
            <span>Save Profile & Apply Changes</span>
          </button>
        </div>
      </div>
    </div>
  );
}
