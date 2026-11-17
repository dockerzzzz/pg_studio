package dev.pgstudio;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 문장의 FROM/JOIN 절에서 별칭을 찾아 `별칭.` 자동완성용 컬럼 목록을 돌려준다. */
final class SqlAliases {
    private SqlAliases() {}

    private static final Pattern NOISE = Pattern.compile("--[^\\n]*|/\\*.*?\\*/|'(?:[^']|'')*'", Pattern.DOTALL);
    private static final String ID = "(?:\"(?:[^\"]|\"\")+\"|[\\w$]+)";
    private static final Set<String> NOT_ALIAS = Set.of("from", "where", "on", "using", "join", "left", "right", "inner",
            "outer", "full", "cross", "natural", "lateral", "group", "order", "limit", "offset", "union", "except",
            "intersect", "having", "window", "set", "values", "returning", "for", "fetch", "select", "as", "default",
            "tablesample");
    // 별칭 자리의 키워드는 매치 단계에서 제외해야 뒤따르는 FROM 을 먹지 않는다
    private static final Pattern REF = Pattern.compile(
            "(?i)(?:\\b(?:from|join|update|into)|,)\\s+(" + ID + "(?:\\." + ID + ")?)"
                    + "(?:\\s+(?:as\\s+)?(?!(?:" + String.join("|", NOT_ALIAS) + ")\\b)(" + ID + "))?");

    /** 테이블 하나: 스키마, 이름, 컬럼. */
    record Table(String schema, String name, List<String> columns) {}

    /** 별칭(소문자) -> 테이블. 별칭 없는 테이블은 자기 이름으로 등록한다. */
    static Map<String, Table> parse(String sql, Map<String, Map<String, List<String>>> completion, String defaultSchema) {
        Map<String, Table> out = new LinkedHashMap<>();
        Matcher m = REF.matcher(NOISE.matcher(sql).replaceAll(" "));
        while (m.find()) {
            Table t = resolve(m.group(1), completion, defaultSchema);
            if (t == null) continue;
            String alias = m.group(2) == null ? null : unquote(m.group(2));
            if (alias != null && !NOT_ALIAS.contains(alias.toLowerCase(Locale.ROOT))) out.put(alias.toLowerCase(Locale.ROOT), t);
            out.putIfAbsent(t.name().toLowerCase(Locale.ROOT), t);
        }
        return out;
    }

    /** `qualifier.` 다음에 올 컬럼의 테이블. 별칭이 우선이고, 아니면 qualifier 자체를 `스키마.테이블` 로 본다. */
    static Table tableFor(String qualifier, String sql, Map<String, Map<String, List<String>>> completion, String defaultSchema) {
        Table t = parse(sql, completion, defaultSchema).get(unquote(qualifier).toLowerCase(Locale.ROOT));
        return t != null || !qualifier.contains(".") ? t : resolve(qualifier, completion, defaultSchema);
    }

    /** `schema.table` 또는 `table` 을 찾는다. 스키마가 없으면 기본 스키마, public, 나머지 순. */
    static Table resolve(String ref, Map<String, Map<String, List<String>>> completion, String defaultSchema) {
        String[] parts = ref.split("\\.(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        if (parts.length > 2) return null;
        String table = unquote(parts[parts.length - 1]);
        List<String> schemas = new ArrayList<>();
        if (parts.length == 2) schemas.add(unquote(parts[0]));
        else {
            if (defaultSchema != null) schemas.add(defaultSchema);
            schemas.add("public");
            schemas.addAll(completion.keySet());
        }
        for (String sc : schemas)
            for (var se : completion.entrySet()) {
                if (!se.getKey().equalsIgnoreCase(sc)) continue;
                for (var te : se.getValue().entrySet())
                    if (te.getKey().equalsIgnoreCase(table)) return new Table(se.getKey(), te.getKey(), te.getValue());
            }
        return null;
    }

    private static String unquote(String id) {
        return id.length() >= 2 && id.startsWith("\"") && id.endsWith("\"") ? id.substring(1, id.length() - 1).replace("\"\"", "\"") : id;
    }
}
