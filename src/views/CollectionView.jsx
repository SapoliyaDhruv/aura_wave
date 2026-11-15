import { useEffect, useMemo, useState } from 'react';
import { Play, Shuffle, Heart, Pencil, Trash2, Save, Search, History } from 'lucide-react';
import { useLibrary } from '../state/library.js';
import { usePlayer } from '../state/player.jsx';
import { useRouter } from '../state/router.jsx';
import { useUI } from '../state/ui.jsx';
import { useToast } from '../state/toast.jsx';
import { library } from '../services/library.js';
import * as catalog from '../services/catalog.js';
import { formatTotal, plural } from '../lib/format.js';
import { Mosaic, Sleeves, IconButton, SkeletonRows, EmptyState } from '../components/ui.jsx';
import TrackRow from '../components/TrackRow.jsx';

/** Resolves the current route into { title, subtitle, tracks, kind, playlist }. */
function useCollection(route) {
  const lib = useLibrary();
  const [remote, setRemote] = useState({ status: 'idle', tracks: [], error: '' });
  const query = route.name === 'collection' ? route.params.query : null;

  useEffect(() => {
    if (!query) return;
    let alive = true;
    setRemote({ status: 'loading', tracks: [], error: '' });
    catalog.search(query)
      .then((r) => alive && setRemote({ status: 'ready', tracks: r.songs, error: '' }))
      .catch((err) => alive && setRemote({ status: 'error', tracks: [], error: err.message }));
    return () => { alive = false; };
  }, [query]);

  return useMemo(() => {
    switch (route.name) {
      case 'liked':
        return { kind: 'liked', title: 'Liked songs', tracks: lib.liked };
      case 'playlist': {
        const playlist = lib.playlists.find((p) => p.id === route.params.id);
        if (!playlist) return { kind: 'missing', title: 'Playlist not found', tracks: [] };
        return { kind: 'playlist', title: playlist.name, subtitle: playlist.source === 'spotify' ? 'Imported from Spotify' : playlist.description, tracks: playlist.tracks, playlist };
      }
      case 'mix':
        return { kind: 'mix', title: route.params.mix.name, subtitle: route.params.mix.subtitle, tracks: route.params.mix.tracks };
      case 'history': {
        const seen = new Set();
        const tracks = lib.history.map((h) => h.track).filter((t) => !seen.has(t.id) && seen.add(t.id));
        return { kind: 'history', title: 'Recently played', tracks };
      }
      case 'collection':
        return { kind: 'collection', title: route.params.title, subtitle: route.params.kind === 'mood' ? 'A station from YouTube Music, matched to your languages' : '', tracks: remote.tracks, status: remote.status, error: remote.error };
      default:
        return { kind: 'missing', title: '', tracks: [] };
    }
  }, [route, lib, remote]);
}

export default function CollectionView() {
  const { route, navigate, back } = useRouter();
  const player = usePlayer();
  const { openSheet } = useUI();
  const toast = useToast();
  const c = useCollection(route);
  const total = c.tracks.reduce((s, t) => s + (t.duration || 0), 0);
  const source = c.title;

  if (c.kind === 'missing') {
    return <div className="view"><EmptyState title="This playlist no longer exists" action={<button type="button" className="btn btn--primary" onClick={() => navigate('library')}>Go to your library</button>} /></div>;
  }

  const cover = c.kind === 'liked'
    ? <div className="cover-liked" aria-hidden="true"><Heart size={56} fill="currentColor" /></div>
    : c.kind === 'history'
      ? <div className="cover-liked cover-liked--history" aria-hidden="true"><History size={56} /></div>
      : c.kind === 'mix'
        ? <Sleeves tracks={c.tracks} variant="stack" px={480} />
        : <Mosaic tracks={c.tracks} title={c.title} px={480} />;

  const saveAsPlaylist = () => {
    const pl = library.createPlaylist(c.title, c.tracks);
    toast(`Saved as ${pl.name}`);
    navigate('playlist', { id: pl.id });
  };

  const morePlaylist = () => openSheet('confirm', {
    title: `Delete “${c.title}”?`,
    body: `${plural(c.tracks.length, 'song')} will be removed from this playlist. The songs stay in Liked songs if you liked them.`,
    confirmLabel: 'Delete playlist',
    onConfirm: () => { library.deletePlaylist(c.playlist.id); toast('Playlist deleted'); back(); },
  });

  return (
    <div className="view collection">
      <header className="collection__head">
        <div className="collection__cover">{cover}</div>
        <div className="collection__info">
          <h1 className="collection__title">{c.title}</h1>
          {c.subtitle && <p className="collection__sub">{c.subtitle}</p>}
          <p className="collection__meta">{plural(c.tracks.length, 'song')}{total > 0 ? `, ${formatTotal(total)}` : ''}</p>
        </div>
      </header>

      <div className="collection__actions">
        <button type="button" className="btn btn--primary btn--lg" disabled={!c.tracks.length} onClick={() => player.playList(c.tracks, 0, { source })}><Play size={18} fill="currentColor" />Play</button>
        <IconButton label="Shuffle play" disabled={!c.tracks.length} onClick={() => player.playList(c.tracks, Math.floor(Math.random() * c.tracks.length), { source })}><Shuffle size={20} /></IconButton>
        {(c.kind === 'mix' || c.kind === 'collection') && c.tracks.length > 0 && <IconButton label="Save as playlist" onClick={saveAsPlaylist}><Save size={19} /></IconButton>}
        {c.kind === 'playlist' && (
          <>
            <IconButton label="Rename playlist" onClick={() => openSheet('rename', { playlist: c.playlist })}><Pencil size={18} /></IconButton>
            <IconButton label="Delete playlist" onClick={morePlaylist}><Trash2 size={18} /></IconButton>
          </>
        )}
        {c.kind === 'history' && c.tracks.length > 0 && (
          <button type="button" className="link-btn collection__clear" onClick={() => openSheet('confirm', { title: 'Clear listening history?', body: 'This also resets play counts used for recommendations. Liked songs and playlists are kept.', confirmLabel: 'Clear history', onConfirm: () => { library.clearHistory(); toast('History cleared'); } })}>Clear history</button>
        )}
      </div>

      {c.status === 'loading' && <SkeletonRows count={8} />}
      {c.status === 'error' && <EmptyState title="This station didn’t load">{c.error}</EmptyState>}

      {!c.tracks.length && !c.status && (
        c.kind === 'liked'
          ? <EmptyState title="No liked songs yet">Tap the heart on any song to keep it here. Likes also shape your recommendations.</EmptyState>
          : c.kind === 'history'
            ? <EmptyState title="Nothing played yet">Songs you play show up here.</EmptyState>
            : <EmptyState title="This playlist is empty" action={<button type="button" className="btn btn--primary" onClick={() => navigate('search')}><Search size={17} />Find songs</button>}>Use “Add to playlist” from any song’s menu.</EmptyState>
      )}

      <div className="tracks">
        {c.tracks.map((t, i) => (
          <TrackRow
            key={`${t.id}-${i}`}
            track={t}
            list={c.tracks}
            source={source}
            showReason={c.kind === 'mix'}
            menu={c.kind === 'playlist' ? { playlistId: c.playlist.id, index: i, total: c.tracks.length } : c.kind === 'mix' ? { recommended: true } : {}}
          />
        ))}
      </div>
    </div>
  );
}

