/**
 * Extracts a vivid, readable accent colour from album art.
 * Used to tint the whole interface with the playing song's "aura".
 */
const memo = new Map();
const FALLBACK = { accent: '#d9a6ff', deep: '#3a2350', rgb: [217, 166, 255] };

function toHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h;
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  return [h * 60, s, l];
}

const hsl = (h, s, l) => `hsl(${Math.round(h)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%)`;

export function extractAura(src) {
  if (!src) return Promise.resolve(FALLBACK);
  if (memo.has(src)) return memo.get(src);
  const p = new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.decoding = 'async';
    const timer = setTimeout(() => resolve(FALLBACK), 4000);
    img.onload = () => {
      clearTimeout(timer);
      try {
        const size = 24;
        const canvas = document.createElement('canvas');
        canvas.width = size; canvas.height = size;
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(img, 0, 0, size, size);
        const { data } = ctx.getImageData(0, 0, size, size);
        // Hue histogram weighted by saturation, so the most characterful colour wins.
        const buckets = new Array(24).fill(null).map(() => ({ w: 0, r: 0, g: 0, b: 0 }));
        for (let i = 0; i < data.length; i += 4) {
          const [h, s, l] = toHsl(data[i], data[i + 1], data[i + 2]);
          if (l < 0.08 || l > 0.94) continue;
          const w = s * s + 0.02;
          const bk = buckets[Math.floor(h / 15) % 24];
          bk.w += w; bk.r += data[i] * w; bk.g += data[i + 1] * w; bk.b += data[i + 2] * w;
        }
        const top = buckets.reduce((a, b) => (b.w > a.w ? b : a));
        if (top.w < 0.5) return resolve({ ...FALLBACK, accent: 'hsl(270 30% 80%)', deep: 'hsl(270 18% 22%)' });
        const r = top.r / top.w, g = top.g / top.w, b = top.b / top.w;
        const [h, s] = toHsl(r, g, b);
        const sat = Math.min(0.85, Math.max(0.35, s));
        resolve({ accent: hsl(h, sat, 0.74), deep: hsl(h, Math.min(0.6, sat), 0.2), rgb: [r, g, b].map(Math.round) });
      } catch {
        resolve(FALLBACK);
      }
    };
    img.onerror = () => { clearTimeout(timer); resolve(FALLBACK); };
    img.src = src;
  });
  memo.set(src, p);
  return p;
}

export const DEFAULT_AURA = FALLBACK;
