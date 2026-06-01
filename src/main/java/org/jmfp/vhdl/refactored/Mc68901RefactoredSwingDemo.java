package org.jmfp.vhdl.refactored;

import javax.swing.*;
import java.awt.*;

/**
 * Minimal Swing demo for configuring and running MC68901 timers.
 */
public final class Mc68901RefactoredSwingDemo {

    private static final int REG_TACR  = 0x19;
    private static final int REG_TBCR  = 0x1b;
    private static final int REG_TCDCR = 0x1d;
    private static final int REG_TADR  = 0x1f;
    private static final int REG_TBDR  = 0x21;
    private static final int REG_TCDR  = 0x23;
    private static final int REG_TDDR  = 0x25;

    private final Mc68901Refactored mfp = new Mc68901Refactored();

    private final JSpinner tacr = spinner(0, 0, 0x0F);
    private final JSpinner tbcr = spinner(0, 0, 0x0F);
    private final JSpinner tccr = spinner(0, 0, 0x07);
    private final JSpinner tdcr = spinner(0, 0, 0x07);
    private final JSpinner tadr = spinner(2, 0, 0xFF);
    private final JSpinner tbdr = spinner(2, 0, 0xFF);
    private final JSpinner tcdr = spinner(2, 0, 0xFF);
    private final JSpinner tddr = spinner(2, 0, 0xFF);
    private final JSpinner runTicks = spinner(10, 1, 1_000_000);
    private final JCheckBox tai = new JCheckBox("TAI");
    private final JCheckBox tbi = new JCheckBox("TBI");
    private final JTextArea status = new JTextArea(15, 72);

    private long ticksExecuted;

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Cannot launch Swing demo in headless environment.");
            return;
        }
        SwingUtilities.invokeLater(() -> new Mc68901RefactoredSwingDemo().show());
    }

    private void show() {
        JFrame frame = new JFrame("MC68901 Refactored Timers Demo");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(buildUi());
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        resetModel();
    }

    private JPanel buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(buildConfigPanel(), BorderLayout.NORTH);
        root.add(new JScrollPane(status), BorderLayout.CENTER);
        status.setEditable(false);
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return root;
    }

    private JPanel buildConfigPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel values = new JPanel(new GridLayout(4, 4, 6, 4));
        addField(values, "TACR (A ctrl)", tacr);
        addField(values, "TBCR (B ctrl)", tbcr);
        addField(values, "TCCR (C ctrl)", tccr);
        addField(values, "TDCR (D ctrl)", tdcr);
        addField(values, "TADR (A data)", tadr);
        addField(values, "TBDR (B data)", tbdr);
        addField(values, "TCDR (C data)", tcdr);
        addField(values, "TDDR (D data)", tddr);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton apply = new JButton("Apply Timer Config");
        JButton tickOne = new JButton("Tick 1");
        JButton run = new JButton("Run N ticks");
        JButton reset = new JButton("Reset");
        actions.add(apply);
        actions.add(tickOne);
        actions.add(runTicks);
        actions.add(run);
        actions.add(tai);
        actions.add(tbi);
        actions.add(reset);

        apply.addActionListener(e -> {
            applyConfigurationFromUi();
            refreshStatus();
        });
        tickOne.addActionListener(e -> {
            applyConfigurationFromUi();
            stepTicks(1);
        });
        run.addActionListener(e -> {
            applyConfigurationFromUi();
            stepTicks(((Number) runTicks.getValue()).intValue());
        });
        reset.addActionListener(e -> resetModel());

        panel.add(values, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private static void addField(JPanel panel, String label, JSpinner spinner) {
        panel.add(new JLabel(label));
        panel.add(spinner);
    }

    private static JSpinner spinner(int initial, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(initial, min, max, 1));
    }

    private void resetModel() {
        mfp.risingEdge(true, true, false, 0, 0, true, true, true, true, 0, false, false);
        ticksExecuted = 0;
        applyConfigurationFromUi();
        refreshStatus();
    }

    static int packTcdcrWriteValue(int timerCControl, int timerDControl) {
        int cHighNibble = ((timerCControl & 0x7) << 4) & 0x70;
        return cHighNibble | (timerDControl & 0x7);
    }

    static void configureTimers(Mc68901Refactored model,
                                int timerAControl, int timerBControl,
                                int timerCControl, int timerDControl,
                                int timerAData, int timerBData,
                                int timerCData, int timerDData) {
        model.writeRegister(REG_TACR, timerAControl);
        model.writeRegister(REG_TBCR, timerBControl);
        model.writeRegister(REG_TCDCR, packTcdcrWriteValue(timerCControl, timerDControl));
        model.writeRegister(REG_TADR, timerAData);
        model.writeRegister(REG_TBDR, timerBData);
        model.writeRegister(REG_TCDR, timerCData);
        model.writeRegister(REG_TDDR, timerDData);
    }

    private void applyConfigurationFromUi() {
        configureTimers(
                mfp,
                ((Number) tacr.getValue()).intValue(),
                ((Number) tbcr.getValue()).intValue(),
                ((Number) tccr.getValue()).intValue(),
                ((Number) tdcr.getValue()).intValue(),
                ((Number) tadr.getValue()).intValue(),
                ((Number) tbdr.getValue()).intValue(),
                ((Number) tcdr.getValue()).intValue(),
                ((Number) tddr.getValue()).intValue()
        );
    }

    private void stepTicks(int count) {
        for (int i = 0; i < count; i++) {
            mfp.clockTimers(tai.isSelected(), tbi.isSelected());
        }
        ticksExecuted += count;
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder b = new StringBuilder(512);
        b.append("ticks=").append(ticksExecuted).append('\n');
        b.append("irqPending=").append(mfp.isInterruptPending())
                .append(" ipra=0x").append(hex2(mfp.getIpra()))
                .append(" iprb=0x").append(hex2(mfp.getIprb()))
                .append('\n').append('\n');
        b.append(timerLine("A", mfp.getTacr() & 0x0F, mfp.getTadr(), mfp.timerA().getMainCounter(),
                mfp.timerA().getPrescaleCounter(), mfp.isTao(),
                mfp.timerA().getLastTimeoutInterrupt(), mfp.timerA().getLastPulseCountInterrupt()));
        b.append(timerLine("B", mfp.getTbcr() & 0x0F, mfp.getTbdr(), mfp.timerB().getMainCounter(),
                mfp.timerB().getPrescaleCounter(), mfp.isTbo(),
                mfp.timerB().getLastTimeoutInterrupt(), mfp.timerB().getLastPulseCountInterrupt()));
        b.append(timerLine("C", (mfp.getTcdcr() >> 3) & 0x7, mfp.getTcdr(), mfp.timerC().getMainCounter(),
                mfp.timerC().getPrescaleCounter(), mfp.isTco(),
                ((mfp.getIprb() >> 5) & 1) != 0, false));
        b.append(timerLine("D", mfp.getTcdcr() & 0x7, mfp.getTddr(), mfp.timerD().getMainCounter(),
                mfp.timerD().getPrescaleCounter(), mfp.isTdo(),
                ((mfp.getIprb() >> 4) & 1) != 0, false));
        status.setText(b.toString());
    }

    private static String timerLine(String name, int control, int data, int mainCounter,
                                    int prescaleCounter, boolean output,
                                    boolean timeoutInterrupt, boolean pulseInterrupt) {
        return "timer " + name +
                " ctrl=0x" + Integer.toHexString(control & 0xFF).toUpperCase() +
                " data=0x" + hex2(data) +
                " main=" + (mainCounter & 0xFF) +
                " prescale=" + prescaleCounter +
                " out=" + output +
                " timeoutIrq=" + timeoutInterrupt +
                " pulseIrq=" + pulseInterrupt +
                '\n';
    }

    private static String hex2(int value) {
        return String.format("%02X", value & 0xFF);
    }
}
