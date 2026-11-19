package com.ollee.pulse;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Asks Gemini what song is playing in a short recording. */
final class Gemini {

    private Gemini() { }

    static final String API = "https://generativelanguage.googleapis.com/v1beta/";

    private static final String PROMPT =
            "This is about 10 seconds recorded on a phone microphone of music playing nearby. "
            + "It may be noisy (a cafe, a jeepney, a mall). "
            + "1) If you recognize the song, give up to 3 guesses as \"Artist - Title\", best first. "
            + "2) Also write the clearest sung lyric line you can hear, in its original language "
            + "(it may be Tagalog, English or another language). "
            + "Only give songs you are reasonably confident about; never invent. "
            + "Reply with JSON only: {\"songs\":[\"Artist - Title\"],\"lyrics\":\"...\"}. "
            + "Use an empty list or empty string when unsure.";

    /** The guesses, best first, then a lyric line to search by (may be empty). */
    static final class Result {
        final List<String> songs = new ArrayList<>();
        String lyrics = "";
    }

    static Result identify(Context c, File audio) throws Exception {
        SharedPreferences p = c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
        String key = p.getString("gemkey", "").trim();
        String model = pickModel(p, key);
        Usage.count(c);

        JSONObject audioPart = new JSONObject().put("inline_data",
                new JSONObject().put("mime_type", "audio/aac").put("data", base64(audio)));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", PROMPT)).put(audioPart))))
                .put("generationConfig", new JSONObject().put("response_mime_type", "application/json"));

        String out;
        try {
            out = post(API + model + ":generateContent", key, body.toString());
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                p.edit().remove("gemmodel").apply();    // model retired: pick a new one next time
            }
            throw e;
        }

        StringBuilder text = new StringBuilder();
        JSONArray parts = new JSONObject(out).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
        for (int i = 0; i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text", ""));
        String json = text.toString().trim();
        int a = json.indexOf('{'), b = json.lastIndexOf('}');
        if (a >= 0 && b > a) json = json.substring(a, b + 1);

        Result r = new Result();
        JSONObject o = new JSONObject(json);
        JSONArray songs = o.optJSONArray("songs");
        if (songs != null) {
            for (int i = 0; i < songs.length() && r.songs.size() < 3; i++) {
                String s = songs.optString(i, "").trim();
                if (!s.isEmpty()) r.songs.add(s);
            }
        }
        r.lyrics = o.optString("lyrics", "").trim();
        return r;
    }

    private static final String ASK =
            "You answer on a tiny watch screen that shows one short row of text at a time. "
            + "Reply in plain text, one row per line: no markdown, no bullets, no emoji. "
            + "Each row is at most 30 characters. A simple answer is 1 to 3 rows. Lists, steps and "
            + "comparisons get one item per row, up to 12 rows. For a table, one table row per line, "
            + "written compactly like: Neutrophils 50-70%. Numbers as digits. "
            + "If asked in Tagalog or Taglish, answer the same way. Question: ";

    private static final String HEAR =
            "The recording is one person asking you a question out loud. They speak softly and the "
            + "recording was made louder, so expect some hiss; it may be English, Tagalog or Taglish. "
            + "Listen to the whole recording carefully before answering. "
            + "First row: Q followed by their words exactly as spoken (keep their wording and language; "
            + "at most 60 characters; if longer, keep the first words that fit). "
            + "Then answer that question in the following rows. "
            + "If you cannot make out the words, reply with exactly two rows: Q ? and then: Say it again";

    /** Gets each row of the answer as soon as it is written (called on the network thread). */
    interface Rows {
        void row(String row);
        /** What it is doing right now (sending, reading, thinking), for the app. */
        default void status(String s) { }
    }

    /**
     * Asks Gemini and streams the answer row by row. Regular Flash with minimal thinking first
     * (fast and smart); Flash-Lite if Flash is at its limit, busy or gives nothing.
     * "Deeper thinking" lets Flash think as long as it likes (slower).
     */
    static void ask(Context c, String question, Rows out) throws Exception {
        run(c, new JSONArray().put(new JSONObject().put("text", ASK + question)), 12, 35000, out);
    }

    /** Same, but Gemini hears the question itself: a WAV recording. Row 1 is "Q <what it heard>". */
    static void askAudio(Context c, byte[] wav, Rows out) throws Exception {
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", ASK.substring(0, ASK.length() - "Question: ".length()) + HEAR))
                .put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))));
        run(c, parts, 13, 35000, out);
    }

    /** A photo from the phone, with or without a typed question. Row 1 is "Q <what it answers>". */
    static void askImage(Context c, byte[] jpeg, String question, Rows out) throws Exception {
        String q = question == null ? "" : question.trim();
        String prompt = "You answer on a tiny watch screen that shows one short row of text at a time. "
                + "Reply in plain text, one row per line: no markdown, no bullets, no emoji. "
                + "Each row is at most 30 characters. Numbers as digits. "
                + "The person sent you a photo from their phone"
                + (q.isEmpty()
                    ? " without a question. If it shows questions, a quiz, a worksheet or a problem, "
                      + "answer or solve them. Otherwise say what it shows and the most useful facts about it. "
                    : " and asks: " + q + " ")
                + "First row: Q followed by what you are answering, at most 60 characters "
                + "(the person's own words if they typed one). "
                + "If there are several numbered questions, answer every one in order, one row each: "
                + "the number, then a very short answer, like: 16 IgG (multiple choice and true or false: "
                + "just the answer). Otherwise keep the answer to a few rows; tables one row per line.";
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", prompt))
                .put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))));
        run(c, parts, 60, 60000, out);           // a full page takes a while to read
    }

    private static void run(Context c, JSONArray parts, int maxRows, int readMs, Rows out) throws Exception {
        SharedPreferences p = c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
        String key = p.getString("gemkey", "").trim();
        boolean deep = p.getBoolean("ai_smart", false);
        int[] sent = {0};
        Rows counted = new Rows() {
            @Override public void row(String r) { sent[0]++; out.row(r); }
            @Override public void status(String st) { out.status(st); }
        };
        try {
            Usage.count(c);
            stream(pickModel(p, key), key, parts, maxRows, readMs, !deep, counted);
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 429")) Usage.hit(c);
            if (m.contains("HTTP 404")) p.edit().remove("gemmodel").apply();
            // Flash thinking too long before writing anything counts too: Flash-Lite starts writing at once
            boolean slow = e instanceof java.net.SocketTimeoutException && m.startsWith("no reply");
            boolean retry = slow || m.contains("HTTP 429") || m.contains("HTTP 5") || m.startsWith("EMPTY") || m.contains("HTTP 404");
            if (!retry || sent[0] > 0) throw e;            // rows already on the watch: keep them
            counted.status(slow ? "Flash is taking too long: asking Flash-Lite instead..." : "Asking Flash-Lite instead...");
            Usage.count(c);
            stream(pickLite(p, key), key, parts, maxRows, readMs, true, counted);
        }
    }

    /** One streamed request; retried once without any thinking setting if the model rejects it. */
    private static void stream(String model, String key, JSONArray parts, int maxRows, int readMs, boolean quick, Rows out) throws Exception {
        try {
            streamOnce(model, key, parts, maxRows, readMs, quick ? QUICK : DEEP, out);
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (!m.contains("http 400") || !m.contains("think")) throw e;
            streamOnce(model, key, parts, maxRows, readMs, PLAIN, out);
        }
    }

    private static final int PLAIN = 0, QUICK = 1, DEEP = 2;

    /** Thinking turned down as far as the model allows (the setting differs by model generation). */
    private static JSONObject littleThinking(String model) throws Exception {
        double v = version(model.toLowerCase());
        if (v >= 3) return new JSONObject().put("thinkingLevel", "minimal");
        if (v >= 2.5) return new JSONObject().put("thinkingBudget", 0);
        return null;
    }

    private static void streamOnce(String model, String key, JSONArray parts0, int maxRows, int readMs, int mode, Rows out) throws Exception {
        JSONObject gen = new JSONObject();
        JSONObject think = mode == QUICK ? littleThinking(model) : null;
        // its thinking is sent along too (and skipped below): the stream never goes quiet while it
        // works on something long, so the connection doesn't time out
        if (think == null && mode == DEEP && version(model.toLowerCase()) >= 2.5) think = new JSONObject();
        if (think != null) gen.put("thinkingConfig", think.put("includeThoughts", true));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", parts0)))
                .put("generationConfig", gen);
        HttpURLConnection c = (HttpURLConnection) new URL(API + model + ":streamGenerateContent?alt=sse").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readMs);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);         // sent as it is written, so progress is real
        long t0 = System.currentTimeMillis();
        boolean sending = true;
        try {
            OutputStream o = c.getOutputStream();
            int kb = data.length / 1024, step = Math.max(16384, data.length / 10), lastPct = -1;
            for (int off = 0; off < data.length; off += step) {
                o.write(data, off, Math.min(step, data.length - off));
                int pct = (int) ((long) Math.min(data.length, off + step) * 100 / data.length);
                if (kb > 100 && pct / 10 != lastPct / 10) { lastPct = pct; out.status("Sending " + kb + " KB... " + pct + "%"); }
            }
            o.close();
            out.status("Sent in " + (System.currentTimeMillis() - t0) / 1000 + " s. Gemini is reading it...");
            sending = false;
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + errorText(c));
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder pending = new StringBuilder();
            int rows = 0;
            boolean any = false;
            String reason = "";
            String line;
            while ((line = in.readLine()) != null && rows < maxRows) {
                if (!line.startsWith("data:")) continue;
                JSONObject chunk = new JSONObject(line.substring(5).trim());
                JSONArray cands = chunk.optJSONArray("candidates");
                if (cands == null || cands.length() == 0) continue;
                JSONObject cand = cands.getJSONObject(0);
                reason = cand.optString("finishReason", reason);
                JSONObject content = cand.optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts == null) continue;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    if (!part.optBoolean("thought", false)) pending.append(part.optString("text", ""));
                    else if (rows == 0) out.status("Gemini is thinking... " + (System.currentTimeMillis() - t0) / 1000 + " s");
                }
                int nl;
                while ((nl = pending.indexOf("\n")) >= 0 && rows < maxRows) {   // each finished line goes out now
                    String r = clean(pending.substring(0, nl));
                    pending.delete(0, nl + 1);
                    if (!r.isEmpty()) { out.row(r); rows++; any = true; }
                }
            }
            String last = clean(pending.toString());
            if (!last.isEmpty() && rows < maxRows) { out.row(last); any = true; }
            if (!any) throw new IOException("EMPTY " + reason);
        } catch (java.net.SocketTimeoutException e) {
            // say where it got stuck: still sending (slow upload) or waiting for Gemini
            throw new java.net.SocketTimeoutException((sending ? "stuck while sending, after " : "no reply from Gemini for ")
                    + (System.currentTimeMillis() - t0) / 1000 + " s (" + data.length / 1024 + " KB)");
        } finally {
            c.disconnect();
        }
    }

    /** One row as plain text: no markdown marks, list bullets or numbering. */
    private static String clean(String row) {
        return row.replaceAll("[*_#`|]", "").replaceAll("^\\s*(?:[-\u2022]|\\d+[.)])\\s+", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static String errorText(HttpURLConnection c) {
        try {
            InputStream e = c.getErrorStream();
            if (e == null) return "";
            ByteArrayOutputStream eb = new ByteArrayOutputStream();
            byte[] bb = new byte[4096];
            int k;
            while ((k = e.read(bb)) > 0 && eb.size() < 4096) eb.write(bb, 0, k);
            String m = new JSONObject(eb.toString("UTF-8")).getJSONObject("error").optString("message", "");
            return m.isEmpty() ? "" : ": " + m;
        } catch (Exception ignored) {
            return "";
        }
    }

    /** The newest Flash-Lite model, found once and remembered (used when Flash is at its limit). */
    private static String pickLite(SharedPreferences p, String key) throws Exception {
        String saved = p.getString("gemlite", "");
        if (!saved.isEmpty()) return saved;
        JSONArray models = new JSONObject(get(API + "models?pageSize=200", key)).optJSONArray("models");
        String best = "models/gemini-2.5-flash-lite";
        double bestVer = -1;
        if (models != null) {
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.getJSONObject(i);
                String name = m.optString("name", ""), n = name.toLowerCase();
                if (!n.contains("flash-lite") || n.contains("image") || n.contains("tts") || n.contains("live")
                        || n.contains("audio") || n.contains("exp")) continue;
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                if (methods == null || !methods.toString().contains("generateContent")) continue;
                double ver = version(n) - (n.contains("preview") ? 0.05 : 0);
                if (ver > bestVer) { bestVer = ver; best = name; }
            }
        }
        p.edit().putString("gemlite", best).apply();
        return best;
    }

    /**
     * The newest regular Flash model the key can use, found once and remembered.
     * (Model names change over time, so it isn't hard-coded.)
     */
    private static String pickModel(SharedPreferences p, String key) throws Exception {
        String saved = p.getString("gemmodel", "");
        if (!saved.isEmpty()) return saved;
        JSONArray models = new JSONObject(get(API + "models?pageSize=200", key)).optJSONArray("models");
        String best = "models/gemini-2.5-flash";
        double bestVer = -1;
        if (models != null) {
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.getJSONObject(i);
                String name = m.optString("name", "");
                String n = name.toLowerCase();
                if (!n.contains("flash") || n.contains("lite") || n.contains("image") || n.contains("tts")
                        || n.contains("live") || n.contains("audio") || n.contains("exp")) continue;
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                if (methods == null || !methods.toString().contains("generateContent")) continue;
                double ver = version(n) - (n.contains("preview") ? 0.05 : 0);
                if (ver > bestVer) { bestVer = ver; best = name; }
            }
        }
        p.edit().putString("gemmodel", best).apply();
        return best;
    }

    /** "models/gemini-2.5-flash" -> 2.5 */
    private static double version(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)").matcher(name);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    private static String base64(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
        } finally {
            in.close();
        }
    }

    private static String get(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("x-goog-api-key", key);
        return read(c);
    }

    private static String post(String url, String key, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(35000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        OutputStream o = c.getOutputStream();
        o.write(body.getBytes(StandardCharsets.UTF_8));
        o.close();
        return read(c);
    }

    private static String read(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                String why = "";
                try {
                    InputStream e = c.getErrorStream();
                    if (e != null) {
                        ByteArrayOutputStream eb = new ByteArrayOutputStream();
                        byte[] bb = new byte[4096];
                        int k;
                        while ((k = e.read(bb)) > 0 && eb.size() < 4096) eb.write(bb, 0, k);
                        why = new JSONObject(eb.toString("UTF-8")).getJSONObject("error").optString("message", "");
                    }
                } catch (Exception ignored) { }
                throw new IOException("HTTP " + code + (why.isEmpty() ? "" : ": " + why));
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
