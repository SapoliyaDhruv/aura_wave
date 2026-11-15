import { useEffect, useRef, useState, useMemo } from 'react';
import { Search as SearchIcon, X, Play, Clock, TrendingUp, Music2, User, Video, Sparkles } from 'lucide-react';
import * as catalog from '../services/catalog.js';
import { getLyrics } from '../services/lyrics.js';
import { library } from '../services/library.js';
import { useLibrary } from '../state/library.js';
import { usePlayer } from '../state/player.jsx';
import { useRouter } from '../state/router.jsx';
import { Artwork, SectionHeader, SkeletonRows, EmptyState, IconButton } from '../components/ui.jsx';
import TrackRow from '../components/TrackRow.jsx';

export const MOODS = [
  { id: 'romantic', name: 'Romantic', q: 'romantic songs', hue: 340 },
  { id: 'heartbreak', name: 'Heartbreak', q: 'sad heartbreak songs', hue: 220 },
  { id: 'party', name: 'Party', q: 'party dance songs', hue: 20 },
  { id: 'chill', name: 'Chill', q: 'chill relaxing songs', hue: 170 },
  { id: 'workout', name: 'Workout', q: 'workout gym songs', hue: 0 },
  { id: 'drive', name: 'Long drive', q: 'long drive road trip songs', hue: 40 },
  { id: 'devotional', name: 'Devotional', q: 'devotional bhajan songs', hue: 50 },
  { id: 'retro', name: '90s & retro', q: '90s evergreen songs', hue: 280 },
  { id: 'punjabi', name: 'Punjabi hits', q: 'punjabi hit songs', hue: 95, noLang: true },
  { id: 'indie', name: 'Indie', q: 'indie acoustic songs', hue: 140 },
];

export const TRENDING_SEARCHES = [
  'Arijit Singh',
  'Anirudh Ravichander',
  'Sidhu Moose Wala',
  'Diljit Dosanjh',
  'Karan Aujla',
  'Taylor Swift',
  'The Weeknd',
  'Pritam',
  'Shreya Ghoshal',
  'Badshah',
  'Lofi Chill Hits',
  'Bollywood Romantic',
];

export function moodQuery(mood, languages = []) {
  const lang = mood.noLang ? '' : (languages[0] || '').toLowerCase();
  return lang && lang !== 'english' ? `${lang} ${mood.q}` : mood.q;
}

/** Finds the lyric line that best matches what the listener typed. */
function bestLine(lyrics, query) {
  const words = query.toLowerCase().split(/\s+/).filter((w) => w.length > 1);
  const lines = lyrics?.lines?.map((l) => l.text) || lyrics?.plain?.split('\n') || [];
  let best = null; let bestScore = 0;
  for (const line of lines) {
    const lw = line.toLowerCase();
    const score = words.filter((w) => lw.includes(w)).length;
    if (score > bestScore) { best = line; bestScore = score; }
  }
  return bestScore >= Math.min(2, words.length) ? best : null;
}

function TopResult({ track, query, list }) {
  const player = usePlayer();
  const [line, setLine] = useState(null);
  const viaLyrics = catalog.isLyricsMatch(query, track);
  useEffect(() => {
    setLine(null);
    if (!viaLyrics) return;
    let alive = true;
    getLyrics(track).then((l) => alive && setLine(bestLine(l, query))).catch(() => {});
    return () => { alive = false; };
  }, [track, query, viaLyrics]);

  return (
    <button type="button" className="top-result" onClick={() => { library.addRecentSearch(query); player.playTrack(track, { context: list, source: `Search: ${query}` }); }}>
      <Artwork src={track.coverUrl} title={track.title} className="top-result__art" eager px={240} />
      <span className="top-result__text">
        {viaLyrics && <span className="tag tag--aura">Found by lyrics</span>}
        <span className="top-result__title">{track.title}</span>
        <span className="top-result__artist">{track.artist}{track.album ? `, ${track.album}` : ''}</span>
        {line && <span className="top-result__line">“{line}”</span>}
      </span>
      <span className="top-result__play play-btn play-btn--md" aria-hidden="true"><Play size={18} fill="currentColor" className="play-btn__play" /></span>
    </button>
  );
}

export default function SearchView() {
  const { searches, taste } = useLibrary();
  const { navigate } = useRouter();
  const [query, setQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState('all'); // 'all' | 'songs' | 'artists' | 'videos'
  const [showAllSongs, setShowAllSongs] = useState(false);
  const [result, setResult] = useState({ status: 'idle', data: null, error: '', q: '' });
  const inputRef = useRef(null);
  const latest = useRef(0);

  // Desktop: ready to type. Phones: don't pop the keyboard up on every visit.
  useEffect(() => {
    if (window.matchMedia?.('(pointer: fine)').matches) inputRef.current?.focus({ preventScroll: true });
  }, []);

  useEffect(() => {
    const q = query.trim();
    if (!q) {
      setResult({ status: 'idle', data: null, error: '', q: '' });
      setShowAllSongs(false);
      return;
    }

    const id = ++latest.current;
    // Fast instant local match preview (0ms) so user never sees an empty screen
    const local = catalog.searchCatalog(q);
    if (local.length > 0) {
      setResult({ status: 'loading', data: { songs: local, videos: [], artists: [] }, error: '', q });
    } else {
      setResult((r) => ({ ...r, status: 'loading' }));
    }

    // Faster 200ms debounce for responsive typing
    const timer = setTimeout(async () => {
      try {
        const data = await catalog.search(q);
        if (id === latest.current) {
          setResult({ status: 'ready', data, error: '', q });
        }
      } catch (err) {
        if (id === latest.current) {
          setResult({ status: 'error', data: null, error: err.message, q });
        }
      }
    }, 200);

    return () => clearTimeout(timer);
  }, [query]);

  const { data } = result;
  const songs = data?.songs || [];
  const artists = data?.artists || [];
  const videos = data?.videos || [];
  const hasResults = songs.length > 0 || artists.length > 0 || videos.length > 0;
  const source = `Search: ${result.q}`;

  const displayedSongs = useMemo(() => {
    if (activeFilter === 'songs' || showAllSongs) return songs;
    return songs.slice(1, 12);
  }, [songs, activeFilter, showAllSongs]);

  return (
    <div className="view search">
      <form
        className="search__bar"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          if (query.trim()) library.addRecentSearch(query);
          inputRef.current?.blur();
        }}
      >
        <SearchIcon size={20} className="search__icon" aria-hidden="true" />
        <label htmlFor="search-input" className="sr-only">Search songs, artists or lyrics</label>
        <input
          id="search-input"
          ref={inputRef}
          className="search__input"
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Songs, artists or lyrics"
          autoComplete="off"
          enterKeyHint="search"
          spellCheck="false"
        />
        {query && (
          <IconButton label="Clear search" size="sm" onClick={() => { setQuery(''); setShowAllSongs(false); inputRef.current?.focus(); }}>
            <X size={18} />
          </IconButton>
        )}
      </form>

      {/* Filter Tabs when query is active and results are loaded */}
      {data && hasResults && (
        <div className="search__filters" role="tablist">
          <button
            type="button"
            className={`search__filter ${activeFilter === 'all' ? 'is-active' : ''}`}
            onClick={() => setActiveFilter('all')}
          >
            All
          </button>
          {songs.length > 0 && (
            <button
              type="button"
              className={`search__filter ${activeFilter === 'songs' ? 'is-active' : ''}`}
              onClick={() => setActiveFilter('songs')}
            >
              Songs ({songs.length})
            </button>
          )}
          {artists.length > 0 && (
            <button
              type="button"
              className={`search__filter ${activeFilter === 'artists' ? 'is-active' : ''}`}
              onClick={() => setActiveFilter('artists')}
            >
              Artists ({artists.length})
            </button>
          )}
          {videos.length > 0 && (
            <button
              type="button"
              className={`search__filter ${activeFilter === 'videos' ? 'is-active' : ''}`}
              onClick={() => setActiveFilter('videos')}
            >
              Videos ({videos.length})
            </button>
          )}
        </div>
      )}

      {/* Idle view (Recent Searches, Trending Searches, Moods) */}
      {result.status === 'idle' && (
        <>
          {searches.length > 0 && (
            <section className="section">
              <SectionHeader
                title="Recent searches"
                action={<button type="button" className="link-btn" onClick={() => library.clearRecentSearches()}>Clear all</button>}
              />
              <ul className="recent-searches">
                {searches.map((s) => (
                  <li key={s} className="recent-searches__row">
                    <button type="button" className="recent-searches__item" onClick={() => setQuery(s)}>
                      <Clock size={16} aria-hidden="true" />
                      <span>{s}</span>
                    </button>
                    <button
                      type="button"
                      className="recent-searches__remove"
                      aria-label={`Remove ${s}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        library.removeRecentSearch(s);
                      }}
                    >
                      <X size={14} />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* Trending Searches discovery */}
          <section className="section">
            <SectionHeader title="Trending right now" subtitle="Popular artists and viral hits" />
            <div className="trending-chips">
              {TRENDING_SEARCHES.map((term) => (
                <button
                  key={term}
                  type="button"
                  className="trending-chip"
                  onClick={() => {
                    setQuery(term);
                    library.addRecentSearch(term);
                  }}
                >
                  <TrendingUp size={14} className="accent-icon" />
                  <span>{term}</span>
                </button>
              ))}
            </div>
          </section>

          <p className="search__tip">
            Remember only a line? Type it. Searching “tujhe dekha to ye jaana sanam” finds the song.
          </p>

          <section className="section">
            <SectionHeader title="Browse by mood" />
            <div className="moods">
              {MOODS.map((m) => (
                <button
                  key={m.id}
                  type="button"
                  className="mood"
                  style={{ '--hue': m.hue }}
                  onClick={() => navigate('collection', { title: m.name, query: moodQuery(m, taste.languages), kind: 'mood' })}
                >
                  {m.name}
                </button>
              ))}
            </div>
          </section>
        </>
      )}

      {result.status === 'loading' && !data && <SkeletonRows count={6} />}
      {result.status === 'error' && <EmptyState title="Search didn’t work">{result.error}</EmptyState>}
      {result.status !== 'idle' && data && !hasResults && (
        <EmptyState title={`No songs match “${result.q}”`}>Check the spelling, or try a line from the lyrics.</EmptyState>
      )}

      {/* Results View */}
      {data && hasResults ? (
        <div className={`search__results ${result.status === 'loading' ? 'is-stale' : ''}`}>
          {/* Top Result - shown in 'all' or 'songs' mode */}
          {(activeFilter === 'all' || activeFilter === 'songs') && songs[0] && (
            <section className="section">
              <SectionHeader title="Top result" />
              <TopResult track={songs[0]} query={result.q} list={songs} />
            </section>
          )}

          {/* Songs List */}
          {(activeFilter === 'all' || activeFilter === 'songs') && songs.length > 1 && (
            <section className="section">
              <SectionHeader
                title="Songs"
                subtitle={activeFilter === 'all' && !showAllSongs ? `Showing top matches (${songs.length} found)` : `${songs.length} tracks found`}
              />
              <div className="tracks">
                {displayedSongs.map((t) => (
                  <TrackRow key={t.id} track={t} list={songs} source={source} />
                ))}
              </div>
              {activeFilter === 'all' && !showAllSongs && songs.length > 12 && (
                <button
                  type="button"
                  className="search-show-more"
                  onClick={() => setShowAllSongs(true)}
                >
                  Show all {songs.length} songs
                </button>
              )}
            </section>
          )}

          {/* Artists */}
          {(activeFilter === 'all' || activeFilter === 'artists') && artists.length > 0 && (
            <section className="section">
              <SectionHeader title="Artists" subtitle={`${artists.length} artists`} />
              <div className="artist-row">
                {artists.map((a) => (
                  <button
                    key={a.id}
                    type="button"
                    className="artist-chip"
                    onClick={() => navigate('artist', { id: a.id, name: a.name })}
                  >
                    <Artwork src={a.imageUrl} title={a.name} round px={240} />
                    <span className="artist-chip__name">{a.name}</span>
                    {a.audience && <span className="artist-chip__meta">{a.audience}</span>}
                  </button>
                ))}
              </div>
            </section>
          )}

          {/* Videos */}
          {(activeFilter === 'all' || activeFilter === 'videos') && videos.length > 0 && (
            <section className="section">
              <SectionHeader title="Music videos" subtitle="Official videos for songs" />
              <div className="tracks">
                {videos.map((t) => (
                  <TrackRow key={t.id} track={t} list={videos} source={source} />
                ))}
              </div>
            </section>
          )}
        </div>
      ) : null}
    </div>
  );
}
