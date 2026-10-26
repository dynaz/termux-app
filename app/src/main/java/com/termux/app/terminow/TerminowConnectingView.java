package com.termux.app.terminow;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The small connection animation of the connecting panel: two plugs that move toward each other with
 * three dots that pulse in sequence while connecting, join with a check mark when connected, and sit
 * apart and muted when the attempt failed or disconnected.
 *
 * <p>It honours the system "remove animations" setting (reduced motion): when animations are off it
 * draws the still state of the phase instead of running the loop. It is a plain {@link View} with one
 * {@link ValueAnimator}; no extra dependencies and nothing that can steal input focus.
 */
public class TerminowConnectingView extends View {

    private static final int ACCENT = 0xFF21C17A;      // connected / active
    private static final int IDLE = 0xFF8A93A6;        // plugs and dots while connecting
    private static final int MUTED = 0xFF5A6072;       // failed / disconnected
    private static final int WARN = 0xFFF2C14E;        // authenticating accent

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path check = new Path();
    @Nullable private ValueAnimator animator;
    private float progress;                            // 0..1 loop while connecting
    @NonNull private TerminowConnectionPhase phase = TerminowConnectionPhase.CONNECTING;

    public TerminowConnectingView(Context context) { this(context, null); }

    public TerminowConnectingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    void setPhase(@NonNull TerminowConnectionPhase phase) {
        if (this.phase == phase) return;
        this.phase = phase;
        boolean animating = phase == TerminowConnectionPhase.CONNECTING || phase == TerminowConnectionPhase.AUTHENTICATING;
        if (animating && reducedMotion()) animating = false;
        if (animating) start(); else stop();
        invalidate();
    }

    private boolean reducedMotion() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void start() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    private void stop() {
        if (animator != null) { animator.cancel(); animator = null; }
        progress = 0f;
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cy = h / 2f;
        float unit = Math.min(h, w / 4f);
        float bodyW = unit * 0.9f, bodyH = unit * 1.1f, prong = unit * 0.5f;

        boolean connected = phase == TerminowConnectionPhase.CONNECTED;
        boolean broken = phase == TerminowConnectionPhase.FAILED || phase == TerminowConnectionPhase.DISCONNECTED;
        int plugColor = connected ? ACCENT : broken ? MUTED : (phase == TerminowConnectionPhase.AUTHENTICATING ? WARN : IDLE);

        // Gap between the plug tips: closed when connected, a small breathing gap while connecting.
        float maxGap = unit * 1.6f;
        float gap;
        if (connected) gap = 0f;
        else if (broken) gap = maxGap;
        else {
            float pulse = (float) (0.5 - 0.5 * Math.cos(progress * 2 * Math.PI)); // 0..1..0
            gap = maxGap * (0.35f + 0.65f * (1f - pulse));
        }

        float cx = w / 2f;
        float leftTip = cx - gap / 2f;
        float rightTip = cx + gap / 2f;

        paint.setColor(plugColor);
        paint.setStyle(Paint.Style.FILL);
        // Left plug body + prongs
        rect.set(leftTip - prong - bodyW, cy - bodyH / 2f, leftTip - prong, cy + bodyH / 2f);
        canvas.drawRoundRect(rect, unit * 0.2f, unit * 0.2f, paint);
        canvas.drawLine(leftTip - prong, cy - bodyH * 0.22f, leftTip, cy - bodyH * 0.22f, strokePaint(plugColor));
        canvas.drawLine(leftTip - prong, cy + bodyH * 0.22f, leftTip, cy + bodyH * 0.22f, strokePaint(plugColor));
        // Right plug body + prongs
        paint.setStyle(Paint.Style.FILL);
        rect.set(rightTip + prong, cy - bodyH / 2f, rightTip + prong + bodyW, cy + bodyH / 2f);
        canvas.drawRoundRect(rect, unit * 0.2f, unit * 0.2f, paint);
        canvas.drawLine(rightTip, cy - bodyH * 0.22f, rightTip + prong, cy - bodyH * 0.22f, strokePaint(plugColor));
        canvas.drawLine(rightTip, cy + bodyH * 0.22f, rightTip + prong, cy + bodyH * 0.22f, strokePaint(plugColor));

        if (connected) {
            // A check mark over the joined plugs.
            paint.setColor(ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, unit * 0.22f));
            check.reset();
            check.moveTo(cx - unit * 0.5f, cy);
            check.lineTo(cx - unit * 0.1f, cy + unit * 0.4f);
            check.lineTo(cx + unit * 0.6f, cy - unit * 0.5f);
            canvas.drawPath(check, paint);
            return;
        }
        if (broken) return;

        // Three pulsing dots between the plug tips.
        float dotR = unit * 0.18f;
        for (int i = 0; i < 3; i++) {
            float phaseOffset = i / 3f;
            float t = (progress + phaseOffset) % 1f;
            float a = (float) (0.3 + 0.7 * (0.5 - 0.5 * Math.cos(t * 2 * Math.PI)));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(plugColor);
            paint.setAlpha((int) (a * 255));
            float dx = cx + (i - 1) * unit * 0.55f;
            canvas.drawCircle(dx, cy, dotR, paint);
        }
        paint.setAlpha(255);
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Paint strokePaint(int color) {
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(Math.max(2f, Math.min(getHeight(), getWidth() / 4f) * 0.16f));
        return stroke;
    }
}
