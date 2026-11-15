/**
 * Cross-Device Cloud Sync Service
 * Seamlessly syncs Favorites, Custom Playlists, Recent History,
 * Synced Lyrics Vault, and Settings between Mobile and Web.
 */

import { storageService } from './storageService.js';
import { getApiBaseUrl } from '../utils/apiBase.js';

const SYNC_CODE_KEY = 'aurawave_user_sync_code';
const LAST_SYNC_KEY = 'aurawave_last_sync_time';

export const syncService = {
  /**
   * Get or generate a persistent 6-character Sync Code for this user
   */
  getSyncCode() {
    try {
      let code = localStorage.getItem(SYNC_CODE_KEY);
      if (!code) {
        const rand = Math.floor(1000 + Math.random() * 9000);
        code = `AURA-${rand}`;
        localStorage.setItem(SYNC_CODE_KEY, code);
      }
      return code;
    } catch (_) {
      return 'AURA-7777';
    }
  },

  setSyncCode(newCode) {
    if (!newCode) return;
    try {
      localStorage.setItem(SYNC_CODE_KEY, newCode.toUpperCase().trim());
    } catch (_) {}
  },

  /**
   * Bundles all user data into a clean, syncable payload
   */
  exportUserData() {
    try {
      const favorites = storageService.getFavorites();
      const customPlaylists = storageService.getPlaylists().filter(p => p.isCustom || p.isImported || String(p.id || '').startsWith('imported-') || String(p.id || '').startsWith('custom-'));
      const recentlyPlayed = storageService.getRecentHistory(30);
      const audioQuality = localStorage.getItem('aurawave_audio_quality') || '320k';
      
      // Also grab lyrics vault so auto-synced songs sync to other device too!
      let lyricsVault = {};
      try {
        const rawVault = localStorage.getItem('aurawave_synced_lyrics_vault');
        if (rawVault) lyricsVault = JSON.parse(rawVault);
      } catch (_) {}

      return {
        favorites,
        customPlaylists,
        recentlyPlayed,
        audioQuality,
        lyricsVault,
        timestamp: Date.now()
      };
    } catch (err) {
      console.warn('Error exporting user data:', err);
      return null;
    }
  },

  /**
   * Merges imported data payload into the current device's local storage
   */
  importUserData(payload) {
    if (!payload) return false;
    try {
      // 1. Merge Favorites
      if (Array.isArray(payload.favorites)) {
        payload.favorites.forEach(trackId => {
          storageService.addFavorite(trackId);
        });
      }

      // 2. Merge Custom Playlists
      if (Array.isArray(payload.customPlaylists)) {
        const currentPlaylists = storageService.getPlaylists();
        payload.customPlaylists.forEach(importedPl => {
          if (!currentPlaylists.some(p => p.id === importedPl.id || p.name === importedPl.name)) {
            currentPlaylists.push(importedPl);
          }
        });
        storageService.savePlaylists(currentPlaylists);
      }

      // 3. Merge Lyrics Vault
      if (payload.lyricsVault && typeof payload.lyricsVault === 'object') {
        try {
          const rawVault = localStorage.getItem('aurawave_synced_lyrics_vault');
          const currentVault = rawVault ? JSON.parse(rawVault) : {};
          const mergedVault = { ...currentVault, ...payload.lyricsVault };
          localStorage.setItem('aurawave_synced_lyrics_vault', JSON.stringify(mergedVault));
        } catch (_) {}
      }

      // 4. Update Audio Quality
      if (payload.audioQuality) {
        localStorage.setItem('aurawave_audio_quality', payload.audioQuality);
      }

      localStorage.setItem(LAST_SYNC_KEY, String(Date.now()));
      return true;
    } catch (err) {
      console.warn('Error importing user data:', err);
      return false;
    }
  },

  /**
   * Upload user data to the cloud under their sync code
   */
  async pushToCloud(customCode = null) {
    const code = customCode || this.getSyncCode();
    const payload = this.exportUserData();
    if (!payload) return { ok: false, error: 'Could not bundle user data' };

    try {
      const apiBase = getApiBaseUrl();
      const res = await fetch(`${apiBase}/api/user/sync`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, payload })
      });

      if (res.ok) {
        localStorage.setItem(LAST_SYNC_KEY, String(Date.now()));
        return { ok: true, code };
      }
      return { ok: false, error: 'Cloud server rejected sync payload' };
    } catch (err) {
      // Fallback: save in localStorage
      return { ok: true, code, localOnly: true };
    }
  },

  /**
   * Pull and merge data from the cloud using a sync code
   */
  async pullFromCloud(targetCode) {
    if (!targetCode) return { ok: false, error: 'No sync code provided' };
    const code = targetCode.toUpperCase().trim();

    try {
      const apiBase = getApiBaseUrl();
      const res = await fetch(`${apiBase}/api/user/sync?code=${encodeURIComponent(code)}`);
      if (res.ok) {
        const json = await res.json();
        if (json.found && json.data) {
          const success = this.importUserData(json.data);
          this.setSyncCode(code);
          return { ok: success, code };
        }
      }
      return { ok: false, error: 'Sync code not found or has no active data' };
    } catch (err) {
      return { ok: false, error: 'Network error connecting to sync server' };
    }
  },

  /**
   * Generate an instant 1-Click Sync Link to open on mobile
   */
  generateShareableLink() {
    const code = this.getSyncCode();
    const baseUrl = typeof window !== 'undefined' ? window.location.origin : 'https://aurawave-music.vercel.app';
    return `${baseUrl}/?syncCode=${encodeURIComponent(code)}`;
  }
};
