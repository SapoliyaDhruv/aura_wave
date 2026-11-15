package com.aurawave.music;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MediaNotification")
public class MediaNotificationPlugin extends Plugin {

    @PluginMethod
    public void update(PluginCall call) {
        String title = call.getString("title", "AuraWave Music");
        String artist = call.getString("artist", "Playing");
        String coverUrl = call.getString("coverUrl", "");
        long duration = call.getInt("duration", 180);
        long position = call.getInt("position", 0);
        boolean isPlaying = Boolean.TRUE.equals(call.getBoolean("isPlaying", true));

        MusicPlaybackService.startOrUpdate(getContext(), title, artist, coverUrl, duration, position, isPlaying);
        MainActivity.updateMediaNotification(title, artist, coverUrl, duration, position, isPlaying);
        call.resolve();
    }

    @PluginMethod
    public void clear(PluginCall call) {
        MusicPlaybackService.stop(getContext());
        MainActivity.clearMediaNotification();
        call.resolve();
    }
}
