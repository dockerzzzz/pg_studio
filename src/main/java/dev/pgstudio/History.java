package dev.pgstudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 실행 이력(~/.pgstudio/history.tsv). 형식: 시각 \t 접속 \t ms \t OK|ERR \t SQL(이스케이프). */
final class History {
    private static final Path FILE = ProfileStore.DIR.resolve("history.tsv");
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private History() {}

    static synchronized void add(String profile, long ms, boolean ok, String sql) {
        try {
            Files.createDirectories(ProfileStore.DIR);
            String line = LocalDateTime.now().format(F) + "\t" + profile.replace('\t', ' ') + "\t" + ms + "\t"
                    + (ok ? "OK" : "ERR") + "\t" + esc(sql) + "\n";
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    /** 최신순. */
    static synchronized List<String[]> read(int max) {
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(FILE)) return out;
        try {
            List<String> lines = Files.readAllLines(FILE, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
                String[] p = lines.get(i).split("\t", 5);
                if (p.length == 5) { p[4] = unesc(p[4]); out.add(p); }
            }
        } catch (IOException ignored) {}
        return out;
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", ""); }

    static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }
}
