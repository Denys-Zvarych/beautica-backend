package com.beautica.search.service;

import com.beautica.service.service.PlatformCategoryLabel;
import com.beautica.service.service.PlatformCategoryLabelResolver;
import com.beautica.service.service.ServiceTypeCatalogueEntry;
import com.beautica.service.service.ServiceTypeSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchSuggestionCatalogue — B504 D21: SERVICE suggestions only for types of a selectable category")
class SearchSuggestionCatalogueTest {

    @Mock private PlatformCategoryLabelResolver labelResolver;
    @Mock private ServiceTypeSlugResolver slugResolver;

    private SearchSuggestionCatalogue catalogue;

    private static final ServiceTypeCatalogueEntry SELECTABLE_TYPE =
            new ServiceTypeCatalogueEntry(UUID.randomUUID(), "manikiur-klasychnyi", "Класичний манікюр", "MANICURE");
    private static final ServiceTypeCatalogueEntry HIDDEN_CATEGORY_TYPE =
            new ServiceTypeCatalogueEntry(UUID.randomUUID(), "masazh-nih", "Масаж ніг", "MASAZH_NIH");

    @BeforeEach
    void setUp() {
        catalogue = new SearchSuggestionCatalogue(labelResolver, slugResolver);
    }

    @Test
    @DisplayName("an active type under a HIDDEN category is left out; one under a selectable category stays")
    void should_dropTypeOfHiddenCategory_when_buildingSnapshot() {
        when(labelResolver.selectableLabels()).thenReturn(List.of(new PlatformCategoryLabel("MANICURE", "Манікюр")));
        when(slugResolver.allActiveTypes()).thenReturn(List.of(SELECTABLE_TYPE, HIDDEN_CATEGORY_TYPE));

        SuggestionCatalogueSnapshot snapshot = catalogue.snapshot();

        assertThat(snapshot.services()).extracting(CatalogueServiceEntry::slug).containsExactly("manikiur-klasychnyi");
        assertThat(snapshot.categories()).extracting(CatalogueCategoryEntry::key).containsExactly("MANICURE");
    }

    @Test
    @DisplayName("once the category is selectable again (unhide), its active type is back in the snapshot")
    void should_keepType_when_itsCategoryIsSelectable() {
        when(labelResolver.selectableLabels()).thenReturn(List.of(
                new PlatformCategoryLabel("MANICURE", "Манікюр"), new PlatformCategoryLabel("MASAZH_NIH", "Масаж ніг")));
        when(slugResolver.allActiveTypes()).thenReturn(List.of(SELECTABLE_TYPE, HIDDEN_CATEGORY_TYPE));

        SuggestionCatalogueSnapshot snapshot = catalogue.snapshot();

        assertThat(snapshot.services()).extracting(CatalogueServiceEntry::slug)
                .containsExactlyInAnyOrder("manikiur-klasychnyi", "masazh-nih");
    }

    @Test
    @DisplayName("the selectable categories are read once and feed both the CATEGORY and the SERVICE half")
    void should_readSelectableLabelsOnce_when_buildingSnapshot() {
        when(labelResolver.selectableLabels()).thenReturn(List.of(new PlatformCategoryLabel("MANICURE", "Манікюр")));
        when(slugResolver.allActiveTypes()).thenReturn(List.of(SELECTABLE_TYPE));

        catalogue.snapshot();

        verify(labelResolver, times(1)).selectableLabels();
    }
}
