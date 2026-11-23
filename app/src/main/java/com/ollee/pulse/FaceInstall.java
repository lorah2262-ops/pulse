package com.ollee.pulse;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs a Forge face from the phone straight to the watch, over the link Pulse already holds,
 * the same way the Ollee app does it (its "Nordic UART" channel, decoded from a Bluetooth log):
 *
 *   frame = [length, 2 bytes][AA 55][CRC-16/CCITT-FALSE of the rest, 2 bytes][02][cmd][body]
 *   0x39 ready?  0x3F slot -> version   0x3E slot+name   0x40 slot+version
 *   0x41 [slot][seq 2][file length 2][200 bytes]... each answered by 0x61 [done][next seq 2]
 *   (the Ollee app's log only shows slot 0 there; sending 0 always put every face in Forge A)
 *
 * Replies use cmd + 0x20. Forge C (slot 2) holds Pulse itself: only installPulse writes it,
 * for an update or repair the user confirmed.
 */
public final class FaceInstall {

    /** What to show: a short line for the watch, a longer one for the app, and whether it's over. */
    public interface Report { void step(String watch, String app, boolean over); }

    static final int CHUNK = 200, MAX = 8158;
    static final String[] SLOT = {"Forge A", "Forge B", "Forge C", "Forge D"};

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean busy;

    /** A face install is running. */
    static boolean busy() { return busy; }

    private FaceInstall() { }

    public static boolean isBusy() { return busy; }

    /** "A", "b", "0", "forge a"... to 0..3; -1 if it isn't a slot. Empty means Forge A. */
    static int slot(String s) {
        s = s == null ? "" : s.trim().toUpperCase();
        if (s.isEmpty()) return 0;
        char c = s.charAt(s.length() - 1);
        if (c >= 'A' && c <= 'D') return c - 'A';
        if (c >= '0' && c <= '3') return c - '0';
        return -1;
    }

    // ---------- the face file ----------

    /** Finds name (or name.js) in the Faces folder the user picked in the app. */
    static byte[] read(Context c, String name, StringBuilder err) {
        String dir = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getString("faces_dir", "");
        if (dir.isEmpty()) { err.append("Pick your Faces folder in the app first (Macros tab, Install faces)"); return null; }
        Uri tree = Uri.parse(dir);
        String want = name.trim();
        String id = null, loose = null;
        try (Cursor q = c.getContentResolver().query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            while (q != null && q.moveToNext()) {
                String n = q.getString(1);
                if (n == null) continue;
                if (n.equals(want) || n.equals(want + ".js")) { id = q.getString(0); break; }
                if (n.equalsIgnoreCase(want) || n.equalsIgnoreCase(want + ".js")) loose = q.getString(0);
            }
        } catch (Exception e) {
            err.append("Can't open the Faces folder (pick it again in the app)");
            return null;
        }
        if (id == null) id = loose;
        if (id == null) { err.append("No file " + want + ".js in the Faces folder"); return null; }
        try (InputStream in = c.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > MAX) break;
            }
            return o.toByteArray();
        } catch (Exception e) {
            err.append("Can't read " + want + ".js");
            return null;
        }
    }

    /** Like list(), with each file's size: {name, bytes}. */
    static java.util.List<String[]> listInfo(Context c, int max) {
        String dir = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getString("faces_dir", "");
        if (dir.isEmpty()) return null;
        java.util.List<String[]> out = new java.util.ArrayList<>();
        Uri tree = Uri.parse(dir);
        try (Cursor q = c.getContentResolver().query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE},
                null, null, null)) {
            while (q != null && q.moveToNext()) {
                String n = q.getString(0);
                if (n != null && n.toLowerCase().endsWith(".js") && n.length() > 3)
                    out.add(new String[]{n.substring(0, n.length() - 3), String.valueOf(q.getLong(1))});
            }
        } catch (Exception e) {
            return null;
        }
        java.util.Collections.sort(out, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        return out.size() > max ? new java.util.ArrayList<>(out.subList(0, max)) : out;
    }

    /** The .js faces in the Faces folder, names without .js, A to Z (max at most); null if no folder. */
    static java.util.List<String> list(Context c, int max) {
        String dir = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getString("faces_dir", "");
        if (dir.isEmpty()) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        Uri tree = Uri.parse(dir);
        try (Cursor q = c.getContentResolver().query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (q != null && q.moveToNext()) {
                String n = q.getString(0);
                if (n != null && n.toLowerCase().endsWith(".js") && n.length() > 3) out.add(n.substring(0, n.length() - 3));
            }
        } catch (Exception e) {
            return null;
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out.size() > max ? new java.util.ArrayList<>(out.subList(0, max)) : out;
    }

    // ---------- what is in each slot (remembered from the last read or install) ----------

    /** The face names in Forge A, B and C as last seen; "" where not known. */
    static String[] known(Context c) {
        String[] k = {"", "", ""};
        String raw = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getString("slot_names", "");
        String[] p = raw.split("\n", -1);
        for (int i = 0; i < 3 && i < p.length; i++) k[i] = p[i];
        return k;
    }

    /** The versions in Forge A, B and C as last seen ("6.9"); "" where not known. */
    static String[] versions(Context c) {
        String[] k = {"", "", ""};
        String raw = c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getString("slot_vers", "");
        String[] p = raw.split("\n", -1);
        for (int i = 0; i < 3 && i < p.length; i++) k[i] = p[i];
        return k;
    }

    private static void remember(Context c, int slot, String name) {
        remember(c, slot, name, null);
    }

    /** ver null = unknown: kept if the name didn't change, cleared if it did. */
    private static void remember(Context c, int slot, String name, String ver) {
        if (slot < 0 || slot > 2) return;
        String[] k = known(c), v = versions(c);
        String n = name == null ? "" : name.trim();
        if (ver != null) v[slot] = ver;
        else if (!n.equals(k[slot])) v[slot] = "";
        k[slot] = n;
        c.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit()
                .putString("slot_names", k[0] + "\n" + k[1] + "\n" + k[2])
                .putString("slot_vers", v[0] + "\n" + v[1] + "\n" + v[2]).apply();
    }

    public interface Names { void on(String[] names); }

    /** Quietly reads Forge A and B's names (for the watch's FACES list); falls back to the last known. */
    public static void slotNames(Context c, Names cb) {
        final Context app = c.getApplicationContext();
        WatchLink w = WatchLink.get(app);
        if (busy || !w.isReady() || !w.hasNus()) {
            h.post(() -> cb.on(known(app)));
            return;
        }
        busy = true;
        inBuf.reset();
        w.setNusListener(FaceInstall::onNus);
        ask(w, 0x3D, new byte[]{0}, 2500, (c1, v1) -> {
            if (c1 == 0x5D) remember(app, 0, new String(v1, StandardCharsets.US_ASCII).trim());
            ask(w, 0x3D, new byte[]{1}, 2500, (c2, v2) -> {
                if (c2 == 0x5D) remember(app, 1, new String(v2, StandardCharsets.US_ASCII).trim());
                busy = false;
                waiting = null;
                waitSeq++;
                cb.on(known(app));
            });
        });
    }

    /** The face's name as the watch keeps it: the file name, plain letters, 20 at most. */
    static String faceName(String file) {
        String n = file.trim();
        if (n.toLowerCase().endsWith(".js")) n = n.substring(0, n.length() - 3);
        StringBuilder b = new StringBuilder();
        for (char ch : n.toCharArray()) if (ch >= 32 && ch < 127 && b.length() < 20) b.append(ch);
        return b.length() == 0 ? "Face" : b.toString();
    }

    // ---------- frames ----------

    static int crc(byte[] d, int from, int to) {
        int c = 0xFFFF;
        for (int i = from; i < to; i++) {
            c ^= (d[i] & 255) << 8;
            for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x1021) & 0xFFFF : (c << 1) & 0xFFFF;
        }
        return c;
    }

    static byte[] frame(int cmd, byte[] body) {
        int n = 2 + body.length;                      // 02, cmd, body
        byte[] f = new byte[6 + n];
        int len = 4 + n;                              // AA 55, CRC, then the rest
        f[0] = (byte) (len >> 8);
        f[1] = (byte) len;
        f[2] = (byte) 0xAA;
        f[3] = 0x55;
        f[6] = 2;
        f[7] = (byte) cmd;
        System.arraycopy(body, 0, f, 8, body.length);
        int c = crc(f, 6, f.length);
        f[4] = (byte) (c >> 8);
        f[5] = (byte) c;
        return f;
    }

    // ---------- one request at a time ----------

    private interface Reply { void on(int cmd, byte[] body); }   // cmd -1 = no answer in time

    private static final ByteArrayOutputStream inBuf = new ByteArrayOutputStream();
    private static Reply waiting;
    private static int waitSeq;

    /** Bytes from the Ollee channel: put frames back together, check them, hand them on. */
    private static void onNus(byte[] v) {
        inBuf.write(v, 0, v.length);
        while (true) {
            byte[] b = inBuf.toByteArray();
            if (b.length < 2) return;
            int len = ((b[0] & 255) << 8) | (b[1] & 255);
            if (len < 6 || len > 600) { inBuf.reset(); return; }          // lost the thread: start over
            if (b.length < 2 + len) return;
            inBuf.reset();
            inBuf.write(b, 2 + len, b.length - 2 - len);
            if ((b[2] & 255) != 0xAA || (b[3] & 255) != 0x55) continue;
            int want = ((b[4] & 255) << 8) | (b[5] & 255);
            if (crc(b, 6, 2 + len) != want) continue;
            int cmd = b[7] & 255;
            byte[] body = new byte[len - 6];
            System.arraycopy(b, 8, body, 0, body.length);
            Reply r = waiting;
            if (r != null) {
                waiting = null;
                waitSeq++;
                r.on(cmd, body);
            }
        }
    }

    private static void ask(WatchLink w, int cmd, byte[] body, long ms, Reply r) {
        waiting = r;
        final int seq = ++waitSeq;
        w.nusWrite(frame(cmd, body));
        h.postDelayed(() -> {
            if (waitSeq == seq && waiting == r) {
                waiting = null;
                if (w.nusPiece() > 20) w.packetsFailed();   // no answer to a big frame: next try uses small ones
                r.on(-1, new byte[0]);
            }
        }, ms);
    }

    private static boolean start(Context c, Report rep) {
        if (busy) { rep.step("bUSY", "A face install is already running", true); return false; }
        WatchLink w = WatchLink.get(c);
        if (!w.isReady()) { rep.step("nO LInK", "The watch isn't connected to Pulse", true); return false; }
        if (!w.hasNus()) { rep.step("nO CHAn", "The watch has no install channel (Nordic UART not found)", true); return false; }
        busy = true;
        inBuf.reset();
        w.setNusListener(FaceInstall::onNus);
        return true;
    }

    private static void finish(Report rep, String watch, String app) {
        busy = false;
        waiting = null;
        waitSeq++;
        rep.step(watch, app, true);
    }

    // ---------- install ----------

    /** Uploads file (from the Faces folder) to a slot: 0 = Forge A, 1 = Forge B. */
    public static void install(Context c, String file, int slot, Report rep) {
        if (file == null || file.trim().isEmpty()) { rep.step("nO FILE", "No file name given (extra file)", true); return; }
        StringBuilder err = new StringBuilder();
        final byte[] js = read(c, file, err);
        if (js == null) { rep.step("nO FILE", err.toString(), true); return; }
        installBytes(c, faceName(file), js, slot, rep);
    }

    /** Uploads a face's bytes (a file, or a backup) to Forge A (0) or B (1). */
    public static void installBytes(Context c, final String name, final byte[] js, int slot, Report rep) {
        installAny(c, name, js, slot, rep, false);
    }

    /** Pulse's own face into Forge C: only for an update or a repair the user confirmed. */
    static void installPulse(Context c, byte[] js, Report rep) {
        String n = known(c)[2];
        installAny(c, n == null || n.trim().isEmpty() ? "Pulse" : n.trim(), js, 2, rep, true);
    }

    private static void installAny(Context c, final String name, final byte[] js, int slot, Report rep, boolean pulse) {
        if (slot == 2 && !pulse) { rep.step("nOt C", "Forge C holds Pulse: it is only written to update Pulse", true); return; }
        if (slot < 0 || slot > (pulse ? 2 : 1)) { rep.step("SLOt", "Pick Forge A or B (slot = A or B)", true); return; }
        if (js == null || js.length == 0) { rep.step("EMPtY", name + " is empty", true); return; }
        if (js.length > MAX) { rep.step("tOO bIG", name + " is " + js.length + " bytes, over " + MAX, true); return; }
        if (!start(c, rep)) return;
        final WatchLink w = WatchLink.get(c);
        final String where = SLOT[slot];
        final Context app = c.getApplicationContext();
        rep.step("LOAd", "Installing " + name + " to " + where + "...", false);
        ask(w, 0x39, new byte[0], 5000, (cmd, b) -> {
            if (cmd < 0) { finish(rep, "nO AnS", "The watch didn't answer. Close the Ollee app and try again."); return; }
            ask(w, 0x3F, new byte[]{(byte) slot}, 4000, (c2, v) -> {
                int maj = 1, min = 0;
                if (c2 == 0x5F && v.length >= 2) { maj = v[0] & 255; min = v[1] & 255; }
                if (++min > 255) { min = 0; maj = Math.min(255, maj + 1); }
                final int fMaj = maj, fMin = min;
                byte[] nm = new byte[21];
                nm[0] = (byte) slot;
                byte[] s = name.getBytes(StandardCharsets.US_ASCII);
                for (int i = 0; i < 20; i++) nm[1 + i] = i < s.length ? s[i] : (byte) ' ';
                ask(w, 0x3E, nm, 5000, (c3, v3) -> {
                    if (c3 != 0x5E) { finish(rep, "FAIL", "The watch didn't take the face name (" + code(c3) + ")"); return; }
                    ask(w, 0x40, new byte[]{(byte) slot, (byte) fMaj, (byte) fMin}, 5000, (c4, v4) -> {
                        if (c4 != 0x60) { finish(rep, "FAIL", "The watch didn't take the version (" + code(c4) + ")"); return; }
                        chunk(w, slot, js, 0, 0, -1, (wa, ap, over) -> {
                            if (over && ap.startsWith("Installed")) {
                                remember(app, slot, name, fMaj + "." + fMin);
                                Backups.save(app, name, fMaj + "." + fMin, js);
                            }
                            rep.step(wa, ap, over);
                        }, name + " " + fMaj + "." + fMin + " is on " + where);
                    });
                });
            });
        });
    }

    private static void chunk(WatchLink w, int slot, byte[] js, int seq, int tries, int shown, Report rep, String done) {
        int count = (js.length + CHUNK - 1) / CHUNK;
        if (seq >= count) { finish(rep, "FAIL", "The watch never said the face was complete"); return; }
        int from = seq * CHUNK, n = Math.min(CHUNK, js.length - from);
        byte[] body = new byte[5 + n];
        body[0] = (byte) slot;                              // which Forge slot the code goes to
        body[1] = (byte) (seq >> 8);
        body[2] = (byte) seq;
        body[3] = (byte) (js.length >> 8);
        body[4] = (byte) js.length;
        System.arraycopy(js, from, body, 5, n);
        int pct = seq * 100 / count / 20 * 20;             // the watch gets 0, 20, 40, 60, 80
        final int now = pct;
        if (pct != shown) rep.step("SEnd " + pct, "Sending... " + pct + "%", false);
        ask(w, 0x41, body, 6000, (cmd, v) -> {
            if (cmd != 0x61 || v.length < 3) {
                if (tries < 2) chunk(w, slot, js, seq, tries + 1, now, rep, done);
                else finish(rep, "FAIL", "The watch stopped answering at " + (seq * 100 / count) + "% (" + code(cmd) + ")");
                return;
            }
            if (v[0] == 1) { finish(rep, "dOnE", "Installed: " + done); return; }
            int next = ((v[1] & 255) << 8) | (v[2] & 255);
            chunk(w, slot, js, next, 0, now, rep, done);
        });
    }

    private static String code(int cmd) {
        return cmd < 0 ? "no reply" : "reply 0x" + Integer.toHexString(cmd);
    }

    // ---------- reading the slots (nothing is written) ----------

    /** Asks the watch for each slot's name and version, A to D. D only answers if the watch has one. */
    public static void slots(Context c, Report rep) {
        if (!start(c, rep)) return;
        rep.step("SLOtS", "Asking the watch about its Forge slots...", false);
        slotCtx = c.getApplicationContext();
        slotNext(WatchLink.get(c), 0, new StringBuilder(), new StringBuilder(), rep);
    }

    private static Context slotCtx;

    private static void slotNext(WatchLink w, int i, StringBuilder app, StringBuilder watch, Report rep) {
        if (i > 3) {
            finish(rep, watch.toString().trim(), app.toString().trim());
            return;
        }
        ask(w, 0x3D, new byte[]{(byte) i}, 3000, (c1, v1) -> {
            String letter = String.valueOf((char) ('A' + i));
            if (c1 != 0x5D) {
                app.append(SLOT[i]).append(": ").append(c1 < 0 ? "no slot (no answer)" : "no slot (" + code(c1) + ")").append('\n');
                if (i == 3) watch.append(" nO d");
                slotNext(w, i + 1, app, watch, rep);
                return;
            }
            final String nm = new String(v1, StandardCharsets.US_ASCII).trim();
            if (slotCtx != null) remember(slotCtx, i, nm);
            ask(w, 0x3F, new byte[]{(byte) i}, 3000, (c2, v2) -> {
                String ver = c2 == 0x5F && v2.length >= 2 ? (v2[0] & 255) + "." + (v2[1] & 255) : "?";
                app.append(SLOT[i]).append(": ").append(nm.isEmpty() ? "(empty)" : nm).append("  v").append(ver).append('\n');
                if (slotCtx != null && i < 3) remember(slotCtx, i, nm, "?".equals(ver) ? null : ver);
                watch.append(' ').append(letter).append(i == 3 ? " FOUNd" : "");
                slotNext(w, i + 1, app, watch, rep);
            });
        });
    }
}
