package com.playwrightdebug.locator.ui;

import com.playwrightdebug.locator.service.StrictModeViolationParser.Candidate;
import com.playwrightdebug.locator.service.StrictModeViolationParser.StrictModeViolation;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboPopup;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Inline panel (for bottom tabs) to pick a Playwright "aka" locator from a strict-mode violation.
 */
public final class StrictModeCandidatePanel extends JPanel {

    private final JLabel headerLabel = new JLabel("strict mode 違反はまだありません");
    private final JComboBox<CandidateItem> comboBox = new JComboBox<>();
    private final JTextArea previewArea = new JTextArea(4, 40);
    private final JButton applyButton = new JButton("この locator に置換");
    private final JButton clearButton = new JButton("クリア");

    private StrictModeViolation currentViolation;
    private String idHint;
    private Consumer<Candidate> onApply = candidate -> {
    };
    private Runnable onClear = () -> {
    };

    public StrictModeCandidatePanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(true);

        comboBox.setEditable(false);
        comboBox.setMaximumRowCount(12);
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus
                );
                label.setText(value == null ? "" : value.toString());
                return label;
            }
        });
        comboBox.addActionListener(e -> updatePreview());
        comboBox.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                widenPopupToFitContent();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        applyButton.setEnabled(false);
        applyButton.addActionListener(e -> applySelection());
        clearButton.addActionListener(e -> {
            clear();
            onClear.run();
        });

        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(headerLabel, BorderLayout.NORTH);
        north.add(comboBox, BorderLayout.CENTER);

        JScrollPane previewScroll = new JScrollPane(previewArea);
        previewScroll.setBorder(BorderFactory.createTitledBorder("選択中の locator"));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(applyButton);
        buttons.add(clearButton);

        add(north, BorderLayout.NORTH);
        add(previewScroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    public void setOnApply(Consumer<Candidate> onApply) {
        this.onApply = onApply == null ? candidate -> {
        } : onApply;
    }

    public void setOnClear(Runnable onClear) {
        this.onClear = onClear == null ? () -> {
        } : onClear;
    }

    public void showViolation(StrictModeViolation violation) {
        showViolation(violation, null, null);
    }

    public void showViolation(StrictModeViolation violation, Candidate preferred) {
        showViolation(violation, preferred, null);
    }

    public void showViolation(StrictModeViolation violation, Candidate preferred, String idHint) {
        this.currentViolation = violation;
        this.idHint = blankToNull(idHint);
        List<CandidateItem> items = new ArrayList<>();
        int preferredIndex = 0;
        for (int i = 0; i < violation.candidates().size(); i++) {
            Candidate candidate = violation.candidates().get(i);
            items.add(new CandidateItem(candidate));
            if (preferred != null && sameCandidate(preferred, candidate)) {
                preferredIndex = i;
            }
        }
        comboBox.setModel(new DefaultComboBoxModel<>(items.toArray(CandidateItem[]::new)));
        if (!items.isEmpty()) {
            comboBox.setSelectedIndex(preferredIndex);
        }
        headerLabel.setText(buildHeader(violation, preferred, this.idHint));
        applyButton.setEnabled(!items.isEmpty());
        updatePreview();
        comboBox.repaint();
    }

    private static String buildHeader(StrictModeViolation violation, Candidate preferred, String idHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("違反: ").append(violation.failingLocator())
                .append("  →  ").append(violation.candidates().size()).append(" 件の候補");
        if (idHint != null && !idHint.isBlank()) {
            sb.append("  |  IDヒント: #").append(idHint);
        }
        if (preferred != null) {
            sb.append("  （候補 ").append(preferred.index()).append(" を選択中）");
        }
        return sb.toString();
    }

    private static boolean sameCandidate(Candidate left, Candidate right) {
        return left.index() == right.index()
                && left.akaLocator().equals(right.akaLocator());
    }

    public void clear() {
        currentViolation = null;
        idHint = null;
        comboBox.setModel(new DefaultComboBoxModel<>());
        comboBox.setSelectedItem(null);
        previewArea.setText("");
        headerLabel.setText("strict mode 違反はまだありません");
        applyButton.setEnabled(false);
    }

    public StrictModeViolation currentViolation() {
        return currentViolation;
    }

    public void focusCombo() {
        SwingUtilities.invokeLater(() -> comboBox.requestFocusInWindow());
    }

    private void applySelection() {
        CandidateItem item = resolveSelectedItem();
        if (item == null || currentViolation == null) {
            return;
        }
        onApply.accept(item.candidate());
    }

    private CandidateItem resolveSelectedItem() {
        Object value = comboBox.getSelectedItem();
        if (value instanceof CandidateItem item) {
            return item;
        }
        return null;
    }

    private void updatePreview() {
        CandidateItem item = resolveSelectedItem();
        if (item == null) {
            previewArea.setText("");
            return;
        }
        Candidate c = item.candidate();
        StringBuilder text = new StringBuilder();
        text.append("index: ").append(c.index()).append(System.lineSeparator());
        c.elementId().ifPresent(id ->
                text.append("element id: #").append(id).append(System.lineSeparator()));
        if (idHint != null) {
            text.append("IDヒント: #").append(idHint).append(System.lineSeparator());
        }
        text.append("aka: ").append(c.akaLocator()).append(System.lineSeparator())
                .append("置換後: ").append(c.pageExpression()).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("element: ").append(c.elementSummary());
        previewArea.setText(text.toString());
    }

    private void widenPopupToFitContent() {
        Object popup = comboBox.getUI().getAccessibleChild(comboBox, 0);
        if (!(popup instanceof BasicComboPopup basicPopup)) {
            return;
        }
        JList<?> list = basicPopup.getList();
        FontMetrics metrics = list.getFontMetrics(list.getFont());
        int maxWidth = comboBox.getWidth();
        for (int i = 0; i < comboBox.getItemCount(); i++) {
            CandidateItem item = comboBox.getItemAt(i);
            if (item == null) {
                continue;
            }
            maxWidth = Math.max(maxWidth, metrics.stringWidth(item.toString()) + 32);
        }
        Dimension size = basicPopup.getPreferredSize();
        size.width = maxWidth;
        basicPopup.setPreferredSize(size);
        basicPopup.setMaximumSize(size);
        if (basicPopup.getParent() != null) {
            basicPopup.getParent().setPreferredSize(size);
            basicPopup.getParent().setMaximumSize(size);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record CandidateItem(Candidate candidate) {
        @Override
        public String toString() {
            return candidate.displayLabel();
        }
    }
}
