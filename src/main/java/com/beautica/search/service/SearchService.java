package com.beautica.search.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.location.DiscoveryLocationKey;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonSearchProjection;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.dto.SearchSort;
import com.beautica.service.service.ServiceTypeMatch;
import com.beautica.service.service.ServiceTypeSlugResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only search service for master and salon discovery.
 *
 * <p>Master search uses native SQL because the projection joins several tables
 * ({@code masters}, {@code users}, optionally {@code master_services} /
 * {@code service_definitions}) and requires a {@code COUNT(*) OVER()} window
 * function to eliminate the second count round-trip (PERF-M1). Salon search is
 * plain JPQL dispatched by {@link #searchSalons} to one of three
 * single-equality, SARGable {@link SalonRepository} methods (Phase 10.8,
 * MEDIUM-1): {@code findActiveByDistrictId} / {@code findActiveByCityId} /
 * {@code findByIsActiveTrue}.
 *
 * <h3>Phase 10.5 — FK-based, district-primary location filter</h3>
 * The earlier free-text {@code AND u.city = :city AND u.region = :region}
 * filter was a real bug — exact string equality over un-normalised free text
 * ("Київ" ≠ "Киев" ≠ "kyiv"). Location filtering is now an FK match on the
 * Phase 10.3 {@code city_id} / {@code district_id} columns, district-primary
 * (a supplied district wins; otherwise the city; a districted city without a
 * district widens to city-level on the read side — write-side enforcement is
 * Phase 10.6).
 *
 * <p><b>Discovery locality is obtained exclusively through the M2 seam</b>
 * ({@link DiscoveryLocationResolver}). This service never reads
 * {@code district_id} directly to <em>decide</em> the filter — it asks the
 * resolver for a {@link DiscoveryLocationKey} and only then binds the chosen
 * column. The SQL still references the FK columns to express the predicate,
 * but which one (district vs city) and the resolved display labels both come
 * from the seam, so Part B (geocoded point/radius) swaps the resolver impl
 * with zero change here.</p>
 *
 * <p><b>Employed {@code SALON_MASTER} locality resolves via the salon link at
 * query time:</b> a salon master's discovery locality is its salon's locality
 * ({@code masters.salon_id → salons.city_id/district_id}); an
 * {@code INDEPENDENT_MASTER} uses its own user-row locality. The query uses
 * {@code COALESCE(sal.city_id, u.city_id)} /
 * {@code COALESCE(sal.district_id, u.district_id)} — the salon address is
 * never copied/denormalised onto the master row, and multi-salon
 * (phases 2.11–2.14) is honoured because the join is evaluated per request.
 * The {@code salons} LEFT JOIN is a single-row PK join (no fan-out), unlike
 * the conditional {@code master_services} join.</p>
 *
 * <p><b>Master-role scope (Phase 19.7, decision 7):</b> the master query
 * carries an explicit {@code AND u.role = 'INDEPENDENT_MASTER'} predicate on
 * the {@code masters m JOIN users u} join, so {@code /search/masters} returns
 * independent masters only. Employed {@code SALON_MASTER} accounts are reached
 * exclusively through the public salon page; {@code SALON_ADMIN} /
 * {@code SALON_OWNER} are not bookable masters. This equality predicate
 * replaces the earlier {@code <> 'SALON_ADMIN'} exclusion that let
 * {@code SALON_MASTER} leak onto the public grid.</p>
 *
 * <p>With the {@code INDEPENDENT_MASTER}-only filter, the salon side of the
 * {@code COALESCE(sal.*, u.*)} discovery-locality expression is effectively
 * dead for this query (an independent master has no {@code salon_id}, so
 * {@code sal.*} is always {@code NULL} and the user-row locality always wins).
 * The {@code salons} LEFT JOIN and the COALESCE are left intact: they remain
 * correct (harmless NULL-coalescing) and the salon-locality resolution is
 * still required by the unchanged salon-discovery path.</p>
 *
 * <h3>SQL is built dynamically per request</h3>
 * The native SQL is assembled by {@link #buildMasterSearchSql} at request time
 * rather than held as a static constant with {@code (:p IS NULL OR col = :p)}
 * placeholders. Two reasons:
 *
 * <ol>
 *   <li><b>Index pushdown</b>. Dropping the parameter entirely when the value
 *       is null (instead of a {@code CAST(:p AS ...) IS NULL OR ...} idiom)
 *       restores the planner's ability to use the
 *       {@code idx_users_district_id} / {@code idx_users_city_id} indexes
 *       (V54).</li>
 *   <li><b>JOIN avoidance</b>. The main master query never joins
 *       {@code master_services} / {@code service_definitions}. Category and
 *       service-name ({@code q}) filters are correlated {@code EXISTS}
 *       sub-selects, and the {@code serviceNames} contract field is a
 *       post-LIMIT correlated lateral computed for only the paged rows. Price
 *       filtering uses the pre-computed {@code m.min_effective_price} column
 *       (PERF-M2, V58). So the main query stays a clean filter + index-ordered
 *       {@code ORDER BY} + {@code LIMIT} that the rating/review/price indexes
 *       serve as a true Top-N — no district-wide {@code GROUP BY} before the
 *       {@code LIMIT}.</li>
 * </ol>
 *
 * <h3>Single-query pagination (PERF-M1)</h3>
 * The data SELECT includes {@code COUNT(*) OVER() AS total_count} as the last
 * column (index 9). After {@code getResultList()}, {@code total_count} is read
 * from {@code row[9]} of the first result row — eliminating the second
 * {@code SELECT COUNT(*)} query that fired on every cache miss.
 *
 * <h3>Pre-computed min price (PERF-M2)</h3>
 * {@code masters.min_effective_price} (V58) is updated by
 * {@link com.beautica.service.service.ServiceCatalogService} whenever a master
 * service assignment is created, removed, or when a service definition is
 * deactivated. Price filtering is therefore a simple {@code WHERE} predicate
 * rather than a {@code GROUP BY … HAVING MIN(COALESCE(…))} aggregate.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    /** Scale of {@code masters.avg_rating} (NUMERIC(3,2)) — matches column precision. */
    private static final int RATING_SCALE = 2;

    /** Scale of {@code masters.min_effective_price} (NUMERIC(10,2)) — matches column precision. */
    private static final int PRICE_SCALE = 2;

    /**
     * Max distinct service names surfaced per master in
     * {@link MasterSearchResult#serviceNames()}. Bounds the response payload —
     * a master may carry dozens of services; the result card only previews a
     * few. Applied as a Postgres {@code array_agg(...)[1:N]} slice.
     */
    private static final int SERVICE_NAME_CAP = 3;

    /** Named-parameter prefix for the resolved {@code service_type_id} of slug {@code n}. */
    private static final String SERVICE_TYPE_ID_PARAM = "stId";

    /** Named-parameter prefix for the resolved {@code %nameUk%} ILIKE pattern of slug {@code n}. */
    private static final String SERVICE_TYPE_NAME_PARAM = "stName";

    /** Projection index of the {@code service_names} {@code array_agg} column. */
    private static final int SERVICE_NAMES_IDX = 10;

    /**
     * Projection index of the {@code matched_names} {@code array_agg} column
     * (Phase 20.3) — the distinct active service names that matched the
     * {@code serviceTypeSlugs} filter. Empty when no service filter is active.
     */
    private static final int MATCHED_SERVICE_NAMES_IDX = 14;

    /** Projection index of the {@code COUNT(*) OVER()} total-count column (PERF-M1). */
    private static final int TOTAL_COUNT_IDX = 15;

    // ── salon dynamic-projection layout (Phase 20.2 per-service filter path) ──
    /** Salon projection index of the discovery {@code city_id}. */
    private static final int SALON_CITY_ID_IDX = 2;
    /** Salon projection index of the discovery {@code district_id}. */
    private static final int SALON_DISTRICT_ID_IDX = 3;
    /** Salon projection index of the {@code COUNT(*) OVER()} total-count column. */
    private static final int SALON_TOTAL_COUNT_IDX = 12;

    /**
     * Role value (stored via {@code EnumType.STRING}) that master discovery is
     * restricted to. Phase 19.7 (decision 7): {@code /search/masters} returns
     * {@code INDEPENDENT_MASTER} only. Employed {@code SALON_MASTER} accounts
     * are reached exclusively through the public salon page, and
     * {@code SALON_ADMIN} / {@code SALON_OWNER} are never bookable masters —
     * an equality predicate on this single role keeps all three off the public
     * master grid regardless of future data shape (replaces the earlier
     * {@code <> 'SALON_ADMIN'} exclusion that let {@code SALON_MASTER} leak in).
     */
    private static final String ROLE_INDEPENDENT_MASTER = "INDEPENDENT_MASTER";

    /**
     * Discovery-locality SQL expressions. A {@code SALON_MASTER}'s locality is
     * its salon's; an {@code INDEPENDENT_MASTER}'s is its own user row. The
     * salon link wins when present — never denormalised onto the master row.
     */
    private static final String DISCOVERY_CITY_EXPR = "COALESCE(sal.city_id, u.city_id)";
    private static final String DISCOVERY_DISTRICT_EXPR = "COALESCE(sal.district_id, u.district_id)";

    /**
     * EntityManager is field-injected via {@link PersistenceContext} rather than
     * constructor-injected: Spring intercepts this annotation specifically to
     * supply a transaction-aware shared proxy. This is the documented Spring
     * exception to the constructor-injection rule.
     */
    @PersistenceContext
    private EntityManager entityManager;

    private final SalonRepository salonRepository;
    private final DiscoveryLocationResolver discoveryLocationResolver;
    private final ServiceTypeSlugResolver serviceTypeSlugResolver;

    /**
     * Discover masters matching optional location (FK, district-primary),
     * category, rating, and price filters. Returns a page sorted by rating
     * descending with resolved {@code cityLabel}/{@code districtLabel}.
     *
     * <p><b>Caching</b>: first 5 pages are cached for 60 seconds; the cache
     * key is now the {@code (cityId, districtId)} FK pair, not the removed
     * free-text params.
     *
     * @throws BusinessException if {@code minPrice} > {@code maxPrice}
     */
    @Cacheable(
            value = "search:masters",
            key = "{#request.location?.cityId, #request.location?.districtId, " +
                  "#request.q, #request.category, #request.sort, " +
                  "#request.minPrice, #request.maxPrice, " +
                  "#request.minRating, #request.normalizedServiceTypeSlugs(), " +
                  "#pageable.pageNumber, #pageable.pageSize}",
            condition = "#pageable.pageNumber < 5",
            sync = true
    )
    @Transactional(readOnly = true)
    public Page<MasterSearchResult> searchMasters(MasterSearchRequest request, Pageable pageable) {
        validatePriceRange(request.minPrice(), request.maxPrice());

        // Phase 20.1: resolve the per-service filter. An empty Optional means at
        // least one selected slug resolves to no active service-type — under AND
        // semantics no master can satisfy the filter, so short-circuit to an
        // empty page rather than running a query that is guaranteed to be empty.
        Optional<List<ResolvedServiceType>> resolved =
                resolveServiceTypes(request.normalizedServiceTypeSlugs());
        if (resolved.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        MasterSearchFilters filters = normalize(request, resolved.get());

        // PERF-M1: single query — COUNT(*) OVER() is column index 10.
        // No separate count round-trip on cache miss.
        //
        // PERF (service-join refactor): the Top-N filter/ORDER BY/LIMIT lives in
        // an inner derived table that the index-ordered indexes can drive, and
        // the serviceNames aggregate runs as a post-LIMIT correlated lateral over
        // only the paged rows. Because the LIMIT/OFFSET must sit on the INNER
        // derived table (so the lateral never sees the whole district), they are
        // bound as named :limit/:offset params on that subquery rather than via
        // Hibernate's setMaxResults/setFirstResult (which would wrap the outer
        // lateral and re-expand the work).
        SqlAndParams dataSql = buildMasterSearchSql(filters, pageable);
        Query dataQuery = entityManager.createNativeQuery(dataSql.sql());
        bind(dataQuery, dataSql.params());

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = dataQuery.getResultList();

        if (rawRows.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        long total = ((Number) rawRows.get(0)[TOTAL_COUNT_IDX]).longValue();

        DiscoveryLabels labels = resolveLabelsForRows(rawRows, 6, 7);
        List<MasterSearchResult> results = new ArrayList<>(rawRows.size());
        for (Object[] row : rawRows) {
            results.add(mapMasterRow(row, labels));
        }

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Discover salons matching the optional FK location filter
     * (district-primary). Delegates filtering to the repository's JPQL query
     * and maps each projection to a public-facing DTO with resolved locality
     * labels. The JPA entity is never loaded — only the five columns needed by
     * {@link SalonSearchResult} are fetched (LOW PERF fix: was "Loads Salon
     * entity then maps via Page#map").
     *
     * <p><b>Caching</b>: same trade-off as {@link #searchMasters} — first 5
     * pages, 60-second TTL, FK-pair key.
     */
    @Cacheable(
            value = "search:salons",
            key = "{#request.location?.cityId, #request.location?.districtId, " +
                  "#request.q, #request.category, #request.sort, " +
                  "#request.minPrice, #request.maxPrice, " +
                  "#request.normalizedServiceTypeSlugs(), " +
                  "#pageable.pageNumber, #pageable.pageSize}",
            condition = "#pageable.pageNumber < 5",
            sync = true
    )
    @Transactional(readOnly = true)
    public Page<SalonSearchResult> searchSalons(SalonSearchRequest request, Pageable pageable) {
        DiscoveryLocationKey key = resolveLocation(request.location());
        UUID cityId = key == null ? null : key.cityId();
        UUID districtId = key == null ? null : key.districtId();
        // Phase 19.7: scope the salon price-range aggregation to the searched
        // category when present (salon-wide when null). Normalised to upper-case
        // so the bound value matches what EnumType.STRING wrote to
        // service_definitions.category — mirrors the masters-search path.
        String category = normalizeCategory(request.category());
        String q = normalizeQuery(request.q());
        String likePattern = q == null ? null : likeContains(q);
        BigDecimal minPrice = normalizePrice(request.minPrice());
        BigDecimal maxPrice = normalizePrice(request.maxPrice());
        SearchSort sort = SearchSort.orDefault(request.sort());

        // Phase 20.2: resolve the per-service filter (see searchMasters). An empty
        // Optional → an unresolvable slug → empty page under AND semantics.
        Optional<List<ResolvedServiceType>> resolved =
                resolveServiceTypes(request.normalizedServiceTypeSlugs());
        if (resolved.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }
        List<ResolvedServiceType> serviceTypes = resolved.get();

        // Phase 20.2: when a per-service filter is active the static repository
        // projection queries cannot express N dynamic, ANDed correlated EXISTS
        // predicates (one per slug — locked decision 3 forbids a single IN/ANY),
        // so the filtered path is assembled dynamically here via the EntityManager
        // — mirroring the master path and the DashboardService array-binding
        // precedent. The unfiltered path keeps the tuned repository overloads
        // (with their no-price COUNT gate) untouched.
        if (!serviceTypes.isEmpty()) {
            return searchSalonsWithServiceFilter(
                    cityId, districtId, category, likePattern, minPrice, maxPrice,
                    sort, serviceTypes, pageable);
        }

        // Re-page with an allow-listed Sort built from the enum — caller text
        // never reaches the ORDER BY (native query applies Sort by select alias).
        Pageable sortedPageable = withSalonSort(pageable, sort);

        Page<SalonSearchProjection> page = findSalonsByLocation(
                cityId, districtId, category, likePattern, minPrice, maxPrice, sortedPageable);

        List<SalonSearchProjection> projections = page.getContent();
        DiscoveryLabels labels = discoveryLocationResolver.resolveLabels(
                distinct(projections, SalonSearchProjection::getCityId),
                distinct(projections, SalonSearchProjection::getDistrictId));

        return page.map(proj -> toSalonSearchResult(proj, labels));
    }

    /**
     * Dynamic per-service-filtered salon search (Phase 20.2). Assembles a single
     * native query — locality dispatch, optional {@code q} / price / category
     * predicates, and <b>one correlated {@code EXISTS} per selected slug</b>
     * (AND semantics) reaching into the salon's active masters' active services
     * via the hybrid {@code service_type_id = :id OR name ILIKE :name} match —
     * plus a {@code COUNT(*) OVER()} window for single-query pagination
     * (PERF-M1) and the {@code matchedServiceNames} lateral (Phase 20.3). Bound
     * params are typed objects (UUID / BigDecimal) so no {@code CAST(:p …)} idiom
     * is emitted ({@code SearchServiceTest} guard).
     */
    private Page<SalonSearchResult> searchSalonsWithServiceFilter(
            UUID cityId, UUID districtId, String category, String likePattern,
            BigDecimal minPrice, BigDecimal maxPrice, SearchSort sort,
            List<ResolvedServiceType> serviceTypes, Pageable pageable) {
        SqlAndParams dataSql = buildSalonSearchSql(
                cityId, districtId, category, likePattern, minPrice, maxPrice,
                sort, serviceTypes, pageable);
        Query dataQuery = entityManager.createNativeQuery(dataSql.sql());
        bind(dataQuery, dataSql.params());

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = dataQuery.getResultList();

        if (rawRows.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        long total = ((Number) rawRows.get(0)[SALON_TOTAL_COUNT_IDX]).longValue();
        DiscoveryLabels labels = resolveLabelsForRows(rawRows, SALON_CITY_ID_IDX, SALON_DISTRICT_ID_IDX);
        List<SalonSearchResult> results = new ArrayList<>(rawRows.size());
        for (Object[] row : rawRows) {
            results.add(mapSalonRow(row, labels));
        }
        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Re-pages with an allow-listed {@link Sort} mapped from {@link SearchSort}.
     * The sort properties are the <em>select aliases</em> of the salon
     * projection query ({@code price_min}, {@code price_max}, {@code name}) —
     * Spring Data appends them to the native {@code ORDER BY}; the caller's raw
     * text is never used.
     *
     * <p>Salons carry no per-row rating or review-count column in the projection,
     * so {@link SearchSort#RATING_DESC} and {@link SearchSort#REVIEWS_DESC} fall
     * back to a stable {@code name} ordering rather than failing — documented
     * divergence from the master endpoint. {@code name} is always appended as the
     * deterministic tiebreaker so paging is stable.
     */
    private static Pageable withSalonSort(Pageable pageable, SearchSort sort) {
        Sort resolved = switch (sort) {
            case PRICE_ASC -> Sort.by(Sort.Order.asc("price_min"), Sort.Order.asc("name"));
            case PRICE_DESC -> Sort.by(Sort.Order.desc("price_max"), Sort.Order.asc("name"));
            case RATING_DESC, REVIEWS_DESC -> Sort.by(Sort.Order.asc("name"));
        };
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), resolved);
    }

    /**
     * Dispatches the salon-location query to a single-equality, SARGable
     * <em>projection</em> repository method by district-primary precedence
     * (Phase 10.8, MEDIUM-1). Returns {@link SalonSearchProjection} pages —
     * only the five columns needed for the search response are fetched:
     * {@code id}, {@code name}, {@code city_id}, {@code district_id},
     * {@code avatar_url}.
     *
     * <ol>
     *   <li>a resolved {@code districtId} wins →
     *       {@link SalonRepository#findActiveByDistrictIdAsProjection} (index-served by
     *       {@code idx_salons_district_id});</li>
     *   <li>else a resolved {@code cityId} →
     *       {@link SalonRepository#findActiveByCityIdAsProjection} (index-served by
     *       {@code idx_salons_city_id});</li>
     *   <li>else no locality filter →
     *       {@link SalonRepository#findByIsActiveTrueAsProjection}.</li>
     * </ol>
     *
     * <p><b>HIGH PERF gate</b>: when both price bounds are null the no-price
     * {@code *NoPriceAsProjection} overload is chosen instead. Its
     * {@code countQuery} is a plain {@code COUNT(*)} with no price-range LATERAL,
     * so the common (no-price-filter) search no longer pays the per-salon price
     * aggregate twice (once for data, once for an irrelevant count). The data
     * query still runs the LATERAL because {@code priceMin}/{@code priceMax} are
     * display columns on every salon card.
     */
    private Page<SalonSearchProjection> findSalonsByLocation(
            UUID cityId, UUID districtId, String category, String q,
            BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        boolean noPriceFilter = minPrice == null && maxPrice == null;
        if (districtId != null) {
            return noPriceFilter
                    ? salonRepository.findActiveByDistrictIdNoPriceAsProjection(districtId, category, q, pageable)
                    : salonRepository.findActiveByDistrictIdAsProjection(
                            districtId, category, q, minPrice, maxPrice, pageable);
        }
        if (cityId != null) {
            return noPriceFilter
                    ? salonRepository.findActiveByCityIdNoPriceAsProjection(cityId, category, q, pageable)
                    : salonRepository.findActiveByCityIdAsProjection(
                            cityId, category, q, minPrice, maxPrice, pageable);
        }
        return noPriceFilter
                ? salonRepository.findByIsActiveTrueNoPriceAsProjection(q, category, pageable)
                : salonRepository.findByIsActiveTrueAsProjection(q, minPrice, maxPrice, category, pageable);
    }

    // ── location seam (M2) ────────────────────────────────────────────────────

    /**
     * Obtains the discovery-locality key through the M2 seam — never reads
     * {@code district_id} directly to decide the filter. Returns {@code null}
     * when no location filter was supplied.
     */
    private DiscoveryLocationKey resolveLocation(LocationFilter location) {
        if (location == null) {
            return null;
        }
        return discoveryLocationResolver.resolveFilter(location.cityId(), location.districtId());
    }

    // ── SQL builder ──────────────────────────────────────────────────────────

    /**
     * Normalised filter bag. {@code cityId}/{@code districtId} are the resolved
     * discovery-locality FK ids (from the M2 seam), not free text. {@code q} is
     * the normalised free-text term (trimmed, null-if-blank); {@code sort} is
     * the resolved ordering (never null after {@link #normalize}).
     */
    private record MasterSearchFilters(
            UUID cityId,
            UUID districtId,
            String q,
            String category,
            SearchSort sort,
            BigDecimal minRating,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            List<ResolvedServiceType> serviceTypes
    ) {
        boolean hasDistrictFilter() {
            return districtId != null;
        }

        boolean hasCityFilter() {
            return districtId == null && cityId != null;
        }
    }

    /**
     * A selected {@code serviceTypeSlug} resolved to its hybrid match operands:
     * the {@code service_type_id} FK target and the pre-escaped {@code %nameUk%}
     * containment pattern. Both branches are ORed in the per-slug predicate
     * ({@code sd.service_type_id = :id OR sd.name ILIKE :name}) so legacy /
     * single-create rows whose FK is still {@code NULL} are recovered by name
     * (Phase 20.x; FK backfill is the deferred Phase 20.4). Only ever built for
     * a slug that resolved to an active service-type, so {@code serviceTypeId}
     * is non-null and bound as a plain {@code UUID} (no {@code CAST}).
     */
    private record ResolvedServiceType(UUID serviceTypeId, String namePattern) {}

    /** Carrier for {@code (sql, params)} pairs returned by {@link #buildMasterSearchSql}. */
    private record SqlAndParams(String sql, Map<String, Object> params) {}

    /**
     * Resolves the normalized {@code serviceTypeSlugs} to their hybrid match
     * operands through the cached {@link ServiceTypeSlugResolver}.
     *
     * <ul>
     *   <li>empty input → present, empty list (no service filter);</li>
     *   <li>every slug resolves → present list of {@link ResolvedServiceType};</li>
     *   <li>any slug unresolved → {@link Optional#empty()} signalling the caller
     *       to short-circuit to an empty page (AND-of-all can never match).</li>
     * </ul>
     */
    private Optional<List<ResolvedServiceType>> resolveServiceTypes(List<String> slugs) {
        if (slugs.isEmpty()) {
            return Optional.of(List.of());
        }
        List<Optional<ServiceTypeMatch>> matches = serviceTypeSlugResolver.resolve(slugs);
        List<ResolvedServiceType> resolved = new ArrayList<>(matches.size());
        for (Optional<ServiceTypeMatch> match : matches) {
            if (match.isEmpty()) {
                return Optional.empty();
            }
            ServiceTypeMatch type = match.get();
            resolved.add(new ResolvedServiceType(type.serviceTypeId(), likeContains(type.nameUk())));
        }
        return Optional.of(List.copyOf(resolved));
    }

    private MasterSearchFilters normalize(MasterSearchRequest request, List<ResolvedServiceType> serviceTypes) {
        DiscoveryLocationKey key = resolveLocation(request.location());
        return new MasterSearchFilters(
                key == null ? null : key.cityId(),
                key == null ? null : key.districtId(),
                normalizeQuery(request.q()),
                normalizeCategory(request.category()),
                SearchSort.orDefault(request.sort()),
                normalizeRating(request.minRating()),
                normalizePrice(request.minPrice()),
                normalizePrice(request.maxPrice()),
                serviceTypes
        );
    }

    /**
     * Builds the single data+count SQL for master search (PERF-M1) as a
     * <em>Top-N inner query + post-LIMIT serviceNames lateral</em>.
     *
     * <h4>Why this shape</h4>
     * The earlier version emitted the {@code master_services}/
     * {@code service_definitions} join, a {@code GROUP BY m.id}, and an inline
     * {@code array_agg(DISTINCT sd.name)} in the <em>main</em> query. That
     * grouped the entire district before {@code LIMIT}, pipeline-breaking the
     * {@code idx_masters_active_rating} (V36) / {@code idx_masters_active_review_count}
     * (V99) index-ordered Top-N. This refactor splits the work in two:
     *
     * <ol>
     *   <li><b>Inner derived table {@code t}</b> — a clean
     *       {@code masters JOIN users} (+ {@code salons} LEFT JOIN for the
     *       discovery-locality COALESCE) filter + {@code ORDER BY} +
     *       {@code LIMIT/OFFSET}. No service join, no {@code GROUP BY}, so the
     *       rating/review/price indexes can serve it as a true index-ordered
     *       Top-N. {@code COUNT(*) OVER()} rides along (PERF-M1) and — because
     *       Postgres applies {@code LIMIT} after window functions — still
     *       reports the full filtered count in every paged row.</li>
     *   <li><b>Category / {@code q} predicates → {@code EXISTS}</b> — a
     *       category or service-name filter is expressed as a correlated
     *       {@code EXISTS} sub-select (see {@link #appendWhereClause}). Each
     *       {@code ILIKE} is then served by its own trigram index (V98) with no
     *       join fan-out and no {@code GROUP BY} (also fixes the
     *       OR-across-relations / single-index-defeat finding).</li>
     *   <li><b>serviceNames → post-LIMIT correlated lateral {@code sn}</b> — the
     *       capped, distinct, custom-preferred service names are computed for
     *       <em>only the paged rows</em> (~20), never the whole district. The
     *       {@code serviceNames} contract field is therefore populated on
     *       <em>every</em> card (empty array when none), including location-only
     *       searches — it is not gated behind any filter.</li>
     * </ol>
     *
     * <p>Price filtering still uses the pre-computed {@code m.min_effective_price}
     * column (PERF-M2, V58) — never a {@code HAVING} aggregate.
     *
     * <p>{@code LIMIT}/{@code OFFSET} are bound as named {@code :limit}/
     * {@code :offset} params on the inner derived table specifically so the
     * lateral sits <em>above</em> the LIMIT; binding them via Hibernate's
     * {@code setMaxResults}/{@code setFirstResult} would wrap the outer lateral
     * and re-expand it over the full district.
     */
    private static SqlAndParams buildMasterSearchSql(MasterSearchFilters filters, Pageable pageable) {
        StringBuilder inner = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();

        appendDataSelect(inner);
        appendFromClause(inner);
        appendWhereClause(inner, filters, params);
        appendOrderBy(inner, filters.sort());
        // Inner Top-N: LIMIT/OFFSET bound here so the index-ordered scan feeds a
        // bounded ~pageSize row set into the serviceNames lateral above.
        inner.append(" LIMIT :limit OFFSET :offset");
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        String sql = wrapWithServiceNamesLateral(
                inner.toString(), filters.sort(), filters.category() != null,
                filters.serviceTypes());
        return new SqlAndParams(sql, params);
    }

    /**
     * Wraps the index-ordered Top-N derived table with the post-LIMIT
     * correlated lateral that computes {@link MasterSearchResult#serviceNames()}
     * for only the paged rows.
     *
     * <p>The lateral selects the DISTINCT active service names of each paged
     * master, ordered by {@code sd.name} and capped to {@code SERVICE_NAME_CAP},
     * then {@code array_agg}s them. {@code sd.name} is the owner-set custom name
     * ({@code NOT NULL} by column constraint). A master with no active services
     * yields {@code NULL} from {@code array_agg} → mapped to an empty list. The
     * outer {@code ORDER BY} is re-applied on the derived-table aliases so paging
     * order survives the lateral join (which is otherwise unordered).
     *
     * <p>PERF-M1 (Phase 19.x audit): {@code price_max} is computed HERE too,
     * folded into this single lateral instead of the former separate correlated
     * scalar sub-select in {@link #appendDataSelect} — both walked the same
     * {@code master_services ⋈ service_definitions} join, so the second pass was
     * pure waste. The names sub-query is DISTINCT + ORDER + LIMIT (capped
     * preview); the price ceiling is an unbounded {@code MAX} over ALL the
     * master's active services, so it cannot share the LIMIT-ed inner derived
     * table — it is a sibling aggregate over the same {@code FROM} in the lateral
     * body. Semantics are byte-for-byte the prior formula:
     * {@code MAX(COALESCE(ms.price_override, CASE WHEN sd.price_type='RANGE'
     * THEN sd.price_max ELSE sd.base_price END))}, so a single fixed price (or a
     * single override) still yields {@code price_max == min_effective_price}.
     * {@code NULL} when the master has no active, priced services.
     *
     * <p>Column layout (indices 0–15, Phase 20.3): {@code price_max} stays at
     * projection index 9 and {@link #SERVICE_NAMES_IDX} (10) is unchanged — only
     * its SOURCE moves from {@code t.price_max} to {@code sn.price_max}. The
     * auth-gated address trio {@code street} (11) / {@code building_no} (12) /
     * {@code location_note} (13) is followed by
     * {@link #MATCHED_SERVICE_NAMES_IDX matched_names} (14) and finally
     * {@link #TOTAL_COUNT_IDX} (15).
     */
    private static String wrapWithServiceNamesLateral(
            String innerSql, SearchSort sort, boolean hasCategoryFilter,
            List<ResolvedServiceType> serviceTypes) {
        boolean hasServiceFilter = !serviceTypes.isEmpty();
        // Phase 20.3: matched_names is sourced from the slug-scoped lateral when a
        // per-service filter is active, else a typed empty array literal (mapped
        // to []). A typed-NULL literal (not CAST(:p …)) is permitted by the guard.
        String matchedNamesExpr = hasServiceFilter ? "mn.matched_names" : "CAST(NULL AS text[])";
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT t.master_id, t.first_name, t.last_name, ")
                .append("t.avg_rating, t.review_count, t.avatar_url, ")
                .append("t.discovery_city_id, t.discovery_district_id, ")
                .append("t.min_effective_price, sn.price_max, sn.service_names, ")
                .append("t.street, t.building_no, t.location_note, ")
                .append(matchedNamesExpr).append(" AS matched_names, t.total_count ")
                .append("FROM (").append(innerSql).append(") t ")
                .append("LEFT JOIN LATERAL (")
                .append("SELECT ")
                // Capped DISTINCT-name preview: array_agg over the LIMIT-ed inner
                // name sub-query (names need ORDER + LIMIT, prices do not).
                .append("(SELECT array_agg(x.name) FROM (")
                .append("SELECT DISTINCT sd.name ")
                .append("FROM master_services ms ")
                .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                .append("WHERE ms.master_id = t.master_id AND ms.is_active = true ");
        // Phase 19.7 fix (Option B): scope the names preview to the searched category
        // (catalogue-wide when null) so a category-filtered card never leaks names
        // from a master's services in OTHER categories. Mirrors the salon path.
        // Appended as a plain equality predicate ONLY when a category filter is
        // active — so :category is referenced solely where it is bound (conditional
        // bind, see appendWhereClause) — avoiding the CAST(:...) idiom forbidden by
        // SearchServiceTest's generated-SQL guard.
        if (hasCategoryFilter) {
            sb.append("AND sd.category = :category ");
        }
        sb.append("ORDER BY sd.name LIMIT ").append(SERVICE_NAME_CAP)
                .append(") x) AS service_names, ")
                // Price ceiling: unbounded MAX over ALL active services (no LIMIT).
                .append("MAX(COALESCE(ms2.price_override, "
                        + "CASE WHEN sd2.price_type = 'RANGE' "
                        + "THEN sd2.price_max ELSE sd2.base_price END)) AS price_max ")
                .append("FROM master_services ms2 ")
                .append("JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id AND sd2.is_active = true ")
                .append("WHERE ms2.master_id = t.master_id AND ms2.is_active = true ")
                .append(") sn ON true ");
        // Phase 20.3 — matched_names: a second post-LIMIT correlated lateral over
        // ONLY the paged masters, mirroring the serviceNames lateral's shape (no
        // GROUP BY/HAVING). DISTINCT active service names matching ANY selected
        // slug (OR across slugs — the card shows what matched), capped to
        // SERVICE_NAME_CAP. Reuses the :stId{n}/:stName{n} params already bound by
        // appendWhereClause. Only emitted when a per-service filter is active.
        if (hasServiceFilter) {
            appendMatchedNamesLateral(sb, serviceTypes);
        }
        appendOuterOrderBy(sb, sort);
        return sb.toString();
    }

    /**
     * Appends the allow-listed {@code ORDER BY} for the resolved {@link SearchSort}.
     *
     * <p>The {@code switch} is exhaustive over the {@link SearchSort} enum — the
     * caller's text never reaches the SQL, so this is injection-safe. Every
     * branch ends with {@code m.id} as a deterministic tiebreaker so paging is
     * stable when the primary key collides (equal ratings/prices/review counts).
     */
    private static void appendOrderBy(StringBuilder sb, SearchSort sort) {
        sb.append(switch (sort) {
            case PRICE_ASC -> "ORDER BY m.min_effective_price ASC NULLS LAST, m.id";
            case PRICE_DESC -> "ORDER BY m.min_effective_price DESC NULLS LAST, m.id";
            case REVIEWS_DESC -> "ORDER BY m.review_count DESC NULLS LAST, m.id";
            case RATING_DESC -> "ORDER BY m.avg_rating DESC NULLS LAST, m.id";
        });
    }

    /**
     * Outer {@code ORDER BY} re-applied on the derived-table aliases ({@code t.*})
     * after the serviceNames lateral join. A {@code LEFT JOIN LATERAL} does not
     * preserve the inner {@code ORDER BY}, so the paging order is restated here
     * over the already-bounded ~pageSize rows (a cheap final sort, not a
     * district-wide one). Mirrors {@link #appendOrderBy} column-for-column with
     * {@code m.} → {@code t.} and {@code m.id} → {@code t.master_id}.
     */
    private static void appendOuterOrderBy(StringBuilder sb, SearchSort sort) {
        sb.append(switch (sort) {
            case PRICE_ASC -> "ORDER BY t.min_effective_price ASC NULLS LAST, t.master_id";
            case PRICE_DESC -> "ORDER BY t.min_effective_price DESC NULLS LAST, t.master_id";
            case REVIEWS_DESC -> "ORDER BY t.review_count DESC NULLS LAST, t.master_id";
            case RATING_DESC -> "ORDER BY t.avg_rating DESC NULLS LAST, t.master_id";
        });
    }

    /**
     * Builds the SELECT clause for the master data <em>inner derived table</em>.
     *
     * <p>This is the index-ordered Top-N core: NO service join, NO
     * {@code array_agg}, NO {@code GROUP BY}. {@code serviceNames} is added one
     * level up by {@link #wrapWithServiceNamesLateral} as a post-LIMIT lateral,
     * so it stays out of the way of the rating/review/price index ordering here.
     *
     * <p>Inner aliases (referenced by name from the outer SELECT — index order
     * within the inner is irrelevant since the outer projects explicitly):
     * {@code master_id, first_name, last_name, avg_rating, review_count,
     * avatar_url, discovery_city_id, discovery_district_id, min_effective_price,
     * total_count}. The outer SELECT re-orders them to the stable 0–10 layout
     * that {@link #mapMasterRow} expects (with {@code service_names} at index 9
     * and {@code total_count} at index 10).
     */
    private static void appendDataSelect(StringBuilder sb) {
        sb.append("SELECT m.id AS master_id, ")
                .append("u.first_name AS first_name, u.last_name AS last_name, ")
                .append("m.avg_rating AS avg_rating, m.review_count AS review_count, ")
                // Avatar column does not yet exist on users/masters as a search
                // projection source. Emit NULL so the projection still maps
                // cleanly until a future phase wires master avatar storage.
                .append("CAST(NULL AS TEXT) AS avatar_url, ")
                // Discovery-locality FK ids (district-primary via salon link
                // for SALON_MASTER, else the user's own). Labels are resolved
                // through the M2 seam; these carry the ids only.
                .append(DISCOVERY_CITY_EXPR).append(" AS discovery_city_id, ")
                .append(DISCOVERY_DISTRICT_EXPR).append(" AS discovery_district_id, ")
                // PERF-M2: pre-computed column from V58 — avoids the per-request
                // MIN(COALESCE(ms.price_override, sd.base_price)) aggregate.
                .append("m.min_effective_price AS min_effective_price, ")
                // priceMax is NO LONGER computed here. PERF-M1 (Phase 19.x audit):
                // it was a SECOND correlated pass over master_services ⋈
                // service_definitions, duplicating the serviceNames lateral's walk
                // of the same join. It is now folded INTO that post-LIMIT lateral
                // (see wrapWithServiceNamesLateral) so ONE lateral returns both
                // service_names and price_max — a single pass per paged master.
                // AUTH-GATED street address (users.street / users.building_no /
                // users.location_note). Always selected; nulled for anonymous
                // callers post-cache in the controller — privacy for independent
                // masters' home addresses.
                .append("u.street AS street, u.building_no AS building_no, ")
                .append("u.location_note AS location_note, ")
                // PERF-M1: window function replaces the second COUNT(*) query.
                // Postgres applies LIMIT after window functions, so this reports
                // the full filtered count in every paged row of the Top-N.
                .append("COUNT(*) OVER() AS total_count ");
    }

    /**
     * {@code masters m JOIN users u} is the spine of the inner Top-N. The
     * {@code salons sal} LEFT JOIN on {@code m.salon_id} is always present
     * (single-row PK join, no fan-out) so an employed {@code SALON_MASTER}'s
     * discovery locality resolves through its salon at query time.
     *
     * <p><b>No service join here.</b> The {@code master_services} /
     * {@code service_definitions} tables are reached only via the correlated
     * {@code EXISTS} predicates ({@link #appendWhereClause}) and the post-LIMIT
     * serviceNames lateral ({@link #wrapWithServiceNamesLateral}) — never as a
     * main-query join, so they cannot pipeline-break the index-ordered Top-N.
     */
    private static void appendFromClause(StringBuilder sb) {
        sb.append("FROM masters m JOIN users u ON u.id = m.user_id ");
        sb.append("LEFT JOIN salons sal ON sal.id = m.salon_id ");
    }

    /**
     * District-primary FK location filter, {@code SALON_ADMIN} exclusion, and
     * price/rating predicates.
     *
     * <p>Price filtering (PERF-M2): the former {@code HAVING MIN(COALESCE(…))}
     * aggregate filter is replaced by a plain {@code WHERE m.min_effective_price}
     * predicate on the pre-computed column (V58). This predicate is pushed into
     * the WHERE clause so Postgres can use the partial index
     * {@code idx_masters_min_effective_price} and avoids the GROUP BY / HAVING
     * pipeline for the common price-only filter.
     *
     * <p>The exclusion sits on the {@code users} join (the role lives there) so
     * an admin account never surfaces in public master discovery regardless of
     * any future data shape.
     */
    private static void appendWhereClause(
            StringBuilder sb,
            MasterSearchFilters filters,
            Map<String, Object> params
    ) {
        sb.append("WHERE m.is_active = true AND u.is_active = true ");
        // Phase 19.7: restrict the public master grid to INDEPENDENT_MASTER only.
        // SALON_MASTER is reachable solely via the salon page; SALON_ADMIN /
        // SALON_OWNER are not bookable masters. Equality (not <>) keeps all
        // non-independent roles out regardless of future data shape.
        sb.append("AND u.role = :includedRole ");
        params.put("includedRole", ROLE_INDEPENDENT_MASTER);

        if (filters.hasDistrictFilter()) {
            sb.append("AND ").append(DISCOVERY_DISTRICT_EXPR).append(" = :districtId ");
            params.put("districtId", filters.districtId());
        } else if (filters.hasCityFilter()) {
            sb.append("AND ").append(DISCOVERY_CITY_EXPR).append(" = :cityId ");
            params.put("cityId", filters.cityId());
        }
        // Free-text name / service-name match. ILIKE is the Postgres
        // case-insensitive LIKE; the bound value is a pre-escaped %term% pattern
        // (LIKE wildcards in the user term are neutralised in normalizeQuery, so
        // ":q" is bound as a plain String, never interpolated into the SQL).
        //
        // The user-name predicates hit u.first_name / u.last_name directly (each
        // served by its own partial trigram index, V98). The service-name match
        // is a correlated EXISTS rather than a main-query join: this keeps the
        // index-ordered Top-N intact (no fan-out, no GROUP BY) and lets the
        // sd.name trigram index (V98) serve the inner ILIKE independently — also
        // fixing the OR-across-relations / single-index-defeat finding.
        if (filters.q() != null) {
            sb.append("AND (u.first_name ILIKE :q OR u.last_name ILIKE :q OR EXISTS (")
                    .append("SELECT 1 FROM master_services ms ")
                    .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                    .append("WHERE ms.master_id = m.id AND ms.is_active = true AND sd.name ILIKE :q)) ");
            params.put("q", likeContains(filters.q()));
        }
        // Category filter as a correlated EXISTS over the master's active
        // services — no main-query service join, so the Top-N stays index-ordered.
        if (filters.category() != null) {
            sb.append("AND EXISTS (")
                    .append("SELECT 1 FROM master_services ms ")
                    .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                    .append("WHERE ms.master_id = m.id AND ms.is_active = true AND sd.category = :category) ");
            // :category bound conditionally (Option B) — only when a category filter
            // is active, so it is always bound wherever it is referenced. The
            // post-LIMIT serviceNames lateral applies the SAME predicate conditionally
            // (plain equality, no CAST) — see wrapWithServiceNamesLateral.
            params.put("category", filters.category());
        }
        // Phase 20.1 — per-service filter: ONE correlated EXISTS per selected slug
        // (AND semantics — a master must offer a service matching EVERY slug;
        // locked decision 3 forbids a single IN/ANY). Each EXISTS hybrid-matches
        // on the FK (sd.service_type_id = :stId{n}) OR the resolved name
        // (sd.name ILIKE :stName{n}), recovering legacy rows whose FK is NULL. The
        // :stId{n} param is bound as a plain UUID object (no CAST). Independent
        // sub-selects reuse the ms/sd aliases — each EXISTS is its own scope.
        appendServiceTypeExists(sb, filters.serviceTypes(), params);
        if (filters.minRating() != null) {
            sb.append("AND m.avg_rating >= :minRating ");
            params.put("minRating", filters.minRating());
        }
        // PERF-M2: price predicates on the pre-computed column — plain WHERE,
        // no GROUP BY / HAVING needed. NULL min_effective_price means no active
        // services; such masters are excluded by the range predicate naturally.
        if (filters.minPrice() != null) {
            sb.append("AND m.min_effective_price >= :minPrice ");
            params.put("minPrice", filters.minPrice());
        }
        if (filters.maxPrice() != null) {
            sb.append("AND m.min_effective_price <= :maxPrice ");
            params.put("maxPrice", filters.maxPrice());
        }
    }

    /**
     * Appends one correlated {@code EXISTS} per selected slug to the master WHERE
     * clause (AND semantics) and binds each slug's {@code service_type_id} (plain
     * UUID, no CAST) + {@code %nameUk%} pattern. Each sub-select is its own scope,
     * so the {@code ms}/{@code sd} aliases are reused across slugs.
     */
    private static void appendServiceTypeExists(
            StringBuilder sb, List<ResolvedServiceType> serviceTypes, Map<String, Object> params) {
        for (int i = 0; i < serviceTypes.size(); i++) {
            ResolvedServiceType st = serviceTypes.get(i);
            String idParam = SERVICE_TYPE_ID_PARAM + i;
            String nameParam = SERVICE_TYPE_NAME_PARAM + i;
            sb.append("AND EXISTS (")
                    .append("SELECT 1 FROM master_services ms ")
                    .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                    .append("WHERE ms.master_id = m.id AND ms.is_active = true ")
                    .append("AND (sd.service_type_id = :").append(idParam)
                    .append(" OR sd.name ILIKE :").append(nameParam).append(")) ");
            params.put(idParam, st.serviceTypeId());
            params.put(nameParam, st.namePattern());
        }
    }

    /**
     * Appends the master matched-names lateral (Phase 20.3): DISTINCT active
     * service names matching ANY selected slug (OR across slugs), capped, over
     * only the paged masters ({@code t.master_id}). Reuses the already-bound
     * {@code :stId{n}}/{@code :stName{n}} params.
     */
    private static void appendMatchedNamesLateral(StringBuilder sb, List<ResolvedServiceType> serviceTypes) {
        sb.append("LEFT JOIN LATERAL (")
                .append("SELECT array_agg(xm.name) AS matched_names FROM (")
                .append("SELECT DISTINCT sd.name ")
                .append("FROM master_services ms ")
                .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                .append("WHERE ms.master_id = t.master_id AND ms.is_active = true AND (");
        appendServiceTypeMatchDisjunction(sb, "sd", serviceTypes.size());
        sb.append(") ORDER BY sd.name LIMIT ").append(SERVICE_NAME_CAP)
                .append(") xm) mn ON true ");
    }

    /**
     * Appends the OR-chain of per-slug hybrid match predicates
     * ({@code (alias.service_type_id = :stId{n} OR alias.name ILIKE :stName{n}) OR …})
     * used by the matched-names laterals. {@code count} is always {@code >= 1}
     * (callers only invoke this with an active filter).
     */
    private static void appendServiceTypeMatchDisjunction(StringBuilder sb, String alias, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append("OR ");
            }
            sb.append("(").append(alias).append(".service_type_id = :").append(SERVICE_TYPE_ID_PARAM).append(i)
                    .append(" OR ").append(alias).append(".name ILIKE :")
                    .append(SERVICE_TYPE_NAME_PARAM).append(i).append(") ");
        }
    }

    private static void bind(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }

    // ── salon per-service-filtered SQL builder (Phase 20.2/20.3) ───────────────

    /**
     * Builds the single data+count native SQL for the per-service-filtered salon
     * search as a <em>Top-N inner derived table + post-LIMIT name laterals</em>
     * — mirroring the master path's {@link #wrapWithServiceNamesLateral} shape.
     *
     * <h4>Why this shape (HIGH PERF fix)</h4>
     * The earlier version emitted a flat query with the {@code matched_names}
     * ({@code mn}) lateral and the {@code pnames} name-preview sub-select attached
     * directly to {@code salons s} <em>below</em> the {@code ORDER BY}/{@code LIMIT}.
     * Because both are referenced only in the SELECT list yet sit under an
     * {@code ORDER BY}, Postgres evaluated each for <em>every</em> matched salon
     * (the full candidate set) before applying {@code LIMIT 20} — and both fan out
     * salon → active masters → active services with an N-slug OR disjunction, so
     * this was a large wasted scan. This refactor splits the work in two:
     *
     * <ol>
     *   <li><b>Inner derived table {@code t}</b> — location + {@code q} + price +
     *       the N service-type {@code EXISTS} filters + {@code ORDER BY} +
     *       {@code LIMIT/OFFSET}. The {@code pr} price-aggregate lateral
     *       (pmin/pmax) <em>stays here</em> because it feeds the price WHERE and
     *       the {@code ORDER BY}. {@code COUNT(*) OVER()} rides along (PERF-M1) —
     *       Postgres applies {@code LIMIT} after window functions, so it still
     *       reports the full filtered count in every paged row.</li>
     *   <li><b>Name laterals → post-LIMIT</b> — the {@code pnames} name preview
     *       ({@code pn}) and the slug-scoped {@code matched_names} ({@code mn})
     *       laterals are attached in the OUTER block, correlated to {@code t.id},
     *       so they run for <em>only the ~pageSize paged rows</em>, never the whole
     *       candidate set.</li>
     * </ol>
     *
     * <p>One correlated {@code EXISTS} per selected slug (AND semantics,
     * salon→active masters→active services hybrid match) lives in the inner WHERE.
     * All params are typed objects — no {@code CAST(:p …)} idiom (guard).
     */
    private static SqlAndParams buildSalonSearchSql(
            UUID cityId, UUID districtId, String category, String likePattern,
            BigDecimal minPrice, BigDecimal maxPrice, SearchSort sort,
            List<ResolvedServiceType> serviceTypes, Pageable pageable) {
        StringBuilder inner = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        boolean hasCategory = category != null;

        // Inner Top-N: only the columns + price aggregates needed for WHERE /
        // ORDER BY / LIMIT. Name previews are deferred to the outer block.
        inner.append("SELECT s.id AS id, s.name AS name, s.city_id AS city_id, ")
                .append("s.district_id AS district_id, s.avatar_url AS avatar_url, ")
                .append("s.street AS street, s.building_no AS building_no, ")
                .append("s.location_note AS location_note, ")
                .append("pr.pmin AS pmin, pr.pmax AS pmax, ")
                .append("COUNT(*) OVER() AS total_count ")
                .append("FROM salons s ");
        appendSalonPriceAggregateLateral(inner, hasCategory);

        inner.append("WHERE s.is_active = true ");
        if (districtId != null) {
            inner.append("AND s.district_id = :districtId ");
            params.put("districtId", districtId);
        } else if (cityId != null) {
            inner.append("AND s.city_id = :cityId ");
            params.put("cityId", cityId);
        }
        if (likePattern != null) {
            inner.append("AND (s.name ILIKE :q OR EXISTS (")
                    .append("SELECT 1 FROM master_services msq ")
                    .append("JOIN masters mmq ON mmq.id = msq.master_id ")
                    .append("JOIN service_definitions sdq ON sdq.id = msq.service_def_id ")
                    .append("WHERE mmq.salon_id = s.id AND mmq.is_active = true ")
                    .append("AND msq.is_active = true AND sdq.is_active = true ")
                    .append("AND sdq.name ILIKE :q)) ");
            params.put("q", likePattern);
        }
        if (hasCategory) {
            params.put("category", category);
        }
        if (minPrice != null) {
            inner.append("AND pr.pmax >= :minPrice ");
            params.put("minPrice", minPrice);
        }
        if (maxPrice != null) {
            inner.append("AND pr.pmin <= :maxPrice ");
            params.put("maxPrice", maxPrice);
        }
        appendSalonServiceTypeExists(inner, serviceTypes, params);

        appendSalonOrderBy(inner, sort);
        inner.append(" LIMIT :limit OFFSET :offset");
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        String sql = wrapSalonWithNameLaterals(inner.toString(), hasCategory, sort, serviceTypes);
        return new SqlAndParams(sql, params);
    }

    /**
     * Wraps the index-ordered Top-N inner derived table {@code t} with the two
     * post-LIMIT correlated name laterals — the {@code pnames} preview ({@code pn})
     * and the slug-scoped {@code matched_names} ({@code mn}) — computed for only
     * the paged rows. Mirrors {@link #wrapWithServiceNamesLateral}.
     *
     * <p>The outer SELECT restores the stable 0–12 projection layout that
     * {@link #mapSalonRow} expects: id, name, city_id (2), district_id (3),
     * avatar_url, pmin (5), pmax (6), pnames (7), street, building_no,
     * location_note, matched_names (11), total_count (12). The price band
     * (pmin/pmax) and address trio are projected straight from {@code t}; only the
     * two name-preview columns come from the outer laterals.
     */
    private static String wrapSalonWithNameLaterals(
            String innerSql, boolean hasCategory, SearchSort sort,
            List<ResolvedServiceType> serviceTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT t.id, t.name, t.city_id, t.district_id, t.avatar_url, ")
                .append("t.pmin, t.pmax, pn.pnames, ")
                .append("t.street, t.building_no, t.location_note, ")
                .append("mn.matched_names, t.total_count ")
                .append("FROM (").append(innerSql).append(") t ");
        appendSalonNamePreviewLateral(sb, hasCategory);
        appendSalonMatchedNamesLateral(sb, serviceTypes);
        appendSalonOuterOrderBy(sb, sort);
        return sb.toString();
    }

    /**
     * Category-scoped price-band aggregate lateral — MIN floor + RANGE-aware MAX
     * ceiling over the salon's active masters' active services. Stays in the inner
     * derived table because {@code pmin}/{@code pmax} feed the price WHERE and the
     * {@code ORDER BY}. Category is bound conditionally (plain equality, no CAST).
     */
    private static void appendSalonPriceAggregateLateral(StringBuilder sb, boolean hasCategory) {
        sb.append("LEFT JOIN LATERAL (")
                .append("SELECT MIN(sd.base_price) AS pmin, ")
                .append("MAX(CASE WHEN sd.price_type = 'RANGE' THEN sd.price_max ELSE sd.base_price END) AS pmax ")
                .append("FROM master_services ms ")
                .append("JOIN masters mm ON mm.id = ms.master_id ")
                .append("JOIN service_definitions sd ON sd.id = ms.service_def_id ")
                .append("WHERE mm.salon_id = s.id AND mm.is_active = true ")
                .append("AND ms.is_active = true AND sd.is_active = true ");
        if (hasCategory) {
            sb.append("AND sd.category = :category ");
        }
        sb.append(") pr ON true ");
    }

    /**
     * Post-LIMIT {@code pnames} name-preview lateral (capped DISTINCT name
     * {@code array_agg}) correlated to {@code t.id} — runs over only the paged
     * salon rows. Byte-for-byte the prior name-preview sub-select formerly bundled
     * inside the {@code pr} price lateral, lifted out so it no longer scans the
     * full candidate set. Category is bound conditionally (plain equality, no CAST).
     */
    private static void appendSalonNamePreviewLateral(StringBuilder sb, boolean hasCategory) {
        sb.append("LEFT JOIN LATERAL (")
                .append("SELECT array_agg(z.name) AS pnames FROM (")
                .append("SELECT DISTINCT sd2.name AS name ")
                .append("FROM master_services ms2 ")
                .append("JOIN masters mm2 ON mm2.id = ms2.master_id ")
                .append("JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id ")
                .append("WHERE mm2.salon_id = t.id AND mm2.is_active = true ")
                .append("AND ms2.is_active = true AND sd2.is_active = true ");
        if (hasCategory) {
            sb.append("AND sd2.category = :category ");
        }
        sb.append("ORDER BY sd2.name LIMIT ").append(SERVICE_NAME_CAP)
                .append(") z) pn ON true ");
    }

    /**
     * Slug-scoped matched-names lateral (Phase 20.3): DISTINCT active service
     * names offered across the salon's active masters that match ANY selected
     * slug (OR across slugs), capped. Correlated to {@code t.id} — sits in the
     * OUTER block over only the paged rows (HIGH PERF fix). Reuses the
     * {@code :stId{n}}/{@code :stName{n}} params bound by
     * {@link #appendSalonServiceTypeExists}.
     */
    private static void appendSalonMatchedNamesLateral(StringBuilder sb, List<ResolvedServiceType> serviceTypes) {
        sb.append("LEFT JOIN LATERAL (")
                .append("SELECT array_agg(zm.name) AS matched_names FROM (")
                .append("SELECT DISTINCT sd3.name AS name ")
                .append("FROM master_services ms3 ")
                .append("JOIN masters mm3 ON mm3.id = ms3.master_id ")
                .append("JOIN service_definitions sd3 ON sd3.id = ms3.service_def_id ")
                .append("WHERE mm3.salon_id = t.id AND mm3.is_active = true ")
                .append("AND ms3.is_active = true AND sd3.is_active = true AND (");
        appendServiceTypeMatchDisjunction(sb, "sd3", serviceTypes.size());
        sb.append(") ORDER BY sd3.name LIMIT ").append(SERVICE_NAME_CAP)
                .append(") zm) mn ON true ");
    }

    /**
     * One correlated {@code EXISTS} per selected slug (AND semantics) reaching the
     * salon's active masters' active services, hybrid-matching FK OR name. Binds
     * each slug's {@code service_type_id} (plain UUID) + {@code %nameUk%} pattern.
     */
    private static void appendSalonServiceTypeExists(
            StringBuilder sb, List<ResolvedServiceType> serviceTypes, Map<String, Object> params) {
        for (int i = 0; i < serviceTypes.size(); i++) {
            ResolvedServiceType st = serviceTypes.get(i);
            String idParam = SERVICE_TYPE_ID_PARAM + i;
            String nameParam = SERVICE_TYPE_NAME_PARAM + i;
            sb.append("AND EXISTS (")
                    .append("SELECT 1 FROM master_services msf ")
                    .append("JOIN masters mmf ON mmf.id = msf.master_id ")
                    .append("JOIN service_definitions sdf ON sdf.id = msf.service_def_id ")
                    .append("WHERE mmf.salon_id = s.id AND mmf.is_active = true ")
                    .append("AND msf.is_active = true AND sdf.is_active = true ")
                    .append("AND (sdf.service_type_id = :").append(idParam)
                    .append(" OR sdf.name ILIKE :").append(nameParam).append(")) ");
            params.put(idParam, st.serviceTypeId());
            params.put(nameParam, st.namePattern());
        }
    }

    /**
     * Allow-listed salon {@code ORDER BY} over the lateral price aliases. Price
     * sorts use {@code pr.pmin}/{@code pr.pmax}; rating/reviews fall back to name
     * (salons carry no per-row rating in this projection — documented divergence,
     * mirrors {@link #withSalonSort}). {@code s.id} is the stable tiebreaker.
     */
    private static void appendSalonOrderBy(StringBuilder sb, SearchSort sort) {
        sb.append(switch (sort) {
            case PRICE_ASC -> "ORDER BY pr.pmin ASC NULLS LAST, s.name, s.id";
            case PRICE_DESC -> "ORDER BY pr.pmax DESC NULLS LAST, s.name, s.id";
            case RATING_DESC, REVIEWS_DESC -> "ORDER BY s.name, s.id";
        });
    }

    /**
     * Outer {@code ORDER BY} re-applied on the derived-table aliases ({@code t.*})
     * after the post-LIMIT name laterals. A {@code LEFT JOIN LATERAL} does not
     * preserve the inner {@code ORDER BY}, so the paging order is restated over the
     * already-bounded ~pageSize rows (a cheap final sort, not a candidate-wide
     * one). Mirrors {@link #appendSalonOrderBy} with {@code pr.}/{@code s.} →
     * {@code t.} — and the price columns are projected onto {@code t} so the sort
     * resolves without re-touching the price lateral.
     */
    private static void appendSalonOuterOrderBy(StringBuilder sb, SearchSort sort) {
        sb.append(switch (sort) {
            case PRICE_ASC -> "ORDER BY t.pmin ASC NULLS LAST, t.name, t.id";
            case PRICE_DESC -> "ORDER BY t.pmax DESC NULLS LAST, t.name, t.id";
            case RATING_DESC, REVIEWS_DESC -> "ORDER BY t.name, t.id";
        });
    }

    // ── parameter normalisation ───────────────────────────────────────────────

    /**
     * Belt-and-suspenders: {@link com.beautica.search.dto.MasterSearchRequest#isPriceRangeValid()}
     * {@code @AssertTrue} already validates this at binding time (before the
     * {@code @Cacheable} proxy fires), so this guard is only reached on a cache
     * miss via an uncached code path. It remains here so that if
     * {@code @Validated} is ever dropped from {@link com.beautica.search.controller.SearchController},
     * the service still rejects invalid ranges rather than silently building a
     * nonsensical SQL predicate.
     */
    private static void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BusinessException("minPrice must not exceed maxPrice");
        }
    }

    /**
     * Upper-cases the category so the bound value matches what
     * {@code EnumType.STRING} writes to {@code service_definitions.category}.
     */
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Minimum trigram-effective query length. A pg_trgm GIN index needs at least
     * one full 3-gram to serve a containment {@code ILIKE}; a 1–2 char term
     * matches no trigram and degrades into a full-table sequential scan. Terms
     * shorter than this are normalised to {@code null} (the predicate is dropped
     * and the location-scoped result set is returned) rather than running a
     * trigram-defeating {@code %ab%} scan.
     */
    private static final int MIN_QUERY_LENGTH = 3;

    /**
     * Trims the free-text query and collapses blank to {@code null} (mirrors the
     * {@code category} treatment). A trimmed term shorter than
     * {@link #MIN_QUERY_LENGTH} is also normalised to {@code null}: pg_trgm
     * cannot serve a 1–2 char containment {@code ILIKE}, so dropping the
     * predicate (returning the location-scoped set) is strictly better than a
     * full-table seq-scan on {@code %ab%}. Case-folding is unnecessary — the SQL
     * uses {@code ILIKE} (case-insensitive). The returned term is the raw user
     * text; {@link #likeContains} applies the {@code LIKE}-wildcard escaping when
     * the {@code %term%} pattern is built, so the escaping lives in one place.
     */
    private static String normalizeQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /**
     * Builds a {@code %term%} {@code LIKE}/{@code ILIKE} containment pattern with
     * the term's own {@code LIKE} metacharacters escaped, so a user-typed
     * {@code %}, {@code _} or {@code \} matches that literal character instead of
     * acting as a wildcard. Escaping uses the default backslash escape character
     * (Postgres {@code ILIKE} honours {@code \} as the escape by default), and
     * {@code \} itself is escaped first to avoid double-translation.
     */
    private static String likeContains(String term) {
        String escaped = term
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * Normalises {@code BigDecimal minRating} to scale 2, matching
     * {@code masters.avg_rating} (NUMERIC(3,2)).
     */
    private static BigDecimal normalizeRating(BigDecimal minRating) {
        if (minRating == null) {
            return null;
        }
        return minRating.setScale(RATING_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Normalises a price {@code BigDecimal} to scale 2, matching
     * {@code masters.min_effective_price} (NUMERIC(10,2)).
     *
     * <p>Without normalisation, semantically equal values such as {@code 1.0}
     * and {@code 1.00} produce distinct cache keys via their default
     * {@code toString()} representation, causing unnecessary cache misses
     * (LOW cache-key normalisation finding). The SQL parameter binding is
     * unaffected — JDBC coerces the scale when the native query executes.
     */
    private static BigDecimal normalizePrice(BigDecimal val) {
        if (val == null) {
            return null;
        }
        return val.setScale(PRICE_SCALE, java.math.RoundingMode.HALF_UP);
    }

    // ── label resolution (M2 seam, batched — §E no N+1) ───────────────────────

    /**
     * Collects the distinct discovery city/district ids from a raw result page
     * and batch-resolves their {@code name_uk} labels through the M2 seam in a
     * fixed two queries — never a per-row taxonomy lookup.
     *
     * @param rows         the raw native-query rows of the page
     * @param cityIdIdx    projection index of the discovery city id
     * @param districtIdIdx projection index of the discovery district id
     */
    private DiscoveryLabels resolveLabelsForRows(
            List<Object[]> rows, int cityIdIdx, int districtIdIdx) {
        Set<UUID> cityIds = new LinkedHashSet<>();
        Set<UUID> districtIds = new LinkedHashSet<>();
        for (Object[] row : rows) {
            if (row[cityIdIdx] != null) {
                cityIds.add((UUID) row[cityIdIdx]);
            }
            if (row[districtIdIdx] != null) {
                districtIds.add((UUID) row[districtIdIdx]);
            }
        }
        return discoveryLocationResolver.resolveLabels(cityIds, districtIds);
    }

    private static <T> Set<UUID> distinct(List<T> items, java.util.function.Function<T, UUID> extractor) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (T item : items) {
            UUID id = extractor.apply(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    // ── row mapping ──────────────────────────────────────────────────────────

    /**
     * Maps a raw native-query row to {@link MasterSearchResult}, stamping the
     * resolved locality labels from the batched M2-seam result.
     *
     * <p>16-column projection (indices 0–15, Phase 20.3):
     * {@code [master_id, first_name, last_name, avg_rating, review_count,
     * avatar_url, discovery_city_id, discovery_district_id,
     * min_effective_price, price_max, service_names, street, building_no,
     * location_note, matched_names, total_count]}.
     * The internal city/district UUIDs (6, 7) are consumed here for label
     * resolution and are NOT placed on the public DTO (§I).
     * {@code total_count} (15) is read by the caller before this method is
     * invoked and is not mapped to the DTO.
     *
     * <p>{@code street} (11) / {@code building_no} (12) / {@code location_note}
     * (13) are mapped through as-is here; the per-request auth-gate (nulling them
     * for anonymous callers) is applied in the controller AFTER the
     * {@code @Cacheable} read so the cache never leaks addresses across the
     * anon/authenticated boundary.
     */
    private static MasterSearchResult mapMasterRow(Object[] row, DiscoveryLabels labels) {
        UUID masterId = (UUID) row[0];
        String firstName = (String) row[1];
        String lastName = (String) row[2];
        Double avgRating = row[3] == null ? null : ((BigDecimal) row[3]).doubleValue();
        Integer reviewCount = row[4] == null ? null : ((Number) row[4]).intValue();
        String avatarUrl = (String) row[5];
        UUID cityId = (UUID) row[6];
        UUID districtId = (UUID) row[7];
        BigDecimal minEffectivePrice = (BigDecimal) row[8];
        BigDecimal priceMax = (BigDecimal) row[9];
        List<String> serviceNames = toServiceNames(row[SERVICE_NAMES_IDX]);
        String street = (String) row[11];
        String buildingNo = (String) row[12];
        String locationNote = (String) row[13];
        List<String> matchedServiceNames = toServiceNames(row[MATCHED_SERVICE_NAMES_IDX]);

        return new MasterSearchResult(
                masterId,
                firstName,
                lastName,
                labels.cityLabel(cityId),
                labels.districtLabel(districtId),
                avgRating,
                reviewCount,
                avatarUrl,
                minEffectivePrice,
                priceMax,
                serviceNames,
                street,
                buildingNo,
                locationNote,
                matchedServiceNames
        );
    }

    /**
     * Converts the JDBC representation of the {@code service_names} SQL array to
     * an immutable {@code List<String>}. A master with no active services
     * yields {@code null} or an empty array from Postgres — both map to an
     * empty list (never {@code null}), so the DTO field serialises as
     * {@code []}. The Postgres array slice already capped and de-duplicated the
     * names; any residual {@code null} elements are dropped defensively.
     */
    private static List<String> toServiceNames(Object raw) {
        if (raw == null) {
            return List.of();
        }
        Object[] elements;
        if (raw instanceof java.sql.Array sqlArray) {
            try {
                elements = (Object[]) sqlArray.getArray();
            } catch (java.sql.SQLException e) {
                throw new BusinessException("Failed to read service names array");
            }
        } else if (raw instanceof Object[] objectArray) {
            elements = objectArray;
        } else {
            return List.of();
        }
        List<String> names = new ArrayList<>(elements.length);
        for (Object element : elements) {
            if (element != null) {
                names.add(element.toString());
            }
        }
        return List.copyOf(names);
    }

    private static SalonSearchResult toSalonSearchResult(SalonSearchProjection proj, DiscoveryLabels labels) {
        return new SalonSearchResult(
                proj.getId(),
                proj.getName(),
                labels.cityLabel(proj.getCityId()),
                labels.districtLabel(proj.getDistrictId()),
                proj.getAvatarUrl(),
                // Phase 19.7: price band across the salon's masters' active
                // services (category-scoped when present). Both null when the
                // salon has no active, priced services — passed through as-is;
                // the backend never collapses priceMin == priceMax (mobile concern).
                proj.getPriceMin(),
                proj.getPriceMax(),
                // Capped, distinct service-name preview (array_agg in the price
                // lateral) — empty list when none, never null (mirrors master).
                toServiceNames(proj.getServiceNames()),
                // AUTH-GATED street address: always selected in SQL and cached on
                // the full object; nulled for anonymous callers in the controller
                // AFTER the @Cacheable read so the cache never leaks addresses.
                proj.getStreet(),
                proj.getBuildingNo(),
                proj.getLocationNote(),
                // Phase 20.3: no per-service filter on this (unfiltered) path, so
                // matchedServiceNames is empty — the card falls back to serviceNames.
                List.of()
        );
    }

    /**
     * Maps a raw native-query row from the per-service-filtered salon path
     * ({@link #searchSalonsWithServiceFilter}) to {@link SalonSearchResult}.
     *
     * <p>13-column projection (indices 0–12):
     * {@code [id, name, city_id, district_id, avatar_url, price_min, price_max,
     * service_names, street, building_no, location_note, matched_names,
     * total_count]}. The internal city/district UUIDs (2, 3) drive label
     * resolution and are not placed on the public DTO (§I); {@code total_count}
     * (12) is read by the caller, not mapped.
     */
    private static SalonSearchResult mapSalonRow(Object[] row, DiscoveryLabels labels) {
        UUID salonId = (UUID) row[0];
        String name = (String) row[1];
        UUID cityId = (UUID) row[SALON_CITY_ID_IDX];
        UUID districtId = (UUID) row[SALON_DISTRICT_ID_IDX];
        String avatarUrl = (String) row[4];
        BigDecimal priceMin = (BigDecimal) row[5];
        BigDecimal priceMax = (BigDecimal) row[6];
        List<String> serviceNames = toServiceNames(row[7]);
        String street = (String) row[8];
        String buildingNo = (String) row[9];
        String locationNote = (String) row[10];
        List<String> matchedServiceNames = toServiceNames(row[11]);

        return new SalonSearchResult(
                salonId,
                name,
                labels.cityLabel(cityId),
                labels.districtLabel(districtId),
                avatarUrl,
                priceMin,
                priceMax,
                serviceNames,
                street,
                buildingNo,
                locationNote,
                matchedServiceNames
        );
    }
}
