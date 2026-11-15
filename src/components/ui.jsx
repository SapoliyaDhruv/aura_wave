import { useState } from 'react';

function hueFrom(text = '') {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) % 360;
  return h;
}

/** Requests a cover at the size it is shown (small thumbs in lists, large art in the player). */
export function sized(url, px = 240) {
  if (!url) return url;
  if (/googleusercontent\.com|ggpht\.com/.test(url)) return url.replace(/=(w\d+-h\d+|s\d+)[^/]*$/, `=w${px}-h${px}-l90-rj`);
  return url;
}

/**
 * Cover image with a generated placeholder when art is missing.
 * If the requested size fails (e.g. the image CDN rate-limits a burst), it retries
 * with the small thumbnail, which is usually cached already, before giving up.
 */
export function Artwork({ src: rawSrc, title = '', size, className = '', round = false, eager = false, px = 240 }) {
  const primary = sized(rawSrc, px);
  const fallback = px > 120 ? sized(rawSrc, 120) : null;
  const [failures, setFailures] = useState({ for: null, count: 0 });
  const count = failures.for === rawSrc ? failures.count : 0;
  const src = count === 0 ? primary : count === 1 && fallback && fallback !== primary ? fallback : null;
  const style = size ? { width: size, height: size } : undefined;
  const cls = `art ${round ? 'art--round' : ''} ${className}`;
  if (!src) {
    const h = hueFrom(title);
    return (
      <div className={`${cls} art--placeholder`} style={{ ...style, '--ph': `hsl(${h} 45% 38%)`, '--ph2': `hsl(${(h + 50) % 360} 50% 22%)` }} aria-hidden="true">
        <span>{title.trim().charAt(0).toUpperCase() || '♪'}</span>
      </div>
    );
  }
  return <img key={src} className={cls} style={style} src={src} alt="" loading={eager ? 'eager' : 'lazy'} decoding="async" onError={() => setFailures({ for: rawSrc, count: count + 1 })} draggable="false" />;
}

/** 2x2 cover grid for playlists, falling back to a single cover. */
export function Mosaic({ tracks = [], title = '', className = '', px = 240 }) {
  const covers = [...new Set(tracks.map((t) => t.coverUrl).filter(Boolean))].slice(0, 4);
  if (covers.length < 4) return <Artwork src={covers[0]} title={title} className={className} px={px} />;
  return (
    <div className={`mosaic ${className}`} aria-hidden="true">
      {covers.map((c) => <img key={c} src={sized(c, Math.round(px / 2))} alt="" loading="lazy" draggable="false" />)}
    </div>
  );
}

/** Fanned record sleeves: the signature cover treatment for mixes. */
export function Sleeves({ tracks = [], variant = 'fan', className = '', px = 360 }) {
  const covers = [...new Set(tracks.map((t) => t.coverUrl).filter(Boolean))].slice(0, 3);
  while (covers.length < 3) covers.push(null);
  return (
    <div className={`sleeves sleeves--${variant} ${className}`} aria-hidden="true">
      {covers.map((c, i) => (
        <div key={i} className={`sleeves__item sleeves__item--${i}`}>
          <Artwork src={c} title={tracks[i]?.title || ''} px={px} />
        </div>
      ))}
    </div>
  );
}

export function Equalizer({ paused = false }) {
  return (
    <span className={`eq ${paused ? 'eq--paused' : ''}`} aria-hidden="true">
      <i /><i /><i />
    </span>
  );
}

export function IconButton({ label, onClick, children, className = '', active = false, disabled = false, size = 'md', ...rest }) {
  return (
    <button
      type="button"
      className={`icon-btn icon-btn--${size} ${active ? 'is-active' : ''} ${className}`}
      onClick={onClick}
      aria-label={label}
      title={label}
      disabled={disabled}
      {...rest}
    >
      {children}
    </button>
  );
}

export function SkeletonRows({ count = 4 }) {
  return (
    <div className="skeleton-rows" aria-hidden="true">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="skeleton-row">
          <div className="skeleton skeleton--art" />
          <div className="skeleton-row__text">
            <div className="skeleton skeleton--line" style={{ width: `${55 + ((i * 17) % 30)}%` }} />
            <div className="skeleton skeleton--line skeleton--short" />
          </div>
        </div>
      ))}
    </div>
  );
}

export function EmptyState({ title, children, action }) {
  return (
    <div className="empty">
      <h3 className="empty__title">{title}</h3>
      {children && <p className="empty__body">{children}</p>}
      {action}
    </div>
  );
}

export function SectionHeader({ title, subtitle, action, lead }) {
  return (
    <div className="section-head">
      {lead}
      <div className="section-head__text">
        <h2 className="section-head__title">{title}</h2>
        {subtitle && <p className="section-head__sub">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}
