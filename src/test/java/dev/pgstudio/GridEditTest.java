package dev.pgstudio;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridEditTest {
    // 컬럼: id(PK), name, qty
    private static final GridEdit.Target T = new GridEdit.Target("shop", "items", new String[]{"id", "name", "qty"}, new int[]{0});

    @Test void updateSqlUsesBindParamsAndPkFromOriginalRow() {
        Object[] row = {7L, "old", 3};
        Map<Integer, String> v = new HashMap<>();
        v.put(2, "10");
        v.put(1, null);
        GridEdit.Prepared p = GridEdit.prepare(T, new GridEdit.Op(GridEdit.Kind.UPDATE, row, v));
        assertEquals("update shop.items set name = ?, qty = ? where id = ?", p.sql());
        assertEquals(Arrays.asList(null, new GridEdit.Txt("10"), 7L), p.params());
        assertEquals("update shop.items set name = NULL, qty = '10' where id = 7", p.preview());
    }

    @Test void deleteAndInsertAndQuoting() {
        GridEdit.Prepared d = GridEdit.prepare(T, new GridEdit.Op(GridEdit.Kind.DELETE, new Object[]{1L, "a", 1}, Map.of()));
        assertEquals("delete from shop.items where id = ?", d.sql());
        GridEdit.Prepared i0 = GridEdit.prepare(T, new GridEdit.Op(GridEdit.Kind.INSERT, new Object[3], Map.of()));
        assertEquals("insert into shop.items default values", i0.sql());
        GridEdit.Prepared i1 = GridEdit.prepare(T, new GridEdit.Op(GridEdit.Kind.INSERT, new Object[3], Map.of(1, "it's")));
        assertEquals("insert into shop.items (name) values (?)", i1.sql());
        assertEquals("insert into shop.items (name) values ('it''s')", i1.preview());
        GridEdit.Target odd = new GridEdit.Target("My Schema", "Order", new String[]{"Id", "select"}, new int[]{0});
        assertEquals("update \"My Schema\".\"Order\" set \"select\" = ? where \"Id\" = ?",
                GridEdit.prepare(odd, new GridEdit.Op(GridEdit.Kind.UPDATE, new Object[]{1, "x"}, Map.of(1, "y"))).sql());
    }

    // ---- 헤드리스 UI 흐름 ----

    private static <C extends Component> C find(Container root, Class<C> type, java.util.function.Predicate<C> pred) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && pred.test(type.cast(c))) return type.cast(c);
            if (c instanceof Container k) { C r = find(k, type, pred); if (r != null) return r; }
        }
        return null;
    }

    private static ResultData data() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1L, "a", 5});
        rows.add(new Object[]{2L, "b", null});
        ResultData d = new ResultData(List.of("id", "name", "qty"), List.of("int8", "text", "int4"), rows, false, 1, "select * from shop.items");
        d.edit = T;
        return d;
    }

    private static final class Capture implements ResultPanel.GridEditor {
        boolean can = true;
        List<GridEdit.Op> ops;
        public boolean canEdit() { return can; }
        public void apply(ResultData d, List<GridEdit.Op> ops, Runnable onSuccess) { this.ops = ops; onSuccess.run(); }
    }

    @Test void editingCellsBuildsOpsAndApplyConfirmsRows() {
        Capture cap = new Capture();
        ResultData d = data();
        ResultPanel p = new ResultPanel(d, cap);
        JTable table = find(p, JTable.class, t -> true);
        JButton apply = find(p, JButton.class, b -> "변경 적용".equals(b.getText()));
        assertFalse(apply.isEnabled(), "변경 전에는 적용 불가");

        table.getModel().setValueAt("zzz", 0, 1);   // name 수정
        table.getModel().setValueAt("", 1, 2);      // NULL 셀을 비운 채 닫음 → 변경 아님
        assertTrue(apply.isEnabled());
        assertEquals("zzz", table.getModel().getValueAt(0, 1));

        table.getModel().setValueAt("a", 0, 1);     // 원래 값으로 되돌리면 변경 취소
        assertFalse(apply.isEnabled());

        table.getModel().setValueAt("zzz", 0, 1);
        apply.doClick();
        assertEquals(1, cap.ops.size());
        assertEquals(GridEdit.Kind.UPDATE, cap.ops.get(0).kind());
        assertEquals(Map.of(1, "zzz"), cap.ops.get(0).values());
        assertEquals(1L, cap.ops.get(0).row()[0], "WHERE 용 원본 PK 보존");
        assertEquals("zzz", d.rows.get(0)[1], "적용 성공 후 그리드 값 확정");
        assertFalse(apply.isEnabled());
    }

    @Test void pkIsNeverEditableAfterApplyAndReadOnlyGridHasNoEditor() {
        Capture cap = new Capture();
        ResultPanel p = new ResultPanel(data(), cap);
        JTable table = find(p, JTable.class, t -> true);
        assertTrue(table.getModel().isCellEditable(0, 1));
        table.getModel().setValueAt("77", 0, 0);   // PK 수정 → 적용 후 stale(재조회 필요)
        find(p, JButton.class, b -> "변경 적용".equals(b.getText())).doClick();
        assertFalse(table.getModel().isCellEditable(0, 1), "PK가 바뀐 행은 다시 조회하기 전까지 수정 불가");

        Capture ro = new Capture();
        ro.can = false;
        ResultPanel rp = new ResultPanel(data(), ro);
        assertFalse(find(rp, JTable.class, t -> true).getModel().isCellEditable(0, 1), "읽기 전용 접속은 수정 불가");
        assertNull(find(rp, JButton.class, b -> "변경 적용".equals(b.getText())));

        ResultData noPk = data();
        noPk.edit = null;
        ResultPanel np = new ResultPanel(noPk, new Capture());
        assertFalse(find(np, JTable.class, t -> true).getModel().isCellEditable(0, 1), "PK 없는 결과는 수정 불가");
    }

    @Test void insertAndDeleteOps() {
        Capture cap = new Capture();
        ResultData d = data();
        ResultPanel p = new ResultPanel(d, cap);
        JTable table = find(p, JTable.class, t -> true);
        find(p, JButton.class, b -> "행 추가".equals(b.getText())).doClick();
        assertEquals(3, d.rows.size());
        table.getModel().setValueAt("new", 2, 1);
        table.setRowSelectionInterval(0, 0);
        table.setColumnSelectionInterval(0, 0);
        find(p, JButton.class, b -> "행 삭제".equals(b.getText())).doClick();
        find(p, JButton.class, b -> "변경 적용".equals(b.getText())).doClick();
        assertEquals(List.of(GridEdit.Kind.DELETE, GridEdit.Kind.INSERT), cap.ops.stream().map(GridEdit.Op::kind).toList());
        assertEquals(Map.of(1, "new"), cap.ops.get(1).values());
        assertEquals(2, d.rows.size(), "삭제 행 제거, 신규 행 유지");
    }
}
