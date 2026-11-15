package com.aurawave.music;

import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

/**
 * Hosts the web app. Background playback (notification, media session, audio focus,
 * wake lock) lives entirely in {@link MusicPlaybackService}; this class only styles the
 * window, configures the WebView and forwards media-button actions to the JS player.
 */
public class MainActivity extends BridgeActivity {
    private static MainActivity instance;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaNotificationPlugin.class);
        super.onCreate(savedInstanceState);
        instance = this;

        try {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor("#14101b"));
            window.setNavigationBarColor(Color.parseColor("#14101b"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decor = window.getDecorView();
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Exception ignored) {}

        // Android 13+: ask once for permission to show the playback notification.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        } catch (Exception ignored) {}

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        configureWebView();
    }

    private void configureWebView() {
        try {
            if (this.bridge == null || this.bridge.getWebView() == null) return;
            WebView webView = this.bridge.getWebView();
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);

            // Present as mobile Chrome so YouTube serves the regular embedded player.
            String ua = settings.getUserAgentString();
            if (ua != null) settings.setUserAgentString(ua.replace("; wv", "").replace("Version/4.0 ", ""));
        } catch (Exception ignored) {}
    }

    /** Forwards a media action (play, pause, toggle, next, prev) to the JS player. */
    public static void triggerMediaAction(String action) {
        final MainActivity activity = instance;
        if (activity == null || activity.bridge == null || activity.bridge.getWebView() == null) return;
        String fn;
        switch (action) {
            case "next": fn = "next"; break;
            case "prev": fn = "prev"; break;
            case "play": fn = "play"; break;
            case "pause": fn = "pause"; break;
            default: fn = "togglePlay";
        }
        final String js = "window.AuraWaveAudio && window.AuraWaveAudio." + fn + " && window.AuraWaveAudio." + fn + "();";
        activity.runOnUiThread(() -> {
            try { activity.bridge.getWebView().evaluateJavascript(js, null); } catch (Exception ignored) {}
        });
    }

    public static void triggerMediaSeek(long seconds) {
        final MainActivity activity = instance;
        if (activity == null || activity.bridge == null || activity.bridge.getWebView() == null) return;
        final String js = "window.AuraWaveAudio && window.AuraWaveAudio.seek && window.AuraWaveAudio.seek(" + seconds + ");";
        activity.runOnUiThread(() -> {
            try { activity.bridge.getWebView().evaluateJavascript(js, null); } catch (Exception ignored) {}
        });
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        // Activity gone (e.g. swiped away): the JS player is gone too, so drop the notification.
        MusicPlaybackService.stop(this);
        super.onDestroy();
    }
}
