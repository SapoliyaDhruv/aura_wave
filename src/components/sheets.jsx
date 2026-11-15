import { useEffect, useState } from 'react';
import {
  ListPlus, ListEnd, ListMusic, Heart, User, Radio, SearchCheck, EyeOff, UserX, Trash2, ArrowUp, ArrowDown, Plus, Check, Moon,
} from 'lucide-react';
import Sheet from './Sheet.jsx';
import { Artwork, SkeletonRows } from './ui.jsx';
import { usePlayer } from '../state/player.jsx';
import { useUI } from '../state/ui.jsx';
import { useRouter } from '../state/router.jsx';
import { useLibrary } from '../state/library.js';
import { useToast } from '../state/toast.jsx';
import { library } from '../services/library.js';
import * as catalog from '../services/catalog.js';
import { invalidateFeed } from '../services/recommender.js';
import { formatTime, leadArtist, shortTitle } from '../lib/format.js';

function MenuItem({ icon: Icon, children, onClick, tone }) {
  return (
    <button type="button" className={`menu-item ${tone ? `menu-item--${tone}` : ''}`} onClick={onClick}>
      <Icon size={19} aria-hidden="true" />
      <span>{children}</span>
    </button>
  );
}

function TrackMenu({ track, playlistId, index, total, recommended }) {
  const player = usePlayer();
  const { openSheet, closeSheet, setNowPlaying } = useUI();
  const { navigate } = useRouter();
  const toast = useToast();
  useLibrary();
  const liked = library.isLiked(track);
  const act = (fn) => () => { closeSheet(); fn(); };
  const firstArtist = track.artists?.[0];

  return (
    <Sheet title={track.title} subtitle={track.artist} onClose={closeSheet}>
      <div className="menu">
        <MenuItem icon={ListPlus} onClick={act(() => player.playNext(track))}>Play next</MenuItem>
        <MenuItem icon={ListEnd} onClick={act(() => player.addToQueue(track))}>Add to queue</MenuItem>
        <MenuItem icon={ListMusic} onClick={() => openSheet('addToPlaylist', { track })}>Add to playlist</MenuItem>
        <MenuItem icon={Heart} onClick={act(() => { const on = library.toggleLike(track); toast(on ? 'Added to Liked songs' : 'Removed from Liked songs'); })}>
          {liked ? 'Remove from Liked songs' : 'Add to Liked songs'}
        </MenuItem>
        <MenuItem icon={Radio} onClick={act(async () => {
          toast(`Starting radio from "${shortTitle(track.title)}"`);
          try {
            const tracks = await catalog.radio(track);
            if (!tracks.length) throw new Error();
            player.playList([track, ...tracks], 0, { source: `${shortTitle(track.title)} radio` });
          } catch { toast('Could not start radio for this song. Try again in a moment.', { tone: 'error' }); }
        })}>Start radio</MenuItem>
        <MenuItem icon={User} onClick={act(() => { setNowPlaying(null); navigate('artist', firstArtist?.id ? { id: firstArtist.id, name: firstArtist.name } : { name: leadArtist(track.artist) }); })}>
          Go to artist
        </MenuItem>
        <MenuItem icon={SearchCheck} onClick={() => openSheet('recording', { track })}>Not the right song?</MenuItem>
        {playlistId && total > 1 && index > 0 && <MenuItem icon={ArrowUp} onClick={act(() => library.moveInPlaylist(playlistId, index, index - 1))}>Move up</MenuItem>}
        {playlistId && total > 1 && index < total - 1 && <MenuItem icon={ArrowDown} onClick={act(() => library.moveInPlaylist(playlistId, index, index + 1))}>Move down</MenuItem>}
        {playlistId && <MenuItem icon={Trash2} tone="danger" onClick={act(() => { library.removeFromPlaylist(playlistId, index); toast('Removed from playlist'); })}>Remove from this playlist</MenuItem>}
        {recommended && (
          <>
            <MenuItem icon={EyeOff} onClick={act(() => { library.hide(track); invalidateFeed(); toast('You won’t see this song in recommendations'); })}>Don’t recommend this song</MenuItem>
            <MenuItem icon={UserX} onClick={act(() => { library.hide(track, { artist: true }); invalidateFeed(); toast(`You won’t see ${leadArtist(track.artist)} in recommendations`); })}>Don’t recommend {leadArtist(track.artist)}</MenuItem>
          </>
        )}
      </div>
    </Sheet>
  );
}

function AddToPlaylist({ track }) {
  const { playlists } = useLibrary();
  const { closeSheet, openSheet } = useUI();
  const toast = useToast();
  return (
    <Sheet title="Add to playlist" subtitle={track.title} onClose={closeSheet}>
      <div className="menu">
        <MenuItem icon={Plus} onClick={() => openSheet('newPlaylist', { track })}>New playlist</MenuItem>
        {playlists.map((p) => {
          const has = p.tracks.some((t) => t.id === track.id || (t.youtubeId && t.youtubeId === track.youtubeId));
          return (
            <button key={p.id} type="button" className="menu-item" disabled={has} onClick={() => { library.addToPlaylist(p.id, track); closeSheet(); toast(`Added to ${p.name}`); }}>
              {has ? <Check size={19} aria-hidden="true" /> : <ListMusic size={19} aria-hidden="true" />}
              <span>{p.name}</span>
              <span className="menu-item__hint">{has ? 'Already added' : `${p.tracks.length} songs`}</span>
            </button>
          );
        })}
      </div>
    </Sheet>
  );
}

function NewPlaylist({ track, tracks }) {
  const { closeSheet } = useUI();
  const { navigate } = useRouter();
  const toast = useToast();
  const [name, setName] = useState('');
  const submit = (e) => {
    e.preventDefault();
    const list = tracks || (track ? [track] : []);
    const pl = library.createPlaylist(name || 'New playlist', list);
    closeSheet();
    toast(`Created ${pl.name}`);
    if (!track) navigate('playlist', { id: pl.id });
  };
  return (
    <Sheet title="New playlist" onClose={closeSheet}>
      <form className="form" onSubmit={submit}>
        <label className="field">
          <span className="field__label">Name</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Late drives" maxLength={60} data-autofocus />
        </label>
        <button type="submit" className="btn btn--primary">Create playlist</button>
      </form>
    </Sheet>
  );
}

/** Lets the listener pick the exact recording; the choice is remembered for good. */
function RecordingPicker({ track }) {
  const player = usePlayer();
  const { closeSheet } = useUI();
  const [state, setState] = useState({ loading: true, items: [], error: '' });
  useEffect(() => {
    let alive = true;
    catalog.alternatives(track)
      .then((items) => alive && setState({ loading: false, items, error: '' }))
      .catch((err) => alive && setState({ loading: false, items: [], error: err.message }));
    return () => { alive = false; };
  }, [track]);

  return (
    <Sheet title="Pick the right version" subtitle={`For "${track.title}" by ${track.artist}. Your choice is remembered.`} onClose={closeSheet} wide>
      {state.loading && <SkeletonRows count={5} />}
      {state.error && <p className="sheet__note">{state.error}</p>}
      {!state.loading && !state.error && !state.items.length && <p className="sheet__note">No other versions were found. Try searching for the song instead.</p>}
      <div className="picker">
        {state.items.map((c) => (
          <button key={c.youtubeId} type="button" className="picker__item" onClick={() => { closeSheet(); player.chooseRecording(track, c); }}>
            <Artwork src={c.coverUrl} title={c.title} px={120} />
            <span className="picker__text">
              <span className="picker__title">{c.title}</span>
              <span className="picker__meta">{c.artist}{c.album ? `, ${c.album}` : ''}</span>
            </span>
            <span className="picker__side">
              <span className={`tag ${c.kind === 'song' ? 'tag--good' : ''}`}>{c.kind === 'song' ? 'Official audio' : c.kind === 'video' ? 'Music video' : 'Upload'}</span>
              {c.duration > 0 && <span className="picker__time">{formatTime(c.duration)}</span>}
            </span>
          </button>
        ))}
      </div>
    </Sheet>
  );
}

function ImportSpotify() {
  const { closeSheet } = useUI();
  const { navigate } = useRouter();
  const toast = useToast();
  const [link, setLink] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      const res = await catalog.importSpotifyPlaylist(link);
      const pl = library.createPlaylist(res.name, res.tracks, 'spotify');
      closeSheet();
      toast(`Imported ${res.tracks.length} songs into ${res.name}`);
      navigate('playlist', { id: pl.id });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet title="Import a Spotify playlist" subtitle="Public playlists only. Each song is matched to its exact recording when you play it." onClose={closeSheet}>
      <form className="form" onSubmit={submit}>
        <label className="field">
          <span className="field__label">Playlist link</span>
          <input className="input" value={link} onChange={(e) => { setLink(e.target.value); setError(''); }} placeholder="open.spotify.com/playlist/…" inputMode="url" data-autofocus aria-invalid={!!error} aria-describedby={error ? 'import-error' : undefined} />
        </label>
        {error && <p id="import-error" className="field__error">{error}</p>}
        <button type="submit" className="btn btn--primary" disabled={busy || !link.trim()}>{busy ? 'Importing…' : 'Import playlist'}</button>
      </form>
    </Sheet>
  );
}

function SleepTimer() {
  const player = usePlayer();
  const { closeSheet } = useUI();
  const pick = (v) => { player.setSleepTimer(v); closeSheet(); };
  const remaining = player.sleep?.endsAt ? Math.max(1, Math.round((player.sleep.endsAt - Date.now()) / 60e3)) : null;
  return (
    <Sheet title="Sleep timer" subtitle={remaining ? `Stopping in about ${remaining} min` : player.sleep?.endOfTrack ? 'Stopping after this song' : 'Stop the music after a while'} onClose={closeSheet}>
      <div className="menu">
        {[10, 20, 30, 45, 60, 90].map((m) => <MenuItem key={m} icon={Moon} onClick={() => pick(m)}>{m} minutes</MenuItem>)}
        <MenuItem icon={Moon} onClick={() => pick('end')}>End of this song</MenuItem>
        {player.sleep && <MenuItem icon={Trash2} tone="danger" onClick={() => pick(null)}>Turn off timer</MenuItem>}
      </div>
    </Sheet>
  );
}

function Confirm({ title, body, confirmLabel, onConfirm }) {
  const { closeSheet } = useUI();
  return (
    <Sheet title={title} onClose={closeSheet}>
      {body && <p className="sheet__note">{body}</p>}
      <div className="sheet__actions">
        <button type="button" className="btn btn--ghost" onClick={closeSheet}>Cancel</button>
        <button type="button" className="btn btn--danger" onClick={() => { closeSheet(); onConfirm(); }} data-autofocus>{confirmLabel}</button>
      </div>
    </Sheet>
  );
}

function Rename({ playlist }) {
  const { closeSheet } = useUI();
  const [name, setName] = useState(playlist.name);
  return (
    <Sheet title="Rename playlist" onClose={closeSheet}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); library.updatePlaylist(playlist.id, { name: name.trim() || playlist.name }); closeSheet(); }}>
        <label className="field">
          <span className="field__label">Name</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} data-autofocus />
        </label>
        <button type="submit" className="btn btn--primary">Save name</button>
      </form>
    </Sheet>
  );
}

const SHEETS = { track: TrackMenu, addToPlaylist: AddToPlaylist, newPlaylist: NewPlaylist, recording: RecordingPicker, importSpotify: ImportSpotify, sleep: SleepTimer, confirm: Confirm, rename: Rename };

export default function SheetHost() {
  const { sheet } = useUI();
  if (!sheet) return null;
  const Component = SHEETS[sheet.type];
  return Component ? <Component key={sheet.id} {...sheet.props} /> : null;
}
