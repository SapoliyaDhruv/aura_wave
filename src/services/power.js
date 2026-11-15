/**
 * Knows whether the app is actually in front of the listener.
 *
 * Inside the Android app the WebView is forced to report itself as "visible" (so
 * YouTube keeps playing with the screen off), which means document.visibilityState
 * can't be trusted there. Capacitor's appStateChange comes from the native activity
 * lifecycle and is reliable, so we use that, and the Page Visibility API on the web.
 *
 * While in the background the app stops drawing (see `html.is-background` in app.css),
 * skips progress-bar updates and slows its timers. Audio is unaffected.
 */
import { Capacitor } from '@capacitor/core';
import { App as CapApp } from '@capacitor/app';

let background = false;
const listeners = new Set();

function set(next) {
  if (next === background) return;
  background = next;
  document.documentElement.classList.toggle('is-background', background);
  listeners.forEach((fn) => { try { fn(background); } catch { /* ignore */ } });
}

export function isBackground() {
  return background;
}

export function onPowerChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

if (typeof window !== 'undefined') {
  if (Capacitor.isNativePlatform()) {
    CapApp.addListener('appStateChange', ({ isActive }) => set(!isActive)).catch(() => {});
  } else {
    document.addEventListener('visibilitychange', () => set(document.visibilityState === 'hidden'));
  }
}
