package org.jmfp.vhdl.idiomatic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RegisterFile} – verifying register reads/writes.
 */
class RegisterFileTest {

    private final TimerUnit timerA = new TimerUnit();
    private final TimerUnit timerB = new TimerUnit();
    private final TimerUnit timerC = new TimerUnit();
    private final TimerUnit timerD = new TimerUnit();
    private final RegisterFile rf = new RegisterFile(timerA, timerB, timerC, timerD);

    @Test
    void resetClearsRegisters() {
        rf.gpip = 0xFF;
        rf.reset();
        assertEquals(0, rf.getGpip());
        assertEquals(0x01, rf.getVr());
    }

    @Test
    void writeAndReadGpip() {
        RegisterFile.Snapshot snap = rf.snapshot();
        rf.write(0x01, 0xAB, snap);
        assertEquals(0xAB, rf.getGpip());
    }

    @Test
    void writeAndReadAer() {
        RegisterFile.Snapshot snap = rf.snapshot();
        rf.write(0x03, 0x55, snap);
        assertEquals(0x55, rf.getAer());
        assertEquals(0x55, rf.read(0x03, snap, 0));
    }

    @Test
    void writeAndReadDdr() {
        RegisterFile.Snapshot snap = rf.snapshot();
        rf.write(0x05, 0xF0, snap);
        assertEquals(0xF0, rf.getDdr());
    }

    @Test
    void writeIeraClearsIpr() {
        rf.ipra = 0xFF;
        RegisterFile.Snapshot snap = rf.snapshot();
        rf.write(0x07, 0x0F, snap); // IERA = 0x0F
        assertEquals(0x0F, rf.getIera());
        // ipra should be ANDed with new IERA value: 0xFF & 0x0F = 0x0F
        assertEquals(0x0F, rf.getIpra());
    }

    @Test
    void writeVrClearsIsrWhenBit3Zero() {
        rf.isra = 0xFF;
        rf.isrb = 0xFF;
        RegisterFile.Snapshot snap = rf.snapshot();
        rf.write(0x17, 0x00, snap); // VR write with bit 3 = 0
        assertEquals(0, rf.getIsra());
        assertEquals(0, rf.getIsrb());
    }

    @Test
    void readTimerCounterPipeline() {
        // Fill pipeline with known value
        timerA.setMainCounter(0x42);
        for (int i = 0; i < 4; i++) timerA.shiftPipeline();
        RegisterFile.Snapshot snap = rf.snapshot();
        assertEquals(0x42, rf.read(0x1f, snap, 0));
    }

    @Test
    void writeTimerAControlRegister() {
        RegisterFile.Snapshot snap = rf.snapshot();
        rf.write(0x19, 0x01, snap); // TACR = 0x01 (prescale mode)
        assertEquals(0x01, rf.getTacr());
    }

    @Test
    void writeTcdcrRegister() {
        RegisterFile.Snapshot snap = rf.snapshot();
        // Write TCDCR: high nibble (bits 6:4) = timer C, low nibble (bits 2:0) = timer D
        rf.write(0x1d, 0x32, snap); // C=3, D=2
        int tcdcr = rf.getTcdcr();
        assertEquals(3, (tcdcr >> 3) & 0x7); // timer C prescale = 3
        assertEquals(2, tcdcr & 0x7);         // timer D prescale = 2
    }

    @Test
    void readTcdcrReconstructsFormat() {
        rf.tcdcr = (3 << 3) | 2; // C=3, D=2
        RegisterFile.Snapshot snap = rf.snapshot();
        int read = rf.read(0x1d, snap, 0);
        assertEquals(0x32, read); // (3 << 4) | 2 = 0x32
    }

    @Test
    void snapshotIsImmutable() {
        rf.iera = 0x10;
        RegisterFile.Snapshot snap = rf.snapshot();
        rf.iera = 0xFF;
        assertEquals(0x10, snap.iera(), "snapshot should not change");
    }

    @Test
    void readUnmappedReturns0xFF() {
        RegisterFile.Snapshot snap = rf.snapshot();
        assertEquals(0xFF, rf.read(0x31, snap, 0));
    }
}
