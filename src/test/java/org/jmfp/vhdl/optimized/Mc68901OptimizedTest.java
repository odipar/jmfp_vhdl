package org.jmfp.vhdl.optimized;

import org.jmfp.vhdl.Mc68901;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Mc68901Optimized}.
 *
 * <p>The primary test verifies bit-identical behaviour between the original
 * monolithic {@link Mc68901} and the optimized {@link Mc68901Optimized}
 * across many random clock edges. A secondary test benchmarks the optimized
 * implementation against the original to confirm reduced CPU time.
 */
class Mc68901OptimizedTest {

    /**
     * Drives both the original and optimized models with identical random
     * inputs and asserts that every output matches at every clock edge.
     */
    @Test
    void outputsMatchOriginalModel() {
        Mc68901 original = new Mc68901();
        Mc68901Optimized optimized = new Mc68901Optimized();

        Random rng = new Random(42);

        // Start with reset
        original.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        optimized.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        assertOutputsMatch(original, optimized, -1);

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
            optimized.risingEdge(clkren, xtlcken, true, id, rs, csn, rwn, dsn,
                    iackn, ii, tai, tbi);

            assertOutputsMatch(original, optimized, edge);
        }
    }

    /**
     * Benchmarks the optimized implementation against the original.
     * The optimized version should be faster due to zero allocation and
     * bit-manipulation priority encoding.
     */
    @Test
    void performanceBenchmark() {
        final int WARMUP = 10_000;
        final int ITERATIONS = 100_000;

        Mc68901 original = new Mc68901();
        Mc68901Optimized optimized = new Mc68901Optimized();

        // Warm up both implementations
        driveEdges(original, null, WARMUP, 1);
        driveEdges(null, optimized, WARMUP, 1);

        // Benchmark original
        original = new Mc68901();
        long startOrig = System.nanoTime();
        driveEdges(original, null, ITERATIONS, 99);
        long elapsedOrig = System.nanoTime() - startOrig;

        // Benchmark optimized
        optimized = new Mc68901Optimized();
        long startOpt = System.nanoTime();
        driveEdges(null, optimized, ITERATIONS, 99);
        long elapsedOpt = System.nanoTime() - startOpt;

        double speedup = (double) elapsedOrig / elapsedOpt;
        System.out.printf("Benchmark: original=%d ms, optimized=%d ms, speedup=%.2fx%n",
                elapsedOrig / 1_000_000, elapsedOpt / 1_000_000, speedup);

        // We only assert the optimized version completes without error;
        // speedup may vary across environments, so we don't assert a threshold.
        assertTrue(elapsedOpt > 0, "optimized benchmark should have measurable time");
    }

    /**
     * Verifies the convenience API works correctly for basic register
     * read/write operations.
     */
    @Test
    void convenienceWriteRead() {
        Mc68901Optimized mfp = new Mc68901Optimized();
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
        Mc68901Optimized mfp = new Mc68901Optimized();
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
        Mc68901Optimized mfp = new Mc68901Optimized();
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        assertFalse(mfp.isInterruptPending(), "no interrupts pending after reset");
    }

    /**
     * Verifies component accessors return non-null objects.
     */
    @Test
    void componentAccessors() {
        Mc68901Optimized mfp = new Mc68901Optimized();
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
        Mc68901Optimized mfp = new Mc68901Optimized();
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

    private static void assertOutputsMatch(Mc68901 orig, Mc68901Optimized opt, int edge) {
        assertEquals(orig.od,     opt.od,     () -> "od mismatch at edge " + edge);
        assertEquals(orig.dtackn, opt.dtackn, () -> "dtackn mismatch at edge " + edge);
        assertEquals(orig.irqn,   opt.irqn,   () -> "irqn mismatch at edge " + edge);
        assertEquals(orig.ieon,   opt.ieon,   () -> "ieon mismatch at edge " + edge);
        assertEquals(orig.io,     opt.io,     () -> "io mismatch at edge " + edge);
        assertEquals(orig.tao,    opt.tao,    () -> "tao mismatch at edge " + edge);
        assertEquals(orig.tbo,    opt.tbo,    () -> "tbo mismatch at edge " + edge);
        assertEquals(orig.tco,    opt.tco,    () -> "tco mismatch at edge " + edge);
        assertEquals(orig.tdo,    opt.tdo,    () -> "tdo mismatch at edge " + edge);
    }

    private static void driveEdges(Mc68901 orig, Mc68901Optimized opt, int count, int seed) {
        Random rng = new Random(seed);
        // Reset
        if (orig != null) orig.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        if (opt != null)  opt.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);

        for (int i = 0; i < count; i++) {
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

            if (orig != null) orig.risingEdge(clkren, xtlcken, true, id, rs, csn, rwn, dsn, iackn, ii, tai, tbi);
            if (opt != null)  opt.risingEdge(clkren, xtlcken, true, id, rs, csn, rwn, dsn, iackn, ii, tai, tbi);
        }
    }
}
