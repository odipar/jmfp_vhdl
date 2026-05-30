package org.jmfp.vhdl.idiomatic;

/**
 * Encapsulates the prescaler / main-counter / timeout logic shared by
 * all four MC68901 timers (A, B, C, D).
 *
 * <p>Timers A and B additionally support an <em>event-count</em> mode
 * driven by an external input pin; timers C and D are delay-mode only.
 * This class handles both modes through its {@link #tick} method.
 */
public final class TimerUnit {

    // --- state ---
    private boolean timerOutput;   // toggle output
    private int     prescaleCounter;
    private int     mainCounter;
    private int[]   mainCounterPipeline = new int[4];

    // only used by timers A and B (event-count capable)
    private int     inputShift;    // 3-bit shift register for external pin

    /** Creates a timer in the reset state. */
    public TimerUnit() {
        reset();
    }

    /** Resets the timer to its initial power-on state. */
    public void reset() {
        timerOutput = false;
        prescaleCounter = 1;
        mainCounter = 0x01;
        mainCounterPipeline = new int[]{0, 0, 0, 0};
        inputShift = 0;
    }

    // ----------------------------------------------------------------
    //  Prescale lookup (identical for all timers)
    // ----------------------------------------------------------------

    /**
     * Maps a 3-bit control-register prescale field to a divide-ratio.
     *
     * @param v 3-bit prescale selector (0–7)
     * @return prescale divide value
     */
    public static int prescale(int v) {
        return switch (v & 0x7) {
            case 0 -> 0;
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 8;
            case 4 -> 25;
            case 5 -> 32;
            case 6 -> 50;
            case 7 -> 100;
            default -> 0;
        };
    }

    // ----------------------------------------------------------------
    //  Core timer tick – delay mode (timers C, D)
    // ----------------------------------------------------------------

    /**
     * Advances the timer by one crystal-clock half-period in
     * <em>delay mode</em> (used by timers C and D).
     *
     * @param controlBits 3-bit prescale selector from TCDCR
     * @param dataReg     current data register value (reload value)
     * @return {@code true} if a timeout interrupt should be requested
     */
    public boolean tick(int controlBits, int dataReg) {
        if (controlBits == 0) {
            return false;
        }
        boolean interrupt = false;
        if (prescaleCounter == 1) {
            prescaleCounter = prescale(controlBits);
            if (mainCounter == 0x01) {
                mainCounter = dataReg & 0xFF;
                timerOutput = !timerOutput;
                interrupt = true;
            } else {
                mainCounter = (mainCounter - 1) & 0xFF;
            }
        } else {
            prescaleCounter = prescaleCounter - 1;
        }
        return interrupt;
    }

    // ----------------------------------------------------------------
    //  Core timer tick – full mode (timers A, B)
    // ----------------------------------------------------------------

    /**
     * Result of a full-mode timer tick for timers A and B.
     *
     * @param timeoutInterrupt  true when the main counter reaches 1 → reload
     * @param pulseCountInterrupt true when an edge is detected on the external pin
     *                            and the timer is in event-count mode with pulse-count
     *                            interrupts enabled
     */
    public record TickResult(boolean timeoutInterrupt, boolean pulseCountInterrupt) {}

    /**
     * Advances the timer by one crystal-clock half-period in
     * <em>full mode</em> (used by timers A and B which have an external
     * input pin for event counting).
     *
     * @param controlReg   4-bit timer control register (TaCR / TbCR)
     * @param dataReg      current data register value (reload value)
     * @param externalPin  current level of the external input (TAI / TBI)
     * @param aerBit       active-edge bit from AER register
     * @param pulseCountIerBit true if the pulse-count interrupt is enabled
     * @param timeoutIerBit    true if the timeout interrupt is enabled
     * @return tick result with interrupt flags
     */
    public TickResult tickFull(int controlReg, int dataReg, boolean externalPin,
                               boolean aerBit, boolean pulseCountIerBit,
                               boolean timeoutIerBit) {
        int prevShift = inputShift;
        inputShift = ((prevShift << 1) | (externalPin ? 1 : 0)) & 0x7;

        boolean highBit = ((prevShift >> 2) & 1) != 0;
        boolean midBit  = ((prevShift >> 1) & 1) != 0;
        boolean edgeDetected = highBit != midBit;
        boolean edgeMatch = externalPin == aerBit;

        boolean pulseInterrupt = false;
        if (pulseCountIerBit && ((controlReg >> 3) & 1) != 0
                && edgeDetected && edgeMatch) {
            pulseInterrupt = true;
        }

        boolean timeoutInterrupt = false;
        if (controlReg != 0x0) {
            boolean isEventCount = controlReg == 0x8;
            boolean shouldCount = (((controlReg >> 3) & 1) == 0)
                    || (isEventCount && edgeDetected && edgeMatch);
            if (shouldCount) {
                if (prescaleCounter == 1 || isEventCount) {
                    if (!isEventCount) {
                        prescaleCounter = prescale(controlReg & 0x7);
                    }
                    if (mainCounter == 0x01) {
                        mainCounter = dataReg & 0xFF;
                        timerOutput = !timerOutput;
                        if (timeoutIerBit) {
                            timeoutInterrupt = true;
                        }
                    } else {
                        mainCounter = (mainCounter - 1) & 0xFF;
                    }
                } else {
                    prescaleCounter = prescaleCounter - 1;
                }
            }
        }
        return new TickResult(timeoutInterrupt, pulseInterrupt);
    }

    // ----------------------------------------------------------------
    //  Pipeline shift (clkren domain)
    // ----------------------------------------------------------------

    /** Shifts the main-counter pipeline register (called once per clkren). */
    public void shiftPipeline() {
        mainCounterPipeline[0] = mainCounterPipeline[1];
        mainCounterPipeline[1] = mainCounterPipeline[2];
        mainCounterPipeline[2] = mainCounterPipeline[3];
        mainCounterPipeline[3] = mainCounter & 0xFF;
    }

    // ----------------------------------------------------------------
    //  Control-register write helpers (used during register writes)
    // ----------------------------------------------------------------

    /**
     * Handles a write to the timer-A or timer-B control register.
     *
     * @param data         the byte written by the CPU
     * @param prevControl  previous control-register value
     */
    public void writeControlAB(int data, int prevControl) {
        if ((data & 0x10) != 0) {
            timerOutput = false;
        }
        int mode = data & 0x0F;
        if (mode == 0x0) {
            prescaleCounter = 1;
        } else if (prevControl == 0x0 && mode != 0x8) {
            prescaleCounter = prescale(data & 0x7);
        }
    }

    /**
     * Handles a write to the data register when the timer is stopped.
     *
     * @param data        the byte written by the CPU
     * @param controlStopped true if the timer's control says "stopped" (0x0)
     */
    public void writeData(int data, boolean controlStopped) {
        if (controlStopped) {
            mainCounter = data & 0xFF;
        }
    }

    /**
     * Called when the prescaler for timer C or D transitions from stopped
     * to running.
     *
     * @param controlBits 3-bit prescale selector
     */
    public void initPrescale(int controlBits) {
        prescaleCounter = prescale(controlBits);
    }

    // ----------------------------------------------------------------
    //  Accessors
    // ----------------------------------------------------------------

    public boolean getOutput()        { return timerOutput; }
    public int     getPipelineRead()  { return mainCounterPipeline[0]; }
    public int     getMainCounter()   { return mainCounter; }
    public int     getPrescaleCounter() { return prescaleCounter; }
    public int     getInputShift()    { return inputShift; }

    /* package-private, for snapshot/restore if needed */
    void setMainCounter(int v)     { mainCounter = v & 0xFF; }
    void setPrescaleCounter(int v) { prescaleCounter = v; }
}
