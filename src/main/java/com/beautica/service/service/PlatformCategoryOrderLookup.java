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

    private final PlatformCategoryRepository platformCategoryRepository;

    PlatformCategoryOrderLookup(PlatformCategoryRepository platformCategoryRepository) {
        this.platformCategoryRepository = platformCategoryRepository;
    }

    @Cacheable(value = CACHE_NAME, key = CACHE_KEY)
    @Transactional(readOnly = true)
    List<PlatformCategory> getApprovedActive() {
        return platformCategoryRepository.findApprovedActive();
    }
}
