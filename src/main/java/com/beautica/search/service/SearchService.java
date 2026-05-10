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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <h3>Why pagination needs a HAVING-aware count</h3>
 * The price filter is applied in {@code HAVING}, not {@code WHERE}, because the
 * effective price is an aggregate. A naive {@code SELECT COUNT(DISTINCT m.id)}
 * without {@code HAVING} would over-count, producing phantom pages on the mobile
 * client (e.g. last page renders empty). The count query therefore wraps the
 * same {@code GROUP BY ... HAVING ...} in a subquery.
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

    /**
     * Native SQL projection for master search. Filters use the standard
     * {@code (:param IS NULL OR col = :param)} idiom so a single query handles
     * every combination of optional filters. The price filter must live in
     * {@code HAVING} because it constrains the {@code MIN(COALESCE(...))}
     * aggregate.
     */
    private static final String MASTER_SEARCH_SQL = """
            SELECT
              m.id              AS master_id,
              m.user_id         AS user_id,
              u.first_name      AS first_name,
              u.last_name       AS last_name,
              u.city            AS city,
              m.avg_rating      AS avg_rating,
              m.review_count    AS review_count,
              -- Avatar column does not yet exist on users/masters (no migration added it as
              -- of V35). Emit NULL so the projection still maps cleanly until a future phase
              -- introduces user/master avatar storage. Discovered by SearchIntegrationTest
              -- in Phase 6.4 — Phase 6.3 unit tests stub the EntityManager and never executed
              -- the real SQL. See docs/backlog.md follow-up.
              CAST(NULL AS TEXT) AS avatar_url,
              MIN(COALESCE(ms.price_override, sd.base_price)) AS min_effective_price
            FROM masters m
            JOIN users u ON u.id = m.user_id
            LEFT JOIN master_services ms ON ms.master_id = m.id AND ms.is_active = true
            LEFT JOIN service_definitions sd ON sd.id = ms.service_def_id
            WHERE m.is_active = true
              AND u.is_active = true
              AND (CAST(:city AS VARCHAR) IS NULL OR u.city = CAST(:city AS VARCHAR))
              AND (CAST(:region AS VARCHAR) IS NULL OR u.region = CAST(:region AS VARCHAR))
              AND (CAST(:category AS VARCHAR) IS NULL OR sd.category = CAST(:category AS VARCHAR))
              AND (CAST(:minRating AS NUMERIC) IS NULL OR m.avg_rating >= CAST(:minRating AS NUMERIC))
            GROUP BY m.id, m.user_id, u.first_name, u.last_name, u.city,
                     m.avg_rating, m.review_count
            HAVING (CAST(:minPrice AS NUMERIC) IS NULL OR MIN(COALESCE(ms.price_override, sd.base_price)) >= CAST(:minPrice AS NUMERIC))
               AND (CAST(:maxPrice AS NUMERIC) IS NULL OR MIN(COALESCE(ms.price_override, sd.base_price)) <= CAST(:maxPrice AS NUMERIC))
            ORDER BY m.avg_rating DESC NULLS LAST
            LIMIT :limit OFFSET :offset
            """;

    /**
     * Count companion to {@link #MASTER_SEARCH_SQL}. Wraps the same
     * {@code GROUP BY} + {@code HAVING} in a subquery so the price filter is
     * honoured in the total — see class-level Javadoc for why.
     */
    private static final String MASTER_SEARCH_COUNT_SQL = """
            SELECT COUNT(*) FROM (
              SELECT m.id
              FROM masters m
              JOIN users u ON u.id = m.user_id
              LEFT JOIN master_services ms ON ms.master_id = m.id AND ms.is_active = true
              LEFT JOIN service_definitions sd ON sd.id = ms.service_def_id
              WHERE m.is_active = true
                AND u.is_active = true
                AND (CAST(:city AS VARCHAR) IS NULL OR u.city = CAST(:city AS VARCHAR))
                AND (CAST(:region AS VARCHAR) IS NULL OR u.region = CAST(:region AS VARCHAR))
                AND (CAST(:category AS VARCHAR) IS NULL OR sd.category = CAST(:category AS VARCHAR))
                AND (CAST(:minRating AS NUMERIC) IS NULL OR m.avg_rating >= CAST(:minRating AS NUMERIC))
              GROUP BY m.id
              HAVING (CAST(:minPrice AS NUMERIC) IS NULL OR MIN(COALESCE(ms.price_override, sd.base_price)) >= CAST(:minPrice AS NUMERIC))
                 AND (CAST(:maxPrice AS NUMERIC) IS NULL OR MIN(COALESCE(ms.price_override, sd.base_price)) <= CAST(:maxPrice AS NUMERIC))
            ) AS filtered
            """;

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
     * @throws BusinessException if {@code minPrice} > {@code maxPrice}
     */
    @Transactional(readOnly = true)
    public Page<MasterSearchResult> searchMasters(MasterSearchRequest request, Pageable pageable) {
        validatePriceRange(request.minPrice(), request.maxPrice());

        String normalizedCity = nullIfBlank(request.city());
        String normalizedRegion = nullIfBlank(request.region());
        String normalizedCategory = normalizeCategory(request.category());
        BigDecimal normalizedMinRating = normalizeRating(request.minRating());

        Query dataQuery = entityManager.createNativeQuery(MASTER_SEARCH_SQL);
        bindFilterParams(dataQuery, normalizedCity, normalizedRegion, normalizedCategory,
                normalizedMinRating, request.minPrice(), request.maxPrice());
        dataQuery.setParameter("limit", pageable.getPageSize());
        dataQuery.setParameter("offset", (long) pageable.getPageNumber() * pageable.getPageSize());

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = dataQuery.getResultList();
        List<MasterSearchResult> results = new ArrayList<>(rawRows.size());
        for (Object[] row : rawRows) {
            results.add(mapMasterRow(row));
        }

        Query countQuery = entityManager.createNativeQuery(MASTER_SEARCH_COUNT_SQL);
        bindFilterParams(countQuery, normalizedCity, normalizedRegion, normalizedCategory,
                normalizedMinRating, request.minPrice(), request.maxPrice());
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Discover salons matching optional city/region. Delegates filtering to the
     * repository's JPQL query and maps each entity to a public-facing DTO so the
     * JPA entity never escapes the service layer.
     */
    @Transactional(readOnly = true)
    public Page<SalonSearchResult> searchSalons(String city, String region, Pageable pageable) {
        Page<Salon> page = salonRepository.findByFilter(nullIfBlank(city), nullIfBlank(region), pageable);
        return page.map(this::toSalonSearchResult);
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

    private static void bindFilterParams(
            Query query,
            String city,
            String region,
            String category,
            BigDecimal minRating,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {
        query.setParameter("city", city);
        query.setParameter("region", region);
        query.setParameter("category", category);
        query.setParameter("minRating", minRating);
        query.setParameter("minPrice", minPrice);
        query.setParameter("maxPrice", maxPrice);
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
