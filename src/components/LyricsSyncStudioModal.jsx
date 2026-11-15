import React, { useState, useEffect, useRef } from 'react';
import {
  X,
  Play,
  Pause,
  RotateCcw,
  Sparkles,
  Check,
  ChevronRight,
  ChevronLeft,
  Volume2,
  Globe,
  UploadCloud,
  FileText,
  Clock,
  Layers,
  ArrowRight
} from 'lucide-react';
import { formatSecondsToLrcTag, submitLyricsToLRCLIB } from '../services/lrclibPublishService';
import { lyricsAutoSync } from '../services/lyricsAutoSync';

function formatTimestamp(sec) {
  if (typeof sec !== 'number' || isNaN(sec)) return '0:00.00';
  const mins = Math.floor(sec / 60);
  const secs = Math.floor(sec % 60);
  const ms = Math.floor((sec % 1) * 100);
  return `${mins}:${secs < 10 ? '0' : ''}${secs}.${ms < 10 ? '0' : ''}${ms}`;
}

export default function LyricsSyncStudioModal({
  isOpen,
  onClose,
  track,
  currentTime = 0,
  duration = 0,
  isPlaying = false,
  onSeek,
  onTogglePlay,
  initialPlainLines = []
}) {
  const [lines, setLines] = useState([]);
  const [currentLineIndex, setCurrentLineIndex] = useState(0);
  const [activeTab, setActiveTab] = useState('tap'); // 'tap', 'fine-tune', 'paste'
  const [rawTextInput, setRawTextInput] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [publishStatus, setPublishStatus] = useState(null); // 'saving', 'published', 'error'
  const listEndRef = useRef(null);

  // Initialize lines when modal opens or track changes
  useEffect(() => {
    if (!isOpen || !track) return;

    // Check if track already has synced lyrics or plain lyrics in autoSync
    const existingSynced = lyricsAutoSync.getEffectiveLyrics(track);
    if (Array.isArray(existingSynced) && existingSynced.length > 2 && typeof existingSynced[0]?.time === 'number') {
      setLines(existingSynced.map(l => ({
        time: l.time,
        text: l.text || '',
        synced: true
      })));
      setRawTextInput(existingSynced.map(l => l.text).join('\n'));
    } else if (Array.isArray(initialPlainLines) && initialPlainLines.length > 0) {
      setLines(initialPlainLines.map(text => ({
        time: null,
        text: String(text).trim(),
        synced: false
      })));
      setRawTextInput(initialPlainLines.join('\n'));
    } else {
      // Default sample fallback
      const defaultLines = [
        "First line of lyrics goes here",
        "Second line follows in rhythm",
        "Feel the beat and tap along"
      ];
      setLines(defaultLines.map(text => ({ time: null, text, synced: false })));
      setRawTextInput(defaultLines.join('\n'));
    }

    setCurrentLineIndex(0);
    setPublishStatus(null);
  }, [isOpen, track?.id]);

  // Keyboard shortcut: Spacebar taps the active line when in 'tap' tab
  useEffect(() => {
    if (!isOpen || activeTab !== 'tap') return;

    const handleKeyDown = (e) => {
      // Only trigger if not typing inside textarea or input
      if (['INPUT', 'TEXTAREA'].includes(e.target.tagName)) return;

      if (e.code === 'Space') {
        e.preventDefault();
        handleTapCurrentLine();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, activeTab, currentLineIndex, currentTime, lines]);

  // Handle stamping timestamp on the current line
  const handleTapCurrentLine = () => {
    if (currentLineIndex >= lines.length) return;

    const stampedTime = Math.max(0, Math.round(currentTime * 100) / 100);

    setLines(prev => {
      const next = [...prev];
      next[currentLineIndex] = {
        ...next[currentLineIndex],
        time: stampedTime,
        synced: true
      };
      return next;
    });

    if (currentLineIndex < lines.length - 1) {
      setCurrentLineIndex(prev => prev + 1);
    }
  };

  // Nudge line time by delta (+0.2s or -0.2s)
  const handleNudgeTime = (index, delta) => {
    setLines(prev => {
      const next = [...prev];
      const curTime = next[index].time != null ? next[index].time : currentTime;
      const newTime = Math.max(0, Math.round((curTime + delta) * 100) / 100);
      next[index] = {
        ...next[index],
        time: newTime,
        synced: true
      };
      return next;
    });
  };

  // Re-stamp specific line at current playback time
  const handleRestampLine = (index) => {
    const stampedTime = Math.max(0, Math.round(currentTime * 100) / 100);
    setLines(prev => {
      const next = [...prev];
      next[index] = {
        ...next[index],
        time: stampedTime,
        synced: true
      };
      return next;
    });
  };

  // Import raw text into lines
  const handleApplyRawText = () => {
    const split = rawTextInput
      .split('\n')
      .map(l => l.trim())
      .filter(l => l.length > 0 && !l.startsWith('#'));

    if (split.length === 0) return;

    setLines(split.map(text => ({
      time: null,
      text,
      synced: false
    })));
    setCurrentLineIndex(0);
    setActiveTab('tap');
  };

  // Save and submit to LRCLIB
  const handleSaveAndPublish = async () => {
    if (!track) return;

    // Check if at least some lines are synced
    const syncedLines = lines.filter(l => l.time != null && l.text);
    if (syncedLines.length < 2) {
      alert('Please tap and sync at least 2 lines before publishing!');
      return;
    }

    setIsSubmitting(true);
    setPublishStatus('saving');

    try {
      const formattedLines = syncedLines.map(l => ({
        time: l.time,
        text: l.text
      }));

      const res = await submitLyricsToLRCLIB({
        track,
        lines: formattedLines
      });

      if (res.globalPublished) {
        setPublishStatus('published');
      } else {
        setPublishStatus('saved_locally');
      }

      setTimeout(() => {
        setIsSubmitting(false);
        onClose();
      }, 1600);
    } catch (err) {
      console.error('Publish error:', err);
      setIsSubmitting(false);
      setPublishStatus('error');
    }
  };

  if (!isOpen) return null;

  const syncedCount = lines.filter(l => l.synced).length;
  const progressPercent = lines.length > 0 ? Math.round((syncedCount / lines.length) * 100) : 0;
  const activeLine = lines[currentLineIndex];
  const prevLine = currentLineIndex > 0 ? lines[currentLineIndex - 1] : null;
  const nextLine = currentLineIndex < lines.length - 1 ? lines[currentLineIndex + 1] : null;

  return (
    <div className="sync-studio-overlay" onClick={onClose}>
      <div className="sync-studio-modal" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="sync-studio-header">
          <div className="sync-studio-track-badge">
            <img
              src={track?.coverUrl || "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80"}
              alt={track?.title || "Track"}
              className="sync-studio-thumb"
            />
            <div className="sync-studio-meta">
              <div className="sync-studio-title-row">
                <h3 className="sync-studio-track-title">{track?.title || 'Unknown Title'}</h3>
                <span className="sync-studio-chip">
                  <Sparkles size={13} color="#a855f7" />
                  <span>Sync Studio</span>
                </span>
              </div>
              <p className="sync-studio-artist">{track?.artist || 'Unknown Artist'}</p>
            </div>
          </div>

          <button
            className="sync-studio-close-btn"
            onClick={onClose}
            aria-label="Close Sync Studio"
          >
            <X size={20} />
          </button>
        </div>

        {/* Tab Switcher */}
        <div className="sync-studio-tabs">
          <button
            className={`sync-tab-btn ${activeTab === 'tap' ? 'active' : ''}`}
            onClick={() => setActiveTab('tap')}
          >
            <Clock size={16} />
            <span>Tap to Sync</span>
          </button>
          <button
            className={`sync-tab-btn ${activeTab === 'fine-tune' ? 'active' : ''}`}
            onClick={() => setActiveTab('fine-tune')}
          >
            <Layers size={16} />
            <span>Review & Adjust ({syncedCount}/{lines.length})</span>
          </button>
          <button
            className={`sync-tab-btn ${activeTab === 'paste' ? 'active' : ''}`}
            onClick={() => setActiveTab('paste')}
          >
            <FileText size={16} />
            <span>Paste Text</span>
          </button>
        </div>

        {/* Content Area */}
        <div className="sync-studio-body">
          {/* TAB 1: TAP TO SYNC */}
          {activeTab === 'tap' && (
            <div className="sync-tap-view">
              {/* Playback Mini Bar */}
              <div className="sync-playback-card">
                <div className="sync-time-display">
                  <span className="sync-time-current">{formatTimestamp(currentTime)}</span>
                  <span className="sync-time-divider">/</span>
                  <span className="sync-time-total">{formatTimestamp(duration)}</span>
                </div>

                <div className="sync-player-controls">
                  <button
                    className="sync-rewind-btn"
                    onClick={() => onSeek && onSeek(Math.max(0, currentTime - 3))}
                    title="Rewind 3s"
                  >
                    -3s
                  </button>
                  <button
                    className="sync-play-btn"
                    onClick={onTogglePlay}
                    title={isPlaying ? "Pause" : "Play"}
                  >
                    {isPlaying ? <Pause size={20} /> : <Play size={20} style={{ marginLeft: '2px' }} />}
                  </button>
                  <button
                    className="sync-forward-btn"
                    onClick={() => onSeek && onSeek(Math.min(duration, currentTime + 3))}
                    title="Forward 3s"
                  >
                    +3s
                  </button>
                </div>
              </div>

              {/* Central Lyric Stage */}
              <div className="sync-stage">
                {prevLine && (
                  <div className="sync-line-card prev-card">
                    <span className="sync-line-tag">{formatTimestamp(prevLine.time)}</span>
                    <p className="sync-line-text">{prevLine.text}</p>
                  </div>
                )}

                <div className="sync-line-card active-card">
                  <div className="sync-active-indicator">
                    <span className="sync-pulse-blob" />
                    <span>Line {currentLineIndex + 1} of {lines.length}</span>
                  </div>
                  <p className="sync-active-text">
                    {activeLine ? activeLine.text : '🎉 All lines synced! You are ready to publish.'}
                  </p>
                  {activeLine && (
                    <span className="sync-cue-hint">
                      Listen to vocal & tap the button below when this line starts
                    </span>
                  )}
                </div>

                {nextLine && (
                  <div className="sync-line-card next-card">
                    <span className="sync-next-badge">Next</span>
                    <p className="sync-line-text">{nextLine.text}</p>
                  </div>
                )}
              </div>

              {/* Giant Tap Button */}
              <div className="sync-tap-action-row">
                <button
                  className="sync-giant-tap-button"
                  onClick={handleTapCurrentLine}
                  disabled={currentLineIndex >= lines.length}
                >
                  <span className="sync-tap-icon">⚡</span>
                  <span className="sync-tap-title">TAP WHEN SUNG</span>
                  <span className="sync-tap-sub">or press [Spacebar]</span>
                </button>
              </div>

              {/* Navigation stepper */}
              <div className="sync-step-nav">
                <button
                  className="sync-step-btn"
                  onClick={() => setCurrentLineIndex(prev => Math.max(0, prev - 1))}
                  disabled={currentLineIndex === 0}
                >
                  <ChevronLeft size={16} />
                  <span>Previous Line</span>
                </button>
                <span className="sync-step-status">
                  {currentLineIndex + 1} / {lines.length}
                </span>
                <button
                  className="sync-step-btn"
                  onClick={() => setCurrentLineIndex(prev => Math.min(lines.length - 1, prev + 1))}
                  disabled={currentLineIndex >= lines.length - 1}
                >
                  <span>Skip to Next</span>
                  <ChevronRight size={16} />
                </button>
              </div>
            </div>
          )}

          {/* TAB 2: REVIEW & FINE-TUNE */}
          {activeTab === 'fine-tune' && (
            <div className="sync-review-view">
              <div className="sync-review-instruction">
                <span>Fine-tune each line's exact vocal timestamp (+/- 0.2s) or test-play directly.</span>
              </div>
              <div className="sync-lines-scroll-list">
                {lines.map((line, idx) => (
                  <div
                    key={idx}
                    className={`sync-review-row ${idx === currentLineIndex ? 'active-row' : ''} ${line.synced ? 'is-synced' : 'is-pending'}`}
                  >
                    <div className="sync-review-index">#{idx + 1}</div>
                    
                    <div className="sync-review-timestamp">
                      <span className="time-val">
                        {line.time != null ? formatTimestamp(line.time) : '--:--.--'}
                      </span>
                    </div>

                    <div className="sync-review-text" title={line.text}>
                      {line.text}
                    </div>

                    <div className="sync-review-actions">
                      <button
                        className="sync-nudge-btn"
                        onClick={() => handleNudgeTime(idx, -0.2)}
                        title="Shift 0.2s earlier"
                      >
                        -0.2s
                      </button>
                      <button
                        className="sync-nudge-btn"
                        onClick={() => handleNudgeTime(idx, +0.2)}
                        title="Shift 0.2s later"
                      >
                        +0.2s
                      </button>
                      <button
                        className="sync-test-play-btn"
                        onClick={() => {
                          if (line.time != null && onSeek) {
                            onSeek(Math.max(0, line.time - 0.2));
                            if (!isPlaying && onTogglePlay) onTogglePlay();
                          }
                        }}
                        title="Play from this line"
                      >
                        <Play size={14} />
                      </button>
                      <button
                        className="sync-restamp-btn"
                        onClick={() => handleRestampLine(idx)}
                        title="Re-stamp at current audio time"
                      >
                        Stamp
                      </button>
                    </div>
                  </div>
                ))}
                <div ref={listEndRef} />
              </div>
            </div>
          )}

          {/* TAB 3: PASTE TEXT */}
          {activeTab === 'paste' && (
            <div className="sync-paste-view">
              <label className="sync-paste-label">
                Paste or edit plain text lyrics (one line per verse):
              </label>
              <textarea
                className="sync-paste-textarea"
                value={rawTextInput}
                onChange={e => setRawTextInput(e.target.value)}
                placeholder="Paste lyrics text here..."
                rows={10}
              />
              <div className="sync-paste-actions">
                <button
                  className="sync-apply-text-btn"
                  onClick={handleApplyRawText}
                >
                  <ArrowRight size={16} />
                  <span>Load Into Sync Studio</span>
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Footer Actions */}
        <div className="sync-studio-footer">
          <div className="sync-footer-stats">
            <div className="sync-progress-bar-bg">
              <div
                className="sync-progress-bar-fill"
                style={{ width: `${progressPercent}%` }}
              />
            </div>
            <span className="sync-progress-text">
              {syncedCount} of {lines.length} lines synced ({progressPercent}%)
            </span>
          </div>

          <div className="sync-footer-buttons">
            <button
              className="sync-cancel-btn"
              onClick={onClose}
              disabled={isSubmitting}
            >
              Cancel
            </button>
            <button
              className={`sync-save-publish-btn ${isSubmitting ? 'submitting' : ''}`}
              onClick={handleSaveAndPublish}
              disabled={isSubmitting || syncedCount < 2}
            >
              {isSubmitting ? (
                <>
                  <Sparkles size={16} className="spin-icon" />
                  <span>
                    {publishStatus === 'published'
                      ? 'Published to LRCLIB!'
                      : publishStatus === 'saved_locally'
                      ? 'Saved Locally!'
                      : 'Saving & Publishing...'}
                  </span>
                </>
              ) : (
                <>
                  <Globe size={16} />
                  <span>Save & Publish to LRCLIB</span>
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
