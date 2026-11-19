package com.ollee.pulse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Buckshot for two, with the rules of the watch game (bUCK.js, 2-player mode): 4 lives each,
 * 6 shells per load (2 to 4 live), 2 new items each per load (at most 4 held).
 * On your turn shoot the other player or yourself, or use an item first. A blank on yourself
 * keeps your turn. Whoever reaches 0 lives loses.
 *
 * Items: 0 SEE look at the shell in the chamber · 1 CUF the other player skips a turn ·
 * 2 bEr rack out the shell (everyone sees it) · 3 CIG +1 life · 4 SAW the next hit does 2 ·
 * 5 rEV flip the shell in the chamber · 6 Adr steal one of their items and use it now ·
 * 7 PIL 50/50: +2 lives or -1 · 8 InV flip every shell left.
 */
final class Buck implements Game {
    static final String[] ITEMS = {"SEE", "CUF", "bEr", "CIG", "SAW", "rEV", "Adr", "PIL", "InV"};
    static final String[] NAMES = {"Magnifier", "Handcuffs", "Beer", "Cigarettes", "Hand saw", "Inverter",
            "Adrenaline", "Pills", "Twist"};
    static final int SEE = 0, CUF = 1, BEER = 2, CIG = 3, SAW = 4, REV = 5, ADR = 6, PIL = 7, INV = 8;
    static final int MAX_HP = 4, MAX_ITEMS = 4, SHELLS = 6;

    // events: load (a = live, n = blank), shoot (to = target, a = 1 live, n = damage), eject (a = 1 live),
    // item (a = item, n = extra: pills +2 / -1), steal (a = item, to = from), cuffed (to = who skips), win

    final int[] hp = {MAX_HP, MAX_HP};
    final List<List<Integer>> items = new ArrayList<>();
    final boolean[] cuffed = new boolean[2];
    private final boolean[] shells = new boolean[SHELLS];
    private final int[] knowAt = {-1, -1};
    private final boolean[] knowLive = new boolean[2];
    private final List<Game.Ev> events = new ArrayList<>();
    private final Random rnd = new Random();
    int at, turn, winner = -1, seq, loadLive, loadBlank;
    boolean saw;

    Buck() {
        items.add(new ArrayList<>());
        items.add(new ArrayList<>());
        turn = rnd.nextInt(2);
        load();
    }

    private void log(int by, String kind, int a, int n, int to) {
        events.add(new Game.Ev(++seq, by, kind, a, n, to));
        if (events.size() > 40) events.remove(0);
    }

    /** A fresh load: shells shuffled, everyone told how many are live, new items handed out. */
    private void load() {
        int live = 2 + rnd.nextInt(3);
        List<Boolean> s = new ArrayList<>();
        for (int i = 0; i < SHELLS; i++) s.add(i < live);
        Collections.shuffle(s, rnd);
        for (int i = 0; i < SHELLS; i++) shells[i] = s.get(i);
        at = 0;
        loadLive = live;
        loadBlank = SHELLS - live;
        knowAt[0] = knowAt[1] = -1;
        for (int p = 0; p < 2; p++)
            for (int k = 0; k < 2 && items.get(p).size() < MAX_ITEMS; k++) items.get(p).add(rnd.nextInt(ITEMS.length));
        log(turn, "load", live, SHELLS - live, -1);
    }

    int shellsLeft() { return SHELLS - at; }

    /** What this seat knows about the shell in the chamber: 1 live, 0 blank, -1 unknown. */
    int peek(int seat) { return knowAt[seat] == at ? (knowLive[seat] ? 1 : 0) : -1; }

    private void forget() { knowAt[0] = knowAt[1] = -1; }

    private void advance() {
        at++;
        if (at >= SHELLS && winner < 0) load();
    }

    private void nextTurn(int p) {
        int o = 1 - p;
        if (cuffed[o]) {                        // cuffed: they sit this one out
            cuffed[o] = false;
            log(p, "cuffed", 0, 0, o);
            turn = p;
        } else {
            turn = o;
        }
    }

    String shoot(int p, int target) {
        boolean live = shells[at];
        int dmg = live ? (saw ? 2 : 1) : 0;
        saw = false;
        if (live) hp[target] = Math.max(0, hp[target] - dmg);
        log(p, "shoot", live ? 1 : 0, dmg, target);
        if (hp[target] <= 0) {
            winner = 1 - target;
            log(winner, "win", 0, 0, -1);
            return null;
        }
        boolean again = !live && target == p;   // a blank on yourself: shoot again
        at++;
        if (again) turn = p; else nextTurn(p);
        if (at >= SHELLS) load();
        return null;
    }

    /** Uses an item; for adrenaline, steal = the index of the item taken from the other player. */
    String use(int p, int index, int steal) {
        List<Integer> mine = items.get(p);
        if (index < 0 || index >= mine.size()) return "No such item";
        int it = mine.get(index);
        String err;
        if (it == ADR) {
            List<Integer> theirs = items.get(1 - p);
            if (steal < 0 || steal >= theirs.size()) return "Pick one of their items to steal";
            int got = theirs.get(steal);
            if (got == ADR) return "You can't steal adrenaline";
            err = check(p, got);
            if (err != null) return err;
            mine.remove(index);
            theirs.remove(steal);
            log(p, "steal", got, 0, 1 - p);
            apply(p, got);
            return null;
        }
        err = check(p, it);
        if (err != null) return err;
        mine.remove(index);
        apply(p, it);
        return null;
    }

    private String check(int p, int it) {
        if (it == CUF && cuffed[1 - p]) return "They're already cuffed";
        if (it == SAW && saw) return "The saw is already on";
        return null;
    }

    private void apply(int p, int it) {
        switch (it) {
            case SEE:
                knowAt[p] = at;
                knowLive[p] = shells[at];
                log(p, "item", it, 0, -1);
                break;
            case CUF:
                cuffed[1 - p] = true;
                log(p, "item", it, 0, 1 - p);
                break;
            case BEER: {
                boolean live = shells[at];
                log(p, "item", it, 0, -1);
                log(p, "eject", live ? 1 : 0, 0, -1);
                forget();
                advance();
                break;
            }
            case CIG:
                hp[p] = Math.min(MAX_HP, hp[p] + 1);
                log(p, "item", it, 1, -1);
                break;
            case SAW:
                saw = true;
                log(p, "item", it, 0, -1);
                break;
            case REV:
                shells[at] = !shells[at];
                if (knowAt[p] == at) knowLive[p] = shells[at];
                if (knowAt[1 - p] == at) knowAt[1 - p] = -1;
                log(p, "item", it, 0, -1);
                break;
            case PIL: {
                boolean good = rnd.nextBoolean();
                hp[p] = good ? Math.min(MAX_HP, hp[p] + 2) : Math.max(0, hp[p] - 1);
                log(p, "item", it, good ? 2 : -1, -1);
                if (hp[p] <= 0) { winner = 1 - p; log(winner, "win", 0, 0, -1); }
                break;
            }
            case INV:
                for (int i = at; i < SHELLS; i++) shells[i] = !shells[i];
                forget();
                log(p, "item", it, 0, -1);
                break;
            default:
                break;
        }
    }

    // ---------- as a party game ----------

    @Override public String kind() { return "buck"; }

    @Override public int seq() { return seq; }

    @Override public int turn() { return turn; }

    @Override public int winner() { return winner; }

    @Override public List<Game.Ev> events() { return events; }

    /** "shoot" with i 0 = yourself, 1 = them; "item" with i = which item, c = what to steal. */
    @Override
    public String move(int seat, String what, int i, int c) {
        if (winner >= 0) return "The game is over";
        if (seat != turn) return "Not your turn";
        if ("shoot".equals(what)) return shoot(seat, i == 0 ? seat : 1 - seat);
        if ("item".equals(what)) return use(seat, i, c);
        return "Unknown move";
    }

    @Override
    public void json(StringBuilder b, int seat) {
        b.append(",\"hp\":[").append(hp[0]).append(',').append(hp[1]).append("],\"max\":").append(MAX_HP)
                .append(",\"cuffed\":[").append(cuffed[0]).append(',').append(cuffed[1]).append(']')
                .append(",\"saw\":").append(saw).append(",\"left\":").append(shellsLeft())
                .append(",\"loadLive\":").append(loadLive).append(",\"loadBlank\":").append(loadBlank)
                .append(",\"peek\":").append(peek(seat)).append(",\"items\":[");
        for (int p = 0; p < 2; p++) {
            b.append(p > 0 ? ",[" : "[");
            List<Integer> l = items.get(p);
            for (int k = 0; k < l.size(); k++) b.append(k > 0 ? "," : "").append(l.get(k));
            b.append(']');
        }
        b.append(']');
    }
}
