package com.beautica.service.service;

import com.beautica.service.entity.ServiceType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public seam for resolving platform service-type <em>slugs</em> to their
 * {@code (id, nameUk)} pair, for the per-service search filter (Phase 20.x).
 *
 * <p><b>Cache reuse — no new cache.</b> Resolution is backed by the already
 * cached active-type list ({@link ServiceTypeLookup#getByCategory(java.util.UUID)}
 * with a {@code null} category → cache {@code service-types}, key {@code 'ALL'}).
 * A single cached read serves a whole request's slug batch; this resolver adds
 * no cache of its own. {@link ServiceTypeLookup} is package-private, so this
 * component lives in the same package to reach it and re-exports a narrow,
 * public API to the {@code search} feature (cross-feature access goes through a
 * public service type, never the repository).</p>
 *
 * <p>The lookup is invoked through this distinct bean (not a self-call), so the
 * {@code @Cacheable} proxy on {@link ServiceTypeLookup#getByCategory} stays
 * active (Spring AOP self-invocation rule, §F.3).</p>
 */
@Component
public class ServiceTypeSlugResolver {

    private final ServiceTypeLookup serviceTypeLookup;

    public ServiceTypeSlugResolver(ServiceTypeLookup serviceTypeLookup) {
        this.serviceTypeLookup = serviceTypeLookup;
    }

    /**
     * Resolves each slug to its {@link ServiceTypeMatch}, preserving input order.
     * An unknown / inactive slug yields an empty {@link Optional} at its position;
     * under the OR/union per-service filter semantics (Phase 20.x) the caller
     * drops those positions and ORs the survivors, rather than erroring or
     * collapsing the whole query. Position is preserved (rather than pre-dropping)
     * so callers retain the freedom to distinguish per-slug outcomes.
     *
     * @param slugs normalized, deduped slug list (may be empty; never {@code null})
     * @return one {@link Optional} per input slug, in order
     */
    @Transactional(readOnly = true)
    public List<Optional<ServiceTypeMatch>> resolve(List<String> slugs) {
        if (slugs.isEmpty()) {
            return List.of();
        }
        Map<String, ServiceTypeMatch> bySlug = indexActiveTypesBySlug();
        return slugs.stream()
                .map(slug -> Optional.ofNullable(bySlug.get(slug)))
                .toList();
    }

    /**
     * Builds a {@code slug → match} index from the cached active-type list. The
     * {@code service_types.slug} column is globally unique, so a last-write-wins
     * merge function is defensive only.
     */
    private Map<String, ServiceTypeMatch> indexActiveTypesBySlug() {
        List<ServiceType> activeTypes = serviceTypeLookup.getByCategory(null);
        Map<String, ServiceTypeMatch> bySlug = new LinkedHashMap<>(activeTypes.size());
        for (ServiceType type : activeTypes) {
            bySlug.put(type.getSlug(), new ServiceTypeMatch(type.getId(), type.getNameUk()));
        }
        return bySlug;
    }

    /**
     * Every active platform service type, reduced to {@link ServiceTypeCatalogueEntry} — the
     * search-suggestions catalogue snapshot's SERVICE half (Phase 331).
     *
     * <p>Backed by the SAME cached read as {@link #resolve(List)}
     * ({@link ServiceTypeLookup#getByCategory(java.util.UUID)} with a {@code null} category),
     * so building the suggestions catalogue costs no new query and no new cache — only a second
     * projection of the one list already held in memory.
     *
     * @return every active type, in the cached list's order (no ordering contract beyond that —
     *         the caller re-sorts as its own ranking requires)
     */
    @Transactional(readOnly = true)
    public List<ServiceTypeCatalogueEntry> allActiveTypes() {
        return serviceTypeLookup.getByCategory(null).stream()
                .map(type -> new ServiceTypeCatalogueEntry(
                        type.getId(), type.getSlug(), type.getNameUk(), type.getPlatformCategoryName()))
                .toList();
    }
}
