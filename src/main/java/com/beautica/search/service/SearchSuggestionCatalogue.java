package com.beautica.search.service;

import com.beautica.service.service.PlatformCategoryLabelResolver;
import com.beautica.service.service.ServiceTypeCatalogueEntry;
import com.beautica.service.service.ServiceTypeSlugResolver;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue half of {@code GET /api/v1/search/suggestions} (Phase 331) — every selectable
 * category and every active service type, with NO availability applied. Availability is joined in
 * memory by {@link SearchSuggestionService} from {@link SearchSuggestionAvailability}'s
 * per-place set.
 *
 * <p><b>One cache entry, 10-minute TTL</b> (declared in {@code CacheConfig}). {@code sync = true}:
 * this is the single hottest key behind an unauthenticated, per-keystroke endpoint (§F-7), so a
 * TTL expiry must collapse to one reload, not a thundering herd.
 *
 * <p><b>No new query, no new source cache.</b> Both halves are re-projections of ALREADY-cached
 * reads used elsewhere on the search path: {@link PlatformCategoryLabelResolver#selectableLabels()}
 * (backs {@code QueryCategoryMatcher}) and {@link ServiceTypeSlugResolver#allActiveTypes()} (a new
 * projection of the same {@code service-types}/{@code 'ALL'} list {@link ServiceTypeSlugResolver
 * #resolve} already reads). This cache exists only to fold the two into one immutable,
 * suggestions-shaped snapshot with its own (shorter) TTL — not to avoid a query the platform
 * wasn't already caching.
 *
 * <p>Separate {@code @Cacheable} bean from {@link SearchSuggestionAvailability}, deliberately: two
 * caches behind two beans means neither self-invokes the other's proxy (§F-3), and the service
 * joins {@code snapshot() ∩ forPlace(key)} in memory.
 */
@Component
public class SearchSuggestionCatalogue {

    /**
     * Registered, bounded and metered in {@code CacheConfig} (another package, hence public): 1
     * entry, 10-minute TTL.
     */
    public static final String CACHE_NAME = "searchSuggestionCatalogue";

    private final PlatformCategoryLabelResolver platformCategoryLabelResolver;
    private final ServiceTypeSlugResolver serviceTypeSlugResolver;

    public SearchSuggestionCatalogue(
            PlatformCategoryLabelResolver platformCategoryLabelResolver,
            ServiceTypeSlugResolver serviceTypeSlugResolver) {
        this.platformCategoryLabelResolver = platformCategoryLabelResolver;
        this.serviceTypeSlugResolver = serviceTypeSlugResolver;
    }

    @Cacheable(value = CACHE_NAME, sync = true)
    @Transactional(readOnly = true)
    public SuggestionCatalogueSnapshot snapshot() {
        return new SuggestionCatalogueSnapshot(
                platformCategoryLabelResolver.selectableLabels().stream()
                        .map(label -> new CatalogueCategoryEntry(label.name(), label.displayName()))
                        .toList(),
                serviceTypeSlugResolver.allActiveTypes().stream()
                        .map(SearchSuggestionCatalogue::toCatalogueServiceEntry)
                        .toList());
    }

    private static CatalogueServiceEntry toCatalogueServiceEntry(ServiceTypeCatalogueEntry entry) {
        return new CatalogueServiceEntry(entry.id(), entry.slug(), entry.nameUk(), entry.categoryKey());
    }
}
