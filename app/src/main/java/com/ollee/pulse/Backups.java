package com.ollee.pulse;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every face Pulse sends is kept here (inside the app, so deleting the Faces folder can't lose it),
 * the 8 newest of each face. From the Faces tab a copy can go back to the watch or into the folder.
 */
final class Backups {

    private Backups() { }

    static final int KEEP = 8;

    static final class Entry {
        final String name, ver;
        final long time, size;
        final File file;

        Entry(String name, String ver, long time, File file) {
            this.name = name;
            this.ver = ver;
            this.time = time;
            this.file = file;
            this.size = file.length();
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "face_backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Keeps a copy of what was just sent: face_backups/UnO/6.12__1731800000000.js */
    static void save(Context c, String name, String ver, byte[] js) {
        File f = new File(dir(c), safe(name));
        if (!f.exists()) f.mkdirs();
        File out = new File(f, ver + "__" + System.currentTimeMillis() + ".js");
        try (FileOutputStream o = new FileOutputStream(out)) {
            o.write(js);
        } catch (Exception ignored) { }
        // only the newest few of each face
        File[] all = f.listFiles();
        if (all == null || all.length <= KEEP) return;
        List<Entry> es = new ArrayList<>();
        for (File x : all) { Entry e = parse(name, x); if (e != null) es.add(e); }
        Collections.sort(es, (a, b) -> Long.compare(b.time, a.time));
        for (int i = KEEP; i < es.size(); i++) es.get(i).file.delete();
    }

    private static String safe(String n) {
        return n.replaceAll("[^A-Za-z0-9 ._()-]", "_");
    }

    private static Entry parse(String name, File x) {
        String n = x.getName();
        int sep = n.indexOf("__");
        if (!n.endsWith(".js") || sep <= 0) return null;
        try {
            return new Entry(name, n.substring(0, sep), Long.parseLong(n.substring(sep + 2, n.length() - 3)), x);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The newest copy of the face the watch calls name, or null if Pulse never sent it. */
    static Entry newest(Context c, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String want = safe(name.trim());
        Entry best = null;
        for (Entry e : all(c)) if (e.name.equalsIgnoreCase(want) && (best == null || e.time > best.time)) best = e;
        return best;
    }

    /** All copies: faces A to Z, newest first within each. */
    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] faces = dir(c).listFiles();
        if (faces == null) return out;
        for (File f : faces) {
            File[] vs = f.listFiles();
            if (!f.isDirectory() || vs == null) continue;
            for (File x : vs) { Entry e = parse(f.getName(), x); if (e != null) out.add(e); }
        }
        Collections.sort(out, (a, b) -> {
            int k = a.name.compareToIgnoreCase(b.name);
            return k != 0 ? k : Long.compare(b.time, a.time);
        });
        return out;
    }

    static byte[] read(Entry e) {
        try (FileInputStream in = new FileInputStream(e.file)) {
            byte[] b = new byte[(int) e.file.length()];
            int n = 0;
            while (n < b.length) {
                int k = in.read(b, n, b.length - n);
                if (k < 0) break;
                n += k;
            }
            return b;
        } catch (Exception x) {
            return null;
        }
    }

    /**
     * Writes a copy into the Faces folder: UnO.js if that name is free, else "UnO v6.12.js".
     * Returns the file name, or null if the folder can't be written (pick it again to allow it).
     */
    static String toFolder(Context c, Entry e) {
        String dir = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getString("faces_dir", "");
        byte[] js = read(e);
        if (dir.isEmpty() || js == null) return null;
        List<String[]> have = FaceInstall.listInfo(c, 500);
        boolean taken = false;
        if (have != null) for (String[] h : have) if (h[0].equalsIgnoreCase(e.name)) taken = true;
        String name = taken ? e.name + " v" + e.ver + ".js" : e.name + ".js";
        try {
            Uri tree = Uri.parse(dir);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri doc = DocumentsContract.createDocument(c.getContentResolver(), parent, "application/octet-stream", name);
            if (doc == null) return null;
            try (OutputStream o = c.getContentResolver().openOutputStream(doc)) {
                if (o == null) return null;
                o.write(js);
            }
            return name;
        } catch (Exception x) {
            return null;
        }
    }
}
