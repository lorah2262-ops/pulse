package com.ollee.pulse;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Press and hold to confirm. A round blob of colour grows in from the left and fills the pill;
 * let go early and it drains back. When it's full the pill says it's done. With undo on, it
 * splits in two: "✓ Deleted" and a dark "Undo" with a ring that counts down. Undo in time and
 * nothing happens; otherwise it goes through when the ring runs out.
 */
final class HoldPill extends View {

    interface Done { void run(); }

    private static final int IDLE = 0, HOLD = 1, DONE = 2;
    private static final long HOLD_MS = 850, UNDO_MS = 4000;

    private final float d;
    private final String label, doneLabel;
    private final int color;
    private final boolean undo;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int state = IDLE;
    private float fill;              // 0..1 while holding
    private float split;             // 0..1: how far the Undo half has slid out
    private float ring;              // 0..1: the undo countdown
    private ValueAnimator run, countdown;
    private Done onDone, onUndo;
    private final RectF undoHit = new RectF();

    HoldPill(Context c, String label, String doneLabel, int color, boolean undo) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        this.label = label;
        this.doneLabel = doneLabel;
        this.color = color;
        this.undo = undo;
        ink.setTextSize(14 * d);
        ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    /** What to do once it's confirmed (after the undo time, if undo is on). */
    HoldPill onDone(Done r) { onDone = r; return this; }

    HoldPill onUndo(Done r) { onUndo = r; return this; }

    private float textW(String s) { return ink.measureText(s); }

    private float mainW() {
        return Math.max(textW(label), textW("✓ " + doneLabel)) + 34 * d;
    }

    private float undoW() { return textW("Undo") + 52 * d; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float w = mainW() + (undo ? (undoW() + 6 * d) * split : 0);
        setMeasuredDimension(Math.round(w), Math.round(42 * d));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (state == DONE) {
                    if (undo && undoHit.contains(e.getX(), e.getY())) { doUndo(); return true; }
                    return false;
                }
                state = HOLD;
                getParent().requestDisallowInterceptTouchEvent(true);
                animateFill(1f, Math.round(HOLD_MS * (1f - fill)), true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (state == HOLD) {
                    state = IDLE;
                    animateFill(0f, 320, false);
                }
                return true;
        }
        return state == HOLD;
    }

    private void animateFill(float to, long ms, final boolean holding) {
        if (run != null) run.cancel();
        run = ValueAnimator.ofFloat(fill, to);
        run.setDuration(Math.max(1, ms));
        run.setInterpolator(holding ? new LinearInterpolator() : Glass.BOUNCY);
        run.addUpdateListener(a -> {
            fill = Math.max(0f, Math.min(1f, (float) a.getAnimatedValue()));
            invalidate();
            if (holding && fill >= 1f && state == HOLD) confirmed();
        });
        run.start();
    }

    private void confirmed() {
        state = DONE;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Glass.pop(this);
        if (!undo) {
            if (onDone != null) onDone.run();
            postDelayed(this::reset, 1400);
            return;
        }
        // slide the Undo half out, then count down
        ValueAnimator out = ValueAnimator.ofFloat(0f, 1f);
        out.setDuration(520);
        out.setInterpolator(Glass.BOUNCY);
        out.addUpdateListener(a -> {
            split = Math.max(0f, (float) a.getAnimatedValue());
            requestLayout();
            invalidate();
        });
        out.start();
        countdown = ValueAnimator.ofFloat(0f, 1f);
        countdown.setDuration(UNDO_MS);
        countdown.setInterpolator(new LinearInterpolator());
        countdown.addUpdateListener(a -> {
            ring = (float) a.getAnimatedValue();
            invalidate();
            if (ring >= 1f && state == DONE) {
                state = IDLE;
                if (onDone != null) onDone.run();
                reset();
            }
        });
        countdown.start();
    }

    private void doUndo() {
        if (countdown != null) countdown.cancel();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        state = IDLE;
        if (onUndo != null) onUndo.run();
        reset();
    }

    /** Back to the plain pill, the halves sliding together. */
    private void reset() {
        state = IDLE;
        ring = 0;
        ValueAnimator back = ValueAnimator.ofFloat(1f, 0f);
        back.setDuration(420);
        back.setInterpolator(Glass.BOUNCY);
        back.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            split = Math.min(split, Math.max(0f, t));
            fill = Math.min(fill, Math.max(0f, t));
            requestLayout();
            invalidate();
        });
        back.start();
    }

    @Override
    protected void onDraw(Canvas k) {
        float H = getHeight(), r = H / 2f, mw = Math.min(getWidth(), mainW());
        RectF main = new RectF(0, 0, mw, H);
        boolean done = state == DONE;
        // the plain pill
        bg.setColor(done && undo ? (color & 0x00FFFFFF) | 0x26000000 : 0xF2FFFFFF);
        k.drawRoundRect(main, r, r, bg);
        if (!done || !undo) {
            bg.setStyle(Paint.Style.STROKE);
            bg.setStrokeWidth(Math.max(1f, d));
            bg.setColor(0x262C5A8C);
            k.drawRoundRect(main, r, r, bg);
            bg.setStyle(Paint.Style.FILL);
        }
        String text = done ? "✓ " + doneLabel : label;
        float tx = mw / 2f - textW(text) / 2f, ty = H / 2f + 5 * d;
        ink.setColor(done && undo ? color : Glass.TEXT);
        k.drawText(text, tx, ty, ink);
        // the colour blob: a circle at the left that grows into the whole pill
        if (fill > 0 && !(done && undo)) {
            Path clip = new Path();
            float grow = fill < 0.35f ? r * (fill / 0.35f) : r + (mw - 2 * r) * ((fill - 0.35f) / 0.65f);
            RectF blob = fill < 0.35f ? new RectF(r - grow, r - grow, r + grow, r + grow)
                    : new RectF(0, 0, Math.min(mw, 2 * r + (mw - 2 * r) * ((fill - 0.35f) / 0.65f)), H);
            clip.addRoundRect(blob, r, r, Path.Direction.CW);
            k.save();
            k.clipPath(clip);
            bg.setColor(color);
            k.drawRoundRect(main, r, r, bg);
            ink.setColor(Color.WHITE);
            k.drawText(text, tx, ty, ink);
            k.restore();
        }
        // the Undo half
        undoHit.setEmpty();
        if (undo && split > 0.01f) {
            float uw = undoW() * Math.min(1f, split), left = mw + 6 * d;
            RectF u = new RectF(left, 0, left + uw, H);
            bg.setColor(0xFF1A1A1A);
            k.drawRoundRect(u, r, r, bg);
            undoHit.set(u);
            k.save();
            k.clipRect(u);
            float rcx = left + 22 * d, rr = 7 * d;
            Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringP.setStyle(Paint.Style.STROKE);
            ringP.setStrokeWidth(2 * d);
            ringP.setColor(0x55FFFFFF);
            k.drawCircle(rcx, H / 2f, rr, ringP);
            ringP.setColor(Color.WHITE);
            k.drawArc(new RectF(rcx - rr, H / 2f - rr, rcx + rr, H / 2f + rr), -90, 360 * (1f - ring), false, ringP);
            ink.setColor(Color.WHITE);
            k.drawText("Undo", rcx + rr + 8 * d, ty, ink);
            k.restore();
        }
    }
}
