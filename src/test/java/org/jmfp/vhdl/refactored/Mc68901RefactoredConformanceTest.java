package org.jmfp.vhdl.refactored;

import org.jmfp.vhdl.VcdConformanceRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test for {@link Mc68901Refactored}: replays
 * {@code tb_feat_mc68901.vcd.zip} and asserts that the refactored model
 * produces bit-identical outputs at every rising clock edge in the simulation.
 *
 * <p>This test mirrors {@code Mc68901OptimizedConformanceTest} but drives
 * {@link Mc68901Refactored} instead.  Because {@link Mc68901Refactored}
 * accepts all inputs as {@link Mc68901Refactored#risingEdge} parameters
 * rather than mutable public fields, the {@link VcdConformanceRunner.ModelDriver}
 * stores the driven values locally and forwards them on the next
 * {@link VcdConformanceRunner.ModelDriver#risingEdge()} call.
 *
 * <p>The VCD file is expected at
 * {@code vhdl/simulations/tb_feat_mc68901.vcd.zip} relative to the project
 * root (the working directory during Maven tests).
 */
class Mc68901RefactoredConformanceTest {

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

        Mc68901Refactored mfp = new Mc68901Refactored();

        // The refactored model accepts inputs as risingEdge() parameters.
        // Store the most recently driven values here and forward them on the
        // next risingEdge() call.
        boolean[] clkren  = {true};
        boolean[] xtlcken = {false};
        boolean[] resetn  = {false};
        int[]     id      = {0};
        int[]     rs      = {0};
        boolean[] csn     = {true};
        boolean[] rwn     = {true};
        boolean[] dsn     = {true};
        boolean[] iackn   = {true};
        int[]     ii      = {0};
        int[]     iiPrev  = {0};
        boolean[] tai     = {false};
        boolean[] tbi     = {false};

        VcdConformanceRunner.ModelDriver driver = new VcdConformanceRunner.ModelDriver() {
            @Override
            public void drive(boolean pClkren, boolean pXtlcken, boolean pResetn,
                              int pId, int pRs, boolean pCsn, boolean pRwn, boolean pDsn,
                              boolean pIackn, int pIi, int pIiPrev, boolean pTai, boolean pTbi) {
                clkren[0]  = pClkren;
                xtlcken[0] = pXtlcken;
                resetn[0]  = pResetn;
                id[0]      = pId;
                rs[0]      = pRs;
                csn[0]     = pCsn;
                rwn[0]     = pRwn;
                dsn[0]     = pDsn;
                iackn[0]   = pIackn;
                ii[0]      = pIi;
                iiPrev[0]  = pIiPrev;
                tai[0]     = pTai;
                tbi[0]     = pTbi;
            }

            @Override
            public void risingEdge() {
                mfp.risingEdge(clkren[0], xtlcken[0], resetn[0],
                               id[0], rs[0], csn[0], rwn[0], dsn[0],
                               iackn[0], ii[0], iiPrev[0], tai[0], tbi[0]);
            }

            @Override public int     getOd()    { return mfp.od; }
            @Override public boolean isDtackn() { return mfp.dtackn; }
            @Override public boolean isIrqn()   { return mfp.irqn; }
            @Override public boolean isIeon()   { return mfp.ieon; }
            @Override public int     getIo()    { return mfp.io; }
            @Override public boolean isTao()    { return mfp.tao; }
            @Override public boolean isTbo()    { return mfp.tbo; }
            @Override public boolean isTco()    { return mfp.tco; }
            @Override public boolean isTdo()    { return mfp.tdo; }
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
        System.out.printf("Refactored conformance: %d/%d edges OK, %d mismatches%n",
                edgesOk.get(), total, mismatches);

        if (firstFail.get() != null) {
            fail("First mismatch at " + firstFail.get());
        }
    }
}
