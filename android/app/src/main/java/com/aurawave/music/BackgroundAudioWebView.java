package com.aurawave.music;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import com.getcapacitor.CapacitorWebView;

/**
 * Custom WebView that prevents Chromium from pausing HTML5 audio and YouTube video
 * playback when the phone screen is turned off (sleep), locked, or the app is minimized.
 *
 * BATTERY OPTIMIZED: Only overrides visibility and prevents onPause while audio is actively playing.
 * When playback is paused or stopped, standard Android WebView lifecycle (onPause, pauseTimers,
 * and visibility GONE) takes effect, allowing Chromium and the CPU to enter true deep sleep.
 */
public class BackgroundAudioWebView extends CapacitorWebView {
    private static volatile boolean playbackActive = false;

    public static void setPlaybackActive(boolean active) {
        playbackActive = active;
    }

    public static boolean isPlaybackActive() {
        return playbackActive;
    }

    public BackgroundAudioWebView(Context context) {
        super(context, null);
    }

    public BackgroundAudioWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackgroundAudioWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (playbackActive) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        } else {
            super.onWindowVisibilityChanged(visibility);
        }
    }

    @Override
    public void dispatchWindowVisibilityChanged(int visibility) {
        if (playbackActive) {
            super.dispatchWindowVisibilityChanged(View.VISIBLE);
        } else {
            super.dispatchWindowVisibilityChanged(visibility);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        if (playbackActive) {
            super.onVisibilityChanged(changedView, View.VISIBLE);
        } else {
            super.onVisibilityChanged(changedView, visibility);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (playbackActive) {
            super.onWindowFocusChanged(true);
        } else {
            super.onWindowFocusChanged(hasWindowFocus);
        }
    }

    @Override
    public void onPause() {
        if (!playbackActive) {
            super.onPause();
        }
    }

    @Override
    public void pauseTimers() {
        if (!playbackActive) {
            super.pauseTimers();
        }
    }
}
