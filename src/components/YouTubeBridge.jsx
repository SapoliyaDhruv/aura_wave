import { useEffect } from 'react';
import { audioEngine } from '../services/audioEngine';

/**
 * Creates the hidden YouTube IFrame player exactly once for the app's lifetime.
 *
 * The player lives in a container attached directly to <body>, outside React,
 * so remounts (StrictMode, onboarding -> main app, layout changes) can never
 * create a second player or leave the audio engine holding a dead one.
 */
let started = false;

function ensurePlayer() {
  if (started || typeof window === 'undefined') return;
  started = true;

  const host = document.createElement('div');
  host.setAttribute('aria-hidden', 'true');
  // Full opacity (1), normal viewport dimensions (240x240), pointer-events: none so clicks pass through.
  // Overlaid with a solid background cover (#0e0b14) so the video is visually hidden from the user,
  // while Chromium and YouTube's player engine treat the iframe as an active, fully visible surface.
  host.style.cssText = 'position:fixed;bottom:0;right:0;width:240px;height:240px;opacity:1;pointer-events:none;z-index:0;overflow:hidden;transform:translateZ(0);';

  const target = document.createElement('div');
  target.id = 'yt-embedded-player';
  target.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;z-index:1;';
  host.appendChild(target);

  const cover = document.createElement('div');
  cover.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;background:#0e0b14;z-index:2;pointer-events:none;';
  host.appendChild(cover);

  document.body.appendChild(host);

  const permit = () => {
    try {
      const iframe = document.querySelector('#yt-embedded-player iframe') || document.querySelector('iframe#yt-embedded-player') || host.querySelector('iframe');
      if (iframe) {
        iframe.setAttribute('allow', 'autoplay *; encrypted-media *; picture-in-picture *; fullscreen *');
        iframe.setAttribute('playsinline', '1');
        iframe.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
      }
    } catch (_) {}
  };

  const origin = (typeof window !== 'undefined' && window.location?.origin && !window.location.origin.includes('localhost') && !window.location.origin.startsWith('capacitor:'))
    ? window.location.origin
    : 'https://aurawave-music.vercel.app';

  let created = false;
  const create = () => {
    if (created || !window.YT || !window.YT.Player) return;
    created = true;
    try {
      new window.YT.Player('yt-embedded-player', {
        height: '240',
        width: '240',
        host: 'https://www.youtube.com',
        playerVars: {
          autoplay: 1,
          controls: 0,
          disablekb: 1,
          enablejsapi: 1,
          fs: 0,
          modestbranding: 1,
          playsinline: 1,
          rel: 0,
          origin,
          widget_referrer: origin,
        },
        events: {
          onReady: (e) => {
            permit();
            audioEngine.setYouTubePlayer(e.target);
          },
          onStateChange: (e) => {
            permit();
            audioEngine.handleYouTubeStateChange(e.data);
          },
          onError: (e) => {
            console.warn('YouTube playback error event code:', e.data);
            audioEngine.handleYouTubeError(e.data);
          },
        },
      });
      setTimeout(permit, 150);
      setTimeout(permit, 600);
    } catch (err) {
      console.warn('Error creating YT.Player:', err);
      created = false;
    }
  };

  if (window.YT?.Player) {
    create();
    return;
  }

  if (window.YT?.ready) {
    window.YT.ready(create);
  }

  const previous = window.onYouTubeIframeAPIReady;
  window.onYouTubeIframeAPIReady = () => {
    if (typeof previous === 'function') previous();
    create();
  };

  // Continual poll for up to 10 seconds in case script already ran before callback registered
  let attempts = 0;
  const poll = setInterval(() => {
    attempts++;
    if (window.YT?.Player) {
      clearInterval(poll);
      create();
    } else if (attempts > 100) {
      clearInterval(poll);
    }
  }, 100);

  if (!document.querySelector('script[src*="youtube.com/iframe_api"]')) {
    const tag = document.createElement('script');
    tag.src = 'https://www.youtube.com/iframe_api';
    tag.async = true;
    document.head.appendChild(tag);
  }
}

export default function YouTubeBridge() {
  useEffect(() => { ensurePlayer(); }, []);
  return null;
}
