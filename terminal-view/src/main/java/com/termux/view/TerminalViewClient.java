package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.termux.terminal.TerminalSession;

/**
 * The interface for communication between {@link TerminalView} and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * {@link View#setOnKeyListener(View.OnKeyListener)}, etc. It must be set for the
 * {@link TerminalView} through {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 */
public interface TerminalViewClient {

    /**
     * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
     */
    float onScale(float scale);



    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    void onSingleTapUp(MotionEvent e);

    boolean shouldBackButtonBeMappedToEscape();

    boolean shouldEnforceCharBasedInput();

    boolean shouldUseCtrlSpaceWorkaround();

    boolean isTerminalViewSelected();



    void copyModeChanged(boolean copyMode);



    boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session);

    boolean onKeyUp(int keyCode, KeyEvent e);

    boolean onLongPress(MotionEvent event);

    /**
     * On a horizontal swipe across the terminal. {@code toLeft} is true when the finger moved left.
     * Return true if the swipe was handled.
     */
    boolean onHorizontalSwipe(boolean toLeft);

    /**
     * Open links that were in the text the user selected, in a browser, when the user asked to;
     * more than one: let the user pick. Return false if this client can not open links, and the
     * selection then offers no such action.
     */
    default boolean onOpenUrls(@androidx.annotation.NonNull java.util.List<String> urls) {
        return false;
    }

    /** Whether {@link #onOpenUrls} opens links. */
    default boolean canOpenUrls() {
        return false;
    }

    /** Whether a sideways drag of a finger moves the terminal to another session, see {@link #onHorizontalDrag}. */
    default boolean canDragToSwitchSessions() {
        return false;
    }

    /** A finger drags the terminal sideways by dx pixels from where the drag began. */
    default void onHorizontalDrag(float dx) {}

    /**
     * The finger was lifted after a sideways drag, at velocityX pixels per second, or the drag
     * was cancelled: switch to the next session or go back.
     */
    default void onHorizontalDragEnd(float dx, float velocityX, boolean cancelled) {}



    boolean readControlKey();

    boolean readAltKey();

    boolean readShiftKey();

    boolean readFnKey();



    boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session);


    void onEmulatorSet();


    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
