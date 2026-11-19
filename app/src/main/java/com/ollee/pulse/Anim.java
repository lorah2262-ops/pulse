package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Animations the watch plays frame by frame. The frames live here on the phone, so new ones
 * need no new watch face: the face only knows how to show a list of 6-character frames.
 */
final class Anim {

    private Anim() { }

    static final int MAX_FRAMES = 40;      // a line to the watch must stay under 400 characters

    static final class Clip {
        final String name;
        final String[] frames;
        final int speed;                   // watch frames (1/20 s) each picture stays up

        Clip(String name, int speed, String... frames) {
            this.name = name;
            this.speed = Math.max(1, Math.min(40, speed));
            this.frames = frames;
        }

        /** F<speed>|<loops>|<frame>|<frame>... */
        String line(int loops) {
            StringBuilder b = new StringBuilder("F").append(speed).append('|').append(Math.max(1, loops));
            for (String f : frames) b.append('|').append(f);
            return b.toString();
        }

        long ms(int loops) {
            return frames.length * speed * 50L * Math.max(1, loops);
        }
    }

    private static final Clip[] BUILT_IN = {
            new Clip("Wave", 5, "(^_^) ", "(^_^)/", "(^_^) ", "(^_^)/", "(^o^)/", "(^_^) "),
            new Clip("Dance", 5, "(^o^) ", " (^_^)", "(^o^) ", " (^_^)", "(>o<) ", " (>_<)", "(^o^) ", " (^_^)"),
            new Clip("Wink", 6, " (o_o)", " (o_o)", " (-_o)", " (o_o)", " (^_^)", " (^_^)"),
            new Clip("Pulse", 2, "_     ", "__    ", "_^_   ", "__^_  ", " __^_ ", "  __^_", "   __^", "    __",
                    "     _", "      "),
            new Clip("Streak", 2, "     =", "    =-", "   =--", "  =-- ", " =--  ", "=--   ", "--    ", "-     ", "      "),
            new Clip("Loading", 4, "[    ]", "[8   ]", "[88  ]", "[888 ]", "[8888]", "[8888]"),
    };

    static final String CUSTOM = "Mine";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    /** The built-in clips, then the user's own (if it has frames). */
    static List<Clip> all(Context c) {
        List<Clip> out = new ArrayList<>();
        for (Clip k : BUILT_IN) out.add(k);
        Clip mine = parse(CUSTOM, p(c).getString("anim_custom", ""), p(c).getInt("anim_speed", 4));
        if (mine != null) out.add(mine);
        return out;
    }

    static Clip find(Context c, String name) {
        if (name == null) return null;
        for (Clip k : all(c)) if (k.name.equalsIgnoreCase(name.trim())) return k;
        return null;
    }

    /** Frames typed one per line (or separated by |): each cut or padded to the watch's 6 digits. */
    static Clip parse(String name, String text, int speed) {
        if (text == null) return null;
        List<String> frames = new ArrayList<>();
        for (String row : text.replace("\r", "").split("[\n|]")) {
            if (row.trim().isEmpty() && frames.isEmpty()) continue;       // leading blank lines
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < row.length() && b.length() < 6; i++) {
                char ch = row.charAt(i);
                b.append(ch >= 32 && ch < 127 && ch != '|' ? ch : ' ');
            }
            while (b.length() < 6) b.append(' ');
            frames.add(b.toString());
            if (frames.size() >= MAX_FRAMES) break;
        }
        if (frames.isEmpty()) return null;
        return new Clip(name, speed, frames.toArray(new String[0]));
    }

    /** The clip played before a message pops up, or null. */
    static Clip intro(Context c) {
        return find(c, p(c).getString("anim_intro", ""));
    }
}
