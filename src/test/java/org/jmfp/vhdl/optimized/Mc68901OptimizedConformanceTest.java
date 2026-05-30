package org.jmfp.vhdl.optimized;

import org.jmfp.vhdl.VcdConformanceRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test for {@link Mc68901Optimized}: replays
 * {@code tb_feat_mc68901.vcd.zip} and asserts that the optimized model
 * produces bit-identical outputs at every rising clock edge in the simulation.
 *
 * <p>This test mirrors {@code Mc68901IdiomaticConformanceTest} but drives
 * {@link Mc68901Optimized} instead of {@link org.jmfp.vhdl.idiomatic.Mc68901Idiomatic},
 * thereby proving that the optimized reimplementation preserves full
 * cycle-accurate correctness.
 *
 * <p>The VCD file is expected at
 * {@code vhdl/simulations/tb_feat_mc68901.vcd.zip} relative to the project
 * root (the working directory during Maven tests).
 */
class Mc68901OptimizedConformanceTest {

    private static final String VCD_ZIP = "vhdl/simulations/tb_feat_mc68901.vcd.zip";

    @Test
    void conformanceTest() throws Exception {
        File vcdFile = new File(VCD_ZIP);
        if (!vcdFile.exists()) {
            System.err.println("[SKIP] VCD file not found: " + VCD_ZIP);
            return;
        }

        AtomicLong edgesOk   = new AtomicLong();
        AtomicLong edgesFail = new AtomicLong();
        AtomicReference<VcdConformanceRunner.Mismatch> firstFail = new AtomicReference<>(null);

        Mc68901Optimized mfp = new Mc68901Optimized();
        VcdConformanceRunner.ModelDriver driver = new VcdConformanceRunner.ModelDriver() {
            @Override
            public void drive(boolean clkren, boolean xtlcken, boolean resetn,
                              int id, int rs, boolean csn, boolean rwn, boolean dsn,
                              boolean iackn, int ii, int iiPrev, boolean tai, boolean tbi) {
                mfp.clkren  = clkren;
                mfp.xtlcken = xtlcken;
                mfp.resetn  = resetn;
                mfp.id      = id;
                mfp.rs      = rs;
                mfp.csn     = csn;
                mfp.rwn     = rwn;
                mfp.dsn     = dsn;
                mfp.iackn   = iackn;
                mfp.ii      = ii;
                mfp.iiPrev  = iiPrev;
                mfp.tai     = tai;
                mfp.tbi     = tbi;
            }
            @Override public void    risingEdge() { mfp.risingEdge(); }
            @Override public int     getOd()      { return mfp.od; }
            @Override public boolean isDtackn()   { return mfp.dtackn; }
            @Override public boolean isIrqn()     { return mfp.irqn; }
            @Override public boolean isIeon()     { return mfp.ieon; }
            @Override public int     getIo()      { return mfp.io; }
            @Override public boolean isTao()      { return mfp.tao; }
            @Override public boolean isTbo()      { return mfp.tbo; }
            @Override public boolean isTco()      { return mfp.tco; }
            @Override public boolean isTdo()      { return mfp.tdo; }
        };

        VcdConformanceRunner runner = new VcdConformanceRunner();
        long mismatches = runner.run(VCD_ZIP, 0, (edge, m) -> {
            if (m == null) {
                edgesOk.incrementAndGet();
            } else {
                edgesFail.incrementAndGet();
                firstFail.compareAndSet(null, m);
                if (edgesFail.get() <= 10) {
                    System.err.println("MISMATCH: " + m);
                }
            }
        }, driver);

        long total = edgesOk.get() + edgesFail.get();
        System.out.printf("Optimized conformance: %d/%d edges OK, %d mismatches%n",
                edgesOk.get(), total, mismatches);

        if (firstFail.get() != null) {
            fail("First mismatch at " + firstFail.get());
        }
    }
}
