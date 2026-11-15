package dev.pgstudio;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JiraTest {
    private static final String NEW_JSON = """
            {"issues":[
              {"id":"1","key":"ABC-123","fields":{"summary":"[BE] Sample issue summary",
                "assignee":{"displayName":"alice"},"reporter":{"displayName":"alice"},"status":{"name":"진행 중"}}},
              {"id":"2","key":"ABC-1","fields":{"summary":"x <b>bold</b>","assignee":null,
                "reporter":{"displayName":"kim"},"status":{"name":"To Do"}}}
            ],"isLast":false,"nextPageToken":"abc"}""";

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>(), lastQuery = new AtomicReference<>(), lastAuth = new AtomicReference<>();
    private volatile int newStatus = 200, legacyStatus = 200;
    private volatile String newBody = NEW_JSON;
    private volatile String legacyBody = "{\"issues\":[],\"total\":0}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/3/search/jql", ex -> reply(ex, newStatus, newBody));
        server.createContext("/rest/api/3/search", ex -> {
            if (ex.getRequestURI().getPath().equals("/rest/api/3/search")) reply(ex, legacyStatus, legacyBody);
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        lastPath.set(ex.getRequestURI().getPath());
        lastQuery.set(ex.getRequestURI().getRawQuery());
        lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
        if (status == 302) ex.getResponseHeaders().add("Location", "https://elsewhere.example.com/");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private JiraClient client() { return new JiraClient(base() + "/", "me@x.com", "tok"); }

    // ---- 클라이언트 ----

    @Test void searchParsesIssuesAndSendsBasicAuthAndEncodedJql() throws Exception {
        JiraClient.Result r = client().search(JiraSettings.DEFAULT_JQL, 100);
        assertEquals("/rest/api/3/search/jql", lastPath.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("me@x.com:tok".getBytes(StandardCharsets.UTF_8)), lastAuth.get());
        String q = lastQuery.get();
        assertTrue(q.contains("maxResults=100") && q.contains("fields=summary,assignee,reporter,status"), q);
        assertEquals(JiraSettings.DEFAULT_JQL, URLDecoder.decode(q.split("&")[0].substring("jql=".length()), StandardCharsets.UTF_8));
        assertFalse(q.split("&")[0].contains(" "), "JQL 은 URL 인코딩되어야 함");

        assertEquals(2, r.issues().size());
        assertTrue(r.more(), "isLast=false → 더 있음");
        JiraClient.Issue a = r.issues().get(0);
        assertEquals("ABC-123", a.key());
        assertEquals("[BE] Sample issue summary", a.summary());
        assertEquals("alice", a.assignee());
        assertEquals("진행 중", a.status());
        assertEquals("미할당", r.issues().get(1).assignee(), "담당자 null → 미할당");
        assertEquals("kim", r.issues().get(1).reporter());
    }

    @Test void fallsBackToLegacyEndpointWhenNewOneIsMissing() throws Exception {
        newStatus = 404;
        newBody = "{}";
        legacyBody = NEW_JSON.replace("\"isLast\":false,\"nextPageToken\":\"abc\"", "\"total\":5");
        JiraClient.Result r = client().search("project = X", 50);
        assertEquals("/rest/api/3/search", lastPath.get());
        assertEquals(2, r.issues().size());
        assertTrue(r.more(), "total(5) > 반환 건수(2)");
    }

    @Test void mapsErrorsToReadableMessages() {
        newStatus = 401;
        assertTrue(assertThrows(JiraClient.JiraException.class, () -> client().search("x", 10)).getMessage().contains("인증"));
        newStatus = 400;
        newBody = "{\"errorMessages\":[\"Error in the JQL Query: bad\"],\"errors\":{}}";
        assertTrue(assertThrows(JiraClient.JiraException.class, () -> client().search("x", 10)).getMessage().contains("Error in the JQL Query"));
        newStatus = 302;
        newBody = "";
        assertTrue(assertThrows(JiraClient.JiraException.class, () -> client().search("x", 10)).getMessage().contains("리다이렉트"),
                "리다이렉트를 따라가면 인증 헤더가 다른 곳으로 갈 수 있으므로 오류 처리");
        newStatus = 200;
        newBody = "not json";
        assertTrue(assertThrows(JiraClient.JiraException.class, () -> client().search("x", 10)).getMessage().contains("해석"));
    }

    @Test void urlAndKeyValidation() {
        assertDoesNotThrow(() -> new JiraClient("https://acme.atlassian.net/", "a@b.c", "t"));
        assertThrows(IllegalArgumentException.class, () -> new JiraClient("http://acme.atlassian.net", "a@b.c", "t"), "http 는 거부");
        assertThrows(IllegalArgumentException.class, () -> new JiraClient("acme.atlassian.net", "a@b.c", "t"));
        assertThrows(IllegalArgumentException.class, () -> new JiraClient("ftp://acme.atlassian.net", "a@b.c", "t"));
        assertEquals("https://acme.atlassian.net/browse/ABC-123", new JiraClient("https://acme.atlassian.net//", "a@b.c", "t").browseUrl("ABC-123"));
        assertTrue(JiraClient.validKey("ABC-123") && JiraClient.validKey("AB_1-7"));
        assertFalse(JiraClient.validKey("pis-1") || JiraClient.validKey("ABC-1/../x") || JiraClient.validKey("ABC-") || JiraClient.validKey(null));
        assertThrows(IllegalArgumentException.class, () -> client().browseUrl("ABC-1?x=1"));
    }

    // ---- 설정 저장 ----

    @Test void settingsSaveTokenOnlyWhenAskedAndFileIsOwnerOnly() throws Exception {
        JiraSettings.Config c = new JiraSettings.Config("https://acme.atlassian.net", "me@x.com", "secret-token", "project = X");
        JiraSettings.save(c, false);
        JiraSettings.Config loaded = JiraSettings.load();
        assertEquals("https://acme.atlassian.net", loaded.baseUrl());
        assertEquals("project = X", loaded.jql());
        assertEquals("", loaded.token(), "저장을 선택하지 않으면 토큰은 파일에 없어야 함");
        assertFalse(Files.readString(ProfileStore.DIR.resolve("jira.properties")).contains("secret-token"));

        JiraSettings.save(c, true);
        assertEquals("secret-token", JiraSettings.load().token());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(ProfileStore.DIR.resolve("jira.properties"))));
        JiraSettings.save(c.withJql("a = b"), false);   // 이후 토큰 없이 저장하면 사라짐
        assertEquals("", JiraSettings.load().token());
    }

    // ---- 패널 (헤드리스) ----

    private static JTable findTable(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTable t) return t;
            if (c instanceof Container k) { JTable t = findTable(k); if (t != null) return t; }
        }
        return null;
    }

    private static void click(JTable t, int x, int y, int count) {
        t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, x, y, count, false));
    }

    @Test void panelListsIssuesAndOpensBrowserOnKeyClick() throws Exception {
        List<String> opened = new ArrayList<>();
        JiraPanel panel = new JiraPanel(new JiraSettings.Config(base(), "me@x.com", "tok", JiraSettings.DEFAULT_JQL), opened::add);
        panel.setSize(440, 600);
        panel.refresh();
        JTable table = findTable(panel);
        for (int i = 0; i < 100 && table.getRowCount() == 0; i++) { Thread.sleep(50); SwingUtilities.invokeAndWait(() -> {}); }
        assertEquals(2, table.getRowCount());
        assertEquals(List.of("업무", "담당자", "보고자"), List.of(table.getColumnName(0), table.getColumnName(1), table.getColumnName(2)));
        assertEquals("alice", table.getValueAt(0, 1));
        assertEquals("미할당", table.getValueAt(1, 1));

        table.setSize(440, 300);   // 헤드리스에서는 레이아웃이 안 돌므로 열 너비를 직접 확정
        table.doLayout();
        Rectangle cell = table.getCellRect(0, 0, false);
        assertTrue(cell.width > 200, "업무 열 폭: " + cell.width);
        click(table, cell.x + 5, cell.y + 5, 1);                       // 이슈 키 위 단일 클릭 → 열기
        assertEquals(List.of(base() + "/browse/ABC-123"), opened);
        click(table, cell.x + cell.width - 10, cell.y + 5, 1);         // 제목 쪽 단일 클릭 → 열지 않음
        assertEquals(1, opened.size());
        Rectangle row2 = table.getCellRect(1, 0, false);
        click(table, row2.x + row2.width - 10, row2.y + 5, 2);         // 더블클릭 → 어디서든 열기
        assertEquals(List.of(base() + "/browse/ABC-123", base() + "/browse/ABC-1"), opened);

        assertTrue(table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, cell.x + 5, cell.y + 5, 0, false)).contains("진행 중"));
    }

    @Test void panelShowsErrorOnAuthFailureAndKeepsListEmpty() throws Exception {
        newStatus = 401;
        JiraPanel panel = new JiraPanel(new JiraSettings.Config(base(), "me@x.com", "bad", JiraSettings.DEFAULT_JQL), u -> fail("열면 안 됨"));
        panel.refresh();
        JTable table = findTable(panel);
        JLabel msg = null;
        for (int i = 0; i < 100; i++) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> {});
            msg = findLabelContaining(panel, "인증");
            if (msg != null) break;
        }
        assertNotNull(msg, "인증 실패 안내가 표시되어야 함");
        assertEquals(0, table.getRowCount());
    }

    private static JLabel findLabelContaining(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel l && l.getText() != null && l.getText().contains(text)) return l;
            if (c instanceof Container k) { JLabel l = findLabelContaining(k, text); if (l != null) return l; }
        }
        return null;
    }

    @Test void tableIsStyledAsGridWithAvatarsAndEllipsisFriendlyCells() throws Exception {
        JiraPanel panel = new JiraPanel(new JiraSettings.Config(base(), "me@x.com", "tok", JiraSettings.DEFAULT_JQL), u -> {});
        panel.setSize(440, 600);
        panel.refresh();
        JTable table = findTable(panel);
        for (int i = 0; i < 100 && table.getRowCount() == 0; i++) { Thread.sleep(50); SwingUtilities.invokeAndWait(() -> {}); }
        assertEquals(2, table.getRowCount());

        assertTrue(table.getShowHorizontalLines() && table.getShowVerticalLines(), "가로/세로 구분선");
        assertTrue(table.getRowHeight() >= 36, "행 간격");
        assertEquals(JTable.AUTO_RESIZE_ALL_COLUMNS, table.getAutoResizeMode(), "패널 폭을 열이 채움");

        // 담당자: 이니셜 아바타, 미할당: 아이콘 없음
        JLabel alice = (JLabel) table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1);
        assertNotNull(alice.getIcon());
        assertEquals("A", ((JiraPanel.AvatarIcon) alice.getIcon()).initial());
        JLabel none = (JLabel) table.prepareRenderer(table.getCellRenderer(1, 1), 1, 1);
        assertNull(none.getIcon());
        assertEquals("미할당", none.getText());

        // 업무 열: 키 + 제목이 별도 라벨(제목은 길면 … 로 잘림), 짝수/홀수 행 배경이 다름
        Component issue0 = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
        Color bg0 = issue0.getBackground();   // 렌더러 컴포넌트는 재사용되므로 prepare 직후에 읽는다
        Color bg1 = table.prepareRenderer(table.getCellRenderer(1, 0), 1, 0).getBackground();
        assertNotEquals(bg0, bg1, "홀짝 행 구분");
        issue0 = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
        JLabel key = (JLabel) find(issue0, l -> l.getText().equals("ABC-123"));
        assertNotNull(key);
        assertNotNull(find(issue0, l -> l.getText().contains("Sample issue")));

        // 헤더: 왼쪽 정렬, 굵게
        JLabel h = (JLabel) table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(table, "업무", false, false, -1, 0);
        assertEquals(SwingConstants.LEFT, h.getHorizontalAlignment());
        assertTrue(h.getFont().isBold());
    }

    private static JLabel find(Component root, java.util.function.Predicate<JLabel> p) {
        if (root instanceof JLabel l && p.test(l)) return l;
        if (root instanceof Container c) for (Component k : c.getComponents()) { JLabel r = find(k, p); if (r != null) return r; }
        return null;
    }

    @Test void jiraSettingsSurviveRestart() throws Exception {
        JiraSettings.save(new JiraSettings.Config("https://acme.atlassian.net", "me@x.com", "tok", "project = ABC ORDER BY created DESC"), false);

        JiraPanel restarted = new JiraPanel();   // 앱 재시작과 동일: 파일에서 설정을 읽는다
        JTextField jql = (JTextField) findField(restarted);
        assertEquals("project = ABC ORDER BY created DESC", jql.getText(), "마지막 JQL 복원");
        assertNull(findLabelContaining(restarted, "설정 버튼으로"), "주소/이메일이 복원되어 '설정 필요' 안내가 없어야 함");
        JiraSettings.Config c = JiraSettings.load();
        assertEquals("https://acme.atlassian.net", c.baseUrl());
        assertEquals("me@x.com", c.email());
        assertEquals("", c.token(), "토큰은 저장하지 않았으므로 복원되지 않음");
    }

    private static Component findField(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextField f) return f;
            if (c instanceof Container k) { Component r = findField(k); if (r != null) return r; }
        }
        return null;
    }
}
