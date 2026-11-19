package com.ollee.pulse;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** The watch's MACrO list: up to 8 slots, each with a name and a kind. */
final class Macros {

    private Macros() { }

    static final int SLOTS = 8;

    /** MacroDroid runs it, or Pulse answers it itself. */
    static final String MACRODROID = "md", STATUS = "status", NEXT = "next", RECORD = "rec";

    static final class Slot {
        final int number;
        String name;
        String kind;
        boolean careful;

        Slot(int n, String name, String kind, boolean careful) {
            number = n;
            this.name = name;
            this.kind = kind;
            this.careful = careful;
        }
    }

    private static final String DEFAULTS = "rEC,md,0;MArK,md,0;SNAP,md,0;LOCK,md,0;StAtUS,status,0;nEXt,next,0;,md,0;bAL,md,0";

    static List<Slot> load(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
        String raw = p.getString("macros", DEFAULTS);
        if (!p.getBoolean("mig_noserver", false)) {        // the server slot was dropped: clear it once
            raw = raw.replace("SErVEr,md,", ",md,");
            p.edit().putString("macros", raw).putBoolean("mig_noserver", true).apply();
        }
        List<Slot> out = new ArrayList<>();
        String[] parts = raw.split(";");
        for (int i = 0; i < SLOTS; i++) {
            String[] f = i < parts.length ? parts[i].split(",", -1) : new String[0];
            String name = f.length > 0 ? f[0] : "";
            String kind = f.length > 1 ? f[1] : MACRODROID;
            boolean careful = f.length > 2 && "1".equals(f[2]);
            out.add(new Slot(i + 1, name, kind, careful));
        }
        return out;
    }

    static void save(Context c, List<Slot> slots) {
        StringBuilder b = new StringBuilder();
        for (Slot s : slots) {
            if (b.length() > 0) b.append(';');
            b.append(clean(s.name)).append(',').append(s.kind).append(',').append(s.careful ? 1 : 0);
        }
        c.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit().putString("macros", b.toString()).apply();
    }

    /** The slot a button combo runs straight from the music screen: hold A + L, or double-tap A. 0 = off. */
    /** A trigger set to the Tap action (the same thing the NFC tag does). */
    static final int TAP = -2;

    static int trigger(Context c, boolean hold) {
        android.content.SharedPreferences p = c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
        int v = p.getInt(hold ? "trig_hold" : "trig_double", -1);
        if (v != -1) return v;
        // the earlier build had one combo and one slot
        return p.getInt("trig_mode", 0) == (hold ? 1 : 2) ? p.getInt("trig_slot", 1) : 0;
    }

    /** Slots that have a name, in order: what the watch lists. */
    static List<Slot> active(Context c) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : load(c)) if (!s.name.isEmpty()) out.add(s);
        return out;
    }

    /** Up to 6 letters, digits and spaces: what the watch's digits can show. */
    static String clean(String s) {
        String t = Txt.clean(s, 20).replaceAll("[^A-Za-z0-9 ?.-]", "").trim();
        return t.length() > 6 ? t.substring(0, 6) : t;
    }
}
