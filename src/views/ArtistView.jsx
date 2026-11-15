import { useEffect, useState } from 'react';
import { Play, Radio } from 'lucide-react';
import * as catalog from '../services/catalog.js';
import { useRouter } from '../state/router.jsx';
import { usePlayer } from '../state/player.jsx';
import { useToast } from '../state/toast.jsx';
import { SkeletonRows, EmptyState, IconButton } from '../components/ui.jsx';
import TrackRow from '../components/TrackRow.jsx';

export default function ArtistView() {
  const { route } = useRouter();
  const player = usePlayer();
  const toast = useToast();
  const { id, name } = route.params;
  const [state, setState] = useState({ status: 'loading', data: null, error: '' });

  useEffect(() => {
    let alive = true;
    setState({ status: 'loading', data: null, error: '' });
    catalog.artist({ id, name })
      .then((data) => alive && setState({ status: 'ready', data, error: '' }))
      .catch((err) => alive && setState({ status: 'error', data: null, error: err.message }));
    return () => { alive = false; };
  }, [id, name]);

  const a = state.data;
  const songs = a?.songs || [];
  const startRadio = async () => {
    if (!songs[0]) return;
    try {
      const more = await catalog.radio(songs[0]);
      player.playList([songs[0], ...more], 0, { source: `${a.name} radio` });
    } catch (err) {
      toast(err.message, { tone: 'error' });
    }
  };

  return (
    <div className="view artist">
      <header className="artist__head" style={a?.imageUrl ? { '--artist-img': `url("${a.imageUrl}")` } : undefined}>
        <div className="artist__shade" />
        <div className="artist__info">
          <h1 className="artist__name">{a?.name || name || 'Artist'}</h1>
          {a?.audience && <p className="artist__meta">{a.audience}</p>}
        </div>
      </header>
      <div className="collection__actions">
        <button type="button" className="btn btn--primary btn--lg" disabled={!songs.length} onClick={() => player.playList(songs, 0, { source: a.name })}><Play size={18} fill="currentColor" />Play</button>
        <IconButton label={`Start ${a?.name || 'artist'} radio`} disabled={!songs.length} onClick={startRadio}><Radio size={20} /></IconButton>
      </div>
      {state.status === 'loading' && <SkeletonRows count={8} />}
      {state.status === 'error' && <EmptyState title="This artist didn’t load">{state.error}</EmptyState>}
      {state.status === 'ready' && !songs.length && <EmptyState title="No songs found for this artist" />}
      <div className="tracks">
        {songs.map((t) => <TrackRow key={t.id} track={t} list={songs} source={a.name} />)}
      </div>
    </div>
  );
}
