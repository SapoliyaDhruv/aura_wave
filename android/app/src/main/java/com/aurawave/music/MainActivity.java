package com.aurawave.music;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.BridgeActivity;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends BridgeActivity {
    public static final String CHANNEL_ID = "aurawave_media_channel";
    public static final int NOTIFICATION_ID = 2026;

    private static MainActivity instance;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;
    private String lastCoverUrl = "";
    private Bitmap cachedCoverBitmap = null;

    private AudioManager audioManager;
    private BroadcastReceiver noisyReceiver;
    private boolean isPlayingTrack = false;
    private boolean wasPlayingBeforeCall = false;

    private final Handler callCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable callCheckRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (audioManager == null) {
                    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                }
                if (audioManager != null) {
                    int mode = audioManager.getMode();
                    boolean inCall = (mode == AudioManager.MODE_RINGTONE ||
                                      mode == AudioManager.MODE_IN_CALL ||
                                      mode == AudioManager.MODE_IN_COMMUNICATION);

                    if (inCall && isPlayingTrack) {
                        // Phone call incoming/ringing or active (Cellular, WhatsApp, VoIP): pause music immediately!
                        wasPlayingBeforeCall = true;
                        triggerMediaAction("pause");
                    } else if (!inCall && wasPlayingBeforeCall) {
                        // Phone call ended: auto-resume music smoothly!
                        wasPlayingBeforeCall = false;
                        triggerMediaAction("play");
                    }
                }
            } catch (Exception ignored) {}

            if (isPlayingTrack || wasPlayingBeforeCall) {
                callCheckHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaNotificationPlugin.class);
        super.onCreate(savedInstanceState);
        instance = this;

        // Dark system bar styling
        try {
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.parseColor("#0a0a0c"));
                window.setNavigationBarColor(Color.parseColor("#0a0a0c"));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decor = window.getDecorView();
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Exception ignored) {}

        // Request notification permission on Android 13+ (POST_NOTIFICATIONS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
                }
            }
        } catch (Exception ignored) {}

        initNotificationChannel();
        initMediaSession();
        initAudioControls();
        configureWebView();
        acquireWakeLock();
    }

    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "AuraWave Music Controls",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Spotify-style lock-screen and status-bar media playback controls");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void initAudioControls() {
        try {
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            // Becoming Noisy: automatically pause if headphones or Bluetooth earbuds disconnect
            noisyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                        triggerMediaAction("pause");
                    }
                }
            };
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception ignored) {}
    }

    private void initMediaSession() {
        try {
            mediaSession = new MediaSessionCompat(this, "AuraWaveMediaSession");
            mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            );

            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    wasPlayingBeforeCall = false;
                    triggerMediaAction("play");
                }

                @Override
                public void onPause() {
                    wasPlayingBeforeCall = false;
                    triggerMediaAction("pause");
                }

                @Override
                public void onSkipToNext() {
                    triggerMediaAction("next");
                }

                @Override
                public void onSkipToPrevious() {
                    triggerMediaAction("prev");
                }

                @Override
                public void onSeekTo(long pos) {
                    triggerMediaSeek(pos / 1000L);
                }

                @Override
                public void onStop() {
                    wasPlayingBeforeCall = false;
                    triggerMediaAction("pause");
                }
            });

            mediaSession.setActive(true);
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraWave:MusicCpuWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(12 * 60 * 60 * 1000L); // 12-hour timeout
            }
        } catch (Exception ignored) {}
    }

    private void configureWebView() {
        try {
            if (this.bridge != null && this.bridge.getWebView() != null) {
                WebView webView = this.bridge.getWebView();
                WebSettings settings = webView.getSettings();

                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(true);

                // Clean User-Agent: Strip "; wv" and "Version/4.0 " so YouTube servers treat it as mobile Chrome
                String ua = settings.getUserAgentString();
                if (ua != null) {
                    String cleanUa = ua.replace("; wv", "").replace("Version/4.0 ", "");
                    settings.setUserAgentString(cleanUa);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void updateMediaNotification(String title, String artist, String coverUrl, long duration, long position, boolean isPlaying) {
        if (instance == null) return;
        instance.runOnUiThread(() -> instance.renderNotification(title, artist, coverUrl, duration, position, isPlaying));
    }

    public static void clearMediaNotification() {
        if (instance == null) return;
        try {
            NotificationManagerCompat.from(instance).cancel(NOTIFICATION_ID);
            instance.isPlayingTrack = false;
            instance.wasPlayingBeforeCall = false;
            instance.callCheckHandler.removeCallbacks(instance.callCheckRunnable);
        } catch (Exception ignored) {}
    }

    private void renderNotification(String title, String artist, String coverUrl, long duration, long position, boolean isPlaying) {
        try {
            this.isPlayingTrack = isPlaying;
            if (isPlaying) {
                callCheckHandler.removeCallbacks(callCheckRunnable);
                callCheckHandler.post(callCheckRunnable);
            }

            // Asynchronously fetch album artwork thumbnail if available and not cached
            if (coverUrl != null && !coverUrl.isEmpty() && !coverUrl.equals(lastCoverUrl)) {
                lastCoverUrl = coverUrl;
                new Thread(() -> {
                    try {
                        URL url = new URL(coverUrl);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setDoInput(true);
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.connect();
                        InputStream input = conn.getInputStream();
                        Bitmap bmp = BitmapFactory.decodeStream(input);
                        if (bmp != null) {
                            cachedCoverBitmap = bmp;
                            runOnUiThread(() -> renderNotification(title, artist, coverUrl, duration, position, isPlaying));
                        }
                    } catch (Exception ignored) {}
                }).start();
            }

            // 1. Update MediaSession metadata & dynamic timeline
            if (mediaSession != null) {
                MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "AuraWave")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration * 1000L : 180000L);

                if (cachedCoverBitmap != null) {
                    meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cachedCoverBitmap);
                }
                mediaSession.setMetadata(meta.build());

                long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                               PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                               PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO |
                               PlaybackStateCompat.ACTION_STOP;
                int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
                float playbackSpeed = isPlaying ? 1.0f : 0.0f;
                PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, position * 1000L, playbackSpeed);
                mediaSession.setPlaybackState(stateBuilder.build());
            }

            // 2. Prepare pending intents
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            Intent openApp = new Intent(this, MainActivity.class);
            openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, flags);

            Intent prevIntent = new Intent(this, MediaNotificationReceiver.class);
            prevIntent.setAction(MediaNotificationReceiver.ACTION_PREV);
            PendingIntent prevPending = PendingIntent.getBroadcast(this, 1, prevIntent, flags);

            Intent toggleIntent = new Intent(this, MediaNotificationReceiver.class);
            toggleIntent.setAction(MediaNotificationReceiver.ACTION_TOGGLE);
            PendingIntent togglePending = PendingIntent.getBroadcast(this, 2, toggleIntent, flags);

            Intent nextIntent = new Intent(this, MediaNotificationReceiver.class);
            nextIntent.setAction(MediaNotificationReceiver.ACTION_NEXT);
            PendingIntent nextPending = PendingIntent.getBroadcast(this, 3, nextIntent, flags);

            Intent stopIntent = new Intent(this, MediaNotificationReceiver.class);
            stopIntent.setAction(MediaNotificationReceiver.ACTION_STOP);
            PendingIntent stopPending = PendingIntent.getBroadcast(this, 4, stopIntent, flags);

            int playPauseIcon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow;
            String playPauseLabel = isPlaying ? "Pause" : "Play";

            androidx.media.app.NotificationCompat.MediaStyle mediaStyle = new androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);

            if (mediaSession != null) {
                mediaStyle.setMediaSession(mediaSession.getSessionToken());
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText("AuraWave Music")
                .setContentIntent(contentIntent)
                .setDeleteIntent(stopPending)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setStyle(mediaStyle)
                .addAction(R.drawable.ic_skip_previous, "Previous", prevPending)
                .addAction(playPauseIcon, playPauseLabel, togglePending)
                .addAction(R.drawable.ic_skip_next, "Next", nextPending);

            if (cachedCoverBitmap != null) {
                builder.setLargeIcon(cachedCoverBitmap);
            }

            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {}
    }

    public static void triggerMediaAction(String action) {
        if (instance != null && instance.bridge != null && instance.bridge.getWebView() != null) {
            instance.runOnUiThread(() -> {
                try {
                    String fn = "togglePlay";
                    if ("next".equals(action)) fn = "next";
                    else if ("prev".equals(action)) fn = "prev";
                    else if ("play".equals(action)) fn = "play";
                    else if ("pause".equals(action)) fn = "pause";
                    String js = "if (window.AuraWaveAudio && typeof window.AuraWaveAudio." + fn + " === 'function') { window.AuraWaveAudio." + fn + "(); }";
                    instance.bridge.getWebView().evaluateJavascript(js, null);
                } catch (Exception ignored) {}
            });
        }
    }

    public static void triggerMediaSeek(long seconds) {
        if (instance != null && instance.bridge != null && instance.bridge.getWebView() != null) {
            instance.runOnUiThread(() -> {
                try {
                    String js = "if (window.AuraWaveAudio && typeof window.AuraWaveAudio.seek === 'function') { window.AuraWaveAudio.seek(" + seconds + "); }";
                    instance.bridge.getWebView().evaluateJavascript(js, null);
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        acquireWakeLock();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        clearMediaNotification();
        callCheckHandler.removeCallbacks(callCheckRunnable);
        if (noisyReceiver != null) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {}
            noisyReceiver = null;
        }
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Exception ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
