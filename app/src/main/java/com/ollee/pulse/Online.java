package com.ollee.pulse;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Online play through a Firebase Realtime Database (its REST API, no SDK).
 *   games/CODE/in        players add messages here: {t: token, a: join|start|play|draw|..., name, i, c}
 *   games/CODE/out/TOKEN the host puts each online player's view here, as a JSON string
 * The phone listens to games/CODE/in as a stream, runs each message as a move, and rewrites the
 * views after every change. The page (on GitHub Pages) does the other half in the browser.
 */
final class Online {
    private final Party party;
    private final String base;                 // https://xxx.firebaseio.com/games/CODE
    final String db;                           // https://xxx.firebaseio.com
    private final String host;                 // db/hosts/ID: which game this phone has on (for the tag)
    private volatile boolean on = true;
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Set<String> done = new HashSet<>();
    private HttpURLConnection stream;
    volatile String problem = "";              // the last error, for the app to show
    private int notes;

    Online(Party party, String db) {
        this.party = party;
        String d = db.trim();
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        if (d.endsWith(".json")) d = d.substring(0, d.length() - 5);
        this.db = d;
        base = d + "/games/" + party.code;
        host = d + "/hosts/" + NfcTap.hostId(party.app);
    }

    void start() {
        out.execute(() -> {
            send("DELETE", base + ".json", null);
            send("PUT", base + "/meta.json", "{\"kind\":\"" + party.kind + "\",\"at\":" + System.currentTimeMillis() + "}");
            // for the watch's tag: a friend's phone looks up the game that's on here
            send("PUT", host + ".json", "{\"code\":\"" + party.code + "\",\"kind\":\"" + party.kind + "\",\"at\":" + System.currentTimeMillis() + "}");
        });
        Thread t = new Thread(this::listen, "online-in");
        t.setDaemon(true);
        t.start();
    }

    void end() {
        on = false;
        try { if (stream != null) stream.disconnect(); } catch (Exception ignored) { }
        out.execute(() -> {
            send("DELETE", base + ".json", null);
            send("DELETE", host + ".json", null);
        });
        out.shutdown();
    }

    /** Rewrites every online player's view (changes that come quickly are sent together). */
    void publish() {
        if (!on || pending.getAndSet(true)) return;
        out.execute(() -> {
            pending.set(false);
            List<Party.Player> who;
            synchronized (party.lock) { who = new ArrayList<>(party.players); }
            for (Party.Player p : who) {
                if (!p.online) continue;
                String view = party.state(p, 0);
                send("PUT", base + "/out/" + p.token + ".json", JSONObject.quote(view));
            }
        });
    }

    // ---------- messages from players ----------

    private void listen() {
        while (on) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/in.json").openConnection();
                c.setRequestProperty("Accept", "text/event-stream");
                c.setReadTimeout(90000);
                c.setConnectTimeout(15000);
                stream = c;
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line, event = "";
                problem = "";
                while (on && (line = in.readLine()) != null) {
                    if (line.startsWith("event:")) event = line.substring(6).trim();
                    else if (line.startsWith("data:") && ("put".equals(event) || "patch".equals(event))) got(line.substring(5).trim());
                    else if ("auth_revoked".equals(event) || "cancel".equals(event)) { problem = "The database said no (check its rules)"; break; }
                }
            } catch (Exception e) {
                problem = "Can't reach the database";
            }
            if (on) try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
        }
    }

    private void got(String data) {
        try {
            JSONObject o = new JSONObject(data);
            String path = o.optString("path", "/");
            Object d = o.opt("data");
            if (!(d instanceof JSONObject)) return;
            JSONObject j = (JSONObject) d;
            if ("/".equals(path)) {
                for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Object m = j.opt(k);
                    if (m instanceof JSONObject) handle(k, (JSONObject) m);
                }
            } else {
                handle(path.substring(1), j);
            }
        } catch (Exception ignored) { }
    }

    private void handle(String key, JSONObject m) {
        if (key.contains("/") || !done.add(key)) return;
        String t = m.optString("t", ""), a = m.optString("a", "");
        if (t.length() < 8) return;
        if ("join".equals(a)) {
            if (party.join(m.optString("name", ""), t) == null) {
                send("PUT", base + "/out/" + t + ".json", JSONObject.quote("{\"err\":\"The game is full or has started\"}"));
            } else {
                publish();
            }
        } else {
            Party.Player p = party.byToken(t);
            if (p != null) {
                String err = "start".equals(a) ? party.start() : party.move(p.seat, a, m.optInt("i", -1), m.optInt("c", -1));
                if (err != null) {                 // tell them why (and the view changes, so it's sent)
                    String v = party.state(p, 0);
                    v = v.substring(0, v.length() - 1) + ",\"note\":" + JSONObject.quote(err) + ",\"n\":" + (++notes) + "}";
                    final String view = v;
                    out.execute(() -> send("PUT", base + "/out/" + t + ".json", JSONObject.quote(view)));
                }
                publish();
            }
        }
        out.execute(() -> send("DELETE", base + "/in/" + key + ".json", null));
    }

    private void send(String method, String url, String body) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) problem = "The database said no (check its rules)";
            else if (code >= 400) problem = "Database error " + code;
            c.disconnect();
        } catch (Exception e) {
            problem = "Can't reach the database";
        }
    }
}
