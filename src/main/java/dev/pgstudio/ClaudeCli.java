package dev.pgstudio;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 로컬 `claude` CLI(비대화형 -p 모드)로 질문을 보낸다.
 * 모든 도구와 MCP 를 끄고, 빈 임시 디렉터리에서 실행하며, 프롬프트는 stdin 으로만 전달한다(프로세스 목록에 노출 방지).
 */
final class ClaudeCli {
    private ClaudeCli() {}

    static final String SYSTEM_PROMPT = "You are a PostgreSQL assistant embedded in a desktop SQL client (PG Studio). "
            + "Reply in the same language as the user (Korean by default) and be concise. "
            + "Write SQL in PostgreSQL syntax and put each statement in its own ```sql fenced block; qualify tables with their schema. "
            + "When the user refers to \"the editor\" or \"my query\", use the editor content provided in the prompt. "
            + "You cannot access the database or any files. The client can execute only single read-only SELECT statements "
            + "from this panel; for INSERT/UPDATE/DELETE/DDL, give the SQL but state clearly that the user must review and run it manually. "
            + "Never ask for passwords or personal data. If the schema is missing or insufficient, state your assumptions.";

    static List<String> command(String exe) {
        return List.of(exe, "-p", "--tools", "", "--strict-mcp-config", "--no-session-persistence",
                "--output-format", "text", "--append-system-prompt", SYSTEM_PROMPT);
    }

    /** 스키마(선택) + 최근 대화 + 새 질문으로 stdin 프롬프트를 만든다. history 항목: {role, text}. */
    static String buildPrompt(String schema, String editorSql, List<String[]> history, String question) {
        StringBuilder sb = new StringBuilder();
        if (schema != null && !schema.isBlank())
            sb.append("Database schema (table names and column names only; no row data):\n").append(schema).append("\n\n");
        if (editorSql != null && !editorSql.isBlank())
            sb.append("Current content of the user's SQL editor (the selected text if any selection exists):\n```sql\n")
                    .append(editorSql.strip()).append("\n```\n\n");
        if (!history.isEmpty()) {
            sb.append("Conversation so far:\n");
            for (String[] h : history) sb.append(h[0]).append(": ").append(h[1]).append("\n\n");
        }
        sb.append("User: ").append(question);
        return sb.toString();
    }

    /** 자동완성 캐시(schema -> table -> columns)에서 스키마 설명 텍스트를 만든다. 너무 크면 잘라낸다. */
    static String schemaText(Map<String, Map<String, List<String>>> completion, int maxChars) {
        return schemaText(completion, maxChars, null);
    }

    /**
     * hint(질문·에디터 SQL)에 이름이 나온 테이블, 그다음 이름이 나온 스키마의 테이블을 먼저 넣는다.
     * 예산을 넘으면 나머지 테이블은 컬럼 없이 이름만 넣는다(알파벳 뒤쪽 스키마가 통째로 빠지지 않도록).
     */
    static String schemaText(Map<String, Map<String, List<String>>> completion, int maxChars, String hint) {
        Set<String> words = new HashSet<>(Arrays.asList(
                (hint == null ? "" : hint.toLowerCase(Locale.ROOT)).split("[^a-z0-9_$]+")));
        List<String[]> tables = new ArrayList<>();   // {schema, table, columns, rank}
        for (var s : completion.entrySet())
            for (var t : s.getValue().entrySet()) {
                int rank = words.contains(t.getKey().toLowerCase(Locale.ROOT)) ? 0
                        : words.contains(s.getKey().toLowerCase(Locale.ROOT)) ? 1 : 2;
                tables.add(new String[]{s.getKey(), t.getKey(), String.join(", ", t.getValue()), String.valueOf(rank)});
            }
        tables.sort(Comparator.comparing(t -> t[3]));   // 안정 정렬: 같은 순위는 원래(알파벳) 순서 유지

        // 이름만 넣을 테이블 몫으로 예산의 최대 1/3 을 남겨 둔다
        int namesSize = 0;
        for (String[] t : tables) namesSize += t[0].length() + t[1].length() + 3;
        int detailBudget = namesSize + tables.stream().mapToInt(t -> t[2].length() + 3).sum() <= maxChars
                ? maxChars : maxChars - Math.min(namesSize, maxChars / 3);
        StringBuilder sb = new StringBuilder();
        List<String> namesOnly = new ArrayList<>();
        for (String[] t : tables) {
            String line = t[0] + "." + t[1] + "(" + t[2] + ")\n";
            if (namesOnly.isEmpty() && sb.length() + line.length() <= detailBudget) sb.append(line);
            else namesOnly.add(t[0] + "." + t[1]);
        }
        if (namesOnly.isEmpty()) return sb.toString();
        String head = "Other tables (columns omitted): ";
        int added = 0;
        if (sb.length() + head.length() < maxChars) {
            sb.append(head);
            for (String n : namesOnly) {
                if (sb.length() + n.length() + 2 > maxChars) break;
                sb.append(added++ == 0 ? "" : ", ").append(n);
            }
            sb.append("\n");
        }
        int omitted = namesOnly.size() - added;
        if (omitted > 0) sb.append("... (").append(omitted).append("개 테이블 생략됨)\n");
        return sb.toString();
    }

    // ---- 실행 파일 찾기 ----

    static List<Path> candidateDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) for (String p : path.split(File.pathSeparator)) if (!p.isBlank()) dirs.add(Path.of(p));
        Path home = Path.of(System.getProperty("user.home"));
        for (String d : new String[]{".local/bin", ".claude/local", ".npm-global/bin", ".bun/bin", ".volta/bin"}) dirs.add(home.resolve(d));
        for (String d : new String[]{"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"}) dirs.add(Path.of(d));
        return new ArrayList<>(dirs);
    }

    static String find(List<Path> dirs) {
        for (Path d : dirs)
            for (String name : new String[]{"claude", "claude.exe", "claude.cmd"}) {   // 뒤의 둘은 Windows
                Path c = d.resolve(name);
                if (Files.isRegularFile(c) && Files.isExecutable(c)) return c.toString();
            }
        return null;
    }

    /** 저장된 경로 → PATH/일반 설치 위치 → 로그인 셸 순으로 찾는다. 못 찾으면 null. */
    static String resolve() {
        String saved = Main.claudePath();
        if (!saved.isBlank() && Files.isExecutable(Path.of(saved))) return saved;
        String found = find(candidateDirs());
        if (found != null) return found;
        try {
            Process p = new ProcessBuilder("/bin/zsh", "-lic", "command -v claude").redirectErrorStream(true).start();
            if (p.waitFor(8, TimeUnit.SECONDS)) {
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                String last = out.lines().reduce((a, b) -> b).orElse("");
                if (last.startsWith("/") && Files.isExecutable(Path.of(last))) return last;
            } else p.destroyForcibly();
        } catch (IOException | InterruptedException ignored) {}
        return null;
    }

    // ---- 실행 ----

    static final class Handle {
        private final Process process;
        private final Path cwd, errFile;
        private volatile boolean cancelled;

        private Handle(Process p, Path cwd, Path errFile) { this.process = p; this.cwd = cwd; this.errFile = errFile; }

        void cancel() { cancelled = true; process.destroyForcibly(); }

        /** 응답 텍스트를 반환한다. 실패/취소/시간 초과 시 IOException. */
        String await(long timeoutSeconds) throws IOException, InterruptedException {
            try {
                byte[] out = process.getInputStream().readAllBytes();
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("응답 시간이 초과되었습니다(" + timeoutSeconds + "초).");
                }
                String text = new String(out, StandardCharsets.UTF_8).strip();
                if (cancelled) throw new IOException("중지되었습니다.");
                if (process.exitValue() != 0) {
                    String err = Files.exists(errFile) ? Files.readString(errFile, StandardCharsets.UTF_8).strip() : "";
                    throw new IOException("claude 종료 코드 " + process.exitValue() + (err.isEmpty() ? (text.isEmpty() ? "" : ": " + text) : ": " + err));
                }
                if (text.isEmpty()) throw new IOException("빈 응답입니다.");
                return text;
            } finally {
                try { Files.deleteIfExists(errFile); Files.deleteIfExists(cwd); } catch (IOException ignored) {}
            }
        }
    }

    static Handle start(String exe, String stdinPrompt) throws IOException { return start(command(exe), stdinPrompt); }

    /** 경로를 찾고, 못 찾으면 파일 선택창으로 물어 저장한다. 취소/실패하면 null. EDT 에서 호출. */
    static String resolveInteractive(Component parent) {
        String exe = resolve();
        if (exe != null) return exe;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("claude 실행 파일을 선택하세요");
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        File f = fc.getSelectedFile();
        if (!f.canExecute()) {
            JOptionPane.showMessageDialog(parent, "실행할 수 없는 파일입니다: " + f);
            return null;
        }
        Main.setClaudePath(f.getAbsolutePath());
        return f.getAbsolutePath();
    }

    static Handle start(List<String> command, String stdinPrompt) throws IOException {
        Path cwd = Files.createTempDirectory("pgstudio-claude-");
        Path err = Files.createTempFile(cwd, "stderr", ".txt");
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectError(err.toFile());
        String extra = String.join(File.pathSeparator, candidateDirs().stream().map(Path::toString).toList());
        pb.environment().merge("PATH", extra, (old, add) -> old + File.pathSeparator + add);
        Process p = pb.start();
        try (var os = p.getOutputStream()) { os.write(stdinPrompt.getBytes(StandardCharsets.UTF_8)); }
        return new Handle(p, cwd, err);
    }
}
