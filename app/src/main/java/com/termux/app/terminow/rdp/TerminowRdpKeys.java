package com.termux.app.terminow.rdp;

import android.view.KeyEvent;

/**
 * Keys of Android as the Windows virtual keys that RDP sends, which the engine turns into scan
 * codes of the enhanced keyboard. Keys that make text are sent as Unicode instead, so that the
 * keyboard layout of the phone is what Windows gets.
 */
public final class TerminowRdpKeys {

    public static final int VK_BACK = 0x08;
    public static final int VK_TAB = 0x09;
    public static final int VK_RETURN = 0x0D;
    public static final int VK_PAUSE = 0x13;
    public static final int VK_CAPITAL = 0x14;
    public static final int VK_ESCAPE = 0x1B;
    public static final int VK_SPACE = 0x20;
    public static final int VK_PRIOR = 0x21;
    public static final int VK_NEXT = 0x22;
    public static final int VK_END = 0x23;
    public static final int VK_HOME = 0x24;
    public static final int VK_LEFT = 0x25;
    public static final int VK_UP = 0x26;
    public static final int VK_RIGHT = 0x27;
    public static final int VK_DOWN = 0x28;
    public static final int VK_SNAPSHOT = 0x2C;
    public static final int VK_INSERT = 0x2D;
    public static final int VK_DELETE = 0x2E;
    public static final int VK_LWIN = 0x5B;
    public static final int VK_RWIN = 0x5C;
    public static final int VK_APPS = 0x5D;
    public static final int VK_F1 = 0x70;
    public static final int VK_NUMLOCK = 0x90;
    public static final int VK_SCROLL = 0x91;
    public static final int VK_LSHIFT = 0xA0;
    public static final int VK_RSHIFT = 0xA1;
    public static final int VK_LCONTROL = 0xA2;
    public static final int VK_RCONTROL = 0xA3;
    public static final int VK_LMENU = 0xA4;
    public static final int VK_RMENU = 0xA5;

    private TerminowRdpKeys() {}

    /** The virtual key of an Android key, 0 if it has none. */
    public static int virtualKeyOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return 0x41 + (keyCode - KeyEvent.KEYCODE_A);
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return 0x30 + (keyCode - KeyEvent.KEYCODE_0);
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return VK_F1 + (keyCode - KeyEvent.KEYCODE_F1);
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return 0x60 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return VK_BACK;
            case KeyEvent.KEYCODE_FORWARD_DEL: return VK_DELETE;
            case KeyEvent.KEYCODE_TAB: return VK_TAB;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return VK_RETURN;
            case KeyEvent.KEYCODE_ESCAPE: return VK_ESCAPE;
            case KeyEvent.KEYCODE_SPACE: return VK_SPACE;
            case KeyEvent.KEYCODE_PAGE_UP: return VK_PRIOR;
            case KeyEvent.KEYCODE_PAGE_DOWN: return VK_NEXT;
            case KeyEvent.KEYCODE_MOVE_HOME: return VK_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return VK_END;
            case KeyEvent.KEYCODE_INSERT: return VK_INSERT;
            case KeyEvent.KEYCODE_DPAD_LEFT: return VK_LEFT;
            case KeyEvent.KEYCODE_DPAD_UP: return VK_UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return VK_RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return VK_DOWN;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return VK_LSHIFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return VK_RSHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT: return VK_LCONTROL;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return VK_RCONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT: return VK_LMENU;
            case KeyEvent.KEYCODE_ALT_RIGHT: return VK_RMENU;
            case KeyEvent.KEYCODE_META_LEFT: return VK_LWIN;
            case KeyEvent.KEYCODE_META_RIGHT: return VK_RWIN;
            case KeyEvent.KEYCODE_MENU: return VK_APPS;
            case KeyEvent.KEYCODE_CAPS_LOCK: return VK_CAPITAL;
            case KeyEvent.KEYCODE_NUM_LOCK: return VK_NUMLOCK;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return VK_SCROLL;
            case KeyEvent.KEYCODE_SYSRQ: return VK_SNAPSHOT;
            case KeyEvent.KEYCODE_BREAK: return VK_PAUSE;
            case KeyEvent.KEYCODE_SEMICOLON: return 0xBA;
            case KeyEvent.KEYCODE_EQUALS: return 0xBB;
            case KeyEvent.KEYCODE_COMMA: return 0xBC;
            case KeyEvent.KEYCODE_MINUS: return 0xBD;
            case KeyEvent.KEYCODE_PERIOD: return 0xBE;
            case KeyEvent.KEYCODE_SLASH: return 0xBF;
            case KeyEvent.KEYCODE_GRAVE: return 0xC0;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 0xDB;
            case KeyEvent.KEYCODE_BACKSLASH: return 0xDC;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 0xDD;
            case KeyEvent.KEYCODE_APOSTROPHE: return 0xDE;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return 0x6A;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return 0x6B;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return 0x6D;
            case KeyEvent.KEYCODE_NUMPAD_DOT: return 0x6E;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return 0x6F;
            default: return 0;
        }
    }

    /** Keys that are sent as keys even when they make text: they control, not type. */
    public static boolean isControlKey(int keyCode) {
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

    /** The modifier keys, which stay down while other keys are pressed. */
    public static boolean isModifier(int virtualKey) {
        return virtualKey == VK_LSHIFT || virtualKey == VK_RSHIFT || virtualKey == VK_LCONTROL || virtualKey == VK_RCONTROL ||
            virtualKey == VK_LMENU || virtualKey == VK_RMENU || virtualKey == VK_LWIN || virtualKey == VK_RWIN;
    }

    /** The wheel flags of RDP for a scroll of {@code steps} notches, up or left when positive. */
    public static int wheelFlags(int steps, boolean horizontal) {
        int amount = Math.min(0xFF, Math.abs(steps) * 120);
        int flags = horizontal ? TerminowRdpNative.PTR_FLAGS_HWHEEL : TerminowRdpNative.PTR_FLAGS_WHEEL;
        // A negative rotation is the two's complement of 9 bits
        if (steps < 0) return flags | TerminowRdpNative.PTR_FLAGS_WHEEL_NEGATIVE | ((0x100 - amount) & 0xFF);
        return flags | amount;
    }
}
