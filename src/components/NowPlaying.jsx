import { useEffect, useRef } from 'react';
import { ChevronDown, MoreHorizontal, Heart, Moon, SearchCheck, Mic2, ListMusic, Disc3 } from 'lucide-react';
import { usePlayer } from '../state/player.jsx';
import { useUI } from '../state/ui.jsx';
import { useRouter } from '../state/router.jsx';
import { useLibrary } from '../state/library.js';
import { library } from '../services/library.js';
import { leadArtist } from '../lib/format.js';
import { Artwork, IconButton } from './ui.jsx';
import { ProgressBar, TransportControls } from './PlayerControls.jsx';
import LyricsPanel from './LyricsPanel.jsx';
import QueuePanel from './QueuePanel.jsx';

export default function NowPlaying() {
  const player = usePlayer();
  const { nowPlaying: mode, setNowPlaying, openSheet } = useUI();
  const { navigate } = useRouter();
  useLibrary();
  const touch = useRef(null);
  const closeRef = useRef(null);
  const track = player.current;

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e) => { if (e.key === 'Escape') setNowPlaying(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [setNowPlaying]);

  if (!track) return null;
  const liked = library.isLiked(track);
  const panel = mode === 'lyrics' || mode === 'queue' ? mode : null;
  const artist = track.artists?.[0];
  const goArtist = () => {
    setNowPlaying(null);
    navigate('artist', artist?.id ? { id: artist.id, name: artist.name } : { name: leadArtist(track.artist) });
  };

  return (
    <div className={`np ${panel ? `np--${panel}` : ''}`} role="dialog" aria-modal="true" aria-label="Now playing">
      <div className="np__backdrop" aria-hidden="true">
        {track.coverUrl && <img src={track.coverUrl} alt="" />}
      </div>

      <header
        className="np__top"
        onTouchStart={(e) => { touch.current = e.touches[0].clientY; }}
        onTouchEnd={(e) => { if (touch.current != null && e.changedTouches[0].clientY - touch.current > 70) setNowPlaying(null); touch.current = null; }}
      >
        <IconButton label="Close player" onClick={() => setNowPlaying(null)} ref={closeRef}><ChevronDown size={26} /></IconButton>
        <div className="np__context">
          <span className="np__context-label">{player.source ? 'Playing from' : 'Now playing'}</span>
          {player.source && <span className="np__context-name">{player.source}</span>}
        </div>
        <IconButton label="More options" onClick={() => openSheet('track', { track })}><MoreHorizontal size={22} /></IconButton>
      </header>

      <div className="np__body">
        <div className="np__stage">
          <div className="np__cover">
            <Artwork src={track.coverUrl} title={track.title} eager px={720} />
          </div>
          <div className="np__info">
            <div className="np__titles">
              <h2 className="np__title">{track.title}</h2>
              <button type="button" className="np__artist" onClick={goArtist}>{track.artist}</button>
            </div>
            <IconButton label={liked ? 'Remove from Liked songs' : 'Like'} size="lg" className={liked ? 'is-liked' : ''} onClick={() => library.toggleLike(track)}>
              <Heart size={26} fill={liked ? 'currentColor' : 'none'} />
            </IconButton>
          </div>
          <ProgressBar className="np__progress" />
          <TransportControls size="xl" />
          <div className="np__actions">
            <div className="segmented" role="tablist" aria-label="Player view">
              <button type="button" role="tab" aria-selected={!panel} className="segmented__btn np__seg-cover" onClick={() => setNowPlaying('player')}><Disc3 size={16} />Cover</button>
              <button type="button" role="tab" aria-selected={panel === 'lyrics'} className="segmented__btn" onClick={() => setNowPlaying('lyrics')}><Mic2 size={16} />Lyrics</button>
              <button type="button" role="tab" aria-selected={panel === 'queue'} className="segmented__btn" onClick={() => setNowPlaying('queue')}><ListMusic size={16} />Up next</button>
            </div>
            <div className="np__tools">
              <IconButton label={player.sleep ? 'Sleep timer is on' : 'Sleep timer'} active={!!player.sleep} onClick={() => openSheet('sleep')}><Moon size={19} /></IconButton>
              <IconButton label="Not the right song?" onClick={() => openSheet('recording', { track })}><SearchCheck size={19} /></IconButton>
            </div>
          </div>
        </div>

        <aside className="np__panel" aria-label={panel === 'queue' ? 'Up next' : 'Lyrics'}>
          {panel === 'queue' ? <QueuePanel /> : <LyricsPanel track={track} />}
        </aside>
      </div>
    </div>
  );
}
