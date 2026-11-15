import React, { useState } from 'react';
import {
  Sparkles,
  Check,
  ArrowRight,
  ArrowLeft,
  X,
  Music,
  Heart,
  ShieldCheck,
  Headphones,
  Compass
} from 'lucide-react';
import {
  profileService,
  AVAILABLE_AVATARS,
  AVAILABLE_LANGUAGES,
  POPULAR_ARTISTS
} from '../services/profileService';

export default function NewUserOnboardingModal({
  isOpen,
  onClose,
  onComplete,
  showToast
}) {
  if (!isOpen) return null;

  // Step 1: Identity & Avatar | Step 2: Languages | Step 3: Favorite Artists
  const [currentStep, setCurrentStep] = useState(1);

  // Form State
  const [name, setName] = useState('');
  const [selectedAvatar, setSelectedAvatar] = useState('🎧');
  const [selectedLangs, setSelectedLangs] = useState(['Hindi', 'Punjabi']);
  const [selectedArtists, setSelectedArtists] = useState(['Arijit Singh', 'AP Dhillon']);

  const handleToggleLang = (langName) => {
    setSelectedLangs(prev => {
      if (prev.includes(langName)) {
        if (prev.length === 1) return prev;
        return prev.filter(l => l !== langName);
      }
      return [...prev, langName];
    });
  };

  const handleToggleArtist = (artistName) => {
    setSelectedArtists(prev => {
      if (prev.includes(artistName)) {
        if (prev.length === 1) return prev;
        return prev.filter(a => a !== artistName);
      }
      return [...prev, artistName];
    });
  };

  const handleFinish = () => {
    const finalName = name.trim() || 'Music Lover';
    const updatedProfile = {
      ...profileService.getActiveProfile(),
      name: finalName,
      avatar: selectedAvatar,
      preferredLanguages: selectedLangs,
      favoriteArtists: selectedArtists,
      isConfigured: true,
      updatedAt: Date.now()
    };

    profileService.saveActiveProfile(updatedProfile);
    profileService.setOnboardingCompleted(true);

    if (showToast) {
      showToast(`✨ Welcome to AuraWave, ${finalName}! Your music feed is ready.`);
    }

    if (onComplete) {
      onComplete(updatedProfile);
    }
    onClose();
  };

  const handleSkip = () => {
    profileService.setOnboardingCompleted(true);
    if (showToast) {
      showToast('🎵 Welcome to AuraWave! Enjoy the music.');
    }
    onClose();
  };

  return (
    <div className="onboarding-modal-backdrop" id="new-user-onboarding-overlay">
      <div className="onboarding-modal-container" id="new-user-onboarding-modal">
        {/* Top Progress bar & Skip */}
        <div className="onboarding-top-nav">
          <div className="onboarding-step-indicator">
            <span className={`step-dot ${currentStep >= 1 ? 'active' : ''}`} />
            <span className={`step-dot ${currentStep >= 2 ? 'active' : ''}`} />
            <span className={`step-dot ${currentStep >= 3 ? 'active' : ''}`} />
            <span className="step-text">STEP {currentStep} OF 3</span>
          </div>
          <button
            type="button"
            className="onboarding-skip-btn"
            onClick={handleSkip}
          >
            Skip for now
          </button>
        </div>

        {/* ============================================================ */}
        {/* STEP 1: IDENTITY & AVATAR                                    */}
        {/* ============================================================ */}
        {currentStep === 1 && (
          <div className="onboarding-step-content">
            <div className="onboarding-header">
              <div className="onboarding-hero-icon">
                <Sparkles size={28} color="#c084fc" />
              </div>
              <h2 className="onboarding-title">Welcome to AuraWave</h2>
              <p className="onboarding-subtitle">
                Personalize your sound stage in 20 seconds. 100% private & on-device.
              </p>
            </div>

            <div className="onboarding-avatar-preview-box">
              <div className="onboarding-avatar-circle-large">
                <span className="onboarding-avatar-large-emoji">{selectedAvatar}</span>
              </div>
              <span className="onboarding-avatar-hint">Select your vibe avatar</span>
            </div>

            {/* Avatar Row */}
            <div className="onboarding-avatars-row">
              {AVAILABLE_AVATARS.map(avatar => (
                <button
                  key={avatar}
                  type="button"
                  className={`onboarding-avatar-btn ${selectedAvatar === avatar ? 'selected' : ''}`}
                  onClick={() => setSelectedAvatar(avatar)}
                >
                  <span>{avatar}</span>
                </button>
              ))}
            </div>

            {/* Name Input */}
            <div className="onboarding-input-group">
              <label className="onboarding-label">WHAT SHOULD WE CALL YOU?</label>
              <input
                type="text"
                placeholder="Enter your name (e.g. Dhruv)"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="onboarding-text-input"
                maxLength={30}
                autoFocus
              />
            </div>

            <div className="onboarding-footer">
              <div className="privacy-mini-badge">
                <ShieldCheck size={14} color="#4ade80" />
                <span>Zero tracking • Never sent to any server</span>
              </div>
              <button
                type="button"
                className="onboarding-next-btn"
                onClick={() => setCurrentStep(2)}
              >
                <span>Next: Choose Languages</span>
                <ArrowRight size={16} />
              </button>
            </div>
          </div>
        )}

        {/* ============================================================ */}
        {/* STEP 2: LANGUAGES & CULTURES                                 */}
        {/* ============================================================ */}
        {currentStep === 2 && (
          <div className="onboarding-step-content">
            <div className="onboarding-header">
              <div className="onboarding-hero-icon">
                <Music size={26} color="#60a5fa" />
              </div>
              <h2 className="onboarding-title">What sounds do you love?</h2>
              <p className="onboarding-subtitle">
                Select your preferred languages to populate your Home mixes.
              </p>
            </div>

            <div className="onboarding-chips-grid">
              {AVAILABLE_LANGUAGES.map(lang => {
                const isSelected = selectedLangs.includes(lang.name);
                return (
                  <button
                    key={lang.id}
                    type="button"
                    className={`onboarding-chip-card ${isSelected ? 'active' : ''}`}
                    onClick={() => handleToggleLang(lang.name)}
                  >
                    <div className="onboarding-chip-top">
                      <span className="onboarding-chip-flag">{lang.flag}</span>
                      {isSelected && <Check size={14} className="chip-check-icon" />}
                    </div>
                    <span className="onboarding-chip-name">{lang.name}</span>
                  </button>
                );
              })}
            </div>

            <div className="onboarding-footer">
              <button
                type="button"
                className="onboarding-back-btn"
                onClick={() => setCurrentStep(1)}
              >
                <ArrowLeft size={16} />
                <span>Back</span>
              </button>
              <button
                type="button"
                className="onboarding-next-btn"
                onClick={() => setCurrentStep(3)}
              >
                <span>Next: Choose Artists</span>
                <ArrowRight size={16} />
              </button>
            </div>
          </div>
        )}

        {/* ============================================================ */}
        {/* STEP 3: FAVORITE ARTISTS                                     */}
        {/* ============================================================ */}
        {currentStep === 3 && (
          <div className="onboarding-step-content">
            <div className="onboarding-header">
              <div className="onboarding-hero-icon">
                <Heart size={26} color="#ec4899" />
              </div>
              <h2 className="onboarding-title">Pick artists you listen to</h2>
              <p className="onboarding-subtitle">
                We use these to build your "Made for You" algorithmic shelf.
              </p>
            </div>

            <div className="onboarding-selection-pill-row">
              <span className="onboarding-selected-count">
                {selectedArtists.length} Artists Selected
              </span>
            </div>

            <div className="onboarding-artists-grid">
              {POPULAR_ARTISTS.map(artist => {
                const isSelected = selectedArtists.includes(artist.name);
                return (
                  <button
                    key={artist.name}
                    type="button"
                    className={`onboarding-artist-card ${isSelected ? 'active' : ''}`}
                    onClick={() => handleToggleArtist(artist.name)}
                  >
                    <div className="onboarding-artist-info">
                      <h4 className="onboarding-artist-name">{artist.name}</h4>
                      <span className="onboarding-artist-genre">{artist.genre}</span>
                    </div>
                    <div className="onboarding-artist-check-circle">
                      {isSelected ? <Check size={13} color="#ffffff" /> : null}
                    </div>
                  </button>
                );
              })}
            </div>

            <div className="onboarding-footer">
              <button
                type="button"
                className="onboarding-back-btn"
                onClick={() => setCurrentStep(2)}
              >
                <ArrowLeft size={16} />
                <span>Back</span>
              </button>
              <button
                type="button"
                className="onboarding-launch-btn"
                onClick={handleFinish}
              >
                <Sparkles size={16} />
                <span>Start Listening Now ✨</span>
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
