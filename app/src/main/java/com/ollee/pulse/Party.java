package com.ollee.pulse;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A game (UNO or Buckshot) hosted on this phone. The host plays on the watch (seat 0).
 * Friends play in any browser, no app: on the same Wi-Fi or this phone's hotspot through a tiny
 * web server here (GET / the page, /join, /state waits for a change, /move, /start), or from
 * anywhere through Online (a Firebase database) with the page on GitHub Pages.
 * The phone runs the game; players only send moves.
 */
final class Party {
    static final int PORT = 8765;
    static volatile Party now;

    /** Told (on any thread) whenever anything in the party changes. */
    static final List<Runnable> watchers = Collections.synchronizedList(new ArrayList<>());

    static final class Player {
        final String token, name;
        final int seat;
        final boolean online;
        long seen;

        Player(String t, String n, int s, boolean o) { token = t; name = n; seat = s; online = o; seen = System.currentTimeMillis(); }
    }

    final Object lock = new Object();
    final String code;
    final List<Player> players = new ArrayList<>();
    final String kind;                         // "uno" or "buck"
    final int[] score = new int[4];            // games won this party, by seat
    Game game;
    Online online;
    int version;
    private final Context app;
    private ServerSocket server;
    private NsdManager.RegistrationListener nsd;
    private volatile boolean open = true;
    private static final SecureRandom RND = new SecureRandom();

    private Party(Context c, String kind) {
        app = c.getApplicationContext();
        this.kind = "buck".equals(kind) ? "buck" : "uno";
        code = String.valueOf(100000 + RND.nextInt(900000));
        players.add(new Player(token(), "Watch", 0, false));
    }

    int max() { return "buck".equals(kind) ? 2 : 4; }

    String title() { return "buck".equals(kind) ? "Buckshot" : "UNO"; }

    /** Starts hosting (or returns the party already running). Null if the server can't start. */
    static synchronized Party host(Context c, String kind) {
        if (now != null) return now;
        Party p = new Party(c, kind);
        try {
            p.server = new ServerSocket(PORT);
        } catch (Exception e) {
            return null;
        }
        now = p;
        Thread t = new Thread(p::serve, "party");
        t.setDaemon(true);
        t.start();
        p.advertise();
        p.changed();
        return p;
    }

    /** Ends the party: the server closes and everyone's screen says it's over. */
    static synchronized void end() {
        Party p = now;
        if (p == null) return;
        now = null;
        p.open = false;
        if (p.online != null) p.online.end();
        try { p.server.close(); } catch (Exception ignored) { }
        try {
            if (p.nsd != null) ((NsdManager) p.app.getSystemService(Context.NSD_SERVICE)).unregisterService(p.nsd);
        } catch (Exception ignored) { }
        synchronized (p.lock) { p.version++; p.lock.notifyAll(); }
        tell();
    }

    private static void tell() {
        Runnable[] all;
        synchronized (watchers) { all = watchers.toArray(new Runnable[0]); }
        for (Runnable r : all) r.run();
    }

    void changed() {
        synchronized (lock) {
            version++;
            lock.notifyAll();
        }
        Online o = online;
        if (o != null) o.publish();
        tell();
    }

    /** Opens this party to the internet too (the Firebase database at db). Null if done, else why not. */
    String goOnline(String db) {
        if (online != null) return null;
        if (db == null || !db.startsWith("https://")) return "Set your Firebase database link first";
        online = new Online(this, db);
        online.start();
        return null;
    }

    // ---------- settings (Home > Play): online on/off, the Firebase database, the game page ----------

    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("pulse", Context.MODE_PRIVATE); }

    static boolean wantOnline(Context c) { return prefs(c).getBoolean("party_online", false); }

    static String dbUrl(Context c) { return prefs(c).getString("party_db", "").trim().replaceAll("/+$", ""); }

    static String pageUrl(Context c) { return prefs(c).getString("party_page", "").trim(); }

    static String lastKind(Context c) { return prefs(c).getString("party_kind", "uno"); }

    /** Hosts with the saved choices: online too when it's turned on. */
    static Party hostSaved(Context c, String kind) {
        prefs(c).edit().putString("party_kind", kind).apply();
        Party p = host(c, kind);
        if (p != null && wantOnline(c)) p.goOnline(dbUrl(c));
        return p;
    }

    /** The internet invite: the game page with the database and the code, or "" when not online. */
    String onlineLink() {
        if (online == null) return "";
        String page = pageUrl(app);
        if (page.isEmpty()) return "";
        try {
            return page + (page.contains("?") ? "&" : "?") + "db=" + java.net.URLEncoder.encode(online.db, "UTF-8") + "&g=" + code;
        } catch (Exception e) {
            return "";
        }
    }

    private static String token() {
        return Long.toHexString(RND.nextLong() & 0xffffffffffL);
    }

    // ---------- what everyone can do ----------

    /** The link friends open, e.g. http://192.168.43.1:8765, or "" with no Wi-Fi or hotspot. */
    static String address() {
        String best = "";
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String name = n.getName();
                    String ip = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) return ip;
                    if (best.isEmpty()) best = ip;
                }
            }
        } catch (Exception ignored) { }
        return best;
    }

    static String link() {
        String ip = address();
        return ip.isEmpty() ? "" : "http://" + ip + ":" + PORT;
    }

    Player join(String name) { return join(name, null); }

    /** Online players bring their own token (made in their browser). */
    Player join(String name, String tok) {
        synchronized (lock) {
            if (tok != null) for (Player p : players) if (p.token.equals(tok)) return p;
            if (game != null || players.size() >= max()) return null;
            String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
            if (n.isEmpty()) n = "Player " + (players.size() + 1);
            if (n.length() > 14) n = n.substring(0, 14);
            Player p = new Player(tok != null ? tok : token(), n, players.size(), tok != null);
            players.add(p);
            changed();
            return p;
        }
    }

    /** Deals a new game for everyone who joined. Null if it started, else why not. */
    String start() {
        synchronized (lock) {
            if (players.size() < 2) return "Waiting for a friend to join";
            if (game != null && game.winner() < 0) return "A game is already on";
            game = "buck".equals(kind) ? new Buck() : new Uno(players.size());
            changed();
            return null;
        }
    }

    /** A move from a seat (see Uno.move and Buck.move). Null if done, else why not. */
    String move(int seat, String what, int index, int color) {
        synchronized (lock) {
            if (game == null) return "The game hasn't started";
            boolean was = game.winner() >= 0;
            String err = game.move(seat, what, index, color);
            if (err == null) {
                if (!was && game.winner() >= 0) score[game.winner()]++;
                changed();
            }
            return err;
        }
    }

    Uno uno() { return game instanceof Uno ? (Uno) game : null; }

    Buck buck() { return game instanceof Buck ? (Buck) game : null; }

    /** The host's watch draws a card: its place in the sorted hand, or -1. */
    int draw(int seat) {
        synchronized (lock) {
            Uno uno = uno();
            if (uno == null) return -1;
            int i = uno.draw(seat);
            if (i >= 0) changed();
            return i;
        }
    }

    Player byToken(String t) {
        synchronized (lock) {
            for (Player p : players) if (p.token.equals(t)) return p;
            return null;
        }
    }

    String nameOf(int seat) {
        synchronized (lock) {
            return seat >= 0 && seat < players.size() ? players.get(seat).name : "";
        }
    }

    // ---------- the page's view of the game, as JSON ----------

    String state(Player me, int since) {
        StringBuilder b = new StringBuilder("{");
        synchronized (lock) {
            me.seen = System.currentTimeMillis();
            b.append("\"v\":").append(version).append(",\"open\":").append(open).append(",\"kind\":\"").append(kind)
                    .append("\",\"code\":\"").append(code).append("\",\"you\":").append(me.seat)
                    .append(",\"started\":").append(game != null).append(",\"players\":[");
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                if (i > 0) b.append(',');
                b.append("{\"name\":\"").append(p.name).append("\",\"score\":").append(score[i])
                        .append(",\"turn\":").append(game != null && game.winner() < 0 && game.turn() == i).append('}');
            }
            b.append(']');
            if (game != null) {
                b.append(",\"turn\":").append(game.turn()).append(",\"winner\":").append(game.winner())
                        .append(",\"seq\":").append(game.seq()).append(",\"events\":[");
                boolean first = true;
                for (Game.Ev e : game.events()) {
                    if (e.seq <= since) continue;
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"s\":").append(e.seq).append(",\"by\":").append(e.by).append(",\"k\":\"").append(e.kind)
                            .append("\",\"a\":").append(e.a).append(",\"n\":").append(e.n).append(",\"to\":").append(e.to).append('}');
                }
                b.append(']');
                game.json(b, me.seat);
            }
        }
        return b.append('}').toString();
    }

    // ---------- the web server ----------

    private void serve() {
        while (open) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> answer(s), "party-req");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (!open) return;
            }
        }
    }

    private void answer(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(40000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* headers: not needed */ }
            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            String path = parts[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            Map<String, String> arg = args(query);
            OutputStream out = sock.getOutputStream();
            if ("/".equals(path)) { send(out, "text/html; charset=utf-8", page()); return; }
            if ("/info".equals(path)) { send(out, "application/json", "{\"kind\":\"" + kind + "\",\"code\":\"" + code + "\"}"); return; }
            if ("/join".equals(path)) {
                Player p = join(arg.get("name"));
                send(out, "application/json", p == null ? "{\"err\":\"The game is full or has started\"}"
                        : "{\"token\":\"" + p.token + "\",\"seat\":" + p.seat + "}");
                return;
            }
            Player me = byToken(arg.containsKey("t") ? arg.get("t") : "");
            if (me == null) { send(out, "application/json", "{\"err\":\"gone\"}"); return; }
            if ("/state".equals(path)) {
                int v = num(arg.get("v"), -1);
                long until = System.currentTimeMillis() + 25000;
                synchronized (lock) {
                    while (open && version == v && System.currentTimeMillis() < until) {
                        lock.wait(Math.max(1, until - System.currentTimeMillis()));
                    }
                }
                send(out, "application/json", state(me, num(arg.get("e"), Integer.MAX_VALUE)));
            } else if ("/move".equals(path)) {
                String err = move(me.seat, arg.get("a"), num(arg.get("i"), -1), num(arg.get("c"), -1));
                send(out, "application/json", err == null ? "{\"ok\":true}" : "{\"err\":\"" + err + "\"}");
            } else if ("/start".equals(path)) {
                String err = start();
                send(out, "application/json", err == null ? "{\"ok\":true}" : "{\"err\":\"" + err + "\"}");
            } else {
                send(out, "text/plain", "Not here");
            }
        } catch (Exception ignored) { }
    }

    private static int num(String s, int d) {
        try { return Integer.parseInt(s); } catch (Exception e) { return d; }
    }

    private static Map<String, String> args(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try { m.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8")); } catch (Exception ignored) { }
        }
        return m;
    }

    private static void send(OutputStream out, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private String pageCache;

    private String page() {
        if (pageCache != null) return pageCache;
        try (InputStream in = app.getAssets().open("play.html")) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            pageCache = o.toString("UTF-8");
        } catch (Exception e) {
            pageCache = "<p>Pulse couldn't load the game page.</p>";
        }
        return pageCache;
    }

    // ---------- found by other Pulse phones nearby ----------

    private void advertise() {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("Pulse " + title() + " " + code);
            info.setServiceType("_pulse._tcp.");
            info.setPort(PORT);
            nsd = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int e) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
                @Override public void onServiceRegistered(NsdServiceInfo s) { }
                @Override public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            ((NsdManager) app.getSystemService(Context.NSD_SERVICE)).registerService(info, NsdManager.PROTOCOL_DNS_SD, nsd);
        } catch (Exception ignored) {
            nsd = null;
        }
    }
}
