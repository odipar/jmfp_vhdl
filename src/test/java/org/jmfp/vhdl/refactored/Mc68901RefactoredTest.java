package org.jmfp.vhdl.refactored;

import org.jmfp.vhdl.Mc68901;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test for {@link Mc68901Refactored}.
 *
 * <p>Verifies bit-identical behaviour between the original monolithic
 * {@link Mc68901} and the refactored {@link Mc68901Refactored} across
 * many random clock edges.
 */
class Mc68901RefactoredTest {

    /**
     * Drives both the original and refactored models with identical random
     * inputs and asserts that every output matches at every clock edge.
     */
    @Test
    void outputsMatchOriginalModel() {
        Mc68901 original = new Mc68901();
        Mc68901Refactored refactored = new Mc68901Refactored();

        Random rng = new Random(42);

        // Start with reset
        original.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        refactored.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        assertOutputsMatch(original, refactored, -1);

        // Drive many random edges
        for (int edge = 0; edge < 50_000; edge++) {
            boolean clkren  = rng.nextBoolean();
            boolean xtlcken = rng.nextBoolean();
            int     id      = rng.nextInt(256);
            int     rs      = rng.nextInt(16);
            boolean csn     = rng.nextBoolean();
            boolean rwn     = rng.nextBoolean();
            boolean dsn     = rng.nextBoolean();
            boolean iackn   = rng.nextBoolean();
            int     ii      = rng.nextInt(256);
            boolean tai     = rng.nextBoolean();
            boolean tbi     = rng.nextBoolean();

            original.risingEdge(clkren, xtlcken, true, id, rs, csn, rwn, dsn,
                    iackn, ii, tai, tbi);
            refactored.risingEdge(clkren, xtlcken, true, id, rs, csn, rwn, dsn,
                    iackn, ii, tai, tbi);

            assertOutputsMatch(original, refactored, edge);
        }
    }

    /**
     * Verifies the convenience API works correctly for basic register
     * read/write operations.
     */
    @Test
    void convenienceWriteRead() {
        Mc68901Refactored mfp = new Mc68901Refactored();
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        mfp.writeRegister(0x03, 0x55);
        assertEquals(0x55, mfp.readRegister(0x03));

        mfp.writeRegister(0x05, 0xF0);
        assertEquals(0xF0, mfp.readRegister(0x05));
    }

    /**
     * Verifies the convenience timer-clocking API.
     */
    @Test
    void convenienceClockTimers() {
        Mc68901Refactored mfp = new Mc68901Refactored();
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        mfp.writeRegister(0x25, 0x02);
        mfp.writeRegister(0x1d, 0x01);

        mfp.clockTimers(false, false);
        assertNotNull(mfp.timerD());
    }

    /**
     * Verifies the isInterruptPending convenience method.
     */
    @Test
    void interruptPendingCheck() {
        Mc68901Refactored mfp = new Mc68901Refactored();
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        assertFalse(mfp.isInterruptPending(), "no interrupts pending after reset");
    }

    /**
     * Verifies component accessors return non-null objects.
     */
    @Test
    void componentAccessors() {
        Mc68901Refactored mfp = new Mc68901Refactored();
        assertNotNull(mfp.registers());
        assertNotNull(mfp.timerA());
        assertNotNull(mfp.timerB());
        assertNotNull(mfp.timerC());
        assertNotNull(mfp.timerD());
    }

    /**
     * Verifies getters match original after reset.
     */
    @Test
    void gettersAfterReset() {
        Mc68901Refactored mfp = new Mc68901Refactored();
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        Mc68901 orig = new Mc68901();
        orig.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        assertEquals(orig.getGpip(), mfp.getGpip());
        assertEquals(orig.getAer(),  mfp.getAer());
        assertEquals(orig.getDdr(),  mfp.getDdr());
        assertEquals(orig.getIera(), mfp.getIera());
        assertEquals(orig.getIerb(), mfp.getIerb());
        assertEquals(orig.getVr(),   mfp.getVr());
        assertEquals(orig.getTacr(), mfp.getTacr());
        assertEquals(orig.getTbcr(), mfp.getTbcr());
        assertEquals(orig.getTcdcr(), mfp.getTcdcr());
        assertEquals(orig.getScr(),  mfp.getScr());
        assertEquals(orig.getUcr(),  mfp.getUcr());
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private static void assertOutputsMatch(Mc68901 orig, Mc68901Refactored ref, int edge) {
        assertEquals(orig.getOd(),     ref.getOd(),     () -> "od mismatch at edge " + edge);
        assertEquals(orig.isDtackn(),  ref.isDtackn(),  () -> "dtackn mismatch at edge " + edge);
        assertEquals(orig.isIrqn(),    ref.isIrqn(),    () -> "irqn mismatch at edge " + edge);
        assertEquals(orig.isIeon(),    ref.isIeon(),    () -> "ieon mismatch at edge " + edge);
        assertEquals(orig.getIo(),     ref.getIo(),     () -> "io mismatch at edge " + edge);
        assertEquals(orig.isTao(),     ref.isTao(),     () -> "tao mismatch at edge " + edge);
        assertEquals(orig.isTbo(),     ref.isTbo(),     () -> "tbo mismatch at edge " + edge);
        assertEquals(orig.isTco(),     ref.isTco(),     () -> "tco mismatch at edge " + edge);
        assertEquals(orig.isTdo(),     ref.isTdo(),     () -> "tdo mismatch at edge " + edge);
    }
}
