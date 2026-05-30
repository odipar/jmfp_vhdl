package org.jmfp.vhdl.optimized;

/**
 * CPU-optimized timer unit that avoids object allocation on every tick.
 *
 * <p>Key optimizations over {@link org.jmfp.vhdl.idiomatic.TimerUnit}:
 * <ul>
 *   <li>No {@code TickResult} record allocation – result flags stored in fields.</li>
 *   <li>Pre-computed prescale lookup array instead of switch statement.</li>
 *   <li>Minimized method call overhead with inlined logic.</li>
 * </ul>
 */
public final class OptimizedTimerUnit {

    // Pre-computed prescale values (indexed by 3-bit control field)
    private static final int[] PRESCALE = {0, 2, 5, 8, 25, 32, 50, 100};

    // --- state ---
    private boolean timerOutput;
    private int     prescaleCounter;
    private int     mainCounter;
    private int     pipe0, pipe1, pipe2, pipe3;  // inlined pipeline array

    // only used by timers A and B (event-count capable)
    private int inputShift;

    // --- result flags (avoid allocation) ---
    private boolean lastTimeoutInterrupt;
    private boolean lastPulseCountInterrupt;

    public OptimizedTimerUnit() {
        reset();
    }

    public void reset() {
        timerOutput = false;
        prescaleCounter = 1;
        mainCounter = 0x01;
        pipe0 = 0; pipe1 = 0; pipe2 = 0; pipe3 = 0;
        inputShift = 0;
        lastTimeoutInterrupt = false;
        lastPulseCountInterrupt = false;
    }

    // ----------------------------------------------------------------
    //  Core timer tick – delay mode (timers C, D)
    // ----------------------------------------------------------------

    /**
     * Advances the timer in delay mode. Returns true if timeout interrupt.
     */
    public boolean tick(int controlBits, int dataReg) {
        if (controlBits == 0) return false;
        if (prescaleCounter == 1) {
            prescaleCounter = PRESCALE[controlBits & 0x7];
            if (mainCounter == 0x01) {
                mainCounter = dataReg & 0xFF;
                timerOutput = !timerOutput;
                return true;
            } else {
                mainCounter = (mainCounter - 1) & 0xFF;
            }
        } else {
            prescaleCounter--;
        }
        return false;
    }

    // ----------------------------------------------------------------
    //  Core timer tick – full mode (timers A, B)
    //  Result stored in lastTimeoutInterrupt / lastPulseCountInterrupt
    // ----------------------------------------------------------------

    /**
     * Advances the timer in full mode. Results are stored in
     * {@link #getLastTimeoutInterrupt()} and {@link #getLastPulseCountInterrupt()}.
     */
    public void tickFull(int controlReg, int dataReg, boolean externalPin,
                         boolean aerBit, boolean pulseCountIerBit,
                         boolean timeoutIerBit) {
        int prevShift = inputShift;
        inputShift = ((prevShift << 1) | (externalPin ? 1 : 0)) & 0x7;

        boolean highBit = ((prevShift >> 2) & 1) != 0;
        boolean midBit  = ((prevShift >> 1) & 1) != 0;
        boolean edgeDetected = highBit != midBit;
        boolean edgeMatch = externalPin == aerBit;

        lastPulseCountInterrupt = pulseCountIerBit && ((controlReg >> 3) & 1) != 0
                && edgeDetected && edgeMatch;

        lastTimeoutInterrupt = false;
        if (controlReg != 0x0) {
            boolean isEventCount = controlReg == 0x8;
            boolean shouldCount = (((controlReg >> 3) & 1) == 0)
                    || (isEventCount && edgeDetected && edgeMatch);
            if (shouldCount) {
                if (prescaleCounter == 1 || isEventCount) {
                    if (!isEventCount) {
                        prescaleCounter = PRESCALE[controlReg & 0x7];
                    }
                    if (mainCounter == 0x01) {
                        mainCounter = dataReg & 0xFF;
                        timerOutput = !timerOutput;
                        if (timeoutIerBit) {
                            lastTimeoutInterrupt = true;
                        }
                    } else {
                        mainCounter = (mainCounter - 1) & 0xFF;
                    }
                } else {
                    prescaleCounter--;
                }
            }
        }
    }

    // ----------------------------------------------------------------
    //  Pipeline shift (clkren domain)
    // ----------------------------------------------------------------

    public void shiftPipeline() {
        pipe0 = pipe1;
        pipe1 = pipe2;
        pipe2 = pipe3;
        pipe3 = mainCounter & 0xFF;
    }

    // ----------------------------------------------------------------
    //  Control-register write helpers
    // ----------------------------------------------------------------

    public void writeControlAB(int data, int prevControl) {
        if ((data & 0x10) != 0) {
            timerOutput = false;
        }
        int mode = data & 0x0F;
        if (mode == 0x0) {
            prescaleCounter = 1;
        } else if (prevControl == 0x0 && mode != 0x8) {
            prescaleCounter = PRESCALE[data & 0x7];
        }
    }

    public void writeData(int data, boolean controlStopped) {
        if (controlStopped) {
            mainCounter = data & 0xFF;
        }
    }

    public void initPrescale(int controlBits) {
        prescaleCounter = PRESCALE[controlBits & 0x7];
    }

    // ----------------------------------------------------------------
    //  Accessors
    // ----------------------------------------------------------------

    public boolean getOutput()        { return timerOutput; }
    public int     getPipelineRead()  { return pipe0; }
    public int     getMainCounter()   { return mainCounter; }
    public int     getPrescaleCounter() { return prescaleCounter; }
    public int     getInputShift()    { return inputShift; }

    public boolean getLastTimeoutInterrupt()    { return lastTimeoutInterrupt; }
    public boolean getLastPulseCountInterrupt() { return lastPulseCountInterrupt; }

    void setMainCounter(int v)     { mainCounter = v & 0xFF; }
    void setPrescaleCounter(int v) { prescaleCounter = v; }
}
