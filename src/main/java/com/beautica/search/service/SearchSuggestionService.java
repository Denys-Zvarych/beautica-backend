package com.beautica.search.service;

import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.SearchSuggestionResponse;
import com.beautica.search.dto.SuggestionType;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * D4's pure fold/match/rank/cap logic for {@code GET /api/v1/search/suggestions} (Phase 331). No
 * SQL, no caching — takes the already-cached {@link SuggestionCatalogueSnapshot} and
 * {@link PlaceAvailability} and returns the ranked, capped response list.
 *
 * <h3>Matching</h3>
 * Both sides are folded identically to the search-domain form
 * ({@link NormalizedSearchQuery#foldApostrophes}, then {@code toLowerCase(Locale.ROOT)}) — the
 * SAME fold {@code QueryCategoryMatcher} applies, so a category label that resolves free-text
 * {@code q} on {@code /search/masters} resolves identically here. A term of 1–2 characters must
 * match the START of the label or of any whitespace/hyphen-separated word within it; a term of 3+
 * characters may match anywhere (substring). Multi-word queries AND every whitespace-split token
 * (mirroring {@code QueryCategoryMatcher}'s group-scoped semantics) — each token independently
 * subject to its OWN length-based rule.
 *
 * <h3>Ranking</h3>
 * label-start &gt; word-start &gt; substring (the label-vs-word distinction is computed for
 * ranking even when the admission rule only required a weaker match — a 3+-char term that happens
 * to start the label outranks one that only appears mid-word). A candidate matched by multiple
 * tokens ranks at its WORST (lowest-quality) per-token tier — it is only as good as its weakest
 * token. On a tier tie, {@link SuggestionType#CATEGORY} sorts before {@link SuggestionType#SERVICE};
 * within the same type, categories keep the catalogue's {@code displayName} order and services are
 * compared by {@code nameUk} under a Ukrainian {@link Collator}.
 */
@Service
public class SearchSuggestionService {

    /** Below this length a term must match a WORD START; at or above it, any substring. */
    private static final int WORD_START_ONLY_BELOW = 3;

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s\\-]+");

    private final SearchSuggestionCatalogue catalogue;
    private final SearchSuggestionAvailability availability;
    private final SearchSuggestionActivePlaces activePlaces;

    public SearchSuggestionService(
            SearchSuggestionCatalogue catalogue,
            SearchSuggestionAvailability availability,
            SearchSuggestionActivePlaces activePlaces) {
        this.catalogue = catalogue;
        this.availability = availability;
        this.activePlaces = activePlaces;
    }

    /**
     * @param rawQuery the caller's {@code q}, already {@code @NotBlank}/{@code @Size(max=50)}
     *                 validated at the controller boundary
     * @param limit    already resolved (the controller applies the {@code null -> 8} default)
     * @param location {@code null}, or a {@link LocationFilter} whose fields may themselves be
     *                 {@code null} — either shape means "no place chosen", i.e. the national list
     * @return at most {@code limit} ranked suggestions; never {@code null}
     */
    public List<SearchSuggestionResponse> suggest(String rawQuery, int limit, LocationFilter location) {
        List<String> tokens = tokenize(rawQuery);
        if (tokens.isEmpty()) {
            return List.of();
        }

        SuggestionCatalogueSnapshot snapshot = catalogue.snapshot();
        PlaceAvailability place = resolvePlaceAvailability(SuggestionPlaceKey.of(
                location != null ? location.cityId() : null,
                location != null ? location.districtId() : null));

        List<Candidate> candidates = new ArrayList<>();
        collectCategoryCandidates(snapshot, place, tokens, candidates);
        collectServiceCandidates(snapshot, place, tokens, snapshot.categoriesByKey(), candidates);

        Collator ukCollator = Collator.getInstance(Locale.forLanguageTag("uk"));
        candidates.sort(rankComparator(ukCollator));

        return candidates.stream()
                .limit(limit)
                .map(Candidate::toResponse)
                .toList();
    }

    /**
     * Audit-fix cycle 1, finding 2 — the active-places short-circuit. The national key
     * ({@link SuggestionPlaceKey#isNational()}) always resolves through
     * {@link SearchSuggestionAvailability#forPlace}: it is one fixed cache entry, not
     * attacker-cyclable, and D5 requires a genuine (possibly empty) national fallback. For any other
     * key, {@link SearchSuggestionActivePlaces#snapshot()} is consulted FIRST; a place absent from
     * that set returns {@link PlaceAvailability#EMPTY} without ever calling
     * {@code availability.forPlace} — no repository query, no per-place cache entry minted for an
     * unknown or attacker-chosen id. Both calls below cross a Spring proxy boundary (this service is
     * a different bean from both {@link SearchSuggestionAvailability} and
     * {@link SearchSuggestionActivePlaces}), so neither {@code @Cacheable} is bypassed by
     * self-invocation (§F-3).
     */
    private PlaceAvailability resolvePlaceAvailability(SuggestionPlaceKey key) {
        if (key.isNational()) {
            return availability.forPlace(key);
        }
        ActivePlaces active = activePlaces.snapshot();
        boolean isActive = key.districtId() != null
                ? active.districtIds().contains(key.districtId())
                : active.cityIds().contains(key.cityId());
        if (!isActive) {
            return PlaceAvailability.EMPTY;
        }
        return availability.forPlace(key);
    }

    private void collectCategoryCandidates(
            SuggestionCatalogueSnapshot snapshot,
            PlaceAvailability place,
            List<String> tokens,
            List<Candidate> out) {
        int order = 0;
        for (CatalogueCategoryEntry category : snapshot.categories()) {
            int catalogueOrder = order++;
            if (!place.categoryKeys().contains(category.key())) {
                continue;
            }
            worstTierAcrossTokens(category.foldedLabel(), tokens)
                    .ifPresent(tier -> out.add(new Candidate(
                            SuggestionType.CATEGORY, category.label(), category.key(), null,
                            tier, catalogueOrder)));
        }
    }

    private void collectServiceCandidates(
            SuggestionCatalogueSnapshot snapshot,
            PlaceAvailability place,
            List<String> tokens,
            Map<String, CatalogueCategoryEntry> categoryByKey,
            List<Candidate> out) {
        for (CatalogueServiceEntry service : snapshot.services()) {
            if (!place.serviceTypeIds().contains(service.id())) {
                continue;
            }
            // D4 dedup: a SERVICE whose label folds equal to its own category's label is dropped
            // — it would otherwise show up as two identical-looking rows. Both sides are already
            // pre-folded (finding 3), so this is a plain equals(), not a re-fold.
            CatalogueCategoryEntry category = categoryByKey.get(service.categoryKey());
            if (category != null && category.foldedLabel().equals(service.foldedNameUk())) {
                continue;
            }
            worstTierAcrossTokens(service.foldedNameUk(), tokens)
                    .ifPresent(tier -> out.add(new Candidate(
                            SuggestionType.SERVICE, service.nameUk(), service.categoryKey(), service.slug(),
                            tier, -1)));
        }
    }

    /**
     * A candidate is only as good as its weakest matching token — every token must match (AND),
     * and the candidate's rank is the WORST (numerically highest ordinal) tier among them.
     *
     * @param foldedLabel the candidate's label, ALREADY folded (finding 3) — the catalogue
     *                    pre-folds it once at snapshot-build time; this method never folds it again
     * @return empty when any token fails to match at all
     */
    private static Optional<MatchTier> worstTierAcrossTokens(String foldedLabel, List<String> tokens) {
        MatchTier worst = null;
        for (String token : tokens) {
            Optional<MatchTier> tier = tierForToken(foldedLabel, token);
            if (tier.isEmpty()) {
                return Optional.empty();
            }
            if (worst == null || tier.get().ordinal() > worst.ordinal()) {
                worst = tier.get();
            }
        }
        return Optional.ofNullable(worst);
    }

    /**
     * Admission + tier for ONE already-folded token against an already-folded label.
     *
     * <p>Admission: below {@link #WORD_START_ONLY_BELOW} characters the token must start the
     * label or one of its whitespace/hyphen-separated words; at or above it, a plain substring is
     * enough. Once admitted, the tier is recomputed independently of which rule admitted it, so a
     * 3+-char token that happens to start the label still ranks as {@link MatchTier#LABEL_START}.
     */
    private static Optional<MatchTier> tierForToken(String foldedLabel, String token) {
        boolean admitted = token.length() >= WORD_START_ONLY_BELOW
                ? foldedLabel.contains(token)
                : hasWordStartingWith(foldedLabel, token);
        if (!admitted) {
            return Optional.empty();
        }
        if (foldedLabel.startsWith(token)) {
            return Optional.of(MatchTier.LABEL_START);
        }
        if (hasWordStartingWith(foldedLabel, token)) {
            return Optional.of(MatchTier.WORD_START);
        }
        return Optional.of(MatchTier.SUBSTRING);
    }

    private static boolean hasWordStartingWith(String foldedLabel, String token) {
        for (String word : WORD_SPLIT.split(foldedLabel)) {
            if (word.startsWith(token)) {
                return true;
            }
        }
        return false;
    }

    /** Whitespace-collapsed, apostrophe- and case-folded query tokens. Empty for a blank query. */
    private static List<String> tokenize(String rawQuery) {
        String folded = fold(rawQuery).trim();
        if (folded.isEmpty()) {
            return List.of();
        }
        return List.of(folded.split("\\s+"));
    }

    /**
     * The query side of the fold (D4) — the term typed THIS request, so unlike a catalogue label
     * there is nothing to pre-fold; delegates to the single shared definition
     * ({@link SuggestionFold#fold}) so the two sides can never drift.
     */
    private static String fold(String value) {
        return SuggestionFold.fold(value);
    }

    private static Comparator<Candidate> rankComparator(Collator ukCollator) {
        return Comparator.<Candidate>comparingInt(c -> c.tier().ordinal())
                .thenComparingInt(c -> c.type() == SuggestionType.CATEGORY ? 0 : 1)
                .thenComparing((a, b) -> a.type() == SuggestionType.CATEGORY
                        ? Integer.compare(a.catalogueOrder(), b.catalogueOrder())
                        : ukCollator.compare(a.label(), b.label()));
    }

    private enum MatchTier {
        LABEL_START, WORD_START, SUBSTRING
    }

    /**
     * @param catalogueOrder the category's position in {@code snapshot.categories()} (already
     *                       {@code displayName} order — {@code PlatformCategory} has no explicit
     *                       {@code sortOrder}, same convention as
     *                       {@code ServiceCatalogService#buildCategoryOrderAndNames}); unused
     *                       ({@code -1}) for a SERVICE row, which ties on {@code nameUk} instead
     */
    private record Candidate(
            SuggestionType type, String label, String categoryKey, String serviceTypeSlug,
            MatchTier tier, int catalogueOrder) {

        SearchSuggestionResponse toResponse() {
            return new SearchSuggestionResponse(type, label, categoryKey, serviceTypeSlug);
        }
    }
}
