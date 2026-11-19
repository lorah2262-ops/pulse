package com.ollee.pulse;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Share → Pulse" from any app (the Gemini app, ChatGPT, Lens, a web page...): the text
 * goes to the watch as answer rows, the same way Ask AI answers show. No window of its own.
 */
public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent i = getIntent();
        if (i != null && i.getType() != null && i.getType().startsWith("image/")) {
            Object stream = i.getExtras() == null ? null : i.getExtras().get(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) { photo((Uri) stream); return; }
        }
        CharSequence text = i == null ? null : i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        List<String> rows = rows(text == null ? "" : text.toString());
        String msg;
        if (rows.isEmpty()) msg = "Nothing to send: share some text";
        else if (!NotifService.showShared(rows)) msg = "Pulse isn't running: allow Notification access in Setup";
        else msg = "On your watch: " + rows.size() + (rows.size() == 1 ? " row" : " rows");
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** A shared photo: no screen, no questions. It is asked right away with the saved question. */
    private void photo(final Uri uri) {
        final Context app = getApplicationContext();
        final SharedPreferences prefs = getSharedPreferences("pulse", MODE_PRIVATE);
        final String q = prefs.getString("photoq", "");
        final Handler ui = new Handler(Looper.getMainLooper());
        Toast.makeText(app, "Pulse: reading the photo...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            byte[] read = null;
            String failed = null;
            try {
                read = Photo.prepare(this, uri);         // while this screen still holds the permission
                if (prefs.getInt("photo_via", 0) == 1) {
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(Photo.shareFile(this))) { o.write(read); }
                }
            } catch (Exception e) {
                failed = "Pulse couldn't open that picture";
            }
            final byte[] jpeg = read;
            final String problem = failed;
            ui.post(() -> {
                if (problem != null) Toast.makeText(app, problem, Toast.LENGTH_LONG).show();
                else if (prefs.getInt("photo_via", 0) == 1) toGemini(q);
                else ask(app, jpeg, q);
                finish();
            });
        }, "pulse-share-photo").start();
    }

    /** Pulse asks Gemini itself; the answer rows go to the watch. */
    private static void ask(final Context app, byte[] jpeg, String q) {
        String why = NotifService.askPhoto(jpeg, q, new Face.Answers() {
            @Override public void row(String r) { }
            @Override public void status(String st) { }
            @Override public void done(String w) {
                if (w != null) Toast.makeText(app, "Pulse: " + w, Toast.LENGTH_LONG).show();
            }
        });
        Toast.makeText(app, why != null ? "Pulse: " + why : "Pulse: asking, the answer goes to your watch",
                Toast.LENGTH_LONG).show();
    }

    /** Set to "Gemini app" in Pulse: hand the photo over; its answer comes back by Share. */
    private void toGemini(String q) {
        Uri u = Photo.shareUri();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.putExtra(Intent.EXTRA_TEXT, q.isEmpty()
                ? "Answer the questions in this photo. One short line per answer, like: 16 IgG"
                : q + "\n(Keep it short: one line per item.)");
        i.setClipData(ClipData.newRawUri("", u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage("com.google.android.apps.bard");
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Pulse: the Gemini app isn't installed", Toast.LENGTH_LONG).show();
        }
    }

    static final int WIDTH = 32, MAX_ROWS = 80;

    /**
     * Text as watch rows: markdown marks and bullets dropped (numbers kept, "16. IgG" becomes
     * "16 IgG"), tables one row per line, long lines wrapped at spaces.
     */
    static List<String> rows(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.replace("\r", "").split("\n")) {
            String l = line.trim();
            if (l.matches("^\\|?[\\s:|-]+\\|?$")) continue;                  // table border |---|---|
            l = l.replaceAll("^#+\\s*", "")                                 // headings
                    .replaceAll("^\\s*(?:[-*•]\\s+)", "")              // bullets
                    .replaceAll("[*_#`]", "")                               // bold, italics, code
                    .replaceAll("^(\\d+)[.)]\\s*", "$1 ")                   // "16. IgG" -> "16 IgG"
                    .replaceAll("^\\(?([a-dA-D])[.)]\\s+", "$1 ")           // "b) Polyclonal" -> "b Polyclonal"
                    .replaceAll("\\s*\\|\\s*", "  ").trim()                 // table cells
                    .replaceAll("\\s+", " ");
            if (l.isEmpty()) continue;
            while (l.length() > WIDTH && out.size() < MAX_ROWS) {           // wrap at a space
                int cut = l.lastIndexOf(' ', WIDTH);
                if (cut < WIDTH / 2) cut = WIDTH;
                out.add(l.substring(0, cut).trim());
                l = l.substring(cut).trim();
            }
            if (!l.isEmpty() && out.size() < MAX_ROWS) out.add(l);
            if (out.size() >= MAX_ROWS) break;
        }
        return out;
    }
}
