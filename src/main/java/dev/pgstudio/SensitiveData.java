package dev.pgstudio;

import java.util.regex.Pattern;

/** 외부(Claude)로 보내기 전에 명백한 민감정보를 찾는 보조 검사. 완전한 탐지를 보장하지 않는다. */
final class SensitiveData {
    private SensitiveData() {}

    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d{4}[- ]?){3}\\d{4}(?!\\d)");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[a-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(password|passwd|pwd|secret|token|api[_-]?key)\\s*[:=]\\s*\\S{4,})");

    /** 발견되면 항목 설명, 없으면 null. */
    static String find(String text) {
        if (text == null) return null;
        if (RRN.matcher(text).find()) return "주민등록번호로 보이는 숫자";
        if (CARD.matcher(text).find()) return "카드번호로 보이는 숫자";
        if (SECRET.matcher(text).find()) return "비밀번호/토큰/API 키로 보이는 문자열";
        return null;
    }
}
