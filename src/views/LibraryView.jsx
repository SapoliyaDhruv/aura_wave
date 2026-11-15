import { Heart, History, Plus, Download, ChevronRight } from 'lucide-react';
import { useLibrary } from '../state/library.js';
import { useRouter } from '../state/router.jsx';
import { useUI } from '../state/ui.jsx';
import { Mosaic, EmptyState } from '../components/ui.jsx';
import { plural } from '../lib/format.js';

function Row({ art, name, meta, onClick }) {
  return (
    <button type="button" className="lib-row" onClick={onClick}>
      <span className="lib-row__art">{art}</span>
      <span className="lib-row__text">
        <span className="lib-row__name">{name}</span>
        <span className="lib-row__meta">{meta}</span>
      </span>
      <ChevronRight size={18} className="lib-row__chev" aria-hidden="true" />
    </button>
  );
}

export default function LibraryView() {
  const { liked, playlists, history } = useLibrary();
  const { navigate } = useRouter();
  const { openSheet } = useUI();
  return (
    <div className="view library">
      <header className="page-head">
        <h1 className="page-title">Your library</h1>
        <div className="page-head__actions">
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => openSheet('importSpotify')}><Download size={16} />Import</button>
          <button type="button" className="btn btn--soft btn--sm" onClick={() => openSheet('newPlaylist')}><Plus size={16} />New playlist</button>
        </div>
      </header>

      <div className="lib-list">
        <Row art={<span className="cover-liked cover-liked--sm"><Heart size={22} fill="currentColor" /></span>} name="Liked songs" meta={plural(liked.length, 'song')} onClick={() => navigate('liked')} />
        <Row art={<span className="cover-liked cover-liked--sm cover-liked--history"><History size={22} /></span>} name="Recently played" meta={history.length ? 'Your listening history' : 'Nothing yet'} onClick={() => navigate('history')} />
        {playlists.map((p) => (
          <Row key={p.id} art={<Mosaic tracks={p.tracks} title={p.name} px={120} />} name={p.name} meta={`${p.source === 'spotify' ? 'From Spotify, ' : ''}${plural(p.tracks.length, 'song')}`} onClick={() => navigate('playlist', { id: p.id })} />
        ))}
      </div>

      {playlists.length === 0 && (
        <EmptyState title="Make your first playlist" action={<button type="button" className="btn btn--primary" onClick={() => openSheet('newPlaylist')}>Create playlist</button>}>
          Collect songs for a mood or a moment, or import one from Spotify.
        </EmptyState>
      )}
    </div>
  );
}
