package com.aurawave.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MediaNotificationReceiver extends BroadcastReceiver {
    public static final String ACTION_TOGGLE = "com.aurawave.music.ACTION_TOGGLE";
    public static final String ACTION_PLAY = "com.aurawave.music.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.aurawave.music.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.aurawave.music.ACTION_NEXT";
    public static final String ACTION_PREV = "com.aurawave.music.ACTION_PREV";
    public static final String ACTION_STOP = "com.aurawave.music.ACTION_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            MainActivity.triggerMediaAction("toggle");
        } else if (ACTION_PLAY.equals(action)) {
            MainActivity.triggerMediaAction("play");
        } else if (ACTION_PAUSE.equals(action)) {
            MainActivity.triggerMediaAction("pause");
        } else if (ACTION_STOP.equals(action)) {
            MainActivity.triggerMediaAction("pause");
            MusicPlaybackService.stop(context);
        } else if (ACTION_NEXT.equals(action)) {
            MainActivity.triggerMediaAction("next");
        } else if (ACTION_PREV.equals(action)) {
            MainActivity.triggerMediaAction("prev");
        }
    }
}
