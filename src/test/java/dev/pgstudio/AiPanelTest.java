package dev.pgstudio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiPanelTest {
    // ---- 실행 가능 SQL 검사 ----
    @Test void readOnlyCheckAllowsOnlySingleSelect() {
        assertNull(ReadOnlySql.check("select * from shop.orders where total > 100;"));
        assertNull(ReadOnlySql.check("-- 주석\nwith x as (select 1) select * from x"));
        assertNull(ReadOnlySql.check("select 'update delete drop' as txt"), "문자열 안의 키워드는 무시");
        assertNull(ReadOnlySql.check("select \"update\" from t"), "따옴표 식별자 안의 키워드는 무시");
        assertNull(ReadOnlySql.check("select updated_at, created_by from t"), "부분 일치는 허용");
        assertNotNull(ReadOnlySql.check("select 1; select 2"), "여러 문장 금지");
        assertNotNull(ReadOnlySql.check("delete from t where id = 1"));
        assertNotNull(ReadOnlySql.check("update t set a = 1 where id = 1"));
        assertNotNull(ReadOnlySql.check("insert into t values (1)"));
        assertNotNull(ReadOnlySql.check("drop table t"));
        assertNotNull(ReadOnlySql.check("with d as (delete from t returning *) select * from d"), "데이터 변경 CTE 금지");
        assertNotNull(ReadOnlySql.check("select pg_terminate_backend(123)"));
        assertNotNull(ReadOnlySql.check("select nextval('s')"));
        assertNotNull(ReadOnlySql.check("select set_config('a','b',false)"));
        assertNotNull(ReadOnlySql.check("copy t to '/tmp/x'"));
        assertNotNull(ReadOnlySql.check("   "));
        assertEquals("select 1", ReadOnlySql.statement("select 1;"));
    }

    // ---- 외부 전송 전 민감정보 차단 ----
    @Test void sensitiveDataIsDetected() {
        assertNotNull(SensitiveData.find("고객 900101-1234567 조회"));
        assertNotNull(SensitiveData.find("카드 1234-5678-9012-3456"));
        assertNotNull(SensitiveData.find("password = hunter2!"));
        assertNotNull(SensitiveData.find("key sk-abcdefghijklmnopqrstuvwxyz"));
        assertNotNull(SensitiveData.find("AKIAABCDEFGHIJKLMNOP"));
        assertNull(SensitiveData.find("지난달 송금 건수를 국가별로 집계하는 쿼리"));
        assertNull(SensitiveData.find("select count(*) from shop.orders where id = 12345"));
    }

    // ---- 응답 파싱 ----
    @Test void parsesTextAndSqlBlocks() {
        var segs = ChatParser.parse("설명입니다.\n```sql\nselect 1;\nselect 2;\n```\n끝\n```\nplain\n```");
        assertEquals(4, segs.size());
        assertFalse(segs.get(0).code());
        assertTrue(segs.get(1).code());
        assertEquals("sql", segs.get(1).lang());
        assertEquals("select 1;\nselect 2;", segs.get(1).text());
        assertEquals("끝", segs.get(2).text());
        assertEquals("", segs.get(3).lang());
        var open = ChatParser.parse("x\n```sql\nselect 1");   // 닫히지 않은 블록
        assertTrue(open.get(1).code());
    }

    // ---- CLI 명령/프롬프트 ----
    @Test void commandDisablesToolsAndKeepsPromptOffArgs() {
        List<String> cmd = ClaudeCli.command("/x/claude");
        assertEquals("/x/claude", cmd.get(0));
        assertTrue(cmd.contains("-p"));
        int i = cmd.indexOf("--tools");
        assertEquals("", cmd.get(i + 1), "모든 도구 비활성화");
        assertTrue(cmd.contains("--strict-mcp-config") && cmd.contains("--no-session-persistence"));
        assertFalse(cmd.stream().anyMatch(a -> a.contains("Database schema")), "스키마/질문은 인자가 아니라 stdin 으로");
    }

    @Test void promptContainsSchemaHistoryAndQuestion() {
        String p = ClaudeCli.buildPrompt("shop.orders(id, total)\n", null, List.of(new String[]{"User", "이전 질문"}, new String[]{"Assistant", "이전 답"}), "새 질문");
        assertTrue(p.contains("shop.orders(id, total)") && p.contains("User: 이전 질문") && p.contains("Assistant: 이전 답"));
        assertTrue(p.endsWith("User: 새 질문"));
        assertFalse(ClaudeCli.buildPrompt(null, null, List.of(), "q").contains("Database schema"));
        assertFalse(ClaudeCli.buildPrompt(null, "  ", List.of(), "q").contains("SQL editor"));
        String withEditor = ClaudeCli.buildPrompt(null, "select 1 from dual;\n", List.of(), "이 쿼리 설명해줘");
        assertTrue(withEditor.contains("```sql\nselect 1 from dual;\n```"), withEditor);
        assertTrue(withEditor.indexOf("SQL editor") < withEditor.indexOf("User: 이 쿼리"), "에디터 SQL이 질문 앞에 와야 함");
    }

    @Test void schemaTextIsCappedAndNamesOnly() {
        Map<String, Map<String, List<String>>> m = new java.util.TreeMap<>();
        for (int t = 0; t < 500; t++) m.computeIfAbsent("s", k -> new java.util.TreeMap<>()).put("table_" + t, List.of("a", "b", "c"));
        String s = ClaudeCli.schemaText(m, 2000);
        assertTrue(s.length() < 2100);
        assertTrue(s.contains("생략됨") && s.startsWith("s.table_0(a, b, c)"));
    }

    @Test void schemaTextPutsMentionedSchemaFirstAndKeepsOthersAsNames() {
        Map<String, Map<String, List<String>>> m = new java.util.TreeMap<>();
        for (int t = 0; t < 500; t++) m.computeIfAbsent("aaa", k -> new java.util.TreeMap<>()).put("table_" + t, List.of("a", "b", "c"));
        m.computeIfAbsent("wlt", k -> new java.util.TreeMap<>()).put("wallet", List.of("id", "balance"));
        String s = ClaudeCli.schemaText(m, 2000, "wlt 스키마 지갑 잔액 보여줘");
        assertTrue(s.startsWith("wlt.wallet(id, balance)"), s);
        assertTrue(s.contains("Other tables (columns omitted): aaa.table_"), s);
        assertTrue(s.length() < 2100);

        String noHint = ClaudeCli.schemaText(m, 2000, null);
        assertTrue(noHint.startsWith("aaa.table_0("), "힌트가 없으면 알파벳 순서");
    }

    @Test void findsExecutableInGivenDirs() throws Exception {
        Path dir = Files.createTempDirectory("fakebin");
        assertNull(ClaudeCli.find(List.of(dir)));
        Path exe = Files.createFile(dir.resolve("claude"));
        assertNull(ClaudeCli.find(List.of(dir)), "실행 권한이 없으면 무시");
        assertTrue(exe.toFile().setExecutable(true));
        assertEquals(exe.toString(), ClaudeCli.find(List.of(dir)));
        Files.delete(exe);
        Files.delete(dir);
    }

    // ---- 채팅 레이아웃 (헤드리스) ----
    private static JComponent find(Container root, java.util.function.Predicate<Component> p) {
        for (Component c : root.getComponents()) {
            if (p.test(c)) return (JComponent) c;
            if (c instanceof Container k) { JComponent r = find(k, p); if (r != null) return r; }
        }
        return null;
    }

    @Test void assistantReplyRendersWrappedTextAndCodeBlocksWithinWidth() throws Exception {
        AiPanel panel = new AiPanel(new AiPanel.Host() {
            public Session currentSession() { return null; }
            public void insertIntoEditor(String sql) {}
            public void appendToEditor(String sql) {}
            public String editorText() { return null; }
            public void replaceEditor(String sql) {}
            public void showResult(Session s, ResultData d, String label) {}
            public void closePanel() {}
        });
        panel.setSize(420, 700);
        Method m = AiPanel.class.getDeclaredMethod("addAssistant", String.class);
        m.setAccessible(true);
        String longLine = "긴 설명 ".repeat(60);
        m.invoke(panel, longLine + "\n```sql\nselect * from shop.orders where total > 100;\n```\n```sql\ndelete from shop.orders;\n```");

        JComponent chat = find(panel, c -> c.getClass().getSimpleName().equals("ChatList"));
        assertNotNull(chat);
        chat.setSize(400, 1);
        chat.doLayout();
        Insets in = chat.getInsets();
        int tallestText = 0;
        for (Component c : chat.getComponents()) {
            assertEquals(400 - in.left - in.right, c.getWidth(), "모든 항목은 패널 폭에 맞춰야 함: " + c.getClass().getSimpleName());
            assertTrue(c.getHeight() > 0, "높이가 0인 항목 없음");
            if (c instanceof JTextArea) tallestText = Math.max(tallestText, c.getHeight());
        }
        JTextArea longText = (JTextArea) find(chat, c -> c instanceof JTextArea ta && ta.getText().startsWith("긴 설명"));
        assertTrue(longText.getHeight() > 3 * longText.getFontMetrics(longText.getFont()).getHeight(), "긴 텍스트는 여러 줄로 줄바꿈");

        // SELECT 블록은 실행 가능, DELETE 블록은 실행 버튼 비활성
        JButton runSelect = (JButton) find(chat, c -> c instanceof JButton b && b.getText().contains("실행") && b.isEnabled());
        assertNotNull(runSelect, "SELECT 블록의 실행 버튼은 활성");
        JButton disabled = (JButton) find(chat, c -> c instanceof JButton b && b.getText().contains("실행") && !b.isEnabled());
        assertNotNull(disabled, "DELETE 블록의 실행 버튼은 비활성");
    }

    // ---- 실제 claude CLI 스모크 (민감정보 없는 중립 프롬프트, 환경변수로 켬) ----
    @Test @EnabledIfEnvironmentVariable(named = "PGSTUDIO_CLAUDE_SMOKE", matches = "1")
    void realCliRoundTrip() throws Exception {
        String exe = ClaudeCli.resolve();
        assertNotNull(exe, "claude 실행 파일을 찾아야 함");
        String reply = ClaudeCli.start(exe, ClaudeCli.buildPrompt(null, null, List.of(),
                "Write a PostgreSQL query returning the numbers 1 to 3 using generate_series. Reply with only a ```sql block.")).await(120);
        var segs = ChatParser.parse(reply);
        assertTrue(segs.stream().anyMatch(ChatParser.Segment::code), "SQL 코드 블록이 있어야 함: " + reply);
        String sql = segs.stream().filter(ChatParser.Segment::code).findFirst().get().text();
        assertNull(ReadOnlySql.check(sql), "생성된 SQL은 실행 가능해야 함: " + sql);
    }

    // ---- 입력창 키: Enter 전송, Shift+Enter 줄바꿈 ----
    @Test void inputEnterSendsAndShiftEnterInsertsNewline() throws Exception {
        AiPanel panel = new AiPanel(new AiPanel.Host() {
            public Session currentSession() { return null; }
            public void insertIntoEditor(String sql) {}
            public void appendToEditor(String sql) {}
            public String editorText() { return null; }
            public void replaceEditor(String sql) {}
            public void showResult(Session s, ResultData d, String label) {}
            public void closePanel() {}
        });
        var f = AiPanel.class.getDeclaredField("input");
        f.setAccessible(true);
        JTextArea input = (JTextArea) f.get(panel);

        assertEquals("send", input.getInputMap().get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)));
        assertEquals("send", input.getInputMap().get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, Shortcuts.mod())));
        Object shiftAction = input.getInputMap().get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK));
        assertEquals(javax.swing.text.DefaultEditorKit.insertBreakAction, shiftAction);

        input.setText("첫줄");
        input.setCaretPosition(input.getText().length());
        Action nl = input.getActionMap().get(shiftAction);
        assertNotNull(nl, "Shift+Enter 는 줄바꿈 액션에 연결돼야 함");
        nl.actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
        assertEquals("첫줄\n", input.getText(), "Shift+Enter 는 전송하지 않고 줄바꿈만");

        // Enter 는 전송 액션: 빈 입력이면 아무 일도 하지 않고(대화창 없음) 안전하게 반환
        input.setText("");
        input.getActionMap().get("send").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
        assertEquals("", input.getText());
    }

    @Test @EnabledIfEnvironmentVariable(named = "PGSTUDIO_CLAUDE_SMOKE", matches = "1")
    void realCliSeesEditorSql() throws Exception {
        String exe = ClaudeCli.resolve();
        assertNotNull(exe);
        String prompt = ClaudeCli.buildPrompt(null, "select 4242 as marker_value from generate_series(1, 2);", List.of(), "지금 에디터의 쿼리가 뭐지");
        String reply = ClaudeCli.start(exe, prompt).await(120);
        assertTrue(reply.contains("4242") || reply.contains("marker_value"), "응답이 에디터 SQL을 알고 있어야 함: " + reply);
    }
}
