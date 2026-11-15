package dev.pgstudio;

import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 결과 그리드: 정렬, 필터, 복사/내보내기(CSV/JSON/INSERT), 셀 상세 보기,
 * 그리고 (단일 테이블 + PK 포함 결과에 한해) 셀 수정/행 추가/행 삭제. 변경은 "적용" 시 수동 커밋 트랜잭션으로 실행된다.
 */
final class ResultPanel extends JPanel {
    /** 콘솔이 구현: 접속 권한 확인과 변경 적용(수동 커밋). */
    interface GridEditor {
        boolean canEdit();
        void apply(ResultData d, List<GridEdit.Op> ops, Runnable onSuccess);
    }

    private final ResultData data;
    private final GridEditor editor;
    private final boolean editable;
    private final AbstractTableModel model;
    private final JTable table;
    private final TableRowSorter<TableModel> sorter;
    private final JLabel info = new JLabel();
    private final JLabel pendingLabel = new JLabel();
    private final JButton applyBtn = new JButton("변경 적용"), discardBtn = new JButton("되돌리기");

    // 변경 상태 (행 배열의 동일성 기준 → 삭제/정렬에도 안전)
    private final Map<Object[], Map<Integer, String>> updates = new IdentityHashMap<>();
    private final Set<Object[]> deleted = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object[]> inserted = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object[]> stale = Collections.newSetFromMap(new IdentityHashMap<>());

    ResultPanel(ResultData d, GridEditor editor) {
        super(new BorderLayout());
        this.data = d;
        this.editor = editor;
        this.editable = editor != null && d.edit != null && editor.canEdit();

        Class<?>[] classes = new Class<?>[d.columns.size()];
        for (int c = 0; c < classes.length; c++) {
            classes[c] = Object.class;
            for (Object[] r : d.rows) if (r[c] != null) { if (r[c] instanceof Comparable) classes[c] = r[c].getClass(); break; }
        }
        model = new AbstractTableModel() {
            public int getRowCount() { return data.rows.size(); }
            public int getColumnCount() { return data.columns.size(); }
            public String getColumnName(int c) { return data.columns.get(c); }
            public Object getValueAt(int r, int c) { return value(data.rows.get(r), c); }
            public Class<?> getColumnClass(int c) { return classes[c]; }
            @Override public boolean isCellEditable(int r, int c) {
                Object[] row = data.rows.get(r);
                return editable && data.edit.baseNames()[c] != null && !deleted.contains(row) && !stale.contains(row);
            }
            @Override public void setValueAt(Object v, int r, int c) { setCell(data.rows.get(r), c, v == null ? null : v.toString()); fireTableCellUpdated(r, c); }
        };
        table = new JTable(model) {
            @Override protected JTableHeader createDefaultTableHeader() {
                return new JTableHeader(columnModel) {
                    @Override public String getToolTipText(MouseEvent e) {
                        int c = columnAtPoint(e.getPoint());
                        if (c < 0) return null;
                        int mc = convertColumnIndexToModel(c);
                        return data.columns.get(mc) + " : " + data.types.get(mc);
                    }
                };
            }
        };
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.setFont(Fonts.mono(13));
        table.setSurrendersFocusOnKeystroke(true);
        // 수정 중에는 값이 문자열일 수 있으므로, 정렬 비교는 타입이 달라도 실패하지 않게 한다.
        sorter = new TableRowSorter<>(model) {
            @Override @SuppressWarnings("unchecked") public Comparator<?> getComparator(int c) {
                Comparator<Object> base = (Comparator<Object>) super.getComparator(c);
                return (Comparator<Object>) (a, b) -> {
                    try { if (base != null) return base.compare(a, b); }
                    catch (ClassCastException ignored) {}
                    return String.valueOf(a).compareTo(String.valueOf(b));
                };
            }
        };
        table.setRowSorter(sorter);
        CellRenderer rend = new CellRenderer();
        for (Class<?> k : new Class<?>[]{Object.class, Number.class, Boolean.class, java.util.Date.class})
            table.setDefaultRenderer(k, rend);
        if (editable) {
            // 값 타입(숫자/불린 등)에 상관없이 텍스트로 입력받고, 변환은 DB 서버가 한다.
            DefaultCellEditor text = new DefaultCellEditor(new JTextField());
            text.setClickCountToStart(2);
            for (Class<?> k : new Class<?>[]{Object.class, Number.class, Boolean.class}) table.setDefaultEditor(k, text);
        }
        fitColumns();

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && !editable) showCell(); }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });

        JTextField filter = new JTextField(18);
        filter.putClientProperty("JTextField.placeholderText", "필터 (모든 컬럼)");
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void upd() {
                String t = filter.getText();
                sorter.setRowFilter(t.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(t)));
                refreshInfo();
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        });
        JButton csv = new JButton("CSV 내보내기");
        csv.addActionListener(e -> export(true));
        JButton json = new JButton("JSON 내보내기");
        json.addActionListener(e -> export(false));
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(filter);
        bar.addSeparator();
        bar.add(csv);
        bar.add(json);
        bar.addSeparator();
        bar.add(info);

        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(bar);
        top.add(editBar());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        refreshInfo();
        refreshPending();
    }

    private JToolBar editBar() {
        JToolBar eb = new JToolBar();
        eb.setFloatable(false);
        if (!editable) {
            String why = editor == null ? null
                    : !editor.canEdit() ? "읽기 전용 접속입니다."
                    : "단일 테이블이고 PK 컬럼을 모두 포함한 select 결과만 수정할 수 있습니다.";
            if (why == null) { eb.setVisible(false); return eb; }
            JLabel l = new JLabel(" 읽기 전용 결과 ");
            l.setForeground(Color.GRAY);
            l.setToolTipText(why);
            eb.add(l);
            return eb;
        }
        JLabel target = new JLabel(" 편집 가능: " + data.edit.qualified() + " ");
        target.setToolTipText("셀을 더블클릭해 수정합니다. 적용하면 수동 커밋 트랜잭션으로 실행되며, Commit을 눌러야 확정됩니다.");
        JButton add = new JButton("행 추가"), del = new JButton("행 삭제");
        add.addActionListener(e -> addRow());
        del.addActionListener(e -> deleteSelected());
        applyBtn.addActionListener(e -> apply());
        discardBtn.addActionListener(e -> discard());
        eb.add(target);
        eb.addSeparator();
        eb.add(add);
        eb.add(del);
        eb.addSeparator();
        eb.add(applyBtn);
        eb.add(discardBtn);
        eb.addSeparator();
        eb.add(pendingLabel);
        return eb;
    }

    // ---- 값/변경 상태 ----

    private Object value(Object[] row, int c) {
        Map<Integer, String> m = updates.get(row);
        if (m != null && m.containsKey(c)) return m.get(c);
        return row[c];
    }

    private void setCell(Object[] row, int c, String s) {
        Map<Integer, String> m = updates.computeIfAbsent(row, k -> new HashMap<>());
        Object orig = row[c];
        boolean unchanged;
        if (inserted.contains(row)) unchanged = s == null || s.isEmpty();
        else if (orig == null) unchanged = s == null || s.isEmpty();   // NULL 셀을 열었다 닫기만 한 경우는 변경 아님
        else unchanged = orig.toString().equals(s);
        if (unchanged) m.remove(c); else m.put(c, s);
        if (m.isEmpty()) updates.remove(row);
        refreshPending();
    }

    private int pendingCount() {
        int n = 0;
        for (var e : updates.entrySet()) if (!deleted.contains(e.getKey()) && !inserted.contains(e.getKey())) n += e.getValue().size();
        for (Object[] r : deleted) if (!inserted.contains(r)) n++;
        for (Object[] r : inserted) if (!deleted.contains(r)) n++;
        return n;
    }

    boolean hasPending() { return pendingCount() > 0; }

    private void refreshPending() {
        int n = pendingCount();
        pendingLabel.setText(n == 0 ? " " : " 변경 " + n + "건 대기 중 (적용 시 수동 커밋) ");
        applyBtn.setEnabled(n > 0);
        discardBtn.setEnabled(n > 0);
        table.repaint();
    }

    private void addRow() {
        Object[] row = new Object[data.columns.size()];
        data.rows.add(row);
        inserted.add(row);
        int idx = data.rows.size() - 1;
        model.fireTableRowsInserted(idx, idx);
        int v = table.convertRowIndexToView(idx);
        if (v >= 0) {
            table.changeSelection(v, 0, false, false);
            table.scrollRectToVisible(table.getCellRect(v, 0, true));
        }
        refreshPending();
        refreshInfo();
    }

    private void deleteSelected() {
        for (int v : table.getSelectedRows()) {
            Object[] row = data.rows.get(table.convertRowIndexToModel(v));
            if (inserted.contains(row)) { data.rows.removeIf(x -> x == row); inserted.remove(row); updates.remove(row); }
            else deleted.add(row);
        }
        model.fireTableDataChanged();
        refreshPending();
        refreshInfo();
    }

    private void discard() {
        data.rows.removeIf(inserted::contains);
        updates.clear();
        deleted.clear();
        inserted.clear();
        model.fireTableDataChanged();
        refreshPending();
        refreshInfo();
    }

    private void apply() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        List<GridEdit.Op> ops = new ArrayList<>();
        for (Object[] row : data.rows)
            if (!inserted.contains(row) && !deleted.contains(row) && updates.containsKey(row))
                ops.add(new GridEdit.Op(GridEdit.Kind.UPDATE, row, new HashMap<>(updates.get(row))));
        for (Object[] row : data.rows)
            if (deleted.contains(row) && !inserted.contains(row)) ops.add(new GridEdit.Op(GridEdit.Kind.DELETE, row, Map.of()));
        for (Object[] row : data.rows)
            if (inserted.contains(row) && !deleted.contains(row))
                ops.add(new GridEdit.Op(GridEdit.Kind.INSERT, row, new HashMap<>(updates.getOrDefault(row, Map.of()))));
        if (ops.isEmpty()) return;
        editor.apply(data, ops, () -> onApplied(ops));
    }

    /** 적용 성공 후: 그리드 값을 새 값으로 확정 표시한다(커밋 전 상태). */
    private void onApplied(List<GridEdit.Op> ops) {
        Set<Integer> pk = new HashSet<>();
        for (int c : data.edit.pkIdx()) pk.add(c);
        for (GridEdit.Op op : ops) {
            switch (op.kind()) {
                case UPDATE -> {
                    op.values().forEach((c, v) -> op.row()[c] = v);
                    if (op.values().keySet().stream().anyMatch(pk::contains)) stale.add(op.row());
                }
                case DELETE -> data.rows.removeIf(x -> x == op.row());
                case INSERT -> {
                    op.values().forEach((c, v) -> op.row()[c] = v);
                    stale.add(op.row());   // 기본값/시퀀스 값은 다시 조회해야 알 수 있음
                }
            }
        }
        updates.clear();
        deleted.clear();
        inserted.clear();
        model.fireTableDataChanged();
        refreshPending();
        refreshInfo();
    }

    // ---- 표시 ----

    private void refreshInfo() {
        int shown = table.getRowCount(), total = data.rows.size();
        info.setText("  " + (shown == total ? total + " rows" : shown + " / " + total + " rows")
                + (data.truncated ? " (최대 행 수 제한으로 잘림)" : "") + " · " + data.millis + " ms");
    }

    private void fitColumns() {
        FontMetrics fm = table.getFontMetrics(table.getFont());
        for (int c = 0; c < table.getColumnCount(); c++) {
            int w = fm.stringWidth(data.columns.get(c)) + 40;
            for (int r = 0; r < Math.min(100, data.rows.size()); r++) {
                Object v = data.rows.get(r)[c];
                w = Math.max(w, fm.stringWidth(v == null ? "NULL" : oneLine(v.toString())) + 16);
            }
            table.getColumnModel().getColumn(c).setPreferredWidth(Math.min(w, 420));
        }
    }

    private static String oneLine(String s) {
        String t = s.length() > 500 ? s.substring(0, 500) + "…" : s;
        return t.replace("\r", "").replace("\n", "↵").replace("\t", "→");
    }

    private final class CellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            if (v == null) {
                setText("NULL");
                setFont(getFont().deriveFont(Font.ITALIC));
                if (!sel) setForeground(Color.GRAY);
            } else {
                setText(oneLine(v.toString()));
                setFont(t.getFont());
            }
            setHorizontalAlignment(v instanceof Number ? RIGHT : LEFT);
            if (!sel && editable) {
                Object[] row = data.rows.get(t.convertRowIndexToModel(r));
                int mc = t.convertColumnIndexToModel(c);
                boolean dark = FlatLaf.isLafDark();
                Color bg = null;
                if (deleted.contains(row)) bg = dark ? new Color(110, 40, 40) : new Color(255, 215, 215);
                else if (inserted.contains(row)) bg = dark ? new Color(40, 90, 50) : new Color(215, 245, 215);
                else if (updates.getOrDefault(row, Map.of()).containsKey(mc)) bg = dark ? new Color(100, 80, 20) : new Color(255, 240, 190);
                setBackground(bg != null ? bg : t.getBackground());
                if (stale.contains(row) && bg == null) setForeground(Color.GRAY);
            }
            return this;
        }
    }

    // ---- 메뉴/복사/내보내기 ----

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
        if (r >= 0 && !table.isRowSelected(r)) table.changeSelection(r, c, false, false);
        JPopupMenu m = new JPopupMenu();
        add(m, "셀 값 보기", this::showCell);
        if (editable) {
            add(m, "NULL로 설정", this::setSelectedNull);
            add(m, "행 삭제", this::deleteSelected);
            m.addSeparator();
        }
        add(m, "복사 (TSV)", () -> copy(format(selectedOrAll(), '\t', false, false)));
        add(m, "헤더 포함 복사", () -> copy(format(selectedOrAll(), '\t', true, false)));
        add(m, "CSV로 복사", () -> copy(format(selectedOrAll(), ',', true, true)));
        add(m, "JSON으로 복사", () -> copy(toJson(selectedOrAll())));
        add(m, "INSERT 문으로 복사", () -> copy(toInserts(selectedOrAll())));
        m.show(table, e.getX(), e.getY());
    }

    private void setSelectedNull() {
        for (int v : table.getSelectedRows())
            for (int vc : table.getSelectedColumns()) {
                int mr = table.convertRowIndexToModel(v), mc = table.convertColumnIndexToModel(vc);
                if (!model.isCellEditable(mr, mc)) continue;
                Object[] row = data.rows.get(mr);
                Map<Integer, String> m = updates.computeIfAbsent(row, k -> new HashMap<>());
                if (row[mc] == null && !inserted.contains(row)) m.remove(mc);
                else if (inserted.contains(row)) m.remove(mc);   // 신규 행의 미입력 = 기본값
                else m.put(mc, null);
                if (m.isEmpty()) updates.remove(row);
            }
        model.fireTableDataChanged();
        refreshPending();
    }

    private static void add(JPopupMenu m, String t, Runnable r) {
        JMenuItem i = new JMenuItem(t);
        i.addActionListener(e -> r.run());
        m.add(i);
    }

    private void copy(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private void showCell() {
        int r = table.getSelectedRow(), c = table.getSelectedColumn();
        if (r < 0 || c < 0) return;
        Object v = table.getValueAt(r, c);
        JTextArea ta = new JTextArea(v == null ? "NULL" : v.toString(), 16, 70);
        ta.setFont(Fonts.mono(13));
        ta.setLineWrap(true);
        ta.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(ta), table.getColumnName(c), JOptionPane.PLAIN_MESSAGE);
    }

    /** 선택된 행(뷰 순서)의 모델 인덱스. 선택이 없으면 보이는 전체 행. */
    private List<Integer> selectedOrAll() {
        List<Integer> out = new ArrayList<>();
        int[] sel = table.getSelectedRows();
        if (sel.length == 0) for (int i = 0; i < table.getRowCount(); i++) out.add(table.convertRowIndexToModel(i));
        else for (int v : sel) out.add(table.convertRowIndexToModel(v));
        return out;
    }

    private List<Integer> allVisible() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < table.getRowCount(); i++) out.add(table.convertRowIndexToModel(i));
        return out;
    }

    private Object val(int r, int c) { return model.getValueAt(r, c); }

    private String format(List<Integer> rows, char sep, boolean header, boolean csvQuote) {
        StringBuilder sb = new StringBuilder();
        if (header) sb.append(join(data.columns, sep, csvQuote)).append('\n');
        for (int r : rows) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < data.columns.size(); c++) { Object o = val(r, c); cells.add(o == null ? "" : o.toString()); }
            sb.append(join(cells, sep, csvQuote)).append('\n');
        }
        return sb.toString();
    }

    private static String join(List<String> cells, char sep, boolean quote) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(sep);
            String s = cells.get(i);
            if (quote && (s.indexOf(sep) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0))
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            sb.append(s);
        }
        return sb.toString();
    }

    private String toJson(List<Integer> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        boolean firstRow = true;
        for (int r : rows) {
            if (!firstRow) sb.append(",\n");
            firstRow = false;
            sb.append("  {");
            for (int c = 0; c < data.columns.size(); c++) {
                if (c > 0) sb.append(", ");
                sb.append(jsonStr(data.columns.get(c))).append(": ");
                Object o = val(r, c);
                if (o == null) sb.append("null");
                else if (o instanceof Number || o instanceof Boolean) sb.append(o);
                else sb.append(jsonStr(o.toString()));
            }
            sb.append('}');
        }
        return sb.append("\n]\n").toString();
    }

    static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
            }
        }
        return sb.append('"').toString();
    }

    private String toInserts(List<Integer> rows) {
        String tbl = JOptionPane.showInputDialog(this, "대상 테이블 이름", data.edit != null ? data.edit.qualified() : "table_name");
        if (tbl == null || tbl.isBlank()) return "";
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < data.columns.size(); i++) cols.append(i > 0 ? ", " : "").append(Ddl.quote(data.columns.get(i)));
        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            sb.append("insert into ").append(tbl.trim()).append(" (").append(cols).append(") values (");
            for (int c = 0; c < data.columns.size(); c++) {
                if (c > 0) sb.append(", ");
                Object o = val(r, c);
                if (o == null) sb.append("NULL");
                else if (o instanceof Number || o instanceof Boolean) sb.append(o);
                else sb.append('\'').append(o.toString().replace("'", "''")).append('\'');
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    private void export(boolean csv) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(csv ? "result.csv" : "result.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String content = csv ? format(allVisible(), ',', true, true) : toJson(allVisible());
            Files.writeString(fc.getSelectedFile().toPath(), content, StandardCharsets.UTF_8);
            info.setText("  저장됨: " + fc.getSelectedFile().getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "저장 실패: " + ex.getMessage());
        }
    }
}
