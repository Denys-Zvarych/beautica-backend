package com.beautica.master.repository;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MasterRepository extends JpaRepository<Master, UUID> {

    Optional<Master> findByUserId(UUID userId);

    /**
     * The id of the {@code masters} row owned by a user, without loading the entity — for callers
     * that only need a cache key (e.g. {@code SalonService}'s owner master-detail eviction).
     * {@code masters.user_id} is unique, so at most one id comes back.
     */
    @Query("SELECT m.id FROM Master m WHERE m.user.id = :userId")
    Optional<UUID> findIdByUserId(@Param("userId") UUID userId);

    /**
     * The {@code master-detail} / {@code booking-slug-info} cache keys of the {@code masters} row
     * owned by a user, without loading the entity — for {@code users}-row writers whose field is
     * rendered by those cached DTOs (Phase 344: {@code users.avatar_url}). Unfiltered by
     * {@code isActive}: an evict of a key that holds nothing is a harmless no-op, while a missed
     * evict serves a stale entry. {@code masters.user_id} is unique, so at most one row comes back.
     */
    @Query("""
            SELECT new com.beautica.master.repository.MasterCacheKeys(m.id, m.user.id, m.bookingSlug)
            FROM Master m
            WHERE m.user.id = :userId
            """)
    Optional<MasterCacheKeys> findCacheKeysByUserId(@Param("userId") UUID userId);

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

    /**
     * Phase 414 — one master by id with its (nullable, phase 294) user fetched in the same statement, for the support
     * report e-mail (name + avatar). Active or not: a reported profile may since have been deactivated or detached.
     */
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.user
            WHERE m.id = :masterId
            """)
    Optional<Master> findByIdWithUser(@Param("masterId") UUID masterId);

    /** @deprecated No JOIN FETCH on user — triggers N+1. Use {@link #findBySalonIdAndIsActiveTrueWithUser} instead. */
    @Deprecated
    Page<Master> findBySalonIdAndIsActiveTrue(UUID salonId, Pageable pageable);

    @Query(
        value = "SELECT m FROM Master m JOIN FETCH m.user WHERE m.salon.id = :salonId AND m.isActive = true",
        countQuery = "SELECT COUNT(m) FROM Master m WHERE m.salon.id = :salonId AND m.isActive = true"
    )
    Page<Master> findBySalonIdAndIsActiveTrueWithUser(@Param("salonId") UUID salonId, Pageable pageable);

    /**
     * Ids of {@code salonId}'s active masters that pass the CHEAP structural client-visibility rule
     * ({@link MasterBookabilitySql#BOOKABLE_MASTER_M}: ≥1 active, correctly-owned service AND working
     * hours within the 180-day horizon) — the same predicate discovery search lists masters by; a
     * fully booked master passes. The public salon roster's gate, fed into
     * {@link #findBySalonIdAndIdInAndIsActiveTrueWithUser}. A native id query because the predicate
     * is native SQL while the roster page is a JPQL {@code JOIN FETCH} with an entity-property sort
     * whitelist. Bounded by one salon's headcount. {@code today} is the Europe/Kyiv civil date from
     * the app clock ({@code ScheduleDateMath#today()}), never the DB clock.
     *
     * <p><b>Planning cost.</b> The inlined predicate costs ~7 ms to PLAN vs ~0.3 ms to execute;
     * pgjdbc server-prepares it after 5 uses ({@code prepareThreshold}), hiding that cost. See
     * {@code DatabaseUrlNormalizerPostProcessor} — a pooled endpoint with {@code prepareThreshold=0}
     * would make every request pay it.
     */
    @Query(value = "SELECT m.id FROM masters m WHERE m.salon_id = :salonId AND m.is_active = true AND "
            + MasterBookabilitySql.BOOKABLE_MASTER_M,
            nativeQuery = true)
    List<UUID> findBookableIdsBySalonId(
            @Param("salonId") UUID salonId,
            @Param(MasterBookabilitySql.TODAY_PARAM) LocalDate today);

    /**
     * The public, bookability-gated sibling of {@link #findBySalonIdAndIsActiveTrueWithUser}: the
     * same {@code JOIN FETCH m.user} roster query and count query, narrowed to {@code ids} (the
     * client-visible set from {@link #findBookableIdsBySalonId}). Filtering in SQL keeps
     * pagination and {@code totalElements} correct after gating — a post-page in-memory filter would
     * return short pages and an inflated total. {@code ids} must be non-empty (callers short-circuit
     * an empty set to an empty page without querying). Bounded by one salon's headcount, so the
     * {@code IN} list never approaches the bind-parameter ceiling.
     *
     * <p>Not a non-graph duplicate (§E-1): it fetches the same graph; it exists because the
     * management roster ({@code SalonService#getSalonStaff}) must stay ungated.
     */
    @Query(
        value = """
            SELECT m FROM Master m JOIN FETCH m.user
            WHERE m.salon.id = :salonId AND m.isActive = true AND m.id IN :ids
            """,
        countQuery = """
            SELECT COUNT(m) FROM Master m
            WHERE m.salon.id = :salonId AND m.isActive = true AND m.id IN :ids
            """
    )
    Page<Master> findBySalonIdAndIdInAndIsActiveTrueWithUser(
            @Param("salonId") UUID salonId, @Param("ids") Collection<UUID> ids, Pageable pageable);

    /**
     * The CHEAP structural search-membership verdict ({@link MasterBookabilitySql#BOOKABLE_MASTER_M}
     * on an active master) for each of {@code masterIds}, plus the master's current salon — the
     * exact predicate discovery search lists masters/salons by. Read twice per write by
     * {@code MasterSearchVisibilityGuard} (before and after, inside the write transaction) so the
     * search caches are cleared only when the verdict actually flips. Bounded by the caller's id
     * list (one master, or the masters assigned one service definition).
     */
    @Query(value = "SELECT m.id AS masterId, m.salon_id AS salonId, "
            + "(m.is_active = true AND " + MasterBookabilitySql.BOOKABLE_MASTER_M + ") AS bookable "
            + "FROM masters m WHERE m.id IN (:masterIds)",
            nativeQuery = true)
    List<SearchBookabilityRow> findSearchBookability(
            @Param("masterIds") Collection<UUID> masterIds,
            @Param(MasterBookabilitySql.TODAY_PARAM) LocalDate today);

    /**
     * Phase 377 — {@code salonId}'s bookable masters for the public salon booking page
     * ({@code GET /book/s/{slug}/info}) in ONE statement: the same structural predicate as
     * {@link #findBookableIdsBySalonId} ({@link MasterBookabilitySql#BOOKABLE_MASTER_M} on an active
     * master), joined to the live {@code users} row for the display columns — never one query per master.
     * An active master always has a user (a detached row is {@code is_active = false}). Ordered like the
     * public roster ({@code avg_rating DESC}, id tiebreak); capped by {@code limit} (one salon's headcount).
     * Phase 391 D3: slug-less masters (legacy pre-V86 rows, or a revoked personal link, phase 378) are LISTED,
     * with a {@code null} slug — the salon flow books them by master id
     * ({@code /book/s/{slug}/masters/{masterId}/*}), so the phase 377 audit's {@code booking_slug IS NOT NULL}
     * omission (which existed only because the page had no other way to book such a master) is gone. The
     * anonymous read path still never writes a slug.
     */
    @Query(value = "SELECT m.id AS id, m.booking_slug AS bookingSlug, u.first_name AS firstName, "
            + "u.last_name AS lastName, u.avatar_url AS avatarUrl, u.professional_title AS professionalTitle, "
            + "m.avg_rating AS avgRating, m.review_count AS reviewCount "
            + "FROM masters m JOIN users u ON u.id = m.user_id "
            + "WHERE m.salon_id = :salonId AND m.is_active = true AND "
            + MasterBookabilitySql.BOOKABLE_MASTER_M
            + " ORDER BY m.avg_rating DESC NULLS LAST, m.id LIMIT :limit",
            nativeQuery = true)
    List<SalonBookingPageMasterRow> findBookingPageMastersBySalonId(
            @Param("salonId") UUID salonId,
            @Param(MasterBookabilitySql.TODAY_PARAM) LocalDate today,
            @Param("limit") int limit);

    /** Row of {@link #findBookingPageMastersBySalonId}. */
    interface SalonBookingPageMasterRow {
        UUID getId();

        String getBookingSlug();

        String getFirstName();

        String getLastName();

        String getAvatarUrl();

        String getProfessionalTitle();

        /** Phase 384 audit - the persisted rating pair the salon master card shows (no extra statement). */
        java.math.BigDecimal getAvgRating();

        int getReviewCount();
    }

    /** Row of {@link #findSearchBookability}. */
    interface SearchBookabilityRow {
        UUID getMasterId();

        UUID getSalonId();

        Boolean getBookable();
    }

    /**
     * Identifier-only projection of the same active roster {@link #findBySalonIdAndIsActiveTrueWithUser}
     * returns — for a caller that wants the ids and nothing else (the roster id-projection
     * finding, backend-perf 2026-09-20 —
     * backend-perf).
     *
     * <p><b>Why this is not a duplicate finder</b> (Anti-Bug §E-1 forbids keeping a non-graph
     * variant BESIDE a graph variant, because callers silently N+1 or hit
     * {@code LazyInitializationException}). That rule protects callers who touch associations; it
     * does not apply to a projection that returns no entity at all. {@code SELECT m.id} cannot be
     * N+1'd and cannot be lazily initialised — there is nothing to initialise. The two methods are
     * not interchangeable in the direction that matters: a caller needing {@code m.user} literally
     * cannot use this one, so it can never be reached by mistake.
     *
     * <p><b>What it replaces.</b> {@code SalonService#getSalonMastersEffectiveSchedule} called the
     * graph finder with {@link Pageable#unpaged()} and immediately did {@code .map(Master::getId)},
     * so the whole roster's {@code masters} AND {@code users} rows were selected, materialised as
     * managed entities and parked in the persistence context for the rest of the request — to
     * extract a list of UUIDs. Same one statement either way; the saving is the row width, the
     * entity instantiation, and the snapshot the persistence context keeps for dirty checking, on a
     * read-only path that never touches a single non-id field.
     *
     * <p>{@link #findBySalonIdAndIsActiveTrueWithUser} is deliberately left ALONE — {@code
     * SalonService#getSalonStaff} and {@code #getMastersBySalon} both map the fetched {@code
     * m.user} into their responses and genuinely need the graph.
     *
     * <p>No {@code ORDER BY}, matching the graph finder byte-for-byte: the roster endpoint's
     * response order is whatever the access path yields, exactly as before, so this change is
     * ordering-neutral rather than quietly imposing a new contract.
     *
     * <p><b>Unscoped by default</b> (Anti-Bug §E-4): {@code salonId} is a plain parameter with no
     * actor predicate. Its one caller is reached only through
     * {@code @PreAuthorize("… and @authz.canManageSalon(authentication, #salonId)")}, which is
     * where the ownership decision lives.
     */
    @Query("SELECT m.id FROM Master m WHERE m.salon.id = :salonId AND m.isActive = true")
    List<UUID> findIdsBySalonIdAndIsActiveTrue(@Param("salonId") UUID salonId);

    /**
     * Phase 378 audit (perf LOW-2) — the {@code booking-slug-info} cache keys of the salon's active members in
     * ONE statement (instead of ids, then slugs). A member without a slug has no public page, so it is
     * skipped. Bounded by the salon's roster. Unscoped (Anti-Bug §E-4): the caller
     * ({@code BookingLinkRetirementWriter}) has already authorized the salon.
     */
    @Query(value = """
            SELECT booking_slug FROM masters
             WHERE salon_id = :salonId AND is_active AND booking_slug IS NOT NULL
            """, nativeQuery = true)
    List<String> findActiveMemberBookingSlugsBySalonId(@Param("salonId") UUID salonId);

    /**
     * Cache keys of EVERY master row affiliated with {@code salonId} — active or not, since
     * {@code GET /masters/{masterId}} carries no {@code isActive} predicate and may hold an
     * inactive master's entry. Id-only constructor projection; the {@code LEFT JOIN} keeps a
     * detached master (null {@code user}) in the result with a null {@code userId}.
     *
     * <p>Used by {@code SalonService#updateSalon} to evict the per-master detail entries whose
     * embedded salon block changed with the salon's {@code cityId}. Bounded by the salon's
     * roster, which is small by construction.
     */
    @Query("""
            SELECT new com.beautica.master.repository.MasterCacheKeys(m.id, u.id)
              FROM Master m LEFT JOIN m.user u
             WHERE m.salon.id = :salonId
            """)
    List<MasterCacheKeys> findCacheKeysBySalonId(@Param("salonId") UUID salonId);

    boolean existsBySalonIdAndUserIdAndIsActiveTrue(UUID salonId, UUID userId);

    /**
     * Returns {@code true} iff the given user has an <em>active</em> master row of the given type,
     * in ANY salon. Backs {@code UserService.getProfile}'s derived
     * {@code UserProfileResponse.hasMasterProfile} (Phase 265) — the presence of this row, never
     * a stored column.
     *
     * <p>Deliberately NOT salon-scoped: {@code GET /users/me} has no path salon and must not
     * invent one — {@code users.salon_id} is the owner's primary salon and would make the flag read
     * {@code false} for an owner whose master row sits in a different salon of theirs.
     *
     * <p>The {@code isActive} term is load-bearing, not defensive: deactivation (e.g. salon deletion)
     * flips the row rather than hard-deleting it, so a plain
     * {@code existsByUserIdAndMasterType} would keep reporting a deactivated row as a live
     * master profile.
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
    // flushAutomatically = true (2026-09-13 perf audit, LOW): callers mutate the managed
    // MasterServiceAssignment (band/duration) and then call this in the SAME transaction, and
    // clearAutomatically detaches that entity immediately afterwards. Whether the pending dirty
    // row would otherwise be flushed depends on Hibernate's auto-flush query-space computation
    // for a bulk HQL UPDATE Master whose subquery reads master_services — do not rely on it.
    // Flushing first makes the subquery read the caller's own write and makes the detach safe.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    // flushAutomatically = true (2026-09-13 perf audit, LOW): callers mutate the managed
    // MasterServiceAssignment (band/duration) and then call this in the SAME transaction, and
    // clearAutomatically detaches that entity immediately afterwards. Whether the pending dirty
    // row would otherwise be flushed depends on Hibernate's auto-flush query-space computation
    // for a bulk HQL UPDATE Master whose subquery reads master_services — do not rely on it.
    // Flushing first makes the subquery read the caller's own write and makes the detach safe.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
     * Phase 375 — race-safe lazy slug issue: writes {@code slug} ONLY while the row still has no slug.
     * Two concurrent first calls generate DIFFERENT candidates, so the unique index never fires;
     * without the {@code IS NULL} guard the later commit would silently overwrite the earlier slug
     * (a link already handed out would die). Under READ COMMITTED the second UPDATE blocks on the
     * row lock, re-evaluates the predicate after the first commits and matches 0 rows; the caller
     * then re-reads the winner via {@link #findBookingSlugById}.
     *
     * <p>No {@code clearAutomatically}: the caller keeps using its already-loaded entity (name,
     * salon). That entity's in-memory slug stays {@code null}, which is harmless — it is unchanged
     * from its snapshot, so dirty-checking never writes the column back.
     *
     * <p><b>3s {@code lock_timeout}, fused</b> (perf audit L3) — the {@code FROM (SELECT set_config(...))}
     * join-source shape of {@code AppointmentRepository#consumeCancelTokenHash} (an {@code UPDATE} has no target
     * list to carry it): Postgres evaluates the 1-row source, and so sets the transaction-local GUC, before
     * the implicit row lock on the matching master row, so a concurrent holder bounds this wait to 3s like
     * the salon twin {@code SalonRepository#lockSalonIdsWithoutBookingSlug}. A longer wait fails with
     * {@code 55P03} → {@code CannotAcquireLockException} → 409 via
     * {@code GlobalExceptionHandler#handlePessimisticLockingFailure}. The GUC stays in force for the rest of
     * the caller's transaction ({@code BookingSlugService#getOrIssueSlugRaceSafe}), which only re-reads.
     *
     * @return 1 when this call issued the slug, 0 when another transaction already had
     */
    // Phase 378 D5 — the INTERNAL issue path (BookingSlugService#getOrCreateSlug, master creation):
    // ignores and clears a revoke flag, so a master the platform itself needs a slug for (a salon
    // page lists only masters with a slug) always gets one. The share path uses
    // assignShareSlugIfAbsentAndNotRevoked instead, which never resurrects a revoked link.
    @Modifying
    @Query(value = """
            UPDATE masters
               SET booking_slug = :slug,
                   booking_link_revoked_at = NULL
              FROM (SELECT set_config('lock_timeout', '3s', true)) AS lock_cfg
             WHERE masters.id = :masterId
               AND masters.booking_slug IS NULL
            """, nativeQuery = true)
    int assignBookingSlugIfAbsent(@Param("masterId") UUID masterId, @Param("slug") String slug);

    /**
     * Phase 378 D4 — the SHARE-path lazy issue ({@code GET /booking-links/mine}): the
     * {@link #assignBookingSlugIfAbsent} statement plus {@code booking_link_revoked_at IS NULL}, so a
     * revoked link is never silently re-issued (only an explicit regenerate clears the flag). 0 rows →
     * a concurrent call issued first OR the link is revoked; the caller re-reads
     * {@link #findBookingLinkStateById} to tell which. Same fused 3s {@code lock_timeout}.
     */
    @Modifying
    @Query(value = """
            UPDATE masters
               SET booking_slug = :slug
              FROM (SELECT set_config('lock_timeout', '3s', true)) AS lock_cfg
             WHERE masters.id = :masterId
               AND masters.booking_slug IS NULL
               AND masters.booking_link_revoked_at IS NULL
            """, nativeQuery = true)
    int assignShareSlugIfAbsentAndNotRevoked(@Param("masterId") UUID masterId, @Param("slug") String slug);

    /**
     * Phase 378 D7/D11a — regenerate: optimistic compare-and-swap of the master's slug. Writes
     * {@code newSlug} and clears the revoke flag ONLY while the stored slug still equals the caller's
     * {@code expected} value; {@code expected} is {@code ""} for "no slug" (a revoked or legacy link) —
     * never {@code null}, so the bind is always typed and {@code COALESCE(booking_slug, '')} matches it
     * (a real slug is never empty: V86 CHECK). Same fused 3s {@code lock_timeout} as the lazy issue.
     *
     * @return 1 on success, 0 when the stored slug no longer equals {@code expected} (→ 409)
     */
    @Modifying
    @Query(value = """
            UPDATE masters
               SET booking_slug = :newSlug,
                   booking_link_revoked_at = NULL
              FROM (SELECT set_config('lock_timeout', '3s', true)) AS lock_cfg
             WHERE masters.id = :masterId
               AND COALESCE(masters.booking_slug, '') = :expected
            """, nativeQuery = true)
    int replaceBookingSlug(
            @Param("masterId") UUID masterId, @Param("expected") String expected, @Param("newSlug") String newSlug);

    /**
     * Phase 378 D7/D11a — revoke: the compare-and-swap twin of {@link #replaceBookingSlug} that clears the
     * slug and stamps the revoke flag (kept, not moved, when already set). {@code expected} as there.
     *
     * @return 1 on success, 0 when the stored slug no longer equals {@code expected} (→ 409)
     */
    @Modifying
    @Query(value = """
            UPDATE masters
               SET booking_slug = NULL,
                   booking_link_revoked_at = COALESCE(masters.booking_link_revoked_at, :revokedAt)
              FROM (SELECT set_config('lock_timeout', '3s', true)) AS lock_cfg
             WHERE masters.id = :masterId
               AND COALESCE(masters.booking_slug, '') = :expected
            """, nativeQuery = true)
    int revokeBookingSlug(
            @Param("masterId") UUID masterId, @Param("expected") String expected,
            @Param("revokedAt") Instant revokedAt);

    /**
     * Phase 378 — the master's committed link state straight from the table (native, so a stale entity
     * is bypassed and the unmapped {@code booking_link_revoked_at} is reachable). Empty when no row.
     */
    @Query(value = """
            SELECT booking_slug AS "bookingSlug", (booking_link_revoked_at IS NOT NULL) AS "revoked"
              FROM masters WHERE id = :masterId
            """, nativeQuery = true)
    Optional<BookingLinkState> findBookingLinkStateById(@Param("masterId") UUID masterId);

    /** Row of {@link #findBookingLinkStateById}: the live slug (or {@code null}) and the revoke flag. */
    interface BookingLinkState {
        String getBookingSlug();

        Boolean getRevoked();

        default boolean revoked() {
            return Boolean.TRUE.equals(getRevoked());
        }
    }

    /**
     * Phase 375 — the master's CURRENT committed booking slug, read straight from the table (a native
     * scalar, so the persistence context's possibly-stale entity is bypassed). Pairs with
     * {@link #assignBookingSlugIfAbsent}.
     */
    @Query(value = "SELECT booking_slug FROM masters WHERE id = :masterId", nativeQuery = true)
    Optional<String> findBookingSlugById(@Param("masterId") UUID masterId);

    /**
     * Phase 380 audit — the committed booking slugs of {@code masterIds} in ONE statement (masters without a
     * slug are simply absent). Backs the batched {@code booking-slug-info} eviction of a service-definition
     * change, which can touch many masters.
     */
    @Query(value = "SELECT booking_slug FROM masters WHERE id IN (:masterIds) AND booking_slug IS NOT NULL",
            nativeQuery = true)
    List<String> findBookingSlugsByIdIn(@Param("masterIds") Collection<UUID> masterIds);

    /**
     * Phase 380 D2 — the public booking page's {@code bookable} verdict for ONE master: active AND the
     * shared structural predicate ({@link MasterBookabilitySql#BOOKABLE_MASTER_M}: ≥1 active
     * correctly-owned service and a schedule within the 180-day horizon). One statement; {@code today}
     * is the Europe/Kyiv civil date from the app clock, never the DB clock.
     */
    @Query(value = "SELECT (m.is_active = true AND " + MasterBookabilitySql.BOOKABLE_MASTER_M
            + ") FROM masters m WHERE m.id = :masterId",
            nativeQuery = true)
    Boolean isBookableById(
            @Param("masterId") UUID masterId,
            @Param(MasterBookabilitySql.TODAY_PARAM) LocalDate today);

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

    /**
     * Phase 387 audit (perf LOW) — the id of the master {@link #findByBookingSlugWithUser} would return, as ONE
     * scalar column: no {@code Master}/{@code User}/{@code Salon} hydration. Backs the guest slot reads
     * ({@code GET /book/{slug}/availability} and {@code /bookable-days}), which need the id only.
     *
     * <p><b>Predicate is a byte-for-byte mirror of {@link #findByBookingSlugWithUser}</b>, including the
     * INNER {@code JOIN m.user}: that finder's {@code JOIN FETCH m.user} is an inner join, so a DETACHED master
     * ({@code user_id IS NULL}, phase 294) never resolves — kept here so the two can never disagree, even
     * though detach also deactivates. The salon join stays {@code LEFT} (an independent master has no salon).
     * Deliberately NOT {@link #findActiveReviewTargetByBookingSlug}, which omits the user join. Pinned by
     * {@code MasterRepositoryBookingSlugTest} (real query: active / no-salon / closed salon / inactive master).
     */
    @Query("""
            SELECT m.id FROM Master m
            JOIN m.user
            LEFT JOIN m.salon s
            WHERE m.bookingSlug = :slug
              AND m.isActive = true
              AND (s IS NULL OR s.isActive = true)
            """)
    Optional<UUID> findBookableIdByBookingSlug(@Param("slug") String slug);

    /**
     * Phase 383 — the id of the ACTIVE master (in an active salon, or none) behind a public booking slug: the
     * exact visibility rule of {@link #findByBookingSlugWithUser}, as ONE scalar statement. Backs
     * {@code GET /book/{slug}/reviews}, which needs the id only and must not hydrate the user/salon graph on
     * every (uncached) request.
     */
    @Query("""
            SELECT m.id AS id, m.reviewCount AS reviewCount FROM Master m
            LEFT JOIN m.salon s
            WHERE m.bookingSlug = :slug
              AND m.isActive = true
              AND (s IS NULL OR s.isActive = true)
            """)
    Optional<com.beautica.common.ReviewTarget> findActiveReviewTargetByBookingSlug(@Param("slug") String slug);

    /**
     * Phase 393 — the salon-scoped twin of {@link #findByBookingSlugWithUser} for the public SALON guest
     * booking ({@code POST /book/s/{slug}/masters/{masterId}/booking}): master {@code masterId} with its user
     * and salon, but only when it is ACTIVE, attached to a user, and works in the ACTIVE salon whose public
     * booking slug is {@code salonSlug}. Keyed by the salon slug + master id, never by a master booking slug
     * (locked rule: salon masters have no personal share link).
     *
     * <p><b>The SOLE enforcement point of the salon-path visibility rule</b>, like the master-slug finder: a
     * master of another salon, a deactivated master, a closed salon or an unknown slug all resolve to empty,
     * and the caller answers one indistinguishable 404 (or 410 for a retired salon slug). INNER joins on both
     * {@code user} (a detached master never resolves) and {@code salon} (an independent master has no salon
     * page). To-one fetches on a single-row PK lookup: columns, never rows. Pinned by
     * {@code MasterRepositorySalonSlugTest} (real query).
     */
    @Query("""
            SELECT m FROM Master m
            JOIN FETCH m.user
            JOIN FETCH m.salon s
            WHERE m.id = :masterId
              AND s.bookingSlug = :salonSlug
              AND s.isActive = true
              AND m.isActive = true
            """)
    Optional<Master> findBookableBySalonSlugWithUser(
            @Param("masterId") UUID masterId, @Param("salonSlug") String salonSlug);

    /**
     * Phase 392 — the id {@link #findBookableBySalonSlugWithUser} would return, as ONE scalar column (no
     * Master/User/Salon hydration), for the salon-scoped guest slot reads
     * ({@code GET /book/s/{slug}/masters/{masterId}/availability} and {@code /bookable-days}). Predicate is a
     * byte-for-byte mirror of that finder, including the INNER {@code JOIN m.user}.
     */
    @Query("""
            SELECT m.id FROM Master m
            JOIN m.user
            JOIN m.salon s
            WHERE m.id = :masterId
              AND s.bookingSlug = :salonSlug
              AND s.isActive = true
              AND m.isActive = true
            """)
    Optional<UUID> findBookableIdBySalonSlug(@Param("masterId") UUID masterId, @Param("salonSlug") String salonSlug);

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
     * local socket, 50 masters: 75.2 ms looped vs 6.2 ms set-based; at an
     * illustrative 2 ms RTT the loop adds ~100 round trips where this adds 2. The caller now runs a pure in-memory
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

    /**
     * Row-locks a master for the Phase 362 portfolio cap (D6): two concurrent portfolio uploads for the same
     * master serialise here, so the {@code count -> insert} pair under the lock can never overshoot the cap.
     *
     * <p>Same shape as {@code SalonRepository#findByIdForUpdate}: {@code FOR NO KEY UPDATE} (conflicts with
     * itself, which is all the serialisation needs, but not with the {@code FOR KEY SHARE} that every FK check
     * on {@code masters} takes — so bookings/appointments referencing this master are never stalled), and a
     * transaction-scoped 3s {@code lock_timeout} fused into the same round trip ({@code 55P03} ->
     * {@code CannotAcquireLockException}; the portfolio path translates it to 503 + {@code Retry-After}).
     *
     * <p>Selects the id only (audit cycle 1, F4): the cap path needs the lock, never the row, so no
     * {@code Master} entity enters the persistence context to be dirty-checked at commit.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_NATIVE_SPACES, value = "masters"))
    @Query(value = """
            SELECT m.id FROM masters m
            CROSS JOIN (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
            WHERE m.id = :masterId
            FOR NO KEY UPDATE OF m
            """, nativeQuery = true)
    Optional<UUID> lockIdForUpdate(@Param("masterId") UUID masterId);
}
