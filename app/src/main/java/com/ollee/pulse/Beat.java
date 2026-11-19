package com.ollee.pulse;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.SystemClock;

/**
 * Estimates the tempo of what the phone is playing: listens to the bass of the output for
 * about 8 seconds and finds the spacing at which its hits repeat. The watch then runs its
 * bars at that tempo on its own, so one number is all that crosses the radio.
 */
final class Beat {

    private Beat() { }

    interface Got { void bpm(int bpm); }      // 0 = couldn't tell

    private static final int STEP_MS = 50, SAMPLES = 170;

    /** Runs on its own thread; the answer comes back on the handler. */
    static void measure(final Handler h, final Got cb) {
        new Thread(() -> {
            int bpm = 0;
            Visualizer v = null;
            try {
                v = new Visualizer(0);                          // 0 = everything the phone plays
                v.setEnabled(false);
                int[] range = Visualizer.getCaptureSizeRange();
                v.setCaptureSize(Math.max(range[0], Math.min(range[1], 1024)));
                v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
                v.setEnabled(true);
                byte[] fft = new byte[v.getCaptureSize()];
                float[] e = new float[SAMPLES];
                long t0 = SystemClock.elapsedRealtime();
                for (int i = 0; i < SAMPLES; i++) {
                    long wait = t0 + (long) i * STEP_MS - SystemClock.elapsedRealtime();
                    if (wait > 0) Thread.sleep(wait);
                    if (v.getFft(fft) != Visualizer.SUCCESS) continue;
                    float sum = 0;
                    for (int k = 1; k <= 5 && 2 * k + 1 < fft.length; k++) {      // the lowest bands: kick and bass
                        sum += (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
                    }
                    e[i] = sum;
                }
                bpm = tempo(e);
            } catch (Throwable ignored) {
                // no microphone permission, or the phone doesn't allow listening to its output
            } finally {
                if (v != null) {
                    try { v.setEnabled(false); } catch (Throwable ignored) { }
                    v.release();
                }
            }
            final int out = bpm;
            h.post(() -> cb.bpm(out));
        }, "pulse-beat").start();
    }

    /** Autocorrelation of the rises in bass energy; lags of 7 to 17 steps = 171 to 70 beats a minute. */
    static int tempo(float[] e) {
        int n = e.length;
        float[] rise = new float[n];
        float total = 0;
        for (int i = 1; i < n; i++) {
            rise[i] = Math.max(0, e[i] - e[i - 1]);
            total += rise[i];
        }
        if (total < n * 0.5f) return 0;                         // silence, or nothing to hear
        float[] r = new float[20];
        int best = 0;
        for (int lag = 6; lag <= 18; lag++) {
            float s = 0;
            for (int i = 1; i + lag < n; i++) s += rise[i] * rise[i + lag];
            r[lag] = s / (n - lag);
            if (lag >= 7 && lag <= 17 && (best == 0 || r[lag] > r[best])) best = lag;
        }
        if (best == 0 || r[best] <= 0) return 0;
        if (best >= 14) {                                       // a slow answer is often every second beat
            int half = best / 2;
            if (r[half + 1] > r[half]) half++;
            if (half >= 7 && r[half] > 0.45f * r[best]) best = half;
        }
        // the true peak usually sits between two steps: fit a curve through the neighbours
        float a = r[best - 1], b = r[best], c = r[best + 1], den = a - 2 * b + c;
        float shift = den == 0 ? 0 : 0.5f * (a - c) / den;
        if (shift < -0.5f || shift > 0.5f) shift = 0;
        return Math.round(60000f / ((best + shift) * STEP_MS));
    }
}
