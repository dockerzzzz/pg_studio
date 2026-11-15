package dev.pgstudio;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;

final class Fonts {
    private Fonts() {}

    static Font mono(int size) {
        Set<String> fams = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String f : new String[]{"JetBrains Mono", "Menlo", "Consolas", "DejaVu Sans Mono"})
            if (fams.contains(f)) return new Font(f, Font.PLAIN, size);
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }
}
