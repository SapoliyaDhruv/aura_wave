import { ArrowUp, ArrowDown, X } from 'lucide-react';
import { usePlayer } from '../state/player.jsx';
import { Artwork, IconButton, Equalizer } from './ui.jsx';

function QueueItem({ track, i, first, last }) {
  const { jumpTo, removeFromQueue, moveInQueue } = usePlayer();
  return (
    <li className="qitem">
      <button type="button" className="qitem__main" onClick={() => jumpTo(i)} aria-label={`Play ${track.title} now`}>
        <Artwork src={track.coverUrl} title={track.title} px={120} />
        <span className="qitem__text">
          <span className="qitem__title">{track.title}</span>
          <span className="qitem__meta">{track.reason || track.artist}</span>
        </span>
      </button>
      <div className="qitem__tools">
        <IconButton label={`Move ${track.title} up`} size="sm" disabled={first} onClick={() => moveInQueue(i, i - 1)}><ArrowUp size={15} /></IconButton>
        <IconButton label={`Move ${track.title} down`} size="sm" disabled={last} onClick={() => moveInQueue(i, i + 1)}><ArrowDown size={15} /></IconButton>
        <IconButton label={`Remove ${track.title} from queue`} size="sm" onClick={() => removeFromQueue(i)}><X size={15} /></IconButton>
      </div>
    </li>
  );
}

export default function QueuePanel() {
  const { queue, index, current, isPlaying, source, autoplay, setAutoplay, clearUpcoming } = usePlayer();
  const upcoming = queue.map((t, i) => ({ t, i })).slice(index + 1);
  const mine = upcoming.filter(({ t }) => !t.auto);
  const auto = upcoming.filter(({ t }) => t.auto);

  return (
    <div className="queue">
      {current && (
        <section className="queue__section">
          <h3 className="queue__label">Now playing</h3>
          <div className="qitem qitem--current">
            <div className="qitem__main">
              <Artwork src={current.coverUrl} title={current.title} px={120} />
              <span className="qitem__text">
                <span className="qitem__title">{current.title}</span>
                <span className="qitem__meta">{current.artist}</span>
              </span>
              <Equalizer paused={!isPlaying} />
            </div>
          </div>
        </section>
      )}

      <section className="queue__section">
        <div className="queue__head">
          <h3 className="queue__label">{source ? `Next from ${source}` : 'Next up'}</h3>
          {mine.length > 0 && <button type="button" className="link-btn" onClick={clearUpcoming}>Clear</button>}
        </div>
        {mine.length ? (
          <ol className="queue__list">
            {mine.map(({ t, i }, k) => <QueueItem key={`${t.id}-${i}`} track={t} i={i} first={k === 0 && i === index + 1} last={i === queue.length - 1} />)}
          </ol>
        ) : (
          <p className="queue__empty">Nothing queued. Use “Play next” or “Add to queue” on any song.</p>
        )}
      </section>

      <section className="queue__section">
        <div className="queue__head">
          <div>
            <h3 className="queue__label">Autoplay</h3>
            <p className="queue__hint">Similar songs keep playing when your queue ends</p>
          </div>
          <label className="switch">
            <input type="checkbox" checked={autoplay} onChange={(e) => setAutoplay(e.target.checked)} />
            <span className="switch__track" aria-hidden="true" />
            <span className="sr-only">Autoplay similar songs</span>
          </label>
        </div>
        {autoplay && auto.length > 0 && (
          <ol className="queue__list">
            {auto.map(({ t, i }) => <QueueItem key={`${t.id}-${i}`} track={t} i={i} first={i === index + 1} last={i === queue.length - 1} />)}
          </ol>
        )}
      </section>
    </div>
  );
}
