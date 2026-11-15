package dev.pgstudio;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 우측 Jira 탭: 내 미해결 이슈 목록(업무 · 담당자 · 보고자). 이슈 키를 클릭하면 브라우저에서 연다. */
final class JiraPanel extends JPanel {
    private static final int MAX_RESULTS = 100;
    private static final String[] COLUMNS = {"업무", "담당자", "보고자"};

    private JiraSettings.Config config;
    private final Consumer<String> opener;
    private final JTextField jqlField = new JTextField();
    private final JLabel count = new JLabel(" "), message = new JLabel(" ");
    private final JButton refreshBtn = new JButton("⟳"), settingsBtn = new JButton("설정");
    private final List<JiraClient.Issue> issues = new ArrayList<>();
    private final AbstractTableModel model;
    private final JTable table;
    private int generation;
    private boolean loadedOnce;
    private boolean tokenSaved;   // 토큰이 파일에 저장돼 있는지(JQL 등을 저장할 때 지우지 않도록)

    JiraPanel() { this(JiraSettings.load(), JiraPanel::openInBrowser); }

    /** 테스트에서 브라우저 열기를 대체할 수 있도록 분리. */
    JiraPanel(JiraSettings.Config initial, Consumer<String> opener) {
        super(new BorderLayout());
        this.config = initial;
        this.tokenSaved = !initial.token().isBlank();
        this.opener = opener;

        JLabel title = new JLabel("  Jira · 내 미해결 이슈");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        refreshBtn.setToolTipText("새로고침");
        refreshBtn.addActionListener(e -> refresh());
        settingsBtn.addActionListener(e -> configure());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        btns.add(refreshBtn);
        btns.add(settingsBtn);
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.WEST);
        header.add(btns, BorderLayout.EAST);

        jqlField.setText(config.jql());
        jqlField.setToolTipText("JQL — Enter로 조회");
        jqlField.putClientProperty("JTextField.placeholderText", "JQL");
        jqlField.setFont(Fonts.mono(12));
        jqlField.addActionListener(e -> {
            String q = jqlField.getText().strip();
            config = config.withJql(q.isEmpty() ? JiraSettings.DEFAULT_JQL : q);
            if (q.isEmpty()) jqlField.setText(config.jql());
            JiraSettings.save(config, tokenSaved);
            refresh();
        });
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(header, BorderLayout.NORTH);
        JPanel jqlRow = new JPanel(new BorderLayout());
        jqlRow.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
        jqlRow.add(jqlField);
        top.add(jqlRow, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        model = new AbstractTableModel() {
            public int getRowCount() { return issues.size(); }
            public int getColumnCount() { return COLUMNS.length; }
            public String getColumnName(int c) { return COLUMNS[c]; }
            public Object getValueAt(int r, int c) {
                JiraClient.Issue i = issues.get(r);
                return switch (c) { case 0 -> i; case 1 -> i.assignee(); default -> i.reporter(); };
            }
        };
        table = new JTable(model) {
            @Override public String getToolTipText(MouseEvent e) {
                int r = rowAtPoint(e.getPoint());
                if (r < 0) return null;
                JiraClient.Issue i = issues.get(r);
                return "<html><b>" + esc(i.key()) + "</b> " + esc(i.summary()) + "<br>상태: " + esc(i.status()) + "</html>";
            }
        };
        table.setRowHeight(40);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);   // 패널 폭을 열이 나눠 채움
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(1, 1));
        Color grid = UIManager.getColor("Table.gridColor");
        table.setGridColor(grid != null ? grid : UIManager.getColor("Separator.foreground"));
        table.getColumnModel().getColumn(0).setCellRenderer(new IssueRenderer());
        PersonRenderer people = new PersonRenderer();
        table.getColumnModel().getColumn(1).setCellRenderer(people);
        table.getColumnModel().getColumn(2).setCellRenderer(people);
        JTableHeader head = table.getTableHeader();
        head.setReorderingAllowed(false);
        head.setPreferredSize(new Dimension(0, 34));
        head.setDefaultRenderer(new HeaderRenderer(head.getDefaultRenderer()));
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                if (r < 0 || c < 0) return;
                JiraClient.Issue i = issues.get(table.convertRowIndexToModel(r));
                boolean onKey = c == 0 && overKey(e.getX() - table.getCellRect(r, c, false).x, i.key());
                if ((e.getClickCount() == 1 && onKey) || e.getClickCount() == 2) open(i);
            }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        table.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                boolean hand = r >= 0 && c == 0 && overKey(e.getX() - table.getCellRect(r, c, false).x, issues.get(r).key());
                table.setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        count.setFont(count.getFont().deriveFont(11f));
        message.setFont(message.getFont().deriveFont(11f));
        JPanel south = new JPanel(new GridLayout(2, 1));
        south.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        south.add(count);
        south.add(message);
        add(south, BorderLayout.SOUTH);

        if (!config.configured()) message.setText("설정 버튼으로 Jira 주소와 계정을 입력하세요.");
    }

    /** 탭이 처음 보일 때 한 번 자동 조회한다. */
    void onShown() {
        if (!loadedOnce && config.configured()) refresh();
    }

    // ---- 조회 ----

    void refresh() {
        if (!config.configured()) { configure(); return; }
        if (config.token().isBlank()) {
            JPasswordField f = new JPasswordField(24);
            int r = JOptionPane.showConfirmDialog(this, f, "Jira API 토큰 (" + config.email() + ")",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION || f.getPassword().length == 0) return;
            config = config.withToken(new String(f.getPassword()));
        }
        final JiraSettings.Config cfg = config;
        final int gen = ++generation;
        loadedOnce = true;
        refreshBtn.setEnabled(false);
        message.setText("조회 중…");
        Thread t = new Thread(() -> {
            JiraClient.Result res = null;
            String err = null;
            try {
                res = new JiraClient(cfg.baseUrl(), cfg.email(), cfg.token()).search(cfg.jql(), MAX_RESULTS);
            } catch (JiraClient.JiraException | IllegalArgumentException e) {
                err = e.getMessage();
            }
            final JiraClient.Result r = res;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) return;   // 더 최근 요청이 있으면 무시
                refreshBtn.setEnabled(true);
                if (r != null) {
                    issues.clear();
                    issues.addAll(r.issues());
                    model.fireTableDataChanged();
                    count.setText(r.issues().size() + "건" + (r.more() ? " 이상 (최대 " + MAX_RESULTS + "건 표시)" : ""));
                    message.setText(r.issues().isEmpty() ? "조건에 맞는 이슈가 없습니다." : "이슈 키를 클릭하면 브라우저에서 엽니다.");
                } else {
                    message.setText("<html><font color='#E05252'>" + esc(error) + "</font></html>");
                    if (error != null && error.startsWith("인증")) config = config.withToken("");   // 잘못된 토큰은 버린다
                }
            });
        }, "jira-search");
        t.setDaemon(true);
        t.start();
    }

    // ---- 설정 ----

    private void configure() {
        JTextField url = new JTextField(config.baseUrl(), 28);
        url.putClientProperty("JTextField.placeholderText", "https://회사.atlassian.net");
        JTextField email = new JTextField(config.email());
        JPasswordField token = new JPasswordField(config.token());
        JCheckBox save = new JCheckBox("토큰 저장 (평문, ~/.pgstudio, 테스트용)", !config.token().isBlank());
        JLabel hint = new JLabel("<html><font color='gray'>API 토큰: Atlassian 계정 › 보안 › API 토큰 만들기<br>"
                + "토큰은 입력한 Jira 주소로만 전송됩니다.</font></html>");
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {{"Jira 주소", url}, {"이메일", email}, {"API 토큰", token}, {"", save}, {"", hint}};
        for (int i = 0; i < rows.length; i++) {
            g.gridx = 0; g.gridy = i; g.weightx = 0;
            p.add(new JLabel((String) rows[i][0]), g);
            g.gridx = 1; g.weightx = 1;
            p.add((Component) rows[i][1], g);
        }
        if (JOptionPane.showConfirmDialog(this, p, "Jira 설정", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            new JiraClient(url.getText(), email.getText(), "x");   // 주소 형식 검증
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Jira 설정", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String base = url.getText().strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        config = new JiraSettings.Config(base, email.getText().strip(), new String(token.getPassword()), config.jql());
        tokenSaved = save.isSelected();
        JiraSettings.save(config, tokenSaved);
        refresh();
    }

    // ---- 링크 ----

    private static final int PAD = 12;

    private boolean overKey(int xInCell, String key) {
        FontMetrics fm = table.getFontMetrics(table.getFont());
        return xInCell >= 0 && xInCell <= PAD + fm.stringWidth(key) + 6;
    }

    private void open(JiraClient.Issue i) {
        try {
            opener.accept(new JiraClient(config.baseUrl(), config.email(), "x").browseUrl(i.key()));
        } catch (RuntimeException e) {
            message.setText("열 수 없습니다: " + e.getMessage());
        }
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int r = table.rowAtPoint(e.getPoint());
        if (r < 0) return;
        table.setRowSelectionInterval(r, r);
        JiraClient.Issue i = issues.get(r);
        JPopupMenu m = new JPopupMenu();
        JMenuItem open = new JMenuItem("브라우저에서 열기");
        open.addActionListener(x -> open(i));
        JMenuItem copy = new JMenuItem("이슈 키 복사");
        copy.addActionListener(x -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(i.key()), null));
        m.add(open);
        m.add(copy);
        m.show(table, e.getX(), e.getY());
    }

    private static void openInBrowser(String url) {
        try { Browser.open(url); } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "브라우저를 열 수 없습니다: " + e.getMessage());
        }
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    // ---- 표시(그리드, 간격, 아바타) ----

    /** 홀짝 행을 살짝 다르게 칠한다. 선택된 행은 선택 색. */
    private static Color rowBackground(JTable t, int row, boolean selected) {
        if (selected) return t.getSelectionBackground();
        Color base = t.getBackground(), fg = t.getForeground();
        if (row % 2 == 0) return base;
        return new Color((base.getRed() * 95 + fg.getRed() * 5) / 100, (base.getGreen() * 95 + fg.getGreen() * 5) / 100,
                (base.getBlue() * 95 + fg.getBlue() * 5) / 100);
    }

    private static Color linkColor() {
        Color c = UIManager.getColor("Component.linkColor");
        return c != null ? c : new Color(0x589DF6);
    }

    /** 업무 열: 이슈 키(링크 색+밑줄) + 제목(길면 … 로 줄임). */
    private static final class IssueRenderer implements TableCellRenderer {
        private final JPanel panel = new JPanel(new BorderLayout(10, 0));
        private final JLabel key = new JLabel(), summary = new JLabel();

        IssueRenderer() {
            panel.setBorder(BorderFactory.createEmptyBorder(0, PAD, 0, PAD));
            panel.add(key, BorderLayout.WEST);
            panel.add(summary, BorderLayout.CENTER);
        }

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            JiraClient.Issue i = (JiraClient.Issue) v;
            key.setText(i.key());
            key.setFont(t.getFont().deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)));
            key.setForeground(sel ? t.getSelectionForeground() : linkColor());
            summary.setText(i.summary());
            summary.setFont(t.getFont());
            summary.setForeground(sel ? t.getSelectionForeground() : t.getForeground());
            panel.setBackground(rowBackground(t, r, sel));
            return panel;
        }
    }

    /** 담당자/보고자 열: 이니셜 아바타 + 이름. 미할당(-)은 회색 텍스트만. */
    private static final class PersonRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            String name = v == null ? "" : v.toString();
            boolean none = name.equals("미할당") || name.equals("-") || name.isBlank();
            setBorder(BorderFactory.createEmptyBorder(0, PAD, 0, PAD));
            setIcon(none ? null : new AvatarIcon(name));
            setIconTextGap(8);
            setBackground(rowBackground(t, r, sel));
            setForeground(sel ? t.getSelectionForeground() : none ? Color.GRAY : t.getForeground());
            return this;
        }
    }

    /** 헤더: 왼쪽 정렬 + 굵게 + 좌우 여백. */
    private static final class HeaderRenderer implements TableCellRenderer {
        private final TableCellRenderer delegate;

        HeaderRenderer(TableCellRenderer delegate) { this.delegate = delegate; }

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            Component comp = delegate.getTableCellRendererComponent(t, v, sel, foc, r, c);
            if (comp instanceof JLabel l) {
                l.setHorizontalAlignment(SwingConstants.LEFT);
                l.setFont(l.getFont().deriveFont(Font.BOLD));
                l.setBorder(BorderFactory.createCompoundBorder(l.getBorder(), BorderFactory.createEmptyBorder(0, PAD, 0, PAD)));
            }
            return comp;
        }
    }

    /** 이름의 첫 글자를 담은 원형 아바타(색은 이름에서 결정). */
    static final class AvatarIcon implements Icon {
        private static final int SIZE = 22;
        private final String initial;
        private final Color color;

        AvatarIcon(String name) {
            String n = name.strip();
            initial = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase();
            color = Color.getHSBColor((Math.abs(name.hashCode()) % 360) / 360f, 0.55f, 0.78f);
        }

        String initial() { return initial; }
        @Override public int getIconWidth() { return SIZE; }
        @Override public int getIconHeight() { return SIZE; }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, SIZE, SIZE);
            g2.setColor(Color.WHITE);
            g2.setFont(c.getFont().deriveFont(Font.BOLD, 11f));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(initial, x + (SIZE - fm.stringWidth(initial)) / 2, y + (SIZE - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}
