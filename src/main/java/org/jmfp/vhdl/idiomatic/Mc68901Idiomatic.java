package org.jmfp.vhdl.idiomatic;

import org.jmfp.vhdl.Mc68901;

/**
 * Idiomatic MC68901 implementation - same public-field API as the base
 * {@link Mc68901} class, demonstrating a structured coding style.
 */
public class Mc68901Idiomatic {

    private final Mc68901 delegate = new Mc68901();

    // ---- Chip inputs (set by caller before risingEdge) ----
    public boolean clkren;
    public boolean xtlcken;
    public boolean resetn;
    public int     id;
    public int     rs;
    public boolean csn;
    public boolean rwn;
    public boolean dsn;
    public boolean iackn;
    public int     ii;
    public int     iiPrev;
    public boolean tai;
    public boolean tbi;

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

    public void risingEdge() {
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
