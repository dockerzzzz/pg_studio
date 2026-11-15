package dev.pgstudio;

import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 자동 배치: 연결 관계를 따라 너비 우선으로 테이블 순서를 정해 이웃이 가까이 놓이게 하고,
 * 그 순서대로 겹치지 않게 선반(shelf) 방식으로 채운다. 결과는 항상 같다(결정적).
 */
final class ErdLayout {
    private ErdLayout() {}

    static final int GAP_X = 96, GAP_Y = 64;

    static Map<Long, Point> compute(ErdModel m, Map<Long, Dimension> sizes) {
        Map<Long, Set<Long>> adj = new HashMap<>();
        for (Long id : m.tables.keySet()) adj.put(id, new HashSet<>());
        for (ErdModel.Fk f : m.fks)
            if (f.child() != f.parent() && adj.containsKey(f.child()) && adj.containsKey(f.parent())) {
                adj.get(f.child()).add(f.parent());
                adj.get(f.parent()).add(f.child());
            }
        Comparator<Long> byDegreeThenName = Comparator.<Long>comparingInt(id -> -adj.get(id).size())
                .thenComparing(id -> m.tables.get(id).id());

        List<Long> order = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Long> starts = new ArrayList<>(m.tables.keySet());
        starts.sort(byDegreeThenName);
        for (Long start : starts) {
            if (!seen.add(start)) continue;
            ArrayDeque<Long> q = new ArrayDeque<>(List.of(start));
            while (!q.isEmpty()) {
                Long cur = q.poll();
                order.add(cur);
                List<Long> next = new ArrayList<>(adj.get(cur));
                next.sort(byDegreeThenName);
                for (Long n : next) if (seen.add(n)) q.add(n);
            }
        }

        long area = 0;
        int maxW = 0;
        for (Long id : order) {
            Dimension d = sizes.get(id);
            area += (long) (d.width + GAP_X) * (d.height + GAP_Y);
            maxW = Math.max(maxW, d.width);
        }
        int target = Math.max(maxW + GAP_X, (int) Math.sqrt(area * 1.6));
        Map<Long, Point> out = new LinkedHashMap<>();
        int x = 0, y = 0, rowH = 0;
        for (Long id : order) {
            Dimension d = sizes.get(id);
            if (x > 0 && x + d.width > target) { x = 0; y += rowH + GAP_Y; rowH = 0; }
            out.put(id, new Point(x, y));
            x += d.width + GAP_X;
            rowH = Math.max(rowH, d.height);
        }
        return out;
    }
}
