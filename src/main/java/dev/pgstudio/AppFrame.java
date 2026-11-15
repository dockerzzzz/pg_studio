package dev.pgstudio;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;

public final class AppFrame extends JFrame {
    private static final int MOD = Shortcuts.mod();

    private final JTabbedPane consoles = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final Navigator navigator = new Navigator(this);
    private int counter;
    private final JPanel centerHolder = new JPanel(new BorderLayout());
    private final JSplitPane aiSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JToggleButton aiToggle = new JToggleButton("✦ Claude · Jira");
    private final JTabbedPane rightTabs = new JTabbedPane();
    private final JiraPanel jira = new JiraPanel();
    private final AiPanel ai = new AiPanel(new AiPanel.Host() {
        @Override public Session currentSession() { return AppFrame.this.activeSession(); }
        @Override public void insertIntoEditor(String sql) { AppFrame.this.insertIntoEditor(sql); }
        @Override public String editorText() {
            return AppFrame.this.consoles.getSelectedComponent() instanceof ConsoleTab c ? c.contextSql() : null;
        }
        @Override public void replaceEditor(String sql) {
            if (AppFrame.this.consoles.getSelectedComponent() instanceof ConsoleTab c) c.replaceSql(sql);
            else AppFrame.this.insertIntoEditor(sql);
        }
        @Override public void showResult(Session s, ResultData d, String label) { AppFrame.this.showResult(s, d, label); }
        @Override public void closePanel() { AppFrame.this.setAiVisible(false); }
    });

    AppFrame() {
        super("PG Studio");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1400, 860);
        setLocationRelativeTo(null);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigator, centerHolder);
        split.setDividerLocation(300);
        aiSplit.setResizeWeight(1.0);
        aiSplit.setContinuousLayout(true);
        rightTabs.addTab("✦ Claude", ai);
        rightTabs.addTab("Jira", jira);
        rightTabs.addChangeListener(e -> { if (rightTabs.getSelectedComponent() == jira) jira.onShown(); });
        aiToggle.setToolTipText("Claude / Jira 패널 열기/닫기 (Cmd/Ctrl+Shift+J)");
        aiToggle.addActionListener(e -> setAiVisible(aiToggle.isSelected()));
        JToolBar top = new JToolBar();
        top.setFloatable(false);
        JButton newConsole = new JButton("＋ 새 콘솔");
        newConsole.setToolTipText("현재 콘솔(또는 선택한 접속)의 연결로 새 콘솔 열기 (Cmd/Ctrl+T)");
        newConsole.addActionListener(e -> newConsole());
        top.add(newConsole);
        top.add(Box.createHorizontalGlue());
        top.add(aiToggle);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        setAiVisible(Main.aiPanelVisible());
        setJMenuBar(menu());
        openConsole(null, null, null, "-- PG Studio\n-- 왼쪽에서 접속을 추가/더블클릭해 연결하거나, 연결 없이 SQL을 작성/포맷할 수 있습니다.\n"
                + "-- 실행(Cmd/Ctrl+Enter)하면 사용할 DB 연결을 선택합니다.\nselect now();\n", false);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                for (int i = 0; i < consoles.getTabCount(); i++)
                    if (consoles.getComponentAt(i) instanceof ConsoleTab c && c.hasPendingTx()) {
                        if (JOptionPane.showConfirmDialog(AppFrame.this, "커밋되지 않은 트랜잭션이 있습니다. 종료하면 롤백됩니다.\n종료할까요?",
                                "종료", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
                            return;
                        }
                        break;
                    }
                setDefaultCloseOperation(EXIT_ON_CLOSE);
                dispose();
                System.exit(0);
            }
        });
    }

    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("새 접속…", KeyStroke.getKeyStroke(KeyEvent.VK_N, MOD | java.awt.event.InputEvent.SHIFT_DOWN_MASK), navigator::newConnection));
        file.add(item("새 콘솔", KeyStroke.getKeyStroke(KeyEvent.VK_T, MOD), this::newConsole));
        file.add(item("SQL 파일 열기…", KeyStroke.getKeyStroke(KeyEvent.VK_O, MOD), this::openSqlFile));
        file.add(item("저장", KeyStroke.getKeyStroke(KeyEvent.VK_S, MOD), () -> {
            if (consoles.getSelectedComponent() instanceof ConsoleTab c) c.save();
        }));
        file.add(item("현재 콘솔 닫기", KeyStroke.getKeyStroke(KeyEvent.VK_W, MOD), () -> {
            if (consoles.getSelectedComponent() instanceof ConsoleTab c) closeTab(c);
        }));
        bar.add(file);

        JMenu view = new JMenu("View");
        view.add(item("Claude/Jira 패널 열기/닫기", KeyStroke.getKeyStroke(KeyEvent.VK_J, MOD | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                () -> setAiVisible(!aiToggle.isSelected())));
        view.add(item("다크/라이트 전환", null, this::toggleTheme));
        bar.add(view);

        JMenu tools = new JMenu("Tools");
        tools.add(item("쿼리 이력", KeyStroke.getKeyStroke(KeyEvent.VK_H, MOD | java.awt.event.InputEvent.SHIFT_DOWN_MASK), () ->
                HistoryDialog.show(this, s -> { if (consoles.getSelectedComponent() instanceof ConsoleTab c) c.insertText(s); })));
        bar.add(tools);

        JMenu help = new JMenu("Help");
        help.add(item("단축키", null, () -> JOptionPane.showMessageDialog(this, """
                Cmd/Ctrl+Enter        선택 영역 또는 커서 위치의 문장 실행
                Cmd/Ctrl+Shift+Enter  전체 스크립트 실행
                Ctrl+Space            자동완성 (타이핑 시 자동 표시)
                Cmd/Ctrl+/            주석 토글
                Cmd/Ctrl+D            줄/선택 복제
                Cmd/Ctrl+Alt+L        SQL 포맷
                Cmd/Ctrl+Shift+U      선택 영역 대문자
                Cmd/Ctrl+F            찾기/바꾸기
                Cmd/Ctrl+= / -        글꼴 크기
                Cmd/Ctrl+T            새 콘솔
                Cmd/Ctrl+S            SQL 파일 저장
                Cmd/Ctrl+Shift+H      쿼리 이력
                """, "단축키", JOptionPane.PLAIN_MESSAGE)));
        bar.add(help);
        return bar;
    }

    private static JMenuItem item(String text, KeyStroke ks, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        if (ks != null) i.setAccelerator(ks);
        i.addActionListener(e -> r.run());
        return i;
    }

    /** 연결된 세션 중 하나를 고른다(선택된 노드의 세션 우선). */
    private Session pickSession() {
        Session sel = navigator.selectedSession();
        if (sel != null) return sel;
        List<Session> list = navigator.connectedSessions();
        if (list.isEmpty()) {
            JOptionPane.showMessageDialog(this, "연결된 DB가 없습니다. 왼쪽 목록에서 접속을 더블클릭해 연결하세요.");
            return null;
        }
        if (list.size() == 1) return list.get(0);
        Object r = JOptionPane.showInputDialog(this, "접속 선택", "새 콘솔", JOptionPane.PLAIN_MESSAGE, null,
                list.stream().map(s -> s.profile.name()).toArray(), null);
        if (r == null) return null;
        return list.stream().filter(s -> s.profile.name().equals(r)).findFirst().orElse(null);
    }

    void openConsole(Session s, String name, String schema, String sql, boolean run) {
        ConsoleTab tab = new ConsoleTab(s, schema, sql);
        tab.sessionChooser = this::pickSession;
        String title = (name != null ? name : (s != null ? s.profile.name() : "console") + " #" + (++counter));
        consoles.addTab(null, tab);
        int idx = consoles.indexOfComponent(tab);
        JLabel label = new JLabel(title);
        JButton close = new JButton("×");
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.addActionListener(e -> closeTab(tab));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        head.setOpaque(false);
        head.add(label);
        head.add(close);
        consoles.setTabComponentAt(idx, head);
        tab.onTitle = label::setText;
        consoles.setSelectedComponent(tab);
        if (run) tab.runNow();
    }

    /** 스키마 ERD 를 새 탭으로 연다. 테이블 더블클릭은 해당 테이블 select top 100 콘솔을 연다. */
    void openErd(Session s, String schema) {
        ErdPanel panel = new ErdPanel(s, schema, t ->
                openConsole(s, t.name, t.schema, "select * from " + Ddl.qualified(t.schema, t.name) + " limit 100;", true));
        consoles.addTab(null, panel);
        int idx = consoles.indexOfComponent(panel);
        JLabel label = new JLabel("ERD · " + schema);
        JButton close = new JButton("×");
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.addActionListener(e -> consoles.remove(panel));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        head.setOpaque(false);
        head.add(label);
        head.add(close);
        consoles.setTabComponentAt(idx, head);
        consoles.setSelectedComponent(panel);
    }

    private void closeTab(ConsoleTab tab) {
        if (tab.hasPendingTx() && JOptionPane.showConfirmDialog(this, "커밋되지 않은 트랜잭션이 있습니다. 닫으면 롤백됩니다.\n닫을까요?",
                "콘솔 닫기", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        tab.dispose();
        consoles.remove(tab);
    }

    /** 새 콘솔: 지금 보고 있는 콘솔의 연결을 이어받고, 콘솔이 없으면 선택한 접속/유일한 연결, 그것도 없으면 연결 없는 콘솔. */
    private void newConsole() {
        Session s = activeSession();
        String schema = consoles.getSelectedComponent() instanceof ConsoleTab c && c.session() == s ? c.schema() : null;
        openConsole(s, null, schema, "", false);
    }

    /** 대화상자 없이 쓸 기본 연결: 선택된 노드의 연결, 없으면 연결이 하나뿐일 때 그것, 아니면 null(연결 없는 콘솔). */
    private Session defaultSession() {
        Session sel = navigator.selectedSession();
        if (sel != null) return sel;
        List<Session> list = navigator.connectedSessions();
        return list.size() == 1 ? list.get(0) : null;
    }

    private void openSqlFile() {
        Session s = defaultSession();
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        openConsole(s, f.getName(), null, "", false);
        ((ConsoleTab) consoles.getSelectedComponent()).openFile(f);
    }

    /** Claude 패널 열기/닫기. 닫으면 콘솔 영역이 전체 폭을 쓴다. */
    private void setAiVisible(boolean show) {
        Main.setAiPanelVisible(show);
        aiToggle.setSelected(show);
        centerHolder.removeAll();
        if (show) {
            aiSplit.setLeftComponent(consoles);
            aiSplit.setRightComponent(rightTabs);
            centerHolder.add(aiSplit, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> {
                aiSplit.setDividerLocation(Math.max(300, aiSplit.getWidth() - 440));
                if (rightTabs.getSelectedComponent() == ai) ai.focusInput(); else jira.onShown();
            });
        } else centerHolder.add(consoles, BorderLayout.CENTER);
        centerHolder.revalidate();
        centerHolder.repaint();
    }

    /** AI 패널이 사용할 현재 DB: 선택된 콘솔의 연결 → 선택된 노드의 연결 → 연결이 하나뿐이면 그것. */
    private Session activeSession() {
        if (consoles.getSelectedComponent() instanceof ConsoleTab c && c.session() != null) return c.session();
        return defaultSession();
    }

    private void insertIntoEditor(String sql) {
        if (!(consoles.getSelectedComponent() instanceof ConsoleTab)) {
            Session s = activeSession();
            if (s == null) { JOptionPane.showMessageDialog(this, "열려 있는 콘솔이 없습니다. 새 콘솔을 먼저 여세요."); return; }
            openConsole(s, null, null, "", false);
        }
        ((ConsoleTab) consoles.getSelectedComponent()).insertText(sql.endsWith("\n") ? sql : sql + "\n");
    }

    private void showResult(Session s, ResultData d, String label) {
        if (!(consoles.getSelectedComponent() instanceof ConsoleTab)) openConsole(s, null, null, "", false);
        ((ConsoleTab) consoles.getSelectedComponent()).showResult(d, label);
    }

    private void toggleTheme() {
        boolean dark = !Main.isDark();
        Main.setDark(dark);
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        FlatLaf.updateUI();
        for (int i = 0; i < consoles.getTabCount(); i++)
            if (consoles.getComponentAt(i) instanceof ConsoleTab c) c.applyEditorTheme();
        ai.refreshTheme();
    }
}
