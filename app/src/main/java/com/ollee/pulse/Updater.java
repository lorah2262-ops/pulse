package com.ollee.pulse;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Pulse updates itself from the GitHub repo's latest release: every push there builds a new APK
 * with a higher build number. The new app carries the new watch face too, and offers it to the
 * watch the next time they connect (as before), so one install updates both.
 */
final class Updater {

    private Updater() { }

    static final String REPO = "lorah2262-ops/pulse";
    static final String DONE = "com.ollee.pulse.UPDATE_DONE";

    static final class Info {
        int build;
        String apk, notes;
        long size;
    }

    interface Got { void on(Info i, String err); }
    interface Progress { void on(String msg, boolean over); }

    /** Where install progress goes (the app's Updates card); set while it is on screen. */
    static volatile Progress screen;

    private static final Handler h = new Handler(Looper.getMainLooper());

    /** This app's build number (versionCode; GitHub sets it to the build's run number). */
    static int mine(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Pulse-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        return c;
    }

    /** Asks GitHub for the latest release. Answers on the main thread. */
    static void check(Context c, Got cb) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Info info = null;
            String err = null;
            try {
                HttpURLConnection k = open("https://api.github.com/repos/" + REPO + "/releases/latest");
                int code = k.getResponseCode();
                if (code == 404) err = "Can't see the releases: is the GitHub repo public?";
                else if (code != 200) err = "GitHub answered " + code;
                else {
                    JSONObject o = new JSONObject(read(k.getInputStream()));
                    info = new Info();
                    String tag = o.optString("tag_name", "");
                    info.build = tag.startsWith("build-") ? Integer.parseInt(tag.substring(6)) : 0;
                    info.notes = o.optString("body", "").trim();
                    JSONArray as = o.optJSONArray("assets");
                    for (int i = 0; as != null && i < as.length(); i++) {
                        JSONObject a = as.getJSONObject(i);
                        if ("Pulse.apk".equals(a.optString("name"))) {
                            info.apk = a.optString("browser_download_url");
                            info.size = a.optLong("size");
                        }
                    }
                    if (info.apk == null) { err = "The latest release has no Pulse.apk"; info = null; }
                }
            } catch (Exception e) {
                err = "No connection to GitHub";
            }
            app.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit()
                    .putLong("upd_checked", System.currentTimeMillis())
                    .putInt("upd_latest", info != null ? info.build : 0).apply();
            final Info fi = info;
            final String fe = err;
            h.post(() -> cb.on(fi, fe));
        }).start();
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    private static void say(String msg, boolean over) {
        Progress p = screen;
        if (p != null) h.post(() -> p.on(msg, over));
    }

    /** Downloads the APK into Android's installer; Android then asks "Update Pulse?". */
    static void install(Context c, Info info) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session s = null;
            try {
                say("Downloading build " + info.build + "...", false);
                HttpURLConnection k = open(info.apk);
                k.setRequestProperty("Accept", "application/octet-stream");
                if (k.getResponseCode() != 200) { say("Download failed (" + k.getResponseCode() + ")", true); return; }
                long len = k.getContentLengthLong() > 0 ? k.getContentLengthLong() : info.size;
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(app.getPackageName());
                if (len > 0) sp.setSize(len);
                int id = pi.createSession(sp);
                s = pi.openSession(id);
                try (InputStream in = k.getInputStream(); OutputStream out = s.openWrite("Pulse.apk", 0, len > 0 ? len : -1)) {
                    byte[] b = new byte[65536];
                    long got = 0;
                    int n, shown = -1;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        got += n;
                        int pct = len > 0 ? (int) (got * 100 / len) : -1;
                        if (pct >= 0 && pct / 10 != shown) { shown = pct / 10; say("Downloading... " + pct + "%", false); }
                    }
                    s.fsync(out);
                }
                Intent done = new Intent(app, Done.class).setAction(DONE);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(app, 9, done, flags);
                say("Opening the installer...", false);
                s.commit(pend.getIntentSender());
                s.close();
                s = null;
            } catch (Exception e) {
                say("Update failed: " + e.getMessage(), true);
            } finally {
                if (s != null) s.abandon();
            }
        }).start();
    }

    /** The installer's answers: ask the user to confirm, then success or why not. */
    public static final class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) { }
                }
                say("Tap Update on Android's prompt", false);
                return;
            }
            if (st == PackageInstaller.STATUS_SUCCESS) { say("Updated", true); return; }
            String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                why = "This Pulse was signed differently: uninstall it once, then install from GitHub";
            else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) why = "Cancelled";
            say(why == null ? "Update failed" : "Update failed: " + why, true);
        }
    }
}
