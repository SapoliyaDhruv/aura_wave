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
  // Kept in the viewport (YouTube may throttle players it considers invisible) but 2px and inert.
  host.style.cssText = 'position:fixed;bottom:0;right:0;width:2px;height:2px;overflow:hidden;pointer-events:none;z-index:0;';
  const target = document.createElement('div');
  target.id = 'yt-embedded-player';
  host.appendChild(target);
  document.body.appendChild(host);

  const permit = () => {
    const iframe = host.querySelector('iframe');
    if (iframe) {
      iframe.setAttribute('allow', 'autoplay *; encrypted-media *; picture-in-picture *; fullscreen *');
      iframe.setAttribute('playsinline', '1');
    }
  };

  const create = () => {
    // eslint-disable-next-line no-new
    new window.YT.Player(target, {
      // YouTube's minimum player size: the smallest video stream to decode (we only need the audio).
      height: '200',
      width: '200',
      host: 'https://www.youtube.com',
      playerVars: { autoplay: 1, controls: 0, disablekb: 1, enablejsapi: 1, fs: 0, modestbranding: 1, playsinline: 1, rel: 0, origin: window.location.origin },
      events: {
        onReady: (e) => { permit(); audioEngine.setYouTubePlayer(e.target); },
        onStateChange: (e) => audioEngine.handleYouTubeStateChange(e.data),
        onError: (e) => audioEngine.handleYouTubeError(e.data),
      },
    });
    setTimeout(permit, 300);
  };

  if (window.YT?.Player) {
    create();
    return;
  }
  const previous = window.onYouTubeIframeAPIReady;
  window.onYouTubeIframeAPIReady = () => {
    if (typeof previous === 'function') previous();
    create();
  };
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
