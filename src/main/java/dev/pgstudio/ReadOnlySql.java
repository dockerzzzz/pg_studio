package dev.pgstudio;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 패널에서 실행해도 되는 SQL인지 검사한다. 단일 읽기 전용 문장만 허용.
 * 이 검사는 보조 수단이며, 실제 방어선은 서버가 강제하는 읽기 전용 연결({@link Session#newReadOnlyConnection})이다.
 */
final class ReadOnlySql {
    private ReadOnlySql() {}

    private static final Pattern START = Pattern.compile("^(select|with|values|table|show)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DENY = Pattern.compile("\\b(insert|update|delete|merge|alter|drop|create|truncate|grant|revoke|"
            + "vacuum|reindex|cluster|call|do|copy|lock|listen|notify|"
            + "pg_terminate_backend|pg_cancel_backend|pg_reload_conf|pg_rotate_logfile|pg_read_file|pg_read_binary_file|"
            + "pg_ls_dir|pg_stat_file|lo_import|lo_export|lo_unlink|dblink\\w*|set_config|nextval|setval|pg_advisory\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 실행 가능하면 null, 아니면 사유. */
    static String check(String sql) {
        if (sql == null || sql.isBlank()) return "비어 있는 SQL입니다.";
        List<SqlSplitter.Range> parts = SqlSplitter.split(sql);
        if (parts.size() != 1) return "단일 SELECT 문만 실행할 수 있습니다.";
        String t = SqlSplitter.stripNoise(parts.get(0).text(sql)).trim();
        if (!START.matcher(t).find()) return "SELECT(WITH/VALUES/TABLE/SHOW) 문만 실행할 수 있습니다.";
        Matcher m = DENY.matcher(t);
        if (m.find()) return "허용되지 않는 명령/함수가 포함되어 있습니다: " + m.group();
        return null;
    }

    /** 실행할 단일 문장 텍스트(끝의 세미콜론 제거). */
    static String statement(String sql) {
        List<SqlSplitter.Range> parts = SqlSplitter.split(sql);
        return parts.isEmpty() ? sql.trim() : parts.get(0).text(sql);
    }
}
