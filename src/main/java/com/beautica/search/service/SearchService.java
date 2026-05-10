package com.beautica.search.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only search service for master and salon discovery.
 *
 * <p>Master search uses native SQL because the projection joins three tables
 * ({@code masters}, {@code users}, {@code service_definitions} via
 * {@code master_services}) and applies an aggregate filter on
 * {@code MIN(COALESCE(price_override, base_price))}, which JPQL cannot express
 * cleanly while keeping the {@code GROUP BY} → {@code HAVING} pipeline visible.
 * Salon search is plain JPQL on {@link SalonRepository#findByFilter}.
 *
 * <h3>SQL is built dynamically per request</h3>
 * The native SQL is assembled by {@link #buildMasterSearchSql} at request time
 * rather than held as a static constant with {@code (:p IS NULL OR col = :p)}
 * placeholders. Two reasons:
 *
 * <ol>
 *   <li><b>Index pushdown</b>. The earlier {@code CAST(:city AS VARCHAR) IS NULL OR
 *       u.city = CAST(:city AS VARCHAR)} form (a workaround for Postgres'
 *       inability to infer null parameter types in OR predicates over native
 *       queries) defeats index pushdown — the planner cannot see that the
 *       parameter is actually constant within the query and emits a sequential
 *       scan. Dropping the parameter entirely when the value is null restores
 *       the planner's ability to use {@code idx_users_active_city_region}.</li>
 *   <li><b>JOIN avoidance</b>. The {@code master_services} / {@code service_definitions}
 *       LEFT JOINs are only useful when the caller filters by price or category.
 *       For the common "all masters in city X" query, building without those joins
 *       shaves a two-table fan-out from every row of the result set.</li>
 * </ol>
 *
 * <h3>Why pagination needs a HAVING-aware count</h3>
 * The price filter is applied in {@code HAVING}, not {@code WHERE}, because the
 * effective price is an aggregate. A naive {@code SELECT COUNT(DISTINCT m.id)}
 * without {@code HAVING} would over-count, producing phantom pages on the mobile
 * client (e.g. last page renders empty). The count query therefore wraps the
 * same {@code GROUP BY ... HAVING ...} in a subquery — but only when a price
 * filter is actually present. The no-price-filter case uses a flat
 * {@code COUNT(DISTINCT m.id)}, avoiding a useless wrapper.
 *
 * <h3>Carry-over Phase 6.2 LOWs fixed here</h3>
 * <ul>
 *   <li>{@code minRating}: {@code Double} → {@code BigDecimal} with scale 2 before
 *       binding, to avoid float drift against {@code NUMERIC(3,2)}.</li>
 *   <li>{@code category}: upper-cased at the service boundary (matches the
 *       {@code ServiceCategory} enum stored as VARCHAR via {@code EnumType.STRING}),
 *       so the plain B-tree {@code idx_service_def_category} stays usable. Using
 *       {@code LOWER(sd.category) = LOWER(:category)} would bypass it.</li>
 *   <li>Cross-field {@code minPrice} ≤ {@code maxPrice} validation in the service
 *       layer — fail-fast prevents wasting a DB round-trip on a tautologically-
 *       empty query.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    /** Scale of {@code masters.avg_rating} (NUMERIC(3,2)) — matches column precision. */
    private static final int RATING_SCALE = 2;

    /**
     * EntityManager is field-injected via {@link PersistenceContext} rather than
     * constructor-injected: Spring intercepts this annotation specifically to
     * supply a transaction-aware shared proxy. Constructor-injecting the raw
     * bean would yield an EntityManager without the per-call delegation logic.
     * This is the documented Spring exception to the constructor-injection rule.
     */
    @PersistenceContext
    private EntityManager entityManager;

    private final SalonRepository salonRepository;

    /**
     * Discover masters matching optional location, category, rating, and price
     * filters. Returns a page sorted by rating descending.
     *
     * <p><b>Caching</b>: first 5 pages are cached for 60 seconds. Discovery
     * results aggregate across many entities, so a write-path
     * {@code @CacheEvict} would need to fan out across every cached query
     * containing the touched row — a key-explosion problem we sidestep with a
     * short TTL. Pages 5+ are intentionally not cached: they are cold-path
     * exploration, not the hot list.
     *
     * @throws BusinessException if {@code minPrice} > {@code maxPrice}
     */
    @Cacheable(
            value = "search:masters",
            key = "#request.toString() + ':' + #pageable.pageNumber + ':' + #pageable.pageSize",
            condition = "#pageable.pageNumber < 5"
    )
    @Transactional(readOnly = true)
    public Page<MasterSearchResult> searchMasters(MasterSearchRequest request, Pageable pageable) {
        validatePriceRange(request.minPrice(), request.maxPrice());

        MasterSearchFilters filters = normalize(request);

        SqlAndParams dataSql = buildMasterSearchSql(filters, pageable, false);
        Query dataQuery = entityManager.createNativeQuery(dataSql.sql());
        bind(dataQuery, dataSql.params());

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = dataQuery.getResultList();
        List<MasterSearchResult> results = new ArrayList<>(rawRows.size());
        for (Object[] row : rawRows) {
            results.add(mapMasterRow(row));
        }

        SqlAndParams countSql = buildMasterSearchSql(filters, pageable, true);
        Query countQuery = entityManager.createNativeQuery(countSql.sql());
        bind(countQuery, countSql.params());
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Discover salons matching optional city/region. Delegates filtering to the
     * repository's JPQL query and maps each entity to a public-facing DTO so the
     * JPA entity never escapes the service layer.
     *
     * <p><b>Caching</b>: same trade-off as {@link #searchMasters} — first 5
     * pages, 60-second TTL, no explicit eviction on salon writes.
     */
    @Cacheable(
            value = "search:salons",
            key = "T(java.util.Objects).toString(#city) + '|' + T(java.util.Objects).toString(#region) "
                    + "+ ':' + #pageable.pageNumber + ':' + #pageable.pageSize",
            condition = "#pageable.pageNumber < 5"
    )
    @Transactional(readOnly = true)
    public Page<SalonSearchResult> searchSalons(String city, String region, Pageable pageable) {
        Page<Salon> page = salonRepository.findByFilter(nullIfBlank(city), nullIfBlank(region), pageable);
        return page.map(this::toSalonSearchResult);
    }

    // ── SQL builder ──────────────────────────────────────────────────────────

    /**
     * Normalised filter bag — eliminates the duplicate normalisation paths the
     * old {@code bindFilterParams} variants carried.
     */
    private record MasterSearchFilters(
            String city,
            String region,
            String category,
            BigDecimal minRating,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {
        boolean hasPriceFilter() {
            return minPrice != null || maxPrice != null;
        }

        boolean needsServiceJoin() {
            return hasPriceFilter() || category != null;
        }
    }

    /** Carrier for {@code (sql, params)} pairs returned by {@link #buildMasterSearchSql}. */
    private record SqlAndParams(String sql, Map<String, Object> params) {}

    private static MasterSearchFilters normalize(MasterSearchRequest request) {
        return new MasterSearchFilters(
                nullIfBlank(request.city()),
                nullIfBlank(request.region()),
                normalizeCategory(request.category()),
                normalizeRating(request.minRating()),
                request.minPrice(),
                request.maxPrice()
        );
    }

    /**
     * Builds the master-search SQL and the matching parameter bag.
     *
     * <p>The shape of the produced SQL is:
     * <ul>
     *   <li>{@code isCountQuery=false} → {@code SELECT m.id, ... [, MIN(...)]
     *       FROM masters m JOIN users u [LEFT JOIN ms LEFT JOIN sd] WHERE ...
     *       [GROUP BY ... [HAVING ...]] ORDER BY ... LIMIT/OFFSET}.</li>
     *   <li>{@code isCountQuery=true} + price filter → wrapped subquery so
     *       {@code COUNT(*)} respects the {@code HAVING} predicate.</li>
     *   <li>{@code isCountQuery=true} + no price filter → flat
     *       {@code SELECT COUNT(DISTINCT m.id)} (no GROUP BY needed because the
     *       JOIN against {@code master_services} can multiply rows when the
     *       category filter is active — DISTINCT collapses them).</li>
     * </ul>
     *
     * <p>Optional predicates are appended only when the value is present,
     * eliminating the {@code (:p IS NULL OR col = :p)} idiom that previously
     * required {@code CAST(:p AS VARCHAR)} workarounds and defeated index
     * pushdown.
     */
    private static SqlAndParams buildMasterSearchSql(
            MasterSearchFilters filters,
            Pageable pageable,
            boolean isCountQuery
    ) {
        boolean needsServiceJoin = filters.needsServiceJoin();
        boolean hasPriceFilter = filters.hasPriceFilter();
        boolean wrapSubquery = isCountQuery && hasPriceFilter;

        StringBuilder sb = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();

        if (wrapSubquery) {
            // Subquery wrapper: the HAVING price filter must be applied before
            // we count, so we count rows of the (GROUP BY m.id, HAVING ...)
            // pre-filtered set.
            sb.append("SELECT COUNT(*) FROM (SELECT m.id ");
            appendFromClause(sb, needsServiceJoin);
        } else if (isCountQuery) {
            // Flat count — DISTINCT collapses the JOIN-induced row multiplication
            // when the category filter is active (a master may match the category
            // through multiple service rows; we count masters, not rows).
            sb.append("SELECT COUNT(DISTINCT m.id) ");
            appendFromClause(sb, needsServiceJoin);
        } else {
            appendDataSelect(sb, needsServiceJoin);
            appendFromClause(sb, needsServiceJoin);
        }

        appendWhereClause(sb, filters, params);

        // GROUP BY: required on the data query whenever we have a service-join
        // (the projection includes MIN()), and required on the count subquery
        // when a HAVING is about to follow.
        boolean needsGroupBy =
                (!isCountQuery && needsServiceJoin)
                        || (wrapSubquery && needsServiceJoin);
        if (needsGroupBy) {
            if (isCountQuery) {
                sb.append("GROUP BY m.id ");
            } else {
                sb.append("GROUP BY m.id, m.user_id, u.first_name, u.last_name, u.city, ")
                        .append("m.avg_rating, m.review_count ");
            }
        }

        appendHavingClause(sb, filters, params, needsServiceJoin);

        if (wrapSubquery) {
            sb.append(") AS filtered");
        }

        if (!isCountQuery) {
            // Deterministic order: rating DESC, then id as the tie-breaker so
            // the (avg_rating, id) covering index in V36 backs the sort.
            sb.append("ORDER BY m.avg_rating DESC NULLS LAST, m.id ");
            sb.append("LIMIT :limit OFFSET :offset");
            params.put("limit", pageable.getPageSize());
            params.put("offset", (long) pageable.getPageNumber() * pageable.getPageSize());
        }

        return new SqlAndParams(sb.toString(), params);
    }

    private static void appendDataSelect(StringBuilder sb, boolean needsServiceJoin) {
        sb.append("SELECT m.id AS master_id, m.user_id AS user_id, ")
                .append("u.first_name AS first_name, u.last_name AS last_name, u.city AS city, ")
                .append("m.avg_rating AS avg_rating, m.review_count AS review_count, ");
        // Avatar column does not yet exist on users/masters (no migration added it
        // as of V35/V36). Emit NULL so the projection still maps cleanly until a
        // future phase introduces user/master avatar storage. See docs/backlog.md.
        sb.append("CAST(NULL AS TEXT) AS avatar_url, ");
        if (needsServiceJoin) {
            sb.append("MIN(COALESCE(ms.price_override, sd.base_price)) AS min_effective_price ");
        } else {
            // Maintain a stable 9-column projection for mapMasterRow regardless of
            // whether the service-join branch is selected.
            sb.append("CAST(NULL AS NUMERIC) AS min_effective_price ");
        }
    }

    private static void appendFromClause(StringBuilder sb, boolean needsServiceJoin) {
        sb.append("FROM masters m JOIN users u ON u.id = m.user_id ");
        if (needsServiceJoin) {
            sb.append("LEFT JOIN master_services ms ON ms.master_id = m.id AND ms.is_active = true ");
            sb.append("LEFT JOIN service_definitions sd ON sd.id = ms.service_def_id ");
        }
    }

    private static void appendWhereClause(
            StringBuilder sb,
            MasterSearchFilters filters,
            Map<String, Object> params
    ) {
        sb.append("WHERE m.is_active = true AND u.is_active = true ");
        if (filters.city() != null) {
            sb.append("AND u.city = :city ");
            params.put("city", filters.city());
        }
        if (filters.region() != null) {
            sb.append("AND u.region = :region ");
            params.put("region", filters.region());
        }
        if (filters.category() != null) {
            sb.append("AND sd.category = :category ");
            params.put("category", filters.category());
        }
        if (filters.minRating() != null) {
            sb.append("AND m.avg_rating >= :minRating ");
            params.put("minRating", filters.minRating());
        }
    }

    private static void appendHavingClause(
            StringBuilder sb,
            MasterSearchFilters filters,
            Map<String, Object> params,
            boolean needsServiceJoin
    ) {
        if (!filters.hasPriceFilter() || !needsServiceJoin) {
            return;
        }
        sb.append("HAVING ");
        boolean first = true;
        if (filters.minPrice() != null) {
            sb.append("MIN(COALESCE(ms.price_override, sd.base_price)) >= :minPrice ");
            params.put("minPrice", filters.minPrice());
            first = false;
        }
        if (filters.maxPrice() != null) {
            if (!first) {
                sb.append("AND ");
            }
            sb.append("MIN(COALESCE(ms.price_override, sd.base_price)) <= :maxPrice ");
            params.put("maxPrice", filters.maxPrice());
        }
    }

    private static void bind(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }

    // ── parameter normalisation ───────────────────────────────────────────────

    private static void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BusinessException("minPrice must not exceed maxPrice");
        }
    }

    /**
     * Upper-cases the category so the bound value matches what
     * {@code EnumType.STRING} writes to {@code service_definitions.category}.
     * Preserving the exact column value lets the plain B-tree
     * {@code idx_service_def_category} (V6) serve the lookup; switching to
     * {@code LOWER(sd.category) = LOWER(:category)} in SQL would force a
     * sequential scan or require a separate functional index.
     */
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Converts the {@code Double minRating} on the request DTO to a
     * {@code BigDecimal} with scale 2, matching {@code masters.avg_rating}
     * (NUMERIC(3,2)). Direct {@code Double} → {@code NUMERIC} comparison
     * exhibits float drift on boundary values (4.10 vs 4.0999...).
     */
    private static BigDecimal normalizeRating(Double minRating) {
        if (minRating == null) {
            return null;
        }
        return BigDecimal.valueOf(minRating).setScale(RATING_SCALE, RoundingMode.HALF_UP);
    }

    private static String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // ── row mapping ──────────────────────────────────────────────────────────

    /**
     * Maps a raw native-query row to {@link MasterSearchResult}.
     *
     * <p>Defensive coercion: JDBC may surface integer columns as {@code Integer}
     * or {@code Long} depending on the driver and column type, and {@code NUMERIC}
     * always arrives as {@code BigDecimal}. We coerce via {@code Number} casts
     * rather than direct casts to avoid {@code ClassCastException} drift if a
     * future Postgres/JDBC bump changes the surface type.
     *
     * <p>The projection is a stable 9-column shape regardless of the join
     * variant — column 8 is {@code avatar_url} (currently always NULL) and
     * column 9 is {@code min_effective_price} (NULL when no service-join was
     * needed, populated from the {@code MIN(COALESCE(...))} aggregate otherwise).
     */
    private static MasterSearchResult mapMasterRow(Object[] row) {
        UUID masterId = (UUID) row[0];
        UUID userId = (UUID) row[1];
        String firstName = (String) row[2];
        String lastName = (String) row[3];
        String city = (String) row[4];
        Double avgRating = row[5] == null ? null : ((BigDecimal) row[5]).doubleValue();
        Integer reviewCount = row[6] == null ? null : ((Number) row[6]).intValue();
        String avatarUrl = (String) row[7];
        BigDecimal minEffectivePrice = (BigDecimal) row[8];

        return new MasterSearchResult(
                masterId,
                userId,
                firstName,
                lastName,
                city,
                avgRating,
                reviewCount,
                avatarUrl,
                minEffectivePrice
        );
    }

    private SalonSearchResult toSalonSearchResult(Salon salon) {
        return new SalonSearchResult(
                salon.getId(),
                salon.getName(),
                salon.getCity(),
                salon.getRegion(),
                salon.getAvatarUrl()
        );
    }
}
