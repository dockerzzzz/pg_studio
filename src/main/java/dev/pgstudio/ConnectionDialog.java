package dev.pgstudio;

import javax.swing.*;
import java.awt.*;
import java.sql.Connection;
import java.util.UUID;

/** 접속 정보 입력/편집/테스트. 비밀번호는 저장하지 않는다. */
final class ConnectionDialog {
    record Result(ConnectionProfile profile, String password, boolean savePassword) {}

    private ConnectionDialog() {}

    static Result show(Component parent, ConnectionProfile existing, String savedPassword) {
        JTextField name = new JTextField(existing == null ? "local" : existing.name(), 24);
        JTextField host = new JTextField(existing == null ? "localhost" : existing.host());
        JTextField port = new JTextField(existing == null ? "5432" : String.valueOf(existing.port()));
        JTextField db = new JTextField(existing == null ? "postgres" : existing.database());
        JTextField user = new JTextField(existing == null ? "postgres" : existing.user());
        JPasswordField pw = new JPasswordField(savedPassword == null ? "" : savedPassword);
        JCheckBox savePw = new JCheckBox("비밀번호 저장 (평문, ~/.pgstudio, 테스트용)", savedPassword != null);
        JComboBox<String> ssl = new JComboBox<>(new String[]{"prefer", "disable", "require", "verify-ca", "verify-full"});
        if (existing != null) ssl.setSelectedItem(existing.sslmode());
        JCheckBox ro = new JCheckBox("읽기 전용 (쓰기 쿼리를 서버에서 거부)", existing != null && existing.readOnly());
        JTextField url = new JTextField(existing == null ? "" : existing.jdbcUrl());
        url.putClientProperty("JTextField.placeholderText", "비워두면 위 항목으로 구성 (jdbc:postgresql://...)");
        JLabel result = new JLabel(" ");

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {{"이름", name}, {"Host", host}, {"Port", port}, {"Database", db}, {"User", user},
                {"Password", pw}, {"", savePw}, {"SSL mode", ssl}, {"", ro}, {"JDBC URL", url}};
        for (int i = 0; i < rows.length; i++) {
            g.gridx = 0; g.gridy = i; g.weightx = 0;
            p.add(new JLabel((String) rows[i][0]), g);
            g.gridx = 1; g.weightx = 1;
            p.add((Component) rows[i][1], g);
        }
        JButton test = new JButton("연결 테스트");
        g.gridx = 1; g.gridy = rows.length; p.add(test, g);
        g.gridy = rows.length + 1; p.add(result, g);

        java.util.function.Supplier<ConnectionProfile> build = () -> {
            int prt;
            try { prt = Integer.parseInt(port.getText().trim()); } catch (NumberFormatException e) { prt = 5432; }
            return new ConnectionProfile(existing == null ? UUID.randomUUID().toString().substring(0, 8) : existing.id(),
                    name.getText().trim().isEmpty() ? host.getText().trim() : name.getText().trim(),
                    host.getText().trim(), prt, db.getText().trim(), user.getText().trim(),
                    (String) ssl.getSelectedItem(), ro.isSelected(), url.getText().trim());
        };
        test.addActionListener(e -> {
            result.setText("연결 중...");
            ConnectionProfile cp = build.get();
            String pass = new String(pw.getPassword());
            new Thread(() -> {
                String msg;
                try (Connection c = Session.open(cp, pass)) {
                    msg = "성공: " + c.getMetaData().getDatabaseProductVersion();
                } catch (Exception ex) { msg = "실패: " + ex.getMessage(); }
                String m = msg;
                SwingUtilities.invokeLater(() -> result.setText("<html>" + m.replace("<", "&lt;") + "</html>"));
            }, "conn-test").start();
        });

        int r = JOptionPane.showConfirmDialog(parent, p, existing == null ? "새 접속" : "접속 편집",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return null;
        return new Result(build.get(), new String(pw.getPassword()), savePw.isSelected());
    }
}
