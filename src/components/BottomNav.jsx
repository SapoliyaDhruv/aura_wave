import React from 'react';
import { Home, Search, Library, ArrowDownCircle } from 'lucide-react';

export default function BottomNav({ activeTab, onSelectTab, downloadedCount = 0 }) {
  const tabs = [
    { id: 'home', label: 'Home', icon: Home },
    { id: 'search', label: 'Search', icon: Search },
    { id: 'library', label: 'Your Library', icon: Library },
    { id: 'offline', label: 'Downloads', icon: ArrowDownCircle, badge: downloadedCount }
  ];

  return (
    <nav className="bottom-nav" id="bottom-navigation-bar">
      {tabs.map(tab => {
        const Icon = tab.icon;
        const isActive = activeTab === tab.id;

        return (
          <button
            key={tab.id}
            id={`nav-btn-${tab.id}`}
            className={`nav-item ${isActive ? 'active' : ''}`}
            onClick={() => onSelectTab(tab.id)}
            aria-label={tab.label}
          >
            <div className="nav-icon-container">
              <Icon size={22} />
              {tab.badge > 0 && tab.id === 'offline' && (
                <span className="nav-count-badge">{tab.badge}</span>
              )}
            </div>
            <span className="nav-label">{tab.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
