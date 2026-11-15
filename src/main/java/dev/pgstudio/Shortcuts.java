package dev.pgstudio;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.event.InputEvent;

final class Shortcuts {
    private Shortcuts() {}

    /** 메뉴 단축키 수정자(맥: Cmd, 그 외: Ctrl). 헤드리스(테스트) 환경에서도 안전하다. */
    static int mod() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (HeadlessException e) {
            return System.getProperty("os.name", "").toLowerCase().contains("mac") ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        }
    }
}
