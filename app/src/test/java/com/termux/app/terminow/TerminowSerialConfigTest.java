package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The serial line settings: the common 115200 8N1 default, a correct one-line summary for any
 * combination, and validation that rejects out-of-range fields before a port is ever opened.
 */
public class TerminowSerialConfigTest {

    @Test
    public void defaultsAre115200_8N1_noFlow() {
        TerminowSerialConfig config = TerminowSerialConfig.defaults();
        assertEquals(115200, config.baudRate);
        assertEquals(8, config.dataBits);
        assertEquals(TerminowSerialConfig.STOPBITS_1, config.stopBits);
        assertEquals(TerminowSerialConfig.PARITY_NONE, config.parity);
        assertEquals(TerminowSerialConfig.FLOW_NONE, config.flowControl);
        assertEquals("115200 8N1", config.summary());
    }

    @Test
    public void summaryShowsParityLetterAndStopBits() {
        assertEquals('N', TerminowSerialConfig.defaults().parityLetter());
        assertEquals('E', TerminowSerialConfig.defaults().withParity(TerminowSerialConfig.PARITY_EVEN).parityLetter());
        assertEquals('O', TerminowSerialConfig.defaults().withParity(TerminowSerialConfig.PARITY_ODD).parityLetter());

        TerminowSerialConfig oddSevenTwo = new TerminowSerialConfig(9600, 7,
            TerminowSerialConfig.STOPBITS_2, TerminowSerialConfig.PARITY_ODD, TerminowSerialConfig.FLOW_NONE);
        assertEquals("9600 7O2", oddSevenTwo.summary());

        TerminowSerialConfig onePointFive = TerminowSerialConfig.defaults().withStopBits(TerminowSerialConfig.STOPBITS_1_5);
        assertEquals("1.5", onePointFive.stopBitsLabel());
        assertEquals("115200 8N1.5", onePointFive.summary());
    }

    @Test
    public void summaryAppendsFlowControlWhenSet() {
        TerminowSerialConfig rtsCts = TerminowSerialConfig.defaults().withFlowControl(TerminowSerialConfig.FLOW_RTS_CTS);
        assertEquals("115200 8N1 RTS/CTS", rtsCts.summary());
        assertEquals("XON/XOFF", TerminowSerialConfig.defaults().withFlowControl(TerminowSerialConfig.FLOW_XON_XOFF).flowControlLabel());
    }

    @Test
    public void validationRejectsOutOfRangeFields() {
        assertTrue(TerminowSerialConfig.defaults().isValid());
        assertTrue(TerminowSerialConfig.defaults().withBaud(1200).isValid());

        assertFalse(TerminowSerialConfig.defaults().withBaud(0).isValid());
        assertFalse(TerminowSerialConfig.defaults().withBaud(-9600).isValid());
        assertFalse(TerminowSerialConfig.defaults().withDataBits(4).isValid());
        assertFalse(TerminowSerialConfig.defaults().withDataBits(9).isValid());
        assertFalse(TerminowSerialConfig.defaults().withStopBits(99).isValid());
        assertFalse(TerminowSerialConfig.defaults().withParity(7).isValid());
        assertFalse(TerminowSerialConfig.defaults().withFlowControl(5).isValid());
    }

    @Test
    public void withBuildersDoNotMutateTheOriginal() {
        TerminowSerialConfig base = TerminowSerialConfig.defaults();
        TerminowSerialConfig changed = base.withBaud(9600).withParity(TerminowSerialConfig.PARITY_EVEN);
        assertEquals(115200, base.baudRate);
        assertEquals(TerminowSerialConfig.PARITY_NONE, base.parity);
        assertEquals(9600, changed.baudRate);
        assertEquals(TerminowSerialConfig.PARITY_EVEN, changed.parity);
    }
}
