package com.beautica.service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlugGenerator} (Phase 16.9.1). Pure logic — no Spring context.
 */
@DisplayName("SlugGenerator — unit")
class SlugGeneratorTest {

    /** The entity @Pattern; the DB CHECK is stricter (min len 2, no edge hyphens). */
    private static final Pattern ENTITY_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final SlugGenerator generator = new SlugGenerator();
    private final Predicate<String> never = slug -> false;

    @Test
    @DisplayName("should_transliterateCyrillicToAsciiSlug_when_nameIsUkrainian")
    void should_transliterateCyrillicToAsciiSlug_when_nameIsUkrainian() {
        String slug = generator.uniqueSlug("Манікюр під ключ", "NAIL_SERVICE", never);

        assertThat(slug).matches(ENTITY_SLUG);
        assertThat(slug).isEqualTo("manikiur-pid-kliuch");
    }

    @Test
    @DisplayName("should_appendNumericSuffix_when_slugCollides")
    void should_appendNumericSuffix_when_slugCollides() {
        Set<String> taken = new HashSet<>(Set.of("balaiazh"));

        String slug = generator.uniqueSlug("Балаяж", "HAIR_COLORING", taken::contains);

        assertThat(slug).matches(ENTITY_SLUG);
        assertThat(slug).isEqualTo("balaiazh-2");
    }

    @Test
    @DisplayName("should_fallBackToCategory_when_nameHasNoAsciiOutput")
    void should_fallBackToCategory_when_nameHasNoAsciiOutput() {
        String slug = generator.uniqueSlug("!!! ???", "MAKEUP", never);

        assertThat(slug).matches(ENTITY_SLUG);
        assertThat(slug).startsWith("makeup");
    }

    @Test
    @DisplayName("should_produceValidSlug_when_bothNameAndCategoryUnusable")
    void should_produceValidSlug_when_bothNameAndCategoryUnusable() {
        String slug = generator.uniqueSlug("@@@", "###", never);

        assertThat(slug).matches(ENTITY_SLUG);
        assertThat(slug).isEqualTo("service");
    }

    @Test
    @DisplayName("should_haveNoLeadingOrTrailingHyphen_when_nameHasPunctuationEdges")
    void should_haveNoLeadingOrTrailingHyphen_when_nameHasPunctuationEdges() {
        String slug = generator.uniqueSlug("  -Манікюр-  ", "NAIL_SERVICE", never);

        assertThat(slug).matches(ENTITY_SLUG);
        assertThat(slug).doesNotStartWith("-").doesNotEndWith("-");
    }

    @Test
    @DisplayName("should_appendLongerRandomToken_when_allShorterCandidatesCollide")
    void should_appendLongerRandomToken_when_allShorterCandidatesCollide() {
        // Reject the base, every numeric suffix, and every 6-char random short token —
        // i.e. anything WITHOUT a 16-char random suffix. This drives the generator past
        // the numeric loop and all 5 random-short-token attempts to the longer-token
        // fallback. Matching by suffix length (not exact value) keeps the test decoupled
        // from SecureRandom output while still proving the loop terminates.
        Predicate<String> onlyLongTokenIsFree = candidate -> {
            int lastDash = candidate.lastIndexOf('-');
            String suffix = lastDash < 0 ? candidate : candidate.substring(lastDash + 1);
            // Free (not taken) only when the suffix is the 16-char fallback token.
            return !suffix.matches("[a-z0-9]{16}");
        };

        String slug = generator.uniqueSlug("Балаяж", "HAIR_COLORING", onlyLongTokenIsFree);

        // Terminated and produced a valid, fallback-shaped slug.
        assertThat(slug).matches(ENTITY_SLUG);
        assertThat(slug).startsWith("balaiazh-");
        String suffix = slug.substring(slug.lastIndexOf('-') + 1);
        assertThat(suffix).as("longer fallback token, actual=%s", suffix).hasSize(16);
    }
}
