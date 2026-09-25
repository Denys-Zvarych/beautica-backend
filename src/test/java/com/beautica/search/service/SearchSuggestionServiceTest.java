package com.beautica.search.service;

import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.SearchSuggestionResponse;
import com.beautica.search.dto.SuggestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 331 — {@link SearchSuggestionService}'s pure fold/match/rank/cap logic (D4), fully mocked
 * collaborators, no Spring context. {@code SearchSuggestionIT} proves the SQL/cache/HTTP path;
 * this class proves the RANKING contract in isolation, which an end-to-end test cannot pin
 * precisely because catalogue/availability content there is seeded data, not a controlled fixture.
 */
@DisplayName("SearchSuggestionService — D4 fold/match/rank/cap")
class SearchSuggestionServiceTest {

    private SearchSuggestionCatalogue catalogue;
    private SearchSuggestionAvailability availability;
    private SearchSuggestionActivePlaces activePlaces;
    private SearchSuggestionService service;

    @BeforeEach
    void setUp() {
        catalogue = mock(SearchSuggestionCatalogue.class);
        availability = mock(SearchSuggestionAvailability.class);
        activePlaces = mock(SearchSuggestionActivePlaces.class);
        service = new SearchSuggestionService(catalogue, availability, activePlaces);
    }

    private static CatalogueServiceEntry service(String slug, String nameUk, String categoryKey) {
        return new CatalogueServiceEntry(UUID.randomUUID(), slug, nameUk, categoryKey);
    }

    /** Every category/service key is available at the fixture "place", nothing filtered. */
    private void allAvailable(List<CatalogueCategoryEntry> categories, List<CatalogueServiceEntry> services) {
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(categories, services));
        Set<String> categoryKeys = categories.stream().map(CatalogueCategoryEntry::key)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> serviceTypeIds = services.stream().map(CatalogueServiceEntry::id)
                .collect(java.util.stream.Collectors.toSet());
        when(availability.forPlace(any())).thenReturn(new PlaceAvailability(categoryKeys, serviceTypeIds));
    }

    private List<SearchSuggestionResponse> suggest(String q, int limit) {
        return service.suggest(q, limit, null);
    }

    // ── the doc's worked example (D4 rank + CATEGORY-before-SERVICE tie) ─────────────────────

    @Test
    @DisplayName("«нар» — CATEGORY «Нарощення вій» first, then SERVICE «Нарощення нігтів», then the weaker word-start match")
    void should_rankCategoryFirst_when_categoryAndServiceTieOnLabelStart() {
        var lash = new CatalogueCategoryEntry("LASH_EXTENSIONS", "Нарощення вій");
        var nails = new CatalogueCategoryEntry("NAILS", "Манікюр");
        var nailExtension = service("nail-extension", "Нарощення нігтів", "NAILS");
        var classicLash = service("lash-classic", "Класичне нарощення", "LASH_EXTENSIONS");
        allAvailable(List.of(lash, nails), List.of(nailExtension, classicLash));

        List<SearchSuggestionResponse> result = suggest("нар", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label)
                .containsExactly("Нарощення вій", "Нарощення нігтів", "Класичне нарощення");
        assertThat(result.get(0).type()).isEqualTo(SuggestionType.CATEGORY);
        assertThat(result.get(0).serviceTypeSlug()).isNull();
        assertThat(result.get(1).type()).isEqualTo(SuggestionType.SERVICE);
        assertThat(result.get(1).serviceTypeSlug()).isEqualTo("nail-extension");
        assertThat(result.get(1).categoryKey()).isEqualTo("NAILS");
    }

    @Test
    @DisplayName("«НАР» — uppercase folds to the same result as «нар»")
    void should_matchCaseInsensitively() {
        var lash = new CatalogueCategoryEntry("LASH_EXTENSIONS", "Нарощення вій");
        allAvailable(List.of(lash), List.of());

        List<SearchSuggestionResponse> result = suggest("НАР", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label).containsExactly("Нарощення вій");
    }

    // ── apostrophe folding ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ʼ / ' / ’ apostrophe variants all fold onto the same match")
    void should_foldEveryApostropheVariant_ontoTheSameMatch() {
        var category = new CatalogueCategoryEntry("MISC", "П'ятихвилинний манікюр");
        allAvailable(List.of(category), List.of());

        List<SearchSuggestionResponse> straight = suggest("п'ят", 8);
        List<SearchSuggestionResponse> curly = suggest("п’ят", 8);
        List<SearchSuggestionResponse> modifier = suggest("пʼят", 8);

        assertThat(straight).extracting(SearchSuggestionResponse::label).containsExactly("П'ятихвилинний манікюр");
        assertThat(curly).isEqualTo(straight);
        assertThat(modifier).isEqualTo(straight);
    }

    // ── apostrophe-only query (edge case: a query with NOTHING but a fold-target character) ────

    @Test
    @DisplayName("a query consisting of a single apostrophe variant does not throw and matches nothing "
            + "(no real label starts a word with an apostrophe)")
    void should_returnEmptyWithoutThrowing_when_queryIsOnlyAnApostrophe() {
        var category = new CatalogueCategoryEntry("MISC", "П'ятихвилинний манікюр");
        allAvailable(List.of(category), List.of());

        List<SearchSuggestionResponse> straight = suggest("'", 8);
        List<SearchSuggestionResponse> curly = suggest("’", 8);
        List<SearchSuggestionResponse> modifier = suggest("ʼ", 8);

        assertThat(straight).isEmpty();
        assertThat(curly).isEmpty();
        assertThat(modifier).isEmpty();
    }

    // ── length-based admission: 1–2 chars word-start, 3+ substring ───────────────────────────

    @Test
    @DisplayName("1-char term — only a WORD START matches; a mid-word occurrence does not")
    void should_matchOnlyWordStart_when_termIsOneCharacter() {
        var startsWithS = new CatalogueCategoryEntry("HAIR", "Стрижка");
        var midWordOnly = new CatalogueCategoryEntry("LASH", "Класичне нарощення"); // 'с' only mid-word
        allAvailable(List.of(startsWithS, midWordOnly), List.of());

        List<SearchSuggestionResponse> result = suggest("с", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label)
                .as("«класичне нарощення» has no word starting with 'с' and must be excluded, "
                        + "not merely ranked lower")
                .containsExactly("Стрижка");
    }

    @Test
    @DisplayName("2-char term — a non-first word starting with the term matches (WORD_START tier)")
    void should_matchWordStartOnSecondWord_when_termIsTwoCharacters() {
        var spaManicure = new CatalogueCategoryEntry("SPA_NAILS", "Спа манікюр");
        allAvailable(List.of(spaManicure), List.of());

        List<SearchSuggestionResponse> result = suggest("ма", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label).containsExactly("Спа манікюр");
    }

    @Test
    @DisplayName("3+ char term — matches mid-word too (SUBSTRING), not only at a word start")
    void should_matchMidWordSubstring_when_termIsThreeOrMoreCharacters() {
        var manicure = new CatalogueCategoryEntry("NAILS", "Манікюр");
        allAvailable(List.of(manicure), List.of());

        List<SearchSuggestionResponse> result = suggest("ікюр", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label).containsExactly("Манікюр");
    }

    @Test
    @DisplayName("2-char term — a mid-word (non-word-start) occurrence does NOT match")
    void should_notMatchMidWord_when_termIsTwoCharactersAndOnlyMidWord() {
        // "ікюр" -> the 2-char prefix "ік" appears only inside "манікюр", never at a word start.
        var manicure = new CatalogueCategoryEntry("NAILS", "Манікюр");
        allAvailable(List.of(manicure), List.of());

        List<SearchSuggestionResponse> result = suggest("ік", 8);

        assertThat(result).isEmpty();
    }

    // ── rank order across all three tiers in one query ────────────────────────────────────────

    @Test
    @DisplayName("rank order: label-start, then word-start, then substring")
    void should_orderByTier_labelStartThenWordStartThenSubstring() {
        var labelStart = new CatalogueCategoryEntry("A", "Манікюр класичний");
        var wordStart = new CatalogueCategoryEntry("B", "Спа манікюр");
        var substring = new CatalogueCategoryEntry("C", "Даманікюрений"); // contrived: 'ман' mid-word only
        allAvailable(List.of(labelStart, wordStart, substring), List.of());

        List<SearchSuggestionResponse> result = suggest("ман", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label)
                .containsExactly("Манікюр класичний", "Спа манікюр", "Даманікюрений");
    }

    // ── duplicate-label SERVICE dropped (D4) ──────────────────────────────────────────────────

    @Test
    @DisplayName("a SERVICE whose label folds equal to its own category's label is dropped")
    void should_dropService_when_itsLabelFoldsEqualToItsCategoryLabel() {
        var nails = new CatalogueCategoryEntry("NAILS", "Манікюр");
        var duplicateService = service("nails-dup", "манікюр", "NAILS"); // same, case-folded
        var distinctService = service("nail-art", "Дизайн нігтів", "NAILS");
        allAvailable(List.of(nails), List.of(duplicateService, distinctService));

        List<SearchSuggestionResponse> result = suggest("ман", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label)
                .as("the duplicate SERVICE row must never appear, even though it matches the term")
                .containsExactly("Манікюр");
        assertThat(result).extracting(SearchSuggestionResponse::serviceTypeSlug)
                .doesNotContain("nails-dup");
    }

    // ── cap ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cap: 10 matches, limit=3 -> exactly 3, in catalogue order")
    void should_capAtLimit_when_moreThanLimitCandidatesMatch() {
        List<CatalogueCategoryEntry> categories = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            categories.add(new CatalogueCategoryEntry("M" + i, "Манікюр варіант " + i));
        }
        allAvailable(categories, List.of());

        List<SearchSuggestionResponse> result = suggest("ман", 3);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(SearchSuggestionResponse::label)
                .containsExactly("Манікюр варіант 0", "Манікюр варіант 1", "Манікюр варіант 2");
    }

    @Test
    @DisplayName("cap: 10 matches, default-style limit=8 -> exactly 8")
    void should_returnUpToEight_when_limitIsEightAndMoreThanEightMatch() {
        List<CatalogueCategoryEntry> categories = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            categories.add(new CatalogueCategoryEntry("M" + i, "Манікюр варіант " + i));
        }
        allAvailable(categories, List.of());

        List<SearchSuggestionResponse> result = suggest("ман", 8);

        assertThat(result).hasSize(8);
    }

    // ── multi-token AND ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("multi-token query ANDs every token — a label matching only one token is excluded")
    void should_requireEveryToken_when_queryHasMultipleWords() {
        var matchesBoth = new CatalogueCategoryEntry("SPA_NAILS", "Спа манікюр");
        var matchesOnlyFirst = new CatalogueCategoryEntry("SPA_DAY", "Спа день");
        allAvailable(List.of(matchesBoth, matchesOnlyFirst), List.of());

        List<SearchSuggestionResponse> result = suggest("сп ман", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label).containsExactly("Спа манікюр");
    }

    // ── availability filter ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("availability filter: a matching category absent from PlaceAvailability is dropped")
    void should_dropMatchingCandidate_when_notInPlaceAvailability() {
        var available = new CatalogueCategoryEntry("NAILS", "Манікюр класичний");
        var unavailable = new CatalogueCategoryEntry("NAILS2", "Манікюр спа");
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(
                List.of(available, unavailable), List.of()));
        when(availability.forPlace(any())).thenReturn(new PlaceAvailability(Set.of("NAILS"), Set.of()));

        List<SearchSuggestionResponse> result = suggest("ман", 8);

        assertThat(result).extracting(SearchSuggestionResponse::label).containsExactly("Манікюр класичний");
    }

    @Test
    @DisplayName("cap is applied AFTER the availability filter: 10 matches, 7 unavailable -> the 3 "
            + "available ones are returned, not a 1-item leftover from a pre-filter top-3")
    void should_applyCapAfterAvailabilityFilter_notBefore() {
        List<CatalogueCategoryEntry> categories = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            categories.add(new CatalogueCategoryEntry("M" + i, "Манікюр варіант " + i));
        }
        // Only indices 0, 5, 9 are available. A WRONG cap-before-filter implementation would take
        // the top 3 by catalogue order (indices 0,1,2) BEFORE filtering, then filter that set of 3
        // down to just index 0 -> a single result. The correct filter-then-cap order filters down
        // to {0,5,9} first, and the cap (3) keeps all three.
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(categories, List.of()));
        when(availability.forPlace(any())).thenReturn(new PlaceAvailability(Set.of("M0", "M5", "M9"), Set.of()));

        List<SearchSuggestionResponse> result = suggest("ман", 3);

        assertThat(result).extracting(SearchSuggestionResponse::label)
                .as("filter-before-cap must keep all 3 available matches, not collapse to 1")
                .containsExactlyInAnyOrder("Манікюр варіант 0", "Манікюр варіант 5", "Манікюр варіант 9");
    }

    // ── blank / no-match ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no match anywhere -> an empty list, never null")
    void should_returnEmptyList_when_nothingMatches() {
        var category = new CatalogueCategoryEntry("NAILS", "Манікюр");
        allAvailable(List.of(category), List.of());

        List<SearchSuggestionResponse> result = suggest("зовсімінше", 8);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a location.cityId/districtId is forwarded to the availability lookup key")
    void should_forwardLocationToAvailability() {
        var category = new CatalogueCategoryEntry("NAILS", "Манікюр");
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(List.of(category), List.of()));
        when(availability.forPlace(any())).thenReturn(new PlaceAvailability(Set.of("NAILS"), Set.of()));
        UUID cityId = UUID.randomUUID();
        when(activePlaces.snapshot()).thenReturn(new ActivePlaces(Set.of(cityId), Set.of()));

        service.suggest("ман", 8, new LocationFilter(cityId, null));

        org.mockito.Mockito.verify(availability).forPlace(new SuggestionPlaceKey(cityId, null));
    }

    // ── active-places short-circuit (audit-fix cycle 1, finding 2) ───────────────────────────────

    @Test
    @DisplayName("national (no place chosen) bypasses the active-places check entirely")
    void should_bypassActivePlacesCheck_when_noPlaceChosen() {
        var category = new CatalogueCategoryEntry("NAILS", "Манікюр");
        allAvailable(List.of(category), List.of());

        suggest("ман", 8);

        verifyNoInteractions(activePlaces);
    }

    @Test
    @DisplayName("a cityId absent from the active-places set short-circuits to [] with ZERO availability queries")
    void should_shortCircuitToEmpty_andSkipAvailabilityQuery_when_cityNotInActiveSet() {
        var category = new CatalogueCategoryEntry("NAILS", "Манікюр");
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(List.of(category), List.of()));
        UUID cityId = UUID.randomUUID();
        when(activePlaces.snapshot()).thenReturn(new ActivePlaces(Set.of(), Set.of()));

        List<SearchSuggestionResponse> result = service.suggest("ман", 8, new LocationFilter(cityId, null));

        assertThat(result).isEmpty();
        verify(availability, never()).forPlace(any());
    }

    @Test
    @DisplayName("a cityId present in the active-places set resolves normally through availability.forPlace")
    void should_resolveNormally_when_cityIsInActiveSet() {
        var category = new CatalogueCategoryEntry("NAILS", "Манікюр");
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(List.of(category), List.of()));
        UUID cityId = UUID.randomUUID();
        when(activePlaces.snapshot()).thenReturn(new ActivePlaces(Set.of(cityId), Set.of()));
        when(availability.forPlace(any())).thenReturn(new PlaceAvailability(Set.of("NAILS"), Set.of()));

        List<SearchSuggestionResponse> result = service.suggest("ман", 8, new LocationFilter(cityId, null));

        assertThat(result).extracting(SearchSuggestionResponse::label).containsExactly("Манікюр");
        verify(availability).forPlace(new SuggestionPlaceKey(cityId, null));
    }

    @Test
    @DisplayName("district-primary: a districtId in the active-places districtIds resolves even when its cityId is not itself active")
    void should_useDistrictActiveSet_when_districtIsChosen() {
        var category = new CatalogueCategoryEntry("NAILS", "Манікюр");
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(List.of(category), List.of()));
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(activePlaces.snapshot()).thenReturn(new ActivePlaces(Set.of(), Set.of(districtId)));
        when(availability.forPlace(any())).thenReturn(new PlaceAvailability(Set.of("NAILS"), Set.of()));

        List<SearchSuggestionResponse> result = service.suggest("ман", 8, new LocationFilter(cityId, districtId));

        assertThat(result).extracting(SearchSuggestionResponse::label).containsExactly("Манікюр");
        verify(availability).forPlace(new SuggestionPlaceKey(null, districtId));
    }

    @Test
    @DisplayName("a districtId absent from the active-places districtIds short-circuits to [], even when its cityId IS active")
    void should_shortCircuit_when_districtNotInActiveSet_regardlessOfCityActivity() {
        var category = new CatalogueCategoryEntry("NAILS", "Манікюр");
        when(catalogue.snapshot()).thenReturn(new SuggestionCatalogueSnapshot(List.of(category), List.of()));
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(activePlaces.snapshot()).thenReturn(new ActivePlaces(Set.of(cityId), Set.of()));

        List<SearchSuggestionResponse> result = service.suggest("ман", 8, new LocationFilter(cityId, districtId));

        assertThat(result).isEmpty();
        verify(availability, never()).forPlace(any());
    }
}
