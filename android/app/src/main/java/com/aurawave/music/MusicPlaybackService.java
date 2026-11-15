package com.aurawave.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.media.AudioAttributesCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Owns everything about background playback, the way native music players do:
 *
 *  - One MediaSession and one notification (lock screen, Bluetooth, headset buttons).
 *  - Foreground service and a CPU wake lock ONLY while music is playing. When paused,
 *    both are released and the notification stays (dismissible) so the phone can sleep.
 *  - Real audio focus: phone calls, navigation prompts and other apps pause the music
 *    (and short interruptions resume it) without polling.
 *  - Pauses when headphones are unplugged.
 *  - Not sticky: if the system kills the app, nothing restarts in the background.
 */
public class MusicPlaybackService extends Service {
    public static final String ACTION_START_OR_UPDATE = "com.aurawave.music.ACTION_START_OR_UPDATE";
    public static final String ACTION_STOP_SERVICE = "com.aurawave.music.ACTION_STOP_SERVICE";

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_COVER_URL = "coverUrl";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_IS_PLAYING = "isPlaying";

    public static final String CHANNEL_ID = "aurawave_playback";
    public static final int NOTIFICATION_ID = 2001;

    /** Wake lock is renewed on every update while playing; this caps it if updates ever stop. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1000L;

    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequestCompat focusRequest;
    private boolean hasFocus = false;
    private boolean pausedForTransientLoss = false;
    private boolean noisyRegistered = false;
    private boolean isForeground = false;

    private String title = "AuraWave";
    private String artist = "";
    private String coverUrl = "";
    private long durationSec = 0;
    private long positionSec = 0;
    private boolean isPlaying = false;

    private String loadedCoverUrl = "";
    private Bitmap coverBitmap;
    private String lastRenderedKey = "";
    private Notification lastNotification;

    private final Handler main = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                clearTransientPause();
                MainActivity.triggerMediaAction("pause");
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (pausedForTransientLoss) {
                    pausedForTransientLoss = false;
                    MainActivity.triggerMediaAction("play");
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Phone call, voice assistant, navigation prompt: pause and resume afterwards.
                if (isPlaying) {
                    pausedForTransientLoss = true;
                    MainActivity.triggerMediaAction("pause");
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app started playing: stop for good, like any well-behaved player.
                hasFocus = false;
                clearTransientPause();
                MainActivity.triggerMediaAction("pause");
                break;
            default:
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system lowers our volume automatically.
                break;
        }
    };

    // ---------- static entry points used by the Capacitor plugin ----------

    public static void startOrUpdate(Context context, String title, String artist, String coverUrl, long duration, long position, boolean isPlaying) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, MusicPlaybackService.class);
            intent.setAction(ACTION_START_OR_UPDATE);
            intent.putExtra(EXTRA_TITLE, title);
            intent.putExtra(EXTRA_ARTIST, artist);
            intent.putExtra(EXTRA_COVER_URL, coverUrl);
            intent.putExtra(EXTRA_DURATION, duration);
            intent.putExtra(EXTRA_POSITION, position);
            intent.putExtra(EXTRA_IS_PLAYING, isPlaying);
            if (isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                // Paused updates never need foreground status.
                context.startService(intent);
            }
        } catch (Exception ignored) {
            // Starting a service from the background can be refused; the next foreground update retries.
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, MusicPlaybackService.class);
            intent.setAction(ACTION_STOP_SERVICE);
            context.startService(intent);
        } catch (Exception ignored) {}
    }

    // ---------- lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
        createSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_SERVICE.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_START_OR_UPDATE.equals(intent.getAction())) {
            String t = intent.getStringExtra(EXTRA_TITLE);
            String a = intent.getStringExtra(EXTRA_ARTIST);
            String c = intent.getStringExtra(EXTRA_COVER_URL);
            title = t != null ? t : "AuraWave";
            artist = a != null ? a : "";
            coverUrl = c != null ? c : "";
            durationSec = intent.getLongExtra(EXTRA_DURATION, 0);
            positionSec = intent.getLongExtra(EXTRA_POSITION, 0);
            boolean playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, false);
            applyPlaybackState(playing);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped away from recents: if nothing is playing, leave no trace behind.
        if (!isPlaying) shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        abandonFocus();
        unregisterNoisy();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- playback state ----------

    private void applyPlaybackState(boolean playing) {
        isPlaying = playing;
        if (playing) {
            requestFocus();
            acquireWakeLock();
            registerNoisy();
        } else {
            releaseWakeLock();
            unregisterNoisy();
            // Keep focus while a call has us paused, so we hear when it ends.
            if (!pausedForTransientLoss) abandonFocus();
        }
        loadCoverIfNeeded();
        updateSession();
        render();
    }

    /** The listener (or another app) took over during an interruption: stop waiting to resume. */
    private void clearTransientPause() {
        if (!pausedForTransientLoss) return;
        pausedForTransientLoss = false;
        if (!isPlaying) applyPlaybackState(false); // drops foreground status and audio focus
    }

    /**
     * Foreground while playing, and also while paused by a call or other short interruption,
     * so resuming afterwards doesn't need a background service start (blocked on Android 12+).
     */
    private boolean keepForeground() {
        return isPlaying || pausedForTransientLoss;
    }

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        if (focusRequest == null) {
            AudioAttributesCompat attrs = new AudioAttributesCompat.Builder()
                .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                .build();
            focusRequest = new AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build();
        }
        hasFocus = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (focusRequest != null && audioManager != null) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest);
        }
        hasFocus = false;
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraWave:playback");
                wakeLock.setReferenceCounted(false);
            }
            // Renewed on every playing update (song change, seek); expires on its own if updates stop.
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        try {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        } catch (Exception ignored) {}
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {}
        noisyRegistered = false;
    }

    private void shutdown() {
        isPlaying = false;
        pausedForTransientLoss = false;
        releaseWakeLock();
        abandonFocus();
        unregisterNoisy();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        isForeground = false;
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    // ---------- media session & notification ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows what is playing with playback controls");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            // Remove the duplicate channel used by older app versions.
            manager.deleteNotificationChannel("aurawave_media_channel");
            manager.deleteNotificationChannel("aurawave_music_controls");
        }
    }

    private void createSession() {
        mediaSession = new MediaSessionCompat(this, "AuraWave");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { pausedForTransientLoss = false; MainActivity.triggerMediaAction("play"); }
            @Override public void onPause() { clearTransientPause(); MainActivity.triggerMediaAction("pause"); }
            @Override public void onSkipToNext() { MainActivity.triggerMediaAction("next"); }
            @Override public void onSkipToPrevious() { MainActivity.triggerMediaAction("prev"); }
            @Override public void onSeekTo(long pos) { MainActivity.triggerMediaSeek(pos / 1000L); }
            @Override public void onStop() { MainActivity.triggerMediaAction("pause"); shutdown(); }
        });
        mediaSession.setActive(true);
    }

    private void updateSession() {
        if (mediaSession == null) return;
        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec > 0 ? durationSec * 1000L : -1L);
        if (coverBitmap != null) meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap);
        mediaSession.setMetadata(meta.build());

        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_STOP;
        // The system extrapolates the position from (position, speed, time), so no periodic updates are needed.
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, positionSec * 1000L, isPlaying ? 1f : 0f)
            .build());
    }

    private void loadCoverIfNeeded() {
        if (coverUrl.isEmpty() || coverUrl.equals(loadedCoverUrl)) return;
        final String url = coverUrl;
        loadedCoverUrl = url;
        new Thread(() -> {
            Bitmap bmp = downloadBitmap(url);
            main.post(() -> {
                if (!url.equals(coverUrl)) return; // song changed meanwhile
                coverBitmap = bmp;
                lastRenderedKey = "";
                updateSession();
                render();
            });
        }, "AuraWave-cover").start();
    }

    private static Bitmap downloadBitmap(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try (InputStream in = conn.getInputStream()) {
                Bitmap raw = BitmapFactory.decodeStream(in);
                if (raw == null) return null;
                int max = 320; // plenty for the lock screen; keeps memory small
                if (raw.getWidth() <= max && raw.getHeight() <= max) return raw;
                float scale = (float) max / Math.max(raw.getWidth(), raw.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(raw, Math.round(raw.getWidth() * scale), Math.round(raw.getHeight() * scale), true);
                if (scaled != raw) raw.recycle();
                return scaled;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void render() {
        // Skip rebuilding the notification when nothing visible changed.
        String key = title + "|" + artist + "|" + isPlaying + "|" + (coverBitmap != null);
        if (key.equals(lastRenderedKey) && lastNotification != null && isForeground == keepForeground()) {
            // Every startForegroundService() must be answered with startForeground(), even if unchanged.
            if (isPlaying) startForegroundCompat(lastNotification);
            return;
        }
        lastRenderedKey = key;

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setDeleteIntent(broadcast(MediaNotificationReceiver.ACTION_STOP, 4))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(keepForeground()) // swipe to dismiss when paused
            .addAction(R.drawable.ic_skip_previous, "Previous", broadcast(MediaNotificationReceiver.ACTION_PREV, 1))
            .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow, isPlaying ? "Pause" : "Play", broadcast(MediaNotificationReceiver.ACTION_TOGGLE, 2))
            .addAction(R.drawable.ic_skip_next, "Next", broadcast(MediaNotificationReceiver.ACTION_NEXT, 3))
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        if (coverBitmap != null) builder.setLargeIcon(coverBitmap);
        Notification notification = builder.build();
        lastNotification = notification;

        try {
            if (keepForeground()) {
                startForegroundCompat(notification);
            } else {
                // Paused: drop foreground status so Android can manage the app normally, keep the controls.
                if (isForeground) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
                    isForeground = false;
                }
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS denied: playback still works, just without the notification.
        } catch (Exception ignored) {}
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForeground = true;
        } catch (Exception ignored) {}
    }

    private PendingIntent broadcast(String action, int requestCode) {
        Intent intent = new Intent(this, MediaNotificationReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
