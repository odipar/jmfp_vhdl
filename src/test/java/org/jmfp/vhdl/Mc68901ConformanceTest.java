package org.jmfp.vhdl;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test: replays {@code tb_feat_mc68901.vcd.zip} and asserts that
 * the Java {@link Mc68901} model produces bit-identical outputs at every
 * rising clock edge in the simulation.
 *
 * <p>The VCD file is expected at
 * {@code vhdl/simulations/tb_feat_mc68901.vcd.zip} relative to the project
 * root (the working directory during Maven tests).
 */
class Mc68901ConformanceTest {

    private static final String VCD_ZIP =
            "vhdl/simulations/tb_feat_mc68901.vcd.zip";

    @Test
    void conformanceTest() throws Exception {
        File vcdFile = new File(VCD_ZIP);
        if (!vcdFile.exists()) {
            System.err.println("[SKIP] VCD file not found: " + VCD_ZIP);
            return;
        }

        AtomicLong                          edgesOk    = new AtomicLong();
        AtomicLong                          edgesFail  = new AtomicLong();
        AtomicReference<VcdConformanceRunner.Mismatch> firstFail =
                new AtomicReference<>(null);

        Mc68901 mfp = new Mc68901();

        // Store driven values to forward on risingEdge call
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
        boolean[] tai     = {false};
        boolean[] tbi     = {false};

        VcdConformanceRunner.ModelDriver driver = new VcdConformanceRunner.ModelDriver() {
            @Override
            public void drive(boolean pClkren, boolean pXtlcken, boolean pResetn,
                              int pId, int pRs, boolean pCsn, boolean pRwn, boolean pDsn,
                              boolean pIackn, int pIi, boolean pTai, boolean pTbi) {
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
                tai[0]     = pTai;
                tbi[0]     = pTbi;
            }
            @Override
            public void risingEdge() {
                mfp.risingEdge(clkren[0], xtlcken[0], resetn[0],
                               id[0], rs[0], csn[0], rwn[0], dsn[0],
                               iackn[0], ii[0], tai[0], tbi[0]);
            }
            @Override public int     getOd()      { return mfp.getOd(); }
            @Override public boolean isDtackn()   { return mfp.isDtackn(); }
            @Override public boolean isIrqn()     { return mfp.isIrqn(); }
            @Override public boolean isIeon()     { return mfp.isIeon(); }
            @Override public int     getIo()      { return mfp.getIo(); }
            @Override public boolean isTao()      { return mfp.isTao(); }
            @Override public boolean isTbo()      { return mfp.isTbo(); }
            @Override public boolean isTco()      { return mfp.isTco(); }
            @Override public boolean isTdo()      { return mfp.isTdo(); }
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
        System.out.printf("Conformance: %d/%d edges OK, %d mismatches%n",
                edgesOk.get(), total, mismatches);

        if (firstFail.get() != null) {
            fail("First mismatch at " + firstFail.get());
        }
    }
}
