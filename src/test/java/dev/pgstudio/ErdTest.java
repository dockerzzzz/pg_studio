package dev.pgstudio;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErdTest {
    private long nextOid = 1;

    /** cols: "이름:타입" (PK 는 pk 인자로 지정, PK 컬럼은 NOT NULL) */
    private ErdModel.Table table(ErdModel m, String name, List<String> pk, String... cols) {
        ErdModel.Table t = new ErdModel.Table(nextOid++, "demo", name, false);
        int n = 1;
        for (String c : cols) {
            String[] p = c.split(":");
            t.columns.add(new ErdModel.Column(n++, p[0], p[1], pk.contains(p[0])));
        }
        t.pk.addAll(pk);
        m.tables.put(t.oid, t);
        return t;
    }

    private ErdModel.Fk edge(ErdModel m, String child, String col) {
        return m.fks.stream().filter(f -> m.tables.get(f.child()).name.equals(child) && f.childCols().contains(col)).findFirst().orElse(null);
    }

    // ---- 추정 관계 ----

    @Test void infersRelationsFromColumnNamesWithTypeCheck() {
        ErdModel m = new ErdModel();
        ErdModel.Table customers = table(m, "customers", List.of("id"), "id:bigint", "name:text");
        table(m, "categories", List.of("id"), "id:integer", "parent_id:integer");
        table(m, "addresses", List.of("id"), "id:bigint");
        table(m, "orders", List.of("id"), "id:bigint", "customer_id:bigint", "shipping_address_id:bigint", "created_by:bigint");
        table(m, "products", List.of("id"), "id:bigint", "category_id:integer");
        table(m, "order_items", List.of("order_id", "product_id"), "order_id:bigint", "product_id:bigint", "qty:integer");
        table(m, "payments", List.of("id"), "id:uuid", "order_id:uuid");          // 타입 계열이 다르면 연결하지 않음
        ErdModel.Table declared = table(m, "reviews", List.of("id"), "id:bigint", "customer_id:bigint");
        declared.fk.add("customer_id");                                             // 이미 FK 로 선언된 컬럼은 건너뜀
        ErdInference.apply(m);

        ErdModel.Fk a = edge(m, "orders", "customer_id");
        assertNotNull(a);
        assertTrue(a.inferred());
        assertEquals(customers.oid, a.parent());
        assertEquals(List.of("id"), a.parentCols());
        assertEquals("addresses", m.tables.get(edge(m, "orders", "shipping_address_id").parent()).name, "마지막 토큰(address) 복수형으로 연결");
        assertEquals("categories", m.tables.get(edge(m, "products", "category_id").parent()).name, "y → ies");
        assertNotNull(edge(m, "order_items", "order_id"));
        assertNotNull(edge(m, "order_items", "product_id"));
        assertTrue(edge(m, "order_items", "order_id").identifying(), "PK 에 포함된 컬럼은 식별 관계");
        assertFalse(edge(m, "orders", "customer_id").identifying());
        assertNull(edge(m, "categories", "parent_id"), "대응하는 테이블이 없으면 연결하지 않음");
        assertNull(edge(m, "orders", "created_by"));
        assertNull(edge(m, "payments", "order_id"), "uuid vs bigint 는 연결 안 함");
        assertNull(edge(m, "reviews", "customer_id"), "선언된 FK 와 중복 금지");
        assertTrue(m.tables.values().stream().filter(t -> t.name.equals("orders")).findFirst().get().inferred.contains("customer_id"));
    }

    @Test void infersByMatchingSinglePkColumnName() {
        ErdModel m = new ErdModel();
        ErdModel.Table client = table(m, "client", List.of("customer_id"), "customer_id:bigint", "name:text");
        table(m, "invoices", List.of("id"), "id:bigint", "customer_id:bigint");
        table(m, "client_profile", List.of("customer_id"), "customer_id:bigint", "bio:text");   // 같은 PK 묶음 → 대표(client)와 1:1
        ErdInference.apply(m);
        ErdModel.Fk f = edge(m, "invoices", "customer_id");
        assertNotNull(f);
        assertEquals(client.oid, f.parent());
        assertEquals(client.oid, edge(m, "client_profile", "customer_id").parent());
        assertNull(edge(m, "client", "customer_id"), "대표 테이블 자신은 연결하지 않음");
    }

    @Test void sharedPkGroupLinksToMainTable() {
        ErdModel m = new ErdModel();
        ErdModel.Table list = table(m, "tb_pyo_quot_list", List.of("quot_id"), "quot_id:varchar(20)", "clt_id:varchar", "src_amt:numeric");
        table(m, "tb_pyo_quot_ext", List.of("quot_id"), "quot_id:varchar(20)", "svc_key:varchar", "a:varchar", "b:varchar", "c:varchar");
        table(m, "tb_pyo_rmt_list", List.of("rmt_id"), "rmt_id:varchar(50)", "quot_id:varchar(50)");
        ErdInference.apply(m);
        assertEquals(list.oid, edge(m, "tb_pyo_rmt_list", "quot_id").parent(), "컬럼이 더 많아도 _ext 는 대표가 아님");
        assertEquals(list.oid, edge(m, "tb_pyo_quot_ext", "quot_id").parent());
        assertTrue(edge(m, "tb_pyo_quot_ext", "quot_id").identifying());
        assertNull(edge(m, "tb_pyo_quot_list", "quot_id"));
    }

    @Test void genericPkNamesAreNotGrouped() {
        ErdModel m = new ErdModel();
        table(m, "users", List.of("id"), "id:bigint");
        table(m, "orders", List.of("id"), "id:bigint");
        ErdInference.apply(m);
        assertTrue(m.fks.isEmpty(), "PK 가 id 인 테이블끼리는 1:1 로 묶지 않음");
    }

    // ---- 배치 ----

    @Test void layoutPlacesEveryTableWithoutOverlapAndIsDeterministic() {
        ErdModel m = new ErdModel();
        Random rnd = new Random(7);
        List<ErdModel.Table> ts = new ArrayList<>();
        Map<Long, Dimension> sizes = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            ErdModel.Table t = table(m, "t" + i, List.of("id"), "id:bigint");
            ts.add(t);
            sizes.put(t.oid, new Dimension(150 + rnd.nextInt(150), 60 + rnd.nextInt(300)));
        }
        for (int i = 1; i < 30; i++)
            m.fks.add(new ErdModel.Fk("fk" + i, ts.get(i).oid, ts.get(rnd.nextInt(i)).oid, List.of("id"), List.of("id"), false, false));
        Map<Long, Point> a = ErdLayout.compute(m, sizes), b = ErdLayout.compute(m, sizes);
        assertEquals(30, a.size());
        assertEquals(a, b, "같은 입력이면 같은 배치");
        List<Rectangle> rects = new ArrayList<>();
        for (var e : a.entrySet()) rects.add(new Rectangle(e.getValue().x, e.getValue().y, sizes.get(e.getKey()).width, sizes.get(e.getKey()).height));
        for (int i = 0; i < rects.size(); i++)
            for (int j = i + 1; j < rects.size(); j++) assertFalse(rects.get(i).intersects(rects.get(j)), "겹침: " + i + "," + j);
    }

    // ---- 선 모양 / 캔버스 ----

    private ErdModel sampleModel() {
        ErdModel m = new ErdModel();
        ErdModel.Table users = table(m, "users", List.of("id"), "id:bigint", "email:text");
        ErdModel.Table orders = table(m, "orders", List.of("id"), "id:bigint", "user_id:bigint", "total:numeric(10,2)");
        ErdModel.Table items = table(m, "order_items", List.of("order_id", "line"), "order_id:bigint", "line:integer", "qty:integer");
        ErdModel.Table notes = table(m, "notes", List.of("id"), "id:bigint", "order_id:bigint");
        orders.fk.add("user_id");
        items.fk.add("order_id");
        m.fks.add(new ErdModel.Fk("orders_user_fk", orders.oid, users.oid, List.of("user_id"), List.of("id"), false, false));
        m.fks.add(new ErdModel.Fk("items_order_fk", items.oid, orders.oid, List.of("order_id"), List.of("id"), true, false));
        ErdInference.apply(m);   // notes.order_id → orders.id (추정)
        return m;
    }

    @Test void edgeStylesFollowRelationshipKind() {
        ErdModel m = sampleModel();
        assertEquals(ErdCanvas.Style.DASHED, ErdCanvas.styleOf(edge(m, "orders", "user_id")), "비식별 = 점선");
        assertEquals(ErdCanvas.Style.SOLID, ErdCanvas.styleOf(edge(m, "order_items", "order_id")), "식별 = 실선");
        ErdModel.Fk inferred = edge(m, "notes", "order_id");
        assertNotNull(inferred);
        assertEquals(ErdCanvas.Style.DOTTED, ErdCanvas.styleOf(inferred), "추정(FK 없음) = 흐린 점선");
    }

    @Test void canvasRendersTogglesInferredAndExportsPng() throws Exception {
        ErdModel m = sampleModel();
        ErdCanvas c = new ErdCanvas(t -> {});
        c.setSize(1000, 700);
        c.setModel(m, "");
        assertEquals(3, c.visibleFks().size());
        c.setShowInferred(false);
        assertEquals(2, c.visibleFks().size(), "추정 관계 숨기기");
        c.setShowInferred(true);

        BufferedImage img = c.render(1.0);
        assertTrue(img.getWidth() > 300 && img.getHeight() > 100);
        int bg = img.getRGB(2, 2), differing = 0;
        for (int y = 0; y < img.getHeight(); y += 2) for (int x = 0; x < img.getWidth(); x += 2) if (img.getRGB(x, y) != bg) differing++;
        assertTrue(differing > 500, "빈 이미지가 아니어야 함: " + differing);

        File f = File.createTempFile("erd", ".png");
        c.exportPng(f);
        BufferedImage back = ImageIO.read(f);
        assertNotNull(back);
        assertEquals(img.getWidth() * 2, back.getWidth(), 2, "PNG 는 2배 해상도로 저장");
        f.delete();

        int before = c.sizeOf(m.tables.keySet().iterator().next()).height;
        c.setMode(ErdCanvas.Mode.NAME);
        assertTrue(c.sizeOf(m.tables.keySet().iterator().next()).height < before, "테이블명만 보기는 박스가 작아짐");
    }

    private static void mouse(ErdCanvas c, int id, Point p, int clicks) {
        c.dispatchEvent(new MouseEvent(c, id, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, p.x, p.y, clicks, false, MouseEvent.BUTTON1));
    }

    @Test void canvasHitTestDragSelectAndOpen() {
        ErdModel m = sampleModel();
        List<ErdModel.Table> opened = new ArrayList<>();
        ErdCanvas c = new ErdCanvas(opened::add);
        c.setSize(1000, 700);
        c.setModel(m, "");
        ErdModel.Table orders = m.tables.values().stream().filter(t -> t.name.equals("orders")).findFirst().get();

        Point center = c.screenCenter(orders.oid);
        assertEquals(orders, c.tableAt(center), "박스 중앙은 해당 테이블");
        assertNull(c.tableAt(new Point(1, 1)));

        Point before = new Point(c.position(orders.oid));
        mouse(c, MouseEvent.MOUSE_PRESSED, center, 1);
        assertEquals(orders.oid, c.selected(), "클릭하면 선택");
        mouse(c, MouseEvent.MOUSE_DRAGGED, new Point(center.x + 60, center.y + 30), 1);
        mouse(c, MouseEvent.MOUSE_RELEASED, new Point(center.x + 60, center.y + 30), 1);
        Point after = c.position(orders.oid);
        assertEquals(60 / c.scale(), after.x - before.x, 2.0, "드래그한 만큼 이동(축척 반영)");
        assertEquals(30 / c.scale(), after.y - before.y, 2.0);

        Point moved = c.screenCenter(orders.oid);
        mouse(c, MouseEvent.MOUSE_CLICKED, moved, 2);
        assertEquals(List.of(orders), opened, "더블클릭하면 해당 테이블 열기");

        double s0 = c.scale();
        c.zoom(1.5, new Point(500, 350));
        assertTrue(c.scale() > s0);
        c.zoom(100, new Point(500, 350));
        assertTrue(c.scale() <= 3.0, "확대 상한");
        assertTrue(c.focusTable("order_it") && c.selected() != null, "이름으로 찾기");
        assertFalse(c.focusTable("없는테이블"));
    }

    @Test void tooltipDescribesRelationshipKinds() {
        ErdModel m = sampleModel();
        ErdCanvas c = new ErdCanvas(t -> {});
        c.setSize(1000, 700);
        c.setModel(m, "");
        ErdModel.Table notes = m.tables.values().stream().filter(t -> t.name.equals("notes")).findFirst().get();
        Point p = c.position(notes.oid);
        // 두 번째 컬럼(order_id) 행 중앙의 화면 좌표
        Point top = c.screenCenter(notes.oid);
        int rowY = (int) Math.round((p.y + ErdCanvas.HEADER_H + 3 + ErdCanvas.ROW_H * 1.5) * c.scale() + (top.y - (p.y + c.sizeOf(notes.oid).height / 2.0) * c.scale()));
        String tip = c.getToolTipText(new MouseEvent(c, MouseEvent.MOUSE_MOVED, 0, 0, top.x, rowY, 0, false));
        assertNotNull(tip);
        assertTrue(tip.contains("order_id") && tip.contains("추정"), tip);
    }
}
