package dev.pgstudio;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import java.util.prefs.Preferences;

public final class Main {
    private static final Preferences PREFS = Preferences.userNodeForPackage(Main.class);

    public static boolean isDark() { return PREFS.getBoolean("dark", true); }
    public static void setDark(boolean dark) { PREFS.putBoolean("dark", dark); }
    public static int fontSize() { return PREFS.getInt("fontSize", 14); }
    public static void setFontSize(int s) { PREFS.putInt("fontSize", Math.max(9, Math.min(36, s))); }

    public static String claudePath() { return PREFS.get("claudePath", ""); }
    public static void setClaudePath(String p) { PREFS.put("claudePath", p); }
    public static boolean aiPanelVisible() { return PREFS.getBoolean("aiPanel", true); }
    public static void setAiPanelVisible(boolean v) { PREFS.putBoolean("aiPanel", v); }
    public static boolean aiAutoRun() { return PREFS.getBoolean("aiAutoRun", true); }
    public static void setAiAutoRun(boolean v) { PREFS.putBoolean("aiAutoRun", v); }

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        if (isDark()) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        SwingUtilities.invokeLater(() -> new AppFrame().setVisible(true));
    }
}
