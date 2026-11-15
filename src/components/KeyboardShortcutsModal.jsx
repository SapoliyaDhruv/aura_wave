import React from 'react';
import { Keyboard, X, Command } from 'lucide-react';

export default function KeyboardShortcutsModal({ isOpen, onClose }) {
  if (!isOpen) return null;

  const shortcuts = [
    { key: 'Space', desc: 'Play / Pause music' },
    { key: 'Ctrl + →', desc: 'Next track' },
    { key: 'Ctrl + ←', desc: 'Previous track' },
    { key: '→ / ←', desc: 'Seek 5 seconds forward / back' },
    { key: 'L', desc: 'Like / Unlike current song' },
    { key: 'M', desc: 'Toggle Mute / Unmute' },
    { key: 'F', desc: 'Open Fullscreen Player' },
    { key: 'T', desc: 'Open Sleep Timer' },
    { key: '?', desc: 'Show this shortcuts guide' }
  ];

  return (
    <div className="modal-backdrop-blur" onClick={onClose} id="shortcuts-modal-overlay">
      <div
        className="shortcuts-modal-content"
        onClick={e => e.stopPropagation()}
        id="shortcuts-modal"
      >
        <div className="sqm-header">
          <div className="sqm-title-group">
            <div className="sqm-icon-wrap" style={{ background: 'rgba(29, 185, 84, 0.15)' }}>
              <Keyboard size={20} color="#1DB954" />
            </div>
            <div>
              <h2 className="sqm-title">Keyboard Shortcuts</h2>
              <p className="sqm-sub">Pro-level media controls at your fingertips</p>
            </div>
          </div>
          <button className="icon-btn sqm-close-btn" onClick={onClose} aria-label="Close modal">
            <X size={20} />
          </button>
        </div>

        <div className="shortcuts-grid">
          {shortcuts.map((sc, idx) => (
            <div key={idx} className="shortcut-row">
              <span className="shortcut-desc">{sc.desc}</span>
              <kbd className="shortcut-key-badge">{sc.key}</kbd>
            </div>
          ))}
        </div>

        <div className="shortcuts-footer-note">
          <span>💡 Shortcuts work anytime you are not typing in a search field</span>
        </div>
      </div>
    </div>
  );
}
