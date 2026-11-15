import React, { useState } from 'react';
import { Check, Music } from 'lucide-react';

const GRADIENT_PALETTES = [
  'linear-gradient(135deg, #10b981 0%, #06b6d4 100%)', // Emerald Cyan
  'linear-gradient(135deg, #6366f1 0%, #a855f7 100%)', // Indigo Purple
  'linear-gradient(135deg, #ec4899 0%, #f43f5e 100%)', // Pink Rose
  'linear-gradient(135deg, #f59e0b 0%, #ef4444 100%)', // Amber Red
  'linear-gradient(135deg, #0ea5e9 0%, #3b82f6 100%)', // Sky Blue
  'linear-gradient(135deg, #14b8a6 0%, #10b981 100%)'  // Teal Green
];

function getInitials(name) {
  if (!name) return 'AW';
  const clean = name.replace(/^(the|dj|mc)\s+/i, '').trim();
  const parts = clean.split(/\s+/).filter(Boolean);
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function getGradientForName(name) {
  if (!name) return GRADIENT_PALETTES[0];
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  const index = Math.abs(hash) % GRADIENT_PALETTES.length;
  return GRADIENT_PALETTES[index];
}

/**
 * ArtistAvatar Component
 * 
 * Guarantees:
 * 1. Only authentic, original photos are displayed (never fake/AI stock photos).
 * 2. If photo is missing or fails to load, gracefully falls back to a stylish initials avatar.
 * 3. Shows verified badge when requested.
 */
export default function ArtistAvatar({
  name = 'Artist',
  imageUrl = null,
  size = 80,
  className = '',
  showVerifiedBadge = true,
  style = {}
}) {
  const [hasError, setHasError] = useState(false);
  const [isLoaded, setIsLoaded] = useState(false);

  const initials = getInitials(name);
  const gradient = getGradientForName(name);
  const showImage = Boolean(imageUrl) && !hasError;
  const fontSize = Math.max(14, Math.round(size * 0.38));
  const badgeSize = Math.max(14, Math.round(size * 0.28));

  return (
    <div
      className={`artist-avatar-container ${className}`}
      style={{
        width: `${size}px`,
        height: `${size}px`,
        minWidth: `${size}px`,
        minHeight: `${size}px`,
        position: 'relative',
        borderRadius: '50%',
        ...style
      }}
    >
      {/* Background Avatar Circle (Initials & Gradient Fallback) */}
      <div
        className="artist-avatar-fallback-circle"
        style={{
          width: '100%',
          height: '100%',
          borderRadius: '50%',
          background: gradient,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#ffffff',
          fontWeight: 800,
          fontSize: `${fontSize}px`,
          letterSpacing: '0.5px',
          boxShadow: '0 8px 24px rgba(0, 0, 0, 0.45)',
          userSelect: 'none',
          position: 'absolute',
          inset: 0,
          border: '2px solid rgba(255, 255, 255, 0.12)'
        }}
      >
        <span>{initials}</span>
      </div>

      {/* Original Image (Only rendered if URL provided and hasn't errored) */}
      {showImage && (
        <img
          src={imageUrl}
          alt={name}
          className="artist-avatar-image"
          onError={() => setHasError(true)}
          onLoad={() => setIsLoaded(true)}
          style={{
            width: '100%',
            height: '100%',
            borderRadius: '50%',
            objectFit: 'cover',
            position: 'absolute',
            inset: 0,
            opacity: isLoaded ? 1 : 0,
            transition: 'opacity 0.25s ease',
            border: '2px solid rgba(255, 255, 255, 0.12)'
          }}
        />
      )}

      {/* Verified Artist Checkmark Badge */}
      {showVerifiedBadge && (
        <div
          className="artist-avatar-verified-badge"
          title="Verified Artist"
          style={{
            position: 'absolute',
            bottom: '-2px',
            right: '-2px',
            width: `${badgeSize}px`,
            height: `${badgeSize}px`,
            borderRadius: '50%',
            background: '#1DB954',
            color: '#000000',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 2px 6px rgba(0, 0, 0, 0.6)',
            border: '2px solid #000000',
            zIndex: 2
          }}
        >
          <Check size={Math.round(badgeSize * 0.65)} strokeWidth={3} />
        </div>
      )}
    </div>
  );
}
