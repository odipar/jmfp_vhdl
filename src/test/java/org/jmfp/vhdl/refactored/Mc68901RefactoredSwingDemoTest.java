package org.jmfp.vhdl.refactored;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Mc68901RefactoredSwingDemoTest {

    @Test
    void packsTcdcrControlNibbles() {
        assertEquals(0x37, Mc68901RefactoredSwingDemo.packTcdcrWriteValue(3, 7));
        assertEquals(0x77, Mc68901RefactoredSwingDemo.packTcdcrWriteValue(0xF, 7));
    }

    @Test
    void configuresAllTimerRegisters() {
        Mc68901Refactored mfp = new Mc68901Refactored();
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        Mc68901RefactoredSwingDemo.configureTimers(mfp, 1, 2, 3, 4, 0x11, 0x22, 0x33, 0x44);

        assertEquals(1, mfp.getTacr());
        assertEquals(2, mfp.getTbcr());
        assertEquals((3 << 3) | 4, mfp.getTcdcr());
        assertEquals(0x11, mfp.getTadr());
        assertEquals(0x22, mfp.getTbdr());
        assertEquals(0x33, mfp.getTcdr());
        assertEquals(0x44, mfp.getTddr());
    }
}
