package omut.aichat.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ChatSearch")
class ChatSearchTest {

    private ChatScripts scripts;
    private ChatSearch search;
    private AtomicInteger totalMatches;
    private AtomicInteger currentIndex;

    @BeforeEach
    void setUp() {
        scripts = mock(ChatScripts.class);
        totalMatches = new AtomicInteger(-1);
        currentIndex = new AtomicInteger(-1);

        search = new ChatSearch(
                scripts,
                (total, current) -> {
                    totalMatches.set(total);
                    currentIndex.set(current);
                }
        );
    }

    @Nested
    @DisplayName("Query changes")
    class QueryChanges {

        @Test
        @DisplayName("Should highlight matches and select the first one")
        void shouldHighlightMatchesAndSelectFirst() {
            when(scripts.highlightMatches("hello")).thenReturn(3);

            search.onSearchQueryChanged("hello");

            verify(scripts).highlightMatches("hello");
            verify(scripts).scrollToMatch(0);
            assertThat(totalMatches.get()).isEqualTo(3);
            assertThat(currentIndex.get()).isZero();
        }

        @Test
        @DisplayName("Should reset current index when no matches found")
        void shouldResetWhenNoMatchesFound() {
            when(scripts.highlightMatches("missing")).thenReturn(0);

            search.onSearchQueryChanged("missing");

            verify(scripts, never()).scrollToMatch(anyInt());
            assertThat(totalMatches.get()).isZero();
            assertThat(currentIndex.get()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("Navigation")
    class Navigation {

        @Test
        @DisplayName("Should move to the next match")
        void shouldMoveToNextMatch() {
            when(scripts.highlightMatches("hello")).thenReturn(3);
            search.onSearchQueryChanged("hello");

            search.onSearchNext();

            verify(scripts).scrollToMatch(1);
            assertThat(currentIndex.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should wrap to the first match after the last")
        void shouldWrapToFirstMatch() {
            when(scripts.highlightMatches("hello")).thenReturn(2);
            search.onSearchQueryChanged("hello");

            search.onSearchNext();
            search.onSearchNext();

            verify(scripts, times(2)).scrollToMatch(0);
            assertThat(currentIndex.get()).isZero();
        }

        @Test
        @DisplayName("Should move to the previous match")
        void shouldMoveToPreviousMatch() {
            when(scripts.highlightMatches("hello")).thenReturn(3);
            search.onSearchQueryChanged("hello");

            search.onSearchPrev();

            verify(scripts).scrollToMatch(2);
            assertThat(currentIndex.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should wrap to the last match when moving backward")
        void shouldWrapToLastMatch() {
            when(scripts.highlightMatches("hello")).thenReturn(2);
            search.onSearchQueryChanged("hello");

            search.onSearchPrev();

            verify(scripts).scrollToMatch(1);
            assertThat(currentIndex.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should do nothing when there are no matches")
        void shouldDoNothingWhenThereAreNoMatches() {
            search.onSearchNext();
            search.onSearchPrev();

            verifyNoInteractions(scripts);
            assertThat(totalMatches.get()).isEqualTo(-1);
            assertThat(currentIndex.get()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("Search reapplication")
    class SearchReapplication {

        @Test
        @DisplayName("Should reapply an active search")
        void shouldReapplyActiveSearch() {
            when(scripts.highlightMatches("hello")).thenReturn(2);

            search.reapplySearchIfActive("hello");

            verify(scripts).highlightMatches("hello");
            verify(scripts).scrollToMatch(0);
            assertThat(totalMatches.get()).isEqualTo(2);
            assertThat(currentIndex.get()).isZero();
        }

        @Test
        @DisplayName("Should do nothing for a blank query")
        void shouldIgnoreBlankQuery() {
            search.reapplySearchIfActive(" ");

            verifyNoInteractions(scripts);
            assertThat(totalMatches.get()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should reset index when reapplying a query with no matches")
        void shouldResetWhenReappliedQueryHasNoMatches() {
            when(scripts.highlightMatches("missing")).thenReturn(0);

            search.reapplySearchIfActive("missing");

            verify(scripts).highlightMatches("missing");
            verify(scripts, never()).scrollToMatch(anyInt());
            assertThat(totalMatches.get()).isZero();
            assertThat(currentIndex.get()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("Reset")
    class Reset {

        @Test
        @DisplayName("Should reset search state and update controls")
        void shouldResetSearchState() {
            when(scripts.highlightMatches("hello")).thenReturn(2);
            search.onSearchQueryChanged("hello");

            search.reset();

            assertThat(totalMatches.get()).isZero();
            assertThat(currentIndex.get()).isEqualTo(-1);
        }
    }
}