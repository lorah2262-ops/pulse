package com.ollee.pulse;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.util.Locale;

/**
 * The floating music player: a black pill over other apps while music plays.
 *
 *  - Small pill: moving bars, the time in the song, the music app's icon. Tap it to open it up.
 *  - Open pill: cover, song and artist, the bars and time, pause / play, the app (tap to open it).
 *  - Drag it to the left or right edge and it stands up: a tall pill on that edge (cover, bars,
 *    time, app). Drag that onto the circle at the bottom middle and it lies down again as the
 *    small pill, right there.
 */
final class MusicPill {

    static final int SMALL = 0, OPEN = 1, BUBBLE = 2;      // BUBBLE = the tall pill on an edge
    private static final int GREEN = 0xFF1ED760;

    private final Context c;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float d;
    private PillView view;
    private Target target;
    private WindowManager.LayoutParams lp;
    private NotifService.Now now = new NotifService.Now();
    private long pausedSince;

    // shape: the form shown, and the sizes in between while it changes
    private int form = SMALL;
    private float w, hgt;
    private float cx, y;               // the pill's middle (x) and top (y) on screen
    private boolean right = true;      // which edge the bubble sits on
    private ValueAnimator shapeRun;

    MusicPill(Context c) {
        this.c = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        d = c.getResources().getDisplayMetrics().density;
        SharedPreferences p = prefs();
        form = p.getInt("pill_form", SMALL) == BUBBLE ? BUBBLE : SMALL;
        right = p.getBoolean("pill_right", true);
        cx = p.getFloat("pill_cx", screenW() / 2f);
        y = p.getFloat("pill_y", 48 * d);
        w = widthOf(form);
        hgt = heightOf(form);
    }

    private SharedPreferences prefs() {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE);
    }

    static boolean wanted(Context c) {
        return c.getSharedPreferences("pulse", Context.MODE_PRIVATE).getBoolean("music_pill", false);
    }

    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    private int screenW() { return c.getResources().getDisplayMetrics().widthPixels; }
    private int screenH() { return c.getResources().getDisplayMetrics().heightPixels; }

    private float widthOf(int f) {
        return f == BUBBLE ? 50 * d : f == OPEN ? Math.min(screenW() - 24 * d, 344 * d) : 176 * d;
    }

    private float heightOf(int f) {
        return f == BUBBLE ? 164 * d : f == OPEN ? 76 * d : 44 * d;
    }

    // ---------- showing and hiding ----------

    private final Runnable hideLater = this::hide;

    /** A new look at the player: show, update or hide. */
    void update(NotifService.Now n) {
        now = n;
        boolean show = wanted(c) && allowed(c) && n.exists;
        if (n.playing) {
            pausedSince = 0;
            h.removeCallbacks(hideLater);
        } else if (pausedSince == 0) {
            pausedSince = SystemClock.elapsedRealtime();
            h.removeCallbacks(hideLater);
            h.postDelayed(hideLater, 5 * 60_000L);        // paused for 5 minutes: put it away
        }
        if (!n.playing && view == null) show = false;     // only comes up when music starts
        if (!show) { hide(); return; }
        if (view == null) add();
        view.setArt(n);
        view.invalidate();
    }

    private void add() {
        view = new PillView(c);
        lp = new WindowManager.LayoutParams(Math.round(w), Math.round(hgt),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        place();
        try {
            wm.addView(view, lp);
        } catch (Exception e) {            // permission taken away
            view = null;
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(0.6f);
        view.setScaleY(0.6f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Glass.BOUNCY).start();
        view.startBars();
    }

    void hide() {
        h.removeCallbacks(hideLater);
        if (shapeRun != null) shapeRun.cancel();
        removeTarget();
        if (view == null) return;
        final View v = view;
        view.stopBars();
        view = null;
        v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(220).withEndAction(() -> {
            try { wm.removeView(v); } catch (Exception ignored) { }
        }).start();
    }

    /** Puts the window where the shape says. */
    private void place() {
        if (lp == null) return;
        lp.width = Math.round(w);
        lp.height = Math.round(hgt);
        if (form == BUBBLE && !dragging) {
            lp.x = Math.round(right ? screenW() - w - 8 * d : 8 * d);
        } else {
            lp.x = Math.round(Math.max(4 * d, Math.min(screenW() - w - 4 * d, cx - w / 2f)));
        }
        lp.y = Math.round(Math.max(0, Math.min(screenH() - hgt - 24 * d, y)));
        if (view != null) {
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        }
    }

    /** Springs from the shape now to another form. */
    private void become(int f) {
        final float w0 = w, h0 = hgt, w1 = widthOf(f), h1 = heightOf(f);
        final float[] from = view == null ? new float[3] : view.mix.clone();
        form = f;
        prefs().edit().putInt("pill_form", f == OPEN ? SMALL : f).putBoolean("pill_right", right)
                .putFloat("pill_cx", cx).putFloat("pill_y", y).apply();
        if (shapeRun != null) shapeRun.cancel();
        shapeRun = ValueAnimator.ofFloat(0f, 1f);
        shapeRun.setDuration(620);
        shapeRun.setInterpolator(Glass.BOUNCY);
        shapeRun.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            w = Math.max(20 * d, w0 + (w1 - w0) * t);
            hgt = Math.max(20 * d, h0 + (h1 - h0) * t);
            if (view != null) {
                float k = Math.max(0f, Math.min(1f, t));
                for (int i = 0; i < 3; i++) view.mix[i] = from[i] + ((i == f ? 1f : 0f) - from[i]) * k;
                view.invalidate();
            }
            place();
        });
        shapeRun.start();
    }

    // ---------- the drop circle for turning the bubble back into a pill ----------

    private final class Target extends View {
        float hot;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Target(Context c) { super(c); }

        @Override protected void onDraw(Canvas k) {
            float r = getWidth() / 2f * (0.78f + 0.14f * hot);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(Math.round(110 + 80 * hot), 0, 0, 0));
            k.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(hot > 0.5f ? GREEN : 0xCCFFFFFF);
            k.drawCircle(getWidth() / 2f, getHeight() / 2f, r - 2 * d, p);
            // a little pill shape inside: "drop here to make it a pill again"
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xEEFFFFFF);
            float pw = r * 0.95f, ph = r * 0.42f;
            k.drawRoundRect(new RectF(getWidth() / 2f - pw / 2, getHeight() / 2f - ph / 2,
                    getWidth() / 2f + pw / 2, getHeight() / 2f + ph / 2), ph / 2, ph / 2, p);
        }
    }

    private WindowManager.LayoutParams targetLp;

    private void showTarget() {
        if (target != null) return;
        target = new Target(c);
        int size = Math.round(84 * d);
        targetLp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        targetLp.gravity = Gravity.TOP | Gravity.START;
        targetLp.x = Math.round(screenW() / 2f - size / 2f);
        targetLp.y = Math.round(screenH() - 150 * d);
        try { wm.addView(target, targetLp); } catch (Exception e) { target = null; return; }
        target.setAlpha(0f);
        target.setScaleX(0.5f);
        target.setScaleY(0.5f);
        target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Glass.BOUNCY).start();
    }

    private void removeTarget() {
        if (target == null) return;
        final View t = target;
        target = null;
        t.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180).withEndAction(() -> {
            try { wm.removeView(t); } catch (Exception ignored) { }
        }).start();
    }

    /** True if the bubble's middle is over the drop circle. */
    private boolean overTarget() {
        if (targetLp == null) return false;
        float bx = lp.x + w / 2f, by = lp.y + hgt / 2f;
        float tx = targetLp.x + targetLp.width / 2f, ty = targetLp.y + targetLp.height / 2f;
        return Math.hypot(bx - tx, by - ty) < 70 * d;
    }

    // ---------- touch ----------

    private boolean dragging;
    private float downX, downY, startX, startY;
    private long downAt;

    private boolean touch(MotionEvent e) {
        float slop = ViewConfiguration.get(c).getScaledTouchSlop();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                downAt = SystemClock.uptimeMillis();
                dragging = false;
                if (view != null) view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(120).start();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) > slop) {
                    dragging = true;
                    if (form == OPEN) become(SMALL);
                    if (form == BUBBLE) showTarget();
                }
                if (dragging) {
                    lp.x = Math.round(startX + dx);
                    lp.y = Math.round(Math.max(0, Math.min(screenH() - hgt, startY + dy)));
                    cx = lp.x + w / 2f;
                    y = lp.y;
                    try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                    if (target != null) {
                        float hot = overTarget() ? 1f : 0f;
                        if (hot != target.hot) {
                            target.hot = hot;
                            target.invalidate();
                            if (hot > 0) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (view != null) view.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Glass.BOUNCY).start();
                if (!dragging) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downAt < 500)
                        tap(e.getX(), e.getY());
                    return true;
                }
                dropped();
                dragging = false;
                return true;
        }
        return false;
    }

    private void dropped() {
        float mid = lp.x + w / 2f;
        if (form == BUBBLE) {
            boolean home = overTarget();
            removeTarget();
            if (home) {                                    // lies down again: the small pill, right there
                cx = screenW() / 2f;
                y = Math.max(0, lp.y + hgt / 2f - heightOf(SMALL) / 2f);
                become(SMALL);
            } else {                                       // stays a bubble, on the nearer edge
                right = mid > screenW() / 2f;
                become(BUBBLE);
            }
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            return;
        }
        if (mid < screenW() * 0.16f || mid > screenW() * 0.84f) {   // to an edge: becomes a bubble
            right = mid > screenW() / 2f;
            become(BUBBLE);
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            return;
        }
        prefs().edit().putFloat("pill_cx", cx).putFloat("pill_y", y).apply();
        place();
    }

    private void tap(float x, float yy) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (form == BUBBLE) { Glass.pop(view); return; }        // the tall pill only moves
        if (form == SMALL) { become(OPEN); return; }
        // open: the play button, the app icon, or anywhere else to close it up
        if (view.playHit.contains(x, yy)) { NotifService.control('P'); return; }
        if (view.appHit.contains(x, yy)) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(now.pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(i); } catch (Exception ignored) { }
            }
            become(SMALL);
            return;
        }
        become(SMALL);
    }

    // ---------- drawing ----------

    private final class PillView extends View {
        /** How much of each form's contents shows: small, open, bubble (they cross-fade while morphing). */
        final float[] mix = new float[3];
        final RectF playHit = new RectF(), appHit = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap art, icon;
        private String iconPkg = "";
        private ValueAnimator bars;
        private float phase;

        PillView(Context c) {
            super(c);
            mix[form == OPEN ? OPEN : form] = 1f;
            title.setColor(Color.WHITE);
            title.setTextSize(15 * d);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            small.setColor(0xFFB3B3B3);
            small.setTextSize(12.5f * d);
            setOnTouchListener((v, e) -> touch(e));
        }

        void setArt(NotifService.Now n) {
            art = n.art;
            if (!n.pkg.equals(iconPkg)) {
                iconPkg = n.pkg;
                icon = null;
                try {
                    Drawable dr = getContext().getPackageManager().getApplicationIcon(n.pkg);
                    int s = Math.round(48 * d);
                    icon = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                    Canvas k = new Canvas(icon);
                    dr.setBounds(0, 0, s, s);
                    dr.draw(k);
                } catch (Exception ignored) { }
            }
        }

        void startBars() {
            if (bars != null) return;
            bars = ValueAnimator.ofFloat(0f, 1f);
            bars.setDuration(1000);
            bars.setRepeatCount(ValueAnimator.INFINITE);
            bars.addUpdateListener(a -> {
                if (now.playing) phase += 0.11f;
                invalidate();
            });
            bars.start();
        }

        void stopBars() {
            if (bars != null) bars.cancel();
            bars = null;
        }

        private String time() {
            long s = now.position() / 1000;
            return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
        }

        /** Three bars that bounce while playing and rest low when paused. */
        private void drawBars(Canvas k, float x, float midY, float hMax, float alpha) {
            fill.setColor(GREEN);
            fill.setAlpha(Math.round(255 * alpha));
            float bw = 3 * d, gap = 3.5f * d;
            for (int i = 0; i < 3; i++) {
                float v = now.playing ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(phase * (1.3f + i * 0.47f) + i * 1.9f)) : 0.3f;
                float bh = Math.max(3 * d, hMax * v);
                float left = x + i * (bw + gap);
                k.drawRoundRect(new RectF(left, midY - bh / 2, left + bw, midY + bh / 2), bw / 2, bw / 2, fill);
            }
        }

        private void drawRound(Canvas k, Bitmap b, RectF r, float radius, float alpha) {
            if (b == null) {
                fill.setColor(0xFF2A2A2A);
                fill.setAlpha(Math.round(255 * alpha));
                k.drawRoundRect(r, radius, radius, fill);
                return;
            }
            BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
            m.setScale(sc, sc);
            m.postTranslate(r.centerX() - b.getWidth() * sc / 2f, r.centerY() - b.getHeight() * sc / 2f);
            sh.setLocalMatrix(m);
            img.setShader(sh);
            img.setAlpha(Math.round(255 * alpha));
            k.drawRoundRect(r, radius, radius, img);
            img.setShader(null);
        }

        @Override
        protected void onDraw(Canvas k) {
            float W = getWidth(), H = getHeight(), r = H / 2f;
            fill.setColor(Color.BLACK);
            fill.setAlpha(255);
            k.drawRoundRect(new RectF(0, 0, W, H), r, r, fill);
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, W, H), r, r, Path.Direction.CW);
            k.save();
            k.clipPath(clip);

            // small pill: bars, time, app icon
            float a = mix[SMALL];
            if (a > 0.02f) {
                drawBars(k, 18 * d, H / 2f, 18 * d, a);
                small.setColor(Color.WHITE);
                small.setTextSize(15 * d);
                small.setAlpha(Math.round(255 * a));
                k.drawText(time(), 40 * d, H / 2f + 5 * d, small);
                float s = Math.min(26 * d, H - 14 * d);
                drawRound(k, icon, new RectF(W - 12 * d - s, H / 2f - s / 2, W - 12 * d, H / 2f + s / 2), s / 2, a);
            }

            // open pill: cover, song / artist with bars and time, play button, app icon
            a = mix[OPEN];
            playHit.setEmpty();
            appHit.setEmpty();
            if (a > 0.02f) {
                float pad = 12 * d, cover = H - 2 * pad;
                drawRound(k, art != null ? art : icon, new RectF(pad, pad, pad + cover, pad + cover), 10 * d, a);
                float appS = Math.min(44 * d, H - 2 * pad), playS = 40 * d;
                RectF appR = new RectF(W - 14 * d - appS, H / 2f - appS / 2, W - 14 * d, H / 2f + appS / 2);
                RectF playR = new RectF(appR.left - 10 * d - playS, H / 2f - playS / 2, appR.left - 10 * d, H / 2f + playS / 2);
                drawRound(k, icon, appR, appS / 2, a);
                fill.setColor(0xFF2E2E2E);
                fill.setAlpha(Math.round(255 * a));
                k.drawCircle(playR.centerX(), playR.centerY(), playS / 2, fill);
                fill.setColor(Color.WHITE);
                fill.setAlpha(Math.round(255 * a));
                float gx = playR.centerX(), gy = playR.centerY(), g = 7 * d;
                if (now.playing) {
                    k.drawRoundRect(new RectF(gx - g * 0.75f, gy - g, gx - g * 0.2f, gy + g), d, d, fill);
                    k.drawRoundRect(new RectF(gx + g * 0.2f, gy - g, gx + g * 0.75f, gy + g), d, d, fill);
                } else {
                    Path tri = new Path();
                    tri.moveTo(gx - g * 0.55f, gy - g);
                    tri.lineTo(gx + g * 0.95f, gy);
                    tri.lineTo(gx - g * 0.55f, gy + g);
                    tri.close();
                    k.drawPath(tri, fill);
                }
                playHit.set(playR);
                playHit.inset(-6 * d, -6 * d);
                appHit.set(appR);
                float tx = pad + cover + 12 * d, tw = playR.left - 10 * d - tx;
                if (tw > 20 * d) {
                    title.setAlpha(Math.round(255 * a));
                    k.drawText(TextUtils.ellipsize(now.title, title, tw, TextUtils.TruncateAt.END).toString(),
                            tx, H / 2f - 3 * d, title);
                    drawBars(k, tx, H / 2f + 12 * d, 11 * d, a);
                    small.setColor(0xFFB3B3B3);
                    small.setTextSize(12.5f * d);
                    small.setAlpha(Math.round(255 * a));
                    String line = time() + (now.artist.isEmpty() ? "" : "  ·  " + now.artist);
                    float lx = tx + 17 * d;
                    k.drawText(TextUtils.ellipsize(line, small, tw - 17 * d, TextUtils.TruncateAt.END).toString(),
                            lx, H / 2f + 16.5f * d, small);
                }
            }

            // tall pill on an edge: cover on top, the bars, the time, the app at the bottom
            a = mix[BUBBLE];
            if (a > 0.02f && H > W * 1.4f) {
                float s = W - 10 * d;
                drawRound(k, art != null ? art : icon, new RectF(5 * d, 5 * d, 5 * d + s, 5 * d + s), s / 2, a);
                if (now.playing) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(2 * d);
                    fill.setColor(GREEN);
                    fill.setAlpha(Math.round(255 * a));
                    k.drawCircle(W / 2f, 5 * d + s / 2, s / 2 + 1 * d, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
                drawBars(k, W / 2f - 8.5f * d, 5 * d + s + 26 * d, 18 * d, a);
                small.setColor(Color.WHITE);
                small.setTextSize(11.5f * d);
                small.setAlpha(Math.round(255 * a));
                long sec = now.position() / 1000;
                String t = sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
                k.drawText(t, W / 2f - small.measureText(t) / 2f, 5 * d + s + 58 * d, small);
                float is = 28 * d;
                drawRound(k, icon, new RectF(W / 2f - is / 2, H - 8 * d - is, W / 2f + is / 2, H - 8 * d), is / 2, a);
            }
            k.restore();
        }
    }
}
