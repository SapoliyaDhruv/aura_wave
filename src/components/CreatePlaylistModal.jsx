import React, { useState } from 'react';
import { X, FolderPlus, Sparkles } from 'lucide-react';

export default function CreatePlaylistModal({ isOpen, onClose, onCreatePlaylist }) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [selectedCover, setSelectedCover] = useState('/covers/neon_pulse.jpg');

  if (!isOpen) return null;

  const covers = [
    { label: 'Neon Cyber', url: '/covers/neon_pulse.jpg' },
    { label: 'Sunset Lo-Fi', url: '/covers/sunset_sketches.jpg' },
    { label: 'Late Night', url: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80' },
    { label: 'Gym Beast', url: 'https://images.unsplash.com/photo-1517838277536-f5f99be501cd?w=600&auto=format&fit=crop&q=80' }
  ];

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!name.trim()) return;
    onCreatePlaylist(name.trim(), description.trim(), selectedCover);
    setName('');
    setDescription('');
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={onClose} id="create-playlist-backdrop">
      <div className="modal-content glass-card" onClick={e => e.stopPropagation()} id="create-playlist-modal">
        <div className="modal-header">
          <div className="modal-title-group">
            <div className="spotify-badge-icon">
              <FolderPlus size={18} color="#1DB954" />
            </div>
            <div>
              <h3 className="modal-title">Create Custom Playlist</h3>
              <p className="modal-subtitle">Organize your favorite ad-free songs</p>
            </div>
          </div>
          <button className="icon-btn" onClick={onClose} id="create-pl-close-btn" aria-label="Close modal">
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="create-playlist-form">
          <div className="modal-input-group">
            <label className="input-label" htmlFor="new-pl-name-input">
              Playlist Name *
            </label>
            <input
              id="new-pl-name-input"
              type="text"
              placeholder="e.g. Gym Pump, Late Night Drive..."
              value={name}
              onChange={e => setName(e.target.value)}
              className="spotify-text-input"
              autoFocus
              required
            />
          </div>

          <div className="modal-input-group">
            <label className="input-label" htmlFor="new-pl-desc-input">
              Description (Optional)
            </label>
            <input
              id="new-pl-desc-input"
              type="text"
              placeholder="e.g. High energy tracks for intense workouts"
              value={description}
              onChange={e => setDescription(e.target.value)}
              className="spotify-text-input"
            />
          </div>

          <div className="modal-input-group">
            <label className="input-label">Choose Artwork Theme</label>
            <div className="cover-picker-row">
              {covers.map(c => (
                <div
                  key={c.url}
                  className={`cover-picker-item ${selectedCover === c.url ? 'selected' : ''}`}
                  onClick={() => setSelectedCover(c.url)}
                >
                  <img src={c.url} alt={c.label} className="cover-picker-img" />
                  <span className="cover-picker-label">{c.label}</span>
                </div>
              ))}
            </div>
          </div>

          <button
            type="submit"
            className="create-submit-btn"
            id="create-playlist-submit-btn"
            disabled={!name.trim()}
          >
            <Sparkles size={16} />
            <span>Create & Save Playlist</span>
          </button>
        </form>
      </div>
    </div>
  );
}
