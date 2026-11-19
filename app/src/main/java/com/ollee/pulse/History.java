package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * What happened on the link: every line pushed to the watch (what woke it), every change of
 * the connection (when it dropped and why), and a mirror of the screen the phone last put up.
 */
final class History {

    private History() { }

    static final class Item {
        final long time;
        final boolean flag;          // sent: the Pulse face was showing. link: this is a drop
        final String text;
        Item(long t, boolean f, String s) { time = t; flag = f; text = s; }
    }

    private static final int KEEP_SENT = 150, KEEP_LINK = 60;
    private static final ArrayList<Item> sent = new ArrayList<>(), link = new ArrayList<>();
    private static boolean loaded;
    private static long savedAt;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        read(p(c).getString("hist_sent", ""), sent);
        read(p(c).getString("hist_link", ""), link);
    }

    private static void read(String raw, List<Item> into) {
        for (String row : raw.split("\n")) {
            String[] f = row.split("\t", 3);
            if (f.length < 3) continue;
            try {
                into.add(new Item(Long.parseLong(f[0]), "1".equals(f[1]), f[2]));
            } catch (NumberFormatException ignored) { }
        }
    }

    private static String write(List<Item> from) {
        StringBuilder b = new StringBuilder();
        for (Item i : from) b.append(i.time).append('\t').append(i.flag ? 1 : 0).append('\t').append(i.text).append('\n');
        return b.toString();
    }

    /** A line went to the watch. */
    static synchronized void sent(Context c, String line, boolean faceOn) {
        load(c);
        sent.add(new Item(System.currentTimeMillis(), faceOn, describe(line)));
        while (sent.size() > KEEP_SENT) sent.remove(0);
        mirror(line);
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - savedAt > 20000) {                     // not on every line: that would be wasteful
            savedAt = now;
            p(c).edit().putString("hist_sent", write(sent)).apply();
        }
    }

    /** The connection changed. Repeats of the same words are skipped. */
    static synchronized void link(Context c, String status) {
        load(c);
        if (!link.isEmpty() && link.get(link.size() - 1).text.equals(status)) return;
        link.add(new Item(System.currentTimeMillis(), status.startsWith("Watch lost"), status));
        while (link.size() > KEEP_LINK) link.remove(0);
        p(c).edit().putString("hist_link", write(link)).apply();
        if (!status.startsWith("Connected to")) {
            mWhat = status;
            mMain = "";
            mDay = "";
        } else {
            mWhat = "Connected: waiting for the Pulse face";
        }
    }

    static synchronized List<Item> sentList(Context c) {
        load(c);
        return new ArrayList<>(sent);
    }

    static synchronized List<Item> linkList(Context c) {
        load(c);
        return new ArrayList<>(link);
    }

    /** Drops since midnight. */
    static synchronized int dropsToday(Context c) {
        load(c);
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        int n = 0;
        for (Item i : link) if (i.flag && i.time >= day.getTimeInMillis()) n++;
        return n;
    }

    /** A line of the watch protocol in plain words. */
    static String describe(String line) {
        if (line == null || line.trim().isEmpty()) return "Keep-alive";
        String[] f = line.substring(1).split("\\|", -1);
        switch (line.charAt(0)) {
            case 'M': return f.length > 3 && !f[3].isEmpty() ? "Music: " + f[3] : "Music: nothing playing";
            case 'U': return "Unread count and saver state";
            case 'T': return "Text: " + f[0];
            case 'N': return "Notification: " + (f.length > 3 ? f[3] : "");
            case 'V': return "Volume " + f[0];
            case 'h': return "Back to the music screen";
            case 'F': return "Animation frames";
            case 'K': return "Recorder timer";
            case 'R': return "Call: " + f[0];
            case 'r': return "Call ended";
            case 'W': return "Waiting animation";
            case '?': return "Asked if the Pulse face is showing";
            default: return "Command " + line.charAt(0);
        }
    }

    // ---------- live mirror ----------

    /** What the phone last put on the watch: the main digits, the two day digits, and what kind of screen. */
    static volatile String mMain = "", mDay = "", mWhat = "Nothing sent yet";
    private static String music = "", musicWhat = "Idle clock";

    private static void mirror(String line) {
        if (line == null || line.trim().isEmpty()) return;
        String[] f = line.substring(1).split("\\|", -1);
        switch (line.charAt(0)) {
            case 'M':
                music = f.length > 3 ? f[3] : "";
                musicWhat = music.isEmpty() ? "Idle clock" : "1".equals(f[0]) ? "Music screen, playing" : "Music screen, paused";
                if (mWhat.startsWith("Music") || mWhat.startsWith("Idle") || mWhat.startsWith("Connected")
                        || mWhat.startsWith("Nothing") || mWhat.startsWith("Another")) show(music, "", musicWhat);
                break;
            case 'T': show(f[0], f.length > 1 ? f[1] : "", "Text screen"); break;
            case 'N': show((f.length > 3 ? f[3] + " " : "") + (f.length > 4 ? f[4] : ""), f.length > 1 ? f[1] : "",
                    "Notification" + (f.length > 2 ? " from " + f[2] : "")); break;
            case 'V': show("VOL " + f[0], "", "Volume"); break;
            case 'R': show("CALL " + f[0], "", "Incoming call"); break;
            case 'F': show("", "", "Animation playing"); break;
            case 'K': show("rEC", "", "Recorder timer"); break;
            case 'W': show("", "", "Waiting animation"); break;
            case 'h': case 'r': show(music, "", musicWhat); break;
            default:
        }
    }

    private static void show(String main, String day, String what) {
        mMain = main;
        mDay = day.trim();
        mWhat = what;
    }

    /** The last button pressed on the watch (a, l = tap; A, L = hold) and when (elapsedRealtime). */
    static volatile char pressKey;
    static volatile long pressAt;

    static void press(char c) {
        pressKey = c;
        pressAt = android.os.SystemClock.elapsedRealtime();
    }

    /** The watch said its Pulse face left the screen (false) or came on (true). */
    static void face(boolean on) {
        if (on) show(music, "", musicWhat);
        else show("", "", "Another face is showing on the watch");
    }
}
