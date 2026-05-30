package org.jmfp.vhdl.optimized;

/**
 * CPU-optimized implementation of the MC68901 Multi-Function Peripheral chip,
 * behaviourally equivalent to {@link org.jmfp.vhdl.idiomatic.Mc68901Idiomatic}.
 *
 * <h2>Key optimizations</h2>
 * <ul>
 *   <li>Zero per-edge heap allocation: no Snapshot record, no TickResult record.</li>
 *   <li>Bit-manipulation {@code priority()} using {@code Integer.numberOfLeadingZeros}
 *       instead of a loop.</li>
 *   <li>Pre-computed prescale lookup table in {@link OptimizedTimerUnit}.</li>
 *   <li>Inlined pipeline array as four fields in {@link OptimizedTimerUnit}.</li>
 * </ul>
 *
 * @see org.jmfp.vhdl.idiomatic.Mc68901Idiomatic
 */
public final class Mc68901Optimized {

    // ---- Chip outputs ----
    private int     od;
    private boolean dtackn;
    private boolean irqn;
    private boolean ieon;
    private int     io;
    private boolean tao;
    private boolean tbo;
    private boolean tco;
    private boolean tdo;

    // ---- Factored-out components ----
    private final OptimizedTimerUnit timerA = new OptimizedTimerUnit();
    private final OptimizedTimerUnit timerB = new OptimizedTimerUnit();
    private final OptimizedTimerUnit timerC = new OptimizedTimerUnit();
    private final OptimizedTimerUnit timerD = new OptimizedTimerUnit();
    private final OptimizedRegisters regs   = new OptimizedRegisters(timerA, timerB, timerC, timerD);

    // ---- Internal state ----
    private boolean csn1;
    private int     siackn;
    private boolean xtldiv;
    private boolean dtackn_irq;
    private boolean dtackn_reg;
    private int     sod;
    private int     ii1;

    private boolean lastCsn   = true;
    private boolean lastIackn = true;
    private boolean lastDsn   = true;

    public Mc68901Optimized() {
        reset();
    }

    // ================================================================
    //  Low-level cycle-accurate API
    // ================================================================

    public void risingEdge(boolean clkren, boolean xtlcken, boolean resetn,
                           int id, int rs, boolean csn, boolean rwn, boolean dsn,
                           boolean iackn, int ii, boolean tai, boolean tbi) {
        lastCsn   = csn;
        lastIackn = iackn;
        lastDsn   = dsn;

        if (!resetn) {
            reset();
            computeOutputs();
            return;
        }

        // === Save pre-edge state ===
        boolean p_xtldiv = xtldiv;
        boolean p_csn1   = csn1;
        int     p_siackn = siackn;
        int     p_ii1    = ii1;
        boolean p_dtackn_irq = dtackn_irq;

        regs.takeSnapshot();

        boolean p_tato = timerA.getOutput();
        boolean p_tbto = timerB.getOutput();
        boolean p_tcto = timerC.getOutput();
        boolean p_tdto = timerD.getOutput();

        // Pre-edge combinational signals
        int p_ii0 = (ii ^ regs.aer) & 0xFF;
        int p_trd = (p_ii1 & ~p_ii0 & ~regs.ddr) & 0xFF;
        int p_intv = ((regs.s_ipra & regs.s_imra) << 8
                    | (regs.s_iprb & regs.s_imrb)) & 0xFFFF;
        int p_ipl = priority(p_intv);
        int p_isr_ipl = priority(((regs.s_isra << 8) | regs.s_isrb) & 0xFFFF);
        boolean p_sirqn = !((p_intv != 0 && !bit(regs.s_vr, 0))
                || p_ipl > p_isr_ipl || !p_dtackn_irq);

        // === xtlcken domain ===
        if (xtlcken) {
            xtldiv = !p_xtldiv;
        }
        if (xtlcken && !p_xtldiv) {
            // Timer A
            timerA.tickFull(regs.s_tacr, regs.s_tadr, tai,
                    bit(regs.aer, 4), bit(regs.s_ierb, 6), bit(regs.s_iera, 5));
            if (timerA.getLastPulseCountInterrupt()) regs.iprb |= (1 << 6);
            if (timerA.getLastTimeoutInterrupt())    regs.ipra |= (1 << 5);

            // Timer B
            timerB.tickFull(regs.s_tbcr, regs.s_tbdr, tbi,
                    bit(regs.aer, 3), bit(regs.s_ierb, 3), bit(regs.s_iera, 0));
            if (timerB.getLastPulseCountInterrupt()) regs.iprb |= (1 << 3);
            if (timerB.getLastTimeoutInterrupt())    regs.ipra |= (1 << 0);

            // Timer C
            if (timerC.tick((regs.s_tcdcr >> 3) & 0x7, regs.s_tcdr)
                    && bit(regs.s_ierb, 5)) {
                regs.iprb |= (1 << 5);
            }

            // Timer D
            if (timerD.tick(regs.s_tcdcr & 0x7, regs.s_tddr)
                    && bit(regs.s_ierb, 4)) {
                regs.iprb |= (1 << 4);
            }
        }

        // === clkren domain ===
        if (clkren) {
            timerA.shiftPipeline();
            timerB.shiftPipeline();
            timerC.shiftPipeline();
            timerD.shiftPipeline();

            csn1 = csn;
            siackn = ((iackn ? 1 : 0) << 2) | ((p_siackn >> 1) & 0x3);
            sod = 0xFF;
            dtackn_irq = true;
            dtackn_reg = true;

            if (!csn && !p_csn1) {
                int addr = (rs << 1) | 0x01;
                if (rwn) {
                    sod = regs.read(addr, ii);
                } else {
                    regs.write(addr, id);
                }
                dtackn_reg = false;
            }

            // GPIP edge detection
            ii1 = p_ii0;
            if (bit(p_trd, 7) && bit(regs.s_iera, 7)) regs.ipra |= (1 << 7);
            if (bit(p_trd, 6) && bit(regs.s_iera, 6)) regs.ipra |= (1 << 6);
            if (bit(p_trd, 5) && bit(regs.s_ierb, 7)) regs.iprb |= (1 << 7);
            if (bit(p_trd, 4) && bit(regs.s_ierb, 6)) regs.iprb |= (1 << 6);
            if (bit(p_trd, 3) && bit(regs.s_ierb, 3)) regs.iprb |= (1 << 3);
            if (bit(p_trd, 2) && bit(regs.s_ierb, 2)) regs.iprb |= (1 << 2);
            if (bit(p_trd, 1) && bit(regs.s_ierb, 1)) regs.iprb |= (1 << 1);
            if (bit(p_trd, 0) && bit(regs.s_ierb, 0)) regs.iprb |= (1 << 0);

            // Interrupt acknowledge cycle
            if (!p_sirqn && !iackn && !bit(p_siackn, 0) && !dsn) {
                dtackn_irq = false;
                sod = (((regs.s_vr >> 1) & 0x0F) << 4) | (p_ipl & 0xF);
                if (bit(p_ipl, 3)) {
                    regs.ipra = regs.s_ipra & ~(1 << (p_ipl & 0x7));
                } else {
                    regs.iprb = regs.s_iprb & ~(1 << (p_ipl & 0x7));
                }
                if (bit(regs.s_vr, 0)) {
                    if (bit(p_ipl, 3)) {
                        regs.isra = regs.s_isra | (1 << (p_ipl & 0x7));
                    } else {
                        regs.isrb = regs.s_isrb | (1 << (p_ipl & 0x7));
                    }
                }
            }
        }

        computeOutputs();
    }

    // ================================================================
    //  High-level convenience API
    // ================================================================

    public void writeRegister(int addr, int data) {
        regs.takeSnapshot();
        regs.write(addr & 0xFF, data & 0xFF);
    }

    public int readRegister(int addr, int ii) {
        regs.takeSnapshot();
        return regs.read(addr & 0xFF, ii);
    }

    public int readRegister(int addr) {
        return readRegister(addr, 0);
    }

    public void clockTimers(boolean tai, boolean tbi) {
        regs.takeSnapshot();

        timerA.tickFull(regs.s_tacr, regs.s_tadr, tai,
                bit(regs.aer, 4), bit(regs.s_ierb, 6), bit(regs.s_iera, 5));
        if (timerA.getLastPulseCountInterrupt()) regs.iprb |= (1 << 6);
        if (timerA.getLastTimeoutInterrupt())    regs.ipra |= (1 << 5);

        timerB.tickFull(regs.s_tbcr, regs.s_tbdr, tbi,
                bit(regs.aer, 3), bit(regs.s_ierb, 3), bit(regs.s_iera, 0));
        if (timerB.getLastPulseCountInterrupt()) regs.iprb |= (1 << 3);
        if (timerB.getLastTimeoutInterrupt())    regs.ipra |= (1 << 0);

        if (timerC.tick((regs.s_tcdcr >> 3) & 0x7, regs.s_tcdr)
                && bit(regs.s_ierb, 5)) {
            regs.iprb |= (1 << 5);
        }
        if (timerD.tick(regs.s_tcdcr & 0x7, regs.s_tddr)
                && bit(regs.s_ierb, 4)) {
            regs.iprb |= (1 << 4);
        }

        tao = timerA.getOutput();
        tbo = timerB.getOutput();
        tco = timerC.getOutput();
        tdo = timerD.getOutput();
    }

    public boolean isInterruptPending() {
        int intv = ((regs.ipra & regs.imra) << 8 | (regs.iprb & regs.imrb)) & 0xFFFF;
        return intv != 0;
    }

    // ================================================================
    //  Component accessors
    // ================================================================

    public OptimizedRegisters registers() { return regs; }
    public OptimizedTimerUnit timerA() { return timerA; }
    public OptimizedTimerUnit timerB() { return timerB; }
    public OptimizedTimerUnit timerC() { return timerC; }
    public OptimizedTimerUnit timerD() { return timerD; }

    // ================================================================
    //  Getters
    // ================================================================

    public int     getOd()      { return od; }
    public boolean isDtackn()   { return dtackn; }
    public boolean isIrqn()     { return irqn; }
    public boolean isIeon()     { return ieon; }
    public int     getIo()      { return io; }
    public boolean isTao()      { return tao; }
    public boolean isTbo()      { return tbo; }
    public boolean isTco()      { return tco; }
    public boolean isTdo()      { return tdo; }

    public int getGpip()  { return regs.gpip; }
    public int getAer()   { return regs.aer; }
    public int getDdr()   { return regs.ddr; }
    public int getIera()  { return regs.iera; }
    public int getIerb()  { return regs.ierb; }
    public int getIpra()  { return regs.ipra; }
    public int getIprb()  { return regs.iprb; }
    public int getIsra()  { return regs.isra; }
    public int getIsrb()  { return regs.isrb; }
    public int getImra()  { return regs.imra; }
    public int getImrb()  { return regs.imrb; }
    public int getVr()    { return regs.vr; }
    public int getTacr()  { return regs.tacr; }
    public int getTbcr()  { return regs.tbcr; }
    public int getTcdcr() { return regs.tcdcr; }
    public int getTadr()  { return regs.tadr; }
    public int getTbdr()  { return regs.tbdr; }
    public int getTcdr()  { return regs.tcdr; }
    public int getTddr()  { return regs.tddr; }
    public int getScr()   { return regs.scr; }
    public int getUcr()   { return regs.ucr; }
    public int getRsr()   { return regs.rsr; }
    public int getTsr()   { return regs.tsr; }
    public int getUdr()   { return regs.udr; }

    // ================================================================
    //  Private helpers
    // ================================================================

    private void reset() {
        xtldiv = false;
        timerA.reset();
        timerB.reset();
        timerC.reset();
        timerD.reset();
        regs.reset();

        csn1 = true;
        siackn = 0x7;
        ii1 = 0;
        dtackn_irq = true;
        dtackn_reg = true;
        sod = 0xFF;
    }

    private void computeOutputs() {
        ieon = true;
        io = (regs.gpip | (~regs.ddr & 0xFF)) & 0xFF;
        tao = timerA.getOutput();
        tbo = timerB.getOutput();
        tco = timerC.getOutput();
        tdo = timerD.getOutput();

        int intv = ((regs.ipra & regs.imra) << 8 | (regs.iprb & regs.imrb)) & 0xFFFF;
        int ipl = priority(intv);
        int isr_ipl = priority(((regs.isra << 8) | regs.isrb) & 0xFFFF);
        boolean sirqn = !((intv != 0 && !bit(regs.vr, 0))
                || ipl > isr_ipl || !dtackn_irq);
        irqn = sirqn;

        dtackn = ((dtackn_irq ? 1 : 0)
                & ((dtackn_reg || lastCsn) ? 1 : 0)) != 0 || lastDsn;
        od = (!lastCsn || !lastIackn) ? sod & 0xFF : 0xFF;
    }

    private static boolean bit(int value, int bitIndex) {
        return ((value >> bitIndex) & 1) != 0;
    }

    /**
     * Optimized priority encoder using bit manipulation instead of a loop.
     * Returns the index of the highest set bit in the lower 16 bits,
     * or 0 if no bits are set.
     */
    private static int priority(int v) {
        if (v == 0) return 0;
        return 31 - Integer.numberOfLeadingZeros(v & 0xFFFF);
    }
}
