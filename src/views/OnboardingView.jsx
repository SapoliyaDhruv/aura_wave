import { useEffect, useState } from 'react';
import { Check, Search, X } from 'lucide-react';
import { library } from '../services/library.js';
import { invalidateFeed } from '../services/recommender.js';
import * as catalog from '../services/catalog.js';
import { useLibrary } from '../state/library.js';
import { useRouter } from '../state/router.jsx';
import { Brand } from '../components/Navigation.jsx';

const LANGUAGES = ['Hindi', 'Punjabi', 'English', 'Tamil', 'Telugu', 'Bengali', 'Marathi', 'Gujarati', 'Malayalam', 'Kannada'];
const SUGGESTED = {
  Hindi: ['Arijit Singh', 'Shreya Ghoshal', 'Pritam', 'Atif Aslam', 'Jubin Nautiyal', 'Sonu Nigam', 'KK', 'Vishal Mishra', 'Anuv Jain', 'A.R. Rahman'],
  Punjabi: ['AP Dhillon', 'Karan Aujla', 'Diljit Dosanjh', 'Sidhu Moose Wala', 'Shubh', 'B Praak'],
  English: ['Taylor Swift', 'The Weeknd', 'Ed Sheeran', 'Billie Eilish', 'Sabrina Carpenter', 'Coldplay', 'Dua Lipa'],
  Tamil: ['Anirudh Ravichander', 'Sid Sriram', 'Yuvan Shankar Raja'],
  Telugu: ['Sid Sriram', 'Devi Sri Prasad', 'Thaman S'],
  Bengali: ['Arijit Singh', 'Anupam Roy', 'Shreya Ghoshal'],
  Marathi: ['Ajay-Atul', 'Shankar Mahadevan'],
  Gujarati: ['Kinjal Dave', 'Aditya Gadhvi'],
  Malayalam: ['K. S. Chithra', 'Vineeth Sreenivasan'],
  Kannada: ['Vijay Prakash', 'Sanjith Hegde'],
};

export default function OnboardingView({ onDone }) {
  const { taste } = useLibrary();
  const { navigate } = useRouter();
  const [languages, setLanguages] = useState(taste.languages?.length ? taste.languages : ['Hindi']);
  const [artists, setArtists] = useState(taste.artists || []);
  const [q, setQ] = useState('');
  const [found, setFound] = useState([]);

  useEffect(() => {
    if (q.trim().length < 2) { setFound([]); return; }
    let alive = true;
    const timer = setTimeout(() => {
      catalog.search(q).then((r) => alive && setFound(r.artists)).catch(() => alive && setFound([]));
    }, 350);
    return () => { alive = false; clearTimeout(timer); };
  }, [q]);

  const toggle = (list, setList, v) => setList(list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  const suggestions = [...new Set(languages.flatMap((l) => SUGGESTED[l] || []))];
  const finish = () => {
    library.setTaste({ languages, artists, onboarded: true });
    invalidateFeed();
    onDone?.();
    navigate('home');
  };

  return (
    <div className="onboard">
      <Brand />
      <h1 className="onboard__title">What do you love listening to?</h1>
      <p className="onboard__lead">Pick a few. Your home page starts from these, then learns from every song you play, like or skip.</p>

      <section className="onboard__step" aria-labelledby="ob-lang">
        <h2 id="ob-lang" className="onboard__step-title">Languages</h2>
        <div className="chips">
          {LANGUAGES.map((l) => (
            <button key={l} type="button" className={`chip ${languages.includes(l) ? 'is-on' : ''}`} aria-pressed={languages.includes(l)} onClick={() => toggle(languages, setLanguages, l)}>
              {languages.includes(l) && <Check size={15} aria-hidden="true" />}{l}
            </button>
          ))}
        </div>
      </section>

      <section className="onboard__step" aria-labelledby="ob-artists">
        <h2 id="ob-artists" className="onboard__step-title">Artists</h2>
        <div className="chips">
          {[...new Set([...artists, ...suggestions])].map((a) => (
            <button key={a} type="button" className={`chip ${artists.includes(a) ? 'is-on' : ''}`} aria-pressed={artists.includes(a)} onClick={() => toggle(artists, setArtists, a)}>
              {artists.includes(a) && <Check size={15} aria-hidden="true" />}{a}
            </button>
          ))}
        </div>
        <label className="field onboard__search">
          <span className="field__label">Find another artist</span>
          <span className="input-wrap">
            <Search size={17} aria-hidden="true" />
            <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Artist name" />
            {q && <button type="button" className="icon-btn icon-btn--sm" aria-label="Clear" onClick={() => setQ('')}><X size={16} /></button>}
          </span>
        </label>
        {found.length > 0 && (
          <div className="chips">
            {found.map((a) => (
              <button key={a.id} type="button" className={`chip ${artists.includes(a.name) ? 'is-on' : ''}`} onClick={() => { toggle(artists, setArtists, a.name); setQ(''); }}>
                {artists.includes(a.name) ? <Check size={15} aria-hidden="true" /> : null}{a.name}
              </button>
            ))}
          </div>
        )}
      </section>

      <div className="onboard__actions">
        <button type="button" className="btn btn--primary btn--lg" disabled={!artists.length} onClick={finish}>
          {artists.length ? `Continue with ${artists.length} artist${artists.length === 1 ? '' : 's'}` : 'Pick at least one artist'}
        </button>
        <button type="button" className="link-btn" onClick={() => { library.setTaste({ languages, onboarded: true }); onDone?.(); navigate('home'); }}>Skip for now</button>
      </div>
    </div>
  );
}
