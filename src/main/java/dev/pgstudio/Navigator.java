package dev.pgstudio;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.*;
import java.util.*;
import java.util.List;

/** DB 탐색기: 접속 > 스키마 > 테이블/뷰/시퀀스/함수 > 컬럼/인덱스 (지연 로딩). */
final class Navigator extends JPanel {
    enum Kind { ROOT, CONN, SCHEMA, FOLDER, TABLE, VIEW, MATVIEW, SEQUENCE, FUNCTION, COLUMN, INDEX, MESSAGE }

    static final class N {
        final Kind kind;
        String label;
        ConnectionProfile profile;
        Session session;
        String schema, name, folder, type;
        long oid;
        boolean pk, loaded;

        N(Kind k, String label) { this.kind = k; this.label = label; }
        @Override public String toString() { return label; }
    }

    private final AppFrame app;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode(new N(Kind.ROOT, "Connections"));
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final List<ConnectionProfile> profiles = new ArrayList<>(ProfileStore.load());
    /** 파일에 저장하기로 한 비밀번호(평문, 테스트용). */
    private final Map<String, String> savedPw = new HashMap<>(ProfileStore.loadPasswords());
    /** 이번 실행 동안 메모리에 유지하는 비밀번호. 저장된 것으로 시작한다. */
    private final Map<String, String> passwords = new HashMap<>(savedPw);

    Navigator(AppFrame app) {
        super(new BorderLayout());
        this.app = app;
        for (ConnectionProfile p : profiles) root.add(connNode(p));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        // 위에서 root 에 직접 add 한 노드는 모델 이벤트가 없어 화면에 반영되지 않으므로, 구조 변경을 알리고 루트를 펼친다.
        model.reload();
        tree.expandPath(new TreePath(root));
        tree.setRowHeight(22);
        tree.setCellRenderer(new Renderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            public void treeWillExpand(TreeExpansionEvent e) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.getPath().getLastPathComponent();
                N d = (N) n.getUserObject();
                if (isLazy(d.kind) && !d.loaded) startLoad(n);
            }
            public void treeWillCollapse(TreeExpansionEvent e) {}
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                DefaultMutableTreeNode n = nodeAt(e);
                if (n == null) return;
                N d = (N) n.getUserObject();
                switch (d.kind) {
                    case TABLE, VIEW, MATVIEW -> selectTop(d);
                    case FUNCTION, SEQUENCE -> showDdl(d);
                    default -> {}
                }
            }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton add = new JButton("+ 접속");
        add.addActionListener(e -> newConnection());
        JButton refresh = new JButton("새로고침");
        refresh.addActionListener(e -> {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (n != null) refresh(n);
        });
        bar.add(add);
        bar.add(refresh);
        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    // ---- 외부 API ----

    /** 목록에 표시된 최상위 접속 행 수 (테스트용). */
    int visibleRowCount() { return tree.getRowCount(); }

    List<Session> connectedSessions() {
        List<Session> out = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            N d = (N) ((DefaultMutableTreeNode) root.getChildAt(i)).getUserObject();
            if (d.session != null) out.add(d.session);
        }
        return out;
    }

    /** 선택된 노드가 속한 연결의 세션(없으면 null). */
    Session selectedSession() {
        DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        return n == null ? null : ((N) n.getUserObject()).session;
    }

    void newConnection() {
        ConnectionDialog.Result r = ConnectionDialog.show(this, null, null);
        if (r == null) return;
        profiles.add(r.profile());
        applyPassword(r);
        ProfileStore.save(profiles, savedPw);
        DefaultMutableTreeNode n = connNode(r.profile());
        model.insertNodeInto(n, root, root.getChildCount());
        tree.scrollPathToVisible(new TreePath(n.getPath()));
    }

    // ---- 노드 구성 ----

    /** 다이얼로그 결과의 비밀번호를 메모리/저장 맵에 반영한다. */
    private void applyPassword(ConnectionDialog.Result r) {
        String id = r.profile().id();
        if (r.password().isEmpty()) { passwords.remove(id); savedPw.remove(id); return; }
        passwords.put(id, r.password());
        if (r.savePassword()) savedPw.put(id, r.password()); else savedPw.remove(id);
    }

    private DefaultMutableTreeNode connNode(ConnectionProfile p) {
        N d = new N(Kind.CONN, p.name());
        d.profile = p;
        return lazy(d);
    }

    private static DefaultMutableTreeNode lazy(N d) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(d);
        n.add(new DefaultMutableTreeNode(new N(Kind.MESSAGE, "…")));
        return n;
    }

    private static boolean isLazy(Kind k) {
        return k == Kind.CONN || k == Kind.SCHEMA || k == Kind.FOLDER || k == Kind.TABLE || k == Kind.VIEW || k == Kind.MATVIEW;
    }

    private DefaultMutableTreeNode nodeAt(MouseEvent e) {
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        return p == null ? null : (DefaultMutableTreeNode) p.getLastPathComponent();
    }

    private N child(N parent, Kind k, String label) {
        N c = new N(k, label);
        c.profile = parent.profile; c.session = parent.session; c.schema = parent.schema;
        return c;
    }

    // ---- 로딩 ----

    private void startLoad(DefaultMutableTreeNode node) {
        N d = (N) node.getUserObject();
        String pw = null;
        if (d.kind == Kind.CONN && d.session == null) {
            pw = passwords.get(d.profile.id());
            if (pw == null) {
                JPasswordField f = new JPasswordField(20);
                int r = JOptionPane.showConfirmDialog(this, f, d.profile.name() + " 비밀번호",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (r != JOptionPane.OK_OPTION) {
                    SwingUtilities.invokeLater(() -> tree.collapsePath(new TreePath(node.getPath())));
                    return;
                }
                pw = new String(f.getPassword());
            }
        }
        d.loaded = true;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(new N(Kind.MESSAGE, d.kind == Kind.CONN ? "연결 중…" : "로딩 중…")));
        model.nodeStructureChanged(node);
        final String password = pw;
        Thread t = new Thread(() -> {
            try {
                if (d.kind == Kind.CONN && d.session == null) {
                    d.session = Session.connect(d.profile, password);
                    passwords.put(d.profile.id(), password);
                }
                List<N> kids = load(d);
                SwingUtilities.invokeLater(() -> fill(node, kids));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    d.loaded = false;
                    node.removeAllChildren();
                    node.add(new DefaultMutableTreeNode(new N(Kind.MESSAGE, "…")));
                    model.nodeStructureChanged(node);
                    tree.collapsePath(new TreePath(node.getPath()));
                    if (d.kind == Kind.CONN) passwords.remove(d.profile.id());
                    JOptionPane.showMessageDialog(Navigator.this, ex.getMessage(), "로딩 실패", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "navigator-load");
        t.setDaemon(true);
        t.start();
    }

    private void fill(DefaultMutableTreeNode node, List<N> kids) {
        node.removeAllChildren();
        if (kids.isEmpty()) node.add(new DefaultMutableTreeNode(new N(Kind.MESSAGE, "(비어 있음)")));
        for (N k : kids) node.add(isLazy(k.kind) ? lazy(k) : new DefaultMutableTreeNode(k));
        model.nodeStructureChanged(node);
        N d = (N) node.getUserObject();
        if (d.kind == Kind.CONN) { ((N) node.getUserObject()).label = d.profile.name(); model.nodeChanged(node); }
    }

    private List<N> load(N d) throws SQLException {
        List<N> out = new ArrayList<>();
        Connection c = d.session.meta;
        switch (d.kind) {
            case CONN -> {
                String q = "select nspname from pg_namespace where nspname not like 'pg\\_%' "
                        + "and nspname <> 'information_schema' order by 1";
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
                    while (rs.next()) { N s = child(d, Kind.SCHEMA, rs.getString(1)); s.schema = rs.getString(1); out.add(s); }
                }
            }
            case SCHEMA -> {
                String[][] f = {{"tables", "Tables"}, {"views", "Views"}, {"matviews", "Materialized views"},
                        {"sequences", "Sequences"}, {"functions", "Functions"}};
                for (String[] x : f) { N n = child(d, Kind.FOLDER, x[1]); n.folder = x[0]; out.add(n); }
            }
            case FOLDER -> loadFolder(d, c, out);
            case TABLE, VIEW, MATVIEW -> loadColumns(d, c, out);
            default -> {}
        }
        return out;
    }

    private void loadFolder(N d, Connection c, List<N> out) throws SQLException {
        if (d.folder.equals("functions")) {
            String q = "select p.proname, p.oid, pg_get_function_identity_arguments(p.oid) from pg_proc p "
                    + "join pg_namespace n on n.oid = p.pronamespace where n.nspname = ? and p.prokind in ('f','p') order by 1, 3";
            try (PreparedStatement ps = c.prepareStatement(q)) {
                ps.setString(1, d.schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        N f = child(d, Kind.FUNCTION, rs.getString(1) + "(" + rs.getString(3) + ")");
                        f.name = rs.getString(1); f.oid = rs.getLong(2);
                        out.add(f);
                    }
                }
            }
            return;
        }
        String kinds = switch (d.folder) {
            case "tables" -> "'r','p','f'"; case "views" -> "'v'"; case "matviews" -> "'m'"; default -> "'S'";
        };
        Kind k = switch (d.folder) {
            case "tables" -> Kind.TABLE; case "views" -> Kind.VIEW; case "matviews" -> Kind.MATVIEW; default -> Kind.SEQUENCE;
        };
        String q = "select c.relname, c.oid from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = ? and c.relkind in (" + kinds + ") order by 1";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, d.schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    N t = child(d, k, rs.getString(1));
                    t.name = rs.getString(1); t.oid = rs.getLong(2);
                    t.loaded = k == Kind.SEQUENCE;
                    out.add(t);
                }
            }
        }
    }

    private void loadColumns(N d, Connection c, List<N> out) throws SQLException {
        String q = "select a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, "
                + "exists (select 1 from pg_index i where i.indrelid = a.attrelid and i.indisprimary and a.attnum = any(i.indkey)) "
                + "from pg_attribute a where a.attrelid = ? and a.attnum > 0 and not a.attisdropped order by a.attnum";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            ps.setLong(1, d.oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    N col = child(d, Kind.COLUMN, rs.getString(1));
                    col.name = rs.getString(1);
                    col.type = rs.getString(2) + (rs.getBoolean(3) ? " not null" : "");
                    col.pk = rs.getBoolean(4);
                    out.add(col);
                }
            }
        }
        if (d.kind != Kind.TABLE) return;
        String iq = "select ic.relname, i.indisunique, i.indisprimary from pg_index i "
                + "join pg_class ic on ic.oid = i.indexrelid where i.indrelid = ? order by 1";
        try (PreparedStatement ps = c.prepareStatement(iq)) {
            ps.setLong(1, d.oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    N ix = child(d, Kind.INDEX, rs.getString(1));
                    ix.name = rs.getString(1);
                    ix.type = rs.getBoolean(3) ? "primary" : rs.getBoolean(2) ? "unique" : "index";
                    out.add(ix);
                }
            }
        }
    }

    private void refresh(DefaultMutableTreeNode node) {
        N d = (N) node.getUserObject();
        if (!isLazy(d.kind) || (d.kind == Kind.CONN && d.session == null)) return;
        d.loaded = false;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(new N(Kind.MESSAGE, "…")));
        model.nodeStructureChanged(node);
        if (d.kind == Kind.CONN && d.session != null) d.session.reloadCompletion();
        TreePath p = new TreePath(node.getPath());
        tree.collapsePath(p);
        tree.expandPath(p);
    }

    private void disconnect(DefaultMutableTreeNode node) {
        N d = (N) node.getUserObject();
        if (d.session != null) d.session.close();
        d.session = null;
        d.loaded = false;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(new N(Kind.MESSAGE, "…")));
        model.nodeStructureChanged(node);
        tree.collapsePath(new TreePath(node.getPath()));
    }

    // ---- 동작 ----

    private String fq(N d) { return Ddl.qualified(d.schema, d.name); }

    private void selectTop(N d) { app.openConsole(d.session, d.name, d.schema, "select * from " + fq(d) + " limit 100;", true); }

    private void showDdl(N d) {
        Thread t = new Thread(() -> {
            try {
                Connection c = d.session.meta;
                String ddl = switch (d.kind) {
                    case TABLE -> Ddl.table(c, d.schema, d.name);
                    case VIEW -> Ddl.view(c, d.schema, d.name, false);
                    case MATVIEW -> Ddl.view(c, d.schema, d.name, true);
                    case FUNCTION -> Ddl.function(c, d.oid);
                    case SEQUENCE -> Ddl.sequence(c, d.schema, d.name);
                    default -> "";
                };
                SwingUtilities.invokeLater(() -> app.openConsole(d.session, "DDL " + d.name, d.schema, ddl, false));
            } catch (SQLException ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, ex.getMessage(), "DDL 실패", JOptionPane.ERROR_MESSAGE));
            }
        }, "ddl");
        t.setDaemon(true);
        t.start();
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        JPopupMenu m = new JPopupMenu();
        if (p == null) {
            item(m, "새 접속…", this::newConnection);
            m.show(tree, e.getX(), e.getY());
            return;
        }
        tree.setSelectionPath(p);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) p.getLastPathComponent();
        N d = (N) node.getUserObject();
        switch (d.kind) {
            case CONN -> {
                if (d.session == null) item(m, "연결", () -> tree.expandPath(p));
                else {
                    item(m, "새 콘솔", () -> app.openConsole(d.session, null, null, "", false));
                    item(m, "새로고침", () -> refresh(node));
                    item(m, "연결 해제", () -> disconnect(node));
                }
                m.addSeparator();
                item(m, "편집…", () -> editConnection(node));
                item(m, "삭제", () -> removeConnection(node));
            }
            case SCHEMA -> {
                item(m, "이 스키마로 새 콘솔", () -> app.openConsole(d.session, d.schema, d.schema, "", false));
                item(m, "새로고침", () -> refresh(node));
            }
            case FOLDER -> item(m, "새로고침", () -> refresh(node));
            case TABLE, VIEW, MATVIEW -> {
                item(m, "Select top 100", () -> selectTop(d));
                item(m, "행 수 세기", () -> app.openConsole(d.session, "count " + d.name, d.schema, "select count(*) from " + fq(d) + ";", true));
                item(m, "DDL 보기", () -> showDdl(d));
                item(m, "이름 복사", () -> copy(fq(d)));
                item(m, "새로고침", () -> refresh(node));
            }
            case FUNCTION, SEQUENCE -> {
                item(m, "DDL 보기", () -> showDdl(d));
                item(m, "이름 복사", () -> copy(d.kind == Kind.SEQUENCE ? fq(d) : Ddl.qualified(d.schema, d.name)));
            }
            case COLUMN, INDEX -> item(m, "이름 복사", () -> copy(Ddl.quote(d.name)));
            default -> {}
        }
        if (m.getComponentCount() > 0) m.show(tree, e.getX(), e.getY());
    }

    private void editConnection(DefaultMutableTreeNode node) {
        N d = (N) node.getUserObject();
        ConnectionDialog.Result r = ConnectionDialog.show(this, d.profile, savedPw.get(d.profile.id()));
        if (r == null) return;
        if (d.session != null) disconnect(node);
        int i = profiles.indexOf(d.profile);
        if (i >= 0) profiles.set(i, r.profile());
        applyPassword(r);
        ProfileStore.save(profiles, savedPw);
        d.profile = r.profile();
        d.label = r.profile().name();
        model.nodeChanged(node);
    }

    private void removeConnection(DefaultMutableTreeNode node) {
        N d = (N) node.getUserObject();
        if (JOptionPane.showConfirmDialog(this, "'" + d.profile.name() + "' 접속 정보를 삭제할까요?", "삭제",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (d.session != null) d.session.close();
        profiles.remove(d.profile);
        passwords.remove(d.profile.id());
        savedPw.remove(d.profile.id());
        ProfileStore.save(profiles, savedPw);
        model.removeNodeFromParent(node);
    }

    private static void item(JPopupMenu m, String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        m.add(i);
    }

    private static void copy(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
            N d = (N) ((DefaultMutableTreeNode) v).getUserObject();
            String gray = sel ? "" : "<font color='gray'>";
            String end = sel ? "" : "</font>";
            String text = switch (d.kind) {
                case CONN -> (d.session != null ? "<font color='#4caf50'>●</font> " : "○ ") + esc(d.label)
                        + (d.profile != null && d.profile.readOnly() ? " " + gray + "[RO]" + end : "");
                case COLUMN -> (d.pk ? "🔑 " : "") + esc(d.label) + " " + gray + esc(d.type) + end;
                case INDEX -> "⚡ " + esc(d.label) + " " + gray + esc(d.type) + end;
                case SCHEMA -> "▤ " + esc(d.label);
                case TABLE -> "▦ " + esc(d.label);
                case VIEW, MATVIEW -> "◫ " + esc(d.label);
                case SEQUENCE -> "# " + esc(d.label);
                case FUNCTION -> "ƒ " + esc(d.label);
                default -> esc(d.label);
            };
            setText("<html>" + text + "</html>");
            setIcon(null);
            return this;
        }

        private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }
    }
}
