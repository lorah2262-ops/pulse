package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * School, Home and Sleep: each one is a saved set of the feature switches and the battery
 * savers. One tap puts a set in place; each can also start by itself at an hour of the day.
 */
final class Profiles {

    private Profiles() { }

    static final String[] NAMES = {"School", "Home", "Sleep"};
    private static final String[] BOOLS = {Features.NOTIFICATIONS, Features.CALLS, Features.MUSIC, Features.VOLUME,
            Features.SEARCH, Features.ASK, Features.NAV, Features.BEAT, Saver.QUIET, Saver.SECONDS, Saver.NIGHT,
            Saver.ALLOW_ON};
    private static final String[] INTS = {Saver.BATCH, Saver.VOLT};
    private static final String[] START = {
            "f_notif=1;f_calls=1;f_music=0;f_volume=0;f_search=0;f_ask=1;f_nav=0;f_beat=0;sv_quiet=1;sv_secs=0;sv_night=0;sv_batch=2",
            "f_notif=1;f_calls=1;f_music=1;f_volume=1;f_search=1;f_ask=1;f_nav=1;f_beat=1;sv_quiet=1;sv_secs=0;sv_night=0;sv_batch=0",
            "f_notif=0;f_calls=1;f_music=0;f_volume=0;f_search=0;f_ask=0;f_nav=0;f_beat=0;sv_quiet=1;sv_secs=0;sv_batch=3"};

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    /** The profile last put in place, or -1. */
    static int current(Context c) {
        return p(c).getInt("prof_now", -1);
    }

    /** The hour a profile starts by itself, or -1. */
    static int at(Context c, int i) {
        return p(c).getInt("prof_at_" + i, -1);
    }

    static void at(Context c, int i, int hour) {
        p(c).edit().putInt("prof_at_" + i, hour).apply();
    }

    private static boolean bool(Context c, String key) {
        if (key.equals(Saver.QUIET)) return Saver.quiet(c);
        if (key.equals(Saver.SECONDS)) return Saver.seconds(c);
        if (key.startsWith("f_")) return Features.on(c, key);
        return p(c).getBoolean(key, false);
    }

    /** Saves the switches as they are now into a profile. */
    static void save(Context c, int i) {
        StringBuilder b = new StringBuilder();
        for (String k : BOOLS) b.append(k).append('=').append(bool(c, k) ? 1 : 0).append(';');
        for (String k : INTS) b.append(k).append('=').append(p(c).getInt(k, 0)).append(';');
        p(c).edit().putString("prof_" + i, b.toString()).putInt("prof_now", i).apply();
    }

    /** Puts a profile's switches in place. */
    static void apply(Context c, int i) {
        SharedPreferences.Editor e = p(c).edit();
        for (String part : p(c).getString("prof_" + i, START[i]).split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 1);
            boolean isInt = false;
            for (String n : INTS) if (n.equals(k)) isInt = true;
            try {
                if (isInt) e.putInt(k, Integer.parseInt(v));
                else e.putBoolean(k, "1".equals(v));
            } catch (NumberFormatException ignored) { }
        }
        e.putInt("prof_now", i).apply();
        NotifService.saverChanged();
        NotifService.profileFaces(c, i);
    }

    /** Starts the profile whose hour this is, once. True if one was put in place. */
    static boolean auto(Context c) {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY), stamp = now.get(Calendar.DAY_OF_YEAR) * 100 + hour;
        if (p(c).getInt("prof_done", -1) == stamp) return false;
        for (int i = 0; i < NAMES.length; i++) {
            if (at(c, i) != hour) continue;
            p(c).edit().putInt("prof_done", stamp).apply();
            apply(c, i);
            return true;
        }
        return false;
    }

    /** When the next profile starts by itself, or 0 if none is scheduled. */
    static long next(Context c) {
        long best = 0;
        for (int i = 0; i < NAMES.length; i++) {
            int hour = at(c, i);
            if (hour < 0) continue;
            Calendar t = Calendar.getInstance();
            t.set(Calendar.MINUTE, 0);
            t.set(Calendar.SECOND, 5);
            t.set(Calendar.MILLISECOND, 0);
            t.set(Calendar.HOUR_OF_DAY, hour);
            if (t.getTimeInMillis() <= System.currentTimeMillis()) t.add(Calendar.DAY_OF_YEAR, 1);
            if (best == 0 || t.getTimeInMillis() < best) best = t.getTimeInMillis();
        }
        return best;
    }
}
