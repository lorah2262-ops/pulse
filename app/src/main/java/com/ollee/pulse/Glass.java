package com.ollee.pulse;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ScrollView;

/** The look: a soft blue sky, frosted white cards, one blue accent, a switch, a status dot, tab icons. */
final class Glass {

    private Glass() { }

    // palette: a light blue base, navy text at three strengths, one blue accent
    static final int BG = 0xFFA6C8F5, TEXT = 0xFF0B2A4A, SOFT = 0xFF2C5A8C, MUTED = 0xFF5B7EA6, FAINT = 0x804A76A8,
            ACCENT = 0xFF2F80ED, VIOLET = 0xFF6C7BE8, GOOD = 0xFF1B9A66, WARN = 0xFFC77A0A, BAD = 0xFFD64545,
            LINE = 0x1F2C5A8C;

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    // ---------- springs ----------

    /**
     * A spring as an interpolator: light mass, stiff, little damping. It shoots past the
     * target, comes back, and wobbles a few times before it rests there.
     * damping: 0 never stops, 1 doesn't bounce at all. wobbles: how many swings fit in the run.
     */
    static final class Springy implements TimeInterpolator {
        private final double zeta, wd, wn;

        Springy(float damping, float wobbles) {
            zeta = damping;
            wd = 2 * Math.PI * wobbles;
            wn = wd / Math.sqrt(1 - zeta * zeta);
        }

        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            double decay = Math.exp(-zeta * wn * t);
            return (float) (1 - decay * (Math.cos(wd * t) + zeta * wn / wd * Math.sin(wd * t)));
        }
    }

    /** Taps and switches: goes about a seventh past, comes back once, and rests. Soft, not twitchy. */
    static final Springy BOUNCY = new Springy(0.56f, 1.25f);
    /** Pages, cards making room, the tab bubble: glides in and settles with the smallest overshoot. */
    static final Springy GENTLE = new Springy(0.74f, 0.95f);

    /** Blurs a view by this many pixels (0 = sharp). Only Android 12 and up can; older ones skip it. */
    static void blur(View v, float px) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        // a new blur makes the GPU redo the whole view, so it changes in 2 px steps, not every frame
        float step = px < 0.5f ? 0f : Math.max(2f, Math.round(px / 2f) * 2f);
        Object last = v.getTag(R_BLUR);
        if (last instanceof Float && (Float) last == step) return;
        v.setTag(R_BLUR, step);
        v.setRenderEffect(step == 0f ? null
                : android.graphics.RenderEffect.createBlurEffect(step, step, Shader.TileMode.CLAMP));
    }

    private static final int R_BLUR = 0x7f0f0b1a;   // a view tag key for the last blur set

    /**
     * A page that can be pulled past its ends: it follows the finger at a fraction of the
     * distance, like something on a rubber band, and springs back with a wobble when let go.
     */
    static final class Elastic extends ScrollView {
        private float pull;                 // how far the finger has dragged past an end, px
        private ValueAnimator back;

        Elastic(Context c) {
            super(c);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setVerticalScrollBarEnabled(false);
            setClipToPadding(false);
        }

        @Override
        protected boolean overScrollBy(int dx, int dy, int sx, int sy, int rx, int ry, int mx, int my, boolean touch) {
            int target = sy + dy;
            int over = target < 0 ? target : target > ry ? target - ry : 0;
            if (touch) {
                if (pull != 0 && (pull < 0) != (dy < 0)) {          // dragging back: let the band relax first
                    float next = pull + dy;
                    if (next != 0 && (next < 0) == (pull < 0)) {
                        pull = next;
                        stretch();
                        return false;
                    }
                    pull = 0;
                    stretch();
                    dy = (int) next;                                // what is left scrolls the page
                } else if (over != 0) {
                    pull += over;
                    stretch();
                }
            } else if (over != 0 && pull == 0 && getChildCount() > 0) {     // a fling ran into the end: a nudge
                float kick = Math.max(-dp(getContext(), 44), Math.min(dp(getContext(), 44), over * 0.4f));
                getChildAt(0).setTranslationY(-kick);
                release();
            }
            return super.overScrollBy(dx, dy, sx, sy, rx, ry, 0, 0, touch);
        }

        /** The further the pull, the harder it resists. */
        private void stretch() {
            if (getChildCount() == 0) return;
            float room = Math.max(1, getHeight()) * 0.9f;
            getChildAt(0).setTranslationY(-pull * 0.5f / (1 + Math.abs(pull) / room));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && back != null) back.cancel();
            boolean used = super.onTouchEvent(e);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) release();
            return used;
        }

        private void release() {
            pull = 0;
            if (getChildCount() == 0) return;
            final View page = getChildAt(0);
            float from = page.getTranslationY();
            if (from == 0) return;
            if (back != null) back.cancel();
            back = ValueAnimator.ofFloat(from, 0f);
            back.setDuration(720);
            back.setInterpolator(BOUNCY);
            back.addUpdateListener(x -> page.setTranslationY((float) x.getAnimatedValue()));
            back.start();
        }
    }

    /** A frosted card: milky white, a little clearer at the bottom, with a bright edge. */
    static GradientDrawable panel(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xEBFFFFFF, 0xC4FFFFFF});
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, Math.round(dp(c, 1f))), 0xB3FFFFFF);
        return g;
    }

    /** The floating tab bar: an almost solid white capsule. */
    static GradientDrawable bar(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF5FFFFFF);
        g.setCornerRadius(dp(c, 34));
        g.setStroke(Math.max(1, Math.round(dp(c, 1f))), 0xFFFFFFFF);
        return g;
    }

    /** A filled accent capsule (the main action on a card). */
    static GradientDrawable accent(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4A97F5, 0xFF2A6FE0});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /**
     * Pressing squashes a view (wider and flatter, like pressing into something soft); letting
     * go springs it back past its shape and it wobbles to rest. Clicks still work.
     */
    static void spring(View v) {
        final float[] down = new float[4];               // where the finger landed, and where the view rested
        final Glow glow = new Glow();
        final boolean[] live = {false};
        // the glow sits between the view's own background and its words, so text stays crisp
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg != null) v.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bg, glow}));
        else v.setForeground(glow);
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            float w = Math.max(1, view.getWidth()), h = Math.max(1, view.getHeight());
            float swell = 1f + Math.min(0.08f, dp(view.getContext(), 8) / Math.max(w, h));
            float reach = Math.min(dp(view.getContext(), 7), Math.min(w, h) * 0.12f);
            if (a == MotionEvent.ACTION_DOWN) {
                live[0] = true;
                down[0] = e.getRawX();
                down[1] = e.getRawY();
                view.animate().cancel();
                down[2] = view.getTranslationX();
                down[3] = view.getTranslationY();
                // let it grow past its row without being cut, and above its neighbours
                android.view.ViewParent p = view.getParent();
                for (int i = 0; i < 3 && p instanceof android.view.ViewGroup; i++, p = p.getParent()) {
                    ((android.view.ViewGroup) p).setClipChildren(false);
                    ((android.view.ViewGroup) p).setClipToPadding(false);
                }
                view.setTranslationZ(dp(view.getContext(), 6));
                // hold the page still while the finger is on the glass
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                glow.at(e.getX(), e.getY(), (w + h) / 3.2f);
                glow.show(view, true);
                view.animate().scaleX(swell).scaleY(swell).setDuration(240)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            } else if (a == MotionEvent.ACTION_MOVE && live[0]) {
                float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                if (Math.abs(dy) > dp(view.getContext(), 26)) {
                    // clearly a scroll: let go of the page and settle back
                    live[0] = false;
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                    release(view, glow, down);
                    return false;
                }
                // a gentle pull toward the finger, the harder the further
                float px = reach * (float) Math.tanh(dx / (reach * 4f)), py = reach * (float) Math.tanh(dy / (reach * 4f));
                view.setTranslationX(down[2] + px);
                view.setTranslationY(down[3] + py);
                glow.at(e.getX(), e.getY(), (w + h) / 3.2f);
            } else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && live[0]) {
                live[0] = false;
                release(view, glow, down);
            }
            return false;
        });
    }

    private static void release(View view, Glow glow, float[] down) {
        glow.show(view, false);
        view.animate().cancel();
        view.animate().scaleX(1f).scaleY(1f).translationX(down[2]).translationY(down[3]).setDuration(680)
                .setInterpolator(BOUNCY).withEndAction(() -> view.setTranslationZ(0f)).start();
    }

    /** The light under a finger on glass: a soft white glow that follows the touch and fades. */
    static final class Glow extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x, y, r = 1f, level;
        private ValueAnimator run;
        private android.graphics.RadialGradient shade;

        void at(float nx, float ny, float nr) {
            x = nx;
            y = ny;
            r = Math.max(1f, nr);
            shade = null;
            invalidateSelf();
        }

        void show(final View host, boolean on) {
            if (run != null) run.cancel();
            run = ValueAnimator.ofFloat(level, on ? 1f : 0f);
            run.setDuration(on ? 200 : 520);
            run.addUpdateListener(a -> { level = (float) a.getAnimatedValue(); invalidateSelf(); host.invalidate(); });
            run.start();
        }

        @Override
        public void draw(Canvas c) {
            if (level <= 0.01f) return;
            if (shade == null) {
                shade = new android.graphics.RadialGradient(x, y, r, new int[]{0x78FFFFFF, 0x2DFFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
                p.setShader(shade);
            }
            p.setAlpha(Math.round(255 * level));
            c.drawRect(getBounds(), p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** A quick pop: the view jumps a little bigger and springs back (a value that just changed). */
    static void pop(View v) {
        v.setScaleX(0.9f);
        v.setScaleY(1.08f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(720).setInterpolator(BOUNCY).start();
    }

    /** Fade and rise into place, a little after the one before it. */
    static void enter(View v, int order) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 22));
        v.setScaleX(0.98f);
        v.setScaleY(0.98f);
        v.setTranslationY(dp(v.getContext(), 34));
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(70L + Math.min(order, 8) * 55L).setDuration(760)
                .setInterpolator(GENTLE).withLayer().start();
    }


    // ---------- the tab bubble ----------

    /**
     * The blue capsule under the chosen tab, drawn as a true capsule between two edges. It can
     * stretch as long as it likes and its ends stay round, and it never leaves the bar: pushed
     * against an end it is squeezed instead.
     */
    static final class Bubble extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float left, right, thin, lift;
        /** Where it was last drawn, in its own coordinates (the lens uses it). */
        final RectF drawn = new RectF();

        float leftEdge() { return left; }

        float rightEdge() { return right; }

        Bubble(Context c) {
            super(c);
            gloss.setColor(Color.WHITE);
        }

        /** thin: 0 full height, 1 a seventh slimmer (stretched). lift: 0..1, held under a finger. */
        void set(float l, float r, float thinner, float held) {
            left = l;
            right = r;
            thin = thinner;
            lift = held;
            invalidate();
            if (getParent() instanceof View) ((View) getParent()).invalidate();   // the lens above it redraws too
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            fill.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF4E9AF6, 0xFF2A6FE0, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || right <= left) return;
            float l = Math.max(0f, left), r = Math.min(w, right);
            if (r - l < h * 0.7f) {                       // squeezed against an end: keep some body
                if (left < 0f) r = Math.min(w, l + h * 0.7f); else l = Math.max(0f, r - h * 0.7f);
            }
            float inset = h * 0.07f * Math.max(0f, Math.min(1f, thin));
            box.set(l, inset, r, h - inset);
            drawn.set(box);
            float rad = box.height() / 2f;
            c.drawRoundRect(box, rad, rad, fill);
            if (lift > 0.01f) {
                gloss.setAlpha(Math.round(46 * Math.min(1f, lift)));
                c.drawRoundRect(box, rad, rad, gloss);
            }
        }
    }

    /** The tab bar: taps go to the tabs as usual, but a finger that lands on the bubble can carry it. */
    /**
     * A bar with a bubble under its row of labels, where the bubble is a lens: inside its shape
     * the row is drawn again in white and a little magnified, so as its edge slides over a word
     * the word turns white letter by letter (and back again behind it), never all at once.
     */
    static class LensBar extends android.widget.FrameLayout {
        Bubble bubble;
        View row;
        float lens = 1.06f;
        private final Paint white = new Paint(), cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF at = new RectF();

        LensBar(Context c) {
            super(c);
            white.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN));
            cut.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (bubble == null || row == null || bubble.drawn.width() < 1f) return;
            at.set(bubble.drawn);
            at.offset(bubble.getLeft() + bubble.getTranslationX(), bubble.getTop() + bubble.getTranslationY());
            int keep = c.saveLayer(at, white);                // everything in here comes out white
            c.save();
            c.scale(lens, lens, at.centerX(), at.centerY());  // the lens: what's under it swells a little
            drawChild(c, row, getDrawingTime());
            c.restore();
            float rad = at.height() / 2f;
            c.drawRoundRect(at, rad, rad, cut);               // keep only what lies inside the bubble, edges smooth
            c.restoreToCount(keep);
        }
    }

    static final class DragBar extends LensBar {
        interface Drag {
            boolean grab(float x);
            void move(float x);
            void drop(float x);
        }

        interface Hold { void held(float x); }

        Drag drag;
        Hold hold;
        private float downX, downY;
        private boolean mine, dragging, held;
        private final int slop;
        private final Runnable longPress = () -> {
            if (hold == null || dragging) return;
            held = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            hold.held(downX);
        };

        DragBar(Context c) {
            super(c);
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                held = false;
                mine = drag != null && drag.grab(downX);
                removeCallbacks(longPress);
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(longPress);
            } else if (a == MotionEvent.ACTION_MOVE
                    && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)) {
                removeCallbacks(longPress);
            }
            if (held) return true;                         // the press opened the quick menu: the tab doesn't get it
            if (a == MotionEvent.ACTION_MOVE && mine && !dragging && Math.abs(e.getX() - downX) > slop) {
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (held) return true;
            if (!dragging) return super.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE) drag.move(e.getX());
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                mine = false;
                drag.drop(e.getX());
            }
            return true;
        }
    }

    // ---------- the live droplet ----------

    /**
     * A drop that buds off the top of the tab bar while the watch is connected: it swells out
     * of the bar on a neck of liquid, the neck thins and lets go, and the drop floats above.
     * Losing the watch draws it back in.
     */
    static final class Droplet extends View {
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path neck = new Path();
        private float f;                       // 0 inside the bar, 1 floating free
        private boolean live;
        private ValueAnimator run;

        Droplet(Context c) {
            super(c);
            white.setColor(0xF7FFFFFF);
            dot.setColor(GOOD);
            setVisibility(GONE);
        }

        void setLive(boolean on) {
            if (on == live) return;
            live = on;
            if (run != null) run.cancel();
            setVisibility(VISIBLE);
            run = ValueAnimator.ofFloat(f, on ? 1f : 0f);
            run.setDuration(on ? 1100 : 520);
            run.setInterpolator(on ? GENTLE : new DecelerateInterpolator(1.5f));
            run.addUpdateListener(a -> {
                f = (float) a.getAnimatedValue();
                if (!live && f < 0.01f) setVisibility(GONE);
                invalidate();
            });
            run.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f;
            float full = dp(getContext(), 19);
            float r = full * (0.7f + 0.3f * Math.min(1f, f));
            float cy = h + full - (h - dp(getContext(), 2)) * f;          // from below the edge up to its place
            float join = Math.max(0f, 1f - f / 0.78f);                    // the neck: thick, then thin, then gone
            if (join > 0f && cy < h) {
                float top = r * 0.92f * (0.35f + 0.65f * join), base = full * 1.5f * (0.4f + 0.6f * join);
                float waist = Math.min(top, base) * join * 0.75f, mid = (cy + h) / 2f;
                neck.reset();
                neck.moveTo(cx - top, cy);
                neck.quadTo(cx - waist, mid, cx - base, h);
                neck.lineTo(cx + base, h);
                neck.quadTo(cx + waist, mid, cx + top, cy);
                neck.close();
                c.drawPath(neck, white);
            }
            c.drawCircle(cx, cy, r, white);
            float show = Math.max(0f, (f - 0.55f) / 0.45f);
            if (show > 0f) {
                dot.setAlpha(Math.round(60 * Math.min(1f, show)));
                c.drawCircle(cx, cy, r * 0.5f * show, dot);
                dot.setAlpha(Math.round(255 * Math.min(1f, show)));
                c.drawCircle(cx, cy, r * 0.27f * show, dot);
            }
        }
    }

    // ---------- a stub that tears off ----------

    /**
     * Makes a view a ticket stub: pulled, it swings on its top-left corner and follows the
     * finger; let go early and it springs back; pulled far enough it comes away, tumbles off
     * and a new one slides in. torn runs once it has come away.
     */
    static void tearable(final View stub, final Runnable torn) {
        final float need = dp(stub.getContext(), 84);
        stub.setOnTouchListener(new View.OnTouchListener() {
            private float x0, y0;
            private boolean ready;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    ready = false;
                    v.animate().cancel();
                    v.setPivotX(0f);
                    v.setPivotY(0f);
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                float pull = (float) Math.hypot(Math.max(0f, dx), dy);
                if (a == MotionEvent.ACTION_MOVE) {
                    boolean far = pull > need;
                    if (far != ready) {
                        ready = far;
                        v.performHapticFeedback(far ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.CLOCK_TICK);
                    }
                    float held = far ? 1f : pull / need;                 // attached: it resists; torn: it follows
                    v.setRotation(Math.min(24f, 6f + 16f * held) * Math.signum(dy == 0 ? 1 : dy) * Math.min(1f, pull / (need * 0.3f)));
                    v.setTranslationX(Math.max(0f, dx) * (far ? 1f : 0.3f));
                    v.setTranslationY(dy * (far ? 1f : 0.3f));
                    return true;
                }
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (ready && a == MotionEvent.ACTION_UP) {
                        v.animate().translationXBy(dp(v.getContext(), 90)).translationYBy(dp(v.getContext(), 260))
                                .rotationBy(34f).alpha(0f).setStartDelay(0).setDuration(460)
                                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                                .withEndAction(() -> {
                                    torn.run();
                                    v.setRotation(0f);
                                    v.setTranslationY(0f);
                                    v.setTranslationX(dp(v.getContext(), 40));
                                    v.animate().translationX(0f).alpha(1f).setStartDelay(500).setDuration(760)
                                            .setInterpolator(GENTLE).start();
                                }).start();
                    } else {
                        v.animate().rotation(0f).translationX(0f).translationY(0f).setStartDelay(0).setDuration(760)
                                .setInterpolator(BOUNCY).start();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    // ---------- a track with a marked stretch ----------

    /**
     * A rounded bar with up to two filled stretches, a marker dot and small names under it:
     * the hours of a day with the off-hours filled, or a voltage scale with the saver zone filled.
     */
    static final class Track extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF bar = new RectF();
        private float s0, e0, s1, e1, mark = -1f;
        private boolean strong = true;
        private String[] names = {};
        private float[] at = {};

        Track(Context c) {
            super(c);
        }

        void names(String[] n, float[] where) {
            names = n;
            at = where;
        }

        /** Two stretches, each from..to as 0..1 of the bar (to <= from = none). strong = full colour. */
        void spans(float from0, float to0, float from1, float to1, boolean full) {
            s0 = from0;
            e0 = to0;
            s1 = from1;
            e1 = to1;
            strong = full;
            invalidate();
        }

        void mark(float where) {
            mark = where;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(dp(getContext(), 42)));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), top = dp(getContext(), 6), tall = dp(getContext(), 12);
            bar.set(0, top, w, top + tall);
            p.setColor(SOFT);
            p.setAlpha(46);
            c.drawRoundRect(bar, tall / 2, tall / 2, p);
            clip.reset();
            clip.addRoundRect(bar, tall / 2, tall / 2, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            p.setColor(ACCENT);
            p.setAlpha(strong ? 255 : 80);
            if (e0 > s0) c.drawRect(w * s0, top, w * e0, top + tall, p);
            if (e1 > s1) c.drawRect(w * s1, top, w * e1, top + tall, p);
            c.restore();
            if (mark >= 0f) {
                float x = Math.max(tall / 2, Math.min(w - tall / 2, w * mark));
                p.setColor(Color.WHITE);
                p.setAlpha(255);
                c.drawCircle(x, top + tall / 2, tall * 0.62f, p);
                p.setColor(TEXT);
                c.drawCircle(x, top + tall / 2, tall * 0.36f, p);
            }
            p.setColor(MUTED);
            p.setAlpha(255);
            p.setTextSize(dp(getContext(), 10.5f));
            for (int i = 0; i < names.length; i++) {
                float tw = p.measureText(names[i]);
                float x = Math.max(0f, Math.min(w - tw, w * at[i] - (at[i] > 0f ? tw / 2 : 0f)));
                c.drawText(names[i], x, top + tall + dp(getContext(), 17), p);
            }
        }
    }

    // ---------- drawn icons (instead of text symbols) ----------

    /**
     * Small icons drawn with lines, so they match the app's weight and colours on every phone
     * (text symbols like a check mark look different from font to font). A check draws itself in.
     */
    static final class Icon extends android.graphics.drawable.Drawable {
        static final int CHECK = 0, ALERT = 1, CHEVRON = 2, ARROW = 3, GRIP = 4, CLOSE = 5, BOLT = 6, DOT = 7,
                NOTE = 8, WAVE = 9, BELL = 10, PLAY = 11, LINK = 12, LEAF = 13;
        private final int kind, color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), part = new Path();
        private final android.graphics.PathMeasure measure = new android.graphics.PathMeasure();
        private float drawn = 1f;
        private boolean disc;

        Icon(int kind, int color, boolean inDisc) {
            this.kind = kind;
            this.color = color;
            disc = inDisc;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        /** The check draws itself from its short stroke to its long one. */
        Icon drawIn(View host) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(520);
            a.setStartDelay(120);
            a.setInterpolator(new DecelerateInterpolator(1.6f));
            a.addUpdateListener(x -> { drawn = (float) x.getAnimatedValue(); invalidateSelf(); if (host != null) host.invalidate(); });
            drawn = 0f;
            a.start();
            return this;
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), x = b.exactCenterX() - s / 2, y = b.exactCenterY() - s / 2;
            int ink = color;
            if (disc) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                c.drawCircle(x + s / 2, y + s / 2, s / 2, p);
                ink = Color.WHITE;
                x += s * 0.2f;
                y += s * 0.2f;
                s *= 0.6f;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setColor(ink);
            p.setStrokeWidth(Math.max(1.5f, s * (disc ? 0.16f : 0.12f)));
            path.reset();
            switch (kind) {
                case CHECK:
                    path.moveTo(x + s * 0.12f, y + s * 0.54f);
                    path.lineTo(x + s * 0.4f, y + s * 0.8f);
                    path.lineTo(x + s * 0.9f, y + s * 0.22f);
                    break;
                case ALERT:
                    path.moveTo(x + s / 2, y + s * 0.12f);
                    path.lineTo(x + s / 2, y + s * 0.58f);
                    path.moveTo(x + s / 2, y + s * 0.88f);
                    path.lineTo(x + s / 2, y + s * 0.89f);
                    break;
                case CHEVRON:
                    path.moveTo(x + s * 0.36f, y + s * 0.18f);
                    path.lineTo(x + s * 0.68f, y + s * 0.5f);
                    path.lineTo(x + s * 0.36f, y + s * 0.82f);
                    break;
                case ARROW:
                    path.moveTo(x + s * 0.1f, y + s * 0.5f);
                    path.lineTo(x + s * 0.88f, y + s * 0.5f);
                    path.moveTo(x + s * 0.6f, y + s * 0.24f);
                    path.lineTo(x + s * 0.88f, y + s * 0.5f);
                    path.lineTo(x + s * 0.6f, y + s * 0.76f);
                    break;
                case CLOSE:
                    path.moveTo(x + s * 0.24f, y + s * 0.24f);
                    path.lineTo(x + s * 0.76f, y + s * 0.76f);
                    path.moveTo(x + s * 0.76f, y + s * 0.24f);
                    path.lineTo(x + s * 0.24f, y + s * 0.76f);
                    break;
                case BOLT:
                    path.moveTo(x + s * 0.58f, y + s * 0.06f);
                    path.lineTo(x + s * 0.24f, y + s * 0.56f);
                    path.lineTo(x + s * 0.5f, y + s * 0.56f);
                    path.lineTo(x + s * 0.42f, y + s * 0.94f);
                    path.lineTo(x + s * 0.76f, y + s * 0.44f);
                    path.lineTo(x + s * 0.5f, y + s * 0.44f);
                    path.close();
                    break;
                case DOT:
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x + s / 2, y + s / 2, s * 0.22f, p);
                    return;
                case NOTE:                                     // a music note
                    path.moveTo(x + s * 0.42f, y + s * 0.74f);
                    path.lineTo(x + s * 0.42f, y + s * 0.14f);
                    path.lineTo(x + s * 0.78f, y + s * 0.26f);
                    path.addCircle(x + s * 0.3f, y + s * 0.76f, s * 0.13f, Path.Direction.CW);
                    break;
                case WAVE:                                     // a heartbeat line: keep-alives
                    path.moveTo(x + s * 0.06f, y + s * 0.55f);
                    path.lineTo(x + s * 0.3f, y + s * 0.55f);
                    path.lineTo(x + s * 0.42f, y + s * 0.22f);
                    path.lineTo(x + s * 0.58f, y + s * 0.82f);
                    path.lineTo(x + s * 0.7f, y + s * 0.5f);
                    path.lineTo(x + s * 0.94f, y + s * 0.5f);
                    break;
                case BELL:
                    path.moveTo(x + s * 0.22f, y + s * 0.7f);
                    path.lineTo(x + s * 0.28f, y + s * 0.38f);
                    path.quadTo(x + s * 0.5f, y + s * 0.02f, x + s * 0.72f, y + s * 0.38f);
                    path.lineTo(x + s * 0.78f, y + s * 0.7f);
                    path.close();
                    path.moveTo(x + s * 0.42f, y + s * 0.86f);
                    path.lineTo(x + s * 0.58f, y + s * 0.86f);
                    break;
                case PLAY:
                    path.moveTo(x + s * 0.3f, y + s * 0.18f);
                    path.lineTo(x + s * 0.8f, y + s * 0.5f);
                    path.lineTo(x + s * 0.3f, y + s * 0.82f);
                    path.close();
                    break;
                case LINK:                                     // two links of a chain
                    path.addRoundRect(new RectF(x + s * 0.08f, y + s * 0.34f, x + s * 0.56f, y + s * 0.66f), s * 0.16f, s * 0.16f, Path.Direction.CW);
                    path.addRoundRect(new RectF(x + s * 0.44f, y + s * 0.34f, x + s * 0.92f, y + s * 0.66f), s * 0.16f, s * 0.16f, Path.Direction.CW);
                    break;
                case LEAF:                                     // saver mode
                    path.moveTo(x + s * 0.2f, y + s * 0.82f);
                    path.quadTo(x + s * 0.12f, y + s * 0.2f, x + s * 0.84f, y + s * 0.16f);
                    path.quadTo(x + s * 0.82f, y + s * 0.8f, x + s * 0.2f, y + s * 0.82f);
                    path.moveTo(x + s * 0.2f, y + s * 0.82f);
                    path.lineTo(x + s * 0.56f, y + s * 0.46f);
                    break;
                default:                                       // GRIP: three short lines
                    for (int i = 0; i < 3; i++) {
                        float yy = y + s * (0.28f + 0.22f * i);
                        path.moveTo(x + s * 0.18f, yy);
                        path.lineTo(x + s * 0.82f, yy);
                    }
            }
            if (drawn >= 1f) {
                c.drawPath(path, p);
                return;
            }
            measure.setPath(path, false);
            part.reset();
            measure.getSegment(0, measure.getLength() * drawn, part, true);
            c.drawPath(part, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** A view that shows one icon. */
    static View icon(Context c, int kind, int color, boolean inDisc) {
        View v = new View(c);
        v.setBackground(new Icon(kind, color, inDisc));
        return v;
    }

    /** Text with an icon at its end (a check after "All set", say). */
    static void trail(android.widget.TextView t, Icon i) {
        if (i == null) { t.setCompoundDrawablesRelative(null, null, null, null); return; }
        int s = Math.round(t.getTextSize() * 1.05f);
        i.setBounds(0, 0, s, s);
        t.setCompoundDrawablesRelative(null, null, i, null);
        t.setCompoundDrawablePadding(Math.round(dp(t.getContext(), 6)));
    }

    /** Puts a drawn arrow wherever the text has "→". */
    static CharSequence arrows(Context c, String s, int color, float textPx) {
        if (s.indexOf('→') < 0) return s;
        android.text.SpannableString out = new android.text.SpannableString(s);
        for (int i = s.indexOf('→'); i >= 0; i = s.indexOf('→', i + 1)) {
            Icon a = new Icon(Icon.ARROW, color, false);
            int sz = Math.round(textPx);
            a.setBounds(0, 0, sz, sz);
            out.setSpan(new android.text.style.ImageSpan(a, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                    i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    // ---------- the watch's segments ----------

    /** Which of the 7 segments light for each character from space to ~ (bits: a b c d e f g), from the Forge glyph chart. */
    private static final int[] SEGS = {0,96,34,99,109,0,68,2,57,15,64,112,16,64,64,18,63,6,91,79,102,109,125,7,127,111,0,0,88,72,76,83,127,119,127,57,63,121,113,61,118,48,30,117,56,55,55,63,115,103,80,109,120,62,62,62,126,110,27,57,36,15,35,8,32,95,124,88,94,123,113,111,116,16,30,117,48,55,84,92,115,103,80,109,120,28,28,62,126,110,27,60,54,30,84};

    static int segs(char ch) {
        return ch < 32 || ch > 126 ? 0 : SEGS[ch - 32];
    }

    private static final Path segPath = new Path();

    /**
     * One seven-segment character in the box x,y,w,h, leaning right like a Casio digit. Each bar
     * is a long hexagon with a hair of gap at its ends; unlit bars are drawn faintly, as on a real LCD.
     */
    static void seg(Canvas c, Paint p, float x, float y, float w, float h, int mask, int lit, int ghost, boolean shadow) {
        float t = w * 0.19f, gap = t * 0.22f, lean = w * 0.13f;
        float l = x + t / 2, r = x + w - t / 2, top = y + t / 2, mid = y + h / 2, bot = y + h - t / 2;
        float[][] bars = {
                {l, top, r, top}, {r, top, r, mid}, {r, mid, r, bot}, {l, bot, r, bot},
                {l, mid, l, bot}, {l, top, l, mid}, {l, mid, r, mid}};
        for (int pass = 0; pass < (shadow ? 3 : 2); pass++) {
            for (int i = 0; i < 7; i++) {
                boolean on = (mask & (1 << i)) != 0;
                if (pass == 0 && on) continue;                    // pass 0: the unlit bars
                if (pass > 0 && !on) continue;
                float sx = pass == 1 && shadow ? t * 0.35f : 0f, sy = pass == 1 && shadow ? t * 0.45f : 0f;
                p.setColor(pass == 0 ? ghost : pass == 1 && shadow ? (lit & 0x00FFFFFF) | 0x26000000 : lit);
                float[] b = bars[i];
                segPath.reset();
                if (b[1] == b[3]) {                                // across
                    float x0 = b[0] + gap, x1 = b[2] - gap, yy = b[1], k = t / 2;
                    pt(true, x0, yy, y, h, lean, sx, sy);
                    pt(false, x0 + k, yy - k, y, h, lean, sx, sy);
                    pt(false, x1 - k, yy - k, y, h, lean, sx, sy);
                    pt(false, x1, yy, y, h, lean, sx, sy);
                    pt(false, x1 - k, yy + k, y, h, lean, sx, sy);
                    pt(false, x0 + k, yy + k, y, h, lean, sx, sy);
                } else {                                           // up and down
                    float y0 = b[1] + gap, y1 = b[3] - gap, xx = b[0], k = t / 2;
                    pt(true, xx, y0, y, h, lean, sx, sy);
                    pt(false, xx + k, y0 + k, y, h, lean, sx, sy);
                    pt(false, xx + k, y1 - k, y, h, lean, sx, sy);
                    pt(false, xx, y1, y, h, lean, sx, sy);
                    pt(false, xx - k, y1 - k, y, h, lean, sx, sy);
                    pt(false, xx - k, y0 + k, y, h, lean, sx, sy);
                }
                segPath.close();
                c.drawPath(segPath, p);
            }
        }
    }

    private static void pt(boolean first, float x, float yy, float y, float h, float lean, float sx, float sy) {
        float px = x + lean * (1f - (yy - y) / h) + sx, py = yy + sy;
        if (first) segPath.moveTo(px, py); else segPath.lineTo(px, py);
    }

    static final int LCD_BG_TOP = 0xFFE1E7EA, LCD_BG_BOT = 0xFFC8D1D6, LCD_INK = 0xFF0F1E2E, LCD_GHOST = 0x100F1E2E;

    /**
     * The watch, drawn in the app's own style: a frosted glass case with the F-91W's cut corners,
     * a deep navy bezel with blue pin-lines and the button names, and the LCD inside laid out like
     * the real screen (PM and 24H, the two day characters and LAP along the top; hours and minutes
     * big with the colon, seconds smaller). The side buttons press in when the watch's buttons are pressed.
     */
    static final class Lcd extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path oct = new Path();
        private final RectF box = new RectF();
        private android.graphics.Shader caseFill, bezelFill, lcdFill, glare;
        private String main = "", day = "";
        private boolean colon;
        private int shift;

        Lcd(Context c) {
            super(c);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            label.setLetterSpacing(0.12f);
        }

        void set(String m, String d, boolean dots) {
            if (!m.equals(main)) {
                main = m;
                shift = 0;
            }
            day = d;
            colon = dots;
            invalidate();
        }

        /** One step of the scroll (and a redraw for a button press). */
        void step() {
            if (main.length() > 6) shift = (shift + 1) % (main.length() + 3);
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.round(width * 0.76f));
        }

        // the watch is drawn in a box 100 units wide (the view's width), 76 tall
        private float U;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            U = w / 100f;
            caseFill = new android.graphics.LinearGradient(0, 8 * U, 0, 68 * U, 0xFFFFFFFF, 0xFFD9E7F9, Shader.TileMode.CLAMP);
            bezelFill = new android.graphics.LinearGradient(0, 0, 0, h, 0x33A6C8F5, 0x55A6C8F5, Shader.TileMode.CLAMP);
            lcdFill = new android.graphics.LinearGradient(0, 30 * U, 0, 52 * U, LCD_BG_TOP, LCD_BG_BOT, Shader.TileMode.CLAMP);
            glare = new android.graphics.LinearGradient(30 * U, 30 * U, 46 * U, 52 * U, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setPathEffect(new android.graphics.CornerPathEffect(3.2f * U));
            p.setPathEffect(null);
        }

        private void octagon(float l, float t, float r, float b, float k) {
            oct.reset();
            oct.moveTo(l + k, t);
            oct.lineTo(r - k, t);
            oct.lineTo(r, t + k);
            oct.lineTo(r, b - k);
            oct.lineTo(r - k, b);
            oct.lineTo(l + k, b);
            oct.lineTo(l, b - k);
            oct.lineTo(l, t + k);
            oct.close();
        }

        /** 0..1: how far a button is pressed in, for a short while after the watch reported it. */
        private float press(char key) {
            if (History.pressKey != key && History.pressKey != Character.toUpperCase(key)) return 0f;
            boolean hold = Character.isUpperCase(History.pressKey);
            float t = (android.os.SystemClock.elapsedRealtime() - History.pressAt) / (hold ? 900f : 420f);
            if (t < 0f || t >= 1f) return 0f;
            postInvalidateOnAnimation();
            return t < 0.22f ? t / 0.22f : 1f - (t - 0.22f) / 0.78f;
        }

        private void nub(Canvas c, float x, float y, float w, float h, boolean left, float in) {
            float dx = (left ? 1 : -1) * w * 0.45f * in;
            box.set(x + dx, y, x + w + dx, y + h);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(blend(0xFFF7FAFE, ACCENT, in));
            c.drawRoundRect(box, h * 0.3f, h * 0.3f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, w * 0.07f));
            p.setColor(blend(0xFF2C5A8C, 0xFF2A6FE0, in));
            c.drawRoundRect(box, h * 0.3f, h * 0.3f, p);
            p.setStyle(Paint.Style.FILL);
        }

        static int mix(int a, int b, float f) { return blend(a, b, f); }

        private static int blend(int a, int b, float f) {
            f = Math.max(0f, Math.min(1f, f));
            int r = 0;
            for (int s = 0; s < 32; s += 8) {
                int x = (a >>> s) & 255, y = (b >>> s) & 255;
                r |= Math.round(x + (y - x) * f) << s;
            }
            return r;
        }

        private void tri(Canvas c, float x, float y, float s, boolean pointLeft) {
            oct.reset();
            if (pointLeft) { oct.moveTo(x, y); oct.lineTo(x + s, y - s * 0.55f); oct.lineTo(x + s, y + s * 0.55f); }
            else { oct.moveTo(x + s, y); oct.lineTo(x, y - s * 0.55f); oct.lineTo(x, y + s * 0.55f); }
            oct.close();
            p.setColor(0xFF4A97F5);
            c.drawPath(oct, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (caseFill == null) return;
            float u = U;
            float cl = 17 * u, cr = 83 * u, ct = 8 * u, cb = 68 * u, k = 12 * u;
            android.graphics.PathEffect round = stroke.getPathEffect();

            // the strap, behind: frosted blue glass going off the top and bottom
            p.setShader(bezelFill);
            box.set(29 * u, 0, 71 * u, 14 * u);
            c.drawRect(box, p);
            box.set(29 * u, 62 * u, 71 * u, 76 * u);
            c.drawRect(box, p);
            p.setShader(null);

            // the buttons and their letters, like the Forge emulator: L and M on the left, A on the right
            float nw = 6 * u, nh = 5.4f * u;
            nub(c, cl - nw * 0.75f, 26 * u, nw, nh, true, press('l'));
            nub(c, cl - nw * 0.75f, 44 * u, nw, nh, true, 0f);
            nub(c, cr - nw * 0.25f, 44 * u, nw, nh, false, press('a'));
            label.setTextSize(5.2f * u);
            label.setLetterSpacing(0f);
            label.setColor(TEXT);
            c.drawText("L", cl - nw - 5.4f * u, 30.6f * u, label);
            c.drawText("M", cl - nw - 6.6f * u, 48.6f * u, label);
            c.drawText("A", cr + nw + 1.2f * u, 48.6f * u, label);

            // the case: frosted white glass, the emulator's shape with softened corners
            p.setPathEffect(round);
            octagon(cl, ct + 1.2f * u, cr, cb + 1.2f * u, k);
            p.setColor(0x262F80ED);
            c.drawPath(oct, p);
            octagon(cl, ct, cr, cb, k);
            p.setShader(caseFill);
            c.drawPath(oct, p);
            p.setShader(null);
            p.setPathEffect(null);
            stroke.setStrokeWidth(Math.max(1f, 0.5f * u));
            stroke.setColor(0xFFFFFFFF);
            c.drawPath(oct, stroke);
            // the bezel line: where the emulator draws orange, Pulse draws its blue
            octagon(cl + 4 * u, ct + 4 * u, cr - 4 * u, cb - 4 * u, 10 * u);
            stroke.setStrokeWidth(1.1f * u);
            stroke.setColor(0xFF4A97F5);
            c.drawPath(oct, stroke);

            // the window
            float wx = 27 * u, wy = 30 * u, ww = 46 * u, wh = ww * 0.46f;
            box.set(wx - 1.4f * u, wy - 1.4f * u, wx + ww + 1.4f * u, wy + wh + 1.4f * u);
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(box, 3.6f * u, 3.6f * u, p);
            stroke.setPathEffect(null);
            stroke.setStrokeWidth(Math.max(1f, 0.45f * u));
            stroke.setColor(0xFF2C5A8C);
            c.drawRoundRect(box, 3.6f * u, 3.6f * u, stroke);
            stroke.setPathEffect(round);
            box.set(wx, wy, wx + ww, wy + wh);
            p.setShader(lcdFill);
            c.drawRoundRect(box, 2.4f * u, 2.4f * u, p);
            p.setShader(null);

            // the name under the window
            label.setTextSize(2.6f * u);
            label.setLetterSpacing(0.5f);
            label.setColor(0x992C5A8C);
            float nwid = label.measureText("PULSE");
            c.drawText("PULSE", 50 * u - nwid / 2f, wy + wh + 7.5f * u, label);

            // inside the LCD: 100 units across the window
            float v = ww / 100f;
            c.save();
            c.translate(wx, wy);
            String six = main;
            if (six.length() > 6) six = (main + "   " + main).substring(shift, shift + 6);
            while (six.length() < 6) six += " ";
            String two = day.length() > 2 ? day.substring(0, 2) : day;
            while (two.length() < 2) two = " " + two;
            label.setLetterSpacing(0.08f);
            label.setColor(0x330F1E2E);
            label.setTextSize(3.6f * v);
            c.drawText("PM", 6 * v, 13 * v, label);
            label.setTextSize(2.6f * v);
            c.drawText("24H", 15 * v, 13 * v, label);
            label.setTextSize(3.6f * v);
            c.drawText("LAP", 62 * v, 11 * v, label);
            label.setTextSize(2.8f * v);
            c.drawText("SPLIT", 74 * v, 19 * v, label);
            seg(c, p, 31 * v, 4.5f * v, 7.2f * v, 12 * v, segs(two.charAt(0)), LCD_INK, LCD_GHOST, true);
            seg(c, p, 40 * v, 4.5f * v, 7.2f * v, 12 * v, segs(two.charAt(1)), LCD_INK, LCD_GHOST, true);
            float bigY = 20 * v, bigH = 22 * v, bigW = 11.5f * v;
            float[] bx = {6 * v, 19.5f * v, 36.5f * v, 50 * v};
            for (int i = 0; i < 4; i++) seg(c, p, bx[i], bigY, bigW, bigH, segs(six.charAt(i)), LCD_INK, LCD_GHOST, true);
            p.setColor(colon ? LCD_INK : LCD_GHOST);
            float cx = 33.2f * v;
            c.drawRect(cx + 1.0f * v, bigY + 5.5f * v, cx + 3.0f * v, bigY + 7.5f * v, p);
            c.drawRect(cx, bigY + 14.5f * v, cx + 2.0f * v, bigY + 16.5f * v, p);
            float smallH = 14.5f * v, smallW = 7.8f * v, smallY = bigY + bigH - smallH;
            seg(c, p, 67 * v, smallY, smallW, smallH, segs(six.charAt(4)), LCD_INK, LCD_GHOST, true);
            seg(c, p, 77.5f * v, smallY, smallW, smallH, segs(six.charAt(5)), LCD_INK, LCD_GHOST, true);
            c.restore();
            box.set(wx, wy, wx + ww, wy + wh);
            p.setShader(glare);
            c.drawRoundRect(box, 2.4f * u, 2.4f * u, p);
            p.setShader(null);
        }
    }

    /** A name the way the watch's six main characters show it: a small strip of LCD. */
    static final class SegText extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private String text = "";

        SegText(Context c) {
            super(c);
        }

        void set(String s) {
            text = s == null ? "" : s;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(dp(getContext(), 118)), Math.round(dp(getContext(), 36)));
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            box.set(0, 0, W, H);
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, H, LCD_BG_TOP, LCD_BG_BOT, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, H * 0.28f, H * 0.28f, p);
            p.setShader(null);
            float ch = H * 0.62f, cw = ch * 0.52f, step = (W - H * 0.5f) / 6f, x = H * 0.25f + (step - cw) / 2f;
            for (int i = 0; i < 6; i++) {
                char k = i < text.length() ? text.charAt(i) : ' ';
                seg(c, p, x + i * step, (H - ch) / 2f, cw, ch, segs(k), LCD_INK, LCD_GHOST, false);
            }
        }
    }

    // ---------- a list you rearrange by holding a row ----------

    /**
     * Hold a row and it lifts off the card (bigger, with a shadow); drag it and the other rows
     * spring out of its way; let go and it drops into the gap with a bounce. It tilts a little
     * with how fast it is moved, like something with weight.
     */
    static final class DragList extends android.widget.LinearLayout {
        interface Moved { void moved(int from, int to); }

        Moved moved;
        private View held;
        private int from, to;
        private float downX, downY, lastY, tilt;
        private long lastT;
        private boolean armed, dragging;
        private final int slop;
        private final Runnable lift = this::lift;

        DragList(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private View rowAt(float y) {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (y >= v.getTop() && y < v.getBottom()) return v;
            }
            return null;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            watch(e);
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            watch(e);
            return true;
        }

        private void watch(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = lastY = e.getY();
                    lastT = e.getEventTime();
                    held = rowAt(downY);
                    dragging = false;
                    armed = held != null;
                    removeCallbacks(lift);
                    if (armed) postDelayed(lift, android.view.ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (armed && !dragging && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)) {
                        armed = false;
                        removeCallbacks(lift);
                    }
                    if (dragging) move(e.getY(), e.getEventTime());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(lift);
                    armed = false;
                    if (dragging) drop();
                    break;
                default:
            }
        }

        private void lift() {
            if (!armed || held == null) return;
            armed = false;
            dragging = true;
            from = to = indexOfChild(held);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            held.animate().cancel();
            held.animate().scaleX(1.05f).scaleY(1.05f).translationZ(dp(getContext(), 14)).setDuration(320)
                    .setInterpolator(GENTLE).start();
            // the press on a switch or a name underneath is called off
            MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            held.dispatchTouchEvent(cancel);
            cancel.recycle();
        }

        private void move(float y, long t) {
            float dy = y - downY;
            held.setTranslationY(dy);
            float speed = (y - lastY) / Math.max(1, t - lastT);          // px per ms
            tilt = tilt * 0.75f + Math.max(-3.5f, Math.min(3.5f, speed * 2.2f)) * 0.25f;
            held.setRotation(tilt);
            lastY = y;
            lastT = t;
            float center = held.getTop() + held.getHeight() / 2f + dy;
            int target = from;
            for (int i = from + 1; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (center > v.getTop() + v.getHeight() / 2f) target = i;
            }
            for (int i = from - 1; i >= 0; i--) {
                View v = getChildAt(i);
                if (center < v.getTop() + v.getHeight() / 2f) target = i;
            }
            if (target == to) return;
            to = target;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            float room = held.getHeight() + space();
            for (int i = 0; i < getChildCount(); i++) {
                if (i == from) continue;
                float shift = from < i && i <= to ? -room : to <= i && i < from ? room : 0f;
                View v = getChildAt(i);
                v.animate().translationY(shift).setDuration(460).setInterpolator(GENTLE).start();
            }
        }

        /** The gap between two rows. */
        private float space() {
            if (getChildCount() < 2) return 0f;
            return getChildAt(1).getTop() - getChildAt(0).getBottom();
        }

        private void drop() {
            dragging = false;
            final View v = held;
            final int a = from, b = to;
            float end = b > a ? getChildAt(b).getBottom() - v.getBottom()
                    : b < a ? getChildAt(b).getTop() - v.getTop() : 0f;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            tilt = 0f;
            v.animate().translationY(end).scaleX(1f).scaleY(1f).rotation(0f).translationZ(0f).setDuration(560)
                    .setInterpolator(BOUNCY).withEndAction(() -> {
                        for (int i = 0; i < getChildCount(); i++) {
                            getChildAt(i).animate().cancel();
                            getChildAt(i).setTranslationY(0f);
                        }
                        if (a != b) {
                            removeView(v);
                            addView(v, b);
                            if (moved != null) moved.moved(a, b);
                        }
                    }).start();
        }
    }

    // ---------- numbers that roll ----------

    /**
     * Text whose characters roll to their new values like an odometer: each changed character
     * slides out as the new one slides in from below (going up) or above (going down), a beat
     * after its neighbour, smeared while it moves and landing with a small spring.
     */
    static final class RollText extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String now = "", was = "";
        private float f = 1f;
        private ValueAnimator run;

        RollText(Context c, float sp, int color, android.graphics.Typeface face) {
            super(c);
            p.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
            p.setColor(color);
            p.setTypeface(face);
            p.setFontFeatureSettings("tnum");       // every digit the same width, so nothing shuffles sideways
        }

        void set(String s, boolean animate) {
            if (s.equals(now)) return;
            was = now;
            now = s;
            if (run != null) run.cancel();
            requestLayout();
            if (!animate || was.isEmpty()) { f = 1f; invalidate(); return; }
            f = 0f;
            run = ValueAnimator.ofFloat(0f, 1f);
            run.setDuration(1100);
            run.setInterpolator(null);
            run.addUpdateListener(a -> { f = a.getAnimatedFraction(); invalidate(); });
            run.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            Paint.FontMetrics m = p.getFontMetrics();
            float wide = Math.max(p.measureText(now), p.measureText(was));
            setMeasuredDimension(Math.round(wide + dp(getContext(), 4)), Math.round(m.descent - m.ascent));
        }

        @Override
        protected void onDraw(Canvas c) {
            Paint.FontMetrics m = p.getFontMetrics();
            float base = -m.ascent, lineH = m.descent - m.ascent;
            c.save();
            c.clipRect(0, 0, getWidth(), getHeight());
            int n = now.length(), color = p.getColor();
            float x = 0f;
            for (int i = 0; i < n; i++) {
                char to = now.charAt(i), from = i < was.length() ? was.charAt(i) : ' ';
                float cw = p.measureText(String.valueOf(to));
                if (to == from || f >= 1f) {
                    c.drawText(String.valueOf(to), x, base, p);
                    x += cw;
                    continue;
                }
                float start = 0.07f * (n - 1 - i);              // the rightmost character goes first
                float t = Math.max(0f, Math.min(1f, (f - start) / Math.max(0.3f, 1f - start)));
                float eased = GENTLE.getInterpolation(t);
                float dir = Character.isDigit(to) && Character.isDigit(from) && to < from ? -1f : 1f;
                float out = -dir * lineH * eased, in = dir * lineH * (1f - eased);
                float smear = (float) Math.sin(Math.PI * Math.min(1f, t)) * 0.22f * lineH;
                for (int k = -1; k <= 1; k++) {                 // a blurred trail while it moves
                    int alpha = k == 0 ? 255 : Math.round(70 * Math.min(1f, smear / (0.1f * lineH)));
                    p.setAlpha(Math.round(alpha * (1f - eased)));
                    c.drawText(String.valueOf(from), x, base + out + k * smear, p);
                    p.setAlpha(Math.round(alpha * Math.min(1f, eased * 1.4f)));
                    c.drawText(String.valueOf(to), x, base + in + k * smear, p);
                }
                p.setColor(color);
                x += cw;
            }
            p.setAlpha(255);
            c.restore();
        }
    }

    /** A ring that fills to a fraction with a spring, its end rounded. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float shown, goal;
        private ValueAnimator run;

        Ring(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0x262F80ED);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(float fraction) {
            float g = Math.max(0f, Math.min(1f, fraction));
            if (Math.abs(g - goal) < 0.001f && run != null) return;
            goal = g;
            if (run != null) run.cancel();
            run = ValueAnimator.ofFloat(shown, g);
            run.setDuration(1100);
            run.setInterpolator(BOUNCY);
            run.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
            run.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), w = s * 0.13f;
            track.setStrokeWidth(w);
            arc.setStrokeWidth(w);
            box.set(w / 2 + (getWidth() - s) / 2, w / 2 + (getHeight() - s) / 2,
                    (getWidth() + s) / 2 - w / 2, (getHeight() + s) / 2 - w / 2);
            c.drawArc(box, 0, 360, false, track);
            arc.setColor(goal >= 0.9f ? WARN : ACCENT);
            if (shown > 0.002f) c.drawArc(box, -90, Math.max(1f, 360 * Math.min(1.08f, shown)), false, arc);
        }
    }

    // ---------- the connect button ----------

    /**
     * Connect, linking and Disconnect in one button, after the "Book a call" button. Tapped, its
     * word swells and melts away while two discs, the phone and the watch, pop up in the middle
     * with a "+" breathing between them. Once linked, the watch slides over to tuck in beside the
     * phone, overlapping it, and "Disconnect" glides in from the right as the button turns to glass.
     */
    static final class LinkPill extends View {
        static final int OFF = 0, LINKING = 1, LINKED = 2;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int state = -1;
        private final float[] w = {1, 0, 0}, from = new float[3], to = new float[3];   // how much of each state
        private float pop = 0f, popFrom;                                                  // the discs' size
        private ValueAnimator run;
        private final long born = android.os.SystemClock.uptimeMillis();
        private android.graphics.Shader blue;

        LinkPill(Context c) {
            super(c);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            text.setTextSize(15 * c.getResources().getDisplayMetrics().scaledDensity);
            setClickable(true);
            spring(this);
        }

        void set(int s) {
            if (s == state) return;
            boolean first = state < 0;
            state = s;
            System.arraycopy(w, 0, from, 0, 3);
            to[0] = s == OFF ? 1 : 0;
            to[1] = s == LINKING ? 1 : 0;
            to[2] = s == LINKED ? 1 : 0;
            popFrom = pop;
            final float popTo = s == OFF ? 0f : 1f;
            if (run != null) run.cancel();
            if (first) { System.arraycopy(to, 0, w, 0, 3); pop = popTo; invalidate(); return; }
            run = ValueAnimator.ofFloat(0f, 1f);
            run.setDuration(s == LINKED ? 900 : 700);
            run.setInterpolator(null);
            run.addUpdateListener(a -> {
                float f = a.getAnimatedFraction(), g = GENTLE.getInterpolation(f);
                for (int i = 0; i < 3; i++) w[i] = from[i] + (to[i] - from[i]) * g;
                pop = popFrom + (popTo - popFrom) * (popTo > popFrom ? BOUNCY.getInterpolation(f) : g);
                invalidate();
            });
            run.start();
            if (s == LINKED) performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            setMeasuredDimension(MeasureSpec.getSize(wm), Math.round(dp(getContext(), 46)));
        }

        @Override
        protected void onSizeChanged(int nw, int nh, int ow, int oh) {
            blue = new android.graphics.LinearGradient(0, 0, nw, nh, 0xFF4A97F5, 0xFF2A6FE0, Shader.TileMode.CLAMP);
        }

        private void disc(Canvas c, float cx, float cy, float r, boolean watch, boolean onBlue) {
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(onBlue ? 0xFFFFFFFF : 0xFFEAF2FD);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.12f);
            p.setColor(onBlue ? 0xFF2A6FE0 : 0xFFFFFFFF);
            c.drawCircle(cx, cy, r, p);
            p.setColor(ACCENT);
            p.setStrokeWidth(r * 0.13f);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            if (watch) {
                box.set(cx - r * 0.36f, cy - r * 0.36f, cx + r * 0.36f, cy + r * 0.36f);
                c.drawRoundRect(box, r * 0.14f, r * 0.14f, p);
                c.drawLine(cx - r * 0.2f, cy - r * 0.36f, cx - r * 0.2f, cy - r * 0.6f, p);
                c.drawLine(cx + r * 0.2f, cy - r * 0.36f, cx + r * 0.2f, cy - r * 0.6f, p);
                c.drawLine(cx - r * 0.2f, cy + r * 0.36f, cx - r * 0.2f, cy + r * 0.6f, p);
                c.drawLine(cx + r * 0.2f, cy + r * 0.36f, cx + r * 0.2f, cy + r * 0.6f, p);
            } else {
                box.set(cx - r * 0.3f, cy - r * 0.52f, cx + r * 0.3f, cy + r * 0.52f);
                c.drawRoundRect(box, r * 0.12f, r * 0.12f, p);
                c.drawLine(cx - r * 0.08f, cy + r * 0.36f, cx + r * 0.08f, cy + r * 0.36f, p);
            }
            p.setStyle(Paint.Style.FILL);
        }

        /** A word, with a soft smear while it moves. */
        private void word(Canvas c, String s, float x, float y, int color, float alpha, float smear) {
            if (alpha <= 0.01f) return;
            text.setColor(color);
            if (smear > 0.5f) {
                text.setAlpha(Math.round(70 * alpha));
                c.drawText(s, x - smear, y, text);
                c.drawText(s, x + smear, y, text);
            }
            text.setAlpha(Math.round(255 * alpha));
            c.drawText(s, x, y, text);
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), h = getHeight(), r = h / 2f;
            float glass = w[2], onBlueF = 1f - glass;
            box.set(0, 0, W, h);
            if (blue != null) {
                p.setShader(blue);
                p.setAlpha(Math.round(255 * onBlueF));
                c.drawRoundRect(box, r, r, p);
                p.setShader(null);
            }
            if (glass > 0f) {
                p.setColor(0xFFFFFFFF);
                p.setAlpha(Math.round(240 * glass));
                c.drawRoundRect(box, r, r, p);
                p.setAlpha(255);
            }
            p.setAlpha(255);
            boolean onBlue = onBlueF > 0.5f;
            float base = r - (text.descent() + text.ascent()) / 2f;
            // "Connect": swells a little and melts as the discs arrive
            float tw = text.measureText("Connect");
            c.save();
            float grow = 1f + 0.12f * (1f - w[0]);
            c.scale(grow, grow, W / 2f, r);
            word(c, "Connect", (W - tw) / 2f, base, 0xFFFFFFFF, w[0] * w[0], 4f * (1f - w[0]) * dp(getContext(), 1));
            c.restore();
            // the discs: in the middle with a "+" between while linking, tucked together at the left once linked
            float dr = r * 0.66f * pop;
            float px = w[0] * (W / 2f - r * 1.3f) + w[1] * (W / 2f - r * 1.3f) + w[2] * (r * 1.05f);
            float wx = w[0] * (W / 2f + r * 1.3f) + w[1] * (W / 2f + r * 1.3f) + w[2] * (r * 1.85f);
            float plus = w[1] * pop;
            if (plus > 0.01f) {
                float mid = (px + wx) / 2f, t = (android.os.SystemClock.uptimeMillis() - born) / 140f;
                float arm = r * 0.2f * (1f + 0.18f * (float) Math.sin(t)) * plus;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(r * 0.12f);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(Math.round(255 * plus));
                c.drawLine(mid - arm, r, mid + arm, r, p);
                c.drawLine(mid, r - arm, mid, r + arm, p);
                p.setStyle(Paint.Style.FILL);
                p.setAlpha(255);
                postInvalidateOnAnimation();
            }
            if (dr > 1f) {
                disc(c, px, r, dr, false, onBlue);       // the phone first, the watch slides over it
                disc(c, wx, r, dr, true, onBlue);
            }
            // "Disconnect": glides in from the right
            float dw = text.measureText("Disconnect");
            float left = Math.max((W - dw) / 2f, r * 1.85f + r * 0.66f + r * 0.5f);
            float slide = (1f - w[2]) * dp(getContext(), 26);
            word(c, "Disconnect", left + slide, base, TEXT, w[2], slide * 0.25f);
        }
    }

    // ---------- the drain meter as a list or a chart ----------

    /**
     * The drain meter, two ways. As a list, each row is a little chip with its name and numbers.
     * Switched to a chart, the rows shrink into their chips, the chips float loose for a moment,
     * then each settles on top of a bar that grows up under it with a bounce. Switching back
     * runs the same in reverse.
     */
    static final class DrainChart extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final String[] names = {"Messages sent", "Music", "Keep-alives", "Notifications", "Animated"},
                shortNames = {"Sent", "Music", "Keep", "Notes", "Anim"};
        private final int[] kinds = {Icon.ARROW, Icon.NOTE, Icon.WAVE, Icon.BELL, Icon.PLAY};
        private final int[] colors = {0xFF2F80ED, 0xFF8E6CEF, 0xFF27AE60, 0xFFF2994A, 0xFFEC6A8C};
        private final Icon[] icons = new Icon[5];
        private String[] now = {"0", "0", "0", "0", "0"}, all = {"0", "0", "0", "0", "0"};
        private float[] size = new float[5];
        private float f;                     // 0 list, 1 chart
        private ValueAnimator run;
        private boolean chart;

        DrainChart(Context c) {
            super(c);
            for (int i = 0; i < 5; i++) icons[i] = new Icon(kinds[i], colors[i], false);
            t.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        }

        /** The numbers: this hour and over the kept hours (as text), and the values the bars use. */
        void set(String[] hour, String[] total, float[] amounts) {
            now = hour;
            all = total;
            float max = 0f;
            for (float a : amounts) max = Math.max(max, a);
            for (int i = 0; i < 5; i++) size[i] = max <= 0 ? 0f : amounts[i] / max;
            invalidate();
        }

        boolean isChart() { return chart; }

        void show(boolean asChart) {
            if (asChart == chart) return;
            chart = asChart;
            if (run != null) run.cancel();
            run = ValueAnimator.ofFloat(f, asChart ? 1f : 0f);
            run.setDuration(Math.round(2400 * Math.abs((asChart ? 1f : 0f) - f)) + 1);
            run.setInterpolator(null);
            run.addUpdateListener(a -> { f = (float) a.getAnimatedValue(); invalidate(); });
            run.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(dp(getContext(), 262)));
        }

        private float dpx(float v) { return dp(getContext(), v); }

        private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), r = dpx(16);
            float base = H - dpx(40), top = dpx(34), maxBar = base - top - r * 2 - dpx(8);
            float slot = W / 5f, bw = r * 2 + dpx(6);
            // headers of the list
            t.setTextSize(dpx(12));
            t.setColor(MUTED);
            t.setAlpha(Math.round(255 * (1f - clamp(f / 0.12f))));
            String h1 = "This hour", h2 = "Last 24 h";
            c.drawText(h1, W - dpx(96) - t.measureText(h1), dpx(14), t);
            c.drawText(h2, W - dpx(4) - t.measureText(h2), dpx(14), t);
            float grow = clamp((f - 0.74f) / 0.24f);
            for (int i = 0; i < 5; i++) {
                float ly = rowY(i), lx = dpx(4) + r;
                // 1. the row shrinks into its chip, the bottom row first
                float k = collapse(i);
                if (k < 1f) {
                    float rowW = (W - dpx(4)) * (1f - GENTLE.getInterpolation(k)) + (r * 2 + dpx(4)) * GENTLE.getInterpolation(k);
                    box.set(lx - r - dpx(2), ly - r - dpx(4), lx - r - dpx(2) + rowW, ly + r + dpx(4));
                    p.setColor(0x122F80ED);
                    c.drawRoundRect(box, r + dpx(4), r + dpx(4), p);
                    int a = Math.round(255 * clamp(1f - k * 1.8f));
                    if (a > 0) {
                        t.setTextSize(dpx(15));
                        t.setColor(TEXT);
                        t.setAlpha(a);
                        c.drawText(names[i], lx + r + dpx(12), ly + dpx(5), t);
                        t.setTextSize(dpx(16));
                        t.setFakeBoldText(true);
                        c.drawText(now[i], W - dpx(96) - t.measureText(now[i]), ly + dpx(5), t);
                        t.setFakeBoldText(false);
                        t.setColor(SOFT);
                        t.setAlpha(a);
                        c.drawText(all[i], W - dpx(4) - t.measureText(all[i]), ly + dpx(5), t);
                    }
                }
                // 3. the bar rises under its chip, the chip nudged up as it arrives
                float cx = slot * i + slot / 2f;
                float finalH = maxBar * size[i] + r * 2 + dpx(8);
                float bar = finalH * BOUNCY.getInterpolation(grow);
                if (bar > 1f) {
                    box.set(cx - bw / 2, base - bar, cx + bw / 2, base);
                    p.setColor(0x182F80ED);
                    c.drawRoundRect(box, bw / 2, bw / 2, p);
                    box.inset(dpx(2), dpx(2));
                    box.bottom = base;
                    p.setColor(0xFFFFFFFF);
                    c.drawRoundRect(box, bw / 2, bw / 2, p);
                }
                // 2. the chip: off its row, swirling with the others, then to its place, a little smeared while it flies
                float[] at = chip(i, f, W, H, r, base, maxBar, slot);
                float[] was = chip(i, Math.max(0f, f - (chart ? 0.02f : -0.02f)), W, H, r, base, maxBar, slot);
                float speed = (float) Math.hypot(at[0] - was[0], at[1] - was[1]);
                if (speed > dpx(3)) {
                    p.setColor(0xFFFFFFFF);
                    p.setAlpha(Math.min(110, Math.round(speed * 4)));
                    c.drawCircle((at[0] + was[0]) / 2f, (at[1] + was[1]) / 2f, r * 0.94f, p);
                    p.setAlpha(255);
                }
                float scale = at[2];
                p.setColor(0x1F0B2A4A);
                c.drawCircle(at[0], at[1] + dpx(1.5f), r * scale, p);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(at[0], at[1], r * scale, p);
                float ir = r * 0.62f * scale;
                icons[i].setBounds(Math.round(at[0] - ir), Math.round(at[1] - ir), Math.round(at[0] + ir), Math.round(at[1] + ir));
                icons[i].draw(c);
                // 4. the chart's words
                float chartIn = clamp((f - 0.8f) / 0.18f);
                if (chartIn > 0f) {
                    t.setTextSize(dpx(13));
                    t.setFakeBoldText(true);
                    t.setColor(TEXT);
                    t.setAlpha(Math.round(255 * chartIn));
                    c.drawText(all[i], cx - t.measureText(all[i]) / 2f, base + dpx(17), t);
                    t.setFakeBoldText(false);
                    t.setTextSize(dpx(11));
                    t.setColor(MUTED);
                    t.setAlpha(Math.round(255 * chartIn));
                    c.drawText(shortNames[i], cx - t.measureText(shortNames[i]) / 2f, base + dpx(33), t);
                }
            }
            if (f > 0f && f < 1f) postInvalidateOnAnimation();
        }

        private float rowY(int i) { return dpx(30) + i * dpx(46) + dpx(16); }

        /** 0..1: how far row i has shrunk into its chip (the bottom row goes first). */
        private float collapse(int i) {
            return clamp((f - 0.055f * (4 - i)) / 0.15f);
        }

        // where the chips drift on their way, as fractions of the card (two stops each)
        private static final float[][] STOP_A = {{0.56f, 0.22f}, {0.22f, 0.42f}, {0.84f, 0.52f}, {0.46f, 0.68f}, {0.76f, 0.16f}};
        private static final float[][] STOP_B = {{0.62f, 0.12f}, {0.12f, 0.58f}, {0.7f, 0.36f}, {0.3f, 0.48f}, {0.9f, 0.3f}};

        private static float smooth(float t) { return t * t * (3f - 2f * t); }

        private static float cr(float p0, float p1, float p2, float p3, float t) {
            float t2 = t * t, t3 = t2 * t;
            return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
        }

        /**
         * Where chip i is at f: x, y and its size. Like the reference: it leaves its row once the
         * row has shrunk round it, drifts loose through two spots on a smooth curve, and arrives
         * at the height of its bar; then the bar rises under it.
         */
        private float[] chip(int i, float f, float W, float H, float r, float base, float maxBar, float slot) {
            float lx = dpx(4) + r, ly = rowY(i);
            float top = dpx(24), area = base - top;
            float tx = slot * i + slot / 2f;
            float finalH = maxBar * size[i] + r * 2 + dpx(8);
            float ty = base - finalH + r + dpx(4);
            float[] xs = {lx, W * STOP_A[i][0], W * STOP_B[i][0], tx};
            float[] ys = {ly, top + area * STOP_A[i][1], top + area * STOP_B[i][1], ty};
            float start = 0.055f * (4 - i) + 0.13f, end = 0.78f;
            float u = smooth(clamp((f - start) / (end - start))) * 3f;   // along the three legs
            int leg = Math.min(2, (int) u);
            float t = u - leg;
            int p0 = Math.max(0, leg - 1), p3 = Math.min(3, leg + 2);
            float x = cr(xs[p0], xs[leg], xs[leg + 1], xs[p3], t), y = cr(ys[p0], ys[leg], ys[leg + 1], ys[p3], t);
            // the bar rises under it and gives it a little push as it arrives
            float bar = finalH * BOUNCY.getInterpolation(clamp((f - 0.74f) / 0.24f));
            y = Math.min(y, base - bar + r + dpx(4));
            y -= dpx(6) * (float) Math.sin(Math.PI * clamp((f - 0.8f) / 0.14f));
            float scale = 1f + 0.08f * (float) Math.sin(Math.PI * clamp((f - start) / (end - start)));
            return new float[]{x, y, scale};
        }
    }

    // ---------- a box that changes shape ----------

    /**
     * A rounded box drawn by itself (no layout passes while it moves, so it stays smooth):
     * its rectangle, corner, tilt and colour are set each frame, and its children are kept
     * centred on it and tilted with it.
     */
    static final class Morph extends android.widget.FrameLayout {
        final RectF rect = new RectF();
        float tilt;
        int color = 0xFFFFFFFF;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG);

        Morph(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            shade.setColor(0x262F80ED);
        }

        void frame() {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.setTranslationX(rect.centerX() - v.getLeft() - v.getWidth() / 2f);
                v.setTranslationY(rect.centerY() - v.getTop() - v.getHeight() / 2f);
                v.setRotation(tilt);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (rect.width() < 1f) return;
            float rad = Math.min(rect.height() / 2f, dp(getContext(), 24));
            c.save();
            c.rotate(tilt, rect.centerX(), rect.centerY());
            c.translate(0, dp(getContext(), 4));
            c.drawRoundRect(rect, rad, rad, shade);
            c.translate(0, -dp(getContext(), 4));
            p.setColor(color);
            c.drawRoundRect(rect, rad, rad, p);
            c.restore();
        }
    }

    // ---------- liquid glass ----------

    /**
     * The tab bar turning to liquid: it gathers into a drop under your finger, the drop wobbles,
     * rises and blooms into a menu of glass; closing, the menu draws back into a drop that falls
     * and spreads out into the tab bar again. Only the shape is drawn here; the menu's rows sit
     * on top of it and fade in once it has bloomed.
     */
    static final class Liquid extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path(), base = new Path();
        private final android.graphics.PathMeasure pm = new android.graphics.PathMeasure();
        private final RectF bar = new RectF(), menu = new RectF(), now = new RectF();
        private float pressX, f;
        private final long born = android.os.SystemClock.uptimeMillis();

        Liquid(Context c, RectF tabBar, RectF target, float x) {
            super(c);
            bar.set(tabBar);
            menu.set(target);
            pressX = x;
            fill.setColor(0xF7FFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(c, 1.2f));
            edge.setColor(0xFFFFFFFF);
            shade.setColor(0x2E2F80ED);
        }

        void set(float open) {
            f = open;
            invalidate();
        }

        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

        @Override
        protected void onDraw(Canvas c) {
            float d = Math.min(bar.height() * 1.25f, dp(getContext(), 72));
            float cx, cy, w, h, rad, wob;
            if (f < 0.32f) {                                        // the bar gathers into a drop
                float t = GENTLE.getInterpolation(f / 0.32f);
                cx = lerp(bar.centerX(), Math.max(bar.left + d, Math.min(bar.right - d, pressX)), t);
                cy = lerp(bar.centerY(), bar.centerY() - d * 0.2f, t);
                w = lerp(bar.width(), d, t);
                h = lerp(bar.height(), d, t);
                rad = h / 2f;
                wob = (float) Math.sin(Math.PI * (f / 0.32f)) * d * 0.08f;
            } else {                                                // the drop rises and blooms into the menu
                float raw = (f - 0.32f) / 0.68f, t = BOUNCY.getInterpolation(raw), g = GENTLE.getInterpolation(raw);
                float sx = Math.max(bar.left + d, Math.min(bar.right - d, pressX));
                cx = lerp(sx, menu.centerX(), g);
                cy = lerp(bar.centerY() - d * 0.2f, menu.centerY(), g);
                w = Math.max(d * 0.6f, lerp(d, menu.width(), t));
                h = Math.max(d * 0.6f, lerp(d, menu.height(), t));
                rad = Math.min(h / 2f, lerp(d / 2f, dp(getContext(), 30), clamp01(raw * 1.6f)));
                wob = (float) Math.sin(Math.PI * Math.min(1f, raw * 1.3f)) * d * 0.1f;
            }
            now.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            base.reset();
            base.addRoundRect(now, rad, rad, Path.Direction.CW);
            shape.reset();
            if (wob < 0.5f) {
                shape.addPath(base);
            } else {                                                // a liquid edge: the outline ripples
                pm.setPath(base, true);
                float len = pm.getLength(), ph = (android.os.SystemClock.uptimeMillis() - born) / 160f;
                float[] pos = new float[2], tan = new float[2];
                int n = 90;
                for (int i = 0; i <= n; i++) {
                    float at = len * i / n;
                    pm.getPosTan(at, pos, tan);
                    float k = wob * (float) (Math.sin(i * 2 * Math.PI * 3 / n + ph) * 0.6 + Math.sin(i * 2 * Math.PI * 5 / n - ph * 1.3) * 0.4);
                    float px = pos[0] + tan[1] * k, py = pos[1] - tan[0] * k;
                    if (i == 0) shape.moveTo(px, py); else shape.lineTo(px, py);
                }
                shape.close();
                postInvalidateOnAnimation();
            }
            c.save();
            c.translate(0, dp(getContext(), 3));
            c.drawPath(shape, shade);
            c.restore();
            c.drawPath(shape, fill);
            c.drawPath(shape, edge);
        }

        private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    }

    // ---------- background ----------

    /** Soft colour clouds drifting behind the glass. Moves a touch with scrolling. */
    static final class Aurora extends View {
        private static final int[] COLORS = {0x80FFFFFF, 0x553B82F6, 0x66FFFFFF, 0x4D6FA8FF};
        private static final float[][] PATH = {     // centre x, y (share of the screen), drift, speed
                {0.15f, 0.12f, 0.18f, 1f}, {0.90f, 0.30f, 0.22f, 0.7f}, {0.25f, 0.62f, 0.20f, 0.85f}, {0.80f, 0.85f, 0.16f, 1.2f}};
        private final Paint[] paints = new Paint[COLORS.length];
        private final Matrix m = new Matrix();
        private final ValueAnimator clock = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        private float t, shift, shiftTo;
        private int builtFor = -1;

        Aurora(Context c) {
            super(c);
            for (int i = 0; i < paints.length; i++) paints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            clock.setDuration(32000);
            clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(new LinearInterpolator());
            clock.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        void play(boolean on) {
            if (on && !clock.isStarted()) clock.start();
            else if (!on) clock.cancel();
        }

        /** Scroll position: the clouds move at a fifth of the speed, for depth. */
        void scrolled(int y) {
            shiftTo = -y * 0.2f;         // eased to in onDraw, so it never jumps
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            shift += (shiftTo - shift) * 0.12f;
            if (Math.abs(shiftTo - shift) > 0.5f) invalidate();
            c.drawColor(BG);
            if (w == 0) return;
            float r = Math.max(w, h) * 0.55f;
            if (builtFor != w) {                     // one gradient per cloud, moved with a matrix
                for (int i = 0; i < paints.length; i++) {
                    paints[i].setShader(new RadialGradient(0, 0, r, COLORS[i], COLORS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
                }
                builtFor = w;
            }
            for (int i = 0; i < paints.length; i++) {
                float[] p = PATH[i];
                float a = t * p[3] + i * 1.7f;
                float x = w * (p[0] + p[2] * (float) Math.sin(a));
                float y = h * (p[1] + p[2] * 0.6f * (float) Math.cos(a * 0.8f)) + shift * (0.6f + i * 0.2f);
                m.setTranslate(x, y);
                paints[i].getShader().setLocalMatrix(m);
                c.drawCircle(x, y, r, paints[i]);
            }
        }
    }

    // ---------- switch ----------

    /** An iOS-style switch: the knob glides across and stretches while held. */
    static final class Toggle extends View {
        interface OnChange { void changed(boolean on); }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG),
                shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int onColor;
        private boolean on, held;
        private float f, stretch, speed;             // f: 0 off .. 1 on (a spring: it runs a little past both)
        private ValueAnimator run;
        private OnChange listener;

        Toggle(Context c, int onColor) {
            super(c);
            this.onColor = onColor;
            knob.setColor(Color.WHITE);
            shade.setColor(0x26000000);
            setOnClickListener(v -> set(!on, true));
        }

        void setOnChange(OnChange l) { listener = l; }

        boolean isOn() { return on; }

        void set(boolean value, boolean animate) {
            if (value == on && (run == null || !run.isRunning())) { f = on ? 1 : 0; invalidate(); return; }
            on = value;
            if (run != null) run.cancel();
            if (!animate) { f = on ? 1 : 0; invalidate(); return; }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            run = ValueAnimator.ofFloat(f, on ? 1 : 0);
            run.setDuration(680);
            run.setInterpolator(BOUNCY);
            run.addUpdateListener(a -> {
                float next = (float) a.getAnimatedValue();
                speed = Math.min(1f, Math.abs(next - f) * 9f);     // fast = stretched, like taffy
                f = next;
                invalidate();
            });
            run.start();
            if (listener != null) listener.changed(on);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(dp(getContext(), 51)), Math.round(dp(getContext(), 31)));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) held = true;
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) held = false;
            ValueAnimator s = ValueAnimator.ofFloat(stretch, held ? 1 : 0);
            s.setDuration(240);
            s.addUpdateListener(x -> { stretch = (float) x.getAnimatedValue(); invalidate(); });
            s.start();
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = dp(getContext(), 2);
            float g = Math.max(0, Math.min(1, f));
            track.setColor(blend(0x402C5A8C, onColor, g));
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, h / 2, h / 2, track);
            float d = h - 2 * pad;
            float past = f < 0 ? -f : f > 1 ? f - 1 : 0;          // the spring ran past an end
            // stretched while it flies, squashed against the end it hits
            float extra = Math.max(stretch, speed) * dp(getContext(), 8) - Math.min(0.45f, past * 1.5f) * d;
            float left = pad + g * (w - 2 * pad - d - extra);
            rect.set(left, pad + dp(getContext(), 1), left + d + extra, pad + d + dp(getContext(), 1));
            c.drawRoundRect(rect, d / 2, d / 2, shade);           // a soft shadow under the knob
            rect.offset(0, -dp(getContext(), 1));
            c.drawRoundRect(rect, d / 2, d / 2, knob);
        }
    }

    static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ---------- tab icons ----------

    /** A small line icon, drawn on a 22-unit grid: 0 home, 1 watch, 2 message, 3 bolt, 4 battery. */
    static final class TabIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final int kind;

        TabIcon(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(SOFT);
        }

        void setColor(int color) {
            p.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = Math.min(getWidth(), getHeight()) / 22f;
            p.setStrokeWidth(1.7f * u);
            path.reset();
            switch (kind) {
                case 0:
                    path.moveTo(3 * u, 10 * u);
                    path.lineTo(11 * u, 3 * u);
                    path.lineTo(19 * u, 10 * u);
                    path.moveTo(5.5f * u, 9 * u);
                    path.lineTo(5.5f * u, 18.5f * u);
                    path.lineTo(16.5f * u, 18.5f * u);
                    path.lineTo(16.5f * u, 9 * u);
                    break;
                case 1:
                    r.set(6 * u, 6 * u, 16 * u, 16 * u);
                    path.addRoundRect(r, 3 * u, 3 * u, Path.Direction.CW);
                    path.moveTo(8 * u, 6 * u);
                    path.lineTo(9 * u, 2.5f * u);
                    path.lineTo(13 * u, 2.5f * u);
                    path.lineTo(14 * u, 6 * u);
                    path.moveTo(8 * u, 16 * u);
                    path.lineTo(9 * u, 19.5f * u);
                    path.lineTo(13 * u, 19.5f * u);
                    path.lineTo(14 * u, 16 * u);
                    break;
                case 2:
                    r.set(3 * u, 4 * u, 19 * u, 15 * u);
                    path.addRoundRect(r, 3 * u, 3 * u, Path.Direction.CW);
                    path.moveTo(7 * u, 15 * u);
                    path.lineTo(7 * u, 19 * u);
                    path.lineTo(11.5f * u, 15 * u);
                    break;
                case 3:
                    path.moveTo(12.5f * u, 2.5f * u);
                    path.lineTo(5.5f * u, 12.5f * u);
                    path.lineTo(11 * u, 12.5f * u);
                    path.lineTo(9.5f * u, 19.5f * u);
                    path.lineTo(16.5f * u, 9.5f * u);
                    path.lineTo(11 * u, 9.5f * u);
                    path.close();
                    break;
                case 6:                                  // a tag sending waves (NFC)
                    path.addCircle(6 * u, 11 * u, 1.2f * u, Path.Direction.CW);
                    r.set(6 * u - 5 * u, 11 * u - 5 * u, 6 * u + 5 * u, 11 * u + 5 * u);
                    path.addArc(r, -45, 90);
                    r.set(6 * u - 9 * u, 11 * u - 9 * u, 6 * u + 9 * u, 11 * u + 9 * u);
                    path.addArc(r, -45, 90);
                    r.set(6 * u - 13 * u, 11 * u - 13 * u, 6 * u + 13 * u, 11 * u + 13 * u);
                    path.addArc(r, -45, 90);
                    break;
                case 5:                                  // a watch face with an F on it
                    r.set(4 * u, 3.5f * u, 18 * u, 18.5f * u);
                    path.addRoundRect(r, 3.5f * u, 3.5f * u, Path.Direction.CW);
                    path.moveTo(9 * u, 15 * u);
                    path.lineTo(9 * u, 7 * u);
                    path.lineTo(13.5f * u, 7 * u);
                    path.moveTo(9 * u, 11 * u);
                    path.lineTo(12.5f * u, 11 * u);
                    break;
                default:
                    r.set(2.5f * u, 6.5f * u, 17.5f * u, 15.5f * u);
                    path.addRoundRect(r, 2.5f * u, 2.5f * u, Path.Direction.CW);
                    path.moveTo(20 * u, 9.5f * u);
                    path.lineTo(20 * u, 12.5f * u);
                    path.moveTo(6 * u, 9.5f * u);
                    path.lineTo(6 * u, 12.5f * u);
                    path.moveTo(9.5f * u, 9.5f * u);
                    path.lineTo(9.5f * u, 12.5f * u);
            }
            c.drawPath(path, p);
        }
    }

    // ---------- status dot ----------

    /** Connected: a ring breathes out of the dot. Connecting: the dot blinks. Off: still. */
    static final class Dot extends View {
        static final int STILL = 0, BREATHE = 1, BLINK = 2;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        private int color = MUTED, mode = -1;
        private float t;

        Dot(Context c) {
            super(c);
            clock.setDuration(1800);
            clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(new LinearInterpolator());
            clock.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        }

        void set(int color, int mode) {
            this.color = color;
            if (mode != this.mode) {
                this.mode = mode;
                if (mode == STILL) { clock.cancel(); t = 0; }
                else if (!clock.isStarted()) clock.start();
            }
            invalidate();
        }

        void stop() {
            clock.cancel();
            mode = -1;               // the next set() starts it again
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Math.round(dp(getContext(), 26));
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(getContext(), 5);
            if (mode == BREATHE) {
                ring.setColor(color);
                ring.setAlpha(Math.round(110 * (1 - t)));
                c.drawCircle(cx, cy, r + t * dp(getContext(), 8), ring);
            }
            dot.setColor(color);
            if (mode == BLINK) dot.setAlpha(Math.round(255 * (0.35f + 0.65f * (float) Math.abs(Math.cos(t * Math.PI)))));
            c.drawCircle(cx, cy, r, dot);
        }
    }
}
