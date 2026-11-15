import { memo } from 'react';
import { Heart, MoreHorizontal, Play } from 'lucide-react';
import { Artwork, Equalizer, IconButton } from './ui.jsx';
import { usePlayer } from '../state/player.jsx';
import { useUI } from '../state/ui.jsx';
import { useLibrary } from '../state/library.js';
import { library } from '../services/library.js';
import { formatTime } from '../lib/format.js';

/**
 * One song in a list. Tap to play (with the surrounding list as the queue),
 * heart to like, "more" for the full track menu.
 */
function TrackRow({ track, list, source = '', rank, showReason = true, menu = {}, onPlay, compact = false }) {
  const player = usePlayer();
  const { openSheet } = useUI();
  useLibrary(); // re-render on like changes
  const isCurrent = player.current?.id === track.id;
  const liked = library.isLiked(track);

  const play = () => {
    if (isCurrent) return player.togglePlay();
    if (onPlay) return onPlay();
    player.playTrack(track, { context: list, source });
  };

  return (
    <div className={`track ${isCurrent ? 'is-current' : ''} ${compact ? 'track--compact' : ''}`}>
      <button type="button" className="track__main" onClick={play} aria-label={`${isCurrent && player.isPlaying ? 'Pause' : 'Play'} ${track.title} by ${track.artist}`}>
        {rank != null && <span className="track__rank">{rank}</span>}
        <span className="track__art">
          <Artwork src={track.coverUrl} title={track.title} px={120} />
          <span className="track__art-overlay">{isCurrent ? <Equalizer paused={!player.isPlaying} /> : <Play size={16} fill="currentColor" />}</span>
        </span>
        <span className="track__text">
          <span className="track__title">{track.title}</span>
          <span className="track__meta">
            {showReason && track.reason ? <span className="track__reason">{track.reason}</span> : <span>{track.artist}</span>}
          </span>
        </span>
        {!compact && track.duration > 0 && <span className="track__time">{formatTime(track.duration)}</span>}
      </button>
      <IconButton label={liked ? `Remove ${track.title} from Liked songs` : `Like ${track.title}`} className={`track__like ${liked ? 'is-liked' : ''}`} onClick={() => library.toggleLike(track)} size="sm">
        <Heart size={17} fill={liked ? 'currentColor' : 'none'} />
      </IconButton>
      <IconButton label={`More options for ${track.title}`} onClick={() => openSheet('track', { track, ...menu })} size="sm">
        <MoreHorizontal size={18} />
      </IconButton>
    </div>
  );
}

export default memo(TrackRow);
