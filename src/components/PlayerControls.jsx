import { useState } from 'react';
import { Pause, Play, SkipBack, SkipForward, Shuffle, Repeat, Repeat1, Loader2 } from 'lucide-react';
import { usePlayer, usePlaybackTime } from '../state/player.jsx';
import { formatTime } from '../lib/format.js';
import { IconButton } from './ui.jsx';

export function ProgressBar({ showTimes = true, className = '' }) {
  const { seek, current } = usePlayer();
  const { time, duration } = usePlaybackTime();
  const [drag, setDrag] = useState(null);
  const value = drag ?? time;
  const max = Math.max(1, duration);
  const pct = Math.min(100, (value / max) * 100);
  return (
    <div className={`progress ${className}`}>
      {showTimes && <span className="progress__time">{formatTime(value)}</span>}
      <input
        type="range"
        className="progress__range"
        min={0}
        max={max}
        step={1}
        value={Math.min(value, max)}
        disabled={!current}
        style={{ '--pct': `${pct}%` }}
        aria-label="Seek"
        aria-valuetext={`${formatTime(value)} of ${formatTime(duration)}`}
        onChange={(e) => setDrag(Number(e.target.value))}
        onPointerUp={() => { if (drag != null) { seek(drag); setDrag(null); } }}
        onKeyUp={() => { if (drag != null) { seek(drag); setDrag(null); } }}
        onBlur={() => { if (drag != null) { seek(drag); setDrag(null); } }}
      />
      {showTimes && <span className="progress__time progress__time--end">{formatTime(duration)}</span>}
    </div>
  );
}

export function PlayButton({ size = 'lg' }) {
  const { isPlaying, isBuffering, togglePlay, current } = usePlayer();
  const iconSize = size === 'xl' ? 30 : size === 'lg' ? 22 : 18;
  return (
    <button type="button" className={`play-btn play-btn--${size}`} onClick={togglePlay} disabled={!current} aria-label={isPlaying ? 'Pause' : 'Play'} title={isPlaying ? 'Pause (Space)' : 'Play (Space)'}>
      {isBuffering && isPlaying ? <Loader2 size={iconSize} className="spin" /> : isPlaying ? <Pause size={iconSize} fill="currentColor" /> : <Play size={iconSize} fill="currentColor" className="play-btn__play" />}
    </button>
  );
}

export function TransportControls({ size = 'lg' }) {
  const { next, prev, shuffle, repeat, toggleShuffle, cycleRepeat, current } = usePlayer();
  const s = size === 'xl' ? 26 : 20;
  return (
    <div className={`transport transport--${size}`}>
      <IconButton label={shuffle ? 'Turn shuffle off' : 'Turn shuffle on'} active={shuffle} onClick={toggleShuffle} className="transport__mode">
        <Shuffle size={s - 4} />
      </IconButton>
      <IconButton label="Previous" onClick={prev} disabled={!current} size={size === 'xl' ? 'lg' : 'md'}>
        <SkipBack size={s} fill="currentColor" />
      </IconButton>
      <PlayButton size={size} />
      <IconButton label="Next" onClick={() => next({ manual: true })} disabled={!current} size={size === 'xl' ? 'lg' : 'md'}>
        <SkipForward size={s} fill="currentColor" />
      </IconButton>
      <IconButton label={repeat === 'off' ? 'Repeat all' : repeat === 'all' ? 'Repeat this song' : 'Turn repeat off'} active={repeat !== 'off'} onClick={cycleRepeat} className="transport__mode">
        {repeat === 'one' ? <Repeat1 size={s - 4} /> : <Repeat size={s - 4} />}
      </IconButton>
    </div>
  );
}
