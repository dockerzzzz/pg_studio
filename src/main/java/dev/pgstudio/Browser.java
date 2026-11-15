package dev.pgstudio;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

final class Browser {
    private Browser() {}

    /** 기본 브라우저로 http/https 주소를 연다. */
    static void open(String url) throws IOException {
        URI u = URI.create(url);
        if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) throw new IOException("http/https 주소만 열 수 있습니다.");
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(u);
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd = os.contains("mac") ? new String[]{"open", url}
                : os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                : new String[]{"xdg-open", url};
        new ProcessBuilder(cmd).start();
    }
}
