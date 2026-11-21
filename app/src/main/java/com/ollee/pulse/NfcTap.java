package com.ollee.pulse;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * The watch's NFC tag as a shortcut. The tag holds pulse://tap; when the phone reads it,
 * Pulse does the action picked on the Tap tab.
 */
final class NfcTap {
    static final String URI = "pulse://tap";
    static final int SMART = 0, CONNECT = 1, MACRO = 2, PROFILE = 3, OLLEE = 4;
    static final String[] NAMES = {"Smart tap", "Connect the watch", "Run a macro", "Next profile", "Let the Ollee app in"};
    static final String[] HINTS = {
            "Does what fits the moment",
            "Reconnects right away",
            "One of your MacroDroid macros",
            "School, Home, Sleep in turn",
            "Pulse steps aside for 10 minutes"};

    private NfcTap() { }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    static int action(Context c) { return Math.max(0, Math.min(NAMES.length - 1, p(c).getInt("tap_action", SMART))); }

    static void action(Context c, int a) { p(c).edit().putInt("tap_action", a).apply(); }

    /** The macro slot number a tap runs (-1 = the first one with a name). */
    static int macro(Context c) { return p(c).getInt("tap_macro", -1); }

    static void macro(Context c, int n) { p(c).edit().putInt("tap_macro", n).apply(); }

    static Macros.Slot macroSlot(Context c) {
        List<Macros.Slot> all = Macros.load(c);
        int want = macro(c);
        Macros.Slot first = null;
        for (Macros.Slot s : all) {
            if (s.name == null || s.name.trim().isEmpty()) continue;
            if (first == null) first = s;
            if (s.number == want) return s;
        }
        return first;
    }

    // ---------- games: a friend's phone tapping the watch opens your game ----------

    /** Whether the tag carries the game link (friends tap to join) instead of pulse://tap. */
    static boolean games(Context c) { return p(c).getBoolean("tap_games", false); }

    static void games(Context c, boolean on) { p(c).edit().putBoolean("tap_games", on).apply(); }

    /** This phone's id under hosts/ in the game database: fixed, so the tag never needs rewriting. */
    static String hostId(Context c) {
        String h = p(c).getString("party_host", "");
        if (h.isEmpty()) {
            byte[] b = new byte[4];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x));
            h = s.toString();
            p(c).edit().putString("party_host", h).apply();
        }
        return h;
    }

    /** Why the game link can't be used yet, or null when online play is set up. */
    static String gamesProblem(Context c) {
        if (!Party.dbUrl(c).startsWith("https://")) return "Add your Firebase link in Online play (Home) first";
        if (!Party.pageUrl(c).startsWith("https://")) return "Add your game page link in Online play (Home) first";
        return null;
    }

    /**
     * What Write tag puts on the tag. With games on: the game page with the database (without https://,
     * to fit small stickers) and this phone's id. Friends' phones open it in the browser and land in
     * whatever game is on; this phone opens Pulse instead (TapActivity) and does the tap action.
     */
    static String tagUri(Context c) {
        if (!games(c) || gamesProblem(c) != null) return URI;
        String page = Party.pageUrl(c);
        return page + (page.contains("?") ? "&" : "?") + "d=" + Party.dbUrl(c).substring(8) + "&h=" + hostId(c);
    }

    /** A tag link is this phone's own (pulse://tap, or a game link with our id). */
    static boolean ours(Context c, android.net.Uri u) {
        if (u == null || "pulse".equals(u.getScheme())) return true;
        try { return hostId(c).equals(u.getQueryParameter("h")); } catch (Exception e) { return false; }
    }

    static long lastAt(Context c) { return p(c).getLong("tap_at", 0); }

    static String lastDid(Context c) { return p(c).getString("tap_did", ""); }

    static int count(Context c) { return p(c).getInt("tap_count", 0); }

    /** Does the tap's action; returns what happened, in a few words. */
    static String run(Context c) {
        String r;
        switch (action(c)) {
            case CONNECT: r = connect(c); break;
            case MACRO: r = runMacro(c); break;
            case PROFILE: r = nextProfile(c); break;
            case OLLEE: r = olleeIn(c); break;
            default: r = smart(c);
        }
        p(c).edit().putLong("tap_at", System.currentTimeMillis()).putString("tap_did", r)
                .putInt("tap_count", count(c) + 1).apply();
        return r;
    }

    /** Ringing: mute it. Not connected: connect. Music: next song. Otherwise: the watch's menu. */
    static String smart(Context c) {
        if (NotifService.ringing()) return NotifService.muteRinger(c) ? "Muted the ringing call" : "Couldn't mute the call";
        if (!WatchLink.get(c).isReady()) return connect(c);
        NotifService.Now n = NotifService.now;
        if (n != null && n.exists && n.playing) {
            NotifService.control('N');
            return "Next song";
        }
        return NotifService.openWatchMenu() ? "Opened the menu on the watch" : "Pulse isn't running: allow Notification access";
    }

    static String connect(Context c) {
        WatchLink w = WatchLink.get(c);
        if (w.isReady()) return "Already connected";
        NotifService.cancelLetGo();
        w.connect();
        return "Connecting to the watch";
    }

    static String runMacro(Context c) {
        Macros.Slot s = macroSlot(c);
        if (s == null) return "No macro set up yet";
        return NotifService.runMacro(c, s);
    }

    static String nextProfile(Context c) {
        int i = (Profiles.current(c) + 1) % Profiles.NAMES.length;
        Profiles.apply(c, i);
        return "Profile: " + Profiles.NAMES[i];
    }

    /** Pulse lets go of the watch for 10 minutes and opens the Ollee app. Tap again to come back. */
    static String olleeIn(Context c) {
        WatchLink w = WatchLink.get(c);
        if (!w.isWanted()) return connect(c);          // already stepped aside: a tap brings Pulse back
        NotifService.letGo(c, 600);
        String pkg = olleePackage(c);
        if (pkg != null) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(i); } catch (Exception ignored) { }
            }
        }
        return "Pulse stepped aside for 10 min";
    }

    /** The Ollee app, found by its name among the installed apps. */
    static String olleePackage(Context c) {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(main, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName())) continue;
                CharSequence label = r.loadLabel(c.getPackageManager());
                if (pkg.toLowerCase().contains("ollee") || (label != null && label.toString().toLowerCase().contains("ollee")))
                    return pkg;
            }
        } catch (Exception ignored) { }
        return null;
    }

    static void buzz(Context c) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }
}
