package com.beautica.search.service;

import com.beautica.service.service.PlatformCategoryLabel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QueryCategoryMatcher} — the resolution step that turns a
 * free-text query into the {@code platform_categories.name} set the SQL binds.
 *
 * <p>The rule under test is the one the whole fix rests on: a category qualifies
 * only when its display name contains EVERY token. That is what makes the emitted
 * per-token {@code category IN (…)} disjunct equivalent to a whole-group one, and
 * therefore what keeps the group-scoped {@code q} semantics intact.
 */
@DisplayName("QueryCategoryMatcher — unit")
class QueryCategoryMatcherTest {

    /** The V74-seeded labels this test reasons about. */
    private static final PlatformCategoryLabel LASH_EXTENSIONS =
            new PlatformCategoryLabel("LASH_EXTENSIONS", "Нарощення вій");
    private static final PlatformCategoryLabel LASH_LAMINATION =
            new PlatformCategoryLabel("LASH_LAMINATION", "Ламінування вій");
    private static final PlatformCategoryLabel HAIR_EXTENSIONS =
            new PlatformCategoryLabel("HAIR_EXTENSIONS", "Нарощування волосся");
    private static final PlatformCategoryLabel INJECTION =
            new PlatformCategoryLabel("INJECTION_COSMETOLOGY", "Ін'єкційна косметологія");

    private static final List<PlatformCategoryLabel> SEED =
            List.of(LASH_EXTENSIONS, LASH_LAMINATION, HAIR_EXTENSIONS, INJECTION);

    @Test
    @DisplayName("should_resolveTheCategory_when_everyTokenIsInItsDisplayName")
    void should_resolveTheCategory_when_everyTokenIsInItsDisplayName() {
        List<String> tokens = List.of("нарощення", "вій");

        List<String> matched = QueryCategoryMatcher.matchingCategoryNames(SEED, tokens);

        assertThat(matched)
                .as("«Нарощення вій» is the only label containing BOTH tokens")
                .containsExactly("LASH_EXTENSIONS");
    }

    @Test
    @DisplayName("should_excludeLabel_when_itCarriesOnlySomeTokens")
    void should_excludeLabel_when_itCarriesOnlySomeTokens() {
        // «Ламінування вій» shares «вій»; «Нарощування волосся» shares neither token
        // as a substring («нарощування» ≠ «нарощення»).
        List<String> tokens = List.of("нарощення", "вій");

        List<String> matched = QueryCategoryMatcher.matchingCategoryNames(SEED, tokens);

        assertThat(matched)
                .as("a partial token hit must not widen the search to a category the user did not name")
                .doesNotContain("LASH_LAMINATION", "HAIR_EXTENSIONS");
    }

    @Test
    @DisplayName("should_resolveEveryContainingCategory_when_theQueryIsASingleSharedToken")
    void should_resolveEveryContainingCategory_when_theQueryIsASingleSharedToken() {
        List<String> matched = QueryCategoryMatcher.matchingCategoryNames(SEED, List.of("вій"));

        assertThat(matched)
                .as("a single token legitimately matches every category whose label contains it, "
                        + "in the input (display-name) order")
                .containsExactly("LASH_EXTENSIONS", "LASH_LAMINATION");
    }

    @Test
    @DisplayName("should_matchCaseInsensitively_when_theUserTypesAnyCasing")
    void should_matchCaseInsensitively_when_theUserTypesAnyCasing() {
        // Mirrors ILIKE: the SQL half of the predicate is case-insensitive, so the
        // Java half resolving the label has to be too, or the two disagree.
        List<String> matched =
                QueryCategoryMatcher.matchingCategoryNames(SEED, List.of("НАРОЩЕННЯ", "ВіЙ"));

        assertThat(matched).containsExactly("LASH_EXTENSIONS");
    }

    @Test
    @DisplayName("should_matchAcrossApostropheVariants_when_theKeyboardEmitsACurlyForm")
    void should_matchAcrossApostropheVariants_when_theKeyboardEmitsACurlyForm() {
        // NormalizedSearchQuery folds the query onto U+0027; the label is folded the
        // same way, so a stored curly form would still match — and today's straight
        // stored form matches a curly-typed query.
        List<String> tokens = List.of(NormalizedSearchQuery.foldApostrophes("ін’єкційна"));

        List<String> matched = QueryCategoryMatcher.matchingCategoryNames(
                List.of(new PlatformCategoryLabel("INJECTION_COSMETOLOGY", "Ін’єкційна косметологія")),
                tokens);

        assertThat(matched).containsExactly("INJECTION_COSMETOLOGY");
    }

    @Test
    @DisplayName("should_matchOnSubstring_when_theTokenIsAPartialWord")
    void should_matchOnSubstring_when_theTokenIsAPartialWord() {
        // Deliberately substring, not prefix/word-boundary — ILIKE '%…%' is substring,
        // and an incremental search box sends partial words on every keystroke.
        List<String> matched = QueryCategoryMatcher.matchingCategoryNames(SEED, List.of("ощення"));

        assertThat(matched).containsExactly("LASH_EXTENSIONS");
    }

    @Test
    @DisplayName("should_returnEmpty_when_noLabelContainsAllTokens")
    void should_returnEmpty_when_noLabelContainsAllTokens() {
        List<String> matched =
                QueryCategoryMatcher.matchingCategoryNames(SEED, List.of("коваленко"));

        assertThat(matched)
                .as("the overwhelmingly common case — the query is a person or service name; "
                        + "an empty result is what keeps the generated SQL byte-identical")
                .isEmpty();
    }

    @Test
    @DisplayName("should_returnEmpty_when_thereAreNoTokens")
    void should_returnEmpty_when_thereAreNoTokens() {
        List<String> matched = QueryCategoryMatcher.matchingCategoryNames(SEED, List.of());

        assertThat(matched)
                .as("an absent query must not resolve to every category")
                .isEmpty();
    }

    @Test
    @DisplayName("should_returnEmpty_when_noCategoriesAreSelectable")
    void should_returnEmpty_when_noCategoriesAreSelectable() {
        List<String> matched =
                QueryCategoryMatcher.matchingCategoryNames(List.of(), List.of("нарощення"));

        assertThat(matched).isEmpty();
    }
}
