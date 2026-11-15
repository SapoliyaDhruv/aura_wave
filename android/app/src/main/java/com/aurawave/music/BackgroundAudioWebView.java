package com.aurawave.music;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import com.getcapacitor.CapacitorWebView;

/**
 * Custom WebView that prevents Chromium from pausing HTML5 audio and YouTube video
 * playback when the phone screen is turned off (sleep), locked, or the app is minimized.
 */
public class BackgroundAudioWebView extends CapacitorWebView {

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
        // Intercept window hidden state and force VISIBLE so Chromium never pauses media playback
        super.onWindowVisibilityChanged(View.VISIBLE);
    }

    @Override
    public void dispatchWindowVisibilityChanged(int visibility) {
        // Intercept window visibility dispatch down the view tree and force VISIBLE
        super.dispatchWindowVisibilityChanged(View.VISIBLE);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        // Keep view marked as VISIBLE to Chromium's RenderWidgetHostViewAndroid
        super.onVisibilityChanged(changedView, View.VISIBLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        // Always report window focus as true so background playback and track transitions are never throttled
        super.onWindowFocusChanged(true);
    }

    @Override
    public void onPause() {
        // Crucial: standard WebView.onPause() tells Chromium to pause all media and timers.
        // We override this to prevent background audio interruption while keeping resumeTimers active.
    }

    @Override
    public void pauseTimers() {
        // Do NOT pause timers when app is locked/sleeping so audio pipelines and timers remain active
    }
}
