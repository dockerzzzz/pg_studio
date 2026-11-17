package dev.pgstudio;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** 하나의 DB 접속(메타데이터용 연결 + 콘솔별 연결 생성기). */
public final class Session implements AutoCloseable {
    public final ConnectionProfile profile;
    private final String password;
    public final Connection meta;
    /** schema -> table -> columns (자동완성용). */
    public volatile Map<String, Map<String, List<String>>> completion = Map.of();
    public final CompletableFuture<Void> ready = new CompletableFuture<>();

    private Session(ConnectionProfile profile, String password, Connection meta) {
        this.profile = profile; this.password = password; this.meta = meta;
    }

    public static Connection open(ConnectionProfile p, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", p.user());
        props.setProperty("password", password == null ? "" : password);
        props.setProperty("ApplicationName", "pg-studio");
        props.setProperty("connectTimeout", "10");
        // autocommit 모드에서도 읽기 전용이 적용되도록 (기본값은 트랜잭션 안에서만 적용됨)
        if (p.readOnly()) props.setProperty("readOnlyMode", "always");
        if ((p.jdbcUrl() == null || p.jdbcUrl().isBlank()) && p.sslmode() != null && !p.sslmode().isBlank()
                && !p.sslmode().equals("prefer")) props.setProperty("sslmode", p.sslmode());
        Connection c = DriverManager.getConnection(p.url(), props);
        c.setReadOnly(p.readOnly());
        return c;
    }

    public static Session connect(ConnectionProfile p, String password) throws SQLException {
        Session s = new Session(p, password, open(p, password));
        Thread t = new Thread(() -> { s.loadCompletion(); s.ready.complete(null); }, "completion-loader");
        t.setDaemon(true);
        t.start();
        return s;
    }

    public Connection newConnection() throws SQLException { return open(profile, password); }

    /** 접속 프로필의 설정과 무관하게 항상 서버가 쓰기를 거부하는 읽기 전용 연결 (AI 패널의 SELECT 실행용). */
    public Connection newReadOnlyConnection() throws SQLException {
        ConnectionProfile p = profile;
        ConnectionProfile ro = new ConnectionProfile(p.id(), p.name(), p.host(), p.port(), p.database(), p.user(),
                p.sslmode(), true, p.jdbcUrl());
        return open(ro, password);
    }

    private void loadCompletion() {
        Map<String, Map<String, List<String>>> m = new TreeMap<>();
        String q = "select n.nspname, c.relname, a.attname from pg_attribute a "
                + "join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace "
                + "where a.attnum > 0 and not a.attisdropped and c.relkind in ('r','p','v','m','f') "
                + "and n.nspname not like 'pg\\_%' and n.nspname <> 'information_schema' "
                + "order by 1, 2, a.attnum limit 200000";
        try (Statement st = meta.createStatement(); ResultSet rs = st.executeQuery(q)) {
            while (rs.next())
                m.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                        .computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3));
        } catch (SQLException ignored) {}
        completion = m;
    }

    public void reloadCompletion() {
        Thread t = new Thread(this::loadCompletion, "completion-reload");
        t.setDaemon(true);
        t.start();
    }

    @Override public void close() {
        try { meta.close(); } catch (SQLException ignored) {}
    }
}
