ㅏpackage dev.pgstudio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FK 제약이 없는 논리적 관계 추정(이름 기반 휴리스틱). 틀릴 수 있으므로 화면에서는 흐린 점선으로 구분한다.
 * 규칙 1: `xxx_id` 컬럼 → 이름이 xxx / xxxs / xxxes / xxxies 인 테이블(또는 xxx 의 마지막 토큰)의 단일 PK.
 * 규칙 2: 다른 테이블의 단일 PK 와 컬럼명이 같은 컬럼 → 그 테이블.
 *   같은 이름의 단일 PK 를 가진 테이블이 여럿이면(1:1 분리 테이블) 대표 테이블 하나로 연결하고,
 *   나머지 묶음 테이블도 PK 로 대표 테이블에 연결한다.
 * 부모의 PK 가 단일 컬럼이고 타입 계열이 같을 때만 연결하며, 이미 FK 로 선언된 컬럼과 자기 참조는 건너뛴다.
 */
final class ErdInference {
    private ErdInference() {}

    static void apply(ErdModel m) {
        List<ErdModel.Table> internal = m.tables.values().stream().filter(t -> !t.external).toList();
        Map<String, ErdModel.Table> byName = new HashMap<>();
        for (ErdModel.Table t : internal) byName.put(t.name.toLowerCase(Locale.ROOT), t);
        Map<String, List<ErdModel.Table>> byPk = new HashMap<>();
        for (ErdModel.Table t : internal)
            if (singlePk(t) != null && !GENERIC_PK.contains(singlePk(t).toLowerCase(Locale.ROOT))) byPk.computeIfAbsent(singlePk(t).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(t);
        Map<String, ErdModel.Table> mainByPk = new HashMap<>();
        byPk.forEach((pk, g) -> mainByPk.put(pk, mainOf(g)));

        for (ErdModel.Table child : internal) {
            for (ErdModel.Column col : child.columns) {
                if (child.fk.contains(col.name())) continue;
                String n = col.name().toLowerCase(Locale.ROOT);
                ErdModel.Table parent = null;
                String parentCol = null;

                if (n.endsWith("_id") && n.length() > 3) {
                    String stem = n.substring(0, n.length() - 3);
                    List<String> guesses = new ArrayList<>(forms(stem));
                    if (stem.contains("_")) guesses.addAll(forms(stem.substring(stem.lastIndexOf('_') + 1)));
                    for (String g : guesses) {
                        ErdModel.Table cand = byName.get(g);
                        if (cand != null && cand != child && compatible(cand, col)) { parent = cand; parentCol = singlePk(cand); break; }
                    }
                }
                if (parent == null) {
                    ErdModel.Table main = mainByPk.get(n);
                    if (main != null && main != child && compatible(main, col)) { parent = main; parentCol = singlePk(main); }
                }
                if (parent == null) continue;
                child.inferred.add(col.name());
                m.fks.add(new ErdModel.Fk("(추정) " + child.name + "." + col.name(), child.oid, parent.oid,
                        List.of(col.name()), List.of(parentCol), child.pk.contains(col.name()), true));
            }
        }
    }

    /** 어느 테이블에나 있는 PK 이름은 같은 엔터티를 뜻하지 않으므로 묶지 않는다. */
    private static final Set<String> GENERIC_PK = Set.of("id", "seq", "no", "sn", "idx", "key", "code", "uuid", "oid");
    private static final Set<String> EXT_SUFFIX = Set.of("ext", "dtl", "detail", "details", "add", "extra", "sub",
            "hist", "history", "log", "bak", "tmp");

    /** 같은 PK 묶음의 대표: 확장 테이블로 보이는 이름이 아닌 것, 컬럼이 많은 것, 이름순. */
    static ErdModel.Table mainOf(List<ErdModel.Table> group) {
        return group.stream().min(Comparator
                .comparingInt((ErdModel.Table t) -> EXT_SUFFIX.contains(t.name.substring(t.name.lastIndexOf('_') + 1).toLowerCase(Locale.ROOT)) ? 1 : 0)
                .thenComparingInt(t -> -t.columns.size())
                .thenComparing(t -> t.name)).orElseThrow();
    }

    private static String singlePk(ErdModel.Table t) { return t.pk.size() == 1 ? t.pk.iterator().next() : null; }

    private static boolean compatible(ErdModel.Table parent, ErdModel.Column childCol) {
        String pk = singlePk(parent);
        if (pk == null) return false;
        ErdModel.Column pc = parent.column(pk);
        return pc != null && family(pc.type()).equals(family(childCol.type()));
    }

    static String family(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        if (t.startsWith("int") || t.equals("smallint") || t.equals("bigint") || t.equals("integer")) return "int";
        if (t.contains("char") || t.equals("text")) return "text";
        return t.replaceAll("\\(.*\\)", "");
    }

    /** stem 의 단수/복수 형태들. */
    static List<String> forms(String s) {
        List<String> out = new ArrayList<>(List.of(s, s + "s"));
        if (s.matches(".*[^aeiou]y")) out.add(s.substring(0, s.length() - 1) + "ies");
        if (s.matches(".*(s|x|z|ch|sh)")) out.add(s + "es");
        return out;
    }
}
