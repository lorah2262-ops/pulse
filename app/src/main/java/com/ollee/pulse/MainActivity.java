package com.ollee.pulse;

import android.Manifest;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The app's screens: five tabs of frosted cards. Everything else happens in NotifService and WatchLink. */
public class MainActivity extends Activity implements WatchLink.Listener {

    // kept for other classes that used the old names
    static final int ACCENT = Glass.ACCENT;

    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL),
            MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Glass.Aurora aurora;
    private Glass.Dot dot;
    private TextView statusText, musicText, buttonText, voltText, voltSub;
    private Spark spark;
    private LinearLayout checklist;
    private EditText keyField, aiField;
    private TextView keyState, aiState, lastHeard, photoAns;
    private EditText photoQ;
    private ImageView photoImg;
    private static final int REQ_CAMERA = 41, REQ_GALLERY = 42;
    private android.media.MediaPlayer player;
    private Section setupSec, keySec, macroSec, aiSec, appsSec;
    private TextView trigHold, trigDouble;
    private Section photoSection;
    private EditText animField;
    private LinearLayout appRows;
    private int ticks;
    // the five tabs: Home, Watch, Messages, Macros, Battery
    private static final int HOME = 0, WATCH = 1, MESSAGES = 2, MACROS = 3, BATTERY = 4, FACES = 5, TAP = 6;
    private final LinearLayout[] pages = new LinearLayout[7];
    private final ScrollView[] scrolls = new ScrollView[7];
    private final LinearLayout[] tabViews = new LinearLayout[7];
    private final TextView[] tabLabels = new TextView[7];
    private final Glass.TabIcon[] tabIcons = new Glass.TabIcon[7];
    // the Tap tab (the watch's NFC tag)
    private TextView tapNfc, tapTag, tapLast;
    private final List<View> tapChecks = new ArrayList<>();
    private LinearLayout tapMacroRow, tapSmart;
    private TextView tapMacroChip;
    private Runnable tagSheet;
    private boolean readerOn;
    private FrameLayout tabBar;
    private Glass.Bubble tabBlob;              // the blue capsule that slides under the chosen tab
    private float blobL, grabAt;               // its left edge now; where on it the finger took hold
    private int hover = -1;                    // the tab it is being carried over
    private FrameLayout rootView;
    private Glass.Droplet live;
    private Glass.Track voltTrack, nightTrack;
    private Runnable menuClose;
    private Glass.LinkPill linkBtn;
    private Glass.RollText voltRoll, usageCount;
    private Glass.Ring usageRing;
    private LinearLayout timeRows, sentRows;
    private long timeMark = -1, sentMark = -1;
    // stage 3
    private static final int REQ_EXPORT = 43, REQ_IMPORT = 44, REQ_FACES = 45, REQ_PAGE = 46;
    // the mini player on Home
    private LinearLayout nowCard, nowFull;
    private int nowHomeIndex = -1;
    private boolean nowTop;
    private long nowPausedAt;            // when the music paused (elapsedRealtime), 0 = playing
    private ImageView nowBack, nowBack2;
    private FrameLayout nowFrame;
    private ImageView nowBackTop;        // the blurred layer showing now
    private String nowKey = "";          // the song shown, to notice a change
    private int nowDir = 1;              // which way the next change slides: 1 = next, -1 = previous
    private TextView nowAppChip;
    private TextView nowAt, nowLeft, nowPillChip, nowApp;
    private LinearLayout nowSmall;
    /** The covers: [0] before, [1] middle, [2] next, [3] a spare waiting off to the side. */
    private final Cover[] covers = new Cover[4];
    private float coverDrag;             // a live swipe, in card widths
    private int spareAt = 2;             // where the spare cover rests (off to one side)
    private int nowAhead;                // the covers already moved for a tap (1 / -1), before the song changed
    private long nowAheadAt;
    private ValueAnimator coverAnim;
    private String settleKey = "";
    private final Runnable nowSettle = () -> { settleKey = ""; songChanged(); };
    private static final android.view.animation.Interpolator SMOOTH =
            new android.view.animation.PathInterpolator(0.25f, 0.1f, 0f, 1f);
    private View nowShade;
    private android.graphics.Bitmap nowArtShown;
    private View nowFill, nowRest;
    private MediaBtn nowPlay;
    private final Runnable nowChanged = () -> ui.post(this::showNow);
    private TextView facesDir, faceState, faceLink, faceCount;
    private LinearLayout backupList, profFaces;
    private LinearLayout faceList, faceTiles, faceBarFill;
    private View faceBarRest, faceBar;
    private Glass.Lcd lcd;
    private Glass.Track usageBar;
    private TextView mirrorWhat, versionText, forecastText, usageText, usageSum, timelineText, sentText;
    private Section timeSec, sentSec;
    private Glass.DragList menuRows;
    private final Runnable lcdStep = new Runnable() {
        @Override
        public void run() {
            if (lcd != null) lcd.step();
            ui.postDelayed(this, 300);
        }
    };

    interface Words { void now(String s); }
    private ValueAnimator blobRun;
    private int tab = -1;
    private final TextView[][] drainCells = new TextView[2][6];
    private TextView drainSpan;
    private Glass.DrainChart drainChart;
    private TextView tileSaver, tileMsgs, tileTrig, tileDrain;
    private LinearLayout macroRows;
    private TextView saveMacrosBtn;
    private List<Macros.Slot> slots;
    private final List<EditText> macroNames = new ArrayList<>();
    private float voltShown = Float.NaN;
    private ValueAnimator voltRun;
    private int cards;
    private String status = "Not connected";

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String playing = NotifService.playerInfo;
            setIfNew(musicText, playing == null || playing.isEmpty() ? "Nothing playing" : playing);
            nowTick();
            String last = "Last  " + NotifService.lastButton
                    + (NotifService.lastNav.isEmpty() ? "" : "\nMaps  " + NotifService.lastNav);
            if (!last.contentEquals(buttonText.getText()))
                buttonText.setText(Glass.arrows(MainActivity.this, last, Glass.MUTED, buttonText.getTextSize()));
            showBattery();
            showMirror();
            if (ticks++ % 5 == 0) {
                showLogs();
                showDrain();
                showTiles();
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // draw behind the status and navigation bars: the glass floats over the colour
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        // a light screen: dark clock and icons in the status and navigation bars
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController bars = getWindow().getDecorView().getWindowInsetsController();
            int dark = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (bars != null) bars.setSystemBarsAppearance(dark, dark);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        aurora = new Glass.Aurora(this);
        root.addView(aurora, new FrameLayout.LayoutParams(-1, -1));

        // one column of cards per tab; each of the other tabs opens with its name
        final String[] pageName = {"", "Watch", "Messages", "Macros", "Battery", "Faces", "Tap"};
        final String[] pageSub = {"", "Features, animations and Ask AI", "Which apps reach your watch",
                "Run things on your phone from the watch", "The watch's coin cell and what drains it",
                "Swap the faces on your watch", "Your watch's NFC tag, as a shortcut"};
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            if (i == HOME) continue;
            TextView k = text("PULSE", 11, Glass.MUTED);
            k.setTypeface(MEDIUM);
            k.setLetterSpacing(0.24f);
            pages[i].addView(k);
            TextView pt = text(pageName[i], 34, Glass.TEXT);
            pt.setTypeface(MEDIUM);
            pages[i].addView(pt);
            TextView ps = text(pageSub[i], 14, Glass.SOFT);
            ps.setPadding(0, 0, 0, dp(6));
            pages[i].addView(ps);
        }
        LinearLayout col = pages[HOME];      // the tab the cards below are added to

        // ---- header ----
        TextView kicker = text("OLLEE  ·  PULSE 2", 11, Glass.MUTED);   // Home's own header
        kicker.setTypeface(MEDIUM);
        kicker.setLetterSpacing(0.24f);
        col.addView(kicker);
        TextView title = text("Pulse", 40, Glass.TEXT);
        title.setTypeface(MEDIUM);
        title.setLetterSpacing(-0.02f);
        col.addView(title);
        TextView sub = text("Your watch, talking to your phone.", 15, Glass.SOFT);
        sub.setPadding(0, 0, 0, dp(10));
        col.addView(sub);
        Glass.enter(kicker, 0);
        Glass.enter(title, 0);
        Glass.enter(sub, 1);

        // ---- status ----
        LinearLayout status = card(col, 28);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        dot = new Glass.Dot(this);
        row.addView(dot);
        statusText = text("Not connected", 20, Glass.TEXT);
        statusText.setTypeface(MEDIUM);
        statusText.setPadding(dp(8), 0, 0, 0);
        row.addView(statusText, weight());
        status.addView(row);
        buttonText = text("", 12, Glass.MUTED);
        buttonText.setPadding(0, dp(10), 0, 0);
        status.addView(buttonText);
        versionText = text("", 13, Glass.WARN);
        versionText.setPadding(0, dp(8), 0, 0);
        versionText.setVisibility(View.GONE);
        versionText.setOnClickListener(v -> pulseSheet());
        status.addView(versionText);
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(16), 0, 0);
        // one button that is Connect or Disconnect, whichever makes sense now
        linkBtn = new Glass.LinkPill(this);
        linkBtn.setOnClickListener(v -> {
            WatchLink w = WatchLink.get(this);
            if (w.isWanted()) w.disconnect(); else w.connect();
        });
        btns.addView(linkBtn, new LinearLayout.LayoutParams(0, -2, 2f));
        btns.addView(gap());
        btns.addView(pill("Test", false, v -> {
            NotifService.test(this);
            testMorph(v);
        }), weight());
        status.addView(btns);

        // ---- profiles: School, Home, Sleep ----
        Section profSec = section(col, "PROFILES", true);
        final int nowProf = Profiles.current(this);
        profSec.summary.setText(nowProf < 0 ? "None yet" : Profiles.NAMES[nowProf]);
        profSec.body.addView(note("One tap sets the feature switches and battery savers for where you are. "
                + "Hold one to save the switches as they are now into it."));
        LinearLayout profRow = new LinearLayout(this);
        profRow.setPadding(0, dp(8), 0, dp(8));
        for (int i = 0; i < Profiles.NAMES.length; i++) {
            final int which = i;
            TextView profBtn = pill(Profiles.NAMES[i], i == nowProf, v -> {
                Profiles.apply(this, which);
                recreate();
            });
            profBtn.setOnLongClickListener(v -> {
                Profiles.save(this, which);
                Toast.makeText(this, "Saved these switches as " + Profiles.NAMES[which], Toast.LENGTH_SHORT).show();
                recreate();
                return true;
            });
            if (i > 0) profRow.addView(gap());
            profRow.addView(profBtn, weight());
        }
        profSec.body.addView(profRow);
        final String[] startNames = new String[25];
        startNames[0] = "Off";
        for (int i = 0; i < 24; i++) startNames[i + 1] = Saver.hourName(i);
        for (int i = 0; i < Profiles.NAMES.length; i++) {
            final int which = i;
            final TextView startChip = chip(startNames[Profiles.at(this, i) + 1]);
            startChip.setOnClickListener(v -> morph(startChip, startNames, Profiles.at(this, which) + 1, n -> {
                Profiles.at(this, which, n - 1);
                swapText(startChip, startNames[n]);
                NotifService.saverChanged();           // sets the alarm for it
            }));
            profSec.body.addView(hairline());
            profSec.body.addView(lineRow(Profiles.NAMES[i] + " starts by itself", "At this hour, every day", startChip));
        }

        // ---- now playing: the one filled card; a mini player at the top while music plays ----
        buildNowCard(col);

        // ---- shortcuts into the other tabs ----
        LinearLayout tiles1 = tileRow(col), tiles2 = tileRow(col);
        tileSaver = tile(tiles1, "Saver mode", BATTERY);
        tileMsgs = tile(tiles1, "Messages", MESSAGES);
        tileTrig = tile(tiles2, "Trigger", MACROS);
        tileDrain = tile(tiles2, "Drain", BATTERY);

        // ---- watch battery ----
        col = pages[BATTERY];
        cards = 0;
        LinearLayout batt = card(col, 24);
        batt.addView(label("WATCH BATTERY"));
        voltText = text("", 1, Glass.TEXT);              // kept for older code paths; the number below rolls
        voltRoll = new Glass.RollText(this, 44, Glass.TEXT, LIGHT);
        voltRoll.set("--", false);
        batt.addView(voltRoll);
        voltSub = text("", 13, Glass.MUTED);
        batt.addView(voltSub);
        spark = new Spark(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(64));
        sp.topMargin = dp(10);
        batt.addView(spark, sp);
        forecastText = text("", 15, Glass.TEXT);
        forecastText.setTypeface(MEDIUM);
        forecastText.setPadding(0, dp(10), 0, dp(2));
        batt.addView(forecastText);
        final String[] emptyNames = {"2.40 V", "2.50 V", "2.60 V", "2.70 V"};
        final TextView emptyChip = chip(emptyNames[Math.max(0, Math.min(3, prefs().getInt("bat_empty", 2)))]);
        emptyChip.setOnClickListener(v -> morph(emptyChip, emptyNames, Math.max(0, Math.min(3, prefs().getInt("bat_empty", 2))), n -> {
            prefs().edit().putInt("bat_empty", n).apply();
            swapText(emptyChip, emptyNames[n]);
            showBattery();
        }));
        batt.addView(lineRow("Counts as empty at", "Where your watch stops working", emptyChip));
        final String[] warnNames = {"Off", "3 days left", "5 days left", "7 days left", "14 days left"};
        final TextView warnChip = chip(warnNames[Math.max(0, Math.min(4, prefs().getInt("bat_warn", 2)))]);
        warnChip.setOnClickListener(v -> morph(warnChip, warnNames, Math.max(0, Math.min(4, prefs().getInt("bat_warn", 2))), n -> {
            prefs().edit().putInt("bat_warn", n).remove("bat_told").apply();
            swapText(warnChip, warnNames[n]);
            if (n > 0 && Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
        }));
        batt.addView(hairline());
        batt.addView(lineRow("Buy-a-cell reminder", "A notification when this little is left", warnChip));
        // a ticket stub: tear it off when a new cell goes in, and the chart starts again
        LinearLayout tearRow = new LinearLayout(this);
        tearRow.setGravity(Gravity.CENTER_VERTICAL);
        tearRow.setPadding(0, dp(12), 0, 0);
        tearRow.setClipChildren(false);
        tearRow.setClipToPadding(false);
        LinearLayout tearWords = new LinearLayout(this);
        tearWords.setOrientation(LinearLayout.VERTICAL);
        tearWords.addView(text("New cell in?", 15, Glass.TEXT));
        tearWords.addView(text("Tear off the stub to start a fresh chart", 13, Glass.MUTED));
        tearRow.addView(tearWords, weight());
        TextView perf = text("•\n•\n•\n•\n•", 7, Glass.FAINT);         // the perforation
        perf.setGravity(Gravity.CENTER);
        perf.setLineSpacing(0f, 0.8f);
        perf.setPadding(dp(6), 0, dp(6), 0);
        tearRow.addView(perf);
        TextView stubView = text("NEW\nCELL", 11, Glass.ACCENT);
        stubView.setTypeface(MEDIUM);
        stubView.setGravity(Gravity.CENTER);
        stubView.setLetterSpacing(0.08f);
        stubView.setPadding(dp(14), dp(8), dp(14), dp(8));
        stubView.setBackground(Glass.panel(this, 12));
        Glass.tearable(stubView, () -> {
            Battery.clear(this);
            voltShown = Float.NaN;
            showBattery();
            Toast.makeText(this, "Fresh chart started", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams stubAt = new LinearLayout.LayoutParams(-2, -2);
        tearRow.addView(stubView, stubAt);
        batt.addView(tearRow);
        batt.setOutlineProvider(null);               // no shadow: only so the torn stub passes over the next card
        batt.setTranslationZ(dp(2));
        batt.setClipChildren(false);
        batt.setClipToPadding(false);
        col.setClipChildren(false);

        // ---- features ----
        col = pages[WATCH];
        cards = 0;
        LinearLayout mirror = card(col, 24);
        mirror.addView(label("LIVE MIRROR"));
        lcd = new Glass.Lcd(this);
        LinearLayout.LayoutParams lcdAt = new LinearLayout.LayoutParams(-1, -2);
        lcdAt.topMargin = dp(8);
        mirror.addView(lcd, lcdAt);
        mirrorWhat = text("", 13, Glass.MUTED);
        mirrorWhat.setPadding(0, dp(8), 0, 0);
        mirror.addView(mirrorWhat);
        LinearLayout feats = card(col, 24);
        feats.addView(label("FEATURES"));
        for (int i = 0; i < Features.ALL.length; i++) {
            final String[] f = Features.ALL[i];
            if (i > 0) feats.addView(hairline());
            Glass.Toggle t = new Glass.Toggle(this, Glass.ACCENT);
            t.set(Features.on(this, f[0]), false);
            t.setOnChange(on -> Features.set(this, f[0], on));
            feats.addView(lineRow(f[1], f[2], t));
        }
        final Section musicSec = buildMusicCard(col);

        // ---- battery saver ----
        col = pages[BATTERY];
        Section saverSec = section(col, "BATTERY SAVER", true);
        final Runnable saverCount = () -> {
            int k = (Saver.quiet(this) ? 1 : 0) + (prefs().getInt(Saver.BATCH, 0) > 0 ? 1 : 0)
                    + (prefs().getInt(Saver.VOLT, 0) > 0 ? 1 : 0) + (Saver.seconds(this) ? 0 : 1)
                    + (prefs().getBoolean(Saver.NIGHT, false) ? 1 : 0);
            saverSec.summary.setText(k + " of 5 on");
            showTracks();
        };
        saverSec.body.addView(note("Every message wakes the watch. These make that happen less."));
        Glass.Toggle quiet = new Glass.Toggle(this, Glass.ACCENT);
        quiet.set(Saver.quiet(this), false);
        quiet.setOnChange(on -> { prefs().edit().putBoolean(Saver.QUIET, on).apply(); saverCount.run(); });
        saverSec.body.addView(lineRow("Quiet off-screen",
                "Song changes wait until the Pulse face is open", quiet));
        final TextView batch = chip(Saver.batchName(prefs().getInt(Saver.BATCH, 0)));
        final String[] batchNames = new String[Saver.BATCH_MIN.length];
        for (int i = 0; i < batchNames.length; i++) batchNames[i] = Saver.batchName(i);
        batch.setOnClickListener(v -> morph(batch, batchNames, prefs().getInt(Saver.BATCH, 0), n -> {
            prefs().edit().putInt(Saver.BATCH, n).apply();
            swapText(batch, Saver.batchName(n));
            NotifService.saverChanged();
            saverCount.run();
        }));
        saverSec.body.addView(hairline());
        saverSec.body.addView(lineRow("Batch messages",
                "Send messages together. Calls still ring at once", batch));
        final TextView volt = chip(Saver.VOLT_NAME[Math.max(0, Math.min(Saver.VOLT_NAME.length - 1, prefs().getInt(Saver.VOLT, 0)))]);
        volt.setOnClickListener(v -> morph(volt, Saver.VOLT_NAME, prefs().getInt(Saver.VOLT, 0), n -> {
            prefs().edit().putInt(Saver.VOLT, n).apply();
            swapText(volt, Saver.VOLT_NAME[n]);
            NotifService.saverChanged();
            saverCount.run();
        }));
        saverSec.body.addView(hairline());
        saverSec.body.addView(lineRow("Watch saver mode",
                "Half speed, no beeps, still bars", volt));
        // the cell's voltage on a scale: the blue stretch is where saver mode is on, the dot is the cell now
        voltTrack = new Glass.Track(this);
        voltTrack.names(new String[]{"2.6 V", "2.8", "3.0", "3.2 V"}, new float[]{0f, 1 / 3f, 2 / 3f, 1f});
        LinearLayout.LayoutParams trackAt = new LinearLayout.LayoutParams(-1, -2);
        trackAt.bottomMargin = dp(8);
        saverSec.body.addView(voltTrack, trackAt);
        Glass.Toggle secs = new Glass.Toggle(this, Glass.ACCENT);
        secs.set(Saver.seconds(this), false);
        secs.setOnChange(on -> { prefs().edit().putBoolean(Saver.SECONDS, on).apply(); saverCount.run(); });
        saverSec.body.addView(hairline());
        saverSec.body.addView(lineRow("Seconds on the idle clock",
                "Off redraws the clock once a minute", secs));
        Glass.Toggle night = new Glass.Toggle(this, Glass.ACCENT);
        night.set(prefs().getBoolean(Saver.NIGHT, false), false);
        night.setOnChange(on -> {
            prefs().edit().putBoolean(Saver.NIGHT, on).apply();
            NotifService.saverChanged();
            saverCount.run();
        });
        saverSec.body.addView(hairline());
        saverSec.body.addView(lineRow("Night link-off",
                "Bluetooth to the watch rests in the blue hours", night));
        // the whole day as one bar: blue = link off, the dot = now
        nightTrack = new Glass.Track(this);
        nightTrack.names(new String[]{"12 AM", "6 AM", "12 PM", "6 PM", "12 AM"}, new float[]{0f, 0.25f, 0.5f, 0.75f, 1f});
        saverSec.body.addView(nightTrack);
        final String[] hourNames = new String[24];
        for (int i = 0; i < 24; i++) hourNames[i] = Saver.hourName(i);
        final TextView from = chip(Saver.hourName(Saver.from(this))), to = chip(Saver.hourName(Saver.to(this)));
        from.setOnClickListener(v -> morph(from, hourNames, Saver.from(this), n -> {
            prefs().edit().putInt(Saver.NIGHT_FROM, n).apply();
            swapText(from, Saver.hourName(n));
            NotifService.saverChanged();
            saverCount.run();
        }));
        to.setOnClickListener(v -> morph(to, hourNames, Saver.to(this), n -> {
            prefs().edit().putInt(Saver.NIGHT_TO, n).apply();
            swapText(to, Saver.hourName(n));
            NotifService.saverChanged();
            saverCount.run();
        }));
        LinearLayout hours = new LinearLayout(this);
        hours.setGravity(Gravity.CENTER_VERTICAL);
        hours.setPadding(0, dp(8), 0, dp(4));
        hours.addView(text("Off from", 14, Glass.SOFT));
        hours.addView(gap());
        hours.addView(from);
        hours.addView(gap());
        hours.addView(text("until", 14, Glass.SOFT));
        hours.addView(gap());
        hours.addView(to);
        saverSec.body.addView(hours);
        saverCount.run();

        // ---- apps that may reach the watch ----
        col = pages[MESSAGES];
        cards = 0;
        appsSec = section(col, "APPS", true);
        appsSec.body.addView(note("Pick which apps may reach the watch. An app shows up here after its first "
                + "notification."));
        Glass.Toggle only = new Glass.Toggle(this, Glass.ACCENT);
        only.set(prefs().getBoolean(Saver.ALLOW_ON, false), false);
        only.setOnChange(on -> {
            prefs().edit().putBoolean(Saver.ALLOW_ON, on).apply();
            buildApps();
        });
        appsSec.body.addView(lineRow("Only chosen apps", "Off = every app reaches the watch", only));
        appRows = new LinearLayout(this);
        appRows.setOrientation(LinearLayout.VERTICAL);
        appsSec.body.addView(appRows);

        // ---- keyword rules ----
        Section ruleSec = section(col, "RULES", false);
        ruleSec.summary.setText("By what it says");
        ruleSec.body.addView(note("Beyond the list of apps: decide by what a message says. For one app, tap its "
                + "rule next to its switch in Apps."));
        Glass.Toggle groupsOff = new Glass.Toggle(this, Glass.ACCENT);
        groupsOff.set(prefs().getBoolean(Rules.GROUPS, false), false);
        groupsOff.setOnChange(on -> prefs().edit().putBoolean(Rules.GROUPS, on).apply());
        ruleSec.body.addView(lineRow("Mute group chats", "Unless the message has one of your names", groupsOff));
        ruleSec.body.addView(hairline());
        TextView namesLabel = text("Your names (comma between)", 13, Glass.MUTED);
        namesLabel.setPadding(0, dp(12), 0, dp(6));
        ruleSec.body.addView(namesLabel);
        EditText namesField = field("name, nickname");
        namesField.setText(prefs().getString(Rules.NAMES, ""));
        live(namesField, s -> prefs().edit().putString(Rules.NAMES, s).apply());
        ruleSec.body.addView(namesField);
        TextView blockLabel = text("Never show messages containing", 13, Glass.MUTED);
        blockLabel.setPadding(0, dp(14), 0, dp(6));
        ruleSec.body.addView(blockLabel);
        EditText blockField = field("promo, sale");
        blockField.setText(prefs().getString(Rules.BLOCK, ""));
        live(blockField, s -> prefs().edit().putString(Rules.BLOCK, s).apply());
        ruleSec.body.addView(blockField);

        // ---- drain meter ----
        col = pages[BATTERY];
        Section drainSec = section(col, "DRAIN METER", false);
        drainSec.summary.setText("What wakes the watch");
        drainSpan = text("Last 24 h", 12, Glass.MUTED);
        // a list or a chart: the switch turns one into the other
        final TextView asList = text("List", 13, Glass.TEXT), asChart = text("Chart", 13, Glass.SOFT);
        LinearLayout way = new LinearLayout(this);
        way.setPadding(0, dp(6), 0, dp(10));
        for (final TextView o : new TextView[]{asList, asChart}) {
            o.setTypeface(MEDIUM);
            o.setPadding(dp(16), dp(7), dp(16), dp(7));
            way.addView(o);
            if (o == asList) way.addView(gap());
        }
        asList.setBackground(Glass.panel(this, 16));
        drainChart = new Glass.DrainChart(this);
        View.OnClickListener flip = v -> {
            boolean c = v == asChart;
            drainChart.show(c);
            asChart.setBackground(c ? Glass.panel(this, 16) : null);
            asList.setBackground(c ? null : Glass.panel(this, 16));
            asChart.setTextColor(c ? Glass.TEXT : Glass.SOFT);
            asList.setTextColor(c ? Glass.SOFT : Glass.TEXT);
            Glass.pop(v);
            prefs().edit().putBoolean("drain_chart", c).apply();
        };
        asList.setOnClickListener(flip);
        asChart.setOnClickListener(flip);
        Glass.spring(asList);
        Glass.spring(asChart);
        drainSec.body.addView(way);
        drainSec.body.addView(drainChart);
        if (prefs().getBoolean("drain_chart", false)) asChart.post(() -> flip.onClick(asChart));
        TextView drainHow = note("Messages are lines sent over Bluetooth. Animated is time at full speed (estimated).");
        drainHow.setPadding(0, dp(12), 0, 0);
        drainSec.body.addView(drainHow);

        // ---- macros ----
        col = pages[MACROS];
        cards = 0;
        macroWheel(col);
        macroSec = section(col, "MACROS", true);
        macroSec.body.addView(note("Names show on the watch, 6 letters at most. Empty hides the slot. Tap a type "
                + "to change it."));
        macroRows = new LinearLayout(this);
        macroRows.setOrientation(LinearLayout.VERTICAL);
        macroRows.setPadding(0, dp(6), 0, dp(4));
        macroSec.body.addView(macroRows);
        buildMacros();
        saveMacrosBtn = pill("Save macros", true, v -> saveMacros());
        macroSec.body.addView(saveMacrosBtn, new LinearLayout.LayoutParams(-1, -2));
        trigHold = chip(trigName(true));
        trigHold.setOnClickListener(v -> pickTrigger(true, trigHold));
        trigDouble = chip(trigName(false));
        trigDouble.setOnClickListener(v -> pickTrigger(false, trigDouble));
        macroSec.body.addView(hairline());
        macroSec.body.addView(lineRow("Hold A + L runs", "On the music screen, without the menu", trigHold));
        macroSec.body.addView(lineRow("Double-tap A runs", "Play/pause then waits a moment for the second tap", trigDouble));
        Section mdSec = section(col, "CONNECT MACRODROID", false);
        mdSec.summary.setText("Intents");
        mdSec.body.addView(note("In MacroDroid, use the trigger Intent Received and the action Send Intent "
                + "(broadcast) with these names."));
        intent(mdSec.body, "The watch runs a macro", "com.ollee.pulse.MACRO",
                "Extra slot = the slot number. Recorder slots add state = start or stop.");
        intent(mdSec.body, "Show a line on the watch", "com.ollee.pulse.SHOW", "Extra text.");
        intent(mdSec.body, "Ask a question", "com.ollee.pulse.ASK",
                "Extra choices = YES|NO|LAtEr. Optional: title, id.");
        intent(mdSec.body, "The answer comes back", "com.ollee.pulse.PICK",
                "Extras id, choice, and index (1, 2, 3... or 0 if cancelled).");
        intent(mdSec.body, "Play an animation", "com.ollee.pulse.PLAY",
                "Extra name = a clip in Watch, Animations. Or frames = f1|f2|... and speed.");
        intent(mdSec.body, "Let go of the watch", "com.ollee.pulse.LINK",
                "Extra on = false: Pulse disconnects so the Ollee app can install a face; it reconnects by "
                + "itself after for = seconds (default 180). on = true reconnects now.");
        intent(mdSec.body, "Install a face", "com.ollee.pulse.INSTALL",
                "Extra file = the face's file in your Faces folder (UnO or UnO.js), slot = A or B (default A). "
                + "Pulse sends it over its own link, no Ollee app. Forge C holds Pulse and is only written to update it.");
        intent(mdSec.body, "Read the Forge slots", "com.ollee.pulse.SLOTS",
                "No extras. Shows the face in each slot, A to D, on the watch. Changes nothing.");

        buildFacesPage(pages[FACES]);
        buildTapPage(pages[TAP]);

        // ---- animations (the frame player) ----
        col = pages[WATCH];
        Section animSec = section(col, "ANIMATIONS", false);
        animSec.summary.setText("Frame player");
        animSec.body.addView(note("Clips the watch plays frame by frame. Open the Pulse face, then tap Play."));
        for (final Anim.Clip clip : Anim.all(this)) {
            if (Anim.CUSTOM.equals(clip.name)) continue;
            final TextView go = chip("Play");
            go.setOnClickListener(v -> playClip(clip));
            animSec.body.addView(hairline());
            animSec.body.addView(lineRow(clip.name, clip.frames.length + " frames", go));
        }
        animSec.body.addView(hairline());
        TextView mineHow = text("Your own: one frame per line, 6 characters each (spaces count).", 12, Glass.MUTED);
        mineHow.setPadding(0, dp(10), 0, 0);
        animSec.body.addView(mineHow);
        animField = field("(^_^) \n (^o^)");
        animField.setSingleLine(false);
        animField.setMinLines(3);
        animField.setMaxLines(10);
        animField.setTypeface(Typeface.MONOSPACE);
        animField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        animField.setText(prefs().getString("anim_custom", ""));
        LinearLayout.LayoutParams afp = new LinearLayout.LayoutParams(-1, -2);
        afp.topMargin = dp(10);
        afp.bottomMargin = dp(10);
        animSec.body.addView(animField, afp);
        final String[] animSpeedName = {"Fast", "Normal", "Slow"};
        final int[] animSpeed = {2, 4, 8};
        final TextView aspd = chip(animSpeedName[prefs().getInt("anim_speed", 4) <= 2 ? 0 : prefs().getInt("anim_speed", 4) <= 4 ? 1 : 2]);
        aspd.setOnClickListener(v -> {
            int cur = prefs().getInt("anim_speed", 4), n = cur <= 2 ? 1 : cur <= 4 ? 2 : 0;
            prefs().edit().putInt("anim_speed", animSpeed[n]).apply();
            swapText(aspd, animSpeedName[n]);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        LinearLayout animBtns = new LinearLayout(this);
        animBtns.setGravity(Gravity.CENTER_VERTICAL);
        animBtns.addView(aspd);
        animBtns.addView(gap());
        animBtns.addView(pill("Save and play", true, v -> {
            prefs().edit().putString("anim_custom", animField.getText().toString()).apply();
            Anim.Clip mine = Anim.find(this, Anim.CUSTOM);
            if (mine == null) Toast.makeText(this, "Type some frames first", Toast.LENGTH_SHORT).show();
            else playClip(mine);
        }), weight());
        animSec.body.addView(animBtns);
        final TextView intro = chip(introName());
        intro.setOnClickListener(v -> {
            List<Anim.Clip> all = Anim.all(this);
            String cur = prefs().getString("anim_intro", "");
            String next = all.isEmpty() ? "" : all.get(0).name;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).name.equals(cur)) next = i + 1 < all.size() ? all.get(i + 1).name : "";
            }
            prefs().edit().putString("anim_intro", next).apply();
            swapText(intro, next.isEmpty() ? "None" : next);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        animSec.body.addView(hairline());
        animSec.body.addView(lineRow("Before a message", "A clip plays first, then the message", intro));

        // ---- play: UNO or Buckshot between the watch and friends' phones ----
        col = pages[HOME];
        LinearLayout playCard = card(col, 24);
        LinearLayout ph = new LinearLayout(this);
        ph.setGravity(Gravity.CENTER_VERTICAL);
        ph.addView(label("PLAY"), weight());
        playState = text("", 13, Glass.MUTED);
        ph.addView(playState);
        playCard.addView(ph);
        playCard.addView(note("You play on the watch, friends play in their browser (any phone, iPhone too, no app). "
                + "Local: same Wi-Fi or your hotspot. Online: anywhere, through your free Firebase database. "
                + "On the watch: hold A, PLAY."));
        LinearLayout kinds = new LinearLayout(this);
        kinds.setPadding(0, dp(10), 0, 0);
        playUno = chip("UNO");
        playBuck = chip("Buckshot");
        playUno.setOnClickListener(v -> { prefs().edit().putString("party_kind", "uno").apply(); refreshPlay(); });
        playBuck.setOnClickListener(v -> { prefs().edit().putString("party_kind", "buck").apply(); refreshPlay(); });
        kinds.addView(playUno, weight());
        kinds.addView(gap());
        kinds.addView(playBuck, weight());
        kinds.addView(gap());
        playWhere = chip("Local");
        playWhere.setOnClickListener(v -> {
            if (Party.now != null) { Toast.makeText(this, "End the game first", Toast.LENGTH_SHORT).show(); return; }
            boolean on = !Party.wantOnline(this);
            if (on && Party.dbUrl(this).isEmpty()) { Toast.makeText(this, "Add your Firebase link below first", Toast.LENGTH_SHORT).show(); if (!playOnlineSec.open) playOnlineSec.toggle(); return; }
            prefs().edit().putBoolean("party_online", on).apply();
            refreshPlay();
        });
        kinds.addView(playWhere, weight());
        playCard.addView(kinds);
        playLink = text("", 17, Glass.ACCENT);
        playLink.setTypeface(MEDIUM);
        playLink.setTextIsSelectable(true);
        playLink.setPadding(0, dp(10), 0, 0);
        playCard.addView(playLink);
        playWeb = text("", 14, Glass.ACCENT);
        playWeb.setTextIsSelectable(true);
        playWeb.setPadding(0, dp(4), 0, 0);
        playCard.addView(playWeb);
        playWho = text("", 14, Glass.SOFT);
        playWho.setPadding(0, dp(4), 0, 0);
        playCard.addView(playWho);
        LinearLayout pr = new LinearLayout(this);
        pr.setPadding(0, dp(12), 0, 0);
        playMain = pill("Host", true, v -> {
            if (Party.now == null) {
                if (Party.hostSaved(this, Party.lastKind(this)) == null) Toast.makeText(this, "Couldn't open the game (is another app using port " + Party.PORT + "?)", Toast.LENGTH_LONG).show();
            } else {
                Party.end();
            }
            refreshPlay();
        });
        pr.addView(playMain, weight());
        pr.addView(gap());
        playStart = pill("Start", false, v -> {
            Party pp = Party.now;
            String err = pp == null ? "Host first" : pp.start();
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
        });
        pr.addView(playStart, weight());
        pr.addView(gap());
        pr.addView(pill("Share link", false, v -> {
            Party pp = Party.now;
            String web = pp == null ? "" : pp.onlineLink(), link = Party.link();
            if (pp == null || (web.isEmpty() && link.isEmpty())) {
                Toast.makeText(this, pp == null ? "Host a game first" : "Join Wi-Fi or turn on your hotspot", Toast.LENGTH_SHORT).show();
                return;
            }
            String what = "buck".equals(pp.kind) ? "Buckshot" : "UNO";
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, web.isEmpty()
                            ? "Play " + what + " against my watch: " + link + " (same Wi-Fi as me)"
                            : "Play " + what + " against my watch: " + web);
            try { startActivity(Intent.createChooser(send, "Send the game link")); } catch (ActivityNotFoundException ignored) { }
        }), weight());
        playCard.addView(pr);

        playOnlineSec = section(col, "ONLINE PLAY", false);
        playOnlineSec.summary.setText("Play with friends anywhere");
        playOnlineSec.body.addView(note("1. Firebase (free): make a project, add a Realtime Database in test mode, paste its link here.\n"
                + "2. GitHub (free): save the game page below, upload it to a repo as index.html, turn on Pages, paste the page link here.\n"
                + "Test mode lets anyone with the link write to that database, so keep it for games only."));
        EditText dbField = field("https://your-project-default-rtdb.firebaseio.com");
        dbField.setText(Party.dbUrl(this));
        live(dbField, t -> prefs().edit().putString("party_db", t).apply());
        playOnlineSec.body.addView(gapRow());
        playOnlineSec.body.addView(dbField);
        EditText pageField = field("https://you.github.io/pulse-games/");
        pageField.setText(Party.pageUrl(this));
        live(pageField, t -> prefs().edit().putString("party_page", t).apply());
        playOnlineSec.body.addView(gapRow());
        playOnlineSec.body.addView(pageField);
        LinearLayout sr = new LinearLayout(this);
        sr.setPadding(0, dp(10), 0, 0);
        sr.addView(pill("Save game page", false, v -> {
            Intent make = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            make.addCategory(Intent.CATEGORY_OPENABLE);
            make.setType("text/html");
            make.putExtra(Intent.EXTRA_TITLE, "index.html");
            try { startActivityForResult(make, REQ_PAGE); } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No file app to save with", Toast.LENGTH_SHORT).show();
            }
        }), weight());
        playOnlineSec.body.addView(sr);
        refreshPlay();

        // ---- setup ----
        col = pages[HOME];
        setupSec = section(col, "SETUP", false);   // opens by itself while something is missing
        Section fileSec = section(col, "SETUP FILE", false);
        fileSec.summary.setText("Share your setup");
        fileSec.body.addView(note("One file with your switches, savers, macros, watch menu, profiles, rules and "
                + "animations. Your Gemini and YouTube keys stay out of it."));
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setPadding(0, dp(10), 0, 0);
        fileRow.addView(pill("Export", true, v -> {
            Intent make = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            make.addCategory(Intent.CATEGORY_OPENABLE);
            make.setType("application/json");
            make.putExtra(Intent.EXTRA_TITLE, "pulse-setup.json");
            try { startActivityForResult(make, REQ_EXPORT); } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No file app to save with", Toast.LENGTH_SHORT).show();
            }
        }), weight());
        fileRow.addView(gap());
        fileRow.addView(pill("Import", false, v -> {
            Intent pickFile = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pickFile.addCategory(Intent.CATEGORY_OPENABLE);
            pickFile.setType("*/*");
            try { startActivityForResult(pickFile, REQ_IMPORT); } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No file app to open with", Toast.LENGTH_SHORT).show();
            }
        }), weight());
        fileSec.body.addView(fileRow);
        checklist = new LinearLayout(this);
        checklist.setOrientation(LinearLayout.VERTICAL);
        setupSec.body.addView(checklist);

        // ---- YouTube key ----
        col = pages[WATCH];
        keySec = section(col, "YOUTUBE KEY", false);
        keySec.body.addView(note("Optional. Without it Pulse reads YouTube's search page."));
        keyField = field("AIza…");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setText(prefs().getString("ytkey", ""));
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, -2);
        kp.topMargin = dp(12);
        kp.bottomMargin = dp(12);
        keySec.body.addView(keyField, kp);
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        keyState = text("", 13, Glass.MUTED);
        keyRow.addView(keyState, weight());
        keyRow.addView(pill("Save", true, v -> {
            prefs().edit().putString("ytkey", keyField.getText().toString().trim()).apply();
            showKey();
            confirm((TextView) v, "Save");
        }));
        keySec.body.addView(keyRow);

        // ---- Gemini key (Ask AI) ----
        aiSec = section(col, "GEMINI KEY", false);
        aiSec.body.addView(note("For Ask AI. Free at aistudio.google.com/apikey: sign in, Create API key, paste "
                + "it here."));
        aiField = field("AIza…");
        aiField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        aiField.setText(prefs().getString("gemkey", ""));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(12);
        ap.bottomMargin = dp(12);
        aiSec.body.addView(aiField, ap);
        LinearLayout aiRow = new LinearLayout(this);
        aiRow.setGravity(Gravity.CENTER_VERTICAL);
        aiState = text("", 13, Glass.MUTED);
        aiRow.addView(aiState, weight());
        aiRow.addView(pill("Save", true, v -> {
            prefs().edit().putString("gemkey", aiField.getText().toString().trim()).remove("gemmodel").apply();
            showKey();
            confirm((TextView) v, "Save");
        }));
        aiSec.body.addView(aiRow);
        Glass.Toggle smart = new Glass.Toggle(this, Glass.ACCENT);
        smart.set(prefs().getBoolean("ai_smart", false), false);
        smart.setOnChange(on -> prefs().edit().putBoolean("ai_smart", on).apply());
        aiSec.body.addView(hairline());
        aiSec.body.addView(lineRow("Deeper thinking", "Better on hard questions, slower", smart));
        final String[] sensName = {"Normal", "High", "Max"};
        final TextView sens = chip(sensName[Math.max(0, Math.min(2, prefs().getInt("mic_sens", 1)))]);
        sens.setOnClickListener(v -> {
            int n = (prefs().getInt("mic_sens", 1) + 1) % 3;
            prefs().edit().putInt("mic_sens", n).apply();
            swapText(sens, sensName[n]);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        aiSec.body.addView(hairline());
        aiSec.body.addView(lineRow("Mic sensitivity", "High suits most. Max hears a whisper", sens));
        final String[] spName = {"Normal", "Slow", "Slower"};
        final TextView spd = chip(spName[Math.max(0, Math.min(2, prefs().getInt("ans_speed", 1)))]);
        spd.setOnClickListener(v -> {
            int n = (prefs().getInt("ans_speed", 1) + 1) % 3;
            prefs().edit().putInt("ans_speed", n).apply();
            swapText(spd, spName[n]);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        aiSec.body.addView(hairline());
        aiSec.body.addView(lineRow("Answer scroll", "How fast answers move across the watch", spd));
        final String[] engName = {"Pulse", "Phone"};
        final TextView eng = chip(engName[prefs().getInt("voice_engine", 0) == 1 ? 1 : 0]);
        eng.setOnClickListener(v -> {
            int n = prefs().getInt("voice_engine", 0) == 1 ? 0 : 1;
            prefs().edit().putInt("voice_engine", n).apply();
            swapText(eng, engName[n]);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        aiSec.body.addView(hairline());
        aiSec.body.addView(lineRow("Listen with", "Pulse records for Gemini. Phone uses Google speech", eng));
        final TextView play = chip("Play");
        play.setOnClickListener(v -> playLast());
        aiSec.body.addView(hairline());
        LinearLayout lastRow = lineRow("Last question", "", play);
        lastHeard = (TextView) ((LinearLayout) lastRow.getChildAt(0)).getChildAt(1);
        aiSec.body.addView(lastRow);

        // ---- ask with a photo ----
        Section photoSec = section(col, "ASK WITH A PHOTO", false);
        photoSection = photoSec;
        photoSec.summary.setText("Camera");
        photoSec.body.addView(note("Take or pick a photo and Gemini answers on your watch. The question is "
                + "optional."));
        photoQ = field("Question (optional)");
        photoQ.setSingleLine(false);
        photoQ.setMaxLines(3);
        photoQ.setText(prefs().getString("photoq", ""));
        LinearLayout.LayoutParams pq = new LinearLayout.LayoutParams(-1, -2);
        pq.topMargin = dp(12);
        pq.bottomMargin = dp(12);
        photoSec.body.addView(photoQ, pq);
        LinearLayout photoBtns = new LinearLayout(this);
        photoBtns.addView(pill("Camera", true, v -> takePhoto()), weight());
        LinearLayout.LayoutParams gl = weight();
        gl.leftMargin = dp(10);
        photoBtns.addView(pill("Gallery", false, v -> pickPhoto()), gl);
        photoSec.body.addView(photoBtns);
        final String[] viaName = {"Pulse", "Gemini app"};
        final TextView via = chip(viaName[prefs().getInt("photo_via", 0) == 1 ? 1 : 0]);
        via.setOnClickListener(v -> {
            int n = prefs().getInt("photo_via", 0) == 1 ? 0 : 1;
            prefs().edit().putInt("photo_via", n).apply();
            swapText(via, viaName[n]);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        LinearLayout viaRow = lineRow("Answer with", "Pulse uses your key. The Gemini app has no limits", via);
        photoSec.body.addView(viaRow);
        photoImg = new ImageView(this);
        photoImg.setAdjustViewBounds(true);
        photoImg.setVisibility(View.GONE);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = dp(14);
        photoSec.body.addView(photoImg, il);
        photoAns = text("", 15, Glass.TEXT);
        photoAns.setPadding(0, dp(12), 0, 0);
        photoSec.body.addView(photoAns);

        // ---- tips ----
        // ---- the watch's menu ----
        Section menuSec = section(col, "WATCH MENU", false);
        menuSec.summary.setText("Hold A on the watch");
        menuSec.body.addView(note("Hold an item and drag it to a new place. Tap its name to rename it. "
                + "Switch it off to hide it from the watch."));
        menuRows = new Glass.DragList(this);
        menuRows.moved = (a, b) -> {
            List<WatchMenu.Item> all = WatchMenu.load(this);
            all.add(b, all.remove(a));
            WatchMenu.save(this, all);
        };
        menuSec.body.addView(menuRows);
        buildMenu();

        // ---- Gemini requests today ----
        Section useSec = section(col, "GEMINI USAGE", false);
        usageSum = useSec.summary;
        LinearLayout useRow = new LinearLayout(this);
        useRow.setGravity(Gravity.CENTER_VERTICAL);
        useRow.setPadding(0, dp(6), 0, dp(10));
        FrameLayout ringBox = new FrameLayout(this);
        usageRing = new Glass.Ring(this);
        ringBox.addView(usageRing, new FrameLayout.LayoutParams(dp(84), dp(84)));
        usageCount = new Glass.RollText(this, 24, Glass.TEXT, MEDIUM);
        usageCount.set("0", false);
        ringBox.addView(usageCount, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        useRow.addView(ringBox, new LinearLayout.LayoutParams(dp(84), dp(84)));
        usageText = text("", 14, Glass.SOFT);
        usageText.setPadding(dp(16), 0, 0, 0);
        useRow.addView(usageText, weight());
        useSec.body.addView(useRow);
        usageBar = new Glass.Track(this);                 // not shown: the ring replaced it
        final String[] limitNames = new String[Usage.LIMITS.length];
        for (int i = 0; i < limitNames.length; i++) limitNames[i] = Usage.LIMITS[i] + " a day";
        final TextView limitChip = chip(Usage.limit(this) + " a day");
        limitChip.setOnClickListener(v -> morph(limitChip, limitNames,
                Math.max(0, Math.min(limitNames.length - 1, prefs().getInt("gem_limit", 3))), n -> {
            prefs().edit().putInt("gem_limit", n).apply();
            swapText(limitChip, limitNames[n]);
            showLogs();
        }));
        useSec.body.addView(lineRow("Daily limit", "Free limits differ by model: check yours in Google AI Studio", limitChip));

        // ---- when the link dropped, and why ----
        timeSec = section(col, "CONNECTION TIMELINE", false);
        timelineText = text("", 13, Glass.SOFT);
        timeSec.body.addView(note("Tap one to see more."));
        timeRows = new LinearLayout(this);
        timeRows.setOrientation(LinearLayout.VERTICAL);
        timeSec.body.addView(timeRows);

        // ---- everything pushed to the watch ----
        sentSec = section(col, "SENT TO THE WATCH", false);
        sentSec.body.addView(note("Newest first. Every line woke the watch's radio."));
        sentText = text("", 13, Glass.SOFT);
        sentRows = new LinearLayout(this);
        sentRows.setOrientation(LinearLayout.VERTICAL);
        sentSec.body.addView(sentRows);

        final Section tips = section(col, "ON THE WATCH", false);
        tips.summary.setText("Buttons");
        tips.body.addView(text("Music   A play · L next · L L previous\n"
                + "Trigger  hold A + L, or double-tap A: each runs the macro you pick in MACROS\n"
                + "Timer    A stop recording · L back to music (it keeps recording)\n"
                + "Pick     L next choice · A pick · hold to cancel\n"
                + "Hold A   menu: search, ask AI, answer, inbox, macros, battery\n"
                + "Hold L   volume (A up, L down, hold A mute)\n"
                + "Answer   A next row · L previous · hold A ask again · hold L music\n"
                + "Close the Ollee app before connecting: the watch takes one phone at a time.", 13, Glass.SOFT));

        // the Watch tab is long: its cards go into three groups behind a segmented switch
        View[][] groups = {
                {mirror, feats, musicSec.card, animSec.card, menuSec.card, tips.card},
                {aiSec.card, keySec.card, photoSec.card, useSec.card},
                {timeSec.card, sentSec.card}};
        splitWatch(groups, new String[]{"Watch face", "AI & keys", "History"});

        TextView foot = text("Pulse 2  ·  made for the Ollee Watch", 11, Glass.MUTED);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(22), 0, 0);
        pages[HOME].addView(foot);

        // each tab scrolls on its own, and stretches like a rubber band past its ends
        for (int i = 0; i < pages.length; i++) {
            ScrollView sv = new Glass.Elastic(this);
            sv.addView(pages[i]);
            sv.setOnScrollChangeListener((v, x, y, ox, oy) -> aurora.scrolled(y));
            scrolls[i] = sv;
            root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        }

        // ---- the tab bar: a white capsule floating at the bottom ----
        Glass.DragBar dragBar = new Glass.DragBar(this);
        tabBar = dragBar;
        tabBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        tabBar.setBackground(Glass.bar(this));
        tabBlob = new Glass.Bubble(this);
        tabBar.addView(tabBlob, new FrameLayout.LayoutParams(-1, 0));    // as tall as a tab once they are laid out
        // the bubble can be picked up and carried to another tab
        dragBar.hold = x -> liquidMenu(x);
        dragBar.drag = new Glass.DragBar.Drag() {
            @Override public boolean grab(float x) {
                float bx = x - tabBar.getPaddingLeft(), w = tabViews[0].getWidth();
                grabAt = bx - blobL;
                return w > 0 && bx >= blobL && bx <= blobL + w;
            }
            @Override public void move(float x) {
                if (blobRun != null) blobRun.cancel();
                float w = tabViews[0].getWidth(), end = tabViews[tabViews.length - 1].getLeft();
                float l = x - tabBar.getPaddingLeft() - grabAt;
                if (l < 0) l *= 0.4f;                                    // past an end it resists, like a rubber band
                else if (l > end) l = end + (l - end) * 0.4f;
                blobL = l;
                tabBlob.set(l, l + w, 0f, 1f);
                tintBubble();
                int over = Math.max(0, Math.min(tabViews.length - 1, Math.round(l / w)));
                if (over != hover) {
                    hover = over;
                    tintBubble();
                    tabBar.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            @Override public void drop(float x) {
                int n = hover < 0 ? tab : hover;
                hover = -1;
                if (n == tab) moveBlob(n, true);
                else showTab(n);
            }
        };
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabBar.addView(tabRow, new FrameLayout.LayoutParams(-1, -2));
        dragBar.bubble = tabBlob;
        dragBar.row = tabRow;
        // the tabs get their size after the first layout (and again if the screen turns)
        tabRow.addOnLayoutChangeListener((vw, x0, y0, x1, y1, ox0, oy0, ox1, oy1) -> {
            if (x1 - x0 != ox1 - ox0 || y1 - y0 != oy1 - oy0) moveBlob(Math.max(0, tab), false);
        });
        final String[] tabName = {"Home", "Watch", "Messages", "Macros", "Battery", "Faces", "Tap"};
        for (int i = 0; i < tabName.length; i++) {
            final int n = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(7), 0, dp(7));
            tabIcons[i] = new Glass.TabIcon(this, i);
            t.addView(tabIcons[i], new LinearLayout.LayoutParams(dp(22), dp(22)));
            tabLabels[i] = text(tabName[i], 10, Glass.SOFT);
            tabLabels[i].setTypeface(MEDIUM);
            tabLabels[i].setGravity(Gravity.CENTER);
            tabLabels[i].setSingleLine(true);
            t.addView(tabLabels[i]);
            t.setOnClickListener(v -> {
                if (n != tab) { showTab(n); return; }
                scrolls[n].smoothScrollTo(0, 0);                 // tapping the bubble: back to the top
                Glass.pop(tabIcons[n]);
                moveBlob(n, true);
            });
            tabViews[i] = t;
            tabRow.addView(t, weight());
        }
        // cards fade out before they reach the clock at the top and the tab bar at the bottom
        final View topFade = new View(this), bottomFade = new View(this);
        topFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Glass.BG, Glass.BG, Glass.BG & 0x00FFFFFF}));
        bottomFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Glass.BG, Glass.BG, Glass.BG & 0x00FFFFFF}));
        root.addView(topFade, new FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP));
        root.addView(bottomFade, new FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM));
        live = new Glass.Droplet(this);
        live.setOnClickListener(v -> showTab(HOME));
        final FrameLayout.LayoutParams dropAt = new FrameLayout.LayoutParams(dp(60), dp(80), Gravity.BOTTOM | Gravity.END);
        dropAt.rightMargin = dp(28);
        root.addView(live, dropAt);
        tabBar.addOnLayoutChangeListener((vw, x0, y0, x1, y1, ox0, oy0, ox1, oy1) -> {
            int above = root.getHeight() - y0 - dp(26);          // its foot stays tucked behind the bar's top edge
            if (above != dropAt.bottomMargin && root.getHeight() > 0) {
                dropAt.bottomMargin = above;
                live.setLayoutParams(dropAt);
            }
        });
        FrameLayout.LayoutParams tb = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tb.leftMargin = dp(16);
        tb.rightMargin = dp(16);
        tb.bottomMargin = dp(12);
        root.addView(tabBar, tb);

        // keep clear of the status bar, the gesture bar and the tab bar
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = ins.getSystemWindowInsetTop(), bottom = ins.getSystemWindowInsetBottom();
            for (LinearLayout p : pages) p.setPadding(dp(18), top + dp(24), dp(18), bottom + dp(110));
            topFade.getLayoutParams().height = top + dp(22);
            topFade.requestLayout();
            bottomFade.getLayoutParams().height = bottom + dp(118);
            bottomFade.requestLayout();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabBar.getLayoutParams();
            lp.bottomMargin = bottom + dp(12);
            tabBar.setLayoutParams(lp);
            return ins;
        });
        setContentView(root);
        showTab(HOME);
        shared(getIntent());
        // after the entrance, so they don't clash
        root.post(() -> { for (LinearLayout p : pages) p.setLayoutTransition(resizing()); });
    }

    /** Shows one tab's page, and the blob slides under its button. The background stays where it is. */
    private void showTab(int n) {
        int was = tab;
        if (n == was) return;
        tab = n;
        for (int i = 0; i < pages.length; i++) {
            boolean on = i == n;
            scrolls[i].setVisibility(on ? View.VISIBLE : View.GONE);
        }
        tintBubble();
        if (was < 0) {                               // the first page: no show
            moveBlob(n, false);
            return;
        }
        turnPage(was, n);
        Glass.pop(tabIcons[n]);
        moveBlob(n, true);
    }

    private View[][] watchGroups;
    private TextView[] subLabels;
    private Glass.Bubble subBubble;
    private int sub = -1;
    private float subL;
    private ValueAnimator subRun;

    /**
     * A small segmented switch under the Watch title. The blue bubble slides between its parts
     * like the one in the tab bar; the cards of the part left behind step aside, the new ones
     * come in from the side they were picked on, one after another.
     */
    private void splitWatch(View[][] groups, String[] names) {
        watchGroups = groups;
        LinearLayout page = pages[WATCH];
        final Glass.LensBar bar = new Glass.LensBar(this);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(Glass.panel(this, 22));
        subBubble = new Glass.Bubble(this);
        bar.addView(subBubble, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout row = new LinearLayout(this);
        bar.addView(row, new FrameLayout.LayoutParams(-1, -2));
        bar.bubble = subBubble;
        bar.row = row;
        bar.lens = 1.05f;
        subLabels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int n = i;
            TextView t = text(names[i], 14, Glass.SOFT);
            t.setTypeface(MEDIUM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            t.setOnClickListener(v -> showSub(n, true));
            subLabels[i] = t;
            row.addView(t, weight());
        }
        row.addOnLayoutChangeListener((vw, x0, y0, x1, y1, ox0, oy0, ox1, oy1) -> {
            if (x1 - x0 != ox1 - ox0) slideSub(Math.max(0, sub), false);
        });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
        at.topMargin = dp(6);
        at.bottomMargin = dp(2);
        page.addView(bar, 3, at);                     // under the kicker, the title and the line below it
        Glass.spring(bar);
        showSub(Math.max(0, Math.min(names.length - 1, prefs().getInt("watch_sub", 0))), false);
    }

    private void showSub(int n, boolean animate) {
        if (n == sub) return;
        final int was = sub;
        sub = n;
        prefs().edit().putInt("watch_sub", n).apply();
        tintSub();
        slideSub(n, animate);
        float dir = was < 0 ? 0f : n > was ? 1f : -1f;
        // the page's own resize animation would squeeze the cards while they slide in, so it
        // is paused for the swap and the page lets them draw outside its edges meanwhile
        final LinearLayout page = pages[WATCH];
        final LayoutTransition keep = page.getLayoutTransition();
        page.setLayoutTransition(null);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        ui.postDelayed(() -> { if (page.getLayoutTransition() == null) page.setLayoutTransition(keep); }, 1100);
        for (int g = 0; g < watchGroups.length; g++) {
            for (int i = 0; i < watchGroups[g].length; i++) {
                View card = watchGroups[g][i];
                card.animate().cancel();
                if (g != n) { card.setVisibility(View.GONE); continue; }
                card.setVisibility(View.VISIBLE);
                if (!animate) {
                    card.setAlpha(1f);
                    card.setTranslationX(0f);
                    card.setTranslationY(0f);
                    continue;
                }
                card.setAlpha(0f);
                card.setTranslationX(dir * dp(54));
                card.setTranslationY(dp(10));
                card.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(40L + 55L * i)
                        .setDuration(700).setInterpolator(Glass.GENTLE).start();
            }
        }
        if (animate) Glass.pop(subLabels[n]);
    }

    /** The segmented switch's bubble: the front edge leads and the back edge catches up. */
    private void slideSub(int n, boolean animate) {
        View t = subLabels[n];
        if (t.getWidth() == 0) return;
        if (subRun != null) subRun.cancel();
        final float from = subL, to = t.getLeft(), w = t.getWidth();
        if (!animate || from == to) {
            subL = to;
            subBubble.set(to, to + w, 0f, 0f);
            tintSub();
            return;
        }
        subRun = ValueAnimator.ofFloat(0f, 1f);
        subRun.setDuration(760);
        subRun.setInterpolator(null);
        subRun.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            float front = from + (to - from) * Glass.GENTLE.getInterpolation(Math.min(1f, f / 0.82f));
            float back = from + (to - from) * Glass.BOUNCY.getInterpolation(Math.max(0f, (f - 0.16f) / 0.84f));
            float gap = Math.abs(front - back);
            subL = f >= 1f ? to : Math.min(front, back);
            subBubble.set(subL, subL + w + (f >= 1f ? 0f : gap), Math.min(1f, gap / w), 0f);
            tintSub();
        });
        subRun.start();
    }

    /** The tab under the bubble reads white, the rest dark. */
    /**
     * The words outside the bubble: they stay dark, but take on the bubble's blue the more of
     * their tab it covers, so they lead smoothly into the white the lens draws inside it.
     */
    private void tintBubble() {
        shade(tabLabels, tabIcons, tabViews, tabBlob);
    }

    private void tintSub() {
        shade(subLabels, null, subLabels, subBubble);
    }

    private static void shade(TextView[] labels, Glass.TabIcon[] icons, View[] tabs, Glass.Bubble b) {
        if (labels == null || b == null) return;
        float l = b.leftEdge(), r = b.rightEdge();
        for (int i = 0; i < labels.length; i++) {
            View t = tabs[i];
            float w = Math.max(1, t.getWidth());
            float cover = Math.max(0f, Math.min(r, t.getLeft() + w) - Math.max(l, t.getLeft())) / w;
            int col = Glass.Lcd.mix(Glass.SOFT, Glass.ACCENT, Math.min(1f, cover));
            if (labels[i].getCurrentTextColor() != col) labels[i].setTextColor(col);
            if (icons != null) icons[i].setColor(col);
        }
    }

    private void tint(int n) {
        for (int i = 0; i < tabLabels.length; i++) {
            tabLabels[i].setTextColor(i == n ? Color.WHITE : Glass.SOFT);
            tabIcons[i].setColor(i == n ? Color.WHITE : Glass.SOFT);
        }
    }

    private ValueAnimator pageRun;

    /**
     * One page hands over to the next, in the direction of the tab that was tapped: the old one
     * drifts away, shrinking and going out of focus; the new one glides in from the other side,
     * comes into focus and settles, and its cards follow one after another. The background stays put.
     */
    private void turnPage(final int from, final int to) {
        if (pageRun != null) pageRun.cancel();
        final float dir = to > from ? 1f : -1f, far = dp(64);
        final View out = scrolls[from], in = scrolls[to];
        for (int i = 0; i < scrolls.length; i++) {
            if (i != from && i != to) { scrolls[i].setVisibility(View.GONE); still(scrolls[i]); }
        }
        out.setVisibility(View.VISIBLE);
        in.setVisibility(View.VISIBLE);
        still(in);
        in.setAlpha(0f);
        pageRun = ValueAnimator.ofFloat(0f, 1f);
        pageRun.setDuration(620);
        pageRun.setInterpolator(null);
        pageRun.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            float leave = Math.min(1f, f / 0.45f);                 // the old page is gone before halfway
            leave = 1f - (1f - leave) * (1f - leave);
            out.setAlpha(1f - leave);
            out.setTranslationX(-dir * far * 0.6f * leave);
            out.setScaleX(1f - 0.05f * leave);
            out.setScaleY(1f - 0.05f * leave);
            Glass.blur(out, 16f * leave);
            float come = Glass.GENTLE.getInterpolation(f);
            float clear = Math.min(1f, f / 0.6f);
            in.setAlpha(clear);
            in.setTranslationX(dir * far * (1f - come));
            in.setScaleX(0.95f + 0.05f * come);
            in.setScaleY(0.95f + 0.05f * come);
            Glass.blur(in, 14f * (1f - clear));
        });
        pageRun.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                still(in);
                still(out);
                if (tab != from) out.setVisibility(View.GONE);
            }
        });
        pageRun.start();
        // the cards arrive a beat apart, each rising into its place
        LinearLayout page = pages[to];
        int shown = Math.min(page.getChildCount(), 9);
        for (int i = 0; i < page.getChildCount(); i++) {
            View card = page.getChildAt(i);
            card.animate().cancel();
            if (i >= shown) { card.setTranslationY(0f); continue; }
            card.setTranslationY(dp(22) + dp(9) * i);
            card.animate().translationY(0f).setStartDelay(60L + 45L * i).setDuration(760)
                    .setInterpolator(Glass.GENTLE).start();
        }
    }

    /** A page back to its plain self: in place, full size, sharp. */
    private void still(View v) {
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        Glass.blur(v, 0f);
    }

    /**
     * The blue capsule under the chosen tab. On its way it stretches wide and thin, then
     * snaps to shape and wobbles: the leading edge gets there first.
     */
    private void moveBlob(int n, boolean animate) {
        View t = tabViews[n];
        if (t == null || t.getWidth() == 0) return;          // not laid out yet: the layout listener calls again
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabBlob.getLayoutParams();
        if (lp.height != t.getHeight()) {
            lp.height = t.getHeight();
            tabBlob.setLayoutParams(lp);
        }
        if (blobRun != null) blobRun.cancel();
        final float to = t.getLeft(), from = blobL;
        if (!animate || from == to) {
            blobL = to;
            tabBlob.set(to, to + t.getWidth(), 0f, 0f);
            tintBubble();
            return;
        }
        // Like a drop of liquid: the front edge sets off first and the back edge is pulled along
        // after it, so the bubble stretches across the gap, then gathers itself and settles.
        final float w = Math.max(1, t.getWidth());
        blobRun = ValueAnimator.ofFloat(0f, 1f);
        blobRun.setDuration(820);
        blobRun.setInterpolator(null);                        // linear time; the springs are applied by hand
        blobRun.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            float front = from + (to - from) * Glass.GENTLE.getInterpolation(Math.min(1f, f / 0.82f));
            float back = from + (to - from) * Glass.BOUNCY.getInterpolation(Math.max(0f, (f - 0.16f) / 0.84f));
            float gap = Math.abs(front - back);
            blobL = f >= 1f ? to : Math.min(front, back);
            tabBlob.set(blobL, blobL + w + (f >= 1f ? 0f : gap), Math.min(1f, gap / w), 0f);
            tintBubble();
        });
        blobRun.start();
    }

    /** Back goes to Home first, then leaves the app. */
    @Override
    public void onBackPressed() {
        if (menuClose != null) menuClose.run();
        else if (tab != HOME) showTab(HOME);
        else super.onBackPressed();
    }

    /** A row of two shortcut cards on Home. */
    private LinearLayout tileRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setBaselineAligned(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(r, lp);
        Glass.enter(r, ++cards);
        return r;
    }

    /** One shortcut card: a title, a line that says how things stand, and a tap that opens a tab. */
    private TextView tile(LinearLayout row, String title, final int goTab) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(Glass.panel(this, 22));
        TextView t = text(title, 15, Glass.TEXT);
        t.setTypeface(MEDIUM);
        c.addView(t);
        TextView s = text("", 12, Glass.SOFT);
        s.setPadding(0, dp(3), 0, 0);
        c.addView(s);
        c.setOnClickListener(v -> showTab(goTab));
        Glass.spring(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.leftMargin = dp(12);
        row.addView(c, lp);
        return s;
    }

    /** The lines under the Home shortcuts. */
    private void showTiles() {
        if (tileSaver == null) return;
        int vi = Math.max(0, Math.min(Saver.VOLT_NAME.length - 1, prefs().getInt(Saver.VOLT, 0)));
        setIfNew(tileSaver, Saver.saverOn(this) ? "On now" : vi == 0 ? "Off" : "Waiting: " + Saver.VOLT_NAME[vi]);
        setIfNew(tileMsgs, prefs().getBoolean(Saver.ALLOW_ON, false) ? Saver.chosen(this) + " apps chosen" : "All apps");
        boolean hold = Macros.trigger(this, true) != 0, twice = Macros.trigger(this, false) != 0;
        setIfNew(tileTrig, hold ? "Hold A + L: " + trigName(true) : twice ? "Double-tap A: " + trigName(false) : "Off");
        List<long[]> rows = Drain.load(this);
        setIfNew(tileDrain, rows.get(rows.size() - 1)[Drain.SENT] + " messages this hour");
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchLink.get(this).setListener(this);
        refreshTap();
        if (!Party.watchers.contains(playWatch)) Party.watchers.add(playWatch);
        refreshPlay();
        if (!NotifService.nowWatchers.contains(nowChanged)) NotifService.nowWatchers.add(nowChanged);
        showNow();
        if (prefs().getBoolean("music_pill", false) && Settings.canDrawOverlays(this)) NotifService.pillChanged();
        NotifService.appInstall = (w, a, over) -> ui.post(() -> {
            if (faceState == null) return;
            faceState.setText(a);
            int pct = -1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Sending\\.\\.\\. (\\d+)%").matcher(a);
            if (m.find()) pct = Integer.parseInt(m.group(1));
            else if (!over) pct = 0;
            if (over) pct = a.startsWith("Installed") ? 100 : -1;
            faceProgress(pct);
            if (over) {
                refreshFaces();
                if (pct == 100) ui.postDelayed(() -> faceProgress(-1), 1500);
            }
        });
        refreshFaces();
        aurora.play(true);
        onStatus(status);
        buildChecklist();
        showKey();
        buildApps();
        showTiles();
        ticks = 0;
        ui.post(refresh);
        ui.removeCallbacks(lcdStep);
        ui.postDelayed(lcdStep, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Party.watchers.remove(playWatch);
        stopReader();
        WatchLink.get(this).setListener(null);
        NotifService.nowWatchers.remove(nowChanged);
        NotifService.appInstall = null;
        ui.removeCallbacks(refresh);
        ui.removeCallbacks(lcdStep);
        aurora.play(false);          // no animation while nobody is looking
        dot.stop();
    }

    @Override
    public void onStatus(String s) {
        status = s;
        if (faceLink != null) faceLinkLine();
        setIfNew(statusText, s);
        boolean ok = s.startsWith("Connected to");
        if (linkBtn != null) {
            boolean wanted = WatchLink.get(this).isWanted();
            linkBtn.set(!wanted ? Glass.LinkPill.OFF : ok ? Glass.LinkPill.LINKED : Glass.LinkPill.LINKING);
        }
        if (live != null) live.setLive(ok);
        boolean trying = s.startsWith("Connect") || s.startsWith("Searching") || s.contains("Reconnecting")
                || s.startsWith("Waiting");
        dot.set(ok ? Glass.GOOD : trying ? Glass.WARN : Glass.BAD,
                ok ? Glass.Dot.BREATHE : trying ? Glass.Dot.BLINK : Glass.Dot.STILL);
    }

    // ---------- sections ----------

    private void showBattery() {
        List<Battery.Reading> all = Battery.load(this);
        if (all.isEmpty()) {
            voltRoll.set("--", false);
            setIfNew(voltSub, "Open the Pulse face on the watch for the first reading");
        } else {
            Battery.Reading last = all.get(all.size() - 1);
            countTo(last.volts);
            float day = Battery.perDay(all);
            String trend = Float.isNaN(day) ? "trend after about a day"
                    : String.format(Locale.US, "%s%.3f V a day", day >= 0 ? "−" : "+", Math.abs(day));
            setIfNew(voltSub, "updated " + ago(last.time) + "  ·  " + trend);
        }
        float left = Battery.daysLeft(this, all);
        setIfNew(forecastText, all.isEmpty() ? "" : Float.isNaN(left) ? "Forecast: needs about a day of falling readings"
                : left < 1f ? "Forecast: less than a day left at this rate"
                : "Forecast: about " + Math.round(left) + (Math.round(left) == 1 ? " day" : " days") + " left at this rate");
        spark.set(all);
        showTracks();
    }

    /** The two bars in the saver card: where saver mode starts against the cell now, and the off-hours of the day. */
    private void showTracks() {
        if (voltTrack == null || nightTrack == null) return;
        int mode = Math.max(0, Math.min(Saver.VOLT_AT.length - 1, prefs().getInt(Saver.VOLT, 0)));
        float edge = mode == 0 ? 0f : Math.max(0f, Math.min(1f, (Saver.VOLT_AT[mode] - 2.6f) / 0.6f));
        voltTrack.spans(0f, edge, 0f, 0f, true);
        List<Battery.Reading> all = Battery.load(this);
        voltTrack.mark(all.isEmpty() ? -1f
                : Math.max(0f, Math.min(1f, (all.get(all.size() - 1).volts - 2.6f) / 0.6f)));
        float a = Saver.from(this) / 24f, b = Saver.to(this) / 24f;
        boolean on = prefs().getBoolean(Saver.NIGHT, false);
        if (b > a) nightTrack.spans(a, b, 0f, 0f, on);
        else nightTrack.spans(a, a == b ? a : 1f, 0f, a == b ? 0f : b, on);
        java.util.Calendar now = java.util.Calendar.getInstance();
        nightTrack.mark((now.get(java.util.Calendar.HOUR_OF_DAY) + now.get(java.util.Calendar.MINUTE) / 60f) / 24f);
    }

    interface Pick { void at(int n); }

    /**
     * A chip that opens into its own menu: the chip itself swells into a sheet of glass holding
     * the choices, the page behind goes out of focus, and picking one folds it back into the chip.
     */
    private void morph(final View anchor, String[] names, int current, final Pick pick) {
        if (menuClose != null || rootView == null || anchor.getWidth() == 0) return;
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        final FrameLayout scrim = new FrameLayout(this);
        final ScrollView sheet = new ScrollView(this);
        sheet.setVerticalScrollBarEnabled(false);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xFAFFFFFF);
        glass.setCornerRadius(dp(20));
        glass.setStroke(dp(1), Color.WHITE);
        sheet.setBackground(glass);
        sheet.setClipToOutline(true);
        sheet.setElevation(dp(10));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), dp(6), dp(6), dp(6));
        final int[] chosen = {-1};
        final View page = scrolls[Math.max(0, tab)];
        final int[] aw = new int[2], rw = new int[2];
        anchor.getLocationInWindow(aw);
        rootView.getLocationInWindow(rw);
        final Runnable close = () -> {
            if (menuClose == null) return;
            menuClose = null;
            list.animate().alpha(0f).setStartDelay(0).setDuration(110).start();
            ValueAnimator sharp = ValueAnimator.ofFloat(10f, 0f);
            sharp.setDuration(300);
            sharp.addUpdateListener(x -> Glass.blur(page, (float) x.getAnimatedValue()));
            sharp.start();
            sheet.animate().scaleX(anchor.getWidth() / (float) Math.max(1, sheet.getWidth()))
                    .scaleY(anchor.getHeight() / (float) Math.max(1, sheet.getHeight())).alpha(0.2f)
                    .setDuration(320).setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(() -> {
                        rootView.removeView(scrim);
                        Glass.blur(page, 0f);
                        anchor.animate().alpha(1f).setDuration(140).start();
                        if (chosen[0] >= 0) pick.at(chosen[0]);
                    }).start();
        };
        View currentRow = null;
        for (int i = 0; i < names.length; i++) {
            TextView o = text(names[i], 15, i == current ? Color.WHITE : Glass.TEXT);
            o.setTypeface(MEDIUM);
            o.setPadding(dp(18), dp(11), dp(18), dp(11));
            o.setMinWidth(dp(150));
            if (i == current) { o.setBackground(Glass.accent(this, 14)); currentRow = o; }
            list.addView(o, new LinearLayout.LayoutParams(-1, -2));
        }
        // a soft highlight that glides to whichever row is under the finger; letting go picks it,
        // so you can press, slide down the list and lift on your choice
        final FrameLayout stack = new FrameLayout(this);
        final View glide = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x242F80ED);
        gd.setCornerRadius(dp(14));
        glide.setBackground(gd);
        glide.setAlpha(0f);
        stack.addView(glide, new FrameLayout.LayoutParams(-1, dp(44)));
        stack.addView(list);
        final int[] under = {-1};
        list.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            View hit = null;
            int idx = -1;
            for (int i = 0; i < list.getChildCount(); i++) {
                View r = list.getChildAt(i);
                if (e.getY() >= r.getTop() && e.getY() < r.getBottom()) { hit = r; idx = i; }
            }
            if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE) {
                if (hit != null && idx != under[0]) {
                    boolean first = under[0] < 0;
                    under[0] = idx;
                    glide.getLayoutParams().height = hit.getHeight();
                    glide.requestLayout();
                    if (first) {
                        glide.setTranslationY(hit.getTop());
                        glide.setScaleX(0.92f);
                        glide.animate().alpha(1f).scaleX(1f).setDuration(220).start();
                    } else {
                        glide.animate().translationY(hit.getTop()).setDuration(380).setInterpolator(Glass.GENTLE).start();
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    hit.animate().scaleX(1.03f).scaleY(1.03f).setDuration(160).start();
                    for (int i = 0; i < list.getChildCount(); i++)
                        if (i != idx) list.getChildAt(i).animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                }
                return true;
            }
            if (a == android.view.MotionEvent.ACTION_UP && hit != null) {
                hit.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                Glass.pop(hit);
                chosen[0] = idx;
                close.run();
                return true;
            }
            glide.animate().alpha(0f).setDuration(160).start();        // the list scrolled instead
            under[0] = -1;
            return true;
        });
        sheet.addView(stack);
        int rootW = rootView.getWidth(), rootH = rootView.getHeight();
        sheet.measure(View.MeasureSpec.makeMeasureSpec(rootW - dp(32), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(330), View.MeasureSpec.AT_MOST));
        int mw = sheet.getMeasuredWidth(), mh = sheet.getMeasuredHeight();
        int ax = aw[0] - rw[0], ay = aw[1] - rw[1];
        int left = Math.max(dp(16), Math.min(rootW - mw - dp(16), ax + anchor.getWidth() - mw));
        int top = Math.max(dp(48), Math.min(rootH - mh - dp(120), ay + anchor.getHeight() / 2 - mh / 2));
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(mw, mh);
        at.leftMargin = left;
        at.topMargin = top;
        scrim.addView(sheet, at);
        scrim.setOnClickListener(v -> close.run());
        rootView.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        menuClose = close;
        // starts as the chip (same place, same size) and swells into the menu
        sheet.setPivotX(ax + anchor.getWidth() / 2f - left);
        sheet.setPivotY(ay + anchor.getHeight() / 2f - top);
        sheet.setScaleX(anchor.getWidth() / (float) mw);
        sheet.setScaleY(anchor.getHeight() / (float) mh);
        sheet.setAlpha(0.7f);
        list.setAlpha(0f);
        anchor.animate().alpha(0f).setDuration(100).start();
        sheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(640).setInterpolator(Glass.BOUNCY).start();
        list.animate().alpha(1f).setStartDelay(150).setDuration(280).start();
        ValueAnimator soften = ValueAnimator.ofFloat(0f, 10f);
        soften.setDuration(360);
        soften.addUpdateListener(x -> Glass.blur(page, (float) x.getAnimatedValue()));
        soften.start();
        final View showRow = currentRow;
        if (showRow != null) sheet.post(() -> sheet.scrollTo(0, Math.max(0, showRow.getTop() - sheet.getHeight() / 2)));
    }

    /** The voltage rolls to its new value instead of jumping. */
    private void countTo(float v) {
        if (!Float.isNaN(voltShown) && Math.abs(v - voltShown) < 0.001f) return;
        boolean first = Float.isNaN(voltShown);
        voltShown = v;
        if (first) voltRoll.set(String.format(Locale.US, "%.2f V", v + 0.11f), false);   // so it rolls in the first time too
        voltRoll.set(String.format(Locale.US, "%.2f V", v), true);
    }

    private void buildChecklist() {
        checklist.removeAllViews();
        int todo = 0;
        String on = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        todo += check("Notification access", "Needed for everything", on != null && on.contains(getPackageName()),
                "Open", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        boolean bt = Build.VERSION.SDK_INT >= 31
                ? checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        todo += check("Bluetooth", "To find and talk to the watch", bt, "Allow", v -> askBluetooth());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        todo += check("Battery unrestricted", "So the phone doesn't put Pulse to sleep",
                pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()), "Fix", v -> startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        todo += check("Open YouTube", "Lets a watch search start a video", Settings.canDrawOverlays(this),
                "Allow", v -> startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        todo += check("Calendar", "For nEXt: classes, exams, assignments",
                checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
                "Allow", v -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 2));
        todo += check("Microphone", "For Ask AI (your earbuds' mic works too)",
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                "Allow", v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3));
        String app = YouTube.installedApp(this);
        todo += check("YouTube app", app == null ? "Not found: install YouTube or ReVanced"
                : app.startsWith("app.revanced") ? "ReVanced" : "YouTube", app != null, null, null);
        setupSec.summary.setText(todo == 0 ? "All set" : todo + " to do");
        setupSec.summary.setTextColor(todo == 0 ? Glass.GOOD : Glass.WARN);
        Glass.Icon badge = new Glass.Icon(todo == 0 ? Glass.Icon.CHECK : Glass.Icon.ALERT,
                todo == 0 ? Glass.GOOD : Glass.WARN, true);
        Glass.trail(setupSec.summary, todo == 0 ? badge.drawIn(setupSec.summary) : badge);
        if (todo > 0 && !setupSec.open) setupSec.toggle();
    }

    // ---------- battery saver ----------

    /** The apps seen lately, each with a switch (only used while "Only chosen apps" is on). */
    private void buildApps() {
        appRows.removeAllViews();
        boolean on = prefs().getBoolean(Saver.ALLOW_ON, false);
        appsSec.summary.setText(on ? Saver.chosen(this) + " chosen" : "All apps");
        List<String[]> apps = Saver.seenApps(this);
        if (apps.isEmpty()) {
            TextView none = text("No apps yet: they appear after their first notification.", 12, Glass.MUTED);
            none.setPadding(0, dp(8), 0, 0);
            appRows.addView(none);
            return;
        }
        for (final String[] a : apps) {
            appRows.addView(hairline());
            Glass.Toggle t = new Glass.Toggle(this, Glass.ACCENT);
            t.set(!on || Saver.allowed(this, a[0]), false);
            t.setOnChange(pick -> {
                Saver.allow(this, a[0], pick);
                appsSec.summary.setText(prefs().getBoolean(Saver.ALLOW_ON, false)
                        ? Saver.chosen(this) + " chosen" : "All apps");
            });
            String tag = Txt.tag(a[0]);
            final TextView rule = chip(Rules.SHORT[Rules.mode(this, a[0])]);
            rule.setOnClickListener(v -> morph(rule, Rules.MODE, Rules.mode(this, a[0]), n -> {
                Rules.mode(this, a[0], n);
                swapText(rule, Rules.SHORT[n]);
                if (n == Rules.WORDS) askWords(a[0], a[1]);
            }));
            LinearLayout both = new LinearLayout(this);
            both.setGravity(Gravity.CENTER_VERTICAL);
            both.addView(rule);
            both.addView(gap());
            both.addView(t);
            appRows.addView(lineRow(a[1], tag == null ? a[0] : "Shows as " + tag, both));
        }
    }

    /** The words an app's messages must have ("Only with these words"). */
    private void askWords(final String pkg, String name) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("exam, meeting, urgent");
        in.setText(Rules.words(this, pkg));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(in);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Words for " + name)
                .setMessage("Only messages with one of these words reach the watch. Put a comma between them.")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> Rules.words(this, pkg, in.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Saves a field as it is typed. */
    private void live(EditText e, final Words to) {
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { to.now(s.toString().trim()); }
        });
    }

    /**
     * The watch menu editor. Each item shows its name as the watch will (a strip of LCD); hold it
     * to drag it somewhere else, tap the name to rename it, switch it off to hide it.
     */
    private void buildMenu() {
        menuRows.removeAllViews();
        for (final WatchMenu.Item it : WatchMenu.load(this)) {
            final LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(10), dp(14), dp(10));
            r.setBackground(Glass.panel(this, 18));
            r.setClickable(true);
            View grip = Glass.icon(this, Glass.Icon.GRIP, Glass.FAINT, false);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(20), dp(20));
            gp.rightMargin = dp(10);
            r.addView(grip, gp);
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            final Glass.SegText name = new Glass.SegText(this);
            name.set(it.name);
            name.setOnClickListener(v -> rename(it, name));
            words.addView(name);
            TextView about = text(it.about(), 12, Glass.MUTED);
            about.setPadding(dp(2), dp(4), 0, 0);
            words.addView(about);
            r.addView(words, weight());
            Glass.Toggle shown = new Glass.Toggle(this, Glass.ACCENT);
            shown.set(it.shown, false);
            r.setAlpha(it.shown ? 1f : 0.55f);
            shown.setOnChange(on -> {
                List<WatchMenu.Item> all = WatchMenu.load(this);
                for (WatchMenu.Item x : all) if (x.id.equals(it.id)) x.shown = on;
                WatchMenu.save(this, all);
                it.shown = on;
                r.animate().alpha(on ? 1f : 0.55f).setDuration(260).start();
            });
            r.addView(shown);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
            at.topMargin = dp(8);
            menuRows.addView(r, at);
        }
    }

    /** A box to type the item's new name, with how the watch will show it right above. */
    private void rename(final WatchMenu.Item it, final Glass.SegText shownName) {
        final Glass.SegText preview = new Glass.SegText(this);
        preview.set(it.name);
        final EditText in = field(it.name);
        in.setText(it.name);
        in.setSelection(in.getText().length());
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        live(in, s -> preview.set(s));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(22), dp(12), dp(22), 0);
        box.addView(preview);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(12);
        box.addView(in, fl);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rename " + it.about())
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = Macros.clean(in.getText().toString());
                    if (n.isEmpty()) return;
                    List<WatchMenu.Item> all = WatchMenu.load(this);
                    for (WatchMenu.Item x : all) if (x.id.equals(it.id)) x.name = n;
                    WatchMenu.save(this, all);
                    it.name = n;
                    shownName.set(n);
                    Glass.pop(shownName);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The mirror of the watch's screen, and the warning when the face is the wrong version. */
    private void showMirror() {
        if (lcd == null) return;
        String what = History.mWhat, main = History.mMain;
        boolean clock = what.startsWith("Idle clock");
        if (clock) {
            java.util.Calendar now = java.util.Calendar.getInstance();
            int hr = now.get(java.util.Calendar.HOUR);
            main = String.format(Locale.US, "%2d%02d", hr == 0 ? 12 : hr, now.get(java.util.Calendar.MINUTE))
                    + (Saver.seconds(this) ? String.format(Locale.US, "%02d", now.get(java.util.Calendar.SECOND)) : "");
        }
        lcd.set(main, History.mDay, clock);
        setIfNew(mirrorWhat, what + "\nThe last screen the phone put up. The watch adds its own animations.");
        int fv = NotifService.faceVersion, need = NotifService.FACE_VERSION;
        boolean wrong = fv >= 0 && fv != need;
        if (wrong) setIfNew(versionText, fv < need
                ? "The face on the watch is older than this app expects (it has v" + fv + ", this app needs v" + need
                        + "). Load the Pulse.min.js that came with this app in Forge."
                : "The face on the watch is newer (v" + fv + ") than this app (v" + need + "). Install the newer app.");
        versionText.setVisibility(wrong ? View.VISIBLE : View.GONE);
        if (wrong && fv < need) setIfNew(versionText, "The face on the watch is older (v" + fv + "). Tap here to update it.");
        if (pulseChip != null) setIfNew(pulseChip, pulseLabel());
    }

    /** Gemini's count, the connection timeline and the list of what was sent. */
    private void showLogs() {
        if (usageText == null) return;
        int asked = Usage.today(this), limit = Usage.limit(this), hit = Usage.hitAt(this);
        setIfNew(usageText, "of " + limit + " requests today\n" + Math.max(0, limit - asked) + " left"
                + (hit > 0 ? "\nGoogle said the limit was reached at " + hit : ""));
        setIfNew(usageSum, asked + " today");
        usageCount.set(String.valueOf(asked), true);
        usageRing.set(asked / (float) limit);
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("MMM d  HH:mm", Locale.US),
                secs = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        int drops = History.dropsToday(this);
        setIfNew(timeSec.summary, drops == 0 ? "No drops today" : drops + (drops == 1 ? " drop today" : " drops today"));
        List<History.Item> links = History.linkList(this);
        long tm = links.isEmpty() ? 0 : links.get(links.size() - 1).time * 31 + links.size();
        if (timeSec.open && tm != timeMark) {
            timeMark = tm;
            timeRows.removeAllViews();
            for (int i = links.size() - 1; i >= Math.max(0, links.size() - 30); i--) {
                final History.Item it = links.get(i);
                final History.Item after = i + 1 < links.size() ? links.get(i + 1) : null;
                logRow(timeRows, clock.format(new java.util.Date(it.time)), it.text,
                        it.flag ? Glass.BAD : it.text.startsWith("Connected") ? Glass.GOOD : Glass.MUTED,
                        row -> linkDetail(row, it, after));
            }
            if (links.isEmpty()) timeRows.addView(text("Nothing yet", 13, Glass.MUTED));
        }
        List<History.Item> pushed = History.sentList(this);
        setIfNew(sentSec.summary, pushed.size() + " kept");
        long sm = pushed.isEmpty() ? 0 : pushed.get(pushed.size() - 1).time * 31 + pushed.size();
        if (sentSec.open && sm != sentMark) {
            sentMark = sm;
            sentRows.removeAllViews();
            for (int i = pushed.size() - 1; i >= Math.max(0, pushed.size() - 40); i--) {
                final History.Item it = pushed.get(i);
                logRow(sentRows, secs.format(new java.util.Date(it.time)), it.text,
                        it.flag ? Glass.ACCENT : Glass.FAINT, row -> sentDetail(row, it));
            }
            if (pushed.isEmpty()) sentRows.addView(text("Nothing yet", 13, Glass.MUTED));
        }
    }

    interface Tap { void on(View row); }

    /**
     * The Test button turns into a card and back: it stretches sideways with a tilt, unfolds into a
     * card showing the test as the watch shows it, then folds down into a blue pill that says
     * whether it reached the watch, and slips back into the button.
     */
    private void testMorph(final View anchor) {
        if (rootView == null || menuClose != null) return;
        final boolean linked = WatchLink.get(this).isReady();
        final int[] aw = new int[2], ow = new int[2];
        anchor.getLocationInWindow(aw);
        rootView.getLocationInWindow(ow);
        final android.graphics.RectF a = new android.graphics.RectF(aw[0] - ow[0], aw[1] - ow[1], aw[0] - ow[0] + anchor.getWidth(), aw[1] - ow[1] + anchor.getHeight());
        final float W = rootView.getWidth(), m = dp(24);
        // the card: compact, kept on screen, growing out of the button
        final float cw = Math.min(dp(250), W - m * 2), ch = dp(118);
        float ccx = Math.max(m + cw / 2f, Math.min(W - m - cw / 2f, a.centerX()));
        final android.graphics.RectF card = new android.graphics.RectF(ccx - cw / 2f, a.centerY() - dp(10) - ch / 2f + dp(26), ccx + cw / 2f, a.centerY() - dp(10) + ch / 2f + dp(26));
        final float pw = dp(196), ph = a.height();
        float pcx = Math.max(m + pw / 2f, Math.min(W - m - pw / 2f, a.centerX()));
        final android.graphics.RectF pill = new android.graphics.RectF(pcx - pw / 2f, a.centerY() - ph / 2f, pcx + pw / 2f, a.centerY() + ph / 2f);
        final android.graphics.RectF wide = new android.graphics.RectF(card.left, a.top, card.right, a.bottom);

        final Glass.Morph box = new Glass.Morph(this);
        final LinearLayout inside = new LinearLayout(this);
        inside.setOrientation(LinearLayout.VERTICAL);
        inside.setGravity(Gravity.CENTER_HORIZONTAL);
        Glass.SegText seg = new Glass.SegText(this);
        seg.set("PULSE");
        inside.addView(seg);
        TextView l1 = text("A test is on its way", 15, Glass.TEXT);
        l1.setTypeface(MEDIUM);
        l1.setPadding(0, dp(10), 0, 0);
        inside.addView(l1);
        inside.setAlpha(0f);
        box.addView(inside, new FrameLayout.LayoutParams(-2, -2));
        final LinearLayout tag = new LinearLayout(this);
        tag.setGravity(Gravity.CENTER_VERTICAL);
        tag.addView(Glass.icon(this, linked ? Glass.Icon.CHECK : Glass.Icon.ALERT, Color.WHITE, false),
                new LinearLayout.LayoutParams(dp(17), dp(17)));
        TextView l2 = text(linked ? "On your watch" : "Watch isn't linked", 15, Color.WHITE);
        l2.setTypeface(MEDIUM);
        l2.setPadding(dp(8), 0, 0, 0);
        tag.addView(l2);
        tag.setAlpha(0f);
        box.addView(tag, new FrameLayout.LayoutParams(-2, -2));
        rootView.addView(box, new FrameLayout.LayoutParams(-1, -1));
        box.rect.set(a);
        anchor.setAlpha(0f);
        final int tint = linked ? Glass.ACCENT : Glass.WARN;
        final android.graphics.RectF from = new android.graphics.RectF();
        // one step of the chain: the box goes from where it is to `to`
        final class Step {
            final android.graphics.RectF to; final long ms, wait; final boolean bounce; final float tiltPeak; final int colorTo;
            Step(android.graphics.RectF t, long d, long w, boolean b, float tp, int ct) { to = t; ms = d; wait = w; bounce = b; tiltPeak = tp; colorTo = ct; }
        }
        final Step[] steps = {
                new Step(wide, 300, 0, false, -3f, 0xFFFFFFFF),          // stretch sideways, tipping a little
                new Step(card, 620, 0, true, 0f, 0xFFFFFFFF),            // unfold into the card
                new Step(pill, 620, 1300, true, 2.5f, tint),             // fold into the coloured pill
                new Step(a, 420, 1200, false, 0f, 0xFFFFFFFF)};          // back into the button
        final Runnable[] next = new Runnable[1];
        final int[] at = {0};
        next[0] = () -> {
            if (at[0] >= steps.length) {
                rootView.removeView(box);
                anchor.setAlpha(1f);
                Glass.pop(anchor);
                return;
            }
            final Step s = steps[at[0]++];
            from.set(box.rect);
            final float tilt0 = box.tilt;
            final int color0 = box.color;
            final int idx = at[0];
            ValueAnimator v = ValueAnimator.ofFloat(0f, 1f);
            v.setDuration(s.ms);
            v.setStartDelay(s.wait);
            v.setInterpolator(null);
            v.addUpdateListener(x -> {
                float f = x.getAnimatedFraction();
                float e = s.bounce ? Glass.BOUNCY.getInterpolation(f) : Glass.GENTLE.getInterpolation(f);
                box.rect.set(from.left + (s.to.left - from.left) * e, from.top + (s.to.top - from.top) * e,
                        from.right + (s.to.right - from.right) * e, from.bottom + (s.to.bottom - from.bottom) * e);
                box.tilt = idx == 1 ? s.tiltPeak * Glass.GENTLE.getInterpolation(f)
                        : tilt0 * (1f - Glass.GENTLE.getInterpolation(f)) + s.tiltPeak * (float) Math.sin(Math.PI * f);
                box.color = Glass.Lcd.mix(color0, s.colorTo, Math.min(1f, f * 1.6f));
                if (idx == 2) inside.setAlpha(Math.max(0f, Math.min(1f, (f - 0.4f) / 0.35f)));
                if (idx == 3) {
                    inside.setAlpha(Math.max(0f, 1f - f * 4f));
                    tag.setAlpha(Math.max(0f, Math.min(1f, (f - 0.45f) / 0.35f)));
                }
                if (idx == 4) {
                    tag.setAlpha(Math.max(0f, 1f - f * 3f));
                    box.setAlpha(1f - Math.max(0f, (f - 0.7f) / 0.3f));
                    anchor.setAlpha(Math.max(0f, (f - 0.6f) / 0.4f));
                }
                box.frame();
            });
            v.addListener(after(next[0]));
            v.start();
        };
        box.post(() -> { box.frame(); next[0].run(); });
    }

    private static android.animation.Animator.AnimatorListener after(final Runnable r) {
        return new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { r.run(); }
        };
    }

    /**
     * Quick actions from the tab bar: hold it and the bar turns to liquid, gathers into a drop,
     * and blooms into a glass menu. Picking something, or tapping outside, pours it back.
     */
    private void liquidMenu(float pressX) {
        if (menuClose != null || rootView == null) return;
        final int[] bw = new int[2], ow = new int[2];
        tabBar.getLocationInWindow(bw);
        rootView.getLocationInWindow(ow);
        final android.graphics.RectF bar = new android.graphics.RectF(bw[0] - ow[0], bw[1] - ow[1], bw[0] - ow[0] + tabBar.getWidth(), bw[1] - ow[1] + tabBar.getHeight());
        // the menu's rows
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(20), dp(18), dp(20), dp(18));
        final Runnable[] close = new Runnable[1];
        final WatchLink link = WatchLink.get(this);
        TextView head = text("QUICK ACTIONS", 11, Glass.MUTED);
        head.setLetterSpacing(0.14f);
        head.setPadding(dp(4), 0, 0, dp(6));
        rows.addView(head);
        rows.addView(quickRow(Glass.Icon.LINK, link.isWanted() ? "Disconnect the watch" : "Connect the watch", () -> {
            if (link.isWanted()) link.disconnect(); else link.connect();
        }, close));
        rows.addView(quickRow(Glass.Icon.BOLT, "Send a test", () -> NotifService.test(this), close));
        final boolean saverNow = prefs().getInt(Saver.VOLT, 0) == Saver.VOLT_NAME.length - 1;
        rows.addView(quickRow(Glass.Icon.LEAF, saverNow ? "Watch saver back to normal" : "Watch saver on now", () -> {
            android.content.SharedPreferences sp = prefs();
            int nowV = sp.getInt(Saver.VOLT, 0), always = Saver.VOLT_NAME.length - 1;
            if (nowV == always) sp.edit().putInt(Saver.VOLT, sp.getInt("sv_volt_prev", 0)).apply();
            else sp.edit().putInt("sv_volt_prev", nowV).putInt(Saver.VOLT, always).apply();
            NotifService.saverChanged();
        }, close));
        LinearLayout profs = new LinearLayout(this);
        profs.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < Profiles.NAMES.length; i++) {
            final int n = i;
            TextView pb = pill(Profiles.NAMES[i], i == Profiles.current(this), v -> {
                Profiles.apply(this, n);
                close[0].run();
                ui.postDelayed(() -> recreate(), 900);
            });
            if (i > 0) profs.addView(gap());
            profs.addView(pb, weight());
        }
        rows.addView(profs);
        int rootW = rootView.getWidth();
        rows.measure(View.MeasureSpec.makeMeasureSpec(rootW - dp(40), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rootView.getHeight(), View.MeasureSpec.AT_MOST));
        int mh = rows.getMeasuredHeight();
        final android.graphics.RectF menu = new android.graphics.RectF(dp(20), bar.top - dp(16) - mh, rootW - dp(20), bar.top - dp(16));
        final FrameLayout scrim = new FrameLayout(this);
        final Glass.Liquid drop = new Glass.Liquid(this, bar, menu, bar.left + pressX);
        scrim.addView(drop, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Math.round(menu.width()), mh);
        rl.leftMargin = Math.round(menu.left);
        rl.topMargin = Math.round(menu.top);
        scrim.addView(rows, rl);
        rows.setAlpha(0f);
        rootView.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        final View page = scrolls[Math.max(0, tab)];
        tabBar.animate().alpha(0f).setDuration(90).start();
        final ValueAnimator open = ValueAnimator.ofFloat(0f, 1f);
        open.setDuration(1050);
        open.setInterpolator(null);
        open.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            drop.set(f);
            float in = Math.max(0f, Math.min(1f, (f - 0.62f) / 0.3f));
            rows.setAlpha(in);
            Glass.blur(rows, 14f * (1f - in));
            Glass.blur(page, 10f * Math.min(1f, f * 2f));
        });
        open.start();
        close[0] = () -> {
            if (menuClose == null) return;
            menuClose = null;
            open.cancel();
            ValueAnimator shut = ValueAnimator.ofFloat(1f, 0f);
            shut.setDuration(820);
            shut.setInterpolator(new DecelerateInterpolator(1.2f));
            shut.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                drop.set(f);
                float in = Math.max(0f, Math.min(1f, (f - 0.7f) / 0.3f));
                rows.setAlpha(in);
                Glass.blur(rows, 14f * (1f - in));
                Glass.blur(page, 10f * Math.min(1f, f * 2f));
            });
            shut.addListener(after(() -> {
                rootView.removeView(scrim);
                Glass.blur(page, 0f);
                tabBar.setAlpha(1f);
                for (int i = 0; i < tabIcons.length; i++) {          // the icons surface again, one by one
                    final View ic = tabIcons[i];
                    ic.setScaleX(0.4f);
                    ic.setScaleY(0.4f);
                    ic.animate().scaleX(1f).scaleY(1f).setStartDelay(40L * i).setDuration(560)
                            .setInterpolator(Glass.BOUNCY).start();
                }
            }));
            shut.start();
        };
        scrim.setOnClickListener(v -> close[0].run());
        menuClose = close[0];
    }

    /** One row of the quick menu: an icon in a disc and what it does. */
    private View quickRow(int kind, String what, final Runnable act, final Runnable[] close) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(9), dp(4), dp(9));
        r.addView(Glass.icon(this, kind, Glass.ACCENT, false), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = text(what, 16, Glass.TEXT);
        t.setPadding(dp(14), 0, 0, 0);
        r.addView(t, weight());
        r.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            act.run();
            close[0].run();
        });
        Glass.spring(r);
        return r;
    }

    /**
     * Run a macro from the phone: tap the round button and your macros burst out around it in a
     * spiral, each on a little spring; tap one to run it and the rest are drawn back in.
     */
    private void macroWheel(LinearLayout col) {
        LinearLayout c = card(col, 28);
        c.addView(label("RUN FROM HERE"));
        c.addView(note("Tap the button to fan out your macros, then tap one to run it."));
        final FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        c.setClipChildren(false);
        col.setClipChildren(false);
        c.addView(stage, new LinearLayout.LayoutParams(-1, dp(250)));
        final FrameLayout hub = new FrameLayout(this);
        hub.setBackground(Glass.accent(this, 34));
        hub.setElevation(dp(6));
        final View bolt = Glass.icon(this, Glass.Icon.BOLT, Color.WHITE, false);
        hub.addView(bolt, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        stage.addView(hub, new FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER));
        Glass.spring(hub);
        final List<View> petals = new java.util.ArrayList<>();
        final boolean[] open = {false};
        final Runnable[] shut = new Runnable[1];
        shut[0] = () -> {
            open[0] = false;
            bolt.animate().rotation(0f).setDuration(500).setInterpolator(Glass.BOUNCY).start();
            for (int i = 0; i < petals.size(); i++) {
                final View pv = petals.get(i);
                pv.animate().translationX(0f).translationY(0f).scaleX(0.3f).scaleY(0.3f).alpha(0f).rotation(-60f)
                        .setStartDelay(18L * i).setDuration(300).setInterpolator(new DecelerateInterpolator(1.6f))
                        .withEndAction(() -> stage.removeView(pv)).start();
            }
            petals.clear();
        };
        hub.setOnClickListener(v -> {
            if (open[0]) { shut[0].run(); return; }
            final List<Macros.Slot> slots = Macros.active(this);
            if (slots.isEmpty()) { Toast.makeText(this, "Name a macro below first", Toast.LENGTH_SHORT).show(); return; }
            open[0] = true;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            bolt.animate().rotation(180f).setDuration(600).setInterpolator(Glass.BOUNCY).start();
            float radius = dp(slots.size() > 6 ? 98 : 88);
            for (int i = 0; i < slots.size(); i++) {
                final Macros.Slot m = slots.get(i);
                final Glass.SegText name = new Glass.SegText(this);
                name.set(m.name);
                FrameLayout petal = new FrameLayout(this);
                petal.setBackground(Glass.panel(this, 22));
                petal.setElevation(dp(4));
                petal.setPadding(dp(6), dp(6), dp(6), dp(6));
                petal.addView(name);
                FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
                stage.addView(petal, pl);
                petals.add(petal);
                double ang = Math.toRadians(-90 + 360.0 * i / slots.size());
                float tx = (float) (Math.cos(ang) * radius * 1.25), ty = (float) (Math.sin(ang) * radius);
                petal.setScaleX(0.3f);
                petal.setScaleY(0.3f);
                petal.setAlpha(0f);
                petal.setRotation(-70f);
                // a spiral: each one swings round a little as it flies out, then springs into place
                final ValueAnimator out = ValueAnimator.ofFloat(0f, 1f);
                out.setStartDelay(40L * i);
                out.setDuration(760);
                out.setInterpolator(null);
                final double a0 = ang - Math.toRadians(55);
                out.addUpdateListener(a -> {
                    float f = a.getAnimatedFraction(), e2 = Glass.BOUNCY.getInterpolation(f);
                    double an = a0 + (ang - a0) * Glass.GENTLE.getInterpolation(f);
                    float rr = radius * e2;
                    petal.setTranslationX((float) (Math.cos(an) * rr * 1.25));
                    petal.setTranslationY((float) (Math.sin(an) * rr));
                    petal.setScaleX(0.3f + 0.7f * e2);
                    petal.setScaleY(0.3f + 0.7f * e2);
                    petal.setAlpha(Math.min(1f, f * 3f));
                    petal.setRotation(-70f * (1f - Glass.GENTLE.getInterpolation(f)));
                });
                out.start();
                petal.setOnClickListener(pv -> {
                    pv.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                    String said = NotifService.runMacro(this, m);
                    Toast.makeText(this, said, Toast.LENGTH_SHORT).show();
                    pv.animate().scaleX(1.25f).scaleY(1.25f).setDuration(160).withEndAction(() -> shut[0].run()).start();
                });
                Glass.spring(petal);
            }
        });
    }

    /** One line of a log: a coloured dot, the time and what happened. Tapping it opens it up. */
    private void logRow(LinearLayout into, String time, String what, int dotColor, final Tap open) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(9), dp(4), dp(9));
        View dotView = Glass.icon(this, Glass.Icon.DOT, dotColor, false);
        r.addView(dotView, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView t = text(time, 12, Glass.MUTED);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(dp(6), 0, dp(10), 0);
        r.addView(t);
        TextView w = text(what, 13, Glass.TEXT);
        w.setSingleLine(true);
        w.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(w, weight());
        r.addView(Glass.icon(this, Glass.Icon.CHEVRON, Glass.FAINT, false), new LinearLayout.LayoutParams(dp(14), dp(14)));
        r.setOnClickListener(v -> open.on(v));
        Glass.spring(r);
        into.addView(r);
    }

    /** A drop or a reconnect, opened up: when, what Android said, and how long it took to come back. */
    private void linkDetail(View row, History.Item it, History.Item after) {
        String when = new java.text.SimpleDateFormat("EEE MMM d, h:mm:ss a", Locale.US).format(new java.util.Date(it.time));
        String meaning = it.text.contains("code 8") ? "The watch went out of range or fell asleep."
                : it.text.contains("code 19") ? "The watch ended the link itself (often another app took it, or the face reset)."
                : it.text.contains("code 22") ? "The phone ended it (Bluetooth restarted, or battery saving)."
                : it.text.contains("code 133") ? "Android couldn't open the link. Closing the Ollee app usually fixes it."
                : it.text.contains("code 147") ? "The watch stopped answering mid-link."
                : it.text.startsWith("Connected") ? "Linked and ready."
                : it.text.startsWith("Night") ? "Night link-off: Pulse let go on purpose."
                : "";
        String back = after == null ? (it.flag ? "Not back yet" : "This is the latest")
                : "Next: " + after.text + " after " + gap(after.time - it.time);
        detail(row, it.flag ? Glass.Icon.ALERT : Glass.Icon.CHECK, it.flag ? Glass.BAD : Glass.GOOD,
                it.flag ? "Link dropped" : "Link", new String[]{when, it.text, meaning, back});
    }

    /** A line sent to the watch, opened up. */
    private void sentDetail(View row, History.Item it) {
        String when = new java.text.SimpleDateFormat("EEE MMM d, h:mm:ss a", Locale.US).format(new java.util.Date(it.time));
        detail(row, Glass.Icon.ARROW, Glass.ACCENT, "Sent to the watch", new String[]{when, it.text,
                it.flag ? "The Pulse face was showing, so it appeared on the watch."
                        : "Another face was showing: the watch woke its radio for it but kept that face.",
                "Each line like this wakes the watch's Bluetooth for a moment."});
    }

    private static String gap(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + (s / 60 % 60) + " min";
    }

    /**
     * A row grows into a card in the middle of the screen: its dot becomes the card's icon, the
     * page behind goes out of focus, and closing it shrinks it back into the row it came from.
     */
    private void detail(final View row, int iconKind, int color, String title, String[] lines) {
        if (menuClose != null || rootView == null) return;
        row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x00000000);
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(22));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xFCFFFFFF);
        glass.setCornerRadius(dp(26));
        glass.setStroke(dp(1), Color.WHITE);
        card.setBackground(glass);
        card.setElevation(dp(14));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View big = Glass.icon(this, iconKind, color, true);
        head.addView(big, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = text(title, 20, Glass.TEXT);
        t.setTypeface(MEDIUM);
        t.setPadding(dp(14), 0, 0, 0);
        head.addView(t, weight());
        View x = Glass.icon(this, Glass.Icon.CLOSE, Glass.MUTED, false);
        head.addView(x, new LinearLayout.LayoutParams(dp(30), dp(30)));
        card.addView(head);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null || lines[i].isEmpty()) continue;
            TextView l = text(lines[i], i == 1 ? 16 : 14, i == 1 ? Glass.TEXT : Glass.SOFT);
            l.setPadding(0, dp(8), 0, 0);
            body.addView(l);
        }
        card.addView(body);
        final View page = scrolls[Math.max(0, tab)];
        int rootW = rootView.getWidth(), rootH = rootView.getHeight();
        int cw = rootW - dp(40);
        card.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rootH, View.MeasureSpec.AT_MOST));
        int ch = card.getMeasuredHeight();
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(cw, ch);
        at.leftMargin = dp(20);
        at.topMargin = Math.max(dp(60), (rootH - ch) / 2 - dp(40));
        scrim.addView(card, at);
        rootView.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        // start exactly over the row
        final int[] rw = new int[2], ow = new int[2];
        row.getLocationInWindow(rw);
        rootView.getLocationInWindow(ow);
        final float sx = row.getWidth() / (float) cw, sy = row.getHeight() / (float) ch;
        final float dx = (rw[0] - ow[0]) - at.leftMargin, dy = (rw[1] - ow[1]) - at.topMargin;
        card.setPivotX(0f);
        card.setPivotY(0f);
        card.setTranslationX(dx);
        card.setTranslationY(dy);
        card.setScaleX(sx);
        card.setScaleY(sy);
        body.setAlpha(0f);
        t.setAlpha(0f);
        row.setAlpha(0f);
        card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(700)
                .setInterpolator(Glass.BOUNCY).start();
        t.animate().alpha(1f).setStartDelay(160).setDuration(260).start();
        body.animate().alpha(1f).setStartDelay(220).setDuration(320).start();
        ValueAnimator soften = ValueAnimator.ofFloat(0f, 12f);
        soften.setDuration(380);
        soften.addUpdateListener(a -> {
            float b = (float) a.getAnimatedValue();
            Glass.blur(page, b);
            scrim.setBackgroundColor(Math.round(b * 4) << 24 | 0x0B2A4A);
        });
        soften.start();
        final Runnable close = () -> {
            if (menuClose == null) return;
            menuClose = null;
            body.animate().alpha(0f).setStartDelay(0).setDuration(120).start();
            t.animate().alpha(0f).setStartDelay(0).setDuration(120).start();
            ValueAnimator sharp = ValueAnimator.ofFloat(12f, 0f);
            sharp.setDuration(340);
            sharp.addUpdateListener(a -> {
                float b = (float) a.getAnimatedValue();
                Glass.blur(page, b);
                scrim.setBackgroundColor(Math.round(b * 4) << 24 | 0x0B2A4A);
            });
            sharp.start();
            card.animate().translationX(dx).translationY(dy).scaleX(sx).scaleY(sy).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).withEndAction(() -> {
                        rootView.removeView(scrim);
                        Glass.blur(page, 0f);
                        row.setAlpha(1f);
                        Glass.pop(row);
                    }).start();
        };
        x.setOnClickListener(v -> close.run());
        scrim.setOnClickListener(v -> close.run());
        card.setClickable(true);
        menuClose = close;
    }

    /** Writes the setup to the file the user picked, or reads one in. */
    private void setupFile(boolean export, Uri uri) {
        try {
            if (export) {
                try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(Setup.write(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "Setup saved", Toast.LENGTH_SHORT).show();
                return;
            }
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[8192];
                for (int got; (got = in.read(buf)) > 0; ) all.write(buf, 0, got);
            }
            int count = Setup.read(this, new String(all.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this, count + " settings put in place", Toast.LENGTH_SHORT).show();
            recreate();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't use that file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDrain() {
        if (drainSpan == null) return;
        List<long[]> rows = Drain.load(this);
        long[] sum = new long[6], last = rows.get(rows.size() - 1);
        for (long[] r : rows) for (int i = 1; i < 6; i++) sum[i] += r[i];
        setIfNew(drainSpan, "Last " + rows.size() + " h");
        int[] at = {Drain.SENT, Drain.MUSIC, Drain.KEEPS, Drain.NOTES};
        String[] hour = new String[5], total = new String[5];
        float[] amount = new float[5];
        for (int i = 0; i < 4; i++) {
            hour[i] = String.valueOf(last[at[i]]);
            total[i] = String.valueOf(sum[at[i]]);
            amount[i] = sum[at[i]];
        }
        hour[4] = String.format(Locale.US, "%.1f min", last[Drain.ANIM_MS] / 60000f);
        total[4] = String.format(Locale.US, "%.1f min", sum[Drain.ANIM_MS] / 60000f);
        amount[4] = sum[Drain.ANIM_MS] / 6000f;              // a tenth of a minute counts like one message
        drainChart.set(hour, total, amount);
    }

    // ---------- ask with a photo ----------

    private void takePhoto() {
        prefs().edit().putString("photoq", photoQ.getText().toString().trim()).apply();
        Photo.shotFile(this).delete();
        Uri out = Photo.shotUri();
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
        i.setClipData(ClipData.newRawUri("", out));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickPhoto() {
        prefs().edit().putString("photoq", photoQ.getText().toString().trim()).apply();
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        shared(i);
    }

    /** A photo shared to Pulse from the gallery (Share, then Pulse): ask about it like a picked one. */
    private void shared(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction()) || i.getType() == null
                || !i.getType().startsWith("image/")) return;
        Object stream = i.getExtras() == null ? null : i.getExtras().get(Intent.EXTRA_STREAM);
        if (!(stream instanceof Uri)) return;
        i.setAction(Intent.ACTION_MAIN);             // handled: don't run it again if the screen is rebuilt
        showTab(WATCH);
        if (photoSection != null && !photoSection.open) photoSection.toggle();
        prefs().edit().putString("photoq", photoQ.getText().toString().trim()).apply();
        readPhoto((Uri) stream);
    }

    private TextView pulseChip;

    /** The Pulse face row: up to date, an update waiting, or not known yet. */
    private String pulseLabel() {
        int fv = NotifService.faceVersion, b = NotifService.bundledVersion(this);
        if (fv < 0) return "v" + b;
        return fv < b ? "Update to v" + b : "v" + fv + " \u2713";
    }

    /** Install the Pulse face this app carries into Forge C: an update, or a repair. */
    private void pulseSheet() {
        int fv = NotifService.faceVersion, b = NotifService.bundledVersion(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(note((fv >= 0 ? "The watch has Pulse v" + fv + ". " : "")
                + "This app carries Pulse v" + b + ". It goes into Forge C, replacing the Pulse face there. "
                + "Takes about 40 seconds; the watch shows the progress, then Pulse starts again."));
        final Runnable[] close = new Runnable[1];
        c.addView(new HoldPill(this, "Hold to install Pulse", "Installing", Glass.ACCENT, false).onDone(() -> {
            if (!NotifService.updatePulseNow()) faceState.setText("Pulse isn't running: allow Notification access in Setup");
            ui.postDelayed(() -> { if (close[0] != null) close[0].run(); }, 700);
        }), new LinearLayout.LayoutParams(-1, dp(52)));
        close[0] = sheet(fv >= 0 && fv < b ? "Update the Pulse face?" : "Reinstall the Pulse face?", "Forge C", c);
    }

    private TextView playState, playLink, playWeb, playWho, playMain, playStart, playUno, playBuck, playWhere;
    private Section playOnlineSec;
    private final Runnable playWatch = () -> ui.post(this::refreshPlay);

    /** The PLAY card: which game, local or online, the links, who joined, and the buttons that make sense. */
    private void refreshPlay() {
        if (playState == null) return;
        Party p = Party.now;
        String kind = p != null ? p.kind : Party.lastKind(this);
        boolean online = p != null ? p.online != null : Party.wantOnline(this);
        playUno.setTextColor("uno".equals(kind) ? Color.WHITE : Glass.ACCENT);
        playUno.setBackground("uno".equals(kind) ? Glass.accent(this, 14) : Glass.panel(this, 14));
        playBuck.setTextColor("buck".equals(kind) ? Color.WHITE : Glass.ACCENT);
        playBuck.setBackground("buck".equals(kind) ? Glass.accent(this, 14) : Glass.panel(this, 14));
        playWhere.setText(online ? "Online" : "Local");
        playUno.setEnabled(p == null);
        playBuck.setEnabled(p == null);
        if (p == null) {
            playState.setText("Not hosting");
            playLink.setVisibility(View.GONE);
            playWeb.setVisibility(View.GONE);
            playWho.setVisibility(View.GONE);
            playMain.setText("Host");
            playStart.setVisibility(View.GONE);
            return;
        }
        String link = Party.link();
        StringBuilder who = new StringBuilder();
        boolean started, over;
        synchronized (p.lock) {
            Game g = p.game;
            started = g != null;
            over = started && g.winner() >= 0;
            Uno u = p.uno();
            Buck bk = p.buck();
            for (Party.Player pl : p.players) {
                if (who.length() > 0) who.append(", ");
                who.append(pl.seat == 0 ? "You (watch)" : pl.name + (pl.online ? " ☁" : ""));
                if (u != null) who.append(" ").append(u.hands.get(pl.seat).size());
                if (bk != null) who.append(" ♥").append(bk.hp[pl.seat]);
                if (p.score[pl.seat] > 0) who.append(" (").append(p.score[pl.seat]).append(" won)");
            }
            playState.setText((!started ? "Lobby · game " + p.code : over ? "Game over" : "Playing") + " · " + p.title());
            playStart.setVisibility((!started || over) && p.players.size() > 1 ? View.VISIBLE : View.GONE);
            playStart.setText(over ? "Again" : "Start");
        }
        playLink.setVisibility(View.VISIBLE);
        playLink.setText(link.isEmpty() ? (online ? "No Wi-Fi: online only" : "Join Wi-Fi or turn on your hotspot") : link);
        String web = p.onlineLink(), problem = p.online != null ? p.online.problem : "";
        playWeb.setVisibility(online ? View.VISIBLE : View.GONE);
        playWeb.setTextColor(problem.isEmpty() && !web.isEmpty() ? Glass.ACCENT : Glass.WARN);
        playWeb.setText(!problem.isEmpty() ? problem : web.isEmpty() ? "Online: add your game page link below" : web);
        playWho.setVisibility(View.VISIBLE);
        playWho.setText(who);
        playMain.setText("End");
    }

    private void installFace(String file, String slot) {
        if (!NotifService.install(file, slot)) faceState.setText("Pulse isn't running: allow Notification access in Setup");
    }

    // ---------- the mini player on Home ----------

    /** Frosted glass: brighter at the top, a fine bright edge. */
    private GradientDrawable glassBg(int radiusDp, boolean strong) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                strong ? new int[]{0x52FFFFFF, 0x24FFFFFF, 0x14FFFFFF} : new int[]{0x3DFFFFFF, 0x1AFFFFFF});
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, dp(1)), strong ? 0x80FFFFFF : 0x4DFFFFFF);
        return g;
    }

    /**
     * The cover, blurred smooth: a small copy blurred three times over and stretched up with
     * filtering, so it comes out soft (no blocks), and it stays inside the card.
     */
    private void blurInto(ImageView v, android.graphics.Bitmap art) {
        if (art == null) { v.setImageDrawable(null); return; }
        int w = 72, h = Math.max(24, Math.min(144, Math.round(72f * art.getHeight() / Math.max(1, art.getWidth()))));
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(art, w, h, true)
                .copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) { boxBlur(px, tmp, w, h, 6, true); boxBlur(tmp, px, w, h, 6, false); }
        small.setPixels(px, 0, w, 0, 0, w, h);
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), small);
        d.setFilterBitmap(true);
        d.setDither(true);
        v.setImageDrawable(d);
    }

    /** One box-blur pass along rows (or columns). */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean rows) {
        int n = rows ? w : h, lines = rows ? h : w;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < n; i++) {
                int a = 0, rr = 0, gg = 0, bb = 0, cnt = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(n - 1, i + k));
                    int c = in[rows ? l * w + j : j * w + l];
                    a += c >>> 24; rr += (c >> 16) & 255; gg += (c >> 8) & 255; bb += c & 255; cnt++;
                }
                out[rows ? l * w + i : i * w + l] = ((a / cnt) << 24) | ((rr / cnt) << 16) | ((gg / cnt) << 8) | (bb / cnt);
            }
        }
    }

    /** A cover with its words under it, on frosted glass: the middle card, or a smaller one beside it. */
    private LinearLayout coverCard(boolean main) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(8), dp(8), dp(8), dp(10));
        c.setBackground(glassBg(main ? 24 : 18, main));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(0x33FFFFFF);
        artBg.setCornerRadius(dp(main ? 16 : 12));
        art.setBackground(artBg);
        art.setClipToOutline(true);
        int size = main ? 150 : 104;
        c.addView(art, new LinearLayout.LayoutParams(dp(size), dp(size)));
        TextView t = text("", main ? 16 : 12, Color.WHITE);
        t.setTypeface(MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(0, dp(main ? 10 : 7), 0, 0);
        c.addView(t, new LinearLayout.LayoutParams(dp(size), -2));
        TextView a2 = text("", main ? 13 : 11, 0xCCFFFFFF);
        a2.setGravity(Gravity.CENTER);
        a2.setSingleLine(true);
        a2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(a2, new LinearLayout.LayoutParams(dp(size), -2));
        return c;
    }

    /** One cover in the carousel, and where it sits: pos -1 = before, 0 = middle, 1 = next. */
    private final class Cover {
        final LinearLayout v;
        final ImageView art;
        final TextView title, artist;
        float pos, from, to, here, hereFrom, hereTo;     // here: 0 = hidden (no song), 1 = shown
        String key = "";
        android.graphics.Bitmap shown;

        Cover(LinearLayout v) {
            this.v = v;
            art = (ImageView) v.getChildAt(0);
            title = (TextView) v.getChildAt(1);
            artist = (TextView) v.getChildAt(2);
        }
    }

    private void buildNowCard(LinearLayout col) {
        nowCard = card(col, 28);
        nowCard.setPadding(0, 0, 0, 0);
        nowCard.setBackground(Glass.accent(this, 28));
        nowCard.setClipToOutline(true);
        nowHomeIndex = col.indexOfChild(nowCard);
        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(true);
        nowFrame = frame;
        nowCard.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        // the blurred cover behind everything, and a shade so white words read on any cover
        nowBack = new ImageView(this);
        nowBack2 = new ImageView(this);
        for (ImageView b : new ImageView[]{nowBack, nowBack2}) {
            b.setScaleType(ImageView.ScaleType.CENTER_CROP);
            b.setAlpha(0f);
            frame.addView(b, new FrameLayout.LayoutParams(-1, -1));
        }
        nowShade = new View(this);
        nowShade.setAlpha(0f);
        frame.addView(nowShade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(16), dp(18), dp(16));
        frame.addView(body, new FrameLayout.LayoutParams(-1, -2));

        // nothing playing: the plain card
        nowSmall = new LinearLayout(this);
        nowSmall.setOrientation(LinearLayout.VERTICAL);
        TextView nowLabel = text("Now playing", 13, 0xCCFFFFFF);
        nowLabel.setPadding(0, 0, 0, dp(4));
        nowSmall.addView(nowLabel);
        musicText = text("Nothing playing", 17, Color.WHITE);
        musicText.setTypeface(MEDIUM);
        nowSmall.addView(musicText);
        body.addView(nowSmall);

        // playing: the carousel and the glass bar
        nowFull = new LinearLayout(this);
        nowFull.setOrientation(LinearLayout.VERTICAL);
        nowFull.setVisibility(View.GONE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView np = text("NOW PLAYING", 11, 0xCCFFFFFF);
        np.setTypeface(MEDIUM);
        np.setLetterSpacing(0.2f);
        head.addView(np, weight());
        nowApp = text("", 12, Color.WHITE);
        nowApp.setTypeface(MEDIUM);
        nowApp.setPadding(dp(10), dp(4), dp(10), dp(4));
        nowApp.setBackground(glassBg(12, false));
        head.addView(nowApp);
        nowFull.addView(head);

        final FrameLayout carousel = new FrameLayout(this);
        carousel.setClipChildren(false);
        float camera = 8000 * getResources().getDisplayMetrics().density;
        for (int i = 0; i < 4; i++) {
            LinearLayout c = coverCard(true);
            c.setCameraDistance(camera);
            carousel.addView(c, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            covers[i] = new Cover(c);
            covers[i].pos = covers[i].from = covers[i].to = i == 3 ? 2 : i - 1;
            placeCover(covers[i]);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(250));
        cp.topMargin = dp(10);
        nowFull.addView(carousel, cp);
        // swipe the covers: they all follow the finger; left for the next song, right for the one before
        final float[] downX = {0};
        carousel.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = e.getX();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    settleCovers();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float d = (e.getX() - downX[0]) / dp(150);
                    coverDrag = Math.max(-1.1f, Math.min(1.1f, d));
                    for (Cover c : covers) placeCover(c);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    float dx = e.getX() - downX[0];
                    for (Cover c : covers) c.pos += coverDrag;   // keep where the finger left them
                    coverDrag = 0;
                    if (Math.abs(dx) > dp(60)) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        skipSong(dx < 0 ? 1 : -1);
                    } else {
                        moveCovers();                            // not far enough: back into place
                    }
                    return true;
            }
            return false;
        });


        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(10), dp(6));
        bar.setBackground(glassBg(30, true));
        MediaBtn prev = new MediaBtn(this, MediaBtn.PREV), next = new MediaBtn(this, MediaBtn.NEXT);
        nowPlay = new MediaBtn(this, MediaBtn.PAUSE);
        MediaBtn vol = new MediaBtn(this, MediaBtn.VOL);
        prev.setOnClickListener(v -> skipSong(-1));
        next.setOnClickListener(v -> skipSong(1));
        nowPlay.setOnClickListener(v -> {
            NotifService.control('P');
            Glass.pop(v);
        });
        vol.setOnClickListener(v -> {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME,
                    android.media.AudioManager.FLAG_SHOW_UI);
        });
        bar.addView(prev, new LinearLayout.LayoutParams(dp(40), dp(44)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(46), dp(46));
        pp.leftMargin = dp(2);
        pp.rightMargin = dp(2);
        bar.addView(nowPlay, pp);
        bar.addView(next, new LinearLayout.LayoutParams(dp(40), dp(44)));
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.VERTICAL);
        prog.setPadding(dp(10), 0, dp(8), 0);
        LinearLayout times = new LinearLayout(this);
        nowAt = text("0:00", 11, 0xDDFFFFFF);
        nowLeft = text("", 11, 0xDDFFFFFF);
        times.addView(nowAt, weight());
        times.addView(nowLeft);
        prog.addView(times);
        LinearLayout line = new LinearLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x40FFFFFF);
        track.setCornerRadius(dp(2));
        line.setBackground(track);
        nowFill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(Color.WHITE);
        fillBg.setCornerRadius(dp(2));
        nowFill.setBackground(fillBg);
        nowRest = new View(this);
        line.addView(nowFill, new LinearLayout.LayoutParams(0, -1, 0f));
        line.addView(nowRest, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(-1, dp(4));
        lp4.topMargin = dp(5);
        prog.addView(line, lp4);
        bar.addView(prog, weight());
        bar.addView(vol, new LinearLayout.LayoutParams(dp(36), dp(40)));
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(-1, -2);
        bpl.topMargin = dp(14);
        nowFull.addView(bar, bpl);
        body.addView(nowFull);

    }

    /** The floating player and which app the watch's buttons reach: a card on the Watch tab. */
    private Section buildMusicCard(LinearLayout col) {
        final Section sec = section(col, "MUSIC", false);
        final LinearLayout music = sec.body;
        final Runnable sum = () -> sec.summary.setText("Floating " + pillLabel().toLowerCase(Locale.US) + " · " + musicAppLabel());
        nowPillChip = chip(pillLabel());
        nowPillChip.setOnClickListener(v -> {
            boolean on = !prefs().getBoolean("music_pill", false);
            if (on && !Settings.canDrawOverlays(this)) {
                prefs().edit().putBoolean("music_pill", true).apply();
                Toast.makeText(this, "Allow Pulse to display over other apps", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException ignored) { }
            } else {
                prefs().edit().putBoolean("music_pill", on).apply();
            }
            swapText(nowPillChip, pillLabel());
            NotifService.pillChanged();
            sum.run();
        });
        music.addView(lineRow("Floating player", "Over other apps", nowPillChip));
        music.addView(hairline());
        nowAppChip = chip(musicAppLabel());
        nowAppChip.setSingleLine(true);
        nowAppChip.setMaxWidth(dp(130));
        nowAppChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowAppChip.setOnClickListener(v -> {
            final List<String[]> apps = NotifService.musicApps(this);
            String cur = NotifService.musicApp(this);
            String[] names = new String[apps.size() + 1];
            names[0] = "Whichever is playing";
            int at = 0;
            for (int i = 0; i < apps.size(); i++) {
                names[i + 1] = apps.get(i)[1];
                if (apps.get(i)[0].equals(cur)) at = i + 1;
            }
            morph(nowAppChip, names, at, n -> {
                NotifService.musicApp(this, n == 0 ? "" : apps.get(n - 1)[0]);
                swapText(nowAppChip, musicAppLabel());
                sum.run();
            });
        });
        music.addView(lineRow("Watch controls", "The app the buttons reach", nowAppChip));
        sum.run();
        return sec;
    }

    private String musicAppLabel() {
        String p = NotifService.musicApp(this);
        return p.isEmpty() ? "Auto" : appLabel(p);
    }

    /** Puts a cover where its pos says: smaller, turned and dimmer the further from the middle. */
    private void placeCover(Cover c) {
        float p = c.pos + coverDrag, a = Math.abs(p), s = Math.signum(p);
        float near = Math.min(a, 1f), far = Math.min(1f, Math.max(0f, a - 1f));
        c.v.setTranslationX(s * (near * dp(106) + far * dp(64)));
        float sc = 1f - near * 0.34f - far * 0.12f;
        c.v.setScaleX(sc);
        c.v.setScaleY(sc);
        c.v.setRotationY(-s * near * 24f);
        c.v.setAlpha(Math.max(0f, (1f - near * 0.3f - far * 0.7f) * c.here));
        c.v.setTranslationZ(dp(10) * (1f - near));
        c.v.setVisibility(c.here <= 0.01f || a >= 1.98f ? View.INVISIBLE : View.VISIBLE);
    }

    /** Where each cover belongs now. */
    private float coverHome(int i) {
        return i == 3 ? spareAt : i - 1;
    }

    /** Glides every cover from where it is to where it belongs (and fades songs in / out). */
    private void moveCovers() {
        if (coverAnim != null) coverAnim.cancel();
        for (int i = 0; i < 4; i++) {
            Cover c = covers[i];
            c.from = c.pos;
            c.to = coverHome(i);
            c.hereFrom = c.here;
            c.hereTo = c.key.isEmpty() || i == 3 ? 0f : 1f;
        }
        coverAnim = ValueAnimator.ofFloat(0f, 1f);
        coverAnim.setDuration(560);
        coverAnim.setInterpolator(SMOOTH);
        coverAnim.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            for (Cover c : covers) {
                c.pos = c.from + (c.to - c.from) * f;
                c.here = c.hereFrom + (c.hereTo - c.hereFrom) * f;
                placeCover(c);
            }
        });
        coverAnim.start();
    }

    /** Jumps any glide to its end (before a swipe takes over). */
    private void settleCovers() {
        if (coverAnim != null) coverAnim.cancel();
    }

    /** No glide: everything straight to its place. */
    private void snapCovers() {
        if (coverAnim != null) coverAnim.cancel();
        for (int i = 0; i < 4; i++) {
            Cover c = covers[i];
            c.pos = c.to = coverHome(i);
            c.here = c.hereTo = c.key.isEmpty() || i == 3 ? 0f : 1f;
            placeCover(c);
        }
    }

    /**
     * The whole row moves one step (dir 1 = to the next song): the middle cover becomes the one
     * before, the next one slides into the middle, and the spare comes in on the far side.
     */
    private void shiftCovers(int dir) {
        Cover p = covers[0], c = covers[1], n = covers[2], sp = covers[3];
        Cover in = sp;
        in.pos = 2 * dir;
        in.here = 0f;
        in.key = "";
        if (dir > 0) { covers[0] = c; covers[1] = n; covers[2] = in; covers[3] = p; }
        else { covers[0] = in; covers[1] = p; covers[2] = c; covers[3] = n; }
        spareAt = -2 * dir;
        moveCovers();
    }

    /** A gentle push the way of the tap, when the next song isn't known yet. */
    private void nudgeCovers(final int dir) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(460);
        a.addUpdateListener(an -> {
            coverDrag = -0.18f * dir * (float) Math.sin(Math.PI * (float) an.getAnimatedValue());
            for (Cover c : covers) placeCover(c);
        });
        a.start();
    }

    /** Previous / next from the buttons or a swipe: move the covers now, the song follows. */
    private void skipSong(int dir) {
        nowDir = dir;
        NotifService.control(dir > 0 ? 'N' : 'B');
        Cover coming = covers[dir > 0 ? 2 : 0];
        if (!coming.key.isEmpty()) {                 // we know the song: it slides in right away
            shiftCovers(dir);
            nowAhead = dir;
            nowAheadAt = android.os.SystemClock.elapsedRealtime();
            final long at = nowAheadAt;
            ui.postDelayed(() -> {                   // the app didn't change song: put the covers back
                if (nowAhead != 0 && nowAheadAt == at) {
                    nowAhead = 0;
                    shiftCovers(-dir);
                    fillCovers(NotifService.now);
                }
            }, 4000);
        } else {
            moveCovers();
            nudgeCovers(dir);
        }
    }

    /** Puts a song on a cover; a different song on a showing cover swaps with a quick fade. */
    private void bindCover(final Cover c, final String title, final String artist,
                           final android.graphics.Bitmap art, final String pkg) {
        String key = title.isEmpty() ? "" : title + "|" + artist;
        if (key.equals(c.key)) {
            if (art != null && art != c.shown) {     // the picture came late
                c.shown = art;
                c.art.setAlpha(0.4f);
                setCover(c.art, art, pkg);
                c.art.animate().alpha(1f).setDuration(260).start();
            }
            return;
        }
        boolean swap = !c.key.isEmpty() && !key.isEmpty() && c.here > 0.5f;
        c.key = key;
        final Runnable set = () -> {
            c.title.setText(title);
            c.artist.setText(artist.isEmpty() ? appLabel(pkg) : artist);
            setCover(c.art, art, pkg);
            c.shown = art;
        };
        if (!swap) { set.run(); return; }
        for (View v : new View[]{c.art, c.title, c.artist}) v.animate().cancel();
        c.art.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            set.run();
            for (View v : new View[]{c.art, c.title, c.artist}) v.animate().alpha(1f).setDuration(220).start();
        }).start();
        c.title.animate().alpha(0f).setDuration(120).start();
        c.artist.animate().alpha(0f).setDuration(120).start();
    }

    /** The middle cover and the ones beside it, from the song now. */
    private void fillCovers(NotifService.Now n) {
        bindCover(covers[1], n.title, n.artist, n.art, n.pkg);
        bindCover(covers[0], n.prevTitle, n.prevArtist, n.prevArt, n.pkg);
        bindCover(covers[2], n.nextTitle, n.nextArtist, n.nextArt, n.pkg);
        boolean move = false;                          // only glide when something has to change
        for (int i = 0; i < 4; i++) {
            Cover c = covers[i];
            float here = c.key.isEmpty() || i == 3 ? 0f : 1f;
            if (c.hereTo != here || c.to != coverHome(i)) move = true;
            if ((coverAnim == null || !coverAnim.isRunning()) && (c.here != here || c.pos != coverHome(i))) move = true;
        }
        if (move) moveCovers();
    }

    /** The song settled on something new: slide the row the right way (unless a tap already did). */
    private void songChanged() {
        NotifService.Now n = NotifService.now;
        if (!n.exists || n.title.isEmpty()) return;
        String key = n.title + "|" + n.artist;
        if (key.equals(nowKey)) return;
        nowKey = key;
        boolean ahead = nowAhead != 0 && android.os.SystemClock.elapsedRealtime() - nowAheadAt < 6000;
        nowAhead = 0;
        if (nowFull.getVisibility() != View.VISIBLE) {
            fillCovers(n);
            snapCovers();
        } else if (ahead) {
            fillCovers(n);                             // already moved: just fill in what's new
        } else {
            int dir = key.equals(covers[2].key) ? 1 : key.equals(covers[0].key) ? -1 : nowDir;
            shiftCovers(dir);
            fillCovers(n);
        }
        fillBack(n);
        ui.postDelayed(() -> nowDir = 1, 900);         // later changes move forward
    }

    /** The blurred cover behind: the new one fades in on top of the old, then the old goes. */
    private void crossfadeBack(android.graphics.Bitmap art) {
        final ImageView front = nowBack.getAlpha() >= nowBack2.getAlpha() ? nowBack : nowBack2;
        ImageView back = front == nowBack ? nowBack2 : nowBack;
        back.animate().cancel();
        front.animate().cancel();
        front.setAlpha(1f);
        blurInto(back, art);
        nowFrame.removeView(back);
        nowFrame.addView(back, nowFrame.indexOfChild(front) + 1);
        back.setAlpha(0f);
        nowBackTop = back;
        back.animate().alpha(1f).setDuration(650).setInterpolator(SMOOTH)
                .withEndAction(() -> front.setAlpha(0f)).start();
    }

    private String pillLabel() {
        boolean on = prefs().getBoolean("music_pill", false);
        return !on ? "Off" : Settings.canDrawOverlays(this) ? "On" : "Allow";
    }

    private void setCover(ImageView v, android.graphics.Bitmap b, String pkg) {
        if (b != null) { v.setImageBitmap(b); return; }
        try { v.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); }
        catch (Exception e) { v.setImageDrawable(null); }
    }

    /** Song changed or play / pause: fill the player, grow or shrink, move to the top or back. */
    private void showNow() {
        if (nowCard == null) return;
        NotifService.Now n = NotifService.now;
        boolean playing = n.exists && n.playing;
        if (nowPillChip != null) nowPillChip.setText(pillLabel());
        if (n.exists) {
            nowApp.setText(appLabel(n.pkg));
            nowPlay.setKind(n.playing ? MediaBtn.PAUSE : MediaBtn.PLAY);
            String key = n.title + "|" + n.artist;
            if (n.title.isEmpty() || key.equals(nowKey)) {
                if (!n.title.isEmpty()) { fillCovers(n); fillBack(n); }   // same song: art may come late
            } else if (nowKey.isEmpty() || nowFull.getVisibility() != View.VISIBLE) {
                songChanged();                         // first song, or the player is small: no slide
            } else if (!key.equals(settleKey)) {       // wait a moment: apps send a song in pieces
                settleKey = key;
                ui.removeCallbacks(nowSettle);
                ui.postDelayed(nowSettle, nowAhead != 0 ? 120 : 220);
            }
        }
        // the player shows while music plays, and for 30 s after it pauses; then it shrinks back
        if (playing) nowPausedAt = 0;
        else if (nowPausedAt == 0) nowPausedAt = android.os.SystemClock.elapsedRealtime();
        boolean recent = nowPausedAt > 0 && android.os.SystemClock.elapsedRealtime() - nowPausedAt < 30_000;
        boolean full = n.exists && (playing || (nowTop && recent));
        if (full != (nowFull.getVisibility() == View.VISIBLE)) {
            nowFull.setVisibility(full ? View.VISIBLE : View.GONE);
            nowSmall.setVisibility(full ? View.GONE : View.VISIBLE);
            if (full) snapCovers();
            ImageView shown = nowBackTop != null ? nowBackTop : nowBack;
            shown.animate().alpha(full ? 1f : 0f).setDuration(400).start();
            nowShade.animate().alpha(full ? 1f : 0f).setDuration(400).start();
        }
        boolean wantTop = full;
        if (wantTop != nowTop) moveNow(wantTop);
        nowTick();
    }



    /** The background for a song: its blurred cover and a shade in the cover's own colour. */
    private void fillBack(NotifService.Now n) {
        if (n.art == nowArtShown) return;
        if (n.art == null && nowArtShown != null) return;        // the picture is still on its way
        nowArtShown = n.art;
        android.graphics.drawable.Drawable shade;
        if (n.art != null) {
            int avg = android.graphics.Bitmap.createScaledBitmap(n.art, 1, 1, true).getPixel(0, 0);
            shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{(avg & 0x00FFFFFF) | 0x33000000, (darker(avg) & 0x00FFFFFF) | 0x80000000,
                            (darker(avg) & 0x00FFFFFF) | 0xB3000000});
        } else {
            shade = Glass.accent(this, 0);
        }
        android.graphics.drawable.Drawable old = nowShade.getBackground();
        if (old instanceof android.graphics.drawable.TransitionDrawable)
            old = ((android.graphics.drawable.TransitionDrawable) old).getDrawable(1);
        if (old != null && nowFull.getVisibility() == View.VISIBLE) {
            android.graphics.drawable.TransitionDrawable t =
                    new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{old, shade});
            t.setCrossFadeEnabled(true);
            nowShade.setBackground(t);
            t.startTransition(650);
        } else {
            nowShade.setBackground(shade);
        }
        if (nowFull.getVisibility() == View.VISIBLE) crossfadeBack(n.art);
        else {
            blurInto(nowBack, n.art);
            nowBackTop = nowBack;
            nowBack.setAlpha(nowShade.getAlpha());
            nowBack2.setAlpha(0f);
        }
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) / 3, Color.green(c) / 3, Color.blue(c) / 3);
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Lifts the card to the top of Home (just under the title) or puts it back in its place. */
    private void moveNow(final boolean top) {
        nowTop = top;
        final LinearLayout home = pages[HOME];
        nowCard.animate().cancel();
        nowCard.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(160).withEndAction(() -> {
            home.removeView(nowCard);
            int at = top ? Math.min(3, home.getChildCount()) : Math.min(nowHomeIndex, home.getChildCount());
            home.addView(nowCard, at);
            nowCard.setTranslationY(top ? -dp(24) : dp(24));
            nowCard.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(620)
                    .setInterpolator(Glass.BOUNCY).start();
        }).start();
    }

    /** Every second: the progress line and the times. */
    private void nowTick() {
        if (nowFull == null || nowFull.getVisibility() != View.VISIBLE) return;
        NotifService.Now n = NotifService.now;
        if (!n.playing && nowPausedAt > 0 && android.os.SystemClock.elapsedRealtime() - nowPausedAt >= 30_000) {
            showNow();                                    // paused a while: shrink back
            return;
        }
        long pos = n.position(), dur = n.dur;
        float f = dur > 0 ? Math.min(1f, pos / (float) dur) : 0f;
        nowFill.setLayoutParams(new LinearLayout.LayoutParams(0, -1, Math.max(0.001f, f)));
        nowRest.setLayoutParams(new LinearLayout.LayoutParams(0, -1, Math.max(0f, 1f - f)));
        setIfNew(nowAt, clock(pos));
        setIfNew(nowLeft, dur > 0 ? "-" + clock(dur - pos) : "");
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /** Previous / play / pause / next, drawn: white on the blue card. */
    private static final class MediaBtn extends View {
        static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3, VOL = 4;
        private int kind;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        MediaBtn(android.content.Context c, int kind) {
            super(c);
            this.kind = kind;
            Glass.spring(this);
        }

        void setKind(int k) {
            if (k != kind) { kind = k; invalidate(); }
        }

        @Override
        protected void onDraw(android.graphics.Canvas k) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            boolean big = kind == PLAY || kind == PAUSE;
            if (big) {
                p.setColor(Color.WHITE);
                k.drawCircle(cx, cy, Math.min(w, h) / 2f, p);
            }
            p.setColor(big ? 0xFF111111 : Color.WHITE);
            float g = Math.min(w, h) * (big ? 0.2f : 0.22f);
            android.graphics.Path t = new android.graphics.Path();
            if (kind == VOL) {                                         // a speaker with one wave
                t.moveTo(cx - g * 1.1f, cy - g * 0.45f);
                t.lineTo(cx - g * 0.55f, cy - g * 0.45f);
                t.lineTo(cx + g * 0.15f, cy - g);
                t.lineTo(cx + g * 0.15f, cy + g);
                t.lineTo(cx - g * 0.55f, cy + g * 0.45f);
                t.lineTo(cx - g * 1.1f, cy + g * 0.45f);
                t.close();
                k.drawPath(t, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(g * 0.22f);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                k.drawArc(new android.graphics.RectF(cx - g * 0.2f, cy - g * 0.75f, cx + g * 1.1f, cy + g * 0.75f), -55, 110, false, p);
                p.setStyle(android.graphics.Paint.Style.FILL);
            } else if (kind == PAUSE) {
                k.drawRoundRect(new android.graphics.RectF(cx - g * 0.8f, cy - g, cx - g * 0.25f, cy + g), g * 0.15f, g * 0.15f, p);
                k.drawRoundRect(new android.graphics.RectF(cx + g * 0.25f, cy - g, cx + g * 0.8f, cy + g), g * 0.15f, g * 0.15f, p);
            } else if (kind == PLAY) {
                t.moveTo(cx - g * 0.6f, cy - g);
                t.lineTo(cx + g * 1.0f, cy);
                t.lineTo(cx - g * 0.6f, cy + g);
                t.close();
                k.drawPath(t, p);
            } else {
                float dir = kind == NEXT ? 1 : -1;
                for (int i = 0; i < 2; i++) {
                    float o = (i == 0 ? -0.9f : 0.1f) * g * dir;
                    t.reset();
                    t.moveTo(cx + o, cy - g * 0.8f);
                    t.lineTo(cx + o + g * 0.9f * dir, cy);
                    t.lineTo(cx + o, cy + g * 0.8f);
                    t.close();
                    k.drawPath(t, p);
                }
                float bx = cx + g * 1.05f * dir;
                k.drawRect(Math.min(bx, bx + g * 0.18f * dir), cy - g * 0.8f, Math.max(bx, bx + g * 0.18f * dir), cy + g * 0.8f, p);
            }
        }
    }

    // ---------- the Faces tab ----------

    private void buildFacesPage(LinearLayout page) {
        // on your watch: the three slots, shown like little watch screens
        LinearLayout watch = card(page, 26);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("ON YOUR WATCH"), weight());
        faceLink = text("", 13, Glass.MUTED);
        faceLink.setTypeface(MEDIUM);
        head.addView(faceLink);
        watch.addView(head);
        faceTiles = new LinearLayout(this);
        faceTiles.setPadding(0, dp(14), 0, 0);
        watch.addView(faceTiles, new LinearLayout.LayoutParams(-1, -2));
        faceState = text(prefs().getString("install_last", "Nothing sent yet."), 13, Glass.SOFT);
        faceState.setPadding(dp(2), dp(14), 0, dp(8));
        watch.addView(faceState);
        faceBar = faceBar();
        faceBar.setVisibility(View.GONE);
        watch.addView(faceBar, new LinearLayout.LayoutParams(-1, dp(6)));
        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(0, dp(12), 0, 0);
        acts.addView(pill("Read slots", true, v -> {
            if (!NotifService.slots()) faceState.setText("Pulse isn't running: allow Notification access in Setup");
        }), weight());
        acts.addView(gap());
        acts.addView(pill("Faces folder", false, v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try { startActivityForResult(pick, REQ_FACES); } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No file app to pick a folder with", Toast.LENGTH_SHORT).show();
            }
        }), weight());
        watch.addView(acts);

        // your faces: a grid of cards
        LinearLayout mine = card(page, 26);
        LinearLayout head2 = new LinearLayout(this);
        head2.setGravity(Gravity.CENTER_VERTICAL);
        head2.addView(label("YOUR FACES"), weight());
        faceCount = text("", 13, Glass.MUTED);
        head2.addView(faceCount);
        mine.addView(head2);
        facesDir = text(facesDirName(), 13, Glass.MUTED);
        facesDir.setPadding(0, dp(4), 0, dp(8));
        mine.addView(facesDir);
        faceList = new LinearLayout(this);
        faceList.setOrientation(LinearLayout.VERTICAL);
        mine.addView(faceList);

        // faces by profile: three little cards side by side
        LinearLayout byProf = card(page, 26);
        byProf.addView(label("BY PROFILE"));
        TextView bpNote = text("When a profile starts (by tap or at its hour), Pulse puts these faces on.", 13, Glass.MUTED);
        bpNote.setPadding(0, dp(4), 0, dp(10));
        byProf.addView(bpNote);
        profFaces = new LinearLayout(this);
        byProf.addView(profFaces, new LinearLayout.LayoutParams(-1, -2));

        // backups: a list with little screens
        LinearLayout backs = card(page, 26);
        LinearLayout head3 = new LinearLayout(this);
        head3.setGravity(Gravity.CENTER_VERTICAL);
        head3.addView(label("BACKUPS"), weight());
        head3.addView(text("8 newest of each", 13, Glass.MUTED));
        backs.addView(head3);
        backupList = new LinearLayout(this);
        backupList.setOrientation(LinearLayout.VERTICAL);
        backs.addView(backupList);

        // the watch menu
        LinearLayout opts = card(page, 26);
        opts.addView(label("ON THE WATCH"));
        final TextView first = chip("Forge " + prefs().getString("faces_slot", "A"));
        first.setOnClickListener(v -> {
            String next = "A".equals(prefs().getString("faces_slot", "A")) ? "B" : "A";
            prefs().edit().putString("faces_slot", next).apply();
            swapText(first, "Forge " + next);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        opts.addView(lineRow("Offered first", "Hold A, FACES, pick a face, then A or B", first));
        opts.addView(hairline());
        pulseChip = chip(pulseLabel());
        pulseChip.setOnClickListener(v -> pulseSheet());
        opts.addView(lineRow("Pulse face", "In Forge C. Update it, or put it back if it's gone", pulseChip));
        opts.addView(hairline());
        TextView how = text("A or b in the watch's small digits means that face is already there. "
                + "Forge C holds Pulse: it's only written to update Pulse, after you say yes.", 13, Glass.MUTED);
        how.setLineSpacing(dp(2), 1f);
        how.setPadding(0, dp(12), 0, 0);
        opts.addView(how);

        refreshFaces();
    }

    private void faceLinkLine() {
        boolean linked = WatchLink.get(this).isReady();
        faceLink.setText(linked ? "\u25CF  Linked" : "\u25CF  Not linked");
        faceLink.setTextColor(linked ? Glass.GOOD : Glass.MUTED);
    }

    /** A thin progress bar: a filled part and the rest, by weight. */
    private View faceBar() {
        LinearLayout bar = new LinearLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(Glass.LINE);
        track.setCornerRadius(dp(3));
        bar.setBackground(track);
        faceBarFill = new LinearLayout(this);
        faceBarFill.setBackground(Glass.accent(this, 3));
        faceBarRest = new View(this);
        bar.addView(faceBarFill, new LinearLayout.LayoutParams(0, -1, 0f));
        bar.addView(faceBarRest, new LinearLayout.LayoutParams(0, -1, 1f));
        return bar;
    }

    private void faceProgress(int pct) {
        if (faceBar == null) return;
        if (pct < 0) { faceBar.setVisibility(View.GONE); return; }
        faceBar.setVisibility(View.VISIBLE);
        faceBarFill.setLayoutParams(new LinearLayout.LayoutParams(0, -1, Math.max(0.02f, pct / 100f)));
        faceBarRest.setLayoutParams(new LinearLayout.LayoutParams(0, -1, Math.max(0f, 1f - pct / 100f)));
    }

    /** A dark rounded "watch screen" with a face's name in the watch's blocky style. */
    private FrameLayout lcd(String name, int sp, int heightDp, boolean dim) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                dim ? new int[]{0xFF31465F, 0xFF3D5674} : new int[]{0xFF0E2B4D, 0xFF1C4F86});
        g.setCornerRadius(dp(14));
        f.setBackground(g);
        TextView t = text(name.isEmpty() ? "- - -" : name, sp, dim ? 0xFF9FB3CA : 0xFFCDE7FF);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(8), 0, dp(8), 0);
        f.addView(t, new FrameLayout.LayoutParams(-1, dp(heightDp), Gravity.CENTER));
        return f;
    }

    /** A small round letter badge (A / B / C) for a screen's corner. */
    private TextView badge(String s, boolean filled) {
        TextView b = text(s, 11, filled ? Color.WHITE : Glass.ACCENT);
        b.setTypeface(MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackground(filled ? Glass.accent(this, 11) : Glass.panel(this, 11));
        return b;
    }

    /** Re-draws the slot tiles, the link line, the grid, the profiles and the backups. */
    private void refreshFaces() {
        if (faceTiles == null) return;
        faceLinkLine();
        String[] k = FaceInstall.known(this), v = FaceInstall.versions(this);
        faceTiles.removeAllViews();
        for (int i = 0; i < 3; i++) {
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            FrameLayout screen = lcd(k[i].isEmpty() ? "?" : k[i], 13, 58, i == 2);
            TextView b = badge(String.valueOf((char) ('A' + i)), i != 2);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START);
            bl.leftMargin = dp(6);
            bl.topMargin = dp(6);
            screen.addView(b, bl);
            t.addView(screen, new LinearLayout.LayoutParams(-1, dp(72)));
            String sub = i == 2 ? "Pulse \u00B7 locked" : k[i].isEmpty() ? "Read slots" : v[i].isEmpty() ? "Forge " + (char) ('A' + i) : "v" + v[i];
            TextView ver = text(sub, 12, Glass.MUTED);
            ver.setGravity(Gravity.CENTER);
            ver.setSingleLine(true);
            ver.setPadding(0, dp(6), 0, 0);
            t.addView(ver, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            faceTiles.addView(t, lp);
        }
        buildFaceList();
        buildProfFaces();
        buildBackups();
    }

    /** Your faces as a two-column grid of cards: a little screen, name, size, and To A / To B. */
    private void buildFaceList() {
        if (faceList == null) return;
        faceList.removeAllViews();
        List<String[]> files = FaceInstall.listInfo(this, 60);
        if (files == null || files.isEmpty()) {
            faceCount.setText("");
            TextView empty = text(files == null ? "Tap Faces folder above and pick Download/Faces."
                    : "No .js files in the Faces folder yet.", 14, Glass.SOFT);
            empty.setPadding(0, dp(6), 0, dp(4));
            faceList.addView(empty);
            return;
        }
        faceCount.setText(files.size() == 1 ? "1 face" : files.size() + " faces");
        String[] k = FaceInstall.known(this);
        LinearLayout row = null;
        for (int i = 0; i < files.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rl.topMargin = dp(10);
                faceList.addView(row, rl);
            }
            final String f = files.get(i)[0];
            long size = 0;
            try { size = Long.parseLong(files.get(i)[1]); } catch (NumberFormatException ignored) { }
            final boolean tooBig = size > FaceInstall.MAX;
            boolean inA = f.equalsIgnoreCase(k[0]), inB = f.equalsIgnoreCase(k[1]);
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(10), dp(10), dp(10), dp(12));
            c.setBackground(Glass.panel(this, 20));
            FrameLayout screen = lcd(f, 17, 64, false);
            if (inA || inB) {
                TextView on = badge(inA && inB ? "AB" : inA ? "A" : "B", true);
                FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(dp(inA && inB ? 30 : 22), dp(22), Gravity.TOP | Gravity.END);
                ol.rightMargin = dp(6);
                ol.topMargin = dp(6);
                screen.addView(on, ol);
            }
            c.addView(screen, new LinearLayout.LayoutParams(-1, dp(74)));
            TextView n = text(f, 15, Glass.TEXT);
            n.setTypeface(MEDIUM);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(dp(2), dp(10), 0, 0);
            c.addView(n);
            String kb = size > 0 ? String.format(Locale.US, "%.1f of 8.2 KB", size / 1000f) : f + ".js";
            TextView sub = text(tooBig ? "Too big \u00B7 " + kb : (inA || inB) ? kb + " \u00B7 on watch" : kb, 11,
                    tooBig ? Glass.BAD : (inA || inB) ? Glass.GOOD : Glass.MUTED);
            sub.setSingleLine(true);
            sub.setPadding(dp(2), dp(1), 0, dp(6));
            c.addView(sub);
            c.addView(sizeBar(size), new LinearLayout.LayoutParams(-1, dp(3)));
            LinearLayout btns = new LinearLayout(this);
            btns.setPadding(0, dp(10), 0, 0);
            for (int sl = 0; sl < 2; sl++) {
                final int slot = sl;
                boolean here = sl == 0 ? inA : inB;
                TextView b = text(here ? "On " + (sl == 0 ? "A" : "B") : "To " + (sl == 0 ? "A" : "B"), 13,
                        here ? Color.WHITE : Glass.ACCENT);
                b.setTypeface(MEDIUM);
                b.setGravity(Gravity.CENTER);
                b.setPadding(0, dp(8), 0, dp(8));
                b.setBackground(here ? Glass.accent(this, 14) : Glass.panel(this, 14));
                Glass.spring(b);
                if (tooBig) { b.setEnabled(false); b.setAlpha(0.35f); }
                else b.setOnClickListener(v -> installSheet(f, slot));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (sl == 1) bp.leftMargin = dp(6);
                btns.addView(b, bp);
            }
            c.addView(btns);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % 2 == 1) cl.leftMargin = dp(10);
            row.addView(c, cl);
        }
        if (files.size() % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    /** How full a face is: a thin bar of bytes out of 8158; amber past 95 %, red when too big. */
    private View sizeBar(long size) {
        LinearLayout bar = new LinearLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(Glass.LINE);
        track.setCornerRadius(dp(2));
        bar.setBackground(track);
        float f = Math.min(1f, size / (float) FaceInstall.MAX);
        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(size > FaceInstall.MAX ? Glass.BAD : f > 0.95f ? Glass.WARN : Glass.ACCENT);
        fg.setCornerRadius(dp(2));
        fill.setBackground(fg);
        bar.addView(fill, new LinearLayout.LayoutParams(0, -1, Math.max(0.001f, f)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, Math.max(0f, 1f - f)));
        return bar;
    }

    /**
     * A sheet that slides up from the bottom (tap outside or back to close). Returns its close action.
     */
    private Runnable sheet(String title, String sub, View content) {
        if (rootView == null) return () -> { };
        if (menuClose != null) menuClose.run();
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x00000000);
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(10), dp(22), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFAFFFFFF);
        bg.setCornerRadius(dp(30));
        panel.setBackground(bg);
        panel.setElevation(dp(16));
        panel.setClickable(true);
        View handle = new View(this);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0x332C5A8C);
        hg.setCornerRadius(dp(3));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(40), dp(5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = dp(14);
        panel.addView(handle, hl);
        TextView t = text(title, 21, Glass.TEXT);
        t.setTypeface(MEDIUM);
        panel.addView(t);
        if (!sub.isEmpty()) {
            TextView s2 = text(sub, 14, Glass.SOFT);
            s2.setPadding(0, dp(4), 0, 0);
            s2.setLineSpacing(dp(2), 1f);
            panel.addView(s2);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(16);
        panel.addView(content, cl);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pl.leftMargin = dp(10);
        pl.rightMargin = dp(10);
        pl.bottomMargin = dp(12);
        scrim.addView(panel, pl);
        rootView.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(0, 0x66);
        fade.setDuration(260);
        fade.addUpdateListener(x -> scrim.setBackgroundColor(((int) x.getAnimatedValue()) << 24));
        fade.start();
        panel.setTranslationY(dp(420));
        panel.animate().translationY(0).setDuration(620).setInterpolator(Glass.BOUNCY).start();
        final Runnable close = new Runnable() {
            boolean done;
            @Override public void run() {
                if (done) return;
                done = true;
                if (menuClose == this) menuClose = null;
                android.animation.ValueAnimator out = android.animation.ValueAnimator.ofInt(0x66, 0);
                out.setDuration(220);
                out.addUpdateListener(x -> scrim.setBackgroundColor(((int) x.getAnimatedValue()) << 24));
                out.start();
                panel.animate().translationY(panel.getHeight() + dp(40)).setDuration(240)
                        .withEndAction(() -> rootView.removeView(scrim)).start();
            }
        };
        scrim.setOnClickListener(v -> close.run());
        menuClose = close;
        return close;
    }

    // ---------- the Tap tab: the watch's NFC tag ----------

    private void buildTapPage(LinearLayout col) {
        // the hero: the watch sending waves, what the tag does, write and try
        LinearLayout hero = card(col, 28);
        hero.setBackground(Glass.accent(this, 28));
        hero.setPadding(dp(20), dp(18), dp(20), dp(20));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new TapArt(this, true), new LinearLayout.LayoutParams(dp(92), dp(76)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        TextView h1 = text("Tap your watch", 22, Color.WHITE);
        h1.setTypeface(MEDIUM);
        words.addView(h1);
        TextView h2 = text("on the back of your phone", 14, 0xD9FFFFFF);
        words.addView(h2);
        top.addView(words, weight());
        hero.addView(top);
        LinearLayout facts = new LinearLayout(this);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setPadding(dp(14), dp(10), dp(14), dp(10));
        facts.setBackground(glassBg(18, false));
        tapNfc = text("", 14, Color.WHITE);
        tapTag = text("", 14, Color.WHITE);
        tapLast = text("", 14, Color.WHITE);
        for (TextView t : new TextView[]{tapNfc, tapTag, tapLast}) {
            t.setPadding(0, dp(3), 0, dp(3));
            facts.addView(t);
        }
        tapNfc.setOnClickListener(v -> {
            android.nfc.NfcAdapter nfc = android.nfc.NfcAdapter.getDefaultAdapter(this);
            if (nfc != null && !nfc.isEnabled()) {
                try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); } catch (ActivityNotFoundException ignored) { }
            }
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(14);
        hero.addView(facts, fl);
        LinearLayout btns = new LinearLayout(this);
        TextView write = text("Write tag", 15, Glass.ACCENT);
        write.setTypeface(MEDIUM);
        write.setGravity(Gravity.CENTER);
        GradientDrawable wb = new GradientDrawable();
        wb.setColor(Color.WHITE);
        wb.setCornerRadius(dp(22));
        write.setBackground(wb);
        Glass.spring(write);
        write.setOnClickListener(v -> writeTagSheet());
        TextView tryIt = text("Try it", 15, Color.WHITE);
        tryIt.setTypeface(MEDIUM);
        tryIt.setGravity(Gravity.CENTER);
        tryIt.setBackground(glassBg(22, true));
        Glass.spring(tryIt);
        tryIt.setOnClickListener(v -> {
            String r = NfcTap.run(this);
            NfcTap.buzz(this);
            Toast.makeText(this, r, Toast.LENGTH_SHORT).show();
            refreshTap();
        });
        btns.addView(write, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        tl.leftMargin = dp(10);
        btns.addView(tryIt, tl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(14);
        hero.addView(btns, bl);

        // what a tap does: pick one
        LinearLayout pick = card(col, 24);
        pick.addView(label("WHEN YOU TAP"));
        tapChecks.clear();
        for (int i = 0; i < NfcTap.NAMES.length; i++) {
            final int n = i;
            if (i > 0) pick.addView(hairline());
            View check = new TapCheck(this);
            tapChecks.add(check);
            LinearLayout row = lineRow(NfcTap.NAMES[i], NfcTap.HINTS[i], check);
            check.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
            row.setOnClickListener(v -> {
                NfcTap.action(this, n);
                Glass.pop(check);
                refreshTap();
            });
            pick.addView(row);
            if (i == NfcTap.MACRO) {
                tapMacroChip = chip("");
                tapMacroChip.setSingleLine(true);
                tapMacroChip.setMaxWidth(dp(150));
                tapMacroChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tapMacroChip.setOnClickListener(v -> {
                    final List<Macros.Slot> named = new ArrayList<>();
                    for (Macros.Slot s : Macros.load(this)) if (s.name != null && !s.name.trim().isEmpty()) named.add(s);
                    if (named.isEmpty()) {
                        Toast.makeText(this, "Set up a macro on the Macros tab first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] names = new String[named.size()];
                    int at = 0;
                    Macros.Slot cur = NfcTap.macroSlot(this);
                    for (int k = 0; k < named.size(); k++) {
                        names[k] = named.get(k).name;
                        if (cur != null && named.get(k).number == cur.number) at = k;
                    }
                    morph(tapMacroChip, names, at, k -> {
                        NfcTap.macro(this, named.get(k).number);
                        swapText(tapMacroChip, named.get(k).name);
                    });
                });
                tapMacroRow = lineRow("Which macro", "Runs in MacroDroid, like from the watch", tapMacroChip);
                tapMacroRow.setPadding(dp(14), dp(4), 0, dp(10));
                pick.addView(tapMacroRow);
            }
        }

        // smart tap, spelled out
        tapSmart = card(col, 24);
        tapSmart.addView(label("SMART TAP"));
        tapSmart.addView(note("It checks these in order and does the first that fits."));
        String[][] steps = {
                {"Phone ringing", "Mutes the ringer"},
                {"Watch not connected", "Connects it"},
                {"Music playing", "Next song"},
                {"Anything else", "Opens the menu on the watch"}};
        for (int i = 0; i < steps.length; i++) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(8), 0, dp(8));
            TextView num = text(String.valueOf(i + 1), 13, Color.WHITE);
            num.setTypeface(MEDIUM);
            num.setGravity(Gravity.CENTER);
            GradientDrawable nb = new GradientDrawable();
            nb.setShape(GradientDrawable.OVAL);
            nb.setColor(Glass.ACCENT);
            num.setBackground(nb);
            r.addView(num, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView a = text(steps[i][0], 15, Glass.TEXT);
            a.setPadding(dp(12), 0, dp(8), 0);
            r.addView(a, weight());
            r.addView(text(steps[i][1], 14, Glass.SOFT));
            tapSmart.addView(r);
        }

        // good to know
        LinearLayout know = card(col, 24);
        know.addView(label("GOOD TO KNOW"));
        know.addView(note("Hold the watch face-down on the middle of the phone's back, just below the cameras. "
                + "The phone reads tags only while its screen is on and unlocked.\n\n"
                + "The tag is separate from the watch's chip, so it works even when the watch is off or on another face. "
                + "Write tag puts Pulse's link on it (this replaces what was there)."));
        refreshTap();
    }

    /** The Tap tab's lines and checks, fresh. */
    private void refreshTap() {
        if (tapNfc == null) return;
        android.nfc.NfcAdapter nfc = android.nfc.NfcAdapter.getDefaultAdapter(this);
        tapNfc.setText(nfc == null ? "✕  This phone has no NFC"
                : nfc.isEnabled() ? "✓  NFC is on" : "✕  NFC is off · tap here to turn it on");
        long w = prefs().getLong("tap_written", 0);
        tapTag.setText(w == 0 ? "•  Tag not written yet" : "✓  Tag written " + new java.text.SimpleDateFormat("d MMM", Locale.US).format(new java.util.Date(w)));
        long at = NfcTap.lastAt(this);
        tapLast.setText(at == 0 ? "•  No taps yet"
                : "•  Last tap " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at)) + " · " + NfcTap.lastDid(this));
        int a = NfcTap.action(this);
        for (int i = 0; i < tapChecks.size(); i++) ((TapCheck) tapChecks.get(i)).set(i == a);
        tapMacroRow.setVisibility(a == NfcTap.MACRO ? View.VISIBLE : View.GONE);
        Macros.Slot m = NfcTap.macroSlot(this);
        tapMacroChip.setText(m == null ? "None yet" : m.name);
        tapSmart.setVisibility(a == NfcTap.SMART ? View.VISIBLE : View.GONE);
    }

    /** Write tag: a sheet that waits for the watch, then puts pulse://tap on its tag. */
    private void writeTagSheet() {
        final android.nfc.NfcAdapter nfc = android.nfc.NfcAdapter.getDefaultAdapter(this);
        if (nfc == null) { Toast.makeText(this, "This phone has no NFC", Toast.LENGTH_SHORT).show(); return; }
        if (!nfc.isEnabled()) {
            Toast.makeText(this, "Turn on NFC first", Toast.LENGTH_SHORT).show();
            try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); } catch (ActivityNotFoundException ignored) { }
            return;
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(150), dp(120));
        al.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(new TapArt(this, false), al);
        final TextView st = text("Hold your watch face-down on the back of the phone", 15, Glass.SOFT);
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, dp(10), 0, dp(6));
        c.addView(st, new LinearLayout.LayoutParams(-1, -2));
        final boolean[] done = {false};
        final Runnable close = sheet("Write the tag", "Puts Pulse's link on your watch's NFC tag", c);
        tagSheet = close;
        try {
            nfc.enableReaderMode(this, tag -> {
                if (done[0]) return;
                if (menuClose != close) { ui.post(this::stopReader); return; }   // the sheet was closed
                final String err = writeTag(tag);
                ui.post(() -> {
                    if (err == null) {
                        done[0] = true;
                        prefs().edit().putLong("tap_written", System.currentTimeMillis())
                                .putString("tap_uid", hex(tag.getId())).apply();
                        swapText(st, "Done! Lift the watch, then tap it to try");
                        NfcTap.buzz(this);
                        refreshTap();
                        ui.postDelayed(() -> { close.run(); stopReader(); }, 1800);
                    } else {
                        swapText(st, err);
                    }
                });
            }, android.nfc.NfcAdapter.FLAG_READER_NFC_A | android.nfc.NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
            readerOn = true;
        } catch (Exception e) {
            swapText(st, "Couldn't start NFC");
        }
        ui.postDelayed(() -> { if (menuClose != close) stopReader(); }, 60_000);
    }

    private void stopReader() {
        if (!readerOn) return;
        readerOn = false;
        try { android.nfc.NfcAdapter.getDefaultAdapter(this).disableReaderMode(this); } catch (Exception ignored) { }
    }

    /** Puts pulse://tap on the tag; null if it worked, else why not. */
    private static String writeTag(android.nfc.Tag tag) {
        android.nfc.NdefMessage msg = new android.nfc.NdefMessage(android.nfc.NdefRecord.createUri(NfcTap.URI));
        try {
            android.nfc.tech.Ndef nd = android.nfc.tech.Ndef.get(tag);
            if (nd != null) {
                nd.connect();
                try {
                    if (!nd.isWritable()) return "This tag is locked, it can't be written";
                    if (nd.getMaxSize() < msg.toByteArray().length) return "This tag is too small";
                    nd.writeNdefMessage(msg);
                } finally {
                    try { nd.close(); } catch (Exception ignored) { }
                }
                return null;
            }
            android.nfc.tech.NdefFormatable f = android.nfc.tech.NdefFormatable.get(tag);
            if (f != null) {
                f.connect();
                try { f.format(msg); } finally { try { f.close(); } catch (Exception ignored) { } }
                return null;
            }
            return "This tag can't hold a link";
        } catch (Exception e) {
            return "Lost the tag: hold the watch still and try again";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        if (b != null) for (byte x : b) s.append(String.format(Locale.US, "%02X", x));
        return s.toString();
    }

    /** A round check: filled blue with a tick when chosen, an empty ring when not. */
    private static final class TapCheck extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean on;

        TapCheck(android.content.Context c) { super(c); }

        void set(boolean b) {
            if (b != on) { on = b; invalidate(); }
        }

        @Override
        protected void onDraw(android.graphics.Canvas k) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - 2, d = getResources().getDisplayMetrics().density;
            if (on) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(Glass.ACCENT);
                k.drawCircle(w / 2, h / 2, r, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * d);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                p.setColor(Color.WHITE);
                android.graphics.Path t = new android.graphics.Path();
                t.moveTo(w / 2 - r * 0.45f, h / 2 + r * 0.02f);
                t.lineTo(w / 2 - r * 0.1f, h / 2 + r * 0.36f);
                t.lineTo(w / 2 + r * 0.5f, h / 2 - r * 0.32f);
                k.drawPath(t, p);
            } else {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(1.6f * d);
                p.setColor(0x4D2C5A8C);
                k.drawCircle(w / 2, h / 2, r, p);
            }
        }
    }

    /** A watch with waves rolling out of it, toward the phone. */
    private static final class TapArt extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final boolean light;
        private ValueAnimator loop;
        private float t;

        TapArt(android.content.Context c, boolean light) {
            super(c);
            this.light = light;
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            loop = ValueAnimator.ofFloat(0f, 1f);
            loop.setDuration(1600);
            loop.setRepeatCount(ValueAnimator.INFINITE);
            loop.setInterpolator(new android.view.animation.LinearInterpolator());
            loop.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
            loop.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (loop != null) loop.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(android.graphics.Canvas k) {
            float w = getWidth(), h = getHeight(), u = Math.min(w / 92f, h / 76f);
            int ink = light ? Color.WHITE : Glass.ACCENT;
            float cx = w / 2f - 20 * u, cy = h / 2f;
            // the strap and the case
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor((ink & 0x00FFFFFF) | 0x40000000);
            r.set(cx - 9 * u, cy - 34 * u, cx + 9 * u, cy + 34 * u);
            k.drawRoundRect(r, 4 * u, 4 * u, p);
            p.setColor(ink);
            r.set(cx - 16 * u, cy - 19 * u, cx + 16 * u, cy + 19 * u);
            k.drawRoundRect(r, 7 * u, 7 * u, p);
            p.setColor(light ? Glass.ACCENT : Color.WHITE);
            r.set(cx - 11 * u, cy - 10 * u, cx + 11 * u, cy + 10 * u);
            k.drawRoundRect(r, 3 * u, 3 * u, p);
            // three waves, each lighting up in turn
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(4 * u);
            for (int i = 0; i < 3; i++) {
                float ph = (t - i * 0.18f + 1f) % 1f;
                float glow = ph < 0.5f ? (float) Math.sin(ph / 0.5f * Math.PI) : 0f;
                p.setColor(ink);
                p.setAlpha(Math.round(255 * (0.22f + 0.78f * glow)));
                float rad = (26 + i * 11) * u;
                r.set(cx + 6 * u - rad, cy - rad, cx + 6 * u + rad, cy + rad);
                k.drawArc(r, -38, 76, false, p);
            }
        }
    }

    /** Before sending a face: what goes where, what it replaces, and a pill to hold. */
    private void installSheet(final String face, final int slot) {
        if (FaceInstall.isBusy()) {
            Toast.makeText(this, "A face is still being sent", Toast.LENGTH_SHORT).show();
            return;
        }
        String where = FaceInstall.SLOT[slot], now = FaceInstall.known(this)[slot];
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout swap = new LinearLayout(this);
        swap.setGravity(Gravity.CENTER_VERTICAL);
        swap.addView(lcd(now.isEmpty() ? "?" : now, 14, 56, true), new LinearLayout.LayoutParams(0, dp(64), 1f));
        TextView arrow = text("\u2192", 22, Glass.ACCENT);
        arrow.setPadding(dp(10), 0, dp(10), 0);
        swap.addView(arrow);
        FrameLayout to = lcd(face, 14, 56, false);
        TextView b = badge(slot == 0 ? "A" : "B", true);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START);
        bl.leftMargin = dp(6);
        bl.topMargin = dp(6);
        to.addView(b, bl);
        swap.addView(to, new LinearLayout.LayoutParams(0, dp(64), 1f));
        c.addView(swap);
        LinearLayout go = new LinearLayout(this);
        go.setGravity(Gravity.CENTER_VERTICAL);
        go.setPadding(0, dp(18), 0, 0);
        final Runnable[] close = new Runnable[1];
        TextView cancel = text("Cancel", 15, Glass.SOFT);
        cancel.setTypeface(MEDIUM);
        cancel.setPadding(dp(4), dp(10), dp(16), dp(10));
        cancel.setOnClickListener(v -> close[0].run());
        go.addView(cancel);
        go.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        go.addView(new HoldPill(this, "Hold to send", "Sending", Glass.ACCENT, false).onDone(() -> {
            installFace(face, slot == 0 ? "A" : "B");
            ui.postDelayed(() -> close[0].run(), 700);
        }));
        c.addView(go);
        close[0] = sheet("Install " + face + " on " + where + "?",
                (now.isEmpty() ? "" : now.equalsIgnoreCase(face) ? "It's already there; this sends it again. "
                        : "Replaces " + now + ". ") + "Takes about 40 seconds; keep the watch near the phone.", c);
    }

    /** School / Home / Sleep as three little cards: the face each puts on A and on B. */
    private void buildProfFaces() {
        if (profFaces == null) return;
        profFaces.removeAllViews();
        List<String> files = FaceInstall.list(this, 60);
        final List<String> opts = new ArrayList<>();
        opts.add("Keep");
        if (files != null) opts.addAll(files);
        int cur = Profiles.current(this);
        for (int i = 0; i < Profiles.NAMES.length; i++) {
            final int prof = i;
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(10), dp(12), dp(10), dp(10));
            c.setBackground(i == cur ? Glass.accent(this, 18) : Glass.panel(this, 18));
            int ink = i == cur ? Color.WHITE : Glass.TEXT, soft = i == cur ? 0xCCFFFFFF : Glass.MUTED;
            TextView name = text(Profiles.NAMES[i], 15, ink);
            name.setTypeface(MEDIUM);
            c.addView(name);
            TextView st = text(i == cur ? "On now" : "", 11, soft);
            c.addView(st);
            for (int slot = 0; slot < 2; slot++) {
                final int sl = slot;
                String face = NotifService.profileFace(this, prof, sl);
                LinearLayout line = new LinearLayout(this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(0, dp(8), 0, 0);
                TextView b = badge(sl == 0 ? "A" : "B", false);
                line.addView(b, new LinearLayout.LayoutParams(dp(20), dp(20)));
                final TextView v = text(face.isEmpty() ? "keep" : face, 13, face.isEmpty() ? soft : ink);
                v.setTypeface(MEDIUM);
                v.setSingleLine(true);
                v.setEllipsize(android.text.TextUtils.TruncateAt.END);
                v.setPadding(dp(6), 0, 0, 0);
                line.addView(v, weight());
                line.setOnClickListener(x -> {
                    String nowFace = NotifService.profileFace(this, prof, sl);
                    int at = Math.max(0, opts.indexOf(nowFace));
                    String[] names = new String[opts.size()];
                    for (int j = 0; j < names.length; j++) names[j] = j == 0 ? "Keep what's there" : opts.get(j);
                    morph(v, names, at, n -> {
                        NotifService.profileFace(this, prof, sl, n == 0 ? "" : opts.get(n));
                        buildProfFaces();
                    });
                });
                c.addView(line);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            profFaces.addView(c, lp);
        }
    }

    /** Every backup as a list row with a little screen; tap one for its sheet. */
    private void buildBackups() {
        if (backupList == null) return;
        backupList.removeAllViews();
        List<Backups.Entry> all = Backups.all(this);
        if (all.isEmpty()) {
            TextView none = text("Nothing yet: faces you send from now on are kept here.", 14, Glass.SOFT);
            none.setPadding(0, dp(10), 0, dp(2));
            backupList.addView(none);
            return;
        }
        java.text.DateFormat df = new java.text.SimpleDateFormat("d MMM, h:mm a", Locale.getDefault());
        for (int i = 0; i < all.size(); i++) {
            final Backups.Entry e = all.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));
            FrameLayout thumb = lcd(e.name.length() > 3 ? e.name.substring(0, 3) : e.name, 12, 44, false);
            r.addView(thumb, new LinearLayout.LayoutParams(dp(50), dp(44)));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(12), 0, dp(8), 0);
            TextView n = text(e.name + "  v" + e.ver, 15, Glass.TEXT);
            n.setTypeface(MEDIUM);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(n);
            words.addView(text(df.format(new java.util.Date(e.time)) + "  \u00B7  "
                    + String.format(Locale.US, "%.1f KB", e.size / 1000f), 12, Glass.MUTED));
            r.addView(words, weight());
            r.addView(Glass.icon(this, Glass.Icon.CHEVRON, Glass.MUTED, false), new LinearLayout.LayoutParams(dp(18), dp(18)));
            r.setOnClickListener(v -> backupSheet(e));
            Glass.spring(r);
            if (i > 0) backupList.addView(hairline());
            backupList.addView(r);
        }
    }

    /** A backup's sheet: send it to A or B (hold), put it back in the folder, or hold to delete (undo). */
    private void backupSheet(final Backups.Entry e) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final int[] slot = {"B".equals(prefs().getString("faces_slot", "A")) ? 1 : 0};
        LinearLayout send = new LinearLayout(this);
        send.setGravity(Gravity.CENTER_VERTICAL);
        final TextView[] ab = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final int s2 = i;
            ab[i] = slotButton(i == 0 ? "A" : "B", i == slot[0], v -> {
                slot[0] = s2;
                for (int j = 0; j < 2; j++) {
                    ab[j].setBackground(j == s2 ? Glass.accent(this, 22) : Glass.panel(this, 22));
                    ab[j].setTextColor(j == s2 ? Color.WHITE : Glass.ACCENT);
                }
            });
            send.addView(ab[i]);
            send.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        }
        send.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        final Runnable[] close = new Runnable[1];
        send.addView(new HoldPill(this, "Hold to send", "Sending", Glass.ACCENT, false).onDone(() -> {
            byte[] js = Backups.read(e);
            if (js == null || !NotifService.installBackup(e.name, js, slot[0]))
                faceState.setText(js == null ? "Couldn't read that backup" : "Pulse isn't running: allow Notification access in Setup");
            else faceState.setText("Sending " + e.name + " v" + e.ver + " to " + FaceInstall.SLOT[slot[0]] + "...");
            ui.postDelayed(() -> close[0].run(), 700);
        }));
        c.addView(send);
        LinearLayout.LayoutParams hl2 = new LinearLayout.LayoutParams(-1, 1);
        hl2.topMargin = dp(16);
        c.addView(hairline(), hl2);
        LinearLayout more = new LinearLayout(this);
        more.setGravity(Gravity.CENTER_VERTICAL);
        more.setPadding(0, dp(14), 0, 0);
        TextView save = chip("Save to folder");
        save.setOnClickListener(v -> {
            String name = Backups.toFolder(this, e);
            if (name == null) {
                Toast.makeText(this, "Can't write there: tap Faces folder and pick it again to allow saving",
                        Toast.LENGTH_LONG).show();
            } else {
                confirm(save, "Save to folder");
                Toast.makeText(this, "Saved as " + name, Toast.LENGTH_SHORT).show();
                refreshFaces();
            }
        });
        more.addView(save);
        more.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        more.addView(new HoldPill(this, "Hold to delete", "Deleted", Glass.BAD, true).onDone(() -> {
            e.file.delete();
            buildBackups();
            close[0].run();
        }));
        c.addView(more);
        java.text.DateFormat df = new java.text.SimpleDateFormat("d MMM, h:mm a", Locale.getDefault());
        close[0] = sheet(e.name + "  v" + e.ver, "Sent " + df.format(new java.util.Date(e.time)) + "  \u00B7  "
                + String.format(Locale.US, "%.1f KB", e.size / 1000f), c);
    }

    /** A round A / B button; filled when that face is in the slot now. */
    private TextView slotButton(String slot, boolean here, View.OnClickListener l) {
        TextView b = text(slot, 15, here ? Color.WHITE : Glass.ACCENT);
        b.setTypeface(MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackground(here ? Glass.accent(this, 22) : Glass.panel(this, 22));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        b.setOnClickListener(l);
        Glass.spring(b);
        return b;
    }

    /** The picked Faces folder, readable ("Download/Faces"), or a hint to pick one. */
    private String facesDirName() {
        String d = prefs().getString("faces_dir", "");
        if (d.isEmpty()) return "No Faces folder yet: tap Faces folder and pick Download/Faces.";
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(d));
            return "Faces folder: " + id.substring(id.indexOf(':') + 1);
        } catch (Exception e) {
            return "Faces folder picked";
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_FACES && result == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                try {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
            }
            prefs().edit().putString("faces_dir", tree.toString()).apply();
            facesDir.setText(facesDirName());
            refreshFaces();
            return;
        }
        if (req == REQ_PAGE && result == RESULT_OK && data != null && data.getData() != null) {
            try (java.io.InputStream in = getAssets().open("play.html");
                 java.io.OutputStream o = getContentResolver().openOutputStream(data.getData(), "wt")) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                Toast.makeText(this, "Saved. Upload it to GitHub as index.html", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't save the page", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if ((req == REQ_EXPORT || req == REQ_IMPORT) && result == RESULT_OK && data != null && data.getData() != null) {
            setupFile(req == REQ_EXPORT, data.getData());
            return;
        }
        if (result != RESULT_OK || (req != REQ_CAMERA && req != REQ_GALLERY)) return;
        final Uri uri = req == REQ_CAMERA ? Photo.shotUri() : data == null ? null : data.getData();
        if (uri == null) return;
        readPhoto(uri);
    }

    /** Shrinks the photo, then asks Gemini (here or in its app) with the question typed above it. */
    private void readPhoto(final Uri uri) {
        final String q = prefs().getString("photoq", "");
        photoAns.setTextColor(Glass.MUTED);
        photoAns.setText("Reading the photo...");
        new Thread(() -> {
            try {
                final byte[] jpeg = Photo.prepare(this, uri);
                if (prefs().getInt("photo_via", 0) == 1) {
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(Photo.shareFile(this))) { o.write(jpeg); }
                    ui.post(() -> sendToGeminiApp(jpeg, q));
                } else {
                    ui.post(() -> askWithPhoto(jpeg, q));
                }
            } catch (Exception e) {
                ui.post(() -> {
                    photoAns.setTextColor(Glass.WARN);
                    photoAns.setText("Couldn't open that picture (" + e.getMessage() + ")");
                });
            }
        }, "pulse-photo").start();
    }

    /** Hands the photo to the Gemini app; its answer comes back by Share → Pulse watch. */
    private void sendToGeminiApp(byte[] jpeg, String q) {
        photoImg.setImageBitmap(Photo.preview(jpeg));
        photoImg.setVisibility(View.VISIBLE);
        Uri u = Photo.shareUri();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.putExtra(Intent.EXTRA_TEXT, q.isEmpty()
                ? "Answer the questions in this photo. One short line per answer, like: 16 IgG"
                : q + "\n(Keep it short: one line per item.)");
        i.setClipData(ClipData.newRawUri("", u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setPackage("com.google.android.apps.bard");
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            i.setPackage(null);                         // no Gemini app: let them pick one
            try {
                startActivity(Intent.createChooser(i, "Ask with"));
            } catch (ActivityNotFoundException e2) {
                photoAns.setTextColor(Glass.WARN);
                photoAns.setText("No app to send the photo to");
                return;
            }
        }
        photoAns.setTextColor(Glass.MUTED);
        photoAns.setText("Sent to the Gemini app. Tap send there; when it answers, tap Share on the answer "
                + "(or hold it, then Share) and pick Pulse watch. It goes straight to your watch.");
    }

    private void askWithPhoto(byte[] jpeg, String q) {
        photoImg.setImageBitmap(Photo.preview(jpeg));
        photoImg.setVisibility(View.VISIBLE);
        photoAns.setTextColor(Glass.MUTED);
        photoAns.setText("Asking Gemini...");
        final StringBuilder got = new StringBuilder();
        String why = NotifService.askPhoto(jpeg, q, new Face.Answers() {
            @Override public void row(String r) {
                got.append(got.length() > 0 ? "\n" : "").append(r);
                photoAns.setTextColor(Glass.TEXT);
                photoAns.setText(got);
            }
            @Override public void status(String st) {
                if (got.length() > 0) return;
                photoAns.setTextColor(Glass.MUTED);
                photoAns.setText(st);
            }
            @Override public void done(String w) {
                if (w == null) { showKey(); return; }
                photoAns.setTextColor(Glass.WARN);
                photoAns.setText(got.length() > 0 ? got + "\n\n(" + w + ")" : "No answer: " + w
                        + "\n" + prefs().getString("gemerr", ""));
                showKey();
            }
        });
        if (why != null) {
            photoAns.setTextColor(Glass.WARN);
            photoAns.setText(why);
        }
    }

    /** Plays the last recording Pulse sent to Gemini, to hear what it heard. */
    private void playLast() {
        java.io.File f = VoiceActivity.lastFile(this);
        if (!f.exists()) { Toast.makeText(this, "No recording yet (Pulse mode only)", Toast.LENGTH_SHORT).show(); return; }
        try {
            if (player != null) player.release();
            player = new android.media.MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
        } catch (Exception e) {
            Toast.makeText(this, "Can't play: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showKey() {
        boolean has = !prefs().getString("ytkey", "").isEmpty();
        keyState.setText(has ? "Key saved · 100 searches a day" : "No key: using the search page");
        keyState.setTextColor(has ? Glass.GOOD : Glass.MUTED);
        keySec.summary.setText(has ? "Saved" : "Optional");
        boolean ai = !prefs().getString("gemkey", "").isEmpty();
        String err = prefs().getString("gemerr", "");
        if (lastHeard != null) {
            String q = prefs().getString("lastheard", "");
            lastHeard.setText(q.isEmpty() ? "Nothing asked yet" : "Heard: " + q);
        }
        aiState.setText(!ai ? "No key: Ask AI is off" : err.isEmpty() ? "Key saved" : "Last error  " + err);
        aiState.setTextColor(!ai ? Glass.MUTED : err.isEmpty() ? Glass.GOOD : Glass.WARN);
        aiSec.summary.setText(ai ? "Saved" : "Needed for Ask AI");
    }

    private void askBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    // ---------- macros ----------

    private static String kindName(String k) {
        return Macros.STATUS.equals(k) ? "Phone battery" : Macros.NEXT.equals(k) ? "Calendar"
                : Macros.RECORD.equals(k) ? "Recorder" : "MacroDroid";
    }

    /** "1 rEC": the macro a combo runs, or Off. */
    private String trigName(boolean hold) {
        int num = Macros.trigger(this, hold);
        if (num == 0) return "Off";
        if (num == Macros.TAP) return "Tap action";
        for (Macros.Slot m : Macros.load(this)) if (m.number == num) return num + " " + (m.name.isEmpty() ? "(empty)" : m.name);
        return "Off";
    }

    /** A list of the named macros to pick from. */
    private void pickTrigger(final boolean hold, final TextView chip) {
        final List<Macros.Slot> named = Macros.active(this);
        CharSequence[] items = new CharSequence[named.size() + 2];
        items[0] = "Off";
        items[1] = "Tap action  (" + NfcTap.NAMES[NfcTap.action(this)] + ")";
        for (int i = 0; i < named.size(); i++) items[i + 2] = named.get(i).number + "   " + named.get(i).name;
        new android.app.AlertDialog.Builder(this)
                .setTitle(hold ? "Hold A + L runs" : "Double-tap A runs")
                .setItems(items, (d, which) -> {
                    prefs().edit().putInt(hold ? "trig_hold" : "trig_double",
                            which == 0 ? 0 : which == 1 ? Macros.TAP : named.get(which - 2).number).apply();
                    swapText(chip, trigName(hold));
                })
                .show();
    }

    private String introName() {
        String n = prefs().getString("anim_intro", "");
        return n.isEmpty() || Anim.find(this, n) == null ? "None" : n;
    }

    private void playClip(Anim.Clip clip) {
        String why = NotifService.playAnim(clip);
        if (why != null) Toast.makeText(this, why, Toast.LENGTH_SHORT).show();
    }

    private void buildMacros() {
        slots = Macros.load(this);
        macroRows.removeAllViews();
        macroNames.clear();
        int named = 0;
        for (final Macros.Slot m : slots) {
            if (!m.name.isEmpty()) named++;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(5), 0, dp(5));
            TextView num = text(String.valueOf(m.number), 14, Glass.MUTED);
            num.setTypeface(MEDIUM);
            r.addView(num, new LinearLayout.LayoutParams(dp(22), -2));
            EditText name = field("—");
            name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
            name.setTypeface(Typeface.MONOSPACE);
            name.setText(m.name);
            r.addView(name, weight());
            macroNames.add(name);
            final TextView kind = chip(kindName(m.kind));
            kind.setOnClickListener(v -> {
                m.kind = Macros.MACRODROID.equals(m.kind) ? Macros.STATUS
                        : Macros.STATUS.equals(m.kind) ? Macros.NEXT
                        : Macros.NEXT.equals(m.kind) ? Macros.RECORD : Macros.MACRODROID;
                swapText(kind, kindName(m.kind));
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            });
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(dp(132), -2);
            kl.leftMargin = dp(10);
            r.addView(kind, kl);
            macroRows.addView(r);
        }
        macroSec.summary.setText(named + " set");
    }

    private void saveMacros() {
        for (int i = 0; i < slots.size(); i++) slots.get(i).name = Macros.clean(macroNames.get(i).getText().toString());
        Macros.save(this, slots);
        buildMacros();
        if (trigHold != null) trigHold.setText(trigName(true));
        if (trigDouble != null) trigDouble.setText(trigName(false));
        confirm(saveMacrosBtn, "Save macros");
    }

    // ---------- building blocks ----------

    /** A card that folds open and shut from its title row. */
    private final class Section {
        final LinearLayout card, body;
        final TextView summary;
        final View chevron;
        boolean open;

        Section(LinearLayout parent, String title, boolean startOpen) {
            card = card(parent, 24);
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = label(title);
            t.setPadding(0, 0, 0, 0);
            head.addView(t, weight());
            summary = text("", 13, Glass.MUTED);
            summary.setPadding(0, 0, dp(8), 0);
            head.addView(summary);
            chevron = Glass.icon(MainActivity.this, Glass.Icon.CHEVRON, Glass.SOFT, false);
            head.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
            card.addView(head);
            body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, dp(12), 0, 0);
            body.setVisibility(View.GONE);
            card.addView(body);
            head.setOnClickListener(v -> toggle());
            if (startOpen) {
                open = true;
                body.setVisibility(View.VISIBLE);
                chevron.setRotation(90);
            }
        }

        void toggle() {
            open = !open;
            chevron.animate().rotation(open ? 90 : 0).setDuration(700)
                    .setInterpolator(Glass.BOUNCY).start();
            if (open) {
                // the card grows to make room, then the rows drop in one after another from above
                body.animate().cancel();
                body.setVisibility(View.VISIBLE);
                body.setAlpha(1f);
                body.setScaleY(1f);
                body.setTranslationY(0f);
                for (int i = 0; i < body.getChildCount(); i++) {
                    View row = body.getChildAt(i);
                    row.animate().cancel();
                    if (i > 11) { row.setAlpha(1f); row.setTranslationY(0f); continue; }
                    row.setAlpha(0f);
                    row.setTranslationY(-dp(12));
                    row.animate().alpha(1f).translationY(0f).setStartDelay(90L + 40L * i).setDuration(620)
                            .setInterpolator(Glass.GENTLE).start();
                }
            } else {
                body.animate().alpha(0f).translationY(-dp(8)).setStartDelay(0).setDuration(220)
                        .withEndAction(() -> { body.setVisibility(View.GONE); body.setTranslationY(0f); }).start();
            }
        }
    }

    private View gapRow() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(10)));
        return v;
    }

    private Section section(LinearLayout parent, String title, boolean open) {
        return new Section(parent, title, open);
    }

    private LinearLayout card(LinearLayout parent, int radius) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        c.setBackground(Glass.panel(this, radius));
        c.setLayoutTransition(resizing());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        Glass.enter(c, ++cards);
        return c;
    }

    /**
     * Animates size changes only (a card growing as it opens, the cards below sliding down).
     * Fading is done by hand, so Android's own fade in and out are switched off.
     */
    private static LayoutTransition resizing() {
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.disableTransitionType(LayoutTransition.APPEARING);
        lt.disableTransitionType(LayoutTransition.DISAPPEARING);
        // neighbours don't jump out of the way: they are pushed, overshoot and settle
        lt.setDuration(700);
        lt.setInterpolator(LayoutTransition.CHANGING, Glass.GENTLE);
        lt.setInterpolator(LayoutTransition.CHANGE_APPEARING, Glass.GENTLE);
        lt.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, Glass.GENTLE);
        return lt;
    }

    /** One checklist row; returns 1 if it still needs doing. */
    private int check(String title, String hint, boolean done, String action, View.OnClickListener l) {
        if (checklist.getChildCount() > 0) checklist.addView(hairline());
        View mark = Glass.icon(this, done ? Glass.Icon.CHECK : Glass.Icon.ALERT, done ? Glass.GOOD : Glass.WARN, true);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(26), dp(26));
        mp.rightMargin = dp(12);
        row.addView(mark, mp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 15, Glass.TEXT));
        words.addView(text(hint, 12, Glass.MUTED));
        row.addView(words, weight());
        if (!done && action != null) row.addView(pill(action, true, l));
        checklist.addView(row);
        return done ? 0 : 1;
    }

    /** Title and hint on the left, a control on the right. */
    private LinearLayout lineRow(String title, String hint, View control) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(11), 0, dp(11));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 16, Glass.TEXT));
        TextView h = text(hint, 13, Glass.MUTED);
        h.setPadding(0, dp(2), dp(12), 0);
        words.addView(h);
        r.addView(words, weight());
        r.addView(control);
        return r;
    }

    /** A card's title: "BATTERY SAVER" in the code reads "Battery saver" on screen. */
    private TextView label(String s) {
        String nice = s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.US);
        nice = nice.replace("Youtube", "YouTube").replace("macrodroid", "MacroDroid");
        TextView t = text(nice, 18, Glass.TEXT);
        t.setTypeface(MEDIUM);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    /** A sentence of explanation at the top of a card. */
    private TextView note(String s) {
        TextView t = text(s, 14, Glass.SOFT);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    /** One line of the drain table: a name, then two numbers in fixed-width columns. */
    private LinearLayout statRow(TextView name, TextView now, TextView all, boolean tight) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(tight ? 4 : 10), 0, dp(tight ? 4 : 10));
        r.addView(name, weight());
        now.setGravity(Gravity.END);
        all.setGravity(Gravity.END);
        r.addView(now, new LinearLayout.LayoutParams(dp(78), -2));
        r.addView(all, new LinearLayout.LayoutParams(dp(78), -2));
        return r;
    }

    /** One MacroDroid intent: what it is for, its action name (tap to copy), and its extras. */
    private void intent(LinearLayout parent, String title, final String action, String extras) {
        parent.addView(hairline());
        TextView t = text(title, 15, Glass.TEXT);
        t.setTypeface(MEDIUM);
        t.setPadding(0, dp(12), 0, dp(6));
        parent.addView(t);
        final TextView a = text(action, 13, Glass.ACCENT);
        a.setTypeface(Typeface.MONOSPACE);
        a.setPadding(dp(12), dp(8), dp(12), dp(8));
        a.setBackground(Glass.panel(this, 12));
        a.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("action", action));
            Glass.pop(a);
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        Glass.spring(a);
        parent.addView(a, new LinearLayout.LayoutParams(-2, -2));
        TextView e = text(extras, 13, Glass.SOFT);
        e.setPadding(0, dp(6), 0, dp(12));
        parent.addView(e);
    }

    private TextView pill(String s, boolean filled, View.OnClickListener l) {
        TextView b = text(s, 15, filled ? Color.WHITE : Glass.TEXT);
        b.setTypeface(MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(11), dp(14), dp(11));
        b.setSingleLine(true);
        b.setMaxLines(1);
        // a long word gets a little smaller instead of breaking onto a second line
        b.setAutoSizeTextTypeUniformWithConfiguration(11, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        b.setBackground(filled ? Glass.accent(this, 22) : Glass.panel(this, 22));
        b.setOnClickListener(l);
        Glass.spring(b);
        return b;
    }

    private TextView chip(String s) {
        TextView c = text(s, 13, Glass.ACCENT);
        c.setTypeface(MEDIUM);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setBackground(Glass.panel(this, 14));
        Glass.spring(c);
        return c;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(Glass.TEXT);
        e.setHintTextColor(Glass.FAINT);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xB3FFFFFF);
        g.setCornerRadius(dp(12));
        g.setStroke(1, 0x402C5A8C);
        e.setBackground(g);
        return e;
    }

    /** A button says "Saved ✓" with a little pop, then goes back. */
    private void confirm(TextView b, String original) {
        b.setText("Saved");
        Glass.trail(b, new Glass.Icon(Glass.Icon.CHECK, b.getCurrentTextColor(), false).drawIn(b));
        Glass.pop(b);
        ui.postDelayed(() -> { Glass.trail(b, null); swapText(b, original); }, 1600);
    }

    /** Text crossfades instead of snapping. */
    private void swapText(TextView t, String s) {
        t.animate().alpha(0f).setDuration(80).withEndAction(() -> {
            t.setText(s);
            t.animate().alpha(1f).setDuration(140).start();
            Glass.pop(t);
        }).start();
    }

    private void setIfNew(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    private View hairline() {
        View line = new View(this);
        line.setBackgroundColor(Glass.LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return line;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        return (m / 1440) + " days ago";
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("pulse", MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
