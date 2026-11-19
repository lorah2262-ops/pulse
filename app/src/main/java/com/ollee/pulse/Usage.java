package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.TimeZone;

/** How many requests went to Gemini today, against the daily limit of the free key. */
final class Usage {

    private Usage() { }

    static final int[] LIMITS = {20, 50, 100, 250, 500, 1000, 1500};

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    /** Google counts a day in Pacific time. */
    private static int day() {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
        return now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR);
    }

    private static void roll(Context c) {
        if (p(c).getInt("gem_day", 0) != day()) {
            p(c).edit().putInt("gem_day", day()).putInt("gem_n", 0).putInt("gem_hit", 0).apply();
        }
    }

    /** One more request sent. */
    static synchronized void count(Context c) {
        roll(c);
        p(c).edit().putInt("gem_n", p(c).getInt("gem_n", 0) + 1).apply();
    }

    /** Google answered "limit reached" (HTTP 429). */
    static synchronized void hit(Context c) {
        roll(c);
        if (p(c).getInt("gem_hit", 0) == 0) p(c).edit().putInt("gem_hit", Math.max(1, p(c).getInt("gem_n", 0))).apply();
    }

    static synchronized int today(Context c) {
        roll(c);
        return p(c).getInt("gem_n", 0);
    }

    /** The count at which Google first said "limit reached" today, or 0. */
    static synchronized int hitAt(Context c) {
        roll(c);
        return p(c).getInt("gem_hit", 0);
    }

    static int limit(Context c) {
        return LIMITS[Math.max(0, Math.min(LIMITS.length - 1, p(c).getInt("gem_limit", 3)))];
    }
}
