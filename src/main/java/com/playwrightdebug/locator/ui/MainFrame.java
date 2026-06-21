package com.playwrightdebug.locator.ui;

import com.microsoft.playwright.Page;
import com.playwrightdebug.locator.model.LocatorInput;
import com.playwrightdebug.locator.model.LocatorTestResult;
import com.playwrightdebug.locator.model.PageInfo;
import com.playwrightdebug.locator.model.PlaywrightScriptContext;
import com.playwrightdebug.locator.service.LocatorTestService;
import com.playwrightdebug.locator.service.PlaywrightCodegenService;
import com.playwrightdebug.locator.service.PlaywrightSession;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.Box;
import javax.swing.BoxLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainFrame extends JFrame {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String COMMON_GROOVY_IMPORTS = """
            import com.microsoft.playwright.Page
            import com.microsoft.playwright.Locator
            import com.microsoft.playwright.options.AriaRole
            import com.microsoft.playwright.Page.GetByRoleOptions
            import com.microsoft.playwright.Locator.FilterOptions
            """.stripIndent();

    private static final String LOCATOR_GROOVY_TEMPLATE = COMMON_GROOVY_IMPORTS + """

            // Locator を返す式（click 等のアクションは書かない）
            page.getByRole(
                AriaRole.BUTTON,
                new GetByRoleOptions().setName("Google 検索")
            )
            """.stripIndent();

    private static final String TEST_SCRIPT_TEMPLATE = COMMON_GROOVY_IMPORTS + """

            // テストコードをそのまま記述（click, fill など）
            page.getByRole(
                AriaRole.BUTTON,
                new GetByRoleOptions().setName("Google 検索")
            ).click()
            """.stripIndent();

    private final PlaywrightSession session = new PlaywrightSession();
    private final PlaywrightCodegenService codegenService = new PlaywrightCodegenService();
    private final LocatorTestService locatorTestService = new LocatorTestService();
    private final ExecutorService uiExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ui-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final JTextField endpointField = new JTextField("http://localhost:9222", 28);
    private final JButton connectButton = new JButton("接続");
    private final JButton disconnectButton = new JButton("切断");
    private final JButton refreshPagesButton = new JButton("ページ更新");
    private final JButton codegenButton = new JButton("Codegen");
    private final JLabel statusLabel = new JLabel("未接続");

    private final JComboBox<PageInfo> pageComboBox = new JComboBox<>();
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(5000, 500, 120000, 500));

    private final JTextArea locatorGroovyArea = new JTextArea(LOCATOR_GROOVY_TEMPLATE, 8, 40);
    private final TextAreaUndoSupport locatorGroovyUndo = new TextAreaUndoSupport(
            locatorGroovyArea,
            locatorGroovyArea.getDocument()
    );

    private final JTextArea testScriptArea = new JTextArea(TEST_SCRIPT_TEMPLATE, 10, 40);
    private final TextAreaUndoSupport testScriptUndo = new TextAreaUndoSupport(
            testScriptArea,
            testScriptArea.getDocument()
    );

    private final JTabbedPane workspaceTabs = new JTabbedPane();
    private final JTextArea logArea = new JTextArea();

    public MainFrame() {
        super("Playwright Debug Locator");
        locatorGroovyArea.setTabSize(2);
        testScriptArea.setTabSize(2);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 680));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(createConnectionPanel(), BorderLayout.NORTH);
        root.add(createMainSplitPane(), BorderLayout.CENTER);
        setContentPane(root);

        bindEvents();
        updateConnectionState(false);
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("CDP 接続"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 0;
        panel.add(new JLabel("Endpoint URL"), gc);

        gc.gridx = 1;
        gc.weightx = 1;
        panel.add(endpointField, gc);

        gc.gridx = 2;
        gc.weightx = 0;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(connectButton);
        buttons.add(disconnectButton);
        buttons.add(refreshPagesButton);
        buttons.add(codegenButton);
        panel.add(buttons, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        gc.gridwidth = 3;
        statusLabel.putClientProperty("JComponent.sizeVariant", "small");
        panel.add(statusLabel, gc);

        return panel;
    }

    private JSplitPane createMainSplitPane() {
        JPanel workspace = new JPanel(new BorderLayout(8, 8));
        workspace.add(createSharedOptionsPanel(), BorderLayout.NORTH);
        workspaceTabs.addTab("Locator 検証", createLocatorVerificationPanel());
        workspaceTabs.addTab("テスト実行", createTestExecutionPanel());
        workspace.add(workspaceTabs, BorderLayout.CENTER);

        JScrollPane logPanel = createLogPanel();
        workspace.setMinimumSize(new Dimension(0, 180));
        logPanel.setMinimumSize(new Dimension(0, 120));

        JPanel topWrapper = new JPanel(new BorderLayout());
        topWrapper.add(workspace, BorderLayout.CENTER);
        topWrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, SPLIT_DIVIDER_MARGIN, 0));

        JPanel bottomWrapper = new JPanel(new BorderLayout());
        bottomWrapper.add(logPanel, BorderLayout.CENTER);
        bottomWrapper.setBorder(BorderFactory.createEmptyBorder(SPLIT_DIVIDER_MARGIN, 0, 0, 0));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topWrapper, bottomWrapper);
        splitPane.setResizeWeight(0.62);
        splitPane.setContinuousLayout(true);
        splitPane.setOneTouchExpandable(true);
        splitPane.setBorder(null);
        splitPane.setDividerLocation(0.62);
        return splitPane;
    }

    private JPanel createSharedOptionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 0;
        panel.add(new JLabel("ページ"), gc);

        gc.gridx = 1;
        gc.weightx = 1;
        pageComboBox.setPrototypeDisplayValue(new PageInfo("", "Sample page title", "https://example.com", null));
        panel.add(pageComboBox, gc);

        gc.gridx = 2;
        gc.weightx = 0;
        panel.add(new JLabel("Timeout (ms)"), gc);

        gc.gridx = 3;
        gc.weightx = 0;
        panel.add(timeoutSpinner, gc);

        return panel;
    }

    private static final int ACTION_TOP_GAP = 10;
    private static final int SPLIT_DIVIDER_MARGIN = 8;

    private JPanel createLocatorVerificationPanel() {
        return createEditorWithActions(
                new JScrollPane(locatorGroovyArea),
                createActionButton("検証実行", this::runFullTest),
                createActionButton("Count", () -> runLocatorAction(locatorTestService::count)),
                createActionButton("Highlight", () -> runLocatorAction(locatorTestService::highlight)),
                createActionButton("Hide Highlight", this::hideHighlight),
                createActionButton("Wait Visible", this::waitForVisible),
                createActionButton("一覧表示", this::listMatches)
        );
    }

    private JPanel createTestExecutionPanel() {
        return createEditorWithActions(
                new JScrollPane(testScriptArea),
                createActionButton("テスト実行", this::runTestScript)
        );
    }

    private JPanel createEditorWithActions(JScrollPane editor, JButton... buttons) {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.add(editor, BorderLayout.CENTER);
        panel.add(createActionPanel(buttons), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createActionPanel(JButton... buttons) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(Box.createVerticalStrut(ACTION_TOP_GAP));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JButton button : buttons) {
            actions.add(button);
        }
        panel.add(actions);

        return panel;
    }

    private JScrollPane createLogPanel() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("結果ログ"));
        return scrollPane;
    }

    private JButton createActionButton(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(e -> action.run());
        return button;
    }

    private void bindEvents() {
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());
        refreshPagesButton.addActionListener(e -> refreshPages());
        codegenButton.addActionListener(e -> launchCodegen());

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                session.close();
                codegenService.stop();
                uiExecutor.shutdownNow();
            }
        });
    }

    private void connect() {
        setBusy(true);
        appendLog("CDP 接続中: " + endpointField.getText().trim());
        uiExecutor.submit(() -> {
            try {
                session.connect(endpointField.getText());
                List<PageInfo> pages = session.listPages();
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(true);
                    setPageItems(pages);
                    appendLog("接続成功。ページ数: " + pages.size());
                    setBusy(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(false);
                    appendLog("接続失敗: " + ex.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void disconnect() {
        setBusy(true);
        uiExecutor.submit(() -> {
            try {
                session.disconnect();
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(false);
                    pageComboBox.setModel(new DefaultComboBoxModel<>());
                    appendLog("切断しました");
                    setBusy(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("切断エラー: " + ex.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void refreshPages() {
        if (!session.isConnected()) {
            appendLog("先に CDP 接続してください");
            return;
        }

        setBusy(true);
        uiExecutor.submit(() -> {
            try {
                List<PageInfo> pages = session.listPages();
                SwingUtilities.invokeLater(() -> {
                    setPageItems(pages);
                    appendLog("ページ一覧を更新しました (" + pages.size() + " 件)");
                    setBusy(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("ページ更新失敗: " + ex.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void launchCodegen() {
        boolean restarting = codegenService.isRunning();
        appendLog(restarting
                ? "Codegen 再起動中 (msedge): 空のページ"
                : "Codegen 起動中 (msedge): 空のページ");
        uiExecutor.submit(() -> {
            try {
                codegenService.launch(null);
                SwingUtilities.invokeLater(() ->
                        appendLog("Codegen を起動しました（Edge / Playwright Inspector）"));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> appendLog("Codegen 起動失敗: " + ex.getMessage()));
            }
        });
    }

    private double timeoutMs() {
        return ((Number) timeoutSpinner.getValue()).doubleValue();
    }

    private LocatorInput currentLocatorInput() {
        return new LocatorInput(LocatorInput.EvaluationMode.GROOVY, locatorGroovyArea.getText());
    }

    private LocatorInput currentTestInput() {
        return new LocatorInput(LocatorInput.EvaluationMode.TEST_SCRIPT, testScriptArea.getText());
    }

    private void runFullTest() {
        runOnSelectedPage(currentLocatorInput(), context -> locatorTestService.runFullTest(
                context,
                currentLocatorInput(),
                timeoutMs()
        ));
    }

    private void runTestScript() {
        runOnSelectedPage(currentTestInput(), context -> locatorTestService.runTestScript(
                context,
                currentTestInput()
        ));
    }

    private void waitForVisible() {
        runOnSelectedPage(currentLocatorInput(), context -> locatorTestService.waitForVisible(
                context,
                currentLocatorInput(),
                timeoutMs()
        ));
    }

    private void listMatches() {
        runOnSelectedPage(currentLocatorInput(), context -> locatorTestService.listMatches(
                context,
                currentLocatorInput(),
                20
        ));
    }

    private void hideHighlight() {
        runOnSelectedPage(currentLocatorInput(), context -> locatorTestService.hideHighlight(context.page()));
    }

    @FunctionalInterface
    private interface LocatorContextAction {
        LocatorTestResult run(PlaywrightScriptContext context);
    }

    @FunctionalInterface
    private interface LocatorInputAction {
        LocatorTestResult run(PlaywrightScriptContext context, LocatorInput input);
    }

    private void runLocatorAction(LocatorInputAction action) {
        LocatorInput input = currentLocatorInput();
        runOnSelectedPage(input, context -> action.run(context, input));
    }

    private void runOnSelectedPage(LocatorInput input, LocatorContextAction action) {
        if (!session.isConnected()) {
            appendLog("先に CDP 接続してください");
            return;
        }

        PageInfo selected = (PageInfo) pageComboBox.getSelectedItem();
        if (selected == null) {
            appendLog("ページを選択してください");
            return;
        }

        if (input.text() == null || input.text().isBlank()) {
            appendLog("式を入力してください");
            return;
        }

        setBusy(true);
        uiExecutor.submit(() -> {
            try {
                LocatorTestResult result = session.run(() -> {
                    Page page = session.requirePage(selected);
                    PlaywrightScriptContext context = session.createContext(page);
                    return action.run(context);
                });
                SwingUtilities.invokeLater(() -> {
                    appendLog(result.asText());
                    setBusy(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("実行失敗: " + ex.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void setPageItems(List<PageInfo> pages) {
        PageInfo previous = (PageInfo) pageComboBox.getSelectedItem();
        DefaultComboBoxModel<PageInfo> model = new DefaultComboBoxModel<>();
        for (PageInfo page : pages) {
            model.addElement(page);
        }
        pageComboBox.setModel(model);

        if (previous != null) {
            for (int i = 0; i < model.getSize(); i++) {
                PageInfo candidate = model.getElementAt(i);
                if (candidate.url().equals(previous.url()) && candidate.title().equals(previous.title())) {
                    pageComboBox.setSelectedIndex(i);
                    return;
                }
            }
        }

        if (model.getSize() > 0) {
            pageComboBox.setSelectedIndex(0);
        }
    }

    private void updateConnectionState(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
        refreshPagesButton.setEnabled(connected);
        pageComboBox.setEnabled(connected);
        endpointField.setEnabled(!connected);
        statusLabel.setText(connected
                ? "接続中: " + session.getEndpointUrl()
                : "未接続 — Chrome/Edge を --remote-debugging-port=9222 で起動してください");
    }

    private void appendLog(String message) {
        String entry = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message;
        logArea.append(entry + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void setBusy(boolean busy) {
        connectButton.setEnabled(!busy && !session.isConnected());
        disconnectButton.setEnabled(!busy && session.isConnected());
        refreshPagesButton.setEnabled(!busy && session.isConnected());
    }
}
