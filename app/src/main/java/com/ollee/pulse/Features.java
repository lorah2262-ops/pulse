package com.ollee.pulse;

import android.content.Context;

/** On/off switches for each Pulse feature (all on by default). */
final class Features {

    private Features() { }

    static final String NOTIFICATIONS = "f_notif", CALLS = "f_calls", MUSIC = "f_music",
            VOLUME = "f_volume", SEARCH = "f_search", ASK = "f_ask", NAV = "f_nav", BEAT = "f_beat";

    /** key, title, what it does: shown as switches in the app */
    static final String[][] ALL = {
            {NOTIFICATIONS, "Notifications", "Messages scroll across the watch"},
            {CALLS, "Incoming calls", "CALL shakes while the phone rings"},
            {MUSIC, "Music remote", "Play, pause, skip and the song title"},
            {VOLUME, "Volume", "Change the media volume from the watch"},
            {SEARCH, "YouTube search", "Type on the watch, play on YouTube"},
            {ASK, "Ask AI by voice", "Speak a question, read Gemini's answer"},
            {NAV, "Turn-by-turn", "Next turn and distance from Google Maps"},
            {BEAT, "Real beat", "The bars move at the song's tempo"},
    };

    static boolean on(Context c, String key) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getBoolean(key, true);
    }

    static void set(Context c, String key, boolean on) {
        c.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit().putBoolean(key, on).apply();
    }
}
