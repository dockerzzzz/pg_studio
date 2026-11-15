package dev.pgstudio;

import java.util.ArrayList;
import java.util.List;

/** Claude 응답을 일반 텍스트와 ``` 코드 블록으로 나눈다. */
final class ChatParser {
    private ChatParser() {}

    record Segment(boolean code, String lang, String text) {}

    static List<Segment> parse(String reply) {
        List<Segment> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inCode = false;
        String lang = "";
        for (String line : reply.replace("\r\n", "\n").split("\n", -1)) {
            String t = line.stripLeading();
            if (t.startsWith("```")) {
                if (!inCode) {
                    flush(out, buf, false, "");
                    inCode = true;
                    lang = t.substring(3).trim().toLowerCase();
                } else {
                    flush(out, buf, true, lang);
                    inCode = false;
                }
            } else buf.append(line).append('\n');
        }
        flush(out, buf, inCode, lang);   // 닫히지 않은 블록은 끝까지 코드로 처리
        return out;
    }

    private static void flush(List<Segment> out, StringBuilder buf, boolean code, String lang) {
        String s = code ? buf.toString().stripTrailing() : buf.toString().strip();
        if (!s.isEmpty()) out.add(new Segment(code, lang, s));
        buf.setLength(0);
    }
}
