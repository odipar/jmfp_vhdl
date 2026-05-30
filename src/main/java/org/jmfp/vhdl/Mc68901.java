package org.jmfp.vhdl;

/**
 * 1:1 Java implementation of the MC68901 Multi-Function Peripheral chip
 * as specified in {@code vhdl/mc68901.vhd}.
 *
 * <p>The {@link #risingEdge} method accepts all chip inputs as parameters,
 * matching the VHDL entity port list exactly. Output signals are readable
 * via getter methods after the call. Internal state is private and can be
 * queried via getter methods.
 */
public class Mc68901 {

    // ---- Chip outputs (read by caller after risingEdge via getters) ----
    private int     od;       // 8-bit
    private boolean dtackn;
    private boolean irqn;
    private boolean ieon;
    private int     io;       // 8-bit
    private boolean tao;
    private boolean tbo;
    private boolean tco;
    private boolean tdo;

    // ---- Internal state (private) ----
    private int gpip;
    private int aer;
    private int ddr;
    private int iera;
    private int ierb;
    private int ipra;
    private int iprb;
    private int isra;
    private int isrb;
    private int imra;
    private int imrb;
    private int vr;          // 5-bit (represents vr[7:3])
    private int tacr;        // 4-bit
    private int tbcr;        // 4-bit
    private int tcdcr;       // 6-bit (high 3 bits = timer C, low 3 bits = timer D)
    private int tadr;
    private int tbdr;
    private int tcdr;
    private int tddr;
    private int scr;
    private int ucr;
    private int rsr;
    private int tsr;
    private int udr;

    private boolean csn1;
    private int siackn;      // 3-bit shift register
    private boolean xtldiv;

    private boolean tato;
    private int tapc;
    private int tamc;        // 8-bit unsigned
    private int[] tamc_r = new int[4];
    private int tai1;        // 3-bit shift register

    private boolean tbto;
    private int tbpc;
    private int tbmc;
    private int[] tbmc_r = new int[4];
    private int tbi1;

    private boolean tcto;
    private int tcpc;
    private int tcmc;
    private int[] tcmc_r = new int[4];

    private boolean tdto;
    private int tdpc;
    private int tdmc;
    private int[] tdmc_r = new int[4];

    private boolean dtackn_irq;
    private boolean dtackn_reg;
    private int sod;
    private int ii1;         // 8-bit (previous ii0)

    // Stored input values needed for combinational output computation
    private boolean lastCsn = true;
    private boolean lastIackn = true;
    private boolean lastDsn = true;

    public Mc68901() {
        reset();
    }

    private void reset() {
        xtldiv = false;
        gpip = 0x00;
        aer = 0x00;
        ddr = 0x00;
        iera = 0x00;
        ierb = 0x00;
        ipra = 0x00;
        iprb = 0x00;
        isra = 0x00;
        isrb = 0x00;
        imra = 0x00;
        imrb = 0x00;
        vr = 0x01;       // VHDL: "00001"
        tacr = 0x0;
        tbcr = 0x0;
        tcdcr = 0x00;
        scr = 0x00;
        ucr = 0x00;
        rsr = 0x00;
        sod = 0xFF;
        dtackn_irq = true;
        dtackn_reg = true;

        tato = false;
        tapc = 1;
        tamc = 0x01;
        tamc_r = new int[]{0, 0, 0, 0};
        tai1 = 0;

        tbto = false;
        tbpc = 1;
        tbmc = 0x01;
        tbmc_r = new int[]{0, 0, 0, 0};
        tbi1 = 0;

        tcto = false;
        tcpc = 1;
        tcmc = 0x01;
        tcmc_r = new int[]{0, 0, 0, 0};

        tdto = false;
        tdpc = 1;
        tdmc = 0x01;
        tdmc_r = new int[]{0, 0, 0, 0};

        csn1 = true;
        siackn = 0x7; // "111"
        ii1 = 0;

        tadr = 0;
        tbdr = 0;
        tcdr = 0;
        tddr = 0;
        tsr = 0;
        udr = 0;
    }

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
        // Store inputs needed for output computation
        lastCsn = csn;
        lastIackn = iackn;
        lastDsn = dsn;

        if (!resetn) {
            reset();
            computeOutputs();
            return;
        }

        // === Save pre-edge state for VHDL signal semantics ===
        boolean p_xtldiv = xtldiv;
        boolean p_csn1 = csn1;
        int p_siackn = siackn;
        int p_ipra = ipra;
        int p_iprb = iprb;
        int p_isra = isra;
        int p_isrb = isrb;
        int p_iera = iera;
        int p_ierb = ierb;
        int p_imra = imra;
        int p_imrb = imrb;
        int p_vr = vr;
        int p_tacr = tacr;
        int p_tbcr = tbcr;
        int p_tcdcr = tcdcr;
        int p_tadr = tadr;
        int p_tbdr = tbdr;
        int p_tcdr = tcdr;
        int p_tddr = tddr;
        int p_tai1 = tai1;
        int p_tbi1 = tbi1;
        int p_tamc = tamc;
        int p_tbmc = tbmc;
        int p_tcmc = tcmc;
        int p_tdmc = tdmc;
        boolean p_tato = tato;
        boolean p_tbto = tbto;
        boolean p_tcto = tcto;
        boolean p_tdto = tdto;
        int p_tapc = tapc;
        int p_tbpc = tbpc;
        int p_tcpc = tcpc;
        int p_tdpc = tdpc;
        int p_ii1 = ii1;
        boolean p_dtackn_irq = dtackn_irq;

        // Pre-edge combinational signals (ii0 uses the passed ii value directly)
        int p_ii0 = (ii ^ aer) & 0xFF;
        int p_trd = (p_ii1 & ~p_ii0 & ~ddr) & 0xFF;
        int p_intv = ((p_ipra & p_imra) << 8 | (p_iprb & p_imrb)) & 0xFFFF;
        int p_ipl = priority(p_intv);
        int p_isr_ipl = priority(((p_isra << 8) | p_isrb) & 0xFFFF);
        boolean p_sirqn = !((p_intv != 0 && !bit(p_vr, 0)) || p_ipl > p_isr_ipl || !p_dtackn_irq);

        // === xtlcken domain ===
        if (xtlcken) {
            xtldiv = !p_xtldiv;
        }
        if (xtlcken && !p_xtldiv) {
            // Timer A operation
            tai1 = ((p_tai1 << 1) | (tai ? 1 : 0)) & 0x7;
            boolean tai1High = bit(p_tai1, 2);
            boolean tai1Mid = bit(p_tai1, 1);

            if (bit(p_ierb, 6) && bit(p_tacr, 3) && (tai1High != tai1Mid) && (tai == bit(aer, 4))) {
                iprb |= (1 << 6);
            }
            if (p_tacr != 0x0) {
                if (!bit(p_tacr, 3) || (p_tacr == 0x8 && (tai1High != tai1Mid) && (tai == bit(aer, 4)))) {
                    if (p_tapc == 1 || p_tacr == 0x8) {
                        if (p_tacr != 0x8) {
                            tapc = prescale(p_tacr & 0x7);
                        }
                        if (p_tamc == 0x01) {
                            tamc = p_tadr & 0xFF;
                            tato = !p_tato;
                            if (bit(p_iera, 5)) {
                                ipra |= (1 << 5);
                            }
                        } else {
                            tamc = (p_tamc - 1) & 0xFF;
                        }
                    } else {
                        tapc = p_tapc - 1;
                    }
                }
            }

            // Timer B operation
            tbi1 = ((p_tbi1 << 1) | (tbi ? 1 : 0)) & 0x7;
            boolean tbi1High = bit(p_tbi1, 2);
            boolean tbi1Mid = bit(p_tbi1, 1);

            if (bit(p_ierb, 3) && bit(p_tbcr, 3) && (tbi1High != tbi1Mid) && (tbi == bit(aer, 3))) {
                iprb |= (1 << 3);
            }
            if (p_tbcr != 0x0) {
                if (!bit(p_tbcr, 3) || (p_tbcr == 0x8 && (tbi1High != tbi1Mid) && (tbi == bit(aer, 3)))) {
                    if (p_tbpc == 1 || p_tbcr == 0x8) {
                        if (p_tbcr != 0x8) {
                            tbpc = prescale(p_tbcr & 0x7);
                        }
                        if (p_tbmc == 0x01) {
                            tbmc = p_tbdr & 0xFF;
                            tbto = !p_tbto;
                            if (bit(p_iera, 0)) {
                                ipra |= (1 << 0);
                            }
                        } else {
                            tbmc = (p_tbmc - 1) & 0xFF;
                        }
                    } else {
                        tbpc = p_tbpc - 1;
                    }
                }
            }

            // Timer C operation
            int tcdcrHigh = (p_tcdcr >> 3) & 0x7;
            if (tcdcrHigh != 0) {
                if (p_tcpc == 1) {
                    tcpc = prescale(tcdcrHigh);
                    if (p_tcmc == 0x01) {
                        tcmc = p_tcdr & 0xFF;
                        tcto = !p_tcto;
                        if (bit(p_ierb, 5)) {
                            iprb |= (1 << 5);
                        }
                    } else {
                        tcmc = (p_tcmc - 1) & 0xFF;
                    }
                } else {
                    tcpc = p_tcpc - 1;
                }
            }

            // Timer D operation
            int tcdcrLow = p_tcdcr & 0x7;
            if (tcdcrLow != 0) {
                if (p_tdpc == 1) {
                    tdpc = prescale(tcdcrLow);
                    if (p_tdmc == 0x01) {
                        tdmc = p_tddr & 0xFF;
                        tdto = !p_tdto;
                        if (bit(p_ierb, 4)) {
                            iprb |= (1 << 4);
                        }
                    } else {
                        tdmc = (p_tdmc - 1) & 0xFF;
                    }
                } else {
                    tdpc = p_tdpc - 1;
                }
            }
        }

        // === clkren domain ===
        if (clkren) {
            // Pipeline shifts
            tamc_r[0] = tamc_r[1];
            tamc_r[1] = tamc_r[2];
            tamc_r[2] = tamc_r[3];
            tamc_r[3] = tamc & 0xFF;

            tbmc_r[0] = tbmc_r[1];
            tbmc_r[1] = tbmc_r[2];
            tbmc_r[2] = tbmc_r[3];
            tbmc_r[3] = tbmc & 0xFF;

            tcmc_r[0] = tcmc_r[1];
            tcmc_r[1] = tcmc_r[2];
            tcmc_r[2] = tcmc_r[3];
            tcmc_r[3] = tcmc & 0xFF;

            tdmc_r[0] = tdmc_r[1];
            tdmc_r[1] = tdmc_r[2];
            tdmc_r[2] = tdmc_r[3];
            tdmc_r[3] = tdmc & 0xFF;

            csn1 = csn;
            siackn = ((iackn ? 1 : 0) << 2) | ((p_siackn >> 1) & 0x3);
            sod = 0xFF;
            dtackn_irq = true;
            dtackn_reg = true;

            if (!csn && !p_csn1) {
                // Register access
                int addr = (rs << 1) | 0x01;
                if (rwn) {
                    // Register read
                    switch (addr) {
                        case 0x01: sod = ((gpip & ddr) | (ii & (~ddr & 0xFF))) & 0xFF; break;
                        case 0x03: sod = aer; break;
                        case 0x05: sod = ddr; break;
                        case 0x07: sod = p_iera; break;
                        case 0x09: sod = p_ierb; break;
                        case 0x0b: sod = p_ipra; break;
                        case 0x0d: sod = p_iprb; break;
                        case 0x0f: sod = p_isra; break;
                        case 0x11: sod = p_isrb; break;
                        case 0x13: sod = p_imra; break;
                        case 0x15: sod = p_imrb; break;
                        case 0x17: sod = (p_vr << 3) & 0xFF; break;
                        case 0x19: sod = p_tacr & 0x0F; break;
                        case 0x1b: sod = p_tbcr & 0x0F; break;
                        case 0x1d: sod = (((p_tcdcr >> 3) & 0x7) << 4) | (p_tcdcr & 0x7); break;
                        case 0x1f: sod = tamc_r[0] & 0xFF; break;
                        case 0x21: sod = tbmc_r[0] & 0xFF; break;
                        case 0x23: sod = tcmc_r[0] & 0xFF; break;
                        case 0x25: sod = tdmc_r[0] & 0xFF; break;
                        case 0x27: sod = scr; break;
                        case 0x29: sod = ucr; break;
                        case 0x2b: sod = rsr; break;
                        case 0x2d: sod = tsr; break;
                        case 0x2f: sod = udr; break;
                        default: break;
                    }
                } else {
                    // Register write
                    int data = id & 0xFF;
                    switch (addr) {
                        case 0x01: gpip = data; break;
                        case 0x03: aer = data; break;
                        case 0x05: ddr = data; break;
                        case 0x07:
                            iera = data;
                            ipra = p_ipra & data;
                            break;
                        case 0x09:
                            ierb = data;
                            iprb = p_iprb & data;
                            break;
                        case 0x0b: ipra = p_ipra & data; break;
                        case 0x0d: iprb = p_iprb & data; break;
                        case 0x0f: isra = p_isra & data; break;
                        case 0x11: isrb = p_isrb & data; break;
                        case 0x13: imra = data; break;
                        case 0x15: imrb = data; break;
                        case 0x17:
                            vr = (data >> 3) & 0x1F;
                            if ((data & 0x08) == 0) {
                                isra = 0x00;
                                isrb = 0x00;
                            }
                            break;
                        case 0x19: {
                            tacr = data & 0x0F;
                            if ((data & 0x10) != 0) {
                                tato = false;
                            }
                            if ((data & 0x0F) == 0x0) {
                                tapc = 1;
                            } else if (p_tacr == 0x0 && (data & 0x0F) != 0x8) {
                                tapc = prescale(data & 0x7);
                            }
                            break;
                        }
                        case 0x1b: {
                            tbcr = data & 0x0F;
                            if ((data & 0x10) != 0) {
                                tbto = false;
                            }
                            if ((data & 0x0F) == 0x0) {
                                tbpc = 1;
                            } else if (p_tbcr == 0x0 && (data & 0x0F) != 0x8) {
                                tbpc = prescale(data & 0x7);
                            }
                            break;
                        }
                        case 0x1d: {
                            int newTcdcr = ((data >> 4) & 0x7) << 3 | (data & 0x7);
                            if (((p_tcdcr >> 3) & 0x7) == 0 && ((data >> 4) & 0x7) != 0) {
                                tcpc = prescale((data >> 4) & 0x7);
                            }
                            if ((p_tcdcr & 0x7) == 0 && (data & 0x7) != 0) {
                                tdpc = prescale(data & 0x7);
                            }
                            tcdcr = newTcdcr;
                            break;
                        }
                        case 0x1f:
                            tadr = data;
                            if (p_tacr == 0x0) {
                                tamc = data;
                            }
                            break;
                        case 0x21:
                            tbdr = data;
                            if (p_tbcr == 0x0) {
                                tbmc = data;
                            }
                            break;
                        case 0x23:
                            tcdr = data;
                            if (((p_tcdcr >> 3) & 0x7) == 0) {
                                tcmc = data;
                            }
                            break;
                        case 0x25:
                            tddr = data;
                            if ((p_tcdcr & 0x7) == 0) {
                                tdmc = data;
                            }
                            break;
                        case 0x27: scr = data; break;
                        case 0x29: ucr = data; break;
                        case 0x2b: rsr = data; break;
                        case 0x2d: tsr = data; break;
                        case 0x2f: udr = data; break;
                        default: break;
                    }
                }
                dtackn_reg = false;
            }

            // GPIP edge detection
            ii1 = p_ii0;
            if (bit(p_trd, 7) && bit(p_iera, 7)) ipra |= (1 << 7);
            if (bit(p_trd, 6) && bit(p_iera, 6)) ipra |= (1 << 6);
            if (bit(p_trd, 5) && bit(p_ierb, 7)) iprb |= (1 << 7);
            if (bit(p_trd, 4) && bit(p_ierb, 6)) iprb |= (1 << 6);
            if (bit(p_trd, 3) && bit(p_ierb, 3)) iprb |= (1 << 3);
            if (bit(p_trd, 2) && bit(p_ierb, 2)) iprb |= (1 << 2);
            if (bit(p_trd, 1) && bit(p_ierb, 1)) iprb |= (1 << 1);
            if (bit(p_trd, 0) && bit(p_ierb, 0)) iprb |= (1 << 0);

            // Interrupt acknowledge cycle
            if (!p_sirqn && !iackn && !bit(p_siackn, 0) && !dsn) {
                dtackn_irq = false;
                sod = (((p_vr >> 1) & 0x0F) << 4) | (p_ipl & 0xF);
                if (bit(p_ipl, 3)) {
                    ipra = p_ipra & ~(1 << (p_ipl & 0x7));
                } else {
                    iprb = p_iprb & ~(1 << (p_ipl & 0x7));
                }
                if (bit(p_vr, 0)) {
                    if (bit(p_ipl, 3)) {
                        isra = p_isra | (1 << (p_ipl & 0x7));
                    } else {
                        isrb = p_isrb | (1 << (p_ipl & 0x7));
                    }
                }
            }
        }

        computeOutputs();
    }

    private void computeOutputs() {
        ieon = true;
        io = (gpip | (~ddr & 0xFF)) & 0xFF;
        tao = tato;
        tbo = tbto;
        tco = tcto;
        tdo = tdto;

        // Compute sirqn from current (post-edge) state
        int intv = ((ipra & imra) << 8 | (iprb & imrb)) & 0xFFFF;
        int ipl = priority(intv);
        int isr_ipl = priority(((isra << 8) | isrb) & 0xFFFF);
        boolean sirqn = !((intv != 0 && !bit(vr, 0)) || ipl > isr_ipl || !dtackn_irq);
        irqn = sirqn;

        dtackn = ((dtackn_irq ? 1 : 0) & ((dtackn_reg || lastCsn) ? 1 : 0)) != 0 || lastDsn;
        od = (!lastCsn || !lastIackn) ? sod & 0xFF : 0xFF;
    }

    // --- Utility methods ---

    private static boolean bit(int value, int bitIndex) {
        return ((value >> bitIndex) & 1) != 0;
    }

    private static int priority(int v) {
        for (int i = 15; i >= 0; i--) {
            if (((v >> i) & 1) != 0) {
                return i;
            }
        }
        return 0;
    }

    private static int prescale(int v) {
        switch (v & 0x7) {
            case 0: return 0;
            case 1: return 2;
            case 2: return 5;
            case 3: return 8;
            case 4: return 25;
            case 5: return 32;
            case 6: return 50;
            case 7: return 100;
            default: return 0;
        }
    }

    // --- Getters for chip outputs ---
    public int     getOd()      { return od; }
    public boolean isDtackn()   { return dtackn; }
    public boolean isIrqn()     { return irqn; }
    public boolean isIeon()     { return ieon; }
    public int     getIo()      { return io; }
    public boolean isTao()      { return tao; }
    public boolean isTbo()      { return tbo; }
    public boolean isTco()      { return tco; }
    public boolean isTdo()      { return tdo; }

    // --- Getters for internal state ---
    public int getGpip() { return gpip; }
    public int getAer() { return aer; }
    public int getDdr() { return ddr; }
    public int getIera() { return iera; }
    public int getIerb() { return ierb; }
    public int getIpra() { return ipra; }
    public int getIprb() { return iprb; }
    public int getIsra() { return isra; }
    public int getIsrb() { return isrb; }
    public int getImra() { return imra; }
    public int getImrb() { return imrb; }
    public int getVr() { return vr; }
    public int getTacr() { return tacr; }
    public int getTbcr() { return tbcr; }
    public int getTcdcr() { return tcdcr; }
    public int getTadr() { return tadr; }
    public int getTbdr() { return tbdr; }
    public int getTcdr() { return tcdr; }
    public int getTddr() { return tddr; }
    public int getScr() { return scr; }
    public int getUcr() { return ucr; }
    public int getRsr() { return rsr; }
    public int getTsr() { return tsr; }
    public int getUdr() { return udr; }
}
