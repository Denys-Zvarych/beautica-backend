package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingPriceRange;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.ClientBookingDetailProjection;
import com.beautica.common.PageResponse;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBookingConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.beautica.common.TimeZones;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final MasterRepository masterRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final UserRepository userRepository;
    private final SalonRepository salonRepository;
    private final AuthorizationService authz;
    private final NotificationOutboxService outboxService;
    private final SlotCalculationService slotCalculationService;
    private final ReviewRepository reviewRepository;
    private final DiscoveryLocationResolver discoveryLocationResolver;
    private final Clock clock;
    private final CacheManager cacheManager;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final ScheduleDateMath dateMath;

    @Transactional
    public BookingResponse createBooking(UUID clientId, String idempotencyKey, CreateBookingRequest request) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Fix M5: use the partial-index-aligned query to avoid full table scan
            return bookingRepository.findActiveByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                    .map(BookingResponse::from)
                    .orElseGet(() -> doCreateBooking(clientId, idempotencyKey, request));
        }
        return doCreateBooking(clientId, idempotencyKey, request);
    }

    @Transactional(readOnly = true)
    public BookingDetailResponse getBooking(UUID actorUserId, UUID bookingId) {
        // Existence + view-authorization collapse to a single uniform 403 (Finding 8 — existence
        // oracle), mirroring cancelBooking/rescheduleBooking. A missing id and an existing-but-
        // foreign booking must be indistinguishable to the caller: a missing booking short-circuits
        // to the SAME 403 the ownership guard (enforceCanViewBooking) throws for a foreign one, so
        // an authenticated actor can no longer probe whether an arbitrary booking id exists by
        // observing a 404-vs-403 split. The full-graph fetch is still required to build the detail
        // response for the legitimate owner (200 + full detail unchanged).
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        authz.enforceCanViewBooking(actorUserId, booking);
        boolean canReview = canReview(
                booking.getStatus(), reviewRepository.existsByBookingId(bookingId), booking.getClient() != null);
        return enrichSingle(booking, canReview);
    }

    /**
     * Builds the enriched {@link BookingDetailResponse} for a fully-hydrated booking,
     * resolving the district-primary discovery locality labels through the M2 seam.
     * Salon-employed masters resolve to the salon's locality; independent masters to the
     * master's own user-row locality — mirroring {@code SearchService}'s COALESCE rule.
     */
    private BookingDetailResponse enrichSingle(Booking booking, boolean canReview) {
        Salon salon = booking.getMaster().getSalon();
        User masterUser = booking.getMaster().getUser();
        UUID cityId = salon != null ? salon.getCityId() : masterUser.getCityId();
        UUID districtId = salon != null ? salon.getDistrictId() : masterUser.getDistrictId();

        DiscoveryLabels labels = discoveryLocationResolver.resolveLabels(
                cityId == null ? List.of() : List.of(cityId),
                districtId == null ? List.of() : List.of(districtId));

        return BookingDetailResponse.from(
                booking, canReview, labels.cityLabel(cityId), labels.districtLabel(districtId));
    }

    /**
     * {@code canReview = COMPLETED && no existing review && a registered client exists to leave
     * one} — single source of the truth table. A guest (LINK) booking has no account
     * ({@code client_id} is null, V89 {@code chk_bookings_guest_fields}), so it can never be
     * review-eligible even once COMPLETED — {@code ReviewService.createReview} requires an
     * authenticated CLIENT owner, which a guest booking can never have.
     */
    private static boolean canReview(BookingStatus status, boolean reviewExists, boolean hasClient) {
        return hasClient && status == BookingStatus.COMPLETED && !reviewExists;
    }

    /** Discovery city id: salon's when salon-employed, else the master's own user row. */
    private static UUID discoveryCityId(Booking booking) {
        Salon salon = booking.getMaster().getSalon();
        return salon != null ? salon.getCityId() : booking.getMaster().getUser().getCityId();
    }

    /** Discovery district id: salon's when salon-employed, else the master's own user row. */
    private static UUID discoveryDistrictId(Booking booking) {
        Salon salon = booking.getMaster().getSalon();
        return salon != null ? salon.getDistrictId() : booking.getMaster().getUser().getDistrictId();
    }

    /** Batch-resolves locality labels for a page of projections (M2 seam — fixed two queries). */
    private DiscoveryLabels resolveProjectionLabels(List<ClientBookingDetailProjection> rows) {
        Set<UUID> cityIds = new LinkedHashSet<>();
        Set<UUID> districtIds = new LinkedHashSet<>();
        for (ClientBookingDetailProjection r : rows) {
            if (r.discoveryCityId() != null) {
                cityIds.add(r.discoveryCityId());
            }
            if (r.discoveryDistrictId() != null) {
                districtIds.add(r.discoveryDistrictId());
            }
        }
        return discoveryLocationResolver.resolveLabels(cityIds, districtIds);
    }

    /** Batch-resolves locality labels for a page of hydrated bookings (M2 seam — fixed two queries). */
    private DiscoveryLabels resolveBookingLabels(List<Booking> bookings) {
        Set<UUID> cityIds = new LinkedHashSet<>();
        Set<UUID> districtIds = new LinkedHashSet<>();
        for (Booking b : bookings) {
            UUID cityId = discoveryCityId(b);
            UUID districtId = discoveryDistrictId(b);
            if (cityId != null) {
                cityIds.add(cityId);
            }
            if (districtId != null) {
                districtIds.add(districtId);
            }
        }
        return discoveryLocationResolver.resolveLabels(cityIds, districtIds);
    }

    /** Maps a CLIENT projection row to the enriched response, stamping resolved labels. */
    private static BookingDetailResponse toDetailResponse(
            ClientBookingDetailProjection p, DiscoveryLabels labels) {
        return new BookingDetailResponse(
                p.id(),
                p.clientId(),
                p.masterId(),
                p.masterServiceId(),
                p.serviceName(),
                p.status(),
                p.startsAt().atZoneSameInstant(TimeZones.KYIV),
                p.endsAt().atZoneSameInstant(TimeZones.KYIV),
                p.priceAtBooking(),
                p.priceMaxAtBooking(),
                p.durationMinutesAtBooking(),
                p.createdAt().atOffset(ZoneOffset.UTC),
                p.clientFirstName(),
                p.clientLastName(),
                p.masterFirstName(),
                p.masterLastName(),
                p.masterProfessionalTitle(),
                p.clientComment(),
                p.providerComment(),
                p.clientCancellationNote(),
                p.masterAvatarUrl(),
                p.masterType(),
                p.salonName(),
                labels.cityLabel(p.discoveryCityId()),
                labels.districtLabel(p.discoveryDistrictId()),
                p.street(),
                p.buildingNo(),
                p.locationNote(),
                p.categoryName(),
                // Defensive only: this projection is CLIENT-scoped (WHERE client_id = :clientId),
                // so p.clientId() is always non-null in practice — never a guest booking.
                canReview(p.status(), p.reviewExists(), p.clientId() != null));
    }

    /**
     * Lists the actor's bookings as the enriched {@link BookingDetailResponse} (Phase 19.3 —
     * {@code GET /bookings/me} switched from the lean {@code BookingResponse} per locked
     * Option A). {@code canReview} is true only for a {@code COMPLETED} booking with no review.
     *
     * <p><b>CLIENT</b> (Phase 26.7.1) now shares the same two-query ID-page + hydrate shape as
     * the provider roles below: {@code findIdsByClientIdFiltered} (sargable, sentinel-free
     * {@code Specification} ID page) then {@code hydrateClientBookingDetails} ({@code IN :ids}
     * projection hydrate, {@code reviewExists} inline via a {@code LEFT JOIN Review}), with order
     * re-imposed onto the hydrate in {@code listClientBookings} — see that method's javadoc. The
     * locality FK ids are batch-resolved to labels in a fixed two queries through the M2 seam
     * (no N+1), same as before this phase.
     *
     * <p><b>Provider roles</b> (master / salon-owner) reuse the established two-query ID-page +
     * graph-hydrate pattern (Fix H1), then add exactly two bounded follow-ups for the page:
     * one batched {@code findReviewedBookingIds} and the two-query label resolution — never a
     * per-row lookup.
     *
     * <p><b>Phase 26.1 — multi-select status.</b> {@code status} widened from a single optional
     * {@link BookingStatus} to a repeatable {@link List}, bound by Spring from both
     * {@code ?status=A} (1-element list, preserving every pre-26.1 caller byte-for-byte) and
     * {@code ?status=A&status=B}. Normalised once here to an {@link EnumSet} — {@code null} or
     * empty means "no predicate" (unfiltered, matching today's behaviour); a non-empty input
     * de-duplicates and is self-bounded at the enum's cardinality (5), so no caller can build an
     * unbounded {@code IN} list no matter how many times {@code status} is repeated.
     *
     * <p><b>Phase 26.2 — optional {@code from}/{@code to} date-range filter.</b> Both are
     * independent, optional {@link LocalDate} bounds on {@code startsAt}: {@code from} alone is
     * an open-ended future window, {@code to} alone an open-ended past window. {@code to} is
     * INCLUSIVE of the whole local day — resolved as a HALF-OPEN {@code Europe/Kyiv} instant
     * range, {@code [from.atStartOfDay(KYIV), to.plusDays(1).atStartOfDay(KYIV))}, never an
     * {@code <=} on {@code to} itself (which would silently drop every booking after 00:00 Kyiv
     * on the final day) and never a UTC/{@code systemDefault()} zone (which would shift every
     * boundary by the Kyiv offset). {@code from > to} throws a 400 {@link BusinessException}
     * rather than returning an empty page — an empty page would hide a client bug. A span wider
     * than 366 days also throws 400, reusing {@link ScheduleDateMath#assertSpanWithinMax} rather
     * than inventing a new literal. An extreme {@code to} near {@link LocalDate#MAX} (which
     * {@code @DateTimeFormat(iso = DATE)} parses without complaint) would make
     * {@code to.plusDays(1)} throw an uncaught {@link java.time.DateTimeException} — guarded by
     * {@link ScheduleDateMath#assertToPlusOneDayRepresentable}, called unconditionally whenever
     * {@code to} is present, so both a {@code to}-only request and a valid small span landing on
     * that boundary get a clean 400 instead of a 500.
     *
     * <p><b>Phase 26.4 — optional {@code serviceId} multi-select filter.</b> {@code serviceId} is
     * a repeatable {@link List} of {@code MasterService} ids, matched against
     * {@code b.masterService.id} (the direct FK), never {@code masterService.serviceDefinition.id}
     * — see {@code BookingSpecifications#masterServiceIdIn}'s javadoc for why. Normalised here,
     * beside the status normalisation above, into a de-duplicated {@link LinkedHashSet};
     * {@code null} or empty means "no predicate". Unlike {@code status} (self-bounded at the enum
     * cardinality of 5), a caller-supplied UUID list is unbounded in principle, so a size above
     * {@link #MAX_SERVICE_ID_FILTER} throws a 400 {@link BusinessException} before the set is ever
     * handed to a query — the same defense-in-depth reasoning as the controller's
     * {@code @Size(max = 5)} bound on {@code status} (Anti-Bug §B1: bounded collections only).
     * No ownership check is performed against the supplied ids: the master/salon scope predicate
     * already constrains every query, so a {@code serviceId} belonging to a different provider
     * simply matches nothing rather than surfacing a 404 that would turn this endpoint into an
     * existence oracle for {@code MasterService} ids (locked decision — see the phase doc).
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingDetailResponse> getMyBookings(
            UUID actorUserId, Authentication auth, List<BookingStatus> status,
            LocalDate from, LocalDate to, List<UUID> serviceId, Pageable pageable) {
        // Role is already encoded in the JWT-derived authority — no DB round-trip needed to
        // resolve the role. Only SALON_OWNER requires a DB call to fetch the associated salonId.
        Role role = resolveActorRole(auth);

        Set<BookingStatus> statuses = (status == null || status.isEmpty())
                ? null
                : EnumSet.copyOf(status);

        Set<UUID> serviceIds = (serviceId == null || serviceId.isEmpty())
                ? null
                : new LinkedHashSet<>(serviceId);
        if (serviceIds != null && serviceIds.size() > MAX_SERVICE_ID_FILTER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Too many serviceId values (max " + MAX_SERVICE_ID_FILTER + ")");
        }

        if (to != null) {
            dateMath.assertToPlusOneDayRepresentable(to);
        }
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'");
            }
            dateMath.assertSpanWithinMax(from, to);
        }
        OffsetDateTime fromTs = from == null ? null : from.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(TimeZones.KYIV).toOffsetDateTime();

        // Phase 26.3: validate/whitelist/default/tiebreak the sort BEFORE the role dispatch, so
        // BOTH the client projection query and the provider ID-page query receive an identical,
        // already-safe Pageable — see normalizeBookingSort's javadoc for why this must happen
        // here and not deeper in either path.
        Pageable normalizedPageable = normalizeBookingSort(pageable);

        Page<BookingDetailResponse> page = role == Role.CLIENT
                ? listClientBookings(actorUserId, statuses, fromTs, toExclusive, serviceIds, normalizedPageable)
                : listProviderBookings(role, actorUserId, statuses, fromTs, toExclusive, serviceIds, normalizedPageable);

        return PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Resolves the caller's role from the JWT-derived granted authority — the same
     * single-authority-per-principal assumption {@link #getMyBookings} has always relied on.
     * Extracted (Phase 26.5) so {@code getMyBookings} and {@link #getMyBookedDays} share one
     * copy of this lookup rather than each inlining it — a second, silently-diverging copy is
     * exactly how these two endpoints' notion of "who is the caller" would end up disagreeing.
     */
    private Role resolveActorRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .findFirst()
                .map(a -> Role.valueOf(a.getAuthority().replace("ROLE_", "")))
                .orElseThrow(() -> new ForbiddenException("Access denied"));
    }

    /**
     * Phase 26.5 — {@code GET /bookings/me/booked-days}: the set of local (Europe/Kyiv) dates
     * on which the caller has at least one booking in {@code [from, to]}, ascending and
     * distinct. Backs the day-rail dot on the booking-management design
     * ({@code SalonManagementDesign/lib/widgets/bookings_toolbar.dart}'s {@code _bookingDays}).
     *
     * <p><b>Filter-independent by design.</b> The design computes {@code _bookingDays} from the
     * screen's full, unfiltered booking list, not the filtered/sorted view — so the dots keep
     * showing where bookings are even while a status/date/service filter narrows the list below.
     * This method therefore takes no {@code status} / {@code serviceId} parameter and applies no
     * status predicate — do not add one "for symmetry" with {@link #getMyBookings}.
     *
     * <p><b>{@code from}/{@code to} are required</b> (unlike {@code getMyBookings}'s optional
     * range) and capped at 366 days via {@link ScheduleDateMath#assertSpanWithinMax} — an
     * unbounded default would scan the caller's entire booking history. Converted to the same
     * half-open {@code [from, toExclusive)} Kyiv-zoned instant range {@code getMyBookings} uses,
     * so a dot returned here and a non-empty {@code GET /bookings/me?from=D&to=D} for the same
     * date D always agree.
     *
     * <p>Role scope mirrors {@link #getMyBookings}: {@code SALON_MASTER}/{@code
     * INDEPENDENT_MASTER} see their own bookings (scoped by {@code masterId}, resolved from the
     * JWT principal — never a request parameter), {@code SALON_OWNER} sees bookings across their
     * owned active salons, {@code CLIENT} sees their own bookings, and {@code SALON_ADMIN} is
     * forbidden — consistent with {@code getMyBookings} rejecting that role for the same reason
     * (they manage staff/services, not bookings).
     *
     * <p>Aggregation happens in Postgres ({@code SELECT DISTINCT} on a timezone-converted date
     * expression, at most ~366 rows back) — never by loading the caller's booking history into
     * heap and reducing with {@code .map(...).distinct()} in Java.
     */
    @Transactional(readOnly = true)
    public List<LocalDate> getMyBookedDays(UUID actorUserId, Authentication auth, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Both 'from' and 'to' are required");
        }
        if (from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'");
        }
        dateMath.assertToPlusOneDayRepresentable(to);
        dateMath.assertSpanWithinMax(from, to);

        OffsetDateTime fromTs = from.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay(TimeZones.KYIV).toOffsetDateTime();

        Role role = resolveActorRole(auth);

        List<java.sql.Date> bookedDates = switch (role) {
            case CLIENT -> bookingRepository.findBookedDatesByClientId(actorUserId, fromTs, toExclusive);
            case SALON_MASTER, INDEPENDENT_MASTER -> {
                Master master = masterRepository.findByUserId(actorUserId)
                        .orElseThrow(() -> new NotFoundException("Master profile not found"));
                yield bookingRepository.findBookedDatesByMasterId(master.getId(), fromTs, toExclusive);
            }
            case SALON_OWNER -> {
                List<UUID> salonIds = salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorUserId);
                yield salonIds.isEmpty()
                        ? List.of()
                        : bookingRepository.findBookedDatesBySalonIds(salonIds, fromTs, toExclusive);
            }
            // SALON_ADMIN intentionally excluded: they manage staff/services, not bookings —
            // same boundary getMyBookings enforces via listProviderBookings.
            case SALON_ADMIN -> throw new ForbiddenException("SALON_ADMIN cannot list bookings via this endpoint");
        };

        // Conversion lives HERE, not in the repository (CRITICAL fix, backend-qa
        // BookingMyBookedDaysIT, 2026-07-17). The three native queries above return the raw JDBC
        // java.sql.Date the driver produces for a `date` column — Spring Data's
        // QueryExecutionResultHandler has no java.sql.Date -> LocalDate converter for a native
        // scalar projection, so declaring List<LocalDate> on the repository method made every
        // request 500 with ConverterNotFoundException. java.sql.Date::toLocalDate reads the
        // value's stored year/month/day fields directly (no zone reinterpretation), so this is
        // lossless: the AT TIME ZONE 'Europe/Kyiv' expression in SQL already produced the correct
        // Kyiv calendar date before JDBC ever sees it — do not move that grouping into Java.
        return bookedDates.stream().map(java.sql.Date::toLocalDate).toList();
    }

    /**
     * Property names {@code GET /bookings/me}'s {@code sort} query parameter may reference
     * (Phase 26.3, narrowed by Phase 26.8). {@code priceAtBooking} was removed from this set —
     * its only caller anywhere in the product was the provider "Мої записи" sort sheet, which
     * mobile Phase 7.8 deleted once that screen became a timeline (a card's position derives
     * from {@code startsAt}, so no ordering of the result set can move it). {@code startsAt} is
     * the sole survivor and is a scalar column directly on {@code Booking} — no association
     * traversal.
     *
     * <p><b>This is a security boundary, not a nicety.</b> Both {@code findIdsByClientIdFiltered}
     * and the provider ID-page queries join through {@code b.master m JOIN m.user}, so any
     * unvalidated dot-path off those aliases is legal JPQL/Criteria — e.g.
     * {@code ?sort=master.user.passwordHash,asc} would order an authenticated caller's own
     * results by their employees' password-hash column, a credential side channel that never
     * appears in the response body. {@link Sort.Order#getProperty()} returns the FULL dotted
     * path as one string, so an exact-match {@link Set#contains} here rejects any multi-segment
     * path outright — it never inspects only the first segment. A one-property whitelist is
     * strictly less attack surface than the prior two-property one.
     */
    private static final Set<String> SORTABLE_BOOKING_PROPERTIES = Set.of("startsAt");

    /** Applied when the caller supplies no {@code sort} at all (Phase 26.3). */
    private static final Sort DEFAULT_BOOKING_SORT = Sort.by(Sort.Direction.DESC, "startsAt");

    /** Mandatory final tiebreaker appended to every sort — see {@link #normalizeBookingSort}. */
    private static final Sort ID_TIEBREAKER_SORT = Sort.by(Sort.Direction.ASC, "id");

    /**
     * Max {@link Sort.Order} entries accepted in {@code GET /bookings/me}'s {@code sort} query
     * parameter (Phase 26.3 audit, finding backend-perf F4). Kept at 3 by Phase 26.8 even though
     * {@link #SORTABLE_BOOKING_PROPERTIES} narrowed to a single property — this bound is a
     * request-cardinality/DoS guard, not a count that must track the whitelist size. A caller can
     * still repeat the sole whitelisted property across multiple {@code (property, direction)}
     * orders (e.g. {@code sort=startsAt,asc&sort=startsAt,desc&sort=startsAt,asc}); each distinct
     * sequence compiles to a textually distinct SQL {@code ORDER BY} (column names can't be bind
     * params), so an unbounded sort list still inflates plan-cache entries regardless of how many
     * distinct property names exist. Parity with the existing {@code @Size(max = 5)} bound on the
     * controller's {@code status} parameter.
     */
    private static final int MAX_SORT_ORDERS = 3;

    /**
     * Max de-duplicated {@code serviceId} values accepted by {@code GET /bookings/me} (Phase
     * 26.4). Unlike {@code status} (an enum, self-bounded at 5 by its own cardinality),
     * {@code serviceId} is an arbitrary {@code UUID} list a caller could otherwise repeat
     * thousands of times, inflating the {@code IN} list (Anti-Bug §B1) — this cap bounds that
     * worst-case single-request list <em>length</em>, a real DoS guard. It does <b>not</b> bound
     * plan-cache <em>shape</em> cardinality: every distinct list length between 1 and this cap
     * still compiles to a textually distinct {@code IN (?, ?, ...)} clause. That axis is instead
     * addressed by {@code hibernate.query.in_clause_parameter_padding} (enabled in
     * {@code application.yml}, Phase 26.4 finding backend-perf F1), which rounds each generated
     * {@code IN} list up to the next power of two so far fewer distinct SQL texts reach Postgres's
     * prepared-statement cache. 50 comfortably exceeds any real master's service catalogue (the
     * option universe the filter sheet renders — see the phase doc's "no facet endpoint" decision)
     * while still capping the query. Mirrored by the controller's {@code @Size(max = 50)} on the
     * repeated {@code serviceId} request parameter.
     */
    private static final int MAX_SERVICE_ID_FILTER = 50;

    /**
     * Single choke point for the {@code sort} query parameter on {@code GET /bookings/me}
     * (Phase 26.3), applied once before the CLIENT/provider role dispatch so neither path can be
     * reached with a raw, unvalidated {@link Sort}.
     *
     * <ol>
     *   <li><b>Default when unsorted.</b> {@code @PageableDefault(sort = "startsAt", DESC)} on
     *       the controller covers the HTTP path, but this method is also called directly by
     *       tests and (defensively) must not depend on that annotation — an unsorted
     *       {@code Pageable} yields DB-arbitrary order once the JPQL/Criteria layers stop
     *       hardcoding {@code ORDER BY b.startsAt DESC} themselves.</li>
     *   <li><b>Whitelist.</b> Every {@link Sort.Order#getProperty()} must exact-match
     *       {@link #SORTABLE_BOOKING_PROPERTIES} — {@code startsAt} only as of Phase 26.8, which
     *       retired {@code priceAtBooking} once its only caller (the provider sort sheet) was
     *       deleted by mobile Phase 7.8; anything else — including a dot-path like
     *       {@code master.user.passwordHash} — throws a 400 {@link BusinessException} before the
     *       {@code Sort} ever reaches a query.</li>
     *   <li><b>Count bound.</b> More than {@link #MAX_SORT_ORDERS} orders throws a 400
     *       {@link BusinessException} (Phase 26.3 audit F4) — parity with the controller's
     *       {@code @Size(max = 5)} bound on {@code status}.</li>
     *   <li><b>Mandatory {@code id} tiebreaker.</b> Appended last, always. {@code startsAt} ties
     *       are a real case, not a hypothetical one — nothing in the schema prevents two
     *       terminal-status bookings (e.g. {@code COMPLETED}/{@code CANCELLED}, which fall
     *       outside the {@code no_overlapping_bookings} EXCLUDE constraint's {@code CONFIRMED}-
     *       only predicate) from sharing an identical {@code startsAt}. Without a unique trailing
     *       column, {@code OFFSET} pagination over tied rows can duplicate and skip rows across
     *       pages.</li>
     * </ol>
     *
     * <p><b>Preserves {@code Pageable.unpaged()}.</b> {@link Pageable#getPageNumber()} and
     * {@link Pageable#getPageSize()} throw {@link UnsupportedOperationException} on an
     * {@code Unpaged} instance by design — several {@code BookingServiceTest} cases call this
     * service directly with {@code Pageable.unpaged()} because they exercise status-filtering
     * logic, not pagination. Rebuilding via {@link PageRequest#of} unconditionally would break
     * that legitimate caller. {@link Pageable#isPaged()} branches to
     * {@link Pageable#unpaged(Sort)} instead, carrying the normalized sort without requiring page
     * number/size semantics that don't apply.
     */
    private Pageable normalizeBookingSort(Pageable pageable) {
        Sort requestedSort = pageable.getSort();
        Sort effectiveSort = requestedSort.isUnsorted() ? DEFAULT_BOOKING_SORT : requestedSort;

        if (effectiveSort.stream().count() > MAX_SORT_ORDERS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Too many sort properties");
        }

        for (Sort.Order order : effectiveSort) {
            if (!SORTABLE_BOOKING_PROPERTIES.contains(order.getProperty())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "Unsupported sort property: " + order.getProperty());
            }
        }

        Sort finalSort = effectiveSort.and(ID_TIEBREAKER_SORT);

        if (!pageable.isPaged()) {
            return Pageable.unpaged(finalSort);
        }

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), finalSort);
    }

    /**
     * CLIENT path (Phase 26.7.1 — two-query ID-page + verbatim-projection hydrate, mirroring
     * {@link #listProviderBookings}'s established pattern). {@code findIdsByClientIdFiltered}
     * runs the sargable, sentinel-free {@link org.springframework.data.jpa.domain.Specification}
     * ID page (ownership + status/date-range/serviceId filters, sort translated from
     * {@code pageable.getSort()}); {@code hydrateClientBookingDetails} then re-emits the
     * PII-sensitive {@code ClientBookingDetailProjection} — CASE-precedence expressions untouched
     * — for exactly that bounded id set via {@code WHERE b.id IN :ids}.
     *
     * <p><b>Order is re-imposed in Java</b> — the {@code IN} hydrate does not preserve the ID
     * page's order — by mapping the hydrated rows into a {@code Map<UUID, …>} and re-walking
     * {@code idPage.getContent()}, the identical pattern {@link #listProviderBookings} already
     * uses for {@code findAllByIdsWithGraph}. An empty ID page short-circuits before the hydrate
     * ever runs, so this method never emits {@code IN ()} (an invalid, dialect-breaking clause).
     */
    private Page<BookingDetailResponse> listClientBookings(
            UUID clientId, Set<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Set<UUID> serviceIds, Pageable pageable) {
        Page<UUID> idPage = bookingRepository.findIdsByClientIdFiltered(
                clientId, statuses, from, toExclusive, serviceIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
        }

        List<ClientBookingDetailProjection> hydrated =
                bookingRepository.hydrateClientBookingDetails(idPage.getContent());
        DiscoveryLabels labels = resolveProjectionLabels(hydrated);

        // Restore the ID page's ordering — IN :ids does not guarantee row order from the
        // database (mirrors listProviderBookings' Map<UUID, Booking> re-order below).
        Map<UUID, ClientBookingDetailProjection> byId = hydrated.stream()
                .collect(Collectors.toMap(ClientBookingDetailProjection::id, Function.identity()));
        List<BookingDetailResponse> ordered = idPage.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(p -> toDetailResponse(p, labels))
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    /**
     * Provider path — ID-page + graph hydrate (Fix H1), then one batched review-existence
     * query and the two-query label resolution for the whole page.
     */
    private Page<BookingDetailResponse> listProviderBookings(
            Role role, UUID actorUserId, Set<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Set<UUID> serviceIds, Pageable pageable) {
        // Two-query pattern (Fix H1 — HHH90003004): first fetch a page of IDs using
        // plain JPQL with no JOIN FETCH (so the DB applies LIMIT/OFFSET correctly), then
        // batch-hydrate only those IDs with the full association graph in a second query.
        Page<UUID> idPage = switch (role) {
            case SALON_MASTER, INDEPENDENT_MASTER -> {
                Master master = masterRepository.findByUserId(actorUserId)
                        .orElseThrow(() -> new NotFoundException("Master profile not found"));
                yield bookingRepository.findIdsByMasterIdFiltered(
                        master.getId(), statuses, from, toExclusive, serviceIds, pageable);
            }
            case SALON_OWNER -> {
                // Fix HIGH-1: salonId is on Salon.owner_id, NOT on User.salonId.
                // userRepository.findSalonIdById always returned empty for SALON_OWNER,
                // causing a guaranteed BusinessException (500). Resolved via SalonRepository
                // which joins on the owner FK. An owner with no active salons gets an empty page
                // rather than a 500 — consistent with the no-results case on other roles.
                List<UUID> salonIds = salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorUserId);
                if (salonIds.isEmpty()) {
                    yield Page.empty(pageable);
                }
                yield bookingRepository.findIdsBySalonIdsFiltered(
                        salonIds, statuses, from, toExclusive, serviceIds, pageable);
            }
            // SALON_ADMIN intentionally excluded: they manage staff/services, not bookings.
            // If this restriction is ever relaxed, add a SALON_ADMIN branch scoped to their salon.
            case SALON_ADMIN -> throw new ForbiddenException("SALON_ADMIN cannot list bookings via this endpoint");
            default -> throw new ForbiddenException("Access denied");
        };

        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
        }

        List<Booking> hydrated = bookingRepository.findAllByIdsWithGraph(idPage.getContent());
        // Batched review-existence for the whole page (one query), so canReview is correct
        // without a per-row existsByBookingId (§E: no N+1).
        Set<UUID> reviewed = new HashSet<>(reviewRepository.findReviewedBookingIds(idPage.getContent()));
        DiscoveryLabels labels = resolveBookingLabels(hydrated);

        // Restore the original ordering dictated by the pageable sort — the IN clause
        // does not guarantee ordering from the database.
        Map<UUID, Booking> byId = hydrated.stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        List<BookingDetailResponse> ordered = idPage.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(b -> {
                    UUID cityId = discoveryCityId(b);
                    UUID districtId = discoveryDistrictId(b);
                    boolean canReview = canReview(b.getStatus(), reviewed.contains(b.getId()), b.getClient() != null);
                    return BookingDetailResponse.from(
                            b, canReview, labels.cityLabel(cityId), labels.districtLabel(districtId));
                })
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    /**
     * Provider-initiated cancellation (Phase 24.2 — repurposes what was {@code /decline}).
     *
     * <p>Under the track 24.x auto-confirm state machine a booking is born {@code CONFIRMED} —
     * there is no provider approval step to decline, so this now models the provider (salon
     * owner, assigned salon admin, or independent master) backing out of an already-confirmed
     * booking. Distinguished from {@link #cancelBooking} (client-initiated) by the resulting
     * {@code DECLINED} status, so the client's booking list can render "салон скасував"
     * separately from "ви скасували".
     */
    @Transactional
    public BookingResponse declineBooking(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        // Fix M4: require a reason, consistent with notCompleteBooking
        if (req.cancellationReason() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Cancellation reason required for declining a booking");
        }
        Booking booking = loadBookingOrThrow(bookingId);
        authz.enforceCanCancelBooking(actorUserId, booking);
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.DECLINED);
        booking.setStatus(BookingStatus.DECLINED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(BookingComments.normalize(req.comment()));
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse completeBooking(UUID actorUserId, UUID bookingId) {
        Booking booking = loadBookingOrThrow(bookingId);
        // Phase 18.4 / 24.2: completion, decline, and not-complete all share the same
        // provider-authority shape (admits SALON_ADMIN) — see AuthorizationService.enforceCanCompleteBooking
        // / enforceCanCancelBooking.
        authz.enforceCanCompleteBooking(actorUserId, booking);
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
        booking.setStatus(BookingStatus.COMPLETED);
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        // Phase 18.3: enqueue the client review prompt in the same transaction. At-most-once by
        // construction — a second complete throws (assertTransition). The COMPLETED state is the
        // gate ReviewService.createReview enforces before a review may be left.
        // Guest (LINK) bookings have a null client (V89 chk_bookings_guest_fields) and no account
        // to leave a review with — skip the review prompt so the drain path never NPEs on getClient().
        if (saved.getClient() != null) {
            outboxService.enqueueReviewRequested(saved.getId());
        }
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        evictRevenueDashboardAfterCommit(actorUserId);
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse notCompleteBooking(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        Booking booking = loadBookingOrThrow(bookingId);
        // Phase 24.2: aligned to the same provider-authority shape as completeBooking/
        // declineBooking (admits SALON_ADMIN) — leaving admin able to complete/decline but not
        // mark a no-show would be an incoherent permission set (decision D2).
        authz.enforceCanCancelBooking(actorUserId, booking);
        if (req.cancellationReason() == null) {
            throw new BusinessException("Cancellation reason required");
        }
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.NOT_COMPLETED);
        booking.setStatus(BookingStatus.NOT_COMPLETED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(BookingComments.normalize(req.comment()));
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        evictRevenueDashboardAfterCommit(actorUserId);
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse cancelBooking(UUID clientUserId, UUID bookingId, CancelBookingRequest req) {
        // Existence + ownership collapse to a single uniform 403 (Finding 8 — existence oracle):
        // a missing id, a guest (LINK, null-client) booking, and an existing-but-foreign booking
        // must all be indistinguishable to the caller. A prior 404-then-403 split let an
        // authenticated CLIENT probe whether an arbitrary booking id exists at all.
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .filter(b -> b.getClient() != null && b.getClient().getId().equals(clientUserId))
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        BookingStatus current = booking.getStatus();
        if (current != BookingStatus.CONFIRMED) {
            throw new BusinessException("Cannot cancel a booking in status %s".formatted(current));
        }
        // Track 24.x read-only-after-elapse: once the appointment window has fully passed the
        // booking is read-only for the client and awaits provider resolution (decline / complete /
        // mark-no-show) — the client can no longer cancel it. Checked AFTER the status guard so a
        // non-CONFIRMED booking still reports the more specific status conflict.
        assertNotElapsedForClient(booking);
        booking.setStatus(BookingStatus.CANCELLED);
        // cancellationReason is guaranteed non-null by @NotNull on CancelBookingRequest
        booking.setCancellationReason(req.cancellationReason());
        // Fix D2 (track 25.x): req.comment() was validated at the API but never persisted —
        // the client's cancellation note was silently discarded. Stored separately from
        // clientComment (the booking-CREATION note) so the provider's "client cancelled" email
        // (see EmailNotificationService.sendClientCancelledEmail, Fix D3) never confuses the two.
        booking.setClientCancellationNote(BookingComments.normalize(req.comment()));
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        return BookingResponse.from(saved);
    }

    /**
     * Moves a client's own {@code CONFIRMED} booking to a new future time.
     *
     * <p>Reuses the create-path validation: {@link #validateStartsAt(OffsetDateTime)}
     * (≥15 min ahead, ≤180 days), the same working-hours / effective-day check via
     * {@link #assertStartsOnAvailableSlot} (the master must actually work the requested slot),
     * the per-master advisory lock, and the overlap check — here excluding the booking's own row
     * ({@link BookingRepository#existsOverlapExcluding}). Also reuses the create-path
     * client-conflict guard ({@link #assertNoClientConflictExcluding}): the new window must not
     * overlap ANY other {@code CONFIRMED} booking this client holds, excluding this booking's own
     * row — see {@link ClientBookingConflictException}.
     * The booking stays {@code CONFIRMED} at the new time (no provider re-approval step — see the
     * track 24.x locked state machine). The provider is still notified of the new time via a
     * {@code BOOKING_RESCHEDULED} outbox event. {@code priceAtBooking} and
     * {@code durationMinutesAtBooking} are frozen and are NOT recomputed.
     *
     * <p>With no revert-to-approval-queue backstop, the slot/overlap/lead-time/schedule checks
     * below are the ONLY thing preventing a double-booked slot — they MUST run unweakened on
     * every reschedule.
     *
     * @param actorUserId the authenticated CLIENT (from the security principal, never the body)
     * @param bookingId   the booking to move
     * @param req         the new start time
     * @return the updated booking
     * @throws ForbiddenException              if the actor is not the owning client (403)
     * @throws BusinessException               if the source state is not CONFIRMED (409) or
     *                                          the new slot conflicts with the master's calendar
     *                                          (409); {@link #validateStartsAt} rejects bad times (400)
     * @throws ClientBookingConflictException  if the new window overlaps another booking this
     *                                          client already holds (409, {@code CLIENT_BOOKING_CONFLICT})
     */
    @Transactional
    public BookingDetailResponse rescheduleBooking(UUID actorUserId, UUID bookingId, RescheduleBookingRequest req) {
        // Existence + ownership collapse to a single uniform 403 (Finding 8 — existence oracle),
        // mirroring cancelBooking. A guest (LINK) booking has no client account, so getClient()
        // is null and the actor can never match — it falls into the same uniform 403 as a
        // missing id or a foreign booking, never a distinguishable 404.
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .filter(b -> b.getClient() != null && b.getClient().getId().equals(actorUserId))
                .orElseThrow(() -> new ForbiddenException("Access denied"));

        BookingStatus current = booking.getStatus();
        if (current != BookingStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot reschedule a booking in status %s".formatted(current));
        }
        // Track 24.x read-only-after-elapse: an already-elapsed booking is read-only for the
        // client and awaits provider resolution — the client can no longer move it to a new time.
        // Checked AFTER the status guard so a non-CONFIRMED booking still reports the more
        // specific status conflict.
        assertNotElapsedForClient(booking);

        OffsetDateTime newStartsAt = req.newStartsAt();
        validateStartsAt(newStartsAt);

        UUID masterId = booking.getMaster().getId();
        UUID masterServiceId = booking.getMasterService().getId();

        // Same working-hours / effective-day validation create relies on: the requested start
        // must fall on a slot the master actually works (Phase 15.4 effective-day resolver +
        // service/master liveness + duration bounds, via SlotCalculationService.getAvailableSlots).
        // Run BEFORE the advisory lock to keep the lock window tight (backend-perf). An
        // off-schedule time yields no matching slot → 409 "Slot not available", the same status
        // the create/overlap path returns for an unbookable time. The authoritative overlap check
        // (excluding this booking's own row) still runs under the lock below.
        assertStartsOnAvailableSlot(masterId, masterServiceId, newStartsAt);

        // Duration + buffer are frozen at the original booking; mirror the create-path
        // end-time formula (duration + buffer) rather than recomputing from master_services.
        OffsetDateTime newEndsAt = newStartsAt.plusMinutes(
                (long) booking.getDurationMinutesAtBooking() + booking.getBufferMinutesAtBooking());

        LocalDate oldDate = booking.getStartsAt().toLocalDate();

        // Same critical section as doCreateBooking, in the same client-then-master order
        // (deadlock freedom — see BookingRepository.acquireClientAdvisoryLockWithTimeout
        // javadoc). actorUserId IS the owning client here — ownership was already verified
        // above. acquireClientLock's fused query also sets the transaction-scoped lock_timeout
        // for the whole transaction, bounding the wait on both this lock and the master lock
        // below.
        acquireClientLock(actorUserId);

        // Client-conflict check (excluding this booking's own row) runs BEFORE the master-busy
        // check — and before the master lock is even acquired — same precedence and rationale
        // as create; see doCreateBooking.
        assertNoClientConflictExcluding(actorUserId, newStartsAt, newEndsAt, bookingId);

        Integer lockResult = bookingRepository.acquireAdvisoryLock(masterId);
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }

        if (bookingRepository.existsOverlapExcluding(masterId, newStartsAt, newEndsAt, bookingId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        booking.reschedule(newStartsAt, newEndsAt);
        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        outboxService.enqueueBookingRescheduled(saved.getId());
        // Evict the freed old-day slots and the now-occupied new-day slots, plus the
        // provider calendar — after commit, so a parallel reader cannot repopulate stale data.
        registerSlotEviction(masterId, salonIdOf(saved), oldDate, saved.getMasterService().getId());
        if (!oldDate.equals(newStartsAt.toLocalDate())) {
            registerSlotEviction(masterId, salonIdOf(saved), newStartsAt.toLocalDate(), saved.getMasterService().getId());
        }
        evictMasterCalendarAfterCommit(masterId);
        // A rescheduled booking is always CONFIRMED, so canReview is false by the
        // COMPLETED predicate — no review-existence query needed on this path. saved.getClient()
        // is guaranteed non-null here (the ownership filter above only matches account-bound
        // bookings), passed through for signature consistency with the other call sites.
        return enrichSingle(saved, canReview(saved.getStatus(), false, saved.getClient() != null));
    }

    private BookingResponse doCreateBooking(UUID clientId, String idempotencyKey, CreateBookingRequest request) {
        // Master kind is irrelevant to bookability — SALON_MASTER, INDEPENDENT_MASTER,
        // and SALON_OWNER masters are all bookable when active with working hours + a
        // matching master_services row.
        Master master = masterRepository.findByIdWithUserAndSalon(request.masterId())
                .filter(Master::isActive)
                .orElseThrow(() -> new NotFoundException("Master not found or inactive"));

        MasterServiceAssignment msa = masterServiceRepository
                .findByMasterIdAndIdWithGraph(request.masterId(), request.masterServiceId())
                .orElseThrow(() -> new NotFoundException("Master service not found"));

        OffsetDateTime startsAt = request.startsAt().toOffsetDateTime();
        validateStartsAt(startsAt);

        BigDecimal effectivePrice = msa.getPriceOverride() != null
                ? msa.getPriceOverride()
                : msa.getServiceDefinition().getBasePrice();
        int effectiveDuration = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes()
                : msa.getServiceDefinition().getBaseDurationMinutes();
        int bufferMinutes = msa.getServiceDefinition().getBufferMinutesAfter();

        OffsetDateTime endsAt = startsAt.plusMinutes((long) effectiveDuration + bufferMinutes);

        // Fix H3: load and validate the client BEFORE acquiring the advisory lock to
        // minimise the lock hold window — no DB round-trip inside the critical section.
        User client = userRepository.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Client not found"));
        // Defence in depth: the controller @PreAuthorize already restricts to CLIENT,
        // but an explicit check here prevents privilege escalation if the annotation is relaxed.
        if (client.getRole() != Role.CLIENT) {
            throw new ForbiddenException("Only clients can create bookings");
        }

        // Client lock (salt 1) is always acquired BEFORE the master lock (salt 0) — the
        // deterministic global order that keeps the two lock classes deadlock-free (see
        // BookingRepository.acquireClientAdvisoryLockWithTimeout javadoc). acquireClientLock's
        // fused query also sets the transaction-scoped lock_timeout for the whole transaction
        // (backend-security: bounds every subsequent lock wait, including the master lock
        // below, so a flood of concurrent requests from one account fails fast with a clean 409
        // instead of parking a Hikari connection for the full pool connection-timeout).
        acquireClientLock(clientId);

        // Client-conflict check runs BEFORE the master-busy check — and, since the reorder,
        // before the master lock is even acquired: when the requested window conflicts with
        // the client's own calendar, the caller gets the more specific, actionable
        // CLIENT_BOOKING_CONFLICT rather than the generic "Slot not available" — locked
        // product decision — AND the shared per-master lock (contended by every other client
        // racing for the same popular master) is never touched for a conflict that is entirely
        // about this client's own calendar (backend-perf).
        assertNoClientConflict(clientId, startsAt, endsAt);

        Integer lockResult = bookingRepository.acquireAdvisoryLock(master.getId());
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }

        if (bookingRepository.existsOverlap(master.getId(), startsAt, endsAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        Booking booking = Booking.builder()
                .client(client)
                .master(master)
                .masterService(msa)
                // salon is set from master.getSalon() which is null for INDEPENDENT_MASTER.
                // This preserves the V18 nullable salon_id column intent without an explicit check.
                .salon(master.getSalon())
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(effectivePrice)
                // Freeze the RANGE ceiling beside the floor (V119). Null = single price. Computed
                // HERE, at creation, so a later service edit can never rewrite an agreed band.
                .priceMaxAtBooking(BookingPriceRange.resolveCeiling(msa))
                .durationMinutesAtBooking(effectiveDuration)
                .bufferMinutesAtBooking(bufferMinutes)
                .idempotencyKey(idempotencyKey)
                .clientComment(BookingComments.normalize(request.clientComment()))
                .build();

        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        // Two outbox rows, two distinct recipients (D3): NEW_BOOKING → the master (see
        // NotificationService.notifyNewBooking); STATUS_CHANGED → the client, whose CONFIRMED
        // branch already dispatches «Бронювання підтверджено» (see notifyBookingStatusChanged).
        // No new event type — the booking is auto-confirmed at creation (track 24.x), so this
        // is simply the client-facing half of the same create event, not a genuine transition.
        outboxService.enqueueNewBooking(saved.getId());
        outboxService.enqueueStatusChanged(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
        return BookingResponse.from(saved);
    }

    /**
     * Reuses the create-path effective-day / working-hours oracle: a start is bookable only
     * if it matches a slot returned by {@link SlotCalculationService#getAvailableSlots} for the
     * master + service on that date. That resolver applies the Phase 15.4 effective-day model
     * (weekly templates, per-date overrides, day-offs), master/service liveness, and duration
     * bounds — so a request to a time the master does not work resolves to no matching slot.
     *
     * <p>Compared by {@link OffsetDateTime#isEqual} on the slot start instant (the slot list is
     * generated on {@code SLOT_STEP} boundaries in Kyiv time; {@code isEqual} ignores the
     * offset/zone representation). A non-matching start throws {@code 409 "Slot not available"} —
     * the same status the create/overlap path returns for an unbookable time.
     */
    private void assertStartsOnAvailableSlot(UUID masterId, UUID masterServiceId, OffsetDateTime startsAt) {
        boolean onSchedule = slotCalculationService.getAvailableSlots(masterId, startsAt.toLocalDate(), masterServiceId)
                .stream()
                .anyMatch(slot -> slot.startsAt().toOffsetDateTime().isEqual(startsAt));
        if (!onSchedule) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }

    private void validateStartsAt(OffsetDateTime startsAt) {
        // Shared with GuestBookingService (DRY) so the authenticated and guest paths
        // enforce the identical lead-time floor + max-window cap.
        BookingStartsAtValidator.validate(startsAt, clock);
    }

    /**
     * Acquires the per-client advisory lock (salt 1) — serializing concurrent create/reschedule
     * requests from the same client. This is always the FIRST lock taken in the transaction;
     * the per-master advisory lock (salt 0) is acquired afterwards, only once the
     * client-conflict check has passed — see
     * {@link BookingRepository#acquireClientAdvisoryLockWithTimeout(UUID)} for the
     * deadlock-freedom argument that depends on this fixed client-then-master order.
     *
     * <p>{@link BookingRepository#acquireClientAdvisoryLockWithTimeout(UUID)} fuses
     * {@code set_config('lock_timeout', '3s', true)} (transaction-scoped, equivalent to
     * {@code SET LOCAL}) into the SAME statement as the lock acquisition — one round trip
     * instead of two. Because the timeout is transaction-scoped, it remains in force for the
     * rest of the transaction and therefore still bounds the master lock acquired later via the
     * plain {@link BookingRepository#acquireAdvisoryLock(UUID)}, with no need to re-apply it.
     */
    private void acquireClientLock(UUID clientId) {
        Integer lockResult = bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId);
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }
    }

    /**
     * Throws {@link ClientBookingConflictException} if the client already holds a
     * {@code CONFIRMED} booking (with ANY master/salon) overlapping
     * {@code [startsAt, endsAt)}. Caller must hold {@link #acquireClientLock(UUID)} first so
     * two concurrent requests from the same client cannot both pass this check.
     */
    private void assertNoClientConflict(UUID clientId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        bookingRepository.findFirstConflictingClientBookingId(clientId, startsAt, endsAt)
                .ifPresent(conflictId -> {
                    throw clientConflictException(conflictId);
                });
    }

    /**
     * Same as {@link #assertNoClientConflict} but excludes the booking being rescheduled from
     * its own conflict scan, so a booking never collides with itself when only its time moves.
     */
    private void assertNoClientConflictExcluding(
            UUID clientId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID excludeBookingId) {
        bookingRepository.findFirstConflictingClientBookingIdExcluding(clientId, startsAt, endsAt, excludeBookingId)
                .ifPresent(conflictId -> {
                    throw clientConflictException(conflictId);
                });
    }

    /**
     * Re-hydrated with the full association graph (client/master/masterService/
     * serviceDefinition) so the exception can read service name + master display name
     * without triggering a lazy load. The row was just found by the query above in this
     * same transaction, so it is guaranteed to still exist.
     *
     * <p><b>Lock-window note (backend-perf audit):</b> this JOIN FETCH runs while the CLIENT
     * advisory lock is still held — {@code pg_advisory_xact_lock} releases only at
     * commit/rollback, so it cannot be dropped mid-transaction before throwing. The
     * client-then-master reorder already shrank this window versus the prior master-then-client
     * order: the master lock has NOT been acquired yet at this point (it is only taken after
     * {@link #assertNoClientConflict}/{@link #assertNoClientConflictExcluding} pass), so this
     * extra read only extends the hold on the caller's OWN client lock, never the shared
     * per-master lock other clients may be waiting on. The fetch itself only runs on the rare
     * conflict path (never on the common success path), so its cost is bounded to that case.
     */
    private ClientBookingConflictException clientConflictException(UUID conflictingBookingId) {
        Booking conflict = bookingRepository.findByIdWithFullGraph(conflictingBookingId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Conflicting booking could not be loaded"));
        return new ClientBookingConflictException(conflict);
    }

    /** Discovery/catalogue salon id for a booking: the booked salon, or null for an independent master. */
    private static UUID salonIdOf(Booking booking) {
        Salon salon = booking.getSalon();
        return salon != null ? salon.getId() : null;
    }

    private void registerSlotEviction(UUID masterId, UUID salonId, LocalDate date, UUID masterServiceId) {
        Runnable task = () -> {
            slotCalculationService.evictAvailableSlots(masterId, date, masterServiceId);
            // The booking changed occupancy → the master's free-slot bookability verdict may
            // flip; evict by master prefix (window keys can't be evicted per-date).
            slotCalculationService.evictBookableFutureSlotsByMaster(masterId);
            // A flipped bookability verdict can add/remove a service from the salon catalogue
            // (perf/security #2). Null salon (independent master) owns no catalogue entry.
            if (salonId != null) {
                salonCatalogCacheEvictor.evict(salonId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // No active transaction (e.g. unit test context) — evict directly
            task.run();
        }
    }

    private Booking loadBookingOrThrow(UUID bookingId) {
        // Fix M6: use full-graph fetch so mutation responses do not trigger
        // additional SELECTs for masterService and serviceDefinition
        return bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
    }

    /**
     * Guards the client-initiated write paths (cancel / reschedule) against an already-elapsed
     * booking: once {@code endsAt} is before "now" the appointment is read-only for the client and
     * only the provider (decline / complete / mark-no-show) may resolve it (track 24.x). Compared
     * on the absolute instant via the injected {@link Clock} (the same time source
     * {@link #validateStartsAt} uses), so tests can pin an elapsed booking deterministically.
     *
     * <p>Deliberately NOT applied to the provider paths ({@link #declineBooking},
     * {@link #completeBooking}, {@link #notCompleteBooking}) — resolving an elapsed booking is
     * exactly their job.
     */
    private void assertNotElapsedForClient(Booking booking) {
        if (booking.getEndsAt().toInstant().isBefore(clock.instant())) {
            throw new BookingElapsedException();
        }
    }

    private void assertTransition(Booking booking, BookingStatus expected, BookingStatus target) {
        if (booking.getStatus() != expected) {
            throw new BusinessException(
                    "Cannot transition from %s to %s".formatted(booking.getStatus(), target));
        }
    }

    /**
     * Evicts only the cache entries that belong to the given master from the
     * {@code master-calendar} cache, running after the current transaction commits.
     *
     * <p>The {@code master-calendar} cache key is a {@link org.springframework.cache.interceptor.SimpleKey}
     * whose first element is the {@code masterId} UUID (see {@code MasterService.getMasterCalendar}).
     * Because {@code SimpleKey.params} is {@code private final} with no public getter in
     * Spring 6.x, the filter uses {@code SimpleKey.toString()} — which renders as
     * {@code "SimpleKey [masterId, from, to, pageNum, pageSize]"} via
     * {@link java.util.Arrays#deepToString} — and checks whether the first array element
     * (the UUID string) is present. This avoids blanket {@code cache.clear()} which
     * would evict ALL masters on every single booking status change (thundering herd).
     *
     * <p>Falls back to {@code cache.clear()} when the underlying cache is not a Caffeine
     * instance (e.g., during tests that use a simple ConcurrentMapCache).
     */
    private void evictMasterCalendarAfterCommit(UUID masterId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvictMasterCalendarEntries(masterId);
                }
            });
        } else {
            doEvictMasterCalendarEntries(masterId);
        }
    }

    private void doEvictMasterCalendarEntries(UUID masterId) {
        Cache cache = cacheManager.getCache("master-calendar");
        if (cache == null) {
            return;
        }
        Object nativeCache = cache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            // SimpleKey.toString() renders as "SimpleKey [elem0, elem1, ...]" via Arrays.deepToString.
            // The first element is the masterId UUID string — detect it by substring match on the
            // toString output, since SimpleKey.params is private with no public getter in Spring 6.x.
            String masterIdPrefix = "[" + masterId.toString() + ",";
            caffeineCache.asMap().keySet().removeIf(k ->
                    k instanceof org.springframework.cache.interceptor.SimpleKey
                            && k.toString().contains(masterIdPrefix));
        } else {
            // Fallback for non-Caffeine caches (e.g., ConcurrentMapCache in tests).
            cache.clear();
        }
    }

    /**
     * Evicts {@code revenue-dashboard} entries for the given actor after commit.
     *
     * <p>Uses per-actor prefix eviction when the underlying cache is Caffeine, avoiding
     * a blanket {@code cache.clear()} that would evict all actors' dashboard entries on
     * every booking status transition (Anti-Bug §F rule 6 / PERF-MEDIUM-5).</p>
     *
     * <p>Falls back to {@code cache.clear()} for non-Caffeine caches (e.g. tests).</p>
     */
    private void evictRevenueDashboardAfterCommit(UUID actorId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvictRevenueDashboard(actorId);
                }
            });
        } else {
            doEvictRevenueDashboard(actorId);
        }
    }

    private void doEvictRevenueDashboard(UUID actorId) {
        Cache springCache = cacheManager.getCache("revenue-dashboard");
        if (springCache == null) {
            return;
        }
        Object nativeCache = springCache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            String actorPrefix = "[" + actorId + ",";
            caffeineCache.asMap().keySet().removeIf(k ->
                    k instanceof org.springframework.cache.interceptor.SimpleKey
                            && k.toString().contains(actorPrefix));
        } else {
            // Fallback for non-Caffeine caches (e.g., ConcurrentMapCache in tests).
            springCache.clear();
        }
    }
}
