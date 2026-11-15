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

    @Test void historyEscapeRoundTrip() {
        String s = "select 1\n\tfrom \\x";
        assertEquals(s, History.unesc(History.esc(s)));
    }
}
