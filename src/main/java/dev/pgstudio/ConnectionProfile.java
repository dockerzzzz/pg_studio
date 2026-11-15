package dev.pgstudio;

/** 저장되는 접속 정보. 비밀번호는 포함하지 않는다(메모리에서만 유지). */
public record ConnectionProfile(String id, String name, String host, int port, String database,
                                String user, String sslmode, boolean readOnly, String jdbcUrl) {

    public String url() {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) return jdbcUrl.trim();
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    @Override public String toString() { return name; }
}
