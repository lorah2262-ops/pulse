package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** The watch's battery voltage readings (sent when the face opens and once an hour). */
final class Battery {

    private Battery() { }

    private static final int KEEP = 96;   // about 4 days of hourly readings

    static final class Reading {
        final long time;
        final float volts;
        Reading(long t, float v) { time = t; volts = v; }
    }

    static void add(Context c, String volts) {
        float v;
        try {
            v = Float.parseFloat(volts.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (v < 1.5f || v > 4.5f) return;
        List<Reading> all = load(c);
        long now = System.currentTimeMillis();
        // readings minutes apart (the face opened twice) replace each other
        if (!all.isEmpty() && now - all.get(all.size() - 1).time < 10 * 60 * 1000) all.remove(all.size() - 1);
        all.add(new Reading(now, v));
        while (all.size() > KEEP) all.remove(0);
        StringBuilder b = new StringBuilder();
        for (Reading r : all) b.append(r.time).append(':').append(r.volts).append(';');
        prefs(c).edit().putString("battery", b.toString()).apply();
    }

    /** A new cell went in: the old readings say nothing about it. */
    static void clear(Context c) {
        prefs(c).edit().remove("battery").apply();
    }

    static List<Reading> load(Context c) {
        List<Reading> out = new ArrayList<>();
        for (String part : prefs(c).getString("battery", "").split(";")) {
            int i = part.indexOf(':');
            if (i <= 0) continue;
            try {
                out.add(new Reading(Long.parseLong(part.substring(0, i)), Float.parseFloat(part.substring(i + 1))));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Volts lost per day over the readings kept, or NaN when there isn't a day of data yet. */
    static float perDay(List<Reading> all) {
        if (all.size() < 2) return Float.NaN;
        Reading a = all.get(0), b = all.get(all.size() - 1);
        double days = (b.time - a.time) / 86400000.0;
        if (days < 0.5) return Float.NaN;
        return (float) ((a.volts - b.volts) / days);
    }

    // ---------- forecast ----------

    static final float[] EMPTY = {2.4f, 2.5f, 2.6f, 2.7f};
    static final int[] WARN = {0, 3, 5, 7, 14};

    /** The voltage the user counts as empty. */
    static float emptyAt(Context c) {
        return EMPTY[Math.max(0, Math.min(EMPTY.length - 1, prefs(c).getInt("bat_empty", 2)))];
    }

    /** Remind when this many days are left (0 = never). */
    static int warnDays(Context c) {
        return WARN[Math.max(0, Math.min(WARN.length - 1, prefs(c).getInt("bat_warn", 2)))];
    }

    /** Days until the cell reaches "empty" at the rate it has been falling, or NaN if that can't be told yet. */
    static float daysLeft(Context c, List<Reading> all) {
        float day = perDay(all);
        if (all.isEmpty() || Float.isNaN(day) || day < 0.002f) return Float.NaN;
        return Math.max(0f, (all.get(all.size() - 1).volts - emptyAt(c)) / day);
    }

    /** A notification, once a day, when the forecast drops to the reminder's days. */
    static void remind(Context c) {
        try {
            int warn = warnDays(c);
            List<Reading> all = load(c);
            float left = daysLeft(c, all);
            if (warn == 0 || Float.isNaN(left) || left > warn) return;
            int today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
            if (prefs(c).getInt("bat_told", -1) == today) return;
            prefs(c).edit().putInt("bat_told", today).apply();
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new android.app.NotificationChannel("pulse_cell", "Watch battery",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT));
            android.app.PendingIntent open = android.app.PendingIntent.getActivity(c, 9,
                    new android.content.Intent(c, MainActivity.class),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(77, new android.app.Notification.Builder(c, "pulse_cell")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                    .setContentTitle("Buy a watch cell")
                    .setContentText(String.format(java.util.Locale.US, "About %d days left at this rate (%.2f V now)",
                            Math.round(left), all.get(all.size() - 1).volts))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) { }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }
}
