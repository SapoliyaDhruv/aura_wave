import { createContext, useCallback, useContext, useMemo, useState } from 'react';

/** Overlay state: the full-screen player and the single active sheet. */
const UIContext = createContext(null);

export function UIProvider({ children }) {
  const [sheet, setSheet] = useState(null); // { type, props }
  const [nowPlaying, setNowPlaying] = useState(null); // null | 'player' | 'lyrics' | 'queue'
  const openSheet = useCallback((type, props = {}) => setSheet({ type, props, id: Date.now() }), []);
  const closeSheet = useCallback(() => setSheet(null), []);
  const value = useMemo(() => ({ sheet, openSheet, closeSheet, nowPlaying, setNowPlaying }), [sheet, openSheet, closeSheet, nowPlaying]);
  return <UIContext.Provider value={value}>{children}</UIContext.Provider>;
}

export const useUI = () => useContext(UIContext);
