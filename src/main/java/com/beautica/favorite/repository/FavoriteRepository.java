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
 * {@code favorites → masters/salons → users} and project locality FK ids plus raw
 * address columns in a single statement (no N+1, §E-2). They return raw {@code Object[]} rows; the service resolves the
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
     * Hard-deletes every {@code favorites} row pointing at a given {@code (targetType, targetId)}
     * pair, across ALL clients — backs {@code SalonService.deactivateSalon}'s Phase 268 D5 cascade
     * ({@code targetType = SALON}), removing every client's favourite for a deleted salon in one
     * statement rather than a per-client loop (§E — no N+1).
     *
     * <p><b>BOTH columns are load-bearing in the predicate.</b> {@code target_id} is polymorphic
     * with no FK across four target types (see the interface Javadoc), so a {@code MASTER} or
     * {@code SERVICE} favourite can carry a {@code target_id} that coincidentally equals a
     * {@code salons.id}. Keying on {@code target_id} alone would delete that unrelated favourite
     * too — the exact trap the phase's mutation check pins (drop {@code targetType} from the
     * predicate and the "don't touch a same-id MASTER favourite" test must go red).
     *
     * <p>Called inside the SAME transaction as the salon deactivation — pure DB work, no network —
     * so it rolls back together with the rest of the cascade rather than running after commit like
     * the R2 sweep.
     */
    @Modifying
    @Query("""
            DELETE FROM Favorite f
            WHERE f.targetType = :targetType AND f.targetId = :targetId
            """)
    int deleteAllByTargetTypeAndTargetId(
            @Param("targetType") FavoriteTargetType targetType, @Param("targetId") UUID targetId);

    /**
     * Per-client favorited-masters projection for {@code GET /favorites/masters}.
     *
     * <p>Column layout (stable — index-matched in the service):
     * <ol start="0">
     *   <li>master_id ({@code f.target_id})</li>
     *   <li>first_name</li>
     *   <li>last_name</li>
     *   <li>avatar_url ({@code users.avatar_url})</li>
     *   <li>own_city_id ({@code users.city_id}) — the master's OWN locality</li>
     *   <li>own_district_id ({@code users.district_id}) — the master's OWN locality</li>
     *   <li>avg_rating ({@code masters.avg_rating}, nullable)</li>
     *   <li>own_street ({@code users.street}, nullable) — the master's OWN address</li>
     *   <li>own_building_no ({@code users.building_no}, nullable) — OWN</li>
     *   <li>own_location_note ({@code users.location_note}, nullable) — OWN</li>
     *   <li>master_type ({@code masters.master_type}, {@code NOT NULL}) — projected ONLY to drive
     *       the address- AND locality-source rules in {@code FavoriteService#mapMasterRow};
     *       it never reaches the response DTO (§I: no internal role/type values on the wire)</li>
     *   <li>salon_city_id ({@code salons.city_id}, nullable — {@code NULL} both when the master
     *       has no salon and when the salon has no recorded locality)</li>
     *   <li>salon_district_id ({@code salons.district_id}, nullable, same two reasons)</li>
     *   <li>salon_id ({@code salons.id}, nullable — {@code NULL} exactly when the master has no
     *       employing salon)</li>
     *   <li>salon_name ({@code salons.name}, {@code NOT NULL} on the table, so {@code NULL} here
     *       has the same single meaning as {@code salon_id})</li>
     *   <li>salon_street ({@code salons.street}, nullable)</li>
     *   <li>salon_building_no ({@code salons.building_no}, nullable)</li>
     *   <li>salon_location_note ({@code salons.location_note}, nullable)</li>
     * </ol>
     *
     * <p><b>Locality is NO LONGER {@code COALESCE}d in SQL</b> (2026-08 security re-audit LOW).
     * Both sources are projected raw and the choice is made in the service by the SAME
     * {@link com.beautica.master.entity.MasterType#disclosesOwnAddress} predicate that governs the
     * street triple — <em>salon-locality-or-nothing</em> for an employed master,
     * <em>own-locality</em> for an independent one. The former
     * {@code COALESCE(sal.city_id, u.city_id)} fell through to the employed master's OWN city and
     * district whenever the salon's {@code city_id} was {@code NULL} (it is nullable — {@code V54}
     * line 62; legacy pre-Phase-10.3 salon rows carry none), printing exactly the locality
     * {@code MasterDetailResponse#fromPublic} masks for those very types. That fall-through was
     * unreachable while a role predicate kept salon masters out of this query; mobile Phase 111
     * removed the predicate and made it live.</p>
     *
     * <p><b>BOTH address sources are projected raw; the service picks one</b> (favourites
     * affiliation fix). Indices 7–9 are the master's OWN address off {@code users}; indices 15–17
     * are the EMPLOYING SALON's off {@code salons}. Neither is {@code COALESCE}d in SQL — the
     * choice is made in {@code FavoriteService#mapMasterRow} by the SAME
     * {@link com.beautica.master.entity.MasterType#disclosesOwnAddress} predicate that already
     * governs the locality pair, so address and locality cannot resolve through different entities
     * on one card. An independent master publishes their own address; a salon-affiliated master
     * publishes their salon's, <em>salon-or-nothing</em> — never the employee's personal one.
     *
     * <p>Suppressing the employee's own row stays load-bearing for the reason it always was: a
     * salon-employed master has no personal address to disclose, and {@code users.street} is not
     * even reliably their workplace — for a multi-salon owner it holds the MOST RECENTLY CREATED
     * salon's address, so the row could print salon B's street beside a master working in salon A.
     * An earlier revision projected {@code users} unconditionally and left the suppression to the
     * mobile client, which is not a server-side control. What changed since is only the fallback:
     * the card now shows the SALON's street (public business data, already returned unmasked by
     * {@link #findFavoriteSalonRows(UUID, Pageable)} and by the public salon profile) instead of
     * showing nothing at all.
     *
     * <p><b>{@code sal.id} / {@code sal.name} (13, 14)</b> back the card's affiliation line —
     * "works at &lt;salon&gt;", tappable through to the salon. They ride the {@code LEFT JOIN salons}
     * that was already here for the locality columns, so they cost no extra join. {@code NULL} for
     * an independent master, which is exactly what the client keys the line's presence off.
     *
     * <p><b>{@code last_service_name} and its {@code LEFT JOIN LATERAL} were REMOVED</b> (mobile
     * Phase 111): the approved favourites design no longer renders it, and the LATERAL existed
     * solely to compute it. Removing it deletes the only per-row subquery in this statement, so
     * the query is now a flat 3-table join — the pagination rationale below survives as a
     * §J bound, no longer as a LATERAL-evaluation bound.
     *
     * <p>The {@code INNER JOIN masters} drops any stale favorite whose target master
     * was deleted (polymorphic, no FK) — the row simply disappears from the list.
     * The join is deliberately NOT role-filtered, and since mobile Phase 111 that is
     * load-bearing rather than incidental: {@code FavoriteService#validateMasterTarget} now
     * admits every {@code MasterType}, so {@code SALON_MASTER}- and {@code SALON_OWNER}-typed
     * rows genuinely reach this query. Do not add a role predicate here.
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
     * is applied to every favourites surface so no list can outlive its salon. <b>Mobile Phase 111
     * promoted this from a provable no-op to a live predicate.</b> It was previously documented as
     * unreachable because {@code validateMasterTarget} admitted only {@code INDEPENDENT_MASTER}
     * targets, whose {@code salon_id} is structurally {@code NULL}; that role predicate is gone,
     * so a salon-employed master's favourite row now exists and this term is what stops a closed
     * salon's staff from lingering in every client's favourites. The {@code IS NULL} branch stays
     * mandatory: without it every independent master would vanish. Cost is one
     * {@code LEFT JOIN salons} in the count query; the paged query already joins {@code sal} for
     * the salon-locality columns, so it costs nothing there.
     *
     * <p><b>Anti-Bug audit LOW-1 (2026-07) → re-audit LOW (2026-08), now RESOLVED.</b> The
     * locality {@code COALESCE} was originally argued to be provably equivalent to a
     * {@code CASE WHEN sal.id IS NOT NULL …} guard, on the ground that {@code sal.*} was always
     * {@code NULL} for every joinable row. Mobile Phase 111 falsified that ground, and the
     * follow-up argument — "a salon with no recorded locality should show the master's rather
     * than nothing" — was wrong for the same reason the street triple is masked: for an employed
     * master {@code users.city_id} is a private datum the locked matrix does not publish, and for
     * a multi-salon owner it is not even the right salon's. Both are now projected raw and gated
     * in the service; the fall-through is gone.
     *
     * <p><b>Pagination (§E-3, §J):</b> the result is bounded by {@code Pageable}
     * (LIMIT/OFFSET). The
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
                   u.city_id                AS own_city_id,
                   u.district_id            AS own_district_id,
                   m.avg_rating             AS avg_rating,
                   u.street                 AS own_street,
                   u.building_no            AS own_building_no,
                   u.location_note          AS own_location_note,
                   m.master_type            AS master_type,
                   sal.city_id              AS salon_city_id,
                   sal.district_id          AS salon_district_id,
                   sal.id                   AS salon_id,
                   sal.name                 AS salon_name,
                   sal.street               AS salon_street,
                   sal.building_no          AS salon_building_no,
                   sal.location_note        AS salon_location_note
            FROM favorites f
            JOIN masters m ON m.id = f.target_id
            JOIN users u ON u.id = m.user_id
            LEFT JOIN salons sal ON sal.id = m.salon_id
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
                   u.city_id                AS own_city_id,
                   u.district_id            AS own_district_id,
                   m.avg_rating             AS avg_rating,
                   u.street                 AS own_street,
                   u.building_no            AS own_building_no,
                   u.location_note          AS own_location_note,
                   m.master_type            AS master_type,
                   sal.city_id              AS salon_city_id,
                   sal.district_id          AS salon_district_id,
                   sal.id                   AS salon_id,
                   sal.name                 AS salon_name,
                   sal.street               AS salon_street,
                   sal.building_no          AS salon_building_no,
                   sal.location_note        AS salon_location_note
            FROM favorites f
            JOIN masters m ON m.id = f.target_id
            JOIN users u ON u.id = m.user_id
            LEFT JOIN salons sal ON sal.id = m.salon_id
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
     *   <li>avg_rating — the PERSISTED {@code salons.avg_rating} column, surfaced as
     *       {@code NULL} when {@code salons.review_count = 0}</li>
     *   <li>street ({@code salons.street}, nullable)</li>
     *   <li>building_no ({@code salons.building_no}, nullable)</li>
     *   <li>location_note ({@code salons.location_note}, nullable)</li>
     * </ol>
     *
     * <p><b>Rating source changed (mobile Phase 111).</b> This query previously computed a live
     * {@code AVG(reviews.rating)} via a grouped {@code LEFT JOIN reviews}. It now reads the
     * persisted {@code salons.avg_rating} column instead, because
     * {@code ReviewRepository#recalculateSalonRating} redefined a salon's rating as the
     * EQUAL-WEIGHTED mean of its active masters' salon-scoped means — a per-review average is no
     * longer the same number. Keeping the old aggregate would have printed a different rating on
     * the favourites card than on the salon's own public profile. The
     * {@code CASE WHEN review_count = 0 THEN NULL} wrapper reproduces
     * {@code ReviewService#getSalonReviewSummary}'s {@code reviewCount == 0 ? null : avgRating}
     * rule exactly, so a never-reviewed salon still yields {@code null} rather than a fabricated
     * {@code 0.00} (the recalc {@code COALESCE}s the empty aggregate to {@code 0}, not
     * {@code NULL}). Dropping the join also removes the {@code GROUP BY}, so this is now a
     * two-table join with no aggregation.
     *
     * <p>The {@code INNER JOIN salons} drops any stale favorite whose target salon was
     * deleted.
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
                   CASE WHEN s.review_count = 0 THEN NULL ELSE s.avg_rating END AS avg_rating,
                   s.street                 AS street,
                   s.building_no            AS building_no,
                   s.location_note          AS location_note
            FROM favorites f
            JOIN salons s ON s.id = f.target_id
            WHERE f.client_id = :clientId
              AND f.target_type = 'SALON'
              AND s.is_active = true
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
                   CASE WHEN s.review_count = 0 THEN NULL ELSE s.avg_rating END AS avg_rating,
                   s.street                 AS street,
                   s.building_no            AS building_no,
                   s.location_note          AS location_note
            FROM favorites f
            JOIN salons s ON s.id = f.target_id
            WHERE f.client_id = :clientId
              AND f.target_type = 'SALON'
              AND s.is_active = true
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
