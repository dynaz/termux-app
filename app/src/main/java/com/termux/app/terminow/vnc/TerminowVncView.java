package com.termux.app.terminow.vnc;

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
 * The remote desktop of a VNC connection: the session's {@link Bitmap} scaled to fit or zoomed and
 * panned, and touch, mouse and keyboard turned into RFB pointer and key events. VNC carries the
 * whole button state in each pointer event, so this view keeps a button mask and sends it with the
 * current position.
 *
 * <p>Touch has two modes. Pointer mode is a trackpad: a drag moves the pointer, a tap clicks where
 * it is, a long press holds the left button to drag, a short two-finger tap right-clicks, two
 * fingers scroll. Direct touch acts where the finger is: a tap clicks there, a long press
 * right-clicks, a drag drags, two fingers scroll or (zoomed in) pan. Pinch zooms. When the session
 * is view-only, input is ignored but zoom and pan still work.</p>
 */
public class TerminowVncView extends View {

    public interface Listener {
        void onTouchStarted();
        void onKeySent();
    }

    // VNC pointer button-mask bits (RFC 6143 §7.5.1).
    private static final int BTN_LEFT = 1;
    private static final int BTN_MIDDLE = 1 << 1;
    private static final int BTN_RIGHT = 1 << 2;
    private static final int BTN_WHEEL_UP = 1 << 3;
    private static final int BTN_WHEEL_DOWN = 1 << 4;
    private static final int BTN_WHEEL_LEFT = 1 << 5;
    private static final int BTN_WHEEL_RIGHT = 1 << 6;

    @Nullable private TerminowVncSession mSession;
    @Nullable private Bitmap mBitmap;
    @Nullable private Listener mListener;
    private boolean mViewOnly;

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
    private float mPointerX = 100;
    private float mPointerY = 100;
    private int mButtonMask;

    private boolean mDragging;
    private boolean mScrolling;
    private boolean mScaling;
    private boolean mMovedTwoFingers;
    private long mTwoFingerTap;
    private float mLastX;
    private float mLastY;
    private float mScrollRest;
    private final float[] mDown = new float[2];

    private final GestureDetector mGestures;
    private final ScaleGestureDetector mScaleGestures;

    public TerminowVncView(@NonNull Context context) {
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
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!mPointerMode) moveTo(e.getX(), e.getY());
                click(BTN_LEFT);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!mPointerMode) moveTo(e.getX(), e.getY());
                click(BTN_LEFT);
                click(BTN_LEFT);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (mScaling || mScrolling || mViewOnly) return;
                if (mPointerMode) {
                    mDragging = true;
                    button(BTN_LEFT, true);
                    performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                } else {
                    moveTo(e.getX(), e.getY());
                    click(BTN_RIGHT);
                    performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                }
            }
        });
        mScaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) { mScaling = true; return true; }
            @Override public boolean onScale(@NonNull ScaleGestureDetector detector) {
                zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
    }

    public void setListener(@Nullable Listener listener) { mListener = listener; }

    public void setSession(@Nullable TerminowVncSession session) {
        mSession = session;
        mViewOnly = session != null && session.isViewOnly();
    }

    public void setBitmap(@Nullable Bitmap bitmap) {
        boolean newSize = bitmap == null || mBitmap == null
            || bitmap.getWidth() != mBitmap.getWidth() || bitmap.getHeight() != mBitmap.getHeight();
        mBitmap = bitmap;
        if (newSize) layoutDesktop(true);
        invalidate();
    }

    public void onFrame(int x, int y, int width, int height) {
        float[] points = {x, y, x + width, y + height};
        mMatrix.mapPoints(points);
        invalidate((int) Math.floor(points[0]) - 1, (int) Math.floor(points[1]) - 1,
            (int) Math.ceil(points[2]) + 1, (int) Math.ceil(points[3]) + 1);
    }

    public boolean isPointerMode() { return mPointerMode; }
    public void setPointerMode(boolean pointerMode) { mPointerMode = pointerMode; invalidate(); }
    public void fitScreen() { mFit = true; layoutDesktop(true); }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
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
        float desktopX = (focusX - mOffsetX) / mScale;
        float desktopY = (focusY - mOffsetY) / mScale;
        mScale = scale;
        mOffsetX = focusX - desktopX * mScale;
        mOffsetY = focusY - desktopY * mScale;
        mFit = Math.abs(mScale - mFitScale) < 0.001f;
        clampOffsets();
        updateMatrix();
    }

    private void pan(float dx, float dy) { mOffsetX += dx; mOffsetY += dy; clampOffsets(); updateMatrix(); }

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

    private void followPointer() {
        float x = mPointerX * mScale + mOffsetX;
        float y = mPointerY * mScale + mOffsetY;
        float margin = Math.min(getWidth(), getHeight()) * 0.12f;
        float dx = 0, dy = 0;
        if (x < margin) dx = margin - x; else if (x > getWidth() - margin) dx = getWidth() - margin - x;
        if (y < margin) dy = margin - y; else if (y > getHeight() - margin) dy = getHeight() - margin - y;
        if (dx != 0 || dy != 0) pan(dx, dy);
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        Bitmap bitmap = mBitmap;
        if (bitmap == null) return;
        canvas.drawBitmap(bitmap, mMatrix, mPaint);
        if (mPointerMode && !mViewOnly) {
            canvas.save();
            canvas.translate(mPointerX * mScale + mOffsetX, mPointerY * mScale + mOffsetY);
            float density = getResources().getDisplayMetrics().density;
            canvas.scale(density, density);
            canvas.drawPath(mCursor, mCursorPaint);
            canvas.drawPath(mCursor, mCursorBorder);
            canvas.restore();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pointer → VNC button mask

    private void moveTo(float viewX, float viewY) {
        float[] point = {viewX, viewY};
        mInverse.mapPoints(point);
        setPointer(point[0], point[1]);
        sendPointer();
    }

    private void setPointer(float x, float y) {
        Bitmap bitmap = mBitmap;
        int width = bitmap != null ? bitmap.getWidth() : 1;
        int height = bitmap != null ? bitmap.getHeight() : 1;
        mPointerX = Math.max(0, Math.min(width - 1, x));
        mPointerY = Math.max(0, Math.min(height - 1, y));
        invalidate();
    }

    private void sendPointer() {
        TerminowVncSession session = mSession;
        if (session != null && !mViewOnly) session.pointer(mButtonMask, Math.round(mPointerX), Math.round(mPointerY));
    }

    private void button(int bit, boolean down) {
        if (mViewOnly) return;
        if (down) mButtonMask |= bit; else mButtonMask &= ~bit;
        sendPointer();
    }

    private void click(int bit) { button(bit, true); button(bit, false); }

    /** A wheel notch is a press-and-release of the wheel "button". */
    private void wheel(int bit) { button(bit, true); button(bit, false); }

    private void scroll(float dyPixels, float dxPixels) {
        float notch = 40 * getResources().getDisplayMetrics().density;
        mScrollRest += dyPixels;
        int steps = (int) (mScrollRest / notch);
        if (steps != 0) {
            mScrollRest -= steps * notch;
            int bit = steps > 0 ? BTN_WHEEL_DOWN : BTN_WHEEL_UP;   // finger down = content scrolls down
            for (int i = 0; i < Math.min(5, Math.abs(steps)); i++) wheel(bit);
        }
        if (Math.abs(dxPixels) > notch) wheel(dxPixels > 0 ? BTN_WHEEL_LEFT : BTN_WHEEL_RIGHT);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && mListener != null) mListener.onTouchStarted();
        mScaleGestures.onTouchEvent(event);
        mGestures.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastX = event.getX(); mLastY = event.getY();
                mScrolling = false; mScaling = false; mScrollRest = 0;
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
                if (event.getPointerCount() == 2 && !mScaling && event.getEventTime() - mTwoFingerTap < 250 && !mMovedTwoFingers) {
                    if (!mPointerMode) moveTo((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2);
                    click(BTN_RIGHT);
                }
                mMovedTwoFingers = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() >= 2) {
                    float x = (event.getX(0) + event.getX(1)) / 2;
                    float y = (event.getY(0) + event.getY(1)) / 2;
                    float dx = x - mLastX, dy = y - mLastY;
                    mLastX = x; mLastY = y;
                    if (Math.abs(dx) + Math.abs(dy) > 2) mMovedTwoFingers = true;
                    if (mScaleGestures.isInProgress()) break;
                    if (mScale > mFitScale + 0.001f && (!mPointerMode || mViewOnly)) pan(dx, dy);
                    else scroll(dy, dx);
                    break;
                }
                if (mScrolling || mScaling) break;
                float dx = event.getX() - mLastX, dy = event.getY() - mLastY;
                mLastX = event.getX(); mLastY = event.getY();
                if (mViewOnly) { if (mScale > mFitScale + 0.001f) pan(dx, dy); break; }
                if (mPointerMode) {
                    setPointer(mPointerX + dx / mScale * 1.4f, mPointerY + dy / mScale * 1.4f);
                    sendPointer();
                    if (mScale > mFitScale + 0.001f) followPointer();
                } else if (!mDragging && Math.hypot(event.getX() - mDown[0], event.getY() - mDown[1]) >
                        12 * getResources().getDisplayMetrics().density) {
                    mDragging = true;
                    moveTo(mDown[0], mDown[1]);
                    button(BTN_LEFT, true);
                    moveTo(event.getX(), event.getY());
                } else if (mDragging) {
                    moveTo(event.getX(), event.getY());
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) { mDragging = false; button(BTN_LEFT, false); }
                mScrolling = false;
                break;
            default: break;
        }
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { mDown[0] = event.getX(); mDown[1] = event.getY(); }
        return super.dispatchTouchEvent(event);
    }

    private boolean onMouse(@NonNull MotionEvent event) {
        moveTo(event.getX(), event.getY());
        if (mViewOnly) return true;
        int buttons = event.getButtonState();
        if (event.getActionMasked() == MotionEvent.ACTION_UP) buttons = 0;
        setButton(BTN_LEFT, (buttons & MotionEvent.BUTTON_PRIMARY) != 0);
        setButton(BTN_RIGHT, (buttons & MotionEvent.BUTTON_SECONDARY) != 0);
        setButton(BTN_MIDDLE, (buttons & MotionEvent.BUTTON_TERTIARY) != 0);
        if (buttons == 0 && event.getActionMasked() == MotionEvent.ACTION_DOWN) click(BTN_LEFT);
        return true;
    }

    private void setButton(int bit, boolean down) {
        boolean now = (mButtonMask & bit) != 0;
        if (now != down) button(bit, down);
    }

    @Override public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && !mViewOnly) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_MOVE:
                    moveTo(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_SCROLL: {
                    moveTo(event.getX(), event.getY());
                    float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    if (v != 0) wheel(v > 0 ? BTN_WHEEL_UP : BTN_WHEEL_DOWN);
                    if (h != 0) wheel(h > 0 ? BTN_WHEEL_RIGHT : BTN_WHEEL_LEFT);
                    return true;
                }
                case MotionEvent.ACTION_BUTTON_PRESS:
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    return onMouse(event);
                default: break;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    // ---------------------------------------------------------------------------------------
    // Keyboard → VNC keysyms

    @Override public boolean onCheckIsTextEditor() { return !mViewOnly; }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                TerminowVncSession session = mSession;
                if (session != null && !mViewOnly) session.text(text);
                if (mListener != null) mListener.onKeySent();
                return true;
            }
            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < Math.max(1, beforeLength); i++) tapKeysym(TerminowVncKeys.XK_BackSpace);
                for (int i = 0; i < afterLength; i++) tapKeysym(TerminowVncKeys.XK_Delete);
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) { return handleKey(event); }
        };
    }

    /** Press and release a keysym — used by the extra-keys toolbar and by IME deletions. */
    public void tapKeysym(int keysym) {
        TerminowVncSession session = mSession;
        if (session == null || mViewOnly || keysym == 0) return;
        session.key(true, keysym);
        session.key(false, keysym);
    }

    /** Hold or release a modifier keysym (for an on-screen Ctrl/Alt/etc.). */
    public void holdKeysym(int keysym, boolean down) {
        TerminowVncSession session = mSession;
        if (session != null && !mViewOnly && keysym != 0) session.key(down, keysym);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyUp(keyCode, event);
    }

    public boolean handleKey(@NonNull KeyEvent event) {
        TerminowVncSession session = mSession;
        if (session == null || mViewOnly) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE && event.getCharacters() != null) {
            session.text(event.getCharacters());
            return true;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int keysym = TerminowVncKeys.keysymForKeyEvent(event, event.getMetaState());
        if (keysym == 0) return false;
        session.key(down, keysym);
        if (!down && !TerminowVncKeys.isModifier(keysym) && mListener != null) mListener.onKeySent();
        return true;
    }
}
