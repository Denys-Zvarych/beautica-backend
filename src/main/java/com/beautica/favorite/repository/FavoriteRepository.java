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
     * WISH LIST (Phase 31.4).
     *
     * <p>Returns the {@link com.beautica.service.entity.MasterServiceAssignment} graph rather
     * than a native {@code Object[]} projection, deliberately (§ Phase 31.4 D2): money must be
     * derived by {@code ServicePricing} — the one code path the master's own menu uses — and a
     * hand-rolled COALESCE in SQL is exactly how the wish list and the profile start printing
     * different prices. The page is capped at 20 rows, so the projection's performance argument
     * does not apply at this size; correctness does.
     *
     * <p>The {@code Favorite → MasterServiceAssignment} join is <b>unmapped</b>
     * ({@code target_id} is polymorphic — {@code masters.id} OR {@code salons.id} OR
     * {@code master_services.id} — so there is no association to navigate); it is an explicit
     * ad-hoc entity {@code JOIN … ON}. Being an INNER join, it also silently drops any stale
     * favourite whose target row was hard-deleted, exactly as the masters/salons projections do.
     *
     * <p><b>All THREE {@code isActive} predicates are load-bearing</b> (Phase 31.3 D4). A
     * favourite row survives its target's deactivation — there is no cleanup job for
     * MASTER/SALON today and this track adds none — so the filtering happens on read: a
     * deactivated service disappears from the wish list instead of offering a «Записатись»
     * that would 404 at booking time. The three flags are independent:
     * <ul>
     *   <li>{@code msa.isActive} — the master unassigned this service from their menu.</li>
     *   <li>{@code sd.isActive} — {@code ServiceCatalogService.deactivateServiceDefinition}
     *       soft-deletes the definition <em>without</em> touching the assignment rows, so
     *       {@code sd.isActive = false} while {@code msa.isActive = true} is a real state.</li>
     *   <li>{@code m.isActive} — <b>the master themself deactivated</b>. Added by the 2026-08
     *       security audit: without it a deactivated master kept a live «Записатись» in every
     *       client's wish list, which {@code BookingService.doCreateBooking} then rejects with
     *       404 — precisely the dead CTA this javadoc claims to prevent. Costs no extra join:
     *       {@code m} is already joined for the row's master identity.</li>
     * </ul>
     *
     * <p><b>A FOURTH predicate — the owning salon's {@code isActive}</b> (2026-08 security
     * re-audit MEDIUM). {@code SalonService.deactivateSalon} sets {@code salons.is_active = false}
     * but does NOT cascade to {@code masters.is_active}, so every master of a closed salon still
     * reads as active. Unlike the MASTER arm, a {@code SALON_MASTER}'s service IS wish-listable
     * (locked decision), so this list is the one surface that can show a closed salon's staff —
     * {@code /favorites/salons} correctly hid the salon itself while this list kept offering its
     * masters' services with a live «Записатись». The booking path now rejects those too
     * ({@code BookingService.doCreateBooking}); this predicate stops the dead CTA being rendered
     * at all.
     *
     * <p><b>The {@code m.salon IS NULL} branch is load-bearing, not defensive.</b> An
     * {@code INDEPENDENT_MASTER} has {@code masters.salon_id = NULL} by construction
     * ({@code MasterService.createMasterForIndependentUser} never sets one) and must stay
     * bookable, so the predicate is a disjunction, never a bare {@code sal.isActive = true}.
     * For the same reason the salon is reached through an explicit {@code LEFT JOIN}: the
     * implicit path form ({@code m.salon.isActive}) compiles to an INNER join in Hibernate 6 and
     * would silently drop every independent master's service from the list.
     *
     * <p><b>Why the projection is {@code Object[]} and not a bare entity page.</b> The row's
     * master identity ({@code firstName}/{@code lastName}/{@code avatarUrl}) is read as SCALARS
     * off a plain {@code JOIN m.user u}, never a {@code JOIN FETCH}. Fetching the user would
     * hydrate the whole 32-column {@code users} row per wish-list item — including
     * {@code password_hash}, {@code password_reset_code_hash}, {@code verification_code_hash}
     * and {@code tokens_valid_after} — into the persistence context, for three strings the DTO
     * actually prints. At {@code size=20} that is 20 credential-bearing managed entities per
     * request (§I). {@code msa.serviceDefinition} and {@code msa.master} stay {@code JOIN FETCH}
     * because {@code ServicePricing} and the master id genuinely need them as entities.
     * {@code Master.user} is LAZY, so the fetched {@code Master} carries only an uninitialised
     * proxy — no {@code users} row is loaded at all. Every fetch is ToOne, so
     * {@code LIMIT}/{@code OFFSET} stays in SQL (no in-memory pagination).
     *
     * <p>Column layout (stable — index-matched in {@code FavoriteService.mapWishListRow}):
     * <ol start="0">
     *   <li>{@link MasterServiceAssignment} (fetch-joined: {@code serviceDefinition}, {@code master})</li>
     *   <li>master's {@code users.first_name}</li>
     *   <li>master's {@code users.last_name}</li>
     *   <li>master's {@code users.avatar_url}</li>
     * </ol>
     *
     * <p><b>Ordering</b> matches {@code /favorites/masters} and {@code /favorites/salons}
     * byte-for-byte: {@code f.createdAt DESC} (most recently hearted first) with the target id
     * as a tiebreak, making it a total order so paging never drops or duplicates a row.
     */
    @Query(value = """
            SELECT msa, u.firstName, u.lastName, u.avatarUrl FROM Favorite f
            JOIN MasterServiceAssignment msa ON msa.id = f.targetId
            JOIN FETCH msa.serviceDefinition sd
            JOIN FETCH msa.master m
            JOIN m.user u
            LEFT JOIN m.salon sal
            WHERE f.clientId = :clientId
              AND f.targetType = com.beautica.favorite.entity.FavoriteTargetType.SERVICE
              AND msa.isActive = true
              AND sd.isActive = true
              AND m.isActive = true
              AND (m.salon IS NULL OR sal.isActive = true)
            ORDER BY f.createdAt DESC, msa.id ASC
            """,
            countQuery = """
            SELECT count(msa) FROM Favorite f
            JOIN MasterServiceAssignment msa ON msa.id = f.targetId
            JOIN msa.master m
            LEFT JOIN m.salon sal
            WHERE f.clientId = :clientId
              AND f.targetType = com.beautica.favorite.entity.FavoriteTargetType.SERVICE
              AND msa.isActive = true
              AND msa.serviceDefinition.isActive = true
              AND m.isActive = true
              AND (m.salon IS NULL OR sal.isActive = true)
            """)
    Page<Object[]> findFavoriteServiceRows(@Param("clientId") UUID clientId,
                                           Pageable pageable);
}
