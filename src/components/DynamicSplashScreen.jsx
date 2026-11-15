import React, { useState, useEffect } from 'react';
import { Volume2, Sparkles, ChevronRight, Music } from 'lucide-react';

/**
 * DynamicSplashScreen - Animated, futuristic intro experience for AuraWave Pro
 * Features:
 * - Expanding sonic frequency ripple aura
 * - Real-time animated audio equalizer bars
 * - Dynamic stage sequencer (Lossless Engine -> Spatial Audio -> Personal Vault -> Ready)
 * - Shimmering brand typography
 * - Tap anywhere or 'Enter Now' skip capability
 * - Smooth cinematic zoom + fade-out transition
 */
export default function DynamicSplashScreen({
  onComplete,
  brandName = 'AuraWave',
  brandTag = 'PRO HI-FI',
  autoDismissTime = 2200
}) {
  const [progress, setProgress] = useState(0);
  const [statusIndex, setStatusIndex] = useState(0);
  const [isExiting, setIsExiting] = useState(false);

  const statusMessages = [
    'Tuning Lossless Audio Engine...',
    'Calibrating Dolby Atmos Spatial Sound...',
    'Syncing Offline Vault & Smart Cache...',
    'Acoustics Optimized • Ready to Play'
  ];

  useEffect(() => {
    // Stage sequencer for dynamic loading progress
    const intervalTime = autoDismissTime / 100;
    const progressTimer = setInterval(() => {
      setProgress(prev => {
        if (prev >= 100) {
          clearInterval(progressTimer);
          return 100;
        }
        const next = prev + 1;
        if (next === 30) setStatusIndex(1);
        if (next === 65) setStatusIndex(2);
        if (next === 90) setStatusIndex(3);
        return next;
      });
    }, intervalTime);

    // Auto-dismiss trigger
    const exitTimer = setTimeout(() => {
      handleFinish();
    }, autoDismissTime + 200);

    return () => {
      clearInterval(progressTimer);
      clearTimeout(exitTimer);
    };
  }, [autoDismissTime]);

  const handleFinish = () => {
    if (isExiting) return;
    setIsExiting(true);
    setTimeout(() => {
      if (onComplete) onComplete();
    }, 450); // wait for fade & scale exit animation
  };

  return (
    <div
      className={`dynamic-splash-overlay ${isExiting ? 'splash-exit' : ''}`}
      onClick={handleFinish}
      role="banner"
      aria-label="AuraWave Pro Splash Screen"
      id="aurawave-dynamic-splash"
    >
      {/* Ambient Radial Aura Lights */}
      <div className="splash-ambient-aura green-aura" />
      <div className="splash-ambient-aura cyan-aura" />
      <div className="splash-ambient-aura purple-aura" />

      {/* Floating Sonic Particles */}
      <div className="splash-particles">
        <span className="particle p1" />
        <span className="particle p2" />
        <span className="particle p3" />
        <span className="particle p4" />
        <span className="particle p5" />
      </div>

      <div className="splash-content-container">
        {/* Pulsating Sonic Resonance Ring with Center Crown/Wave */}
        <div className="splash-sonic-core">
          <div className="sonic-wave-ring ring-1" />
          <div className="sonic-wave-ring ring-2" />
          <div className="sonic-wave-ring ring-3" />

          {/* Central Holographic Emblem */}
          <div className="splash-emblem-orb">
            <img src="/logo.png" alt="AuraWave" className="splash-emblem-logo-img" />
            <div className="emblem-orbit-pulse" />
          </div>
        </div>

        {/* Shimmering Brand Typography */}
        <div className="splash-brand-block">
          <div className="splash-badge-row">
            <span className="splash-pro-pill">
              <Sparkles size={11} className="sparkle-icon" />
              <span>{brandTag}</span>
            </span>
            <span className="splash-hifi-pill">24-BIT MASTER</span>
          </div>

          <h1 className="splash-brand-title">
            <span>{brandName}</span>
          </h1>

          <p className="splash-tagline">Pure Ad-Free Spatial Music Experience</p>
        </div>

        {/* Dynamic Equalizer Frequency Wave */}
        <div className="splash-dynamic-eq">
          <span className="eq-bar bar-1" />
          <span className="eq-bar bar-2" />
          <span className="eq-bar bar-3" />
          <span className="eq-bar bar-4" />
          <span className="eq-bar bar-5" />
          <span className="eq-bar bar-6" />
          <span className="eq-bar bar-7" />
          <span className="eq-bar bar-8" />
          <span className="eq-bar bar-7" />
          <span className="eq-bar bar-6" />
          <span className="eq-bar bar-5" />
          <span className="eq-bar bar-4" />
          <span className="eq-bar bar-3" />
          <span className="eq-bar bar-2" />
          <span className="eq-bar bar-1" />
        </div>

        {/* Dynamic Progress Sequencer */}
        <div className="splash-loader-block">
          <div className="splash-status-text">
            <Volume2 size={13} className="status-audio-icon" />
            <span key={statusIndex} className="status-msg-animate">
              {statusMessages[statusIndex]}
            </span>
          </div>

          <div className="splash-progress-track">
            <div
              className="splash-progress-fill"
              style={{ width: `${progress}%` }}
            />
          </div>

          <div className="splash-footer-row">
            <span className="splash-pct">{progress}%</span>
            <button
              type="button"
              className="splash-skip-btn"
              onClick={(e) => {
                e.stopPropagation();
                handleFinish();
              }}
            >
              <span>Enter Now</span>
              <ChevronRight size={13} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
