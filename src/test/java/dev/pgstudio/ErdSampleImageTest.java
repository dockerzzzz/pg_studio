package dev.pgstudio;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 실제 DB 의 스키마를 앱의 ERD 코드로 렌더링해 PNG 로 저장한다(문서/확인용). PGSTUDIO_ERD_SAMPLE_DIR 와 PGSTUDIO_TEST_URL 이 필요하다. */
@EnabledIfEnvironmentVariable(named = "PGSTUDIO_ERD_SAMPLE_DIR", matches = ".+")
class ErdSampleImageTest {
    @Test void renderSamples() throws Exception {
        File dir = new File(System.getenv("PGSTUDIO_ERD_SAMPLE_DIR"));
        dir.mkdirs();
        ConnectionProfile p = new ConnectionProfile("s", "sample", "localhost", 54329, "postgres", "postgres", "prefer", false, System.getenv("PGSTUDIO_TEST_URL"));
        Session s = Session.connect(p, "");
        try {
            ErdModel m = ErdModel.load(s.meta, System.getProperty("erd.schema", "demo"));
            for (boolean dark : new boolean[]{false, true}) {
                if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
                ErdCanvas c = new ErdCanvas(t -> {});
                c.setSize(1500, 900);
                c.setModel(m, "");
                ImageIO.write(c.render(1.5), "png", new File(dir, "erd-" + (dark ? "dark" : "light") + "-full.png"));
                if (!dark) {   // 화면 캡처처럼: 주문 테이블 선택(관계 강조) 상태
                    ErdModel.Table orders = m.tables.values().stream().filter(t -> t.name.equals("orders")).findFirst().get();
                    c.select(orders.oid);
                    BufferedImage shot = new BufferedImage(1500, 900, BufferedImage.TYPE_INT_ARGB);
                    var g = shot.createGraphics();
                    c.paint(g);
                    g.dispose();
                    ImageIO.write(shot, "png", new File(dir, "erd-light-selected-orders.png"));
                }
            }
        } finally { s.close(); }
    }
}
