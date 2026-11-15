import { useEffect, useRef, useState } from 'react';
import { Search as SearchIcon, X, Play, Clock } from 'lucide-react';
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
  const [result, setResult] = useState({ status: 'idle', data: null, error: '', q: '' });
  const inputRef = useRef(null);
  const latest = useRef(0);

  // Desktop: ready to type. Phones: don't pop the keyboard up on every visit.
  useEffect(() => {
    if (window.matchMedia?.('(pointer: fine)').matches) inputRef.current?.focus({ preventScroll: true });
  }, []);

  useEffect(() => {
    const q = query.trim();
    if (!q) { setResult({ status: 'idle', data: null, error: '', q: '' }); return; }
    const id = ++latest.current;
    setResult((r) => ({ ...r, status: 'loading' }));
    const timer = setTimeout(async () => {
      try {
        const data = await catalog.search(q);
        if (id === latest.current) setResult({ status: 'ready', data, error: '', q });
      } catch (err) {
        if (id === latest.current) setResult({ status: 'error', data: null, error: err.message, q });
      }
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);

  const { data } = result;
  const songs = data?.songs || [];
  const hasResults = songs.length || data?.artists?.length || data?.videos?.length;
  const source = `Search: ${result.q}`;

  return (
    <div className="view search">
      <form className="search__bar" role="search" onSubmit={(e) => { e.preventDefault(); library.addRecentSearch(query); inputRef.current?.blur(); }}>
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
        {query && <IconButton label="Clear search" size="sm" onClick={() => { setQuery(''); inputRef.current?.focus(); }}><X size={18} /></IconButton>}
      </form>

      {result.status === 'idle' && (
        <>
          {searches.length > 0 && (
            <section className="section">
              <SectionHeader title="Recent searches" action={<button type="button" className="link-btn" onClick={() => library.clearRecentSearches()}>Clear</button>} />
              <ul className="recent-searches">
                {searches.map((s) => (
                  <li key={s}><button type="button" className="recent-searches__item" onClick={() => setQuery(s)}><Clock size={16} aria-hidden="true" />{s}</button></li>
                ))}
              </ul>
            </section>
          )}
          <p className="search__tip">Remember only a line? Type it. Searching “tujhe dekha to ye jaana sanam” finds the song.</p>
          <section className="section">
            <SectionHeader title="Browse by mood" />
            <div className="moods">
              {MOODS.map((m) => (
                <button key={m.id} type="button" className="mood" style={{ '--hue': m.hue }} onClick={() => navigate('collection', { title: m.name, query: moodQuery(m, taste.languages), kind: 'mood' })}>
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

      {data && hasResults ? (
        <div className={`search__results ${result.status === 'loading' ? 'is-stale' : ''}`}>
          {songs[0] && (
            <section className="section">
              <SectionHeader title="Top result" />
              <TopResult track={songs[0]} query={result.q} list={songs} />
            </section>
          )}
          {songs.length > 1 && (
            <section className="section">
              <SectionHeader title="Songs" />
              <div className="tracks">
                {songs.slice(1, 15).map((t) => <TrackRow key={t.id} track={t} list={songs} source={source} />)}
              </div>
            </section>
          )}
          {data.artists?.length > 0 && (
            <section className="section">
              <SectionHeader title="Artists" />
              <div className="artist-row">
                {data.artists.map((a) => (
                  <button key={a.id} type="button" className="artist-chip" onClick={() => navigate('artist', { id: a.id, name: a.name })}>
                    <Artwork src={a.imageUrl} title={a.name} round px={240} />
                    <span className="artist-chip__name">{a.name}</span>
                    {a.audience && <span className="artist-chip__meta">{a.audience}</span>}
                  </button>
                ))}
              </div>
            </section>
          )}
          {data.videos?.length > 0 && (
            <section className="section">
              <SectionHeader title="Music videos" subtitle="Official videos for songs not in the audio catalogue" />
              <div className="tracks">
                {data.videos.map((t) => <TrackRow key={t.id} track={t} list={data.videos} source={source} />)}
              </div>
            </section>
          )}
        </div>
      ) : null}
    </div>
  );
}
