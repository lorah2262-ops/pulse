package com.ollee.pulse;

import java.util.List;

/** A party game run by the host phone (UNO or Buckshot). Players only send moves. */
interface Game {

    /** One thing that happened, for screens to animate. a / n / to mean what each kind says. */
    final class Ev {
        final int seq, by, a, n, to;
        final String kind;

        Ev(int seq, int by, String kind, int a, int n, int to) {
            this.seq = seq; this.by = by; this.kind = kind; this.a = a; this.n = n; this.to = to;
        }
    }

    String kind();

    int seq();

    int turn();

    int winner();

    List<Ev> events();

    /** A move from a seat. Null if done, else why not. */
    String move(int seat, String what, int i, int c);

    /** The game's part of a seat's view, as JSON fields (each starting with a comma). */
    void json(StringBuilder b, int seat);
}
