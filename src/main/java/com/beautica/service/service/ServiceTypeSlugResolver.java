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
     * An unknown / inactive slug yields an empty {@link Optional} at its position
     * so the caller can short-circuit an AND-of-all-slugs filter to an empty
     * result set rather than erroring (Phase 20.x locked decision 3).
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
}
