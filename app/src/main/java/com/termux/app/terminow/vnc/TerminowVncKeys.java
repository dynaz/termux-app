package com.termux.app.terminow.vnc;

import android.view.KeyEvent;

/**
 * Android key codes and characters as X11 keysyms, which the RFB protocol carries in its KeyEvent
 * message (RFC 6143 §7.5.4). Printable characters map to their Latin-1 code point, or to
 * {@code 0x01000000 + codepoint} above Latin-1; the named keys below have fixed keysyms.
 */
public final class TerminowVncKeys {

    // X11 keysyms for the named (non-printing) keys.
    public static final int XK_BackSpace = 0xFF08;
    public static final int XK_Tab = 0xFF09;
    public static final int XK_Return = 0xFF0D;
    public static final int XK_Escape = 0xFF1B;
    public static final int XK_Home = 0xFF50;
    public static final int XK_Left = 0xFF51;
    public static final int XK_Up = 0xFF52;
    public static final int XK_Right = 0xFF53;
    public static final int XK_Down = 0xFF54;
    public static final int XK_Page_Up = 0xFF55;
    public static final int XK_Page_Down = 0xFF56;
    public static final int XK_End = 0xFF57;
    public static final int XK_Insert = 0xFF63;
    public static final int XK_Menu = 0xFF67;
    public static final int XK_Delete = 0xFFFF;
    public static final int XK_Pause = 0xFF13;
    public static final int XK_Scroll_Lock = 0xFF14;
    public static final int XK_Print = 0xFF61;
    public static final int XK_Num_Lock = 0xFF7F;
    public static final int XK_Caps_Lock = 0xFFE5;
    public static final int XK_Shift_L = 0xFFE1;
    public static final int XK_Shift_R = 0xFFE2;
    public static final int XK_Control_L = 0xFFE3;
    public static final int XK_Control_R = 0xFFE4;
    public static final int XK_Alt_L = 0xFFE9;
    public static final int XK_Alt_R = 0xFFEA;
    public static final int XK_Super_L = 0xFFEB;
    public static final int XK_Super_R = 0xFFEC;
    public static final int XK_F1 = 0xFFBE;
    public static final int XK_KP_Enter = 0xFF8D;

    private TerminowVncKeys() {}

    /** The keysym for a character: Latin-1 directly, Unicode above that as {@code 0x01000000 | cp}. */
    public static int keysymForChar(char c) {
        switch (c) {
            case '\n': case '\r': return XK_Return;
            case '\t': return XK_Tab;
            case '\b': return XK_BackSpace;
            case 0x1b: return XK_Escape;
        }
        if (c >= 0x20 && c <= 0xFF) return c;        // Latin-1 keysyms equal the code point
        if (c == 0) return 0;
        return 0x01000000 | c;                        // the X11 Unicode keysym range
    }

    /**
     * The keysym for a key press. Prefers the character the key would type with the current
     * modifiers (so the phone's layout decides letters and symbols); falls back to the named-key
     * table for keys that produce no character.
     */
    public static int keysymForKeyEvent(KeyEvent event, int metaState) {
        int unicode = event.getUnicodeChar(metaState);
        if (unicode != 0 && (unicode & android.view.KeyCharacterMap.COMBINING_ACCENT) == 0 && !isNamed(event.getKeyCode()))
            return keysymForChar((char) unicode);
        return namedKeysym(event.getKeyCode());
    }

    /** A named (non-printing) keysym for an Android key code, or 0 if the key has no keysym. */
    public static int namedKeysym(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return XK_F1 + (keyCode - KeyEvent.KEYCODE_F1);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return XK_BackSpace;
            case KeyEvent.KEYCODE_FORWARD_DEL: return XK_Delete;
            case KeyEvent.KEYCODE_TAB: return XK_Tab;
            case KeyEvent.KEYCODE_ENTER: return XK_Return;
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return XK_KP_Enter;
            case KeyEvent.KEYCODE_ESCAPE: return XK_Escape;
            case KeyEvent.KEYCODE_PAGE_UP: return XK_Page_Up;
            case KeyEvent.KEYCODE_PAGE_DOWN: return XK_Page_Down;
            case KeyEvent.KEYCODE_MOVE_HOME: return XK_Home;
            case KeyEvent.KEYCODE_MOVE_END: return XK_End;
            case KeyEvent.KEYCODE_INSERT: return XK_Insert;
            case KeyEvent.KEYCODE_DPAD_LEFT: return XK_Left;
            case KeyEvent.KEYCODE_DPAD_UP: return XK_Up;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return XK_Right;
            case KeyEvent.KEYCODE_DPAD_DOWN: return XK_Down;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return XK_Shift_L;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return XK_Shift_R;
            case KeyEvent.KEYCODE_CTRL_LEFT: return XK_Control_L;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return XK_Control_R;
            case KeyEvent.KEYCODE_ALT_LEFT: return XK_Alt_L;
            case KeyEvent.KEYCODE_ALT_RIGHT: return XK_Alt_R;
            case KeyEvent.KEYCODE_META_LEFT: return XK_Super_L;
            case KeyEvent.KEYCODE_META_RIGHT: return XK_Super_R;
            case KeyEvent.KEYCODE_MENU: return XK_Menu;
            case KeyEvent.KEYCODE_CAPS_LOCK: return XK_Caps_Lock;
            case KeyEvent.KEYCODE_NUM_LOCK: return XK_Num_Lock;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return XK_Scroll_Lock;
            case KeyEvent.KEYCODE_SYSRQ: return XK_Print;
            case KeyEvent.KEYCODE_BREAK: return XK_Pause;
            case KeyEvent.KEYCODE_SPACE: return ' ';
            default: return 0;
        }
    }

    private static boolean isNamed(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    /** True for modifier keys, which the viewer holds down across other presses. */
    public static boolean isModifier(int keysym) {
        return keysym == XK_Shift_L || keysym == XK_Shift_R || keysym == XK_Control_L || keysym == XK_Control_R
            || keysym == XK_Alt_L || keysym == XK_Alt_R || keysym == XK_Super_L || keysym == XK_Super_R;
    }
}
