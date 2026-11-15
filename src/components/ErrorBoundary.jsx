import { Component } from 'react';

export default class ErrorBoundary extends Component {
  state = { error: null };

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('AuraWave crashed:', error, info);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="crash" role="alert">
        <h1 className="crash__title">AuraWave hit a problem</h1>
        <p className="crash__body">Your library is safe on this device. Reload to keep listening.</p>
        <pre className="crash__detail">{this.state.error.message}</pre>
        <button type="button" className="btn btn--primary" onClick={() => window.location.reload()}>Reload AuraWave</button>
      </div>
    );
  }
}
