import React from 'react';
import { Moon, X, Check, Timer, BellOff } from 'lucide-react';

export default function SleepTimerModal({
  isOpen,
  onClose,
  activeTimerMinutes,
  remainingSeconds,
  onSetTimer,
  onCancelTimer
}) {
  if (!isOpen) return null;

  const timerOptions = [
    { label: '5 minutes', minutes: 5 },
    { label: '15 minutes', minutes: 15 },
    { label: '30 minutes', minutes: 30 },
    { label: '45 minutes', minutes: 45 },
    { label: '1 hour', minutes: 60 },
    { label: 'End of Track', minutes: 'endOfTrack' }
  ];

  const formatRemaining = (secs) => {
    if (!secs || secs <= 0) return '0:00';
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  return (
    <div className="modal-backdrop-blur" onClick={onClose} id="sleep-timer-modal-overlay">
      <div
        className="sleep-timer-modal-content"
        onClick={e => e.stopPropagation()}
        id="sleep-timer-modal"
      >
        <div className="sqm-header">
          <div className="sqm-title-group">
            <div className="sqm-icon-wrap" style={{ background: 'rgba(139, 92, 246, 0.15)' }}>
              <Moon size={20} color="#a78bfa" />
            </div>
            <div>
              <h2 className="sqm-title">Sleep Timer</h2>
              <p className="sqm-sub">
                {activeTimerMinutes
                  ? `Active • Audio stops in ${formatRemaining(remainingSeconds)}`
                  : 'Automatically pause music when you fall asleep'}
              </p>
            </div>
          </div>
          <button className="icon-btn sqm-close-btn" onClick={onClose} aria-label="Close modal">
            <X size={20} />
          </button>
        </div>

        {/* Active Timer Countdown Banner */}
        {activeTimerMinutes && (
          <div className="sleep-timer-active-banner">
            <div className="sleep-timer-countdown-wrap">
              <Timer size={18} color="#a78bfa" className="spin-slow" />
              <span className="sleep-timer-countdown-text">
                {activeTimerMinutes === 'endOfTrack'
                  ? 'Pausing at end of current track'
                  : `Music stops in ${formatRemaining(remainingSeconds)}`}
              </span>
            </div>
            <button
              type="button"
              className="sleep-timer-cancel-btn"
              onClick={() => {
                onCancelTimer();
                onClose();
              }}
            >
              <BellOff size={14} />
              <span>Turn Off Timer</span>
            </button>
          </div>
        )}

        {/* Timer Duration Options */}
        <div className="sleep-timer-options-list">
          {timerOptions.map(opt => {
            const isSelected = activeTimerMinutes === opt.minutes;
            return (
              <button
                key={String(opt.minutes)}
                type="button"
                className={`sleep-timer-opt-btn ${isSelected ? 'active' : ''}`}
                onClick={() => {
                  onSetTimer(opt.minutes);
                  onClose();
                }}
              >
                <div className="sleep-timer-opt-left">
                  <Moon size={16} color={isSelected ? '#1DB954' : '#a1a1aa'} />
                  <span className="sleep-timer-opt-label">{opt.label}</span>
                </div>
                {isSelected && <Check size={18} color="#1DB954" />}
              </button>
            );
          })}
        </div>

        {activeTimerMinutes && (
          <div className="sleep-timer-footer-actions">
            <button
              type="button"
              className="sleep-timer-add-time-btn"
              onClick={() => onSetTimer(typeof activeTimerMinutes === 'number' ? activeTimerMinutes + 5 : 5)}
            >
              + 5 Minutes
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
