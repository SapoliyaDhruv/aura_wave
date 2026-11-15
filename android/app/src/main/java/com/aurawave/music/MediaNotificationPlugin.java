package com.aurawave.music;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** JS bridge: the player reports what is playing; the service shows it and manages power. */
@CapacitorPlugin(name = "MediaNotification")
public class MediaNotificationPlugin extends Plugin {

    @PluginMethod
    public void update(PluginCall call) {
        String title = call.getString("title", "AuraWave");
        String artist = call.getString("artist", "");
        String coverUrl = call.getString("coverUrl", "");
        long duration = call.getInt("duration", 0);
        long position = call.getInt("position", 0);
        boolean isPlaying = Boolean.TRUE.equals(call.getBoolean("isPlaying", false));
        BackgroundAudioWebView.setPlaybackActive(isPlaying);
        MusicPlaybackService.startOrUpdate(getContext(), title, artist, coverUrl, duration, position, isPlaying);
        call.resolve();
    }

    @PluginMethod
    public void clear(PluginCall call) {
        BackgroundAudioWebView.setPlaybackActive(false);
        MusicPlaybackService.stop(getContext());
        call.resolve();
    }
}
