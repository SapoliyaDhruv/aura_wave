import React, { useState, useEffect, useRef } from 'react';
import { Lock, Unlock, Moon, Play, Pause, SkipBack, SkipForward, Sparkles, Smartphone } from 'lucide-react';
import { audioEngine } from '../services/audioEngine';

export default function PocketLockOverlay({
  isOpen,
  onClose,
  track,
  isPlaying,
  currentTime,
  duration,
  onTogglePlay,
  onPrev,
  onNext
}) {
  const [showHint, setShowHint] = useState(false);
  const [clickCount, setClickCount] = useState(0);
  const [clockTime, setClockTime] = useState('');
  const [batteryLevel, setBatteryLevel] = useState(null);
  const tapTimeoutRef = useRef(null);

  // Update clock time every second
  useEffect(() => {
    const updateTime = () => {
      const now = new Date();
      setClockTime(
        now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      );
    };
    updateTime();
    const interval = setInterval(updateTime, 1000);
    return () => clearInterval(interval);
  }, []);

  // Battery status if available
  useEffect(() => {
    if (typeof navigator !== 'undefined' && navigator.getBattery) {
      navigator.getBattery().then(battery => {
        setBatteryLevel(Math.round(battery.level * 100));
        battery.addEventListener('levelchange', () => {
          setBatteryLevel(Math.round(battery.level * 100));
        });
      }).catch(() => {});
    }
  }, []);

  // Request fullscreen and ensure WakeLock stays active while Pocket Mode is engaged
  useEffect(() => {
    if (isOpen) {
      // 1. Maintain wake lock so the mobile display stays awake with pixels black
      if (audioEngine._requestWakeLock) {
        audioEngine._requestWakeLock();
      }

      // 2. Try entering immersive fullscreen to hide mobile browser chrome
      try {
        if (document.documentElement && document.documentElement.requestFullscreen) {
          document.documentElement.requestFullscreen().catch(() => {});
        }
      } catch (_) {}
    } else {
      // Exit fullscreen when unlocking
      try {
        if (document.fullscreenElement && document.exitFullscreen) {
          document.exitFullscreen().catch(() => {});
        }
      } catch (_) {}
    }
  }, [isOpen]);

  if (!isOpen) return null;

  // Double-tap or double-click to wake gesture
  const handleSurfaceTap = (e) => {
    e.stopPropagation();
    setClickCount(prev => prev + 1);
    setShowHint(true);

    if (tapTimeoutRef.current) {
      clearTimeout(tapTimeoutRef.current);
    }

    if (clickCount >= 1) {
      // Double tap detected! Unlock smoothly
      handleUnlock();
      return;
    }

    tapTimeoutRef.current = setTimeout(() => {
      setClickCount(0);
      setShowHint(false);
    }, 700);
  };

  const handleUnlock = () => {
    if (tapTimeoutRef.current) clearTimeout(tapTimeoutRef.current);
    setClickCount(0);
    setShowHint(false);
    onClose();
  };

  const formatSecs = (sec) => {
    if (isNaN(sec) || sec < 0) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  const progressPercent = duration > 0 ? (currentTime / duration) * 100 : 0;

  return (
    <div
      className="pocket-lock-container"
      id="pocket-lock-container"
      onClick={handleSurfaceTap}
      aria-label="Pocket Mode Active. Double tap anywhere to wake."
    >
      {/* Top Ambient Status Bar */}
      <div className="pocket-top-bar">
        <div className="pocket-status-pill">
          <Moon size={13} className="pocket-moon-icon" />
          <span>OLED POCKET GUARD</span>
          <span className="pocket-pulse-dot" />
        </div>
        <div className="pocket-clock">
          <span>{clockTime}</span>
          {batteryLevel !== null && (
            <span className="pocket-battery">{batteryLevel}%</span>
          )}
        </div>
      </div>

      {/* Center Track Ambient Visual */}
      <div className="pocket-center-content">
        <div className="pocket-glow-ring">
          <div className="pocket-lock-badge">
            <Lock size={28} />
          </div>
          {isPlaying && (
            <div className="pocket-audio-ripples">
              <span className="ripple r1" />
              <span className="ripple r2" />
            </div>
          )}
        </div>

        <div className="pocket-track-details">
          <h2 className="pocket-title">{track?.title || 'AuraWave Streaming'}</h2>
          <p className="pocket-artist">{track?.artist || 'Unknown Artist'}</p>
        </div>

        {/* Ambient progress line */}
        <div className="pocket-progress-wrapper">
          <div className="pocket-progress-bar" style={{ width: `${progressPercent}%` }} />
        </div>
        <div className="pocket-time-row">
          <span>{formatSecs(currentTime)}</span>
          <span>{formatSecs(duration)}</span>
        </div>

        {/* Minimalist touch-safe player controls */}
        <div className="pocket-quick-controls" onClick={e => e.stopPropagation()}>
          <button
            className="pocket-ctrl-btn"
            onClick={onPrev}
            title="Previous track"
            aria-label="Previous track"
          >
            <SkipBack size={18} />
          </button>

          <button
            className="pocket-main-play-btn"
            onClick={onTogglePlay}
            title={isPlaying ? 'Pause' : 'Play'}
            aria-label={isPlaying ? 'Pause' : 'Play'}
          >
            {isPlaying ? <Pause size={20} /> : <Play size={20} fill="currentColor" />}
          </button>

          <button
            className="pocket-ctrl-btn"
            onClick={onNext}
            title="Next track"
            aria-label="Next track"
          >
            <SkipForward size={18} />
          </button>
        </div>
      </div>

      {/* Bottom Wake Guidance & Instant Unlock Button */}
      <div className="pocket-bottom-bar" onClick={e => e.stopPropagation()}>
        <div className={`pocket-wake-hint ${showHint ? 'hint-visible' : ''}`}>
          <Smartphone size={16} className="hint-phone-icon" />
          <span>Double-tap anywhere to wake phone</span>
        </div>

        <button
          className="pocket-unlock-button"
          id="pocket-unlock-btn"
          onClick={handleUnlock}
          aria-label="Unlock Phone Screen"
        >
          <Unlock size={16} />
          <span>TAP TO UNLOCK</span>
        </button>
      </div>
    </div>
  );
}
