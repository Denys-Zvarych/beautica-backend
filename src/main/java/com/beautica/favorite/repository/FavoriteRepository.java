package com.beautica.favorite.repository;

import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Favorite} rows and the two per-client favorites read
 * projections.
 *
 * <h3>Ownership scoping (§E-4)</h3>
 * Every finder is keyed on {@code clientId} as its leading argument — the caller
 * ({@code FavoriteService}) always passes the authenticated principal's id, never
 * a body-supplied value, so a client can only read/write its own favorites.
 *
 * <h3>Read projections are native, locality-label-deferred</h3>
 * The two list queries are native SQL because they join across
 * {@code favorites → masters/salons → users/reviews} and (for masters) compute a
 * correlated per-client "latest booking service name" in a single statement (no
 * N+1, §E-2). They return raw {@code Object[]} rows; the service resolves the
 * discovery-locality {@code name_uk} labels for the whole page through the M2
 * seam ({@code DiscoveryLocationResolver}) exactly as
 * {@code com.beautica.search.service.SearchService} does — the raw FK city/district
 * ids never leave the service (§I).
 */
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    boolean existsByClientIdAndTargetTypeAndTargetId(
            UUID clientId, FavoriteTargetType targetType, UUID targetId);

    Optional<Favorite> findByClientIdAndTargetTypeAndTargetId(
            UUID clientId, FavoriteTargetType targetType, UUID targetId);

    /**
     * Idempotent delete (§ DELETE returns 204 whether or not a row existed). Returns
     * the number of rows removed (0 or 1) so the service can stay side-effect-aware
     * without a prior existence round-trip. The enclosing {@code FavoriteService}
     * method is {@code @Transactional}, so this {@code @Modifying} query runs inside
     * that transaction — no repository-level {@code @Transactional} needed.
     */
    @Modifying
    int deleteByClientIdAndTargetTypeAndTargetId(
            UUID clientId, FavoriteTargetType targetType, UUID targetId);

    /**
     * Per-client favorited-masters projection for {@code GET /favorites/masters}.
     *
     * <p>Column layout (stable — index-matched in the service):
     * <ol start="0">
     *   <li>master_id ({@code f.target_id})</li>
     *   <li>first_name</li>
     *   <li>last_name</li>
     *   <li>avatar_url ({@code users.avatar_url})</li>
     *   <li>discovery_city_id ({@code COALESCE(sal.city_id, u.city_id)})</li>
     *   <li>discovery_district_id ({@code COALESCE(sal.district_id, u.district_id)})</li>
     *   <li>avg_rating ({@code masters.avg_rating}, nullable)</li>
     *   <li>last_service_name — <b>this client's</b> latest booking service name with
     *       that master (any status, latest {@code starts_at}), or {@code null}</li>
     * </ol>
     *
     * <p>The {@code INNER JOIN masters} drops any stale favorite whose target master
     * was deleted (polymorphic, no FK) — the row simply disappears from the list.
     * Only {@code INDEPENDENT_MASTER}-owned masters are favoritable, but the join is
     * not role-filtered here: the service rejects a {@code SALON_MASTER} target at
     * write time, so no such favorite row can exist to read back.
     *
     * <p>{@code last_service_name} is a correlated {@code LATERAL} subquery taking the
     * single most-recent booking for this {@code (client, master)} pair — one extra
     * index-served lookup per favorited master (served by {@code
     * idx_bookings_master_client_starts_at}, V93), evaluated inside the same statement
     * (no per-row application round-trip).
     *
     * <p><b>Pagination (§E-3, §J):</b> the result is bounded by {@code Pageable}
     * (LIMIT/OFFSET) so the LATERAL runs at most {@code pageSize} times per request. The
     * stable {@code ORDER BY f.created_at DESC, f.target_id} is preserved as a total order
     * so paging never drops or duplicates a row across pages. The unbounded
     * {@link #findFavoriteMasterRows(UUID)} overload below exists only for the legacy
     * single-page service path and its unit tests; the controller never calls it.
     */
    @Query(value = """
            SELECT f.target_id              AS master_id,
                   u.first_name             AS first_name,
                   u.last_name              AS last_name,
                   u.avatar_url             AS avatar_url,
                   COALESCE(sal.city_id, u.city_id)         AS discovery_city_id,
                   COALESCE(sal.district_id, u.district_id) AS discovery_district_id,
                   m.avg_rating             AS avg_rating,
                   lb.service_name          AS last_service_name
            FROM favorites f
            JOIN masters m ON m.id = f.target_id
            JOIN users u ON u.id = m.user_id
            LEFT JOIN salons sal ON sal.id = m.salon_id
            LEFT JOIN LATERAL (
                SELECT sd.name AS service_name
                FROM bookings b
                JOIN master_services ms ON ms.id = b.master_service_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE b.master_id = f.target_id
                  AND b.client_id = f.client_id
                ORDER BY b.starts_at DESC
                LIMIT 1
            ) lb ON true
            WHERE f.client_id = :clientId
              AND f.target_type = 'MASTER'
            ORDER BY f.created_at DESC, f.target_id
            """,
            countQuery = """
            SELECT count(*)
            FROM favorites f
            JOIN masters m ON m.id = f.target_id
            WHERE f.client_id = :clientId
              AND f.target_type = 'MASTER'
            """,
            nativeQuery = true)
    Page<Object[]> findFavoriteMasterRows(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Unbounded single-page variant of {@link #findFavoriteMasterRows(UUID, Pageable)}.
     * Retained only for the legacy {@code FavoriteService.listMasterFavorites(UUID)}
     * overload and its unit tests — the controller exclusively uses the paginated form.
     */
    @Query(value = """
            SELECT f.target_id              AS master_id,
                   u.first_name             AS first_name,
                   u.last_name              AS last_name,
                   u.avatar_url             AS avatar_url,
                   COALESCE(sal.city_id, u.city_id)         AS discovery_city_id,
                   COALESCE(sal.district_id, u.district_id) AS discovery_district_id,
                   m.avg_rating             AS avg_rating,
                   lb.service_name          AS last_service_name
            FROM favorites f
            JOIN masters m ON m.id = f.target_id
            JOIN users u ON u.id = m.user_id
            LEFT JOIN salons sal ON sal.id = m.salon_id
            LEFT JOIN LATERAL (
                SELECT sd.name AS service_name
                FROM bookings b
                JOIN master_services ms ON ms.id = b.master_service_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE b.master_id = f.target_id
                  AND b.client_id = f.client_id
                ORDER BY b.starts_at DESC
                LIMIT 1
            ) lb ON true
            WHERE f.client_id = :clientId
              AND f.target_type = 'MASTER'
            ORDER BY f.created_at DESC, f.target_id
            """, nativeQuery = true)
    List<Object[]> findFavoriteMasterRows(@Param("clientId") UUID clientId);

    /**
     * Per-client favorited-salons projection for {@code GET /favorites/salons}.
     *
     * <p>Column layout (stable — index-matched in the service):
     * <ol start="0">
     *   <li>salon_id ({@code f.target_id})</li>
     *   <li>name</li>
     *   <li>avatar_url</li>
     *   <li>city_id</li>
     *   <li>district_id</li>
     *   <li>avg_rating — {@code AVG(reviews.rating)} over the salon's reviews
     *       ({@code null} when never reviewed; the {@code salons} table carries no
     *       pre-computed rating column)</li>
     * </ol>
     *
     * <p>The {@code INNER JOIN salons} drops any stale favorite whose target salon was
     * deleted. Rating is a grouped {@code LEFT JOIN reviews} aggregate evaluated in the
     * same statement (no N+1).
     *
     * <p><b>Pagination (§E-3, §J):</b> bounded by {@code Pageable}; the stable
     * {@code ORDER BY f.created_at DESC, s.id} is a total order so paging is consistent.
     * The unbounded {@link #findFavoriteSalonRows(UUID)} overload below is the legacy
     * single-page path used by its unit tests only; the controller never calls it.
     */
    @Query(value = """
            SELECT s.id                     AS salon_id,
                   s.name                   AS name,
                   s.avatar_url             AS avatar_url,
                   s.city_id                AS city_id,
                   s.district_id            AS district_id,
                   AVG(r.rating)            AS avg_rating
            FROM favorites f
            JOIN salons s ON s.id = f.target_id
            LEFT JOIN reviews r ON r.salon_id = s.id
            WHERE f.client_id = :clientId
              AND f.target_type = 'SALON'
            GROUP BY s.id, s.name, s.avatar_url, s.city_id, s.district_id, f.created_at
            ORDER BY f.created_at DESC, s.id
            """,
            countQuery = """
            SELECT count(*)
            FROM favorites f
            JOIN salons s ON s.id = f.target_id
            WHERE f.client_id = :clientId
              AND f.target_type = 'SALON'
            """,
            nativeQuery = true)
    Page<Object[]> findFavoriteSalonRows(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Unbounded single-page variant of {@link #findFavoriteSalonRows(UUID, Pageable)}.
     * Retained only for the legacy {@code FavoriteService.listSalonFavorites(UUID)}
     * overload and its unit tests — the controller exclusively uses the paginated form.
     */
    @Query(value = """
            SELECT s.id                     AS salon_id,
                   s.name                   AS name,
                   s.avatar_url             AS avatar_url,
                   s.city_id                AS city_id,
                   s.district_id            AS district_id,
                   AVG(r.rating)            AS avg_rating
            FROM favorites f
            JOIN salons s ON s.id = f.target_id
            LEFT JOIN reviews r ON r.salon_id = s.id
            WHERE f.client_id = :clientId
              AND f.target_type = 'SALON'
            GROUP BY s.id, s.name, s.avatar_url, s.city_id, s.district_id, f.created_at
            ORDER BY f.created_at DESC, s.id
            """, nativeQuery = true)
    List<Object[]> findFavoriteSalonRows(@Param("clientId") UUID clientId);
}
