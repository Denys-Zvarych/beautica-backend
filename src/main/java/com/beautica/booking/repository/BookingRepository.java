package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingViewAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID>, BookingRepositoryCustom {

    // ── ID-only paginated queries — two-query pattern (Fix H1 — HHH90003004) ──
    //
    // JOIN FETCH + Pageable on a collection path forces Hibernate to load all rows
    // into memory and paginate in the application layer (HHH90003004). The fix is a
    // two-query pattern: (1) paginate on IDs only (no JOIN FETCH → correct SQL LIMIT/
    // OFFSET), then (2) batch-hydrate the full graph for only those IDs.

    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.client.id = :clientId
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
            """)
    Page<UUID> findIdsByClientId(@Param("clientId") UUID clientId, Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.client.id = :clientId AND b.status = :status
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId AND b.status = :status
            """)
    Page<UUID> findIdsByClientIdAndStatus(
            @Param("clientId") UUID clientId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    // ── Phase 26.1 — collapsed filtered query family ───────────────────────────
    //
    // findIdsByMasterId / findIdsByMasterIdAndStatus and findIdsBySalonIds /
    // findIdsBySalonIdsAndStatus collapsed into ONE filtered query per scope, so
    // 26.2 (date range), 26.3 (sort) and 26.4 (service filter) each edit a single
    // query instead of four.
    //
    // Phase 26.1 audit fix (Finding 1 — HIGH, backend-perf): findIdsByMasterIdFiltered and
    // findIdsBySalonIdsFiltered originally used the (:statuses IS NULL OR b.status IN :statuses)
    // sentinel idiom here. That idiom is NOT sargable — PgJDBC's default prepareThreshold=5 makes
    // Postgres fall back to a GENERIC plan after the 5th execution, one that cannot see the bound
    // value and so cannot fold away the dead OR branch (measured ~19x slower on a 503k-row table,
    // scaling with the provider's row count). Both methods now live in BookingRepositoryCustom /
    // BookingRepositoryCustomImpl, built dynamically via org.springframework.data.jpa.domain
    // .Specification (see BookingSpecifications for the full rationale) so the emitted SQL
    // contains ONLY the predicates actually requested — no dead branch, and each predicate shape
    // gets its own Postgres plan-cache entry. Method signatures are unchanged, so every caller
    // (BookingService) needed no changes. Phase 26.3 replaced the (then still hardcoded)
    // ORDER BY b.startsAt DESC with a translation of pageable.getSort() into Criteria Orders —
    // see BookingRepositoryCustomImpl.findIdPage — because Sort.getProperty() is a validated,
    // whitelisted value by the time it reaches this layer (BookingService#normalizeBookingSort).
    //
    // findClientBookingDetails below still uses the sentinel idiom — Finding 2 (MEDIUM) — left
    // as-is deliberately; see that method's javadoc for why a Specification rewrite was judged
    // impractical there. Its own hardcoded ORDER BY b.startsAt DESC WAS removed by Phase 26.3
    // (a distinct defect from the sentinel one) — see that method's javadoc.

    @Query(value = """
            SELECT b.id FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            """)
    Page<UUID> findIdsBySalonIdAndOwnerId(
            @Param("salonId") UUID salonId,
            @Param("ownerId") UUID ownerId,
            Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            AND b.status = :status
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            AND b.status = :status
            """)
    Page<UUID> findIdsBySalonIdAndOwnerIdAndStatus(
            @Param("salonId") UUID salonId,
            @Param("ownerId") UUID ownerId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    // ── SALON_OWNER multi-salon queries (Fix HIGH-1) ───────────────────────────
    //
    // The previous approach resolved salonId via userRepository.findSalonIdById which only
    // returns a value for invited roles (SALON_ADMIN, SALON_MASTER). For SALON_OWNER the
    // relationship is stored on Salon.owner_id, not User.salonId — always returning empty.
    // findIdsBySalonIdsFiltered accepts a pre-resolved list of salonIds (from SalonRepository
    // .findIdsByOwnerIdAndIsActiveTrue) and joins through master → salon, covering all active
    // salons owned by the actor in a single query. Implemented in BookingRepositoryCustomImpl
    // (Specification-based, see the Phase 26.1 audit-fix comment above / BookingSpecifications)
    // rather than as a @Query here.

    // The provider "Графік" (calendar) query — a booking is born CONFIRMED (track 24.x
    // auto-confirm), so it appears here the instant it is created.
    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.master.id = :masterId
            AND b.startsAt >= :from
            AND b.startsAt < :to
            AND b.status IN (com.beautica.booking.enums.BookingStatus.CONFIRMED,
                             com.beautica.booking.enums.BookingStatus.COMPLETED)
            ORDER BY b.startsAt ASC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.master.id = :masterId
            AND b.startsAt >= :from
            AND b.startsAt < :to
            AND b.status IN (com.beautica.booking.enums.BookingStatus.CONFIRMED,
                             com.beautica.booking.enums.BookingStatus.COMPLETED)
            """)
    Page<UUID> findActiveIdsByMasterIdAndStartsAtBetween(
            @Param("masterId") UUID masterId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable);

    /**
     * Batch-hydrates a bounded set of booking IDs with the full association graph.
     * Always called with the result of an ID-only page query, so the IN list size
     * equals the configured page size (default 20) — never unbounded.
     */
    @Query("""
            SELECT b FROM Booking b
            LEFT JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.id IN :ids
            """)
    List<Booking> findAllByIdsWithGraph(@Param("ids") List<UUID> ids);

    // ── Client booking-detail projection (Phase 19.3) ─────────────────────────
    /**
     * One-query, N+1-free projection for {@code GET /bookings/me}: every field
     * {@link com.beautica.booking.dto.BookingDetailResponse} needs for a client row,
     * plus a {@code reviewExists} flag via {@code LEFT JOIN Review}.
     * {@code mu.professionalTitle} rides the same {@code JOIN m.user mu} already used for
     * {@code mu.firstName}/{@code mu.lastName} — no additional join, and the column is
     * nullable (a master may never have set a title).
     *
     * <p>{@code locationNote}, {@code street}, {@code buildingNo}, {@code cityId} and
     * {@code districtId} are resolved by {@code CASE WHEN s.id IS NOT NULL THEN s.X ELSE mu.X END}
     * — salon-presence wins outright, even when the salon's own column is {@code NULL}. This
     * mirrors {@link com.beautica.booking.dto.BookingDetailResponse#from} exactly: {@code salon
     * != null ? salon.getX() : masterUser.getX()}. <b>Do not use {@code COALESCE(s.X, mu.X)}
     * here</b> — {@code COALESCE} falls through to the master's own value whenever the salon's
     * column is {@code NULL}, which for a salon-employed master leaks the master's personal
     * data (e.g. their home door code) onto a salon booking. Riding the same
     * {@code LEFT JOIN m.salon s} / {@code JOIN m.user mu} aliases — no additional join.
     *
     * <p>{@code statuses} is optional: when {@code null} the
     * {@code (:statuses IS NULL OR b.status IN :statuses)} idiom matches all rows
     * (one method covers both the filtered and unfiltered list paths). Phase 26.1 widened
     * this from a scalar {@code statusFilter} equality to a {@code Collection} {@code IN}
     * check so {@code GET /bookings/me} can accept repeated {@code status} values.
     *
     * <p><b>Phase 26.1 audit fix, Finding 2 (MEDIUM, backend-perf) — sentinel intentionally kept
     * here.</b> The provider-path sibling queries ({@code findIdsByMasterIdFiltered} /
     * {@code findIdsBySalonIdsFiltered}) were rewritten onto a dynamic {@code Specification}
     * (see {@link BookingSpecifications}) to remove this exact {@code (:x IS NULL OR …)} plan-cache
     * hazard. This query was evaluated for the same treatment and DEFERRED, not overlooked:
     * it is a single {@code SELECT new ClientBookingDetailProjection(…)} constructor expression
     * over 26 fields, including a {@code LEFT JOIN Review r ON r.booking = b} and five
     * {@code CASE WHEN s.id IS NOT NULL THEN s.X ELSE mu.X END} salon-precedence expressions (see
     * above — the COALESCE-vs-CASE-WHEN distinction here guards a real PII leak). Reproducing that
     * shape correctly via the JPA Criteria API (a 26-argument {@code CriteriaBuilder.construct}
     * call, a criteria {@code ON}-join for the review, and five criteria {@code selectCase()}
     * expressions) is a high-risk rewrite of a query with a locked, security-sensitive precedence
     * rule, for a MEDIUM finding — the risk of silently reintroducing the salon/master PII leak
     * this javadoc warns about outweighs the plan-cache benefit. Left as-is; revisit only
     * alongside a dedicated test pass for this specific query, not as a drive-by extension of the
     * Finding 1 fix.
     *
     * <p><b>Discovery locality is district-primary via the salon link</b> — the salon's
     * city/district/address wins when the master is salon-employed, else the master's own
     * user row. This mirrors {@code SearchService}'s {@code COALESCE(salon, user)} rule so
     * the booking detail and search results agree on a provider's locality. The projection
     * returns the FK ids only; {@code BookingService} resolves the {@code name_uk} labels
     * through the {@code DiscoveryLocationResolver} M2 seam (§E: batched, not per row).
     *
     * <p>Pagination is safe to apply directly here: the query projects scalar columns (no
     * JOIN FETCH on a collection), so Hibernate emits a correct SQL {@code LIMIT/OFFSET}
     * and the HHH90003004 in-memory-pagination trap does not apply.
     *
     * <p><b>Phase 26.3 — ordering is {@code Pageable}-driven, not hardcoded.</b> This query
     * used to carry a literal {@code ORDER BY b.startsAt DESC}, which Spring Data
     * <i>appends to</i> rather than replaces when the caller's {@code Pageable} also carries a
     * {@link org.springframework.data.domain.Sort} — so {@code ?sort=priceAtBooking,desc}
     * silently became {@code ORDER BY b.starts_at DESC, b.price_at_booking DESC}, a no-op tie
     * clause (a master cannot hold two overlapping bookings, so {@code startsAt} is effectively
     * unique). The hardcoded clause is removed; {@code b.startsAt} — a valid property path off
     * the primary alias {@code b} — is what Spring appends from {@code pageable.getSort()}
     * instead. The {@link Sort} arriving here is ALWAYS pre-validated and pre-normalized by
     * {@code BookingService#normalizeBookingSort} before this method is called: whitelisted to
     * {@code startsAt}/{@code priceAtBooking}/{@code createdAt} (rejecting dot-paths such as
     * {@code master.user.passwordHash} with a 400 — this predicate area joins through
     * {@code m}/{@code mu}, so an unvalidated {@code Sort} would be a live credential-ordering
     * oracle), defaulted to {@code startsAt DESC} when unsorted, and always carrying a trailing
     * {@code b.id ASC} tiebreaker for deterministic {@code OFFSET} pagination under ties. Do not
     * call this method with a raw, unvalidated {@code Pageable}.
     *
     * <p>Scope: callers MUST pass the authenticated client's own user id — the predicate
     * {@code b.client.id = :clientId} is the ownership boundary (Anti-Bug §E-4).
     *
     * <p><b>Phase 26.2 — optional date range.</b> {@code from}/{@code toExclusive} extend this
     * method's existing {@code (:x IS NULL OR ...)} sentinel idiom (left as-is here per Finding 2
     * above — this method was NOT converted to a {@link org.springframework.data.jpa.domain.Specification}).
     * Both are already-resolved {@code Europe/Kyiv} instants computed once in
     * {@code BookingService#getMyBookings} — {@code toExclusive} is the HALF-OPEN upper bound
     * ({@code to.plusDays(1)} at Kyiv midnight), never an {@code <=} on {@code to} itself, so a
     * {@code to}-day booking after 00:00 Kyiv is not silently dropped.
     *
     * <p><b>The two date predicates {@code CAST} the parameter before the null-check</b> —
     * {@code (CAST(:from AS java.time.OffsetDateTime) IS NULL OR b.startsAt >= :from)} — unlike
     * the untyped {@code (:statuses IS NULL OR ...)} predicate above. This is not stylistic. A
     * bare {@code :from IS NULL} (tried first, and separately a reordered
     * {@code b.startsAt >= :from OR :from IS NULL} — {@code OR} is commutative so reordering
     * changes nothing) both raised {@code ERROR: could not determine data type of parameter $N}
     * on EVERY execution — a hard 500, not merely the GENERIC-plan slowdown Finding 1 describes.
     * PostgreSQL's extended-query-protocol parser cannot resolve a parameter's OID from a lone
     * {@code IS NULL} usage when nothing else in the query pins its type; {@code :statuses IS
     * NULL} is unaffected only because Hibernate's collection/{@code IN}-clause binding machinery
     * always assigns the array element type explicitly (needed to build {@code = ANY(?)}), a
     * guarantee scalar temporal parameters don't get for free. The {@code CAST} gives Postgres an
     * explicit {@code timestamp(6) with time zone} OID for the SAME parameter used later in the
     * real {@code >=}/{@code <} comparison — verified against the {@code timestamptz} column, so
     * this does not shift the compared instant (unlike casting to timezone-less {@code timestamp},
     * which would silently reintroduce a server-default-zone bug). Do not revert to a bare
     * {@code :from IS NULL} — {@code ClientBookingDetailProjectionTest} pins this against a real
     * Postgres instance (Testcontainers), not a mock, specifically to catch a regression here.
     *
     * <p><b>Phase 26.4 — optional service filter, {@code :serviceIds} gets NO {@code CAST}.</b>
     * {@code (:serviceIds IS NULL OR b.masterService.id IN :serviceIds)} mirrors the untyped
     * {@code :statuses} predicate above, not the {@code CAST}-guarded date predicates — because
     * {@code serviceIds} is a {@code Collection<UUID>}, bound the same way {@code statuses} is:
     * Hibernate's collection/{@code IN}-clause binding always assigns the array element type
     * explicitly (needed to build {@code = ANY(?)}), so Postgres never has to infer an OID from a
     * lone {@code IS NULL} the way it does for the scalar {@code :from}/{@code :toExclusive}
     * parameters. {@code ClientBookingDetailProjectionTest} exercises the non-null branch against
     * a real Postgres instance (Testcontainers) to confirm this — do not add a {@code CAST} here
     * pre-emptively; it is unneeded for a collection parameter and would be dead defensive code.
     */
    @Query(value = """
            SELECT new com.beautica.booking.repository.ClientBookingDetailProjection(
                b.id,
                b.client.id,
                m.id,
                ms.id,
                sd.name,
                b.status,
                b.startsAt,
                b.endsAt,
                b.priceAtBooking,
                b.durationMinutesAtBooking,
                b.createdAt,
                b.client.firstName,
                b.client.lastName,
                mu.firstName,
                mu.lastName,
                mu.professionalTitle,
                b.clientComment,
                b.providerComment,
                b.clientCancellationNote,
                mu.avatarUrl,
                mu.role,
                s.name,
                CASE WHEN s.id IS NOT NULL THEN s.cityId ELSE mu.cityId END,
                CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END,
                CASE WHEN s.id IS NOT NULL THEN s.street ELSE mu.street END,
                CASE WHEN s.id IS NOT NULL THEN s.buildingNo ELSE mu.buildingNo END,
                CASE WHEN s.id IS NOT NULL THEN s.locationNote ELSE mu.locationNote END,
                sd.category,
                CASE WHEN r.id IS NOT NULL THEN true ELSE false END
            )
            FROM Booking b
            JOIN b.client
            JOIN b.master m
            JOIN m.user mu
            LEFT JOIN m.salon s
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            LEFT JOIN Review r ON r.booking = b
            WHERE b.client.id = :clientId
              AND (:statuses IS NULL OR b.status IN :statuses)
              AND (CAST(:from AS java.time.OffsetDateTime) IS NULL OR b.startsAt >= :from)
              AND (CAST(:toExclusive AS java.time.OffsetDateTime) IS NULL OR b.startsAt < :toExclusive)
              AND (:serviceIds IS NULL OR b.masterService.id IN :serviceIds)
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
              AND (:statuses IS NULL OR b.status IN :statuses)
              AND (CAST(:from AS java.time.OffsetDateTime) IS NULL OR b.startsAt >= :from)
              AND (CAST(:toExclusive AS java.time.OffsetDateTime) IS NULL OR b.startsAt < :toExclusive)
              AND (:serviceIds IS NULL OR b.masterService.id IN :serviceIds)
            """)
    Page<ClientBookingDetailProjection> findClientBookingDetails(
            @Param("clientId") UUID clientId,
            @Param("statuses") Collection<BookingStatus> statuses,
            @Param("from") OffsetDateTime from,
            @Param("toExclusive") OffsetDateTime toExclusive,
            @Param("serviceIds") Collection<UUID> serviceIds,
            Pageable pageable);

    // ── Full-graph single lookup (Fix M6 — lazy loads on mutation response) ────

    /**
     * <b>Guest (LINK) bookings ({@code client_id IS NULL}, V89) must resolve here too</b> —
     * {@code client} is a {@code LEFT JOIN FETCH}, not an inner join. An inner join here
     * silently excludes every null-client row, which made {@code loadBookingOrThrow} (backing
     * {@code /complete}, {@code /decline}, {@code /not-complete}) and {@code getBooking}
     * ({@code GET /bookings/{id}}) 404 for ANY guest booking — the entire provider-side guest
     * lifecycle was unreachable (CRITICAL finding, track 24.7 audit). See
     * {@link #findAllByIdsWithGraph} for the sibling batch-hydrate query with the same fix.
     */
    @Query("""
            SELECT b FROM Booking b
            LEFT JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.id = :id
            """)
    Optional<Booking> findByIdWithFullGraph(@Param("id") UUID id);

    // ── Calendar / overlap queries (kept as native SQL) ────────────────────────

    // ── Idempotency lookup — partial-index aligned (Fix M5) ───────────────────

    /**
     * Matches the partial unique index {@code uq_client_idempotency_key_active}
     * which covers only CONFIRMED rows (track 24.x — a booking is born CONFIRMED). Filtering by
     * status here allows the planner to use the partial index rather than scanning all rows.
     *
     * <p>Intentional design: idempotency keys can be reused once a booking reaches a
     * terminal state (COMPLETED, CANCELLED, etc.) — a repeat request creates a new booking.
     * This avoids permanent client-side key exhaustion for long-lived users.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.client.id = :clientId
              AND b.idempotencyKey = :idempotencyKey
              AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    Optional<Booking> findActiveByClientIdAndIdempotencyKey(
            @Param("clientId") UUID clientId,
            @Param("idempotencyKey") String idempotencyKey);

    @Query(value = """
            SELECT * FROM bookings
            WHERE master_id = :masterId
              AND status = 'CONFIRMED'
              AND starts_at < :windowEnd
              AND ends_at   > :windowStart
            """, nativeQuery = true)
    // Callers must pass a narrow [windowStart, windowEnd) spanning only the target day.
    // A wide window causes full table scans and inflates the returned list unnecessarily.
    List<Booking> findOverlappingByMaster(
            @Param("masterId") UUID masterId,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );

    /**
     * The occupied {@code [startsAt, endsAt)} intervals of a master's CONFIRMED bookings
     * overlapping {@code [windowStart, windowEnd)}, ordered by start. Backs the whole availability
     * computation — the calendar day projection ({@code SlotCalculationService#getBookableWorkingDays}),
     * the free-slot bookability gate ({@code hasBookableFutureSlot}) and the batched catalogue filter
     * ({@code filterBookableAssignments}): the whole window is loaded ONCE per master and sliced per-day
     * in memory, instead of one {@link #findOverlappingByMaster} query per day.
     *
     * <p><b>Projection, not entities (Perf MEDIUM-1).</b> Returns {@link BookingTimeRange} — the only two
     * columns any consumer reads. The previous {@code SELECT *} native variant hydrated full managed
     * {@code Booking} entities (20+ columns incl. guest PII and the cancel token) purely to call two
     * getters. The overlap predicate ({@code starts_at < windowEnd AND ends_at > windowStart}) is
     * unchanged from {@link #findOverlappingByMaster} — so a booking whose tail spills past a day
     * boundary is still returned, and the query still rides {@code idx_bookings_master_slot_overlap}.
     * Bounded by the service layer's ≤180-day booking horizon (Anti-Bug §E-3 — not an unbounded scan).
     */
    @Query("""
            SELECT new com.beautica.booking.repository.BookingTimeRange(b.startsAt, b.endsAt)
            FROM Booking b
            WHERE b.master.id = :masterId
              AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
              AND b.startsAt < :windowEnd
              AND b.endsAt   > :windowStart
            ORDER BY b.startsAt ASC
            """)
    List<BookingTimeRange> findActiveTimeRangesByMasterInRange(
            @Param("masterId") UUID masterId,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );

    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM bookings
               WHERE master_id = :masterId
                 AND status = 'CONFIRMED'
                 AND starts_at < :requestedEndsAt
                 AND ends_at   > :requestedStartsAt
            )
            """, nativeQuery = true)
    boolean existsOverlap(
            @Param("masterId") UUID masterId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt
    );

    /**
     * Overlap check that excludes a single booking's own row — used by the reschedule
     * flow so a booking does not collide with itself when only its time changes.
     *
     * <p>Same predicate as {@link #existsOverlap(UUID, OffsetDateTime, OffsetDateTime)}
     * (CONFIRMED rows only, half-open interval overlap) plus
     * {@code id <> :excludeBookingId}. Callers must hold the per-master advisory lock
     * (see {@link #acquireAdvisoryLock(UUID)}) before invoking, identical to create.
     */
    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM bookings
               WHERE master_id = :masterId
                 AND id <> :excludeBookingId
                 AND status = 'CONFIRMED'
                 AND starts_at < :requestedEndsAt
                 AND ends_at   > :requestedStartsAt
            )
            """, nativeQuery = true)
    boolean existsOverlapExcluding(
            @Param("masterId") UUID masterId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt,
            @Param("excludeBookingId") UUID excludeBookingId
    );

    // ── Client-scoped conflict check (cross-master/salon double-booking) ─────────
    /**
     * Id of the client's earliest {@code CONFIRMED} booking — with ANY
     * master/salon — that overlaps the requested {@code [requestedStartsAt, requestedEndsAt)}
     * window. Half-open interval overlap, same predicate shape as {@link #existsOverlap}, but
     * scoped by {@code client_id} instead of {@code master_id} so it catches a client double-
     * booking themselves across two different masters. {@code ORDER BY starts_at ASC LIMIT 1}
     * makes the earliest conflict deterministic when a client somehow holds more than one
     * overlapping booking. Backed by the partial index {@code idx_bookings_client_slot_overlap}
     * (V112), mirroring {@code idx_bookings_master_slot_overlap} (V26).
     *
     * <p>Callers must hold the per-client advisory lock (see
     * {@link #acquireClientAdvisoryLockWithTimeout(UUID)}) before invoking, so a concurrent
     * request from the same client cannot race this check-then-insert.
     */
    @Query(value = """
            SELECT id FROM bookings
             WHERE client_id = :clientId
               AND status = 'CONFIRMED'
               AND starts_at < :requestedEndsAt
               AND ends_at   > :requestedStartsAt
             ORDER BY starts_at ASC
             LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findFirstConflictingClientBookingId(
            @Param("clientId") UUID clientId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt
    );

    /**
     * Same as {@link #findFirstConflictingClientBookingId} but excludes a single booking's own
     * row — used by the reschedule flow so a booking does not conflict with itself when only
     * its time changes.
     */
    @Query(value = """
            SELECT id FROM bookings
             WHERE client_id = :clientId
               AND id <> :excludeBookingId
               AND status = 'CONFIRMED'
               AND starts_at < :requestedEndsAt
               AND ends_at   > :requestedStartsAt
             ORDER BY starts_at ASC
             LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findFirstConflictingClientBookingIdExcluding(
            @Param("clientId") UUID clientId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt,
            @Param("excludeBookingId") UUID excludeBookingId
    );

    /**
     * Fused, single-round-trip form of the per-client advisory lock: sets this transaction's
     * {@code lock_timeout} to 3s via {@code set_config('lock_timeout', '3s', true)} — the
     * {@code is_local=true} third argument makes this functionally identical to
     * {@code SET LOCAL lock_timeout = '3s'} (transaction-scoped; resets automatically at
     * commit/rollback and never leaks onto the next borrower of the pooled connection) — AND
     * acquires the salt-{@code 1} advisory lock in the SAME statement. Folding the two into one
     * round trip removes a network hop from every booking write (perf finding; measurable on
     * Neon's serverless proxy, which is a real network hop per statement, not a local call).
     *
     * <p>Postgres evaluates a SELECT target list left-to-right per row, so
     * {@code set_config(...)} is guaranteed to run before {@code pg_advisory_xact_lock(...)} on
     * the same row — the 3s ceiling is already in force for THIS lock acquisition. Because the
     * GUC is transaction-scoped (not just statement-scoped), it also remains in force for the
     * rest of the transaction, so it still bounds the subsequent per-master
     * {@link #acquireAdvisoryLock(UUID)} wait in {@code BookingService.doCreateBooking} /
     * {@code rescheduleBooking} WITHOUT needing to be re-applied there.
     *
     * <p>Serializes concurrent create/reschedule requests from the SAME client so two
     * simultaneous requests cannot both pass the client-conflict check before either insert
     * commits (the classic check-then-act race). Without the timeout, a single authenticated
     * CLIENT firing N concurrent requests would deterministically serialize all of them on the
     * identical advisory lock — parking N-1 Hikari connections for up to the full
     * connection-timeout and starving the pool (Neon free-tier {@code maximum-pool-size: 10})
     * for every other tenant (security fix, booking advisory-lock DoS audit).
     *
     * <p>Uses salt {@code 1} (vs. salt {@code 0} for {@link #acquireAdvisoryLock(UUID)}) so the
     * client-lock and master-lock hash spaces never collide, even in the (already vanishingly
     * unlikely) event a master id and a client's user id share the same UUID text.
     *
     * <p><b>Deadlock freedom:</b> every caller acquires the CLIENT lock (salt 1) BEFORE the
     * master lock (salt 0) — see {@code BookingService.doCreateBooking} /
     * {@code rescheduleBooking}. Because both request paths are the only writers that ever take
     * both locks, and both always acquire them in the same client-then-master order, no session
     * can hold a master lock while waiting on a client lock — the precondition for a two-lock
     * deadlock cycle never arises. {@code GuestBookingService} takes ONLY the master lock (via
     * {@link #acquireAdvisoryLockWithTimeout(UUID)} — no client id to key on), so it can never
     * participate in a two-lock cycle either.
     *
     * <p>A session that waits longer than {@code lock_timeout} aborts the lock wait with
     * Postgres {@code 55P03 lock_not_available}. Hibernate/Spring exception translation
     * surfaces this as {@link org.springframework.dao.CannotAcquireLockException} — mapped to
     * a clean 409 by {@code GlobalExceptionHandler#handleCannotAcquireLock} — instead of
     * parking the connection for the full Hikari connection-timeout (20 s) or surfacing a
     * bare 500.
     */
    @Query(value = """
            SELECT 1 FROM (
                SELECT set_config('lock_timeout', '3s', true),
                       pg_advisory_xact_lock(hashtextextended(CAST(:clientId AS text), 1))
            ) sub
            """, nativeQuery = true)
    Integer acquireClientAdvisoryLockWithTimeout(@Param("clientId") UUID clientId);

    // ── View-access projection — ownership-only, role from SecurityContext ───────
    /**
     * Returns booking ownership data for {@code canViewBooking} in one round-trip.
     *
     * <p>The actor join was removed (Finding 2): the cross-entity join
     * {@code JOIN com.beautica.user.User actor ON actor.id = :actorId} was producing
     * a Cartesian product in SQL and pulling {@code actorRole} from the database on
     * every access check. The actor's role is already present in the
     * {@code SecurityContextHolder} (set by {@code JwtAuthenticationFilter}) —
     * resolving it from there eliminates the cross-join and the extra DB column read.
     *
     * <p>Returns empty when the booking does not exist.
     *
     * <p><b>{@code client} is an explicit {@code LEFT JOIN}</b>, not the implicit
     * {@code b.client.id} path — implicit single-valued-association navigation in JPQL
     * compiles to an INNER join, which would silently exclude every guest (LINK) booking
     * whose {@code client_id} is {@code NULL} (V89). Same class of defect as
     * {@link #findByIdWithFullGraph}'s former inner {@code JOIN FETCH b.client} (CRITICAL
     * finding, track 24.7 audit) — fixed defensively here even though this projection is not
     * currently wired into any {@code @PreAuthorize} SpEL.
     */
    @Query("""
            SELECT new com.beautica.booking.repository.BookingViewAccess(
                bc.id,
                bm.user.id,
                sOwner.id
            )
            FROM Booking b
            LEFT JOIN b.client bc
            JOIN b.master bm
            JOIN bm.user
            LEFT JOIN bm.salon bs
            LEFT JOIN bs.owner sOwner
            WHERE b.id = :bookingId
            """)
    Optional<BookingViewAccess> findViewAccessById(@Param("bookingId") UUID bookingId);

    /**
     * Completion-authorization projection (Phase 18.4). Mirrors {@link #findViewAccessById}
     * but returns the booking's {@code salonId} (null for an independent-master booking) so
     * {@code AuthorizationService.canCompleteBooking} can admit a {@code SALON_ADMIN} assigned
     * to that salon — not only the owner. Returns empty when the booking does not exist.
     */
    @Query("""
            SELECT new com.beautica.booking.repository.BookingCompletionAccess(
                bm.user.id,
                bs.id
            )
            FROM Booking b
            JOIN b.master bm
            JOIN bm.user
            LEFT JOIN bm.salon bs
            WHERE b.id = :bookingId
            """)
    Optional<BookingCompletionAccess> findCompletionAccessById(@Param("bookingId") UUID bookingId);

    // Hash collision risk: hashtextextended produces a 64-bit hash of the UUID text.
    // Birthday-paradox probability is negligible for current master counts (<10,000)
    // but should be revisited if the platform scales significantly.
    //
    // Ordering: BookingService.doCreateBooking / rescheduleBooking acquire this lock AFTER
    // acquireClientAdvisoryLockWithTimeout (see that method's javadoc for the full
    // deadlock-freedom argument) — the per-client conflict check runs and can fail fast
    // without ever contending this shared per-master lock, which every other client racing
    // for the same popular master is waiting on. No timeout is (re-)applied here: the fused
    // set_config('lock_timeout', ..., true) issued by acquireClientAdvisoryLockWithTimeout
    // earlier in the SAME transaction is transaction-scoped, so its 3s ceiling already covers
    // this wait too.
    @Query(value = """
            SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))) sub
            """, nativeQuery = true)
    Integer acquireAdvisoryLock(@Param("masterId") UUID masterId);

    /**
     * Fused, single-round-trip form of the per-master advisory lock for callers that take
     * ONLY the master lock (no client lock beforehand) — currently just
     * {@code GuestBookingService#persistBooking}. Sets this transaction's {@code lock_timeout}
     * to 3s via {@code set_config('lock_timeout', '3s', true)} (transaction-scoped, equivalent
     * to {@code SET LOCAL}) AND acquires the salt-{@code 0} advisory lock in the SAME
     * statement/round-trip — see {@link #acquireClientAdvisoryLockWithTimeout(UUID)} for the
     * full rationale (evaluation-order guarantee, DoS-closing motivation, perf round-trip
     * saving) shared by both fused methods.
     *
     * <p>The guest-booking endpoint ({@code POST /api/v1/book/{slug}/booking}) is
     * {@code permitAll}, gated only by a non-IP-bound guest JWT plus a per-IP rate limit, so an
     * unbounded lock wait there is an equally viable advisory-lock DoS vector as the
     * authenticated path — hence the timeout is fused here too, not just on the client lock.
     *
     * <p>{@code BookingService} does NOT use this method for its own master lock: it always
     * takes the client lock first via {@link #acquireClientAdvisoryLockWithTimeout(UUID)},
     * which already sets the transaction-scoped timeout, so its later master lock uses the
     * plain {@link #acquireAdvisoryLock(UUID)} — re-applying the timeout there would be a
     * redundant round trip.
     */
    @Query(value = """
            SELECT 1 FROM (
                SELECT set_config('lock_timeout', '3s', true),
                       pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))
            ) sub
            """, nativeQuery = true)
    Integer acquireAdvisoryLockWithTimeout(@Param("masterId") UUID masterId);

    // ── Guest-booking reminder sweep (Phase 13.3) ─────────────────────────────
    /**
     * Loads guest (LINK) bookings due for a 24h reminder SMS. Bounded by a narrow
     * {@code [from, to)} window supplied by {@link com.beautica.booking.job.BookingReminderJob}
     * (Anti-Bug §E-3: not unbounded), and aligned with the partial index
     * {@code idx_bookings_reminder} (LINK + reminder_sent = FALSE).
     *
     * <p>The fetched rows are mutated ({@code reminderSent = true}) and saved by the
     * job inside its transaction, so the {@code masterService}/{@code serviceDefinition}
     * graph is joined to render the reminder text without a lazy load.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.bookingSource = com.beautica.booking.enums.BookingSource.LINK
              AND b.reminderSent = false
              AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
              AND b.startsAt BETWEEN :from AND :to
            """)
    List<Booking> findGuestBookingsForReminder(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    // ── Guest-cancel by link (Phase 13.4) ─────────────────────────────────────
    /**
     * Resolves a guest (LINK) booking by its one-time {@code cancel_token} for the
     * public cancel page. The lookup hits the V90 partial-unique index
     * {@code idx_bookings_cancel_token} (UNIQUE over non-NULL rows only).
     *
     * <p>The master + service graph is JOIN-FETCHed so the cancel-info page can render
     * {@code masterName}/{@code serviceName} without a lazy load (Anti-Bug §E-2).
     *
     * <p>A consumed token is {@code NULL} (set by {@link #consumeCancelToken}), so a
     * replayed link returns empty → 404 (no info leak about token state).
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.cancelToken = :cancelToken
            """)
    Optional<Booking> findByCancelTokenWithGraph(@Param("cancelToken") UUID cancelToken);

    /**
     * Atomically consumes a cancel token: flips a still-{@code CONFIRMED} guest booking
     * to {@code CANCELLED} and nulls the token, in a single conditional UPDATE.
     *
     * <p><b>One-time / race-safe.</b> The {@code WHERE cancel_token = :token AND status =
     * CONFIRMED} predicate guarantees that of N concurrent {@code POST /cancel/{token}}
     * requests, exactly ONE UPDATE affects 1 row; every other affects 0. Only the winner
     * fires the cancellation SMS + master notification, so the side-effects run exactly
     * once. A replayed POST (token already NULL) updates 0 rows → the service maps it to
     * 404. This mirrors the atomic check-and-set used by the Phase 13.2 OTP recorder and
     * avoids the check-then-act double-cancel race a load→mutate→save flow would leave open.
     *
     * @return the number of rows updated — {@code 1} for the winner, {@code 0} otherwise
     */
    @Modifying
    @Query("""
            UPDATE Booking b
               SET b.status = com.beautica.booking.enums.BookingStatus.CANCELLED,
                   b.cancelToken = null
             WHERE b.cancelToken = :cancelToken
               AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    int consumeCancelToken(@Param("cancelToken") UUID cancelToken);
}
