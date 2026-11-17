package dev.pgstudio;

import com.formdev.flatlaf.FlatLaf;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 우측 Claude 패널: 로컬 claude CLI 로 SQL 작성을 돕고, 제안된 SELECT 를 읽기 전용 연결로 실행한다.
 * 전송되는 것은 질문과(선택 시) 테이블/컬럼 이름뿐이며, 쿼리 결과 데이터는 Claude 로 보내지 않는다.
 */
final class AiPanel extends JPanel {
    interface Host {
        Session currentSession();
        void insertIntoEditor(String sql);
        /** 현재 콘솔(없으면 새 콘솔) 에디터 끝에 붙인다. */
        void appendToEditor(String sql);
        /** 현재 콘솔 에디터의 선택 영역(없으면 전체) 텍스트. 콘솔이 없으면 null. */
        String editorText();
        /** 선택 영역이 있으면 선택 영역, 없으면 에디터 전체를 교체한다(에디터의 Undo 로 되돌릴 수 있음). */
        void replaceEditor(String sql);
        void showResult(Session s, ResultData d, String label);
        void closePanel();
    }

    private static final int MAX_HISTORY = 6, MAX_HISTORY_CHARS = 3000, SCHEMA_CHARS = 60000, EDITOR_CHARS = 8000, ROW_LIMIT = 500;
    private static boolean consented;   // 앱 실행 중 첫 전송 때 한 번 확인

    private final Host host;
    private final ChatList chat = new ChatList();
    private final JScrollPane scroll = new JScrollPane(chat);
    private final JTextArea input = new JTextArea(3, 20);
    private final JButton send = new JButton("전송"), stop = new JButton("중지");
    private final JCheckBox schemaBox = new JCheckBox("스키마(테이블/컬럼명) 포함", true);
    private final JCheckBox editorBox = new JCheckBox("에디터 SQL 포함 (선택 영역 우선)", true);
    private final JCheckBox autoBox = new JCheckBox("답변 쿼리를 콘솔에 자동 입력 · SELECT는 자동 실행", Main.aiAutoRun());
    private final JLabel target = new JLabel(" ");
    private final List<String[]> history = new ArrayList<>();
    private final List<RSyntaxTextArea> codeAreas = new ArrayList<>();
    private volatile ClaudeCli.Handle current;

    AiPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, UIManager.getColor("Component.borderColor")));

        JLabel title = new JLabel("  ✦ Claude · SQL 도우미");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JButton reset = new JButton("새 대화"), close = new JButton("×");
        reset.addActionListener(e -> resetChat());
        close.setToolTipText("패널 닫기");
        close.addActionListener(e -> host.closePanel());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        btns.add(reset);
        btns.add(close);
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.WEST);
        header.add(btns, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.putClientProperty("JTextField.placeholderText", "질문 입력 · Enter 전송 · Shift+Enter 줄바꿈");
        int mod = Shortcuts.mod();
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mod), "send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction);
        input.getActionMap().put("send", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { send(); }
        });
        send.addActionListener(e -> send());
        stop.addActionListener(e -> { ClaudeCli.Handle h = current; if (h != null) h.cancel(); });
        stop.setEnabled(false);

        JLabel note = new JLabel("<html><font color='gray'>질문·(선택한) 스키마 이름·에디터 SQL만 Claude로 전송 · 쿼리 결과 데이터는 전송 안 함 · SELECT만 실행</font></html>");
        note.setFont(note.getFont().deriveFont(11f));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        row.add(stop);
        row.add(send);
        JPanel south = new JPanel(new BorderLayout(0, 2));
        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(note);
        top.add(schemaBox);
        top.add(editorBox);
        top.add(autoBox);
        autoBox.addActionListener(e -> Main.setAiAutoRun(autoBox.isSelected()));
        top.add(target);
        south.add(top, BorderLayout.NORTH);
        south.add(new JScrollPane(input), BorderLayout.CENTER);
        south.add(row, BorderLayout.SOUTH);
        south.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        add(south, BorderLayout.SOUTH);

        showWelcome();
        updateTarget();
    }

    void refreshTheme() {
        for (RSyntaxTextArea a : codeAreas) applyTheme(a);
    }

    private static void applyTheme(RSyntaxTextArea a) {
        try (InputStream in = AiPanel.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/"
                + (FlatLaf.isLafDark() ? "dark" : "idea") + ".xml")) {
            if (in != null) Theme.load(in).apply(a);
        } catch (IOException ignored) {}
        a.setFont(Fonts.mono(12));
    }

    void focusInput() { input.requestFocusInWindow(); }

    // ---- 대화 ----

    private void showWelcome() {
        addText("Claude", "자연어로 질문하면 PostgreSQL 쿼리를 작성해 줍니다. 제안된 SELECT는 [실행] 버튼으로 읽기 전용 연결에서 바로 실행하고, "
                + "[삽입]/[에디터 교체]로 콘솔 에디터에 반영할 수 있습니다. 에디터의 현재 SQL(선택 영역 우선)도 함께 보고 답합니다. INSERT/UPDATE/DELETE/DDL은 실행되지 않고 직접 검토 후 실행해야 합니다.\n\n"
                + "고객정보·주민번호·계좌/카드번호·비밀번호는 입력하지 마세요.", false);
    }

    private void resetChat() {
        ClaudeCli.Handle h = current;
        if (h != null) h.cancel();
        history.clear();
        codeAreas.clear();
        chat.removeAll();
        showWelcome();
        chat.revalidate();
        chat.repaint();
    }

    private void updateTarget() {
        Session s = host.currentSession();
        target.setText(s == null ? "대상 DB: 없음 (연결 후 스키마 사용 가능)" : "대상 DB: " + s.profile.name());
        target.setFont(target.getFont().deriveFont(11f));
    }

    private void send() {
        String q = input.getText().strip();
        if (q.isEmpty() || current != null) return;
        String sensitive = SensitiveData.find(q);
        if (sensitive != null) {
            JOptionPane.showMessageDialog(this, sensitive + "이(가) 포함되어 있어 전송하지 않았습니다.\n"
                    + "해당 부분을 지우거나 가명/마스킹 처리한 뒤 다시 보내주세요.", "민감정보 감지", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String editorSql = null;
        if (editorBox.isSelected()) {
            String t = host.editorText();
            if (t != null && !t.isBlank()) {
                String sens = SensitiveData.find(t);
                if (sens != null) {
                    JOptionPane.showMessageDialog(this, "에디터 SQL에 " + sens + "이(가) 포함되어 있어 전송하지 않았습니다.\n"
                            + "해당 부분을 지우거나 마스킹하거나, '에디터 SQL 포함'을 끄고 다시 보내주세요.", "민감정보 감지", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                editorSql = t.length() > EDITOR_CHARS ? t.substring(0, EDITOR_CHARS) + "\n-- ... (생략됨)" : t;
            }
        }
        String exe = ClaudeCli.resolveInteractive(this);
        if (exe == null) { addText("오류", "claude CLI를 찾지 못했습니다.", true); return; }
        Session s = host.currentSession();
        boolean withSchema = schemaBox.isSelected() && s != null;
        if (!consented) {
            String what = "질문" + (withSchema ? "과 '" + s.profile.name() + "' 의 테이블/컬럼 이름" : "")
                    + (editorSql != null ? (withSchema ? "," : "과") + " 현재 에디터의 SQL" : "");
            int r = JOptionPane.showConfirmDialog(this,
                    "<html>" + what + "이(가) 로컬 Claude CLI를 통해 <b>Anthropic으로 전송</b>됩니다.<br>"
                            + "쿼리 결과 데이터는 전송하지 않습니다.<br><br>"
                            + "고객정보·주민번호·계좌/카드번호·비밀번호 등 민감정보는 입력하지 마세요.<br>"
                            + "이번 실행 동안 처음 한 번만 확인합니다. 계속할까요?</html>",
                    "외부 전송 확인", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            consented = true;
        }
        String schema = withSchema ? ClaudeCli.schemaText(s.completion, SCHEMA_CHARS, q + "\n" + (editorSql == null ? "" : editorSql)) : null;
        if (schema != null && schema.isBlank()) schema = null;
        List<String[]> recent = new ArrayList<>(history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size()));
        String prompt = ClaudeCli.buildPrompt(schema, editorSql, recent, q);

        input.setText("");
        addText("나", q, false);
        List<String> attached = new ArrayList<>();
        if (editorSql != null) attached.add("에디터 SQL " + (int) editorSql.lines().count() + "줄");
        if (schema != null) attached.add("스키마");
        if (withSchema && schema == null) attached.add(s.ready.isDone() ? "스키마 없음(조회 실패)" : "스키마 없음(아직 로딩 중)");
        if (!attached.isEmpty()) {
            JLabel note = new JLabel("📎 첨부: " + String.join(" · ", attached));
            note.setForeground(Color.GRAY);
            note.setFont(note.getFont().deriveFont(11f));
            addComponent(note);
        }
        history.add(new String[]{"User", q});
        JLabel thinking = new JLabel("Claude가 답변을 작성 중… 0초");
        thinking.setForeground(Color.GRAY);
        addComponent(thinking);
        long t0 = System.currentTimeMillis();
        Timer tick = new Timer(1000, e -> thinking.setText("Claude가 답변을 작성 중… " + (System.currentTimeMillis() - t0) / 1000 + "초"));
        tick.start();
        setBusy(true);
        updateTarget();
        final String exePath = exe;
        Thread t = new Thread(() -> {
            String reply = null, error = null;
            try {
                ClaudeCli.Handle h = ClaudeCli.start(exePath, prompt);
                current = h;
                reply = h.await(180);
            } catch (IOException ex) {
                error = ex.getMessage();
            } catch (InterruptedException ex) {
                error = "중단되었습니다.";
            }
            final String r = reply, err = error;
            SwingUtilities.invokeLater(() -> {
                tick.stop();
                chat.remove(thinking);
                current = null;
                setBusy(false);
                if (r != null) {
                    history.add(new String[]{"Assistant", r.length() > MAX_HISTORY_CHARS ? r.substring(0, MAX_HISTORY_CHARS) + "…" : r});
                    addAssistant(r);
                } else {
                    history.remove(history.size() - 1);   // 응답이 없으면 질문도 맥락에서 제외
                    addText("오류", err, true);
                }
            });
        }, "claude-cli");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean busy) {
        send.setEnabled(!busy);
        stop.setEnabled(busy);
        input.setEnabled(!busy);
        if (!busy) input.requestFocusInWindow();
    }

    // ---- 메시지 렌더링 (평면 구조 + 폭 기준 레이아웃) ----

    private void addText(String who, String text, boolean error) {
        JLabel l = new JLabel(who);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setForeground(error ? new Color(0xE05252) : who.equals("나") ? UIManager.getColor("Component.accentColor") : Color.GRAY);
        addComponent(l);
        addComponent(textArea(text, error));
    }

    private void addAssistant(String reply) {
        JLabel l = new JLabel("Claude");
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setForeground(new Color(0xD97757));
        addComponent(l);
        boolean auto = autoBox.isSelected();   // 첫 번째 SQL 블록만 자동 처리
        for (ChatParser.Segment seg : ChatParser.parse(reply)) {
            if (seg.code() && (seg.lang().isEmpty() || seg.lang().equals("sql") || seg.lang().equals("postgresql") || seg.lang().equals("pgsql"))) {
                addComponent(codeBlock(seg.text(), auto));
                auto = false;
            }
            else addComponent(textArea(seg.text(), false));
        }
    }

    private static JTextArea textArea(String text, boolean error) {
        JTextArea ta = new JTextArea(text);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setEditable(false);
        ta.setOpaque(false);
        ta.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        if (error) ta.setForeground(new Color(0xE05252));
        return ta;
    }

    private JComponent codeBlock(String sql, boolean auto) {
        RSyntaxTextArea ta = new RSyntaxTextArea(sql);
        ta.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        ta.setEditable(false);
        ta.setHighlightCurrentLine(false);
        ta.setCodeFoldingEnabled(false);
        applyTheme(ta);
        codeAreas.add(ta);
        int lines = Math.min(14, (int) sql.lines().count());
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(100, lines * ta.getFontMetrics(ta.getFont()).getHeight() + 28));

        JLabel result = new JLabel(" ");
        result.setFont(result.getFont().deriveFont(11f));
        JButton insert = new JButton("삽입"), replace = new JButton("에디터 교체"), run = new JButton("▶ 실행 (SELECT)"), copy = new JButton("복사");
        insert.setToolTipText("커서 위치에 삽입");
        replace.setToolTipText("선택 영역이 있으면 선택 영역, 없으면 에디터 전체를 교체합니다 (Cmd/Ctrl+Z로 되돌리기)");
        insert.addActionListener(e -> host.insertIntoEditor(sql));
        replace.addActionListener(e -> { host.replaceEditor(sql); result.setText("에디터에 반영됨 (Cmd/Ctrl+Z로 되돌릴 수 있음)"); });
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sql), null));
        String why = ReadOnlySql.check(sql);
        if (why != null) { run.setEnabled(false); run.setToolTipText(why); result.setText("실행 불가: " + why); }
        run.addActionListener(e -> runSelect(sql, run, result));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(insert);
        buttons.add(replace);
        buttons.add(run);
        buttons.add(copy);
        JPanel south = new JPanel(new GridLayout(2, 1));
        south.add(buttons);
        south.add(result);
        JPanel p = new JPanel(new BorderLayout());
        p.add(sp, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        if (auto) {
            host.appendToEditor(sql);
            // 실행은 기존 [실행] 버튼과 같은 읽기 전용 연결로만 한다
            if (why == null) runSelect(sql, run, result);
            else result.setText("콘솔에 입력됨 · 자동 실행 안 함: " + why);
        }
        return p;
    }

    private void addComponent(JComponent c) {
        chat.add(c);
        chat.revalidate();
        SwingUtilities.invokeLater(() -> {
            chat.revalidate();
            JScrollBar b = scroll.getVerticalScrollBar();
            b.setValue(b.getMaximum());
        });
    }

    // ---- SELECT 실행 (읽기 전용 연결, 결과는 로컬에만 표시) ----

    private void runSelect(String sqlText, JButton run, JLabel status) {
        Session s = host.currentSession();
        if (s == null) { status.setText("연결된 DB가 없습니다. 왼쪽에서 접속하세요."); return; }
        String why = ReadOnlySql.check(sqlText);
        if (why != null) { status.setText("실행 불가: " + why); return; }
        String sql = ReadOnlySql.statement(sqlText);
        run.setEnabled(false);
        status.setText("실행 중…");
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            ResultData d = null;
            String err = null;
            try (Connection c = s.newReadOnlyConnection(); Statement st = c.createStatement()) {
                st.execute("set statement_timeout = 30000");
                st.setMaxRows(ROW_LIMIT);
                try (ResultSet rs = st.executeQuery(sql)) { d = ConsoleTab.read(rs, ROW_LIMIT, sql, t0); }
            } catch (java.sql.SQLException ex) {
                err = ex.getMessage();
            }
            final ResultData data = d;
            final String error = err;
            History.add(s.profile.name() + " (AI)", (System.nanoTime() - t0) / 1_000_000, error == null, sql);
            SwingUtilities.invokeLater(() -> {
                run.setEnabled(true);
                if (data != null) {
                    status.setText(data.rows.size() + " rows · " + data.millis + " ms (콘솔 결과 탭에 표시됨)");
                    host.showResult(s, data, "AI Result");
                } else status.setText("오류: " + error);
            });
        }, "ai-select");
        t.setDaemon(true);
        t.start();
    }

    // ---- 레이아웃 ----

    /** 자식을 폭에 맞춰 위에서 아래로 쌓는다. JTextArea 는 폭을 먼저 정해 줄바꿈 높이를 계산한다. */
    private static final class ChatList extends JPanel implements Scrollable {
        ChatList() {
            setLayout(new LayoutManager() {
                public void addLayoutComponent(String n, Component c) {}
                public void removeLayoutComponent(Component c) {}
                public Dimension preferredLayoutSize(Container p) { return new Dimension(200, layoutAll(p, false)); }
                public Dimension minimumLayoutSize(Container p) { return new Dimension(100, 50); }
                public void layoutContainer(Container p) { layoutAll(p, true); }

                int layoutAll(Container p, boolean apply) {
                    Insets in = p.getInsets();
                    int w = Math.max(100, (p.getWidth() > 0 ? p.getWidth() : 360) - in.left - in.right);
                    int y = in.top;
                    for (Component c : p.getComponents()) {
                        if (!c.isVisible()) continue;
                        if (c instanceof JTextArea) c.setSize(w, Short.MAX_VALUE);
                        int h = c.getPreferredSize().height;
                        if (apply) c.setBounds(in.left, y, w, h);
                        y += h;
                    }
                    return y + in.bottom;
                }
            });
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
