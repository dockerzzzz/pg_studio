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
}
