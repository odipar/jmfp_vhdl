package org.jmfp.vhdl;

import java.io.IOException;
import java.util.Set;

/**
 * Replays a {@code tb_feat_mc68901.vcd} (or its .zip companion) and drives
 * any {@link ModelDriver} implementation through every recorded clock edge,
 * collecting any output mismatches.
 *
 * <h2>Signal-symbol mapping (UUT scope inside VCD)</h2>
 * <pre>
 *  Input symbols (UUT scope, match testbench):
 *    !  clk         "  clkren      #  clkfen     $  xtlcken
 *    %  resetn      &  id[7:0]     (  rs[5:1]    )  csn
 *    *  rwn         +  dsn         .  iackn       /  iein
 *    1  ii[7:0]     3  tai         4  tbi
 *
 *  Output symbols (UUT scope only):
 *    ?  od[7:0]     @  dtackn      A  irqn        B  ieon
 *    C  io[7:0]     D  tao         E  tbo         F  tco
 *    G  tdo         H  so          I  rrn         J  trn
 * </pre>
 */
public class VcdConformanceRunner {

    // VCD symbol constants — UUT scope (public so that callers can reuse them)
    public static final String SYM_CLK     = "!";
    public static final String SYM_CLKREN  = "\"";
    public static final String SYM_XTLCKEN = "$";
    public static final String SYM_RESETN  = "%";
    public static final String SYM_ID      = "&";
    public static final String SYM_RS      = "(";
    public static final String SYM_CSN     = ")";
    public static final String SYM_RWN     = "*";
    public static final String SYM_DSN     = "+";
    public static final String SYM_IACKN   = ".";
    public static final String SYM_II      = "1";
    public static final String SYM_TAI     = "3";
    public static final String SYM_TBI     = "4";

    public static final String SYM_OD      = "?";
    public static final String SYM_DTACKN  = "@";
    public static final String SYM_IRQN    = "A";
    public static final String SYM_IEON    = "B";
    public static final String SYM_IO      = "C";
    public static final String SYM_TAO     = "D";
    public static final String SYM_TBO     = "E";
    public static final String SYM_TCO     = "F";
    public static final String SYM_TDO     = "G";

    // -------------------------------------------------------------------------

    /**
     * Abstracts over the specific MC68901 model implementation being tested.
     *
     * <p>Implementors map the 13 input signals onto their model's public fields,
     * delegate {@link #risingEdge()} to the model, and expose its 9 output
     * signals through the accessor methods.
     */
    public interface ModelDriver {
        /** Drive all 12 input signals into the model for the current rising edge. */
        void drive(boolean clkren, boolean xtlcken, boolean resetn,
                   int id, int rs, boolean csn, boolean rwn, boolean dsn,
                   boolean iackn, int ii, boolean tai, boolean tbi);
        /** Advance the model by one clock cycle. */
        void risingEdge();
        /** 8-bit data-bus output (od[7:0]). */
        int     getOd();
        /** Active-low data-transfer acknowledge (dtackn). */
        boolean isDtackn();
        /** Active-low interrupt request (irqn). */
        boolean isIrqn();
        /** Interrupt daisy-chain output (ieon). */
        boolean isIeon();
        /** 8-bit general-purpose I/O output (io[7:0]). */
        int     getIo();
        /** Timer A output (tao). */
        boolean isTao();
        /** Timer B output (tbo). */
        boolean isTbo();
        /** Timer C output (tco). */
        boolean isTco();
        /** Timer D output (tdo). */
        boolean isTdo();
    }

    // -------------------------------------------------------------------------

    /** One mismatch record. */
    public static class Mismatch {
        public final long   time;
        public final long   edge;      // rising-edge count (1-based)
        public final String signal;
        public final long   expected;
        public final long   actual;

        public Mismatch(long time, long edge, String signal, long expected, long actual) {
            this.time     = time;
            this.edge     = edge;
            this.signal   = signal;
            this.expected = expected;
            this.actual   = actual;
        }

        @Override
        public String toString() {
            return String.format("t=%d edge=%d %s: expected=%d actual=%d",
                    time, edge, signal, expected, actual);
        }
    }

    /** Listener called for every rising edge. */
    @FunctionalInterface
    public interface EdgeListener {
        /** @param edge   1-based edge index  @param mismatch  null if OK */
        void onEdge(long edge, Mismatch mismatch);
    }

    // -------------------------------------------------------------------------

    /**
     * Run conformance check against the given VCD zip file.
     *
     * @param vcdZipPath    path to tb_feat_mc68901.vcd.zip
     * @param maxMismatches stop after this many mismatches (0 = unlimited)
     * @param listener      optional per-edge callback (may be null)
     * @param driver        the MC68901 model to drive
     * @return total number of mismatches found
     */
    public long run(String vcdZipPath, long maxMismatches, EdgeListener listener,
                    ModelDriver driver) throws IOException {

        long[]    edgeCount     = {0L};
        long[]    mismatchCount = {0L};
        boolean[] stop          = {false};

        // xtlcken is driven by xtlcken_proc, a VHDL process that is itself
        // sensitive to clk.  It therefore changes one simulation delta *after*
        // the rising clock edge, i.e. after the UUT process has already run.
        // Concretely: at timestamp T_k, the VCD records the new (post-flip)
        // xtlcken value, but the VHDL UUT still sees the pre-flip value from
        // T_{k-1}.  All other inputs are driven by the stimulus process through
        // timed waits, which fire in the same delta as the clock; the UUT
        // therefore sees their current VCD values on the rising edge.
        //
        // Fix: snapshot the VCD xtlcken value at the *end* of every callback
        // and feed that lagged value into risingEdge() on the next rising edge.
        boolean[] prevXtlcken = {false};   // matches VHDL initial value '0'

        // ii (GPIP input) is driven by the stimulus process using timed waits.
        // Because these waits land on clock-edge boundaries, ii often changes at
        // the exact same simulation delta as the rising clock edge.  The VHDL
        // concurrent signal ii0 <= ii xor aer lags ii by one delta, so the
        // clocked process reads the *old* ii0 on the edge where ii also changes.
        // Fix: snapshot ii at the end of every callback and pass the lagged
        // value as ii into the model — the model handles delta-lag internally.
        int[] prevIi = {0};               // matches VHDL initial value x"00"

        VcdParser parser = new VcdParser();
        parser.parseZip(vcdZipPath, (time, changed, p) -> {

            if (stop[0]) return;

            // Detect rising CLK edge
            if (changed.contains(SYM_CLK) && p.getValue(SYM_CLK) == 1L) {

                edgeCount[0]++;
                long edge = edgeCount[0];

                // 1. Drive inputs.  Use the lagged snapshot for xtlcken and ii;
                //    use current VCD values for all other inputs.
                driver.drive(
                        p.getValue(SYM_CLKREN) != 0,
                        prevXtlcken[0],
                        p.getValue(SYM_RESETN) != 0,
                        (int) p.getValue(SYM_ID),
                        (int) p.getValue(SYM_RS),
                        p.getValue(SYM_CSN)    != 0,
                        p.getValue(SYM_RWN)    != 0,
                        p.getValue(SYM_DSN)    != 0,
                        p.getValue(SYM_IACKN)  != 0,
                        prevIi[0],
                        p.getValue(SYM_TAI)    != 0,
                        p.getValue(SYM_TBI)    != 0);

                // 2. Clock the Java model
                driver.risingEdge();

                // 3. Compare Java outputs to VCD expected values
                Mismatch m = null;
                m = check(m, time, edge, "od",     p.getValue(SYM_OD),     driver.getOd()     & 0xFF);
                m = check(m, time, edge, "dtackn", p.getValue(SYM_DTACKN), driver.isDtackn()  ? 1 : 0);
                m = check(m, time, edge, "irqn",   p.getValue(SYM_IRQN),   driver.isIrqn()    ? 1 : 0);
                m = check(m, time, edge, "ieon",   p.getValue(SYM_IEON),   driver.isIeon()    ? 1 : 0);
                m = check(m, time, edge, "io",     p.getValue(SYM_IO),     driver.getIo()     & 0xFF);
                m = check(m, time, edge, "tao",    p.getValue(SYM_TAO),    driver.isTao()     ? 1 : 0);
                m = check(m, time, edge, "tbo",    p.getValue(SYM_TBO),    driver.isTbo()     ? 1 : 0);
                m = check(m, time, edge, "tco",    p.getValue(SYM_TCO),    driver.isTco()     ? 1 : 0);
                m = check(m, time, edge, "tdo",    p.getValue(SYM_TDO),    driver.isTdo()     ? 1 : 0);

                if (m != null) {
                    mismatchCount[0]++;
                    if (listener != null) listener.onEdge(edge, m);
                    if (maxMismatches > 0 && mismatchCount[0] >= maxMismatches) {
                        stop[0] = true;
                    }
                } else {
                    if (listener != null) listener.onEdge(edge, null);
                }
            }

            // Snapshot xtlcken and ii at the end of every callback so that the
            // next rising-edge call receives the pre-edge (lagged) values.
            prevXtlcken[0] = p.getValue(SYM_XTLCKEN) != 0;
            prevIi[0]      = (int) p.getValue(SYM_II);
        });

        return mismatchCount[0];
    }

    /** Return {@code m} unchanged if values match; otherwise return a new Mismatch for the first failure. */
    private static Mismatch check(Mismatch m, long time, long edge,
                                   String name, long expected, long actual) {
        if (m != null) return m;    // only report first mismatch per edge
        if (expected != actual) return new Mismatch(time, edge, name, expected, actual);
        return null;
    }
}
