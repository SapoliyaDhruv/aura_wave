/**
 * Featured Content & Data Models matching AuraWave Pro Design Specifications
 */

export const HERO_SONIC_JOURNEY = {
  title: 'Curated Sonic Journeys',
  subLabel: 'WEEKLY FREQUENCY • VOL. 48',
  description: 'Your weekly hyper-personalized mix infused with seamless crossfades, lossless master audio, and stage-lit acoustics tuned for critical listening.',
  badges: [
    { label: '✦ LOSSLESS HI-FI', class: 'sonic-badge-blue' },
    { label: 'DOLBY ATMOS', class: 'sonic-badge-purple' },
    { label: 'ZERO ADS', class: 'sonic-badge-green' }
  ],
  syncedWidget: {
    label: 'NOW SYNCED',
    title: 'Curated Sonic Journey',
    meta: 'Lossless Spatial Master',
    cover: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=300&auto=format&fit=crop&q=80'
  }
};

export const DEFAULT_RECENTLY_PLAYED = [
  {
    id: 'rp-kesariya-acoustic',
    title: 'Kesariya Acoustic',
    artist: 'Arijit Singh',
    duration: 268,
    coverUrl: 'https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=300&auto=format&fit=crop&q=80',
    isVerified: true
  },
  {
    id: 'rp-starboy-remix',
    title: 'Starboy (Remix)',
    artist: 'The Weeknd',
    duration: 230,
    coverUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=300&auto=format&fit=crop&q=80',
    isVerified: true
  },
  {
    id: 'rp-brown-munde',
    title: 'Brown Munde',
    artist: 'AP Dhillon, Gurinder Gill',
    duration: 266,
    coverUrl: 'https://images.unsplash.com/photo-1492684223066-81342ee5ff30?w=300&auto=format&fit=crop&q=80',
    isVerified: true
  },
  {
    id: 'rp-lover',
    title: 'Lover',
    artist: 'Diljit Dosanjh',
    duration: 191,
    coverUrl: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=300&auto=format&fit=crop&q=80',
    isVerified: true
  },
  {
    id: 'rp-blinding-lights',
    title: 'Blinding Lights',
    artist: 'The Weeknd',
    duration: 200,
    coverUrl: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=300&auto=format&fit=crop&q=80',
    isVerified: true
  },
  {
    id: 'rp-raataan-lambiyan',
    title: 'Raataan Lambiyan',
    artist: 'Tanishk Bagchi, Jubin Nautiyal',
    duration: 230,
    coverUrl: 'https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=300&auto=format&fit=crop&q=80',
    isVerified: true
  }
];

export const RECOMMENDED_FOR_YOU_ITEMS = [
  {
    id: 'rec-midnight-wave',
    badge: 'EP • 24-bit',
    title: 'Midnight Wave EP',
    subtitle: 'Electronic Euphoria • Synth',
    cover: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=400&auto=format&fit=crop&q=80',
    query: 'Electronic Synthwave Hits'
  },
  {
    id: 'rec-desi-drip',
    badge: 'MASTER',
    title: 'Desi Drip Vol. 2',
    subtitle: 'Punjabi Hip-Hop Stars',
    cover: 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=400&auto=format&fit=crop&q=80',
    query: 'Punjabi Hip Hop Banger'
  },
  {
    id: 'rec-monsoon-coffee',
    badge: 'ATMOS',
    title: 'Monsoon Coffee',
    subtitle: 'Indian Indie Lo-Fi • Chilled',
    cover: 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=400&auto=format&fit=crop&q=80',
    query: 'Monsoon Lo-Fi Hindi'
  },
  {
    id: 'rec-neon-highway',
    badge: 'ORIGINAL',
    title: 'Neon Highway',
    subtitle: 'Synthwave • Retrowave',
    cover: 'https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=400&auto=format&fit=crop&q=80',
    query: 'Synthwave Retrowave Beats'
  },
  {
    id: 'rec-soulful-ghazals',
    badge: 'LOSSLESS',
    title: 'Soulful Ghazals',
    subtitle: 'Timeless Acoustic Classics',
    cover: 'https://images.unsplash.com/photo-1465847899084-d164df4dedc6?w=400&auto=format&fit=crop&q=80',
    query: 'Acoustic Soulful Classics'
  }
];
