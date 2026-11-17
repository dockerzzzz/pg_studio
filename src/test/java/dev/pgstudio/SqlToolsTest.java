package dev.pgstudio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlToolsTest {
    @Test void splitsOnSemicolonsIgnoringQuotesCommentsAndDollarQuotes() {
        String sql = "select 'a;b'; -- c;d\nselect \"x;y\" from t; /* ; */ do $$ begin perform 1; end $$; select 3";
        List<SqlSplitter.Range> r = SqlSplitter.split(sql);
        assertEquals(4, r.size());
        assertEquals("select 'a;b'", r.get(0).text(sql));
        assertTrue(r.get(2).text(sql).startsWith("/* ; */ do $$"));
        assertEquals("select 3", r.get(3).text(sql));
    }

    @Test void statementAtCaret() {
        String sql = "select 1;\n\nselect 2;\nselect 3;";
        assertEquals("select 1", SqlSplitter.statementAt(sql, 3).text(sql));
        assertEquals("select 1", SqlSplitter.statementAt(sql, 9).text(sql));   // ';' 바로 뒤
        assertEquals("select 2", SqlSplitter.statementAt(sql, 14).text(sql));
        assertEquals("select 3", SqlSplitter.statementAt(sql, sql.length()).text(sql));
    }

    @Test void ignoresCommentOnlyChunks() {
        assertEquals(1, SqlSplitter.split("-- hi\nselect 1; -- tail\n").size());
    }

    @Test void dangerDetection() {
        assertNotNull(SqlSplitter.danger("DELETE FROM t", false));
        assertNull(SqlSplitter.danger("delete from t where id = 1", false));
        assertNotNull(SqlSplitter.danger("update t set a = 'where'", false)); // 문자열 안의 where 는 WHERE 절이 아님
        assertNotNull(SqlSplitter.danger("drop table t", false));
        assertNotNull(SqlSplitter.danger("truncate t", false));
        assertNull(SqlSplitter.danger("select * from t", true));
        assertNotNull(SqlSplitter.danger("insert into t values (1)", true));
        assertNull(SqlSplitter.danger("insert into t values (1)", false));
    }

    @Test void formatterBreaksClausesAndUppercasesKeywords() {
        String out = SqlFormatter.format("select a,b from t left join u on t.id=u.id where a=1 and b='x' order by a;");
        assertEquals("""
                SELECT a,
                  b
                FROM t
                LEFT JOIN u ON t.id = u.id
                WHERE a = 1
                  AND b = 'x'
                ORDER BY a;""", out.stripTrailing());
    }

    @Test void formatterKeepsFunctionCallsAndCasts() {
        String out = SqlFormatter.format("select count(*), coalesce(a,0)::int from t");
        assertTrue(out.contains("count(*)"), out);
        assertTrue(out.contains("coalesce(a, 0)::int"), out);
    }

    @Test void resolvesAliasesFromFromAndJoin() {
        java.util.Map<String, java.util.Map<String, List<String>>> m = new java.util.TreeMap<>();
        m.computeIfAbsent("wlt", k -> new java.util.TreeMap<>()).put("wallet", List.of("id", "balance"));
        m.computeIfAbsent("wlt", k -> new java.util.TreeMap<>()).put("txn", List.of("id", "wallet_id", "amount"));
        m.computeIfAbsent("public", k -> new java.util.TreeMap<>()).put("member", List.of("id", "name"));
        String sql = "select w., t.amount from wlt.wallet w\n join wlt.txn as t on t.wallet_id = w.id, member where 1=1";

        assertEquals("wallet", SqlAliases.tableFor("w", sql, m, null).name());
        assertEquals("txn", SqlAliases.tableFor("T", sql, m, null).name());
        assertEquals("member", SqlAliases.tableFor("member", sql, m, null).name());
        assertEquals(List.of("id", "balance"), SqlAliases.tableFor("wlt.wallet", "select ", m, null).columns());
        assertNull(SqlAliases.tableFor("where", sql, m, null), "키워드는 별칭이 아님");
        assertNull(SqlAliases.tableFor("x", sql, m, null));
        assertEquals("txn", SqlAliases.tableFor("\"t\"", "select from wlt.\"txn\" \"t\"", m, null).name());
    }

    @Test void numericZeroWithScaleIsNotScientific() {
        java.math.BigDecimal zero = new java.math.BigDecimal("0E-10");
        assertEquals("0.0000000000", new ConsoleTab.PlainDecimal(zero).toString());
        assertEquals(0, new ConsoleTab.PlainDecimal(new java.math.BigDecimal("-1000.00")).compareTo(new java.math.BigDecimal("-1000")));
    }

    @Test void historyEscapeRoundTrip() {
        String s = "select 1\n\tfrom \\x";
        assertEquals(s, History.unesc(History.esc(s)));
    }
}
