import { Heart, Mic2, ListMusic, Volume2, VolumeX, Maximize2 } from 'lucide-react';
import { useState } from 'react';
import { usePlayer, usePlaybackTime } from '../state/player.jsx';
import { useUI } from '../state/ui.jsx';
import { useLibrary } from '../state/library.js';
import { library } from '../services/library.js';
import { audioEngine } from '../services/audioEngine.js';
import { Artwork, IconButton } from './ui.jsx';
import { PlayButton, ProgressBar, TransportControls } from './PlayerControls.jsx';

function LineProgress() {
  const { time, duration } = usePlaybackTime();
  return <div className="mini__line" style={{ '--pct': `${duration ? Math.min(100, (time / duration) * 100) : 0}%` }} aria-hidden="true" />;
}

/** Phone: compact player above the tab bar. */
export function MiniPlayer() {
  const { current } = usePlayer();
  const { setNowPlaying } = useUI();
  useLibrary();
  if (!current) return null;
  const liked = library.isLiked(current);
  return (
    <div className="mini">
      <button type="button" className="mini__open" onClick={() => setNowPlaying('player')} aria-label={`Open player: ${current.title} by ${current.artist}`}>
        <Artwork src={current.coverUrl} title={current.title} className="mini__art" px={120} />
        <span className="mini__text">
          <span className="mini__title">{current.title}</span>
          <span className="mini__artist">{current.artist}</span>
        </span>
      </button>
      <IconButton label={liked ? 'Remove from Liked songs' : 'Like'} className={liked ? 'is-liked' : ''} onClick={() => library.toggleLike(current)}>
        <Heart size={20} fill={liked ? 'currentColor' : 'none'} />
      </IconButton>
      <PlayButton size="md" />
      <LineProgress />
    </div>
  );
}

function Volume() {
  const [vol, setVol] = useState(audioEngine.volume ?? 0.85);
  const [muted, setMuted] = useState(false);
  const apply = (v) => { setVol(v); setMuted(v === 0); audioEngine.setVolume(v); };
  return (
    <div className="volume">
      <IconButton label={muted ? 'Unmute' : 'Mute'} size="sm" onClick={() => (muted ? apply(vol || 0.6) : (setMuted(true), audioEngine.setVolume(0)))}>
        {muted || vol === 0 ? <VolumeX size={18} /> : <Volume2 size={18} />}
      </IconButton>
      <input type="range" className="progress__range volume__range" min={0} max={1} step={0.02} value={muted ? 0 : vol} style={{ '--pct': `${(muted ? 0 : vol) * 100}%` }} onChange={(e) => apply(Number(e.target.value))} aria-label="Volume" />
    </div>
  );
}

/** Desktop: full-width player bar. */
export function PlayerBar() {
  const { current, source } = usePlayer();
  const { setNowPlaying, nowPlaying } = useUI();
  useLibrary();
  const liked = current ? library.isLiked(current) : false;
  return (
    <div className="bar">
      <div className="bar__now">
        {current ? (
          <>
            <button type="button" className="bar__art-btn" onClick={() => setNowPlaying('player')} aria-label="Open full-screen player">
              <Artwork src={current.coverUrl} title={current.title} className="bar__art" px={120} />
            </button>
            <div className="bar__text">
              <span className="bar__title">{current.title}</span>
              <span className="bar__artist">{current.artist}</span>
              {source && <span className="bar__source">From {source}</span>}
            </div>
            <IconButton label={liked ? 'Remove from Liked songs' : 'Like'} size="sm" className={liked ? 'is-liked' : ''} onClick={() => library.toggleLike(current)}>
              <Heart size={18} fill={liked ? 'currentColor' : 'none'} />
            </IconButton>
          </>
        ) : (
          <span className="bar__idle">Pick a song to start listening</span>
        )}
      </div>
      <div className="bar__center">
        <TransportControls size="md" />
        <ProgressBar />
      </div>
      <div className="bar__tools">
        <IconButton label="Lyrics" size="sm" active={nowPlaying === 'lyrics'} disabled={!current} onClick={() => setNowPlaying(nowPlaying === 'lyrics' ? null : 'lyrics')}><Mic2 size={18} /></IconButton>
        <IconButton label="Queue" size="sm" active={nowPlaying === 'queue'} disabled={!current} onClick={() => setNowPlaying(nowPlaying === 'queue' ? null : 'queue')}><ListMusic size={18} /></IconButton>
        <Volume />
        <IconButton label="Full-screen player" size="sm" disabled={!current} onClick={() => setNowPlaying('player')}><Maximize2 size={17} /></IconButton>
      </div>
    </div>
  );
}
