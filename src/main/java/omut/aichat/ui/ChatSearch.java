package omut.aichat.ui;

import java.util.function.BiConsumer;

public class ChatSearch {

    private final ChatScripts scripts;
    private final BiConsumer<Integer, Integer> controlsUpdater;

    private int searchTotalMatches = 0;
    private int searchCurrentIndex = -1;

    public ChatSearch(
            ChatScripts scripts,
            BiConsumer<Integer, Integer> controlsUpdater
    ) {
        this.scripts = scripts;
        this.controlsUpdater = controlsUpdater;
    }

    public void onSearchQueryChanged(String query) {
        searchTotalMatches = scripts.highlightMatches(query);
        searchCurrentIndex = searchTotalMatches > 0 ? 0 : -1;

        if (searchCurrentIndex == 0) {
            scripts.scrollToMatch(0);
        }

        updateSearchControls();
    }

    public void onSearchNext() {
        if (searchTotalMatches == 0) return;

        searchCurrentIndex = (searchCurrentIndex + 1) % searchTotalMatches;
        scripts.scrollToMatch(searchCurrentIndex);
        updateSearchControls();
    }

    public void onSearchPrev() {
        if (searchTotalMatches == 0) return;

        searchCurrentIndex = (searchCurrentIndex - 1 + searchTotalMatches)
                % searchTotalMatches;
        scripts.scrollToMatch(searchCurrentIndex);
        updateSearchControls();
    }

    public void updateSearchControls() {
        controlsUpdater.accept(searchTotalMatches, searchCurrentIndex);
    }

    public void reapplySearchIfActive(String query) {
        if (query == null || query.isBlank()) return;

        searchTotalMatches = scripts.highlightMatches(query);
        searchCurrentIndex = searchTotalMatches > 0 ? 0 : -1;

        if (searchCurrentIndex == 0) {
            scripts.scrollToMatch(0);
        }

        updateSearchControls();
    }

    public void reset() {
        searchTotalMatches = 0;
        searchCurrentIndex = -1;
        updateSearchControls();
    }
}