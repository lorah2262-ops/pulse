package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The whole setup as one file: switches, savers, macros, the watch menu, profiles, rules and
 * animations. Keys for Gemini and YouTube stay out (they are personal), and so do the logs.
 */
final class Setup {

    private Setup() { }

    private static final String[] SKIP = {"gemkey", "ytkey", "battery", "drain", "lastans", "lastheard", "gemerr",
            "gemmodel", "gemlite", "wanted", "hist_sent", "hist_link", "gem_day", "gem_n", "gem_hit", "bat_told",
            "prof_done", Saver.SEEN};

    private static boolean skipped(String key) {
        for (String s : SKIP) if (s.equals(key)) return true;
        return false;
    }

    /** Every setting as text: each value carries a letter for its kind. */
    static String write(Context c) throws Exception {
        JSONObject all = new JSONObject();
        for (Map.Entry<String, ?> e : c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getAll().entrySet()) {
            if (skipped(e.getKey())) continue;
            Object v = e.getValue();
            String out;
            if (v instanceof Boolean) out = "b" + ((Boolean) v ? 1 : 0);
            else if (v instanceof Integer) out = "i" + v;
            else if (v instanceof Long) out = "l" + v;
            else if (v instanceof Float) out = "f" + v;
            else if (v instanceof Set) {
                StringBuilder b = new StringBuilder("S");
                for (Object o : (Set<?>) v) b.append(o).append('\u0001');
                out = b.toString();
            } else out = "s" + v;
            all.put(e.getKey(), out);
        }
        return new JSONObject().put("pulse_setup", 1).put("settings", all).toString(1);
    }

    /** Puts a setup file in place. Returns how many settings it held. */
    static int read(Context c, String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (!root.has("pulse_setup")) throw new Exception("not a Pulse setup file");
        JSONObject all = root.getJSONObject("settings");
        SharedPreferences.Editor e = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit();
        int n = 0;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next(), v = all.getString(k);
            if (skipped(k) || v.isEmpty()) continue;
            String rest = v.substring(1);
            switch (v.charAt(0)) {
                case 'b': e.putBoolean(k, "1".equals(rest)); break;
                case 'i': e.putInt(k, Integer.parseInt(rest)); break;
                case 'l': e.putLong(k, Long.parseLong(rest)); break;
                case 'f': e.putFloat(k, Float.parseFloat(rest)); break;
                case 'S':
                    Set<String> set = new HashSet<>();
                    for (String part : rest.split("\u0001")) if (!part.isEmpty()) set.add(part);
                    e.putStringSet(k, set);
                    break;
                case 's': e.putString(k, rest); break;
                default: continue;
            }
            n++;
        }
        e.apply();
        NotifService.saverChanged();
        return n;
    }
}
