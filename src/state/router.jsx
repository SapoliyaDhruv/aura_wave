import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

/**
 * Tiny in-memory router with a history stack. Hash-free on purpose: the Android
 * build serves from capacitor://localhost where deep links aren't needed, and
 * the back button walks this stack (see App.jsx).
 */
const RouterContext = createContext(null);
const TABS = ['home', 'search', 'library'];

export function RouterProvider({ children }) {
  const [stack, setStack] = useState([{ name: 'home' }]);
  const stackRef = useRef(stack);
  stackRef.current = stack;
  const scrollMemory = useRef(new Map());
  const route = stack[stack.length - 1];

  const navigate = useCallback((name, params = {}) => {
    setStack((s) => {
      const top = s[s.length - 1];
      if (top.name === name && JSON.stringify(top.params || {}) === JSON.stringify(params)) return s;
      if (TABS.includes(name)) return [{ name, params }];
      return [...s, { name, params }];
    });
  }, []);

  /** Returns false when there is nowhere left to go (caller may then minimise the app). */
  const back = useCallback(() => {
    const s = stackRef.current;
    if (s.length > 1) { setStack(s.slice(0, -1)); return true; }
    if (s[0].name !== 'home') { setStack([{ name: 'home' }]); return true; }
    return false;
  }, []);

  const canGoBack = stack.length > 1 || route.name !== 'home';
  const tab = TABS.includes(stack[0].name) ? stack[0].name : 'home';

  useEffect(() => {
    const main = document.getElementById('main-scroll');
    if (!main) return;
    const key = JSON.stringify(route);
    main.scrollTo({ top: scrollMemory.current.get(key) || 0 });
    const onScroll = () => scrollMemory.current.set(key, main.scrollTop);
    main.addEventListener('scroll', onScroll, { passive: true });
    return () => main.removeEventListener('scroll', onScroll);
  }, [route]);

  const value = useMemo(() => ({ route, tab, navigate, back, canGoBack, depth: stack.length }), [route, tab, navigate, back, canGoBack, stack.length]);
  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>;
}

export const useRouter = () => useContext(RouterContext);
