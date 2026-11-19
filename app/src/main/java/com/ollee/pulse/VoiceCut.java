package com.ollee.pulse;

import java.io.ByteArrayOutputStream;

/**
 * Finds a spoken question in microphone audio and makes it loud enough to understand.
 * Plain Java (no Android) so it can be tested off the phone.
 *
 * Feed it 20 ms frames of 16 kHz mono 16-bit audio. It first measures the room's
 * background noise, then counts anything clearly above that as talking, so a quiet
 * voice still counts. The question ends after a stretch of silence; the result is a
 * WAV with the quiet start and end trimmed and the voice raised to a normal level.
 */
final class VoiceCut {

    static final int RATE = 16000, FRAME = 320;                 // 20 ms
    /** Sensitivity: 0 normal, 1 high, 2 max. */
    private static final double[] FACTOR = {3.0, 2.0, 1.5};     // how far above the noise counts as voice
    private static final int[] MIN_RMS = {350, 160, 90};         // ...and never below this
    private static final int[] END_MS = {1400, 1700, 2000};      // silence that ends the question
    private static final double[] MAX_GAIN = {4, 10, 20};

    private static final int SKIP = 10;                         // first 200 ms: beep tail, earbud link warming up
    private static final int CALIBRATE = 15;                    // next 300 ms: measure the room
    private static final int START_FRAMES = 3;                  // 60 ms of voice starts the question
    private static final int PREROLL = 15;                      // keep 300 ms before the first word
    private static final int MAX_FRAMES = 30 * 1000 / 20;       // 30 s at most
    private static final int WAIT_FRAMES = 8 * 1000 / 20;       // 8 s with no voice: give up

    private final int sens;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private final double[] calib = new double[CALIBRATE];
    private double floor, threshold;
    private int frames, loud, quiet, startFrame = -1, lastVoice = -1;
    private boolean ended;
    private int level;

    VoiceCut(int sensitivity) {
        sens = Math.max(0, Math.min(2, sensitivity));
    }

    /** Takes one frame; returns true once the question has ended (or there will be none). */
    boolean feed(short[] f, int n) {
        if (ended) return true;
        byte[] b = new byte[n * 2];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            b[2 * i] = (byte) f[i];
            b[2 * i + 1] = (byte) (f[i] >> 8);
            sum += (double) f[i] * f[i];
        }
        pcm.write(b, 0, b.length);
        double rms = Math.sqrt(sum / Math.max(1, n));
        frames++;
        level = 0;
        if (frames <= SKIP) return false;
        if (frames <= SKIP + CALIBRATE) {                       // the room's own noise
            calib[frames - SKIP - 1] = rms;
            if (frames == SKIP + CALIBRATE) {
                double[] c = calib.clone();
                java.util.Arrays.sort(c);
                floor = Math.max(20, c[CALIBRATE / 2]);         // the middle value: one click can't spoil it
            }
            return false;
        }
        // the room's level keeps being followed: quickly down, slowly up (a fan switching on)
        floor += (rms - floor) * (rms < floor ? 0.2 : 0.004);
        if (floor < 20) floor = 20;
        threshold = Math.max(MIN_RMS[sens], floor * FACTOR[sens]);

        boolean talking = startFrame >= 0;
        // once talking, a softer level still counts (fading word endings), but well above the room
        double keep = Math.max(MIN_RMS[sens] * 0.6, Math.max(threshold * 0.6, floor * 1.5));
        boolean voice = rms >= (talking ? keep : threshold);
        level = rms < keep ? 0 : rms < threshold * 1.4 ? 1 : rms < threshold * 2.2 ? 2 : 3;
        if (voice) {
            loud++;
            quiet = 0;
            if (talking || loud >= START_FRAMES) lastVoice = frames;
            if (!talking && loud >= START_FRAMES) startFrame = frames - START_FRAMES;
        } else {
            loud = 0;
            quiet++;
        }
        if (startFrame >= 0 && quiet * 20 >= END_MS[sens]) ended = true;              // done talking
        if (startFrame < 0 && frames >= SKIP + CALIBRATE + WAIT_FRAMES) ended = true;  // nothing said
        if (frames >= MAX_FRAMES) ended = true;
        return ended;
    }

    /** 0..3 for the voice bars on the watch. */
    int level() { return level; }

    boolean heardVoice() { return startFrame >= 0; }

    /** The question as a WAV: trimmed, and raised to a normal loudness. Null if nothing was said. */
    byte[] wav() {
        if (startFrame < 0) return null;
        byte[] all = pcm.toByteArray();
        int from = Math.max(0, startFrame - PREROLL) * FRAME * 2;
        int to = Math.min(all.length, (Math.max(lastVoice, startFrame) + 15) * FRAME * 2);   // 300 ms after the last word
        int n = (to - from) / 2;
        short[] s = new short[n];
        int[] hist = new int[257];
        for (int i = 0; i < n; i++) {
            s[i] = (short) ((all[from + 2 * i] & 0xff) | (all[from + 2 * i + 1] << 8));
            hist[Math.abs((int) s[i]) >> 7]++;
        }
        // nearly the loudest sample (a click or a bump is ignored), in steps of 128
        int peak = 128, seen = 0;
        for (int k = 256; k >= 0; k--) {
            seen += hist[k];
            if (seen > n / 1000) { peak = (k + 1) * 128; break; }
        }
        double gain = Math.min(MAX_GAIN[sens], 0.7 * 32767 / peak);
        if (gain < 1) gain = 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + n * 2);
        header(out, n * 2);
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(s[i] * gain);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
        }
        return out.toByteArray();
    }

    /** Seconds of question audio kept (for tests and the log). */
    double seconds() {
        return startFrame < 0 ? 0 : ((Math.max(lastVoice, startFrame) + 15) - Math.max(0, startFrame - PREROLL)) * 0.02;
    }

    private static void header(ByteArrayOutputStream o, int dataLen) {
        writeStr(o, "RIFF");
        writeInt(o, 36 + dataLen);
        writeStr(o, "WAVE");
        writeStr(o, "fmt ");
        writeInt(o, 16);
        writeShort(o, 1);           // PCM
        writeShort(o, 1);           // mono
        writeInt(o, RATE);
        writeInt(o, RATE * 2);
        writeShort(o, 2);
        writeShort(o, 16);
        writeStr(o, "data");
        writeInt(o, dataLen);
    }

    private static void writeStr(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i));
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }
}
