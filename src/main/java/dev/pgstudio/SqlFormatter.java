package dev.pgstudio;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 가벼운 SQL 포매터: 키워드 대문자화, 주요 절 줄바꿈, 괄호 깊이별 들여쓰기. */
public final class SqlFormatter {
    public static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "and", "or", "not", "in", "is", "null", "as", "on", "join", "left", "right",
            "inner", "outer", "full", "cross", "group", "by", "order", "having", "limit", "offset", "union", "all",
            "distinct", "insert", "into", "values", "update", "set", "delete", "create", "table", "alter", "drop",
            "with", "case", "when", "then", "else", "end", "like", "ilike", "between", "exists", "asc", "desc",
            "returning", "default", "primary", "key", "references", "index", "unique", "true", "false", "using",
            "over", "partition", "fetch", "only", "for", "conflict", "do", "nothing", "view", "materialized", "if",
            "cascade", "begin", "commit", "rollback", "explain", "analyze", "truncate", "except", "intersect",
            "lateral", "filter", "window", "schema", "sequence", "function", "grant", "revoke", "to", "add",
            "column", "constraint", "foreign", "check", "temporary", "temp", "recursive", "nulls", "first", "last");
    private static final Set<String> BREAK_BEFORE = Set.of(
            "select", "from", "where", "group", "order", "having", "limit", "offset", "union", "except", "intersect",
            "values", "returning");
    private static final Set<String> JOIN_MODS = Set.of("left", "right", "inner", "full", "cross");
    private static final Set<String> COMMA_BREAK = Set.of("select", "group", "order", "set");

    private SqlFormatter() {}

    private enum T { WORD, STRING, QUOTED, COMMENT_LINE, COMMENT_BLOCK, OP, DOLLAR }
    private record Tok(T type, String text) {}

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int st = i;
            if (c == '\'' || c == '"') {
                i++;
                while (i < n) {
                    if (s.charAt(i) == c) { if (i + 1 < n && s.charAt(i + 1) == c) i += 2; else { i++; break; } }
                    else i++;
                }
                out.add(new Tok(c == '\'' ? T.STRING : T.QUOTED, s.substring(st, i)));
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') i++;
                out.add(new Tok(T.COMMENT_LINE, s.substring(st, i)));
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                out.add(new Tok(T.COMMENT_BLOCK, s.substring(st, i)));
            } else if (c == '$' && i + 1 < n && (s.charAt(i + 1) == '$' || Character.isLetter(s.charAt(i + 1)))) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                if (j < n && s.charAt(j) == '$') {
                    String tag = s.substring(i, j + 1);
                    int e = s.indexOf(tag, j + 1);
                    i = e < 0 ? n : e + tag.length();
                    out.add(new Tok(T.DOLLAR, s.substring(st, i)));
                } else { i++; out.add(new Tok(T.OP, "$")); }
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) i++;
                out.add(new Tok(T.WORD, s.substring(st, i)));
            } else if (s.startsWith("::", i)) { i += 2; out.add(new Tok(T.OP, "::"));
            } else if (s.startsWith("<=", i) || s.startsWith(">=", i) || s.startsWith("<>", i) || s.startsWith("!=", i)
                    || s.startsWith("||", i) || s.startsWith("->", i)) {
                i += 2;
                if (i < n && s.charAt(i) == '>' && s.charAt(i - 1) == '>') i++;
                out.add(new Tok(T.OP, s.substring(st, i)));
            } else { i++; out.add(new Tok(T.OP, String.valueOf(c))); }
        }
        return out;
    }

    public static String format(String sql) {
        List<Tok> toks = tokenize(sql);
        StringBuilder sb = new StringBuilder();
        String[] clause = new String[128];
        java.util.Arrays.fill(clause, "");
        int depth = 0;
        Tok prev = null;
        boolean atLineStart = true, forceBreak = false;
        for (int idx = 0; idx < toks.size(); idx++) {
            Tok t = toks.get(idx);
            String low = t.text.toLowerCase();
            boolean kw = t.type == T.WORD && KEYWORDS.contains(low);
            String out = kw ? t.text.toUpperCase() : t.text;
            String ind = "  ".repeat(depth);
            boolean brk = forceBreak;
            if (t.type == T.WORD && kw) {
                String nextLow = idx + 1 < toks.size() ? toks.get(idx + 1).text.toLowerCase() : "";
                if (BREAK_BEFORE.contains(low) && !(low.equals("select") && prev != null && prev.text.equals("("))
                        && !(low.equals("from") && prev != null && prev.text.equalsIgnoreCase("distinct"))) brk = true;
                if (low.equals("join") && !(prev != null && (JOIN_MODS.contains(prev.text.toLowerCase())
                        || prev.text.equalsIgnoreCase("outer")))) brk = true;
                if (JOIN_MODS.contains(low) && (nextLow.equals("join") || nextLow.equals("outer"))) brk = true;
                if ((low.equals("and") || low.equals("or")) && Set.of("where", "on", "having").contains(clause[depth])) {
                    brk = true;
                    ind = ind + "  ";
                }
                if (BREAK_BEFORE.contains(low) || low.equals("set")) clause[depth] = low;
                if (low.equals("on")) clause[depth] = "on";
                if (low.equals("join")) clause[depth] = "join";
            }
            if (t.text.equals("(")) { depth++; clause[depth] = ""; }
            if (t.text.equals(")")) { depth = Math.max(0, depth - 1); ind = "  ".repeat(depth); }

            if (sb.length() > 0 && brk) { sb.append('\n').append(ind); atLineStart = true; }
            else if (!atLineStart && needsSpace(prev, t)) sb.append(' ');
            sb.append(out);
            atLineStart = false;
            forceBreak = false;
            if (t.type == T.COMMENT_LINE) { forceBreak = true; }
            if (t.text.equals(",") && COMMA_BREAK.contains(clause[depth])) forceBreak = true;
            if (t.text.equals(",") && forceBreak) { sb.append('\n').append("  ".repeat(depth + 1)); forceBreak = false; atLineStart = true; }
            if (t.text.equals(";")) { sb.append('\n'); atLineStart = true; prev = null; java.util.Arrays.fill(clause, ""); depth = 0; continue; }
            prev = t;
        }
        return sb.toString().stripTrailing();
    }

    private static boolean needsSpace(Tok prev, Tok cur) {
        if (prev == null) return false;
        String p = prev.text, c = cur.text;
        if (c.equals(",") || c.equals(")") || c.equals(";") || c.equals(".") || c.equals("::")) return false;
        if (p.equals("(") || p.equals(".") || p.equals("::")) return false;
        if (c.equals("(")) {
            boolean fn = (prev.type == T.WORD && !KEYWORDS.contains(p.toLowerCase())) || prev.type == T.QUOTED;
            return !fn;
        }
        return true;
    }
}
