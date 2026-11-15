import { useEffect, useState } from 'react';
import { App as CapApp } from '@capacitor/app';
import { ChevronLeft } from 'lucide-react';
import { ToastProvider } from './state/toast.jsx';
import { PlayerProvider, usePlayer } from './state/player.jsx';
import { RouterProvider, useRouter } from './state/router.jsx';
import { UIProvider, useUI } from './state/ui.jsx';
import { useLibrary } from './state/library.js';
import { library } from './services/library.js';
import { audioEngine } from './services/audioEngine.js';
import { extractAura, DEFAULT_AURA } from './lib/color.js';
import YouTubeBridge from './components/YouTubeBridge.jsx';
import { NavRail, TabBar } from './components/Navigation.jsx';
import { MiniPlayer, PlayerBar } from './components/PlayerDock.jsx';
import NowPlaying from './components/NowPlaying.jsx';
import SheetHost from './components/sheets.jsx';
import { IconButton, sized } from './components/ui.jsx';
import HomeView from './views/HomeView.jsx';
import SearchView from './views/SearchView.jsx';
import LibraryView from './views/LibraryView.jsx';
import CollectionView from './views/CollectionView.jsx';
import ArtistView from './views/ArtistView.jsx';
import SettingsView from './views/SettingsView.jsx';
import OnboardingView from './views/OnboardingView.jsx';

/** Tints the whole interface with the playing song's colours. */
function useAura(coverUrl) {
  useEffect(() => {
    let alive = true;
    extractAura(sized(coverUrl, 120)).then((a) => {
      if (!alive) return;
      const root = document.documentElement.style;
      root.setProperty('--aura', a.accent || DEFAULT_AURA.accent);
      root.setProperty('--aura-deep', a.deep || DEFAULT_AURA.deep);
      document.querySelector('meta[name="theme-color"]')?.setAttribute('content', '#14101b');
    });
    return () => { alive = false; };
  }, [coverUrl]);
}

function useMediaQuery(query) {
  const [match, setMatch] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setMatch(mq.matches);
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, [query]);
  return match;
}

function useShortcuts() {
  const player = usePlayer();
  const { navigate } = useRouter();
  const { setNowPlaying, nowPlaying, sheet } = useUI();
  useEffect(() => {
    const onKey = (e) => {
      const tag = e.target.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || e.target.isContentEditable || sheet) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.code === 'Space' && tag !== 'BUTTON') { e.preventDefault(); player.togglePlay(); }
      else if (e.key === 'ArrowRight' && e.shiftKey) player.next({ manual: true });
      else if (e.key === 'ArrowLeft' && e.shiftKey) player.prev();
      else if (e.key === 'ArrowRight' && tag !== 'BUTTON') player.seek(Math.min(audioEngine.duration || 0, audioEngine.currentTime + 5));
      else if (e.key === 'ArrowLeft' && tag !== 'BUTTON') player.seek(Math.max(0, audioEngine.currentTime - 5));
      else if (e.key === '/') { e.preventDefault(); setNowPlaying(null); navigate('search'); }
      else if (e.key.toLowerCase() === 'l' && player.current) library.toggleLike(player.current);
      else if (e.key.toLowerCase() === 'y' && player.current) setNowPlaying(nowPlaying === 'lyrics' ? null : 'lyrics');
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [player, navigate, setNowPlaying, nowPlaying, sheet]);
}

/** Android back button: close overlays first, then walk back, then minimise (music keeps playing). */
function useBackButton() {
  const { back } = useRouter();
  const { sheet, closeSheet, nowPlaying, setNowPlaying } = useUI();
  useEffect(() => {
    let handle;
    CapApp.addListener('backButton', () => {
      if (sheet) return closeSheet();
      if (nowPlaying) return setNowPlaying(null);
      if (!back()) CapApp.minimizeApp().catch(() => {});
    }).then((h) => { handle = h; }).catch(() => {});
    return () => { handle?.remove(); };
  }, [sheet, closeSheet, nowPlaying, setNowPlaying, back]);
}

function CurrentView() {
  const { route } = useRouter();
  switch (route.name) {
    case 'search': return <SearchView />;
    case 'library': return <LibraryView />;
    case 'liked':
    case 'playlist':
    case 'mix':
    case 'history':
    case 'collection': return <CollectionView key={JSON.stringify(route.params || {}) + route.name} />;
    case 'artist': return <ArtistView key={route.params?.id || route.params?.name} />;
    case 'settings': return <SettingsView />;
    case 'onboarding': return <OnboardingView />;
    default: return <HomeView />;
  }
}

function Shell() {
  const player = usePlayer();
  const { route, canGoBack, back, depth } = useRouter();
  const { nowPlaying } = useUI();
  const lib = useLibrary();
  const wide = useMediaQuery('(min-width: 1024px)');
  useAura(player.current?.coverUrl);
  useShortcuts();
  useBackButton();

  const firstRun = !lib.taste.onboarded && !lib.liked.length && !lib.history.length;
  if (firstRun && route.name !== 'onboarding') {
    return <div className="app app--onboarding"><YouTubeBridge /><main id="main-scroll" className="main"><OnboardingView /></main></div>;
  }

  const showBack = canGoBack && (depth > 1 || !['home', 'search', 'library'].includes(route.name));
  return (
    <div className={`app ${player.current ? 'has-player' : ''}`}>
      <YouTubeBridge />
      <div className="aura-field" aria-hidden="true" />
      {wide && <NavRail />}
      <main id="main-scroll" className="main">
        {showBack && (
          <div className="main__top">
            <IconButton label="Back" onClick={back}><ChevronLeft size={24} /></IconButton>
          </div>
        )}
        <CurrentView />
      </main>
      {wide ? <PlayerBar /> : <><MiniPlayer /><TabBar /></>}
      {nowPlaying && <NowPlaying />}
      <SheetHost />
    </div>
  );
}

export default function App() {
  return (
    <ToastProvider>
      <RouterProvider>
        <UIProvider>
          <PlayerProvider>
            <Shell />
          </PlayerProvider>
        </UIProvider>
      </RouterProvider>
    </ToastProvider>
  );
}
