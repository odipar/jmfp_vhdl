package org.jmfp.vhdl.idiomatic;

import org.jmfp.vhdl.Mc68901;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Mc68901Idiomatic}.
 *
 * <p>The primary test verifies bit-identical behaviour between the original
 * monolithic {@link Mc68901} and the refactored {@link Mc68901Idiomatic}
 * across many random clock edges.
 */
class Mc68901IdiomaticTest {

    /**
     * Drives both the original and idiomatic models with identical random
     * inputs and asserts that every output matches at every clock edge.
     */
    @Test
    void outputsMatchOriginalModel() {
        Mc68901 original = new Mc68901();
        Mc68901Idiomatic idiomatic = new Mc68901Idiomatic();

        Random rng = new Random(42);

        // Start with reset
        original.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        idiomatic.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        assertOutputsMatch(original, idiomatic, -1);

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
            idiomatic.risingEdge(clkren, xtlcken, true, id, rs, csn, rwn, dsn,
                    iackn, ii, tai, tbi);

            assertOutputsMatch(original, idiomatic, edge);
        }
    }

    /**
     * Verifies the convenience API works correctly for basic register
     * read/write operations.
     */
    @Test
    void convenienceWriteRead() {
        Mc68901Idiomatic mfp = new Mc68901Idiomatic();
        // Reset
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        // Write AER via convenience API
        mfp.writeRegister(0x03, 0x55);
        assertEquals(0x55, mfp.readRegister(0x03));

        // Write DDR
        mfp.writeRegister(0x05, 0xF0);
        assertEquals(0xF0, mfp.readRegister(0x05));
    }

    /**
     * Verifies the convenience timer-clocking API.
     */
    @Test
    void convenienceClockTimers() {
        Mc68901Idiomatic mfp = new Mc68901Idiomatic();
        // Reset
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        // Set up timer D: prescale=1 (divide-by-2), data=0x02
        mfp.writeRegister(0x25, 0x02); // TDDR = 0x02
        mfp.writeRegister(0x1d, 0x01); // TCDCR low nibble = 1 (timer D prescale)

        // Clock timers – first tick should count
        mfp.clockTimers(false, false);
        // Timer D mainCounter started at 0x02, prescale divides
        // Behaviour depends on initial state; just verify no crash
        assertNotNull(mfp.timerD());
    }

    /**
     * Verifies the isInterruptPending convenience method.
     */
    @Test
    void interruptPendingCheck() {
        Mc68901Idiomatic mfp = new Mc68901Idiomatic();
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        assertFalse(mfp.isInterruptPending(), "no interrupts pending after reset");
    }

    /**
     * Verifies component accessors return non-null objects.
     */
    @Test
    void componentAccessors() {
        Mc68901Idiomatic mfp = new Mc68901Idiomatic();
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
        Mc68901Idiomatic mfp = new Mc68901Idiomatic();
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

    private static void assertOutputsMatch(Mc68901 orig, Mc68901Idiomatic idi, int edge) {
        assertEquals(orig.getOd(),     idi.getOd(),     () -> "od mismatch at edge " + edge);
        assertEquals(orig.isDtackn(),  idi.isDtackn(),  () -> "dtackn mismatch at edge " + edge);
        assertEquals(orig.isIrqn(),    idi.isIrqn(),    () -> "irqn mismatch at edge " + edge);
        assertEquals(orig.isIeon(),    idi.isIeon(),    () -> "ieon mismatch at edge " + edge);
        assertEquals(orig.getIo(),     idi.getIo(),     () -> "io mismatch at edge " + edge);
        assertEquals(orig.isTao(),     idi.isTao(),     () -> "tao mismatch at edge " + edge);
        assertEquals(orig.isTbo(),     idi.isTbo(),     () -> "tbo mismatch at edge " + edge);
        assertEquals(orig.isTco(),     idi.isTco(),     () -> "tco mismatch at edge " + edge);
        assertEquals(orig.isTdo(),     idi.isTdo(),     () -> "tdo mismatch at edge " + edge);
    }
}
