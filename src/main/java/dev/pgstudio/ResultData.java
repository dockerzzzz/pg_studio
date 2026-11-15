package dev.pgstudio;

import java.util.List;

public final class ResultData {
    public final List<String> columns;
    public final List<String> types;
    public final List<Object[]> rows;
    public final boolean truncated;
    public final long millis;
    public final String sql;
    /** 편집 가능한 결과(단일 테이블 + PK 포함)이면 대상 정보, 아니면 null. */
    public volatile GridEdit.Target edit;

    public ResultData(List<String> columns, List<String> types, List<Object[]> rows, boolean truncated, long millis, String sql) {
        this.columns = columns; this.types = types; this.rows = rows;
        this.truncated = truncated; this.millis = millis; this.sql = sql;
    }
}
