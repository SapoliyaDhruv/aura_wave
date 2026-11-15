import { useEffect, useMemo, useRef, useState } from 'react';
import { Minus, Plus } from 'lucide-react';
import { usePlayer, usePlaybackTime } from '../state/player.jsx';
import { getLyrics, getOffset, setOffset } from '../services/lyrics.js';
import { IconButton } from './ui.jsx';

const reduceMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

export default function LyricsPanel({ track }) {
  const { seek } = usePlayer();
  const { time } = usePlaybackTime();
  const [state, setState] = useState({ status: 'loading', data: null, for: null });
  const [offset, setOffsetState] = useState(0);
  const listRef = useRef(null);
  const userScrollUntil = useRef(0);

  useEffect(() => {
    if (!track) return;
    let alive = true;
    setState({ status: 'loading', data: null, for: track.id });
    setOffsetState(getOffset(track));
    getLyrics(track)
      .then((data) => alive && setState({ status: data ? 'ready' : 'none', data, for: track.id }))
      .catch(() => alive && setState({ status: 'error', data: null, for: track.id }));
    return () => { alive = false; };
  }, [track?.id, track?.youtubeId]); // eslint-disable-line react-hooks/exhaustive-deps

  const lines = state.data?.lines;
  const t = time - offset;
  const active = useMemo(() => {
    if (!lines?.length) return -1;
    let i = -1;
    for (let k = 0; k < lines.length; k++) { if (lines[k].time <= t + 0.15) i = k; else break; }
    return i;
  }, [lines, t]);

  useEffect(() => {
    if (active < 0 || Date.now() < userScrollUntil.current) return;
    const el = listRef.current?.querySelector(`[data-line="${active}"]`);
    el?.scrollIntoView({ block: 'center', behavior: reduceMotion() ? 'auto' : 'smooth' });
  }, [active]);

  const nudge = (d) => {
    const v = Math.round((offset + d) * 10) / 10;
    setOffsetState(v);
    setOffset(track, v);
  };

  if (state.status === 'loading') {
    return <div className="lyrics lyrics--loading" aria-busy="true">{[70, 45, 80, 60, 50].map((w, i) => <div key={i} className="skeleton skeleton--lyric" style={{ width: `${w}%` }} />)}</div>;
  }
  if (state.status !== 'ready') {
    return (
      <div className="lyrics lyrics--empty">
        <p className="lyrics__none">{state.status === 'error' ? 'Lyrics could not be loaded. Check your connection.' : 'No lyrics found for this song.'}</p>
      </div>
    );
  }

  if (!lines?.length) {
    return (
      <div className="lyrics">
        <div className="lyrics__plain">{state.data.plain}</div>
        <p className="lyrics__source">Not synced to the music. Lyrics from {state.data.source}</p>
      </div>
    );
  }

  const progressFor = (i) => {
    if (i !== active) return i < active ? 1 : 0;
    const end = lines[i + 1]?.time ?? lines[i].time + 4;
    return Math.min(1, Math.max(0, (t - lines[i].time) / Math.max(0.5, end - lines[i].time)));
  };

  return (
    <div className="lyrics">
      <div
        className="lyrics__lines"
        ref={listRef}
        onWheel={() => { userScrollUntil.current = Date.now() + 4000; }}
        onTouchMove={() => { userScrollUntil.current = Date.now() + 4000; }}
      >
        <div className="lyrics__inner">
        {lines.map((line, i) => (
          <button
            key={`${line.time}-${i}`}
            type="button"
            data-line={i}
            className={`lyric ${i === active ? 'is-active' : ''} ${i < active ? 'is-past' : ''} ${!line.text ? 'lyric--gap' : ''}`}
            style={i === active ? { '--line-progress': `${progressFor(i) * 100}%` } : undefined}
            onClick={() => { userScrollUntil.current = 0; seek(Math.max(0, line.time + offset)); }}
          >
            {line.text || '♪'}
          </button>
        ))}
        </div>
      </div>
      <div className="lyrics__foot">
        <span className="lyrics__source">Lyrics from {state.data.source}</span>
        <div className="lyrics__offset" role="group" aria-label="Lyrics timing">
          <IconButton label="Show lyrics earlier" size="sm" onClick={() => nudge(-0.5)}><Minus size={15} /></IconButton>
          <span className="lyrics__offset-value">{offset === 0 ? 'In sync' : `${offset > 0 ? '+' : ''}${offset.toFixed(1)}s`}</span>
          <IconButton label="Show lyrics later" size="sm" onClick={() => nudge(0.5)}><Plus size={15} /></IconButton>
        </div>
      </div>
    </div>
  );
}
