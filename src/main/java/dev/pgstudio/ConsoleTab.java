package dev.pgstudio;

import com.formdev.flatlaf.FlatLaf;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.postgresql.util.PGobject;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** SQL 콘솔: 에디터 + 실행 + 결과 탭. 콘솔마다 독립된 JDBC 연결(트랜잭션)을 가진다. */
final class ConsoleTab extends JPanel {
    private static final int MOD = Shortcuts.mod();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private volatile Session session;
    private final String schema;
    private final RSyntaxTextArea editor = new RSyntaxTextArea(14, 80);
    private final JTabbedPane results = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JComboBox<String> txMode = new JComboBox<>(new String[]{"Auto commit", "Manual commit"});
    private final JSpinner maxRows = new JSpinner(new SpinnerNumberModel(500, 1, 1_000_000, 100));
    private final JButton run = new JButton("▶ 실행"), runAll = new JButton("▶▶ 스크립트"),
            explain = new JButton("Explain"), analyze = new JButton("Analyze"), cancel = new JButton("■ 취소"),
            commit = new JButton("Commit"), rollback = new JButton("Rollback");
    private final DefaultCompletionProvider provider = new DefaultCompletionProvider() {
        @Override protected boolean isValidChar(char ch) { return super.isValidChar(ch) || ch == '.'; }
    };
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "console-exec");
        t.setDaemon(true);
        return t;
    });

    private volatile Connection conn;
    private volatile Statement running;
    private volatile boolean dirty;
    private boolean syncingTx;   // 그리드 적용이 콤보를 프로그램적으로 바꿀 때 리스너 무시
    private File file;
    Consumer<String> onTitle = s -> {};
    /** DB 연결이 없을 때 사용자가 연결을 고르도록 하는 콜백(AppFrame 이 설정). */
    java.util.function.Supplier<Session> sessionChooser = () -> null;

    ConsoleTab(Session session, String schema, String initialSql) {
        super(new BorderLayout());
        this.session = session;
        this.schema = schema;

        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        editor.setCodeFoldingEnabled(true);
        editor.setTabSize(4);
        editor.setTabsEmulated(true);
        editor.setMarkOccurrences(true);
        editor.setAntiAliasingEnabled(true);
        editor.setText(initialSql == null ? "" : initialSql);
        editor.discardAllEdits();
        applyEditorTheme();

        AutoCompletion ac = new AutoCompletion(provider);
        ac.setAutoActivationEnabled(true);
        ac.setAutoActivationDelay(250);
        ac.setShowDescWindow(true);
        ac.install(editor);
        refillCompletions();
        if (session != null) session.ready.thenRun(() -> SwingUtilities.invokeLater(this::refillCompletions));

        output.setEditable(false);
        output.setFont(Fonts.mono(12));
        results.addTab("Output", new JScrollPane(output));

        RTextScrollPane editorScroll = new RTextScrollPane(editor);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScroll, results);
        split.setResizeWeight(0.45);

        add(buildToolbar(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        updateConnectionStatus();
        bindKeys();
        setBusy(false);
        updateTxButtons();
    }

    // ---- UI 구성 ----

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        run.setToolTipText("선택 영역 또는 커서 위치의 문장 실행 (Cmd/Ctrl+Enter)");
        runAll.setToolTipText("전체 스크립트 실행 (Cmd/Ctrl+Shift+Enter)");
        explain.setToolTipText("실행 계획 (EXPLAIN)");
        analyze.setToolTipText("실행 계획 + 실제 실행 (EXPLAIN ANALYZE, BUFFERS)");
        run.addActionListener(e -> runCurrent());
        runAll.addActionListener(e -> runScript());
        explain.addActionListener(e -> explain(false));
        analyze.addActionListener(e -> explain(true));
        cancel.addActionListener(e -> cancelRunning());
        commit.addActionListener(e -> finishTx(true));
        rollback.addActionListener(e -> finishTx(false));
        txMode.addActionListener(e -> changeTxMode());
        JButton format = new JButton("Format");
        format.setToolTipText("SQL 정리 (Cmd/Ctrl+Alt+L)");
        format.addActionListener(e -> format());
        JButton connect = new JButton("연결 선택");
        connect.setToolTipText("이 콘솔에서 사용할 DB 연결을 선택/변경");
        connect.addActionListener(e -> attach(sessionChooser.get()));
        JButton history = new JButton("이력");
        history.addActionListener(e -> HistoryDialog.show(SwingUtilities.getWindowAncestor(this), this::insertText));
        JButton open = new JButton("열기");
        open.addActionListener(e -> openFile());
        JButton save = new JButton("저장");
        save.addActionListener(e -> save());
        for (Component c : new Component[]{run, runAll, explain, analyze, cancel}) bar.add(c);
        bar.addSeparator();
        for (Component c : new Component[]{txMode, commit, rollback}) bar.add(c);
        bar.addSeparator();
        bar.add(new JLabel(" 최대 행 "));
        maxRows.setMaximumSize(new Dimension(90, 28));
        bar.add(maxRows);
        bar.addSeparator();
        for (Component c : new Component[]{connect, format, history, open, save}) bar.add(c);
        return bar;
    }

    private void bindKeys() {
        bind(KeyEvent.VK_ENTER, MOD, "run", this::runCurrent);
        bind(KeyEvent.VK_ENTER, MOD | InputEvent.SHIFT_DOWN_MASK, "runAll", this::runScript);
        bind(KeyEvent.VK_SLASH, MOD, "comment", this::toggleComment);
        bind(KeyEvent.VK_D, MOD, "dupLine", this::duplicateLine);
        bind(KeyEvent.VK_L, MOD | InputEvent.ALT_DOWN_MASK, "format", this::format);
        bind(KeyEvent.VK_F, MOD, "find", () -> SearchSupport.show(editor));
        bind(KeyEvent.VK_EQUALS, MOD, "zoomIn", () -> zoom(1));
        bind(KeyEvent.VK_MINUS, MOD, "zoomOut", () -> zoom(-1));
        bind(KeyEvent.VK_U, MOD | InputEvent.SHIFT_DOWN_MASK, "upper", this::upperSelection);
    }

    private void bind(int key, int mods, String name, Runnable r) {
        editor.getInputMap().put(KeyStroke.getKeyStroke(key, mods), name);
        editor.getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    void applyEditorTheme() {
        try (InputStream in = getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/"
                + (FlatLaf.isLafDark() ? "dark" : "idea") + ".xml")) {
            if (in != null) Theme.load(in).apply(editor);
        } catch (IOException ignored) {}
        editor.setFont(Fonts.mono(Main.fontSize()));
    }

    private void zoom(int d) {
        Main.setFontSize(Main.fontSize() + d);
        editor.setFont(Fonts.mono(Main.fontSize()));
    }

    // ---- 자동완성 ----

    private void updateConnectionStatus() {
        Session s = session;
        setStatus(s == null ? "연결 안 됨 — 실행하면 DB를 선택합니다 (에디터/포맷/파일 기능은 연결 없이 사용 가능)"
                : s.profile.name() + (schema != null ? " · " + schema : "") + (s.profile.readOnly() ? " · 읽기 전용" : ""));
    }

    /** 이 콘솔의 DB 연결을 지정/교체한다. 이전 연결의 미커밋 트랜잭션은 롤백된다. */
    void attach(Session s) {
        if (s == null || s == session) return;
        Connection old = conn;
        conn = null;
        dirty = false;
        if (old != null) exec.submit(() -> {
            try { if (!old.isClosed() && !old.getAutoCommit()) old.rollback(); old.close(); } catch (SQLException ignored) {}
        });
        session = s;
        refillCompletions();
        s.ready.thenRun(() -> SwingUtilities.invokeLater(this::refillCompletions));
        updateConnectionStatus();
        updateTxButtons();
    }

    private void refillCompletions() {
        Session sess = session;
        List<Completion> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String k : SqlFormatter.KEYWORDS) if (seen.add(k)) list.add(new BasicCompletion(provider, k.toUpperCase(), "keyword"));
        if (sess != null) for (var se : sess.completion.entrySet()) {
            String sc = se.getKey();
            if (seen.add(sc)) list.add(new BasicCompletion(provider, sc, "schema"));
            for (var te : se.getValue().entrySet()) {
                String tb = te.getKey();
                if (seen.add(tb)) list.add(new BasicCompletion(provider, tb, "table/view in " + sc));
                String q = sc + "." + tb;
                if (seen.add(q)) list.add(new BasicCompletion(provider, q, "table/view"));
                for (String col : te.getValue()) {
                    if (seen.add(col)) list.add(new BasicCompletion(provider, col, "column"));
                    String qc = tb + "." + col;
                    if (seen.add(qc)) list.add(new BasicCompletion(provider, qc, "column of " + tb));
                }
            }
        }
        provider.clear();
        provider.addCompletions(list);
    }

    // ---- 실행 ----

    private void runCurrent() {
        String sel = editor.getSelectedText();
        if (sel != null && !sel.isBlank()) { execute(splitTexts(sel), false, false); return; }
        SqlSplitter.Range r = SqlSplitter.statementAt(editor.getText(), editor.getCaretPosition());
        if (r == null) { setStatus("실행할 SQL이 없습니다."); return; }
        // 실행되는 문장을 에디터에서 잠깐 표시
        editor.select(r.start(), r.end());
        String text = r.text(editor.getText());
        editor.setCaretPosition(Math.min(r.end(), editor.getText().length()));
        execute(List.of(text), false, false);
    }

    private void runScript() {
        String sel = editor.getSelectedText();
        execute(splitTexts(sel != null && !sel.isBlank() ? sel : editor.getText()), false, false);
    }

    private void explain(boolean analyzeRun) {
        String sel = editor.getSelectedText();
        String text;
        if (sel != null && !sel.isBlank()) text = sel.trim();
        else {
            SqlSplitter.Range r = SqlSplitter.statementAt(editor.getText(), editor.getCaretPosition());
            if (r == null) return;
            text = r.text(editor.getText());
        }
        execute(List.of(text), true, analyzeRun);
    }

    private static List<String> splitTexts(String s) {
        List<String> out = new ArrayList<>();
        for (SqlSplitter.Range r : SqlSplitter.split(s)) out.add(r.text(s));
        return out;
    }

    private void execute(List<String> stmts, boolean explainMode, boolean analyzeRun) {
        if (stmts.isEmpty()) return;
        if (session == null) attach(sessionChooser.get());
        if (session == null) { setStatus("실행하려면 DB 연결이 필요합니다."); return; }
        if (!session.profile.readOnly()) {
            for (String s : stmts) {
                String why = SqlSplitter.danger(s, explainMode && analyzeRun);
                if (why != null) {
                    String preview = s.length() > 300 ? s.substring(0, 300) + "…" : s;
                    if (JOptionPane.showConfirmDialog(this, why + "\n\n" + preview + "\n\n실행할까요?", "실행 확인",
                            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
                    break;
                }
            }
        }
        for (int i = 1; i < results.getTabCount(); i++)
            if (results.getComponentAt(i) instanceof ResultPanel rp && rp.hasPending()) {
                if (JOptionPane.showConfirmDialog(this, "적용하지 않은 그리드 변경이 있습니다. 새로 실행하면 사라집니다.\n계속할까요?",
                        "그리드 변경 버리기", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
                break;
            }
        while (results.getTabCount() > 1) results.removeTabAt(1);
        setBusy(true);
        setStatus("실행 중...");
        final int limit = (Integer) maxRows.getValue();
        final boolean manual = txMode.getSelectedIndex() == 1;
        exec.submit(() -> {
            List<ResultData> datas = new ArrayList<>();
            StringBuilder log = new StringBuilder();
            long total = System.nanoTime();
            int failed = 0;
            try {
                ensureConnection(manual);
                for (String raw : stmts) {
                    String sql = explainMode ? "EXPLAIN " + (analyzeRun ? "(ANALYZE, BUFFERS) " : "") + raw : raw;
                    long t0 = System.nanoTime();
                    boolean ok = true;
                    String msg = "";
                    try (Statement st = conn.createStatement()) {
                        running = st;
                        st.setMaxRows(limit);
                        boolean has = st.execute(sql);
                        StringBuilder m = new StringBuilder();
                        while (true) {
                            if (has) {
                                try (ResultSet rs = st.getResultSet()) {
                                    ResultData d = read(rs, limit, raw, t0);
                                    if (!explainMode && raw.stripLeading().toLowerCase().matches("(?s)^(select|with|table)\\b.*")) {
                                        try { d.edit = GridEdit.detect(rs, session.meta); } catch (Exception ignored) {}
                                    }
                                    datas.add(d);
                                    m.append(d.rows.size()).append(" rows");
                                }
                            } else {
                                int c = st.getUpdateCount();
                                if (c == -1) break;
                                m.append(c).append(" rows affected");
                            }
                            m.append("; ");
                            has = st.getMoreResults();
                        }
                        for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning())
                            log.append("  NOTICE: ").append(w.getMessage()).append('\n');
                        msg = m.toString().replaceAll("; $", "");
                        if (manual) dirty = true;
                    } catch (SQLException e) {
                        ok = false;
                        failed++;
                        msg = e.getMessage();
                        if (manual) dirty = true;
                    } finally {
                        running = null;
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    History.add(session.profile.name(), ms, ok, raw);
                    log.append('[').append(LocalTime.now().format(TIME)).append("] ").append(ok ? "✔ " : "✖ ")
                            .append(msg).append(" · ").append(ms).append(" ms · ").append(firstLine(raw)).append('\n');
                    if (!ok) break;
                }
            } catch (SQLException e) {
                failed++;
                log.append("[").append(LocalTime.now().format(TIME)).append("] ✖ 연결 실패: ").append(e.getMessage()).append('\n');
            }
            long totalMs = (System.nanoTime() - total) / 1_000_000;
            final int f = failed;
            SwingUtilities.invokeLater(() -> {
                output.append(log.toString());
                output.setCaretPosition(output.getDocument().getLength());
                int n = 1;
                for (ResultData d : datas) {
                    results.addTab("Result " + n++, new ResultPanel(d, gridEditor));
                }
                results.setSelectedIndex(datas.isEmpty() || f > 0 ? 0 : 1);
                setBusy(false);
                setStatus((f > 0 ? "오류 발생 · " : "완료 · ") + totalMs + " ms");
                updateTxButtons();
            });
        });
    }

    static ResultData read(ResultSet rs, int limit, String sql, long t0) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(), types = new ArrayList<>();
        for (int i = 1; i <= n; i++) { cols.add(md.getColumnLabel(i)); types.add(md.getColumnTypeName(i)); }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next() && rows.size() < limit) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) row[i] = normalize(rs.getObject(i + 1));
            rows.add(row);
        }
        return new ResultData(cols, types, rows, rows.size() >= limit, (System.nanoTime() - t0) / 1_000_000, sql);
    }

    private static Object normalize(Object o) {
        if (o == null) return null;
        if (o instanceof PGobject || o instanceof java.sql.Array || o instanceof SQLXML) return o.toString();
        if (o instanceof byte[] b) {
            StringBuilder sb = new StringBuilder("\\x");
            for (int i = 0; i < Math.min(b.length, 64); i++) sb.append(String.format("%02x", b[i]));
            return b.length > 64 ? sb + "… (" + b.length + " bytes)" : sb.toString();
        }
        return o;
    }

    private static String firstLine(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    private void ensureConnection(boolean manual) throws SQLException {
        if (conn != null && !conn.isClosed()) return;
        Session s = session;
        if (s == null) throw new SQLException("DB 연결이 선택되지 않았습니다.");
        Connection c = s.newConnection();
        if (schema != null) c.setSchema(schema);
        c.setAutoCommit(!manual);
        dirty = false;
        conn = c;
    }

    private final ResultPanel.GridEditor gridEditor = new ResultPanel.GridEditor() {
        @Override public boolean canEdit() { Session s = session; return s != null && !s.profile.readOnly(); }
        @Override public void apply(ResultData d, List<GridEdit.Op> ops, Runnable onSuccess) { applyGrid(d, ops, onSuccess); }
    };

    /**
     * 그리드 변경 적용. 콘솔 모드와 무관하게 항상 수동 커밋: 트랜잭션을 열어 둔 채 실행하고 콘솔을 Manual 로 전환한다.
     * 한 번의 적용은 세이브포인트로 묶여, 중간에 실패하면 이번 적용분만 롤백된다(이전 미커밋 작업은 유지).
     */
    private void applyGrid(ResultData d, List<GridEdit.Op> ops, Runnable onSuccess) {
        GridEdit.Target t = d.edit;
        List<GridEdit.Prepared> prepared = new ArrayList<>();
        StringBuilder preview = new StringBuilder();
        for (GridEdit.Op op : ops) {
            GridEdit.Prepared p = GridEdit.prepare(t, op);
            prepared.add(p);
            preview.append(p.preview()).append(";\n");
        }
        JTextArea ta = new JTextArea(preview.toString(), 12, 80);
        ta.setFont(Fonts.mono(12));
        ta.setEditable(false);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>다음 " + prepared.size() + "개 문장을 <b>수동 커밋 트랜잭션</b>으로 실행합니다.<br>"
                + "적용 후 <b>Commit</b>을 눌러야 확정되며, <b>Rollback</b>으로 취소할 수 있습니다.</html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(ta));
        if (JOptionPane.showConfirmDialog(this, panel, "그리드 변경 적용", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        setBusy(true);
        exec.submit(() -> {
            long t0 = System.nanoTime();
            String err = null;
            try {
                ensureConnection(true);
                if (conn.getAutoCommit()) conn.setAutoCommit(false);
                Savepoint sp = conn.setSavepoint();
                try {
                    for (int i = 0; i < prepared.size(); i++) {
                        int n = GridEdit.execute(conn, prepared.get(i));
                        if (ops.get(i).kind() != GridEdit.Kind.INSERT && n != 1)
                            throw new SQLException("영향 행 수가 " + n + "입니다(예상 1) — 다른 곳에서 먼저 변경/삭제됐을 수 있습니다: " + prepared.get(i).preview());
                    }
                    conn.releaseSavepoint(sp);
                } catch (SQLException e) {
                    try { conn.rollback(sp); } catch (SQLException ignored) {}
                    throw e;
                }
                dirty = true;
            } catch (SQLException e) {
                err = e.getMessage();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            final String error = err;
            Session s = session;
            for (GridEdit.Prepared p : prepared) History.add(s.profile.name(), ms, error == null, p.preview());
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                String now = LocalTime.now().format(TIME);
                if (error == null) {
                    syncingTx = true;
                    txMode.setSelectedIndex(1);
                    syncingTx = false;
                    onSuccess.run();
                    output.append("[" + now + "] ✔ 그리드 변경 " + prepared.size() + "건 적용 (커밋 대기) · " + ms + " ms\n");
                    updateTxButtons();
                    setStatus("⚠ 그리드 변경 " + prepared.size() + "건이 적용됨 — Commit으로 확정하거나 Rollback으로 취소하세요");
                } else {
                    output.append("[" + now + "] ✖ 그리드 변경 실패(이번 적용분 롤백됨): " + error + "\n");
                    setStatus("그리드 변경 실패");
                    JOptionPane.showMessageDialog(this, error, "적용 실패", JOptionPane.ERROR_MESSAGE);
                }
            });
        });
    }

    private void cancelRunning() {
        Statement s = running;
        if (s != null) {
            try { s.cancel(); } catch (SQLException ignored) {}
        }
    }

    // ---- 트랜잭션 ----

    private void changeTxMode() {
        if (syncingTx) return;
        boolean manual = txMode.getSelectedIndex() == 1;
        exec.submit(() -> {
            try {
                if (conn != null && !conn.isClosed()) { conn.setAutoCommit(!manual); if (!manual) dirty = false; }
            } catch (SQLException e) {
                SwingUtilities.invokeLater(() -> setStatus("모드 전환 실패: " + e.getMessage()));
            }
            SwingUtilities.invokeLater(this::updateTxButtons);
        });
    }

    private void finishTx(boolean doCommit) {
        exec.submit(() -> {
            String msg;
            try {
                if (conn != null && !conn.isClosed() && !conn.getAutoCommit()) {
                    if (doCommit) conn.commit(); else conn.rollback();
                    dirty = false;
                    msg = doCommit ? "커밋 완료" : "롤백 완료";
                } else msg = "열린 트랜잭션이 없습니다.";
            } catch (SQLException e) { msg = "실패: " + e.getMessage(); }
            String m = msg;
            SwingUtilities.invokeLater(() -> {
                output.append("[" + LocalTime.now().format(TIME) + "] " + m + "\n");
                setStatus(m);
                updateTxButtons();
            });
        });
    }

    private void updateTxButtons() {
        boolean manual = txMode.getSelectedIndex() == 1;
        commit.setEnabled(manual);
        rollback.setEnabled(manual);
        if (manual && dirty) setStatus("⚠ 커밋되지 않은 트랜잭션이 있습니다.");
    }

    boolean hasPendingTx() { return dirty; }

    /** 탭을 닫을 때: 미커밋 변경은 롤백되고 연결은 닫힌다. */
    void dispose() {
        cancelRunning();
        exec.submit(() -> {
            try { if (conn != null) { if (!conn.isClosed() && !conn.getAutoCommit()) conn.rollback(); conn.close(); } }
            catch (SQLException ignored) {}
        });
        exec.shutdown();
    }

    private void setBusy(boolean busy) {
        for (JButton b : new JButton[]{run, runAll, explain, analyze}) b.setEnabled(!busy);
        cancel.setEnabled(busy);
    }

    private void setStatus(String s) { status.setText(s); }

    // ---- 에디터 편의 기능 ----

    Session session() { return session; }

    /** AI 컨텍스트용: 선택 영역이 있으면 그것, 없으면 에디터 전체. */
    String contextSql() {
        String sel = editor.getSelectedText();
        return sel != null && !sel.isBlank() ? sel : editor.getText();
    }

    /** 선택 영역(없으면 전체)을 교체한다. 하나의 편집으로 기록되어 Undo 가능. */
    void replaceSql(String sql) {
        if (editor.getSelectedText() == null) editor.selectAll();
        editor.replaceSelection(sql);
        editor.requestFocusInWindow();
    }

    /** 외부(AI 패널 등)에서 만든 읽기 전용 결과를 이 콘솔의 결과 탭으로 표시한다. */
    void showResult(ResultData d, String label) {
        results.addTab(label, new ResultPanel(d, null));
        results.setSelectedIndex(results.getTabCount() - 1);
        output.append("[" + LocalTime.now().format(TIME) + "] ✔ " + label + ": " + d.rows.size() + " rows · " + d.millis + " ms · " + firstLine(d.sql) + "\n");
    }

    void insertText(String s) { editor.replaceSelection(s); editor.requestFocusInWindow(); }

    String text() { return editor.getText(); }

    private void format() {
        String sel = editor.getSelectedText();
        if (sel != null && !sel.isEmpty()) editor.replaceSelection(SqlFormatter.format(sel));
        else {
            int caret = editor.getCaretPosition();
            editor.setText(SqlFormatter.format(editor.getText()));
            editor.setCaretPosition(Math.min(caret, editor.getText().length()));
        }
    }

    private void upperSelection() {
        String sel = editor.getSelectedText();
        if (sel != null) editor.replaceSelection(sel.toUpperCase());
    }

    private void toggleComment() {
        try {
            int s = editor.getLineOfOffset(editor.getSelectionStart());
            int e = editor.getLineOfOffset(Math.max(editor.getSelectionStart(), editor.getSelectionEnd() - (editor.getSelectionEnd() > editor.getSelectionStart()
                    && editor.getSelectionEnd() == editor.getLineStartOffset(editor.getLineOfOffset(editor.getSelectionEnd())) ? 1 : 0)));
            boolean allCommented = true;
            for (int l = s; l <= e; l++) {
                String line = lineText(l);
                if (!line.isBlank() && !line.stripLeading().startsWith("--")) { allCommented = false; break; }
            }
            StringBuilder sb = new StringBuilder();
            for (int l = s; l <= e; l++) {
                String line = lineText(l);
                if (allCommented) line = line.replaceFirst("--\\s?", "");
                else if (!line.isBlank()) line = "-- " + line;
                sb.append(line).append(l < e ? "\n" : "");
            }
            int from = editor.getLineStartOffset(s), to = editor.getLineEndOffset(e);
            boolean endsNl = to > from && editor.getText(to - 1, 1).equals("\n");
            editor.replaceRange(sb + (endsNl ? "\n" : ""), from, to);
            editor.select(from, from + sb.length());
        } catch (BadLocationException ignored) {}
    }

    private String lineText(int l) throws BadLocationException {
        int a = editor.getLineStartOffset(l), b = editor.getLineEndOffset(l);
        String t = editor.getText(a, b - a);
        return t.endsWith("\n") ? t.substring(0, t.length() - 1) : t;
    }

    private void duplicateLine() {
        try {
            int sel0 = editor.getSelectionStart(), sel1 = editor.getSelectionEnd();
            if (sel1 > sel0) {
                editor.insert(editor.getSelectedText(), sel1);
                return;
            }
            int l = editor.getLineOfOffset(editor.getCaretPosition());
            int end = editor.getLineEndOffset(l);
            String line = lineText(l);
            editor.insert((end > 0 && editor.getText(end - 1, 1).equals("\n") ? "" : "\n") + line + (editor.getText(end - 1, 1).equals("\n") ? "\n" : ""), end);
        } catch (BadLocationException ignored) {}
    }

    // ---- 파일 ----

    private void openFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            editor.setText(Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8));
            editor.discardAllEdits();
            file = fc.getSelectedFile();
            onTitle.accept(file.getName());
        } catch (IOException e) { JOptionPane.showMessageDialog(this, "열기 실패: " + e.getMessage()); }
    }

    void openFile(File f) {
        try {
            editor.setText(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            editor.discardAllEdits();
            file = f;
            onTitle.accept(f.getName());
        } catch (IOException e) { JOptionPane.showMessageDialog(this, "열기 실패: " + e.getMessage()); }
    }

    void save() {
        if (file == null) {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("query.sql"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            file = fc.getSelectedFile();
        }
        try {
            Files.writeString(file.toPath(), editor.getText(), StandardCharsets.UTF_8);
            onTitle.accept(file.getName());
            setStatus("저장됨: " + file.getAbsolutePath());
        } catch (IOException e) { JOptionPane.showMessageDialog(this, "저장 실패: " + e.getMessage()); }
    }

    /** 첫 실행(initialSql 이 있고 run=true)을 위해 외부에서 호출. */
    void runNow() { runScript(); }
}
