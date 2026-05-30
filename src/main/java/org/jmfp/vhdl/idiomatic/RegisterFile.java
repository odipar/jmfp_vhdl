package org.jmfp.vhdl.idiomatic;

/**
 * Encapsulates the MC68901 register file (24 addressable registers) and
 * dispatches reads/writes to the appropriate backing fields or timer units.
 *
 * <p>This class holds no timing logic of its own; it simply provides a
 * structured {@link #read}/{@link #write} interface used by
 * {@link Mc68901Idiomatic} during the clkren domain.
 */
public final class RegisterFile {

    // --- Registers ---
    int gpip;
    int aer;
    int ddr;
    int iera;
    int ierb;
    int ipra;
    int iprb;
    int isra;
    int isrb;
    int imra;
    int imrb;
    int vr;      // 5-bit (represents vr[7:3])
    int tacr;    // 4-bit
    int tbcr;    // 4-bit
    int tcdcr;   // 6-bit (high 3 = timer C, low 3 = timer D)
    int tadr;
    int tbdr;
    int tcdr;
    int tddr;
    int scr;
    int ucr;
    int rsr;
    int tsr;
    int udr;

    // Associated timers
    private final TimerUnit timerA;
    private final TimerUnit timerB;
    private final TimerUnit timerC;
    private final TimerUnit timerD;

    public RegisterFile(TimerUnit a, TimerUnit b, TimerUnit c, TimerUnit d) {
        this.timerA = a;
        this.timerB = b;
        this.timerC = c;
        this.timerD = d;
    }

    /** Resets all registers to power-on defaults. */
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

    // ----------------------------------------------------------------
    //  Snapshot helper – captures pre-edge register values
    // ----------------------------------------------------------------

    /** Immutable snapshot of register values taken before a clock edge. */
    public record Snapshot(
            int iera, int ierb, int ipra, int iprb,
            int isra, int isrb, int imra, int imrb,
            int vr, int tacr, int tbcr, int tcdcr,
            int tadr, int tbdr, int tcdr, int tddr) {}

    /** Takes a snapshot of the current register values. */
    public Snapshot snapshot() {
        return new Snapshot(iera, ierb, ipra, iprb, isra, isrb,
                imra, imrb, vr, tacr, tbcr, tcdcr,
                tadr, tbdr, tcdr, tddr);
    }

    // ----------------------------------------------------------------
    //  Register read
    // ----------------------------------------------------------------

    /**
     * Reads a register.
     *
     * @param addr 8-bit register address (odd, 0x01–0x2F)
     * @param snap pre-edge snapshot for reading stable values
     * @param ii   current I/O input pins
     * @return 8-bit register value
     */
    public int read(int addr, Snapshot snap, int ii) {
        return switch (addr) {
            case 0x01 -> ((gpip & ddr) | (ii & (~ddr & 0xFF))) & 0xFF;
            case 0x03 -> aer;
            case 0x05 -> ddr;
            case 0x07 -> snap.iera;
            case 0x09 -> snap.ierb;
            case 0x0b -> snap.ipra;
            case 0x0d -> snap.iprb;
            case 0x0f -> snap.isra;
            case 0x11 -> snap.isrb;
            case 0x13 -> snap.imra;
            case 0x15 -> snap.imrb;
            case 0x17 -> (snap.vr << 3) & 0xFF;
            case 0x19 -> snap.tacr & 0x0F;
            case 0x1b -> snap.tbcr & 0x0F;
            case 0x1d -> (((snap.tcdcr >> 3) & 0x7) << 4) | (snap.tcdcr & 0x7);
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
    //  Register write
    // ----------------------------------------------------------------

    /**
     * Writes a register.
     *
     * @param addr 8-bit register address (odd, 0x01–0x2F)
     * @param data 8-bit data written by the CPU
     * @param snap pre-edge snapshot
     */
    public void write(int addr, int data, Snapshot snap) {
        data &= 0xFF;
        switch (addr) {
            case 0x01 -> gpip = data;
            case 0x03 -> aer = data;
            case 0x05 -> ddr = data;
            case 0x07 -> { iera = data; ipra = snap.ipra & data; }
            case 0x09 -> { ierb = data; iprb = snap.iprb & data; }
            case 0x0b -> ipra = snap.ipra & data;
            case 0x0d -> iprb = snap.iprb & data;
            case 0x0f -> isra = snap.isra & data;
            case 0x11 -> isrb = snap.isrb & data;
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
                timerA.writeControlAB(data, snap.tacr);
            }
            case 0x1b -> {
                tbcr = data & 0x0F;
                timerB.writeControlAB(data, snap.tbcr);
            }
            case 0x1d -> {
                int newTcdcr = ((data >> 4) & 0x7) << 3 | (data & 0x7);
                if (((snap.tcdcr >> 3) & 0x7) == 0 && ((data >> 4) & 0x7) != 0) {
                    timerC.initPrescale((data >> 4) & 0x7);
                }
                if ((snap.tcdcr & 0x7) == 0 && (data & 0x7) != 0) {
                    timerD.initPrescale(data & 0x7);
                }
                tcdcr = newTcdcr;
            }
            case 0x1f -> {
                tadr = data;
                timerA.writeData(data, snap.tacr == 0x0);
            }
            case 0x21 -> {
                tbdr = data;
                timerB.writeData(data, snap.tbcr == 0x0);
            }
            case 0x23 -> {
                tcdr = data;
                timerC.writeData(data, ((snap.tcdcr >> 3) & 0x7) == 0);
            }
            case 0x25 -> {
                tddr = data;
                timerD.writeData(data, (snap.tcdcr & 0x7) == 0);
            }
            case 0x27 -> scr = data;
            case 0x29 -> ucr = data;
            case 0x2b -> rsr = data;
            case 0x2d -> tsr = data;
            case 0x2f -> udr = data;
            default -> { /* unmapped */ }
        }
    }

    // --- Accessors ---
    public int getGpip()  { return gpip; }
    public int getAer()   { return aer; }
    public int getDdr()   { return ddr; }
    public int getIera()  { return iera; }
    public int getIerb()  { return ierb; }
    public int getIpra()  { return ipra; }
    public int getIprb()  { return iprb; }
    public int getIsra()  { return isra; }
    public int getIsrb()  { return isrb; }
    public int getImra()  { return imra; }
    public int getImrb()  { return imrb; }
    public int getVr()    { return vr; }
    public int getTacr()  { return tacr; }
    public int getTbcr()  { return tbcr; }
    public int getTcdcr() { return tcdcr; }
    public int getTadr()  { return tadr; }
    public int getTbdr()  { return tbdr; }
    public int getTcdr()  { return tcdr; }
    public int getTddr()  { return tddr; }
    public int getScr()   { return scr; }
    public int getUcr()   { return ucr; }
    public int getRsr()   { return rsr; }
    public int getTsr()   { return tsr; }
    public int getUdr()   { return udr; }
}
