package dev.pgstudio;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 따옴표/주석/달러-쿼트를 인식하는 SQL 문장 분리기. */
public final class SqlSplitter {
    public record Range(int start, int end) {
        public String text(String s) { return s.substring(start, end).trim(); }
    }

    private SqlSplitter() {}

    private static final Pattern COMMENTS = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LITERALS = Pattern.compile("'(?:[^']|'')*'");

    public static List<Range> split(String s) {
        List<Range> out = new ArrayList<>();
        int n = s.length(), start = 0, i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(s, i, c);
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (s.startsWith("/*", i)) { depth++; i += 2; }
                    else if (s.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
            } else if (c == '$') {
                int e = dollarTagEnd(s, i);
                if (e > 0) {
                    String tag = s.substring(i, e);
                    int close = s.indexOf(tag, e);
                    i = close < 0 ? n : close + tag.length();
                } else i++;
            } else if (c == ';') {
                add(out, s, start, i);
                i++;
                start = i;
            } else i++;
        }
        add(out, s, start, n);
        return out;
    }

    private static void add(List<Range> out, String s, int start, int end) {
        if (start < end && !isBlank(s.substring(start, end))) out.add(new Range(start, end));
    }

    /** 커서 위치의 문장(없으면 마지막 문장). */
    public static Range statementAt(String s, int caret) {
        List<Range> ranges = split(s);
        if (ranges.isEmpty()) return null;
        for (Range r : ranges) if (caret <= r.end() + 1) return r;
        return ranges.get(ranges.size() - 1);
    }

    private static final Pattern QUOTED_IDS = Pattern.compile("\"(?:[^\"]|\"\")*\"");

    /** 주석을 제거하고 문자열 리터럴/따옴표 식별자의 내용을 비운다(키워드 검사용). */
    public static String stripNoise(String s) {
        String t = COMMENTS.matcher(s).replaceAll(" ");
        t = LITERALS.matcher(t).replaceAll("''");
        return QUOTED_IDS.matcher(t).replaceAll("\"\"");
    }

    public static boolean isBlank(String s) { return COMMENTS.matcher(s).replaceAll("").isBlank(); }

    private static int skipQuoted(String s, int i, char q) {
        int n = s.length();
        i++;
        while (i < n) {
            if (s.charAt(i) == q) {
                if (i + 1 < n && s.charAt(i + 1) == q) i += 2; else return i + 1;
            } else i++;
        }
        return n;
    }

    private static int dollarTagEnd(String s, int i) {
        int n = s.length(), j = i + 1;
        if (j < n && s.charAt(j) == '$') return j + 1;
        if (j < n && (Character.isLetter(s.charAt(j)) || s.charAt(j) == '_')) {
            while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
            if (j < n && s.charAt(j) == '$') return j + 1;
        }
        return -1;
    }

    /** 위험한 문장이면 사유를, 아니면 null. */
    public static String danger(String stmt, boolean explainAnalyze) {
        String t = LITERALS.matcher(COMMENTS.matcher(stmt).replaceAll("")).replaceAll("''").trim().toLowerCase();
        if (t.matches("(?s)^(drop|truncate)\\b.*")) return "파괴적 명령(DROP/TRUNCATE)입니다.";
        if (t.matches("(?s)^(delete|update)\\b.*") && !Pattern.compile("\\bwhere\\b").matcher(t).find())
            return "WHERE 없는 DELETE/UPDATE는 모든 행에 적용됩니다.";
        if (explainAnalyze && !t.matches("(?s)^(select|with|values|table)\\b.*"))
            return "EXPLAIN ANALYZE는 문장을 실제로 실행합니다.";
        return null;
    }
}
