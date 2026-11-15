import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { audioEngine } from '../services/audioEngine.js';
import { library } from '../services/library.js';
import { autoplayAfter } from '../services/recommender.js';
import { resolveTrack, pinRecording, normalizeTrack } from '../services/catalog.js';
import { load } from '../lib/store.js';
import { leadArtist } from '../lib/format.js';
import { songKey } from '../../shared/match.js';
import { useToast } from './toast.jsx';
import { isBackground, onPowerChange } from '../services/power.js';

const PlayerContext = createContext(null);
const TimeContext = createContext({ time: 0, duration: 0 });
const SESSION_KEY = 'aw2_session';

function shuffled(list) {
  const a = [...list];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export function PlayerProvider({ children }) {
  const toast = useToast();
  const restored = useMemo(() => load(SESSION_KEY, null), []);
  const [queue, setQueue] = useState(() => restored?.queue || []);
  const [index, setIndex] = useState(() => Math.min(restored?.index || 0, Math.max(0, (restored?.queue || []).length - 1)));
  const [source, setSource] = useState(() => restored?.source || '');
  const [isPlaying, setIsPlaying] = useState(false);
  const [isBuffering, setIsBuffering] = useState(false);
  const [time, setTime] = useState(() => restored?.time || 0);
  const [duration, setDuration] = useState(0);
  const [shuffle, setShuffle] = useState(() => restored?.shuffle || false);
  const [repeat, setRepeat] = useState(() => restored?.repeat || 'off'); // off | all | one
  const [autoplay, setAutoplay] = useState(() => restored?.autoplay !== false);
  const [sleep, setSleep] = useState(null); // { endsAt } | { endOfTrack: true }

  const current = queue[index] || null;

  // Refs mirror state for engine callbacks, which outlive renders.
  const r = useRef({});
  r.current = { queue, index, current, repeat, autoplay, sleep, shuffle };
  const listen = useRef({ trackId: null, seconds: 0, last: 0 });
  const session = useRef({ played: new Set(), skips: new Map(), skippedArtists: new Set(), failures: 0, restoreAt: restored?.time || 0, originalOrder: null });
  const fetchingAuto = useRef(null); // shared in-flight autoplay request
  const queueVersion = useRef(0); // bumps whenever a new list replaces the queue
  const skipTimer = useRef(null);
  const timeRef = useRef(restored?.time || 0);

  // ----- listening outcome bookkeeping -----
  const closeListen = useCallback((manualSkip = false) => {
    const l = listen.current;
    const track = r.current.queue.find((t) => t.id === l.trackId);
    if (!track) return;
    library.recordOutcome(track, l.seconds);
    if (manualSkip && l.seconds < 30) {
      const a = leadArtist(track.artist).toLowerCase();
      const n = (session.current.skips.get(a) || 0) + 1;
      session.current.skips.set(a, n);
      if (n >= 2) session.current.skippedArtists.add(a); // skipped this artist twice: rest them this session
    }
    listen.current = { trackId: null, seconds: 0, last: 0 };
  }, []);

  const startTrack = useCallback((track) => {
    if (!track) return;
    clearTimeout(skipTimer.current);
    listen.current = { trackId: track.id, seconds: 0, last: 0 };
    session.current.restoreAt = 0;
    session.current.played.add(songKey(track));
    library.recordPlay(track);
    audioEngine.playTrack({ ...track });
  }, []);

  // ----- queue navigation -----
  const goTo = useCallback((newIndex, { manual = false } = {}) => {
    const { queue: q } = r.current;
    if (!q[newIndex]) return;
    closeListen(manual);
    setIndex(newIndex);
    setTime(0);
    startTrack(q[newIndex]);
  }, [closeListen, startTrack]);

  /** Appends similar songs to the queue. Concurrent callers share one request. */
  const extendWithAutoplay = useCallback(() => {
    if (fetchingAuto.current) return fetchingAuto.current;
    const { queue: q, current: cur } = r.current;
    if (!cur) return Promise.resolve({ more: [], start: -1 });
    const version = queueVersion.current;
    fetchingAuto.current = (async () => {
      try {
        const inQueue = new Set(q.map(songKey));
        const more = (await autoplayAfter(cur, { played: new Set([...session.current.played, ...inQueue]), skippedArtists: session.current.skippedArtists }))
          .map((t) => ({ ...t, auto: true }));
        // The listener started something else while we were fetching: drop these.
        if (!more.length || version !== queueVersion.current) return { more: [], start: -1 };
        const start = r.current.queue.length;
        const nextQueue = [...r.current.queue, ...more];
        r.current.queue = nextQueue;
        setQueue(nextQueue);
        return { more, start };
      } catch {
        return { more: [], start: -1 };
      } finally {
        fetchingAuto.current = null;
      }
    })();
    return fetchingAuto.current;
  }, []);

  const next = useCallback(async ({ manual = true } = {}) => {
    const { queue: q, index: i, repeat: rep, autoplay: auto } = r.current;
    if (i < q.length - 1) return goTo(i + 1, { manual });
    if (rep === 'all' && q.length) return goTo(0, { manual });
    if (auto) {
      const version = queueVersion.current;
      const { more, start } = await extendWithAutoplay();
      if (version !== queueVersion.current) return; // a new list started meanwhile
      if (more.length && start >= 0 && r.current.index === i) {
        closeListen(manual);
        setIndex(start);
        setTime(0);
        startTrack(r.current.queue[start]);
        return;
      }
    }
    audioEngine.pause();
    if (!manual) toast('That was the last song in your queue.');
  }, [goTo, extendWithAutoplay, closeListen, startTrack, toast]);

  const prev = useCallback(() => {
    if (audioEngine.currentTime > 4 || r.current.index === 0) {
      audioEngine.seek(0);
      return;
    }
    goTo(r.current.index - 1, { manual: true });
  }, [goTo]);

  // ----- public actions -----
  const playList = useCallback((tracks, startIndex = 0, { source: src = '' } = {}) => {
    const list = tracks.map(normalizeTrack).filter(Boolean);
    if (!list.length) return;
    closeListen(true);
    let ordered = list;
    let start = Math.max(0, Math.min(startIndex, list.length - 1));
    if (r.current.shuffle) {
      const first = list[start];
      ordered = [first, ...shuffled(list.filter((_, i) => i !== start))];
      start = 0;
      session.current.originalOrder = list;
    }
    session.current.failures = 0;
    queueVersion.current += 1;
    r.current.queue = ordered;
    setQueue(ordered);
    setIndex(start);
    setSource(src);
    setTime(0);
    startTrack(ordered[start]);
  }, [closeListen, startTrack]);

  const playTrack = useCallback((track, { source: src = '', context = null } = {}) => {
    if (context?.length) {
      const i = context.findIndex((t) => t.id === track.id);
      return playList(context, i >= 0 ? i : 0, { source: src });
    }
    playList([track], 0, { source: src });
  }, [playList]);

  const togglePlay = useCallback(() => {
    if (!r.current.current) return;
    if (audioEngine.isPlaying) {
      audioEngine.pause();
      return;
    }
    if (audioEngine.activeMode === 'idle' || !audioEngine.currentTrack || audioEngine.currentTrack.id !== r.current.current.id) {
      // Restored session or a failed track: start fresh, resuming the saved position.
      listen.current = { trackId: r.current.current.id, seconds: 0, last: 0 };
      audioEngine.playTrack({ ...r.current.current });
      return;
    }
    audioEngine.resume();
  }, []);

  const seek = useCallback((sec) => {
    audioEngine.seek(sec);
    setTime(sec);
    listen.current.last = sec;
  }, []);

  const playNext = useCallback((track) => {
    const t = normalizeTrack(track);
    if (!r.current.current) return playList([t]);
    setQueue((q) => [...q.slice(0, r.current.index + 1), t, ...q.slice(r.current.index + 1)]);
    toast(`"${t.title}" will play next`);
  }, [playList, toast]);

  const addToQueue = useCallback((track) => {
    const t = normalizeTrack(track);
    if (!r.current.current) return playList([t]);
    // Insert before autoplay suggestions so your picks come first.
    setQueue((q) => {
      const firstAuto = q.findIndex((x, i) => i > r.current.index && x.auto);
      const at = firstAuto === -1 ? q.length : firstAuto;
      return [...q.slice(0, at), t, ...q.slice(at)];
    });
    toast(`Added "${t.title}" to the queue`);
  }, [playList, toast]);

  const removeFromQueue = useCallback((i) => {
    if (i === r.current.index) return;
    setQueue((q) => q.filter((_, k) => k !== i));
    if (i < r.current.index) setIndex((x) => x - 1);
  }, []);

  const moveInQueue = useCallback((from, to) => {
    const { index: cur } = r.current;
    if (from === cur || to <= cur) return;
    setQueue((q) => {
      if (to >= q.length) return q;
      const a = [...q];
      const [m] = a.splice(from, 1);
      a.splice(to, 0, m);
      return a;
    });
  }, []);

  const jumpTo = useCallback((i) => goTo(i, { manual: true }), [goTo]);

  const clearUpcoming = useCallback(() => {
    setQueue((q) => q.slice(0, r.current.index + 1));
  }, []);

  const toggleShuffle = useCallback(() => {
    const on = !r.current.shuffle;
    setShuffle(on);
    const { queue: q, index: i } = r.current;
    if (on) {
      session.current.originalOrder = q;
      setQueue([...q.slice(0, i + 1), ...shuffled(q.slice(i + 1))]);
    } else if (session.current.originalOrder) {
      // Back to the original order, keeping removals and anything added since.
      const present = new Set(q.map((t) => t.id));
      const orig = session.current.originalOrder.filter((t) => present.has(t.id));
      const origIds = new Set(orig.map((t) => t.id));
      const restoredOrder = [...orig, ...q.filter((t) => !origIds.has(t.id))];
      const at = restoredOrder.findIndex((t) => t.id === q[i]?.id);
      if (at >= 0) { setQueue(restoredOrder); setIndex(at); }
      session.current.originalOrder = null;
    }
  }, []);

  const cycleRepeat = useCallback(() => setRepeat((m) => (m === 'off' ? 'all' : m === 'all' ? 'one' : 'off')), []);

  /** "Not the right song?": remember the chosen recording and play it now. */
  const chooseRecording = useCallback((track, chosen) => {
    pinRecording(track, chosen);
    const fixed = { ...track, youtubeId: chosen.youtubeId, coverUrl: track.coverUrl || chosen.coverUrl, duration: chosen.duration || track.duration };
    library.rememberResolution(fixed);
    setQueue((q) => q.map((t) => (t.id === track.id ? fixed : t)));
    if (r.current.current?.id === track.id) {
      listen.current = { trackId: fixed.id, seconds: 0, last: 0 };
      audioEngine.playTrack({ ...fixed });
    }
    toast('Got it. This version will play from now on.');
  }, [toast]);

  const setSleepTimer = useCallback((minutes) => {
    if (minutes === null) { setSleep(null); toast('Sleep timer off'); return; }
    if (minutes === 'end') { setSleep({ endOfTrack: true }); toast('Music will stop after this song'); return; }
    setSleep({ endsAt: Date.now() + minutes * 60e3 });
    toast(`Music will stop in ${minutes} minutes`);
  }, [toast]);

  // ----- engine wiring -----
  useEffect(() => {
    if (restored?.queue?.[restored.index]) audioEngine.setInitialTrack(restored.queue[restored.index]);
    const offs = [
      audioEngine.on('stateChange', ({ isPlaying: p, isBuffering: b }) => { setIsPlaying(!!p); setIsBuffering(!!b); if (p && !b) session.current.failures = 0; }),
      audioEngine.on('timeUpdate', ({ currentTime, duration: d }) => {
        const l = listen.current;
        const delta = currentTime - l.last;
        if (delta > 0 && delta < 2.5) l.seconds += delta;
        l.last = currentTime;
        // Resume a restored session at the saved position, once.
        if (session.current.restoreAt > 5 && currentTime < 2 && d > session.current.restoreAt + 5) {
          audioEngine.seek(session.current.restoreAt);
          session.current.restoreAt = 0;
          return;
        }
        if (currentTime > 2) session.current.restoreAt = 0;
        timeRef.current = currentTime;
        // Nobody can see the progress bar in the background: skip the re-render.
        if (isBackground()) return;
        setTime(currentTime);
        if (d && Number.isFinite(d)) setDuration(d);
      }),
      audioEngine.on('ended', () => {
        if (r.current.sleep?.endOfTrack) { setSleep(null); audioEngine.pause(); return; }
        if (r.current.repeat === 'one') { listen.current.last = 0; audioEngine.repeatTrack(); return; }
        next({ manual: false });
      }),
      // Back in front: show the real position immediately.
      onPowerChange((bg) => { if (!bg) { setTime(audioEngine.currentTime || timeRef.current); if (audioEngine.duration) setDuration(audioEngine.duration); } }),
      audioEngine.on('mediaNext', () => next({ manual: true })),
      audioEngine.on('mediaPrev', () => prev()),
      audioEngine.on('resolved', ({ track }) => {
        library.rememberResolution(track);
        setQueue((q) => q.map((t) => (t.id === track.id ? { ...t, youtubeId: track.youtubeId, coverUrl: t.coverUrl || track.coverUrl } : t)));
      }),
      audioEngine.on('unplayable', ({ track, reason }) => {
        session.current.failures += 1;
        // Not the listener's choice: don't count it as a skip in recommendations.
        if (listen.current.trackId === track?.id) listen.current = { trackId: null, seconds: 0, last: 0 };
        const { queue: q, index: i } = r.current;
        if (session.current.failures >= 3 || q.length <= 1 || (i >= q.length - 1 && !r.current.autoplay)) {
          toast(reason, { tone: 'error' });
          return;
        }
        toast(`Skipped "${track?.title}". ${reason}`, { tone: 'error' });
        clearTimeout(skipTimer.current);
        skipTimer.current = setTimeout(() => {
          if (r.current.current?.id === track?.id) next({ manual: false });
        }, 900);
      }),
    ];
    return () => offs.forEach((off) => off());
  }, [next, prev, restored, toast]);

  // Resolve the upcoming song ahead of time so the transition is instant.
  useEffect(() => {
    const upcoming = queue[index + 1];
    if (!upcoming || upcoming.youtubeId || !isPlaying) return;
    const timer = setTimeout(() => {
      resolveTrack(upcoming).then((res) => {
        if (res?.youtubeId) setQueue((q) => q.map((t) => (t.id === upcoming.id ? { ...t, youtubeId: res.youtubeId, coverUrl: t.coverUrl || res.coverUrl } : t)));
      }).catch(() => {});
    }, 4000);
    return () => clearTimeout(timer);
  }, [queue, index, isPlaying]);

  // Top up autoplay before the queue runs dry (works with the screen off, too).
  useEffect(() => {
    if (autoplay && current && repeat === 'off' && index >= queue.length - 2 && isPlaying) extendWithAutoplay();
  }, [autoplay, current, repeat, index, queue.length, isPlaying, extendWithAutoplay]);

  // Sleep timer countdown
  useEffect(() => {
    if (!sleep?.endsAt) return;
    const timer = setTimeout(() => { audioEngine.pause(); setSleep(null); toast('Sleep timer ended. Good night.'); }, Math.max(0, sleep.endsAt - Date.now()));
    return () => clearTimeout(timer);
  }, [sleep, toast]);

  // Persist the session so the app reopens where you left off: on every queue change,
  // every 10s while playing, and when the app is backgrounded (Android may kill it).
  useEffect(() => {
    let last = '';
    const persist = () => {
      const json = JSON.stringify({ queue: queue.slice(Math.max(0, index - 20), index + 80), index: Math.min(index, 20), source, time: Math.floor(timeRef.current / 5) * 5, shuffle, repeat, autoplay });
      if (json === last) return; // nothing changed (e.g. paused): skip the storage write
      last = json;
      try { localStorage.setItem(SESSION_KEY, json); } catch { /* storage full or blocked */ }
    };
    const timer = setTimeout(persist, 800);
    const interval = setInterval(persist, 15000);
    const onHide = () => { if (document.visibilityState === 'hidden') persist(); };
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('pagehide', persist);
    return () => { clearTimeout(timer); clearInterval(interval); document.removeEventListener('visibilitychange', onHide); window.removeEventListener('pagehide', persist); };
  }, [queue, index, source, shuffle, repeat, autoplay]);

  const value = useMemo(() => ({
    queue, index, current, source, isPlaying, isBuffering, shuffle, repeat, autoplay, sleep,
    playList, playTrack, togglePlay, next, prev, seek, playNext, addToQueue, removeFromQueue, moveInQueue, clearUpcoming, jumpTo,
    toggleShuffle, cycleRepeat, setAutoplay, chooseRecording, setSleepTimer,
  }), [queue, index, current, source, isPlaying, isBuffering, shuffle, repeat, autoplay, sleep, playList, playTrack, togglePlay, next, prev, seek, playNext, addToQueue, removeFromQueue, moveInQueue, clearUpcoming, jumpTo, toggleShuffle, cycleRepeat, chooseRecording, setSleepTimer]);

  const timeValue = useMemo(() => ({ time, duration: duration || current?.duration || 0 }), [time, duration, current]);

  return (
    <PlayerContext.Provider value={value}>
      <TimeContext.Provider value={timeValue}>{children}</TimeContext.Provider>
    </PlayerContext.Provider>
  );
}

export const usePlayer = () => useContext(PlayerContext);
export const usePlaybackTime = () => useContext(TimeContext);
