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

import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    // hydrateClientBookingDetails (formerly findClientBookingDetails) carried the same sentinel
    // idiom — Finding 2 (MEDIUM) — on THREE predicates (statuses, from/toExclusive, serviceIds),
    // deliberately left as-is through Phase 26.4 because a straight Specification/Criteria
    // rewrite would have had to re-express the query's five PII salon-precedence CASE WHEN
    // expressions in a second language. Phase 26.7.1 closed this gap via a hybrid: filtering
    // moved to BookingRepositoryCustom#findIdsByClientIdFiltered (a Specification ID page, same
    // sargable shape as findIdsByMasterIdFiltered), and the JPQL projection above was reduced to
    // a pure WHERE b.id IN :ids hydrate — the SELECT/CASE block itself was never touched. See
    // that method's javadoc for the full history.

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

    // ── Phase 26.5 — GET /bookings/me/booked-days (day-rail dot set) ──────────
    //
    // The rail's dot set must be the caller's FULL booking history for the range — no
    // status filter (see the design's `_bookingDays` getter, computed from the unfiltered
    // `widget.bookings`, not the filtered `_visible` view) — so, unlike
    // findActiveIdsByMasterIdAndStartsAtBetween above, these three queries deliberately
    // carry no `status IN (...)` predicate.
    //
    // Native + SELECT DISTINCT on a timezone-converted date expression: JPQL has no
    // AT TIME ZONE function, and grouping must happen in Postgres (≤ ~361 rows back), not
    // by loading every booking row into heap and reducing in Java. The half-open
    // [:from, :toExclusive) bound on starts_at mirrors BookingService's LocalDate ->
    // OffsetDateTime conversion (from.atStartOfDay(TimeZones.KYIV), to.plusDays(1)
    // .atStartOfDay(TimeZones.KYIV)) exactly, so a dot here and a day-filtered result on
    // GET /bookings/me always agree.
    //
    // The 'Europe/Kyiv' literal below cannot be replaced with a bound parameter reliably
    // (AT TIME ZONE's right-hand operand is polymorphic; Postgres can fail to infer a bound
    // parameter's type there) nor with a reference to the Java constant TimeZones.KYIV
    // (native SQL has no access to JVM constants) — it is intentionally a literal, kept in
    // sync with TimeZones.KYIV's value ("Europe/Kyiv") by convention. Update both together.
    //
    // Return type is java.sql.Date, NOT java.time.LocalDate (CRITICAL fix, backend-qa
    // BookingMyBookedDaysIT, 2026-07-17). A native scalar projection returns the raw JDBC type
    // the driver produces for a `date` column — java.sql.Date — and Spring Data's
    // QueryExecutionResultHandler / GenericConversionService has no java.sql.Date -> LocalDate
    // converter registered for that path (that conversion machinery is Hibernate's
    // entity-attribute JSR-310 support, which a native scalar projection never goes through).
    // Declaring List<LocalDate> here made every call 500 with ConverterNotFoundException. The
    // caller (BookingService#getMyBookedDays) converts via java.sql.Date::toLocalDate, which is
    // lossless here because the value is already the Kyiv calendar date computed by the
    // `AT TIME ZONE 'Europe/Kyiv'` expression above, not a UTC-zoned instant — toLocalDate() on a
    // java.sql.Date reads its stored year/month/day fields directly, no zone reinterpretation.

    @Query(value = """
            SELECT DISTINCT (b.starts_at AT TIME ZONE 'Europe/Kyiv')::date AS d
            FROM bookings b
            WHERE b.master_id = :masterId
              AND b.starts_at >= :from
              AND b.starts_at < :toExclusive
            ORDER BY d
            """, nativeQuery = true)
    List<Date> findBookedDatesByMasterId(
            @Param("masterId") UUID masterId,
            @Param("from") OffsetDateTime from,
            @Param("toExclusive") OffsetDateTime toExclusive);

    @Query(value = """
            SELECT DISTINCT (b.starts_at AT TIME ZONE 'Europe/Kyiv')::date AS d
            FROM bookings b
            WHERE b.salon_id IN (:salonIds)
              AND b.starts_at >= :from
              AND b.starts_at < :toExclusive
            ORDER BY d
            """, nativeQuery = true)
    List<Date> findBookedDatesBySalonIds(
            @Param("salonIds") Collection<UUID> salonIds,
            @Param("from") OffsetDateTime from,
            @Param("toExclusive") OffsetDateTime toExclusive);

    @Query(value = """
            SELECT DISTINCT (b.starts_at AT TIME ZONE 'Europe/Kyiv')::date AS d
            FROM bookings b
            WHERE b.client_id = :clientId
              AND b.starts_at >= :from
              AND b.starts_at < :toExclusive
            ORDER BY d
            """, nativeQuery = true)
    List<Date> findBookedDatesByClientId(
            @Param("clientId") UUID clientId,
            @Param("from") OffsetDateTime from,
            @Param("toExclusive") OffsetDateTime toExclusive);

    /**
     * Batch-hydrates a bounded set of booking IDs with the full association graph.
     * Always called with the result of an ID-only page query, so the IN list size
     * equals the configured page size (default 20) — never unbounded.
     *
     * <p><b>Deliberately does NOT fetch {@code s.owner}</b> — as with {@link #findByIdWithFullGraph}.
     * {@code Salon.owner} is {@code LAZY}, so the fetch join was never an EAGER mitigation, and
     * none of this method's three callers dereferences it: {@code BookingDetailResponse#from}
     * (via {@code BookingService#listProviderBookings}) reads only the salon's name / street /
     * buildingNo / locationNote plus its cityId / districtId, {@code BookingResponse#from} (via
     * {@code MasterService#getMasterCalendar}) touches no salon at all, and
     * {@code NotificationOutboxDrainWorker#dispatchAll} never calls {@code getSalon()}. Restoring
     * it costs an extra join to {@code users} and a full {@code User} row — {@code password_hash}
     * included — hydrated per distinct salon on every provider booking-list and master-calendar
     * page. {@link #findByIdWithFullGraph} does not fetch {@code s.owner} either, for the same
     * reason: the {@code AuthorizationService} ownership checks its callers feed read
     * {@code master.getSalon().getOwner().getId()}, and an identifier is served off the
     * uninitialised proxy without a statement — so the fetch bought nothing there and was removed
     * alongside this one.
     *
     * <p>The "no caller dereferences it" half of that claim is now enforced, not merely asserted:
     * {@code BookingPriceRangeContractIT#should_notScaleStatementCount_when_salonMasterPageHasManyBookings}
     * pins the statement count of a provider page whose rows carry a REAL salon — the only fixture
     * shape in which a {@code Salon.owner} proxy exists at all — so a regression that starts walking
     * it shows up as 6 -&gt; 7 there. Note the gate detects a dereference of a non-identifier
     * property; {@code getOwner().getId()} is served off the uninitialised proxy and costs nothing,
     * which is why {@code AuthorizationService}'s id-only checks would not have needed this fetch
     * even if they did run here.
     */
    @Query("""
            SELECT b FROM Booking b
            LEFT JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.id IN :ids
            """)
    List<Booking> findAllByIdsWithGraph(@Param("ids") List<UUID> ids);

    // ── Client booking-detail projection (Phase 19.3; sentinel removed Phase 26.7.1) ──
    /**
     * Hydrates a bounded set of booking ids into the enriched
     * {@link com.beautica.booking.dto.BookingDetailResponse} projection for {@code GET
     * /bookings/me} (CLIENT role): every field that DTO needs for a client row, plus a
     * {@code reviewExists} flag via {@code LEFT JOIN Review}. {@code mu.professionalTitle} rides
     * the same {@code JOIN m.user mu} already used for {@code mu.firstName}/{@code mu.lastName}
     * — no additional join, and the column is nullable (a master may never have set a title).
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
     * <p><b>Phase 26.7.1 — the sentinel is gone; this is now a pure {@code IN :ids} hydrate.</b>
     * Prior to this phase the {@code WHERE} clause carried {@code b.client.id = :clientId} plus
     * three {@code (:x IS NULL OR …)} sentinel predicates (statuses, {@code CAST}-guarded
     * {@code from}/{@code toExclusive}, and {@code serviceIds}) — the same non-sargable idiom
     * Phase 26.1 removed from the provider paths (Finding 2, MEDIUM, backend-perf), left
     * deliberately unconverted here because a straight Criteria rewrite would have had to
     * re-express the five PII {@code CASE WHEN} salon-precedence expressions below in a second
     * language (Option A in the phase doc), risking a silent leak of a salon-employed master's
     * home address onto a salon booking. Phase 26.7.1's hybrid (Option C) resolves this without
     * that risk: the filtering — the mandatory {@code b.client.id = :clientId} scope plus the
     * optional status/date-range/serviceId predicates — moved to a dynamic
     * {@link org.springframework.data.jpa.domain.Specification} ID page,
     * {@code BookingRepositoryCustom#findIdsByClientIdFiltered} (see
     * {@link BookingSpecifications#clientIdEquals}), built with the exact same sargable,
     * no-dead-branch technique as {@code findIdsByMasterIdFiltered}. THIS method now does only
     * the hydrate half: given a bounded page of ids from that ID-page query, it re-emits the
     * verbatim {@code SELECT new ClientBookingDetailProjection(…)} projection — including all
     * five {@code CASE WHEN} expressions, copied character-for-character, never re-expressed —
     * against a single {@code WHERE b.id IN :ids}. No sentinel, no {@code CAST}, no {@code
     * :clientId}/{@code :statuses}/{@code :from}/{@code :toExclusive}/{@code :serviceIds}
     * parameters remain on this query; ownership and filtering are enforced entirely upstream, on
     * the ID page. {@code BookingService#listClientBookings} composes the two calls and
     * re-imposes the ID page's order onto these hydrated rows (an {@code IN} clause does not
     * guarantee row order), mirroring the provider path's
     * {@code findIdsByMasterIdFiltered}/{@code findIdsBySalonIdsFiltered} +
     * {@code findAllByIdsWithGraph} two-query pattern exactly.
     *
     * <p><b>Discovery locality is district-primary via the salon link</b> — the salon's
     * city/district/address wins when the master is salon-employed, else the master's own
     * user row. This mirrors {@code SearchService}'s {@code COALESCE(salon, user)} rule so
     * the booking detail and search results agree on a provider's locality. The projection
     * returns the FK ids only; {@code BookingService} resolves the {@code name_uk} labels
     * through the {@code DiscoveryLocationResolver} M2 seam (§E: batched, not per row).
     *
     * <p>{@code ids} is always the bounded (page-size, default 20) result of
     * {@code findIdsByClientIdFiltered} — never an unbounded, caller-supplied collection. This is
     * the same bounded-{@code IN} contract {@link #findAllByIdsWithGraph} already documents for
     * the provider path.
     *
     * <p><b>Ordering is derived upstream, on the ID page — this query carries no {@code ORDER
     * BY}.</b> As of Phase 26.7.1 the {@link Sort} that used to reach this method (pre-validated
     * and pre-normalized by {@code BookingService#normalizeBookingSort}: whitelisted to
     * {@code startsAt} alone since Phase 26.8 retired {@code priceAtBooking} — no repeat of that
     * property is accepted either — defaulted to {@code startsAt DESC} when unsorted, always
     * carrying a trailing {@code id ASC} tiebreaker) is instead
     * translated into Criteria {@code Order}s by
     * {@code BookingRepositoryCustomImpl.findIdPage} — the same choke point the provider
     * ID-page queries already use. This unifies both roles' sort discipline onto one code path;
     * see {@code findIdsByClientIdFiltered}'s javadoc for the full contract.
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
                CASE WHEN r.id IS NOT NULL THEN true ELSE false END,
                b.priceMaxAtBooking,
                b.appointment.id,
                b.client.avatarUrl
            )
            FROM Booking b
            JOIN b.client
            JOIN b.master m
            JOIN m.user mu
            LEFT JOIN m.salon s
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            LEFT JOIN Review r ON r.booking = b
            WHERE b.id IN :ids
            """)
    List<ClientBookingDetailProjection> hydrateClientBookingDetails(@Param("ids") Collection<UUID> ids);

    // ── Full-graph single lookup (Fix M6 — lazy loads on mutation response) ────

    /**
     * <b>Guest (LINK) bookings ({@code client_id IS NULL}, V89) must resolve here too</b> —
     * {@code client} is a {@code LEFT JOIN FETCH}, not an inner join. An inner join here
     * silently excludes every null-client row, which made the provider transition paths (backing
     * {@code /complete}, {@code /decline}, {@code /not-complete}) and {@code getBooking}
     * ({@code GET /bookings/{id}}) 404 for ANY guest booking — the entire provider-side guest
     * lifecycle was unreachable (CRITICAL finding, track 24.7 audit). See
     * {@link #findAllByIdsWithGraph} for the sibling batch-hydrate query with the same fix.
     *
     * <p><b>Deliberately does NOT fetch {@code s.owner}</b> — the third and last removal of this
     * dead fetch, after {@link #findAllByIdsWithGraph} and
     * {@code findActiveByClientIdAndIdempotencyKey}. It was dead for two independent reasons:
     * <ol>
     *   <li>Every {@code getOwner()} in {@code src/main/java} is either a null check or
     *       {@code .getId()} ({@code AuthorizationService} x7, {@code MasterService#requireOwner},
     *       {@code SalonResponse#from}). Hibernate serves an identifier off an uninitialised proxy
     *       without a statement, so nothing on these paths ever needed the row.</li>
     *   <li>{@code Salon.owner} is {@code @ManyToOne(LAZY)} on a {@code nullable = false} column
     *       ({@code Salon}), so Hibernate always hands back a proxy without a DB hit — the
     *       {@code getOwner() != null} guards never initialise it and never evaluate false.</li>
     * </ol>
     * The fetch therefore cost an extra join into {@code users} plus a full {@code User} row
     * ({@code password_hash} included) on all six callers — {@code BookingService} (getBooking and
     * the decline/complete/cancel/reschedule paths) and {@code ReviewService#createReview} —
     * and bought nothing. Pinned by
     * {@code BookingPriceRangeContractIT#should_notHydrateTheSalonOwner_when_loadingABookingDetail}:
     * a statement count cannot detect a re-added fetch join (a fetch join widens an existing join
     * rather than issuing a statement), so that gate counts HYDRATED ENTITIES instead — the same
     * split of responsibilities as {@code SALON_MASTER_PAGE_ENTITIES} guarding
     * {@link #findAllByIdsWithGraph}.
     */
    @Query("""
            SELECT b FROM Booking b
            LEFT JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.id = :id
            """)
    Optional<Booking> findByIdWithFullGraph(@Param("id") UUID id);

    /**
     * All chained booking rows of ONE multi-service visit (BE-3), ordered by {@code startsAt}
     * ascending. That ordering is retained unconditionally, but "back-to-back" is NOT: once any
     * item has been rescheduled individually (phase 30.1's relaxed contiguity —
     * {@code AppointmentTransitionService#rescheduleAppointmentItem}), consecutive rows may be
     * separated by a legal gap. Callers must not assume adjacency from this ordering alone.
     *
     * <p>Naturally bounded — a visit holds at most {@code SlotCalculationService.MAX_SERVICES_PER_VISIT}
     * (10) rows — so no {@code Pageable} is needed (§E-3). Rides the partial index
     * {@code idx_bookings_appointment} (V125, {@code WHERE appointment_id IS NOT NULL}). Hydrates the
     * SAME graph as {@link #findByIdWithFullGraph} ({@code master.user}, {@code master.salon},
     * {@code masterService.serviceDefinition}) so {@code AppointmentDetailResponse.from} reads the
     * master summary + per-item service name with no lazy load or N+1. {@code b.client} is deliberately
     * NOT fetched — the appointment header carries the client, and the item projection does not read
     * it.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.appointment.id = :appointmentId
            ORDER BY b.startsAt ASC
            """)
    List<Booking> findByAppointmentIdWithGraph(@Param("appointmentId") UUID appointmentId);

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
     *
     * <p><b>Fetch graph is scoped to exactly what {@link com.beautica.booking.dto.BookingResponse
     * BookingResponse#from} reads</b>, and no wider. This query runs on EVERY {@code POST /bookings}
     * that carries an idempotency key — not only on a replay — so every association fetched here is
     * paid for on the create hot path. Its sole production caller is
     * {@code BookingService#createBooking}, which maps the {@code Optional} straight through
     * {@code BookingResponse::from} and returns the record; the entity never escapes that
     * expression, so nothing downstream can dereference an association this query did not fetch.
     * {@code BookingResponse#from} touches {@code getId}, {@code getClient().getId()},
     * {@code getMaster().getId()}, {@code getMasterService().getId()},
     * {@code getMasterService().getServiceDefinition().getName()} and scalar columns — hence
     * client + masterService + serviceDefinition are fetched and nothing else is.
     *
     * <p><b>Deliberately does NOT fetch {@code m.user}, {@code m.salon} or {@code s.owner}</b>
     * (all three {@code LAZY}, so the joins were never an EAGER mitigation — same defect class as
     * {@link #findAllByIdsWithGraph}). Together they cost two extra joins into {@code users} plus
     * one into {@code salons}, hydrating a full {@code User} row — {@code password_hash} included —
     * for both the master and the salon owner, on every keyed create. {@code b.master} itself stays
     * fetch-joined: {@code getMaster().getId()} alone would be served by an uninitialised proxy, so
     * dropping it is a separate behavioural question this note does not settle.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master
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

    /**
     * Overlap check that excludes an ENTIRE visit's own chained rows — the appointment-level
     * (BE-4 reschedule) analogue of {@link #existsOverlapExcluding}. A multi-service visit
     * occupies N {@code bookings} rows (all sharing {@code appointment_id}), so a single
     * {@code id <> :excludeBookingId} exclusion is not enough when re-planning the WHOLE block:
     * the new span can legitimately overlap several of the visit's OWN current rows.
     * {@code appointment_id IS DISTINCT FROM :appointmentId} is null-safe (legacy single-service
     * bookings carry a {@code NULL appointment_id} and are never excluded by this predicate).
     * Same predicate otherwise as {@link #existsOverlap} (CONFIRMED rows only, half-open interval
     * overlap). Callers must hold the per-master advisory lock (see {@link #acquireAdvisoryLock(UUID)})
     * before invoking, identical to the single-booking reschedule flow.
     */
    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM bookings
               WHERE master_id = :masterId
                 AND appointment_id IS DISTINCT FROM :appointmentId
                 AND status = 'CONFIRMED'
                 AND starts_at < :requestedEndsAt
                 AND ends_at   > :requestedStartsAt
            )
            """, nativeQuery = true)
    boolean existsOverlapExcludingAppointment(
            @Param("masterId") UUID masterId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt,
            @Param("appointmentId") UUID appointmentId
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
     * Same as {@link #findFirstConflictingClientBookingIdExcluding} but excludes an ENTIRE visit's
     * own chained rows via {@code appointment_id} — the appointment-level (BE-4 reschedule)
     * analogue, used when re-planning a whole multi-service visit rather than one booking. Null-safe
     * the same way {@link #existsOverlapExcludingAppointment} is.
     */
    @Query(value = """
            SELECT id FROM bookings
             WHERE client_id = :clientId
               AND appointment_id IS DISTINCT FROM :appointmentId
               AND status = 'CONFIRMED'
               AND starts_at < :requestedEndsAt
               AND ends_at   > :requestedStartsAt
             ORDER BY starts_at ASC
             LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findFirstConflictingClientBookingIdExcludingAppointment(
            @Param("clientId") UUID clientId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt,
            @Param("appointmentId") UUID appointmentId
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
     * <p>Postgres does NOT formally guarantee subexpression evaluation order (docs §4.2.14 leaves
     * it undefined), but the executor's {@code ExecProject} evaluates target-list entries in
     * order, so in every current implementation {@code set_config(...)} runs before
     * {@code pg_advisory_xact_lock(...)} on the same row — the 3s ceiling is already in force for
     * THIS lock acquisition. Were that ever to change, only this one acquisition would wait
     * unbounded (the pre-fix behaviour); the GUC would still bound the rest. Because the
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
     * a clean 409 by {@code GlobalExceptionHandler#handlePessimisticLockingFailure} — instead of
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

    /**
     * All-rows visit-level (BE-4 reschedule) analogue of {@link #findCompletionAccessById}, backing
     * BOTH {@code AuthorizationService.canRescheduleAppointment} AND {@code
     * AuthorizationService.enforceCanManageAppointment} — the pre-lock authorization check for the
     * whole-visit decline/complete/not-complete transitions AND the per-ITEM {@code
     * declineAppointmentItem}. A visit is single-master (BE-1 locked design) BY CONSTRUCTION of the
     * only writers that create chained bookings ({@code VisitPlanner.planChainedItems} resolves
     * every item off one {@code Master}) — but that invariant has no DB constraint behind it:
     * nothing in the schema (see {@code V124__create_appointments.sql} / {@code
     * V125__add_bookings_appointment_id.sql}) enforces that a future writer cannot append a
     * different-master item. This query therefore fetches EVERY row, deterministically ordered by
     * {@code b.id}, and every caller must require provider authority over ALL of them, not just the
     * first — trusting a single arbitrary row (the previous {@code Limit.of(1)} overload, removed)
     * would silently authorize the whole visit off one item's master, an authorization bypass the
     * moment a mixed-master visit exists. Cheap regardless: a visit is capped at {@code
     * SlotCalculationService.MAX_SERVICES_PER_VISIT} (10 rows, §E-3), so this is at most a 10-row
     * projection read — no {@code JOIN FETCH}, same shape as the previous capped query. Returns
     * empty when the appointment does not exist or has no items (fail-closed at the caller).
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
            WHERE b.appointment.id = :appointmentId
            ORDER BY b.id
            """)
    List<BookingCompletionAccess> findAllCompletionAccessByAppointmentId(
            @Param("appointmentId") UUID appointmentId);

    /**
     * Scalar, entity-manager-bypassing projection of the CONFIRMED subset of an appointment's
     * chained items — the post-lock freshness re-check
     * {@code AppointmentTransitionService#rescheduleAppointment} runs immediately after acquiring
     * the header lock (cycle-5 audit finding 1, 2026-08-03) and BEFORE mutating its own target
     * items, whose entities were necessarily loaded (by {@code resolveVisitForClientReschedule}/
     * {@code resolveVisitForProviderReschedule}) BEFORE that lock existed to protect the read.
     *
     * <p><b>Why a bare {@code b.id} projection, never {@code SELECT b}.</b> The caller's target
     * items are already managed in the SAME persistence context. An entity-returning query for the
     * same ids would hand back those SAME cached Java instances from the identity map rather than
     * fresh column values — Hibernate never overwrites an already-managed entity's fields from a
     * later query's resultset — mirroring {@link #findAllCompletionAccessByAppointmentId}'s and
     * {@code AppointmentRepository#findClientIdById}'s identical non-poisoning rationale. A scalar
     * projection never touches the entity manager, so it cannot be poisoned by (or poison) an
     * earlier or later load of the same rows.
     *
     * <p>{@code Booking} now carries {@code @DynamicUpdate} (G1, cycle-7 audit 2026-08-03), so a
     * plain {@code save()} of a stale, still-{@code CONFIRMED}-in-memory item no longer risks
     * writing back a concurrently-committed terminal status — Hibernate only includes the columns
     * this call's own transaction actually dirtied. This check therefore no longer exists to
     * prevent a silent resurrection; it exists so a stale item is rejected with a clean, retryable
     * 409 INSTEAD OF proceeding to move an item whose CONFIRMED precondition already lapsed — the
     * caller compares this result against its own target ids and aborts on any mismatch rather
     * than silently completing a transition the caller no longer has authority to make (the
     * concurrent write already resolved this item to a different terminal state).
     *
     * @return the ids of {@code appointmentId}'s chained items that are, AS OF THIS CALL,
     *         genuinely still {@code CONFIRMED} — empty if the appointment has no such items
     */
    @Query("""
            SELECT b.id FROM Booking b
             WHERE b.appointment.id = :appointmentId
               AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    Set<UUID> findConfirmedIdsByAppointmentId(@Param("appointmentId") UUID appointmentId);

    /**
     * Scalar, entity-manager-bypassing CONFIRMED-status probe for exactly ONE booking id — the
     * per-ITEM counterpart of {@link #findConfirmedIdsByAppointmentId}, used by the three per-item
     * write paths that mutate a single already-loaded child row after taking the appointment
     * header lock: {@code AppointmentTransitionService#rescheduleAppointmentItem},
     * {@code AppointmentTransitionService#declineAppointmentItem}, and
     * {@code BookingService#cancelBooking(UUID, Booking, CancelBookingRequest)} (F1, HIGH, cycle-6
     * audit 2026-08-03 — closes the entity-staleness/terminal-state-resurrection defect class
     * {@code rescheduleAppointment}'s own post-lock recheck already closed for the whole-visit
     * path).
     *
     * <p><b>Why a bare boolean, never {@code SELECT b}.</b> Same non-poisoning rationale as
     * {@link #findConfirmedIdsByAppointmentId}: each of the three callers' target {@code Booking}
     * is already managed in the SAME persistence context, loaded (necessarily) BEFORE the header
     * lock existed to protect that read. An entity-returning query for the same id would hand back
     * that SAME cached instance from the identity map rather than fresh column values — Hibernate
     * never overwrites an already-managed entity's fields from a later query's resultset. A scalar
     * projection never touches the entity manager, so it cannot be poisoned by, or poison, an
     * earlier or later load of the same row.
     *
     * <p>{@code Booking} now carries {@code @DynamicUpdate} (G1, cycle-7 audit 2026-08-03), so a
     * plain {@code save()}/{@code saveAndFlush()} of a stale, still-{@code CONFIRMED}-in-memory
     * target only writes the columns THIS transaction actually dirtied — e.g. a per-item cancel
     * and a per-item reschedule of the SAME leg racing each other (both observing the header stay
     * CONFIRMED because a sibling remains) can no longer clobber each other's disjoint columns
     * (status vs. starts_at/ends_at). This check's job is therefore narrower than it used to be:
     * it no longer prevents column-level corruption, it prevents a caller from completing a
     * transition whose CONFIRMED precondition already lapsed — e.g. a reschedule silently
     * "succeeding" (new time persisted) on a leg the other racer already declined, which would be
     * a confusing state even though no column was corrupted. Each caller compares this result and
     * aborts with a clean 409 on {@code false} rather than let that stale transition through.
     *
     * @return {@code true} iff {@code bookingId} exists and is, AS OF THIS CALL, still CONFIRMED
     */
    @Query("""
            SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
            FROM Booking b
            WHERE b.id = :bookingId AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    boolean existsConfirmedById(@Param("bookingId") UUID bookingId);

    // ── Schedule-override conflict check (2026-07-26 design) ──────────────────

    /**
     * Candidates for the "schedule override over existing bookings" conflict check: every
     * {@code CONFIRMED} booking of {@code masterId} whose {@code startsAt} falls in
     * {@code [notBefore, windowEnd)} — ONE query for the caller's WHOLE requested date range
     * (Anti-Bug §E — never one query per expanded date), joined exactly enough to render an
     * {@code OverrideConflictResponse} (client/guest name, service name) and to route the eventual
     * cancellation ({@code appointmentId}, nullable — standalone vs. appointment-child decline).
     *
     * <p>{@code notBefore} folds BOTH lower bounds the design's conflict rule needs — "the date
     * range starts here" and "never a booking that already started/is past on today's date" — into
     * ONE comparison: the caller passes {@code max(rangeStart, now)}, computed once against the
     * injected {@code Clock} (never {@code Instant.now()} — Anti-Bug §G).
     *
     * <p>{@code b.client} is a {@code LEFT JOIN}, not the implicit inner-join path — a guest
     * (LINK) booking's {@code client_id} is {@code NULL} (V89), and an inner join here would
     * silently exclude every guest conflict, the same defect class {@link #findByIdWithFullGraph}'s
     * javadoc documents at length.
     *
     * <p><b>{@code pageable} (backend-perf audit finding 3, 2026-07-26 re-audit).</b> The caller
     * passes an UNSORTED {@link Pageable} purely to cap the number of rows returned (Spring Data
     * translates it to a plain SQL {@code LIMIT}/{@code OFFSET} — the query's own
     * {@code ORDER BY b.startsAt ASC} above is untouched, since the {@code Pageable} carries no
     * {@code Sort} of its own). Without this, a caller scanning a range up to 366 days wide could
     * pull every {@code CONFIRMED} booking the master has in that whole span into memory before any
     * filtering ever ran. See {@code ScheduleOverrideConflictService#MAX_CANDIDATES_SCANNED}'s
     * javadoc for the caller-side cap value and rationale.
     */
    @Query("""
            SELECT new com.beautica.booking.repository.OverrideConflictCandidate(
                b.id,
                b.appointment.id,
                b.startsAt,
                b.endsAt,
                b.client.firstName,
                b.client.lastName,
                b.guestName,
                b.guestSurname,
                sd.name
            )
            FROM Booking b
            LEFT JOIN b.client
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            WHERE b.master.id = :masterId
              AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
              AND b.startsAt >= :notBefore
              AND b.startsAt < :windowEnd
            ORDER BY b.startsAt ASC
            """)
    List<OverrideConflictCandidate> findConfirmedCandidatesForOverrideConflictCheck(
            @Param("masterId") UUID masterId,
            @Param("notBefore") OffsetDateTime notBefore,
            @Param("windowEnd") OffsetDateTime windowEnd,
            Pageable pageable);

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
     * ONLY the master lock (no client lock beforehand). Originally just
     * {@code GuestBookingService#persistBooking}; now also used by the no-client-lock (guest
     * visit) branch of {@code BookingService#rescheduleBooking} and
     * {@code AppointmentTransitionService#rescheduleAppointment} (a guest booking/visit has no
     * client account to lock or conflict-check against), and by
     * {@code ScheduleOverrideConflictService#applyOverrideWithConflictHandling} (the
     * schedule-override write is master-scoped only — it never takes a client lock at all,
     * regardless of whether the conflicting bookings it may decline are guest or registered-client).
     * Sets this transaction's {@code lock_timeout}
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
     * <p>Every OTHER caller that takes a client lock first (e.g. {@code BookingService}'s own
     * registered-client create/reschedule path) does NOT use this method for its master lock: the
     * client lock already sets the transaction-scoped timeout via
     * {@link #acquireClientAdvisoryLockWithTimeout(UUID)}, so the later master lock uses the
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
    // {@code LEFT JOIN FETCH b.appointment} (BE-7): a multi-service guest visit's N item rows share one
    // appointment_id, so the reminder sweep groups by it to send ONE reminder per visit (not one per
    // item). The fetch hydrates the header eagerly so BookingReminderJob can read appointment id with no
    // lazy load / N+1; legacy single guest bookings LEFT-join to a null header and each get their own
    // reminder, unchanged.
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            LEFT JOIN FETCH b.appointment
            WHERE b.bookingSource = com.beautica.booking.enums.BookingSource.LINK
              AND b.reminderSent = false
              AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
              AND b.startsAt BETWEEN :from AND :to
            """)
    List<Booking> findGuestBookingsForReminder(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    // ── Guest (LINK) visit cancel by link (BE-7) ──────────────────────────────
    /**
     * Cancels every still-{@code CONFIRMED} child booking of a guest visit in ONE conditional UPDATE:
     * flips each to {@code CANCELLED}, stamps {@code CLIENT_CANCELLED}, and nulls its per-item cancel
     * token (V91 permits a NULL token on a terminal LINK row). Called by
     * {@code GuestVisitCancellationService} right after {@code AppointmentRepository#consumeCancelToken}
     * wins the header race, so the header and all N items reach {@code CANCELLED} atomically in the same
     * transaction — freeing every item's slot (the {@code no_overlapping_bookings} EXCLUDE predicate is
     * {@code status = 'CONFIRMED'} only). Rides the partial index {@code idx_bookings_appointment}.
     *
     * @return the number of item rows cancelled
     */
    @Modifying
    @Query("""
            UPDATE Booking b
               SET b.status = com.beautica.booking.enums.BookingStatus.CANCELLED,
                   b.cancellationReason = com.beautica.booking.enums.CancellationReason.CLIENT_CANCELLED,
                   b.cancelToken = null
             WHERE b.appointment.id = :appointmentId
               AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    int cancelItemsByAppointmentId(@Param("appointmentId") UUID appointmentId);

    /**
     * Marks every item of the given guest visits as reminded (BE-7). Called by
     * {@code BookingReminderJob} after it sends ONE reminder per visit: this flips ALL of a visit's
     * item rows — including any tail item whose own {@code starts_at} falls outside the sweep's 2h
     * reminder window (a visit can span up to 10h) — so no later sweep can re-remind the visit's tail.
     * Bounded to the visits actually reminded in one sweep.
     *
     * @return the number of item rows updated
     */
    @Modifying
    @Query("""
            UPDATE Booking b
               SET b.reminderSent = true
             WHERE b.appointment.id IN :appointmentIds
               AND b.reminderSent = false
            """)
    int markVisitRemindersSentByAppointmentIds(@Param("appointmentIds") Collection<UUID> appointmentIds);

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
     *
     * <p><b>BE-7 (guest visits).</b> The {@code b.appointment IS NULL} guard confines this legacy
     * single-booking path to true standalone guest bookings. A multi-service visit's child item
     * carries its own per-item {@code cancel_token} (mandated by the V91 {@code chk_bookings_guest_fields}
     * CHECK) but MUST only be cancellable as a whole visit via the header token
     * ({@code GuestVisitCancellationService}). Were a per-item token accepted here it would cancel one
     * item and desync the {@code Appointment} header + siblings. Excluding {@code appointment_id IS NOT
     * NULL} makes such a token resolve to empty → the existing 404 path, identical to an unknown token
     * (no state oracle).
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.cancelToken = :cancelToken
              AND b.appointment IS NULL
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
