package dev.pgstudio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * ~/.pgstudio/connections.properties 에 접속 프로필을 저장한다.
 * 비밀번호는 사용자가 "비밀번호 저장"을 선택한 접속에 한해 평문으로 기록된다(테스트용). 파일 권한은 소유자 전용(600).
 */
public final class ProfileStore {
    public static final Path DIR = Path.of(System.getProperty("user.home"), ".pgstudio");
    private static final Path FILE = DIR.resolve("connections.properties");

    private ProfileStore() {}

    public static List<ConnectionProfile> load() {
        List<ConnectionProfile> out = new ArrayList<>();
        if (!Files.exists(FILE)) return out;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) { p.load(in); } catch (IOException e) { return out; }
        String ids = p.getProperty("ids", "");
        for (String id : ids.split(",")) {
            if (id.isBlank()) continue;
            String k = id + ".";
            int port;
            try { port = Integer.parseInt(p.getProperty(k + "port", "5432")); } catch (NumberFormatException e) { port = 5432; }
            out.add(new ConnectionProfile(id, p.getProperty(k + "name", id), p.getProperty(k + "host", "localhost"), port,
                    p.getProperty(k + "database", "postgres"), p.getProperty(k + "user", "postgres"),
                    p.getProperty(k + "sslmode", "prefer"), Boolean.parseBoolean(p.getProperty(k + "readOnly", "false")),
                    p.getProperty(k + "jdbcUrl", "")));
        }
        return out;
    }

    /** 평문으로 저장된 비밀번호 (profileId -> password). */
    public static Map<String, String> loadPasswords() {
        Map<String, String> out = new HashMap<>();
        if (!Files.exists(FILE)) return out;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) { p.load(in); } catch (IOException e) { return out; }
        for (String id : p.getProperty("ids", "").split(",")) {
            String pw = p.getProperty(id + ".password");
            if (!id.isBlank() && pw != null) out.put(id, pw);
        }
        return out;
    }

    public static void save(List<ConnectionProfile> profiles) { save(profiles, Map.of()); }

    public static void save(List<ConnectionProfile> profiles, Map<String, String> savedPasswords) {
        Properties p = new Properties();
        StringBuilder ids = new StringBuilder();
        for (ConnectionProfile c : profiles) {
            if (!ids.isEmpty()) ids.append(',');
            ids.append(c.id());
            String k = c.id() + ".";
            p.setProperty(k + "name", c.name());
            p.setProperty(k + "host", c.host());
            p.setProperty(k + "port", String.valueOf(c.port()));
            p.setProperty(k + "database", c.database());
            p.setProperty(k + "user", c.user());
            p.setProperty(k + "sslmode", c.sslmode());
            p.setProperty(k + "readOnly", String.valueOf(c.readOnly()));
            p.setProperty(k + "jdbcUrl", c.jdbcUrl() == null ? "" : c.jdbcUrl());
            String pw = savedPasswords.get(c.id());
            if (pw != null) p.setProperty(k + "password", pw);
        }
        p.setProperty("ids", ids.toString());
        try {
            Files.createDirectories(DIR);
            // 비밀번호가 들어갈 수 있으므로 먼저 600 권한으로 만든 뒤 내용을 쓴다.
            try {
                if (!Files.exists(FILE))
                    Files.createFile(FILE, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                else Files.setPosixFilePermissions(FILE, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {}
            try (OutputStream out = Files.newOutputStream(FILE)) { p.store(out, "PG Studio connections"); }
        } catch (IOException ignored) {}
    }
}
