package dev.pgstudio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/** Jira Cloud REST API(v3) 이슈 검색 클라이언트. 인증 정보는 설정한 Jira 주소로만 전송된다. */
final class JiraClient {
    record Issue(String key, String summary, String assignee, String reporter, String status) {}
    record Result(List<Issue> issues, boolean more) {}

    static final class JiraException extends Exception {
        JiraException(String message) { super(message); }
    }

    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]+-\\d+");
    private static final String FIELDS = "summary,assignee,reporter,status";

    private final String base;
    private final String auth;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)   // 인증 헤더가 다른 곳으로 따라가지 않도록
            .build();

    /** https 주소만 허용한다(개발/테스트용 localhost 는 http 허용). */
    JiraClient(String baseUrl, String email, String token) {
        String b = baseUrl.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        URI u;
        try { u = URI.create(b); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Jira 주소 형식이 올바르지 않습니다."); }
        boolean local = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost());
        if (u.getHost() == null || !("https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && local)))
            throw new IllegalArgumentException("Jira 주소는 https:// 로 시작해야 합니다.");
        this.base = b;
        this.auth = "Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    static boolean validKey(String key) { return key != null && KEY.matcher(key).matches(); }

    String browseUrl(String key) {
        if (!validKey(key)) throw new IllegalArgumentException("잘못된 이슈 키: " + key);
        return base + "/browse/" + key;
    }

    Result search(String jql, int max) throws JiraException {
        String q = "jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) + "&maxResults=" + max + "&fields=" + FIELDS;
        HttpResponse<String> r = get("/rest/api/3/search/jql?" + q);
        if (r.statusCode() == 404 || r.statusCode() == 410) {   // 구버전 엔드포인트만 있는 경우
            r = get("/rest/api/3/search?" + q);
            check(r);
            return parse(r.body(), true);
        }
        check(r);
        return parse(r.body(), false);
    }

    private HttpResponse<String> get(String path) throws JiraException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .GET().build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JiraException("Jira에 연결할 수 없습니다: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraException("요청이 중단되었습니다.");
        }
    }

    private static void check(HttpResponse<String> r) throws JiraException {
        int s = r.statusCode();
        if (s == 200) return;
        if (s == 401 || s == 403) throw new JiraException("인증에 실패했습니다(HTTP " + s + "). 이메일과 API 토큰을 확인하세요.");
        if (s >= 300 && s < 400) throw new JiraException("Jira 주소가 다른 곳으로 리다이렉트됩니다(HTTP " + s + "). 주소를 확인하세요.");
        String detail = "";
        try {
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            if (o.has("errorMessages") && o.getAsJsonArray("errorMessages").size() > 0) {
                List<String> m = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("errorMessages")) m.add(e.getAsString());
                detail = ": " + String.join(" / ", m);
            }
        } catch (RuntimeException ignored) {}
        throw new JiraException("Jira 요청 실패(HTTP " + s + ")" + detail);
    }

    static Result parse(String body, boolean legacy) throws JiraException {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonArray arr = o.getAsJsonArray("issues");
            List<Issue> out = new ArrayList<>();
            for (JsonElement e : arr) {
                JsonObject i = e.getAsJsonObject(), f = i.getAsJsonObject("fields");
                out.add(new Issue(i.get("key").getAsString(), str(f, "summary"), person(f, "assignee", "미할당"),
                        person(f, "reporter", "-"), name(f, "status")));
            }
            boolean more = legacy
                    ? o.has("total") && o.get("total").getAsInt() > out.size()
                    : o.has("isLast") && !o.get("isLast").getAsBoolean();
            return new Result(out, more);
        } catch (RuntimeException e) {
            throw new JiraException("Jira 응답을 해석할 수 없습니다.");
        }
    }

    private static String str(JsonObject f, String k) { return f.has(k) && !f.get(k).isJsonNull() ? f.get(k).getAsString() : ""; }

    private static String person(JsonObject f, String k, String none) {
        if (!f.has(k) || f.get(k).isJsonNull()) return none;
        JsonObject p = f.getAsJsonObject(k);
        return p.has("displayName") && !p.get("displayName").isJsonNull() ? p.get("displayName").getAsString() : none;
    }

    private static String name(JsonObject f, String k) {
        if (!f.has(k) || f.get(k).isJsonNull()) return "";
        JsonObject s = f.getAsJsonObject(k);
        return s.has("name") ? s.get("name").getAsString() : "";
    }
}
