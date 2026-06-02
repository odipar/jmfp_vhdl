package org.jmfp.vhdl.swing;

import org.jmfp.vhdl.refactored.Mc68901Refactored;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Swing application that runs the refactored MC68901 MFP chip and displays
 * real-time timer tick counters.
 *
 * <p>The chip is clocked at the Atari ST crystal frequency of 2.4576 MHz.
 * Each timer can be configured with a prescaler (divider) and a count value.
 * The effective timer frequency is: crystal / (2 * prescaler * count).
 */
public final class MfpTimerApp extends JFrame {

    /** Atari ST MFP crystal frequency: 2.4576 MHz. */
    private static final double XTAL_FREQUENCY_HZ = 2_457_600.0;

    /** Prescaler values indexed by the 3-bit control field. */
    private static final int[] PRESCALE_VALUES = {0, 4, 10, 16, 50, 64, 100, 200};

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

    // Tick counters (number of timer timeouts)
    private volatile long tickCountA;
    private volatile long tickCountB;
    private volatile long tickCountC;
    private volatile long tickCountD;

    // Simulation state
    private volatile boolean running = true;
    private Thread simulationThread;

    public MfpTimerApp() {
        super("MC68901 MFP Timer Simulator – Atari ST Crystal (2.4576 MHz)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        timerPanelA = new TimerPanel("Timer A");
        timerPanelB = new TimerPanel("Timer B");
        timerPanelC = new TimerPanel("Timer C");
        timerPanelD = new TimerPanel("Timer D");

        JPanel timersPanel = new JPanel(new GridLayout(2, 2, 8, 8));
        timersPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        timersPanel.add(timerPanelA);
        timersPanel.add(timerPanelB);
        timersPanel.add(timerPanelC);
        timersPanel.add(timerPanelD);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton applyBtn = new JButton("Apply Settings");
        applyBtn.addActionListener(e -> applySettings());
        JButton resetBtn = new JButton("Reset Counters");
        resetBtn.addActionListener(e -> resetCounters());
        controlPanel.add(applyBtn);
        controlPanel.add(resetBtn);

        setLayout(new BorderLayout());
        add(timersPanel, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.SOUTH);

        // Set default values: Timer C = 192 (200Hz), Timer D = 1 (9600 baud ref)
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
        setMinimumSize(new Dimension(700, 400));
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
    }

    private void startSimulation() {
        simulationThread = new Thread(() -> {
            // Simulate at effective speed using batched ticks
            // Real clock: 2.4576 MHz = ~407ns per tick
            // We batch ticks and sleep periodically to avoid consuming 100% CPU
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
                        mfp.clockTimers(false, false);

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
            double freq = XTAL_FREQUENCY_HZ / (2.0 * prescale * count);
            if (freq >= 1000) {
                freqLabel.setText(String.format("%.2f kHz", freq / 1000.0));
            } else {
                freqLabel.setText(String.format("%.2f Hz", freq));
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
