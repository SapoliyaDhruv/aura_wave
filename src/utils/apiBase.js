/**
 * API Base URL Resolver
 * Resolves API requests to the cloud Vercel backend when running inside native mobile
 * containers (Capacitor/Android APK on localhost/capacitor://) or development.
 */
export function getApiBaseUrl() {
  if (typeof window !== 'undefined' && window.location) {
    const origin = window.location.origin || '';
    // If running inside Capacitor Android APK, iOS container, or local dev without serverless
    if (
      origin.includes('localhost') ||
      origin.includes('127.0.0.1') ||
      origin.startsWith('capacitor:') ||
      origin.startsWith('http://localhost') ||
      origin.startsWith('https://localhost')
    ) {
      return 'https://aurawave-music.vercel.app';
    }
    return origin;
  }
  return 'https://aurawave-music.vercel.app';
}
