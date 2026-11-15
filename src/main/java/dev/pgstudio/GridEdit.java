package dev.pgstudio;

import org.postgresql.PGResultSetMetaData;

import java.sql.*;
import java.util.*;

/** 결과 그리드 편집: 수정 가능 여부 판별(단일 테이블 + PK 포함)과 UPDATE/DELETE/INSERT 생성·실행. */
final class GridEdit {
    private GridEdit() {}

    /** baseNames[c] 는 컬럼 c 의 원본 컬럼명(수식/중복 컬럼이면 null), pkIdx 는 PK 컬럼들의 결과 인덱스. */
    record Target(String schema, String table, String[] baseNames, int[] pkIdx) {
        String qualified() { return schema == null || schema.isEmpty() ? Ddl.quote(table) : Ddl.qualified(schema, table); }
    }

    enum Kind { UPDATE, DELETE, INSERT }

    /** row: 원본 값을 가진 행(PK 조건에 사용). values: 컬럼 인덱스 -> 새 텍스트(null 이면 SQL NULL). */
    record Op(Kind kind, Object[] row, Map<Integer, String> values) {}

    /** 사용자가 입력한 텍스트 값: 서버가 컬럼 타입으로 변환하도록 타입 미지정으로 바인딩한다. */
    record Txt(String s) {}

    record Prepared(String sql, List<Object> params, String preview) {}

    /** 결과셋이 단일 테이블이고 PK 전체를 포함하면 편집 대상, 아니면 null. */
    static Target detect(ResultSet rs, Connection metaConn) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        PGResultSetMetaData pm = md.unwrap(PGResultSetMetaData.class);
        int n = md.getColumnCount();
        String schema = null, table = null;
        String[] base = new String[n];
        for (int i = 1; i <= n; i++) {
            String tb = pm.getBaseTableName(i), sc = pm.getBaseSchemaName(i), col = pm.getBaseColumnName(i);
            if (tb == null || tb.isEmpty()) continue;
            if (table == null) { table = tb; schema = sc; }
            else if (!table.equals(tb) || !Objects.equals(schema, sc)) return null;
            if (col != null && !col.isEmpty() && !Arrays.asList(base).contains(col)) base[i - 1] = col;
        }
        if (table == null) return null;
        String reg = schema == null || schema.isEmpty() ? Ddl.quote(table) : Ddl.qualified(schema, table);
        List<String> pk = new ArrayList<>();
        String q = "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey) "
                + "where i.indrelid = ?::regclass and i.indisprimary";
        try (PreparedStatement ps = metaConn.prepareStatement(q)) {
            ps.setString(1, reg);
            try (ResultSet r = ps.executeQuery()) { while (r.next()) pk.add(r.getString(1)); }
        }
        if (pk.isEmpty()) return null;
        int[] idx = new int[pk.size()];
        for (int k = 0; k < idx.length; k++) {
            idx[k] = Arrays.asList(base).indexOf(pk.get(k));
            if (idx[k] < 0) return null;
        }
        return new Target(schema, table, base, idx);
    }

    static Prepared prepare(Target t, Op op) {
        StringBuilder sql = new StringBuilder(), pv = new StringBuilder();
        List<Object> params = new ArrayList<>();
        List<Integer> cols = new ArrayList<>(new TreeSet<>(op.values().keySet()));
        switch (op.kind()) {
            case UPDATE -> {
                String head = "update " + t.qualified() + " set ";
                sql.append(head); pv.append(head);
                for (int k = 0; k < cols.size(); k++) {
                    int c = cols.get(k);
                    String id = Ddl.quote(t.baseNames()[c]);
                    String v = op.values().get(c);
                    sql.append(k > 0 ? ", " : "").append(id).append(" = ?");
                    pv.append(k > 0 ? ", " : "").append(id).append(" = ").append(literal(v == null ? null : new Txt(v)));
                    params.add(v == null ? null : new Txt(v));
                }
                where(t, op, sql, pv, params);
            }
            case DELETE -> {
                String head = "delete from " + t.qualified();
                sql.append(head); pv.append(head);
                where(t, op, sql, pv, params);
            }
            case INSERT -> {
                String head = "insert into " + t.qualified();
                sql.append(head); pv.append(head);
                if (cols.isEmpty()) { sql.append(" default values"); pv.append(" default values"); }
                else {
                    StringBuilder names = new StringBuilder(), ph = new StringBuilder(), lit = new StringBuilder();
                    for (int k = 0; k < cols.size(); k++) {
                        int c = cols.get(k);
                        String v = op.values().get(c);
                        names.append(k > 0 ? ", " : "").append(Ddl.quote(t.baseNames()[c]));
                        ph.append(k > 0 ? ", " : "").append('?');
                        lit.append(k > 0 ? ", " : "").append(literal(v == null ? null : new Txt(v)));
                        params.add(v == null ? null : new Txt(v));
                    }
                    sql.append(" (").append(names).append(") values (").append(ph).append(')');
                    pv.append(" (").append(names).append(") values (").append(lit).append(')');
                }
            }
        }
        return new Prepared(sql.toString(), params, pv.toString());
    }

    private static void where(Target t, Op op, StringBuilder sql, StringBuilder pv, List<Object> params) {
        sql.append(" where "); pv.append(" where ");
        for (int k = 0; k < t.pkIdx().length; k++) {
            int c = t.pkIdx()[k];
            String id = Ddl.quote(t.baseNames()[c]);
            Object v = op.row()[c];
            sql.append(k > 0 ? " and " : "").append(id).append(" = ?");
            pv.append(k > 0 ? " and " : "").append(id).append(" = ").append(literal(v));
            params.add(v);
        }
    }

    static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Txt t) return quoteStr(t.s());
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return quoteStr(v.toString());
    }

    private static String quoteStr(String s) { return "'" + s.replace("'", "''") + "'"; }

    /** 실행하고 영향 행 수를 반환한다. */
    static int execute(Connection c, Prepared p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(p.sql())) {
            for (int i = 0; i < p.params().size(); i++) {
                Object v = p.params().get(i);
                if (v == null) ps.setNull(i + 1, Types.NULL);
                else if (v instanceof Txt t) ps.setObject(i + 1, t.s(), Types.OTHER);
                else ps.setObject(i + 1, v);
            }
            return ps.executeUpdate();
        }
    }
}
