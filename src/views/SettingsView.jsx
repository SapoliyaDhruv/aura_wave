import { useRef } from 'react';
import { Capacitor } from '@capacitor/core';
import { Download, Upload, Moon, Trash2, Sparkles, RotateCcw } from 'lucide-react';
import { useLibrary } from '../state/library.js';
import { usePlayer } from '../state/player.jsx';
import { useRouter } from '../state/router.jsx';
import { useUI } from '../state/ui.jsx';
import { useToast } from '../state/toast.jsx';
import { library } from '../services/library.js';
import { invalidateFeed } from '../services/recommender.js';
import { plural } from '../lib/format.js';

/**
 * Saves the backup file. In the Android app (no browser downloads) it is written to the
 * app cache and handed to the system share sheet (Files, Drive, send to yourself...).
 * In a browser it downloads normally.
 */
async function shareOrDownload(data) {
  const name = `aurawave-backup-${new Date().toISOString().slice(0, 10)}.json`;
  const text = JSON.stringify(data, null, 2);
  if (Capacitor.isNativePlatform()) {
    const { Filesystem, Directory, Encoding } = await import('@capacitor/filesystem');
    const { Share } = await import('@capacitor/share');
    const { uri } = await Filesystem.writeFile({ path: name, data: text, directory: Directory.Cache, encoding: Encoding.UTF8 });
    await Share.share({ title: 'AuraWave backup', files: [uri], dialogTitle: 'Save your AuraWave backup' });
    return 'shared';
  }
  const blob = new Blob([text], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = Object.assign(document.createElement('a'), { href: url, download: name });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
  return 'downloaded';
}

function Row({ icon: Icon, title, body, children }) {
  return (
    <div className="setting">
      <Icon size={20} className="setting__icon" aria-hidden="true" />
      <div className="setting__text">
        <span className="setting__title">{title}</span>
        {body && <span className="setting__body">{body}</span>}
      </div>
      <div className="setting__control">{children}</div>
    </div>
  );
}

export default function SettingsView() {
  const lib = useLibrary();
  const player = usePlayer();
  const { navigate } = useRouter();
  const { openSheet } = useUI();
  const toast = useToast();
  const fileRef = useRef(null);
  const hiddenCount = Object.keys(lib.hidden.songs).length + Object.keys(lib.hidden.artists).length;

  const exportBackup = async () => {
    try {
      const how = await shareOrDownload(library.exportBackup());
      toast(how === 'shared' ? 'Backup created. Save it somewhere safe, like Drive or Files.' : 'Backup downloaded');
    } catch (err) {
      // Closing the share sheet without picking anything is not an error.
      if (err?.name !== 'AbortError' && !/cancel/i.test(err?.message || '')) toast('Could not create the backup file. Try again.', { tone: 'error' });
    }
  };

  const importBackup = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    try {
      library.importBackup(JSON.parse(await file.text()));
      invalidateFeed();
      toast('Backup restored. Your songs and playlists were merged in.');
    } catch (err) {
      toast(err instanceof SyntaxError ? 'That file is not a valid backup.' : err.message, { tone: 'error' });
    }
  };

  return (
    <div className="view settings">
      <header className="page-head"><h1 className="page-title">Settings</h1></header>

      <section className="settings__group" aria-labelledby="set-play">
        <h2 id="set-play" className="settings__heading">Playback</h2>
        <Row icon={Sparkles} title="Autoplay" body="Keep playing similar songs when your queue ends">
          <label className="switch">
            <input type="checkbox" checked={player.autoplay} onChange={(e) => player.setAutoplay(e.target.checked)} />
            <span className="switch__track" aria-hidden="true" />
            <span className="sr-only">Autoplay</span>
          </label>
        </Row>
        <Row icon={Moon} title="Sleep timer" body={player.sleep ? 'On' : 'Stop the music after a set time'}>
          <button type="button" className="btn btn--soft btn--sm" onClick={() => openSheet('sleep')}>{player.sleep ? 'Change' : 'Set timer'}</button>
        </Row>
      </section>

      <section className="settings__group" aria-labelledby="set-taste">
        <h2 id="set-taste" className="settings__heading">Recommendations</h2>
        <Row icon={Sparkles} title="Your artists and languages" body={lib.taste.artists.length ? lib.taste.artists.slice(0, 4).join(', ') + (lib.taste.artists.length > 4 ? ` and ${lib.taste.artists.length - 4} more` : '') : 'Not set'}>
          <button type="button" className="btn btn--soft btn--sm" onClick={() => navigate('onboarding')}>Edit</button>
        </Row>
        <Row icon={RotateCcw} title="Hidden songs and artists" body={hiddenCount ? `${plural(hiddenCount, 'item')} kept out of recommendations` : 'Nothing hidden'}>
          <button type="button" className="btn btn--soft btn--sm" disabled={!hiddenCount} onClick={() => { library.resetHidden(); invalidateFeed(); toast('Hidden songs and artists can be recommended again'); }}>Reset</button>
        </Row>
      </section>

      <section className="settings__group" aria-labelledby="set-data">
        <h2 id="set-data" className="settings__heading">Your data</h2>
        <p className="settings__note">Everything is stored on this device. Back it up before reinstalling the app or switching phones.</p>
        <Row icon={Download} title="Back up library" body={`${plural(lib.liked.length, 'liked song')}, ${plural(lib.playlists.length, 'playlist')}`}>
          <button type="button" className="btn btn--soft btn--sm" onClick={exportBackup}>Back up</button>
        </Row>
        <Row icon={Upload} title="Restore from backup" body="Merges songs and playlists from a backup file">
          <button type="button" className="btn btn--soft btn--sm" onClick={() => fileRef.current?.click()}>Choose file</button>
          <input ref={fileRef} type="file" accept="application/json,.json" hidden onChange={importBackup} />
        </Row>
        <Row icon={Trash2} title="Listening history" body={`${plural(lib.history.length, 'play')} recorded`}>
          <button type="button" className="btn btn--soft btn--sm" disabled={!lib.history.length} onClick={() => openSheet('confirm', { title: 'Clear listening history?', body: 'This also resets play counts used for recommendations. Liked songs and playlists are kept.', confirmLabel: 'Clear history', onConfirm: () => { library.clearHistory(); invalidateFeed(); toast('History cleared'); } })}>Clear</button>
        </Row>
      </section>

      <p className="settings__about">AuraWave plays official audio from YouTube Music. Synced lyrics come from LRCLIB.</p>
    </div>
  );
}
