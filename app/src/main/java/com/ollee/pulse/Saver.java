package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Battery-saver settings: everything that makes the watch's radio and screen work less. */
final class Saver {

    private Saver() { }

    static final String QUIET = "sv_quiet", BATCH = "sv_batch", NIGHT = "sv_night", NIGHT_FROM = "sv_from",
            NIGHT_TO = "sv_to", VOLT = "sv_volt", SECONDS = "sv_secs", ALLOW_ON = "sv_allow", ALLOW = "sv_apps",
            SEEN = "sv_seen";

    /** Batch messages: minutes to hold them (0 = send each one right away). */
    static final int[] BATCH_MIN = {0, 2, 5, 10};
    /** Watch saver mode: below which battery voltage it switches on (the last entry = always on). */
    static final String[] VOLT_NAME = {"Off", "Below 2.80 V", "Below 2.85 V", "Below 2.90 V", "Always"};
    static final float[] VOLT_AT = {0f, 2.80f, 2.85f, 2.90f, 9f};

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    /** Song changes wait until the Pulse face is on screen. */
    static boolean quiet(Context c) {
        return p(c).getBoolean(QUIET, true);
    }

    /** ms to hold messages before sending them together (0 = don't). */
    static long batchMs(Context c) {
        int i = p(c).getInt(BATCH, 0);
        return i < 0 || i >= BATCH_MIN.length ? 0 : BATCH_MIN[i] * 60000L;
    }

    static String batchName(int i) {
        return i <= 0 || i >= BATCH_MIN.length ? "Off" : "Every " + BATCH_MIN[i] + " min";
    }

    /** The idle clock shows seconds (off = it redraws once a minute). */
    static boolean seconds(Context c) {
        return p(c).getBoolean(SECONDS, false);
    }

    /** The watch should run in saver mode now: half frame rate, no beeps, still bars. */
    static boolean saverOn(Context c) {
        int i = p(c).getInt(VOLT, 0);
        if (i <= 0 || i >= VOLT_AT.length) return false;
        if (i == VOLT_AT.length - 1) return true;
        List<Battery.Reading> b = Battery.load(c);
        return !b.isEmpty() && b.get(b.size() - 1).volts < VOLT_AT[i];
    }

    // ---------- night link-off ----------

    static int from(Context c) { return p(c).getInt(NIGHT_FROM, 23); }

    static int to(Context c) { return p(c).getInt(NIGHT_TO, 6); }

    /** Inside the hours when the link is switched off. */
    static boolean isNight(Context c) {
        if (!p(c).getBoolean(NIGHT, false)) return false;
        int from = from(c), to = to(c), h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (from == to) return false;
        return from < to ? h >= from && h < to : h >= from || h < to;
    }

    /** When the night hours next start or end (ms), or 0 when night link-off is off. */
    static long nextBoundary(Context c) {
        if (!p(c).getBoolean(NIGHT, false) || from(c) == to(c)) return 0;
        long now = System.currentTimeMillis(), best = 0;
        for (int hour : new int[]{from(c), to(c)}) {
            Calendar t = Calendar.getInstance();
            t.set(Calendar.HOUR_OF_DAY, hour);
            t.set(Calendar.MINUTE, 0);
            t.set(Calendar.SECOND, 5);
            t.set(Calendar.MILLISECOND, 0);
            if (t.getTimeInMillis() <= now) t.add(Calendar.DAY_OF_YEAR, 1);
            if (best == 0 || t.getTimeInMillis() < best) best = t.getTimeInMillis();
        }
        return best;
    }

    /** 23 -> "11 PM" */
    static String hourName(int h) {
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }

    // ---------- app allow-list ----------

    /** May this app's notifications reach the watch? */
    static boolean allowed(Context c, String pkg) {
        if (!p(c).getBoolean(ALLOW_ON, false)) return true;
        Set<String> s = p(c).getStringSet(ALLOW, null);
        return s != null && s.contains(pkg);
    }

    static void allow(Context c, String pkg, boolean on) {
        Set<String> s = new HashSet<>();
        Set<String> old = p(c).getStringSet(ALLOW, null);
        if (old != null) s.addAll(old);
        if (on) s.add(pkg);
        else s.remove(pkg);
        p(c).edit().putStringSet(ALLOW, s).apply();
    }

    static int chosen(Context c) {
        Set<String> s = p(c).getStringSet(ALLOW, null);
        return s == null ? 0 : s.size();
    }

    /** Remembers an app that sent a notification, so the app can list it (newest first, 40 at most). */
    static void seen(Context c, String pkg, String label) {
        String all = p(c).getString(SEEN, "");
        if (all.startsWith(pkg + "\t")) return;                 // already the newest
        StringBuilder b = new StringBuilder(pkg).append('\t').append(label.replace('\t', ' ').replace('\n', ' ')).append('\n');
        int kept = 1;
        for (String row : all.split("\n")) {
            if (row.isEmpty() || row.startsWith(pkg + "\t")) continue;
            if (kept++ >= 40) break;
            b.append(row).append('\n');
        }
        p(c).edit().putString(SEEN, b.toString()).apply();
    }

    /** {package, name} of the apps that sent notifications lately, newest first. */
    static List<String[]> seenApps(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String row : p(c).getString(SEEN, "").split("\n")) {
            int i = row.indexOf('\t');
            if (i > 0) out.add(new String[]{row.substring(0, i), row.substring(i + 1)});
        }
        return out;
    }
}
