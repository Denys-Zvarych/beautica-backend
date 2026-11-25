package com.beautica.service.service;

import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cached lookup for the approved+active {@link PlatformCategory} rows, in
 * {@code ORDER BY displayName} — the same order {@link CategoryRequestService#listApproved}
 * exposes to the mobile picker. Backs {@link ServiceCatalogService#buildCategoryOrderAndNames}, which
 * derives a {@code category name -> ordinal} map from this list to sort a salon's public
 * catalog groups.
 *
 * <p>This data is 100% static, admin-approval-gated reference data, identical across every
 * salon/request — {@link PlatformCategory} rows change only via {@link CategoryRequestService
 * #approve}/{@link CategoryRequestService#reject}. A dedicated bean (rather than a private
 * method on {@link ServiceCatalogService}) is required for the {@code @Cacheable} proxy to
 * actually intercept the call — a same-class self-invocation bypasses Spring AOP entirely
 * (see {@link ServiceCatalogService#searchServiceTypes} for the established precedent of this
 * exact caveat).
 *
 * <p>Deliberately a NEW bean rather than reusing {@link CatalogCategoryLookup}: that class
 * caches the separate, orphaned "System A" {@code service_categories} table, not
 * {@code platform_categories} — see the warning in {@link ServiceCatalogService
 * #buildCategoryOrderAndNames}'s Javadoc. Returns entities (not a DTO): this bean is an internal
 * lookup consumed only by {@link ServiceCatalogService}, never exposed across a controller
 * boundary.
 */
@Component
class PlatformCategoryOrderLookup {

    /** Named cache — {@link CategoryRequestService#approve}/{@link CategoryRequestService#reject}
     *  evict this same cache/key pair, since those are the only writes that change
     *  APPROVED/active membership. */
    static final String CACHE_NAME = "platform-category-order";
    /** Constant key — the approved+active list is platform-wide, not per-caller. */
    static final String CACHE_KEY = "'all'";
    /** B504 (track D18) — cache of {@link #getApprovedIncludingHidden()}; same constant key. */
    static final String LABELS_CACHE_NAME = "platform-category-labels";

    private final PlatformCategoryRepository platformCategoryRepository;

    PlatformCategoryOrderLookup(PlatformCategoryRepository platformCategoryRepository) {
        this.platformCategoryRepository = platformCategoryRepository;
    }

    // sync = true: since PlatformCategoryLabelResolver put this list on the public
    // (permitAll) free-text search path, a TTL expiry on this ONE constant key would
    // otherwise let every concurrent search request issue its own findApprovedActive()
    // — a thundering herd on the single hottest key in the application.
    @Cacheable(value = CACHE_NAME, key = CACHE_KEY, sync = true)
    @Transactional(readOnly = true)
    List<PlatformCategory> getApprovedActive() {
        return platformCategoryRepository.findApprovedActive();
    }

    /**
     * B504 (track D18) — APPROVED categories, hidden ones included, by display name: labels and group order for
     * services that already use a hidden category. One hot platform-wide key, hence {@code sync = true} (D17).
     * Emptied by {@code CatalogCacheEvictor} (portal writes) and by the approve / ops-create evict sets.
     */
    @Cacheable(value = LABELS_CACHE_NAME, key = CACHE_KEY, sync = true)
    @Transactional(readOnly = true)
    List<PlatformCategory> getApprovedIncludingHidden() {
        return platformCategoryRepository.findApprovedIncludingHidden();
    }
}
