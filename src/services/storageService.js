/**
 * Storage Service - Persistent local storage for user playlists, favorites,
 * recently played history, custom playlist management, and personalized lyrics sync offsets.
 */

const STORAGE_KEYS = {
  PLAYLISTS: 'aurawave_stored_playlists',
  FAVORITES: 'aurawave_stored_favorites',
  FAVORITES_META: 'aurawave_favorites_meta',
  RECENTLY_PLAYED: 'aurawave_recent_history',
  PLAY_STATS: 'aurawave_play_stats',
  LYRICS_OFFSETS: 'aurawave_lyrics_offsets'
};

export function isDummyTrack(t) {
  if (!t) return true;
  const title = (t.title || '').toLowerCase();
  const artist = (t.artist || '').toLowerCase();
  const album = (t.album || '').toLowerCase();
  const id = String(t.id || '');
  if (['track-1', 'track-2', 'track-3', 'track-4', 'track-5'].includes(id)) return true;
  if (title.includes('sunset sketches') || title.includes('neon pulse') || title.includes('cyber city') || title.includes('midnight reverie') || title.includes('sunflower waves')) return true;
  if (artist.includes('aura lofi') || artist.includes('cyberwave') || artist.includes('synthwave')) return true;
  if (album.includes('lo-fi') || album.includes('cyberwave') || album.includes('chill beats')) return true;
  return false;
}

export const storageService = {
  // Playlists
  getPlaylists(defaultPlaylists = []) {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.PLAYLISTS);
      if (data) {
        let parsed = JSON.parse(data);
        if (Array.isArray(parsed) && parsed.length > 0) {
          const defaultIds = new Set((defaultPlaylists || []).map(dp => dp.id));
          
          // Permanently preserve ALL user-created and imported playlists!
          const userPlaylists = parsed
            .filter(p => {
              if (!p) return false;
              if (p.isCustom || p.isImported) return true;
              const id = String(p.id || '');
              if (id.startsWith('imported-') || id.startsWith('custom-') || id.startsWith('sp-')) return true;
              // If it's not one of the default built-in playlists, it belongs to the user!
              return !defaultIds.has(p.id);
            })
            .map(p => {
              const cleanedTracks = (p.tracks || []).filter(t => !isDummyTrack(t));
              return {
                ...p,
                isCustom: true, // mark as user playlist
                isImported: p.isImported || String(p.id || '').startsWith('imported-') || String(p.id || '').startsWith('sp-'),
                tracks: cleanedTracks,
                trackCount: cleanedTracks.length
              };
            });
          
          // Combine fresh defaults with all user playlists (never lose user playlists)
          const userIds = new Set(userPlaylists.map(up => up.id));
          const freshDefaults = (defaultPlaylists || []).filter(dp => !userIds.has(dp.id));
          const combined = [...freshDefaults, ...userPlaylists];
          this.savePlaylists(combined);
          return combined;
        }
      }
    } catch (e) {
      console.warn('Failed to load playlists from storage:', e);
    }
    // Save defaults initially
    this.savePlaylists(defaultPlaylists);
    return defaultPlaylists;
  },

  savePlaylists(playlists) {
    try {
      localStorage.setItem(STORAGE_KEYS.PLAYLISTS, JSON.stringify(playlists));
    } catch (e) {
      console.warn('Failed to save playlists to storage:', e);
    }
  },

  // Save or update an imported/custom playlist
  savePlaylist(newOrUpdatedPlaylist) {
    if (!newOrUpdatedPlaylist || !newOrUpdatedPlaylist.id) return this.getPlaylists([]);
    const playlists = this.getPlaylists([]);
    const exists = playlists.some(p => p.id === newOrUpdatedPlaylist.id);
    let updated;
    const formatted = {
      ...newOrUpdatedPlaylist,
      isCustom: true,
      isImported: true,
      trackCount: Array.isArray(newOrUpdatedPlaylist.tracks) ? newOrUpdatedPlaylist.tracks.length : (newOrUpdatedPlaylist.trackCount || 0)
    };
    if (exists) {
      updated = playlists.map(p => p.id === newOrUpdatedPlaylist.id ? { ...p, ...formatted } : p);
    } else {
      updated = [formatted, ...playlists];
    }
    this.savePlaylists(updated);
    return updated;
  },

  // Create a new custom playlist
  createPlaylist(name, description = '', cover = '/covers/neon_pulse.jpg') {
    const playlists = this.getPlaylists([]);
    const newPlaylist = {
      id: `custom-pl-${Date.now()}`,
      name: name.trim() || 'My Playlist',
      description: description.trim() || 'Custom user playlist',
      cover: cover,
      isCustom: true,
      trackCount: 0,
      tracks: []
    };
    const updated = [newPlaylist, ...playlists];
    this.savePlaylists(updated);
    return { newPlaylist, allPlaylists: updated };
  },

  // Delete a playlist
  deletePlaylist(playlistId) {
    const playlists = this.getPlaylists([]);
    const updated = playlists.filter(p => p.id !== playlistId);
    this.savePlaylists(updated);
    return updated;
  },

  // Rename a playlist
  renamePlaylist(playlistId, newName, newDesc) {
    const playlists = this.getPlaylists([]);
    const updated = playlists.map(p => {
      if (p.id === playlistId) {
        return {
          ...p,
          name: newName.trim() || p.name,
          description: newDesc !== undefined ? newDesc.trim() : p.description
        };
      }
      return p;
    });
    this.savePlaylists(updated);
    return updated;
  },

  // Add a track to a playlist
  addTrackToPlaylist(playlistId, track) {
    const playlists = this.getPlaylists([]);
    let added = false;
    const updated = playlists.map(p => {
      if (p.id === playlistId) {
        const currentTracks = Array.isArray(p.tracks) ? p.tracks : [];
        // Prevent duplicate
        const exists = currentTracks.some(t => {
          if (typeof t === 'string') return t === track.id;
          return t.id === track.id || (t.title === track.title && t.artist === track.artist);
        });

        if (!exists) {
          added = true;
          return {
            ...p,
            tracks: [track, ...currentTracks],
            trackCount: currentTracks.length + 1
          };
        }
      }
      return p;
    });

    if (added) {
      this.savePlaylists(updated);
    }
    return { updated, added };
  },

  // Remove a track from a playlist
  removeTrackFromPlaylist(playlistId, trackId) {
    const playlists = this.getPlaylists([]);
    const updated = playlists.map(p => {
      if (p.id === playlistId) {
        const currentTracks = Array.isArray(p.tracks) ? p.tracks : [];
        const filtered = currentTracks.filter(t => {
          if (typeof t === 'string') return t !== trackId;
          return t.id !== trackId;
        });
        return {
          ...p,
          tracks: filtered,
          trackCount: filtered.length
        };
      }
      return p;
    });
    this.savePlaylists(updated);
    return updated;
  },

  // Favorites
  getFavorites(defaultFavorites = []) {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.FAVORITES);
      if (data) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed)) {
          // Filter out legacy dummy track IDs
          const cleaned = parsed.filter(id => !['track-1', 'track-2', 'track-3', 'track-4', 'track-5'].includes(id));
          return cleaned;
        }
      }
    } catch (e) {
      console.warn('Failed to load favorites from storage:', e);
    }
    return defaultFavorites.filter(id => !['track-1', 'track-2', 'track-3', 'track-4', 'track-5'].includes(id));
  },

  saveFavorites(favorites) {
    try {
      localStorage.setItem(STORAGE_KEYS.FAVORITES, JSON.stringify(favorites));
    } catch (e) {
      console.warn('Failed to save favorites to storage:', e);
    }
  },

  // Recently Played History
  getRecentlyPlayed() {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.RECENTLY_PLAYED);
      if (data) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed)) {
          return parsed.filter(t => !isDummyTrack(t)).map(t => {
            if (t.audioUrl && (t.audioUrl.includes('mzstatic.com') || t.audioUrl.includes('apple.com') || t.audioUrl.includes('preview'))) {
              const copy = { ...t };
              delete copy.audioUrl;
              return copy;
            }
            return t;
          });
        }
      }
    } catch (e) {
      console.warn('Failed to load history from storage:', e);
    }
    return [];
  },

  addRecentlyPlayed(track) {
    if (!track || isDummyTrack(track)) return;
    try {
      const trackCopy = { ...track };
      if (trackCopy.audioUrl && (trackCopy.audioUrl.includes('mzstatic.com') || trackCopy.audioUrl.includes('apple.com') || trackCopy.audioUrl.includes('preview'))) {
        delete trackCopy.audioUrl;
      }
      const current = this.getRecentlyPlayed();
      const filtered = current.filter(t => t.id !== trackCopy.id && t.title !== trackCopy.title);
      const updated = [trackCopy, ...filtered].slice(0, 30);
      localStorage.setItem(STORAGE_KEYS.RECENTLY_PLAYED, JSON.stringify(updated));
      return updated;
    } catch (e) {
      console.warn('Failed to save recently played track:', e);
    }
    return [];
  },

  // -------------------------------------------------------------
  // Track Play Frequency & Listening Taste Analytics
  // -------------------------------------------------------------
  getPlayStats() {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.PLAY_STATS);
      if (data) {
        return JSON.parse(data);
      }
    } catch (e) {
      console.warn('Failed to load play stats:', e);
    }
    return {};
  },

  recordTrackPlay(track) {
    if (!track || isDummyTrack(track)) return null;
    try {
      const cleanTitle = (track.title || '').toLowerCase().replace(/[^a-z0-9]/g, '');
      const cleanArtist = (track.artist || '').toLowerCase().replace(/[^a-z0-9]/g, '');
      const trackKey = track.id || `${cleanTitle}__${cleanArtist}`;

      const stats = this.getPlayStats();
      const existing = stats[trackKey] || {};
      const newPlayCount = (existing.playCount || 0) + 1;

      stats[trackKey] = {
        id: track.id || trackKey,
        title: track.title,
        artist: track.artist,
        coverUrl: track.coverUrl,
        youtubeId: track.youtubeId,
        genre: track.genre || 'pop',
        language: track.language || 'hindi',
        themeColor: track.themeColor || '#1DB954',
        duration: track.duration || 180,
        playCount: newPlayCount,
        lastPlayed: Date.now()
      };

      localStorage.setItem(STORAGE_KEYS.PLAY_STATS, JSON.stringify(stats));

      // Also ensure recently played history is updated
      const updatedHistory = this.addRecentlyPlayed(track);

      return {
        updatedStats: stats,
        trackStats: stats[trackKey],
        updatedHistory
      };
    } catch (e) {
      console.warn('Failed to record track play:', e);
      return null;
    }
  },

  getTrackPlayCount(track) {
    if (!track) return 0;
    const cleanTitle = (track.title || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    const cleanArtist = (track.artist || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    const trackKey = track.id || `${cleanTitle}__${cleanArtist}`;
    const stats = this.getPlayStats();
    return stats[trackKey]?.playCount || 0;
  },

  getFrequentlyPlayed(limit = 10, allCatalogTracks = []) {
    const stats = this.getPlayStats();
    const playedItems = Object.values(stats)
      .filter(item => item && item.playCount > 0 && !isDummyTrack(item))
      .sort((a, b) => (b.playCount || 0) - (a.playCount || 0));

    // Map each item to full track details from catalog when available
    const catalogMap = new Map();
    allCatalogTracks.forEach(t => {
      if (t && t.id) catalogMap.set(t.id, t);
      if (t && t.title) catalogMap.set(t.title.toLowerCase().trim(), t);
    });

    const enriched = playedItems.map(item => {
      const match = catalogMap.get(item.id) || catalogMap.get((item.title || '').toLowerCase().trim());
      return {
        ...(match || {}),
        ...item,
        playCount: item.playCount,
        isFrequent: true
      };
    });

    // If user has listened to few tracks (< 4), blend in top curated tracks with high affinity
    if (enriched.length < 4 && allCatalogTracks.length > 0) {
      const seenTitles = new Set(enriched.map(t => (t.title || '').toLowerCase().trim()));
      allCatalogTracks.filter(t => !isDummyTrack(t)).slice(0, 8).forEach(curated => {
        const cleanTitle = (curated.title || '').toLowerCase().trim();
        if (!seenTitles.has(cleanTitle)) {
          seenTitles.add(cleanTitle);
          enriched.push({
            ...curated,
            playCount: 1,
            isCuratedFrequent: true
          });
        }
      });
    }

    return enriched.slice(0, limit);
  },

  getTasteProfile(allCatalogTracks = []) {
    const stats = this.getPlayStats();
    const favorites = this.getFavorites();
    const playedItems = Object.values(stats).filter(i => i && i.playCount > 0);

    const artistScores = {};
    const genreScores = {};
    let totalPlays = 0;

    playedItems.forEach(item => {
      const count = item.playCount || 1;
      totalPlays += count;

      if (item.artist) {
        const primary = item.artist.split(/[,&]/)[0].trim();
        artistScores[primary] = (artistScores[primary] || 0) + count;
      }
      if (item.genre) {
        const g = item.genre.toLowerCase().trim();
        genreScores[g] = (genreScores[g] || 0) + count;
      }
    });

    // Boost scores for favorited tracks
    favorites.forEach(fId => {
      const match = allCatalogTracks.find(t => t.id === fId);
      if (match) {
        if (match.artist) {
          const primary = match.artist.split(/[,&]/)[0].trim();
          artistScores[primary] = (artistScores[primary] || 0) + 3;
        }
        if (match.genre) {
          const g = match.genre.toLowerCase().trim();
          genreScores[g] = (genreScores[g] || 0) + 3;
        }
      }
    });

    const topArtists = Object.entries(artistScores)
      .sort((a, b) => b[1] - a[1])
      .map(([name, score]) => ({ name, score }));

    const topGenres = Object.entries(genreScores)
      .sort((a, b) => b[1] - a[1])
      .map(([name, score]) => ({ name, score }));

    const mostPlayed = playedItems.sort((a, b) => (b.playCount || 0) - (a.playCount || 0))[0] || null;

    return {
      totalPlays,
      uniqueSongsPlayed: playedItems.length,
      topArtist: topArtists[0]?.name || null,
      topGenre: topGenres[0]?.name || null,
      topArtists,
      topGenres,
      mostPlayedTrack: mostPlayed,
      favoritesCount: favorites.length
    };
  },

  // Full metadata persistence for user favorites
  saveFavoriteMeta(track) {
    if (!track || !track.id) return;
    try {
      const data = localStorage.getItem(STORAGE_KEYS.FAVORITES_META);
      const metaMap = data ? JSON.parse(data) : {};
      metaMap[track.id] = {
        id: track.id,
        title: track.title,
        artist: track.artist,
        coverUrl: track.coverUrl,
        youtubeId: track.youtubeId,
        genre: track.genre,
        language: track.language,
        duration: track.duration
      };
      localStorage.setItem(STORAGE_KEYS.FAVORITES_META, JSON.stringify(metaMap));
    } catch (_) {}
  },

  getFavoritesMeta() {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.FAVORITES_META);
      return data ? JSON.parse(data) : {};
    } catch (_) {
      return {};
    }
  },

  // Lyrics timing offset calibration per track
  getLyricsOffset(trackId) {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.LYRICS_OFFSETS);
      if (data) {
        const offsets = JSON.parse(data);
        return offsets[trackId] || 0;
      }
    } catch (_) {}
    return 0;
  },

  setLyricsOffset(trackId, offsetSeconds) {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.LYRICS_OFFSETS);
      const offsets = data ? JSON.parse(data) : {};
      offsets[trackId] = offsetSeconds;
      localStorage.setItem(STORAGE_KEYS.LYRICS_OFFSETS, JSON.stringify(offsets));
    } catch (_) {}
  },

  // Recent Searches
  getRecentSearches() {
    try {
      const data = localStorage.getItem('aurawave_recent_searches');
      return data ? JSON.parse(data) : [];
    } catch (_) {
      return [];
    }
  },

  addRecentSearch(query) {
    if (!query || !query.trim()) return [];
    try {
      const clean = query.trim();
      const list = this.getRecentSearches().filter(q => q.toLowerCase() !== clean.toLowerCase());
      const updated = [clean, ...list].slice(0, 10);
      localStorage.setItem('aurawave_recent_searches', JSON.stringify(updated));
      return updated;
    } catch (_) {
      return [];
    }
  },

  clearRecentSearches() {
    try {
      localStorage.removeItem('aurawave_recent_searches');
    } catch (_) {}
    return [];
  }
};
