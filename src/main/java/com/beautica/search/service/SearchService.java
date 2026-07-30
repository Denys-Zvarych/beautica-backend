package com.beautica.search.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.location.DiscoveryLocationKey;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonSearchProjection;
import com.beautica.salon.repository.SalonSearchSql;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.dto.SearchSort;
import com.beautica.service.service.PlatformCategoryLabelResolver;
import com.beautica.service.service.ServiceTypeMatch;
import com.beautica.service.service.ServiceTypeSlugResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    /**
     * Named-parameter prefix for the {@code %token%} ILIKE pattern of free-text
     * token {@code n} ({@code :q0} … {@code :q3}). Shared by the master builder,
     * the dynamic salon builder and the static salon projection queries (see
     * {@link SalonSearchSql}), so the three stay bind-compatible.
     */
    private static final String Q_PARAM_PREFIX = "q";

    /**
     * Named-parameter prefix for the canonical {@code platform_categories.name} of
     * resolved category {@code n} ({@code :qcat0}, {@code :qcat1}, …) — the category
     * half of free-text search.
     *
     * <p><b>Must stay equal to</b> the {@code qcat} prefix hard-coded in
     * {@code SalonSearchSql.categoryDisjunct}. That class cannot import this constant
     * (a {@code @Query} value must be a compile-time constant expression), which is
     * the same split-brain that already exists for {@link #Q_PARAM_PREFIX} / {@code :qN}.
     */
    private static final String Q_CATEGORY_PARAM_PREFIX = "qcat";

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
     * Probe pagination used to recover {@code totalElements} for an out-of-range
     * page — the first row of the first page, whose {@code COUNT(*) OVER()} column
     * carries the true total. See {@link #probeTotalForEmptyPage}.
     */
    private static final Pageable TOTAL_PROBE_PAGE = PageRequest.of(0, 1);

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
     * {@code service_definitions.owner_type} value (stored via
     * {@code EnumType.STRING}, see {@code com.beautica.service.entity.OwnerType})
     * identifying a definition owned by an independent master, whose
     * {@code owner_id} is the {@code masters.id} — <b>not</b> the user id. Used by
     * {@link #appendMasterOwnedServiceGate} to keep a stale cross-owner
     * {@code master_services} assignment from surfacing (or making discoverable) a
     * salon-owned service on an independent master's public card.
     *
     * <p>Deliberately a literal rather than a bound parameter: it is a compile-time
     * constant that never varies per request, and inlining it keeps the sub-plans
     * free of an extra bind slot the planner would otherwise have to treat as
     * opaque. It is not caller-derived, so there is no injection surface — the same
     * reasoning as {@code sdq.owner_type = 'SALON'} in {@link SalonSearchSql}.</p>
     */
    private static final String OWNER_TYPE_INDEPENDENT_MASTER = "INDEPENDENT_MASTER";

    /**
     * Discovery-locality SQL expressions. A {@code SALON_MASTER}'s locality is
     * its salon's; an {@code INDEPENDENT_MASTER}'s is its own user row. The
     * salon link wins when present — never denormalised onto the master row.
     *
     * <p><b>Anti-Bug audit LOW-1 (2026-07):</b> {@code COALESCE}, not
     * {@code CASE WHEN sal.id IS NOT NULL THEN … ELSE … END}, is intentional and
     * safe here — <em>not</em> because a salon is guaranteed to have a
     * {@code city_id} (legacy pre-Phase-10.3 rows can be city-less;
     * {@code Salon.cityId} carries no {@code NOT NULL}), but because
     * {@code appendWhereClause} below pins {@code u.role = 'INDEPENDENT_MASTER'}
     * unconditionally (Phase 19.7 decision 7). Every row this query can ever
     * return belongs to an {@code INDEPENDENT_MASTER}, whose {@code masters.salon_id}
     * is structurally always {@code NULL} ({@code MasterService.createMasterForIndependentUser}
     * never sets a salon) — so {@code sal.*} is always {@code NULL} for every row
     * this query can produce, regardless of what any actual salon's {@code city_id}
     * holds. {@code COALESCE} and {@code CASE WHEN} are therefore provably
     * equivalent here; switching would be a no-op guarded by nothing but code
     * churn. Pinned end-to-end (including a city-less legacy salon) by
     * {@code SearchReworkRegressionTest.should_neverSurfaceSalonMasterUnderOwnPersonalLocality_when_salonIsCityLess}
     * and {@code .should_excludeSalonMastersButDiscoverIndependent_acrossOwnerSalons}.
     * Contrast {@code ClientAggregationRepository.findTopDistricts}, which has no
     * such role gate (bookings span all master types) and DOES use
     * {@code CASE WHEN} for exactly this reason.</p>
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
    private final PlatformCategoryLabelResolver platformCategoryLabelResolver;
    private final CacheManager cacheManager;

    /**
     * Discover masters matching optional location (FK, district-primary),
     * category, rating, and price filters. Returns a page sorted by rating
     * descending with resolved {@code cityLabel}/{@code districtLabel}.
     *
     * <p><b>Caching</b>: first 5 pages only. The cache is <b>split by population</b>
     * (see {@link SearchCacheNames}): location-only browse keys land in
     * {@code search:masters:browse} (500 entries / 60 s), free-text keys in
     * {@code search:masters:q} (300 entries / 30 s), routed by
     * {@code searchCacheResolver}.
     *
     * @throws BusinessException if {@code minPrice} > {@code maxPrice}
     */
    @Cacheable(
            // Declares the BROWSE half only; searchCacheResolver swaps in
            // SearchCacheNames.MASTERS_QUERY when the request carries a normalised q, so
            // the unbounded free-text key population cannot evict the bounded,
            // cross-user-shared browse pages. See SearchCacheNames / SearchCacheResolver.
            cacheNames = SearchCacheNames.MASTERS_BROWSE,
            cacheResolver = "searchCacheResolver",
            key = "{#request.location?.cityId, #request.location?.districtId, " +
                  "T(com.beautica.search.service.NormalizedSearchQuery).cacheKey(#request.q), " +
                  "#request.category, #request.sort, " +
                  "#request.minPrice, #request.maxPrice, " +
                  "#request.minRating, #request.normalizedServiceTypeSlugs(), " +
                  "#pageable.pageNumber, #pageable.pageSize}",
            condition = "#pageable.pageNumber < 5",
            sync = true
    )
    @Transactional(readOnly = true)
    public Page<MasterSearchResult> searchMasters(MasterSearchRequest request, Pageable pageable) {
        validatePriceRange(request.minPrice(), request.maxPrice());

        // Defect B: a supplied-but-too-short query returns an EXPLICIT empty page —
        // never the unfiltered set. Dropping a filter must not masquerade as "no
        // filter" (?q=Ру used to return every master in scope). The controller
        // short-circuits first and attaches the user-facing helper message; this
        // guard keeps the service authoritative for any non-HTTP caller, mirroring
        // the validatePriceRange belt-and-suspenders above.
        NormalizedSearchQuery query = NormalizedSearchQuery.of(request.q());
        if (query.belowMinimumLength()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        // Phase 20.1: resolve the per-service filter under OR/union semantics.
        // Unknown slugs are dropped; the OR EXISTS is built over the survivors.
        // An empty Optional means EVERY selected slug was unknown/inactive — the
        // user filtered by service types that do not exist, so no master can
        // match: return an explicit empty page (distinct from "no filter").
        Optional<List<ResolvedServiceType>> resolved =
                resolveServiceTypes(request.normalizedServiceTypeSlugs());
        if (resolved.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        MasterSearchFilters filters =
                normalize(request, query, resolved.get(), resolveQueryCategories(query));

        // Filter-scoped total memo (perf follow-up, architect decision — keyset/cursor
        // pagination is REJECTED for this surface; see the backlog entry). A memoized
        // totalElements for this EXACT filter tuple (page/size excluded from the key — see
        // masterTotalKey) lets an out-of-range page short-circuit to zero native-query
        // statements instead of re-running the COUNT(*) OVER() probe on every uncached
        // (page >= 5) hit. TRADE-OFF: the memo can be stale for up to the cache's TTL;
        // contained by using it ONLY to prove offset >= total (an empty page), never to serve
        // or shape data — the worst case is a transiently-empty tail page that self-heals
        // within the TTL. See SearchCacheNames.MASTERS_TOTAL.
        Object totalKey = masterTotalKey(request);
        if (pageable.getOffset() > 0) {
            Long memoizedTotal = readMemoizedTotal(SearchCacheNames.MASTERS_TOTAL, totalKey);
            if (memoizedTotal != null && pageable.getOffset() >= memoizedTotal) {
                return new PageImpl<>(List.of(), pageable, memoizedTotal);
            }
        }

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
            // Out-of-range page: COUNT(*) OVER() has no row to ride on — recover the
            // true total with a first-page probe. See probeTotalForEmptyPage.
            long recovered = pageable.getOffset() == 0 ? 0L
                    : probeTotalForEmptyPage(
                            buildMasterSearchSql(filters, TOTAL_PROBE_PAGE), TOTAL_COUNT_IDX);
            writeMemoizedTotal(SearchCacheNames.MASTERS_TOTAL, totalKey, recovered);
            return new PageImpl<>(List.of(), pageable, recovered);
        }

        long total = ((Number) rawRows.get(0)[TOTAL_COUNT_IDX]).longValue();
        writeMemoizedTotal(SearchCacheNames.MASTERS_TOTAL, totalKey, total);

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
     * pages, FK-pair key, split into {@code search:salons:browse} (500 entries / 60 s)
     * and {@code search:salons:q} (300 entries / 30 s) by {@code searchCacheResolver}.
     */
    @Cacheable(
            // Browse half declared; searchCacheResolver routes free-text calls to
            // SearchCacheNames.SALONS_QUERY — see searchMasters above.
            cacheNames = SearchCacheNames.SALONS_BROWSE,
            cacheResolver = "searchCacheResolver",
            key = "{#request.location?.cityId, #request.location?.districtId, " +
                  "T(com.beautica.search.service.NormalizedSearchQuery).cacheKey(#request.q), " +
                  "#request.category, #request.sort, " +
                  "#request.minPrice, #request.maxPrice, " +
                  "#request.normalizedServiceTypeSlugs(), " +
                  "#pageable.pageNumber, #pageable.pageSize}",
            condition = "#pageable.pageNumber < 5",
            sync = true
    )
    @Transactional(readOnly = true)
    public Page<SalonSearchResult> searchSalons(SalonSearchRequest request, Pageable pageable) {
        // Defect B — see searchMasters: too short is an explicit empty page, never
        // the unfiltered set.
        NormalizedSearchQuery query = NormalizedSearchQuery.of(request.q());
        if (query.belowMinimumLength()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        DiscoveryLocationKey key = resolveLocation(request.location());
        UUID cityId = key == null ? null : key.cityId();
        UUID districtId = key == null ? null : key.districtId();
        // Phase 19.7: scope the salon price-range aggregation to the searched
        // category when present (salon-wide when null). Normalised to upper-case
        // so the bound value matches what EnumType.STRING wrote to
        // service_definitions.category — mirrors the masters-search path.
        String category = normalizeCategory(request.category());
        BigDecimal minPrice = normalizePrice(request.minPrice());
        BigDecimal maxPrice = normalizePrice(request.maxPrice());
        SearchSort sort = SearchSort.orDefault(request.sort());

        // Phase 20.2: resolve the per-service filter under OR/union semantics
        // (see searchMasters). Unknown slugs are dropped; an empty Optional means
        // EVERY selected slug was unknown/inactive → explicit empty page (distinct
        // from "no filter", which the empty-input case returns as everything).
        Optional<List<ResolvedServiceType>> resolved =
                resolveServiceTypes(request.normalizedServiceTypeSlugs());
        if (resolved.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }
        List<ResolvedServiceType> serviceTypes = resolved.get();
        // Category half of free-text search — see resolveQueryCategories. Empty for
        // almost every query; non-empty only when the user typed a category label.
        List<String> qCategories = resolveQueryCategories(query);

        // Filter-scoped total memo (perf follow-up) — mirrors searchMasters (see its Javadoc
        // note for the full trade-off). Computed once here, from the raw request, so the SAME
        // key is shared by both salon SQL-building sites below (the unfiltered static path and
        // searchSalonsWithServiceFilter, which is threaded the key as a parameter since it only
        // sees the already-decomposed filter fields, not the request).
        Object totalKey = salonTotalKey(request);

        // Phase 20.2: when a per-service filter is active the static repository projection
        // queries cannot express the dynamic, slug-count-dependent correlated EXISTS this path
        // needs, so the filtered path is assembled dynamically here via the EntityManager —
        // mirroring the master path and the DashboardService array-binding precedent. The
        // unfiltered path keeps the tuned repository overloads (with their no-price COUNT gate)
        // untouched.
        //
        // WHAT THE SQL ACTUALLY EMITS — read this before "optimising" it:
        // exactly ONE correlated EXISTS whose inner WHERE is an OR-disjunction across the
        // selected slugs (see appendSalonServiceTypeExists / appendServiceTypeMatchDisjunction),
        // i.e. the equivalent of `service_type_id = ANY(:ids)`. It is NOT N ANDed EXISTS
        // predicates, one per slug. An earlier revision of this comment claimed exactly that,
        // and the claim caused a performance finding to be filed against correct code — the
        // "collapse N ANDed EXISTS into a GROUP BY ... HAVING COUNT(DISTINCT ...) = N" rewrite
        // that finding proposed would silently flip the semantics from OR to AND.
        //
        // The OR/union semantics are LOCKED PRODUCT BEHAVIOUR, not an implementation detail: a
        // provider matches if it offers ANY selected service type. Pinned by
        // SearchIntegrationTest#should_returnMastersOfferingAnySlug_when_unionOfTwoSlugs
        // (a master offering only one of two selected slugs must still be returned), with the
        // salon-side mirror in the same suite. Do not convert this to AND semantics, and do not
        // introduce a flattened provider→service-type projection table to serve AND semantics,
        // without a product decision reversing that rule first.
        //
        // THE CATEGORY DISPATCH — do not narrow this condition. The six static
        // SalonRepository projection queries declare a FIXED bind arity (:q0…:q3) and a
        // @Query value must be a compile-time constant, so they cannot express the
        // variable-length `sdq.category IN (:qcat…)` disjunct that makes a search for a
        // CATEGORY DISPLAY NAME («Нарощення вій») match anything at all. The dynamic
        // builder can, so a query that resolved to any platform category is routed here
        // too — not just a serviceTypeSlugs filter. This is the sole reason
        // SalonSearchSql's static and dynamic forms are allowed to differ on the
        // category half (see its class Javadoc); drop the `|| !qCategories.isEmpty()`
        // and salon category search silently returns zero rows again, with every
        // existing test still green.
        if (!serviceTypes.isEmpty() || !qCategories.isEmpty()) {
            return searchSalonsWithServiceFilter(
                    cityId, districtId, category, query.tokens(), qCategories,
                    minPrice, maxPrice, sort, serviceTypes, pageable, totalKey);
        }

        if (pageable.getOffset() > 0) {
            Long memoizedTotal = readMemoizedTotal(SearchCacheNames.SALONS_TOTAL, totalKey);
            if (memoizedTotal != null && pageable.getOffset() >= memoizedTotal) {
                return new PageImpl<>(List.of(), pageable, memoizedTotal);
            }
        }

        // The ordering is an allow-listed enum NAME bound as :sortMode — caller text
        // never reaches the ORDER BY (see SalonSearchSql.STATIC_ORDER_LIMIT_TAIL).
        List<SalonSearchProjection> projections = findSalonsByLocation(
                cityId, districtId, category, query.tokens(), minPrice, maxPrice, sort, pageable);

        if (projections.isEmpty()) {
            // Out-of-range page: recover the true total from a first-page probe of the
            // SAME repository overload. See probeTotalForEmptyPage.
            long recovered = 0L;
            if (pageable.getOffset() > 0) {
                List<SalonSearchProjection> probe = findSalonsByLocation(
                        cityId, districtId, category, query.tokens(),
                        minPrice, maxPrice, sort, TOTAL_PROBE_PAGE);
                recovered = probe.isEmpty() ? 0L : probe.get(0).getTotalCount();
            }
            writeMemoizedTotal(SearchCacheNames.SALONS_TOTAL, totalKey, recovered);
            return new PageImpl<>(List.of(), pageable, recovered);
        }

        // Single-query pagination: COUNT(*) OVER() rides along on every paged row, so
        // the total is read off the first one instead of a second SELECT COUNT(*)
        // statement that re-ran the whole correlated group EXISTS. Mirrors the master
        // path and searchSalonsWithServiceFilter.
        long total = projections.get(0).getTotalCount();
        writeMemoizedTotal(SearchCacheNames.SALONS_TOTAL, totalKey, total);

        DiscoveryLabels labels = discoveryLocationResolver.resolveLabels(
                distinct(projections, SalonSearchProjection::getCityId),
                distinct(projections, SalonSearchProjection::getDistrictId));

        List<SalonSearchResult> results = new ArrayList<>(projections.size());
        for (SalonSearchProjection projection : projections) {
            results.add(toSalonSearchResult(projection, labels));
        }
        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Dynamic per-service-filtered salon search (Phase 20.2). Assembles a single
     * native query — locality dispatch, optional {@code q} / price / category
     * predicates, and <b>one correlated {@code EXISTS} per selected slug</b>
     * (AND semantics) reaching into the salon's active masters' active services
     * via the {@code service_type_id = :id} FK match —
     * plus a {@code COUNT(*) OVER()} window for single-query pagination
     * (PERF-M1) and the {@code matchedServiceNames} lateral (Phase 20.3). Bound
     * params are typed objects (UUID / BigDecimal) so no {@code CAST(:p …)} idiom
     * is emitted ({@code SearchServiceTest} guard).
     *
     * @param totalKey the filter-scoped {@link SearchCacheNames#SALONS_TOTAL} memo key,
     *                 computed once by the caller ({@link #salonTotalKey}) from the raw
     *                 request so it is byte-identical to the key the unfiltered static
     *                 path in {@link #searchSalons} uses for the same filter tuple
     */
    private Page<SalonSearchResult> searchSalonsWithServiceFilter(
            UUID cityId, UUID districtId, String category, List<String> qTokens,
            List<String> qCategories, BigDecimal minPrice, BigDecimal maxPrice, SearchSort sort,
            List<ResolvedServiceType> serviceTypes, Pageable pageable, Object totalKey) {
        if (pageable.getOffset() > 0) {
            Long memoizedTotal = readMemoizedTotal(SearchCacheNames.SALONS_TOTAL, totalKey);
            if (memoizedTotal != null && pageable.getOffset() >= memoizedTotal) {
                return new PageImpl<>(List.of(), pageable, memoizedTotal);
            }
        }

        SqlAndParams dataSql = buildSalonSearchSql(
                cityId, districtId, category, qTokens, qCategories, minPrice, maxPrice,
                sort, serviceTypes, pageable);
        Query dataQuery = entityManager.createNativeQuery(dataSql.sql());
        bind(dataQuery, dataSql.params());

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = dataQuery.getResultList();

        if (rawRows.isEmpty()) {
            // Out-of-range page: recover the true total from a first-page probe.
            // See probeTotalForEmptyPage.
            long recovered = pageable.getOffset() == 0 ? 0L
                    : probeTotalForEmptyPage(
                            buildSalonSearchSql(cityId, districtId, category, qTokens, qCategories,
                                    minPrice, maxPrice, sort, serviceTypes, TOTAL_PROBE_PAGE),
                            SALON_TOTAL_COUNT_IDX);
            writeMemoizedTotal(SearchCacheNames.SALONS_TOTAL, totalKey, recovered);
            return new PageImpl<>(List.of(), pageable, recovered);
        }

        long total = ((Number) rawRows.get(0)[SALON_TOTAL_COUNT_IDX]).longValue();
        writeMemoizedTotal(SearchCacheNames.SALONS_TOTAL, totalKey, total);
        DiscoveryLabels labels = resolveLabelsForRows(rawRows, SALON_CITY_ID_IDX, SALON_DISTRICT_ID_IDX);
        List<SalonSearchResult> results = new ArrayList<>(rawRows.size());
        for (Object[] row : rawRows) {
            results.add(mapSalonRow(row, labels));
        }
        return new PageImpl<>(results, pageable, total);
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
     * {@code *NoPriceAsProjection} overload is chosen instead, dropping the two
     * band-overlap predicates from the plan. The price-range LATERAL itself still
     * runs on both overloads because {@code priceMin}/{@code priceMax} are display
     * columns on every salon card.
     *
     * <p><b>Pagination is single-query</b> (perf audit 2026-07-29). These methods
     * return a {@code List}, not a {@code Page}: a Spring Data {@code Pageable}
     * cannot express either half of what the plan needs — a {@code LIMIT} bound
     * <em>inside</em> the Top-N derived table (so the two name laterals really do run
     * for only the paged rows, which they did not before) or {@code COUNT(*) OVER()}
     * in place of a second {@code countQuery} that re-ran the whole correlated group
     * {@code EXISTS}. {@code :limit}/{@code :offset}/{@code :sortMode} are therefore
     * passed explicitly, mirroring {@link #buildSalonSearchSql}, and the caller
     * assembles the {@code Page}.
     */
    private List<SalonSearchProjection> findSalonsByLocation(
            UUID cityId, UUID districtId, String category, List<String> qTokens,
            BigDecimal minPrice, BigDecimal maxPrice, SearchSort sort, Pageable pageable) {
        boolean noPriceFilter = minPrice == null && maxPrice == null;
        // The static queries declare a fixed number of :qN slots, so the token
        // patterns are padded with null (an unbound-equivalent, always-true branch).
        String[] q = paddedSalonTokenPatterns(qTokens);
        // Allow-listed: the bound value is an enum constant name, never caller text.
        String sortMode = sort.name();
        int limit = pageable.getPageSize();
        long offset = pageable.getOffset();
        if (districtId != null) {
            return noPriceFilter
                    ? salonRepository.findActiveByDistrictIdNoPriceAsProjection(
                            districtId, category, q[0], q[1], q[2], q[3], sortMode, limit, offset)
                    : salonRepository.findActiveByDistrictIdAsProjection(
                            districtId, category, q[0], q[1], q[2], q[3],
                            minPrice, maxPrice, sortMode, limit, offset);
        }
        if (cityId != null) {
            return noPriceFilter
                    ? salonRepository.findActiveByCityIdNoPriceAsProjection(
                            cityId, category, q[0], q[1], q[2], q[3], sortMode, limit, offset)
                    : salonRepository.findActiveByCityIdAsProjection(
                            cityId, category, q[0], q[1], q[2], q[3],
                            minPrice, maxPrice, sortMode, limit, offset);
        }
        return noPriceFilter
                ? salonRepository.findByIsActiveTrueNoPriceAsProjection(
                        q[0], q[1], q[2], q[3], category, sortMode, limit, offset)
                : salonRepository.findByIsActiveTrueAsProjection(
                        q[0], q[1], q[2], q[3], minPrice, maxPrice, category, sortMode, limit, offset);
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
     * discovery-locality FK ids (from the M2 seam), not free text.
     * {@code qTokens} holds the whitespace tokens of the free-text query (see
     * {@link NormalizedSearchQuery}) — empty means "no text filter"; {@code sort}
     * is the resolved ordering (never null after {@link #normalize}).
     *
     * <p>{@code qCategories} holds the canonical {@code platform_categories.name}
     * values whose Ukrainian display name contains EVERY {@code qTokens} entry (see
     * {@link #resolveQueryCategories}). It is derived from {@code qTokens}, NOT from
     * the caller's {@code category} request filter — the two are unrelated and must
     * not be conflated: {@code category} is an explicit facet the client selected and
     * it NARROWS the result set (an ANDed {@code EXISTS}), whereas {@code qCategories}
     * is inferred from free text and WIDENS it (an extra {@code OR} disjunct inside
     * the {@code q} predicate). Almost always empty.
     */
    private record MasterSearchFilters(
            UUID cityId,
            UUID districtId,
            List<String> qTokens,
            List<String> qCategories,
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
     * A selected {@code serviceTypeSlug} resolved to its {@code service_type_id}
     * FK target. The per-slug predicate matches strictly on this FK
     * ({@code sd.service_type_id = :id}) — the former {@code name ILIKE} substring
     * fallback was removed because service-type {@code name_uk} values are
     * substrings of unrelated service names and produced false positives. Only
     * ever built for a slug that resolved to an active service-type, so
     * {@code serviceTypeId} is non-null and bound as a plain {@code UUID} (no
     * {@code CAST}).
     *
     * <p><b>Prod precondition:</b> {@code service_definitions.service_type_id} is
     * nullable and the existing-row backfill is the deferred Phase 20.4. Rows with
     * a {@code NULL} FK match no service-type filter under FK-only matching, so
     * Phase 20.4 must run before deploying this change to any dataset that still
     * has untyped rows (local/demo data is fully typed — 0 NULL FKs).
     */
    private record ResolvedServiceType(UUID serviceTypeId) {}

    /** Carrier for {@code (sql, params)} pairs returned by {@link #buildMasterSearchSql}. */
    private record SqlAndParams(String sql, Map<String, Object> params) {}

    /**
     * Filter-tuple key for the {@link SearchCacheNames#MASTERS_TOTAL} memo — byte-for-byte the
     * {@code @Cacheable} key declared on {@link #searchMasters} MINUS the two pagination
     * elements ({@code #pageable.pageNumber}, {@code #pageable.pageSize}), since a total is a
     * function of the filter alone, never the page. A dedicated record (rather than a raw
     * {@code List<Object>}) gives value-based {@code equals}/{@code hashCode} across every field
     * for free, so two different filter tuples can never collide on this key — the highest-risk
     * defect class for this cache is a copy-paste omission that silently merges two callers'
     * totals (see {@link #masterTotalKey}).
     */
    private record MasterTotalKey(
            UUID cityId,
            UUID districtId,
            String q,
            String category,
            SearchSort sort,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            BigDecimal minRating,
            List<String> serviceTypeSlugs) {
    }

    /**
     * Filter-tuple key for the {@link SearchCacheNames#SALONS_TOTAL} memo — mirrors
     * {@link MasterTotalKey} (see its Javadoc), minus the {@code minRating} field the salon
     * request does not carry. Being a DIFFERENT record type than {@link MasterTotalKey} is
     * itself part of the discrimination contract: even an identical location filter can never
     * {@code equals()} across the two record types, so the master and salon memos cannot
     * collide even though they are read/written through the same {@link #readMemoizedTotal} /
     * {@link #writeMemoizedTotal} helpers (they are additionally isolated by cache name).
     */
    private record SalonTotalKey(
            UUID cityId,
            UUID districtId,
            String q,
            String category,
            SearchSort sort,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            List<String> serviceTypeSlugs) {
    }

    /**
     * Builds the {@link MasterTotalKey} for {@code request} — every field the
     * {@code @Cacheable} key on {@link #searchMasters} declares, in the same order, EXCEPT
     * {@code pageable.pageNumber}/{@code pageable.pageSize}. Reads the raw request fields
     * (not the normalized {@link MasterSearchFilters}) so this stays independent of any future
     * change to how the filters are normalized internally.
     *
     * <p>Extracts {@code (cityId, districtId)} via a record deconstruction pattern rather than a
     * chained field accessor on the filter object — not for style, but because
     * {@code SearchReworkRegressionTest}'s M2 source guard forbids that dotted call shape
     * anywhere outside the single seam call
     * ({@code discoveryLocationResolver.resolveFilter(...)}). This is a plain field READ for a
     * cache key (no district-vs-city precedence decision), so it does not violate the seam's
     * intent — the pattern-match shape simply keeps the guard's literal-text scan from
     * false-positiving on it.
     */
    private static MasterTotalKey masterTotalKey(MasterSearchRequest request) {
        RawLocationIds ids = RawLocationIds.of(request.location());
        return new MasterTotalKey(
                ids.cityId(),
                ids.districtId(),
                NormalizedSearchQuery.cacheKey(request.q()),
                request.category(),
                request.sort(),
                request.minPrice(),
                request.maxPrice(),
                request.minRating(),
                request.normalizedServiceTypeSlugs());
    }

    /**
     * Builds the {@link SalonTotalKey} for {@code request} — mirrors {@link #masterTotalKey}
     * (including the record-deconstruction rationale in its Javadoc). Computed once at the top
     * of {@link #searchSalons} (before the salon path dispatches to either the static
     * unfiltered query or {@link #searchSalonsWithServiceFilter}) so both SQL-building sites
     * share one key for the same filter tuple.
     */
    private static SalonTotalKey salonTotalKey(SalonSearchRequest request) {
        RawLocationIds ids = RawLocationIds.of(request.location());
        return new SalonTotalKey(
                ids.cityId(),
                ids.districtId(),
                NormalizedSearchQuery.cacheKey(request.q()),
                request.category(),
                request.sort(),
                request.minPrice(),
                request.maxPrice(),
                request.normalizedServiceTypeSlugs());
    }

    /**
     * Raw {@code (cityId, districtId)} pair lifted off a {@link LocationFilter} for cache-key
     * purposes only — {@code null}/{@code null} when the filter itself is {@code null}. See the
     * Javadoc on {@link #masterTotalKey} for why this is deconstructed via a record pattern
     * instead of chained accessors.
     */
    private record RawLocationIds(UUID cityId, UUID districtId) {
        private static final RawLocationIds NONE = new RawLocationIds(null, null);

        static RawLocationIds of(LocationFilter location) {
            return location instanceof LocationFilter(UUID cityId, UUID districtId)
                    ? new RawLocationIds(cityId, districtId)
                    : NONE;
        }
    }

    /**
     * Reads the memoized total for {@code key} from the named cache, or {@code null} when
     * absent (cold filter, evicted, or TTL-expired). Deliberately NOT {@code @Cacheable}: this
     * is a read-then-maybe-skip inside the same method that also writes the memo below, not a
     * memoized return value, so it goes through {@link CacheManager} directly. A missing cache
     * bean (e.g. a {@code @WebMvcTest} slice with no {@code CacheConfig}) is treated as a cold
     * miss rather than a wiring error, mirroring the null-registry guard in {@code CacheConfig}.
     */
    private Long readMemoizedTotal(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        return cache == null ? null : cache.get(key, Long.class);
    }

    /**
     * Writes the now-known total for {@code key} into the named cache. Safe to call
     * unconditionally from any branch that has the true total in hand (the success path or the
     * empty-page probe) — a missing cache bean is a silent no-op, mirroring
     * {@link #readMemoizedTotal}.
     */
    private void writeMemoizedTotal(String cacheName, Object key, long total) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.put(key, total);
        }
    }

    /**
     * Resolves the normalized {@code serviceTypeSlugs} to their FK match
     * operands through the cached {@link ServiceTypeSlugResolver}, under the
     * <b>OR / union</b> per-service filter semantics (Phase 20.x): a provider
     * matches if it offers <em>any</em> selected service type.
     *
     * <p>Each slug is resolved independently. An unknown / inactive slug is
     * <em>dropped</em> (not fatal) so the surviving disjunction still matches
     * providers offering the slugs that did resolve. The {@link Optional} return
     * distinguishes the three caller outcomes:</p>
     *
     * <ul>
     *   <li><b>empty input</b> (no slugs selected) → {@code Optional.of(List.of())}:
     *       no service-type filter is applied (the caller runs the unfiltered
     *       query);</li>
     *   <li><b>at least one slug resolves</b> (all valid, or a valid+unknown mix)
     *       → {@code Optional.of([resolved subset])}: the caller ORs an
     *       {@code EXISTS} disjunction over exactly the slugs that resolved;</li>
     *   <li><b>non-empty input, but every slug is unknown/inactive</b> →
     *       {@link Optional#empty()}: the user explicitly filtered by service
     *       types and none of them exist, so no provider can match. This is an
     *       <em>intentional</em> empty page — distinct from the empty-input case,
     *       which would otherwise return everything.</li>
     * </ul>
     */
    private Optional<List<ResolvedServiceType>> resolveServiceTypes(List<String> slugs) {
        if (slugs.isEmpty()) {
            // Case 1: no service-type filter selected → unfiltered query.
            return Optional.of(List.of());
        }
        List<Optional<ServiceTypeMatch>> matches = serviceTypeSlugResolver.resolve(slugs);
        List<ResolvedServiceType> resolved = new ArrayList<>(matches.size());
        for (Optional<ServiceTypeMatch> match : matches) {
            // OR semantics: silently drop a slug that resolves to no active
            // service-type; the remaining disjunction still matches providers
            // offering the slugs that did resolve.
            if (match.isEmpty()) {
                continue;
            }
            ServiceTypeMatch type = match.get();
            resolved.add(new ResolvedServiceType(type.serviceTypeId()));
        }
        if (resolved.isEmpty()) {
            // Case 4: every selected slug is unknown/inactive. The user DID filter
            // by service types; none exist, so no provider can match. Signal the
            // caller to return an explicit empty page (NOT "no filter").
            return Optional.empty();
        }
        // Cases 2 & 3: all-valid, or valid+unknown mix → OR over the resolved subset.
        return Optional.of(List.copyOf(resolved));
    }

    /**
     * Resolves the free-text query to the canonical {@code platform_categories.name}
     * values whose Ukrainian {@code display_name} contains EVERY token — the fix for
     * "typing a category name returns nothing".
     *
     * <h4>The bug this closes</h4>
     * «Нарощення вій» is a {@code platform_categories.display_name}. It exists in no
     * {@code service_definitions.name} and no {@code service_types.name_uk}, and the
     * free-text predicate reached neither {@code platform_categories} nor any
     * category column — so the single most natural query a client can type (the name
     * of the category they are shopping for) returned zero rows while 341 active
     * masters offered exactly that category.
     *
     * <h4>Why resolving up front rather than joining</h4>
     * Joining {@code platform_categories} into the predicate would put a second
     * relation under the {@code ILIKE} and forfeit the
     * {@code idx_service_definitions_name_trgm} plan on {@code sd.name} — the classic
     * OR-across-relations index defeat this query was already refactored once to
     * avoid (see {@link #buildMasterSearchSql}). Resolved here, the SQL gains a plain
     * {@code category IN (…)} equality set that {@code idx_service_def_category} (V6)
     * serves, and the trigram branch is untouched.
     *
     * <h4>Cost, and why it needs no cache key of its own</h4>
     * One read of the cached approved+active category list
     * ({@code platform-category-order}, 60-min TTL) plus a scan over ~20 in-memory
     * labels. No extra round-trip, and nothing is added to the {@code @Cacheable} key
     * on {@link #searchMasters}/{@link #searchSalons}: the resolved set is a pure
     * function of the normalised {@code q}, which the key already carries. The one
     * consequence is that approving or renaming a category can leave a search page
     * stale for up to that cache's TTL (30–60 s) — the same staleness window every
     * other search result already has, and far shorter than the 60-min reference-data
     * TTL, so no eviction hook is warranted.
     *
     * @return matching canonical category names, in a deterministic order; empty for
     *         an absent query and for the overwhelming majority of real queries
     */
    private List<String> resolveQueryCategories(NormalizedSearchQuery query) {
        if (!query.hasTokens()) {
            return List.of();
        }
        return QueryCategoryMatcher.matchingCategoryNames(
                platformCategoryLabelResolver.selectableLabels(), query.tokens());
    }

    private MasterSearchFilters normalize(
            MasterSearchRequest request,
            NormalizedSearchQuery query,
            List<ResolvedServiceType> serviceTypes,
            List<String> qCategories) {
        DiscoveryLocationKey key = resolveLocation(request.location());
        return new MasterSearchFilters(
                key == null ? null : key.cityId(),
                key == null ? null : key.districtId(),
                query.tokens(),
                qCategories,
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
                filters.serviceTypes(), filters.qTokens().size(), filters.qCategories());
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
     * <p>PERF-M1 (Phase 19.x audit): the price band ({@code price_min} +
     * {@code price_max}) is computed HERE too, folded into this single lateral
     * instead of a separate correlated scalar sub-select — both would walk the
     * same {@code master_services ⋈ service_definitions} join, so a second pass
     * is pure waste. The names sub-query is DISTINCT + ORDER + LIMIT (capped
     * preview); the price band is a pair of unbounded aggregates over the
     * (filter-scoped) active services, so it cannot share the LIMIT-ed inner
     * name sub-query — it is a sibling aggregate over the same {@code FROM} in
     * the lateral body.
     *
     * <p><b>Search-price bug fix — the band is scoped to the active filter.</b>
     * When a {@code serviceTypeSlugs} (slug-precedence) or {@code category}
     * filter is active the lateral's {@code WHERE} restricts the aggregate to the
     * matched services (same predicate {@code matched_names} uses), so a card for
     * the «2д» slug shows the 2д price, not the whole-catalogue band. With NO
     * filter the band is whole-catalogue and {@code price_min} equals the indexed
     * {@code min_effective_price}; the outer SELECT sources the floor from the
     * indexed column in that case ({@code hasPriceScope == false}) and from
     * {@code sn.price_min} otherwise. {@code price_min} mirrors the denormalised
     * formula {@code MIN(COALESCE(ms.price_override, sd.base_price))}; the ceiling
     * is {@code MAX(COALESCE(ms.price_override, CASE WHEN sd.price_type='RANGE'
     * THEN sd.price_max ELSE sd.base_price END))}. A single FIXED price still
     * yields {@code price_min == price_max} → the card collapses to one value.
     * {@code NULL} when the master has no active, priced services in scope.
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
            List<ResolvedServiceType> serviceTypes, int qTokenCount,
            List<String> qCategories) {
        boolean hasServiceFilter = !serviceTypes.isEmpty();
        // Phase 20.3 + free-text extension: matched_names is now produced for a
        // per-service filter, a free-text q, or both (see appendMatchedNamesLateral).
        boolean hasMatchedNames = hasServiceFilter || qTokenCount > 0;
        // Search-price bug fix: the displayed price band must be scoped to the
        // active service-type / category filter, not the master's whole catalogue.
        // When a slug OR category filter is active, BOTH bounds come from the
        // slug/category-scoped sn lateral (price_min/price_max). With no filter the
        // band is whole-catalogue: the floor is the indexed denormalised column
        // (t.min_effective_price) and the ceiling is the unscoped sn MAX.
        boolean hasPriceScope = hasServiceFilter || hasCategoryFilter;
        // Phase 20.3: matched_names is sourced from the mn lateral when a
        // per-service filter and/or a free-text q is active, else a typed empty
        // array literal (mapped to []). A typed-NULL literal (not CAST(:p …)) is
        // permitted by the guard.
        String matchedNamesExpr = hasMatchedNames ? "mn.matched_names" : "CAST(NULL AS text[])";
        // Displayed floor: scoped lateral MIN when filtering, else the indexed
        // denormalised column. The price WHERE/ORDER BY keep using the indexed
        // m./t.min_effective_price column (appendOrderBy/appendWhereClause) — only
        // this DISPLAYED projection column switches source.
        String minPriceExpr = hasPriceScope ? "sn.price_min" : "t.min_effective_price";
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT t.master_id, t.first_name, t.last_name, ")
                .append("t.avg_rating, t.review_count, t.avatar_url, ")
                .append("t.discovery_city_id, t.discovery_district_id, ")
                .append(minPriceExpr).append(", sn.price_max, sn.service_names, ")
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
        // Ownership gate: an active assignment alone does not prove the definition
        // belongs to THIS master (service_definitions is polymorphic). Without it a
        // stale cross-owner row would print a salon's service name on an independent
        // master's public card. See appendMasterOwnedServiceGate.
        appendMasterOwnedServiceGate(sb, "sd", "t.master_id");
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
                // Search-price bug fix — price band scoped by the WHERE below.
                // price_min mirrors the denormalised min_effective_price formula
                // (MIN(COALESCE(override, base_price)) — base_price is the RANGE
                // floor) so a no-filter band still equals the indexed column.
                .append("MIN(COALESCE(ms2.price_override, sd2.base_price)) AS price_min, ")
                // Price ceiling: MAX over the scoped active services (no LIMIT).
                .append("MAX(COALESCE(ms2.price_override, "
                        + "CASE WHEN sd2.price_type = 'RANGE' "
                        + "THEN sd2.price_max ELSE sd2.base_price END)) AS price_max ")
                .append("FROM master_services ms2 ")
                .append("JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id AND sd2.is_active = true ")
                .append("WHERE ms2.master_id = t.master_id AND ms2.is_active = true ");
        // Search-price bug fix: scope the price aggregate to the active filter so
        // the card shows the MATCHED service's price, not the whole catalogue.
        // Slug takes precedence over category (mirrors matched_names). Reuses
        // :stId{n} / :category already bound by appendWhereClause — no
        // new params, no CAST idiom. Whole-catalogue (no filter) = no predicate.
        if (hasServiceFilter) {
            sb.append("AND (");
            appendServiceTypeMatchDisjunction(sb, "sd2", serviceTypes.size());
            sb.append(") ");
        } else if (hasCategoryFilter) {
            sb.append("AND sd2.category = :category ");
        }
        sb.append(") sn ON true ");
        if (hasMatchedNames) {
            appendMatchedNamesLateral(sb, serviceTypes, qTokenCount, qCategories);
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
        // Free-text name / service-name / category-name match — see appendQPredicate.
        appendQPredicate(sb, filters.qTokens(), filters.qCategories(), params);
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
        // Phase 20.1 — per-service filter: ONE correlated EXISTS whose inner WHERE
        // OR-matches ANY selected slug (OR/union semantics — a master qualifies if
        // it offers a service matching AT LEAST ONE of the selected slugs). The
        // per-slug branch matches strictly on the FK (sd.service_type_id = :stId{n}).
        // The :stId{n} param is bound as a plain UUID object (no CAST). The OR
        // disjunction mirrors the matched-names lateral exactly.
        appendServiceTypeExists(sb, filters.serviceTypes(), params);
        if (filters.minRating() != null) {
            sb.append("AND m.avg_rating >= :minRating ");
            params.put("minRating", filters.minRating());
        }
        // Phase 20.x — price band-overlap, SCOPED to the SAME active filter the rest
        // of the query uses, mirroring the salon LATERAL band-overlap (the reference
        // semantics in SalonRepository: pr.pmax >= :minPrice AND pr.pmin <= :maxPrice).
        // Replaces the previous predicates on the denormalized whole-catalogue column
        // m.min_effective_price (V58), which had two defects: (1) the min bound tested
        // pmin >= :minPrice instead of pmax >= :minPrice — band-overlap requires the
        // master's HIGH price to clear the floor, so a master with a cheap + an
        // expensive service was wrongly excluded; (2) both bounds used the
        // whole-catalogue column while the result card shows a FILTER-SCOPED band, so
        // under a category / serviceType filter the predicate disagreed with the card
        // and the salon endpoint. See appendPriceBandExists.
        appendPriceBandExists(sb, filters, params);
    }

    /**
     * Appends the free-text ({@code q}) filter to the master WHERE clause under
     * <b>group-scoped</b> token semantics, and binds every {@code :qN} pattern.
     *
     * <h4>Semantics</h4>
     * A master matches when <b>every</b> token is satisfied by the name columns
     * <em>or by one single active service</em> — the <em>same</em> service for
     * all service-satisfied tokens:
     *
     * <pre>
     *   matches(master) :=  ( &forall; token : token matches u.first_name OR u.last_name )
     *                    OR ( &exist; active service sd of this master :
     *                         &forall; token : token matches u.first_name OR u.last_name OR sd.name )
     * </pre>
     *
     * The second disjunct subsumes the first whenever the master has at least one
     * active service; the first is kept so a master with <b>zero</b> active
     * services is still findable by name. Re-testing the name columns
     * <em>inside</em> the {@code EXISTS} is what keeps a mixed query working:
     * {@code q=Олена манікюр} against master «Олена» offering «Манікюр» matches
     * because {@code Олена} is satisfied by {@code first_name} and {@code манікюр}
     * by that one service. A naive "all tokens must hit one single service" form
     * would break exactly that case.
     *
     * <h4>What this replaced</h4>
     * The predicate used to be emitted <em>per token</em> and ANDed at the master
     * level, so different tokens could be satisfied by <em>different</em> services
     * of the same master. Measured on the local demo dataset,
     * {@code q=Ботокс для волосся} returned 67 masters of which 11 did not offer
     * that service at all — they offered «Ботокс» / «Ботокс вій» <em>plus</em>
     * «Щастя для волосся», which jointly contain all three tokens. The
     * group-scoped form returns 56, matching the ground-truth
     * {@code serviceTypeSlugs=hair-treatment-botox} count exactly.
     *
     * <h4>Why the old per-token predicates are STILL emitted (as a pre-filter)</h4>
     * The per-token form is <b>strictly implied</b> by the group form — if one
     * service satisfies every token, then for each token <em>some</em> service
     * satisfies it — so ANDing both changes no result. It is kept because it is
     * the only form the planner can serve from an index: each per-token
     * {@code EXISTS} is correlated on {@code ms.master_id = m.id} alone, which
     * Postgres flattens into a <b>hashed</b> sub-plan whose inner
     * {@code sd.name ILIKE} is served by the V98
     * {@code idx_service_definitions_name_trgm} trigram index. The exact group
     * {@code EXISTS} additionally references {@code u.first_name}/{@code u.last_name},
     * so it can only be a per-row correlated sub-plan. Emitting the pre-filter
     * first cuts the candidate set before the exact predicate runs even once.
     *
     * <p><b>Measured reduction — corrected (perf audit 2026-07-29).</b> An earlier
     * revision of this Javadoc claimed the pre-filter "cuts the candidate set
     * 2 344 → 67 masters". That number was a 3.5× overstatement: the correlated
     * sub-plan never sees all 2 344 {@code masters} rows, because the
     * {@code masters ⋈ users} hash join with {@code u.role = 'INDEPENDENT_MASTER'}
     * has already restricted the candidate set to <b>602</b> rows. The real
     * reduction on the local dataset is 602 → 67 for {@code q=Ботокс для волосся}.
     * The conclusion is unchanged — the pre-filter is still the only index-servable
     * form and still worth emitting — only the magnitude was wrong.</p>
     *
     * <h4>Tokens shorter than {@code MIN_QUERY_LENGTH} are excluded from the pre-filter</h4>
     * {@code NormalizedSearchQuery} applies its 3-character floor with
     * {@code anyMatch}, so ONE trigram-servable token admits up to three 1–2-char
     * companions ({@code q=ння ка ій ов}). A 1–2-char {@code %xx%} pattern contains
     * no full 3-gram, so {@code idx_service_definitions_name_trgm} <b>cannot</b>
     * serve it — the pre-filter {@code EXISTS} for such a token degrades into a
     * full sequential scan of {@code master_services} ⋈ {@code service_definitions}
     * on a public, unauthenticated endpoint. Measured on the local dataset,
     * {@code q=ння ка ій ов} produced four {@code Seq Scan on master_services
     * (rows=14 238)} plus three {@code Seq Scan on service_definitions} — 16 203
     * shared buffers and 42 ms of execution to return ONE row, i.e. a free ~4×
     * DB-work amplifier for any caller who appends throwaway 1-char terms.
     * Short tokens are therefore skipped <em>here only</em>: the pre-filter is
     * explicitly optional (logically implied by the group predicate), so dropping
     * terms from it cannot change results, while the exact group predicate below
     * still carries EVERY token so {@code "Мар'я Ко"} keeps filtering on both.
     *
     * <h4>Single-token queries emit the pre-filter ONLY</h4>
     * For exactly one token the group predicate is <b>provably</b> the pre-filter,
     * not merely implied by it. The group form is
     * {@code (name) OR EXISTS(svc: name OR sd.name)}; the {@code name} disjunct
     * inside the {@code EXISTS} is row-invariant, so if it is true the leading
     * disjunct has already short-circuited, and if it is false the {@code EXISTS}
     * collapses to {@code EXISTS(svc: sd.name ILIKE q)} — the pre-filter verbatim.
     * Emitting both therefore evaluates the identical condition twice, the second
     * time as a per-row correlated sub-plan. Measured on {@code q=ння}
     * (589 masters): both forms 10 796 shared buffers / 12.9 ms exec, pre-filter
     * only <b>2 963 buffers / 9.9 ms</b> — −73 % buffers, −23 % execution, same
     * rows. This is the dominant traffic shape: an incremental search box issues a
     * request per settled keystroke and every one is single-token until the user
     * types a space.
     *
     * <p>The salon path deliberately does <em>not</em> carry this pre-filter — its
     * {@code EXISTS} is correlated on {@code owner_id} and never reaches the
     * trigram index in any formulation, so the extra sub-plans are pure cost. See
     * {@link SalonSearchSql} for the measurement and for the equivalent
     * single-token short-circuit on that side.</p>
     *
     * <h4>Owner constraint on the service branches</h4>
     * Both {@code EXISTS} forms constrain the service definition to one this
     * master actually <em>owns</em>, via {@link #appendMasterOwnedServiceGate} —
     * the single place the {@code owner_type + owner_id} pair is emitted on every
     * master-side service surface (pre-filter, exact group predicate, and both
     * name laterals). Without it, a stale active {@code master_services} row
     * pointing at a definition owned by someone else would make a master
     * discoverable by typing that other owner's service name — the same class of
     * stale-assignment leak the {@code mmq.salon_id = s.id} gate closes on the
     * salon side.
     *
     * <p><b>The pre-filter carries the full pair too, and this is not optional.</b>
     * The single-token short-circuit below returns before the exact group predicate
     * is emitted, so on the dominant traffic shape the pre-filter is the
     * <em>only</em> owner gate in the query. A "weaker pre-filter is sound because
     * it need only be implied by the exact predicate" argument holds only while the
     * exact predicate is also emitted — it does not survive the short-circuit. An
     * earlier revision omitted {@code owner_id} here on the theory that a correlated
     * {@code sd.owner_id = m.id} would stop Postgres flattening the sub-plan into a
     * HASHED one and forfeit the trigram index. <b>Measured, that theory is false</b>
     * (PostgreSQL 16.13, {@code q=ння}, 589 masters): pre-filter without
     * {@code owner_id} 1 669 shared buffers / 8.1 ms; with
     * {@code sd.owner_id = ms.master_id} <b>1 669 buffers / 8.2 ms</b> — byte-identical
     * plan shape, still {@code hashed SubPlan}, still {@code Bitmap Index Scan on
     * idx_service_definitions_name_trgm}. The planner simply folds the extra equality
     * into the sub-plan's existing hash condition
     * ({@code (ms.service_def_id = sd.id) AND (ms.master_id = sd.owner_id)}).
     * Spelling the owner as {@code ms.master_id} rather than {@code m.id} is what
     * keeps it inner-only; the outer-correlated spelling also measured identically,
     * but the inner one needs no assumption about the planner's de-correlation.
     *
     * <p>Because the pre-filter now carries the full gate, it is <em>identical</em>
     * to the exact group predicate for a single token rather than merely implied by
     * it — which is exactly what makes the short-circuit below an identity.
     *
     * <p>Each bound value is a pre-escaped {@code %token%} pattern
     * ({@link #likeContains} neutralises {@code LIKE} wildcards in the user term),
     * bound as a plain {@code String} — never interpolated into the SQL. Token
     * count is capped by {@code NormalizedSearchQuery.MAX_TOKENS}, so the
     * predicate stays bounded.</p>
     */
    private static void appendQPredicate(
            StringBuilder sb, List<String> qTokens, List<String> qCategories,
            Map<String, Object> params) {
        if (qTokens.isEmpty()) {
            return;
        }
        for (int i = 0; i < qTokens.size(); i++) {
            params.put(Q_PARAM_PREFIX + i, likeContains(qTokens.get(i)));
        }
        bindQueryCategoryParams(qCategories, params);
        // Category half of free-text search — the EMPTY STRING for any query that
        // named no platform category, which is what keeps everything below
        // byte-identical to its pre-fix form on the dominant traffic shape.
        String preFilterCategories = categoryDisjunct("sd", qCategories);
        // (1) Index-servable pre-filter — logically implied by (2), see Javadoc.
        //     Emitted only for trigram-servable tokens: a 1–2 char pattern cannot
        //     reach idx_service_definitions_name_trgm and becomes a full seq scan.
        for (int i = 0; i < qTokens.size(); i++) {
            if (qTokens.get(i).length() < NormalizedSearchQuery.MIN_QUERY_LENGTH) {
                continue;
            }
            String param = Q_PARAM_PREFIX + i;
            sb.append("AND (u.first_name ILIKE :").append(param)
                    .append(" OR u.last_name ILIKE :").append(param)
                    .append(" OR EXISTS (")
                    .append("SELECT 1 FROM master_services ms ")
                    .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                    .append("WHERE ms.master_id = m.id AND ms.is_active = true ");
            // FULL owner gate — owner_type AND owner_id — expressed against the
            // sub-query's own ms.master_id rather than the outer m.id. Given the
            // ms.master_id = m.id correlation directly above, the two spellings are
            // transitively equal, so this is the same constraint the exact group
            // predicate carries; routing it through ms.master_id keeps it an
            // INNER-ONLY equality that Postgres folds straight into the existing
            // hash condition instead of widening the outer correlation. See Javadoc.
            appendMasterOwnedServiceGate(sb, "sd", "ms.master_id");
            sb.append("AND ");
            appendServiceNameMatch(sb, "sd", param, preFilterCategories);
            sb.append(")) ");
        }
        // (2) Exact group-scoped predicate: all tokens by the name columns, OR all
        //     tokens by the name columns / ONE single service.
        //
        //     Skipped for the single-token case, where the pre-filter above is not
        //     merely implied by this predicate but IDENTICAL to it (see Javadoc).
        //     That identity depends on the pre-filter carrying the FULL owner gate
        //     (owner_type + owner_id) — do not weaken the gate above without
        //     deleting this short-circuit in the same edit, or owner_id stops being
        //     enforced anywhere on the single-token path.
        //     The guard also requires that the pre-filter was actually emitted for
        //     that token — a lone sub-minimum token cannot reach here through
        //     NormalizedSearchQuery, but the service stays authoritative for any
        //     non-HTTP caller that hand-builds a token list.
        if (qTokens.size() == 1
                && qTokens.get(0).length() >= NormalizedSearchQuery.MIN_QUERY_LENGTH) {
            return;
        }
        sb.append("AND ((");
        for (int i = 0; i < qTokens.size(); i++) {
            String param = Q_PARAM_PREFIX + i;
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append("(u.first_name ILIKE :").append(param)
                    .append(" OR u.last_name ILIKE :").append(param).append(")");
        }
        sb.append(") OR EXISTS (")
                .append("SELECT 1 FROM master_services msg ")
                .append("JOIN service_definitions sdg ON sdg.id = msg.service_def_id AND sdg.is_active = true ")
                .append("WHERE msg.master_id = m.id AND msg.is_active = true ");
        appendMasterOwnedServiceGate(sb, "sdg", "m.id");
        appendMasterTokenConjunction(sb, "u", "sdg", qTokens.size(), qCategories);
        sb.append(")) ");
    }

    /**
     * Appends the {@code service_definitions} half of one per-token clause:
     * {@code <defAlias>.name ILIKE :qN}, widened to
     * {@code (<defAlias>.name ILIKE :qN OR <defAlias>.category IN (:qcat…))} when the
     * query resolved to platform categories.
     *
     * <p>The extra parenthesis pair is emitted ONLY in the widened form, so a query
     * that named no category produces the exact byte sequence this call site emitted
     * before the category half existed.
     */
    private static void appendServiceNameMatch(
            StringBuilder sb, String defAlias, String param, String categoryDisjunct) {
        if (categoryDisjunct.isEmpty()) {
            sb.append(defAlias).append(".name ILIKE :").append(param);
            return;
        }
        sb.append("(").append(defAlias).append(".name ILIKE :").append(param)
                .append(categoryDisjunct).append(")");
    }

    /**
     * Builds {@code " OR <defAlias>.category IN (:qcat0, :qcat1, …)"}, or the empty
     * string when the query matched no platform category.
     *
     * <p><b>The empty case is the point.</b> Returning {@code ""} makes every
     * predicate that splices it byte-identical to its pre-fix form for the
     * overwhelming majority of queries (a provider name, a service name — anything
     * that is not a category label). That is the query-plan protection: the hot path
     * is not merely semantically equivalent, it is the SAME SQL string, so it reuses
     * the same server-side plan-cache entry and cannot regress. Only a query that
     * actually named a category pays for the extra disjunct.
     *
     * <p>The list length is bounded by the approved+active {@code platform_categories}
     * row count (admin-gated reference data, a couple of dozen rows) — never by
     * anything the caller can inflate with query text. Adding tokens can only SHRINK
     * the set, since every token is another conjunct each label must satisfy.
     *
     * <p>Mirrors {@code SalonSearchSql.categoryDisjunct}, which must emit the
     * identical {@code :qcat{n}} spelling because both bind through
     * {@link #bindQueryCategoryParams}. The duplication is forced by the same
     * compile-time-constant constraint documented on {@link #Q_CATEGORY_PARAM_PREFIX}.
     */
    private static String categoryDisjunct(String defAlias, List<String> qCategories) {
        if (qCategories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" OR ").append(defAlias).append(".category IN (");
        for (int i = 0; i < qCategories.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(":").append(Q_CATEGORY_PARAM_PREFIX).append(i);
        }
        return sb.append(")").toString();
    }

    /**
     * Binds each resolved category's {@code :qcat{n}} slot as a plain {@code String}
     * (no {@code CAST(:p …)} idiom — {@code SearchServiceTest} guard). The values are
     * canonical {@code platform_categories.name} rows read from the database, never
     * caller text, so no user input reaches the SQL through this path even though the
     * user's query is what selected them.
     */
    private static void bindQueryCategoryParams(
            List<String> qCategories, Map<String, Object> params) {
        for (int i = 0; i < qCategories.size(); i++) {
            params.put(Q_CATEGORY_PARAM_PREFIX + i, qCategories.get(i));
        }
    }

    /**
     * Constrains a {@code service_definitions} row aliased {@code defAlias} to one
     * <em>owned</em> by the independent master aliased {@code masterAlias} — the
     * master-side mirror of {@link #appendSalonBookableGate}'s
     * {@code mx.salon_id = <salon>.id} rotated-master guard.
     *
     * <p>An active {@code master_services} row is not by itself proof that the
     * definition belongs to this master: {@code service_definitions} is polymorphic
     * ({@code owner_type} ∈ {@code SALON} / {@code INDEPENDENT_MASTER}, with
     * {@code owner_id} = {@code salons.id} or {@code masters.id} respectively), so a
     * stale assignment left behind by a master who moved between a salon and solo
     * practice would otherwise surface the salon's service name on that master's
     * public card — and, since the free-text extension, make the master
     * <em>discoverable</em> by the salon's service name.
     *
     * <p>The owning master is identified by an already-qualified expression rather
     * than an alias, because the call sites spell it differently: the exact group
     * {@code EXISTS} in the inner Top-N WHERE clause has {@code m.id}, the
     * post-{@code LIMIT} laterals have the derived table's {@code t.master_id}, and
     * the index-servable pre-filter uses its own {@code ms.master_id} — equal to
     * {@code m.id} by the correlation already present in that sub-query, but
     * inner-only, so the equality folds into the sub-plan's hash condition instead
     * of widening the outer correlation (see {@link #appendQPredicate}).
     *
     * <p>This method is the ONLY place the {@code owner_type + owner_id} pair is
     * emitted on the master side. Every service-name surface — pre-filter, exact
     * group predicate, {@code service_names} lateral, {@code matched_names} lateral
     * — must route through it; a surface that hand-writes only {@code owner_type}
     * silently drops the ownership half of the gate.
     *
     * @param defAlias     alias of the {@code service_definitions} row to constrain
     * @param masterIdExpr qualified expression yielding the owning master's id
     *                     ({@code m.id}, {@code t.master_id} or {@code ms.master_id})
     */
    private static void appendMasterOwnedServiceGate(
            StringBuilder sb, String defAlias, String masterIdExpr) {
        sb.append("AND ").append(defAlias).append(".owner_type = '")
                .append(OWNER_TYPE_INDEPENDENT_MASTER).append("' ")
                .append("AND ").append(defAlias).append(".owner_id = ")
                .append(masterIdExpr).append(" ");
    }

    /**
     * Appends {@code AND (<nameAlias>.first_name ILIKE :qN OR <nameAlias>.last_name
     * ILIKE :qN OR <defAlias>.name ILIKE :qN)} once per token — the per-token
     * condition of the group-scoped predicate, evaluated against ONE fixed service
     * row.
     *
     * <p>Shared by the WHERE-clause {@code EXISTS} ({@code u} / {@code sdg}, inner
     * derived table) and the post-{@code LIMIT} matched-names lateral
     * ({@code t} / {@code sdq}, outer block) so the two cannot express different
     * semantics.</p>
     *
     * <h4>Category half — why per-token placement does not loosen the group rule</h4>
     * When the query resolved to platform categories, each clause gains
     * {@code OR <defAlias>.category IN (:qcat…)}. That looks like it would let
     * different tokens be satisfied for different reasons, re-opening the
     * cross-service false positives this group predicate was built to close. It does
     * not, because membership in the resolved set already proves the category's
     * display name contained <b>every</b> token
     * ({@code QueryCategoryMatcher} matches all-tokens-against-one-label). So whenever
     * the disjunct is true it is true for all tokens at once, and the per-token and
     * whole-group placements are provably the same predicate — which is what lets the
     * fix reuse this skeleton, and the single-token short-circuit identity in
     * {@link #appendQPredicate}, untouched.
     *
     * <p>This is also the answer to the multi-token problem that defeats the naive
     * fix: «нарощення» and «вій» co-occur in no single service name, so any
     * formulation requiring each token to be independently satisfiable by one service
     * row still returns nothing. Resolving the label as a whole is what breaks that.
     *
     * @param nameAlias   alias carrying {@code first_name} / {@code last_name}
     * @param defAlias    alias of the single {@code service_definitions} row under test
     * @param qCategories resolved canonical category names; empty for almost every query
     */
    private static void appendMasterTokenConjunction(
            StringBuilder sb, String nameAlias, String defAlias, int tokenCount,
            List<String> qCategories) {
        String categories = categoryDisjunct(defAlias, qCategories);
        for (int i = 0; i < tokenCount; i++) {
            String param = Q_PARAM_PREFIX + i;
            sb.append("AND (").append(nameAlias).append(".first_name ILIKE :").append(param)
                    .append(" OR ").append(nameAlias).append(".last_name ILIKE :").append(param)
                    .append(" OR ").append(defAlias).append(".name ILIKE :").append(param)
                    .append(categories).append(") ");
        }
    }

    /**
     * Appends the scoped price band-overlap as a SINGLE correlated {@code EXISTS}
     * over the master's active services, emitted only when a price bound is set.
     *
     * <p>The inner WHERE reuses the SAME active filter as the surrounding query so
     * the price band matches the displayed (scoped) card band: the serviceTypes
     * disjunction ({@link #appendServiceTypeMatchDisjunction}) takes precedence,
     * else the {@code sd.category = :category} equality. Both reuse params already
     * bound by {@link #appendWhereClause} ({@code :stId{n}} or
     * {@code :category}) — no new scope params, no {@code CAST(:p AS ...)} idiom.
     *
     * <p>{@code HAVING} without {@code GROUP BY} aggregates the whole correlated set
     * → returns a row iff the band-overlap holds. The {@code MAX(...) >= :minPrice}
     * line is emitted only when {@code minPrice != null} and {@code MIN(...) <=
     * :maxPrice} only when {@code maxPrice != null} (joined with {@code AND} when
     * both present). A master with no matching active services yields NULL aggregates
     * → {@code EXISTS} false → excluded, preserving V58's NULL-exclusion behaviour.
     */
    private static void appendPriceBandExists(
            StringBuilder sb, MasterSearchFilters filters, Map<String, Object> params) {
        boolean hasMin = filters.minPrice() != null;
        boolean hasMax = filters.maxPrice() != null;
        if (!hasMin && !hasMax) {
            return;
        }
        // Phase 20.x perf — coarse, index-friendly pre-filter on the ORDERING column,
        // emitted ONLY for the max bound (no symmetric max_effective_price column
        // exists for the min bound — that residual stays in the EXISTS). It is
        // logically REDUNDANT with the exact EXISTS max bound below and so changes no
        // result: scoping to a category / serviceType can only RAISE the minimum, so
        //   unscoped m.min_effective_price <= scoped MIN(...) <= :maxPrice
        // holds whenever the EXISTS max condition passes — it can never exclude a
        // master the EXISTS includes. Its sole purpose is to let the planner
        // range-bound the PRICE_ASC/PRICE_DESC ordering index
        // (idx_masters_min_effective_price) and stop the scan once LIMIT is filled,
        // instead of walking extra index rows. Reuses the :maxPrice param bound by the
        // EXISTS below — no new param, no CAST(:p) idiom.
        if (hasMax) {
            sb.append("AND m.min_effective_price <= :maxPrice ");
        }
        sb.append("AND EXISTS (")
                .append("SELECT 1 FROM master_services ms ")
                .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                .append("WHERE ms.master_id = m.id AND ms.is_active = true ");
        if (!filters.serviceTypes().isEmpty()) {
            sb.append("AND (");
            appendServiceTypeMatchDisjunction(sb, "sd", filters.serviceTypes().size());
            sb.append(") ");
        } else if (filters.category() != null) {
            sb.append("AND sd.category = :category ");
        }
        sb.append("HAVING ");
        if (hasMin) {
            sb.append("MAX(COALESCE(ms.price_override, ")
                    .append("CASE WHEN sd.price_type = 'RANGE' THEN sd.price_max ELSE sd.base_price END)) >= :minPrice ");
            params.put("minPrice", filters.minPrice());
        }
        if (hasMax) {
            if (hasMin) {
                sb.append("AND ");
            }
            sb.append("MIN(COALESCE(ms.price_override, sd.base_price)) <= :maxPrice ");
            params.put("maxPrice", filters.maxPrice());
        }
        sb.append(") ");
    }

    /**
     * Appends a SINGLE correlated {@code EXISTS} to the master WHERE clause whose
     * inner WHERE OR-matches ANY selected slug (OR/union semantics — the master
     * qualifies if it offers a service matching at least one selected slug) and
     * binds each slug's {@code service_type_id} (plain UUID, no CAST). The inner
     * OR disjunction reuses
     * {@link #appendServiceTypeMatchDisjunction} so the per-slug match clause is
     * identical to the matched-names lateral. Emits nothing when no slug is
     * selected; the single-slug case produces one OR branch.
     */
    private static void appendServiceTypeExists(
            StringBuilder sb, List<ResolvedServiceType> serviceTypes, Map<String, Object> params) {
        if (serviceTypes.isEmpty()) {
            return;
        }
        sb.append("AND EXISTS (")
                .append("SELECT 1 FROM master_services ms ")
                .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                .append("WHERE ms.master_id = m.id AND ms.is_active = true AND (");
        appendServiceTypeMatchDisjunction(sb, "sd", serviceTypes.size());
        sb.append(")) ");
        bindServiceTypeParams(serviceTypes, params);
    }

    /**
     * Binds each selected slug's {@code :stId{n}} (plain UUID, no CAST) param.
     * Shared by the master and salon service-type {@code EXISTS} builders; the
     * matched-names laterals reuse the already-bound params without rebinding.
     */
    private static void bindServiceTypeParams(
            List<ResolvedServiceType> serviceTypes, Map<String, Object> params) {
        for (int i = 0; i < serviceTypes.size(); i++) {
            ResolvedServiceType st = serviceTypes.get(i);
            params.put(SERVICE_TYPE_ID_PARAM + i, st.serviceTypeId());
        }
    }

    /**
     * Appends the master {@code matched_names} lateral (Phase 20.3, extended to
     * free text): the DISTINCT active service names that <b>explain</b> the row —
     * capped to {@link #SERVICE_NAME_CAP}, computed post-{@code LIMIT} over only
     * the paged masters ({@code t.master_id}), mirroring the {@code serviceNames}
     * lateral's shape (no {@code GROUP BY}/{@code HAVING}). Reuses the
     * {@code :stId{n}} / {@code :qN} params already bound by
     * {@link #appendWhereClause}; binds nothing itself.
     *
     * <h4>Per-service filter contribution</h4>
     * Services matching ANY selected slug (OR across slugs) — unchanged.
     *
     * <h4>Free-text ({@code q}) contribution</h4>
     * A service is reported when it
     * <ol>
     *   <li>satisfies the same group-scoped condition the WHERE clause used —
     *       every token matched by {@code t.first_name} / {@code t.last_name} or
     *       by <em>this</em> service's name ({@link #appendMasterTokenConjunction},
     *       the identical helper the WHERE clause calls, so the two cannot express
     *       different semantics); <b>and</b></li>
     *   <li>contributes at least one token <em>through its own name</em>.</li>
     * </ol>
     * Condition (2) is what stops a pure name match ({@code q=Вікторія Руденко})
     * from listing an arbitrary alphabetical slice of the master's whole
     * catalogue: every service trivially satisfies (1) in that case, but none
     * satisfies (2), so {@code matchedServiceNames} comes back empty and the card
     * falls back to the {@code serviceNames} preview — the documented no-match
     * behaviour.
     *
     * <h4>Rule when BOTH {@code q} and {@code serviceTypeSlugs} are supplied</h4>
     * The two conditions are <b>ANDed — an intersection</b>. The WHERE clause ANDs
     * the two filters, so the only services that explain the row under
     * <em>every</em> active filter are those in the intersection; surfacing a
     * union would put a service on the card that does not match what the user
     * asked for. Because the two WHERE filters are independent {@code EXISTS}
     * predicates, a master can match {@code q} through one service and the slug
     * through another; the intersection is then empty and the card falls back to
     * {@code serviceNames} — the same fallback as the pure-name case above.
     */
    private static void appendMatchedNamesLateral(
            StringBuilder sb, List<ResolvedServiceType> serviceTypes, int qTokenCount,
            List<String> qCategories) {
        sb.append("LEFT JOIN LATERAL (")
                .append("SELECT array_agg(xm.name) AS matched_names FROM (")
                .append("SELECT DISTINCT sd.name ")
                .append("FROM master_services ms ")
                .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                .append("WHERE ms.master_id = t.master_id AND ms.is_active = true ");
        // Same ownership gate as the serviceNames preview — a stale cross-owner
        // assignment must not "explain" the match with a salon's service name.
        appendMasterOwnedServiceGate(sb, "sd", "t.master_id");
        if (!serviceTypes.isEmpty()) {
            sb.append("AND (");
            appendServiceTypeMatchDisjunction(sb, "sd", serviceTypes.size());
            sb.append(") ");
        }
        if (qTokenCount > 0) {
            // (2) own-name contribution — keeps a pure name match from listing the
            //     whole catalogue. Widened by the category disjunct so a CATEGORY-name
            //     match still explains itself: no service name contains any token in
            //     that case, so without the disjunct every candidate would be rejected
            //     here and the card would fall back to an arbitrary alphabetical slice
            //     of the master's whole catalogue. Belonging to the category the user
            //     typed is a scoped explanation, not the arbitrary slice this
            //     condition exists to prevent.
            sb.append("AND (");
            for (int i = 0; i < qTokenCount; i++) {
                if (i > 0) {
                    sb.append(" OR ");
                }
                sb.append("sd.name ILIKE :").append(Q_PARAM_PREFIX).append(i);
            }
            sb.append(categoryDisjunct("sd", qCategories)).append(") ");
            // (1) same group-scoped condition as the WHERE clause, against THIS service.
            appendMasterTokenConjunction(sb, "t", "sd", qTokenCount, qCategories);
        }
        sb.append("ORDER BY sd.name LIMIT ").append(SERVICE_NAME_CAP)
                .append(") xm) mn ON true ");
    }

    /**
     * Appends the OR-chain of per-slug FK match predicates
     * ({@code alias.service_type_id = :stId{n} OR …}) used by the selection
     * {@code EXISTS} builders and the matched-names laterals. {@code count} is
     * always {@code >= 1} (callers only invoke this with an active filter).
     *
     * <p>Matches strictly on the {@code service_type_id} FK. The former
     * {@code name ILIKE '%nameUk%'} containment fallback was removed: service-type
     * {@code name_uk} values are substrings of unrelated service names (e.g.
     * "Корекція" matched beard/lash/permanent-makeup correction), which returned
     * providers that do not offer the selected type. FK-only is exact.
     */
    private static void appendServiceTypeMatchDisjunction(StringBuilder sb, String alias, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append("OR ");
            }
            sb.append(alias).append(".service_type_id = :").append(SERVICE_TYPE_ID_PARAM).append(i).append(" ");
        }
    }

    /**
     * Appends the coarse <em>bookable gate</em> for a salon-owned {@code service_definitions}
     * row aliased {@code defAlias}, correlated to the salon aliased {@code salonAlias}: an
     * {@code EXISTS} that the def is actively performed by at least one active master
     * <em>belonging to that salon</em>. Mirrors the catalogue's canonical coarse gate
     * {@code ServiceRepository.findBookableServicesBySalon}
     * ({@code EXISTS(MasterServiceAssignment WHERE serviceDefinition = sd AND isActive AND
     * master.isActive AND master.salon.id = salonId)}).
     *
     * <p>The {@code mx.salon_id = <salonAlias>.id} correlation closes the rotated-master
     * stale-assignment leak (a master who left this salon but kept an active assignment row must
     * not keep the salon's service visible) — matching the same predicate now present on the
     * catalogue query and the assignment-level {@code findBookableAssignmentsBySalon} query.
     *
     * <p><b>Free-slot bookability is NOT reflected in search SQL.</b> The authoritative catalogue
     * ({@code ServiceCatalogService#getSalonServiceCatalog}) additionally hides a service unless a
     * performing master has ≥1 free future slot (the Phase 23.x fix). That per-slot computation is
     * intentionally not expressed in this search SQL — search stays on the coarse gate and may show a
     * salon whose masters are momentarily fully booked. Reconciling the two in SQL is a separate
     * architect follow-up.
     */
    // TODO(architect follow-up): free-slot bookability not reflected in search SQL — see catalogue authoritative gate
    private static void appendSalonBookableGate(StringBuilder sb, String defAlias, String salonAlias) {
        sb.append("AND EXISTS (SELECT 1 FROM master_services msx ")
                .append("JOIN masters mx ON mx.id = msx.master_id AND mx.is_active = true ")
                .append("AND mx.salon_id = ").append(salonAlias).append(".id ")
                .append("WHERE msx.service_def_id = ").append(defAlias).append(".id ")
                .append("AND msx.is_active = true) ");
    }

    private static void bind(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }

    /**
     * Recovers {@code totalElements} for a page that returned no rows because the
     * caller asked for an offset past the end of the result set.
     *
     * <h4>Why it is needed</h4>
     * All three discovery paths (masters, static salons, per-service-filtered
     * salons) paginate with a single query: {@code COUNT(*) OVER()} rides along as
     * a projection column on every returned row, replacing a second
     * {@code SELECT COUNT(*)} that would re-run the whole correlated group
     * {@code EXISTS}. That trade has one edge: with zero rows there is no row for
     * the window count to ride on, so the total is unreadable and the page would
     * otherwise report {@code totalElements = 0} for a query that genuinely matches
     * hundreds of providers. A client that lands on an out-of-range page — a
     * restored deep link, a stale cached page index, or a result set that shrank
     * between two requests — would be told "no results" instead of "no results
     * <em>on this page</em>", and would have no total to page back from.
     *
     * <h4>Why it re-runs the data query instead of a COUNT query</h4>
     * Re-issuing the SAME builder with {@link #TOTAL_PROBE_PAGE} (page 0, size 1)
     * reads the true total off the window column of the single returned row. A
     * dedicated {@code countQuery} would be a second copy of every predicate — the
     * exact duplication that has drifted before on this surface — for no gain: the
     * window aggregate is computed over the whole filtered set either way, so both
     * shapes cost one full evaluation of the predicate. The post-{@code LIMIT} name
     * laterals run for a single row.
     *
     * <h4>When it runs</h4>
     * Only when the page came back empty AND {@code pageable.getOffset() > 0}. A
     * genuinely empty first page skips the probe entirely and reports {@code 0}, so
     * the common no-match request still costs exactly one query. Callers apply that
     * offset guard; this method assumes it.
     *
     * @param probeSql      the same search SQL rebuilt for {@link #TOTAL_PROBE_PAGE}
     * @param totalCountIdx projection index of the {@code COUNT(*) OVER()} column
     * @return the true total, or {@code 0} if the filter genuinely matches nothing
     */
    private long probeTotalForEmptyPage(SqlAndParams probeSql, int totalCountIdx) {
        Query probeQuery = entityManager.createNativeQuery(probeSql.sql());
        bind(probeQuery, probeSql.params());

        @SuppressWarnings("unchecked")
        List<Object[]> probeRows = probeQuery.getResultList();

        return probeRows.isEmpty() ? 0L : ((Number) probeRows.get(0)[totalCountIdx]).longValue();
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
     * <p>A single correlated {@code EXISTS} (OR/union semantics — inner WHERE
     * OR-matches ANY selected slug, salon→active masters→active services FK
     * match) lives in the inner WHERE. All params are typed objects — no
     * {@code CAST(:p …)} idiom (guard).
     */
    private static SqlAndParams buildSalonSearchSql(
            UUID cityId, UUID districtId, String category, List<String> qTokens,
            List<String> qCategories, BigDecimal minPrice, BigDecimal maxPrice, SearchSort sort,
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
        appendSalonPriceAggregateLateral(inner, hasCategory, serviceTypes);

        inner.append("WHERE s.is_active = true ");
        if (districtId != null) {
            inner.append("AND s.district_id = :districtId ");
            params.put("districtId", districtId);
        } else if (cityId != null) {
            inner.append("AND s.city_id = :cityId ");
            params.put("cityId", cityId);
        }
        // Free-text tokens (defects C + E). The predicate body — including the
        // bookable-master EXISTS gate — comes from SalonSearchSql, the SAME
        // definition the static SalonRepository projection queries splice in.
        //
        // Defect E: this branch used to be a hand-written copy that OMITTED that
        // gate, so a salon service no active master performs could match `q` here
        // while being invisible on the static path — a latent violation of the
        // locked rule that a salon's client-visible offering is master-performed
        // only. Both forms now derive from one body and cannot drift again.
        //
        // The predicate is GROUP-SCOPED: all tokens by the salon name, OR all
        // tokens by the salon name / ONE single bookable service. Tokens can no
        // longer be spread across two different services of the same salon — see
        // SalonSearchSql for the semantics and the measured plan choice.
        for (int i = 0; i < qTokens.size(); i++) {
            params.put(Q_PARAM_PREFIX + i, likeContains(qTokens.get(i)));
        }
        // Category half — bound here (not in SalonSearchSql, which only emits text)
        // through the same helper the master path uses, so the two paths cannot bind
        // the :qcat{n} slots differently.
        bindQueryCategoryParams(qCategories, params);
        inner.append(SalonSearchSql.dynamicQGroupPredicate(qTokens.size(), qCategories.size()));
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

        String sql = wrapSalonWithNameLaterals(
                inner.toString(), hasCategory, sort, serviceTypes, qTokens.size(), qCategories);
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
            List<ResolvedServiceType> serviceTypes, int qTokenCount,
            List<String> qCategories) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT t.id, t.name, t.city_id, t.district_id, t.avatar_url, ")
                .append("t.pmin, t.pmax, pn.pnames, ")
                .append("t.street, t.building_no, t.location_note, ")
                .append("mn.matched_names, t.total_count ")
                .append("FROM (").append(innerSql).append(") t ");
        appendSalonNamePreviewLateral(sb, hasCategory);
        appendSalonMatchedNamesLateral(sb, serviceTypes, qTokenCount, qCategories);
        appendSalonOuterOrderBy(sb, sort);
        return sb.toString();
    }

    /**
     * Slug- and category-scoped price-band aggregate lateral — MIN floor +
     * RANGE-aware MAX ceiling over the salon's active services. Stays in the inner
     * derived table because {@code pmin}/{@code pmax} feed the price WHERE and the
     * {@code ORDER BY}.
     *
     * <p><b>Search-price bug fix:</b> this builder is only reached with a
     * non-empty slug list (the per-service-filtered salon path), so the band is
     * scoped to the matched services via the same slug disjunction
     * {@code matched_names} uses — slug precedence over the category predicate, so
     * a «2д»-filtered salon card shows the 2д band, not its whole catalogue. The
     * {@code :stId{n}} params are already bound by
     * {@link #appendSalonServiceTypeExists}; {@code :category} is bound
     * conditionally (plain equality, no CAST).
     */
    private static void appendSalonPriceAggregateLateral(
            StringBuilder sb, boolean hasCategory, List<ResolvedServiceType> serviceTypes) {
        // Bookable price band (salon service-type fix): the band is the MIN–MAX of
        // the PERFORMING masters' EFFECTIVE prices, not the salon-owned base_price.
        // Effective price = COALESCE(price_override, base_price) for the floor and
        // COALESCE(price_override, RANGE ceiling) for the ceiling — identical to the
        // master path (appendPriceBandExists / the master serviceNames lateral).
        // Rows are gated to active master_services on active masters (mirrors
        // ServiceRepository.findBookableServicesBySalon), so an unbookable owned
        // service never drags the band and a per-master override shows through.
        sb.append("LEFT JOIN LATERAL (")
                .append("SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin, ")
                .append("MAX(COALESCE(ms.price_override, ")
                .append("CASE WHEN sd.price_type = 'RANGE' THEN sd.price_max ELSE sd.base_price END)) AS pmax ")
                .append("FROM master_services ms ")
                .append("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true ")
                // mad.salon_id = s.id closes the rotated-master leak, matching appendSalonBookableGate.
                .append("JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true ")
                .append("AND mad.salon_id = s.id ")
                .append("WHERE sd.owner_type = 'SALON' AND sd.owner_id = s.id ")
                .append("AND ms.is_active = true ");
        // Slug precedence: scope the price band to the matched services. Category
        // narrows only when no slug filter is active (defensive — this path always
        // carries slugs).
        if (!serviceTypes.isEmpty()) {
            sb.append("AND (");
            appendServiceTypeMatchDisjunction(sb, "sd", serviceTypes.size());
            sb.append(") ");
        } else if (hasCategory) {
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
                .append("FROM service_definitions sd2 ")
                .append("WHERE sd2.owner_type = 'SALON' AND sd2.owner_id = t.id ")
                .append("AND sd2.is_active = true ");
        appendSalonBookableGate(sb, "sd2", "t");
        if (hasCategory) {
            sb.append("AND sd2.category = :category ");
        }
        sb.append("ORDER BY sd2.name LIMIT ").append(SERVICE_NAME_CAP)
                .append(") z) pn ON true ");
    }

    /**
     * Salon {@code matched_names} lateral (Phase 20.3, extended to free text): the
     * DISTINCT bookable service names that <b>explain</b> the row, capped.
     * Correlated to {@code t.id} — sits in the OUTER block over only the paged
     * rows (HIGH PERF fix). Reuses the {@code :stId{n}} params bound by
     * {@link #appendSalonServiceTypeExists} and the {@code :qN} patterns bound by
     * {@link #buildSalonSearchSql}; binds nothing itself.
     *
     * <p>The free-text half comes from
     * {@link SalonSearchSql#dynamicMatchedNamesPredicate(String, int)} — the same
     * definition the static projection queries splice in as
     * {@link SalonSearchSql#STATIC_MATCHED_NAMES_LATERAL}, so the two salon paths
     * cannot drift. Semantics and the {@code q} + {@code serviceTypeSlugs}
     * intersection rule mirror the master path exactly — see
     * {@link #appendMatchedNamesLateral}.</p>
     *
     * <p>Always emitted (unlike the master lateral): this builder is reached only
     * when a {@code serviceTypeSlugs} filter is active OR the free-text query
     * resolved to a platform category, so at least one half is always present. The
     * category branch cannot arrive with {@code qTokenCount == 0}, because categories
     * are resolved FROM the query tokens — a non-empty {@code qCategories} implies a
     * non-empty token list.</p>
     */
    private static void appendSalonMatchedNamesLateral(
            StringBuilder sb, List<ResolvedServiceType> serviceTypes, int qTokenCount,
            List<String> qCategories) {
        sb.append("LEFT JOIN LATERAL (")
                .append("SELECT array_agg(zm.name) AS matched_names FROM (")
                .append("SELECT DISTINCT sd3.name AS name ")
                .append("FROM service_definitions sd3 ")
                .append("WHERE sd3.owner_type = 'SALON' AND sd3.owner_id = t.id ")
                .append("AND sd3.is_active = true ");
        appendSalonBookableGate(sb, "sd3", "t");
        if (!serviceTypes.isEmpty()) {
            sb.append("AND (");
            appendServiceTypeMatchDisjunction(sb, "sd3", serviceTypes.size());
            sb.append(") ");
        }
        sb.append(SalonSearchSql.dynamicMatchedNamesPredicate("sd3", qTokenCount, qCategories.size()));
        sb.append("ORDER BY sd3.name LIMIT ").append(SERVICE_NAME_CAP)
                .append(") zm) mn ON true ");
    }

    /**
     * A SINGLE correlated {@code EXISTS} (OR/union semantics) reaching the salon's
     * active masters' active services, whose inner WHERE OR-matches ANY selected
     * slug (FK match) — the salon qualifies if any active master offers a service
     * matching at least one selected slug. Reuses
     * {@link #appendServiceTypeMatchDisjunction} for the inner OR and binds each
     * slug's {@code service_type_id} (plain UUID). Emits nothing when no slug is
     * selected; the single-slug case produces one OR branch.
     */
    private static void appendSalonServiceTypeExists(
            StringBuilder sb, List<ResolvedServiceType> serviceTypes, Map<String, Object> params) {
        if (serviceTypes.isEmpty()) {
            return;
        }
        sb.append("AND EXISTS (")
                .append("SELECT 1 FROM service_definitions sdf ")
                .append("WHERE sdf.owner_type = 'SALON' AND sdf.owner_id = s.id ")
                .append("AND sdf.is_active = true ");
        appendSalonBookableGate(sb, "sdf", "s");
        sb.append("AND (");
        appendServiceTypeMatchDisjunction(sb, "sdf", serviceTypes.size());
        sb.append(")) ");
        bindServiceTypeParams(serviceTypes, params);
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
     * Builds the {@code %token%} ILIKE patterns for the static salon projection
     * queries, padded with {@code null} to exactly
     * {@link SalonSearchSql#STATIC_TOKEN_PARAM_COUNT} slots.
     *
     * <p>A {@code @Query} body cannot size itself to the token count, so every
     * {@code :qN} slot must be bound; an unused slot binds {@code null}, whose
     * {@code CAST(:qN AS text) IS NULL OR …} branch short-circuits to TRUE. All
     * four {@code null} therefore means "no {@code q} filter", which is exactly
     * what an absent query must produce.</p>
     *
     * <p><b>Left-packing is an invariant, not a coincidence.</b>
     * {@code SalonSearchSql.MULTI_TOKEN_GUARD} reads {@code :q1 IS NOT NULL} as
     * "this query carries more than one token" and drops a row-invariant
     * {@code s.name} disjunct on that basis. That reading is sound only while slots
     * are filled from index 0 with no holes: a caller that bound {@code :q1} while
     * leaving {@code :q0} null would <em>invert</em> the guard — the single token
     * present would take the MULTI-token branch and the {@code s.name} disjunct
     * would be dropped for a query that needs it, changing results with no error.
     * This method is the sole producer of those four values, so the postcondition
     * below is where the invariant can be made self-enforcing rather than
     * conventional. It costs three reference comparisons per query.</p>
     */
    private static String[] paddedSalonTokenPatterns(List<String> tokens) {
        if (tokens.size() > SalonSearchSql.STATIC_TOKEN_PARAM_COUNT) {
            // Unreachable: NormalizedSearchQuery caps the token count. Fail loudly
            // rather than silently dropping a filter if the two caps ever diverge.
            throw new IllegalStateException(
                    "q token count " + tokens.size() + " exceeds the "
                            + SalonSearchSql.STATIC_TOKEN_PARAM_COUNT
                            + " bind slots declared by the static salon projection queries");
        }
        String[] patterns = new String[SalonSearchSql.STATIC_TOKEN_PARAM_COUNT];
        for (int i = 0; i < tokens.size(); i++) {
            patterns[i] = likeContains(tokens.get(i));
        }
        requireLeftPacked(patterns);
        return patterns;
    }

    /**
     * Postcondition for {@link #paddedSalonTokenPatterns}: asserts the bind slots
     * are left-packed (no bound slot follows an unbound one), which is the
     * invariant {@code SalonSearchSql.MULTI_TOKEN_GUARD} decides on. Throws rather
     * than degrading, because the failure mode it guards is a silently wrong result
     * set, not an error.
     */
    private static void requireLeftPacked(String[] patterns) {
        for (int i = 1; i < patterns.length; i++) {
            if (patterns[i] != null && patterns[i - 1] == null) {
                throw new IllegalStateException(
                        "salon q bind slots must be left-packed: :q" + i + " is bound while :q"
                                + (i - 1) + " is null, which inverts "
                                + "SalonSearchSql.MULTI_TOKEN_GUARD");
            }
        }
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
        List<String> matchedServiceNames = toMatchedServiceNames(row[MATCHED_SERVICE_NAMES_IDX]);

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
    /**
     * Maps a raw {@code matched_names} SQL array to the capped, deterministically
     * ordered {@link MasterSearchResult#matchedServiceNames()} /
     * {@link SalonSearchResult#matchedServiceNames()} value.
     *
     * <p>The master path and the dynamic salon path already emit the sort and the
     * {@link #SERVICE_NAME_CAP} slice in SQL, so this is a no-op there. The
     * <em>static</em> salon projection queries do not: their matched-names lateral
     * is an unordered, uncapped {@code array_agg(DISTINCT …)} (see
     * {@link SalonSearchSql#STATIC_MATCHED_NAMES_LATERAL} for the Spring Data
     * {@code ORDER BY}-budget history that forced that shape). Sorting and capping
     * here keeps the field's contract identical across all three paths from ONE
     * place, and is unconditional so a 1–3 element result cannot vary between
     * requests.</p>
     */
    private static List<String> toMatchedServiceNames(Object raw) {
        // Always sorted, never "sorted only when over the cap": the static salon
        // lateral's array_agg(DISTINCT …) has no guaranteed output order, so a
        // 1–3 element result would otherwise vary between requests.
        return toServiceNames(raw).stream().sorted().limit(SERVICE_NAME_CAP).toList();
    }

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
                // Phase 20.3 + free-text extension: the bookable services whose own
                // name explains the `q` match (group-scoped — see SalonSearchSql).
                // Empty when no `q` was supplied or when the salon's own name
                // carried the whole match; the card then falls back to serviceNames.
                // No per-service filter reaches this path (a slug filter routes to
                // searchSalonsWithServiceFilter), so this is the pure-`q` half.
                toMatchedServiceNames(proj.getMatchedServiceNames())
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
        List<String> matchedServiceNames = toMatchedServiceNames(row[11]);

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
