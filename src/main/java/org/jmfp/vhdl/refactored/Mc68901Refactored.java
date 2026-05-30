package org.jmfp.vhdl.refactored;

import org.jmfp.vhdl.Mc68901;

/**
 * Refactored MC68901 implementation where all chip inputs are passed
 * as parameters to {@link #risingEdge} rather than set as public fields.
 * Output signals remain accessible as public fields.
 */
public class Mc68901Refactored {

    private final Mc68901 delegate = new Mc68901();

    // ---- Chip outputs (read by caller after risingEdge) ----
    public int     od;
    public boolean dtackn;
    public boolean irqn;
    public boolean ieon;
    public int     io;
    public boolean tao;
    public boolean tbo;
    public boolean tco;
    public boolean tdo;

    /**
     * Advance the model by one rising clock edge with all inputs as parameters.
     */
    public void risingEdge(boolean clkren, boolean xtlcken, boolean resetn,
                           int id, int rs, boolean csn, boolean rwn, boolean dsn,
                           boolean iackn, int ii, int iiPrev, boolean tai, boolean tbi) {
        delegate.clkren  = clkren;
        delegate.xtlcken = xtlcken;
        delegate.resetn  = resetn;
        delegate.id      = id;
        delegate.rs      = rs;
        delegate.csn     = csn;
        delegate.rwn     = rwn;
        delegate.dsn     = dsn;
        delegate.iackn   = iackn;
        delegate.ii      = ii;
        delegate.iiPrev  = iiPrev;
        delegate.tai     = tai;
        delegate.tbi     = tbi;

        delegate.risingEdge();

        od     = delegate.od;
        dtackn = delegate.dtackn;
        irqn   = delegate.irqn;
        ieon   = delegate.ieon;
        io     = delegate.io;
        tao    = delegate.tao;
        tbo    = delegate.tbo;
        tco    = delegate.tco;
        tdo    = delegate.tdo;
    }
}
