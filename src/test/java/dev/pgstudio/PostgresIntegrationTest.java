package dev.pgstudio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** PGSTUDIO_TEST_URL 이 설정된 경우에만 실행 (예: jdbc:postgresql://localhost:54329/postgres, user=postgres). */
@EnabledIfEnvironmentVariable(named = "PGSTUDIO_TEST_URL", matches = "jdbc:.+")
class PostgresIntegrationTest {
    private static ConnectionProfile profile() {
        return new ConnectionProfile("t1", "test", "localhost", 54329, "postgres", "postgres", "prefer", false,
                System.getenv("PGSTUDIO_TEST_URL"));
    }

    @Test void connectDdlAndCompletion() throws Exception {
        Session s = Session.connect(profile(), "");
        try {
            s.ready.get(10, TimeUnit.SECONDS);
            assertTrue(s.completion.containsKey("shop"));
            assertEquals(List.of("id", "name", "email", "meta", "created_at"), s.completion.get("shop").get("customers"));

            String t = Ddl.table(s.meta, "shop", "customers");
            assertTrue(t.contains("create table shop.customers"), t);
            assertTrue(t.contains("id bigint generated always as identity not null"), t);
            assertTrue(t.toLowerCase().contains("primary key (id)"), t);
            assertTrue(t.toLowerCase().contains("unique (email)"), t);

            String o = Ddl.table(s.meta, "shop", "orders");
            assertTrue(o.toLowerCase().contains("foreign key (customer_id) references shop.customers(id)"), o);
            assertTrue(o.toLowerCase().contains("check ((total >= (0)::numeric))"), o);
            assertTrue(o.toLowerCase().contains("create index orders_cust_idx on shop.orders"), o);
            assertTrue(o.contains("numeric(10,2)") && o.contains("text[]"), o);

            assertTrue(Ddl.view(s.meta, "shop", "big_orders", false).startsWith("create or replace view shop.big_orders as"));
            assertTrue(Ddl.sequence(s.meta, "shop", "orders_id_seq").contains("create sequence shop.orders_id_seq"));
            try (Statement st = s.meta.createStatement(); ResultSet rs = st.executeQuery(
                    "select oid from pg_proc where proname = 'add' and pronamespace = 'shop'::regnamespace")) {
                rs.next();
                assertTrue(Ddl.function(s.meta, rs.getLong(1)).contains("FUNCTION shop.add"));
            }
        } finally { s.close(); }
    }

    @Test void consoleConnectionIsIndependentAndTransactional() throws Exception {
        Session s = Session.connect(profile(), "");
        try (Connection a = s.newConnection(); Connection b = s.newConnection()) {
            a.setSchema("shop");
            a.setAutoCommit(false);
            try (Statement st = a.createStatement()) { st.execute("insert into customers(name) values ('Tx')"); }
            try (Statement st = b.createStatement(); ResultSet rs = st.executeQuery("select count(*) from shop.customers where name='Tx'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "커밋 전에는 다른 연결에서 보이지 않아야 함");
            }
            a.rollback();
        } finally { s.close(); }
    }

    @Test void readOnlyProfileRejectsWrites() throws Exception {
        ConnectionProfile ro = new ConnectionProfile("t2", "ro", "localhost", 54329, "postgres", "postgres", "prefer", true,
                System.getenv("PGSTUDIO_TEST_URL"));
        try (Connection c = Session.open(ro, ""); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("insert into shop.customers(name) values ('x')"));
        }
    }

    @Test void savedPasswordRoundTripAndFilePermissions() throws Exception {
        ConnectionProfile a = new ConnectionProfile("pa", "a", "h", 5432, "d", "u", "prefer", false, "");
        ConnectionProfile b = new ConnectionProfile("pb", "b", "h", 5432, "d", "u", "prefer", false, "");
        ProfileStore.save(List.of(a, b), java.util.Map.of("pa", "s3cret=with spaces#and\\backslash"));
        assertEquals(java.util.Map.of("pa", "s3cret=with spaces#and\\backslash"), ProfileStore.loadPasswords());
        assertEquals(List.of(a, b), ProfileStore.load());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(ProfileStore.DIR.resolve("connections.properties"))));
        // 저장 대상에서 빠지면 파일에서도 사라진다
        ProfileStore.save(List.of(a, b), java.util.Map.of());
        assertTrue(ProfileStore.loadPasswords().isEmpty());
    }

    @Test void profileStoreRoundTripWithoutPassword() throws Exception {
        ConnectionProfile p = new ConnectionProfile("abc123", "my db", "db.example.com", 6543, "app", "me", "require", true, "");
        ProfileStore.save(List.of(p));
        assertEquals(List.of(p), ProfileStore.load());
        String raw = java.nio.file.Files.readString(ProfileStore.DIR.resolve("connections.properties"));
        assertFalse(raw.toLowerCase().matches("(?s).*\\.password\\s*=.*"), "비밀번호 키가 파일에 기록되면 안 됨");
        assertTrue(ProfileStore.DIR.toString().contains("test-home"), "테스트는 격리된 홈을 써야 함: " + ProfileStore.DIR);
    }

    // ---- 그리드 편집 (수동 커밋) ----

    private static int count(Connection c, String where) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select count(*) from shop.grid_test where " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test void gridEditDetectsTargetAndAppliesInManualTransaction() throws Exception {
        Session s = Session.connect(profile(), "");
        try (Connection admin = s.newConnection(); Connection a = s.newConnection(); Connection b = s.newConnection()) {
            try (Statement st = admin.createStatement()) {
                st.execute("drop table if exists shop.grid_test");
                st.execute("create table shop.grid_test (id serial primary key, name text, qty int)");
                st.execute("insert into shop.grid_test(name, qty) values ('one', 1), ('two', 2)");
            }
            // 판별: 단일 테이블 + PK 포함 → 편집 가능
            GridEdit.Target t;
            try (Statement st = a.createStatement(); ResultSet rs = st.executeQuery("select * from shop.grid_test order by id")) {
                t = GridEdit.detect(rs, s.meta);
            }
            assertNotNull(t);
            assertEquals("grid_test", t.table());
            assertArrayEquals(new String[]{"id", "name", "qty"}, t.baseNames());
            assertArrayEquals(new int[]{0}, t.pkIdx());
            // PK 미포함 / 수식 컬럼 / 조인 → 편집 불가 또는 해당 컬럼만 제외
            try (Statement st = a.createStatement(); ResultSet rs = st.executeQuery("select name from shop.grid_test")) {
                assertNull(GridEdit.detect(rs, s.meta), "PK 미포함");
            }
            try (Statement st = a.createStatement(); ResultSet rs = st.executeQuery(
                    "select g.id, o.id from shop.grid_test g, shop.orders o limit 1")) {
                assertNull(GridEdit.detect(rs, s.meta), "조인 결과");
            }
            try (Statement st = a.createStatement(); ResultSet rs = st.executeQuery("select id, upper(name) as u, qty from shop.grid_test")) {
                GridEdit.Target e = GridEdit.detect(rs, s.meta);
                assertNotNull(e);
                assertNull(e.baseNames()[1], "수식 컬럼은 수정 불가");
            }

            // 적용: autocommit 이던 연결이라도 수동 커밋으로 → 커밋 전에는 다른 연결에 안 보임
            a.setAutoCommit(false);
            Savepoint sp = a.setSavepoint();
            Object[] row1 = {1, "one", 1};
            assertEquals(1, GridEdit.execute(a, GridEdit.prepare(t, new GridEdit.Op(GridEdit.Kind.UPDATE, row1, Map.of(1, "ONE", 2, "100")))));
            assertEquals(1, GridEdit.execute(a, GridEdit.prepare(t, new GridEdit.Op(GridEdit.Kind.INSERT, new Object[3], Map.of(1, "three")))));
            assertEquals(1, GridEdit.execute(a, GridEdit.prepare(t, new GridEdit.Op(GridEdit.Kind.DELETE, new Object[]{2, "two", 2}, Map.of()))));
            a.releaseSavepoint(sp);
            assertEquals(1, count(a, "name = 'ONE' and qty = 100"), "자기 트랜잭션에서는 보임(텍스트 '100' 이 int 로 변환됨)");
            assertEquals(0, count(b, "name = 'ONE'"), "커밋 전에는 다른 연결에 안 보여야 함");
            assertEquals(1, count(b, "name = 'two'"));

            // 중간 실패 시 세이브포인트까지만 롤백: 이전 변경은 유지
            Savepoint sp2 = a.setSavepoint();
            assertThrows(SQLException.class, () -> {
                GridEdit.execute(a, GridEdit.prepare(t, new GridEdit.Op(GridEdit.Kind.UPDATE, new Object[]{1, "x", 1}, Map.of(1, "BAD"))));
                GridEdit.execute(a, GridEdit.prepare(t, new GridEdit.Op(GridEdit.Kind.UPDATE, new Object[]{1, "x", 1}, Map.of(2, "not-a-number"))));
            });
            a.rollback(sp2);
            assertEquals(1, count(a, "name = 'ONE'"), "실패한 적용분만 롤백, 이전 변경 유지");

            // 존재하지 않는 PK → 0행: 호출자가 영향 행 수로 감지할 수 있어야 함
            assertEquals(0, GridEdit.execute(a, GridEdit.prepare(t, new GridEdit.Op(GridEdit.Kind.DELETE, new Object[]{999, "?", 0}, Map.of()))));

            a.commit();
            assertEquals(1, count(b, "name = 'ONE' and qty = 100"), "커밋 후 다른 연결에 보임");
            assertEquals(1, count(b, "name = 'three'"));
            assertEquals(0, count(b, "name = 'two'"));
            try (Statement st = admin.createStatement()) { st.execute("drop table shop.grid_test"); }
        } finally { s.close(); }
    }

    // ---- AI 패널: 읽기 전용 연결 ----

    @Test void aiReadOnlyConnectionRunsSelectButServerRejectsWrites() throws Exception {
        Session s = Session.connect(profile(), "");   // 프로필 자체는 읽기 전용이 아님
        try (Connection c = s.newReadOnlyConnection(); Statement st = c.createStatement()) {
            st.setMaxRows(500);
            try (ResultSet rs = st.executeQuery("select count(*) from shop.customers")) {
                ResultData d = ConsoleTab.read(rs, 500, "select count(*) ...", System.nanoTime());
                assertEquals(1, d.rows.size());
            }
            // 클라이언트 검사를 우회했다고 가정해도 서버가 거부해야 한다
            assertThrows(SQLException.class, () -> st.execute("insert into shop.customers(name) values ('ai')"));
            assertThrows(SQLException.class, () -> st.execute("create table shop.ai_x(id int)"));
            assertThrows(SQLException.class, () -> st.execute("select nextval('shop.orders_id_seq')"));
        } finally { s.close(); }
        try (Connection c = Session.open(profile(), ""); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "select count(*) from shop.customers where name = 'ai'")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "거부된 쓰기가 반영되면 안 됨");
        }
    }

    // ---- 실행 중 쿼리 취소 ----

    private static <C extends java.awt.Component> C findIn(java.awt.Container root, Class<C> type, java.util.function.Predicate<C> pred) {
        for (java.awt.Component c : root.getComponents()) {
            if (type.isInstance(c) && pred.test(type.cast(c))) return type.cast(c);
            if (c instanceof java.awt.Container k) { C r = findIn(k, type, pred); if (r != null) return r; }
        }
        return null;
    }

    @Test void cancelButtonStopsLongRunningQuery() throws Exception {
        Session s = Session.connect(profile(), "");
        try {
            ConsoleTab tab = new ConsoleTab(s, null, "select pg_sleep(60);");
            var run = ConsoleTab.class.getDeclaredMethod("runScript");
            run.setAccessible(true);
            javax.swing.SwingUtilities.invokeAndWait(() -> { try { run.invoke(tab); } catch (Exception e) { throw new RuntimeException(e); } });
            javax.swing.JButton cancel = findIn(tab, javax.swing.JButton.class, b -> b.getText().contains("취소"));
            javax.swing.JButton runBtn = findIn(tab, javax.swing.JButton.class, b -> b.getText().contains("실행") && !b.getText().contains("스크립트"));
            assertTrue(cancel.isEnabled(), "실행 중에는 취소 버튼이 활성");
            assertFalse(runBtn.isEnabled(), "실행 중에는 실행 버튼이 비활성");

            // 서버에서 실제로 sleep 중인지 확인
            try (Connection c = Session.open(profile(), ""); Statement st = c.createStatement()) {
                int active = 0;
                for (int i = 0; i < 50 && active == 0; i++) {
                    Thread.sleep(100);
                    try (ResultSet rs = st.executeQuery("select count(*) from pg_stat_activity where query like 'select pg_sleep(60)%' and state = 'active' and pid <> pg_backend_pid()")) {
                        rs.next();
                        active = rs.getInt(1);
                    }
                }
                assertEquals(1, active, "서버에서 쿼리가 실행 중이어야 함");

                long t0 = System.currentTimeMillis();
                javax.swing.SwingUtilities.invokeAndWait(cancel::doClick);
                javax.swing.JTextArea out = findIn(tab, javax.swing.JTextArea.class, a -> !(a instanceof org.fife.ui.rsyntaxtextarea.RSyntaxTextArea));
                for (int i = 0; i < 100 && !out.getText().contains("✖"); i++) { Thread.sleep(100); javax.swing.SwingUtilities.invokeAndWait(() -> {}); }
                long ms = System.currentTimeMillis() - t0;

                assertTrue(out.getText().contains("✖"), "취소되면 Output 에 오류로 표시: " + out.getText());
                assertTrue(out.getText().toLowerCase().contains("cancel"), out.getText());
                assertTrue(ms < 8000, "60초 쿼리가 몇 초 안에 멈춰야 함: " + ms + "ms");
                assertTrue(runBtn.isEnabled() && !cancel.isEnabled(), "취소 후 버튼 상태 복원");
                try (ResultSet rs = st.executeQuery("select count(*) from pg_stat_activity where query like 'select pg_sleep(60)%' and state = 'active' and pid <> pg_backend_pid()")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1), "서버에서도 쿼리가 끝나야 함");
                }
            }
            tab.dispose();
        } finally { s.close(); }
    }

    // ---- ERD 모델 (실제 카탈로그) ----

    @Test void erdModelReadsDeclaredInferredExternalAndSkipsPartitions() throws Exception {
        Session s = Session.connect(profile(), "");
        try (Connection c = s.newConnection(); Statement st = c.createStatement()) {
            st.execute("drop schema if exists erd_t cascade");
            st.execute("drop schema if exists erd_u cascade");
            st.execute("create schema erd_u");
            st.execute("create schema erd_t");
            st.execute("create table erd_u.u (id int primary key, extra text)");
            st.execute("create table erd_t.p (id int primary key, name text)");
            st.execute("create table erd_t.c_ident (p_id int not null references erd_t.p(id), n int not null, primary key (p_id, n))");
            st.execute("create table erd_t.c_non (id int primary key, p_id int references erd_t.p(id))");
            st.execute("create table erd_t.logical (id int primary key, p_id int)");
            st.execute("create table erd_t.ext_child (id int primary key, u_id int references erd_u.u(id))");
            st.execute("create table erd_t.part (id int, d date, primary key (id, d)) partition by range (d)");
            st.execute("create table erd_t.part_1 partition of erd_t.part for values from ('2024-01-01') to ('2025-01-01')");

            ErdModel m = ErdModel.load(s.meta, "erd_t");
            Map<String, ErdModel.Table> byName = new java.util.HashMap<>();
            m.tables.values().forEach(t -> byName.put(t.name, t));

            assertTrue(byName.containsKey("part") && !byName.containsKey("part_1"), "파티션 자식은 제외");
            assertEquals(java.util.Set.of("p_id", "n"), byName.get("c_ident").pk);
            assertTrue(byName.get("c_ident").fk.contains("p_id"));

            ErdModel.Fk ident = m.fks.stream().filter(f -> f.childCols().equals(List.of("p_id")) && !f.inferred() && byName.get("c_ident").oid == f.child()).findFirst().get();
            assertTrue(ident.identifying(), "FK 컬럼이 PK 의 일부 → 식별 관계");
            ErdModel.Fk non = m.fks.stream().filter(f -> byName.get("c_non").oid == f.child() && !f.inferred()).findFirst().get();
            assertFalse(non.identifying(), "PK 가 아닌 FK → 비식별 관계");
            ErdModel.Fk logical = m.fks.stream().filter(f -> byName.get("logical").oid == f.child()).findFirst().get();
            assertTrue(logical.inferred(), "FK 제약이 없으면 추정 관계");
            assertEquals(byName.get("p").oid, logical.parent());
            assertTrue(byName.get("logical").inferred.contains("p_id"));

            ErdModel.Table ext = byName.get("u");
            assertNotNull(ext, "다른 스키마의 참조 대상은 external 로 포함");
            assertTrue(ext.external);
            assertEquals("erd_u", ext.schema);
            assertEquals(List.of("id"), ext.columns.stream().map(ErdModel.Column::name).toList(), "external 은 PK/참조 컬럼만");
            assertEquals(java.util.Set.of("id"), ext.pk);
            assertEquals(1, m.fks.stream().filter(f -> byName.get("ext_child").oid == f.child()).count());
            st.execute("drop schema erd_t cascade");
            st.execute("drop schema erd_u cascade");
        } finally { s.close(); }
    }
}
