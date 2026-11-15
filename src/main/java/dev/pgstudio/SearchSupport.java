package dev.pgstudio;

import org.fife.rsta.ui.search.ReplaceDialog;
import org.fife.rsta.ui.search.SearchEvent;
import org.fife.rsta.ui.search.SearchListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.*;
import java.awt.Frame;

/** 찾기/바꾸기 (Cmd/Ctrl+F). */
final class SearchSupport implements SearchListener {
    private static SearchSupport instance;
    private final ReplaceDialog dialog;
    private RSyntaxTextArea target;

    private SearchSupport(Frame owner) { dialog = new ReplaceDialog(owner, this); }

    static void show(RSyntaxTextArea ta) {
        if (instance == null) instance = new SearchSupport((Frame) SwingUtilities.getWindowAncestor(ta));
        instance.target = ta;
        instance.dialog.setVisible(true);
    }

    @Override public void searchEvent(SearchEvent e) {
        SearchContext ctx = e.getSearchContext();
        switch (e.getType()) {
            case MARK_ALL -> SearchEngine.markAll(target, ctx);
            case FIND -> {
                SearchResult r = SearchEngine.find(target, ctx);
                if (!r.wasFound()) UIManager.getLookAndFeel().provideErrorFeedback(target);
            }
            case REPLACE -> {
                SearchResult r = SearchEngine.replace(target, ctx);
                if (!r.wasFound()) UIManager.getLookAndFeel().provideErrorFeedback(target);
            }
            case REPLACE_ALL -> {
                SearchResult r = SearchEngine.replaceAll(target, ctx);
                JOptionPane.showMessageDialog(dialog, r.getCount() + "건 바꿨습니다.");
            }
        }
    }

    @Override public String getSelectedText() { return target == null ? null : target.getSelectedText(); }
}
