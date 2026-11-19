package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

/** Keyword rules for notifications, on top of the list of chosen apps. */
final class Rules {

    private Rules() { }

    static final String GROUPS = "rule_groups", NAMES = "rule_names", BLOCK = "rule_block";
    static final int ALL = 0, NAME = 1, WORDS = 2, MUTE = 3;
    static final String[] MODE = {"Everything", "Only with my name", "Only with these words…", "Mute this app"},
            SHORT = {"All", "My name", "Words", "Muted"};

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    static int mode(Context c, String pkg) {
        return Math.max(0, Math.min(3, p(c).getInt("rule_" + pkg, ALL)));
    }

    static void mode(Context c, String pkg, int m) {
        p(c).edit().putInt("rule_" + pkg, m).apply();
    }

    static String words(Context c, String pkg) {
        return p(c).getString("rulew_" + pkg, "");
    }

    static void words(Context c, String pkg, String w) {
        p(c).edit().putString("rulew_" + pkg, w.trim()).apply();
    }

    /** Is any of these comma-separated words in the text? An empty list matches nothing. */
    private static boolean any(String list, String hay) {
        for (String w : list.toLowerCase().split(",")) {
            String t = w.trim();
            if (!t.isEmpty() && hay.contains(t)) return true;
        }
        return false;
    }

    /** May this message go to the watch? group = it came from a group chat. */
    static boolean pass(Context c, String pkg, String who, String text, boolean group) {
        SharedPreferences p = p(c);
        String hay = (who + " " + text).toLowerCase();
        if (any(p.getString(BLOCK, ""), hay)) return false;
        boolean named = any(p.getString(NAMES, ""), text.toLowerCase());
        switch (mode(c, pkg)) {
            case MUTE: return false;
            case NAME: if (!named) return false; break;
            case WORDS:
                String w = words(c, pkg);
                if (!w.trim().isEmpty() && !any(w, hay)) return false;
                break;
            default:
        }
        return !(group && p.getBoolean(GROUPS, false) && !named);
    }
}
