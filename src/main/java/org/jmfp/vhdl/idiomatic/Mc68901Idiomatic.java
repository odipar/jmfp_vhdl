package org.jmfp.vhdl.idiomatic;

/**
 * Structured, idiomatic Java implementation of the MC68901 Multi-Function
 * Peripheral chip, behaviourally equivalent to the monolithic
 * {@link org.jmfp.vhdl.Mc68901}.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>{@link TimerUnit} – encapsulates prescaler / main-counter / timeout
 *       logic shared by all four timers (A, B, C, D).</li>
 *   <li>{@link RegisterFile} – maps register addresses to backing fields
 *       and timer units, handling read/write dispatch.</li>
 *   <li>{@code Mc68901Idiomatic} – top-level chip model that orchestrates
 *       clock domains, interrupt logic, and I/O.</li>
 * </ul>
 *
 * <h2>Low-level API</h2>
 * The {@link #risingEdge} method is a drop-in replacement for
 * {@link org.jmfp.vhdl.Mc68901#risingEdge} and produces bit-identical
 * outputs at every clock edge.
 *
 * <h2>High-level convenience API</h2>
 * For users who do not need cycle-accurate fidelity, the convenience
 * methods {@link #writeRegister}, {@link #readRegister}, and
 * {@link #clockTimers} provide a simpler programming model.
 */
public final class Mc68901Idiomatic {

    // ---- Chip outputs (same public contract as Mc68901) ----
    public int     od;
    public boolean dtackn;
    public boolean irqn;
    public boolean ieon;
    public int     io;
    public boolean tao;
    public boolean tbo;
    public boolean tco;
    public boolean tdo;

    // ---- Factored-out components ----
    private final TimerUnit    timerA = new TimerUnit();
    private final TimerUnit    timerB = new TimerUnit();
    private final TimerUnit    timerC = new TimerUnit();
    private final TimerUnit    timerD = new TimerUnit();
    private final RegisterFile regs   = new RegisterFile(timerA, timerB, timerC, timerD);

    // ---- Internal state not delegated to sub-objects ----
    private boolean csn1;
    private int     siackn;
    private boolean xtldiv;
    private boolean dtackn_irq;
    private boolean dtackn_reg;
    private int     sod;
    private int     ii1;

    // Stored input values for combinational output computation
    private boolean lastCsn   = true;
    private boolean lastIackn = true;
    private boolean lastDsn   = true;

    public Mc68901Idiomatic() {
        reset();
    }

    // ================================================================
    //  Low-level cycle-accurate API  (drop-in for Mc68901)
    // ================================================================

    /**
     * Advance the model by one rising clock edge.
     * Parameters match the VHDL entity input ports.
     *
     * @param clkren  rising-edge enable for register/bus logic
     * @param xtlcken crystal clock enable for timer logic
     * @param resetn  active-low reset
     * @param id      8-bit input data bus
     * @param rs      5-bit register select (rs[5:1])
     * @param csn     active-low chip select
     * @param rwn     read/write (1=read, 0=write)
     * @param dsn     active-low data strobe
     * @param iackn   active-low interrupt acknowledge
     * @param ii      8-bit general purpose I/O input
     * @param tai     timer A input
     * @param tbi     timer B input
     */
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

        RegisterFile.Snapshot snap = regs.snapshot();

        boolean p_tato = timerA.getOutput();
        boolean p_tbto = timerB.getOutput();
        boolean p_tcto = timerC.getOutput();
        boolean p_tdto = timerD.getOutput();

        // Pre-edge combinational signals
        int p_ii0 = (ii ^ regs.aer) & 0xFF;
        int p_trd = (p_ii1 & ~p_ii0 & ~regs.ddr) & 0xFF;
        int p_intv = ((snap.ipra() & snap.imra()) << 8
                    | (snap.iprb() & snap.imrb())) & 0xFFFF;
        int p_ipl = priority(p_intv);
        int p_isr_ipl = priority(((snap.isra() << 8) | snap.isrb()) & 0xFFFF);
        boolean p_sirqn = !((p_intv != 0 && !bit(snap.vr(), 0))
                || p_ipl > p_isr_ipl || !p_dtackn_irq);

        // === xtlcken domain ===
        if (xtlcken) {
            xtldiv = !p_xtldiv;
        }
        if (xtlcken && !p_xtldiv) {
            // Timer A
            TimerUnit.TickResult ra = timerA.tickFull(
                    snap.tacr(), snap.tadr(), tai,
                    bit(regs.aer, 4), bit(snap.ierb(), 6), bit(snap.iera(), 5));
            if (ra.pulseCountInterrupt()) regs.iprb |= (1 << 6);
            if (ra.timeoutInterrupt())    regs.ipra |= (1 << 5);

            // Timer B
            TimerUnit.TickResult rb = timerB.tickFull(
                    snap.tbcr(), snap.tbdr(), tbi,
                    bit(regs.aer, 3), bit(snap.ierb(), 3), bit(snap.iera(), 0));
            if (rb.pulseCountInterrupt()) regs.iprb |= (1 << 3);
            if (rb.timeoutInterrupt())    regs.ipra |= (1 << 0);

            // Timer C
            if (timerC.tick((snap.tcdcr() >> 3) & 0x7, snap.tcdr())
                    && bit(snap.ierb(), 5)) {
                regs.iprb |= (1 << 5);
            }

            // Timer D
            if (timerD.tick(snap.tcdcr() & 0x7, snap.tddr())
                    && bit(snap.ierb(), 4)) {
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
                    sod = regs.read(addr, snap, ii);
                } else {
                    regs.write(addr, id, snap);
                }
                dtackn_reg = false;
            }

            // GPIP edge detection
            ii1 = p_ii0;
            if (bit(p_trd, 7) && bit(snap.iera(), 7)) regs.ipra |= (1 << 7);
            if (bit(p_trd, 6) && bit(snap.iera(), 6)) regs.ipra |= (1 << 6);
            if (bit(p_trd, 5) && bit(snap.ierb(), 7)) regs.iprb |= (1 << 7);
            if (bit(p_trd, 4) && bit(snap.ierb(), 6)) regs.iprb |= (1 << 6);
            if (bit(p_trd, 3) && bit(snap.ierb(), 3)) regs.iprb |= (1 << 3);
            if (bit(p_trd, 2) && bit(snap.ierb(), 2)) regs.iprb |= (1 << 2);
            if (bit(p_trd, 1) && bit(snap.ierb(), 1)) regs.iprb |= (1 << 1);
            if (bit(p_trd, 0) && bit(snap.ierb(), 0)) regs.iprb |= (1 << 0);

            // Interrupt acknowledge cycle
            if (!p_sirqn && !iackn && !bit(p_siackn, 0) && !dsn) {
                dtackn_irq = false;
                sod = (((snap.vr() >> 1) & 0x0F) << 4) | (p_ipl & 0xF);
                if (bit(p_ipl, 3)) {
                    regs.ipra = snap.ipra() & ~(1 << (p_ipl & 0x7));
                } else {
                    regs.iprb = snap.iprb() & ~(1 << (p_ipl & 0x7));
                }
                if (bit(snap.vr(), 0)) {
                    if (bit(p_ipl, 3)) {
                        regs.isra = snap.isra() | (1 << (p_ipl & 0x7));
                    } else {
                        regs.isrb = snap.isrb() | (1 << (p_ipl & 0x7));
                    }
                }
            }
        }

        computeOutputs();
    }

    // ================================================================
    //  High-level convenience API
    // ================================================================

    /**
     * Writes an 8-bit value to a register by address (0x01–0x2F, odd).
     *
     * <p>This is a convenience wrapper that performs a single bus write
     * cycle (chip-select + write strobe + clkren). It does <em>not</em>
     * advance the timers.
     *
     * @param addr register address (odd byte, 0x01–0x2F)
     * @param data 8-bit value to write
     */
    public void writeRegister(int addr, int data) {
        RegisterFile.Snapshot snap = regs.snapshot();
        regs.write(addr & 0xFF, data & 0xFF, snap);
    }

    /**
     * Reads an 8-bit value from a register by address (0x01–0x2F, odd).
     *
     * @param addr register address (odd byte, 0x01–0x2F)
     * @param ii   current I/O input pin levels (only needed for GPIP read at 0x01)
     * @return 8-bit register value
     */
    public int readRegister(int addr, int ii) {
        RegisterFile.Snapshot snap = regs.snapshot();
        return regs.read(addr & 0xFF, snap, ii);
    }

    /**
     * Convenience overload that passes 0 for the I/O input pins.
     *
     * @param addr register address (odd byte, 0x01–0x2F)
     * @return 8-bit register value
     */
    public int readRegister(int addr) {
        return readRegister(addr, 0);
    }

    /**
     * Advances all four timers by one crystal-clock half-period.
     *
     * <p>This is a simplified interface for users who want to drive the
     * timers without constructing full {@code risingEdge} parameters.
     *
     * @param tai timer A external input
     * @param tbi timer B external input
     */
    public void clockTimers(boolean tai, boolean tbi) {
        RegisterFile.Snapshot snap = regs.snapshot();

        TimerUnit.TickResult ra = timerA.tickFull(
                snap.tacr(), snap.tadr(), tai,
                bit(regs.aer, 4), bit(snap.ierb(), 6), bit(snap.iera(), 5));
        if (ra.pulseCountInterrupt()) regs.iprb |= (1 << 6);
        if (ra.timeoutInterrupt())    regs.ipra |= (1 << 5);

        TimerUnit.TickResult rb = timerB.tickFull(
                snap.tbcr(), snap.tbdr(), tbi,
                bit(regs.aer, 3), bit(snap.ierb(), 3), bit(snap.iera(), 0));
        if (rb.pulseCountInterrupt()) regs.iprb |= (1 << 3);
        if (rb.timeoutInterrupt())    regs.ipra |= (1 << 0);

        if (timerC.tick((snap.tcdcr() >> 3) & 0x7, snap.tcdr())
                && bit(snap.ierb(), 5)) {
            regs.iprb |= (1 << 5);
        }
        if (timerD.tick(snap.tcdcr() & 0x7, snap.tddr())
                && bit(snap.ierb(), 4)) {
            regs.iprb |= (1 << 4);
        }

        tao = timerA.getOutput();
        tbo = timerB.getOutput();
        tco = timerC.getOutput();
        tdo = timerD.getOutput();
    }

    /**
     * Returns {@code true} if any enabled interrupt is pending.
     * This is a convenience check equivalent to testing the {@code irqn}
     * output after a full {@link #risingEdge} cycle.
     */
    public boolean isInterruptPending() {
        int intv = ((regs.ipra & regs.imra) << 8 | (regs.iprb & regs.imrb)) & 0xFFFF;
        return intv != 0;
    }

    // ================================================================
    //  Component accessors
    // ================================================================

    /** Returns the register file backing this chip instance. */
    public RegisterFile registers() { return regs; }

    /** Returns timer A. */
    public TimerUnit timerA() { return timerA; }
    /** Returns timer B. */
    public TimerUnit timerB() { return timerB; }
    /** Returns timer C. */
    public TimerUnit timerC() { return timerC; }
    /** Returns timer D. */
    public TimerUnit timerD() { return timerD; }

    // ================================================================
    //  Getters (same contract as Mc68901 for compatibility)
    // ================================================================

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

    private static int priority(int v) {
        for (int i = 15; i >= 0; i--) {
            if (((v >> i) & 1) != 0) return i;
        }
        return 0;
    }
}
