package com.beautica.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NormalizedSearchQuery} — specifically the boundary between the executable
 * state and {@code belowMinimumLength()}, which is what decides whether an unauthenticated
 * {@code GET /api/v1/search/**} issues a database statement at all.
 *
 * <p><b>Why this class exists now.</b> {@code trigramServable} was {@code token.length() >= 3} — a
 * LENGTH test standing in for a TRIGRAM test. The two agree on every friendly input and diverge on
 * exactly the one an attacker sends: {@code ?q=•••} is three characters, so it was reported
 * executable and reached {@code SearchService#likeContains} as a {@code %•••%} predicate.
 * {@code pg_trgm} extracts trigrams from alphanumeric runs only, so its key set is empty, no GIN
 * index can serve it, and the query degrades into a sequential scan — the exact failure the
 * 3-character floor was introduced to prevent, reached through the gap in its own proxy. The same
 * root cause was found on the Phase 326 settlement autocomplete and is fixed with the same
 * predicate, defined once here (§DRY: the second copy of a security check is how they drift).
 */
@DisplayName("NormalizedSearchQuery — the executable / unservable boundary")
class NormalizedSearchQueryTest {

    @Nested
    @DisplayName("zero-trigram queries")
    class ZeroTrigram {

        @Test
        @DisplayName("a punctuation-only query is unservable, not executable")
        void should_reportBelowMinimumLength_when_queryBearsNoTrigram() {
            NormalizedSearchQuery query = NormalizedSearchQuery.of("•••");

            assertThat(query.belowMinimumLength())
                    .as("show_trgm('•••') is the empty set — no GIN index can answer it, so this "
                            + "must take the explicit-empty-page branch rather than becoming a "
                            + "'%%•••%%' predicate and a full sequential scan")
                    .isTrue();
            assertThat(query.hasTokens())
                    .as("an unservable query must carry no tokens into the SQL predicate")
                    .isFalse();
            assertThat(query.isAbsent())
                    .as("it is NOT the absent state: something was typed, so the caller owes the "
                            + "user the hint and must not fall back to the unfiltered result set")
                    .isFalse();
        }

        @Test
        @DisplayName("every punctuation run that reached the scan is now unservable, at any length")
        void should_reportBelowMinimumLength_when_queryIsAnyLongPunctuationRun() {
            assertThat(NormalizedSearchQuery.of("•".repeat(100)).belowMinimumLength()).isTrue();
            assertThat(NormalizedSearchQuery.of("%".repeat(100)).belowMinimumLength()).isTrue();
            assertThat(NormalizedSearchQuery.of(".".repeat(100)).belowMinimumLength()).isTrue();
            assertThat(NormalizedSearchQuery.of("'".repeat(100)).belowMinimumLength()).isTrue();
            assertThat(NormalizedSearchQuery.of("---___---").belowMinimumLength()).isTrue();
        }

        @Test
        @DisplayName("a zero-trigram token beside a servable one does not disqualify the query")
        void should_remainExecutable_when_oneTokenBearsTrigramsAndAnotherDoesNot() {
            // The servable token drives the index scan and the other becomes a cheap ANDed
            // recheck on the rows it produced — the same reasoning that makes the floor apply to
            // the longest token rather than to every token. Rejecting the whole query here would
            // break «Мар'я ...»-shaped input for no benefit.
            NormalizedSearchQuery query = NormalizedSearchQuery.of("львів •••");

            assertThat(query.hasTokens()).isTrue();
            assertThat(query.belowMinimumLength()).isFalse();
            assertThat(query.tokens()).containsExactly("львів", "•••");
        }

        @Test
        @DisplayName("ONE alphanumeric anywhere makes a term servable again")
        void should_beExecutable_when_aLongPunctuationRunCarriesASingleLetter() {
            // Measured: «•»x99 + «о» plans as an index scan at 5.9 ms, against 119 ms for the pure
            // run. The guard therefore asks for presence, not for a majority — pinned so it cannot
            // be "tightened" into something that rejects real input.
            assertThat(NormalizedSearchQuery.of("•".repeat(99) + "о").hasTokens()).isTrue();
            assertThat(NormalizedSearchQuery.of("112").hasTokens()).isTrue();
        }

        @Test
        @DisplayName("bearsTrigrams is Unicode-aware — Cyrillic is not ASCII \\p{Alnum}")
        void should_recogniseCyrillicAndDigits_when_testingForTrigrams() {
            assertThat(NormalizedSearchQuery.bearsTrigrams("львів")).isTrue();
            assertThat(NormalizedSearchQuery.bearsTrigrams("lviv")).isTrue();
            assertThat(NormalizedSearchQuery.bearsTrigrams("7")).isTrue();

            assertThat(NormalizedSearchQuery.bearsTrigrams("•••")).isFalse();
            assertThat(NormalizedSearchQuery.bearsTrigrams("’’’")).isFalse();
            assertThat(NormalizedSearchQuery.bearsTrigrams("")).isFalse();
        }
    }

    @Nested
    @DisplayName("the states the guard must not disturb")
    class UnchangedStates {

        @Test
        @DisplayName("an absent query is still absent, not unservable")
        void should_reportAbsent_when_nothingWasTyped() {
            assertThat(NormalizedSearchQuery.of(null).isAbsent()).isTrue();
            assertThat(NormalizedSearchQuery.of("   ").isAbsent()).isTrue();
            assertThat(NormalizedSearchQuery.of(null).belowMinimumLength()).isFalse();
        }

        @Test
        @DisplayName("a short alphabetic query is still below the minimum for the length reason")
        void should_reportBelowMinimumLength_when_queryIsTwoLetters() {
            assertThat(NormalizedSearchQuery.of("ль").belowMinimumLength()).isTrue();
        }

        @Test
        @DisplayName("an apostrophe name is executable and folded onto the straight form")
        void should_remainExecutable_when_queryCarriesACurlyApostrophe() {
            NormalizedSearchQuery query = NormalizedSearchQuery.of("в’ячеслав");

            assertThat(query.hasTokens()).isTrue();
            assertThat(query.tokens()).containsExactly("в'ячеслав");
        }
    }
}
