import React, { useState, useEffect, useMemo, useRef } from 'react';
import {
  Search,
  X,
  Play,
  Pause,
  Music,
  Heart,
  ListPlus,
  ArrowDownCircle,
  Share2,
  UserPlus,
  UserCheck,
  Clock,
  Sparkles,
  TrendingUp,
  CheckCircle2,
  Plus,
  Flame,
  Volume2,
  Disc3,
  Layers
} from 'lucide-react';
import { CURATED_TRACKS, normalizeTrackName, normalizeArtistName } from '../data/curatedTracks';
import { searchGlobalMusic, resolveSearchQueryAliases, fuzzyPhonetic } from '../services/searchService';
import { storageService } from '../services/storageService';
import { prewarmTrackResolution } from '../services/spotifyService';
import ArtistAvatar from './ArtistAvatar';
import { getArtistProfile, VERIFIED_ARTISTS } from '../data/verifiedArtists';

function formatDuration(sec) {
  if (!sec || isNaN(sec)) return '3:45';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

const TRENDING_SPARKS = [
  { label: 'Diljit Dosanjh', icon: '🔥', query: 'Diljit Dosanjh' },
  { label: 'Kesariya (Lossless)', icon: '⚡', query: 'Kesariya Arijit Singh' },
  { label: 'Midnight Lo-Fi', icon: '🎧', query: 'Lo-Fi Hindi Beats' },
  { label: 'Arijit Unplugged', icon: '💖', query: 'Arijit Singh Romantic Hits' },
  { label: 'AP Dhillon Drops', icon: '🚀', query: 'AP Dhillon' },
  { label: 'Desi Hip-Hop Hustle', icon: '💥', query: 'Desi Hip Hop' },
  { label: 'Anuv Jain Acoustic', icon: '🌙', query: 'Anuv Jain Acoustic Indie' },
  { label: 'Billie Eilish', icon: '👑', query: 'Billie Eilish' },
  { label: 'Sidhu Moosewala', icon: '🕺', query: 'Sidhu Moosewala' }
];

const FEATURED_STATIONS = [
  {
    id: 'romance',
    badge: 'TRENDING MOOD',
    title: 'Bollywood Romance',
    desc: 'Soulful melodies & timeless love ballads',
    icon: '💖',
    query: 'Arijit Singh Romantic Hits',
    gradient: 'linear-gradient(135deg, #e11d48 0%, #be123c 50%, #4c0519 100%)'
  },
  {
    id: 'desi-hiphop',
    badge: 'LOSSLESS 808S',
    title: 'Desi Hip-Hop & Hustle',
    desc: 'Underground bars, heavy 808s & street',
    icon: '🔥',
    query: 'MTV Hustle Desi Hip Hop',
    gradient: 'linear-gradient(135deg, #ea580c 0%, #c2410c 50%, #431407 100%)'
  },
  {
    id: 'punjabi',
    badge: 'PARTY PURE',
    title: 'Punjabi Banger Hits',
    desc: 'High-energy wedding, club & drive',
    icon: '🕺',
    query: 'Diljit Dosanjh Punjabi Bangers',
    gradient: 'linear-gradient(135deg, #7c3aed 0%, #6d28d9 50%, #2e1065 100%)'
  },
  {
    id: 'lofi',
    badge: 'BINAURAL STUDY',
    title: 'Lo-Fi Chill & Study',
    desc: 'Focus beats, rain textures & peaceful calm',
    stat: '640K Aura Listeners',
    icon: '🎧',
    query: 'Lo-Fi Hindi Beats',
    gradient: 'linear-gradient(135deg, #0d9488 0%, #0f766e 50%, #042f2e 100%)'
  },
  {
    id: 'gym',
    badge: '140 - 175 BPM',
    title: 'Gym & Beast Mode',
    desc: 'High-BPM fuel for PRs and heavy pump sessions',
    stat: '1.8M Aura Listeners',
    icon: '⚡',
    query: 'High Energy Workout Music',
    gradient: 'linear-gradient(135deg, #16a34a 0%, #15803d 50%, #052e16 100%)'
  },
  {
    id: 'acoustic',
    badge: 'MIDNIGHT WAVES',
    title: 'Late Night Acoustic',
    desc: 'Raw unplugged vocals, nylon strings & memories',
    stat: '512K Aura Listeners',
    icon: '🌙',
    query: 'Anuv Jain Acoustic Indie',
    gradient: 'linear-gradient(135deg, #4f46e5 0%, #3730a3 50%, #1e1b4b 100%)'
  }
];

const DEFAULT_RECENT_SEARCHES = [
  'AP Dhillon',
  'Desi Hip-Hop',
  'Bollywood Lo-Fi',
  'Aura Workout',
  'Diljit Dosanjh'
];

export default function SearchModal({
  onSelectTrack,
  currentTrackId,
  currentTrackTitle,
  currentTrackArtist,
  currentTrack,
  isPlaying,
  onOpenAddToPlaylist,
  onAddToQueue,
  onToggleDownload,
  downloadedTrackIds = [],
  isMobile = false,
  favorites = [],
  onToggleFavorite
}) {
  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState('All'); // 'All' | 'Songs' | 'Artists' | 'Albums' | 'Playlists'
  const [recentSearches, setRecentSearches] = useState(() => {
    const saved = storageService.getRecentSearches();
    return saved && saved.length > 0 ? saved : DEFAULT_RECENT_SEARCHES;
  });
  const [searchResults, setSearchResults] = useState([]);
  const [isSearching, setIsSearching] = useState(false);
  const [followedArtists, setFollowedArtists] = useState(new Set());
  const [showSuggestions, setShowSuggestions] = useState(false);
  const debounceRef = useRef(null);
  const inputRef = useRef(null);

  // Default featured popular matching songs
  const defaultPopularSongs = useMemo(() => {
    return [
      {
        id: 'kesariya-lossless-master',
        title: 'Kesariya (Lossless Master)',
        artist: 'Arijit Singh, Pritam, Amitabh Bhattacharya • Brahmāstra',
        duration: 268,
        formatBadge: 'HI-FI',
        coverUrl: 'https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=300&auto=format&fit=crop&q=80',
        isVerified: true
      },
      {
        id: 'true-stories-dolby',
        title: 'True Stories',
        artist: 'AP Dhillon, Shinda Kahlon • Two Hearts Never Break',
        duration: 194,
        formatBadge: 'DOLBY ATMOS',
        coverUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=300&auto=format&fit=crop&q=80',
        isVerified: true
      },
      {
        id: 'tum-hi-ho-unplugged',
        title: 'Tum Hi Ho (Unplugged Studio Cut)',
        artist: 'Arijit Singh, Mithoon • Aashiqui 2 Deluxe Edition',
        duration: 262,
        formatBadge: 'HI-FI',
        coverUrl: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=300&auto=format&fit=crop&q=80',
        isVerified: false
      },
      {
        id: 'born-to-shine-live',
        title: 'Born to Shine (Stadium Live ...)',
        artist: 'Diljit Dosanjh • G.O.A.T. Live Experience',
        duration: 214,
        formatBadge: 'DOLBY ATMOS',
        coverUrl: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=300&auto=format&fit=crop&q=80',
        isVerified: false
      }
    ];
  }, []);

  // Check if search explicitly targets a verified artist
  const isExplicitArtistMatch = useMemo(() => {
    if (!searchQuery.trim()) return false;
    const q = searchQuery.toLowerCase().trim();
    return Object.values(VERIFIED_ARTISTS).some(a => {
      const aName = a.name.toLowerCase();
      return q === aName || q.startsWith(aName);
    });
  }, [searchQuery]);

  // Top artist match card only used when explicitly searching an artist
  const topArtistMatch = useMemo(() => {
    if (!isExplicitArtistMatch) return null;
    return getArtistProfile(searchQuery, searchResults, CURATED_TRACKS);
  }, [searchQuery, searchResults, isExplicitArtistMatch]);

  // Perform search
  useEffect(() => {
    if (!searchQuery.trim()) {
      setSearchResults([]);
      setIsSearching(false);
      return;
    }

    if (debounceRef.current) clearTimeout(debounceRef.current);
    setIsSearching(true);

    debounceRef.current = setTimeout(async () => {
      try {
        const query = searchQuery.trim();
        const resolved = resolveSearchQueryAliases(query);
        const fuzzyQ = fuzzyPhonetic(query);

        // 1. Search local curated catalog first with phonetic & alias awareness
        const localMatches = CURATED_TRACKS.filter(t => {
          const title = normalizeTrackName(t.title);
          const artist = normalizeArtistName(t.artist);
          const fTitle = fuzzyPhonetic(t.title);
          const q = query.toLowerCase();
          const r = resolved.toLowerCase();
          return title.includes(q) || title.includes(r) || artist.includes(q) || (fuzzyQ.length >= 3 && fTitle.includes(fuzzyQ));
        });

        // 2. Search global music API
        const onlineMatches = await searchGlobalMusic(query);
        const combined = [...localMatches];
        onlineMatches.forEach(om => {
          if (!combined.some(c => normalizeTrackName(c.title) === normalizeTrackName(om.title))) {
            combined.push(om);
          }
        });

        setSearchResults(combined);

        // Pre-warm top search results so tapping them starts playback with zero delay
        combined.slice(0, 5).forEach(t => {
          if (!t.youtubeId) {
            prewarmTrackResolution(t);
          }
        });
      } catch (e) {
        console.warn('Search error:', e);
      } finally {
        setIsSearching(false);
      }
    }, 240);

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [searchQuery]);

  const handleSelectQuery = (q) => {
    setSearchQuery(q);
    setShowSuggestions(false);
    storageService.addRecentSearch(q);
    setRecentSearches(storageService.getRecentSearches());
  };

  const handleRemoveRecent = (e, q) => {
    e.stopPropagation();
    const updated = recentSearches.filter(item => item !== q);
    setRecentSearches(updated);
  };

  const handleClearAllRecents = () => {
    setRecentSearches([]);
  };

  const handleToggleFollow = (artistId) => {
    setFollowedArtists(prev => {
      const next = new Set(prev);
      if (next.has(artistId)) {
        next.delete(artistId);
      } else {
        next.add(artistId);
      }
      return next;
    });
  };

  // Instant Suggestions for Autocomplete
  const liveSuggestions = useMemo(() => {
    if (!searchQuery.trim() || searchQuery.length < 2) return [];
    const q = searchQuery.toLowerCase();
    const hits = [];

    // Check artists
    Object.values(VERIFIED_ARTISTS).forEach(a => {
      if (a.name.toLowerCase().includes(q)) {
        hits.push({ type: 'Artist', text: a.name, query: a.name, icon: '🎤' });
      }
    });

    // Check curated tracks
    CURATED_TRACKS.forEach(t => {
      if (t.title.toLowerCase().includes(q) && hits.length < 5) {
        hits.push({ type: 'Song', text: `${t.title} - ${t.artist}`, query: t.title, icon: '🎵' });
      }
    });

    // Check moods
    FEATURED_STATIONS.forEach(s => {
      if (s.title.toLowerCase().includes(q) && hits.length < 6) {
        hits.push({ type: 'Mood', text: s.title, query: s.query, icon: s.icon });
      }
    });

    return hits.slice(0, 5);
  }, [searchQuery]);

  const displayedSongs = searchResults.length > 0 ? searchResults : defaultPopularSongs;
  const topSongMatch = displayedSongs.length > 0 ? displayedSongs[0] : null;

  // Deduplicated right-column songs so the #1 song isn't duplicated side-by-side
  const rightColumnSongs = useMemo(() => {
    if (activeFilter === 'Songs') {
      return displayedSongs.slice(0, 30);
    }
    if (topSongMatch && (!isExplicitArtistMatch || !topArtistMatch)) {
      const filtered = displayedSongs.filter(
        t => t.id !== topSongMatch.id && normalizeTrackName(t.title) !== normalizeTrackName(topSongMatch.title)
      );
      return filtered.slice(0, 4);
    }
    return displayedSongs.slice(0, 4);
  }, [activeFilter, displayedSongs, topSongMatch, isExplicitArtistMatch, topArtistMatch]);

  // Filtered views depending on tab
  const filteredArtists = useMemo(() => {
    const list = Object.values(VERIFIED_ARTISTS);
    if (!searchQuery.trim()) return list.slice(0, 6);
    const q = searchQuery.toLowerCase();
    const filtered = list.filter(a => a.name.toLowerCase().includes(q) || a.genres.some(g => g.toLowerCase().includes(q)));
    return filtered.length > 0 ? filtered : list.slice(0, 4);
  }, [searchQuery]);

  const isCurrentPlaying = (track) => {
    if (!currentTrack && !currentTrackId && !currentTrackTitle) return false;
    const matchId = currentTrackId === track.id || currentTrack?.id === track.id;
    const matchTitle = currentTrackTitle && normalizeTrackName(currentTrackTitle) === normalizeTrackName(track.title);
    return (matchId || matchTitle) && isPlaying;
  };

  return (
    <div className={`sp-search-stage ${isMobile ? 'is-mobile-search' : ''}`} id="search-stage-view">

      {/* 2. Search Input Stage Bar */}
      <div className="search-input-stage-wrap">
        <Search size={22} className="search-bar-icon" />
        <input
          ref={inputRef}
          type="text"
          className="search-bar-input"
          placeholder="Search songs, artists, Hindi hits, Diljit, podcasts..."
          value={searchQuery}
          onChange={e => {
            setSearchQuery(e.target.value);
            setShowSuggestions(true);
          }}
          onFocus={() => setShowSuggestions(true)}
          autoFocus={!isMobile}
          id="main-stage-search-input"
        />

        {/* Pulsing indicator when searching */}
        {isSearching && (
          <div className="search-pulse-indicator" title="Scanning lossless audio catalog...">
            <span />
            <span />
            <span />
          </div>
        )}

        {searchQuery && (
          <button
            className="search-clear-btn"
            onClick={() => {
              setSearchQuery('');
              setShowSuggestions(false);
              if (inputRef.current) inputRef.current.focus();
            }}
            title="Clear search"
          >
            <X size={18} />
          </button>
        )}

        {/* Live Suggestions Autocomplete Dropdown */}
        {showSuggestions && liveSuggestions.length > 0 && (
          <div className="search-suggestions-dropdown">
            <div className="suggestions-header">
              <Sparkles size={13} color="#1DB954" />
              <span>QUICK MATCHES</span>
            </div>
            {liveSuggestions.map((item, i) => (
              <div
                key={i}
                className="suggestion-item-row"
                onClick={() => handleSelectQuery(item.query)}
              >
                <span className="sugg-icon">{item.icon}</span>
                <span className="sugg-text">{item.text}</span>
                <span className="sugg-badge">{item.type}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 3. Filter Tabs: All | Songs | Artists | Albums | Playlists */}
      <div className="search-filters-row">
        {['All', 'Songs', 'Artists', 'Playlists', 'Moods'].map(tab => (
          <button
            key={tab}
            className={`search-filter-chip ${activeFilter === tab ? 'active' : ''}`}
            onClick={() => setActiveFilter(tab)}
          >
            {tab}
          </button>
        ))}
      </div>

      {/* 4. Recent Searches Row (when no search query) */}
      {recentSearches.length > 0 && !searchQuery && (
        <div className="search-recents-section">
          <div className="recents-header">
            <div className="recents-title-row">
              <Clock size={14} color="#a1a1aa" />
              <span className="recents-title">RECENT SEARCHES</span>
            </div>
            <button className="recents-clear-all" onClick={handleClearAllRecents}>
              Clear All
            </button>
          </div>

          <div className="recents-chips-scroll">
            {recentSearches.map((item, idx) => (
              <div
                key={idx}
                className="recent-query-chip"
                onClick={() => handleSelectQuery(item)}
              >
                <Clock size={13} color="#71717a" className="chip-clock" />
                <span>{item}</span>
                <button
                  className="chip-remove-btn"
                  onClick={(e) => handleRemoveRecent(e, item)}
                  title="Remove"
                >
                  <X size={12} />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 5. TAB VIEW: ARTISTS ONLY */}
      {activeFilter === 'Artists' && (
        <div className="artists-filter-view">
          <div className="search-section-header">
            <div className="section-dot-title">
              <span className="live-dot" />
              <h2 className="search-main-heading">Verified Artists Catalog</h2>
            </div>
            <span className="matched-precision-tag">ORIGINAL PORTRAITS ONLY</span>
          </div>

          <div className="artists-catalog-grid">
            {filteredArtists.map(artist => {
              const isFollowed = followedArtists.has(artist.id);
              return (
                <div key={artist.id} className="artist-catalog-card">
                  <div className="artist-avatar-container">
                    <ArtistAvatar
                      name={artist.name}
                      imageUrl={artist.image}
                      size={96}
                      showVerifiedBadge={artist.verified}
                    />
                  </div>
                  <h3 className="artist-catalog-name">{artist.name}</h3>
                  <p className="artist-catalog-listeners">{artist.listeners}</p>
                  <div className="artist-catalog-genres">
                    {artist.genres.slice(0, 2).map(g => (
                      <span key={g} className="artist-genre-pill">{g}</span>
                    ))}
                  </div>

                  <div className="artist-catalog-actions">
                    <button
                      type="button"
                      className={`artist-catalog-follow-btn ${isFollowed ? 'following' : ''}`}
                      onClick={() => handleToggleFollow(artist.id)}
                    >
                      {isFollowed ? (
                        <>
                          <UserCheck size={14} color="#1DB954" />
                          <span>Following</span>
                        </>
                      ) : (
                        <>
                          <UserPlus size={14} />
                          <span>Follow</span>
                        </>
                      )}
                    </button>
                    {artist.topTrack && (
                      <button
                        type="button"
                        className="artist-catalog-play-btn"
                        onClick={() => onSelectTrack(artist.topTrack)}
                        title={`Play top track by ${artist.name}`}
                      >
                        <Play size={18} fill="#000000" color="#000000" style={{ marginLeft: '1px' }} />
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* 6. TAB VIEW: PLAYLISTS OR MOODS */}
      {(activeFilter === 'Playlists' || activeFilter === 'Moods') && (
        <div className="playlists-filter-view">
          <div className="search-section-header">
            <div className="section-dot-title">
              <span className="live-dot" />
              <h2 className="search-main-heading">Curated Playlists & Mood Stations</h2>
            </div>
            <span className="matched-precision-tag">LOSSLESS BITRATE</span>
          </div>

          <div className="stations-grid-2x3">
            {FEATURED_STATIONS.map(st => (
              <div
                key={st.id}
                className="station-tile-card"
                style={{ background: st.gradient }}
                onClick={() => handleSelectQuery(st.query)}
              >
                <div className="station-card-top">
                  <span className="station-badge">{st.badge}</span>
                </div>
                <div className="station-card-middle">
                  <h3 className="station-title">{st.title}</h3>
                  <p className="station-desc">{st.desc}</p>
                </div>
                <div className="station-card-bottom">
                  {st.stat && <span className="station-stat">{st.stat}</span>}
                  <span className="station-big-emoji">{st.icon}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 7. TAB VIEW: ALL OR SONGS (SPOTIFY STANDARD EXPERIENCE) */}
      {(activeFilter === 'All' || activeFilter === 'Songs') && (
        <>
          {/* 2-Column Results Grid: Left Top Result | Right Songs */}
          <div className={`search-results-grid ${activeFilter === 'Songs' ? 'songs-only' : ''}`}>
            {/* Left: Spotify-Style Top Result (Song or Verified Artist) */}
            {activeFilter === 'All' && (
              <div className="top-result-column">
                <h2 className="search-block-title">Top result</h2>
                {isExplicitArtistMatch && topArtistMatch ? (
                  /* Explicit Verified Artist Card */
                  <div
                    className="spotify-top-result-card artist-mode"
                    onClick={() => {
                      if (topArtistMatch.topTrack) onSelectTrack(topArtistMatch.topTrack);
                    }}
                  >
                    <div className="artist-avatar-wrap">
                      <ArtistAvatar
                        name={topArtistMatch.name}
                        imageUrl={topArtistMatch.image}
                        size={92}
                        showVerifiedBadge={topArtistMatch.verified}
                      />
                    </div>
                    <h3 className="top-result-song-title">{topArtistMatch.name}</h3>
                    <div className="top-result-meta-row">
                      <span className="top-result-type-badge">Artist</span>
                    </div>
                    <div className="top-result-floating-play">
                      <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                    </div>
                  </div>
                ) : topSongMatch ? (
                  /* Spotify-Standard Top Song Match */
                  <div
                    className="spotify-top-result-card"
                    onClick={() => onSelectTrack(topSongMatch)}
                  >
                    <img
                      src={topSongMatch.coverUrl}
                      alt={topSongMatch.title}
                      className="top-result-song-cover"
                    />
                    <h3 className="top-result-song-title" title={topSongMatch.title}>
                      {topSongMatch.title}
                    </h3>
                    <div className="top-result-meta-row">
                      <span className="top-result-artist-name">{topSongMatch.artist}</span>
                      <span className="top-result-type-badge">Song</span>
                    </div>
                    <div className="top-result-floating-play">
                      <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
                    </div>
                  </div>
                ) : null}
              </div>
            )}

            {/* Right: Clean Spotify Desktop Songs Column */}
            <div className={`songs-result-column ${activeFilter === 'Songs' ? 'full-width' : ''}`}>
              <h2 className="search-block-title">Songs</h2>

              <div className="popular-songs-list">
                {rightColumnSongs.map((track, idx) => {
                  const playing = isCurrentPlaying(track);
                  const isFav = favorites.includes(track.id);
                  const isDownloaded = downloadedTrackIds.includes(track.id) || track.isVerified;

                  return (
                    <div
                      key={`song-${track.id || idx}`}
                      className={`search-song-row ${playing ? 'active playing-row' : ''}`}
                      onClick={() => onSelectTrack(track)}
                    >
                      <div className="search-thumb-wrapper">
                        <img
                          src={track.coverUrl}
                          alt={track.title}
                          className="search-song-thumb"
                        />
                        {/* Play/Pause Hover Overlay Icon */}
                        <div className="search-thumb-play-overlay">
                          {playing ? (
                            <Pause size={18} fill="#ffffff" color="#ffffff" />
                          ) : (
                            <Play size={18} fill="#ffffff" color="#ffffff" style={{ marginLeft: '1px' }} />
                          )}
                        </div>
                        {/* Animated Equalizer Wave Overlay when playing */}
                        {playing && (
                          <div className="search-thumb-eq">
                            <span /><span /><span />
                          </div>
                        )}
                      </div>

                      <div className="search-song-info">
                        <div className="search-song-title-row">
                          <span className={`search-song-name ${playing ? 'playing-title' : ''}`}>
                            {track.title}
                          </span>
                          {track.formatBadge && !isMobile && (
                            <span className={`search-format-badge ${track.formatBadge === 'DOLBY ATMOS' ? 'atmos' : 'hifi'}`}>
                              {track.formatBadge}
                            </span>
                          )}
                        </div>
                        <span className="search-song-artist">
                          {track.artist}
                          {isMobile && ` • ${formatDuration(track.duration)}`}
                        </span>
                      </div>

                      {!isMobile && (
                        <span className="search-song-duration">
                          {formatDuration(track.duration)}
                        </span>
                      )}

                      <div className="search-song-actions">
                        {/* Like / Favorite Button */}
                        <button
                          type="button"
                          className={`song-action-icon ${isFav ? 'is-favorited' : ''}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            if (onToggleFavorite) onToggleFavorite(track);
                          }}
                          title={isFav ? 'Remove from Liked Songs' : 'Save to Liked Songs'}
                        >
                          <Heart size={16} fill={isFav ? '#ef4444' : 'none'} color={isFav ? '#ef4444' : 'currentColor'} />
                        </button>

                        {/* Add to Playlist Button */}
                        {onOpenAddToPlaylist && (
                          <button
                            type="button"
                            className="song-action-icon"
                            onClick={(e) => {
                              e.stopPropagation();
                              onOpenAddToPlaylist(track);
                            }}
                            title="Add to Playlist"
                          >
                            <ListPlus size={16} />
                          </button>
                        )}

                        {/* Download Offline Button */}
                        <button
                          type="button"
                          className={`song-action-icon ${isDownloaded ? 'is-downloaded' : ''}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            if (onToggleDownload) onToggleDownload(track);
                          }}
                          title={isDownloaded ? 'Downloaded Offline' : 'Download Offline'}
                        >
                          {isDownloaded ? (
                            <div className="sp-verified-check-circle" style={{ width: 16, height: 16 }}>
                              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                                <circle cx="12" cy="12" r="10" stroke="#1DB954" strokeWidth="2" fill="rgba(29, 185, 84, 0.2)" />
                                <path d="M8 12.5l2.5 2.5 5.5-5.5" stroke="#1DB954" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
                              </svg>
                            </div>
                          ) : (
                            <ArrowDownCircle size={16} />
                          )}
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>

          {/* 8. Explore Moods & Genres Section (in All tab) */}
          {activeFilter === 'All' && (
            <div className="explore-stations-section">
              <div className="search-section-header">
                <div>
                  <h2 className="search-main-heading">Explore Moods & Genres</h2>
                  <p className="search-subtext">Curated mood mixes & genre playlists matching your listening energy</p>
                </div>
                <span className="matched-precision-tag">6 Featured Stations</span>
              </div>

              <div className="stations-grid-2x3">
                {FEATURED_STATIONS.map(st => (
                  <div
                    key={st.id}
                    className="station-tile-card"
                    style={{ background: st.gradient }}
                    onClick={() => handleSelectQuery(st.query)}
                  >
                    <div className="station-card-top">
                      <span className="station-badge">{st.badge}</span>
                    </div>
                    <div className="station-card-middle">
                      <h3 className="station-title">{st.title}</h3>
                      <p className="station-desc">{st.desc}</p>
                    </div>
                    <div className="station-card-bottom">
                      {st.stat && <span className="station-stat">{st.stat}</span>}
                      <span className="station-big-emoji">{st.icon}</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
