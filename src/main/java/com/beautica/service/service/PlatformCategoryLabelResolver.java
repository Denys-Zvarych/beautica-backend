package com.beautica.service.service;

import com.beautica.service.entity.PlatformCategory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Public seam exposing the selectable {@link PlatformCategory} rows as
 * {@code (name, displayName)} pairs to the {@code search} feature — the mirror of
 * {@link ServiceTypeSlugResolver} for the <em>category</em> half of free-text
 * search.
 *
 * <h3>Why it exists</h3>
 * «Нарощення вій» is a {@code platform_categories.display_name}. It appears in no
 * {@code service_definitions.name} and no {@code service_types.name_uk}, so the
 * free-text predicate — which searched provider names and service names only —
 * returned zero results for the single most natural thing a client can type: the
 * name of the category they are shopping for. Resolving the query's tokens against
 * this list up front turns that into a {@code service_definitions.category IN (…)}
 * disjunct the existing indexes already serve.
 *
 * <h3>Cache reuse — no new cache</h3>
 * Backed by the already-cached {@link PlatformCategoryOrderLookup#getApprovedActive()}
 * ({@code platform-category-order}, 60-min TTL, evicted by
 * {@link CategoryRequestService#approve}/{@link CategoryRequestService#reject} — the
 * only writes that change APPROVED/active membership). This resolver adds no cache
 * of its own, so a whole request's category resolution costs one cached list read
 * and no round-trip. {@link PlatformCategoryOrderLookup} is package-private, so this
 * component lives in the same package to reach it and re-exports a narrow, public
 * API to {@code search} (cross-feature access goes through a public service type,
 * never the repository).
 *
 * <p>The lookup is invoked through this distinct bean (not a self-call), so the
 * {@code @Cacheable} proxy on {@link PlatformCategoryOrderLookup#getApprovedActive}
 * stays active (Spring AOP self-invocation rule, §F.3).
 *
 * <h3>Selectability, not merely {@code active}</h3>
 * The backing query filters {@code status = APPROVED AND active = true} — the same
 * gate {@code ServiceCatalogService} enforces on the write side. A PENDING
 * self-service request is invisible to search, so a user cannot discover (or
 * confirm the existence of) a category an admin has not approved yet by typing its
 * name into the public search box.
 */
@Component
public class PlatformCategoryLabelResolver {

    private final PlatformCategoryOrderLookup platformCategoryOrderLookup;

    public PlatformCategoryLabelResolver(PlatformCategoryOrderLookup platformCategoryOrderLookup) {
        this.platformCategoryOrderLookup = platformCategoryOrderLookup;
    }

    /**
     * The selectable categories, ordered by {@code displayName} (the backing
     * query's {@code ORDER BY}). The order is part of the contract, not incidental:
     * the caller turns the matched subset into a positional {@code :qcat{n}} bind
     * list, and a stable order keeps the generated SQL — and therefore the plan
     * cache — stable across requests for the same query text.
     *
     * <p>Bounded by the table itself: {@code platform_categories} is admin-gated
     * reference data on the order of a couple of dozen rows (it is downloaded whole
     * by the mobile category picker), which is what makes an un-paginated read
     * legitimate here.
     *
     * @return selectable {@code (name, displayName)} pairs; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<PlatformCategoryLabel> selectableLabels() {
        return platformCategoryOrderLookup.getApprovedActive().stream()
                .map(category -> new PlatformCategoryLabel(category.getName(), category.getDisplayName()))
                .toList();
    }
}
