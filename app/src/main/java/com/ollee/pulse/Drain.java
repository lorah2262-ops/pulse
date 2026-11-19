package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts what makes the watch work, hour by hour, so the app can show what eats its battery:
 * messages sent over Bluetooth, notifications, keep-alives, and time spent animating.
 */
final class Drain {

    private Drain() { }

    private static final int KEEP = 24;                 // hours kept
    static final int HOUR = 0, SENT = 1, MUSIC = 2, NOTES = 3, KEEPS = 4, ANIM_MS = 5;

    private static final ArrayList<long[]> rows = new ArrayList<>();
    private static boolean loaded;
    private static long animUntil, savedAt;

    /** A line went to the watch. Screens sent while the face is showing also wake its animation. */
    static synchronized void sent(Context c, String line, boolean faceOn) {
        long now = System.currentTimeMillis();
        long[] r = row(c, now);
        r[SENT]++;
        char k = line.isEmpty() ? ' ' : line.charAt(0);
        if (k == 'M') r[MUSIC]++;
        if (faceOn && "TVWNRhMFK".indexOf(k) >= 0) anim(r, now);
        save(c, now);
    }

    /** A keep-alive (an empty line) went to the watch. */
    static synchronized void keep(Context c) {
        long now = System.currentTimeMillis();
        long[] r = row(c, now);
        r[SENT]++;
        r[KEEPS]++;
        save(c, now);
    }

    /** A notification was passed on to the watch. */
    static synchronized void note(Context c) {
        long now = System.currentTimeMillis();
        row(c, now)[NOTES]++;
        save(c, now);
    }

    /** A button was pressed on the watch: it animates at full speed for about 10 s. */
    static synchronized void wake(Context c) {
        long now = System.currentTimeMillis();
        anim(row(c, now), now);
        save(c, now);
    }

    private static void anim(long[] r, long now) {
        long from = Math.max(now, animUntil);
        animUntil = now + 10000;
        r[ANIM_MS] += animUntil - from;
    }

    /** The hours kept, oldest first; each {hour number, sent, music, notifications, keep-alives, animated ms}. */
    static synchronized List<long[]> load(Context c) {
        row(c, System.currentTimeMillis());
        List<long[]> out = new ArrayList<>();
        for (long[] r : rows) out.add(r.clone());
        return out;
    }

    private static long[] row(Context c, long now) {
        if (!loaded) {
            loaded = true;
            for (String part : p(c).getString("drain", "").split(";")) {
                String[] f = part.split(":");
                if (f.length != 6) continue;
                try {
                    long[] r = new long[6];
                    for (int i = 0; i < 6; i++) r[i] = Long.parseLong(f[i]);
                    rows.add(r);
                } catch (NumberFormatException ignored) { }
            }
        }
        long hour = now / 3600000L;
        long[] last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        if (last == null || last[HOUR] != hour) {
            last = new long[6];
            last[HOUR] = hour;
            rows.add(last);
            while (rows.size() > KEEP) rows.remove(0);
            savedAt = 0;                                 // a new hour: write the finished one now
        }
        return last;
    }

    /** Written at most once a minute: this is called for every line sent. */
    private static void save(Context c, long now) {
        if (now - savedAt < 60000) return;
        savedAt = now;
        StringBuilder b = new StringBuilder();
        for (long[] r : rows) {
            for (int i = 0; i < 6; i++) b.append(i > 0 ? ":" : "").append(r[i]);
            b.append(';');
        }
        p(c).edit().putString("drain", b.toString()).apply();
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }
}
