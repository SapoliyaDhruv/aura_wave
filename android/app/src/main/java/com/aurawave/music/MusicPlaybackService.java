package com.aurawave.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground Service for Android Media Playback.
 * Ensures the Android OS never freezes or throttles the music playback process
 * when paused on the lock screen or sleeping.
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

    public static final String CHANNEL_ID = "aurawave_music_controls";
    public static final int NOTIFICATION_ID = 2001;

    private static MusicPlaybackService instance = null;
    private MediaSessionCompat mediaSession = null;
    private PowerManager.WakeLock wakeLock = null;
    private String lastCoverUrl = "";
    private Bitmap cachedCoverBitmap = null;

    public static MusicPlaybackService getInstance() {
        return instance;
    }

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {}
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, MusicPlaybackService.class);
            intent.setAction(ACTION_STOP_SERVICE);
            context.startService(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initNotificationChannel();
        initMediaSession();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP_SERVICE.equals(action)) {
            stopForegroundService();
            return START_NOT_STICKY;
        }

        if (ACTION_START_OR_UPDATE.equals(action)) {
            String title = intent.getStringExtra(EXTRA_TITLE);
            String artist = intent.getStringExtra(EXTRA_ARTIST);
            String coverUrl = intent.getStringExtra(EXTRA_COVER_URL);
            long duration = intent.getLongExtra(EXTRA_DURATION, 180);
            long position = intent.getLongExtra(EXTRA_POSITION, 0);
            boolean isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true);

            updateForegroundNotification(
                title != null ? title : "AuraWave Music",
                artist != null ? artist : "Playing",
                coverUrl != null ? coverUrl : "",
                duration,
                position,
                isPlaying
            );
        }

        return START_STICKY;
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
                    MainActivity.triggerMediaAction("play");
                }

                @Override
                public void onPause() {
                    MainActivity.triggerMediaAction("pause");
                }

                @Override
                public void onSkipToNext() {
                    MainActivity.triggerMediaAction("next");
                }

                @Override
                public void onSkipToPrevious() {
                    MainActivity.triggerMediaAction("prev");
                }

                @Override
                public void onSeekTo(long pos) {
                    MainActivity.triggerMediaSeek(pos / 1000L);
                }

                @Override
                public void onStop() {
                    MainActivity.triggerMediaAction("pause");
                    stopForegroundService();
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
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraWave:MusicPlaybackServiceWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(12 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void updateForegroundNotification(String title, String artist, String coverUrl, long duration, long position, boolean isPlaying) {
        try {
            // Asynchronously fetch cover artwork thumbnail
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
                            updateForegroundNotification(title, artist, coverUrl, duration, position, isPlaying);
                        }
                    } catch (Exception ignored) {}
                }).start();
            }

            // Update MediaSession metadata
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

            Notification notification = builder.build();

            // Elevate process to foreground service so Android OS NEVER freezes the app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception ignored) {}
    }

    private void stopForegroundService() {
        try {
            stopForeground(true);
            if (mediaSession != null) {
                mediaSession.setActive(false);
            }
            stopSelf();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopForegroundService();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
        if (mediaSession != null) {
            try { mediaSession.release(); } catch (Exception ignored) {}
            mediaSession = null;
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
