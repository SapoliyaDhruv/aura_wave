import React from 'react';

export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{
          padding: '40px 20px',
          textAlign: 'center',
          color: '#ffffff',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '16px'
        }}>
          <h2 style={{ fontFamily: 'Outfit, sans-serif', color: '#1DB954' }}>Something went wrong</h2>
          <p style={{ color: '#a1a1aa', fontSize: '13px', maxWidth: '300px' }}>
            {this.state.error?.message || 'An unexpected error occurred.'}
          </p>
          <button
            onClick={() => {
              this.setState({ hasError: false, error: null });
              window.location.reload();
            }}
            style={{
              background: '#1DB954',
              color: '#000',
              border: 'none',
              padding: '10px 20px',
              borderRadius: '999px',
              fontWeight: '700',
              cursor: 'pointer'
            }}
          >
            Reload Player
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
