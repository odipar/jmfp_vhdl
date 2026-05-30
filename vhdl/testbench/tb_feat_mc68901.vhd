-- tb_feat_mc68901.vhd - Feature Testbench for the MC68901 Multi-Function Peripheral
--
-- Simulation phases (500 ms total):
--   Phase 1 (  0- 80 ms): Timer A delay mode with mid-phase frequency change
--     0-40 ms:  P=100, data=200 -> 10 ms period (~4 timeouts)
--     40-80 ms: P=32,  data=50  -> 800 us period (much faster)
--   Phase 2 ( 80-165 ms): Timer B event count mode with mid-phase count change
--     Edges 1-40:  TBDR=10 -> interrupt per 10 active edges (4 interrupts)
--     Edges 41-80: TBDR=4  -> interrupt per  4 active edges (10 interrupts)
--   Phase 3 (165-265 ms): Timers C and D delay mode with mid-phase frequency change
--     0-50 ms:   C: 5 ms   (P=50,  data=200),  D: 5 ms   (P=100, data=100)
--     50-100 ms: C: 400 us (P=8,   data=100),  D: 2.5 ms (P=25,  data=200)
--   Phase 4 (265-360 ms): GPIP edge-detect interrupts with mid-phase polarity change
--     Cycles 1-5:  AER=0x81: GPIP0 rising, GPIP1 falling, GPIP7 rising
--     Cycles 6-10: AER=0x82: GPIP0 falling, GPIP1 rising,  GPIP7 rising
--   Phase 5 (360-500 ms): All four timers simultaneously with mid-phase frequency change
--     0-70 ms:   A: 400 us (P=8,d=100),   B: 625 us  (P=25,d=50),
--                C: 3.2 ms (P=32,d=200),  D: 2.5 ms  (P=50,d=100)
--     70-140 ms: A: 1.25 ms(P=25,d=100),  B: 800 us  (P=8, d=200),
--                C: 2.5 ms (P=100,d=50),  D: 250 us  (P=5, d=100)
--
-- Clock setup:
--   clk     = 8 MHz (CLK_PERIOD = 125 ns)
--   xtlcken = 4 MHz (toggled every system clock rising edge)
--   Timer base clock (internal xtldiv/2) = 2 MHz -> tick period = 500 ns
--   Timer period = data * prescale(control bits) * 500 ns
--     prescale encoding: 001->2, 010->5, 011->8, 100->25, 101->32, 110->50, 111->100
--
-- Register address decoding:
--   addr = "00" & rs[5:1] & '1',  so  rs[5:1] = addr[6:2]
--
-- Interrupt priority (highest -> lowest):
--   15:GPIP7  14:GPIP6  13:TimerA  12-9:USART  8:TimerB
--    7:GPIP5   6:GPIP4   5:TimerC   4:TimerD   3:GPIP3
--    2:GPIP2   1:GPIP1   0:GPIP0
--

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity tb_feat_mc68901 is
end tb_feat_mc68901;

architecture behavior of tb_feat_mc68901 is


    -- System clock: 8 MHz
    constant CLK_PERIOD : time := 125 ns;

    -- ---------------------------------------------------------------------------
    -- Register select constants  (rs[5:1] = addr[6:2]).
    -- ---------------------------------------------------------------------------
    constant RS_GPIP  : std_logic_vector(5 downto 1) := "00000"; -- GPIP         (addr 0x01)
    constant RS_AER   : std_logic_vector(5 downto 1) := "00001"; -- Active Edge  (addr 0x03)
    constant RS_DDR   : std_logic_vector(5 downto 1) := "00010"; -- Data Dir     (addr 0x05)
    constant RS_IERA  : std_logic_vector(5 downto 1) := "00011"; -- Int Enable A (addr 0x07)
    constant RS_IERB  : std_logic_vector(5 downto 1) := "00100"; -- Int Enable B (addr 0x09)
    constant RS_IPRA  : std_logic_vector(5 downto 1) := "00101"; -- Int Pending A(addr 0x0B)
    constant RS_IPRB  : std_logic_vector(5 downto 1) := "00110"; -- Int Pending B(addr 0x0D)
    constant RS_ISRA  : std_logic_vector(5 downto 1) := "00111"; -- In-Service A (addr 0x0F)
    constant RS_ISRB  : std_logic_vector(5 downto 1) := "01000"; -- In-Service B (addr 0x11)
    constant RS_IMRA  : std_logic_vector(5 downto 1) := "01001"; -- Int Mask A   (addr 0x13)
    constant RS_IMRB  : std_logic_vector(5 downto 1) := "01010"; -- Int Mask B   (addr 0x15)
    constant RS_VR    : std_logic_vector(5 downto 1) := "01011"; -- Vector Reg   (addr 0x17)
    constant RS_TACR  : std_logic_vector(5 downto 1) := "01100"; -- Timer A Ctrl (addr 0x19)
    constant RS_TBCR  : std_logic_vector(5 downto 1) := "01101"; -- Timer B Ctrl (addr 0x1B)
    constant RS_TCDCR : std_logic_vector(5 downto 1) := "01110"; -- Timer C/D Ctrl(addr 0x1D)
    constant RS_TADR  : std_logic_vector(5 downto 1) := "01111"; -- Timer A Data (addr 0x1F)
    constant RS_TBDR  : std_logic_vector(5 downto 1) := "10000"; -- Timer B Data (addr 0x21)
    constant RS_TCDR  : std_logic_vector(5 downto 1) := "10001"; -- Timer C Data (addr 0x23)
    constant RS_TDDR  : std_logic_vector(5 downto 1) := "10010"; -- Timer D Data (addr 0x25)

    -- Signals
    signal clk     : std_logic := '0';
    signal clkren  : std_logic := '1';
    signal clkfen  : std_logic := '0';
    signal xtlcken : std_logic := '0';
    signal resetn  : std_logic := '0';
    signal id      : std_logic_vector(7 downto 0) := (others => '0');
    signal od      : std_logic_vector(7 downto 0);
    signal rs      : std_logic_vector(5 downto 1) := (others => '0');
    signal csn     : std_logic := '1';
    signal rwn     : std_logic := '1';
    signal dsn     : std_logic := '1';
    signal dtackn  : std_logic;
    signal irqn    : std_logic;
    signal iackn   : std_logic := '1';
    signal iein    : std_logic := '1';
    signal ieon    : std_logic;
    signal ii      : std_logic_vector(7 downto 0) := (others => '0');
    signal io      : std_logic_vector(7 downto 0);
    signal tai     : std_logic := '0';
    signal tbi     : std_logic := '0';
    signal tao     : std_logic;
    signal tbo     : std_logic;
    signal tco     : std_logic;
    signal tdo     : std_logic;
    signal si      : std_logic := '1';
    signal rc      : std_logic := '0';
    signal so      : std_logic;
    signal tc      : std_logic := '0';
    signal rrn     : std_logic;
    signal trn     : std_logic;

    -- ---------------------------------------------------------------------------
    -- Register write procedure.
    -- Drives one complete MFP bus write cycle and waits for DTACK.
    -- The chip requires csn='0' for two consecutive clkren edges before it
    -- acknowledges; the 'wait until' below handles that automatically.
    -- ---------------------------------------------------------------------------
    procedure mfp_write (
        constant reg_sel  : in  std_logic_vector(5 downto 1);
        constant data     : in  std_logic_vector(7 downto 0);
        signal   s_rs     : out std_logic_vector(5 downto 1);
        signal   s_id     : out std_logic_vector(7 downto 0);
        signal   s_csn    : out std_logic;
        signal   s_rwn    : out std_logic;
        signal   s_dsn    : out std_logic;
        signal   s_dtackn : in  std_logic
    ) is
    begin
        s_rs  <= reg_sel;
        s_id  <= data;
        s_rwn <= '0';
        wait for CLK_PERIOD;
        s_csn <= '0';
        s_dsn <= '0';
        wait until s_dtackn = '0';
        wait for CLK_PERIOD;
        s_csn <= '1';
        s_dsn <= '1';
        s_rwn <= '1';
        wait for CLK_PERIOD * 2;
    end procedure;

    -- ---------------------------------------------------------------------------
    -- Interrupt acknowledge procedure.
    -- Simulates a CPU interrupt acknowledge bus cycle.  iackn is held low for
    -- four clock periods so that the internal siackn shift register is fully
    -- loaded before dsn is asserted.
    -- Must only be called when irqn = '0' (interrupt pending).
    -- ---------------------------------------------------------------------------
    procedure mfp_iack (
        signal s_iackn  : out std_logic;
        signal s_dsn    : out std_logic;
        signal s_dtackn : in  std_logic
    ) is
    begin
        s_iackn <= '0';
        wait for CLK_PERIOD * 4; -- shift iackn through the 3-stage siackn register
        s_dsn <= '0';
        wait until s_dtackn = '0';
        wait for CLK_PERIOD;
        s_iackn <= '1';
        s_dsn   <= '1';
        wait for CLK_PERIOD * 2;
    end procedure;

begin

    uut : entity work.mc68901(behavioral)
    port map (
        clk     => clk,
        clkren  => clkren,
        clkfen  => clkfen,
        xtlcken => xtlcken,
        resetn  => resetn,
        id      => id,
        od      => od,
        rs      => rs,
        csn     => csn,
        rwn     => rwn,
        dsn     => dsn,
        dtackn  => dtackn,
        irqn    => irqn,
        iackn   => iackn,
        iein    => iein,
        ieon    => ieon,
        ii      => ii,
        io      => io,
        tai     => tai,
        tbi     => tbi,
        tao     => tao,
        tbo     => tbo,
        tco     => tco,
        tdo     => tdo,
        si      => si,
        rc      => rc,
        so      => so,
        tc      => tc,
        rrn     => rrn,
        trn     => trn
    );

    -- Clock process: 8 MHz
    clk_proc : process
    begin
        clk <= '1';
        wait for CLK_PERIOD / 2;
        clk <= '0';
        wait for CLK_PERIOD / 2;
    end process;

    -- xtlcken: crystal clock enable, asserted every other system clock (4 MHz).
    -- The MC68901 divides xtlcken by 2 internally (xtldiv), giving a 2 MHz
    -- timer base clock (tick period = 500 ns).
    xtlcken_proc : process (clk)
    begin
        if rising_edge(clk) then
            xtlcken <= not xtlcken;
        end if;
    end process;


    -- =========================================================================
    -- Stimulus process
    -- =========================================================================
    stim_proc : process
    begin
        -- Initial state
        resetn <= '0';
        csn    <= '1';
        rwn    <= '1';
        dsn    <= '1';
        iackn  <= '1';
        tai    <= '0';
        tbi    <= '0';
        ii     <= x"00";

        -- Hold reset for 20 clock cycles then release
        wait for CLK_PERIOD * 20;
        resetn <= '1';
        wait for CLK_PERIOD * 4;

        -- -------------------------------------------------------------------
        -- Common setup
        --   VR = 0x40  ->  base interrupt vector 0x40, automatic EOI (VR[3]=0)
        --   Interrupt vectors produced:
        --     Timer A  -> ipl=13 -> 0x4D
        --     Timer B  -> ipl= 8 -> 0x48
        --     Timer C  -> ipl= 5 -> 0x45
        --     Timer D  -> ipl= 4 -> 0x44
        --     GPIP7    -> ipl=15 -> 0x4F
        --     GPIP1    -> ipl= 1 -> 0x41
        --     GPIP0    -> ipl= 0 -> 0x40
        -- -------------------------------------------------------------------
        mfp_write(RS_VR, x"40", rs, id, csn, rwn, dsn, dtackn);


        -- =====================================================================
        -- Phase 1 (0-80 ms): Timer A delay mode with mid-phase frequency change
        --
        -- First half (0-40 ms):
        --   TACR = 0x07 -> delay mode, prescale P=100
        --   TADR = 200  -> period = 200 * 100 * 500 ns = 10 ms (~4 timeouts)
        -- Mid-phase change at 40 ms: stop Timer A, reload with new data and prescale.
        -- Second half (40-80 ms):
        --   TADR = 50   -> period = 50 * 32 * 500 ns = 800 us (~50 timeouts)
        --   TACR = 0x05 -> delay mode, prescale P=32
        -- IERA = 0x20 and IMRA = 0x20 remain set throughout.
        -- =====================================================================
        mfp_write(RS_TADR, x"C8", rs, id, csn, rwn, dsn, dtackn); -- data = 200
        mfp_write(RS_IERA, x"20", rs, id, csn, rwn, dsn, dtackn); -- enable Timer A (bit 5)
        mfp_write(RS_IMRA, x"20", rs, id, csn, rwn, dsn, dtackn); -- unmask Timer A (bit 5)
        mfp_write(RS_TACR, x"07", rs, id, csn, rwn, dsn, dtackn); -- start: delay P=100

        wait for 40 ms;

        -- Change Timer A frequency: stop, load new data, restart at P=32
        mfp_write(RS_TACR, x"00", rs, id, csn, rwn, dsn, dtackn); -- stop Timer A
        mfp_write(RS_TADR, x"32", rs, id, csn, rwn, dsn, dtackn); -- data = 50
        mfp_write(RS_TACR, x"05", rs, id, csn, rwn, dsn, dtackn); -- restart: delay P=32

        wait for 40 ms;

        -- Stop Timer A; writing 0x00 to IERA also clears any pending ipra bits
        mfp_write(RS_TACR, x"00", rs, id, csn, rwn, dsn, dtackn); -- stop Timer A
        mfp_write(RS_IERA, x"00", rs, id, csn, rwn, dsn, dtackn); -- disable + clear ipra
        mfp_write(RS_IMRA, x"00", rs, id, csn, rwn, dsn, dtackn); -- mask group A
        -- Acknowledge any interrupt that arrived between the stop write and here
        while irqn = '0' loop
            mfp_iack(iackn, dsn, dtackn);
        end loop;


        -- =====================================================================
        -- Phase 2 (80-165 ms): Timer B event count mode with mid-phase count change
        --
        -- AER   = 0x00 -> active edge = falling for TBI (aer[3]=0)
        -- IERA  = 0x01 -> Timer B timeout interrupt enable (iera[0])
        -- IMRA  = 0x01 -> Timer B interrupt unmasked       (imra[0])
        -- First 40 TBI pulses:  TBDR=10 -> interrupt every 10th falling edge (4 interrupts)
        -- Count change after pulse 40: stop Timer B, TBDR=4, restart.
        -- Next 40 TBI pulses:   TBDR=4  -> interrupt every  4th falling edge (10 interrupts)
        -- =====================================================================
        mfp_write(RS_TBDR, x"0A", rs, id, csn, rwn, dsn, dtackn); -- data = 10
        mfp_write(RS_AER,  x"00", rs, id, csn, rwn, dsn, dtackn); -- TBI active: falling
        mfp_write(RS_IERA, x"01", rs, id, csn, rwn, dsn, dtackn); -- enable Timer B (bit 0)
        mfp_write(RS_IMRA, x"01", rs, id, csn, rwn, dsn, dtackn); -- unmask Timer B (bit 0)
        mfp_write(RS_TBCR, x"08", rs, id, csn, rwn, dsn, dtackn); -- start: event count

        -- First 40 TBI pulses (each pulse = one rising + one falling edge)
        for i in 1 to 40 loop
            wait for 500 us;
            tbi <= '1';
            wait for 500 us;
            tbi <= '0';
        end loop;

        -- Change Timer B event count threshold: stop, reload with TBDR=4, restart
        mfp_write(RS_TBCR, x"00", rs, id, csn, rwn, dsn, dtackn); -- stop Timer B
        mfp_write(RS_TBDR, x"04", rs, id, csn, rwn, dsn, dtackn); -- data = 4 (timer stopped: tbmc also loaded)
        mfp_write(RS_TBCR, x"08", rs, id, csn, rwn, dsn, dtackn); -- restart: event count

        -- Next 40 TBI pulses with new threshold
        for i in 1 to 40 loop
            wait for 500 us;
            tbi <= '1';
            wait for 500 us;
            tbi <= '0';
        end loop;

        wait for 5 ms; -- settle: allow last interrupt to be registered

        -- Stop Timer B and clear pending interrupts
        mfp_write(RS_TBCR, x"00", rs, id, csn, rwn, dsn, dtackn); -- stop Timer B
        mfp_write(RS_IERA, x"00", rs, id, csn, rwn, dsn, dtackn); -- disable + clear ipra
        mfp_write(RS_IMRA, x"00", rs, id, csn, rwn, dsn, dtackn); -- mask group A
        while irqn = '0' loop
            mfp_iack(iackn, dsn, dtackn);
        end loop;


        -- =====================================================================
        -- Phase 3 (165-265 ms): Timers C and D delay mode with mid-phase frequency change
        --
        -- IERB = 0x30 (Timer C bit5, Timer D bit4) and IMRB = 0x30 remain set throughout.
        -- First 50 ms:
        --   Timer C: TCDCR[6:4]=110 (P=50),  TCDR=200 -> 200*50*500ns  = 5 ms  (~10 timeouts)
        --   Timer D: TCDCR[2:0]=111 (P=100), TDDR=100 -> 100*100*500ns = 5 ms  (~10 timeouts)
        -- Mid-phase change at 50 ms: stop both timers, load new data, restart.
        -- Second 50 ms:
        --   Timer C: TCDCR[6:4]=011 (P=8),  TCDR=100 -> 100*8*500ns   = 400 us (~125 timeouts)
        --   Timer D: TCDCR[2:0]=100 (P=25), TDDR=200 -> 200*25*500ns  = 2.5 ms (~20 timeouts)
        -- TCDCR = 0x67 = 0_110_0_111  (first half); 0x34 = 0_011_0_100 (second half)
        -- =====================================================================
        mfp_write(RS_TCDR,  x"C8", rs, id, csn, rwn, dsn, dtackn); -- Timer C data = 200
        mfp_write(RS_TDDR,  x"64", rs, id, csn, rwn, dsn, dtackn); -- Timer D data = 100
        mfp_write(RS_IERB,  x"30", rs, id, csn, rwn, dsn, dtackn); -- enable Timer C+D
        mfp_write(RS_IMRB,  x"30", rs, id, csn, rwn, dsn, dtackn); -- unmask Timer C+D
        mfp_write(RS_TCDCR, x"67", rs, id, csn, rwn, dsn, dtackn); -- start: C P=50, D P=100

        wait for 50 ms;

        -- Change Timer C and D frequencies: stop both, load new data, restart
        mfp_write(RS_TCDCR, x"00", rs, id, csn, rwn, dsn, dtackn); -- stop C and D
        mfp_write(RS_TCDR,  x"64", rs, id, csn, rwn, dsn, dtackn); -- Timer C data = 100
        mfp_write(RS_TDDR,  x"C8", rs, id, csn, rwn, dsn, dtackn); -- Timer D data = 200
        -- TCDCR: C bits[6:4]=011 (P=8), D bits[2:0]=100 (P=25) = 0_011_0_100 = 0x34
        mfp_write(RS_TCDCR, x"34", rs, id, csn, rwn, dsn, dtackn); -- restart: C P=8, D P=25

        wait for 50 ms;

        -- Stop both timers; clearing IPRB by writing IERB = 0x00
        mfp_write(RS_TCDCR, x"00", rs, id, csn, rwn, dsn, dtackn); -- stop C and D
        mfp_write(RS_IERB,  x"00", rs, id, csn, rwn, dsn, dtackn); -- disable + clear iprb
        mfp_write(RS_IMRB,  x"00", rs, id, csn, rwn, dsn, dtackn); -- mask group B


        -- =====================================================================
        -- Phase 4 (265-360 ms): GPIP edge-detect interrupts with mid-phase polarity change
        --
        -- DDR  = 0x00 -> all GPIP pins configured as inputs
        -- IERA = 0x80 (GPIP7), IERB = 0x03 (GPIP0+GPIP1), both unmasked throughout.
        -- Cycles 1-5: AER = 0x81 -> GPIP0 rising, GPIP1 falling, GPIP7 rising
        -- Polarity swap after cycle 5: AER = 0x82 -> GPIP0 falling, GPIP1 rising, GPIP7 rising
        -- Cycles 6-10: adjusted stimulus matches new polarities; 3 interrupts per cycle.
        -- =====================================================================
        mfp_write(RS_DDR,  x"00", rs, id, csn, rwn, dsn, dtackn); -- all inputs
        mfp_write(RS_AER,  x"81", rs, id, csn, rwn, dsn, dtackn); -- GPIP0 rising, GPIP1 falling, GPIP7 rising
        mfp_write(RS_IERA, x"80", rs, id, csn, rwn, dsn, dtackn); -- enable GPIP7 (iera[7])
        mfp_write(RS_IERB, x"03", rs, id, csn, rwn, dsn, dtackn); -- enable GPIP1+GPIP0
        mfp_write(RS_IMRA, x"80", rs, id, csn, rwn, dsn, dtackn); -- unmask GPIP7
        mfp_write(RS_IMRB, x"03", rs, id, csn, rwn, dsn, dtackn); -- unmask GPIP1+GPIP0

        -- Cycles 1-5: GPIP0 rising, GPIP7 rising, GPIP1 falling each trigger an interrupt
        for i in 1 to 5 loop
            -- GPIP0 rising edge -> iprb[0] set (AER[0]=1)
            wait for 2 ms;
            ii(0) <= '1';
            -- GPIP7 rising edge -> ipra[7] set (AER[7]=1)
            wait for 1 ms;
            ii(7) <= '1';
            -- GPIP1 goes high (no interrupt: AER[1]=0 means falling edge is active)
            wait for 1 ms;
            ii(1) <= '1';
            -- Release GPIP0 and GPIP7 (falling edges, not active for these channels)
            wait for 1 ms;
            ii(0) <= '0';
            ii(7) <= '0';
            -- GPIP1 falling edge -> iprb[1] set (AER[1]=0)
            wait for 1 ms;
            ii(1) <= '0';
            wait for 500 us;
            -- Acknowledge all three pending interrupts
            while irqn = '0' loop
                mfp_iack(iackn, dsn, dtackn);
            end loop;
        end loop;

        -- Swap edge polarity: GPIP0 now active on falling, GPIP1 now active on rising
        mfp_write(RS_AER, x"82", rs, id, csn, rwn, dsn, dtackn); -- GPIP0 falling, GPIP1 rising, GPIP7 rising

        -- Cycles 6-10: adjusted stimulus for new polarities
        for i in 1 to 5 loop
            -- GPIP0 goes high (no interrupt: AER[0]=0 means falling edge is active)
            wait for 2 ms;
            ii(0) <= '1';
            -- GPIP7 rising edge -> ipra[7] set (AER[7]=1, unchanged)
            wait for 1 ms;
            ii(7) <= '1';
            -- GPIP1 rising edge -> iprb[1] set (AER[1]=1, now rising is active)
            wait for 1 ms;
            ii(1) <= '1';
            -- GPIP0 falling edge -> iprb[0] set (AER[0]=0)
            wait for 1 ms;
            ii(0) <= '0';
            ii(7) <= '0';
            -- GPIP1 goes low (no interrupt: AER[1]=1 means rising edge is active)
            wait for 1 ms;
            ii(1) <= '0';
            wait for 500 us;
            -- Acknowledge all three pending interrupts
            while irqn = '0' loop
                mfp_iack(iackn, dsn, dtackn);
            end loop;
        end loop;

        -- Disable and mask GPIP interrupt channels
        mfp_write(RS_IERA, x"00", rs, id, csn, rwn, dsn, dtackn); -- disable + clear ipra
        mfp_write(RS_IERB, x"00", rs, id, csn, rwn, dsn, dtackn); -- disable + clear iprb
        mfp_write(RS_IMRA, x"00", rs, id, csn, rwn, dsn, dtackn);
        mfp_write(RS_IMRB, x"00", rs, id, csn, rwn, dsn, dtackn);


        -- =====================================================================
        -- Phase 5 (360-500 ms): All four timers simultaneously with mid-phase frequency change
        --
        -- First half (0-70 ms):
        --   Timer A: TACR=0x03 (P=8),        TADR=100 -> 100*8*500ns   = 400 us
        --   Timer B: TBCR=0x04 (P=25),        TBDR=50  ->  50*25*500ns  = 625 us
        --   Timer C: TCDCR[6:4]=101 (P=32),  TCDR=200 -> 200*32*500ns  = 3.2 ms
        --   Timer D: TCDCR[2:0]=110 (P=50),  TDDR=100 -> 100*50*500ns  = 2.5 ms
        --   TCDCR = 0x56 = 0_101_0_110
        -- Mid-phase at 70 ms: drain pending interrupts, stop all, change settings, restart.
        -- Second half (70-140 ms):
        --   Timer A: TACR=0x04 (P=25),        TADR=100 -> 100*25*500ns  = 1.25 ms
        --   Timer B: TBCR=0x03 (P=8),         TBDR=200 -> 200*8*500ns   = 800 us
        --   Timer C: TCDCR[6:4]=111 (P=100), TCDR=50  ->  50*100*500ns = 2.5 ms
        --   Timer D: TCDCR[2:0]=010 (P=5),   TDDR=100 -> 100*5*500ns   = 250 us
        --   TCDCR = 0x72 = 0_111_0_010
        -- =====================================================================
        mfp_write(RS_TADR,  x"64", rs, id, csn, rwn, dsn, dtackn); -- Timer A data = 100
        mfp_write(RS_TBDR,  x"32", rs, id, csn, rwn, dsn, dtackn); -- Timer B data =  50
        mfp_write(RS_TCDR,  x"C8", rs, id, csn, rwn, dsn, dtackn); -- Timer C data = 200
        mfp_write(RS_TDDR,  x"64", rs, id, csn, rwn, dsn, dtackn); -- Timer D data = 100
        mfp_write(RS_IERA,  x"21", rs, id, csn, rwn, dsn, dtackn); -- enable Timer A+B
        mfp_write(RS_IERB,  x"30", rs, id, csn, rwn, dsn, dtackn); -- enable Timer C+D
        mfp_write(RS_IMRA,  x"21", rs, id, csn, rwn, dsn, dtackn); -- unmask Timer A+B
        mfp_write(RS_IMRB,  x"30", rs, id, csn, rwn, dsn, dtackn); -- unmask Timer C+D
        mfp_write(RS_TACR,  x"03", rs, id, csn, rwn, dsn, dtackn); -- start Timer A: delay P=8
        mfp_write(RS_TBCR,  x"04", rs, id, csn, rwn, dsn, dtackn); -- start Timer B: delay P=25
        mfp_write(RS_TCDCR, x"56", rs, id, csn, rwn, dsn, dtackn); -- start C P=32, D P=50

        wait for 70 ms;

        -- Drain any accumulated interrupts before the frequency change
        while irqn = '0' loop
            mfp_iack(iackn, dsn, dtackn);
        end loop;

        -- Change all timer frequencies: stop all, load new settings, restart
        mfp_write(RS_TACR,  x"00", rs, id, csn, rwn, dsn, dtackn); -- stop Timer A
        mfp_write(RS_TBCR,  x"00", rs, id, csn, rwn, dsn, dtackn); -- stop Timer B
        mfp_write(RS_TCDCR, x"00", rs, id, csn, rwn, dsn, dtackn); -- stop C and D
        mfp_write(RS_TADR,  x"64", rs, id, csn, rwn, dsn, dtackn); -- Timer A data = 100
        mfp_write(RS_TBDR,  x"C8", rs, id, csn, rwn, dsn, dtackn); -- Timer B data = 200
        mfp_write(RS_TCDR,  x"32", rs, id, csn, rwn, dsn, dtackn); -- Timer C data =  50
        mfp_write(RS_TDDR,  x"64", rs, id, csn, rwn, dsn, dtackn); -- Timer D data = 100
        mfp_write(RS_TACR,  x"04", rs, id, csn, rwn, dsn, dtackn); -- restart A: delay P=25 -> 1.25 ms
        mfp_write(RS_TBCR,  x"03", rs, id, csn, rwn, dsn, dtackn); -- restart B: delay P=8  -> 800 us
        -- TCDCR: C bits[6:4]=111 (P=100), D bits[2:0]=010 (P=5) = 0_111_0_010 = 0x72
        mfp_write(RS_TCDCR, x"72", rs, id, csn, rwn, dsn, dtackn); -- restart C P=100, D P=5

        wait for 70 ms;

        -- Stop all timers and clear all pending interrupts
        mfp_write(RS_TACR,  x"00", rs, id, csn, rwn, dsn, dtackn);
        mfp_write(RS_TBCR,  x"00", rs, id, csn, rwn, dsn, dtackn);
        mfp_write(RS_TCDCR, x"00", rs, id, csn, rwn, dsn, dtackn);
        mfp_write(RS_IERA,  x"00", rs, id, csn, rwn, dsn, dtackn); -- disable + clear ipra
        mfp_write(RS_IERB,  x"00", rs, id, csn, rwn, dsn, dtackn); -- disable + clear iprb
        mfp_write(RS_IMRA,  x"00", rs, id, csn, rwn, dsn, dtackn);
        mfp_write(RS_IMRB,  x"00", rs, id, csn, rwn, dsn, dtackn);
        while irqn = '0' loop
            mfp_iack(iackn, dsn, dtackn);
        end loop;

        wait; -- end of simulation
    end process;

end behavior;
