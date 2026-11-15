package dev.pgstudio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Jira 접속 설정(~/.pgstudio/jira.properties). 주소/이메일/JQL 은 항상 저장하고,
 * API 토큰은 사용자가 "토큰 저장"을 선택한 경우에만 평문으로 저장한다(파일 권한 600).
 */
final class JiraSettings {
    private JiraSettings() {}

    static final String DEFAULT_JQL = "assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC";
    private static final Path FILE = ProfileStore.DIR.resolve("jira.properties");

    record Config(String baseUrl, String email, String token, String jql) {
        boolean configured() { return !baseUrl.isBlank() && !email.isBlank(); }
        Config withToken(String t) { return new Config(baseUrl, email, t, jql); }
        Config withJql(String q) { return new Config(baseUrl, email, token, q); }
    }

    static Config load() {
        Properties p = new Properties();
        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) { p.load(in); } catch (IOException ignored) {}
        }
        return new Config(p.getProperty("url", ""), p.getProperty("email", ""), p.getProperty("token", ""),
                p.getProperty("jql", DEFAULT_JQL));
    }

    static void save(Config c, boolean saveToken) {
        Properties p = new Properties();
        p.setProperty("url", c.baseUrl());
        p.setProperty("email", c.email());
        p.setProperty("jql", c.jql());
        if (saveToken && !c.token().isBlank()) p.setProperty("token", c.token());
        ProfileStore.writeSecure(FILE, p, "PG Studio Jira settings");
    }
}
