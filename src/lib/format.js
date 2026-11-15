export function formatTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) return '0:00';
  const s = Math.floor(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${r}` : `${m}:${r}`;
}

export function formatTotal(sec) {
  const m = Math.round(sec / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} hr ${m % 60} min`;
}

export function greeting(date = new Date()) {
  const h = date.getHours();
  if (h < 5) return 'Still up';
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  if (h < 22) return 'Good evening';
  return 'Late night';
}

/** First artist of a credit line ("Pritam, Arijit Singh" -> "Pritam"). */
export function leadArtist(credit = '') {
  return String(credit).split(/\s*(?:,|&|\bfeat\.?|\bft\.?|\bx\b)\s*/i)[0] || credit;
}

/** Removes "(From "Film")" style credits for compact display. */
export function shortTitle(title = '') {
  return String(title).replace(/\s*\((from|feat\.?|ft\.?)[^)]*\)/gi, '').trim() || title;
}

export function plural(n, word) {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}
