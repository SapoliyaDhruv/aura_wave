import React, { useState } from 'react';
import { X, Sparkles, Music, Check, ArrowRight, Loader2 } from 'lucide-react';
import { SPOTIFY_PRESETS, importSpotifyPlaylist } from '../services/spotifyService';

export default function ImportSpotifyModal({ isOpen, onClose, onImportSuccess }) {
  const [urlInput, setUrlInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  if (!isOpen) return null;

  const handleImport = async (targetUrl) => {
    const url = targetUrl || urlInput;
    if (!url || !url.trim()) {
      setErrorMsg('Please paste a Spotify playlist link or select a preset below.');
      return;
    }

    setLoading(true);
    setErrorMsg('');

    try {
      const playlist = await importSpotifyPlaylist(url);
      setLoading(false);
      onImportSuccess(playlist);
      onClose();
    } catch (err) {
      setLoading(false);
      setErrorMsg(err.message || 'Failed to import playlist. Please try another link.');
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose} id="import-spotify-modal-backdrop">
      <div className="modal-content glass-card" onClick={e => e.stopPropagation()} id="import-spotify-modal">
        <div className="modal-header">
          <div className="modal-title-group">
            <div className="spotify-badge-icon">
              <Sparkles size={18} color="#1DB954" />
            </div>
            <div>
              <h3 className="modal-title">Import Spotify Playlist</h3>
              <p className="modal-subtitle">100% Ad-Free • Unlimited Skips • Synced Lyrics</p>
            </div>
          </div>
          <button className="icon-btn" onClick={onClose} id="modal-close-btn" aria-label="Close modal">
            <X size={20} />
          </button>
        </div>

        {/* Input Field */}
        <div className="modal-input-group">
          <label className="input-label" htmlFor="spotify-url-input">
            Spotify Playlist Link or ID
          </label>
          <div className="input-with-button">
            <input
              id="spotify-url-input"
              type="text"
              placeholder="e.g. https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
              value={urlInput}
              onChange={e => {
                setUrlInput(e.target.value);
                setErrorMsg('');
              }}
              className="spotify-text-input"
            />
            <button
              className="import-submit-btn"
              id="import-submit-btn"
              disabled={loading}
              onClick={() => handleImport(urlInput)}
            >
              {loading ? <Loader2 size={18} className="rotating-spinner" /> : <ArrowRight size={18} />}
            </button>
          </div>
          {errorMsg && <p className="error-message">{errorMsg}</p>}
        </div>

        {/* 1-Click Presets */}
        <div className="modal-presets-section">
          <h4 className="presets-heading">Or Try Popular Spotify Presets:</h4>
          <div className="presets-list">
            {(SPOTIFY_PRESETS || []).map(preset => (
              <div
                key={preset.id}
                className="preset-item"
                onClick={() => handleImport(preset.id)}
                id={`preset-${preset.id}`}
              >
                <img src={preset.cover} alt={preset.title} className="preset-cover" />
                <div className="preset-details">
                  <span className="preset-title">{preset.title}</span>
                  <span className="preset-sub">
                    {(preset.tracks?.length || 5)} tracks • {preset.owner || 'Spotify'}
                  </span>
                </div>
                <button className="preset-import-btn" aria-label={`Import ${preset.title}`}>
                  <ArrowRight size={16} />
                </button>
              </div>
            ))}
          </div>
        </div>

        <div className="modal-footer-note">
          <Music size={14} />
          <span>Streams ad-free audio in background with real-time synchronized lyrics</span>
        </div>
      </div>
    </div>
  );
}
