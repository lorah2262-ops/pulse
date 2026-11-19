package com.ollee.pulse;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/** The watch battery voltage over time: a glowing line that draws itself in. */
public class Spark extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Battery.Reading> data = new ArrayList<>();
    private int count = -1;
    private float reveal = 1f, pulse;
    private final ValueAnimator breathe = ValueAnimator.ofFloat(0f, 1f);

    public Spark(Context c) {
        super(c);
        float d = Glass.dp(c, 1);
        line.setColor(Glass.ACCENT);
        line.setStrokeWidth(2 * d);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        glow.set(line);
        glow.setStrokeWidth(7 * d);
        glow.setAlpha(40);                       // a soft halo under the line
        fill.setStyle(Paint.Style.FILL);
        dot.setColor(Glass.ACCENT);
        breathe.setDuration(1600);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            if (data.size() > 1) invalidate();
        });
    }

    void set(List<Battery.Reading> all) {
        boolean grew = all.size() != count;
        data = all;
        if (grew) {
            boolean first = count < 0;
            count = all.size();
            if (first && count > 1) {            // draw the line in from the left
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(1100);
                a.setStartDelay(350);
                a.setInterpolator(new DecelerateInterpolator(2f));
                a.addUpdateListener(x -> { reveal = (float) x.getAnimatedValue(); invalidate(); });
                reveal = 0f;
                a.start();
            }
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        breathe.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        breathe.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x402F80ED, 0x002F80ED, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = data.size();
        float w = getWidth(), h = getHeight(), pad = h * 0.14f;
        if (n < 2) {                             // nothing yet: a faint dashed baseline
            dot.setColor(0x402C5A8C);
            for (float x = 0; x < w; x += Glass.dp(getContext(), 10)) c.drawCircle(x, h / 2, Glass.dp(getContext(), 1), dot);
            dot.setColor(Glass.ACCENT);
            return;
        }
        long t0 = data.get(0).time, t1 = data.get(n - 1).time;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (Battery.Reading r : data) { lo = Math.min(lo, r.volts); hi = Math.max(hi, r.volts); }
        if (hi - lo < 0.05f) { float mid = (hi + lo) / 2; lo = mid - 0.025f; hi = mid + 0.025f; }
        Path p = new Path(), area = new Path();
        float x = 0, y = 0, right = w - pad;
        for (int i = 0; i < n; i++) {
            Battery.Reading r = data.get(i);
            x = t1 == t0 ? right * i / (n - 1f) : (r.time - t0) * right / (float) (t1 - t0);
            y = pad + (hi - r.volts) * (h - 2 * pad) / (hi - lo);
            if (i == 0) { p.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y); }
            else { p.lineTo(x, y); area.lineTo(x, y); }
        }
        area.lineTo(x, h);
        area.close();
        c.save();
        c.clipRect(0, 0, w * reveal + 1, h);
        c.drawPath(area, fill);
        c.drawPath(p, glow);
        c.drawPath(p, line);
        c.restore();
        if (reveal >= 1f) {                      // the newest reading breathes
            float r = line.getStrokeWidth();
            dot.setAlpha(Math.round(70 * (1 - pulse)));
            c.drawCircle(x, y, r * (2 + 3 * pulse), dot);
            dot.setAlpha(255);
            c.drawCircle(x, y, r * 1.8f, dot);
        }
    }
}
