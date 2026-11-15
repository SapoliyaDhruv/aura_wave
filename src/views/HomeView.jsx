import { useCallback, useEffect, useMemo, useState } from 'react';
import { Play, Shuffle, RefreshCw, Settings } from 'lucide-react';
import { buildFeed, localFeed } from '../services/recommender.js';
import { useLibrary } from '../state/library.js';
import { usePlayer } from '../state/player.jsx';
import { useRouter } from '../state/router.jsx';
import { extractAura } from '../lib/color.js';
import { greeting, shortTitle } from '../lib/format.js';
import { Artwork, Sleeves, SectionHeader, SkeletonRows, IconButton, EmptyState, sized } from '../components/ui.jsx';
import TrackRow from '../components/TrackRow.jsx';

function useTint(src) {
  const [tint, setTint] = useState(null);
  useEffect(() => {
    let alive = true;
    extractAura(sized(src, 120)).then((a) => alive && setTint(a));
    return () => { alive = false; };
  }, [src]);
  return tint;
}

function Hero({ mix, onRefresh, refreshing }) {
  const player = usePlayer();
  const tint = useTint(mix.tracks[0]?.coverUrl);
  const playing = player.source === mix.name;
  return (
    <section className="hero" style={tint ? { '--hero-a': tint.accent, '--hero-d': tint.deep } : undefined} aria-labelledby="hero-title">
      <Sleeves tracks={mix.tracks} variant="fan" className="hero__sleeves" px={480} />
      <div className="hero__text">
        <h2 id="hero-title" className="hero__title">{mix.name}</h2>
        <p className="hero__sub">{mix.subtitle}</p>
        <p className="hero__names">{[...new Set(mix.tracks.slice(0, 4).map((t) => shortTitle(t.title)))].join(', ')} and more</p>
        <div className="hero__actions">
          <button type="button" className="btn btn--primary btn--lg" onClick={() => player.playList(mix.tracks, 0, { source: mix.name })}>
            <Play size={18} fill="currentColor" />{playing ? 'Play again' : 'Play'}
          </button>
          <IconButton label="Shuffle play" onClick={() => { const i = Math.floor(Math.random() * mix.tracks.length); player.playList(mix.tracks, i, { source: mix.name }); }}><Shuffle size={20} /></IconButton>
          <IconButton label="Get new picks" onClick={onRefresh} disabled={refreshing}><RefreshCw size={19} className={refreshing ? 'spin' : ''} /></IconButton>
        </div>
      </div>
    </section>
  );
}

function QuickGrid({ tracks }) {
  const player = usePlayer();
  return (
    <div className="quick">
      {tracks.slice(0, 6).map((t) => (
        <button key={t.id} type="button" className={`quick__item ${player.current?.id === t.id ? 'is-current' : ''}`} onClick={() => player.playTrack(t, { context: tracks, source: 'Recently played' })}>
          <Artwork src={t.coverUrl} title={t.title} px={120} />
          <span className="quick__title">{shortTitle(t.title)}</span>
        </button>
      ))}
    </div>
  );
}

function MixCard({ mix }) {
  const { navigate } = useRouter();
  const player = usePlayer();
  return (
    <div className="mix-card">
      <button type="button" className="mix-card__open" onClick={() => navigate('mix', { mix })}>
        <Sleeves tracks={mix.tracks} variant="stack" />
        <span className="mix-card__name">{mix.name}</span>
        <span className="mix-card__sub">{mix.subtitle}</span>
      </button>
      <button type="button" className="mix-card__play play-btn play-btn--md" aria-label={`Play ${mix.name}`} onClick={() => player.playList(mix.tracks, 0, { source: mix.name })}>
        <Play size={18} fill="currentColor" className="play-btn__play" />
      </button>
    </div>
  );
}

function CoverCard({ track, list, source }) {
  const player = usePlayer();
  const isCurrent = player.current?.id === track.id;
  return (
    <button type="button" className={`cover-card ${isCurrent ? 'is-current' : ''}`} onClick={() => (isCurrent ? player.togglePlay() : player.playTrack(track, { context: list, source }))}>
      <Artwork src={track.coverUrl} title={track.title} px={360} />
      <span className="cover-card__title">{shortTitle(track.title)}</span>
      <span className="cover-card__reason">{track.reason || track.artist}</span>
    </button>
  );
}

function Shelf({ children, label }) {
  return <div className="shelf" role="list" aria-label={label}>{children}</div>;
}

export default function HomeView() {
  const lib = useLibrary();
  const player = usePlayer();
  const { navigate } = useRouter();
  const [feed, setFeed] = useState({ status: 'loading', data: null, error: '' });
  const [refreshing, setRefreshing] = useState(false);
  const local = useMemo(() => localFeed(lib), [lib]);

  const load = useCallback(async (force = false) => {
    if (force) setRefreshing(true);
    try {
      const data = await buildFeed({ force });
      setFeed({ status: data.empty ? 'empty' : 'ready', data, error: '' });
    } catch (err) {
      setFeed((f) => ({ status: f.data ? 'ready' : 'error', data: f.data, error: err.message }));
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { load(false); }, [load, lib.taste.onboarded]);

  const data = feed.data;
  return (
    <div className="view home">
      <header className="home__head">
        <h1 className="home__greeting">{greeting()}</h1>
        <IconButton label="Settings" className="home__settings" onClick={() => navigate('settings')}><Settings size={21} /></IconButton>
      </header>

      {local.recent.length > 0 && <QuickGrid tracks={local.recent} />}

      {feed.status === 'loading' && (
        <div className="hero hero--skeleton" aria-busy="true" aria-label="Loading your mix">
          <div className="skeleton hero__skeleton-art" />
          <div className="hero__text"><div className="skeleton skeleton--title" /><div className="skeleton skeleton--line" /></div>
        </div>
      )}

      {feed.status === 'error' && (
        <EmptyState title="Recommendations didn’t load" action={<button type="button" className="btn btn--primary" onClick={() => load(true)}>Try again</button>}>
          {feed.error}
        </EmptyState>
      )}

      {feed.status === 'empty' && (
        <EmptyState title="Let’s find your sound" action={<button type="button" className="btn btn--primary" onClick={() => navigate('onboarding')}>Pick artists you love</button>}>
          Play or like a few songs, or pick some artists, and this page fills with mixes made for you.
        </EmptyState>
      )}

      {data?.hero?.tracks?.length > 0 && <Hero mix={data.hero} onRefresh={() => load(true)} refreshing={refreshing} />}

      {data?.mixes?.length > 0 && (
        <section className="section">
          <SectionHeader title="Your mixes" subtitle="Built around the artists you play most" />
          <Shelf label="Your mixes">{data.mixes.map((m) => <MixCard key={m.id} mix={m} />)}</Shelf>
        </section>
      )}

      {data?.because?.tracks?.length > 0 && (
        <section className="section">
          <SectionHeader
            lead={<Artwork src={data.because.seed.coverUrl} title={data.because.seed.title} className="section-head__lead" px={120} />}
            title={`Because you played ${shortTitle(data.because.seed.title)}`}
            subtitle="Songs listeners often play next"
            action={<button type="button" className="btn btn--ghost btn--sm" onClick={() => player.playList(data.because.tracks, 0, { source: `After ${shortTitle(data.because.seed.title)}` })}><Play size={15} fill="currentColor" />Play all</button>}
          />
          <div className="tracks">
            {data.because.tracks.slice(0, 5).map((t) => <TrackRow key={t.id} track={t} list={data.because.tracks} source={`After ${shortTitle(data.because.seed.title)}`} menu={{ recommended: true }} />)}
          </div>
        </section>
      )}

      {local.onRepeat.length >= 3 && (
        <section className="section">
          <SectionHeader title="On repeat" subtitle="Your most played this month" />
          <div className="tracks">
            {local.onRepeat.slice(0, 5).map((t, i) => <TrackRow key={t.id} track={t} rank={i + 1} list={local.onRepeat} source="On repeat" />)}
          </div>
        </section>
      )}

      {data?.freshFinds?.length > 0 && (
        <section className="section">
          <SectionHeader title="Fresh finds" subtitle="New to you, close to your taste" />
          <Shelf label="Fresh finds">{data.freshFinds.map((t) => <CoverCard key={t.id} track={t} list={data.freshFinds} source="Fresh finds" />)}</Shelf>
        </section>
      )}

      {local.rediscover.length >= 3 && (
        <section className="section">
          <SectionHeader title="Rediscover" subtitle="Songs you loved and haven’t heard lately" />
          <Shelf label="Rediscover">{local.rediscover.map((t) => <CoverCard key={t.id} track={t} list={local.rediscover} source="Rediscover" />)}</Shelf>
        </section>
      )}

      {feed.status === 'loading' && <SkeletonRows count={4} />}
      {feed.error && feed.status === 'ready' && <p className="home__stale">Showing earlier picks. {feed.error}</p>}
    </div>
  );
}
