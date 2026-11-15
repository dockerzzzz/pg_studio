package dev.pgstudio;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.function.Consumer;

/** 스키마 ERD 탭: 도구 모음 + 캔버스. */
final class ErdPanel extends JPanel {
    private final Session session;
    private final String schema;
    private final ErdCanvas canvas;
    private final JLabel status = new JLabel(" ");
    private int generation;

    ErdPanel(Session session, String schema, Consumer<ErdModel.Table> onOpen) {
        super(new BorderLayout());
        this.session = session;
        this.schema = schema;
        this.canvas = new ErdCanvas(onOpen);

        JTextField search = new JTextField(14);
        search.putClientProperty("JTextField.placeholderText", "테이블 찾기 (Enter)");
        search.addActionListener(e -> { if (!canvas.focusTable(search.getText())) status.setText("'" + search.getText() + "' 테이블을 찾지 못했습니다."); });
        JComboBox<ErdCanvas.Mode> mode = new JComboBox<>(ErdCanvas.Mode.values());
        mode.addActionListener(e -> canvas.setMode((ErdCanvas.Mode) mode.getSelectedItem()));
        JCheckBox inferred = new JCheckBox("추정 관계 표시", true);
        inferred.setToolTipText("FK 제약은 없지만 이름(예: customer_id → customers.id)으로 짐작되는 관계를 흐린 점선으로 표시");
        inferred.addActionListener(e -> canvas.setShowInferred(inferred.isSelected()));
        JButton fit = button("맞춤", "전체가 보이도록 맞춤", e -> canvas.fit());
        JButton zin = button("+", "확대", e -> canvas.zoom(1.25, center()));
        JButton zout = button("−", "축소", e -> canvas.zoom(0.8, center()));
        JButton layout = button("자동 배치", "테이블 위치를 자동으로 다시 배치", e -> canvas.autoLayout());
        JButton png = button("PNG 저장", "다이어그램을 이미지로 저장", e -> exportPng());
        JButton reload = button("새로고침", "DB에서 다시 읽기", e -> load());
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        for (Component c : new Component[]{search, mode, inferred, fit, zin, zout, layout, png, reload}) bar.add(c);

        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(bar, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        load();
    }

    private JButton button(String text, String tip, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(l);
        return b;
    }

    private Point center() { return new Point(canvas.getWidth() / 2, canvas.getHeight() / 2); }

    ErdCanvas canvas() { return canvas; }

    void load() {
        final int gen = ++generation;
        status.setText("'" + schema + "' 스키마를 읽는 중…");
        Thread t = new Thread(() -> {
            ErdModel m = null;
            String err = null;
            try { m = ErdModel.load(session.meta, schema); }
            catch (SQLException e) { err = e.getMessage(); }
            final ErdModel model = m;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) return;
                if (model == null) { status.setText("불러오기 실패: " + error); return; }
                canvas.setModel(model, "표시할 테이블이 없습니다. ('" + schema + "' 스키마에 일반 테이블이 없음)");
                status.setText("테이블 " + model.tables.values().stream().filter(x -> !x.external).count() + "개 · 관계 " + model.fks.size()
                        + "개   |   ─ 식별  ╌ 비식별  ┈ 추정(FK 없음)   ·   드래그: 이동 · 휠: 스크롤 · Ctrl/Cmd+휠: 확대/축소 · 더블클릭: Select top 100");
            });
        }, "erd-load");
        t.setDaemon(true);
        t.start();
    }

    private void exportPng() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("erd-" + schema + ".png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            canvas.exportPng(fc.getSelectedFile());
            status.setText("저장됨: " + fc.getSelectedFile().getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "저장 실패: " + e.getMessage());
        }
    }
}
