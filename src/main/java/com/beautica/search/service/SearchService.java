package com.beautica.search.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.location.DiscoveryLocationKey;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only search service for master and salon discovery.
 *
 * <p>Master search uses native SQL because the projection joins three tables
 * ({@code masters}, {@code users}, {@code service_definitions} via
 * {@code master_services}) and applies an aggregate filter on
 * {@code MIN(COALESCE(price_override, base_price))}, which JPQL cannot express
 * cleanly while keeping the {@code GROUP BY} → {@code HAVING} pipeline visible.
 * Salon search is plain JPQL on {@link SalonRepository#findByLocation}.
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
 * <p><b>{@code SALON_ADMIN} exclusion:</b> the master query carries an
 * explicit {@code AND u.role <> 'SALON_ADMIN'} predicate on the
 * {@code masters m JOIN users u} join so an admin account can never surface in
 * public master discovery, independent of any future data shape.</p>
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
 *   <li><b>JOIN avoidance</b>. The {@code master_services} /
 *       {@code service_definitions} LEFT JOINs are only useful when the caller
 *       filters by price or category. For the common "all masters in district
 *       X" query, building without those joins shaves a two-table fan-out from
 *       every row.</li>
 * </ol>
 *
 * <h3>Why pagination needs a HAVING-aware count</h3>
 * The price filter is applied in {@code HAVING}, not {@code WHERE}, because the
 * effective price is an aggregate. A naive {@code SELECT COUNT(DISTINCT m.id)}
 * without {@code HAVING} would over-count, producing phantom pages. The count
 * query therefore wraps the same {@code GROUP BY ... HAVING ...} in a subquery
 * — but only when a price filter is actually present.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    /** Scale of {@code masters.avg_rating} (NUMERIC(3,2)) — matches column precision. */
    private static final int RATING_SCALE = 2;

    /** Role value (stored via {@code EnumType.STRING}) excluded from master discovery. */
    private static final String ROLE_SALON_ADMIN = "SALON_ADMIN";

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
                  "#request.category, #request.minPrice, #request.maxPrice, " +
                  "#request.minRating, #pageable.pageNumber, #pageable.pageSize}",
            condition = "#pageable.pageNumber < 5",
            sync = true
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

        DiscoveryLabels labels = resolveLabelsForRows(rawRows, 6, 7);
        List<MasterSearchResult> results = new ArrayList<>(rawRows.size());
        for (Object[] row : rawRows) {
            results.add(mapMasterRow(row, labels));
        }

        SqlAndParams countSql = buildMasterSearchSql(filters, pageable, true);
        Query countQuery = entityManager.createNativeQuery(countSql.sql());
        bind(countQuery, countSql.params());
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Discover salons matching the optional FK location filter
     * (district-primary). Delegates filtering to the repository's JPQL query
     * and maps each entity to a public-facing DTO with resolved locality
     * labels so the JPA entity never escapes the service layer.
     *
     * <p><b>Caching</b>: same trade-off as {@link #searchMasters} — first 5
     * pages, 60-second TTL, FK-pair key.
     */
    @Cacheable(
            value = "search:salons",
            key = "{#request.location?.cityId, #request.location?.districtId, " +
                  "#pageable.pageNumber, #pageable.pageSize}",
            condition = "#pageable.pageNumber < 5",
            sync = true
    )
    @Transactional(readOnly = true)
    public Page<SalonSearchResult> searchSalons(SalonSearchRequest request, Pageable pageable) {
        DiscoveryLocationKey key = resolveLocation(request.location());
        UUID cityId = key == null ? null : key.cityId();
        UUID districtId = key == null ? null : key.districtId();

        Page<Salon> page = salonRepository.findByLocation(cityId, districtId, pageable);

        List<Salon> salons = page.getContent();
        DiscoveryLabels labels = discoveryLocationResolver.resolveLabels(
                distinct(salons, Salon::getCityId),
                distinct(salons, Salon::getDistrictId));

        return page.map(salon -> toSalonSearchResult(salon, labels));
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
     */
    private record MasterSearchFilters(
            UUID cityId,
            UUID districtId,
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

        boolean hasDistrictFilter() {
            return districtId != null;
        }

        boolean hasCityFilter() {
            return districtId == null && cityId != null;
        }
    }

    /** Carrier for {@code (sql, params)} pairs returned by {@link #buildMasterSearchSql}. */
    private record SqlAndParams(String sql, Map<String, Object> params) {}

    private MasterSearchFilters normalize(MasterSearchRequest request) {
        DiscoveryLocationKey key = resolveLocation(request.location());
        return new MasterSearchFilters(
                key == null ? null : key.cityId(),
                key == null ? null : key.districtId(),
                normalizeCategory(request.category()),
                normalizeRating(request.minRating()),
                request.minPrice(),
                request.maxPrice()
        );
    }

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
            sb.append("SELECT COUNT(*) FROM (SELECT m.id ");
            appendFromClause(sb, needsServiceJoin);
        } else if (isCountQuery) {
            sb.append("SELECT COUNT(DISTINCT m.id) ");
            appendFromClause(sb, needsServiceJoin);
        } else {
            appendDataSelect(sb, needsServiceJoin);
            appendFromClause(sb, needsServiceJoin);
        }

        appendWhereClause(sb, filters, params);

        boolean needsGroupBy =
                (!isCountQuery && needsServiceJoin)
                        || (wrapSubquery && needsServiceJoin);
        if (needsGroupBy) {
            if (isCountQuery) {
                sb.append("GROUP BY m.id ");
            } else {
                sb.append("GROUP BY m.id, u.first_name, u.last_name, ")
                        .append(DISCOVERY_CITY_EXPR).append(", ")
                        .append(DISCOVERY_DISTRICT_EXPR).append(", ")
                        .append("m.avg_rating, m.review_count ");
            }
        }

        appendHavingClause(sb, filters, params, needsServiceJoin);

        if (wrapSubquery) {
            sb.append(") AS filtered");
        }

        if (!isCountQuery) {
            sb.append("ORDER BY m.avg_rating DESC NULLS LAST, m.id ");
            sb.append("LIMIT :limit OFFSET :offset");
            params.put("limit", pageable.getPageSize());
            params.put("offset", pageable.getOffset());
        }

        return new SqlAndParams(sb.toString(), params);
    }

    private static void appendDataSelect(StringBuilder sb, boolean needsServiceJoin) {
        sb.append("SELECT m.id AS master_id, ")
                .append("u.first_name AS first_name, u.last_name AS last_name, ")
                .append("m.avg_rating AS avg_rating, m.review_count AS review_count, ")
                // Avatar column does not yet exist on users/masters as a search
                // projection source. Emit NULL so the projection still maps
                // cleanly until a future phase wires master avatar storage.
                .append("CAST(NULL AS TEXT) AS avatar_url, ")
                // Discovery-locality FK ids (district-primary via salon link
                // for SALON_MASTER, else the user's own). Labels are resolved
                // through the M2 seam; columns 6/7 carry the ids only.
                .append(DISCOVERY_CITY_EXPR).append(" AS discovery_city_id, ")
                .append(DISCOVERY_DISTRICT_EXPR).append(" AS discovery_district_id, ");
        if (needsServiceJoin) {
            sb.append("MIN(COALESCE(ms.price_override, sd.base_price)) AS min_effective_price ");
        } else {
            sb.append("CAST(NULL AS NUMERIC) AS min_effective_price ");
        }
    }

    /**
     * {@code masters m JOIN users u} is the spine. The {@code salons sal}
     * LEFT JOIN on {@code m.salon_id} is always present (single-row PK join,
     * no fan-out) so an employed {@code SALON_MASTER}'s discovery locality
     * resolves through its salon at query time. The {@code master_services} /
     * {@code service_definitions} joins remain conditional (fan-out).
     */
    private static void appendFromClause(StringBuilder sb, boolean needsServiceJoin) {
        sb.append("FROM masters m JOIN users u ON u.id = m.user_id ");
        sb.append("LEFT JOIN salons sal ON sal.id = m.salon_id ");
        if (needsServiceJoin) {
            sb.append("LEFT JOIN master_services ms ON ms.master_id = m.id AND ms.is_active = true ");
            sb.append("LEFT JOIN service_definitions sd ON sd.id = ms.service_def_id ");
        }
    }

    /**
     * District-primary FK location filter + {@code SALON_ADMIN} exclusion.
     * The exclusion sits on the {@code users} join (the role lives there) so
     * an admin account never surfaces in public master discovery regardless
     * of any future data shape.
     */
    private static void appendWhereClause(
            StringBuilder sb,
            MasterSearchFilters filters,
            Map<String, Object> params
    ) {
        sb.append("WHERE m.is_active = true AND u.is_active = true ");
        sb.append("AND u.role <> :excludedRole ");
        params.put("excludedRole", ROLE_SALON_ADMIN);

        if (filters.hasDistrictFilter()) {
            sb.append("AND ").append(DISCOVERY_DISTRICT_EXPR).append(" = :districtId ");
            params.put("districtId", filters.districtId());
        } else if (filters.hasCityFilter()) {
            sb.append("AND ").append(DISCOVERY_CITY_EXPR).append(" = :cityId ");
            params.put("cityId", filters.cityId());
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
     */
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().toUpperCase(Locale.ROOT);
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
     * <p>Stable 8-column projection: {@code [master_id, first_name, last_name,
     * avg_rating, review_count, avatar_url, discovery_city_id,
     * discovery_district_id, min_effective_price]}. The internal city/district
     * UUIDs are consumed here for label resolution and are NOT placed on the
     * public DTO (§I).
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

        return new MasterSearchResult(
                masterId,
                firstName,
                lastName,
                labels.cityLabel(cityId),
                labels.districtLabel(districtId),
                avgRating,
                reviewCount,
                avatarUrl,
                minEffectivePrice
        );
    }

    private static SalonSearchResult toSalonSearchResult(Salon salon, DiscoveryLabels labels) {
        return new SalonSearchResult(
                salon.getId(),
                salon.getName(),
                labels.cityLabel(salon.getCityId()),
                labels.districtLabel(salon.getDistrictId()),
                salon.getAvatarUrl()
        );
    }
}
