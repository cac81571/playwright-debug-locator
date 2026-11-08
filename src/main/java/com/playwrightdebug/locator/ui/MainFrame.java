package com.playwrightdebug.locator.ui;

import com.microsoft.playwright.Page;
import com.playwrightdebug.locator.model.LocatorInput;
import com.playwrightdebug.locator.model.LocatorTestResult;
import com.playwrightdebug.locator.model.PageInfo;
import com.playwrightdebug.locator.model.PlaywrightScriptContext;
import com.playwrightdebug.locator.service.DevToolsRecorderConverter;
import com.playwrightdebug.locator.service.LiveJavaRoleEnricher;
import com.playwrightdebug.locator.service.LocatorTestService;
import com.playwrightdebug.locator.service.PlaywrightCodegenService;
import com.playwrightdebug.locator.service.PlaywrightSession;
import com.playwrightdebug.locator.service.RecorderIdHint;
import com.playwrightdebug.locator.service.StrictModeLocatorReplacer;
import com.playwrightdebug.locator.service.StrictModeViolationParser;
import com.playwrightdebug.locator.service.StrictModeViolationParser.Candidate;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private final DevToolsRecorderConverter recorderConverter = new DevToolsRecorderConverter();
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
    private final JButton recorderGenerateButton = createActionButton("Playwrightコード生成", this::generateRecorderJunit);
    private final JButton recorderPlaywrightRunButton = createActionButton("Playwright 実行", this::runRecorderPlaywright);
    private final JButton recorderSnippetButton = createActionButton("スニペット", this::showRecorderSnippetMenu);
    private final JButton recorderCopyButton = createActionButton("コピー", this::copyRecorderJava);
    private final JLabel recorderRunErrorLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("未接続");

    private final JComboBox<PageInfo> pageComboBox = new JComboBox<>();
    private final JSpinner connectTimeoutSpinner = new JSpinner(new SpinnerNumberModel(3000, 500, 120000, 500));
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(3000, 500, 120000, 500));

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

    private final RSyntaxTextArea recorderJsonArea = CodeEditorFactory.createJsonEditor("");
    private final RSyntaxTextArea recorderJavaArea = CodeEditorFactory.createJavaEditor("");

    private final JTabbedPane workspaceTabs = new JTabbedPane();
    private final JTabbedPane resultTabs = new JTabbedPane();
    private final JTextArea logArea = new JTextArea();
    private final StrictModeCandidatePanel strictModePanel = new StrictModeCandidatePanel();
    private static final int RESULT_TAB_LOG = 0;
    private static final int RESULT_TAB_STRICT = 1;
    private static final Color STRICT_MODE_LINE_HIGHLIGHT = new Color(255, 192, 203);

    public MainFrame() {
        super("Playwright Debug Locator");
        locatorGroovyArea.setTabSize(2);
        testScriptArea.setTabSize(2);
        Font editorFont = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        logArea.setFont(editorFont);
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
        panel.add(new JLabel("接続 Timeout (ms)"), gc);

        gc.gridx = 3;
        gc.weightx = 0;
        panel.add(connectTimeoutSpinner, gc);

        gc.gridx = 4;
        gc.weightx = 0;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(connectButton);
        buttons.add(disconnectButton);
        buttons.add(refreshPagesButton);
        buttons.add(codegenButton);
        panel.add(buttons, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        gc.gridwidth = 5;
        statusLabel.putClientProperty("JComponent.sizeVariant", "small");
        panel.add(statusLabel, gc);

        return panel;
    }

    private JSplitPane createMainSplitPane() {
        JPanel workspace = new JPanel(new BorderLayout(8, 8));
        workspace.add(createSharedOptionsPanel(), BorderLayout.NORTH);
        workspaceTabs.addTab("Recorder (JSON) 変換", createRecorderConversionPanel());
        workspaceTabs.addTab("Locator 検証", createLocatorVerificationPanel());
        workspaceTabs.addTab("テスト実行", createTestExecutionPanel());
        workspaceTabs.setSelectedIndex(0);
        workspace.add(workspaceTabs, BorderLayout.CENTER);

        JComponent bottomPanel = createResultTabs();
        workspace.setMinimumSize(new Dimension(0, 180));
        bottomPanel.setMinimumSize(new Dimension(0, 120));

        JPanel topWrapper = new JPanel(new BorderLayout());
        topWrapper.add(workspace, BorderLayout.CENTER);
        topWrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, SPLIT_DIVIDER_MARGIN, 0));

        JPanel bottomWrapper = new JPanel(new BorderLayout());
        bottomWrapper.add(bottomPanel, BorderLayout.CENTER);
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
        panel.add(new JLabel("操作 Timeout (ms)"), gc);

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

    private JPanel createRecorderConversionPanel() {
        RTextScrollPane jsonScroll = CodeEditorFactory.wrapWithLineNumbers(recorderJsonArea);
        jsonScroll.setBorder(BorderFactory.createTitledBorder("DevTools Recorder JSON"));

        RTextScrollPane javaScroll = CodeEditorFactory.wrapWithLineNumbers(recorderJavaArea);
        javaScroll.setBorder(BorderFactory.createTitledBorder("Playwright JUnit (Java)"));

        JPanel jsonSide = createEditorWithActions(
                jsonScroll,
                createActionButton("JSON を開く", this::openRecorderJsonFile),
                recorderGenerateButton
        );
        JPanel javaSide = new JPanel(new BorderLayout(0, 0));
        javaSide.add(javaScroll, BorderLayout.CENTER);
        javaSide.add(createRecorderJavaActionPanel(), BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, jsonSide, javaSide);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setBorder(null);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createEditorWithActions(Component editor, JButton... buttons) {
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

    private JPanel createRecorderJavaActionPanel() {
        recorderRunErrorLabel.setFont(recorderRunErrorLabel.getFont().deriveFont(Font.BOLD));
        recorderRunErrorLabel.setText(" ");
        recorderRunErrorLabel.setVisible(false);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(Box.createVerticalStrut(ACTION_TOP_GAP));

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(recorderPlaywrightRunButton);
        left.add(recorderSnippetButton);
        left.add(recorderRunErrorLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(recorderCopyButton);

        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        panel.add(row);
        return panel;
    }

    private void showRecorderRunError() {
        recorderRunErrorLabel.setForeground(new Color(200, 0, 0));
        recorderRunErrorLabel.setText("エラーあり");
        recorderRunErrorLabel.setVisible(true);
    }

    private void showRecorderRunSuccess() {
        recorderRunErrorLabel.setForeground(new Color(0, 140, 40));
        recorderRunErrorLabel.setText("実行成功");
        recorderRunErrorLabel.setVisible(true);
    }

    private void clearRecorderRunError() {
        recorderRunErrorLabel.setText(" ");
        recorderRunErrorLabel.setVisible(false);
    }

    private void showRecorderSnippetMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (PlaywrightSnippetCatalog.Snippet snippet : PlaywrightSnippetCatalog.all()) {
            JMenuItem item = new JMenuItem(snippet.label());
            item.addActionListener(e -> PlaywrightSnippetCatalog.insert(recorderJavaArea, snippet));
            menu.add(item);
        }
        menu.show(recorderSnippetButton, 0, recorderSnippetButton.getHeight());
    }

    private JComponent createResultTabs() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane logScroll = new JScrollPane(logArea);
        JButton clearLogButton = createActionButton("クリア", this::clearResultLog);
        JPanel logButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        logButtons.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        logButtons.add(clearLogButton);

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(logScroll, BorderLayout.CENTER);
        logPanel.add(logButtons, BorderLayout.SOUTH);

        resultTabs.addTab("結果ログ", logPanel);
        resultTabs.addTab("strict mode 候補", strictModePanel);

        strictModePanel.setOnApply(this::applyStrictModeCandidate);
        strictModePanel.setOnClear(() -> resultTabs.setSelectedIndex(RESULT_TAB_LOG));
        resultTabs.addChangeListener(e -> {
            if (resultTabs.getSelectedIndex() == RESULT_TAB_STRICT) {
                strictModePanel.focusCombo();
            }
        });
        return resultTabs;
    }

    private void clearResultLog() {
        logArea.setText("");
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
        double connectTimeout = connectTimeoutMs();
        String endpoint = endpointField.getText().trim();
        appendLog("CDP 接続中: " + endpoint + " (timeout " + (int) connectTimeout + " ms)");
        uiExecutor.submit(() -> {
            try {
                PlaywrightSession.CdpPreflight preflight = session.probeCdpEndpoint(endpoint);
                SwingUtilities.invokeLater(() -> {
                    if (preflight.reachable()) {
                        appendLog("CDP 応答あり。対象ページ約 " + preflight.pageCount()
                                + " 件。Playwright 接続中...");
                    } else {
                        appendLog("CDP 事前チェック失敗: " + preflight.detail());
                    }
                });
                session.connect(endpoint, connectTimeout);
                SwingUtilities.invokeLater(() -> appendLog("CDP 接続完了。ページ一覧を取得中..."));
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

    private void openRecorderJsonFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Recorder JSON (*.json)", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            String json = Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
            recorderJsonArea.setText(json);
            recorderJsonArea.setCaretPosition(0);
            appendLog("Recorder JSON を読み込みました: " + chooser.getSelectedFile().getName());
        } catch (Exception ex) {
            appendLog("JSON 読み込み失敗: " + ex.getMessage());
        }
    }

    private void generateRecorderJunit() {
        String json = recorderJsonArea.getText();
        if (json == null || json.isBlank()) {
            appendLog("生成する Recorder JSON がありません");
            return;
        }
        try {
            DevToolsRecorderConverter.ConversionResult result = recorderConverter.convert(json);
            applyGeneratedRecorderResult(result);
        } catch (Exception ex) {
            appendLog("Playwrightコード生成失敗: " + ex.getMessage());
        }
    }

    private void applyGeneratedRecorderResult(DevToolsRecorderConverter.ConversionResult result) {
        clearStrictModeLineHighlights();
        recorderJavaArea.setText(result.javaSource());
        recorderJavaArea.setCaretPosition(0);
        appendLog("Playwrightコードを生成しました: " + result.className());
        if (!result.warnings().isEmpty()) {
            for (String warning : result.warnings()) {
                appendLog("警告: " + warning);
            }
        }
    }

    private void copyRecorderJava() {
        String text = recorderJavaArea.getText();
        if (text == null || text.isBlank()) {
            appendLog("コピーする生成コードがありません");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        appendLog("生成コードをクリップボードにコピーしました");
    }

    private void runRecorderPlaywright() {
        String javaSource = recorderJavaArea.getText();
        if (javaSource == null || javaSource.isBlank()) {
            appendLog("実行する Playwright コードがありません。先に Playwrightコード生成してください");
            return;
        }
        if (!session.isConnected()) {
            appendLog("先に CDP 接続してください");
            return;
        }
        PageInfo selected = (PageInfo) pageComboBox.getSelectedItem();
        if (selected == null) {
            appendLog("ページを選択してください");
            return;
        }

        double timeout = timeoutMs();
        int statementHint = Math.max(1, (int) javaSource.lines().filter(l -> {
            String t = l.trim();
            return !t.isEmpty() && !t.startsWith("//") && !t.startsWith("import ") && !t.startsWith("@")
                    && !t.startsWith("public ") && !t.startsWith("void ") && !t.equals("{") && !t.equals("}");
        }).count());
        // 複数ステップ全体の打ち切り（各操作の timeout とは別）
        long hardTimeoutMs = Math.max(5_000L, (long) timeout * statementHint + 3_000L);

        setBusy(true);
        clearRecorderRunError();
        clearStrictModeState();
        appendLog("Recorder Playwright を実行中... (hard timeout " + hardTimeoutMs + " ms)");
        uiExecutor.submit(() -> {
            try {
                EnrichAndRunOutcome outcome = session.run(() -> {
                    Page page = session.requirePage(selected);
                    page.setDefaultTimeout(timeout);
                    page.setDefaultNavigationTimeout(timeout);
                    page.context().setDefaultTimeout(timeout);
                    page.context().setDefaultNavigationTimeout(timeout);

                    LiveJavaRoleEnricher.Result enriched = LiveJavaRoleEnricher.enrich(page, javaSource);
                    String sourceToRun = enriched.source() == null ? javaSource : enriched.source();
                    PlaywrightScriptContext context = session.createContext(page);
                    LocatorTestResult result = locatorTestService.runRecorderJava(context, sourceToRun, timeout);
                    return new EnrichAndRunOutcome(result, enriched);
                }, hardTimeoutMs);
                SwingUtilities.invokeLater(() -> {
                    LiveJavaRoleEnricher.Result enriched = outcome.enriched();
                    if (enriched.replacements() > 0) {
                        int caret = recorderJavaArea.getCaretPosition();
                        recorderJavaArea.setText(enriched.source());
                        int max = recorderJavaArea.getDocument().getLength();
                        recorderJavaArea.setCaretPosition(Math.max(0, Math.min(caret, max)));
                        appendLog("実行時 ARIA 補完: " + enriched.replacements()
                                + " 箇所を @aria コメントから getByRole に更新");
                    }
                    if (enriched.notes() != null) {
                        for (String note : enriched.notes()) {
                            appendLog("  - " + note);
                        }
                    }
                    appendLog(outcome.result().asText());
                    setBusy(false);
                    if (outcome.result().isSuccess()) {
                        showRecorderRunSuccess();
                    } else {
                        showRecorderRunError();
                    }
                    offerStrictModeFixIfPresent(outcome.result().asText());
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(session.isConnected());
                    appendLog("実行失敗: " + ex.getMessage());
                    if (!session.isConnected()) {
                        pageComboBox.setModel(new DefaultComboBoxModel<>());
                    }
                    setBusy(false);
                    showRecorderRunError();
                    offerStrictModeFixIfPresent(ex.getMessage());
                });
            }
        });
    }

    private record EnrichAndRunOutcome(LocatorTestResult result, LiveJavaRoleEnricher.Result enriched) {
    }

    private void offerStrictModeFixIfPresent(String errorText) {
        var parsed = StrictModeViolationParser.parse(errorText);
        if (parsed.isEmpty()) {
            clearStrictModeState();
            return;
        }

        var violation = parsed.get();
        JTextComponent editor = activeScriptEditor();
        String source = editor == null ? "" : editor.getText();
        highlightStrictModeLines(editor, source, violation.failingLocator());

        var hintId = RecorderIdHint.findHintForFailingLocator(source, violation.failingLocator());
        var preferred = hintId.flatMap(id ->
                RecorderIdHint.findCandidateByElementId(violation.candidates(), id)).orElse(null);
        String hint = hintId.orElse(null);

        StringBuilder log = new StringBuilder("strict mode 違反の候補が ")
                .append(violation.candidates().size())
                .append(" 件あります。「strict mode 候補」タブで選択してください。");
        if (hintId.isPresent() && preferred != null) {
            log.append("（IDヒント #").append(hintId.get())
                    .append(" → 候補 ").append(preferred.index()).append(" を選択中）");
        } else if (hintId.isPresent()) {
            log.append("（IDヒント #").append(hintId.get()).append(" は候補に一致しませんでした）");
        }
        appendLog(log.toString());
        strictModePanel.showViolation(violation, preferred, hint);
        resultTabs.setSelectedIndex(RESULT_TAB_STRICT);
        strictModePanel.focusCombo();
    }

    private void highlightStrictModeLines(JTextComponent editor, String source, String failingLocator) {
        clearStrictModeLineHighlights();
        if (editor == null || failingLocator == null || failingLocator.isBlank()) {
            return;
        }

        List<Integer> lineIndexes = RecorderIdHint.findFailingLineIndexes(source, failingLocator);
        if (lineIndexes.isEmpty()) {
            appendLog("違反 locator に対応するコード行が見つかりませんでした: " + failingLocator);
            return;
        }

        if (editor instanceof RTextArea rTextArea) {
            for (int lineIndex : lineIndexes) {
                try {
                    rTextArea.addLineHighlight(lineIndex, STRICT_MODE_LINE_HIGHLIGHT);
                } catch (BadLocationException ignored) {
                    // ignore invalid line
                }
            }
            try {
                int first = lineIndexes.get(0);
                int offset = rTextArea.getLineStartOffset(first);
                rTextArea.setCaretPosition(offset);
                rTextArea.requestFocusInWindow();
            } catch (BadLocationException ignored) {
                // ignore
            }
        } else {
            // JTextArea fallback: select the first matching line.
            try {
                int first = lineIndexes.get(0);
                int start = editor.getDocument().getDefaultRootElement().getElement(first).getStartOffset();
                int end = editor.getDocument().getDefaultRootElement().getElement(first).getEndOffset();
                editor.select(start, Math.max(start, end - 1));
                editor.requestFocusInWindow();
            } catch (Exception ignored) {
                // ignore
            }
        }

        appendLog("違反コード行をハイライトしました: "
                + lineIndexes.stream().map(i -> String.valueOf(i + 1)).reduce((a, b) -> a + ", " + b).orElse("-"));
    }

    private void clearStrictModeLineHighlights() {
        recorderJavaArea.removeAllLineHighlights();
        recorderJsonArea.removeAllLineHighlights();
    }

    private void clearStrictModeState() {
        clearStrictModeLineHighlights();
        strictModePanel.clear();
    }

    private void applyStrictModeCandidate(Candidate candidate) {
        var violation = strictModePanel.currentViolation();
        if (violation == null) {
            appendLog("置換対象の strict mode 情報がありません");
            return;
        }
        JTextComponent editor = activeScriptEditor();
        if (editor == null) {
            appendLog("置換先エディタが見つかりません。選択式: " + candidate.pageExpression());
            return;
        }
        StrictModeLocatorReplacer.ReplaceResult replaced = StrictModeLocatorReplacer.replace(
                editor.getText(),
                violation.failingLocator(),
                candidate.pageExpression()
        );
        if (replaced.replacements() <= 0) {
            appendLog("自動置換できませんでした。選択式をクリップボードへコピーします: "
                    + candidate.pageExpression());
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(candidate.pageExpression()), null);
            return;
        }
        int caret = editor.getCaretPosition();
        editor.setText(replaced.text());
        clearStrictModeLineHighlights();
        int max = editor.getDocument().getLength();
        editor.setCaretPosition(Math.max(0, Math.min(caret, max)));
        appendLog("locator を " + replaced.replacements() + " 箇所置換しました → "
                + candidate.pageExpression());
    }

    private JTextComponent activeScriptEditor() {
        return switch (workspaceTabs.getSelectedIndex()) {
            case 0 -> recorderJavaArea;
            case 1 -> locatorGroovyArea;
            case 2 -> testScriptArea;
            default -> null;
        };
    }

    private double connectTimeoutMs() {
        return ((Number) connectTimeoutSpinner.getValue()).doubleValue();
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
                currentTestInput(),
                timeoutMs()
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
        double timeout = timeoutMs();
        // Playwright が navigation wait でタイムアウトしない場合があるため、UI 側で打ち切る
        long hardTimeoutMs = Math.max(1_000L, (long) timeout + 2_000L);
        uiExecutor.submit(() -> {
            try {
                LocatorTestResult result = session.run(() -> {
                    Page page = session.requirePage(selected);
                    page.setDefaultTimeout(timeout);
                    page.setDefaultNavigationTimeout(timeout);
                    page.context().setDefaultTimeout(timeout);
                    page.context().setDefaultNavigationTimeout(timeout);
                    PlaywrightScriptContext context = session.createContext(page);
                    return action.run(context);
                }, hardTimeoutMs);
                SwingUtilities.invokeLater(() -> {
                    appendLog(result.asText());
                    setBusy(false);
                    offerStrictModeFixIfPresent(result.asText());
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(session.isConnected());
                    appendLog("実行失敗: " + ex.getMessage());
                    if (!session.isConnected()) {
                        pageComboBox.setModel(new DefaultComboBoxModel<>());
                    }
                    setBusy(false);
                    offerStrictModeFixIfPresent(ex.getMessage());
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
        // Clear first so the closed combo repaints when index stays 0 but title/url changed.
        pageComboBox.setModel(new DefaultComboBoxModel<>());
        pageComboBox.setModel(model);

        int select = -1;
        if (previous != null) {
            for (int i = 0; i < model.getSize(); i++) {
                PageInfo candidate = model.getElementAt(i);
                if (candidate.id() != null && !candidate.id().isBlank()
                        && candidate.id().equals(previous.id())) {
                    select = i;
                    break;
                }
            }
            if (select < 0) {
                int urlOnlyMatch = -1;
                for (int i = 0; i < model.getSize(); i++) {
                    PageInfo candidate = model.getElementAt(i);
                    if (!candidate.url().equals(previous.url())) {
                        continue;
                    }
                    if (candidate.title().equals(previous.title())) {
                        select = i;
                        break;
                    }
                    if (urlOnlyMatch < 0) {
                        urlOnlyMatch = i;
                    }
                }
                if (select < 0) {
                    select = urlOnlyMatch;
                }
            }
        }
        if (select < 0 && model.getSize() > 0) {
            select = 0;
        }
        if (select >= 0) {
            pageComboBox.setSelectedIndex(-1);
            pageComboBox.setSelectedIndex(select);
        }
        pageComboBox.revalidate();
        pageComboBox.repaint();
    }

    private void updateConnectionState(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
        refreshPagesButton.setEnabled(connected);
        recorderGenerateButton.setEnabled(true);
        recorderPlaywrightRunButton.setEnabled(connected);
        pageComboBox.setEnabled(connected);
        endpointField.setEnabled(!connected);
        connectTimeoutSpinner.setEnabled(!connected);
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
        recorderGenerateButton.setEnabled(!busy);
        recorderPlaywrightRunButton.setEnabled(!busy && session.isConnected());
    }
}
