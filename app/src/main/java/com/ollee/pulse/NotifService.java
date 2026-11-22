package com.ollee.pulse;

import android.app.Notification;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.provider.Settings;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs while notification access is on. Forwards notifications and the
 * music state to the watch, and carries out the watch's music buttons.
 */
public class NotifService extends NotificationListenerService implements WatchLink.CommandHandler {

    private final Handler h = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    private WatchLink link;
    private MediaSessionManager msm;
    private ComponentName me;
    private MediaController ctl;
    private String lastMedia = "";
    private String ringKey;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> pickPlayer(list);

    private final MediaController.Callback playerCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { pushSoon(); snapNow(); }
        @Override public void onMetadataChanged(MediaMetadata m) { pushSoon(); snapNow(); }
        @Override public void onQueueChanged(java.util.List<android.media.session.MediaSession.QueueItem> q) { snapNow(); }
        @Override public void onSessionDestroyed() { pickPlayer(null); }
    };

    // ---------- what's playing, for the app's mini player and the floating player ----------

    /** One look at the player: song, artist, cover, app, and where in the song it is. */
    static final class Now {
        String title = "", artist = "", pkg = "";
        android.graphics.Bitmap art;
        /** The songs before and after this one in the player's queue, if it shares one. */
        String prevTitle = "", prevArtist = "", nextTitle = "", nextArtist = "";
        android.graphics.Bitmap prevArt, nextArt;
        boolean playing, exists;
        long pos, at, dur;                 // position (ms) as of at (elapsedRealtime); song length (ms)
        float speed = 1f;

        /** Where the song is right now, in ms. */
        long position() {
            long p = pos;
            if (playing && at > 0) p += (long) ((SystemClock.elapsedRealtime() - at) * speed);
            return dur > 0 ? Math.min(dur, Math.max(0, p)) : Math.max(0, p);
        }
    }

    static volatile Now now = new Now();
    /** Called (on the main thread) whenever the song or play state changes. */
    static final java.util.List<Runnable> nowWatchers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private MusicPill pill;

    private void snapNow() {
        Now n = new Now();
        MediaController c = ctl;
        if (c != null) {
            n.exists = true;
            n.pkg = c.getPackageName();
            MediaMetadata md = c.getMetadata();
            PlaybackState ps = c.getPlaybackState();
            if (md != null) {
                n.title = Txt.clean(md.getString(MediaMetadata.METADATA_KEY_TITLE), 80);
                n.artist = Txt.clean(md.getString(MediaMetadata.METADATA_KEY_ARTIST), 60);
                if (n.artist.isEmpty()) n.artist = Txt.clean(md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), 60);
                n.dur = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
                n.art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (n.art == null) n.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (n.art == null) n.art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            if (n.title.isEmpty()) n.title = appName(n.pkg);
            try {
                java.util.List<android.media.session.MediaSession.QueueItem> q = c.getQueue();
                long active = ps == null ? -1 : ps.getActiveQueueItemId();
                if (q != null && active >= 0) {
                    for (int i = 0; i < q.size(); i++) {
                        if (q.get(i).getQueueId() != active) continue;
                        if (i > 0) {
                            android.media.MediaDescription dsc = q.get(i - 1).getDescription();
                            n.prevTitle = Txt.clean(String.valueOf(dsc.getTitle() == null ? "" : dsc.getTitle()), 60);
                            n.prevArtist = Txt.clean(String.valueOf(dsc.getSubtitle() == null ? "" : dsc.getSubtitle()), 40);
                            n.prevArt = dsc.getIconBitmap();
                        }
                        if (i + 1 < q.size()) {
                            android.media.MediaDescription dsc = q.get(i + 1).getDescription();
                            n.nextTitle = Txt.clean(String.valueOf(dsc.getTitle() == null ? "" : dsc.getTitle()), 60);
                            n.nextArtist = Txt.clean(String.valueOf(dsc.getSubtitle() == null ? "" : dsc.getSubtitle()), 40);
                            n.nextArt = dsc.getIconBitmap();
                        }
                        break;
                    }
                }
            } catch (Exception ignored) { }
            if (ps != null) {
                n.playing = ps.getState() == PlaybackState.STATE_PLAYING;
                n.pos = ps.getPosition();
                n.at = ps.getLastPositionUpdateTime();
                n.speed = ps.getPlaybackSpeed() == 0 ? 1f : ps.getPlaybackSpeed();
            }
        }
        now = n;
        for (Runnable r : nowWatchers) r.run();
        if (pill == null) pill = new MusicPill(this);
        pill.update(n);
    }

    /** The app's player buttons: P play/pause, N next, B previous (works even if watch music is off). */
    static void control(char c) {
        NotifService s = self;
        if (s != null) s.doMusic(c);
    }

    /** The app's floating-player switch changed, or overlay permission was granted. */
    static void pillChanged() {
        NotifService s = self;
        if (s == null) return;
        if (s.pill == null) s.pill = new MusicPill(s);
        s.pill.update(now);
    }

    private final Runnable pushNow = () -> pushMedia(false);

    // ---------- volume state ----------
    private AudioManager audio;
    private String lastVol = "";
    private boolean volReceiver;
    private final Runnable volNow = () -> pushVolume(false, true);

    // ---------- screens, YouTube, macros ----------
    private Face face;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final StringBuilder cmdLine = new StringBuilder();
    private boolean inLine, showReceiver;
    private long lineStart;

    /** MacroDroid answers a macro (or says something on its own): com.ollee.pulse.SHOW, extra "text". */
    private final BroadcastReceiver showMsg = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String act = String.valueOf(i.getAction());
            if (act.endsWith(".LINK")) { handOver(extra(i, "on"), extra(i, "for")); return; }
            if (act.endsWith(".INSTALL")) { runInstall(extra(i, "file"), extra(i, "slot")); return; }
            if (act.endsWith(".SLOTS")) { runSlots(); return; }
            if (face == null) return;
            if (act.endsWith(".ASK")) {
                // a macro asks: extras choices ("YES|NO|LAtEr"), title and id (both optional)
                List<String> list = new ArrayList<>();
                String raw = extra(i, "choices");
                for (String x : raw.split(raw.contains("|") ? "\\|" : ",")) {
                    if (!x.trim().isEmpty() && list.size() < 12) list.add(x.trim());
                }
                face.onAsk(extra(i, "id"), extra(i, "title"), list);
            } else if (act.endsWith(".PLAY")) {
                // a macro plays an animation: extra name (a clip in the app), or frames ("f1|f2|...") and speed
                Anim.Clip clip = Anim.find(NotifService.this, extra(i, "name"));
                if (clip == null) clip = Anim.parse("macro", extra(i, "frames"), num(extra(i, "speed"), 4));
                if (clip != null) face.play(clip, num(extra(i, "loops"), 1));
            } else {
                String t = i.getStringExtra("text");
                if (t != null) face.onShow(t);
            }
        }
    };

    // ---------- installing faces from the phone (no Ollee app) ----------

    /** The app's Install faces card listens here while it's open. */
    static volatile FaceInstall.Report appInstall;

    private FaceInstall.Report installReport() {
        return (watch, app, over) -> {
            if (face != null) face.installMsg(watch, over);
            getSharedPreferences("pulse", MODE_PRIVATE).edit().putString("install_last", app).apply();
            FaceInstall.Report a = appInstall;
            if (a != null) a.step(watch, app, over);
        };
    }

    /** com.ollee.pulse.INSTALL, extras file (UnO or UnO.js) and slot (A or B, default A). */
    void runInstall(String file, String slot) {
        FaceInstall.install(this, file, FaceInstall.slot(slot), installReport());
    }

    /** com.ollee.pulse.SLOTS: reads each Forge slot's name and version, A to D. Writes nothing. */
    void runSlots() {
        FaceInstall.slots(this, installReport());
    }

    /** From the app: false if Pulse's service isn't running. */
    static boolean install(String file, String slot) {
        NotifService s = self;
        if (s == null) return false;
        s.runInstall(file, slot);
        return true;
    }

    static boolean slots() {
        NotifService s = self;
        if (s == null) return false;
        s.runSlots();
        return true;
    }

    /** From the app: sends a backup copy back to Forge A (0) or B (1). */
    static boolean installBackup(String name, byte[] js, int slot) {
        NotifService s = self;
        if (s == null) return false;
        FaceInstall.installBytes(s, name, js, slot, s.installReport());
        return true;
    }

    // ---------- faces by profile ----------

    /** The face a profile puts in Forge A (0) or B (1); "" = leave it. */
    static String profileFace(Context c, int prof, int slot) {
        return c.getSharedPreferences("pulse", MODE_PRIVATE).getString("prof_face_" + prof + "_" + slot, "");
    }

    static void profileFace(Context c, int prof, int slot, String face) {
        c.getSharedPreferences("pulse", MODE_PRIVATE).edit().putString("prof_face_" + prof + "_" + slot, face).apply();
    }

    /** A profile was put in place: send its faces (A, then B), skipping any already there. */
    static void profileFaces(Context c, int prof) {
        boolean any = !profileFace(c, prof, 0).isEmpty() || !profileFace(c, prof, 1).isEmpty();
        if (!any) return;
        c.getSharedPreferences("pulse", MODE_PRIVATE).edit().putInt("prof_face_pending", prof).apply();
        NotifService s = self;
        if (s != null) s.h.post(() -> s.profileStep(0));
    }

    private void profileStep(int slot) {
        int prof = getSharedPreferences("pulse", MODE_PRIVATE).getInt("prof_face_pending", -1);
        if (prof < 0) return;
        if (slot > 1) {                                       // both done
            getSharedPreferences("pulse", MODE_PRIVATE).edit().remove("prof_face_pending").apply();
            return;
        }
        String want = profileFace(this, prof, slot);
        if (want.isEmpty() || want.equalsIgnoreCase(FaceInstall.known(this)[slot])) { profileStep(slot + 1); return; }
        if (link == null || !link.isReady()) return;          // tried again when the watch links up
        if (FaceInstall.isBusy()) { h.postDelayed(() -> profileStep(slot), 15000); return; }
        final FaceInstall.Report base = installReport();
        FaceInstall.install(this, want, slot, (w, a, over) -> {
            base.step(w, a, over);
            if (!over) return;
            boolean watchTrouble = "nO LInK".equals(w) || "nO AnS".equals(w) || "FAIL".equals(w)
                    || "bUSY".equals(w) || "nO CHAn".equals(w);
            if (!watchTrouble) h.postDelayed(() -> profileStep(slot + 1), 2500);
            // watch trouble: stays pending and is tried again on the next link-up
        });
    }

    // ---------- handing the watch to another app (the Ollee app, to install a face) ----------

    private final Runnable relink = () -> WatchLink.get(this).connect();

    /**
     * com.ollee.pulse.LINK, extra on = false: Pulse lets go of the watch so another app (the
     * Ollee app) can connect to it. It comes back by itself after "for" seconds (default 180)
     * in case the macro stops halfway; on = true reconnects right away.
     */
    private void handOver(String on, String secs) {
        WatchLink w = WatchLink.get(this);
        h.removeCallbacks(relink);
        boolean back = on.isEmpty() || on.equals("true") || on.equals("1") || on.equalsIgnoreCase("on");
        if (back) {
            w.connect();
            return;
        }
        int s = 180;
        try { s = Math.max(30, Math.min(900, Integer.parseInt(secs.trim()))); } catch (NumberFormatException ignored) { }
        w.disconnect();
        h.postDelayed(relink, s * 1000L);
    }

    /** A tap on the watch's tag: Pulse lets go for a while (the Ollee app's turn). */
    static void letGo(Context c, int secs) {
        NotifService s = self;
        if (s != null) s.handOver("false", String.valueOf(secs));
        else WatchLink.get(c).disconnect();
    }

    /** Coming back early: forget the timed reconnect (connect() is called right after). */
    static void cancelLetGo() {
        NotifService s = self;
        if (s != null) s.h.removeCallbacks(s.relink);
    }

    /** A call is ringing right now. */
    static boolean ringing() {
        NotifService s = self;
        return s != null && s.ringKey != null;
    }

    private boolean ringMuted;
    private final Runnable unmuteRing = this::unmuteRinger;

    /** Quiets a ringing call: the system's way if allowed, else the ring volume is muted for a minute. */
    static boolean muteRinger(Context c) {
        try {
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) { tm.silenceRinger(); return true; }
        } catch (Exception ignored) { }
        NotifService s = self;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0);
            if (s != null) {
                s.ringMuted = true;
                s.h.removeCallbacks(s.unmuteRing);
                s.h.postDelayed(s.unmuteRing, 60_000);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void unmuteRinger() {
        if (!ringMuted) return;
        ringMuted = false;
        h.removeCallbacks(unmuteRing);
        try {
            ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) { }
    }

    /** The tap's "otherwise": the hold-A menu, shown on the watch. */
    static boolean openWatchMenu() {
        NotifService s = self;
        if (s == null || s.face == null) return false;
        s.h.post(() -> s.face.openMenuFromPhone());
        return true;
    }

    /** An intent extra as text, whatever type the sender used. */
    private static String extra(Intent i, String key) {
        Bundle b = i.getExtras();
        Object o = b == null ? null : b.get(key);
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(String s, int or) {
        try {
            return Math.max(1, Math.min(40, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return or;
        }
    }

    /** The app's Play button. */
    static String playAnim(Anim.Clip clip) {
        NotifService s = self;
        if (s == null || s.face == null) return "Pulse isn't running: allow Notification access in Setup";
        if (s.link == null || !s.link.isReady()) return "The watch isn't connected";
        return s.face.play(clip, 1) ? null : "Open the Pulse face on the watch first";
    }

    // ---------- real beat ----------

    private final HashMap<String, Integer> bpms = new HashMap<>();
    private boolean beatBusy;

    /** The tempo of this song if it has been measured (0 = not yet, or it couldn't be); starts measuring a new one. */
    private int bpmOf(final String title) {
        Integer known = bpms.get(title);
        if (known != null) return known;
        if (beatBusy || face == null || !face.faceOn || !Features.on(this, Features.BEAT) || Saver.saverOn(this)
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return 0;
        beatBusy = true;
        h.postDelayed(() -> Beat.measure(h, bpm -> {       // a moment later: the song has to be audible first
            beatBusy = false;
            if (bpms.size() > 60) bpms.clear();
            bpms.put(title, bpm);
            if (bpm > 0) pushMedia(false);                 // one more line: the same song, now with its tempo
        }), 1500);
        return 0;
    }

    // ---------- turn-by-turn (Google Maps) ----------

    private static final String MAPS = "com.google.android.apps.maps";
    private static final Pattern DIST = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|mi|ft|yd|m)\\b", Pattern.CASE_INSENSITIVE);
    /** For the app: what Pulse last read from the Maps notification, so a missing turn can be explained. */
    static volatile String lastNav = "";
    private char navLast;
    private int navStep = -1;
    private String navNote = "";
    private final Runnable navGone = this::navGone;

    private void navGone() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification x : all) {
                    if (x.getNotification() != null && isNav(x.getPackageName(), x.getNotification())) return;
                }
            }
        } catch (Exception ignored) { }
        navLast = 0;
        navStep = -1;
        lastNav = "";
        if (face != null) face.navEnd();          // navigation ended
    }

    private static boolean isNav(String pkg, Notification n) {
        return MAPS.equals(pkg) && ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || Notification.CATEGORY_NAVIGATION.equals(n.category));
    }

    /**
     * The navigation notification: the distance is in its words, the turn only in its picture.
     * Words are tried first (some languages and versions spell the turn out), then the arrow's shape.
     */
    private void nav(Notification n) {
        h.removeCallbacks(navGone);                // still navigating
        Bundle ex = n.extras;
        List<String> texts = new ArrayList<>();
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT}) {
            String t = Txt.clean(ex.getCharSequence(k), 80);
            if (!t.isEmpty() && !texts.contains(t)) texts.add(t);
        }
        Bitmap arrow = null;
        boolean views = texts.isEmpty();
        if (views) arrow = readViews(n, texts);      // an own layout instead of title and text: read its views
        if (arrow == null) arrow = iconBitmap(n);
        if (texts.isEmpty()) {
            lastNav = "no words in the notification" + (arrow == null ? ", no picture" : "")
                    + " (own layout: " + (n.contentView != null || n.bigContentView != null ? "yes" : "no") + ")";
            return;
        }
        StringBuilder all = new StringBuilder();
        for (String t : texts) all.append(t).append(" / ");
        String words = all.toString().toLowerCase(java.util.Locale.US);
        char dir = words.contains("u-turn") || words.contains("u turn") ? 't'
                : words.contains("roundabout") || words.contains("rotonda") ? 'o'
                : words.contains("left") || words.contains("kaliwa") ? 'L'
                : words.contains("right") || words.contains("kanan") ? 'r'
                : words.contains("arrive") || words.contains("destination") ? 'F'
                : words.contains("straight") || words.contains("continue") ? 'U'
                : arrowDir(arrow);
        Matcher m = DIST.matcher(words);
        boolean hasDist = m.find();
        String dist = hasDist ? m.group(1).replace(',', '.') : "";
        String unit = hasDist ? m.group(2) : "";
        float meters = -1;
        try {
            if (hasDist) {
                meters = Float.parseFloat(dist) * (unit.equals("km") ? 1000f : unit.equals("mi") ? 1609f
                        : unit.equals("ft") ? 0.3048f : unit.equals("yd") ? 0.9144f : 1f);
            }
        } catch (NumberFormatException ignored) { }
        if (dist.length() > 4) dist = dist.substring(0, 4);
        String head = dir == 'L' ? "L " : dir == 'r' ? "r " : dir == 'U' ? "UP" : dir == 't' ? "Ut"
                : dir == 'o' ? "rb" : dir == 'F' ? "En" : "GO";
        StringBuilder main = new StringBuilder(head);
        while (main.length() + dist.length() < 6) main.append(' ');
        main.append(dist);
        String day = unit.equals("km") ? "KM" : unit.equals("m") ? "M " : unit.equals("mi") ? "MI"
                : unit.equals("ft") ? "Ft" : unit.equals("yd") ? "Yd" : "  ";
        String seen = all.length() > 70 ? all.substring(0, 70) : all.toString();
        String read = (views ? "(views) " : "") + seen + "→ " + main.toString().trim() + " " + day.trim()
                + (arrow == null ? " (no arrow picture)" : "");
        // battery: the distance counts down every second. Only a new turn and a few distance
        // marks go to the watch: each whole km, then 500, 200, 100 and 50 m.
        int step = meters < 0 ? 0 : meters >= 1000 ? 100 + (int) (meters / 1000) : meters > 500 ? 5
                : meters > 200 ? 4 : meters > 100 ? 3 : meters > 50 ? 2 : 1;
        if (dir != navLast || step != navStep) {
            navNote = face.onNav(dir, main.toString(), day);
            if (navNote.startsWith("sent")) {      // not sent = tried again at the next update
                navLast = dir;
                navStep = step;
            }
        }
        lastNav = read + " · " + navNote;
    }

    /** The words and the biggest picture of a notification that draws its own layout. */
    private Bitmap readViews(Notification n, List<String> out) {
        try {
            RemoteViews rv = n.bigContentView != null ? n.bigContentView : n.contentView;
            if (rv == null) rv = Notification.Builder.recoverBuilder(this, n).createBigContentView();
            if (rv == null) return null;
            View root = rv.apply(getApplicationContext(), new FrameLayout(this));
            Drawable[] pic = new Drawable[1];
            walk(root, out, pic);
            return pic[0] == null ? null : render(pic[0]);
        } catch (Throwable e) {
            return null;
        }
    }

    private void walk(View v, List<String> out, Drawable[] pic) {
        if (v instanceof TextView) {
            String t = Txt.clean(((TextView) v).getText(), 80);
            if (!t.isEmpty() && out.size() < 8 && !out.contains(t)) out.add(t);
        } else if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d != null && (pic[0] == null
                    || d.getIntrinsicWidth() * d.getIntrinsicHeight() > pic[0].getIntrinsicWidth() * pic[0].getIntrinsicHeight())) {
                pic[0] = d;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), out, pic);
        }
    }

    private Bitmap iconBitmap(Notification n) {
        try {
            Icon ic = n.getLargeIcon();
            Drawable d = ic == null ? null : ic.loadDrawable(this);
            return d == null ? null : render(d);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap render(Drawable d) {
        final int w = 48;
        Bitmap b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, w);
        d.draw(new Canvas(b));
        return b;
    }

    /** Left, right or straight from the turn arrow: where its head (top) sits compared to its stem (bottom). */
    private static char arrowDir(Bitmap b) {
        if (b == null) return '?';
        final int w = b.getWidth();
        long top = 0, topN = 0, bot = 0, botN = 0, all = 0;
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                if (Color.alpha(b.getPixel(x, y)) < 128) continue;
                all++;
                if (y < w / 3) { top += x; topN++; }
                else if (y >= 2 * w / 3) { bot += x; botN++; }
            }
        }
        if (all > w * w * 7 / 10 || topN < 8 || botN < 8) return '?';     // not an arrow on a clear background
        float dx = (float) top / topN - (float) bot / botN;
        return dx < -w * 0.12f ? 'L' : dx > w * 0.12f ? 'r' : 'U';
    }

    /** The phone's own volume buttons (or any app) changed the volume. */
    private final BroadcastReceiver volChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            h.removeCallbacks(volNow);
            h.postDelayed(volNow, 120);
        }
    };

    // ---------- battery saver: batched messages ----------

    private final List<Face.Note> batch = new ArrayList<>();
    private final Runnable flushBatch = this::flushBatch;

    /** Sends everything held back in one go (oldest first, so the newest ends up on top). */
    private void flushBatch() {
        h.removeCallbacks(flushBatch);
        if (batch.isEmpty() || face == null) return;
        List<Face.Note> out = new ArrayList<>(batch);
        batch.clear();
        face.onBatch(out);
    }

    // ---------- battery saver: night link-off ----------

    private boolean tickReceiver;

    /** The face this app was built for, and the one the watch says it has (-1 not heard yet, 0 too old to say). */
    static final int FACE_VERSION = 3;
    static volatile int faceVersion = -1;
    private boolean offered;                      // the update was offered on this link already

    /** The Pulse face this app carries (assets/Pulse.min.js), or null. */
    static byte[] bundledFace(Context c) {
        try (java.io.InputStream in = c.getAssets().open("Pulse.min.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static int bundled = -2;

    /** The version the carried face says in its hello ('%vN'), or 0 if there's none. */
    static int bundledVersion(Context c) {
        if (bundled != -2) return bundled;
        byte[] js = bundledFace(c);
        bundled = 0;
        if (js != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("%v(\\d+)").matcher(new String(js, java.nio.charset.StandardCharsets.ISO_8859_1));
            if (m.find()) bundled = Integer.parseInt(m.group(1));
        }
        return bundled;
    }

    /** True when the face on the watch said an older version than the one this app carries. */
    static boolean faceOutdated(Context c) {
        return faceVersion >= 0 && faceVersion < bundledVersion(c);
    }

    /** Once per link (and not again for a day after "later"): the watch asks UPdAtE? */
    private void offerUpdate() {
        if (offered || face == null || !faceOutdated(this)) return;
        long later = getSharedPreferences("pulse", MODE_PRIVATE).getLong("upd_later", 0);
        if (System.currentTimeMillis() - later < 24 * 3600_000L) return;
        offered = true;
        List<String> l = new ArrayList<>();
        l.add("UPdAtE");
        l.add("LAtEr");
        face.onAsk(Face.UPDATE_ID, "nEW PULSE V" + bundledVersion(this), l);
    }

    void updateLater() {
        getSharedPreferences("pulse", MODE_PRIVATE).edit().putLong("upd_later", System.currentTimeMillis()).apply();
    }

    /** Installs the carried Pulse face into Forge C (the user said yes on the watch or in the app). */
    void updatePulse() {
        byte[] js = bundledFace(this);
        if (js == null) { installReport().step("nO FILE", "This app has no Pulse face inside", true); return; }
        FaceInstall.installPulse(this, js, installReport());
    }

    /** From the app: false if Pulse's service isn't running. */
    static boolean updatePulseNow() {
        NotifService s = self;
        if (s == null) return false;
        s.updatePulse();
        return true;
    }

    private final BroadcastReceiver nightTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            applyNight();
        }
    };

    /** Inside the night hours: drop the link. Outside: bring it back. Then wait for the next change. */
    void applyNight() {
        if (link == null) return;
        if (DayPack.holding) { h.postDelayed(this::applyNight, 60_000L); return; }   // a day pack install is using the link
        Profiles.auto(this);                       // a profile set to start at this hour
        if (Saver.isNight(this)) link.pause("Night link-off until " + Saver.hourName(Saver.to(this)));
        else link.resume();
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(this, 7,
                    new Intent("com.ollee.pulse.TICK").setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
            long at = Saver.nextBoundary(this), prof = Profiles.next(this);
            if (prof > 0 && (at <= 0 || prof < at)) at = prof;
            if (at > 0) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) { }
    }

    /** The app's macro wheel: run a slot. */
    static String runMacro(Context c, Macros.Slot m) {
        NotifService s = self;
        if (s != null && s.face != null) return s.face.runFromPhone(m);
        if (!Macros.MACRODROID.equals(m.kind)) return "Pulse isn't running: allow Notification access in Setup";
        Intent i = new Intent("com.ollee.pulse.MACRO");
        i.putExtra("slot", String.valueOf(m.number));
        i.putExtra("name", m.name);
        c.sendBroadcast(i);
        return m.name + ": sent to MacroDroid";
    }

    /** The app changed a battery-saver setting. */
    static void saverChanged() {
        NotifService s = self;
        if (s == null) return;
        s.applyNight();
        if (s.face != null) s.face.stateChanged();
        if (Saver.batchMs(s) == 0) s.flushBatch();
    }

    // ---------- lifecycle ----------

    private static NotifService self;

    @Override
    public void onListenerConnected() {
        self = this;
        link = WatchLink.get(this);
        if (face == null) face = new Face(this, h);
        if (!Party.watchers.contains(partyWatch)) Party.watchers.add(partyWatch);
        link.setCommandHandler(this);
        link.start();
        me = new ComponentName(this, NotifService.class);
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, me);
        } catch (SecurityException ignored) { }
        pickPlayer(null);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (!volReceiver) {
            IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volChanged, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(volChanged, f);
            volReceiver = true;
        }
        if (!tickReceiver) {
            IntentFilter f = new IntentFilter("com.ollee.pulse.TICK");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(nightTick, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(nightTick, f);
            tickReceiver = true;
        }
        applyNight();
        if (!packReceiver) {
            IntentFilter f = new IntentFilter(DayPack.ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(packAlarm, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(packAlarm, f);
            try {
                getContentResolver().registerContentObserver(android.provider.CalendarContract.CONTENT_URI, true, calChanged);
            } catch (Exception ignored) { }
            packReceiver = true;
        }
        DayPack.plan(this);
        if (!showReceiver) {
            // exported: MacroDroid is another app. Registered here because Android 8+
            // doesn't deliver this kind of broadcast to receivers in the manifest.
            IntentFilter f = new IntentFilter("com.ollee.pulse.SHOW");
            f.addAction("com.ollee.pulse.ASK");
            f.addAction("com.ollee.pulse.PLAY");
            f.addAction("com.ollee.pulse.LINK");
            f.addAction("com.ollee.pulse.INSTALL");
            f.addAction("com.ollee.pulse.SLOTS");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(showMsg, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(showMsg, f);
            showReceiver = true;
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        setPlayer(null);
        // keep handling watch buttons (media keys still work) and ask Android to bind us again
        requestRebind(new ComponentName(this, NotifService.class));
    }

    private final Runnable partyWatch = () -> h.post(() -> { if (face != null) face.partyChanged(); });

    // day pack: its alarm, and a fresh plan when the calendar changes (waits a little: edits come in bursts)
    private boolean packReceiver;
    private final BroadcastReceiver packAlarm = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { DayPack.run(c); }
    };
    private final Runnable replan = () -> DayPack.plan(this);
    private final android.database.ContentObserver calChanged = new android.database.ContentObserver(h) {
        @Override
        public void onChange(boolean self) {
            h.removeCallbacks(replan);
            h.postDelayed(replan, 20_000L);
        }
    };

    @Override
    public void onDestroy() {
        Party.watchers.remove(partyWatch);
        Party.end();
        if (pill != null) pill.hide();
        h.removeCallbacks(repick);
        watchAll(null);
        if (link != null) link.setCommandHandler(null);
        net.shutdownNow();
        if (volReceiver) {
            try { unregisterReceiver(volChanged); } catch (Exception ignored) { }
            volReceiver = false;
        }
        if (showReceiver) {
            try { unregisterReceiver(showMsg); } catch (Exception ignored) { }
            showReceiver = false;
        }
        if (tickReceiver) {
            try { unregisterReceiver(nightTick); } catch (Exception ignored) { }
            tickReceiver = false;
        }
        if (packReceiver) {
            try { unregisterReceiver(packAlarm); } catch (Exception ignored) { }
            try { getContentResolver().unregisterContentObserver(calChanged); } catch (Exception ignored) { }
            packReceiver = false;
        }
        h.removeCallbacks(flushBatch);
        super.onDestroy();
    }

    // ---------- music ----------

    /** For the setup screen: which player the watch buttons go to, and the last button. */
    static volatile String playerInfo = "no music app yet", lastButton = "none yet";

    /** App of the last player that was actually playing. */
    private String lastPkg;

    private List<MediaController> sessions() {
        try {
            return msm == null ? null : msm.getActiveSessions(me);
        } catch (SecurityException e) {
            return null;
        }
    }

    /**
     * The player the watch controls: one that is playing now; else the app that played
     * last (not just any app with a leftover player, e.g. YouTube in the background);
     * null = let Android route a media key to the last player itself.
     */
    private MediaController choose(List<MediaController> list) {
        // a music app picked in the app always wins (Symfonium, Poweramp...), playing or not
        String want = musicApp(this);
        if (!want.isEmpty()) {
            if (list != null) for (MediaController c : list) if (want.equals(c.getPackageName())) {
                lastPkg = want;
                return c;
            }
            lastPkg = want;
            return null;                                      // not open: its media button starts it
        }
        if (list == null || list.isEmpty()) return null;
        MediaController last = null, blank = null;
        for (MediaController c : list) {
            String p = c.getPackageName();
            if (NOT_PLAYERS.contains(p)) continue;
            PlaybackState s = c.getPlaybackState();
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            if (playing && hasSong(c)) {
                lastPkg = p;
                return c;
            }
            if (last == null && p.equals(lastPkg)) last = c;
            if (playing && blank == null) blank = c;
        }
        if (last != null) return last;
        // "playing" but says nothing yet: a player that's starting, or a volume-key helper.
        // Watched, but not remembered as the music app until it names a song.
        if (blank != null) return blank;
        if (lastPkg != null) return null;                 // the last app's player is gone
        for (MediaController c : list) if (!NOT_PLAYERS.contains(c.getPackageName()) && hasSong(c)) return c;
        return null;
    }

    /**
     * Apps that keep a "playing" media session that isn't music: volume-button helpers hold one
     * while the screen is off so they can catch the volume keys.
     */
    private static final java.util.Set<String> NOT_PLAYERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "app.maslanka.volumee"));

    /** The session says what's playing (a title, an artist or a length), like real players do. */
    private static boolean hasSong(MediaController c) {
        MediaMetadata md;
        try { md = c.getMetadata(); } catch (Exception e) { return false; }
        if (md == null) return false;
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE), a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return (t != null && !t.trim().isEmpty()) || (a != null && !a.trim().isEmpty())
                || md.getLong(MediaMetadata.METADATA_KEY_DURATION) > 0;
    }

    /** Every other player is watched too, so one that starts (or names its song) takes over. */
    private final java.util.List<MediaController> watched = new java.util.ArrayList<>();
    private final Runnable repick = () -> pickPlayer(null);
    private final MediaController.Callback otherCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { h.removeCallbacks(repick); h.postDelayed(repick, 400); }
        @Override public void onMetadataChanged(MediaMetadata m) { h.removeCallbacks(repick); h.postDelayed(repick, 400); }
    };

    private void watchAll(List<MediaController> list) {
        if (list != null && list.size() == watched.size()) {       // same sessions as before: nothing to do
            boolean same = true;
            for (int i = 0; i < list.size() && same; i++) same = list.get(i).getSessionToken().equals(watched.get(i).getSessionToken());
            if (same) return;
        }
        for (MediaController c : watched) try { c.unregisterCallback(otherCb); } catch (Exception ignored) { }
        watched.clear();
        if (list == null) return;
        for (MediaController c : list) {
            if (NOT_PLAYERS.contains(c.getPackageName())) { watched.add(c); continue; }   // kept for the comparison only
            try { c.registerCallback(otherCb, h); watched.add(c); } catch (Exception ignored) { }
        }
    }

    private void pickPlayer(List<MediaController> list) {
        if (list == null) list = sessions();
        watchAll(list);
        setPlayer(choose(list));
        pushSoon();
    }

    private void setPlayer(MediaController c) {
        if (ctl != null && c != null && ctl.getSessionToken().equals(c.getSessionToken())) {
            info();
            return;
        }
        if (ctl != null) ctl.unregisterCallback(playerCb);
        ctl = c;
        if (ctl != null) ctl.registerCallback(playerCb, h);
        info();
        snapNow();
    }

    private void info() {
        playerInfo = ctl == null ? (lastPkg == null ? "no music app yet" : appName(lastPkg) + " (via media key)")
                : appName(ctl.getPackageName()) + (isPlaying() ? " (playing)" : " (paused)");
    }

    private void pushSoon() {
        h.removeCallbacks(pushNow);
        h.postDelayed(pushNow, 300);
    }

    private boolean isPlaying() {
        PlaybackState s = ctl == null ? null : ctl.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** M<playing>|<position s>|<length s>|<artist - title> */
    private void pushMedia(boolean force) {
        if (link == null) return;
        refreshPlayer();
        String line, key;
        if (ctl == null || !Features.on(this, Features.MUSIC)) {
            line = "M0|0|0|";
            key = line;
        } else {
            PlaybackState ps = ctl.getPlaybackState();
            MediaMetadata md = ctl.getMetadata();
            boolean playing = isPlaying();
            long pos = 0;
            if (ps != null) {
                pos = ps.getPosition();
                if (playing && ps.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
            }
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String title = md == null ? "" : Txt.clean(md.getString(MediaMetadata.METADATA_KEY_TITLE), 60);
            String artist = md == null ? "" : Txt.clean(md.getString(MediaMetadata.METADATA_KEY_ARTIST), 30);
            String t = artist.isEmpty() ? title : title.isEmpty() ? artist : artist + " - " + title;
            t = Txt.clean(t, 70);
            if (t.isEmpty() && dur <= 0) {
                // nothing named: not music (a volume-key helper's session, or a player still starting).
                // The watch shows its clock instead of an app's package name.
                line = "M0|0|0|";
                key = line;
            } else {
                if (t.isEmpty()) t = appName(ctl.getPackageName());
                int bpm = playing ? bpmOf(t) : 0;
                line = "M" + (playing ? 1 : 0) + "|" + Math.max(0, pos / 1000) + "|" + Math.max(0, dur / 1000) + "|" + t
                        + (bpm > 0 ? "|" + bpm : "");
                key = (playing ? 1 : 0) + "|" + dur + "|" + t + "|" + bpm;    // ignore position-only changes
            }
        }
        if (!link.isReady()) return;           // not sent = not remembered, so it goes out on reconnect
        // battery saver: while another watch face is showing, song changes stay on the phone.
        // Not remembered either, so the latest goes out when the Pulse face opens (H).
        if (Saver.quiet(this) && face != null && !face.faceOn) return;
        if (!force && key.equals(lastMedia)) return;
        lastMedia = key;
        link.sendLine(line);
    }

    private void mediaKey(int code) {
        // the picked music app gets the key itself, even if it's closed (that's how it starts playing)
        String want = musicApp(this);
        if (!want.isEmpty() && sendKeyTo(want, code)) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /** A media button straight to one app's receiver. False if it has none. */
    private boolean sendKeyTo(String pkg, int code) {
        Intent probe = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg);
        List<android.content.pm.ResolveInfo> rs = getPackageManager().queryBroadcastReceivers(probe, 0);
        if (rs == null || rs.isEmpty()) return false;
        ComponentName cn = new ComponentName(rs.get(0).activityInfo.packageName, rs.get(0).activityInfo.name);
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cn);
            i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, code));
            try { sendBroadcast(i); } catch (Exception e) { return false; }
        }
        return true;
    }

    /** The music app the watch controls ("" = whichever is playing). */
    static String musicApp(Context c) {
        return c.getSharedPreferences("pulse", MODE_PRIVATE).getString("music_pkg", "");
    }

    static void musicApp(Context c, String pkg) {
        c.getSharedPreferences("pulse", MODE_PRIVATE).edit().putString("music_pkg", pkg).apply();
        NotifService s = self;
        if (s != null) s.h.post(() -> s.pickPlayer(null));
    }

    /** Apps that take media buttons or have a player open now: {package, name}. */
    static List<String[]> musicApps(Context c) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        android.content.pm.PackageManager pm = c.getPackageManager();
        try {
            for (android.content.pm.ResolveInfo r : pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0)) {
                String p = r.activityInfo.packageName;
                if (!m.containsKey(p)) m.put(p, String.valueOf(r.loadLabel(pm)));
            }
        } catch (Exception ignored) { }
        NotifService s = self;
        List<MediaController> live = s == null ? null : s.sessions();
        if (live != null) for (MediaController mc : live) {
            String p = mc.getPackageName();
            if (!m.containsKey(p)) {
                try { m.put(p, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)))); }
                catch (Exception e) { m.put(p, p); }
            }
        }
        m.remove(c.getPackageName());
        List<String[]> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) out.add(new String[]{e.getKey(), e.getValue()});
        java.util.Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    // ---------- watch buttons ----------

    /** Re-reads the players before every button: a paused app can close its player. */
    private void refreshPlayer() {
        setPlayer(choose(sessions()));
    }

    /** The app's Test button: a notification through the normal path. */
    static void test(Context c) {
        NotifService s = self;
        if (s != null && s.face != null) s.face.onNotification("PULSE", "PULSE", Txt.seg("Hello from your phone"));
        else WatchLink.get(c).sendLine("N0| 1|PULSE|PULSE|" + Txt.seg("Hello from your phone"));
    }

    // ---------- volume ----------

    /**
     * Tells the screen controller the media volume. external = the phone's own
     * buttons or another app changed it (the watch pops its bar up briefly).
     */
    private void pushVolume(boolean safe, boolean external) {
        if (face == null || audio == null || !Features.on(this, Features.VOLUME)) return;
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int mx = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean mute = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        String key = v + "|" + mx + "|" + mute;
        if (external && key.equals(lastVol)) return;
        lastVol = key;
        face.volume(v, mx, mute, safe, external);
    }

    /** One step up (1) or down (-1), from the watch. */
    void volume(int dir) {
        if (audio == null) return;
        final int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        final int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (dir > 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        h.postDelayed(() -> {
            // going up but nothing moved below the max = the hearing-safety limit said no
            boolean blocked = dir > 0 && before < max
                    && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == before;
            pushVolume(blocked, false);
        }, 150);
    }

    void toggleMute() {
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
        h.postDelayed(() -> pushVolume(false, false), 150);
    }

    // ---------- YouTube search ----------

    /** Up to 5 YouTube suggestions for the typed text (empty list if none or offline). */
    void suggestFor(final String q, final Face.Got cb) {
        if (q.isEmpty() || !Features.on(this, Features.SEARCH)) {
            cb.got(new ArrayList<>());
            return;
        }
        net.execute(() -> {
            List<String> out = new ArrayList<>();
            try {
                for (String x : YouTube.suggest(q)) {
                    String t = Txt.clean(x, 28);
                    if (!t.isEmpty()) out.add(t);
                    if (out.size() == 5) break;
                }
            } catch (Exception ignored) { }
            h.post(() -> cb.got(out));
        });
    }

    /** Finds the top video for q and opens it in YouTube (ReVanced first). */
    void playFor(final String q, final Face.Played cb) {
        if (!Features.on(this, Features.SEARCH)) { cb.done(false, "OFF"); return; }
        if (!Settings.canDrawOverlays(this)) {
            cb.done(false, "ALLOW");            // Android blocks opening apps from the background
            return;
        }
        final String app = YouTube.installedApp(this);
        if (app == null) { cb.done(false, "nO yt"); return; }
        final String key = getSharedPreferences("pulse", MODE_PRIVATE).getString("ytkey", "");
        net.execute(() -> {
            String id = null, why = "nO HIt";
            try {
                id = YouTube.findVideo(q, key);
            } catch (Exception e) {
                why = "nO nEt";
            }
            final String vid = id, fail = why;
            h.post(() -> {
                if (vid == null) { cb.done(false, fail); return; }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + vid));
                    i.setPackage(app);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    lastButton = "Search \u2192 " + q;
                    cb.done(true, "");
                    h.postDelayed(() -> { refreshPlayer(); pushMedia(true); }, 3000);
                } catch (Exception e) {
                    cb.done(false, "nO yt");
                }
            });
        });
    }

    // ---------- Ask AI by voice ----------

    /** Opens the listening window; returns why it can't (for the watch), or null. */
    String startVoice(VoiceActivity.Ear ear) {
        if (getSharedPreferences("pulse", MODE_PRIVATE).getString("gemkey", "").trim().isEmpty()) return "nO KEY";
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "nO MIC";
        if (!Settings.canDrawOverlays(this)) return "ALLOW";
        VoiceActivity.ear = ear;
        try {
            Intent i = new Intent(this, VoiceActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(i);
        } catch (Exception e) {
            return "ALLOW";
        }
        return null;
    }

    /** Gemini's answer, row by row as it is written, then done; all on the main thread. */
    void askFor(final String q, final Face.Answers cb) {
        heard(q);
        askWith(rows -> Gemini.ask(this, q, rows), cb);
    }

    /** The same for a recorded question: Gemini hears it; row 1 is "Q <what it heard>". */
    void askAudio(final byte[] wav, final Face.Answers cb) {
        final boolean[] first = {true};
        askWith(rows -> Gemini.askAudio(this, wav, r -> {
            if (first[0]) { first[0] = false; heard(r.startsWith("Q ") ? r.substring(2) : r); }
            rows.row(r);
        }), cb);
    }

    /** The same for a photo from the app; row 1 is "Q <what it answers>". */
    void askImage(final byte[] jpeg, final String q, final Face.Answers cb) {
        final boolean[] first = {true};
        askWith(rows -> Gemini.askImage(this, jpeg, q, new Gemini.Rows() {
            @Override public void row(String r) {
                if (first[0]) { first[0] = false; heard(r.startsWith("Q ") ? r.substring(2) : r); }
                rows.row(r);
            }
            @Override public void status(String st) { rows.status(st); }
        }), cb);
    }

    /**
     * From the app's photo card: Gemini answers on the watch and in the app.
     * The watch link lives here in the service, not in the app's screen, so it stays up while
     * the camera or gallery is open; if it was down, it is reconnected first.
     * Returns why it can't, or null. Main thread.
     */
    static String askPhoto(byte[] jpeg, String q, Face.Answers app) {
        NotifService s = self;
        if (s == null || s.face == null) return "Pulse isn't running: allow Notification access in Setup";
        if (s.getSharedPreferences("pulse", MODE_PRIVATE).getString("gemkey", "").trim().isEmpty())
            return "Add your Gemini key first";
        WatchLink w = WatchLink.get(s);
        if (!w.isReady()) w.connect();
        s.face.askPhoto(jpeg, q, app);
        return null;
    }

    /** Shared text to the watch (from ShareActivity); false if Pulse isn't running. Main thread. */
    static boolean showShared(java.util.List<String> rows) {
        NotifService s = self;
        if (s == null || s.face == null) return false;
        WatchLink w = WatchLink.get(s);
        if (!w.isReady()) w.connect();
        s.heard("Shared: " + rows.get(0));
        s.face.showShared(rows);
        return true;
    }

    /** What the last question was heard as, shown in the app. */
    private void heard(String q) {
        getSharedPreferences("pulse", MODE_PRIVATE).edit().putString("lastheard", q).apply();
    }

    private interface Call { void run(Gemini.Rows rows) throws Exception; }

    private void askWith(final Call call, final Face.Answers cb) {
        net.execute(() -> {
            String why = null;
            try {
                call.run(new Gemini.Rows() {
                    @Override public void row(String r) { h.post(() -> cb.row(r)); }
                    @Override public void status(String st) { h.post(() -> cb.status(st)); }
                });
                getSharedPreferences("pulse", MODE_PRIVATE).edit().remove("gemerr").apply();
            } catch (Exception e) {
                String m = e.getClass().getSimpleName() + ": " + e.getMessage();
                getSharedPreferences("pulse", MODE_PRIVATE).edit()
                        .putString("gemerr", new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                                .format(new java.util.Date()) + "  " + m).apply();
                why = m.contains("HTTP 429") ? "AI LIMIt"
                        : m.contains("HTTP 401") || m.contains("HTTP 403") ? "AI KEY"
                        : m.contains("HTTP 5") ? "AI bUSY"
                        : m.contains("EMPTY") ? "AI EMPtY"
                        : e instanceof java.net.SocketTimeoutException ? "AI SLOW"
                        : e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                                || e instanceof java.net.ConnectException ? "AI nO nEt"
                        : "AI ErrOr";
            }
            final String w = why;
            h.post(() -> cb.done(w));
        });
    }

    // ---------- Listen: what song is playing around me? ----------

    /** *: open the listening window (Android only allows the mic while the app is on screen). */
    private void listen() {
        if (link == null) return;
        String why = null;
        if (getSharedPreferences("pulse", MODE_PRIVATE).getString("gemkey", "").trim().isEmpty()) why = "nO KEY";
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) why = "nO MIC";
        else if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) why = "ALLOW";
        if (why != null) {
            link.sendLine("L0|" + why);
            return;
        }
        try {
            Intent i = new Intent(this, ListenActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(i);
        } catch (Exception e) {
            link.sendLine("L0|ALLOW");
        }
    }

    /** Called by the listening window with the clip, or null if recording failed. */
    static void recorded(File clip) {
        NotifService s = self;
        if (s != null) s.identify(clip);
    }

    private void identify(final File clip) {
        if (link == null) return;
        if (clip == null) {
            link.sendLine("L0|nO MIC");
            return;
        }
        net.execute(() -> {
            String line;
            try {
                Gemini.Result r = Gemini.identify(this, clip);
                List<String> out = new ArrayList<>();
                for (String s : r.songs) {
                    String t = Txt.seg(Txt.clean(s, 40));
                    if (!t.isEmpty()) out.add(t);
                }
                // the lyric line is a last guess: YouTube usually finds a song from its words
                String lyr = Txt.seg(Txt.clean(r.lyrics, 40));
                if (!lyr.isEmpty() && out.size() < 4) out.add(lyr);
                if (out.isEmpty()) {
                    line = "L0|nOSOnG";
                } else {
                    StringBuilder b = new StringBuilder("L" + out.size());
                    for (String t : out) b.append('|').append(t);
                    line = b.toString();
                }
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                line = "L0|" + (m.contains("HTTP 429") ? "LIMIt" : m.contains("HTTP 4") ? "bAdKEY" : "nO nEt");
            }
            final String send = line;
            h.post(() -> { if (link != null) link.sendLine(send); });
        });
    }

    @Override
    public void onCommand(char c) {
        // %volts arrives as a whole line; everything else is one letter
        if (inLine && SystemClock.elapsedRealtime() - lineStart > 1500) inLine = false;   // never stay stuck
        if (inLine) {
            if (c == '\n') {
                inLine = false;
                String got = cmdLine.substring(1);
                if (got.startsWith("v")) {                 // the face's version, sent with its hello
                    try {
                        faceVersion = Integer.parseInt(got.substring(1).trim());
                    } catch (NumberFormatException ignored) { }
                    h.postDelayed(this::offerUpdate, 3000);  // after the face has settled
                    return;
                }
                Battery.add(this, got);
                Battery.remind(this);
                if (face != null) face.stateChanged();     // the new reading may switch saver mode
            } else if (cmdLine.length() < 40) {
                cmdLine.append(c);
            }
            return;
        }
        if (c == '%') {
            inLine = true;
            lineStart = SystemClock.elapsedRealtime();
            cmdLine.setLength(0);
            cmdLine.append(c);
            return;
        }
        if ("aAlLeHX".indexOf(c) < 0) return;
        if (c == 'H' || c == 'X') History.face(c == 'H');
        if ("aAlL".indexOf(c) >= 0) History.press(c);
        // a face too old to say its version stays silent after its hello
        if (c == 'H' && faceVersion < 0) h.postDelayed(() -> {
            if (faceVersion < 0) {
                faceVersion = 0;
                offerUpdate();
            }
        }, 4000);
        if (link != null) link.setFaceOn(c != 'X');   // X = the Pulse face left the screen
        if (face != null && c == 'H') face.faceOn = true;      // before the music below is pushed
        if (c == 'H') {                               // the face came on: fresh music and volume
            refreshPlayer();
            pushMedia(true);
            pushVolume(false, false);
        }
        if (face != null) face.onEvent(c);
    }

    /** P play/pause, N next, B previous. */
    void music(char c) {
        if (!Features.on(this, Features.MUSIC)) return;
        doMusic(c);
    }

    private void doMusic(char c) {
        refreshPlayer();
        lastButton = (c == 'P' ? "Play/pause" : c == 'N' ? "Next" : "Previous") + " \u2192 "
                + (ctl == null ? "media key" : appName(ctl.getPackageName()));
        if (c == 'P') {
            final boolean wasPlaying = isPlaying();
            if (ctl == null) {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            } else {
                if (wasPlaying) ctl.getTransportControls().pause();
                else ctl.getTransportControls().play();
                // if the app ignored it, press the system media key instead
                h.postDelayed(() -> {
                    refreshPlayer();
                    if (isPlaying() == wasPlaying) {
                        mediaKey(wasPlaying ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY);
                    }
                    pushMedia(true);
                }, 1200);
            }
        } else if (c == 'N' || c == 'B') {
            final int key = c == 'N' ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            if (ctl == null) {
                mediaKey(key);
            } else {
                final String before = now.title + "|" + now.position() / 3000;
                if (c == 'N') ctl.getTransportControls().skipToNext();
                else ctl.getTransportControls().skipToPrevious();
                // some players ignore the session's skip: press the media key instead
                h.postDelayed(() -> {
                    snapNow();
                    String after = now.title + "|" + now.position() / 3000;
                    if (before.equals(after)) mediaKey(key);
                }, 1500);
            }
        }
        pushSoon();
    }

    @Override
    public void onReady() {
        faceVersion = -1;                          // it may be a different face since the last link
        offered = false;
        h.postDelayed(() -> profileStep(0), 6000); // a profile's faces that couldn't go out yet
        pickPlayer(null);
        h.postDelayed(() -> pushMedia(true), 400);
        h.postDelayed(() -> pushVolume(false, false), 700);
        // The face says hello (H) 1.5 s after it sees the link, which can be before this side is
        // listening. Ask: a face that is showing answers with another H.
        h.postDelayed(() -> {
            if (link != null && face != null && !face.faceOn) link.sendLine("?");
        }, 1200);
    }

    // ---------- notifications ----------

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // the night hours ended while the phone slept through the alarm: reconnect now
        if (link != null && link.isPaused() && !Saver.isNight(this)) link.resume();
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (n == null || pkg.equals(getPackageName())) return;
        if (MAPS.equals(pkg)) {                    // for the app's Maps line: say why a turn can't show
            if (!isNav(pkg, n)) {
                if (navStep < 0) lastNav = "a Maps notification that isn't navigation (flags "
                        + Integer.toHexString(n.flags) + ", category " + n.category + ")";
            } else if (link == null || !link.isReady()) {
                lastNav = "navigation seen, but the watch isn't connected";
            }
        }
        if (link == null || !link.isReady()) return;
        // Google Maps while navigating: an ongoing notification that turns into the next turn
        if (isNav(pkg, n)) {
            if (face != null && Features.on(this, Features.NAV)) nav(n);
            else lastNav = "Turn-by-turn is switched off in FEATURES";
            return;
        }
        Bundle ex = n.extras;
        String title = Txt.clean(ex.getCharSequence(Notification.EXTRA_TITLE), 40);
        String text = Txt.clean(ex.getCharSequence(Notification.EXTRA_TEXT), 100);

        // calls: only INCOMING ringing calls. Wait a moment so the phone's audio mode has
        // settled: an outgoing or answered call is "in call", a ringing one is not.
        if (Notification.CATEGORY_CALL.equals(n.category)) {
            if (!Features.on(this, Features.CALLS)) return;
            final String key = sbn.getKey();
            final boolean fullScreen = n.fullScreenIntent != null;
            final String caller = Txt.clean(title.isEmpty() ? text : title, 30);
            h.postDelayed(() -> checkCall(key, fullScreen, caller), 700);
            return;
        }

        if (!Features.on(this, Features.NOTIFICATIONS)) return;

        // skip summaries, ongoing things (music, downloads, navigation) and system noise
        if ((n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_FOREGROUND_SERVICE)) != 0) return;
        String cat = n.category;
        if (Notification.CATEGORY_TRANSPORT.equals(cat) || Notification.CATEGORY_SERVICE.equals(cat)
                || Notification.CATEGORY_PROGRESS.equals(cat) || Notification.CATEGORY_SYSTEM.equals(cat)
                || Notification.CATEGORY_STATUS.equals(cat)) return;

        // chat apps: take the newest message and its sender
        String who = title;
        boolean inGroup = ex.getBoolean("android.isGroupConversation", false);
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle b = (Bundle) msgs[msgs.length - 1];
            String mtext = Txt.clean(b.getCharSequence("text"), 100);
            if (!mtext.isEmpty() || b.getCharSequence("text") != null) text = mtext;
            String sender = Txt.clean(b.getCharSequence("sender"), 24);
            if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                Object p = b.get("sender_person");
                if (p instanceof Person) sender = Txt.clean(((Person) p).getName(), 24);
            }
            String group = Txt.clean(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE), 20);
            if (!group.isEmpty() && !group.equals(sender)) inGroup = true;
            if (!sender.isEmpty()) who = group.isEmpty() || group.equals(sender) ? sender : sender + " in " + group;
        }

        boolean hadText = ex.getCharSequence(Notification.EXTRA_TEXT) != null;
        if (text.isEmpty() && hadText) text = "(emoji)";       // emoji-only messages
        if (who.isEmpty() && text.isEmpty()) return;

        String tag = Txt.tag(pkg);
        if (tag == null) tag = Txt.shortName(appName(pkg));
        who = Txt.clean(who, 30);
        // a carrier short code (8080, 222...) answering a macro: the macro shows the answer itself
        if (face != null && face.recentMacro() && who.matches("\\d{3,5}")) return;
        Saver.seen(this, pkg, appName(pkg));          // for the app's list of apps to choose from
        if (!Saver.allowed(this, pkg)) return;        // battery saver: only the chosen apps
        if (!Rules.pass(this, pkg, who, text, inGroup)) return;      // keyword rules
        if (isRepeat(tag + "|" + who + "|" + text)) return;
        if (face == null) return;
        Drain.note(this);
        long wait = Saver.batchMs(this);
        if (wait <= 0) {
            face.onNotification(Txt.seg(tag), Txt.seg(who), Txt.seg(text));
            return;
        }
        // battery saver: hold it, and send everything together when the time is up
        batch.add(new Face.Note(Txt.seg(tag), Txt.seg(who), Txt.seg(text)));
        if (batch.size() == 1) h.postDelayed(flushBatch, wait);
        else if (batch.size() >= 10) flushBatch();
    }

    private void checkCall(String key, boolean fullScreen, String caller) {
        if (link == null) return;
        int mode = ((AudioManager) getSystemService(AUDIO_SERVICE)).getMode();
        boolean talking = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        boolean ringing = !talking && (fullScreen || mode == AudioManager.MODE_RINGTONE);
        if (ringing && !key.equals(ringKey)) {
            ringKey = key;
            if (face != null) face.onCall(Txt.seg(caller));
        } else if (!ringing && key.equals(ringKey)) {
            ringKey = null;                       // answered: stop ringing on the watch
            if (face != null) face.onCallEnd();
            unmuteRinger();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Maps sometimes takes its notification down and puts a new one up. Only call it the
        // end of the trip if, a few seconds later, no navigation notification is left.
        if (face != null && sbn.getNotification() != null && isNav(sbn.getPackageName(), sbn.getNotification())) {
            h.removeCallbacks(navGone);
            h.postDelayed(navGone, 6000);
        }
        if (sbn.getKey().equals(ringKey)) {
            ringKey = null;
            if (face != null) face.onCallEnd();
        }
    }

    /** Apps often re-post the same notification; only send it once a minute. */
    private boolean isRepeat(String line) {
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue() > 60000) it.remove();
        if (seen.containsKey(line)) return true;
        seen.put(line, now);
        return false;
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
