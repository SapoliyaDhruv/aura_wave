import React, { useEffect, useRef } from 'react';
import { audioEngine } from '../services/audioEngine';

export default function YouTubeBridge() {
  const containerRef = useRef(null);

  useEffect(() => {
    let playerInstance = null;
    let pollInterval = null;

    const applyIframePermissions = () => {
      try {
        const iframe = document.querySelector('#yt-embedded-player iframe') || document.querySelector('iframe#yt-embedded-player');
        if (iframe) {
          iframe.setAttribute('allow', 'autoplay *; encrypted-media *; picture-in-picture *; fullscreen *');
          iframe.setAttribute('playsinline', '1');
        }
      } catch (_) {}
    };

    const createPlayer = () => {
      if (playerInstance) return;
      const el = document.getElementById('yt-embedded-player');
      if (!el) return;

      if (window.YT && window.YT.Player) {
        try {
          const originUrl = typeof window !== 'undefined' && window.location ? window.location.origin : 'https://localhost';

          playerInstance = new window.YT.Player('yt-embedded-player', {
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
              origin: originUrl
            },
            events: {
              onReady: (event) => {
                console.log('✅ YouTube Player is READY and connected to AudioEngine');
                applyIframePermissions();
                audioEngine.setYouTubePlayer(event.target);
              },
              onStateChange: (event) => {
                applyIframePermissions();
                audioEngine.handleYouTubeStateChange(event.data);
              },
              onError: (event) => {
                console.warn('⚠️ YouTube playback error event:', event.data);
                audioEngine.handleYouTubeError(event.data);
              }
            }
          });

          // Post-creation permission enhancement
          setTimeout(applyIframePermissions, 100);
          setTimeout(applyIframePermissions, 500);
        } catch (err) {
          console.warn('Error creating YT.Player:', err);
        }
      }
    };

    // Ensure YouTube Iframe API script is present
    if (typeof window !== 'undefined' && !window.YT && !document.getElementById('yt-iframe-script')) {
      const tag = document.createElement('script');
      tag.id = 'yt-iframe-script';
      tag.src = 'https://www.youtube.com/iframe_api';
      const firstScriptTag = document.getElementsByTagName('script')[0];
      if (firstScriptTag && firstScriptTag.parentNode) {
        firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
      } else {
        document.head.appendChild(tag);
      }
    }

    if (window.YT && window.YT.Player) {
      createPlayer();
    } else {
      const prevCallback = window.onYouTubeIframeAPIReady;
      window.onYouTubeIframeAPIReady = () => {
        if (typeof prevCallback === 'function') prevCallback();
        createPlayer();
      };

      // Poll every 200ms to catch ready state
      pollInterval = setInterval(() => {
        if (window.YT && window.YT.Player) {
          clearInterval(pollInterval);
          pollInterval = null;
          createPlayer();
        }
      }, 200);
    }

    return () => {
      if (pollInterval) clearInterval(pollInterval);
    };
  }, []);

  return (
    <div
      style={{
        position: 'fixed',
        bottom: '0',
        right: '0',
        width: '2px',
        height: '2px',
        pointerEvents: 'none',
        zIndex: 0,
        opacity: 1,
        overflow: 'hidden',
        transform: 'translateZ(0)',
        willChange: 'transform'
      }}
      aria-hidden="true"
    >
      <div id="yt-embedded-player" ref={containerRef} />
    </div>
  );
}
