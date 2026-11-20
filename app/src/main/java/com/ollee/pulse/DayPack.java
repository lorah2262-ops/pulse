package com.ollee.pulse;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Day pack: a little before your next calendar event, Pulse connects, backs up the face in
 * Forge A, writes a face with the coming events baked in (clock, countdown, reminder beeps) and
 * lets the link go again. After the last event of that stretch it puts your own face back.
 * Between those two moments the watch needs no Bluetooth at all.
 *
 *   plan()   works out the next moment something has to happen and sets one alarm for it
 *   run()    the alarm: install, refresh, or restore, then plan() again
 */
final class DayPack {

    private DayPack() { }

    static final String NAME = "Day Pack", ACTION = "com.ollee.pulse.DAYPACK";
    static final int MAX_EVENTS = 12;
    static final long MIN = 60_000L, HOUR = 3_600_000L, JOIN_GAP = 2 * HOUR, AFTER = 5 * MIN;

    /** True while Pulse holds the link up for the day pack (night link-off waits). */
    static volatile boolean holding;
    private static boolean working, looking;
    private static final Handler h = new Handler(Looper.getMainLooper());

    // ---------- settings ----------

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("pulse", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return p(c).getBoolean("dp_on", false); }
    static int lead(Context c) { return p(c).getInt("dp_lead", 30); }        // minutes before the first event
    static int remind(Context c) { return p(c).getInt("dp_remind", 10); }    // minutes before each event the watch beeps
    static boolean installed(Context c) { return p(c).getBoolean("dp_in", false); }
    static String status(Context c) { return p(c).getString("dp_status", "Off"); }

    static void setOn(Context c, boolean on) {
        p(c).edit().putBoolean("dp_on", on).apply();
        plan(c);
    }

    static void setLead(Context c, int m) { p(c).edit().putInt("dp_lead", m).apply(); plan(c); }
    static void setRemind(Context c, int m) { p(c).edit().putInt("dp_remind", m).apply(); }

    /** Screens that show the status hear about changes. */
    interface Watcher { void changed(); }
    static Watcher watcher;

    private static void say(Context c, String s) {
        p(c).edit().putString("dp_status", s).apply();
        History.link(c, "Day pack: " + s);
        Watcher w = watcher;
        if (w != null) h.post(w::changed);
    }

    // ---------- the calendar ----------

    static final class Ev {
        final long begin, end;
        final String title, place;
        long leave;                                      // when to set off (leave-by), 0 if none
        Ev(long b, long e, String t, String pl) { begin = b; end = Math.max(e, b + 15 * MIN); title = t; place = pl == null ? "" : pl.trim(); }
    }

    /** When the pack has to be on the watch for this event: lead time before it, or before leaving for it. */
    static long dueAt(Context c, Ev e) {
        long at = e.begin - lead(c) * MIN;
        int travel = LeaveBy.cached(c, e.place);
        if (travel >= 0) at = Math.min(at, e.begin - (travel + LeaveBy.buffer(c) + 15) * MIN);
        return at;
    }

    /** Timed events (no all-day ones) that start between from and to, soonest first. */
    static List<Ev> events(Context c, long from, long to) {
        List<Ev> out = new ArrayList<>();
        try {
            Uri.Builder u = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(u, from);
            ContentUris.appendId(u, to);
            try (Cursor q = c.getContentResolver().query(u.build(),
                    new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                            CalendarContract.Instances.EVENT_LOCATION},
                    CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
                while (q != null && q.moveToNext()) {
                    if (q.getInt(3) == 1) continue;
                    long b = q.getLong(1);
                    if (b < from) continue;
                    String t = Txt.clean(q.getString(0), 30);
                    out.add(new Ev(b, q.getLong(2), t.isEmpty() ? "EVEnt" : t, q.getString(4)));
                }
            }
        } catch (SecurityException e) {
            return null;                                   // no calendar permission
        } catch (Exception ignored) { }
        return out;
    }

    /** The events the pack carries, from the first one on, and when the stretch they make ends. */
    private static long stretchEnd(List<Ev> evs) {
        long end = evs.get(0).end;
        for (int i = 1; i < evs.size(); i++) {
            if (evs.get(i).begin > end + JOIN_GAP) break;  // a long gap: the pack comes off in between
            end = Math.max(end, evs.get(i).end);
        }
        return end;
    }

    // ---------- the face ----------

    /** The day pack face with these events and this reminder time written into it. */
    static byte[] build(Context c, List<Ev> evs, int remindMin) {
        String tpl;
        try (java.io.InputStream in = c.getAssets().open("DayPack.min.js")) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            tpl = new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        StringBuilder a = new StringBuilder("[");
        Calendar k = Calendar.getInstance();
        for (int i = 0; i < evs.size() && i < MAX_EVENTS; i++) {
            Ev e = evs.get(i);
            k.setTimeInMillis(e.begin);
            String t = Txt.seg(e.title).replaceAll("[^ -~]", "").replace("\\", "").replace("\"", "'");
            if (i > 0) a.append(',');
            a.append('[').append(k.get(Calendar.YEAR)).append(',').append(k.get(Calendar.MONTH)).append(',')
                    .append(k.get(Calendar.DAY_OF_MONTH)).append(',').append(k.get(Calendar.HOUR_OF_DAY)).append(',')
                    .append(k.get(Calendar.MINUTE)).append(",\"").append(t).append("\",");
            if (e.leave > 0 && e.leave < e.begin) {
                k.setTimeInMillis(e.leave);
                a.append(k.get(Calendar.HOUR_OF_DAY)).append(',').append(k.get(Calendar.MINUTE)).append(']');
            } else a.append("-1,0]");
        }
        a.append(']');
        String js = tpl.replace("[[\"__EV__\"]]", a.toString()).replace("[\"__RM__\"]", "[" + remindMin + "]");
        return js.getBytes(StandardCharsets.US_ASCII);
    }

    // ---------- when ----------

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 8, new Intent(ACTION).setPackage(c.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void wakeAt(Context c, long t) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.cancel(alarm(c));
            if (t > 0) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(t, System.currentTimeMillis() + 2000), alarm(c));
        } catch (Exception ignored) { }
    }

    private static final SimpleDateFormat HM = new SimpleDateFormat("h:mm a", Locale.US);
    private static final SimpleDateFormat DHM = new SimpleDateFormat("EEE h:mm a", Locale.US);

    private static String when(long t) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(t);
        boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        return (today ? HM : DHM).format(new Date(t));
    }

    /** Sets the one alarm for whatever comes next. Called on changes, on start, and after every run. */
    static void plan(Context c) {
        c = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (working) return;
        if (!on(c)) {
            if (installed(c)) { wakeAt(c, now); say(c, "Turning off: putting your face back"); }
            else { wakeAt(c, 0); say(c, "Off"); }
            return;
        }
        if (installed(c)) {
            long until = p(c).getLong("dp_until", 0);
            if (now >= until) { wakeAt(c, now); return; }
            wakeAt(c, until);
            say(c, "On the watch until " + when(until) + ", then " + prevName(c) + " goes back");
            return;
        }
        List<Ev> evs = events(c, now, now + 36 * HOUR);
        if (evs == null) { wakeAt(c, 0); say(c, "Allow Calendar in Setup"); return; }
        if (evs.isEmpty()) { wakeAt(c, now + 6 * HOUR); say(c, "No events in the next 36 hours"); return; }
        Ev e = evs.get(0);
        if (LeaveBy.on(c) && !e.place.isEmpty() && LeaveBy.cached(c, e.place) < 0 && !looking) {
            looking = true;
            final Context app = c;
            new Thread(() -> {
                LeaveBy.leaveAt(app, e.place, e.begin);          // fills the cache (or marks it unknown)
                h.post(() -> { looking = false; plan(app); });
            }).start();
        }
        long at = dueAt(c, e);
        // look again every few hours too, so new or moved events are seen
        wakeAt(c, at <= now ? now : Math.min(at, now + 3 * HOUR));
        int tr = LeaveBy.cached(c, e.place);
        String go = tr >= 0 ? ", leave " + when(e.begin - (tr + LeaveBy.buffer(c)) * MIN) : "";
        say(c, at <= now ? "Installing for " + e.title + "..." : "Next: " + e.title + " at " + when(e.begin) + go + ". Installs at " + when(at));
    }

    private static String prevName(Context c) {
        String n = p(c).getString("dp_prev", "");
        return n.isEmpty() ? "your face" : n;
    }

    /** The alarm went off (or a retry): do what is due now. */
    static void run(Context c) {
        final Context app = c.getApplicationContext();
        if (working) return;
        if (FaceInstall.isBusy()) { wakeAt(app, System.currentTimeMillis() + MIN); return; }
        long now = System.currentTimeMillis();
        if (installed(app)) {
            if (on(app) && now < p(app).getLong("dp_until", 0)) { plan(app); return; }
            // time to come off: unless the next stretch is about to start, then just refresh it
            List<Ev> evs = on(app) ? events(app, now - AFTER, now + 36 * HOUR) : null;
            if (evs != null && !evs.isEmpty() && dueAt(app, evs.get(0)) <= now + 2 * MIN) install(app, evs);
            else restore(app);
            return;
        }
        if (!on(app)) { plan(app); return; }
        List<Ev> evs = events(app, now - AFTER, now + 36 * HOUR);
        if (evs == null || evs.isEmpty() || dueAt(app, evs.get(0)) > now + 2 * MIN) { plan(app); return; }
        install(app, evs);
    }

    /** Installs right away for the next events (the app's "Install now"). */
    static void now(Context c) {
        final Context app = c.getApplicationContext();
        long t = System.currentTimeMillis();
        List<Ev> evs = events(app, t - AFTER, t + 36 * HOUR);
        if (evs == null) { say(app, "Allow Calendar in Setup"); return; }
        if (evs.isEmpty()) { say(app, "No events in the next 36 hours"); return; }
        install(app, evs);
    }

    // ---------- doing it ----------

    private static void retry(Context c, String why) {
        working = false;
        long t = System.currentTimeMillis() + 10 * MIN;
        wakeAt(c, t);
        say(c, why + ". Trying again at " + when(t));
    }

    private static void install(final Context c, List<Ev> all) {
        final long first = all.get(0).begin;
        final List<Ev> evs = new ArrayList<>();
        for (Ev e : all) if (e.begin <= first + 16 * HOUR && evs.size() < MAX_EVENTS) evs.add(e);
        if (LeaveBy.on(c)) {
            working = true;
            say(c, "Working out when to leave...");
            new Thread(() -> {
                for (int i = 0; i < evs.size(); i++) {
                    Ev e = evs.get(i), before = i > 0 ? evs.get(i - 1) : null;
                    // already there: the class before is at the same place and ends in time
                    boolean there = before != null && before.place.equalsIgnoreCase(e.place) && before.end <= e.begin
                            && e.begin - before.end < JOIN_GAP;
                    if (!e.place.isEmpty() && !there) e.leave = LeaveBy.leaveAt(c, e.place, e.begin);
                }
                h.post(() -> { working = false; send(c, evs); });
            }).start();
        } else send(c, evs);
    }

    private static void send(final Context c, final List<Ev> evs) {
        final long until = stretchEnd(evs) + AFTER;
        final byte[] js = build(c, evs, remind(c));
        if (js == null) { say(c, "This app has no Day Pack face inside"); return; }
        working = true;
        say(c, "Connecting to install for " + evs.get(0).title + "...");
        withLink(c, release -> FaceInstall.slotNames(c, names -> {
            String cur = names[0] == null ? "" : names[0].trim();
            if (!installed(c) && !cur.equalsIgnoreCase(NAME)) {
                // what is in Forge A now has to be put back later: make sure Pulse has a copy of it
                if (!cur.isEmpty() && Backups.newest(c, cur) == null && FaceInstall.read(c, cur, new StringBuilder()) == null) {
                    release.run();
                    working = false;
                    wakeAt(c, System.currentTimeMillis() + 6 * HOUR);
                    say(c, "Skipped: Forge A has " + cur + " and Pulse has no copy of it to put back. "
                            + "Install it once from Pulse (Faces) and the day pack can take over");
                    return;
                }
                p(c).edit().putString("dp_prev", cur).apply();
            }
            FaceInstall.installBytes(c, NAME, js, 0, (watch, appMsg, over) -> {
                if (!over) return;
                release.run();
                if (appMsg.startsWith("Installed")) {
                    p(c).edit().putBoolean("dp_in", true).putLong("dp_until", until).apply();
                    working = false;
                    plan(c);
                } else retry(c, "Install failed (" + appMsg + ")");
            });
        }), () -> retry(c, "Couldn't reach the watch"));
    }

    /** The app's "Put my face back": restores now, whatever the plan said. */
    static void restoreNow(Context c) {
        final Context app = c.getApplicationContext();
        if (working || !installed(app)) return;
        p(app).edit().putLong("dp_until", 0).apply();
        restore(app);
    }

    private static void restore(final Context c) {
        final String prev = p(c).getString("dp_prev", "");
        if (prev.isEmpty()) {                       // Forge A was empty before: nothing to put back
            p(c).edit().putBoolean("dp_in", false).apply();
            plan(c);
            return;
        }
        Backups.Entry b = Backups.newest(c, prev);
        byte[] js = b != null ? Backups.read(b) : FaceInstall.read(c, prev, new StringBuilder());
        if (js == null) {
            p(c).edit().putBoolean("dp_in", false).apply();
            say(c, "Couldn't find " + prev + " to put back: install it from Faces");
            plan(c);
            return;
        }
        working = true;
        say(c, "Connecting to put " + prev + " back...");
        withLink(c, release -> FaceInstall.installBytes(c, prev, js, 0, (watch, appMsg, over) -> {
            if (!over) return;
            release.run();
            if (appMsg.startsWith("Installed")) {
                p(c).edit().putBoolean("dp_in", false).apply();
                working = false;
                plan(c);
            } else retry(c, "Putting " + prev + " back failed (" + appMsg + ")");
        }), () -> retry(c, "Couldn't reach the watch to put " + prev + " back"));
    }

    interface Ready { void go(Runnable release); }

    /**
     * Brings the link up if it isn't (waking a night pause too), runs ready once the install channel is
     * there, and hands it a release that drops the link again if Pulse raised it just for this.
     */
    private static void withLink(Context c, Ready ready, Runnable fail) {
        final WatchLink w = WatchLink.get(c);
        final boolean had = w.isReady(), wasWanted = w.isWanted(), wasPaused = w.isPaused();
        holding = true;
        final Runnable release = () -> {
            holding = false;
            if (had) return;
            if (wasPaused) w.pause("Night link-off");
            else if (!wasWanted) w.disconnect();
        };
        if (!had) { if (wasPaused) w.resume(); else w.connect(); }
        final long give = System.currentTimeMillis() + 90_000L;
        h.post(new Runnable() {
            int settled;
            @Override
            public void run() {
                if (w.hasNus()) {
                    if (had || ++settled >= 3) { ready.go(release); return; }   // a moment for the face to say hello
                } else if (System.currentTimeMillis() > give) {
                    release.run();
                    fail.run();
                    return;
                }
                h.postDelayed(this, 1000);
            }
        });
    }
}
