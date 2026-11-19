package com.ollee.pulse;

import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Decides what the Pulse 2 watch face shows and what each button does.
 * The watch sends a / A / l / L (tap or hold A / L); this answers with screens.
 * Everything runs on the main thread.
 */
final class Face {

    enum S { MUSIC, MENU, INBOX, POPUP, CALL, VOLUME, TYPING, RESULTS, WAIT, MACROS, MACRO_RUN, MSG, EVENTS, ASK, ASK_WAIT, ASK_A, CHOOSE, NAV, REC, GAME }

    private static final String[] MENU = {"SEArCH", "ASK AI", "AnSWEr", "InbOX", "MACrO", "bAtt"};
    private List<String> menuItems = new ArrayList<>(), menuIds = new ArrayList<>();
    private static final String WH = "_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789&";   // _ = space
    private static final int[] STOPS = {1, 6, 11, 16, 21, 27};
    private static final int MAX_NOTES = 10;

    /** Answers from the phone's helpers (called on the main thread). */
    interface Got { void got(List<String> list); }
    interface Played { void done(boolean ok, String why); }
    interface Answers { void row(String r); void done(String why); default void status(String s) { } }   // why null = ok

    static final class Note {
        final String app, who, text;
        Note(String a, String w, String t) { app = a; who = w; text = t; }
    }

    /** What the watch should know when its face opens: unread count, saver mode, clock without seconds. */
    private int saverSent = -1;

    private String state() {
        saverSent = Saver.saverOn(svc) ? 1 : 0;
        return "U" + unread + "|" + saverSent + "|" + (Saver.seconds(svc) ? 0 : 1);
    }

    /** A new battery reading or a changed setting: tell the watch if saver mode flipped. */
    void stateChanged() {
        if (faceOn && saverSent != (Saver.saverOn(svc) ? 1 : 0)) send(state());
    }

    private final NotifService svc;
    private final Handler h;
    S s = S.MUSIC;
    boolean faceOn;

    // notifications
    private final List<Note> notes = new ArrayList<>();
    private int unread, ni;
    // call
    private String caller;
    // volume (last known)
    private int vol, volMax = 15;
    private boolean muted;
    // menu
    private int mi;
    // typing and results
    private String qt = "";
    private int qp = -1, querySeq, si;
    private boolean wantResults;         // hold A came before the suggestions did
    private List<String> sg = new ArrayList<>(), rl = new ArrayList<>();
    // macros
    private List<Macros.Slot> slots = new ArrayList<>();
    private int ms;
    private long lastRun = -1;
    /** One upcoming calendar item, split into what the watch shows in turn. */
    static final class Ev {
        final String day, time, title;    // time null = all day
        final boolean pm;
        Ev(String d, String t, boolean p, String n) { day = d; time = t; pm = p; title = n; }
    }
    private final List<Ev> evs = new ArrayList<>();       // nEXt: up to 5 items
    private int ei, step;
    private final Runnable evNext = this::evStep;               // when a MacroDroid slot last ran (elapsedRealtime)
    private static final long LATE = 120000; // replies this soon after still pop up
    // music: a first L tap waiting to see if a second one comes
    private boolean lWait;

    private final Runnable timeout = this::onTimeout;
    private final Runnable nextTrack = this::nextTrack;
    private final Runnable commitNow = this::commit;
    private final Runnable suggestNow = this::fetchSuggestions;
    private final Runnable resultsNow = () -> {
        if (wantResults && s == S.TYPING) { wantResults = false; showResults(); }
    };

    Face(NotifService svc, Handler h) {
        this.svc = svc;
        this.h = h;
    }

    // ---------- talking to the watch ----------

    private WatchLink link() { return WatchLink.get(svc); }

    private void send(String line) {
        if (faceOn) link().sendLine(line);
    }

    /** Text screen. hold = frames (20 a second) before the watch goes back to music by itself. */
    private void text(String main, String day, int flags, int fx, int beep, String alt, int hold) {
        text(main, day, flags, fx, beep, alt, hold, 0);
    }

    /** speed = frames per letter while scrolling (0 = the watch's normal 4). */
    private void text(String main, String day, int flags, int fx, int beep, String alt, int hold, int speed) {
        send("T" + bar(main) + "|" + bar(day) + "|" + flags + "|" + fx + "|" + beep + "|" + bar(alt) + "|" + hold
                + (speed > 0 ? "|" + speed : ""));
    }

    private static String bar(String s) {
        return s == null ? "" : s.replace('|', '/');
    }

    private void after(long ms) {
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, ms);
    }

    private void home(int fx) {
        h.removeCallbacks(timeout);
        h.removeCallbacks(evNext);
        h.removeCallbacks(meter);
        h.removeCallbacks(rowNext);
        h.removeCallbacks(commitNow);
        s = S.MUSIC;
        send(fx > 0 ? "h" + fx : "h");
        // Maps is still navigating: the turn takes the music screen's place again
        if (!navLine.isEmpty() && SystemClock.elapsedRealtime() >= navHiddenUntil) {
            s = S.NAV;
            send(navLine);
        }
    }

    /** A short message; the watch returns to music by itself (and says 'e'). */
    private void toast(String t, int fx) {
        h.removeCallbacks(timeout);
        s = S.MSG;
        text(t, "", 0, fx, 1, "", 36);
    }

    private void onTimeout() {
        switch (s) {
            case WAIT: fail("nO HIt"); break;
            case MACRO_RUN: macroReply("nO AnS", 7); break;
            case ASK: VoiceActivity.cancel(); home(0); break;
            case ASK_WAIT: toast("nO AnS", 7); break;
            case CHOOSE: pick(0, ""); home(0); break;      // nobody chose
            case RESULTS: case TYPING: case MENU: case INBOX: case VOLUME: case MACROS: case MSG:
            default: home(0);
        }
    }

    // ---------- events from the watch ----------

    void onEvent(char c) {
        if (c == 'H') { onEnter(); return; }
        if (c == 'X') {
            faceOn = false;
            h.removeCallbacks(timeout);
            // left the Pulse face while reading (or waiting for) an answer: come back to it
            resumeAsk = s == S.ASK_A || s == S.ASK_WAIT;
            h.removeCallbacks(forgetResume);
            if (resumeAsk) h.postDelayed(forgetResume, 600000);      // for 10 minutes
            if (s != S.CALL && s != S.GAME) s = S.MUSIC;
            return;
        }
        boolean was = faceOn;
        faceOn = true;
        // the face's hello got lost (it can be sent before the link is fully up): the first
        // button press is the proof that the face is showing, so send it its settings now
        if (!was) send(state());
        if (c == 'e') {              // a timed screen ended on the watch
            if (inGame && Party.now != null && (s == S.POPUP || s == S.MSG)) {   // back to the game
                s = S.GAME;
                gameRender();
                return;
            }
            if (s == S.POPUP || s == S.MSG || s == S.CALL) {
                s = S.MUSIC;
                h.removeCallbacks(navBack);
                h.postDelayed(navBack, 300);         // and back to the turn, if Maps is navigating
            }
            return;
        }
        boolean hold = c == 'A' || c == 'L', a = c == 'a' || c == 'A';
        // trigger: A and L held together from the music screen. The watch reports the two
        // holds one after the other, so the first one opens the menu or the volume for a moment.
        if (hold && Macros.trigger(svc, true) != 0) {
            long now = SystemClock.elapsedRealtime();
            if (holdFromMusic && c != holdWas && now - holdAt < 600) {
                holdAt = 0;
                holdFromMusic = false;
                fireTrigger(Macros.trigger(svc, true));
                return;
            }
            holdAt = now;
            holdWas = c;
            holdFromMusic = s == S.MUSIC;
        }
        switch (s) {
            case MENU: menu(a, hold); break;
            case INBOX: inbox(a, hold); break;
            case POPUP:
                if (!a && !hold) showNote(false);
                else home(0);
                break;
            case CALL: if (a && !hold) home(0); break;
            case VOLUME: volumeKey(a, hold); break;
            case TYPING: typing(a, hold); break;
            case RESULTS: results(a, hold); break;
            case WAIT: if (!a && hold) showResults(); break;
            case MACROS: macros(a, hold); break;
            case MACRO_RUN: if (!a && hold) backToMacros(); break;
            case CHOOSE: choose(a, hold); break;
            case GAME: gameKey(a, hold); break;
            case NAV:                                         // any button: the music screen for a moment
                navHiddenUntil = SystemClock.elapsedRealtime() + NAV_PAUSE;
                h.removeCallbacks(navBack);
                h.postDelayed(navBack, NAV_PAUSE);
                home(0);
                break;
            case REC:
                if (a && !hold && recSlot != null) record(recSlot);      // tap A: stop recording
                else home(0);                                            // the recording goes on
                break;
            case EVENTS: events(a, hold); break;
            case ASK:
                if (hold && !a) { VoiceActivity.cancel(); home(0); }
                else if (a && !hold) VoiceActivity.finishNow();     // done talking
                break;
            case ASK_WAIT: if (hold && !a) home(0); break;
            case ASK_A: askA(a, hold); break;
            case MSG: home(0); break;
            default: music(a, hold);
        }
    }

    /** The Pulse face came on screen: show what it should. */
    private void onEnter() {
        faceOn = true;
        link().setFaceOn(true);
        send(state());
        if (s == S.CALL && caller != null) { send("R" + bar(caller)); return; }
        if (s == S.GAME && Party.now != null) { gameRender(); return; }
        // back on the Pulse face after leaving it from an answer: straight back to that answer
        if (resumeAsk && s == S.MUSIC) {
            resumeAsk = false;
            h.removeCallbacks(forgetResume);
            if (!rows.isEmpty()) s = S.ASK_A;
            else if (!aiDone) { s = S.ASK_WAIT; after(45000); }
        }
        // the face came back (or the watch reconnected) while an answer is coming or showing: keep it
        if (s == S.ASK_A && !rows.isEmpty()) { if (ri >= rows.size()) ri = 0; showRow(false); return; }
        if (s == S.ASK_WAIT) {
            text("ASK", "AI", 0, 0, 0, "", 0);
            if (!lowPower) send("W");
            return;
        }
        // the recorder's timer was up when the face left or the link dropped: put it back
        if (s == S.REC && recStart > 0) { showRec(false); return; }
        if (s == S.NAV && !navLine.isEmpty()) { send(navLine); return; }     // still navigating: the turn again
        // a macro asked a question while another face was showing: ask it now (for 2 minutes)
        if (!chooseId.isEmpty() && s == S.MUSIC) {
            if (SystemClock.elapsedRealtime() - chooseAt < 120000) {
                s = S.CHOOSE;
                showChoice(true);
                return;
            }
            pick(0, "");
        }
        if (unread > 0 && !notes.isEmpty()) {
            unread = 0;
            send("U0");
            ni = 0;
            s = S.INBOX;
            showNote(true);
            after(30000);
            return;
        }
        home(0);
    }

    // ---------- music ----------

    private void music(boolean a, boolean hold) {
        if (hold) {
            if (a) openMenu();
            else openVolume();
            return;
        }
        if (a) {
            if (Macros.trigger(svc, false) != 0) {  // double-tap A is a trigger: play/pause waits to see if a second tap comes
                if (aWait) {
                    h.removeCallbacks(playNow);
                    aWait = false;
                    fireTrigger(Macros.trigger(svc, false));
                } else {
                    aWait = true;
                    h.postDelayed(playNow, 350);
                }
                return;
            }
            svc.music('P');
            return;
        }
        if (lWait) {                 // second L in time: previous track
            h.removeCallbacks(nextTrack);
            lWait = false;
            svc.music('B');
            send("h2");
        } else {
            lWait = true;
            h.postDelayed(nextTrack, 350);
        }
    }

    private void nextTrack() {
        lWait = false;
        svc.music('N');
        send("h1");
    }

    // ---------- menu ----------

    private void openMenu() {
        s = S.MENU;
        mi = 0;
        // AnSWEr (the last AI answer) only shows when there is one
        // the order, names and hidden items come from the app's menu editor
        menuItems = new ArrayList<>();
        menuIds = new ArrayList<>();
        for (WatchMenu.Item it : WatchMenu.load(svc)) {
            if (!it.shown || (it.id.equals("answer") && !hasAnswer())) continue;
            menuItems.add(it.name);
            menuIds.add(it.id);
        }
        if (menuItems.isEmpty()) {                 // everything hidden: still give the battery
            menuItems.add("bAtt");
            menuIds.add("batt");
        }
        showMenu();
    }

    /** The watch's NFC tap opens the hold-A menu. */
    void openMenuFromPhone() {
        openMenu();
    }

    private void showMenu() {
        String item = menuItems.get(mi);
        String day = menuIds.get(mi).equals("inbox") && !notes.isEmpty() ? String.valueOf(notes.size()) : "";
        text(item, day, 0, 0, 0, "", 0);
        after(8000);
    }

    private void menu(boolean a, boolean hold) {
        if (hold) { home(0); return; }
        if (!a) {
            mi = (mi + 1) % menuItems.size();
            showMenu();
            return;
        }
        switch (menuIds.get(mi)) {
            case "search":
                if (!Features.on(svc, Features.SEARCH)) { toast("OFF", 7); break; }
                qt = "";
                qp = -1;
                sg = new ArrayList<>();
                s = S.TYPING;
                showTyping();
                break;
            case "ask":
                startAsk();
                break;
            case "answer":
                openAnswer();
                break;
            case "inbox":
                if (notes.isEmpty()) { toast("nO MSG", 0); break; }
                ni = 0;
                unread = 0;
                send("U0");
                s = S.INBOX;
                showNote(true);
                after(30000);
                break;
            case "macro":
                openMacros();
                break;
            case "faces":
                openFaces();
                break;
            case "play":
                openPlay();
                break;
            default:
                List<Battery.Reading> b = Battery.load(svc);
                toast(b.isEmpty() ? "--- V" : String.format(Locale.US, "%.2f V", b.get(b.size() - 1).volts), 0);
        }
    }

    // ---------- notifications ----------

    /** A notification from the phone (or a message MacroDroid sent on its own). */
    void onNotification(String app, String who, String text) {
        notes.add(0, new Note(app, who, text));
        while (notes.size() > MAX_NOTES) notes.remove(notes.size() - 1);
        if (faceOn && (s == S.MUSIC || s == S.POPUP || s == S.INBOX)) {
            ni = 0;
            s = S.POPUP;
            Anim.Clip intro = Anim.intro(svc);
            if (intro == null) {
                showNote(false);
            } else {                          // a clip first, then the message just before it ends
                send(intro.line(1));
                h.removeCallbacks(noteNow);
                h.postDelayed(noteNow, Math.max(200, intro.ms(1) - 150));
            }
        } else {
            unread++;
            link().sendLine("U" + unread);
            link().sendLine("b");
        }
    }

    /** Messages that were held back and now arrive together: one wake-up for the watch, not one each. */
    void onBatch(List<Note> list) {
        if (list.isEmpty()) return;
        for (Note n : list) notes.add(0, n);
        while (notes.size() > MAX_NOTES) notes.remove(notes.size() - 1);
        if (faceOn && (s == S.MUSIC || s == S.POPUP || s == S.INBOX)) {
            if (list.size() > 1) {                 // the newest pops up, the rest count as unread
                unread += list.size() - 1;
                send("U" + unread);
            }
            ni = 0;
            s = S.POPUP;
            showNote(false);
        } else {
            unread += list.size();
            link().sendLine("U" + unread);
            link().sendLine("b");
        }
    }

    private void showNote(boolean loop) {
        Note n = notes.get(Math.min(ni, notes.size() - 1));
        String day = String.valueOf(loop ? ni + 1 : notes.size());
        send("N" + (loop ? 1 : 0) + "|" + day + "|" + bar(n.app) + "|" + bar(n.who) + "|" + bar(n.text));
    }

    private void inbox(boolean a, boolean hold) {
        after(30000);
        if (hold) {
            if (a) { home(0); return; }
            notes.clear();
            toast("CLEAr", 5);
            return;
        }
        if (!a) { showNote(true); return; }
        if (++ni >= notes.size()) home(0);
        else showNote(true);
    }

    // ---------- trigger: a button combo runs one macro straight from the music screen ----------

    private long holdAt;
    private char holdWas;
    private boolean holdFromMusic, aWait, quick;     // quick = run by the trigger: go back to music after
    private final Runnable playNow = this::playNow;

    private void playNow() {
        aWait = false;
        svc.music('P');
    }

    private final Runnable noteNow = () -> {
        if (s == S.POPUP || s == S.MUSIC) { s = S.POPUP; showNote(false); }
    };

    private void fireTrigger(int num) {
        h.removeCallbacks(timeout);
        if (num == Macros.TAP) {                     // the Tap action, same as tapping the NFC tag
            int a = NfcTap.action(svc);
            String r = NfcTap.run(svc);
            if (a == NfcTap.PROFILE || a == NfcTap.OLLEE || r.startsWith("No ") || r.startsWith("Couldn"))
                toast(Txt.seg(Txt.clean(r, 30)), 7);
            return;
        }
        slots = Macros.active(svc);
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).number == num) {
                ms = i;
                quick = true;
                run(slots.get(i));
                return;
            }
        }
        toast("nO SLOt", 7);
    }

    /** After a macro ran: the list it was picked from, or music if the trigger ran it. */
    private void backToMacros() {
        if (quick) {
            quick = false;
            home(0);
        } else {
            showMacros();
        }
    }

    // ---------- recorder: runs its macro, and the watch counts the time ----------

    private long recStart;                 // elapsedRealtime when it was started, 0 = not recording
    private Macros.Slot recSlot;

    /** First run starts (the macro gets state=start), the next one stops (state=stop). */
    private void record(Macros.Slot m) {
        boolean start = recStart == 0;
        long now = SystemClock.elapsedRealtime();
        Intent i = new Intent("com.ollee.pulse.MACRO");
        i.putExtra("slot", String.valueOf(m.number));
        i.putExtra("name", m.name);
        i.putExtra("state", start ? "start" : "stop");
        svc.sendBroadcast(i);
        lastRun = now;
        NotifService.lastButton = "Macro " + m.number + " (" + m.name + ") → " + (start ? "recording" : "stopped");
        h.removeCallbacks(timeout);
        if (start) {
            recStart = now;
            recSlot = m;
            showRec(true);
            return;
        }
        long sec = (now - recStart) / 1000;
        recStart = 0;
        s = S.MSG;
        // the total, like a clock: minutes and seconds, or hours and minutes past an hour
        String t = sec < 3600 ? String.format(Locale.US, "%2d%02d  ", sec / 60, sec % 60)
                : String.format(Locale.US, "%2d%02d  ", sec / 3600, sec / 60 % 60);
        text(t, "rE", 1, 0, 1, "StOP", 0);
        h.postDelayed(() -> { if (s == S.MSG) backToMacros(); }, 4000);
    }

    /** K<seconds so far>|<day digits>|<beep>: the watch counts on from there by itself. */
    private void showRec(boolean beep) {
        h.removeCallbacks(timeout);
        s = S.REC;
        send("K" + (SystemClock.elapsedRealtime() - recStart) / 1000 + "|rE|" + (beep ? 1 : 0));
    }

    // ---------- Pulse as a platform: a macro asks, the watch picks ----------

    private List<String> choices = new ArrayList<>();
    private String chooseId = "";
    private int ci;
    private long chooseAt;
    private final Runnable choiceNow = () -> { if (s == S.CHOOSE) showChoice(false); };

    /** com.ollee.pulse.ASK: shows the choices; the pick goes back as com.ollee.pulse.PICK. */
    void onAsk(String id, String title, List<String> list) {
        if (list.isEmpty()) return;
        if (!chooseId.isEmpty()) pick(0, "");                    // an older question nobody answered
        chooseId = id == null || id.isEmpty() ? "ask" : id;
        if (s == S.CALL || s == S.ASK || s == S.ASK_WAIT) { pick(0, ""); return; }   // busy
        choices = list;
        ci = 0;
        chooseAt = SystemClock.elapsedRealtime();
        if (!faceOn) {                                           // asked when the Pulse face comes back
            link().sendLine("b");
            return;
        }
        h.removeCallbacks(commitNow);
        s = S.CHOOSE;
        String t = Txt.seg(Txt.clean(title, 30));
        if (t.isEmpty()) {
            showChoice(true);
        } else {                                                 // the question first, then the choices
            text(t, "", 0, 0, 1, "", 0);
            h.removeCallbacks(choiceNow);
            h.postDelayed(choiceNow, Math.max(1500, readTime(t) - 1500));
            after(30000);
        }
    }

    private void showChoice(boolean beep) {
        h.removeCallbacks(choiceNow);
        String day = String.valueOf(ci + 1);
        if (FACES_ID.equals(chooseId)) {                          // already on the watch: A / b instead of the number
            String m = faceMark(choices.get(ci));
            if (!m.isEmpty()) day = m;
        }
        text(Txt.seg(Txt.clean(choices.get(ci), 30)), day, 0, 0, beep ? 1 : 0, "", 0);
        after(30000);
    }

    // tap L: next choice · tap A: pick it · hold either: cancel
    private void choose(boolean a, boolean hold) {
        if (hold) {
            pick(0, "");
            home(0);
            return;
        }
        if (!a) {
            ci = (ci + 1) % choices.size();
            showChoice(false);
            return;
        }
        String c = choices.get(ci);
        pick(ci + 1, c);
        toast(Txt.seg(Txt.clean(c, 6)), 8);                      // the pick shoots off to the right
    }

    static final String FACES_ID = "pulse_faces", FACES_SLOT_ID = "pulse_faces_slot", UPDATE_ID = "pulse_update";
    private String[] slotNames = {"", "", ""};
    private String pendingFace = "";
    private String[] slotOrder = {"A", "B"};

    /**
     * FACES in the menu: the .js files in the Faces folder, each marked A / b if it's already in that
     * slot (the day digits). Picking one asks Forge A or B (showing what's there now), then installs it.
     */
    // ---------- PLAY: UNO against friends on the same Wi-Fi (the phone hosts, friends use a browser) ----------
    //
    // ---------- PLAY: UNO or Buckshot with friends (browsers on the same Wi-Fi, or online) ----------
    // Lobby: the link scrolls by, day digits = players. A start, hold A back to music (the party
    // keeps going), hold L end the party.
    // UNO, your turn: the top card (color + value, = for a wild) and your selected card, LAP lit if
    // it can be played. L next card, A play it, hold L draw (hold L again keeps it and passes).
    // Wild: COLOr r/G/b/y, L next color, A pick. Drop more (colon lit): A drops the next one of the
    // same number, hold L is done.
    // Buckshot: day digits = your lives and theirs. L cycles HIM / SELF / your items, A does it.
    // HR lit = sawed off (double damage), colon = they're cuffed. Adrenaline: L picks one of their
    // items (St...), A steals it, hold L cancels.
    // Battery: each move is ONE message; the watch plays the effect itself (streak, shake,
    // dissolve, send). Only the last 3 moves are shown when several land at once.

    static final String PLAY_ID = "pulse_play";
    private boolean inGame, gPick, gSteal;
    private int gSel, gColor, gItem;
    private final Runnable gRefresh = this::gameRender;

    private void openPlay() {
        if (Party.now != null) { enterPlay(); return; }
        onAsk(PLAY_ID, "", java.util.Arrays.asList("UnO", "bUCK"));
    }

    private void enterPlay() {
        inGame = true;
        gPick = gSteal = false;
        gAnim = false;
        gSeq = -1;
        gSel = 0;
        s = S.GAME;
        h.removeCallbacks(timeout);
        gameRender();
    }

    private void hostPlay(String kind) {
        Party p = Party.hostSaved(svc, kind);
        if (p == null) { toast("nO PArtY", 7); return; }
        enterPlay();
    }

    private static final class Step {
        final String main, day, alt;
        final int flags, fx, beep, ms;
        Step(String main, String day, int flags, int fx, int beep, String alt, int ms) {
            this.main = main; this.day = day; this.flags = flags; this.fx = fx; this.beep = beep; this.alt = alt; this.ms = ms;
        }
    }

    /** Something changed in the party (a join, a move): show each new move on the watch, then redraw. */
    void partyChanged() {
        Party p = Party.now;
        if (p == null) {
            if (inGame) { inGame = false; if (s == S.GAME) toast("PArtY End", 7); }
            return;
        }
        if (s != S.GAME) return;
        if (gAnim) { gMore = true; return; }                 // after the moves already showing
        List<Step> steps = new ArrayList<>();
        synchronized (p.lock) {
            Game g = p.game;
            if (g == null) { gSeq = -1; gameRender(); return; }
            if (gSeq < 0 || gSeq > g.seq()) gSeq = firstOfGame(g);   // a new game: from its start
            List<Game.Ev> fresh = new ArrayList<>();
            for (Game.Ev e : g.events()) if (e.seq > gSeq) fresh.add(e);
            if (!fresh.isEmpty()) gSeq = fresh.get(fresh.size() - 1).seq;
            if (fresh.size() > 3) fresh = fresh.subList(fresh.size() - 3, fresh.size());   // keep it short
            for (Game.Ev e : fresh) {
                if (g instanceof Uno) unoStep((Uno) g, e, steps); else buckStep((Buck) g, e, steps);
            }
        }
        if (steps.isEmpty()) { gameRender(); return; }
        gAnim = true;
        playSteps(steps, 0);
    }

    private static int firstOfGame(Game g) {
        int first = g.seq() - 1;
        for (Game.Ev e : g.events()) if ("start".equals(e.kind) || "load".equals(e.kind)) { first = e.seq - 1; break; }
        return first;
    }

    private static String who(int seat) { return seat == 0 ? "YOU" : "P" + (seat + 1); }

    private void unoStep(Uno u, Game.Ev e, List<Step> out) {
        String day = String.valueOf(u.hands.get(0).size());
        switch (e.kind) {
            case "start":
                out.add(new Step(topOf(e.a, Uno.colorOf(e.a)), day, 0, 5, 1, "", 1600));   // the deal: blocks dissolve to the top card
                break;
            case "play":
                if (Uno.value(e.a) >= Uno.WILD) {
                    out.add(new Step("COLOr" + Uno.COLORS.charAt(Math.max(0, e.to)), who(e.by), 0, 5, e.by == 0 ? 0 : 1, "", 1600));
                } else if (e.by == 0) {
                    out.add(new Step(Uno.seg(e.a), day, 0, 8, 0, "", 650));                 // yours shoots off
                } else {
                    out.add(new Step(Uno.seg(e.a) + " " + who(e.by), day, 0, 2, 1, "", 1200)); // theirs streaks in
                }
                break;
            case "penalty":
                out.add(new Step("+" + e.n + " " + who(e.to), day, 4, 7, 1, "", 1300));
                break;
            case "skip":
                out.add(new Step("SKIP" + (e.to == 0 ? "" : " " + (e.to + 1)), day, 0, 7, 0, "", 1300));
                break;
            case "reverse":
                out.add(new Step("rEV", day, 0, u.dir > 0 ? 1 : 2, 0, "", 1200));
                break;
            case "uno":
                out.add(new Step("UnO " + (e.by == 0 ? "" : "P" + (e.by + 1)), day, 4, 7, 1, "", 1400));
                break;
            default:
                break;                                                     // draws and passes: the redraw shows them
        }
    }

    private void buckStep(Buck b, Game.Ev e, List<Step> out) {
        String day = "" + b.hp[0] + b.hp[1];
        switch (e.kind) {
            case "load":
                out.add(new Step("L" + e.a + " b" + e.n, day, 0, 5, 1, "", 2200));          // how many live / blank
                break;
            case "shoot": {
                boolean self = e.to == e.by;
                if (e.by != 0) out.add(new Step(self ? "SELF" : "At YOU", day, 0, 2, 0, "", 1100));   // they aim...
                if (e.a == 1) out.add(new Step("bAnG", day, 4, 7, 1, (e.to == 0 ? "YOU" : "HIM") + "-" + e.n, 2200));
                else out.add(new Step("CLICK", day, 0, 0, 0, self ? "AGAIn" : "", 1500));
                break;
            }
            case "item": {
                String n = Buck.ITEMS[e.a];
                String alt = e.a == Buck.PIL ? (e.n > 0 ? "+2" : "-1") : e.a == Buck.CIG ? "+1" : "";
                if (e.a == Buck.SEE && e.by == 0) { int pk = b.peek(0); alt = pk == 1 ? "LIVE" : pk == 0 ? "bLAnK" : ""; }
                out.add(new Step(n, day, 0, e.by == 0 ? 8 : 2, e.by == 0 ? 0 : 1, alt, alt.isEmpty() ? 1100 : 2000));
                break;
            }
            case "steal":
                out.add(new Step("St" + Buck.ITEMS[e.a], day, 0, e.by == 0 ? 8 : 2, 0, "", 1100));
                break;
            case "eject":
                out.add(new Step("OUt " + (e.a == 1 ? "L" : "b"), day, 0, 1, 0, "", 1300));
                break;
            case "cuffed":
                out.add(new Step("CUFFd", e.to == 0 ? "" : day, 1, 7, 0, e.to == 0 ? "YOU" : "HIM", 1500));
                break;
            default:
                break;
        }
    }

    private void playSteps(final List<Step> steps, final int i) {
        if (s != S.GAME || i >= steps.size()) {
            gAnim = false;
            if (gMore) { gMore = false; partyChanged(); } else gameRender();
            return;
        }
        Step t = steps.get(i);
        text(t.main, t.day, t.flags, t.fx, t.beep, t.alt, 0);
        h.postDelayed(() -> playSteps(steps, i + 1), t.ms);
    }

    private boolean gAnim, gMore;
    private int gSeq = -1;

    /** The pile's top for the watch: color letter, then the value (= for a wild). */
    private static String topOf(int card, int color) {
        return "" + Uno.COLORS.charAt(Math.max(0, Math.min(3, color)))
                + (Uno.value(card) >= Uno.WILD ? "=" : Uno.seg(card).substring(1));
    }

    private void gameRender() {
        h.removeCallbacks(gRefresh);
        Party p = Party.now;
        if (p == null) { inGame = false; home(0); return; }
        if (gAnim) return;
        synchronized (p.lock) {
            if (p.game == null) { lobby(p); return; }
            if (p.game instanceof Buck) buckRender(p, (Buck) p.game); else unoRender((Uno) p.game);
        }
    }

    private void lobby(Party p) {
        String name = "buck".equals(p.kind) ? "bUCK" : "UnO";
        String web = p.online != null ? "  OnLInE " + p.code : "";
        String link = Party.address();
        String say = name + (link.isEmpty() ? (web.isEmpty() ? "  nO WIFI - tUrn On HOtSPOt" : web)
                : "  JOIn " + link + " " + Party.PORT + web) + (p.players.size() > 1 ? "  A StArt" : "");
        text(Txt.seg(say), String.valueOf(p.players.size()), 0, 0, 0, "", 0);
    }

    private void unoRender(Uno u) {
        int day = u.hands.get(0).size();
        if (u.over()) {
            text(u.winner == 0 ? "YOU WIn" : "P" + (u.winner + 1) + " WInS", "", u.winner == 0 ? 4 : 0, u.winner == 0 ? 5 : 7, 1, "", 0);
            return;
        }
        List<Integer> hand = u.hands.get(0);
        if (gSel >= hand.size()) gSel = 0;
        String top = topOf(u.top, u.color);
        if (u.turn == 0 && gPick) {
            text("COLOr" + Uno.COLORS.charAt(gColor), String.valueOf(day), 0, 0, 0, "", 0);
            return;
        }
        if (u.turn == 0 && u.combo >= 0 && !u.canPlay(hand.get(gSel))) {      // dropping more: jump to one that fits
            for (int i = 0; i < hand.size(); i++) if (u.canPlay(hand.get(i))) { gSel = i; break; }
        }
        int card = hand.get(gSel);
        if (u.turn == 0) {
            int flags = (u.canPlay(card) ? 2 : 0) | (u.combo >= 0 ? 1 : 0);     // LAP = playable, colon = drop more
            text(top + "  " + Uno.seg(card), String.valueOf(day), flags, 0, 0, "", 0);
        } else {
            text(top + " P" + (u.turn + 1), String.valueOf(day), 0, 0, 0, "", 0);
        }
    }

    /** Buckshot options on your turn: 0 shoot them, 1 shoot yourself, then your items. */
    private void buckRender(Party p, Buck b) {
        String day = "" + b.hp[0] + b.hp[1];
        if (b.winner >= 0) {
            text(b.winner == 0 ? "YOU LIVE" : "YOU dEd", day, b.winner == 0 ? 4 : 0, b.winner == 0 ? 5 : 7, 1, "", 0);
            return;
        }
        int flags = (b.saw ? 4 : 0) | (b.cuffed[1] ? 1 : 0);
        if (b.turn != 0) { text("tHEIr", day, flags, 0, 0, "", 0); return; }
        if (gSteal) {
            List<Integer> theirs = b.items.get(1);
            if (theirs.isEmpty()) { gSteal = false; } else {
                if (gItem >= theirs.size()) gItem = 0;
                text("St" + Buck.ITEMS[theirs.get(gItem)], day, flags, 0, 0, "", 0);
                return;
            }
        }
        List<Integer> mine = b.items.get(0);
        int n = 2 + mine.size();
        if (gSel >= n) gSel = 0;
        int pk = b.peek(0);
        String know = pk == 1 ? " L" : pk == 0 ? " b" : "";
        String label = gSel == 0 ? "HIM" + know : gSel == 1 ? "SELF" + know : Buck.ITEMS[mine.get(gSel - 2)];
        text(label, day, flags | (gSel < 2 ? 2 : 0), 0, 0, "", 0);   // LAP = a shot
    }

    private void gameKey(boolean a, boolean hold) {
        Party p = Party.now;
        if (p == null) { inGame = false; home(0); return; }
        if (hold && a) { inGame = false; home(0); return; }          // back to music; the party goes on
        if (gAnim) return;                                            // let the move finish
        Game g = p.game;
        if (g == null || g.winner() >= 0) {
            if (hold) { Party.end(); inGame = false; home(0); return; }   // hold L: end the party
            if (a) {
                String err = p.start();
                if (err != null) { toast(p.players.size() < 2 ? "WAIt" : "nO", 7); }
            }
            return;
        }
        if (g instanceof Buck) { buckKey(p, (Buck) g, a, hold); return; }
        Uno u = (Uno) g;
        List<Integer> hand = u.hands.get(0);
        if (u.turn != 0) {                                              // not your turn: L shows your cards
            if (!a && !hold && !hand.isEmpty()) {
                gSel = (gSel + 1) % hand.size();
                text(topOf(u.top, u.color) + "  " + Uno.seg(hand.get(gSel)), String.valueOf(hand.size()), 0, 0, 0, "", 0);
                h.removeCallbacks(gRefresh);
                h.postDelayed(gRefresh, 2000);
            }
            return;
        }
        if (gPick) {
            if (hold) { gPick = false; gameRender(); return; }
            if (!a) { gColor = (gColor + 1) % 4; gameRender(); return; }
            gPick = false;
            p.move(0, "play", gSel, gColor);
            gSel = 0;
            return;
        }
        if (hold) {                                                     // hold L: draw, keep and pass, or done
            if (u.combo >= 0) p.move(0, "done", -1, -1);
            else if (u.drew) p.move(0, "pass", -1, -1);
            else {
                int idx = p.draw(0);
                if (idx >= 0) gSel = idx;
            }
            return;
        }
        if (!a) { gSel = (gSel + 1) % hand.size(); gameRender(); return; }
        int card = hand.get(gSel);
        if (!u.canPlay(card)) { toast("nO", 7); return; }
        if (Uno.value(card) >= Uno.WILD) { gPick = true; gColor = 0; gameRender(); return; }
        p.move(0, "play", gSel, -1);
        gSel = 0;
    }

    private void buckKey(Party p, Buck b, boolean a, boolean hold) {
        if (b.turn != 0) return;
        if (gSteal) {
            if (hold) { gSteal = false; gameRender(); return; }
            if (!a) { gItem++; gameRender(); return; }
            gSteal = false;
            String err = p.move(0, "item", gSel - 2, gItem);
            if (err != null) toast("nO", 7);
            gSel = 0;
            return;
        }
        if (hold) { gSel = 0; gameRender(); return; }                   // hold L: back to HIM
        if (!a) { gSel++; gameRender(); return; }
        if (gSel < 2) { p.move(0, "shoot", gSel == 0 ? 1 : 0, -1); gSel = 0; return; }
        int it = b.items.get(0).get(gSel - 2);
        if (it == Buck.ADR) {
            if (b.items.get(1).isEmpty()) { toast("nOtHInG", 7); return; }
            gSteal = true; gItem = 0; gameRender(); return;
        }
        String err = p.move(0, "item", gSel - 2, -1);
        if (err != null) toast("nO", 7);
        gSel = 0;
    }

    private void openFaces() {
        if (FaceInstall.isBusy()) { toast("bUSY", 0); return; }
        final List<String> files = FaceInstall.list(svc, 12);
        if (files == null) { toast("nO dIr", 0); return; }
        if (files.isEmpty()) { toast("nO FILE", 0); return; }
        s = S.MSG;
        text("FACES", "", 0, 0, 0, "", 0);
        after(10000);
        FaceInstall.slotNames(svc, names -> {
            slotNames = names;
            if (s != S.MSG) return;                                    // left the menu meanwhile
            onAsk(FACES_ID, "", files);
        });
    }

    /** "A", "b" or "Ab" when this face is already in Forge A / B. */
    private String faceMark(String face) {
        String m = "";
        if (face.equalsIgnoreCase(slotNames[0])) m += "A";
        if (face.equalsIgnoreCase(slotNames[1])) m += "b";
        return m;
    }

    /** Second question: which slot, the app's chosen one first, each with what it holds now. */
    private void askSlot() {
        String first = svc.getSharedPreferences("pulse", 0).getString("faces_slot", "A");
        slotOrder = "B".equals(first) ? new String[]{"B", "A"} : new String[]{"A", "B"};
        List<String> list = new ArrayList<>();
        for (String sl : slotOrder) {
            String now = slotNames["A".equals(sl) ? 0 : 1];
            list.add(("A".equals(sl) ? "A " : "b ") + (now.isEmpty() ? "EMPtY" : now));
        }
        onAsk(FACES_SLOT_ID, "", list);
    }

    /** index 0 = cancelled or timed out. */
    private void pick(int index, String choice) {
        if (chooseId.isEmpty()) return;
        if (FACES_ID.equals(chooseId)) {                          // Pulse's own face list
            chooseId = "";
            if (index > 0) {
                pendingFace = choice;
                h.postDelayed(this::askSlot, 900);                    // after the pick's little animation
            }
            return;
        }
        if (PLAY_ID.equals(chooseId)) {                           // which game to host
            chooseId = "";
            if (index > 0) { final String kind = index == 2 ? "buck" : "uno"; h.postDelayed(() -> hostPlay(kind), 900); }
            return;
        }
        if (UPDATE_ID.equals(chooseId)) {                         // a newer Pulse face: UPdAtE / LAtEr
            chooseId = "";
            if (index == 1) h.postDelayed(svc::updatePulse, 900);
            else svc.updateLater();
            return;
        }
        if (FACES_SLOT_ID.equals(chooseId)) {                     // ...then A or B
            chooseId = "";
            if (index > 0 && index <= slotOrder.length) {
                final String file = pendingFace, slot = slotOrder[index - 1];
                h.postDelayed(() -> svc.runInstall(file, slot), 900);
            }
            return;
        }
        Intent i = new Intent("com.ollee.pulse.PICK");
        i.putExtra("id", chooseId);
        i.putExtra("index", String.valueOf(index));
        i.putExtra("choice", choice);
        svc.sendBroadcast(i);
        NotifService.lastButton = "Pick " + chooseId + " → " + (index == 0 ? "cancelled" : choice);
        chooseId = "";
    }

    // ---------- frame player ----------

    /** Plays a clip on the watch; it goes back to music by itself. False if the face isn't showing. */
    boolean play(Anim.Clip clip, int loops) {
        if (!faceOn || s == S.CALL || s == S.ASK || s == S.ASK_WAIT) return false;
        h.removeCallbacks(timeout);
        h.removeCallbacks(commitNow);
        s = S.MSG;
        send(clip.line(loops));
        return true;
    }

    // ---------- turn-by-turn ----------

    private String navLine = "";
    private char navDir;
    private long navHiddenUntil;
    private static final long NAV_PAUSE = 12000;       // how long a button press shows music instead
    private final Runnable navBack = this::navBack;

    /** Puts the turn back on the watch once nothing else is using its screen. */
    private void navBack() {
        if (!faceOn || s != S.MUSIC || navLine.isEmpty()) return;
        if (SystemClock.elapsedRealtime() < navHiddenUntil) return;
        h.removeCallbacks(timeout);
        s = S.NAV;
        send(navLine);
    }

    /** The next turn from Google Maps. A new turn brings the screen back and ticks. */
    String onNav(char dir, String main, String day) {
        boolean turn = dir != navDir;
        navDir = dir;
        // the last 1 = a quiet screen: the watch doesn't speed up for it and may stay at one frame a second.
        // Kept even when it can't be shown now, so the latest turn is ready when the screen is free.
        navLine = "T" + bar(main) + "|" + bar(day) + "|0|0|0||0|0|1";
        if (!faceOn) return "not sent: the Pulse face isn't showing on the watch";
        if (SystemClock.elapsedRealtime() < navHiddenUntil) return "not sent: a button was pressed (the turn returns in a few seconds)";
        if (s != S.MUSIC && s != S.NAV) return "not sent: the watch is on another Pulse screen (the turn follows when it closes)";
        h.removeCallbacks(timeout);
        s = S.NAV;
        send("T" + bar(main) + "|" + bar(day) + "|0|0|" + (turn ? 2 : 0) + "||0|0|1");
        return "sent to the watch";
    }

    void navEnd() {
        navLine = "";
        navDir = 0;
        navHiddenUntil = 0;
        h.removeCallbacks(navBack);
        if (s == S.NAV) home(0);
    }

    // ---------- calls ----------

    void onCall(String who) {
        h.removeCallbacks(timeout);
        h.removeCallbacks(commitNow);
        caller = who;
        s = S.CALL;
        if (faceOn) send("R" + bar(who));
        else link().sendLine("b");
    }

    void onCallEnd() {
        caller = null;
        if (s == S.CALL) {
            s = S.MUSIC;
            send("r");
        }
    }

    // ---------- volume ----------

    /** The phone's media volume changed; external = by the phone's own buttons. */
    void volume(int level, int max, boolean mute, boolean safe, boolean external) {
        boolean changed = level != vol || mute != muted;
        vol = level;
        volMax = max;
        muted = mute;
        if (s == S.VOLUME) {
            sendVolume(false, safe);
        } else if (external && changed && faceOn && s == S.MUSIC) {
            s = S.VOLUME;                  // pop the bar up for 2 s
            sendVolume(false, safe);
            after(2000);
        }
    }

    private void sendVolume(boolean open, boolean safe) {
        sendVolume(open, safe, false);
    }

    /** click = a short tick from the watch, for presses on the watch itself */
    private void sendVolume(boolean open, boolean safe, boolean click) {
        send("V" + vol + "|" + volMax + "|" + (muted ? 1 : 0) + "|" + (safe ? 1 : 0) + "|" + (open ? 1 : 0)
                + (click ? "|1" : ""));
    }

    private void openVolume() {
        if (!Features.on(svc, Features.VOLUME)) { toast("OFF", 7); return; }
        s = S.VOLUME;
        sendVolume(true, false);
        after(3000);
    }

    private void volumeKey(boolean a, boolean hold) {
        after(3000);
        if (hold) {
            if (a) svc.toggleMute();
            else home(0);
            return;
        }
        vol = Math.max(0, Math.min(volMax, vol + (a ? 1 : -1)));   // show it now, the phone confirms
        muted = false;
        sendVolume(false, false, true);
        svc.volume(a ? 1 : -1);
    }

    // ---------- YouTube search: typing ----------

    private void showTyping() {
        after(30000);
        String tail = qt.length() > 5 ? qt.substring(qt.length() - 5) : qt;
        tail = tail.replace(' ', '_');
        String day = qt.length() > 1 && !sg.isEmpty() ? String.valueOf(sg.size()) : "";
        String main;
        if (qp >= 0) main = tail + WH.charAt(qp);              // the letter on the wheel
        else if (qt.isEmpty()) main = "tYPE";
        else main = tail + "_";
        // typed text sits at the right edge, the newest letter last
        if (!qt.isEmpty() || qp >= 0) while (main.length() < 6) main = " " + main;
        text(Txt.seg(main), day, qp >= 0 ? 2 : 0, 0, 0, "", 0);
    }

    private void typing(boolean a, boolean hold) {
        h.removeCallbacks(commitNow);
        wantResults = false;
        h.removeCallbacks(resultsNow);
        if (!hold && a) {                                      // A: next letter
            qp = qp < 0 ? 1 : (qp + 1) % WH.length();
        } else if (!hold) {                                    // L: jump A F K P U 0 space
            int i = 0;
            while (i < STOPS.length && STOPS[i] <= qp) i++;
            qp = i < STOPS.length ? STOPS[i] : 0;
        } else if (a) {                                        // hold A: see suggestions
            commit();
            if (qt.trim().isEmpty()) return;
            if (!sg.isEmpty()) { showResults(); return; }
            wantResults = true;                                // wait for them (spinner, 4 s at most)
            send("W");
            h.removeCallbacks(suggestNow);
            fetchSuggestions();
            h.postDelayed(resultsNow, 4000);
            return;
        } else if (qp >= 0) {                                  // hold L: drop the wheel letter,
            qp = -1;
        } else if (!qt.isEmpty()) {                            // delete,
            qt = qt.substring(0, qt.length() - 1);
            suggestSoon();
        } else {                                               // or leave
            home(0);
            return;
        }
        if (qp >= 0) h.postDelayed(commitNow, 1000);           // 1 s without a press locks it in
        showTyping();
    }

    private void commit() {
        h.removeCallbacks(commitNow);
        if (qp < 0) return;
        if (qt.length() < 30) qt += qp == 0 ? " " : String.valueOf(WH.charAt(qp));
        qp = -1;
        suggestSoon();
        if (s == S.TYPING) showTyping();
    }

    private void suggestSoon() {
        querySeq++;
        sg = new ArrayList<>();
        h.removeCallbacks(suggestNow);
        h.postDelayed(suggestNow, 350);
    }

    private void fetchSuggestions() {
        final int seq = querySeq;
        svc.suggestFor(qt.trim(), list -> {
            if (seq != querySeq) return;           // an answer to old letters
            sg = list;
            if (s != S.TYPING) return;
            if (wantResults) {
                h.removeCallbacks(resultsNow);
                resultsNow.run();
            } else {
                showTyping();
            }
        });
    }

    // ---------- YouTube search: results and playing ----------

    private void showResults() {
        if (s == S.TYPING || rl.isEmpty()) {
            rl = new ArrayList<>(sg);
            if (rl.isEmpty()) rl.add(qt.trim().toLowerCase(Locale.US));
            si = 0;
        }
        s = S.RESULTS;
        text(Txt.seg(rl.get(si)), String.valueOf(si + 1), 0, 0, 0, "", 0);
        after(30000);
    }

    private void results(boolean a, boolean hold) {
        if (!a && hold) {                         // back to typing
            s = S.TYPING;
            rl = new ArrayList<>();
            showTyping();
            return;
        }
        if (!a) {
            si = (si + 1) % rl.size();
            text(Txt.seg(rl.get(si)), String.valueOf(si + 1), 0, 0, 0, "", 0);
            after(30000);
            return;
        }
        s = S.WAIT;
        text(Txt.seg(rl.get(si)), "", 0, 8, 0, "", 0);   // the title shoots off to the right
        send("W");                                // then the spinner while the phone looks
        after(20000);
        svc.playFor(rl.get(si), (ok, why) -> {
            if (s != S.WAIT) return;
            if (ok) home(6);                      // collapse, drop, the bars burst on
            else fail(why);
        });
    }

    private void fail(String why) {
        s = S.MSG;
        text(why, "", 0, 7, 1, "", 0);
        h.removeCallbacks(timeout);
        h.postDelayed(() -> { if (s == S.MSG) showResults(); }, 1600);
    }

    // ---------- Ask AI by voice ----------

    private int lvl;
    private final Runnable meter = this::meter;

    /** Listen for a question (earbuds' mic if connected). */
    private void startAsk() {
        if (!Features.on(svc, Features.ASK)) { toast("OFF", 7); return; }
        String why = svc.startVoice(new VoiceActivity.Ear() {
            @Override public void level(int l) { lvl = l; }
            @Override public void audio(byte[] wav) { onAudio(wav); }
            @Override public void heard(String text) { onHeard(text); }
            @Override public void failed(String w) { if (s == S.ASK) toast(Txt.seg(w), 7); }
        });
        if (why != null) { toast(why, 7); return; }
        s = S.ASK;
        lvl = 0;
        lowPower = lowBattery();
        lastBars = "";
        text("tALK", "AI", 0, 0, lowPower ? 0 : 1, "", 0);
        h.removeCallbacks(meter);
        if (!lowPower) h.postDelayed(meter, 900);
        after(45000);
    }

    // voice bars, drawn by the phone: louder = taller, middle highest. Each change is a radio
    // message to the watch, so only real changes are sent, at most every 400 ms; on a low
    // battery there are no bars at all (tALK just stays).
    private void meter() {
        if (s != S.ASK) return;
        StringBuilder b = new StringBuilder();
        int[] dist = {2, 1, 0, 0, 1, 2};
        for (int d : dist) b.append("_o88".charAt(Math.max(0, Math.min(3, lvl - d))));
        String bars = b.toString();
        if (!bars.equals(lastBars)) {
            lastBars = bars;
            text(bars, "AI", 0, 0, 0, "", 0);
        }
        h.postDelayed(meter, 400);
    }

    private String lastBars = "";
    /** Below this the coin cell sags when the radio is busy, and the watch can reset. */
    private static final float LOW_V = 2.85f;
    private boolean lowPower, photo, resumeAsk;
    private final Runnable forgetResume = () -> resumeAsk = false;

    private boolean lowBattery() {
        List<Battery.Reading> b = Battery.load(svc);
        return !b.isEmpty() && b.get(b.size() - 1).volts < LOW_V;
    }

    private interface Asker { void ask(Answers a); }

    // Pulse recorder: Gemini hears the recording; its row 1 is "Q <what it heard>"
    private void onAudio(byte[] wav) { begin(null, a -> svc.askAudio(wav, a)); }

    // phone speech service: the words are known already, so they show first as row 1
    private void onHeard(String text) {
        final String q = Txt.clean(text, 80);
        begin("Q " + q, a -> svc.askFor(q, a));
    }

    /** Text shared to Pulse from another app (the Gemini app...): shown like an AI answer. */
    void showShared(List<String> shared) {
        if (s == S.ASK) VoiceActivity.cancel();
        askSeq++;                                      // a question still running won't mix in
        h.removeCallbacks(meter);
        rows.clear();
        for (String r : shared) {
            String t = Txt.seg(Txt.clean(r, 60));
            if (!t.isEmpty()) rows.add(t);
        }
        if (rows.isEmpty()) return;
        aiDone = true;
        answered = rows.size();
        ri = 0;
        resumeAsk = false;
        saveAnswer();
        faceOn = true;                                 // sent from the phone: assume the face is on
        link().setFaceOn(true);
        lowPower = lowBattery();
        s = S.ASK_A;
        showRow(!lowPower);
    }

    /** A photo asked from the app: the watch shows the answer like a spoken question. */
    void askPhoto(byte[] jpeg, String q, Answers app) {
        if (s == S.ASK) VoiceActivity.cancel();
        // asked from the app, not the watch: Pulse may not have heard from the face since it
        // started, so assume it's on (if it isn't, the watch simply ignores the screens)
        faceOn = true;
        link().setFaceOn(true);
        lowPower = lowBattery();
        s = S.ASK;
        photo = true;
        begin(null, a -> svc.askImage(jpeg, q, new Answers() {
            @Override public void row(String r) { a.row(r); app.row(r); }
            @Override public void done(String why) { a.done(why); app.done(why); }
            @Override public void status(String st) { app.status(st); }
        }));
    }

    private void begin(String qRow, Asker asker) {
        h.removeCallbacks(meter);
        if (s != S.ASK) return;
        s = S.ASK_WAIT;
        text("ASK", "AI", 0, lowPower ? 0 : 8, 0, "", 0);   // the word shoots off to the right
        if (!lowPower) send("W");                       // the spinner keeps the watch busy
        after(photo ? 150000 : 80000);                  // room for Flash-Lite to step in
        photo = false;
        rows.clear();
        ri = 0;
        aiDone = false;
        answered = 0;
        final int ask = ++askSeq;
        if (qRow != null) addRow(qRow);
        asker.ask(new Answers() {
            @Override public void row(String r) {
                if (ask != askSeq) return;
                answered++;
                addRow(r);
            }
            @Override public void done(String why) {
                if (ask != askSeq) return;
                aiDone = true;
                if (answered > 0) saveAnswer();
                if (answered > 0 || (s != S.ASK_WAIT && s != S.ASK_A)) return;
                h.removeCallbacks(rowNext);
                toast(why != null ? why : "nO AnSWEr", 7);
            }
        });
    }

    private void addRow(String r) {
        String t = Txt.seg(Txt.clean(r, r.startsWith("Q ") ? 60 : 40));
        if (t.isEmpty()) return;
        rows.add(t);
        if (s == S.ASK_WAIT) {                         // the first row: show it right away
            s = S.ASK_A;
            ri = 0;
            showRow(!lowPower);
        }
    }

    private final List<String> rows = new ArrayList<>();
    private int ri, askSeq, answered;
    private boolean aiDone;
    private final Runnable rowNext = this::nextRow;

    // ---------- the last answer, kept to reopen from the menu ----------

    private boolean hasAnswer() {
        return !rows.isEmpty() || !prefs().getString("lastans", "").isEmpty();
    }

    private void saveAnswer() {
        StringBuilder b = new StringBuilder();
        for (String r : rows) b.append(b.length() > 0 ? "\n" : "").append(r);
        prefs().edit().putString("lastans", b.toString()).apply();
    }

    /** Back to the last answer, at the row you were on (or row 1 after a restart). */
    private void openAnswer() {
        if (rows.isEmpty()) {
            String saved = prefs().getString("lastans", "");
            if (saved.isEmpty()) { toast("nOnE", 0); return; }
            for (String r : saved.split("\n")) if (!r.isEmpty()) rows.add(r);
            ri = 0;
            aiDone = true;
        }
        if (ri >= rows.size()) ri = 0;
        s = S.ASK_A;
        showRow(false);
    }

    private android.content.SharedPreferences prefs() {
        return svc.getSharedPreferences("pulse", android.content.Context.MODE_PRIVATE);
    }

    // kept so a waiting row can show once it arrives
    private void nextRow() {
        if (s == S.ASK_A) showRow(false);
    }

    /** How slowly answers scroll: the app's "Answer scroll" setting. */
    private int answerSpeed() {
        int n = prefs().getInt("ans_speed", 1);
        return n <= 0 ? 4 : n == 1 ? 7 : 10;
    }

    // one row of the answer; it scrolls round and round until you move on
    private void showRow(boolean beep) {
        String t = rows.get(ri);
        text(t, String.valueOf(ri + 1), 0, 0, beep ? 1 : 0, "", 0, answerSpeed());
        h.removeCallbacks(rowNext);
        after(300000);                                 // 5 minutes untouched: back to music
    }

    // A: next row · L: previous row · hold A: ask again by voice · hold L: music
    private void askA(boolean a, boolean hold) {
        h.removeCallbacks(rowNext);
        if (hold) { if (a) startAsk(); else home(0); return; }
        if (a) {
            if (ri + 1 >= rows.size()) {
                if (!aiDone) { toastRow("WAIt"); return; }   // the next row is still being written
                ri = 0;                                       // the end: back to the first row
            } else ri++;
        } else {
            ri = ri > 0 ? ri - 1 : rows.size() - 1;
        }
        showRow(false);
    }

    // a word for a moment, then back to the row
    private void toastRow(String w) {
        text(w, String.valueOf(ri + 1), 0, 0, 0, "", 0);
        h.postDelayed(rowNext, 900);
    }

    // ---------- macros ----------

    private void openMacros() {
        slots = Macros.active(svc);
        if (slots.isEmpty()) { toast("nOnE", 0); return; }
        quick = false;
        ms = 0;
        showMacros();
    }

    private void showMacros() {
        s = S.MACROS;
        Macros.Slot m = slots.get(Math.min(ms, slots.size() - 1));
        text(Txt.seg(m.name), String.valueOf(m.number), 0, 0, 0, "", 0);
        after(20000);
    }

    private void macros(boolean a, boolean hold) {
        if (!a) {
            if (hold) { home(0); return; }
            ms = (ms + 1) % slots.size();
            showMacros();
            return;
        }
        if (hold) {                               // A runs the slot with a tap; hold A on a running
            if (recStart > 0 && Macros.RECORD.equals(slots.get(ms).kind)) showRec(false);   // recorder: its timer
            return;
        }
        run(slots.get(ms));
    }

    private void run(Macros.Slot m) {
        if (Macros.RECORD.equals(m.kind)) { record(m); return; }
        s = S.MACRO_RUN;
        text(Txt.seg(m.name), "", 0, 8, 0, "", 0);   // the name shoots off to the right
        send("W");
        if (Macros.STATUS.equals(m.kind)) { h.postDelayed(this::status, 500); return; }
        if (Macros.NEXT.equals(m.kind)) { h.postDelayed(this::nextEvent, 500); return; }
        after(6000);
        Intent i = new Intent("com.ollee.pulse.MACRO");
        i.putExtra("slot", String.valueOf(m.number));
        i.putExtra("name", m.name);
        svc.sendBroadcast(i);
        lastRun = SystemClock.elapsedRealtime();
        NotifService.lastButton = "Macro " + m.number + " (" + m.name + ") → MacroDroid";
    }

    /**
     * The app's macro wheel ran a slot. With the Pulse face open it runs just as from the watch
     * (the name flies across the watch). Otherwise MacroDroid slots still run; Pulse's own kinds
     * need the face, because they answer on the watch. Returns what happened, for the app.
     */
    String runFromPhone(Macros.Slot m) {
        if (faceOn && (s == S.MUSIC || s == S.MACROS)) {
            run(m);
            return m.name + ": running, watch too";
        }
        if (!Macros.MACRODROID.equals(m.kind)) return m.name + " answers on the watch: open the Pulse face first";
        Intent i = new Intent("com.ollee.pulse.MACRO");
        i.putExtra("slot", String.valueOf(m.number));
        i.putExtra("name", m.name);
        svc.sendBroadcast(i);
        lastRun = SystemClock.elapsedRealtime();
        NotifService.lastButton = "Macro " + m.number + " (" + m.name + ") \u2192 MacroDroid, from the app";
        return m.name + ": sent to MacroDroid";
    }

    /** MacroDroid answered (com.ollee.pulse.SHOW): show it, or treat it as a notification. */
    void onShow(String text) {
        String t = Txt.seg(Txt.clean(text, 40));
        if (t.isEmpty()) return;
        if (s == S.REC) return;                   // the recorder's timer stays up
        if (s == S.MACRO_RUN) { macroReply(t, 0); return; }
        // a slow answer (say, a balance SMS) to a macro run a moment ago: show it now,
        // unless a call or a search is on the screen
        if (faceOn && recentMacro() && s != S.CALL && s != S.TYPING && s != S.RESULTS && s != S.WAIT) {
            h.removeCallbacks(timeout);
            h.removeCallbacks(commitNow);
            s = S.MSG;
            text(t, "", 0, 0, 1, "", 0);
            h.postDelayed(() -> { if (s == S.MSG) home(0); }, Math.max(4000, readTime(t)));
            return;
        }
        onNotification("MACrO", "", t);
    }

    /** A face install's progress (SEnd 40, dOnE...): stays up while it runs, then back home. */
    void installMsg(String t, boolean over) {
        if (!faceOn) return;
        t = Txt.seg(Txt.clean(t, 40));
        if (t.isEmpty()) return;
        h.removeCallbacks(timeout);
        h.removeCallbacks(commitNow);
        h.removeCallbacks(installHome);
        s = S.MSG;
        text(t, "", 0, 0, over ? 1 : 0, "", 0);
        h.postDelayed(installHome, over ? Math.max(4000, readTime(t)) : 30000);
    }

    private final Runnable installHome = () -> { if (s == S.MSG) home(0); };

    /** A MacroDroid slot ran in the last 2 minutes (its reply may still be coming). */
    boolean recentMacro() {
        return lastRun >= 0 && SystemClock.elapsedRealtime() - lastRun < LATE;
    }

    private void macroReply(String t, int fx) {
        h.removeCallbacks(timeout);
        text(t, "", 0, fx, 1, "", 0);
        s = S.MSG;
        h.postDelayed(() -> { if (s == S.MSG) backToMacros(); }, readTime(t));
    }

    /** ms to leave a reply up: one pass of the scroll (0.2 s a letter) plus a second, 3 s at least. */
    private static long readTime(String t) {
        return t.length() > 6 ? (t.length() + 7) * 200L + 1000 : 3000;
    }

    // built-in: phone battery and temperature
    private void status() {
        Intent b = svc.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) { macroReply("nO dAtA", 7); return; }
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        h.removeCallbacks(timeout);
        s = S.MSG;
        text("PH " + Math.round(level * 100f / scale), "", 0, 0, 1, Math.round(temp / 10f) + "C", 0);
        h.postDelayed(() -> { if (s == S.MSG) backToMacros(); }, 4000);
    }

    // built-in: the next calendar event (classes, exams, assignments...)
    private void nextEvent() {
        evs.clear();
        String fail = null;
        try {
            long now = System.currentTimeMillis();
            Uri.Builder u = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(u, now);
            ContentUris.appendId(u, now + 14L * 86400000L);
            Cursor c = svc.getContentResolver().query(u.build(),
                    new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.ALL_DAY},
                    CalendarContract.Instances.VISIBLE + "=1",      // only calendars shown in the app
                    null, CalendarContract.Instances.BEGIN + " ASC");
            if (c != null) {
                try {
                    while (c.moveToNext() && evs.size() < 5) {
                        long begin = c.getLong(1);
                        boolean allDay = c.getInt(2) == 1;
                        if (!allDay && begin < now) continue;   // already started
                        String title = Txt.clean(c.getString(0), 30);
                        evs.add(event(begin, allDay, title.isEmpty() ? "EVEnt" : title));
                    }
                } finally {
                    c.close();
                }
            }
            if (evs.isEmpty()) fail = "nO EVEnt";
        } catch (SecurityException e) {
            fail = "CAL?";                                    // calendar permission missing
        } catch (Exception e) {
            fail = "nO CAL";
        }
        if (fail != null) { macroReply(Txt.seg(fail), 0); return; }
        ei = 0;
        showEvent(true);
    }

    /** Shows item ei from its first step (day), beeping only for the first item. */
    private void showEvent(boolean beep) {
        s = S.EVENTS;
        step = 0;
        h.removeCallbacks(evNext);
        evShow(beep);
        after(45000);
    }

    // day (still) -> time (still, like the clock) -> title (scrolls once) -> again
    private void evShow(boolean beep) {
        Ev e = evs.get(ei);
        String n = String.valueOf(ei + 1);
        long wait;
        if (step == 0) {
            text(e.day, n, 0, 0, beep ? 1 : 0, "", 0);
            wait = 1500;
        } else if (step == 1) {
            text(e.time, n, 1 | (e.pm ? 8 : 0), 0, 0, "", 0);   // colon, and PM in the afternoon
            wait = 2000;
        } else {
            text(e.title, n, 0, 0, 0, "", 0);
            wait = e.title.length() > 6 ? (e.title.length() + 7) * 200L : 2000;   // one pass of the scroll
        }
        h.postDelayed(evNext, wait);
    }

    private void evStep() {
        if (s != S.EVENTS) return;
        Ev e = evs.get(ei);
        step = step == 0 && e.time == null ? 2 : (step + 1) % 3;   // all-day items have no time
        evShow(false);
    }

    // tap L: the next item (after the last: back to the list) · tap A: back to the list · hold L: music
    private void events(boolean a, boolean hold) {
        h.removeCallbacks(evNext);
        if (hold) {
            if (!a) home(0);
            else evShow(false);
            return;
        }
        if (a || ++ei >= evs.size()) { backToMacros(); return; }
        showEvent(false);
    }

    private static final String[] DAYS = {"SUn", "MOn", "tUE", "WEd", "tHU", "FrI", "SAt"},
            MONTHS = {"JAn", "FEb", "MAr", "APr", "MAY", "JUn", "JUL", "AUG", "SEP", "OCt", "nOV", "dEC"};

    /** tOdAY / tMrW / weekday / "nOV 20", then " 945" style time (shown with the colon), then the title. */
    private static Ev event(long t, boolean allDay, String title) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (allDay) {                                   // all-day items are stored at midnight UTC
            Calendar u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            u.setTimeInMillis(t);
            c.clear();
            c.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH));
        }
        Calendar d0 = (Calendar) now.clone(), d1 = (Calendar) c.clone();
        for (Calendar x : new Calendar[]{d0, d1}) {
            x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0);
            x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0);
        }
        long dd = Math.round((d1.getTimeInMillis() - d0.getTimeInMillis()) / 86400000.0);
        String day = dd <= 0 ? "tOdAY" : dd == 1 ? "tMrW" : dd < 7 ? DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
                : MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.DAY_OF_MONTH);
        String time = null;
        boolean pm = false;
        if (!allDay) {
            int hr = c.get(Calendar.HOUR_OF_DAY);
            pm = hr >= 12;
            int h12 = hr % 12 == 0 ? 12 : hr % 12;
            time = (h12 < 10 ? " " : "") + h12 + String.format(Locale.US, "%02d", c.get(Calendar.MINUTE)) + "  ";
        }
        return new Ev(Txt.seg(day), time, pm, Txt.seg(title));
    }
}
