package com.beautica.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SuggestionCatalogueSnapshot#categoriesByKey()} — audit-fix cycle 2, finding 1 (LOW perf).
 * Proves the index is built ONCE at construction (same {@link Map} instance across repeated
 * accessor calls) and mirrors {@code categories()}, so {@link SearchSuggestionService#suggest} can
 * read it instead of rebuilding a fresh {@code HashMap} on every request.
 */
@DisplayName("SuggestionCatalogueSnapshot — categoriesByKey pre-built index")
class SuggestionCatalogueSnapshotTest {

    @Test
    @DisplayName("categoriesByKey() returns the SAME Map instance across repeated calls — built once, not per access")
    void should_returnSameMapInstance_when_calledRepeatedly() {
        var nails = new CatalogueCategoryEntry("NAILS", "Манікюр");
        var lash = new CatalogueCategoryEntry("LASH_EXTENSIONS", "Нарощення вій");
        var snapshot = new SuggestionCatalogueSnapshot(List.of(nails, lash), List.of());

        Map<String, CatalogueCategoryEntry> first = snapshot.categoriesByKey();
        Map<String, CatalogueCategoryEntry> second = snapshot.categoriesByKey();

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("categoriesByKey() indexes every category from categories() by its key, with matching entries")
    void should_indexEveryCategoryByKey_matchingCategoriesList() {
        var nails = new CatalogueCategoryEntry("NAILS", "Манікюр");
        var lash = new CatalogueCategoryEntry("LASH_EXTENSIONS", "Нарощення вій");
        var snapshot = new SuggestionCatalogueSnapshot(List.of(nails, lash), List.of());

        Map<String, CatalogueCategoryEntry> byKey = snapshot.categoriesByKey();

        assertThat(byKey).hasSize(2);
        assertThat(byKey.get("NAILS")).isSameAs(nails);
        assertThat(byKey.get("LASH_EXTENSIONS")).isSameAs(lash);
    }

    @Test
    @DisplayName("an empty categories list produces an empty (not null) categoriesByKey()")
    void should_returnEmptyMap_when_categoriesListIsEmpty() {
        var snapshot = new SuggestionCatalogueSnapshot(List.of(), List.of());

        assertThat(snapshot.categoriesByKey()).isEmpty();
    }

    @Test
    @DisplayName("categoriesByKey() is unmodifiable")
    void should_beUnmodifiable() {
        var nails = new CatalogueCategoryEntry("NAILS", "Манікюр");
        var snapshot = new SuggestionCatalogueSnapshot(List.of(nails), List.of());

        assertThatThrownBy(() -> snapshot.categoriesByKey().put("X", nails))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the 3-arg canonical constructor accepts an explicit categoriesByKey as-is")
    void should_useSuppliedMap_when_canonicalConstructorIsUsedDirectly() {
        var nails = new CatalogueCategoryEntry("NAILS", "Манікюр");
        Map<String, CatalogueCategoryEntry> explicit = Map.of("NAILS", nails);

        var snapshot = new SuggestionCatalogueSnapshot(List.of(nails), List.of(), explicit);

        assertThat(snapshot.categoriesByKey()).isSameAs(explicit);
    }

    @Test
    @DisplayName("services() is untouched by the categoriesByKey index — still whatever was passed in")
    void should_leaveServicesListUntouched() {
        var nails = new CatalogueCategoryEntry("NAILS", "Манікюр");
        var service = new CatalogueServiceEntry(UUID.randomUUID(), "nail-art", "Дизайн нігтів", "NAILS");
        var snapshot = new SuggestionCatalogueSnapshot(List.of(nails), List.of(service));

        assertThat(snapshot.services()).containsExactly(service);
    }
}
