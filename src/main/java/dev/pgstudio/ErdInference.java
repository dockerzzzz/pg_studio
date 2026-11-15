package dev.pgstudio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FK 제약이 없는 논리적 관계 추정(이름 기반 휴리스틱). 틀릴 수 있으므로 화면에서는 흐린 점선으로 구분한다.
 * 규칙 1: `xxx_id` 컬럼 → 이름이 xxx / xxxs / xxxes / xxxies 인 테이블(또는 xxx 의 마지막 토큰)의 단일 PK.
 * 규칙 2: 다른 테이블의 단일 PK 와 컬럼명이 같은 컬럼 → 그 테이블.
 * 부모의 PK 가 단일 컬럼이고 타입 계열이 같을 때만 연결하며, 이미 FK 로 선언된 컬럼과 자기 참조는 건너뛴다.
 */
final class ErdInference {
    private ErdInference() {}

    static void apply(ErdModel m) {
        List<ErdModel.Table> internal = m.tables.values().stream().filter(t -> !t.external).toList();
        Map<String, ErdModel.Table> byName = new HashMap<>();
        for (ErdModel.Table t : internal) byName.put(t.name.toLowerCase(Locale.ROOT), t);

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
                if (parent == null && !(child.pk.size() == 1 && child.pk.contains(col.name()))) {
                    for (ErdModel.Table cand : internal)
                        if (cand != child && n.equals(String.valueOf(singlePk(cand)).toLowerCase(Locale.ROOT)) && compatible(cand, col)) {
                            parent = cand; parentCol = singlePk(cand); break;
                        }
                }
                if (parent == null) continue;
                child.inferred.add(col.name());
                m.fks.add(new ErdModel.Fk("(추정) " + child.name + "." + col.name(), child.oid, parent.oid,
                        List.of(col.name()), List.of(parentCol), child.pk.contains(col.name()), true));
            }
        }
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
