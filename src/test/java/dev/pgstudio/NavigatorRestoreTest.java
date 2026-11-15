package dev.pgstudio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 회귀 테스트: 앱을 다시 시작했을 때 저장된 접속이 왼쪽 목록에 보여야 한다. */
class NavigatorRestoreTest {
    @Test void savedConnectionsAreVisibleAfterRestart() {
        ConnectionProfile a = new ConnectionProfile("ra", "first", "h1", 5432, "d", "u", "prefer", false, "");
        ConnectionProfile b = new ConnectionProfile("rb", "second", "h2", 5433, "d", "u", "prefer", true, "");
        ProfileStore.save(List.of(a, b), Map.of("ra", "pw"));

        Navigator restarted = new Navigator(null);   // 앱 재시작과 동일: 파일에서 읽어 목록 구성
        assertEquals(2, restarted.visibleRowCount(), "저장된 접속 2개가 목록에 보여야 함");
    }
}
