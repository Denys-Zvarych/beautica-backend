package com.beautica.salon.repository;

import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.entity.Salon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Salon data access, including the six native discovery projections behind
 * {@code SearchService.findSalonsByLocation} (the salon name / category / price
 * search facets — the per-service-type facet is built dynamically in
 * {@code SearchService}).
 *
 * <p><b>Bookable row-source (salon service-type fix).</b> A salon's client-visible
 * offering is <em>only</em> what its masters actually perform — it mirrors the
 * booking catalogue ({@code ServiceRepository.findBookableServicesBySalon}). So
 * every salon-owned {@code service_definitions} row that feeds discovery (the
 * category-match gate, the {@code ?q} service-name gate, the {@code serviceNames}
 * preview and the price band) is additionally gated by
 * {@code EXISTS(active master_services on an active master)}. A salon that owns a
 * service no active master performs must not match, must not price off it, and
 * must not list its name. The price band itself is the MIN–MAX of the performing
 * masters' EFFECTIVE prices ({@code COALESCE(price_override, base_price)} floor,
 * {@code COALESCE(price_override, RANGE ceiling)} ceiling) — never the raw
 * salon-owned {@code base_price} — so a per-master override shows through and an
 * unbookable owned service never drags the band.
 *
 * <p><b>Rotated-master correlation (HIGH leak fix).</b> Every {@code masters} join that
 * gates a salon's services carries the salon correlation ({@code mad.salon_id = s.id} in
 * the price-band lateral, {@code mmc/mmq.salon_id = s.id} in the category/{@code ?q}
 * {@code EXISTS} sub-selects, {@code mm2.salon_id = t.id} in the {@code serviceNames}
 * preview lateral). It can no longer drift between a data and a count statement because
 * there is no second statement: the six bodies are assembled from the shared
 * {@code SalonSearchSql.STATIC_*} fragments and paginate through {@code COUNT(*) OVER()}
 * plus an inner {@code LIMIT}. Without the correlation a master who
 * left the salon (or belongs to another salon) but still holds an active assignment to
 * this salon's owned definition would leak that definition's {@code serviceNames} / price
 * band into this salon's discovery card. The performing master must belong to the salon
 * that owns the definition, matching {@code SearchService.appendSalonBookableGate}'s
 * filtered path and {@code MasterServiceRepository.findBookableAssignmentsBySalon}.
 */
public interface SalonRepository extends JpaRepository<Salon, UUID> {

    List<Salon> findAllByOwnerIdAndIsActiveTrue(UUID ownerId);

    /**
     * Returns the oldest active salon for the given owner, ordered by {@code created_at ASC}.
     *
     * <p>Used by {@code MediaService.resolvePortfolioTarget} to deterministically select
     * which salon receives a portfolio upload when an owner has more than one active salon.
     * The {@code findTop} prefix limits the DB result to a single row, avoiding a
     * non-deterministic {@code salons.get(0)} pick on an unordered list (Perf MEDIUM F6).
     */
    Optional<Salon> findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(UUID ownerId);

    /**
     * Returns the IDs of all active salons owned by the given owner.
     *
     * <p>Used by {@code DashboardService.resolveScope} to collect every salon the SALON_OWNER
     * is allowed to see so that multi-salon owners receive revenue data across all their salons,
     * not just the oldest one (FIX 1 — HIGH security/correctness finding).
     */
    @Query("SELECT s.id FROM Salon s WHERE s.owner.id = :ownerId AND s.isActive = true")
    List<UUID> findIdsByOwnerIdAndIsActiveTrue(@Param("ownerId") UUID ownerId);

    /**
     * "Is {@code id} a salon owned by {@code ownerId}?" — the hottest authorization predicate in
     * the application: every {@code @PreAuthorize("@authz.canManageSalon(...)")} endpoint reaches
     * it through {@code AuthorizationService#hasManagementAccess}'s {@code SALON_OWNER} arm, and
     * {@code isOwnerOf} / {@code salonsShareOwner} call it too.
     *
     * <p><b>Why an explicit query (Phase 283 perf).</b> Left derived, Spring Data emitted
     * {@code select s1_0.id from salons s1_0 left join users o1_0 on o1_0.id=s1_0.owner_id where
     * s1_0.id=? and o1_0.id=? fetch first ? rows only} — the {@code OwnerId} path expression made
     * it join {@code users} purely to read back {@code users.id}, a value already sitting on the
     * {@code salons} row as the {@code owner_id} FK. The join can never change the result:
     * {@code salons.owner_id} is {@code UUID NOT NULL REFERENCES users(id)} (V3; later migrations
     * only touch <em>indexes</em> and a dropped UNIQUE, never the nullability or the FK), so every
     * {@code salons} row has exactly one matching {@code users} row and a {@code LEFT JOIN} on it
     * is a pure no-op. Dropping it leaves a single-table lookup on the {@code salons} PK.
     *
     * <p><b>The existential-probe shape is preserved deliberately.</b> This is {@code SELECT EXISTS
     * (SELECT 1 ...)}, NOT {@code count(...) > 0}: Postgres stops at the first qualifying row and
     * returns a bare boolean, exactly like the derived form's {@code fetch first 1 rows only}
     * probe. A count rewrite would have been a regression on the app's hottest gate, which is why
     * the previous pass declined the change outright. Native SQL because HQL has no way to express
     * a top-level {@code EXISTS} projection without either a {@code count} aggregate or a dummy
     * {@code FROM} — and the method signature (name, params, {@code boolean} return) is unchanged,
     * so every one of its ~40 call sites and mocks is untouched.
     *
     * <p><b>No {@code is_active} predicate</b>, matching the pre-existing behaviour exactly: a
     * deactivated salon still answers {@code true} for its owner. {@link
     * #findIdsByIdInAndOwnerId}'s Javadoc explains why that asymmetry with
     * {@link #findIdsByOwnerIdAndIsActiveTrue} is load-bearing; do not "tidy" it here.
     */
    @Query(
            value = "SELECT EXISTS (SELECT 1 FROM salons s WHERE s.id = :id AND s.owner_id = :ownerId)",
            nativeQuery = true)
    boolean existsByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /**
     * Batched twin of {@link #existsByIdAndOwnerId}: of the supplied salon ids, the subset owned
     * by {@code ownerId}. Backs
     * {@code AuthorizationService#filterBookingIdsWithProviderAuthority} — ONE statement for a
     * whole page of bookings instead of one {@code existsByIdAndOwnerId} per row (anti-bug §E: no
     * N+1). Cost is flat in page size: the caller de-duplicates the page's salon ids first, and a
     * page with no salon-employed master skips the call entirely.
     *
     * <p><b>Deliberately carries no {@code isActive} predicate</b>, unlike
     * {@link #findIdsByOwnerIdAndIsActiveTrue}. The per-row predicate this batches — the
     * {@code master.getSalon().getOwner().getId().equals(actorId)} arm of
     * {@code AuthorizationService#hasProviderAuthorityOverBooking(UUID, Booking)} and its
     * {@link #existsByIdAndOwnerId} fallback — has none either, and neither does the
     * {@code POST /client-reviews} write gate. Adding one here would make the listing's
     * {@code providerCanReviewClient} flag disagree with both {@code GET /bookings/&#123;id&#125;}
     * and the write endpoint for an owner whose salon has been deactivated.
     */
    @Query("SELECT s.id FROM Salon s WHERE s.id IN :salonIds AND s.owner.id = :ownerId")
    List<UUID> findIdsByIdInAndOwnerId(
            @Param("salonIds") Collection<UUID> salonIds, @Param("ownerId") UUID ownerId);

    /**
     * Lightweight owner-id projection (Phase 21.3 rotation PERF fix). Used by
     * {@code AuthorizationService.salonsShareOwner} to resolve the source salon's owner without
     * hydrating the full {@code Salon} entity (~20 columns) for a single FK read — mirrors the
     * existing {@code findOwnerUserId}-style projection pattern used elsewhere in this repository
     * layer (e.g. {@code ServiceRepository.findOwnerUserId}).
     */
    @Query("SELECT s.owner.id FROM Salon s WHERE s.id = :salonId")
    Optional<UUID> findOwnerIdById(@Param("salonId") UUID salonId);

    /**
     * Active salons sharing {@code salonId}'s owner, <b>excluding {@code salonId} itself</b>
     * (Phase 21.3b — {@code GET /salons/{salonId}/sibling-salons}). This is the destination
     * candidate set for the rotate-admin mutation
     * ({@code PATCH /salons/{salonId}/admins/{userId}/salon}), so its predicate is deliberately
     * the exact complement of what {@code SalonService.rotateAdmin} accepts:
     * {@code AuthorizationService.salonsShareOwner} (same owner) AND
     * {@link #existsByIdAndIsActiveTrue} (active) AND {@code destinationSalonId != salonId}
     * (the no-op 400 guard). A row this query returns is a legal rotation destination; a row it
     * omits would be rejected by that mutation. The two must not drift — pinned by
     * {@code SalonSiblingRotationParityIT}.
     *
     * <p><b>ONE statement (§E — no N+1).</b> The owner is resolved by a scalar sub-select on the
     * {@code salons} PK rather than a caller-side {@code findOwnerIdById} round-trip, so no
     * "resolve owner, then list" two-query chain exists. {@code salonId} not resolving to a salon
     * yields an empty list (the sub-select produces no row), never an error; the controller's
     * {@code @authz.canManageSalon} gate already denies that case with 403 before this runs.
     *
     * <p><b>No {@code JOIN FETCH s.owner} (Perf MEDIUM-1).</b> It was present to serve
     * {@code SalonResponse.from}'s {@code salon.getOwner().getId()} read, and cost 38 {@code users}
     * columns per row — including {@code password_hash} and every verification/password-reset code
     * hash — to produce one UUID that is already on the {@code salons} row as the FK. The response
     * shape is now {@code SiblingSalonOption}, which reads no association at all, so nothing here
     * touches the owner proxy. Even under the old DTO the fetch was unnecessary: {@code /salons/mine}
     * maps the identical {@code SalonResponse.from} off {@link #findAllByOwnerIdAndIsActiveTrue} with
     * no {@code JOIN FETCH} and emits no follow-up {@code users} select — Hibernate reads the
     * identifier straight off the uninitialised proxy.
     *
     * <p>The owner predicate is expressed as {@code s.owner.id} (not an explicit join), matching
     * {@link #findIdsByOwnerIdAndIsActiveTrue} and {@link #findIdsByIdInAndOwnerId} in this same
     * interface: an {@code @ManyToOne} identifier dereference compiles to the {@code owner_id} FK
     * column with no {@code users} join at all.
     *
     * <p>Ordered by {@code created_at ASC, id ASC} — index-aligned with the partial
     * {@code idx_salons_owner_active_created} ({@code owner_id, created_at WHERE is_active = true},
     * V46) that already serves the {@code owner_id + is_active} predicate, and matching the
     * existing owner-salon ordering convention of
     * {@link #findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc}. No new index is needed. The
     * trailing {@code id} is a UNIQUE tiebreaker (Perf MEDIUM-2): {@code created_at} alone is not
     * unique — two salons created in the same statement/tick share a timestamp and would come back
     * in an arbitrary, run-to-run-unstable order, which the picker renders as a jumping list. The
     * index still drives the scan; the tiebreaker only resolves ties <em>within</em> an equal
     * {@code created_at} group (Postgres Incremental Sort), bounded by
     * {@code SalonService.MAX_ACTIVE_SALONS_PER_OWNER} rows.
     *
     * <p><b>Constructor projection, not an entity (Perf LOW-B).</b> Returning {@code Salon} made
     * Hibernate select all 22 {@code salons} columns and hydrate one managed entity per row just to
     * build a 4-field {@code SiblingSalonOption}. {@code SELECT new …} emits exactly the four
     * columns the DTO carries and hydrates nothing into the persistence context, so this finder is
     * structurally incapable of dragging an association (or a widened row) back in — see
     * {@code SalonSiblingProjectionShapeIT}.
     *
     * <p>{@code createdAt} is ordered on but not selected. That is legal — the query has no
     * {@code DISTINCT} and no {@code GROUP BY}, so Postgres may order by any column of the scanned
     * relation whether or not it is in the target list — and it is what keeps the plan on
     * {@code idx_salons_owner_active_created}: the index supplies {@code created_at} pre-sorted and
     * the {@code id} tiebreaker is resolved by an Incremental Sort within each equal-timestamp
     * group, exactly as under the entity form. Projecting fewer columns cannot change the access
     * path; it only narrows the heap fetch.
     */
    @Query("""
            SELECT new com.beautica.salon.dto.SiblingSalonOption(s.id, s.name, s.street, s.buildingNo)
            FROM Salon s
            WHERE s.isActive = true
              AND s.id <> :salonId
              AND s.owner.id = (SELECT src.owner.id FROM Salon src WHERE src.id = :salonId)
            ORDER BY s.createdAt ASC, s.id ASC
            """)
    List<SiblingSalonOption> findActiveSiblingsBySalonId(@Param("salonId") UUID salonId);

    /**
     * Active-salon headcount for one owner — backs the per-owner portfolio cap enforced by
     * {@code SalonService.createSalon} ({@code MAX_ACTIVE_SALONS_PER_OWNER}, Perf LOW-3).
     *
     * <p>Deliberately separate from {@link #existsByOwnerId}, which
     * {@code createSalon} also calls: that one carries <b>no</b> {@code isActive} predicate because
     * it decides {@code is_primary} ("has this owner EVER had a salon?"), whereas the cap governs
     * the live portfolio — a deactivated salon must free its slot. Collapsing the two would either
     * make a deactivated first salon re-claim {@code is_primary} or make the cap count soft-deleted
     * rows. Both are index-served by the partial {@code idx_salons_owner_active_created} (V46).
     */
    long countByOwnerIdAndIsActiveTrue(UUID ownerId);

    /**
     * Lightweight existence + active-flag check (Phase 21.3 rotation PERF fix). Used by
     * {@code SalonService.rotateAdmin} to validate the destination salon without loading the full
     * entity — the destination UUID is written directly onto {@code User.salonId} and no entity
     * fields are otherwise needed. Returns {@code false} for both "does not exist" and "exists but
     * inactive", which the caller treats identically (Anti-Bug §D — no destination-status oracle).
     */
    boolean existsByIdAndIsActiveTrue(UUID id);

    /**
     * Nulls both image-URL pointer columns for a deleted salon (Phase 268 D2/D4) — called by
     * {@code SalonService} strictly AFTER {@code MediaService#deleteBySalon} has attempted the R2
     * deletes for the corresponding blobs, never before (D4's R2-first-then-DB ordering; the DB
     * pointer is dropped whether or not the R2 call succeeded — a retained pointer would re-publish
     * a deleted salon's photo at a live public URL, which is the outcome D4 exists to prevent).
     *
     * <p>Runs OUTSIDE the salon-deletion transaction — after commit, on the same thread as the R2
     * sweep (D8) — so this is its own tiny transaction, not a mutation the caller's {@code @Transactional}
     * boundary covers.
     */
    @Modifying
    @Query("UPDATE Salon s SET s.avatarUrl = null, s.coverImageUrl = null WHERE s.id = :salonId")
    int nullImageUrls(@Param("salonId") UUID salonId);

    // True iff the given owner already has at least one salon (primary or not).
    // Used in SalonService.createSalon to decide is_primary = true/false.
    boolean existsByOwnerId(UUID ownerId);

    // Returns the owner's primary salon, if one exists.
    Optional<Salon> findByOwnerIdAndIsPrimaryTrue(UUID ownerId);

    Optional<Salon> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Query("SELECT s FROM Salon s JOIN FETCH s.owner WHERE s.id = :id AND s.isActive = true")
    Optional<Salon> findByIdAndIsActiveTrueWithOwner(@Param("id") UUID id);

    /**
     * Active salons in a specific discovery district (district-primary branch
     * of the Phase 10.5 FK location filter).
     *
     * <p><b>Plan shape (Phase 10.8, MEDIUM-1):</b> a single-column equality on
     * {@code district_id} under a constant {@code is_active = true}. No
     * disjunctive NULL-guard OR-chain spanning two columns, so Postgres can
     * index-serve it via {@code idx_salons_district_id} (V54) at any scale —
     * the predicate is SARGable. {@link SearchService#searchSalons} dispatches
     * here when a districtId is resolved; the caller never passes
     * {@code null}.
     *
     * <p>Replaces the removed free-text {@code findByFilter} (exact
     * string-equality on {@code city}/{@code region} — the Phase 10.5 bug);
     * no legacy variant is kept alongside (§E). A salon's discovery locality
     * is its own {@code district_id} — there is no salon-to-salon link to
     * resolve through (that resolution is the master-only
     * {@code SALON_MASTER → salon} case). The {@code Page} return type makes
     * Spring Data emit the matching {@code COUNT(*)} companion.
     *
     * <p><b>§E — search path MUST use the projection overload.</b>
     * {@link SearchService} must call
     * {@link #findActiveByDistrictIdAsProjection} so
     * only the columns needed by {@link SalonSearchProjection} are fetched.
     * This full-entity overload remains for non-search callers that genuinely
     * need the full {@code Salon} graph.
     *
     * @param districtId resolved discovery district id (never {@code null})
     */
    @Query("""
            SELECT s FROM Salon s
            WHERE s.isActive = true
              AND s.districtId = :districtId
            """)
    Page<Salon> findActiveByDistrictId(
            @Param("districtId") UUID districtId,
            Pageable pageable
    );

    /**
     * Projection overload of {@link #findActiveByDistrictId}: fetches only the
     * columns needed for salon discovery plus the bookable price band and
     * category-scoped {@code serviceNames} preview — no full entity hydration,
     * no lazy proxy for the {@code owner} association.
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation} to
     * eliminate the LOW PERF finding ("Loads Salon entity then maps via
     * Page#map"). The price/name LATERAL and the category / {@code q}
     * service-name gates are all restricted to <em>bookable</em> salon-owned
     * services (see the interface-level Javadoc).
     *
     * <p><b>Free-text {@code q}:</b> the predicate is spliced in from
     * {@link SalonSearchSql#STATIC_Q_GROUP_PREDICATE} — the single definition
     * shared with the dynamic per-service-filtered builder in
     * {@code SearchService}, so the two can no longer drift apart. It declares
     * {@link SalonSearchSql#STATIC_TOKEN_PARAM_COUNT} bind slots
     * ({@code q0}…{@code q3}), one per whitespace token of the caller's query.
     * The tokens are <b>group-scoped</b>, not independently ANDed at the salon
     * level: every token must be satisfied by the salon name or by ONE single
     * bookable service — the same service for all service-satisfied tokens, so
     * tokens can no longer be spread across two different services. Every slot
     * must be bound; an unused slot is bound {@code null} (its branch
     * short-circuits to TRUE). All-null disables the filter.</p>
     *
     * <p><b>{@code matchedServiceNames}:</b> the post-{@code LIMIT}
     * {@link SalonSearchSql#STATIC_MATCHED_NAMES_LATERAL} adds the
     * {@code matched_service_names} column — the bookable services whose own name
     * explains the {@code q} match. Empty when no {@code q} was supplied or when
     * the salon's own name carried the whole match.</p>
     *
     * @param districtId resolved discovery district id (never {@code null})
     * @param q0 first {@code %token%} ILIKE pattern, or {@code null}
     * @param q1 second {@code %token%} ILIKE pattern, or {@code null}
     * @param q2 third {@code %token%} ILIKE pattern, or {@code null}
     * @param q3 fourth {@code %token%} ILIKE pattern, or {@code null}
     * @param sortMode {@code SearchSort.name()} — selects the {@code ORDER BY} key
     * @param limit page size (bound INSIDE the derived table, see the class Javadoc)
     * @param offset row offset
     */
    @Query(value = SalonSearchSql.STATIC_PROJECTION_HEAD
            + SalonSearchSql.STATIC_DISTRICT_PREDICATE
            + SalonSearchSql.STATIC_CATEGORY_GATE
            + SalonSearchSql.STATIC_Q_GROUP_PREDICATE
            + SalonSearchSql.STATIC_PRICE_PREDICATE
            + SalonSearchSql.STATIC_ORDER_LIMIT_TAIL
            + SalonSearchSql.STATIC_NAME_PREVIEW_LATERAL
            + SalonSearchSql.STATIC_MATCHED_NAMES_LATERAL
            + SalonSearchSql.STATIC_OUTER_ORDER_BY,
            nativeQuery = true)
    List<SalonSearchProjection> findActiveByDistrictIdAsProjection(
            @Param("districtId") UUID districtId,
            @Param("category") String category,
            @Param("q0") String q0,
            @Param("q1") String q1,
            @Param("q2") String q2,
            @Param("q3") String q3,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("sortMode") String sortMode,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /**
     * No-price-bound variant of {@link #findActiveByDistrictIdAsProjection}
     * (HIGH PERF gate). Identical apart from omitting the price band-overlap
     * predicate — the price-range LATERAL still runs because
     * {@code priceMin}/{@code priceMax} are display columns on
     * {@link SalonSearchProjection}. {@link SearchService} dispatches here when
     * {@code minPrice == null && maxPrice == null}.
     *
     * <p>The gate used to be about the separate {@code countQuery}: the priced
     * overload's count re-ran the per-salon price LATERAL, the no-price one did not.
     * There is no second statement any more ({@code COUNT(*) OVER()} — see
     * {@link SalonSearchSql}), so the two overloads now differ only in the two
     * band-overlap lines, and the gate survives purely to keep those out of the
     * plan when no price filter was supplied.</p>
     *
     * @param districtId resolved discovery district id (never {@code null})
     */
    @Query(value = SalonSearchSql.STATIC_PROJECTION_HEAD
            + SalonSearchSql.STATIC_DISTRICT_PREDICATE
            + SalonSearchSql.STATIC_CATEGORY_GATE
            + SalonSearchSql.STATIC_Q_GROUP_PREDICATE
            + SalonSearchSql.STATIC_ORDER_LIMIT_TAIL
            + SalonSearchSql.STATIC_NAME_PREVIEW_LATERAL
            + SalonSearchSql.STATIC_MATCHED_NAMES_LATERAL
            + SalonSearchSql.STATIC_OUTER_ORDER_BY,
            nativeQuery = true)
    List<SalonSearchProjection> findActiveByDistrictIdNoPriceAsProjection(
            @Param("districtId") UUID districtId,
            @Param("category") String category,
            @Param("q0") String q0,
            @Param("q1") String q1,
            @Param("q2") String q2,
            @Param("q3") String q3,
            @Param("sortMode") String sortMode,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /**
     * Active salons in a specific discovery city (city-only branch of the
     * Phase 10.5 FK location filter — a districted city without a resolved
     * district widens to city level on the read side).
     *
     * <p><b>Plan shape (Phase 10.8, MEDIUM-1):</b> a single-column equality on
     * {@code city_id} under a constant {@code is_active = true}, SARGable and
     * index-served by {@code idx_salons_city_id} (V54) at any scale.
     * {@link SearchService#searchSalons} dispatches here when no district was
     * resolved but a cityId is present; the caller never passes {@code null}.
     *
     * <p><b>§E — search path MUST use the projection overload.</b>
     * {@link SearchService} must call
     * {@link #findActiveByCityIdAsProjection}.
     * This full-entity overload remains for non-search callers.
     *
     * @param cityId resolved discovery city id (never {@code null})
     */
    @Query("""
            SELECT s FROM Salon s
            WHERE s.isActive = true
              AND s.cityId = :cityId
            """)
    Page<Salon> findActiveByCityId(
            @Param("cityId") UUID cityId,
            Pageable pageable
    );

    /**
     * Projection overload of {@link #findActiveByCityId}: fetches only the
     * columns needed for salon discovery plus the bookable price band and
     * category-scoped {@code serviceNames} preview.
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation}.
     *
     * @param cityId resolved discovery city id (never {@code null})
     */
    @Query(value = SalonSearchSql.STATIC_PROJECTION_HEAD
            + SalonSearchSql.STATIC_CITY_PREDICATE
            + SalonSearchSql.STATIC_CATEGORY_GATE
            + SalonSearchSql.STATIC_Q_GROUP_PREDICATE
            + SalonSearchSql.STATIC_PRICE_PREDICATE
            + SalonSearchSql.STATIC_ORDER_LIMIT_TAIL
            + SalonSearchSql.STATIC_NAME_PREVIEW_LATERAL
            + SalonSearchSql.STATIC_MATCHED_NAMES_LATERAL
            + SalonSearchSql.STATIC_OUTER_ORDER_BY,
            nativeQuery = true)
    List<SalonSearchProjection> findActiveByCityIdAsProjection(
            @Param("cityId") UUID cityId,
            @Param("category") String category,
            @Param("q0") String q0,
            @Param("q1") String q1,
            @Param("q2") String q2,
            @Param("q3") String q3,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("sortMode") String sortMode,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /**
     * No-price-bound variant of {@link #findActiveByCityIdAsProjection}
     * (HIGH PERF gate). Plain {@code COUNT(*)} count query (no LATERAL) for the
     * common no-price-filter path; the data query keeps the LATERAL because the
     * price band is a display column. {@link SearchService} dispatches here when
     * both price bounds are null.
     *
     * @param cityId resolved discovery city id (never {@code null})
     */
    @Query(value = SalonSearchSql.STATIC_PROJECTION_HEAD
            + SalonSearchSql.STATIC_CITY_PREDICATE
            + SalonSearchSql.STATIC_CATEGORY_GATE
            + SalonSearchSql.STATIC_Q_GROUP_PREDICATE
            + SalonSearchSql.STATIC_ORDER_LIMIT_TAIL
            + SalonSearchSql.STATIC_NAME_PREVIEW_LATERAL
            + SalonSearchSql.STATIC_MATCHED_NAMES_LATERAL
            + SalonSearchSql.STATIC_OUTER_ORDER_BY,
            nativeQuery = true)
    List<SalonSearchProjection> findActiveByCityIdNoPriceAsProjection(
            @Param("cityId") UUID cityId,
            @Param("category") String category,
            @Param("q0") String q0,
            @Param("q1") String q1,
            @Param("q2") String q2,
            @Param("q3") String q3,
            @Param("sortMode") String sortMode,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /**
     * All active salons (no-locality-filter branch of the Phase 10.5 FK
     * location filter — both cityId and districtId resolved to {@code null}).
     *
     * <p><b>Plan shape (Phase 10.8, MEDIUM-1):</b> a single constant predicate
     * {@code is_active = true} with no locality column reference — no
     * non-SARGable OR-chain. Spring Data derives the matching {@code COUNT(*)}
     * companion. {@link SearchService#searchSalons} dispatches here only when
     * no locality filter was supplied.
     *
     * <p><b>§E — search path MUST use the projection overload.</b>
     * {@link SearchService} must call
     * {@link #findByIsActiveTrueAsProjection}.
     * This full-entity overload remains for non-search callers.
     */
    Page<Salon> findByIsActiveTrue(Pageable pageable);

    /**
     * Projection overload of {@link #findByIsActiveTrue}: fetches only the
     * columns needed for salon discovery plus the bookable price band and
     * category-scoped {@code serviceNames} preview.
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation}.
     */
    @Query(value = SalonSearchSql.STATIC_PROJECTION_HEAD
            + SalonSearchSql.STATIC_CATEGORY_GATE
            + SalonSearchSql.STATIC_Q_GROUP_PREDICATE
            + SalonSearchSql.STATIC_PRICE_PREDICATE
            + SalonSearchSql.STATIC_ORDER_LIMIT_TAIL
            + SalonSearchSql.STATIC_NAME_PREVIEW_LATERAL
            + SalonSearchSql.STATIC_MATCHED_NAMES_LATERAL
            + SalonSearchSql.STATIC_OUTER_ORDER_BY,
            nativeQuery = true)
    List<SalonSearchProjection> findByIsActiveTrueAsProjection(
            @Param("q0") String q0,
            @Param("q1") String q1,
            @Param("q2") String q2,
            @Param("q3") String q3,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("category") String category,
            @Param("sortMode") String sortMode,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /**
     * No-price-bound variant of {@link #findByIsActiveTrueAsProjection}
     * (HIGH PERF gate). Plain {@code COUNT(*)} count query (no LATERAL) for the
     * common no-price-filter path; the data query keeps the LATERAL because the
     * price band is a display column. {@link SearchService} dispatches here when
     * both price bounds are null and no locality filter was supplied.
     */
    @Query(value = SalonSearchSql.STATIC_PROJECTION_HEAD
            + SalonSearchSql.STATIC_CATEGORY_GATE
            + SalonSearchSql.STATIC_Q_GROUP_PREDICATE
            + SalonSearchSql.STATIC_ORDER_LIMIT_TAIL
            + SalonSearchSql.STATIC_NAME_PREVIEW_LATERAL
            + SalonSearchSql.STATIC_MATCHED_NAMES_LATERAL
            + SalonSearchSql.STATIC_OUTER_ORDER_BY,
            nativeQuery = true)
    List<SalonSearchProjection> findByIsActiveTrueNoPriceAsProjection(
            @Param("q0") String q0,
            @Param("q1") String q1,
            @Param("q2") String q2,
            @Param("q3") String q3,
            @Param("category") String category,
            @Param("sortMode") String sortMode,
            @Param("limit") int limit,
            @Param("offset") long offset
    );
}
