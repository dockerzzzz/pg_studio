package dev.pgstudio;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ERD 데이터: 스키마의 테이블/컬럼/PK/FK. 다른 스키마의 테이블을 참조하는 FK 는 참조 대상을
 * "external" 테이블(PK 와 참조되는 컬럼만)로 포함한다. 파티션 자식 테이블은 제외한다.
 */
final class ErdModel {
    record Column(int attnum, String name, String type, boolean notNull) {}

    static final class Table {
        final long oid;
        final String schema, name;
        final boolean external;
        final List<Column> columns = new ArrayList<>();
        final Set<String> pk = new LinkedHashSet<>(), fk = new HashSet<>(), inferred = new HashSet<>();

        Table(long oid, String schema, String name, boolean external) {
            this.oid = oid; this.schema = schema; this.name = name; this.external = external;
        }

        String id() { return schema + "." + name; }
        Column column(String n) { for (Column c : columns) if (c.name().equals(n)) return c; return null; }
        Column byNum(int num) { for (Column c : columns) if (c.attnum() == num) return c; return null; }
    }

    /**
     * identifying: FK 컬럼이 자식 PK 의 일부(식별 관계, 실선). 아니면 비식별 관계(점선).
     * inferred: 실제 FK 제약이 없는 이름 기반 추정 관계(흐린 점선).
     */
    record Fk(String name, long child, long parent, List<String> childCols, List<String> parentCols, boolean identifying, boolean inferred) {}

    final Map<Long, Table> tables = new LinkedHashMap<>();
    final List<Fk> fks = new ArrayList<>();

    private record Con(long rel, char type, String name, long parent, List<Integer> key, List<Integer> fkey) {}

    static ErdModel load(Connection c, String schema) throws SQLException {
        ErdModel m = new ErdModel();
        String cols = "select c.oid::bigint, n.nspname, c.relname, a.attnum, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull "
                + "from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped "
                + "where n.nspname = ? and c.relkind in ('r','p') and not c.relispartition order by c.relname, a.attnum";
        try (PreparedStatement ps = c.prepareStatement(cols)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) { readColumns(rs, m, false); }
        }
        List<Con> cons = constraints(c, m.tables.keySet());
        Set<Long> missing = new LinkedHashSet<>();
        for (Con k : cons) if (k.type() == 'f' && !m.tables.containsKey(k.parent())) missing.add(k.parent());
        if (!missing.isEmpty()) {
            String ext = "select c.oid::bigint, n.nspname, c.relname, a.attnum, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull "
                    + "from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped "
                    + "where c.oid::bigint = any(?) order by n.nspname, c.relname, a.attnum";
            try (PreparedStatement ps = c.prepareStatement(ext)) {
                ps.setArray(1, bigintArray(c, missing));
                try (ResultSet rs = ps.executeQuery()) { readColumns(rs, m, true); }
            }
            cons = new ArrayList<>(cons);
            for (Con k : constraints(c, missing)) if (k.type() == 'p') cons.add(k);
        }
        Map<Long, Set<String>> referenced = new HashMap<>();
        for (Con k : cons) {   // 1) PK 먼저 (식별 관계 판정에 필요)
            Table t = m.tables.get(k.rel());
            if (t != null && k.type() == 'p')
                for (int n : k.key()) { Column col = t.byNum(n); if (col != null) t.pk.add(col.name()); }
        }
        for (Con k : cons) {   // 2) FK
            Table t = m.tables.get(k.rel());
            Table p = m.tables.get(k.parent());
            if (t == null || p == null || k.type() != 'f') continue;
            List<String> cc = new ArrayList<>(), pc = new ArrayList<>();
            for (int n : k.key()) { Column col = t.byNum(n); if (col != null) cc.add(col.name()); }
            for (int n : k.fkey()) { Column col = p.byNum(n); if (col != null) pc.add(col.name()); }
            t.fk.addAll(cc);
            referenced.computeIfAbsent(p.oid, x -> new HashSet<>()).addAll(pc);
            m.fks.add(new Fk(k.name(), t.oid, p.oid, cc, pc, !cc.isEmpty() && t.pk.containsAll(cc), false));
        }
        // 외부 테이블은 PK 와 참조되는 컬럼만 남긴다
        for (Table t : m.tables.values())
            if (t.external) t.columns.removeIf(col -> !t.pk.contains(col.name()) && !referenced.getOrDefault(t.oid, Set.of()).contains(col.name()));
        ErdInference.apply(m);   // FK 제약은 없지만 이름으로 짐작되는 관계
        return m;
    }

    private static void readColumns(ResultSet rs, ErdModel m, boolean external) throws SQLException {
        while (rs.next()) {
            long oid = rs.getLong(1);
            Table t = m.tables.computeIfAbsent(oid, k -> {
                try { return new Table(oid, rs.getString(2), rs.getString(3), external); }
                catch (SQLException e) { throw new IllegalStateException(e); }
            });
            t.columns.add(new Column(rs.getInt(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)));
        }
    }

    private static List<Con> constraints(Connection c, Collection<Long> oids) throws SQLException {
        List<Con> out = new ArrayList<>();
        if (oids.isEmpty()) return out;
        String q = "select con.conrelid::bigint, con.contype::text, con.conname, con.confrelid::bigint, "
                + "array_to_string(con.conkey, ','), coalesce(array_to_string(con.confkey, ','), '') "
                + "from pg_constraint con where con.contype in ('p','f') and con.conrelid::bigint = any(?) order by con.conname";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            ps.setArray(1, bigintArray(c, oids));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Con(rs.getLong(1), rs.getString(2).charAt(0), rs.getString(3), rs.getLong(4), ints(rs.getString(5)), ints(rs.getString(6))));
            }
        }
        return out;
    }

    private static Array bigintArray(Connection c, Collection<Long> oids) throws SQLException {
        return c.createArrayOf("bigint", oids.toArray());
    }

    private static List<Integer> ints(String s) {
        List<Integer> out = new ArrayList<>();
        if (s != null && !s.isBlank()) for (String p : s.split(",")) out.add(Integer.parseInt(p.trim()));
        return out;
    }
}
