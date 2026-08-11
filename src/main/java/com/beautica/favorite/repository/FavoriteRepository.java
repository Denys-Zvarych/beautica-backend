package com.beautica.favorite.repository;

import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.service.entity.MasterServiceAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * <p><b>{@code m.is_active = true} is load-bearing</b> (2026-08 security audit). A
     * favourite row survives its target's deactivation — there is no cleanup job — so the
     * filtering happens on read, exactly as {@link #findFavoriteServiceRows} does for the
     * wish list. Without it a deactivated master stayed in every client's favourites with a
     * live «Записатись» that the booking path then rejects with 404. The predicate is
     * mirrored in the count query so page metadata and content agree.
     *
     * <p><b>{@code (m.salon_id IS NULL OR sal.is_active = true)} — defence in depth</b> (2026-08
     * security re-audit MEDIUM). {@code SalonService.deactivateSalon} does not cascade to
     * {@code masters.is_active}, so a closed salon's masters still read as active; the predicate
     * is applied to every favourites surface so no list can outlive its salon. <b>On THIS query it
     * is expected to be a no-op</b> — {@code validateMasterTarget} admits only
     * {@code INDEPENDENT_MASTER} targets, whose {@code salon_id} is structurally {@code NULL}, so
     * the {@code IS NULL} branch always fires. It is added anyway because that no-op-ness is a
     * property of a rule in ANOTHER class ({@code FavoriteService}), and the SERVICE arm already
     * proves those rules diverge (a {@code SALON_MASTER}'s service IS wish-listable). Cost is one
     * {@code LEFT JOIN salons} in the count query; the paged query already joins {@code sal} for
     * the {@code COALESCE}, so it costs nothing there. The {@code IS NULL} branch is mandatory,
     * not cosmetic: without it every independent master — i.e. every row this query can return —
     * would vanish.
     *
     * <p><b>Anti-Bug audit LOW-1 (2026-07):</b> {@code COALESCE(sal.city_id, u.city_id)}
     * below is intentional, not the fall-through class fixed in
     * {@code BookingRepository.findClientBookingDetails} (19.3) — {@code Salon.cityId}
     * itself CAN be null (legacy pre-Phase-10.3 rows, no {@code NOT NULL}), but that
     * fact is irrelevant here: {@code FavoriteService.validateMasterTarget} rejects any
     * target whose owning user role is not {@code INDEPENDENT_MASTER} with a 400
     * <em>before</em> a favorite row is ever written, and an {@code INDEPENDENT_MASTER}'s
     * {@code masters.salon_id} is structurally always {@code NULL}
     * ({@code MasterService.createMasterForIndependentUser} never sets a salon). So
     * {@code sal.*} is always {@code NULL} for every row this query can ever join,
     * independent of any salon's actual {@code city_id}/{@code district_id} data —
     * {@code COALESCE} and a {@code CASE WHEN sal.id IS NOT NULL …} guard are provably
     * equivalent here. Pinned by
     * {@code FavoriteServiceTest.should_throwBadRequest_when_targetIsSalonMaster}
     * (write-time rejection, independent of any salon data).
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
              AND m.is_active = true
              AND (m.salon_id IS NULL OR sal.is_active = true)
            ORDER BY f.created_at DESC, f.target_id
            """,
            countQuery = """
            SELECT count(*)
            FROM favorites f
            JOIN masters m ON m.id = f.target_id
            LEFT JOIN salons sal ON sal.id = m.salon_id
            WHERE f.client_id = :clientId
              AND f.target_type = 'MASTER'
              AND m.is_active = true
              AND (m.salon_id IS NULL OR sal.is_active = true)
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
              AND m.is_active = true
              AND (m.salon_id IS NULL OR sal.is_active = true)
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
     * <p><b>{@code s.is_active = true} is load-bearing</b> (2026-08 security audit), for the
     * same reason as {@link #findFavoriteMasterRows(UUID, Pageable)}: {@code salons.is_active}
     * is a real soft-delete flag and a favourite row outlives it, so a deactivated salon would
     * otherwise keep a dead card in every client's favourites. Mirrored in the count query.
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
              AND s.is_active = true
            GROUP BY s.id, s.name, s.avatar_url, s.city_id, s.district_id, f.created_at
            ORDER BY f.created_at DESC, s.id
            """,
            countQuery = """
            SELECT count(*)
            FROM favorites f
            JOIN salons s ON s.id = f.target_id
            WHERE f.client_id = :clientId
              AND f.target_type = 'SALON'
              AND s.is_active = true
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
              AND s.is_active = true
            GROUP BY s.id, s.name, s.avatar_url, s.city_id, s.district_id, f.created_at
            ORDER BY f.created_at DESC, s.id
            """, nativeQuery = true)
    List<Object[]> findFavoriteSalonRows(@Param("clientId") UUID clientId);

    /**
     * Per-client favorited-services page for {@code GET /favorites/services} — the BEAUTY
     * WISH LIST (Phase 31.4), now merging TWO favourite arms into one list (salon-service-
     * favourites track):
     * <ul>
     *   <li>{@code SERVICE} — a {@code master_services.id} (a chosen master's assignment;
     *       Phase 31.3/31.4, unchanged in shape).</li>
     *   <li>{@code SALON_SERVICE} — a salon-owned {@code service_definitions.id} favourited
     *       BEFORE a master was chosen (the salon-catalogue step).</li>
     * </ul>
     *
     * <p><b>Why native SQL with a {@code UNION ALL}, not two JPQL queries merged in Java.</b>
     * The two arms have structurally different identities (one has a {@code master_services}
     * row, the other doesn't) but the wish-list card renders the SAME shape for both, and the
     * two arms must interleave by {@code created_at DESC} as ONE paginated list — a client who
     * favourited a salon service, then a master's service, then another salon service must see
     * all three in that order on one page, not two separately-paginated sub-lists stitched
     * together. A single {@code UNION ALL} keeps this ONE statement (content) plus ONE count
     * statement (only issued by Spring Data when a page is full), so
     * {@code FavoriteListProjectionTest}'s pinned "1 statement for a short page, 2 for a full
     * page" invariant is unaffected by adding the second arm — the query count does not grow
     * with the number of arms, only with whether the page needs a count at all.
     *
     * <p><b>Zero entity hydration, deliberately (§I, unchanged property).</b> Every column is a
     * scalar; nothing here is a JPA entity or association, for EITHER arm. This is a strictly
     * stronger form of the previous "master identity as scalars, never {@code JOIN FETCH
     * m.user}" guarantee: neither arm hydrates a {@code Master}, {@code User}, {@code Salon} or
     * {@code ServiceDefinition} row into the persistence context, so
     * {@code FavoriteListProjectionTest}'s "zero Salon / zero User entity loads" assertions hold
     * for both arms without qualification.
     *
     * <p><b>Price band</b> — {@code price_type} / {@code price_min} / {@code price_max} are read
     * straight off {@code service_definitions.price_type} / {@code base_price} / {@code
     * price_max} for BOTH arms; that is exactly the definition's advertised band, i.e. exactly
     * what {@code ServicePricing.ofDefinition(sd)} would derive from the same row (Phase 31.4
     * D2) — the override on {@code master_services.price_override} affects only the booking
     * floor, never this band (see {@code ServicePricing}'s class javadoc), so it is correctly
     * absent from this SELECT. {@code duration_minutes} is the one field the two arms compute
     * differently: the MASTER arm applies {@code COALESCE(msa.duration_override_minutes,
     * sd.base_duration_minutes)} (the master's own override); the SALON arm has no assignment to
     * override from, so it is bare {@code sd.base_duration_minutes} — matching
     * {@code ServicePricing.ofDefinition}'s {@code effectiveDurationMinutes} for a bare
     * definition (override arguments {@code null}). {@code FavoriteService} formats
     * {@code priceDisplay} from these three scalars via the same {@code PriceDisplayFormatter}
     * {@code ServicePricing} calls internally — see {@code FavoriteServiceResponse.fromRow}.
     *
     * <p><b>Master-performed invariant on the SALON arm (locked product decision).</b> The
     * {@code EXISTS} clause mirrors {@code MasterServiceRepository
     * #existsBookableAssignmentForSalonService} / {@code #findBookableAssignmentsBySalon}: a
     * favourited salon service only appears here while at least one active master of the active
     * salon still performs it. The favourite ROW is never deleted when that stops being true —
     * exactly the existing MASTER/SALON/SERVICE self-healing behaviour — it just stops (and later
     * resumes) appearing in this read. The MASTER arm keeps its pre-existing four flags
     * ({@code msa.is_active}, {@code sd.is_active}, {@code m.is_active}, salon-active-or-null)
     * for the same reason, unchanged from Phase 31.3/31.4.
     *
     * <p><b>Column layout</b> (stable — index-matched in {@code FavoriteService.mapWishListRow}
     * and {@code FavoriteServiceResponse.fromRow}); columns 15/16 exist for the {@code ORDER BY}
     * tiebreak only and are never read on the Java side:
     * <ol start="0">
     *   <li>{@code source_type} — {@code "MASTER"} or {@code "SALON"}</li>
     *   <li>{@code master_service_id} — {@code master_services.id}; {@code NULL} for a SALON row</li>
     *   <li>{@code master_id} — {@code masters.id}; {@code NULL} for a SALON row</li>
     *   <li>{@code service_def_id} — {@code service_definitions.id}, present on both arms</li>
     *   <li>{@code service_name}</li>
     *   <li>{@code master_first_name} — {@code NULL} for a SALON row</li>
     *   <li>{@code master_last_name} — {@code NULL} for a SALON row</li>
     *   <li>{@code master_avatar_url} — {@code NULL} for a SALON row</li>
     *   <li>{@code duration_minutes} — effective duration (see above)</li>
     *   <li>{@code price_type}</li>
     *   <li>{@code price_min} — the definition's {@code base_price}</li>
     *   <li>{@code price_max} — {@code NULL} for FIXED</li>
     *   <li>{@code salon_id} — {@code NULL} for a MASTER row</li>
     *   <li>{@code salon_name} — {@code NULL} for a MASTER row</li>
     *   <li>{@code salon_avatar_url} — {@code NULL} for a MASTER row</li>
     *   <li>{@code created_at} — ordering only</li>
     *   <li>{@code favorite_id} — tiebreak only, makes the order total</li>
     * </ol>
     *
     * <p><b>Ordering</b> matches {@code /favorites/masters} and {@code /favorites/salons}:
     * {@code created_at DESC} (most recently hearted first) with the favourite row's own id as a
     * tiebreak — a total order across BOTH arms, so paging never drops or duplicates a row even
     * when a MASTER row and a SALON row share the same {@code created_at} millisecond.
     */
    @Query(value = """
            SELECT * FROM (
                SELECT
                    'MASTER'                 AS source_type,
                    msa.id                   AS master_service_id,
                    m.id                     AS master_id,
                    sd.id                    AS service_def_id,
                    sd.name                  AS service_name,
                    u.first_name             AS master_first_name,
                    u.last_name              AS master_last_name,
                    u.avatar_url             AS master_avatar_url,
                    COALESCE(msa.duration_override_minutes, sd.base_duration_minutes) AS duration_minutes,
                    sd.price_type            AS price_type,
                    sd.base_price            AS price_min,
                    sd.price_max             AS price_max,
                    NULL::uuid               AS salon_id,
                    NULL::varchar            AS salon_name,
                    NULL::varchar            AS salon_avatar_url,
                    f.created_at             AS created_at,
                    f.id                     AS favorite_id
                FROM favorites f
                JOIN master_services msa ON msa.id = f.target_id
                JOIN service_definitions sd ON sd.id = msa.service_def_id
                JOIN masters m ON m.id = msa.master_id
                JOIN users u ON u.id = m.user_id
                LEFT JOIN salons sal ON sal.id = m.salon_id
                WHERE f.client_id = :clientId
                  AND f.target_type = 'SERVICE'
                  AND msa.is_active = true
                  AND sd.is_active = true
                  AND m.is_active = true
                  AND (m.salon_id IS NULL OR sal.is_active = true)

                UNION ALL

                SELECT
                    'SALON'                  AS source_type,
                    NULL::uuid               AS master_service_id,
                    NULL::uuid               AS master_id,
                    sd.id                    AS service_def_id,
                    sd.name                  AS service_name,
                    NULL::varchar            AS master_first_name,
                    NULL::varchar            AS master_last_name,
                    NULL::varchar            AS master_avatar_url,
                    sd.base_duration_minutes AS duration_minutes,
                    sd.price_type            AS price_type,
                    sd.base_price            AS price_min,
                    sd.price_max             AS price_max,
                    sal.id                   AS salon_id,
                    sal.name                 AS salon_name,
                    sal.avatar_url           AS salon_avatar_url,
                    f.created_at             AS created_at,
                    f.id                     AS favorite_id
                FROM favorites f
                JOIN service_definitions sd ON sd.id = f.target_id AND sd.owner_type = 'SALON'
                JOIN salons sal ON sal.id = sd.owner_id
                WHERE f.client_id = :clientId
                  AND f.target_type = 'SALON_SERVICE'
                  AND sd.is_active = true
                  AND sal.is_active = true
                  AND EXISTS (
                      SELECT 1 FROM master_services msa2
                      JOIN masters m2 ON m2.id = msa2.master_id
                      WHERE msa2.service_def_id = sd.id
                        AND msa2.is_active = true
                        AND m2.is_active = true
                        AND m2.salon_id = sal.id
                        AND m2.is_active = true
                  )
            ) combined
            ORDER BY created_at DESC, favorite_id
            """,
            countQuery = """
            SELECT
                (SELECT COUNT(*)
                 FROM favorites f
                 JOIN master_services msa ON msa.id = f.target_id
                 JOIN service_definitions sd ON sd.id = msa.service_def_id
                 JOIN masters m ON m.id = msa.master_id
                 LEFT JOIN salons sal ON sal.id = m.salon_id
                 WHERE f.client_id = :clientId
                   AND f.target_type = 'SERVICE'
                   AND msa.is_active = true
                   AND sd.is_active = true
                   AND m.is_active = true
                   AND (m.salon_id IS NULL OR sal.is_active = true))
                +
                (SELECT COUNT(*)
                 FROM favorites f
                 JOIN service_definitions sd ON sd.id = f.target_id AND sd.owner_type = 'SALON'
                 JOIN salons sal ON sal.id = sd.owner_id
                 WHERE f.client_id = :clientId
                   AND f.target_type = 'SALON_SERVICE'
                   AND sd.is_active = true
                   AND sal.is_active = true
                   AND EXISTS (
                       SELECT 1 FROM master_services msa2
                       JOIN masters m2 ON m2.id = msa2.master_id
                       WHERE msa2.service_def_id = sd.id
                         AND msa2.is_active = true
                         AND m2.is_active = true
                         AND m2.salon_id = sal.id))
            """,
            nativeQuery = true)
    Page<Object[]> findFavoriteServiceRows(@Param("clientId") UUID clientId,
                                           Pageable pageable);

    /**
     * Membership test for polymorphic target ids a CLIENT has favourited — backs the per-request
     * {@code isFavorite} decoration on {@code GET /masters/{masterId}/services} ({@code targetType
     * = SERVICE}, {@code targetIds} = {@code masterServices.id} values, Phase 32.1 D5) AND on
     * {@code GET /salons/{salonId}/services} ({@code targetType = SALON_SERVICE}, {@code targetIds}
     * = {@code service_definitions.id} values, salon-service-favourites track), NOT the wish-list
     * page above. Renamed from a {@code SERVICE}-implicit method to an explicit {@code targetType}
     * parameter for exactly this reuse — every caller now states which arm it means.
     *
     * <p><b>One statement, membership tested in memory</b> against the returned {@link Set} —
     * deliberately not {@code existsBy…} per row (N+1) and not {@link #findFavoriteServiceRows}
     * (which is shaped for a capped 20-row wish-list page, far more work than a set of ids, and
     * the wrong shape for a 200-row menu or catalogue).
     *
     * <p><b>Index: {@code uq_favorite UNIQUE (client_id, target_type, target_id)}
     * ({@code V92__create_favorites.sql}) — no new migration.</b> Equality on {@code client_id}
     * and {@code target_type}, an {@code IN} on {@code target_id}, and {@code target_id} is the
     * only column selected: a covering, left-prefix-exact, index-only scan.
     * {@code idx_favorites_client_created} (V135) is the ORDERING index the three per-client list
     * queries ride and is not what this predicate uses.
     *
     * <p><b>{@code targetType} is load-bearing, not decorative.</b> {@code target_id} is
     * polymorphic with no FK — a {@code MASTER} or {@code SALON} favourite can carry a
     * {@code target_id} that happens to collide with an unrelated {@code master_services.id} or
     * {@code service_definitions.id}. Dropping this predicate would flag that unrelated favourite
     * as a match too.
     *
     * <p>Callers MUST short-circuit on an empty {@code targetIds} collection — a master with no
     * active services (or a salon with no catalogue rows) must never reach this method with an
     * empty {@code IN} list.
     *
     * <p><b>{@code @Transactional(readOnly = true)}</b> (audit-fix, P9 checklist): without it,
     * Spring Data opens a plain read-write transaction for this custom {@code @Query} method. No
     * measurable perf delta here — it returns scalar {@code UUID}s, no managed entities — but it
     * is added for correctness/consistency with the read-only contract this method actually has.
     * Deliberately NOT swept across the sibling {@code @Query} methods in this file (none of them
     * annotate either) — that is a pre-existing pattern predating this change and out of this
     * diff's locus; sweeping it would widen an audit-fix into an unrelated file-wide change.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT f.targetId FROM Favorite f
            WHERE f.clientId = :clientId
              AND f.targetType = :targetType
              AND f.targetId IN :targetIds
            """)
    Set<UUID> findFavoritedServiceIds(@Param("clientId") UUID clientId,
                                       @Param("targetType") FavoriteTargetType targetType,
                                       @Param("targetIds") Collection<UUID> targetIds);
}
