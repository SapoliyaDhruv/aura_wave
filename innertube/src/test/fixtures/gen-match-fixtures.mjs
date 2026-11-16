// Regenerates match-fixtures.json from match.js beside this file: the song matcher of the
// original web app, kept as the reference the Kotlin port is checked against.
// Run:  node gen-match-fixtures.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import * as m from './match.js';

const titles = [
  'Tum Hi Ho', 'Tum Hi Ho (From "Aashiqui 2")', 'Kesariya (From "Brahmastra")', 'Raataan Lambiyan', 'Raatan Lambiyan',
  'Channa Mereya - Unplugged', 'Channa Mereya (Lofi Flip)', 'Tere Bina', 'Tere Bin Nahi Laage', 'Maahi Ve [Official Audio]',
  'Mahi Ve', 'Apna Bana Le (Instrumental)', 'Shape of You (feat. Stormzy) [Remix]', 'Señorita', 'Blinding Lights - Slowed + Reverb',
  'Arijit Singh - Tum Hi Ho | Official Video', 'O Bedardeya', 'Heeriye (feat. Arijit Singh)', 'Pasoori', 'Tum Hi Ho Karaoke With Lyrics',
  'तुम ही हो', 'Dil Diyan Gallan 8D Audio', "Short n' Sweet", 'Zaalima', 'Zalima', 'Khairiyat (Live)', 'Ve Kamleya', 'We Kamleya',
];
const artists = [
  'Arijit Singh', 'Pritam, Arijit Singh & Shreya Ghoshal', 'Mithoon feat. Arijit Singh', 'Ed Sheeran x Stormzy', 'Various Artists',
  'Jasleen Royal, Arijit Singh', 'The Weeknd', 'Shawn Mendes and Camila Cabello', 'Ali Sethi / Shae Gill', 'B Praak', 'Beyoncé',
];
const albums = ['', 'Aashiqui 2', 'Unplugged Sessions', 'Status', 'Karaoke Hits Vol. 2', 'Live in Concert', 'Brahmastra (Original Motion Picture Soundtrack)'];

const out = {
  coreTitle: titles.map((t) => [t, m.coreTitle(t)]),
  fold: [...titles, ...artists].map((t) => [t, m.fold(t)]),
  versionFlags: titles.map((t) => [t, [...m.versionFlags(t)]]),
  albumFlags: albums.map((t) => [t, [...m.versionFlags(t, { album: true })]]),
  splitArtists: artists.map((a) => [a, m.splitArtists(a)]),
  titleSimilarity: [],
  artistOverlap: [],
  scoreCandidate: [],
  songKey: [],
};

for (const a of titles) for (const b of titles) out.titleSimilarity.push([a, b, m.titleSimilarity(a, b)]);
for (const a of artists) for (const b of artists) out.artistOverlap.push([a, b, m.artistOverlap(a, b)]);
for (const t of titles.slice(0, 12)) for (const a of artists.slice(0, 4)) out.songKey.push([t, a, m.songKey({ title: t, artist: a })]);

const kinds = ['song', 'video', 'upload'];
const durations = [0, 60, 262, 264, 270, 300, 340];
let i = 0;
for (const wt of titles) {
  for (const ct of titles) {
    // A deterministic spread of artists, albums, durations and kinds across the title pairs.
    const want = { title: wt, artist: i % 5 === 0 ? '' : artists[i % artists.length], duration: durations[(i * 3) % durations.length] };
    const cand = {
      title: ct,
      artist: artists[(i * 7 + 1) % artists.length],
      album: albums[i % albums.length],
      duration: durations[(i * 5 + 2) % durations.length],
      kind: kinds[i % 3],
      channel: artists[(i * 2) % artists.length],
    };
    out.scoreCandidate.push([want, cand, m.scoreCandidate(want, cand)]);
    i++;
  }
}

const file = path.join(path.dirname(fileURLToPath(import.meta.url)), '../resources/match-fixtures.json');
writeFileSync(file, JSON.stringify(out));
console.log(`wrote ${file}:`, Object.fromEntries(Object.entries(out).map(([k, v]) => [k, v.length])));
