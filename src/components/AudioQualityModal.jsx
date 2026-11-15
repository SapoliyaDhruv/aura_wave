import React from 'react';
import { X, Sparkles, Check, Sliders, Waves, ShieldCheck } from 'lucide-react';
import { audioEngine } from '../services/audioEngine';

export default function AudioQualityModal({ isOpen, onClose, currentQuality, onSelectQuality }) {
  if (!isOpen) return null;

  const qualityOptions = [
    {
      id: '320k',
      badge: 'Hi-Fi Master',
      title: 'Very High (320 kbps)',
      desc: 'Lossless studio clarity with Spotify-style dynamic loudness normalization and warm low-end boost.',
      specs: '320kbps Opus/AAC • +3.5dB Sub-Bass • Dynamic Mastering',
      isPro: true
    },
    {
      id: '160k',
      badge: 'Standard High',
      title: 'High (160 kbps)',
      desc: 'Optimal balance between streaming data usage and crisp studio fidelity.',
      specs: '160kbps AAC • +1.5dB Warmth • Balanced EQ',
      isPro: false
    },
    {
      id: '96k',
      badge: 'Data Saver',
      title: 'Normal (96 kbps)',
      desc: 'Lightweight playback designed for slow connections and low mobile data usage.',
      specs: '96kbps MP3 • Flat EQ • Zero Enhancement',
      isPro: false
    }
  ];

  const handleSelect = (qId) => {
    audioEngine.setAudioQuality(qId);
    onSelectQuality(qId);
  };

  return (
    <div className="modal-backdrop-blur" onClick={onClose}>
      <div
        className="spotify-quality-modal"
        onClick={e => e.stopPropagation()}
        id="audio-quality-modal"
      >
        <div className="sqm-header">
          <div className="sqm-title-group">
            <div className="sqm-icon-wrap">
              <Sparkles size={20} color="#1DB954" />
            </div>
            <div>
              <h2 className="sqm-title">Audio Streaming Quality</h2>
              <p className="sqm-sub">Match Spotify's 320kbps Master Sound Stage</p>
            </div>
          </div>
          <button className="icon-btn sqm-close-btn" onClick={onClose} aria-label="Close modal">
            <X size={20} />
          </button>
        </div>

        <div className="sqm-options-list">
          {qualityOptions.map((opt) => {
            const isSelected = currentQuality === opt.id;
            return (
              <div
                key={opt.id}
                className={`sqm-card ${isSelected ? 'active-quality' : ''}`}
                onClick={() => handleSelect(opt.id)}
                id={`quality-opt-${opt.id}`}
              >
                <div className="sqm-card-top">
                  <div className="sqm-card-meta">
                    <span className={`sqm-badge ${opt.id === '320k' ? 'hifi-badge' : ''}`}>
                      {opt.badge}
                    </span>
                    <h3 className="sqm-card-name">{opt.title}</h3>
                  </div>
                  {isSelected && (
                    <div className="sqm-check-circle">
                      <Check size={16} strokeWidth={3} />
                    </div>
                  )}
                </div>

                <p className="sqm-card-desc">{opt.desc}</p>
                <div className="sqm-card-footer">
                  <Waves size={13} color="#1DB954" />
                  <span className="sqm-specs-pill">{opt.specs}</span>
                </div>
              </div>
            );
          })}
        </div>

        <div className="sqm-dsp-info">
          <ShieldCheck size={18} color="#1DB954" />
          <span>Real-time Web Audio API Dynamic Compressor & Low-Shelf Equalizer active</span>
        </div>

        <div className="sqm-actions">
          <button className="sqm-done-btn" onClick={onClose}>
            Done
          </button>
        </div>
      </div>
    </div>
  );
}
