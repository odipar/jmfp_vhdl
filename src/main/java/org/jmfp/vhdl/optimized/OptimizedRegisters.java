package org.jmfp.vhdl.optimized;

/**
 * CPU-optimized register file that avoids per-edge object allocation.
 *
 * <p>Key optimizations over {@link org.jmfp.vhdl.idiomatic.RegisterFile}:
 * <ul>
 *   <li>No {@code Snapshot} record allocation – uses mutable snapshot fields
 *       that are bulk-copied before each edge via {@link #takeSnapshot()}.</li>
 *   <li>Snapshot fields are directly accessible for zero-overhead reads.</li>
 * </ul>
 */
public final class OptimizedRegisters {

    // --- Live registers ---
    public int gpip;
    public int aer;
    public int ddr;
    public int iera;
    public int ierb;
    public int ipra;
    public int iprb;
    public int isra;
    public int isrb;
    public int imra;
    public int imrb;
    public int vr;
    public int tacr;
    public int tbcr;
    public int tcdcr;
    public int tadr;
    public int tbdr;
    public int tcdr;
    public int tddr;
    public int scr;
    public int ucr;
    public int rsr;
    public int tsr;
    public int udr;

    // --- Snapshot fields (pre-edge copy, avoids allocation) ---
    public int s_iera, s_ierb, s_ipra, s_iprb;
    public int s_isra, s_isrb, s_imra, s_imrb;
    public int s_vr, s_tacr, s_tbcr, s_tcdcr;
    public int s_tadr, s_tbdr, s_tcdr, s_tddr;

    // Associated timers
    private final OptimizedTimerUnit timerA;
    private final OptimizedTimerUnit timerB;
    private final OptimizedTimerUnit timerC;
    private final OptimizedTimerUnit timerD;

    public OptimizedRegisters(OptimizedTimerUnit a, OptimizedTimerUnit b,
                              OptimizedTimerUnit c, OptimizedTimerUnit d) {
        this.timerA = a;
        this.timerB = b;
        this.timerC = c;
        this.timerD = d;
    }

    public void reset() {
        gpip = 0; aer = 0; ddr = 0;
        iera = 0; ierb = 0;
        ipra = 0; iprb = 0;
        isra = 0; isrb = 0;
        imra = 0; imrb = 0;
        vr = 0x01;
        tacr = 0; tbcr = 0; tcdcr = 0;
        scr = 0; ucr = 0; rsr = 0; tsr = 0; udr = 0;
        tadr = 0; tbdr = 0; tcdr = 0; tddr = 0;
    }

    /**
     * Copies current register values into snapshot fields.
     * Call this once per edge instead of allocating a Snapshot record.
     */
    public void takeSnapshot() {
        s_iera = iera; s_ierb = ierb;
        s_ipra = ipra; s_iprb = iprb;
        s_isra = isra; s_isrb = isrb;
        s_imra = imra; s_imrb = imrb;
        s_vr = vr; s_tacr = tacr; s_tbcr = tbcr; s_tcdcr = tcdcr;
        s_tadr = tadr; s_tbdr = tbdr; s_tcdr = tcdr; s_tddr = tddr;
    }

    // ----------------------------------------------------------------
    //  Register read (uses snapshot fields)
    // ----------------------------------------------------------------

    public int read(int addr, int ii) {
        return switch (addr) {
            case 0x01 -> ((gpip & ddr) | (ii & (~ddr & 0xFF))) & 0xFF;
            case 0x03 -> aer;
            case 0x05 -> ddr;
            case 0x07 -> s_iera;
            case 0x09 -> s_ierb;
            case 0x0b -> s_ipra;
            case 0x0d -> s_iprb;
            case 0x0f -> s_isra;
            case 0x11 -> s_isrb;
            case 0x13 -> s_imra;
            case 0x15 -> s_imrb;
            case 0x17 -> (s_vr << 3) & 0xFF;
            case 0x19 -> s_tacr & 0x0F;
            case 0x1b -> s_tbcr & 0x0F;
            case 0x1d -> (((s_tcdcr >> 3) & 0x7) << 4) | (s_tcdcr & 0x7);
            case 0x1f -> timerA.getPipelineRead() & 0xFF;
            case 0x21 -> timerB.getPipelineRead() & 0xFF;
            case 0x23 -> timerC.getPipelineRead() & 0xFF;
            case 0x25 -> timerD.getPipelineRead() & 0xFF;
            case 0x27 -> scr;
            case 0x29 -> ucr;
            case 0x2b -> rsr;
            case 0x2d -> tsr;
            case 0x2f -> udr;
            default   -> 0xFF;
        };
    }

    // ----------------------------------------------------------------
    //  Register write (uses snapshot fields)
    // ----------------------------------------------------------------

    public void write(int addr, int data) {
        data &= 0xFF;
        switch (addr) {
            case 0x01 -> gpip = data;
            case 0x03 -> aer = data;
            case 0x05 -> ddr = data;
            case 0x07 -> { iera = data; ipra = s_ipra & data; }
            case 0x09 -> { ierb = data; iprb = s_iprb & data; }
            case 0x0b -> ipra = s_ipra & data;
            case 0x0d -> iprb = s_iprb & data;
            case 0x0f -> isra = s_isra & data;
            case 0x11 -> isrb = s_isrb & data;
            case 0x13 -> imra = data;
            case 0x15 -> imrb = data;
            case 0x17 -> {
                vr = (data >> 3) & 0x1F;
                if ((data & 0x08) == 0) {
                    isra = 0;
                    isrb = 0;
                }
            }
            case 0x19 -> {
                tacr = data & 0x0F;
                timerA.writeControlAB(data, s_tacr);
            }
            case 0x1b -> {
                tbcr = data & 0x0F;
                timerB.writeControlAB(data, s_tbcr);
            }
            case 0x1d -> {
                int newTcdcr = ((data >> 4) & 0x7) << 3 | (data & 0x7);
                if (((s_tcdcr >> 3) & 0x7) == 0 && ((data >> 4) & 0x7) != 0) {
                    timerC.initPrescale((data >> 4) & 0x7);
                }
                if ((s_tcdcr & 0x7) == 0 && (data & 0x7) != 0) {
                    timerD.initPrescale(data & 0x7);
                }
                tcdcr = newTcdcr;
            }
            case 0x1f -> {
                tadr = data;
                timerA.writeData(data, s_tacr == 0x0);
            }
            case 0x21 -> {
                tbdr = data;
                timerB.writeData(data, s_tbcr == 0x0);
            }
            case 0x23 -> {
                tcdr = data;
                timerC.writeData(data, ((s_tcdcr >> 3) & 0x7) == 0);
            }
            case 0x25 -> {
                tddr = data;
                timerD.writeData(data, (s_tcdcr & 0x7) == 0);
            }
            case 0x27 -> scr = data;
            case 0x29 -> ucr = data;
            case 0x2b -> rsr = data;
            case 0x2d -> tsr = data;
            case 0x2f -> udr = data;
            default -> { /* unmapped */ }
        }
    }
}
