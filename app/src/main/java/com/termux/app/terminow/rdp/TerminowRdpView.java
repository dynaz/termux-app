package com.termux.app.terminow.rdp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The remote desktop: the Bitmap of the connection, scaled to fit or zoomed and panned, and the
 * input of touch, mouse and keyboard turned into RDP.
 *
 * Touch has two modes. Pointer mode is a trackpad: a drag moves the pointer, a tap clicks where
 * it is, a tap of two fingers is a right click, a long press starts a drag, two fingers scroll.
 * Direct touch clicks where the finger is: a tap clicks, a long press is a right click, a drag
 * drags, two fingers scroll or, zoomed in, pan. Pinch zooms in both. A mouse is a mouse: where
 * it is, its buttons and its wheel.
 */
public class TerminowRdpView extends View {

    public interface Listener {
        void onTouchStarted();

        /** A key or text other than a modifier was sent: the extra keys release Ctrl, Alt, Shift and Win. */
        void onKeySent();
    }

    @Nullable private TerminowRdpSession mSession;
    @Nullable private Bitmap mBitmap;
    @Nullable private Listener mListener;
    private final Matrix mMatrix = new Matrix();
    private final Matrix mInverse = new Matrix();
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCursorBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mCursor = new Path();
    private float mScale = 1f;
    private float mFitScale = 1f;
    private float mOffsetX;
    private float mOffsetY;
    private boolean mFit = true;
    private boolean mPointerMode = true;
    /** Where the pointer is on the desktop, in its pixels. */
    private float mPointerX = 100;
    private float mPointerY = 100;
    private boolean mDragging;
    private boolean mScrolling;
    private boolean mScaling;
    private float mLastX;
    private float mLastY;
    private float mScrollRest;
    private int mMouseButtons;
    private final GestureDetector mGestures;
    private final ScaleGestureDetector mScaleGestures;

    public TerminowRdpView(@NonNull Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        mCursorPaint.setColor(Color.WHITE);
        mCursorBorder.setColor(Color.BLACK);
        mCursorBorder.setStyle(Paint.Style.STROKE);
        mCursorBorder.setStrokeWidth(1.5f);
        mCursor.moveTo(0, 0);
        mCursor.lineTo(0, 17);
        mCursor.lineTo(4.5f, 13);
        mCursor.lineTo(8, 20.5f);
        mCursor.lineTo(10.5f, 19.3f);
        mCursor.lineTo(7, 12);
        mCursor.lineTo(12.5f, 12);
        mCursor.close();
        mGestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!mPointerMode) moveTo(e.getX(), e.getY());
                click(TerminowRdpNative.PTR_FLAGS_BUTTON1);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!mPointerMode) moveTo(e.getX(), e.getY());
                click(TerminowRdpNative.PTR_FLAGS_BUTTON1);
                click(TerminowRdpNative.PTR_FLAGS_BUTTON1);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mScaling || mScrolling) return;
                if (mPointerMode) {
                    // A long press starts a drag with the left button, from where the pointer is
                    mDragging = true;
                    button(TerminowRdpNative.PTR_FLAGS_BUTTON1, true);
                    performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                } else {
                    moveTo(e.getX(), e.getY());
                    click(TerminowRdpNative.PTR_FLAGS_BUTTON2);
                    performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                }
            }
        });
        mGestures.setIsLongpressEnabled(true);
        mScaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                mScaling = true;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public void setSession(@Nullable TerminowRdpSession session) {
        mSession = session;
    }

    public void setBitmap(@Nullable Bitmap bitmap) {
        boolean newSize = bitmap == null || mBitmap == null || bitmap.getWidth() != mBitmap.getWidth() || bitmap.getHeight() != mBitmap.getHeight();
        mBitmap = bitmap;
        if (newSize) layoutDesktop(true);
        invalidate();
    }

    /** A part of the desktop changed, in its pixels. */
    public void onFrame(int x, int y, int width, int height) {
        float[] points = {x, y, x + width, y + height};
        mMatrix.mapPoints(points);
        invalidate((int) Math.floor(points[0]) - 1, (int) Math.floor(points[1]) - 1, (int) Math.ceil(points[2]) + 1,
            (int) Math.ceil(points[3]) + 1);
    }

    public boolean isPointerMode() {
        return mPointerMode;
    }

    public void setPointerMode(boolean pointerMode) {
        mPointerMode = pointerMode;
        invalidate();
    }

    /** The whole desktop in the view again. */
    public void fitScreen() {
        mFit = true;
        layoutDesktop(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Rotation, a fold, split screen or the keyboard: the desktop keeps fitting, or its zoom
        layoutDesktop(mFit);
    }

    private void layoutDesktop(boolean fit) {
        Bitmap bitmap = mBitmap;
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        mFitScale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        if (fit || mScale < mFitScale) mScale = mFitScale;
        clampOffsets();
        updateMatrix();
    }

    private void zoom(float factor, float focusX, float focusY) {
        Bitmap bitmap = mBitmap;
        if (bitmap == null) return;
        float scale = Math.max(mFitScale, Math.min(mScale * factor, Math.max(mFitScale * 6, 4f)));
        // The point under the fingers stays there
        float desktopX = (focusX - mOffsetX) / mScale;
        float desktopY = (focusY - mOffsetY) / mScale;
        mScale = scale;
        mOffsetX = focusX - desktopX * mScale;
        mOffsetY = focusY - desktopY * mScale;
        mFit = Math.abs(mScale - mFitScale) < 0.001f;
        clampOffsets();
        updateMatrix();
    }

    private void pan(float dx, float dy) {
        mOffsetX += dx;
        mOffsetY += dy;
        clampOffsets();
        updateMatrix();
    }

    private void clampOffsets() {
        Bitmap bitmap = mBitmap;
        if (bitmap == null) return;
        float width = bitmap.getWidth() * mScale;
        float height = bitmap.getHeight() * mScale;
        mOffsetX = width <= getWidth() ? (getWidth() - width) / 2 : Math.max(getWidth() - width, Math.min(0, mOffsetX));
        mOffsetY = height <= getHeight() ? (getHeight() - height) / 2 : Math.max(getHeight() - height, Math.min(0, mOffsetY));
    }

    private void updateMatrix() {
        mMatrix.reset();
        mMatrix.postScale(mScale, mScale);
        mMatrix.postTranslate(mOffsetX, mOffsetY);
        mMatrix.invert(mInverse);
        invalidate();
    }

    /** Keep the pointer in the view when zoomed in, in pointer mode. */
    private void followPointer() {
        float x = mPointerX * mScale + mOffsetX;
        float y = mPointerY * mScale + mOffsetY;
        float margin = Math.min(getWidth(), getHeight()) * 0.12f;
        float dx = 0;
        float dy = 0;
        if (x < margin) dx = margin - x;
        else if (x > getWidth() - margin) dx = getWidth() - margin - x;
        if (y < margin) dy = margin - y;
        else if (y > getHeight() - margin) dy = getHeight() - margin - y;
        if (dx != 0 || dy != 0) pan(dx, dy);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        Bitmap bitmap = mBitmap;
        if (bitmap == null) return;
        canvas.drawBitmap(bitmap, mMatrix, mPaint);
        if (mPointerMode) {
            canvas.save();
            canvas.translate(mPointerX * mScale + mOffsetX, mPointerY * mScale + mOffsetY);
            float size = getResources().getDisplayMetrics().density;
            canvas.scale(size, size);
            canvas.drawPath(mCursor, mCursorPaint);
            canvas.drawPath(mCursor, mCursorBorder);
            canvas.restore();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pointer

    private void moveTo(float viewX, float viewY) {
        float[] point = {viewX, viewY};
        mInverse.mapPoints(point);
        setPointer(point[0], point[1]);
        send(TerminowRdpNative.PTR_FLAGS_MOVE);
    }

    private void setPointer(float x, float y) {
        Bitmap bitmap = mBitmap;
        int width = bitmap != null ? bitmap.getWidth() : 1;
        int height = bitmap != null ? bitmap.getHeight() : 1;
        mPointerX = Math.max(0, Math.min(width - 1, x));
        mPointerY = Math.max(0, Math.min(height - 1, y));
        invalidate();
    }

    private void send(int flags) {
        TerminowRdpSession session = mSession;
        if (session != null) session.sendMouse(flags, Math.round(mPointerX), Math.round(mPointerY));
    }

    private void button(int button, boolean down) {
        send(button | (down ? TerminowRdpNative.PTR_FLAGS_DOWN : 0));
    }

    private void click(int button) {
        button(button, true);
        button(button, false);
    }

    private void scroll(float dyPixels, float dxPixels) {
        // A notch about every 48dp of finger
        float notch = 48 * getResources().getDisplayMetrics().density;
        mScrollRest += dyPixels;
        int steps = (int) (mScrollRest / notch);
        if (steps != 0) {
            mScrollRest -= steps * notch;
            send(TerminowRdpKeys.wheelFlags(steps, false));
        }
        if (Math.abs(dxPixels) > notch) send(TerminowRdpKeys.wheelFlags(dxPixels > 0 ? -1 : 1, true));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && mListener != null) mListener.onTouchStarted();
        mScaleGestures.onTouchEvent(event);
        mGestures.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastX = event.getX();
                mLastY = event.getY();
                mScrolling = false;
                mScaling = false;
                mScrollRest = 0;
                requestFocus();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    mScrolling = true;
                    mLastX = (event.getX(0) + event.getX(1)) / 2;
                    mLastY = (event.getY(0) + event.getY(1)) / 2;
                    mTwoFingerTap = event.getEventTime();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // A short tap of two fingers is a right click
                if (event.getPointerCount() == 2 && !mScaling && event.getEventTime() - mTwoFingerTap < 250 && !mMovedTwoFingers) {
                    if (!mPointerMode) moveTo((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2);
                    click(TerminowRdpNative.PTR_FLAGS_BUTTON2);
                }
                mMovedTwoFingers = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() >= 2) {
                    float x = (event.getX(0) + event.getX(1)) / 2;
                    float y = (event.getY(0) + event.getY(1)) / 2;
                    float dx = x - mLastX;
                    float dy = y - mLastY;
                    mLastX = x;
                    mLastY = y;
                    if (Math.abs(dx) + Math.abs(dy) > 2) mMovedTwoFingers = true;
                    if (mScaleGestures.isInProgress()) break;
                    if (!mPointerMode && mScale > mFitScale + 0.001f) pan(dx, dy);
                    else scroll(dy, dx);
                    break;
                }
                if (mScrolling || mScaling) break;
                float dx = event.getX() - mLastX;
                float dy = event.getY() - mLastY;
                mLastX = event.getX();
                mLastY = event.getY();
                if (mPointerMode) {
                    // A trackpad: the pointer moves by the finger, a little faster than it
                    setPointer(mPointerX + dx / mScale * 1.4f, mPointerY + dy / mScale * 1.4f);
                    send(TerminowRdpNative.PTR_FLAGS_MOVE);
                    if (mScale > mFitScale + 0.001f) followPointer();
                } else if (!mDragging && Math.hypot(event.getX() - mDown[0], event.getY() - mDown[1]) >
                    12 * getResources().getDisplayMetrics().density) {
                    // Direct touch: a drag presses where it started
                    mDragging = true;
                    moveTo(mDown[0], mDown[1]);
                    button(TerminowRdpNative.PTR_FLAGS_BUTTON1, true);
                    moveTo(event.getX(), event.getY());
                } else if (mDragging) {
                    moveTo(event.getX(), event.getY());
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    mDragging = false;
                    button(TerminowRdpNative.PTR_FLAGS_BUTTON1, false);
                }
                mScrolling = false;
                break;
            default:
                break;
        }
        return true;
    }

    private long mTwoFingerTap;
    private boolean mMovedTwoFingers;
    /** Where the finger went down, in the view. */
    private final float[] mDown = new float[2];

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDown[0] = event.getX();
            mDown[1] = event.getY();
        }
        return super.dispatchTouchEvent(event);
    }

    /** A mouse, a touchpad of a keyboard cover or a stylus with buttons: positions and buttons as they are. */
    private boolean onMouse(@NonNull MotionEvent event) {
        moveTo(event.getX(), event.getY());
        int buttons = event.getButtonState();
        if (event.getActionMasked() == MotionEvent.ACTION_UP) buttons = 0;
        int changed = buttons ^ mMouseButtons;
        if ((changed & MotionEvent.BUTTON_PRIMARY) != 0)
            button(TerminowRdpNative.PTR_FLAGS_BUTTON1, (buttons & MotionEvent.BUTTON_PRIMARY) != 0);
        if ((changed & MotionEvent.BUTTON_SECONDARY) != 0)
            button(TerminowRdpNative.PTR_FLAGS_BUTTON2, (buttons & MotionEvent.BUTTON_SECONDARY) != 0);
        if ((changed & MotionEvent.BUTTON_TERTIARY) != 0)
            button(TerminowRdpNative.PTR_FLAGS_BUTTON3, (buttons & MotionEvent.BUTTON_TERTIARY) != 0);
        // A tap of a touchpad comes as a down without buttons
        if (changed == 0 && event.getActionMasked() == MotionEvent.ACTION_DOWN && buttons == 0) {
            button(TerminowRdpNative.PTR_FLAGS_BUTTON1, true);
            buttons = MotionEvent.BUTTON_PRIMARY;
        }
        mMouseButtons = buttons;
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_MOVE:
                    moveTo(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_SCROLL: {
                    moveTo(event.getX(), event.getY());
                    float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    if (v != 0) send(TerminowRdpKeys.wheelFlags(v > 0 ? Math.max(1, Math.round(v)) : Math.min(-1, Math.round(v)), false));
                    if (h != 0) send(TerminowRdpKeys.wheelFlags(h > 0 ? -1 : 1, true));
                    return true;
                }
                case MotionEvent.ACTION_BUTTON_PRESS:
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    return onMouse(event);
                default:
                    break;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    // ---------------------------------------------------------------------------------------
    // Keyboard

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // No suggestions or corrections: every character goes to Windows as it is typed
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                TerminowRdpSession session = mSession;
                if (session != null) session.sendText(text);
                if (mListener != null) mListener.onKeySent();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < Math.max(1, beforeLength); i++) tapKey(TerminowRdpKeys.VK_BACK);
                for (int i = 0; i < afterLength; i++) tapKey(TerminowRdpKeys.VK_DELETE);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return handleKey(event);
            }
        };
    }

    /** Press and release a key, like the extra keys do. */
    public void tapKey(int virtualKey) {
        TerminowRdpSession session = mSession;
        if (session == null) return;
        session.sendKey(virtualKey, true);
        session.sendKey(virtualKey, false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyUp(keyCode, event);
    }

    /**
     * A key of the soft keyboard or of a keyboard: text as Unicode, unless Ctrl, Alt or Meta is
     * held, which makes it a shortcut; other keys by their virtual key.
     */
    public boolean handleKey(@NonNull KeyEvent event) {
        TerminowRdpSession session = mSession;
        if (session == null) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE && event.getCharacters() != null) {
            session.sendText(event.getCharacters());
            return true;
        }
        boolean shortcut = (event.getMetaState() & (KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON)) != 0;
        int unicode = event.getUnicodeChar();
        if (unicode != 0 && !shortcut && !TerminowRdpKeys.isControlKey(keyCode)) {
            if (down) {
                session.sendText(String.valueOf((char) unicode));
                if (mListener != null) mListener.onKeySent();
            }
            return true;
        }
        int virtualKey = TerminowRdpKeys.virtualKeyOf(keyCode);
        if (virtualKey == 0) return false;
        session.sendKey(virtualKey, down);
        if (!down && !TerminowRdpKeys.isModifier(virtualKey) && mListener != null) mListener.onKeySent();
        return true;
    }
}
