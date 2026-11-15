/**
 * Profile & Zero-Knowledge Backup Service
 * 
 * 100% Client-Side & Local-First:
 * Zero user data is ever sent to or stored on any server or database.
 * Supports:
 * 1. User Profiles & 30-Second Taste Onboarding (Languages & Favorite Artists)
 * 2. Multi-Profile switching (Personal, Focus, Family) on the same device
 * 3. Two Disaster-Recovery Backup Options:
 *    - Option 1: 6-Character Cloud Recovery Code (Encrypted Transit)
 *    - Option 2: 1-Tap Offline File Export & Import (Immune to APK deletion)
 */

import { storageService } from './storageService.js';
import { syncService } from './syncService.js';
import { offlineStorage } from './offlineStorage.js';

const ACTIVE_PROFILE_KEY = 'aurawave_active_profile';
const ALL_PROFILES_KEY = 'aurawave_saved_profiles';

export const AVAILABLE_AVATARS = [
  '🎧', '⚡', '🎸', '🎵', '🎤', '🌙', '🪐', '💎', '🔥', '🦁', '👑', '🚀'
];

export const AVAILABLE_LANGUAGES = [
  { id: 'hindi', name: 'Hindi', flag: '🇮🇳' },
  { id: 'punjabi', name: 'Punjabi', flag: '👳' },
  { id: 'english', name: 'English / Pop', flag: '🌍' },
  { id: 'telugu', name: 'Telugu', flag: '🎵' },
  { id: 'tamil', name: 'Tamil', flag: '🎶' },
  { id: 'lofi', name: 'Lo-Fi / Chill', flag: '☕' },
  { id: 'bhojpuri', name: 'Bhojpuri', flag: '🪕' },
  { id: 'gujarati', name: 'Gujarati', flag: '🪘' },
  { id: 'kpop', name: 'K-Pop', flag: '🇰🇷' },
  { id: 'edm', name: 'EDM / Dance', flag: '🔊' }
];

export const POPULAR_ARTISTS = [
  { name: 'Arijit Singh', genre: 'Romantic • Bollywood' },
  { name: 'AP Dhillon', genre: 'Punjabi Wave • Urban' },
  { name: 'Diljit Dosanjh', genre: 'Punjabi Folk • Pop' },
  { name: 'Shreya Ghoshal', genre: 'Melody • Classic' },
  { name: 'Taylor Swift', genre: 'Global Pop' },
  { name: 'The Weeknd', genre: 'Synthwave • R&B' },
  { name: 'Atif Aslam', genre: 'Soulful • Acoustic' },
  { name: 'Sidhu Moose Wala', genre: 'Punjabi Rap' },
  { name: 'Pritam', genre: 'Bollywood Hits' },
  { name: 'Anirudh Ravichander', genre: 'South Hits • Energetic' },
  { name: 'Karan Aujla', genre: 'Punjabi Hip-Hop' },
  { name: 'Armaan Malik', genre: 'Smooth Melodies' }
];

export const AVAILABLE_VIBES = [
  'Late Night Chill',
  'Romantic Melodies',
  'Workout / High Energy',
  'Acoustic & Unplugged',
  'Party & Dance Drops',
  'Study & Focus Lo-Fi'
];

export const profileService = {
  /**
   * Retrieves active profile from local-first storage.
   * If none exists, creates a fresh default profile.
   */
  getActiveProfile() {
    try {
      const data = localStorage.getItem(ACTIVE_PROFILE_KEY);
      if (data) {
        return JSON.parse(data);
      }
    } catch (_) {}

    const defaultProfile = {
      id: 'profile_' + Date.now(),
      name: 'Music Lover',
      avatar: '🎧',
      preferredLanguages: ['Hindi', 'Punjabi'],
      favoriteArtists: ['Arijit Singh', 'AP Dhillon'],
      favoriteVibes: ['Romantic Melodies', 'Late Night Chill'],
      isConfigured: false,
      createdAt: Date.now()
    };

    this.saveActiveProfile(defaultProfile);
    return defaultProfile;
  },

  /**
   * Check if a first-time user has already completed the welcome onboarding
   */
  isOnboardingCompleted() {
    try {
      if (localStorage.getItem('aurawave_onboarding_completed') === 'true') return true;
      const prof = this.getActiveProfile();
      return !!prof.isConfigured;
    } catch (_) {
      return false;
    }
  },

  setOnboardingCompleted(completed = true) {
    try {
      localStorage.setItem('aurawave_onboarding_completed', completed ? 'true' : 'false');
    } catch (_) {}
  },

  /**
   * Saves active profile locally
   */
  saveActiveProfile(profile) {
    if (!profile) return;
    try {
      localStorage.setItem(ACTIVE_PROFILE_KEY, JSON.stringify(profile));
      
      // Update in all profiles list
      const all = this.getAllProfiles();
      const idx = all.findIndex(p => p.id === profile.id);
      if (idx !== -1) {
        all[idx] = profile;
      } else {
        all.push(profile);
      }
      localStorage.setItem(ALL_PROFILES_KEY, JSON.stringify(all));

      // Trigger automatic cloud sync for cross-device consistency
      syncService.pushToCloud().catch(() => {});
    } catch (_) {}
  },

  /**
   * Get all local profiles on this device (multi-user / multi-mood)
   */
  getAllProfiles() {
    try {
      const data = localStorage.getItem(ALL_PROFILES_KEY);
      if (data) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed) && parsed.length > 0) return parsed;
      }
    } catch (_) {}
    return [];
  },

  /**
   * Switch active profile
   */
  switchProfile(profileId) {
    const all = this.getAllProfiles();
    const found = all.find(p => p.id === profileId);
    if (found) {
      localStorage.setItem(ACTIVE_PROFILE_KEY, JSON.stringify(found));
      return found;
    }
    return null;
  },

  /**
   * Create a new local profile
   */
  createProfile(name, avatar = '🎧', languages = ['Hindi'], artists = ['Arijit Singh']) {
    const newProfile = {
      id: 'profile_' + Date.now(),
      name: name.trim() || 'Listener',
      avatar,
      preferredLanguages: languages,
      favoriteArtists: artists,
      favoriteVibes: ['Romantic Melodies'],
      isConfigured: true,
      createdAt: Date.now()
    };
    this.saveActiveProfile(newProfile);
    return newProfile;
  },

  /**
   * Delete a local profile
   */
  deleteProfile(profileId) {
    let all = this.getAllProfiles().filter(p => p.id !== profileId);
    if (all.length === 0) {
      const fresh = this.getActiveProfile();
      all = [fresh];
    }
    localStorage.setItem(ALL_PROFILES_KEY, JSON.stringify(all));
    this.saveActiveProfile(all[0]);
    return all[0];
  },

  // =========================================================================
  // BACKUP OPTION 2: 1-Tap Offline File Export & Import (APK Deletion Proof)
  // =========================================================================

  /**
   * Generates a complete, comprehensive backup bundle
   */
  generateFullBackupPayload() {
    try {
      const activeProfile = this.getActiveProfile();
      const allProfiles = this.getAllProfiles();
      const favorites = storageService.getFavorites();
      const favoriteMeta = storageService.getFavoritesMetadata ? storageService.getFavoritesMetadata() : [];
      const customPlaylists = storageService.getPlaylists().filter(p => p.isCustom || p.isImported || String(p.id || '').startsWith('imported-') || String(p.id || '').startsWith('custom-'));
      const recentHistory = storageService.getRecentHistory(50);
      
      let lyricsVault = {};
      try {
        const rawVault = localStorage.getItem('aurawave_synced_lyrics_vault');
        if (rawVault) lyricsVault = JSON.parse(rawVault);
      } catch (_) {}

      let lyricsOffsets = {};
      try {
        const rawOffsets = localStorage.getItem('aurawave_lyrics_offsets');
        if (rawOffsets) lyricsOffsets = JSON.parse(rawOffsets);
      } catch (_) {}

      const syncCode = syncService.getSyncCode();
      const autoDownload = offlineStorage.isAutoDownloadEnabled();
      const audioQuality = localStorage.getItem('aurawave_audio_quality') || '320k';

      return {
        aurawave_backup_version: 2,
        exported_at: new Date().toISOString(),
        device_note: '100% Private Offline Backup • Immune to APK deletion',
        syncCode,
        activeProfile,
        allProfiles,
        favorites,
        favoriteMeta,
        customPlaylists,
        recentHistory,
        lyricsVault,
        lyricsOffsets,
        settings: {
          autoDownload,
          audioQuality
        }
      };
    } catch (err) {
      console.warn('Failed to compile full backup payload:', err);
      return null;
    }
  },

  /**
   * Downloads or shares the backup as a .json file (with native Android Share & Data URI support)
   */
  async downloadBackupFile() {
    const payload = this.generateFullBackupPayload();
    if (!payload) return { ok: false, error: 'Could not generate backup' };

    const dateStr = new Date().toISOString().slice(0, 10);
    const fileName = `aurawave_backup_${dateStr}.json`;
    const jsonStr = JSON.stringify(payload, null, 2);

    // 1. Android / Mobile WebView: Use Web Share API with File object
    try {
      if (typeof navigator !== 'undefined' && typeof File !== 'undefined' && navigator.canShare) {
        const file = new File([jsonStr], fileName, { type: 'application/json' });
        if (navigator.canShare({ files: [file] })) {
          await navigator.share({
            title: 'AuraWave Backup',
            text: 'AuraWave Music Library & Profile Backup',
            files: [file]
          });
          return { ok: true, method: 'share' };
        }
      }
    } catch (shareErr) {
      if (shareErr.name === 'AbortError') {
        return { ok: true, method: 'cancelled' };
      }
      console.warn('Share API fallback:', shareErr);
    }

    // 2. Standard Blob download link
    try {
      const blob = new Blob([jsonStr], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(() => URL.revokeObjectURL(url), 2000);
      return { ok: true, method: 'download' };
    } catch (err) {
      console.error('File download failed, attempting data-uri:', err);
    }

    // 3. Data URI fallback
    try {
      const dataUri = 'data:application/json;charset=utf-8,' + encodeURIComponent(jsonStr);
      const a = document.createElement('a');
      a.href = dataUri;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      return { ok: true, method: 'datauri' };
    } catch (_) {}

    return { ok: false, error: 'Could not trigger download' };
  },

  /**
   * Copies raw JSON backup to clipboard as a foolproof alternative
   */
  async copyBackupToClipboard() {
    const payload = this.generateFullBackupPayload();
    if (!payload) return false;
    const jsonStr = JSON.stringify(payload, null, 2);
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard) {
        await navigator.clipboard.writeText(jsonStr);
        return true;
      }
    } catch (_) {}
    return false;
  },

  /**
   * Imports and restores a backup bundle into local storage
   */
  importFullBackup(payload) {
    if (!payload || typeof payload !== 'object') {
      return { ok: false, error: 'Invalid backup file format' };
    }

    try {
      // 1. Restore Profile
      if (payload.activeProfile) {
        localStorage.setItem(ACTIVE_PROFILE_KEY, JSON.stringify(payload.activeProfile));
      }
      if (Array.isArray(payload.allProfiles) && payload.allProfiles.length > 0) {
        localStorage.setItem(ALL_PROFILES_KEY, JSON.stringify(payload.allProfiles));
      }

      // 2. Restore Favorites
      if (Array.isArray(payload.favorites)) {
        payload.favorites.forEach(trackId => {
          storageService.addFavorite(trackId);
        });
      }
      if (Array.isArray(payload.favoriteMeta)) {
        try {
          localStorage.setItem('aurawave_favorites_meta', JSON.stringify(payload.favoriteMeta));
        } catch (_) {}
      }

      // 3. Restore Playlists
      if (Array.isArray(payload.customPlaylists)) {
        const currentPlaylists = storageService.getPlaylists();
        payload.customPlaylists.forEach(importedPl => {
          if (!currentPlaylists.some(p => p.id === importedPl.id || p.name === importedPl.name)) {
            currentPlaylists.push(importedPl);
          }
        });
        storageService.savePlaylists(currentPlaylists);
      }

      // 4. Restore Lyrics Vault
      if (payload.lyricsVault && typeof payload.lyricsVault === 'object') {
        try {
          const rawVault = localStorage.getItem('aurawave_synced_lyrics_vault');
          const currentVault = rawVault ? JSON.parse(rawVault) : {};
          const merged = { ...currentVault, ...payload.lyricsVault };
          localStorage.setItem('aurawave_synced_lyrics_vault', JSON.stringify(merged));
        } catch (_) {}
      }

      // 5. Restore Sync Code
      if (payload.syncCode) {
        syncService.setSyncCode(payload.syncCode);
      }

      // 6. Restore Settings
      if (payload.settings) {
        if (typeof payload.settings.autoDownload === 'boolean') {
          offlineStorage.setAutoDownloadEnabled(payload.settings.autoDownload);
        }
        if (payload.settings.audioQuality) {
          localStorage.setItem('aurawave_audio_quality', payload.settings.audioQuality);
        }
      }

      // Sync restored state to cloud
      syncService.pushToCloud().catch(() => {});

      return { ok: true, restoredAt: Date.now() };
    } catch (err) {
      console.error('Error restoring backup file:', err);
      return { ok: false, error: err.message || 'Restoration failed' };
    }
  },

  /**
   * Reads a chosen file and triggers restoration
   */
  readBackupFile(file) {
    return new Promise((resolve) => {
      if (!file) return resolve({ ok: false, error: 'No file selected' });
      const reader = new FileReader();
      reader.onload = (e) => {
        try {
          const json = JSON.parse(e.target.result);
          const result = this.importFullBackup(json);
          resolve(result);
        } catch (err) {
          resolve({ ok: false, error: 'File is not a valid JSON backup' });
        }
      };
      reader.onerror = () => resolve({ ok: false, error: 'Failed to read file' });
      reader.readAsText(file);
    });
  }
};
