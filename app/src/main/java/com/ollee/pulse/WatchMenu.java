package com.ollee.pulse;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** The watch's hold-A menu: its order, the names it shows and which items are hidden. */
final class WatchMenu {

    private WatchMenu() { }

    static final String[] IDS = {"search", "ask", "answer", "inbox", "macro", "batt", "faces", "play"},
            NAMES = {"SEArCH", "ASK AI", "AnSWEr", "InbOX", "MACrO", "bAtt", "FACES", "PLAY"},
            ABOUT = {"YouTube search", "Ask AI by voice", "The last AI answer", "Messages", "Macros", "Battery voltage",
                    "Install a face from your Faces folder", "UNO with friends on your Wi-Fi or hotspot"};

    static final class Item {
        final String id;
        String name;
        boolean shown;
        Item(String i, String n, boolean s) { id = i; name = n; shown = s; }

        String about() {
            for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return ABOUT[i];
            return id;
        }
    }

    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getString("wmenu", "");
        for (String part : raw.split(";")) {
            String[] f = part.split(",", -1);
            if (f.length < 3 || index(f[0]) < 0 || find(out, f[0]) != null) continue;
            out.add(new Item(f[0], f[1].isEmpty() ? NAMES[index(f[0])] : f[1], !"0".equals(f[2])));
        }
        for (int i = 0; i < IDS.length; i++) if (find(out, IDS[i]) == null) out.add(new Item(IDS[i], NAMES[i], true));
        return out;
    }

    static void save(Context c, List<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item i : items) {
            if (b.length() > 0) b.append(';');
            String n = Macros.clean(i.name);
            b.append(i.id).append(',').append(n).append(',').append(i.shown ? 1 : 0);
        }
        c.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit().putString("wmenu", b.toString()).apply();
    }

    private static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    private static Item find(List<Item> all, String id) {
        for (Item i : all) if (i.id.equals(id)) return i;
        return null;
    }
}
