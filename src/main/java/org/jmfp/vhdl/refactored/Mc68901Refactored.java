package org.jmfp.vhdl.refactored;

import org.jmfp.vhdl.optimized.OptimizedRegisters;
import org.jmfp.vhdl.optimized.OptimizedTimerUnit;

/**
 * Refactored implementation of the MC68901 Multi-Function Peripheral chip,
 * behaviourally equivalent to {@link org.jmfp.vhdl.optimized.Mc68901Optimized}.
 *
 * <p>Reduces cyclomatic complexity by grouping conditional blocks, factoring
 * out timer advancement and interrupt-acknowledge logic into dedicated methods,
 * and replacing repetitive GPIP edge-detection statements with a loop.
 *
 * @see org.jmfp.vhdl.optimized.Mc68901Optimized
 */
public final class Mc68901Refactored {

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

    // ---- Components ----
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

    // GPIP interrupt routing: {ii bit, isA (1=ipra/iera, 0=iprb/ierb), ier bit, ipr bit}
    private static final int[][] GPIP_MAP = {
        {7, 1, 7, 7},  // ii[7] -> ipra[7], enabled by iera[7]
        {6, 1, 6, 6},  // ii[6] -> ipra[6], enabled by iera[6]
        {5, 0, 7, 7},  // ii[5] -> iprb[7], enabled by ierb[7]
        {4, 0, 6, 6},  // ii[4] -> iprb[6], enabled by ierb[6]
        {3, 0, 3, 3},  // ii[3] -> iprb[3], enabled by ierb[3]
        {2, 0, 2, 2},  // ii[2] -> iprb[2], enabled by ierb[2]
        {1, 0, 1, 1},  // ii[1] -> iprb[1], enabled by ierb[1]
        {0, 0, 0, 0},  // ii[0] -> iprb[0], enabled by ierb[0]
    };

    public Mc68901Refactored() {
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

        // Save pre-edge state
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
            if (!p_xtldiv) {
                advanceTimers(tai, tbi);
            }
        }

        // === clkren domain ===
        if (clkren) {
            shiftAllPipelines();
            csn1 = csn;
            siackn = ((iackn ? 1 : 0) << 2) | ((p_siackn >> 1) & 0x3);
            sod = 0xFF;
            dtackn_irq = true;
            dtackn_reg = true;

            handleRegisterAccess(csn, p_csn1, rs, rwn, id, ii);
            handleGpipEdges(p_trd);
            handleInterruptAcknowledge(p_sirqn, iackn, p_siackn, dsn, p_ipl);

            ii1 = p_ii0;
        }

        computeOutputs();
    }

    // ================================================================
    //  High-level convenience API
    // ================================================================

    /** Writes a value to a register by address. */
    public void writeRegister(int addr, int data) {
        regs.takeSnapshot();
        regs.write(addr & 0xFF, data & 0xFF);
    }

    /** Reads a register by address; {@code ii} supplies the GPIP input pins. */
    public int readRegister(int addr, int ii) {
        regs.takeSnapshot();
        return regs.read(addr & 0xFF, ii);
    }

    /** Reads a register by address with GPIP pins defaulting to zero. */
    public int readRegister(int addr) {
        return readRegister(addr, 0);
    }

    /** Advances all four timers by one tick with the given external inputs. */
    public void clockTimers(boolean tai, boolean tbi) {
        regs.takeSnapshot();
        advanceTimers(tai, tbi);
        tao = timerA.getOutput();
        tbo = timerB.getOutput();
        tco = timerC.getOutput();
        tdo = timerD.getOutput();
    }

    /** Returns {@code true} if any enabled interrupt is pending. */
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
    //  Private helpers – factored out logic
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

    private void advanceTimers(boolean tai, boolean tbi) {
        // Timer A (full mode)
        timerA.tickFull(regs.s_tacr, regs.s_tadr, tai,
                bit(regs.aer, 4), bit(regs.s_ierb, 6), bit(regs.s_iera, 5));
        if (timerA.getLastPulseCountInterrupt()) regs.iprb |= (1 << 6);
        if (timerA.getLastTimeoutInterrupt())    regs.ipra |= (1 << 5);

        // Timer B (full mode)
        timerB.tickFull(regs.s_tbcr, regs.s_tbdr, tbi,
                bit(regs.aer, 3), bit(regs.s_ierb, 3), bit(regs.s_iera, 0));
        if (timerB.getLastPulseCountInterrupt()) regs.iprb |= (1 << 3);
        if (timerB.getLastTimeoutInterrupt())    regs.ipra |= (1 << 0);

        // Timer C (delay mode)
        if (timerC.tick((regs.s_tcdcr >> 3) & 0x7, regs.s_tcdr) && bit(regs.s_ierb, 5)) {
            regs.iprb |= (1 << 5);
        }

        // Timer D (delay mode)
        if (timerD.tick(regs.s_tcdcr & 0x7, regs.s_tddr) && bit(regs.s_ierb, 4)) {
            regs.iprb |= (1 << 4);
        }
    }

    private void shiftAllPipelines() {
        timerA.shiftPipeline();
        timerB.shiftPipeline();
        timerC.shiftPipeline();
        timerD.shiftPipeline();
    }

    private void handleRegisterAccess(boolean csn, boolean p_csn1, int rs,
                                      boolean rwn, int id, int ii) {
        if (!csn && !p_csn1) {
            int addr = (rs << 1) | 0x01;
            if (rwn) {
                sod = regs.read(addr, ii);
            } else {
                regs.write(addr, id);
            }
            dtackn_reg = false;
        }
    }

    private void handleGpipEdges(int p_trd) {
        for (int[] mapping : GPIP_MAP) {
            int iiBit = mapping[0];
            boolean isA = mapping[1] != 0;
            int ierBit = mapping[2];
            int iprBit = mapping[3];
            if (bit(p_trd, iiBit) && bit(isA ? regs.s_iera : regs.s_ierb, ierBit)) {
                if (isA) {
                    regs.ipra |= (1 << iprBit);
                } else {
                    regs.iprb |= (1 << iprBit);
                }
            }
        }
    }

    private void handleInterruptAcknowledge(boolean p_sirqn, boolean iackn,
                                            int p_siackn, boolean dsn, int p_ipl) {
        if (p_sirqn || iackn || bit(p_siackn, 0) || dsn) return;

        dtackn_irq = false;
        sod = (((regs.s_vr >> 1) & 0x0F) << 4) | (p_ipl & 0xF);

        boolean highBit = bit(p_ipl, 3);
        if (highBit) {
            regs.ipra = regs.s_ipra & ~(1 << (p_ipl & 0x7));
        } else {
            regs.iprb = regs.s_iprb & ~(1 << (p_ipl & 0x7));
        }

        if (bit(regs.s_vr, 0)) {
            if (highBit) {
                regs.isra = regs.s_isra | (1 << (p_ipl & 0x7));
            } else {
                regs.isrb = regs.s_isrb | (1 << (p_ipl & 0x7));
            }
        }
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
        irqn = !((intv != 0 && !bit(regs.vr, 0)) || ipl > isr_ipl || !dtackn_irq);

        dtackn = ((dtackn_irq ? 1 : 0)
                & ((dtackn_reg || lastCsn) ? 1 : 0)) != 0 || lastDsn;
        od = (!lastCsn || !lastIackn) ? sod & 0xFF : 0xFF;
    }

    private static boolean bit(int value, int bitIndex) {
        return ((value >> bitIndex) & 1) != 0;
    }

    private static int priority(int v) {
        if (v == 0) return 0;
        return 31 - Integer.numberOfLeadingZeros(v & 0xFFFF);
    }
}
