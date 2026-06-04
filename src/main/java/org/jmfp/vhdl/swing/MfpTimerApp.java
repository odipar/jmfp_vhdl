package org.jmfp.vhdl.swing;

import org.jmfp.vhdl.refactored.Mc68901Refactored;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Swing application that runs the refactored MC68901 MFP chip and displays
 * real-time timer tick counters, interrupt status, and GPIP state.
 *
 * <p>The chip is clocked at the Atari ST crystal frequency of 2.4576 MHz using
 * the low-level {@link Mc68901Refactored#risingEdge} API with both {@code clkren}
 * and {@code xtlcken} asserted.
 *
 * <p>The timer output frequency is:
 * crystal / (prescaler &times; count).
 *
 * <p>In addition to the four timer channels the application shows the MFP's
 * interrupt subsystem (IERA/IERB, IPRA/IPRB, IRQ output) and the eight
 * general-purpose I/O pins (GPIP).
 */
public final class MfpTimerApp extends JFrame {

    /** Atari ST MFP crystal frequency: 2.4576 MHz. */
    private static final double XTAL_FREQUENCY_HZ = 2_457_600.0;

    /** Prescaler values indexed by the 3-bit control field. */
    private static final int[] PRESCALE_VALUES = {0, 4, 10, 16, 100, 64, 100, 200};

    /** Labels for prescaler dropdown. */
    private static final String[] PRESCALE_LABELS = {
        "Stopped", "/4", "/10", "/16", "/50", "/64", "/100", "/200"
    };

    private final Mc68901Refactored mfp = new Mc68901Refactored();

    // UI components for each timer
    private final TimerPanel timerPanelA;
    private final TimerPanel timerPanelB;
    private final TimerPanel timerPanelC;
    private final TimerPanel timerPanelD;

    // UI components for interrupts and GPIP
    private final InterruptPanel interruptPanel;
    private final GpipPanel      gpipPanel;
    private final JLabel         elapsedTimeLabel;

    // Tick counters (number of timer output transitions / 2 = timeouts)
    private volatile long tickCountA;
    private volatile long tickCountB;
    private volatile long tickCountC;
    private volatile long tickCountD;
    
    // Simulation time tracking
    private volatile long totalCrystalTicks;
    private volatile long startTimeNanos;

    // Interrupt-status snapshot updated by the simulation thread
    private volatile boolean irqActive;
    private volatile int     iprASnapshot;
    private volatile int     iprBSnapshot;
    private volatile int     gpipSnapshot;

    // Simulation state
    private volatile boolean running = true;
    private Thread simulationThread;

    public MfpTimerApp() {
        super("MC68901 MFP Simulator – Atari ST Crystal (2.4576 MHz)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        timerPanelA    = new TimerPanel("Timer A");
        timerPanelB    = new TimerPanel("Timer B");
        timerPanelC    = new TimerPanel("Timer C");
        timerPanelD    = new TimerPanel("Timer D");
        interruptPanel = new InterruptPanel();
        gpipPanel      = new GpipPanel();

        JPanel timersPanel = new JPanel(new GridLayout(2, 2, 8, 8));
        timersPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        timersPanel.add(timerPanelA);
        timersPanel.add(timerPanelB);
        timersPanel.add(timerPanelC);
        timersPanel.add(timerPanelD);

        JPanel bottomRow = new JPanel(new GridLayout(1, 2, 8, 0));
        bottomRow.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        bottomRow.add(interruptPanel);
        bottomRow.add(gpipPanel);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton applyBtn = new JButton("Apply Settings");
        applyBtn.addActionListener(e -> applySettings());
        JButton resetBtn = new JButton("Reset Counters");
        resetBtn.addActionListener(e -> resetCounters());
        JButton clearIprBtn = new JButton("Clear Interrupts");
        clearIprBtn.addActionListener(e -> clearInterrupts());
        
        elapsedTimeLabel = new JLabel("Elapsed: 0.000 s");
        elapsedTimeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        
        controlPanel.add(applyBtn);
        controlPanel.add(resetBtn);
        controlPanel.add(clearIprBtn);
        controlPanel.add(Box.createHorizontalStrut(20));  // spacer
        controlPanel.add(elapsedTimeLabel);

        JPanel southWrap = new JPanel(new BorderLayout());
        southWrap.add(bottomRow,    BorderLayout.CENTER);
        southWrap.add(controlPanel, BorderLayout.SOUTH);

        setLayout(new BorderLayout(0, 4));
        add(timersPanel, BorderLayout.CENTER);
        add(southWrap,   BorderLayout.SOUTH);

        // Default timer configuration
        timerPanelA.setCount(246);
        timerPanelA.setPrescaler(7);  // /200
        timerPanelB.setCount(246);
        timerPanelB.setPrescaler(7);  // /200
        timerPanelC.setCount(192);
        timerPanelC.setPrescaler(7);  // /200
        timerPanelD.setCount(2);
        timerPanelD.setPrescaler(1);  // /4

        applySettings();
        startSimulation();
        startUIRefresh();

        pack();
        setMinimumSize(new Dimension(820, 520));
        setLocationRelativeTo(null);
    }

    private void applySettings() {
        int prescA = timerPanelA.getPrescaler();
        int countA = timerPanelA.getCount();
        int prescB = timerPanelB.getPrescaler();
        int countB = timerPanelB.getCount();
        int prescC = timerPanelC.getPrescaler();
        int countC = timerPanelC.getCount();
        int prescD = timerPanelD.getPrescaler();
        int countD = timerPanelD.getCount();

        synchronized (mfp) {
            // Timer A control register (0x19): prescaler in low 3 bits for delay mode
            mfp.writeRegister(0x19, prescA & 0x07);
            // Timer A data register (0x1F)
            mfp.writeRegister(0x1F, countA & 0xFF);

            // Timer B control register (0x1B): prescaler in low 3 bits for delay mode
            mfp.writeRegister(0x1B, prescB & 0x07);
            // Timer B data register (0x21)
            mfp.writeRegister(0x21, countB & 0xFF);

            // Timer C/D control register (0x1D): C in high nibble, D in low nibble
            int tcdcr = ((prescC & 0x07) << 4) | (prescD & 0x07);
            mfp.writeRegister(0x1D, tcdcr);
            // Timer C data register (0x23)
            mfp.writeRegister(0x23, countC & 0xFF);
            // Timer D data register (0x25)
            mfp.writeRegister(0x25, countD & 0xFF);

            // Interrupt enable: IERA bit5=Timer-A-timeout, bit0=Timer-B-timeout
            mfp.writeRegister(0x07, 0x21);  // IERA
            // Interrupt enable: IERB bit5=Timer-C-timeout, bit4=Timer-D-timeout
            mfp.writeRegister(0x09, 0x30);  // IERB
            // Interrupt mask: same bits as IER so all enabled channels reach /IRQ
            mfp.writeRegister(0x13, 0x21);  // IMRA
            mfp.writeRegister(0x15, 0x30);  // IMRB
        }

        // Update frequency labels
        timerPanelA.updateFrequency(prescA, countA);
        timerPanelB.updateFrequency(prescB, countB);
        timerPanelC.updateFrequency(prescC, countC);
        timerPanelD.updateFrequency(prescD, countD);
    }

    private void resetCounters() {
        tickCountA = 0;
        tickCountB = 0;
        tickCountC = 0;
        tickCountD = 0;
        totalCrystalTicks = 0;
        startTimeNanos = System.nanoTime();
    }

    private void clearInterrupts() {
        synchronized (mfp) {
            // Writing 0x00 to IPRA/IPRB clears all pending interrupt bits
            mfp.writeRegister(0x0B, 0x00);  // IPRA
            mfp.writeRegister(0x0D, 0x00);  // IPRB
        }
    }

    private void startSimulation() {
        startTimeNanos = System.nanoTime();
        totalCrystalTicks = 0;
        
        simulationThread = new Thread(() -> {
            // Clock the chip via risingEdge with both clkren and xtlcken asserted.
            //
            // Chip-select (csn) and interrupt-acknowledge (iackn) are de-asserted
            // (active-low, so held high) to avoid unintended bus cycles.
            final int BATCH_SIZE = 4096;
            final long SLEEP_NS = (long) (BATCH_SIZE / XTAL_FREQUENCY_HZ * 1_000_000_000.0);

            while (running) {
                long startNs = System.nanoTime();

                synchronized (mfp) {
                    boolean prevTao = mfp.timerA().getOutput();
                    boolean prevTbo = mfp.timerB().getOutput();
                    boolean prevTco = mfp.timerC().getOutput();
                    boolean prevTdo = mfp.timerD().getOutput();

                    for (int i = 0; i < BATCH_SIZE; i++) {
                        // clkren=true, xtlcken=true, resetn=true, id=0, rs=0,
                        // csn=true (inactive), rwn=true, dsn=true (inactive),
                        // iackn=true (inactive), ii=0, tai=false, tbi=false
                        mfp.risingEdge(true, true, true, 0, 0, true, true, true, true, 0, false, false);
                        totalCrystalTicks++;

                        boolean curTao = mfp.timerA().getOutput();
                        boolean curTbo = mfp.timerB().getOutput();
                        boolean curTco = mfp.timerC().getOutput();
                        boolean curTdo = mfp.timerD().getOutput();

                        if (curTao != prevTao) tickCountA++;
                        if (curTbo != prevTbo) tickCountB++;
                        if (curTco != prevTco) tickCountC++;
                        if (curTdo != prevTdo) tickCountD++;

                        prevTao = curTao;
                        prevTbo = curTbo;
                        prevTco = curTco;
                        prevTdo = curTdo;
                    }

                    // Snapshot interrupt and GPIP state for the UI thread
                    irqActive    = !mfp.isIrqn();   // irqn is active-low
                    iprASnapshot = mfp.getIpra();
                    iprBSnapshot = mfp.getIprb();
                    gpipSnapshot = mfp.getIo();
                }

                long elapsed = System.nanoTime() - startNs;
                long remaining = SLEEP_NS - elapsed;
                if (remaining > 0) {
                    try {
                        Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "MFP-Simulation");
        simulationThread.setDaemon(true);
        simulationThread.start();
    }

    private void startUIRefresh() {
        Timer uiTimer = new Timer(100, e -> {
            timerPanelA.setTickCount(tickCountA);
            timerPanelB.setTickCount(tickCountB);
            timerPanelC.setTickCount(tickCountC);
            timerPanelD.setTickCount(tickCountD);

            synchronized (mfp) {
                timerPanelA.setMainCounter(mfp.timerA().getMainCounter());
                timerPanelB.setMainCounter(mfp.timerB().getMainCounter());
                timerPanelC.setMainCounter(mfp.timerC().getMainCounter());
                timerPanelD.setMainCounter(mfp.timerD().getMainCounter());
            }
            
            // Update elapsed time display
            double elapsedSeconds = totalCrystalTicks / XTAL_FREQUENCY_HZ;
            elapsedTimeLabel.setText(String.format("Elapsed: %.3f s", elapsedSeconds));

            interruptPanel.update(irqActive, iprASnapshot, iprBSnapshot);
            gpipPanel.update(gpipSnapshot);
        });
        uiTimer.start();
    }

    // ================================================================
    //  Timer configuration panel
    // ================================================================

    private static final class TimerPanel extends JPanel {
        private final JComboBox<String> prescalerCombo;
        private final JSpinner countSpinner;
        private final JLabel freqLabel;
        private final JLabel tickLabel;
        private final JLabel counterLabel;

        TimerPanel(String name) {
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(), name,
                    TitledBorder.LEFT, TitledBorder.TOP));
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(3, 5, 3, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Prescaler row
            gbc.gridx = 0; gbc.gridy = 0;
            add(new JLabel("Prescaler:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            prescalerCombo = new JComboBox<>(PRESCALE_LABELS);
            prescalerCombo.setSelectedIndex(1);
            add(prescalerCombo, gbc);

            // Count row
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("Count (1-255):"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            countSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 255, 1));
            add(countSpinner, gbc);

            // Frequency display row
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("Frequency:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            freqLabel = new JLabel("—");
            freqLabel.setFont(freqLabel.getFont().deriveFont(Font.BOLD));
            add(freqLabel, gbc);

            // Tick counter row
            gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("Ticks:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            tickLabel = new JLabel("0");
            tickLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
            add(tickLabel, gbc);

            // Main counter row
            gbc.gridx = 0; gbc.gridy = 4; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("Counter:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            counterLabel = new JLabel("0x01");
            counterLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            add(counterLabel, gbc);
        }

        int getPrescaler() {
            return prescalerCombo.getSelectedIndex();
        }

        void setPrescaler(int index) {
            prescalerCombo.setSelectedIndex(index);
        }

        int getCount() {
            return (Integer) countSpinner.getValue();
        }

        void setCount(int value) {
            countSpinner.setValue(value);
        }

        void setTickCount(long ticks) {
            tickLabel.setText(String.format("%,d", ticks));
        }

        void setMainCounter(int value) {
            counterLabel.setText(String.format("0x%02X (%d)", value, value));
        }

        void updateFrequency(int prescalerIndex, int count) {
            if (prescalerIndex == 0) {
                freqLabel.setText("Stopped");
                return;
            }
            int prescale = PRESCALE_VALUES[prescalerIndex];
            double freq = XTAL_FREQUENCY_HZ / (prescale * count);
            if (freq >= 1000) {
                freqLabel.setText(String.format("%.2f kHz", freq / 1000.0));
            } else {
                freqLabel.setText(String.format("%.2f Hz", freq));
            }
        }
    }

    // ================================================================
    //  Interrupt status panel
    // ================================================================

    /**
     * Displays the MFP interrupt controller state: IRQ output pin, the two
     * interrupt-pending registers (IPRA / IPRB), and which timer channels
     * currently have a pending interrupt.
     *
     * <p>IERA / IERB are configured in {@link MfpTimerApp#applySettings()} to
     * enable timeouts for all four timer channels. IMRA / IMRB are set to the
     * same mask so enabled interrupts are visible on the /IRQ output.
     */
    private static final class InterruptPanel extends JPanel {

        /** Bit positions in IPRA for each timer timeout channel. */
        private static final int IPRA_BIT_TIMER_A = 5;
        private static final int IPRA_BIT_TIMER_B = 0;

        /** Bit positions in IPRB for each timer timeout channel. */
        private static final int IPRB_BIT_TIMER_C = 5;
        private static final int IPRB_BIT_TIMER_D = 4;

        private final JLabel irqLabel;
        private final JLabel iprALabel;
        private final JLabel iprBLabel;
        private final JLabel[] timerPendingLabels = new JLabel[4];

        InterruptPanel() {
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(), "Interrupt Status",
                    TitledBorder.LEFT, TitledBorder.TOP));
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(3, 5, 3, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // IRQ output row
            gbc.gridx = 0; gbc.gridy = 0;
            add(new JLabel("/IRQ:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            irqLabel = new JLabel("inactive");
            irqLabel.setFont(irqLabel.getFont().deriveFont(Font.BOLD));
            add(irqLabel, gbc);

            // IPRA row
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("IPRA:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            iprALabel = new JLabel("0x00");
            iprALabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            add(iprALabel, gbc);

            // IPRB row
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("IPRB:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            iprBLabel = new JLabel("0x00");
            iprBLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            add(iprBLabel, gbc);

            // Per-timer pending rows
            String[] timerNames = {"Timer A", "Timer B", "Timer C", "Timer D"};
            for (int t = 0; t < 4; t++) {
                gbc.gridx = 0; gbc.gridy = 3 + t;
                gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
                add(new JLabel(timerNames[t] + ":"), gbc);
                gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
                timerPendingLabels[t] = new JLabel("—");
                add(timerPendingLabels[t], gbc);
            }
        }

        void update(boolean irqActive, int ipra, int iprb) {
            if (irqActive) {
                irqLabel.setText("ACTIVE");
                irqLabel.setForeground(Color.RED);
            } else {
                irqLabel.setText("inactive");
                irqLabel.setForeground(UIManager.getColor("Label.foreground"));
            }
            iprALabel.setText(String.format("0x%02X  (b%s)", ipra, toBinary8(ipra)));
            iprBLabel.setText(String.format("0x%02X  (b%s)", iprb, toBinary8(iprb)));

            updateTimerPending(0, (ipra >> IPRA_BIT_TIMER_A) & 1);
            updateTimerPending(1, (ipra >> IPRA_BIT_TIMER_B) & 1);
            updateTimerPending(2, (iprb >> IPRB_BIT_TIMER_C) & 1);
            updateTimerPending(3, (iprb >> IPRB_BIT_TIMER_D) & 1);
        }

        private void updateTimerPending(int index, int pending) {
            if (pending != 0) {
                timerPendingLabels[index].setText("PENDING");
                timerPendingLabels[index].setForeground(Color.RED);
            } else {
                timerPendingLabels[index].setText("clear");
                timerPendingLabels[index].setForeground(UIManager.getColor("Label.foreground"));
            }
        }

        private static String toBinary8(int value) {
            return String.format("%8s", Integer.toBinaryString(value & 0xFF)).replace(' ', '0');
        }
    }

    // ================================================================
    //  GPIP (General Purpose I/O Port) display panel
    // ================================================================

    /**
     * Displays the eight general-purpose I/O pins of the MFP (the IO output,
     * which reflects the GPIP register merged with the DDR-controlled pins).
     * Each pin is shown as a named bit indicator.
     */
    private static final class GpipPanel extends JPanel {

        private static final String[] PIN_NAMES = {
            "I0 (GP)", "I1 (GP)", "I2 (GP)", "I3 (GP/TBi)",
            "I4 (GP/TAi)", "I5 (GP)", "I6 (GP)", "I7 (GP)"
        };

        private final JLabel[] pinLabels = new JLabel[8];

        GpipPanel() {
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(), "GPIP / I/O Pins",
                    TitledBorder.LEFT, TitledBorder.TOP));
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 5, 2, 5);
            gbc.anchor = GridBagConstraints.WEST;

            for (int i = 0; i < 8; i++) {
                gbc.gridx = 0; gbc.gridy = i;
                gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
                add(new JLabel(PIN_NAMES[i] + ":"), gbc);
                gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
                pinLabels[i] = new JLabel("0");
                pinLabels[i].setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                add(pinLabels[i], gbc);
            }
        }

        void update(int io) {
            for (int i = 0; i < 8; i++) {
                int bit = (io >> i) & 1;
                pinLabels[i].setText(bit != 0 ? "1  (HIGH)" : "0  (low)");
                pinLabels[i].setForeground(
                        bit != 0 ? new Color(0, 128, 0) : UIManager.getColor("Label.foreground"));
            }
        }
    }

    // ================================================================
    //  Main entry point
    // ================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            new MfpTimerApp().setVisible(true);
        });
    }
}
