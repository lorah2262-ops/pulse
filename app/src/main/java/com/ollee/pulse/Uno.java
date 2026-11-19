package com.ollee.pulse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * UNO for 2 to 4 players, run by the host phone. Every player (the host's watch, a friend's
 * browser) only sends moves; this decides what's allowed.
 *
 * A card is color * 16 + value. Colors 0 red, 1 green, 2 blue, 3 yellow, 4 wild.
 * Values 0-9, 10 skip, 11 reverse, 12 draw two, 13 wild, 14 wild draw four.
 * With two players, reverse works like skip.
 *
 * Drop more: after playing a card you may drop more cards with the same number or symbol in the
 * same turn (any color, the last one sets the color). Their effects add up: two +2 = +4.
 * The turn ends by itself when you have no more of that number, or when you say done.
 *
 * Every move is logged as an event (seq, who, what, card, count) so screens can animate it.
 */
final class Uno implements Game {
    static final int SKIP = 10, REV = 11, DRAW2 = 12, WILD = 13, WILD4 = 14;
    static final String COLORS = "rGby";
    static final String[] COLOR_NAMES = {"red", "green", "blue", "yellow"};

    // events: start (a = top card), play (a = card, to = color), draw, pass, penalty (n cards, to = who),
    // skip (n, to), reverse, uno, win

    final int players;
    final List<List<Integer>> hands = new ArrayList<>();
    final List<Integer> pile = new ArrayList<>();      // played cards, the newest last (the top is not in it)
    final List<Game.Ev> events = new ArrayList<>();
    private final List<Integer> deck = new ArrayList<>();
    private final Random rnd = new Random();
    int top, color, turn, dir = 1, winner = -1, seq;
    boolean drew;                 // the player on turn drew a card this turn (may play it, or pass)
    int combo = -1;               // the value being dropped this turn, or -1
    private int pendDraw, pendSkip;
    String last = "";             // short words for the watch
    int lastBy = -1;

    Uno(int players) {
        this.players = players;
        for (int c = 0; c < 4; c++) {
            deck.add(c * 16);
            for (int v = 1; v <= 12; v++) { deck.add(c * 16 + v); deck.add(c * 16 + v); }
        }
        for (int i = 0; i < 4; i++) { deck.add(4 * 16 + WILD); deck.add(4 * 16 + WILD4); }
        Collections.shuffle(deck, rnd);
        for (int p = 0; p < players; p++) {
            List<Integer> h = new ArrayList<>();
            for (int i = 0; i < 7; i++) h.add(take());
            Collections.sort(h);
            hands.add(h);
        }
        do { top = take(); if (value(top) > 9) { pile.add(top); top = -1; } } while (top < 0);
        color = top >> 4;
        turn = rnd.nextInt(players);
        last = "START";
        log(turn, "start", top, 0, -1);
    }

    static int value(int card) { return card & 15; }

    static int colorOf(int card) { return card >> 4; }

    /** Two letters for the segment screen: r5, GS (skip), bU (reverse), yd (+2); wild 88, wild four 84. */
    static String seg(int card) {
        int v = value(card);
        if (v == WILD) return "88";
        if (v == WILD4) return "84";
        char c = COLORS.charAt(colorOf(card));
        return "" + c + (v < 10 ? (char) ('0' + v) : v == SKIP ? 'S' : v == REV ? 'U' : 'd');
    }

    /** Words for the browser: "Red 5", "Green Skip", "Wild +4". */
    static String name(int card) {
        int v = value(card);
        if (v == WILD) return "Wild";
        if (v == WILD4) return "Wild +4";
        String c = Character.toUpperCase(COLOR_NAMES[colorOf(card)].charAt(0)) + COLOR_NAMES[colorOf(card)].substring(1);
        return c + " " + (v < 10 ? String.valueOf(v) : v == SKIP ? "Skip" : v == REV ? "Reverse" : "+2");
    }

    private void log(int by, String kind, int card, int count, int to) {
        events.add(new Game.Ev(++seq, by, kind, card, count, to));
        if (events.size() > 40) events.remove(0);
    }

    private int take() {
        if (deck.isEmpty()) {                     // the played cards go back in
            deck.addAll(pile);
            pile.clear();
            Collections.shuffle(deck, rnd);
        }
        if (deck.isEmpty()) return 4 * 16 + WILD;
        return deck.remove(deck.size() - 1);
    }

    /** Can this card go down now (for the player on turn)? */
    boolean canPlay(int card) {
        int v = value(card);
        if (combo >= 0) return v == combo;                      // dropping more of the same
        return v == WILD || v == WILD4 || colorOf(card) == color || v == value(top) && colorOf(top) < 4;
    }

    boolean over() { return winner >= 0; }

    private int next(int from, int steps) {
        return ((from + dir * steps) % players + players) % players;
    }

    private boolean hasValue(List<Integer> h, int v) {
        for (int c : h) if (value(c) == v) return true;
        return false;
    }

    /** Plays hand[index]; for a wild, chosen = the new color 0-3. Null if done, else why not. */
    String play(int p, int index, int chosen) {
        if (over()) return "The game is over";
        if (p != turn) return "Not your turn";
        List<Integer> h = hands.get(p);
        if (index < 0 || index >= h.size()) return "No such card";
        int card = h.get(index);
        if (!canPlay(card)) return combo >= 0 ? "Only more of the same number now" : "That card doesn't match";
        int v = value(card);
        if ((v == WILD || v == WILD4) && (chosen < 0 || chosen > 3)) return "Pick a color";
        h.remove(index);
        pile.add(top);
        top = card;
        color = v >= WILD ? chosen : colorOf(card);
        drew = false;
        lastBy = p;
        last = seg(card) + (v >= WILD ? " " + COLORS.charAt(color) : "");
        log(p, "play", card, 0, color);
        if (v == SKIP) pendSkip++;
        else if (v == REV) { if (players == 2) pendSkip++; else dir = -dir; log(p, "reverse", card, 0, -1); }
        else if (v == DRAW2) pendDraw += 2;
        else if (v == WILD4) pendDraw += 4;
        if (h.size() == 1) log(p, "uno", -1, 0, -1);
        if (h.isEmpty()) { winner = p; finishEffects(p); log(p, "win", -1, 0, -1); return null; }
        if (hasValue(h, v)) { combo = v; return null; }          // more of the same can follow
        endTurn(p);
        return null;
    }

    /** Ends a drop-more turn. */
    String done(int p) {
        if (p != turn || combo < 0) return "Nothing to finish";
        endTurn(p);
        return null;
    }

    private void finishEffects(int p) {
        if (pendDraw > 0) {
            int victim = next(p, 1);
            for (int i = 0; i < pendDraw; i++) hands.get(victim).add(take());
            Collections.sort(hands.get(victim));
            log(p, "penalty", -1, pendDraw, victim);
        }
    }

    private void endTurn(int p) {
        combo = -1;
        int steps = 1;
        if (pendDraw > 0) { finishEffects(p); steps = 2; }
        if (pendSkip > 0) {
            int victim = next(p, 1);
            if (pendDraw == 0) log(p, "skip", -1, pendSkip, victim);
            steps = Math.max(steps, 1 + Math.min(pendSkip, players - 1));   // with 2 players: you go again
        }
        pendDraw = 0;
        pendSkip = 0;
        turn = next(p, steps);
    }

    /** Draws one card. Returns its index in the hand (sorted), or -1 if not allowed. */
    int draw(int p) {
        if (over() || p != turn || drew || combo >= 0) return -1;
        int card = take();
        List<Integer> h = hands.get(p);
        h.add(card);
        Collections.sort(h);
        drew = true;
        lastBy = p;
        last = "drAW";
        log(p, "draw", -1, 1, p);
        if (!canPlay(card)) { drew = false; turn = next(p, 1); }     // nothing to play: the turn passes
        return h.indexOf(card);
    }

    /** After drawing a card that could be played: keep it and pass. */
    String pass(int p) {
        if (p != turn || !drew) return "You can pass only after drawing";
        drew = false;
        lastBy = p;
        last = "PASS";
        log(p, "pass", -1, 0, -1);
        turn = next(p, 1);
        return null;
    }

    // ---------- as a party game ----------

    @Override public String kind() { return "uno"; }

    @Override public int seq() { return seq; }

    @Override public int turn() { return turn; }

    @Override public int winner() { return winner; }

    @Override public List<Game.Ev> events() { return events; }

    @Override
    public String move(int seat, String what, int i, int c) {
        if ("play".equals(what)) return play(seat, i, c);
        if ("draw".equals(what)) return draw(seat) < 0 ? "You can't draw now" : null;
        if ("pass".equals(what)) return pass(seat);
        if ("done".equals(what)) return done(seat);
        return "Unknown move";
    }

    private String cardJson(int c) {
        return "{\"c\":" + c + ",\"n\":\"" + name(c) + "\",\"ok\":" + canPlay(c) + "}";
    }

    @Override
    public void json(StringBuilder b, int seat) {
        b.append(",\"top\":").append(cardJson(top)).append(",\"color\":").append(color)
                .append(",\"dir\":").append(dir).append(",\"combo\":").append(turn == seat ? combo : -1)
                .append(",\"drew\":").append(drew && turn == seat).append(",\"under\":[");
        for (int i = Math.max(0, pile.size() - 3); i < pile.size(); i++) {
            if (i > Math.max(0, pile.size() - 3)) b.append(',');
            b.append(pile.get(i));
        }
        b.append("],\"counts\":[");
        for (int i = 0; i < players; i++) b.append(i > 0 ? "," : "").append(hands.get(i).size());
        b.append("],\"hand\":[");
        List<Integer> h = hands.get(seat);
        for (int i = 0; i < h.size(); i++) b.append(i > 0 ? "," : "").append(cardJson(h.get(i)));
        b.append(']');
    }
}
