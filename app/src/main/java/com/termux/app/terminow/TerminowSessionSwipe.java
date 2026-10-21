package com.termux.app.terminow;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * Switching sessions by dragging the terminal sideways: it follows the finger, and when it is
 * let go it either slides out while the next session slides in from the other side, or springs
 * back. Like turning a page, dragging to the left shows the next session.
 */
public final class TerminowSessionSwipe {

    /** Switches the session that the terminal shows, forward for the next one. */
    public interface Switcher {
        void switchSession(boolean forward);
    }

    /** How far the terminal must be dragged, as a part of its width, to switch on a slow release. */
    static final float DISTANCE = 0.22f;
    /** How fast a release switches even when the drag was short, in dp per second. */
    static final float VELOCITY_DP = 650;

    private final View mView;
    private final Switcher mSwitcher;
    private boolean mAnimating;

    public TerminowSessionSwipe(@NonNull View view, @NonNull Switcher switcher) {
        mView = view;
        mSwitcher = switcher;
    }

    /** The finger drags the terminal by dx; it moves with it, a little fainter the further it goes. */
    public void drag(float dx) {
        if (mAnimating) return;
        mView.animate().cancel();
        int width = Math.max(1, mView.getWidth());
        mView.setTranslationX(dx);
        mView.setAlpha(1f - 0.35f * Math.min(1f, Math.abs(dx) / width));
    }

    /** The finger was lifted: switch, or go back to where the terminal was. */
    public void release(float dx, float velocityX, boolean cancelled) {
        if (mAnimating) return;
        int width = Math.max(1, mView.getWidth());
        float density = mView.getResources().getDisplayMetrics().density;
        if (cancelled || !shouldSwitch(dx, velocityX, width, density)) {
            mView.animate().translationX(0).alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator(1.5f)).start();
            return;
        }
        boolean forward = dx < 0;
        float out = forward ? -width : width;
        // Faster the less is left to go and the faster the finger was
        long duration = Math.max(90, Math.min(200, (long) (200 * (1f - Math.abs(dx) / width))));
        mAnimating = true;
        mView.animate().translationX(out).alpha(0.4f).setDuration(duration).setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> {
                mSwitcher.switchSession(forward);
                // The next session comes in from the other side
                mView.setTranslationX(-out * 0.3f);
                mView.animate().translationX(0).alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(() -> mAnimating = false).start();
            }).start();
    }

    /**
     * Whether a drag that was let go switches: far enough, or fast enough in its direction. A
     * fling back towards where it began goes back, whatever the distance.
     */
    static boolean shouldSwitch(float dx, float velocityX, int width, float density) {
        if (dx == 0) return false;
        float fast = VELOCITY_DP * density;
        if (Math.abs(velocityX) > fast) return Math.signum(velocityX) == Math.signum(dx);
        return Math.abs(dx) > width * DISTANCE;
    }
}
