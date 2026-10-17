package com.termux.app.terminow.rdp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

/** Keys of Android as Windows virtual keys, and the wheel of RDP. */
public class TerminowRdpKeysTest {

    @Test
    public void lettersDigitsAndFunctionKeys() {
        assertEquals(0x41, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_A));
        assertEquals(0x5A, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_Z));
        assertEquals(0x30, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_0));
        assertEquals(0x70, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_F1));
        assertEquals(0x7B, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_F12));
        assertEquals(0x60, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_NUMPAD_0));
    }

    @Test
    public void keysThatControl() {
        assertEquals(TerminowRdpKeys.VK_BACK, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_DEL));
        assertEquals(TerminowRdpKeys.VK_DELETE, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_FORWARD_DEL));
        assertEquals(TerminowRdpKeys.VK_RETURN, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_NUMPAD_ENTER));
        assertEquals(TerminowRdpKeys.VK_LWIN, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_META_LEFT));
        assertEquals(TerminowRdpKeys.VK_LEFT, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(0, TerminowRdpKeys.virtualKeyOf(KeyEvent.KEYCODE_BACK));
        assertTrue(TerminowRdpKeys.isControlKey(KeyEvent.KEYCODE_ENTER));
        assertFalse(TerminowRdpKeys.isControlKey(KeyEvent.KEYCODE_A));
        assertTrue(TerminowRdpKeys.isModifier(TerminowRdpKeys.VK_LCONTROL));
        assertFalse(TerminowRdpKeys.isModifier(TerminowRdpKeys.VK_TAB));
    }

    @Test
    public void theWheelIsNineBitsOfTwosComplement() {
        // One notch up: 120; one down: -120, which is 0x188 with the sign bit
        assertEquals(TerminowRdpNative.PTR_FLAGS_WHEEL | 120, TerminowRdpKeys.wheelFlags(1, false));
        assertEquals(TerminowRdpNative.PTR_FLAGS_WHEEL | TerminowRdpNative.PTR_FLAGS_WHEEL_NEGATIVE | 0x88, TerminowRdpKeys.wheelFlags(-1, false));
        assertEquals(TerminowRdpNative.PTR_FLAGS_HWHEEL | 120, TerminowRdpKeys.wheelFlags(1, true));
        // Never more than the 8 bits of the amount
        assertEquals(TerminowRdpNative.PTR_FLAGS_WHEEL | 0xFF, TerminowRdpKeys.wheelFlags(5, false));
    }
}
