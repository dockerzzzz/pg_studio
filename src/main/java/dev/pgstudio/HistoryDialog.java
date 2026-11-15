package dev.pgstudio;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

final class HistoryDialog {
    private HistoryDialog() {}

    static void show(Window owner, Consumer<String> insert) {
        JDialog d = new JDialog(owner, "쿼리 이력", Dialog.ModalityType.MODELESS);
        DefaultTableModel m = new DefaultTableModel(new String[]{"시각", "접속", "ms", "결과", "SQL"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String[] r : History.read(1000)) m.addRow(new Object[]{r[0], r[1], r[2], r[3], r[4].replace('\n', ' ')});
        JTable t = new JTable(m);
        t.setFont(Fonts.mono(12));
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(m);
        t.setRowSorter(sorter);
        int[] w = {150, 120, 60, 50, 700};
        for (int i = 0; i < w.length; i++) t.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "검색");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void upd() {
                sorter.setRowFilter(search.getText().isEmpty() ? null
                        : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(search.getText())));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        });
        java.util.List<String[]> raw = History.read(1000);
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && t.getSelectedRow() >= 0)
                    insert.accept(raw.get(t.convertRowIndexToModel(t.getSelectedRow()))[4]);
            }
        });
        d.add(search, BorderLayout.NORTH);
        d.add(new JScrollPane(t));
        d.add(new JLabel("  더블클릭하면 에디터에 삽입합니다."), BorderLayout.SOUTH);
        d.setSize(1100, 480);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
