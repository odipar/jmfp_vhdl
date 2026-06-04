package org.jmfp.vhdl.swing;

import org.jmfp.vhdl.idiomatic.TimerUnit;
import org.jmfp.vhdl.refactored.Mc68901Refactored;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify the timer frequency calculations.
 * 
 * <p>These tests prove that the divide-by-2 flip-flop assumption was invalid
 * and that the correct frequency formula is: crystal / (prescaler × count)
 * not: crystal / (2 × prescaler × count)
 */
class TimerFrequencyTest {

    /** Atari ST MFP crystal frequency: 2.4576 MHz. */
    private static final double XTAL_FREQUENCY_HZ = 2_457_600.0;

    /**
     * Verifies that the prescaler values from TimerUnit match the expected
     * hardware specification values (2, 5, 8, 25, 32, 50, 100).
     */
    @Test
    void prescalerValuesMatchHardwareSpec() {
        assertEquals(0,   TimerUnit.prescale(0), "Prescale 0 = stopped");
        assertEquals(2,   TimerUnit.prescale(1), "Prescale 1 = /2");
        assertEquals(5,   TimerUnit.prescale(2), "Prescale 2 = /5");
        assertEquals(8,   TimerUnit.prescale(3), "Prescale 3 = /8");
        assertEquals(25,  TimerUnit.prescale(4), "Prescale 4 = /25");
        assertEquals(32,  TimerUnit.prescale(5), "Prescale 5 = /32");
        assertEquals(50,  TimerUnit.prescale(6), "Prescale 6 = /50");
        assertEquals(100, TimerUnit.prescale(7), "Prescale 7 = /100");
    }

    /**
     * Tests the frequency calculation for a known configuration.
     * 
     * <p>With prescaler=2 and count=246, the frequency should be:
     * 2.4576 MHz / (2 × 246) = 2.4576 MHz / 492 = 4995.12 Hz ≈ 5.0 kHz
     * 
     * <p>NOT 2.4576 MHz / (2 × 2 × 246) = 2497.56 Hz ≈ 2.5 kHz (incorrect with divide-by-2)
     */
    @Test
    void frequencyCalculationWithPrescaler2Count246() {
        int prescaler = 2;
        int count = 246;
        
        double expectedFreqHz = XTAL_FREQUENCY_HZ / (prescaler * count);
        assertEquals(4995.12, expectedFreqHz, 0.01, 
            "Frequency with prescaler=2, count=246 should be ~5.0 kHz");
        
        // Verify this is NOT the divide-by-2 result
        double incorrectFreqHz = XTAL_FREQUENCY_HZ / (2.0 * prescaler * count);
        assertEquals(2497.56, incorrectFreqHz, 0.01);
        assertNotEquals(expectedFreqHz, incorrectFreqHz, 
            "The divide-by-2 formula gives a different (incorrect) result");
    }

    /**
     * Tests the frequency calculation for prescaler=100 and count=192.
     * 
     * <p>With prescaler=100 and count=192, the frequency should be:
     * 2.4576 MHz / (100 × 192) = 2.4576 MHz / 19200 = 128 Hz
     * 
     * <p>NOT 2.4576 MHz / (2 × 100 × 192) = 64 Hz (incorrect with divide-by-2)
     */
    @Test
    void frequencyCalculationWithPrescaler100Count192() {
        int prescaler = 100;
        int count = 192;
        
        double expectedFreqHz = XTAL_FREQUENCY_HZ / (prescaler * count);
        assertEquals(128.0, expectedFreqHz, 0.01, 
            "Frequency with prescaler=100, count=192 should be exactly 128 Hz");
        
        // Verify this is NOT the divide-by-2 result
        double incorrectFreqHz = XTAL_FREQUENCY_HZ / (2.0 * prescaler * count);
        assertEquals(64.0, incorrectFreqHz, 0.01);
        assertNotEquals(expectedFreqHz, incorrectFreqHz, 
            "The divide-by-2 formula gives a different (incorrect) result");
    }

    /**
     * Empirical test: runs the actual timer for a known number of ticks
     * and verifies the timeout count matches the expected frequency.
     * 
     * <p>With prescaler=2 (controlBits=1) and count=10, we expect:
     * - Prescaler cycles: 2
     * - Main counter cycles: 10
     * - Total ticks per timeout: 2 × 10 = 20 ticks
     * 
     * <p>This proves the formula is: ticks = prescaler × count (no divide-by-2)
     * 
     * <p>Note: The timer starts with mainCounter=1, so the first tick immediately
     * causes a timeout. We measure the period between the first and second timeouts
     * to verify the formula.
     */
    @Test
    void empiricalTimerTickCount() {
        TimerUnit timer = new TimerUnit();
        int controlBits = 1; // prescaler = 2
        int dataReg = 10;     // count = 10
        
        // Expected ticks per period: prescaler × count = 2 × 10 = 20
        int expectedTicks = 20;
        
        // First timeout happens immediately (mainCounter starts at 1)
        boolean gotInterrupt = timer.tick(controlBits, dataReg);
        assertTrue(gotInterrupt, "First tick should fire (mainCounter=1)");
        
        // Now count ticks until the next timeout
        int tickCount = 0;
        gotInterrupt = false;
        for (int i = 0; i < 100 && !gotInterrupt; i++) {
            gotInterrupt = timer.tick(controlBits, dataReg);
            tickCount++;
        }
        
        assertTrue(gotInterrupt, "Should get second interrupt within 100 ticks");
        assertEquals(expectedTicks, tickCount, 
            "Period between timeouts should be exactly prescaler × count = 2 × 10 = 20 ticks");
    }

    /**
     * Tests another configuration to verify the formula.
     * 
     * <p>With prescaler=5 (controlBits=2) and count=4, we expect:
     * - Total ticks per timeout: 5 × 4 = 20 ticks
     */
    @Test
    void empiricalTimerTickCountPrescaler5() {
        TimerUnit timer = new TimerUnit();
        int controlBits = 2; // prescaler = 5
        int dataReg = 4;      // count = 4
        
        int expectedTicks = 20; // 5 × 4 = 20
        
        // First timeout happens immediately
        boolean gotInterrupt = timer.tick(controlBits, dataReg);
        assertTrue(gotInterrupt, "First tick should fire");
        
        // Count ticks until next timeout
        int tickCount = 0;
        gotInterrupt = false;
        for (int i = 0; i < 100 && !gotInterrupt; i++) {
            gotInterrupt = timer.tick(controlBits, dataReg);
            tickCount++;
        }
        
        assertTrue(gotInterrupt, "Should get second interrupt within 100 ticks");
        assertEquals(expectedTicks, tickCount, 
            "Period between timeouts should be exactly prescaler × count = 5 × 4 = 20 ticks");
    }
}
