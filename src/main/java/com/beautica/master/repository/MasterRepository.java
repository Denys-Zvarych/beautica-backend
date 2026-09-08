package com.beautica.master.repository;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
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

public interface MasterRepository extends JpaRepository<Master, UUID> {

    Optional<Master> findByUserId(UUID userId);

    /**
     * Same as {@link #findByUserId} but also JOIN FETCH-es the {@code salon} association,
     * eliminating the extra {@code SELECT * FROM salons WHERE id = ?} fired when callers
     * dereference {@code master.getSalon().getId()} (MEDIUM F2+F3).
     *
     * <p>Do NOT use for {@link com.beautica.master.service.MasterService#getMasterByUserId}
     * — that cached method never dereferences {@code salon} and must keep its existing query
     * to avoid unnecessary join overhead.
     */
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.salon
            WHERE m.user.id = :userId
            """)
    Optional<Master> findByUserIdWithSalon(@Param("userId") UUID userId);

    /**
     * Fetches the master together with both its {@code user} and {@code salon} associations
     * in a single query. Used by
     * {@link com.beautica.master.service.MasterService#getMyMasterDetail(java.util.UUID)}
     * so the cached result is a fully-initialized DTO rather than a detached JPA entity
     * with unresolvable lazy proxies (CRITICAL Anti-Bug §E).
     *
     * <p>Prefer this over the bare {@link #findByUserId} wherever the caller needs to
     * dereference either {@code master.getUser()} or {@code master.getSalon()}.
     *
     * @deprecated Use {@link #findActiveByUserIdWithUserAndSalon} for authenticated
     *     self-service endpoints — deactivated masters must not access their own profile.
     *     This variant (no {@code isActive} filter) is retained for internal callers
     *     (e.g. admin paths) that explicitly need to resolve any master regardless of status.
     */
    @Deprecated
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.user
            LEFT JOIN FETCH m.salon
            WHERE m.user.id = :userId
            """)
    Optional<Master> findByUserIdWithUserAndSalon(@Param("userId") UUID userId);

    /**
     * Fetches the <em>active</em> master together with both its {@code user} and {@code salon}
     * associations in a single query. Filters {@code isActive = true} at the DB level so a
     * deactivated master with a still-valid JWT cannot call {@code GET /masters/me} or any
     * other self-service endpoint backed by this finder (Anti-Bug §E, backlog LOW master/service).
     *
     * <p>Use this method for all authenticated self-service reads
     * ({@link com.beautica.master.service.MasterService#getMyMasterDetail} and
     * {@link com.beautica.master.service.MasterService#getMasterByUserId}).
     */
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.user
            LEFT JOIN FETCH m.salon
            WHERE m.user.id = :userId
              AND m.isActive = true
            """)
    Optional<Master> findActiveByUserIdWithUserAndSalon(@Param("userId") UUID userId);

    /** @deprecated No JOIN FETCH on user — triggers N+1. Use {@link #findBySalonIdAndIsActiveTrueWithUser} instead. */
    @Deprecated
    Page<Master> findBySalonIdAndIsActiveTrue(UUID salonId, Pageable pageable);

    @Query(
        value = "SELECT m FROM Master m JOIN FETCH m.user WHERE m.salon.id = :salonId AND m.isActive = true",
        countQuery = "SELECT COUNT(m) FROM Master m WHERE m.salon.id = :salonId AND m.isActive = true"
    )
    Page<Master> findBySalonIdAndIsActiveTrueWithUser(@Param("salonId") UUID salonId, Pageable pageable);

    boolean existsBySalonIdAndUserIdAndIsActiveTrue(UUID salonId, UUID userId);

    /**
     * Returns {@code true} iff the given user has an active master row of the given type in the
     * given salon. Used by the owner-as-master authorization fast-path (Phase 12.3) and by
     * {@code MasterService.createMasterForOwner} to perform the idempotent active-row check.
     */
    boolean existsByUserIdAndSalonIdAndMasterTypeAndIsActiveTrue(
            UUID userId, UUID salonId, MasterType masterType);

    /**
     * Returns {@code true} iff the given user has an <em>active</em> master row of the given type,
     * in ANY salon. Backs {@code UserService.getProfile}'s derived
     * {@code UserProfileResponse.hasMasterProfile} (Phase 265) — the owner-as-master toggle state,
     * which is the presence of this row, never a stored column.
     *
     * <p><b>Why this is not a duplicate of
     * {@link #existsByUserIdAndSalonIdAndMasterTypeAndIsActiveTrue}.</b> That method answers
     * "…in THIS salon", and every one of its callers has a {@code salonId} in hand from the path
     * ({@code /salons/{salonId}/master}). {@code GET /users/me} has no path salon and must not
     * invent one: {@code users.salon_id} is the owner's primary salon and would make the flag read
     * {@code false} for an owner whose master row sits in a different salon of theirs. Passing a
     * fabricated salon into the salon-scoped variant is exactly the bug this narrower predicate
     * avoids, so the two coexist deliberately (§E-1) with disjoint call sites.
     *
     * <p>The {@code isActive} term is load-bearing, not defensive: the DELETE toggle endpoint
     * <em>deactivates</em> the row rather than hard-deleting it, so a plain
     * {@code existsByUserIdAndMasterType} would report every owner who has ever opted in as still
     * opted in, forever.
     */
    boolean existsByUserIdAndMasterTypeAndIsActiveTrue(UUID userId, MasterType masterType);

    boolean existsByIdAndSalonId(UUID id, UUID salonId);

    /**
     * Ownership self-assertion for the {@code SALON_MASTER}/{@code INDEPENDENT_MASTER} account
     * self-deletion booking cascade (Phase 301 Q3) — mirrors {@link #existsByIdAndSalonId}'s
     * pattern (used at {@code BookingService.java:1559} by the master-removal cascade), just keyed
     * by the acting USER rather than by the salon owner. {@code
     * BookingService#disposeFutureConfirmedForMasterSelfDelete} calls this to prove {@code
     * masterId} actually belongs to the deleting caller before bulk-declining any of their
     * bookings — the caller cannot lean on role-based authorization here, since {@code
     * SALON_MASTER} is rejected by every existing booking-mutation seam's fast path (§1 of the
     * phase 301 plan).
     */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    /**
     * Returns {@code true} if a master with the given {@code id} belongs to any of the
     * provided {@code salonIds}. Collapses the N-query ownership loop into a single
     * {@code WHERE id = ? AND salon_id IN (...)} existence check.
     */
    boolean existsByIdAndSalonIdIn(UUID id, Collection<UUID> salonIds);

    /**
     * Fetches the master together with its {@code user} and {@code salon} associations in a single
     * query. Backs {@link com.beautica.common.security.AuthorizationService}'s master-scoped
     * predicates ({@code canManageMaster}, {@code canManageMasterSchedule},
     * {@code canReadMasterSchedule}) and every by-id master read/write path:
     * {@code MasterService} (getMasterDetail, upsertWorkingHours, deactivateMaster,
     * rotateMasterToSalon), {@code MasterScheduleService#loadActiveMaster},
     * {@code FavoriteService#validateMasterTarget} and {@code BookingService#doCreateBooking}.
     *
     * <p><b>Renamed from {@code findByIdWithSalonAndOwner} (Phase 26.9 follow-up).</b> The old name
     * was wrong twice over once the owner fetch went: it advertised a {@code salon.owner} fetch that
     * no longer happens, and it never mentioned {@code m.user} even though that association has
     * always been fetch-joined here. The new name is the by-id sibling of the existing
     * {@link #findByUserIdWithUserAndSalon} / {@link #findActiveByUserIdWithUserAndSalon} and
     * introduces no new vocabulary. A name that advertises a fetch the query does not perform is
     * exactly the condition under which this fetch was previously re-added on the
     * {@code BookingRepository} side — hence the rename rather than a comment.
     *
     * <p><b>Deliberately does NOT fetch {@code salon.owner}</b> — the fourth and final removal of
     * this dead fetch, after {@code BookingRepository}'s {@code findAllByIdsWithGraph},
     * {@code findActiveByClientIdAndIdempotencyKey} and {@code findByIdWithFullGraph}. It was dead
     * for two independent reasons:
     * <ol>
     *   <li>{@code Salon.owner} is {@code @ManyToOne(LAZY)} on a {@code nullable = false} column
     *       whose FK lives on {@code salons}, so Hibernate always hands back a proxy WITHOUT a
     *       statement. The {@code getOwner() != null} guards on the callers therefore never
     *       initialise it and never evaluate false — the fetch changed no branch.</li>
     *   <li>Every {@code getOwner()} in {@code src/main/java} is either such a null check or
     *       {@code .getId()} ({@code AuthorizationService} x7, {@code MasterService#requireOwner},
     *       {@code SalonResponse#from}). Entities map their {@code @Id} on the field with no
     *       {@code @Access} override, so Hibernate serves the identifier straight off the
     *       uninitialised proxy without a statement.</li>
     * </ol>
     * The fetch cost an extra join into {@code users} plus a full hydrated {@code User} row
     * ({@code password_hash} included) on all ten callers — including the cached, unauthenticated
     * {@code GET /api/v1/masters/{masterId}} — and bought nothing.
     *
     * <p><b>Correction to this method's former javadoc.</b> It justified the fetch as needed for
     * "authorization checks that run outside an active JPA session". That was wrong on both halves.
     * Every caller runs inside a {@code @Transactional} service method (the {@code AuthorizationService}
     * predicates are invoked from {@code @PreAuthorize} SpEL, which Spring Data serves in its own
     * transaction), so no caller is session-less to begin with; and even where one were, reading an
     * identifier off a to-one proxy needs no session — it is answered from the FK the proxy was
     * built with, detached or not, and cannot raise {@code LazyInitializationException}. The owner
     * remains fully <em>reachable</em> through {@code getSalon().getOwner()}; it is simply no longer
     * <em>hydrated</em>. A caller that ever needs a non-identifier owner property must add its own
     * fetch on its own query rather than re-widening this shared one.
     *
     * <p>Pinned by {@code MasterOwnerFetchContractIT} — a statement count cannot see a re-added
     * fetch join (it widens an existing join rather than issuing a statement), so that gate asserts
     * proxy non-initialisation plus an absolute hydrated-entity count instead.
     */
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.user
            LEFT JOIN FETCH m.salon
            WHERE m.id = :masterId
            """)
    Optional<Master> findByIdWithUserAndSalon(@Param("masterId") UUID masterId);

    /**
     * The id of the master's salon, <b>but only when that salon is owned by {@code ownerId}</b> and
     * still open — otherwise empty.
     *
     * <p>Used by {@code StaffBookingScopeResolver} on its ONE branch that has to disambiguate: a
     * {@code SALON_OWNER} who owns more than one salon, creating a staff booking through
     * {@code POST /masters/&#123;masterId&#125;/bookings}, which carries no {@code &#123;salonId&#125;}
     * to pick with (phase-171 amendment A2).
     *
     * <p><b>The ownership predicate is inside the query on purpose.</b> The value feeds
     * {@code StaffBookingScope.InSalon}, which {@code StaffBookingService#assertMasterInScope} then
     * compares against the master's salon. A plain "read the master's salon id" projection would
     * make that comparison a tautology; here the salon cannot be returned at all unless the actor
     * owns it, so the emitted scope is always the caller's authority rather than the target's
     * attribute. Do not add a non-owner-scoped variant beside it (§E-1).
     *
     * <p>{@code JOIN} (inner) is correct here rather than {@code LEFT JOIN}: a salon-less
     * independent master has no owned salon to return, and empty is exactly the right answer.
     *
     * @param masterId the target master
     * @param ownerId  the authenticated {@code SALON_OWNER}
     */
    @Query("""
            SELECT s.id FROM Master m
            JOIN m.salon s
            WHERE m.id = :masterId
              AND s.owner.id = :ownerId
              AND s.isActive = true
            """)
    Optional<UUID> findSalonIdByIdAndSalonOwnerId(
            @Param("masterId") UUID masterId, @Param("ownerId") UUID ownerId);

    /**
     * Re-computes and persists {@code masters.min_effective_price} for a single
     * master as a single UPDATE — eliminates the load/mutate/save round-trip.
     *
     * <p>The subquery mirrors {@code MIN(COALESCE(ms.price_override, sd.base_price))}
     * across active {@code master_services} rows with an active
     * {@code service_definitions} row. The result is {@code null} when the master
     * has no active services (semantically: no bookable price to display).
     *
     * <p>Must be called inside an existing {@code @Transactional} context — callers
     * in {@link com.beautica.service.service.ServiceCatalogService} satisfy this.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Master m
            SET m.minEffectivePrice = (
                SELECT MIN(COALESCE(ms.priceOverride, sd.basePrice))
                FROM MasterServiceAssignment ms
                JOIN ServiceDefinition sd ON ms.serviceDefinition.id = sd.id
                WHERE ms.master.id = :masterId
                  AND ms.isActive = true
                  AND sd.isActive = true
            )
            WHERE m.id = :masterId
            """)
    void refreshMinEffectivePrice(@Param("masterId") UUID masterId);

    /**
     * Bulk variant of {@link #refreshMinEffectivePrice} — collapses N individual UPDATE
     * round-trips into a single statement (Fix MEDIUM-6 PERF).
     *
     * <p>Replaces the {@code affectedMasterIds.forEach(masterRepository::refreshMinEffectivePrice)}
     * loop in {@code ServiceCatalogService.deactivateServiceDefinition}.
     *
     * <p>Must be called inside an existing {@code @Transactional} context.
     * {@code clearAutomatically = true} ensures the first-level cache is invalidated
     * after the bulk UPDATE so subsequent reads see the refreshed price.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Master m
            SET m.minEffectivePrice = (
                SELECT MIN(COALESCE(ms.priceOverride, sd.basePrice))
                FROM MasterServiceAssignment ms
                JOIN ServiceDefinition sd ON ms.serviceDefinition.id = sd.id
                WHERE ms.master.id = m.id
                  AND ms.isActive = true
                  AND sd.isActive = true
            )
            WHERE m.id IN :masterIds
            """)
    void refreshMinEffectivePriceForAll(@Param("masterIds") List<UUID> masterIds);

    /**
     * Global uniqueness check for the public booking slug (Phase 13.1). Backs the
     * {@code BookingSlugService} retry loop — a candidate slug is rejected if any
     * master already owns it. Spans the whole {@code masters} table (single-table
     * model — there is no separate {@code independent_masters} table), so this one
     * query covers SALON_MASTER, SALON_OWNER, and INDEPENDENT_MASTER rows.
     */
    boolean existsByBookingSlug(String bookingSlug);

    /**
     * Resolves a public booking slug to its master, JOIN FETCH-ing the {@code user}
     * association so the caller can read {@code firstName}/{@code lastName}/
     * {@code avatarUrl}/{@code bio} (which live on {@link com.beautica.user.User},
     * not on {@link Master}) without a lazy load or LazyInitializationException
     * (Anti-Bug §E). Filters {@code isActive = true} so a deactivated master's
     * public booking page returns 404.
     *
     * <p><b>SALON-ACTIVE GUARD (2026-08 security re-audit HIGH).</b> The
     * {@code (s IS NULL OR s.isActive = true)} term is the JPQL mirror of
     * {@link com.beautica.booking.domain.MasterBookability#isBookable(Master)} — see that class
     * for the rule and why the two must never diverge. It is enforced HERE, in the finder, rather
     * than in each caller, because BOTH public slug surfaces share this one query and each had a
     * different gap: {@code GuestBookingService#createGuestBooking} ({@code POST /book/{slug}/booking},
     * {@code permitAll}) filtered only {@code Master::isActive}, and {@code BookingSlugService#findBySlug}
     * ({@code GET /book/{slug}/info}) filtered NOTHING at all. {@code MasterService#createMasterFromInvite}
     * mints a booking slug for every {@code SALON_MASTER}, so a closed salon's staff otherwise keep
     * live, publicly bookable links.
     *
     * <p><b>This query is now the SOLE enforcement point for both surfaces.</b>
     * {@code GuestBookingService} has since dropped the {@code .filter(Master::isActive)} it
     * applied on top — a strictly weaker duplicate of the {@code m.isActive} term below — so
     * neither caller re-checks anything. Deleting either predicate here silently re-opens the
     * corresponding public hole. Both terms are pinned by
     * {@code MasterRepositoryBookingSlugTest}, which is a real query test precisely because a
     * Mockito stub of this method cannot observe a WHERE clause.
     *
     * <p><b>The join MUST be {@code LEFT}.</b> Writing the predicate as the implicit path
     * {@code m.salon.isActive = true} compiles to an INNER join on Hibernate 6 and would silently
     * drop every {@code INDEPENDENT_MASTER} (who has no salon) from their own booking page. The
     * fetch is a to-one join — it adds columns, never rows, so there is no cartesian risk on this
     * single-row PK/slug lookup, and it initialises {@code salon} for the guard without a lazy load.
     */
    @Query("""
            SELECT m FROM Master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            WHERE m.bookingSlug = :slug
              AND m.isActive = true
              AND (s IS NULL OR s.isActive = true)
            """)
    Optional<Master> findByBookingSlugWithUser(@Param("slug") String slug);

    // ── phase 295 — salon-deletion staff HARD-DELETE cascade ────────────────────────────────────

    /**
     * Every {@code masters} row whose account is one of {@code userIds}, ATTACHED rows only, with
     * {@code user} JOIN FETCH-ed (phase 295).
     *
     * <p>Used by {@code SalonService#deleteSalonStaff} to resolve the provider row behind each
     * staff account it is about to hard-delete, so that row can be deleted or detached FIRST — see
     * that method for the one representable ordering.
     *
     * <p><b>Deliberately NOT {@code is_active}-scoped.</b> A staff member deactivated by an
     * earlier operation (a rotation, a manual {@code DELETE /masters/{id}}) still has a live
     * {@code masters} row pointing at their {@code users} row, and {@code masters.user_id} is
     * UNIQUE — so an {@code is_active = true} filter here would silently skip exactly the rows
     * whose FK then fires {@code ON DELETE SET NULL} at {@code DELETE FROM users} time and
     * violates {@code chk_masters_detachment_coherent} (V157). The caller's other master list
     * ({@link #findBySalonIdAndIsActiveTrueWithUser}) IS active-scoped because it feeds
     * {@code MasterService#deactivateMasters}, a different job.
     *
     * <p><b>The {@code user IS NOT NULL} predicate is not redundant.</b> {@code JOIN FETCH m.user}
     * is an INNER join and already drops a detached row; the explicit predicate states the intent
     * that this finder answers "which ATTACHED masters belong to these accounts", so a future
     * conversion to {@code LEFT JOIN FETCH} (the reflex fix for a NULL-dropping join since V157)
     * cannot silently start returning already-detached rows for the caller to detach twice.
     *
     * <p>Bounded by construction: {@code userIds} is one salon's resolved staff list, and
     * {@code masters.user_id} is UNIQUE, so this returns at most {@code userIds.size()} rows.
     */
    @Query("""
            SELECT m FROM Master m
            JOIN FETCH m.user u
            WHERE u.id IN :userIds
              AND m.user IS NOT NULL
            """)
    List<Master> findAllByUserIdInWithUser(@Param("userIds") Collection<UUID> userIds);

    /**
     * Which of {@code masterIds} still have at least one historical record pointing at them
     * (phase 295, D1). A master id ABSENT from the result can be {@code DELETE}d outright; one
     * PRESENT must be DETACHED instead and survive as a name-only stub.
     *
     * <p><b>ONE query for the whole batch, not one per master (phase 295 audit, HIGH-2).</b> The
     * predecessor {@code countHistoricalReferences(UUID)} was called inside the per-master loop in
     * {@code SalonService#deleteSalonStaff}. Because it is {@code nativeQuery = true} with no
     * declared query spaces, Hibernate 6 calls {@code session.flush()} before EVERY invocation —
     * so each iteration flushed the previous iteration's detach UPDATE on its own (making
     * {@code hibernate.jdbc.batch_size} and {@code order_updates} inert for that loop) and
     * dirty-checked the entire persistence context, which at that point still holds everything the
     * phase 293 decline cascade loaded. Cost was O(N x |persistence context|). Measured on a warm
     * local socket, 50 masters: 75.2 ms looped vs 6.2 ms set-based; on Railway&rarr;Neon at 2 ms
     * RTT the loop adds ~100 round trips where this adds 2. The caller now runs a pure in-memory
     * branch over the returned id set, and {@code masterRepository.flush()} before the account
     * delete becomes the ONLY flush — which is what finally lets the detach UPDATEs and the master
     * DELETEs batch.
     *
     * <p>Three {@code EXISTS} arms rather than three {@code COUNT(*)}s, so a master with 40 000
     * bookings costs the same index probe as one with a single booking. The arms are exactly the
     * three {@code NO ACTION} foreign keys that would otherwise make {@code DELETE FROM masters}
     * fail with a 500:
     * <ul>
     *   <li>{@code bookings.master_id} — {@code V18__create_bookings.sql:6}</li>
     *   <li>{@code reviews.master_id} — {@code V40__create_reviews.sql:7}</li>
     *   <li>{@code client_reviews.author_master_id} —
     *       {@code V128__create_client_reviews_and_user_rating.sql:20}, indexed by
     *       {@code idx_client_reviews_author_master} (V159) — it had no index at all until then,
     *       so this arm AND the RI check Postgres runs on {@code DELETE FROM masters} both Seq
     *       Scanned {@code client_reviews}, once per master.</li>
     * </ul>
     * If a future migration adds a FOURTH such reference to {@code masters}, it belongs here in
     * the same commit — otherwise the delete branch starts throwing a
     * {@code DataIntegrityViolationException} out of {@code DELETE /salons/{id}}. Pinned by
     * {@code SalonStaffHardDeleteIT}'s past-booking case, whose mutation check (force this to
     * return an empty list) fails on exactly that FK violation.
     *
     * <p>Native rather than JPQL: JPQL has no {@code EXISTS} over an arbitrary table expression
     * that Hibernate will compile to independent index probes in a single round trip, and
     * {@code client_reviews} is not on {@code Master}'s object graph at all.
     *
     * <p>Bounded by construction: {@code masterIds} is one salon's resolved staff master list.
     * {@code SalonService} short-circuits on an empty collection to save a pointless round trip —
     * <b>not</b> because an empty bind is unsafe. Measured 2026-09-04 (phase 295 QA): Hibernate 6
     * rewrites an empty list bind for an {@code IN} predicate into an always-false form, and
     * calling this method with {@code List.of()} returns an empty list without throwing. An
     * earlier revision of this javadoc asserted the opposite ("a native {@code IN ()} is not valid
     * SQL"); it was wrong and is corrected here rather than left to mislead the next caller.
     */
    @Query(value = """
            SELECT m.id FROM masters m
            WHERE m.id IN (:masterIds)
              AND (   EXISTS (SELECT 1 FROM bookings b        WHERE b.master_id         = m.id)
                   OR EXISTS (SELECT 1 FROM reviews r         WHERE r.master_id         = m.id)
                   OR EXISTS (SELECT 1 FROM client_reviews cr WHERE cr.author_master_id = m.id))
            """, nativeQuery = true)
    List<UUID> findIdsWithHistoricalReferences(@Param("masterIds") Collection<UUID> masterIds);
}
