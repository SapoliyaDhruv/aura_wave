import { Home, Search, Library, Heart, Plus, Settings } from 'lucide-react';
import { useRouter } from '../state/router.jsx';
import { useLibrary } from '../state/library.js';
import { useUI } from '../state/ui.jsx';
import { Mosaic } from './ui.jsx';

const TABS = [
  { id: 'home', label: 'Home', icon: Home },
  { id: 'search', label: 'Search', icon: Search },
  { id: 'library', label: 'Library', icon: Library },
];

export function Brand() {
  return (
    <div className="brand" aria-label="AuraWave">
      <svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true">
        <circle cx="16" cy="16" r="14" fill="none" stroke="var(--aura)" strokeWidth="2" opacity="0.35" />
        <path d="M5 17c3 0 3-6 6-6s3 10 6 10 3-12 6-12 3 8 4 8" fill="none" stroke="var(--aura)" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <span className="brand__name">AuraWave</span>
    </div>
  );
}

export function NavRail() {
  const { tab, route, navigate } = useRouter();
  const { playlists, liked } = useLibrary();
  const { openSheet } = useUI();
  return (
    <nav className="rail" aria-label="Main">
      <Brand />
      <ul className="rail__tabs">
        {TABS.map(({ id, label, icon: Icon }) => (
          <li key={id}>
            <button type="button" className={`rail__tab ${tab === id ? 'is-active' : ''}`} aria-current={tab === id ? 'page' : undefined} onClick={() => navigate(id)}>
              <Icon size={21} aria-hidden="true" />{label}
            </button>
          </li>
        ))}
      </ul>
      <div className="rail__lib">
        <div className="rail__lib-head">
          <span className="rail__lib-title">Playlists</span>
          <button type="button" className="icon-btn icon-btn--sm" onClick={() => openSheet('newPlaylist')} aria-label="New playlist" title="New playlist"><Plus size={18} /></button>
        </div>
        <ul className="rail__list">
          <li>
            <button type="button" className={`rail__item ${route.name === 'liked' ? 'is-active' : ''}`} onClick={() => navigate('liked')}>
              <span className="rail__liked-art"><Heart size={15} fill="currentColor" /></span>
              <span className="rail__item-text"><span className="rail__item-name">Liked songs</span><span className="rail__item-meta">{liked.length} songs</span></span>
            </button>
          </li>
          {playlists.map((p) => (
            <li key={p.id}>
              <button type="button" className={`rail__item ${route.name === 'playlist' && route.params?.id === p.id ? 'is-active' : ''}`} onClick={() => navigate('playlist', { id: p.id })}>
                <Mosaic tracks={p.tracks} title={p.name} className="rail__item-art" px={96} />
                <span className="rail__item-text"><span className="rail__item-name">{p.name}</span><span className="rail__item-meta">{p.tracks.length} songs</span></span>
              </button>
            </li>
          ))}
        </ul>
      </div>
      <button type="button" className={`rail__tab rail__settings ${route.name === 'settings' ? 'is-active' : ''}`} onClick={() => navigate('settings')}>
        <Settings size={19} aria-hidden="true" />Settings
      </button>
    </nav>
  );
}

export function TabBar() {
  const { tab, navigate } = useRouter();
  return (
    <nav className="tabbar" aria-label="Main">
      {TABS.map(({ id, label, icon: Icon }) => (
        <button key={id} type="button" className={`tabbar__tab ${tab === id ? 'is-active' : ''}`} aria-current={tab === id ? 'page' : undefined} onClick={() => navigate(id)}>
          <Icon size={23} strokeWidth={tab === id ? 2.4 : 1.8} aria-hidden="true" />
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
}
