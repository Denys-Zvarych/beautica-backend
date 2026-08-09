package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.domain.MasterBookability;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingPriceRange;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.dto.UnclosedCountResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingPartition;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.event.BookingCompletedEvent;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingSpecifications;
import com.beautica.booking.repository.ClientBookingDetailProjection;
import com.beautica.common.PageResponse;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBookingConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthenticationUtils;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    private final ClientReviewRepository clientReviewRepository;
    private final DiscoveryLocationResolver discoveryLocationResolver;
    private final Clock clock;
    private final com.beautica.common.cache.MasterCachePrefixEvictor cachePrefixEvictor;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final ScheduleDateMath dateMath;
    private final AppointmentTransitionService appointmentTransitionService;
    // Phase 30.6 — needed ONLY for cancelAppointmentItem's visit-ownership + path-consistency
    // guards. AppointmentTransitionService already depends on THIS class (via
    // appointmentTransitionService above? no — the other direction: this class depends on
    // AppointmentTransitionService for the header-lock seam), so cancelAppointmentItem could not
    // live in AppointmentTransitionService without creating a circular bean graph
    // (BookingService → AppointmentTransitionService → BookingService). See that method's own
    // Javadoc (phase 30.6 D1) — do not "move this to where it looks like it belongs".
    private final AppointmentRepository appointmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates a booking (or replays an idempotent one) and returns the <b>enriched</b> detail view.
     *
     * <p><b>Why the enriched shape.</b> This endpoint previously returned the lean
     * {@link BookingResponse}, which carries only ids — no master name, avatar, salon name or
     * address. Every client therefore had to follow a successful create with a mandatory
     * {@code GET /bookings/{id}} purely to render the confirmation screen. Returning
     * {@link BookingDetailResponse} here removes that second round trip.
     *
     * <p><b>The change is strictly additive on the wire.</b> {@link BookingDetailResponse}'s first
     * twelve components are identical to {@link BookingResponse}'s in name, type, order and
     * semantics, so the emitted JSON is a superset: every field an existing client reads is still
     * present and unchanged, and a client that ignores the new fields behaves exactly as before.
     * Nothing was removed or renamed. The mobile app's redundant follow-up GET keeps working and
     * can be dropped separately, on its own schedule.
     *
     * <p><b>Cost.</b> Enrichment needs the full graph plus locality-label resolution, which the
     * lean path did not pay for. That is a net saving overall: the follow-up GET it replaces did
     * exactly this work <em>plus</em> a second HTTP request, authentication and transaction.
     */
    @Transactional
    public BookingDetailResponse createBooking(UUID clientId, String idempotencyKey, CreateBookingRequest request) {
        UUID bookingId;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Fix M5: use the partial-index-aligned query to avoid full table scan
            bookingId = bookingRepository.findActiveByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                    .map(Booking::getId)
                    .orElseGet(() -> doCreateBooking(clientId, idempotencyKey, request).id());
        } else {
            bookingId = doCreateBooking(clientId, idempotencyKey, request).id();
        }
        return enrichCreated(bookingId);
    }

    /**
     * Re-reads the just-created (or replayed) booking through the full graph and enriches it.
     *
     * <p>{@code canReview} is hardcoded {@code false} rather than probed, and that is sound by
     * construction: a booking is born {@code CONFIRMED} with a FUTURE {@code startsAt} (the
     * lead-time floor {@link BookingStartsAtValidator} enforces on create), so its {@code endsAt}
     * is future too — {@link BookingClosureRule#isReviewEligible} is {@code false} for every
     * disjunct ({@code status != COMPLETED} and {@code isAwaitingClosure} requires an ELAPSED
     * {@code endsAt}) — and the idempotent-replay query filters to {@code CONFIRMED} only.
     * Computing it for real would add a guaranteed-false {@code reviewRepository.existsByBookingId}
     * probe to every create. If a booking ever becomes creatable already-elapsed or in a terminal
     * state, this shortcut must go. {@code providerCanReviewClient} is hardcoded {@code false} for
     * the exact same reason — it also requires {@code COMPLETED} (see its own predicate below).
     */
    private BookingDetailResponse enrichCreated(UUID bookingId) {
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        return enrichSingle(booking, false, false, resolveNow());
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
        // now is resolved ONCE here and threaded into both canReview below and enrichSingle's
        // awaitingClosure computation — never two independent clock.instant() reads for one
        // response (Phase 29.2's single-instant-per-request discipline, now load-bearing for
        // canReview too since it depends on endsAt-vs-now, not just status).
        OffsetDateTime now = resolveNow();
        // reviewRepository.existsByBookingId is the one DB-bound input to canReview(...); short-
        // circuit on the in-memory checks first (mirrors computeProviderCanReviewClient below) so
        // the query only fires when it can actually flip the result — a future-dated CONFIRMED or
        // guest booking (the common case for a detail fetch) never reaches it.
        boolean hasClient = booking.getClient() != null;
        boolean eligibleByStatusAndTime =
                BookingClosureRule.isReviewEligible(booking.getStatus(), booking.getEndsAt(), now);
        boolean canReview = hasClient
                && eligibleByStatusAndTime
                && canReview(booking.getStatus(), booking.getEndsAt(), now,
                        reviewRepository.existsByBookingId(bookingId), hasClient);
        boolean providerCanReviewClient = computeProviderCanReviewClient(actorUserId, booking, now);
        return enrichSingle(booking, canReview, providerCanReviewClient, now);
    }

    /**
     * Viewer-aware predicate backing {@code BookingDetailResponse#providerCanReviewClient}
     * (extends track 27.x / Phase 27.5) — computed ONLY by {@link #getBooking} (see that DTO
     * field's javadoc for why every other construction site hardcodes {@code false}).
     *
     * <p>Mirrors the EXACT conditions {@code ClientReviewService.create} checks before persisting
     * a {@code ClientReview}, so this can never promise a CTA the write endpoint would then
     * reject: (1) the actor has provider review-authority over this booking, via
     * {@link AuthorizationService#hasProviderAuthorityOverBooking} — the same predicate
     * {@code enforceCanReviewClient} throws on, reused non-throwing here rather than
     * re-derived; (2) {@link BookingClosureRule#isReviewEligible} — {@code status == COMPLETED}
     * OR an elapsed-but-unclosed {@code CONFIRMED} booking (the SAME predicate the client-side
     * {@code canReview} flag and {@code ClientReviewService.create}'s write gate both use, so a
     * booking that aged into Past by elapsed time is never falsely withheld here even though the
     * provider never closed it — mirrors the fix already applied to the client review path); (3)
     * the booking has a real client (a guest/LINK booking has none — V89 {@code
     * chk_bookings_guest_fields}); (4) no {@link com.beautica.review.entity.ClientReview} already
     * exists for this booking. A CLIENT or SALON_MASTER viewer always fails condition (1), so this
     * correctly reads {@code false} for them without any special-casing here.
     *
     * <p><b>Phase-242 QA audit, finding 2 (MEDIUM) — the leading
     * {@link AuthorizationService#isOwningClientViewer} gate is a COST gate, not a decision.</b>
     * Condition (1) reads {@code master.getSalon()} then {@code salon.getOwner()} before it can
     * conclude "not a provider". {@code getOwner()} is a PROPERTY read, so it INITIALISES the
     * {@code Salon} proxy, and since phase 242 re-pointed {@code findByIdWithFullGraph} to fetch
     * {@code b.salon} instead of {@code m.salon} that walk issues a standalone
     * {@code SELECT ... FROM salons} on any booking whose master has rotated salons since. The
     * phase-242 audit-fix batch hoisted the same client probe inside
     * {@code AuthorizationService#enforceCanViewBooking}, which fixed {@code GET /appointments/{id}}
     * — but this method re-entered the identical walk a few statements later, so the owning CLIENT,
     * the highest-volume viewer class of {@code GET /bookings/{id}}, kept paying it. Gating on the
     * SAME classification {@code enforceCanViewBooking} already computed (no new
     * {@code SecurityContext} read, no new query) takes it off the client path for good.
     *
     * <p>Sound because an owning-CLIENT viewer can never satisfy condition (1) — see
     * {@link AuthorizationService#isOwningClientViewer}'s javadoc for the full argument
     * ({@code bookings.client_id} is a {@code Role.CLIENT} row asserted at insert and immutable
     * afterwards, so it is neither {@code masters.user_id} nor {@code salons.owner_id}, and the
     * management-access arm is unconditionally false for {@code Role.CLIENT}). The gate can only
     * ever remove a {@code true} the WRITE endpoint would have rejected anyway:
     * {@code POST /client-reviews} is guarded by
     * {@code @PreAuthorize @authz.canReviewClient(...)}, which denies {@code ROLE_CLIENT} outright
     * with no DB hit. {@code SALON_MASTER} is deliberately not gated — see the same javadoc.
     *
     * @param now the SAME already-resolved instant {@link #getBooking} uses for {@code canReview}
     *            and {@code awaitingClosure} — never a second, independently-resolved instant.
     */
    private boolean computeProviderCanReviewClient(UUID actorUserId, Booking booking, OffsetDateTime now) {
        return !authz.isOwningClientViewer(actorUserId, booking)
                && authz.hasProviderAuthorityOverBooking(actorUserId, booking)
                && BookingClosureRule.isReviewEligible(booking.getStatus(), booking.getEndsAt(), now)
                && booking.getClient() != null
                && !clientReviewRepository.existsByBookingId(booking.getId());
    }

    /**
     * Builds the enriched {@link BookingDetailResponse} for a fully-hydrated booking,
     * resolving the district-primary discovery locality labels through the M2 seam.
     * Salon-employed masters resolve to the salon's locality; independent masters to the
     * master's own user-row locality — mirroring {@code SearchService}'s COALESCE rule.
     *
     * <p>{@code now} is supplied by the caller, not resolved here, so a caller that also needs
     * {@code now} to compute {@code canReview} (see {@link #getBooking}) reads {@code
     * clock.instant()} exactly once for the whole response — never two independent instants for
     * {@code canReview} and {@code awaitingClosure} on the same row.
     */
    private BookingDetailResponse enrichSingle(
            Booking booking, boolean canReview, boolean providerCanReviewClient, OffsetDateTime now) {
        // Phase 242 — the BOOKING's salon snapshot, matching BookingDetailResponse#from. These two
        // ids feed cityLabel/districtLabel, so keeping them on master.getSalon() would pair salon
        // A's street with salon B's city on any booking made before a rotation.
        Salon salon = booking.getSalon();
        User masterUser = booking.getMaster().getUser();
        UUID cityId = salon != null ? salon.getCityId() : masterUser.getCityId();
        UUID districtId = salon != null ? salon.getDistrictId() : masterUser.getDistrictId();

        DiscoveryLabels labels = discoveryLocationResolver.resolveLabels(
                cityId == null ? List.of() : List.of(cityId),
                districtId == null ? List.of() : List.of(districtId));

        // Phase 29.2: single-row path, so "once per request" trivially holds — there is only one
        // row to disagree with itself.
        return BookingDetailResponse.from(
                booking, canReview, providerCanReviewClient,
                labels.cityLabel(cityId), labels.districtLabel(districtId), now);
    }

    /**
     * Absolute-instant "now" for the Phase 29.1 {@link BookingClosureRule} — {@link
     * Clock#instant()} as a fixed-offset {@link OffsetDateTime}, mirroring the identical
     * expression {@link #getMyBookings(UUID, Authentication, List, LocalDate, LocalDate, List,
     * BookingPartition, Pageable)} already resolves for the Phase 28.1 partition boundary. Never
     * {@code Instant.now()} / {@code OffsetDateTime.now()} (Anti-Bug §G), and {@link
     * TimeZones#KYIV} must never appear here — see {@code BookingSpecifications#partition}'s
     * javadoc for the clock/timezone invariant this mirrors.
     */
    private OffsetDateTime resolveNow() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * {@code canReview = (COMPLETED, or CONFIRMED with an already-elapsed endsAt) && no existing
     * review && a registered client exists to leave one}. The STATUS+TIME half delegates to
     * {@link BookingClosureRule#isReviewEligible} — the single canonical definition shared with
     * {@code ReviewService#createReview}'s write-path gate, so the CTA this flag backs and the
     * write endpoint that accepts/rejects it can never disagree (locked product decision: a
     * booking that entered the client's "Past" tab by ELAPSED TIME is reviewable even when the
     * provider never closed it out — see {@link BookingClosureRule#isReviewEligible}'s javadoc).
     * A guest (LINK) booking has no account ({@code client_id} is null, V89 {@code
     * chk_bookings_guest_fields}), so it can never be review-eligible regardless of status/time —
     * {@code ReviewService.createReview} requires an authenticated CLIENT owner, which a guest
     * booking can never have.
     */
    private static boolean canReview(
            BookingStatus status, OffsetDateTime endsAt, OffsetDateTime now,
            boolean reviewExists, boolean hasClient) {
        return hasClient && !reviewExists && BookingClosureRule.isReviewEligible(status, endsAt, now);
    }

    /**
     * Discovery city id: the BOOKED salon's when the visit was made at a salon, else the master's
     * own user row (phase 242 — {@code booking.getSalon()}, never {@code master.getSalon()}; must
     * move together with the address block or the labels describe a different premises).
     */
    private static UUID discoveryCityId(Booking booking) {
        Salon salon = booking.getSalon();
        return salon != null ? salon.getCityId() : booking.getMaster().getUser().getCityId();
    }

    /** Discovery district id: same rule and same source as {@link #discoveryCityId}. */
    private static UUID discoveryDistrictId(Booking booking) {
        Salon salon = booking.getSalon();
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

    /**
     * Maps a CLIENT projection row to the enriched response, stamping resolved labels. {@code now}
     * is the SAME instant resolved once in {@link #getMyBookings(UUID, Authentication, List,
     * LocalDate, LocalDate, List, BookingPartition, Pageable)} for the whole page — threaded
     * through, never re-read here (Phase 29.2).
     */
    private static BookingDetailResponse toDetailResponse(
            ClientBookingDetailProjection p, DiscoveryLabels labels, OffsetDateTime now) {
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
                canReview(p.status(), p.endsAt(), now, p.reviewExists(), p.clientId() != null),
                // providerCanReviewClient hardcoded false: this path only ever serves the CLIENT
                // branch of GET /bookings/me (findClientBookingDetails is scoped by client_id), and
                // a CLIENT viewer structurally fails the provider-authority predicate — see
                // BookingDetailResponse's class javadoc.
                false,
                p.appointmentId(),
                // No null-guard needed (unlike BookingDetailResponse#from's entity path): this
                // projection is CLIENT-scoped via `JOIN b.client`, so a guest booking cannot
                // appear here at all. A client with no uploaded photo yields null naturally.
                p.clientAvatarUrl(),
                BookingClosureRule.isAwaitingClosure(p.status(), p.endsAt(), now),
                // Phase B1 — the SAME normalisation the entity path applies, called from the one
                // shared helper rather than re-inlined here, so the two mappers cannot disagree
                // about what a zero-review master's rating is (BookingDetailContractIT's reflective
                // parity loop covers this field automatically).
                BookingDetailResponse.masterAvgRatingOrNull(p.masterReviewCount(), p.masterAvgRating()),
                p.masterReviewCount(),
                // Phase B2 — the booking's own salon snapshot (b.salon.id), NOT p.salonName()'s
                // source (m.salon). Nullable for an independent master's booking.
                p.salonId());
    }

    /**
     * Lists the actor's bookings as the enriched {@link BookingDetailResponse} (Phase 19.3 —
     * {@code GET /bookings/me} switched from the lean {@code BookingResponse} per locked
     * Option A). {@code canReview} is true for an unreviewed booking that is either {@code
     * COMPLETED} or {@code CONFIRMED} with an already-elapsed {@code endsAt} — see {@link
     * BookingClosureRule#isReviewEligible}.
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
     *
     * <p><b>Phase 28.2.</b> This 7-argument overload is preserved byte-for-byte and simply
     * delegates to the 8-argument {@link #getMyBookings(UUID, Authentication, List, LocalDate,
     * LocalDate, List, BookingPartition, Pageable)} overload below with {@code partition = null}
     * — no line of the actual query logic lives here any more. This is what pins the "absent
     * {@code partition} ⇒ byte-identical to today" backwards-compatibility contract at the type
     * level: every pre-28.1 caller (production and test — including the five sibling {@code
     * BookingMyBookings*IT} suites this phase must not edit) keeps compiling and behaving
     * identically without a single call site changing. {@code @Transactional(readOnly = true)} is
     * repeated here (not just on the 8-arg overload) so an EXTERNAL caller of this overload still
     * gets a transaction from the Spring proxy; the resulting self-invocation into the 8-arg
     * overload then simply runs inside that already-open transaction (Spring's default {@code
     * REQUIRED} propagation) rather than needing a second proxy interception.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingDetailResponse> getMyBookings(
            UUID actorUserId, Authentication auth, List<BookingStatus> status,
            LocalDate from, LocalDate to, List<UUID> serviceId, Pageable pageable) {
        return getMyBookings(actorUserId, auth, status, from, to, serviceId, null, pageable);
    }

    /**
     * Phase 28.2 — {@code partition} overload backing {@code GET /bookings/me?partition=}. Same
     * {@code status}/{@code from}/{@code to}/{@code serviceId} contract as the 7-argument overload
     * above (see its javadoc), plus:
     *
     * <p><b>Precedence — {@code partition != null} makes {@code status} IGNORED, not a 400.</b>
     * This is deliberate: it is the rollout safety valve. A mobile client sends BOTH params during
     * the transition, so a build hitting an OLD backend (which silently drops the unknown {@code
     * partition} query param) degrades exactly to today's shipped {@code status}-only behaviour
     * instead of an unfiltered list. {@code statuses} below is computed as {@code null} (no
     * predicate at all) whenever {@code partition != null} — the ignore happens by construction,
     * not by a downstream filter that could regress into an accidental {@code AND}.
     *
     * <p>{@code now} is resolved exactly once, here, as an absolute-instant {@link
     * OffsetDateTime} derived from {@link Clock#instant()} — never {@code OffsetDateTime.now()} or
     * {@code Instant.now()} (Anti-Bug §G). {@link ZoneOffset#UTC} is used purely as the
     * fixed-offset REPRESENTATION of that instant so it can bind to the {@code OffsetDateTime}-
     * typed {@code endsAt} Criteria path; {@link TimeZones#KYIV} must never appear here — see
     * {@code BookingSpecifications#partition}'s javadoc for the full clock/timezone invariant this
     * mirrors.
     *
     * <p><b>Phase 29.2 — {@code now} is resolved UNCONDITIONALLY</b>, even when {@code partition ==
     * null}. It is no longer only the partition boundary's input: it is also threaded into every
     * row's {@code awaitingClosure} response flag (both the CLIENT projection path and the
     * provider entity path), so a plain {@code GET /bookings/me} with no {@code partition} still
     * needs one resolved instant for the whole page. This is the SAME single-instant-per-page
     * discipline the partition boundary already established — one {@code now} answers every row on
     * one page, never re-derived per row.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingDetailResponse> getMyBookings(
            UUID actorUserId, Authentication auth, List<BookingStatus> status,
            LocalDate from, LocalDate to, List<UUID> serviceId, BookingPartition partition,
            Pageable pageable) {
        // Role is already encoded in the JWT-derived authority — no DB round-trip needed to
        // resolve the role. Only SALON_OWNER requires a DB call to fetch the associated salonId.
        // AuthenticationUtils.role is the single source of truth for this read (B14): it scans
        // ALL authorities, ignores unrecognised ROLE_* strings instead of throwing, and rejects a
        // multi-role principal — do NOT reintroduce a local extractor here or in getMyBookedDays.
        Role role = AuthenticationUtils.role(auth);

        // Phase 28.2 precedence rule: partition present -> status predicate is never built at all.
        Set<BookingStatus> statuses = (partition != null || status == null || status.isEmpty())
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

        // Phase 28.1/29.2: absolute-instant "now" — Clock#instant(), never Kyiv-zoned — resolved
        // ONCE for the whole page. Used for the partition boundary (when partition != null) AND
        // for every row's awaitingClosure flag (always) — see resolveNow()'s javadoc.
        OffsetDateTime now = resolveNow();

        // Phase 26.3: validate/whitelist/default/tiebreak the sort BEFORE the role dispatch, so
        // BOTH the client projection query and the provider ID-page query receive an identical,
        // already-safe Pageable — see normalizeBookingSort's javadoc for why this must happen
        // here and not deeper in either path.
        Pageable normalizedPageable = normalizeBookingSort(pageable);

        Page<BookingDetailResponse> page = role == Role.CLIENT
                ? listClientBookings(actorUserId, statuses, fromTs, toExclusive, serviceIds, partition, now, normalizedPageable)
                : listProviderBookings(role, actorUserId, statuses, fromTs, toExclusive, serviceIds, partition, now, normalizedPageable);

        return PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
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

        // Same shared, hardened role read getMyBookings uses — see the comment there.
        Role role = AuthenticationUtils.role(auth);

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
     * Phase 29.4 — {@code GET /bookings/me/unclosed-count}: a cheap, single {@code COUNT(*)} of
     * bookings {@link BookingClosureRule#awaitingClosure(OffsetDateTime) awaiting the caller's own
     * closure} — exactly the same rows {@code ?partition=AWAITING_CLOSURE} (Phase 29.3) would
     * return, without paying for a page of hydrated rows just to read {@code totalElements}.
     *
     * <p><b>Scope resolution mirrors {@code getMyBookings}'s provider/client scope exactly</b> —
     * same role dispatch, same {@code masterRepository.findByUserId} / {@code
     * salonRepository.findIdsByOwnerIdAndIsActiveTrue} lookups, same {@link BookingSpecifications}
     * scope factories, same {@code SALON_ADMIN} rejection (they manage staff/services, not
     * bookings — identical boundary to {@link #getMyBookedDays} and {@link #listProviderBookings}).
     * No new {@code @authz} method, no new authorization surface — see this phase's own
     * "Authorization — nothing new" clause. A {@code SALON_OWNER} with no active salons gets
     * {@code count: 0} with no query at all, mirroring {@link #listProviderBookings}'s {@code
     * Page.empty} short-circuit for the same case.
     *
     * <p><b>One query, no N+1.</b> The scope predicate is ANDed with {@link
     * BookingClosureRule#awaitingClosure(OffsetDateTime)} and executed as a single {@code
     * COUNT(*)} via {@link BookingRepository#count(Specification)} — never a hydrated {@link Page}
     * whose {@code getTotalElements()} is read for its side effect, and never a per-master count
     * loop for the salon-scope case.
     */
    @Transactional(readOnly = true)
    public UnclosedCountResponse getUnclosedCount(UUID actorUserId, Authentication auth) {
        Role role = AuthenticationUtils.role(auth);
        OffsetDateTime now = resolveNow();

        Specification<Booking> scope = switch (role) {
            case CLIENT -> BookingSpecifications.clientIdEquals(actorUserId);
            case SALON_MASTER, INDEPENDENT_MASTER -> {
                Master master = masterRepository.findByUserId(actorUserId)
                        .orElseThrow(() -> new NotFoundException("Master profile not found"));
                yield BookingSpecifications.masterIdEquals(master.getId());
            }
            case SALON_OWNER -> {
                List<UUID> salonIds = salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorUserId);
                yield salonIds.isEmpty() ? null : BookingSpecifications.salonIdIn(salonIds);
            }
            // SALON_ADMIN intentionally excluded: they manage staff/services, not bookings — same
            // boundary getMyBookings/getMyBookedDays enforce for the identical reason.
            case SALON_ADMIN -> throw new ForbiddenException("SALON_ADMIN cannot list bookings via this endpoint");
        };

        if (scope == null) {
            return new UnclosedCountResponse(0L);
        }

        long count = bookingRepository.count(scope.and(BookingClosureRule.awaitingClosure(now)));
        return new UnclosedCountResponse(count);
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
     * parameter (Phase 26.3 audit, finding backend-perf F4) — a cheap O(1) length guard applied
     * BEFORE the per-order whitelist/duplicate loop, so a pathological
     * {@code ?sort=…&sort=…&sort=…&…} is rejected without allocating or walking anything.
     *
     * <p><b>It is no longer the binding constraint on plan-cache cardinality</b>, and must not be
     * read as one. Since the Phase 26.8 audit, {@link #normalizeBookingSort} rejects a REPEATED
     * property outright; combined with {@link #SORTABLE_BOOKING_PROPERTIES} holding exactly one
     * member, the effective maximum is 1 order and the reachable {@code ORDER BY} texts are
     * exactly two ({@code startsAt ASC, id ASC} and {@code startsAt DESC, id ASC}). Before that
     * rejection a caller could send {@code sort=startsAt,asc&sort=startsAt,desc&sort=startsAt,asc}
     * and mint up to 14 textually distinct {@code ORDER BY} clauses — column names cannot be bind
     * parameters, so each is its own Postgres prepared-statement/plan-cache entry — where 2
     * suffice. The duplicate check closed that; this constant is retained only as the outer
     * length bound, at parity with the controller's {@code @Size(max = 5)} on {@code status}.
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
     *       {@code @Size(max = 5)} bound on {@code status}. Checked first, so an absurdly long
     *       sort list is rejected before any per-order work.</li>
     *   <li><b>No repeated property.</b> A property that appears twice throws a 400
     *       {@link BusinessException} (Phase 26.8 audit, backend-perf). A repeat is never
     *       meaningful — SQL applies the first {@code ORDER BY} term for a column and every later
     *       one on the same column is dead — but each distinct {@code (property, direction)}
     *       sequence still compiles to a distinct {@code ORDER BY} text and therefore its own
     *       plan-cache entry. Rejecting repeats collapses the reachable {@code ORDER BY} texts on
     *       this endpoint to exactly two. No real caller is affected: the shipped mobile client
     *       sends at most one order.</li>
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
     *
     * <p>Preserved for SORT normalization only — an {@code Unpaged} that survives this method and
     * reaches {@code BookingRepositoryCustom} is rejected there with an
     * {@link IllegalArgumentException} (an unbounded id scan is not a supported query). Those unit
     * tests only work because they mock the repository; unpaged is NOT a usable production path
     * through this service.
     */
    private Pageable normalizeBookingSort(Pageable pageable) {
        Sort requestedSort = pageable.getSort();
        Sort effectiveSort = requestedSort.isUnsorted() ? DEFAULT_BOOKING_SORT : requestedSort;

        if (effectiveSort.stream().count() > MAX_SORT_ORDERS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Too many sort properties");
        }

        Set<String> seenProperties = new HashSet<>();
        for (Sort.Order order : effectiveSort) {
            if (!SORTABLE_BOOKING_PROPERTIES.contains(order.getProperty())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "Unsupported sort property: " + order.getProperty());
            }
            if (!seenProperties.add(order.getProperty())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "Duplicate sort property: " + order.getProperty());
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
     *
     * <p><b>Phase 28.2.</b> {@code partition != null} routes to {@link
     * BookingRepository#findIdsByClientIdFilteredByPartition} instead of {@link
     * BookingRepository#findIdsByClientIdFiltered} — a genuinely different repository method, not
     * a branch inside the same one, so the {@code partition == null} path below is textually
     * identical to the pre-28.1 code.
     */
    private Page<BookingDetailResponse> listClientBookings(
            UUID clientId, Set<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Set<UUID> serviceIds, BookingPartition partition, OffsetDateTime now, Pageable pageable) {
        Page<UUID> idPage = partition != null
                ? bookingRepository.findIdsByClientIdFilteredByPartition(
                        clientId, partition, now, from, toExclusive, serviceIds, pageable)
                : bookingRepository.findIdsByClientIdFiltered(
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
                .map(p -> toDetailResponse(p, labels, now))
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    /**
     * Provider path — ID-page + graph hydrate (Fix H1), then one batched review-existence
     * query and the two-query label resolution for the whole page.
     *
     * <p><b>Phase 28.2.</b> {@code partition != null} routes each branch to its {@code
     * ...ByPartition} repository counterpart instead of the {@code statuses}-filtered method —
     * see {@link #listClientBookings}'s javadoc for why this is a distinct-method dispatch, not a
     * branch inside one shared query method.
     */
    private Page<BookingDetailResponse> listProviderBookings(
            Role role, UUID actorUserId, Set<BookingStatus> statuses,
            OffsetDateTime from, OffsetDateTime toExclusive,
            Set<UUID> serviceIds, BookingPartition partition, OffsetDateTime now, Pageable pageable) {
        // Two-query pattern (Fix H1 — HHH90003004): first fetch a page of IDs using
        // plain JPQL with no JOIN FETCH (so the DB applies LIMIT/OFFSET correctly), then
        // batch-hydrate only those IDs with the full association graph in a second query.
        Page<UUID> idPage = switch (role) {
            case SALON_MASTER, INDEPENDENT_MASTER -> {
                Master master = masterRepository.findByUserId(actorUserId)
                        .orElseThrow(() -> new NotFoundException("Master profile not found"));
                yield partition != null
                        ? bookingRepository.findIdsByMasterIdFilteredByPartition(
                                master.getId(), partition, now, from, toExclusive, serviceIds, pageable)
                        : bookingRepository.findIdsByMasterIdFiltered(
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
                yield partition != null
                        ? bookingRepository.findIdsBySalonIdsFilteredByPartition(
                                salonIds, partition, now, from, toExclusive, serviceIds, pageable)
                        : bookingRepository.findIdsBySalonIdsFiltered(
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
                    boolean canReview = canReview(
                            b.getStatus(), b.getEndsAt(), now, reviewed.contains(b.getId()), b.getClient() != null);
                    // providerCanReviewClient hardcoded false: this row-by-row path backs
                    // GET /bookings/me's provider listing, which is out of scope for the
                    // "Залишити відгук про клієнта" CTA this field gates — see
                    // BookingDetailResponse's class javadoc. Only GET /bookings/{id}
                    // (BookingService#getBooking) computes it for real.
                    return BookingDetailResponse.from(
                            b, canReview, false, labels.cityLabel(cityId), labels.districtLabel(districtId), now);
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
        Booking saved = declineBookingCore(actorUserId, bookingId, req);
        outboxService.enqueueStatusChanged(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        return BookingResponse.from(saved, resolveNow());
    }

    /**
     * Batched-decline counterpart of {@link #declineBooking}, for the schedule-override-conflict
     * write path ({@code ScheduleOverrideConflictService}), which can decline many STANDALONE
     * bookings (no {@code appointmentId}) of the SAME master in one write. Runs the identical
     * mutation ({@link #declineBookingCore}) but skips BOTH of {@link #declineBooking}'s own
     * post-mutation steps:
     * <ul>
     *   <li>its two after-commit cache scans (perf finding, 2026-07-26 audit) — the caller performs
     *       ONE combined eviction itself after the whole decline loop instead of one
     *       {@code registerSlotEviction} + {@code evictMasterCalendarAfterCommit} pair PER declined
     *       booking, all for the same master;</li>
     *   <li>the {@code outboxService.enqueueStatusChanged} notification call — a booking declined via
     *       a schedule-override conflict enqueues NOTHING (D6, 2026-07-26 product decision reversal):
     *       the client discovers the cancellation from the booking's status alone, and notifying for
     *       this flow is deferred to a later phase. This is a deliberate, permanent property of THIS
     *       method, not an oversight — do not "fix" it back by re-adding the enqueue call here.</li>
     * </ul>
     *
     * <p>Package-private: the only caller ({@code ScheduleOverrideConflictService}) lives in this same
     * package. {@link #declineBooking} itself is completely unchanged — same signature, same eviction,
     * same notification, same tests — this is purely an additive extraction of the shared core the
     * two now call.
     */
    Booking declineBookingForBatch(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        return declineBookingCore(actorUserId, bookingId, req);
    }

    /**
     * Shared mutation core of {@link #declineBooking} / {@link #declineBookingForBatch}: existence +
     * ownership + status validation, then the actual {@code CONFIRMED -> DECLINED} transition and
     * save. Deliberately does NOT touch any cache and does NOT enqueue any notification — both are
     * entirely the caller's responsibility, so the two callers above can differ in how (and how
     * often) they evict and whether they notify at all, never in what the transition itself does.
     *
     * <p><b>Freshness re-check (G5, HIGH — same defect class as G4, closing the last of the three
     * standalone-booking provider transitions).</b> {@link #assertNotAppointmentChild} above
     * guarantees this is always a STANDALONE booking, so — exactly like {@link
     * #cancelBooking(UUID, Booking, CancelBookingRequest)} and {@link #rescheduleBooking}'s
     * standalone branches before G4 — there is no header lock to serialize this write against a
     * concurrent {@link #cancelBooking} of the SAME row. {@code assertTransition} just above only
     * proves {@code booking.getStatus() == CONFIRMED} on the snapshot {@code findByIdWithFullGraph}
     * loaded at the top of this method; it cannot see a client cancel that commits CANCELLED in the
     * gap between that load and this method's own save below. Before this fix, this method would
     * then unconditionally overwrite that CANCELLED row with DECLINED, discarding {@code
     * clientCancellationNote} — an asymmetric exploit, since G4 already protects {@link
     * #cancelBooking} when it is the SECOND writer (its own {@code isStillConfirmed} recheck), but
     * nothing protected the reverse direction: a provider could fire decline the instant a
     * cancellation looked imminent and win by committing last. Routed through {@link
     * #isStillConfirmed} — the SAME package-private seam G4 introduced, reused rather than
     * duplicated — because {@code Booking} carries no lock this method could hang a rendezvous off
     * instead (see that method's Javadoc).
     */
    private Booking declineBookingCore(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        // Fix M4: require a reason, consistent with notCompleteBooking
        if (req.cancellationReason() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Cancellation reason required for declining a booking");
        }
        // Existence + ownership collapse to a single uniform 403 (Finding 8 — existence oracle),
        // mirroring notCompleteBooking / cancelBooking: a missing id and an existing-but-foreign
        // booking must be indistinguishable to the caller. A plain full-graph load surfaced a 404
        // for a missing id BEFORE the ownership guard ran — letting a valid provider distinguish
        // "exists-but-not-mine" (403) from "doesn't-exist" (404). A missing booking now
        // short-circuits to the SAME 403 the ownership guard throws for a foreign one. Ownership
        // enforcement is unchanged (below): a foreign provider still gets 403.
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        authz.enforceCanCancelBooking(actorUserId, booking);
        // Multi-service visit item: refuse the single-booking transition (use /appointments/{id}).
        assertNotAppointmentChild(booking);
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.DECLINED);
        // Product decision reversal: decline is no longer future-only — a provider may decline a
        // CONFIRMED booking at any time, elapsed or not (e.g. the client never showed up and the
        // provider simply wants to close it out via decline rather than a separate no-show action).

        // Freshness re-check (G5) — see this method's own Javadoc. Must run immediately before the
        // mutation below: assertTransition above only proves CONFIRMED on the stale pre-load
        // snapshot, not on the current row.
        if (!isStillConfirmed(booking.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service changed concurrently — please retry");
        }
        booking.setStatus(BookingStatus.DECLINED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(BookingComments.normalize(req.comment()));
        // Notification enqueue is NOT here — see this method's own javadoc: it is the caller's
        // responsibility (declineBooking always enqueues; declineBookingForBatch never does, D6).
        return bookingRepository.save(booking);
    }

    /**
     * Provider-initiated completion of an already-{@code CONFIRMED} booking.
     *
     * <p><b>Freshness re-check (G5, HIGH — same defect class as G4/G5 above; see {@link
     * #declineBookingCore}'s Javadoc for the full rationale, which applies here verbatim).</b>
     * {@code assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.COMPLETED)} below
     * fixes this method's source state as {@code CONFIRMED} — the ONLY status it ever transitions
     * from — so {@link #isStillConfirmed}, the exact predicate {@link #cancelBooking} and {@link
     * #rescheduleBooking} already recheck against, is the correct freshness probe here too, not a
     * different one. Without it, this method could commit COMPLETED over a booking a concurrent
     * client {@link #cancelBooking} already moved to CANCELLED in the gap between this method's
     * {@code findByIdWithFullGraph} load and its own save.
     */
    @Transactional
    public BookingResponse completeBooking(UUID actorUserId, UUID bookingId) {
        // Existence + ownership collapse to a single uniform 403 (Finding 8 — existence oracle),
        // mirroring notCompleteBooking / declineBooking: a missing id and an existing-but-foreign
        // booking are indistinguishable to the caller (a plain full-graph load would surface a 404
        // for a missing id BEFORE the ownership guard, leaking existence). Ownership enforcement is
        // unchanged (below): a foreign provider still gets 403.
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        // Phase 18.4 / 24.2: completion, decline, and not-complete all share the same
        // provider-authority shape (admits SALON_ADMIN) — see AuthorizationService.enforceCanCompleteBooking
        // / enforceCanCancelBooking.
        authz.enforceCanCompleteBooking(actorUserId, booking);
        // Multi-service visit item: refuse the single-booking transition (use /appointments/{id}).
        assertNotAppointmentChild(booking);
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
        // Phase 27.1: complete unlocks once the appointment has begun/elapsed (now >= startsAt) —
        // no requirement that it has ENDED. Checked AFTER the status guard, same ordering as decline.
        BookingTemporalGuard.assertElapsedForComplete(booking.getStartsAt(), clock);

        // Freshness re-check (G5) — see this method's own Javadoc. Must run immediately before the
        // mutation below: assertTransition above only proves CONFIRMED on the stale pre-load
        // snapshot, not on the current row.
        if (!isStillConfirmed(booking.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service changed concurrently — please retry");
        }
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
        // Announce the completion as a domain fact so other feature packages can react without
        // importing this one. Consumed by ClientPassportCacheEvictor (AFTER_COMMIT, per key) — a
        // completed booking moves bookingsConsidered, the district/city rankings and the budget
        // band on the client's BEAUTY PASSPORT. Published INSIDE the transaction; Spring holds it
        // until commit because the listener is @TransactionalEventListener(AFTER_COMMIT), so a
        // rolled-back completion evicts nothing. Guest (LINK) bookings carry a null client — the
        // event records that faithfully and the listener skips it, exactly as the review-prompt
        // enqueue above does.
        eventPublisher.publishEvent(new BookingCompletedEvent(
                saved.getClient() != null ? saved.getClient().getId() : null));
        return BookingResponse.from(saved, resolveNow());
    }

    /**
     * Provider-initiated no-show closure of an already-{@code CONFIRMED} booking.
     *
     * <p><b>Freshness re-check (G5, HIGH — same defect class as G4/G5 above; see {@link
     * #declineBookingCore}'s Javadoc for the full rationale).</b> {@code assertTransition(booking,
     * BookingStatus.CONFIRMED, BookingStatus.NOT_COMPLETED)} below fixes this method's source state
     * as {@code CONFIRMED}, so {@link #isStillConfirmed} is the correct freshness probe here too —
     * without it, this method could commit NOT_COMPLETED over a booking a concurrent client {@link
     * #cancelBooking} already moved to CANCELLED in the gap between this method's {@code
     * findByIdWithFullGraph} load and its own save.
     */
    @Transactional
    public BookingResponse notCompleteBooking(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        // Existence + ownership collapse to a single uniform 403 (Finding 8 — existence oracle),
        // mirroring cancelBooking: a missing id and an existing-but-foreign booking must be
        // indistinguishable to the caller. Since /not-complete dropped its redundant
        // @authz.canCancelBooking SpEL clause (which duplicated the service-layer fetch), a plain
        // full-graph load surfaced a 404 for a missing id BEFORE the ownership guard ran — letting
        // a valid provider distinguish "exists-but-not-mine" (403) from "doesn't-exist" (404). A
        // missing booking now short-circuits to the SAME 403 the ownership guard throws for a foreign
        // one. Ownership enforcement is unchanged (below): a foreign provider still gets 403.
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        // Phase 24.2: aligned to the same provider-authority shape as completeBooking/
        // declineBooking (admits SALON_ADMIN) — leaving admin able to complete/decline but not
        // mark a no-show would be an incoherent permission set (decision D2).
        authz.enforceCanCancelBooking(actorUserId, booking);
        // Multi-service visit item: refuse the single-booking transition (use /appointments/{id}).
        assertNotAppointmentChild(booking);
        if (req.cancellationReason() == null) {
            throw new BusinessException("Cancellation reason required");
        }
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.NOT_COMPLETED);

        // Freshness re-check (G5) — see this method's own Javadoc. Must run immediately before the
        // mutation below: assertTransition above only proves CONFIRMED on the stale pre-load
        // snapshot, not on the current row.
        if (!isStillConfirmed(booking.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service changed concurrently — please retry");
        }
        booking.setStatus(BookingStatus.NOT_COMPLETED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(BookingComments.normalize(req.comment()));
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        evictRevenueDashboardAfterCommit(actorUserId);
        return BookingResponse.from(saved, resolveNow());
    }

    /**
     * Client-initiated cancel of a single {@code CONFIRMED} booking.
     *
     * <p><b>Track 27.x widening (per-leg client cancel).</b> Unlike the sibling provider transitions
     * ({@link #declineBooking}, {@link #completeBooking}, {@link #notCompleteBooking}), this path
     * deliberately does NOT call {@link #assertNotAppointmentChild} — a multi-service visit child
     * (non-null {@code booking.getAppointment()}) is now a legal target here. Product decision: the
     * client's booking list renders ONE card per service, each with its own cancel action, so a
     * client must be able to cancel exactly one leg of a visit without cancelling its siblings. The
     * whole-visit cancel ({@code PATCH /appointments/{id}/cancel} →
     * {@link AppointmentTransitionService#cancelAppointment}) is unchanged and stays the only way to
     * cancel every leg atomically in one call. Every other single-booking transition
     * (decline/complete/not-complete, all provider-initiated) still refuses an appointment child via
     * {@link #assertNotAppointmentChild} — see that method's Javadoc.
     *
     * <p>Every other CLIENT guard below still applies, evaluated on THIS child row alone: ownership
     * (403), {@code CONFIRMED}-only status (400), and the read-only-after-elapse guard (409) — a
     * cancelled/terminal sibling never influences any of them, since each is a self-contained row.
     *
     * <p>When the cancelled booking is an appointment child, the visit HEADER is LOCKED before this
     * child's own status change is written, and the header is recomputed (collapsed if this was the
     * last CONFIRMED leg) AFTER this child is persisted CANCELLED — the two-phase
     * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel} /
     * {@link AppointmentTransitionService#collapseAppointmentHeaderAfterClientItemCancel} split
     * (cycle-2 audit finding 1 — canonical appointments-before-bookings lock order), mirroring the
     * existing provider per-service decline precedent: the header stays CONFIRMED while ≥1 sibling
     * remains CONFIRMED; cancelling the LAST CONFIRMED leg collapses the header to CANCELLED (reason
     * CLIENT_CANCELLED), carrying this cancel's note.
     */
    @Transactional
    public BookingResponse cancelBooking(UUID clientUserId, UUID bookingId, CancelBookingRequest req) {
        // Existence collapses to the same uniform 403 the ownership filter below also throws for a
        // foreign/guest booking (Finding 8 — existence oracle) — see cancelBooking(UUID, Booking,
        // CancelBookingRequest)'s Javadoc for why the ownership check itself moved into that overload
        // rather than being folded into this query's own .filter(...) (perf audit F2, cross-batch:
        // cancelAppointmentItem needs the UNFILTERED load to also serve its path-consistency check,
        // off the SAME round trip, before the ownership check runs).
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        return cancelBooking(clientUserId, booking, req);
    }

    /**
     * The body of {@link #cancelBooking(UUID, UUID, CancelBookingRequest)}, factored out so
     * {@link #cancelAppointmentItem} can supply an ALREADY full-graph-loaded {@link Booking} —
     * loaded once for its own path-consistency check — instead of paying for a second
     * {@code findByIdWithFullGraph} round trip here (perf audit F2, cross-batch).
     *
     * <p><b>Ownership is re-derived here, never trusted from the caller</b> — the ONE public
     * entry point ({@link #cancelBooking(UUID, UUID, CancelBookingRequest)}) does the load with NO
     * filter of its own precisely so this method remains the SINGLE place the
     * {@code booking.getClient() == clientUserId} check is written; behaviour for that caller is
     * byte-for-byte unchanged (still one round trip, ownership still checked before anything else).
     * {@link #cancelAppointmentItem} separately re-verified the VISIT's ({@code Appointment})
     * {@code client_id} before ever loading this row — this check re-verifies the CHILD
     * ({@code Booking})'s OWN {@code client_id}, a column the DB does not constrain to agree with
     * the appointment's (no FK/CHECK ties the two) — so this is genuine defense-in-depth, not a
     * redundant repeat of the visit-level check that caller already made.
     *
     * <p>Preserves the original method's exact guard ORDER: ownership (403) → CONFIRMED-only status
     * (400) → read-only-after-elapse (409) → the two-phase header lock/collapse → freshness
     * re-check (now unconditional — appointment children AND standalone bookings alike, G4) →
     * mutation.
     *
     * <p><b>Freshness re-check (F1, HIGH, cycle-6 audit 2026-08-03; widened to standalone bookings by
     * G4, cycle-7 audit 2026-08-03).</b> Immediately after the header lock attempt above and BEFORE
     * {@code booking}'s own status is mutated, this method re-verifies {@code booking} via
     * {@link BookingRepository#existsConfirmedById} — a scalar, entity-manager-bypassing probe,
     * never a second entity load. {@code booking} was loaded BEFORE the header lock (if any) existed
     * to protect that read, so the lock alone only proves the HEADER is still CONFIRMED, not that
     * THIS leg still is: a per-item reschedule of the SAME leg (fired near-concurrently at the
     * sibling per-item endpoint) leaves the header CONFIRMED — a sibling remains — while moving
     * {@code booking} to a new time without changing its status. {@code Booking} now carries
     * {@code @DynamicUpdate} (G1), so this save's own dirty-column set is {@code status}/
     * {@code cancellationReason}/{@code clientCancellationNote} only — it can no longer resurrect a
     * concurrent reschedule's stale in-memory time back over the fresh row, or vice versa. What
     * remains, and what this check still exists to reject, is completing a cancel whose CONFIRMED
     * precondition already lapsed: a booking a concurrent operation already moved to a different
     * terminal state (or, on the standalone path below, already rescheduled) would otherwise be
     * silently re-declared CANCELLED, discarding whatever the other operation just did, even though
     * no column would be corrupted in the process. Runs regardless of {@code headerWasLocked} — a
     * header that already left CONFIRMED (some whole-visit transition landed first) means
     * {@code booking} itself was already moved to that terminal state, which the
     * {@code current != CONFIRMED} guard above — evaluated on the SAME pre-lock stale snapshot —
     * could not have caught either. A mismatch aborts with the same 409 shape used elsewhere in this
     * class for a concurrently-changed booking.
     *
     * <p><b>Standalone booking ({@code appointmentId == null}): now covered too (G4, HIGH, cycle-7
     * audit 2026-08-03 — fixed here, REVERSES this paragraph's pre-G1 conclusion).</b> Before G1,
     * this overload deliberately skipped the recheck for a standalone booking: with no
     * {@code @DynamicUpdate}, a bare scalar recheck with no lock to anchor it could only narrow, not
     * close, the TOCTOU window against a concurrent {@link #rescheduleBooking} of the same
     * standalone booking — not worth an extra round trip on the common case. G1 changes the
     * calculus: {@code Booking}'s {@code @DynamicUpdate} means the two operations' column sets
     * (status/reason/note vs. starts_at/ends_at) are now disjoint, so this cancel can no longer
     * clobber a concurrent reschedule's new time (or be clobbered by it) — the column-corruption
     * concern this paragraph originally worried about is gone. But a DIFFERENT, real gap survives
     * G1: {@link #rescheduleBooking}'s own standalone branch (see its Javadoc) can return a
     * misleading {@code 200}/{@code CONFIRMED} response and enqueue a {@code BOOKING_RESCHEDULED}
     * notification for a booking THIS method concurrently cancelled moments earlier, because
     * rescheduling never re-verified the booking was still CONFIRMED right before its own save. That
     * asymmetric exposure lives entirely on the reschedule side (see that method's Javadoc for why
     * cancel-wins-last is comparatively benign — a terminal, CANCELLED record echoing a stale time is
     * not actionable), but this recheck is now unconditional on THIS method too, for the same reason
     * G2 made {@link #rescheduleBooking}'s recheck unconditional: symmetry with the appointment-child
     * branch above removes any asymmetric "which direction is exploitable" argument for the next
     * reader, at the cost of one extra, cheap, indexed scalar query on every cancel — negligible next
     * to the round trips this method already makes.
     */
    private BookingResponse cancelBooking(UUID clientUserId, Booking booking, CancelBookingRequest req) {
        // Existence + ownership collapse to a single uniform 403 (Finding 8 — existence oracle):
        // a missing id, a guest (LINK, null-client) booking, and an existing-but-foreign booking
        // must all be indistinguishable to the caller. A prior 404-then-403 split let an
        // authenticated CLIENT probe whether an arbitrary booking id exists at all.
        if (booking.getClient() == null || !booking.getClient().getId().equals(clientUserId)) {
            throw new ForbiddenException("Access denied");
        }
        BookingStatus current = booking.getStatus();
        if (current != BookingStatus.CONFIRMED) {
            throw new BusinessException("Cannot cancel a booking in status %s".formatted(current));
        }
        // Track 24.x read-only-after-elapse: once the appointment window has fully passed the
        // booking is read-only for the client and awaits provider resolution (decline / complete /
        // mark-no-show) — the client can no longer cancel it. Checked AFTER the status guard so a
        // non-CONFIRMED booking still reports the more specific status conflict.
        assertNotElapsedForClient(booking);

        // Track 27.x widening, cycle-2 audit finding 1 (lock-order inversion / deadlock risk): when
        // this child belongs to a multi-service visit, lock the visit HEADER BEFORE this child row's
        // own status change is written below — the canonical appointments-before-bookings lock order
        // shared with the whole-visit transition methods (AppointmentTransitionService
        // #lockHeaderForWholeVisitTransition). getAppointment().getId() is served off the
        // uninitialised @ManyToOne(LAZY) proxy without a statement (same pattern
        // BookingRepository#findByIdWithFullGraph's Javadoc documents for
        // master.getSalon().getOwner().getId()), and appointmentId's null-check short-circuits the
        // lock call entirely for a legacy standalone booking — ZERO extra statements on that path.
        UUID appointmentId = booking.getAppointment() != null ? booking.getAppointment().getId() : null;
        boolean headerWasLocked = appointmentId != null
                && appointmentTransitionService.lockAppointmentHeaderBeforeClientItemCancel(appointmentId);

        // Freshness re-check (F1, HIGH, cycle-6 audit 2026-08-03; widened to standalone bookings by
        // G4, cycle-7 audit 2026-08-03) — see this method's own "Freshness re-check" / "Standalone
        // booking" Javadoc paragraphs above. Unconditional: both an appointment child and a
        // standalone booking are covered, mirroring rescheduleBooking's identical widening. Routed
        // through isStillConfirmed (G4) — the spy-able seam a standalone-booking concurrency IT
        // needs, since this path has no lock call to hang a rendezvous off instead.
        if (!isStillConfirmed(booking.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service changed concurrently — please retry");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        // cancellationReason is guaranteed non-null by @NotNull on CancelBookingRequest
        booking.setCancellationReason(req.cancellationReason());
        // Fix D2 (track 25.x): req.comment() was validated at the API but never persisted —
        // the client's cancellation note was silently discarded. Stored separately from
        // clientComment (the booking-CREATION note) so the provider's "client cancelled" email
        // (see EmailNotificationService.sendClientCancelledEmail, Fix D3) never confuses the two.
        booking.setClientCancellationNote(BookingComments.normalize(req.comment()));
        Booking saved = bookingRepository.save(booking);
        // Phase 2 (collapse) of the header recompute — see the method Javadoc above and
        // AppointmentTransitionService#collapseAppointmentHeaderAfterClientItemCancel. Must run AFTER
        // the save above so the collapse UPDATE's NOT EXISTS sibling check observes this child's NEW
        // (CANCELLED) status, not its pre-transition one.
        if (appointmentId != null) {
            appointmentTransitionService.collapseAppointmentHeaderAfterClientItemCancel(
                    appointmentId, headerWasLocked, saved.getClientCancellationNote());
        }
        outboxService.enqueueStatusChanged(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        return BookingResponse.from(saved, resolveNow());
    }

    /**
     * Cancels ONE service line of a multi-service visit, reached via the appointment-scoped
     * per-item URL {@code PATCH /appointments/{appointmentId}/services/{bookingId}/cancel} (phase
     * 30.6). Adds exactly two guards — visit ownership and path consistency — then reuses
     * {@link #cancelBooking(UUID, Booking, CancelBookingRequest)}'s body UNCHANGED, so this route
     * and the pre-existing {@code PATCH /bookings/{bookingId}/cancel} can NEVER diverge in
     * semantics. There is deliberately no second implementation of the two-phase header
     * lock/collapse — see that method's own Javadoc for the full account.
     *
     * <p><b>Round-trip count (perf audit F2, cross-batch — FIXED from 3 down to 2).</b> The
     * pre-fix version issued {@link AppointmentRepository#findClientIdById} (visit ownership), then
     * {@link BookingRepository#existsByIdAndAppointmentId} (a bare index-probe EXISTS, purely for
     * path consistency), then delegated to {@code cancelBooking(UUID, UUID, CancelBookingRequest)},
     * which independently re-issued {@code findByIdWithFullGraph} — three round trips where the last
     * two both, in effect, re-established "does this booking exist under this appointment". Fixed:
     * the path-consistency check now runs IN-MEMORY against the SAME full-graph load this method
     * needs for the mutation anyway — {@code booking.getAppointment().getId()} reads off the
     * uninitialised {@code @ManyToOne(LAZY)} proxy without a statement (the same zero-cost pattern
     * {@link #cancelBooking(UUID, UUID, CancelBookingRequest)} already uses for the identical field)
     * — so the dedicated {@code existsByIdAndAppointmentId} probe is no longer needed at all, and the
     * full-graph load that {@code cancelBooking} used to do a second time is now done exactly ONCE,
     * here, and threaded into the shared {@link #cancelBooking(UUID, Booking, CancelBookingRequest)}
     * overload.
     *
     * <p><b>Guard order is preserved EXACTLY</b> (Finding 8 + the 403 → 404 → 409/400 priority): (1)
     * visit ownership via the projection-only {@code findClientIdById} — 403, zero entity loads; (2)
     * path consistency — {@code bookingId} must be a child of THIS {@code appointmentId} — checked
     * on the loaded row's OWN appointment FK, BEFORE the shared overload's ownership re-check runs,
     * so a bookingId under a different (or no) appointment still 404s exactly as the dedicated probe
     * did, never leaking into a 403 from the overload's booking-level ownership filter; (3) inside
     * the shared overload: booking-level ownership (403, defensive — see that method's Javadoc) →
     * CONFIRMED-only status (400) → elapsed (409).
     *
     * <p><b>Lives here rather than in {@code AppointmentTransitionService}</b> to avoid a circular
     * bean graph: this class already depends on {@code AppointmentTransitionService} for the
     * header-lock seam {@link #cancelBooking(UUID, Booking, CancelBookingRequest)} uses, so the
     * reverse edge would cycle (phase 30.6 D1) — do not "move this to where it looks like it
     * belongs".
     *
     * @param clientUserId  the authenticated CLIENT (from the security principal, never the body)
     * @param appointmentId the visit the target booking must belong to
     * @param bookingId     the one service line to cancel
     * @param req           the cancellation reason + optional note, identical shape to
     *                      {@code PATCH /bookings/{bookingId}/cancel}
     * @throws ForbiddenException a missing appointment id, a guest (LINK) visit, or a foreign visit
     *                            (uniform 403 — no existence oracle)
     * @throws NotFoundException  {@code bookingId} is not a child of {@code appointmentId} (404),
     *                            reachable only once the caller is authorized on the visit
     */
    @Transactional
    public BookingResponse cancelAppointmentItem(
            UUID clientUserId, UUID appointmentId, UUID bookingId, CancelBookingRequest req) {
        // Authorize on the VISIT before loading anything (commit 4d156c0): a projection-only
        // client-id read collapses a missing appointment, a guest visit (null client_id), and a
        // foreign visit into ONE uniform 403 — no existence oracle.
        UUID owner = appointmentRepository.findClientIdById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        if (!owner.equals(clientUserId)) {
            throw new ForbiddenException("Access denied");
        }
        // Path consistency, AFTER authorization — so this 404 is reachable only by a caller already
        // authorized on the visit, exactly as declineAppointmentItem's own 404 is. Folded into the
        // SAME full-graph load the mutation below needs (perf audit F2) — no separate exists probe.
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .filter(b -> b.getAppointment() != null && b.getAppointment().getId().equals(appointmentId))
                .orElseThrow(() -> new NotFoundException("Appointment service not found"));
        return cancelBooking(clientUserId, booking, req);
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
     * <p><b>Phase 27.2 — widened to providers</b> (REVERSES the previously-locked "reschedule is
     * client-only" decision). {@code actorRole} branches the ownership/status/elapsed guard only;
     * everything downstream (window validation, schedule check, locks, conflict/overlap checks,
     * the mutation, outbox enqueue, cache eviction) is one shared tail both paths fall into — see
     * the two private branch helpers below. The CLIENT branch is byte-for-byte the pre-27.2
     * behaviour. The PROVIDER branch authorizes via {@link AuthorizationService#enforceCanRescheduleBooking}
     * (same provider-authority shape as decline/complete) and guards temporal validity via
     * {@link BookingTemporalGuard#assertCurrentNotElapsedForReschedule} instead of
     * {@link #assertNotElapsedForClient} — the two are NOT the same predicate: the client guard
     * compares {@code endsAt} (a client may still act right up until the appointment has fully
     * ended), while the provider guard compares {@code startsAt} (a provider can no longer move a
     * booking that has already begun — {@code /complete}/{@code /not-complete} are the only
     * remaining resolutions once that happens). They are therefore kept as two independent guards,
     * not a shared helper.
     *
     * <p><b>Whose client-conflict/lock applies on the provider path.</b> The per-client advisory
     * lock and {@link #assertNoClientConflictExcluding} check the OWNING CLIENT's other bookings
     * for a conflict — that is {@code booking.getClient().getId()}, never {@code actorUserId},
     * when the actor is the provider. A guest (LINK) booking has no client account
     * ({@code booking.getClient() == null}) — there is nothing to lock or conflict-check, so that
     * step is skipped cleanly for a provider rescheduling a guest booking (mirrors the guest
     * null-guards elsewhere in this class).
     *
     * <p><b>Phase 30.2 — appointment-child header lock (lock-order fix).</b> This method already
     * moves a single appointment child today (it never called {@code assertNotAppointmentChild}),
     * but — unlike {@link #cancelBooking}, which compensates with the two-phase header seam — it
     * took only the client-then-master advisory locks and never locked the visit HEADER at all,
     * inverting the canonical appointments-before-bookings lock order (cycle-2 audit finding 1) for
     * this class of write. Fixed below: when {@code booking.getAppointment() != null}, the header is
     * locked via {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule} —
     * AFTER guard resolution (never lock for a request about to 403/409) and BEFORE the client/master
     * advisory locks (restoring the canonical order) — with ZERO extra statements on the legacy
     * standalone-booking path: {@code booking.getAppointment()} reads the FK id off the uninitialised
     * {@code @ManyToOne(LAZY)} proxy without a query, and a {@code null} appointment id (the common
     * case) short-circuits the call entirely. There is deliberately no phase-2 collapse call — a
     * rescheduled item stays {@code CONFIRMED}, so the header's status can never change on this path
     * (see that method's own Javadoc). The lock result is intentionally discarded: a non-CONFIRMED
     * header under a CONFIRMED child is a state the child-level status guard already vetted, and
     * turning it into an error here would exceed the scope of this lock-order fix (the new
     * appointment-scoped route, phase 30.4, treats it as a 409 instead, because there the header is a
     * named part of the request).
     *
     * <p><b>Freshness re-check (G2, HIGH, cycle-7 audit 2026-08-03 — fixed here).</b> This method is
     * the LEGACY {@code PATCH /bookings/{id}/reschedule} route, reachable for an appointment child by
     * BOTH the CLIENT and PROVIDER paths (see {@code BookingController} — unlike
     * {@link #declineBooking}/{@link #completeBooking}/{@link #notCompleteBooking}, this path never
     * calls {@link #assertNotAppointmentChild}). Before this fix it took the header lock above but
     * never re-verified {@code booking} itself afterward — the one per-item mutator in this class
     * missing the freshness re-check every OTHER per-item path (this class's
     * {@link #cancelBooking(UUID, Booking, CancelBookingRequest)} and
     * {@link AppointmentTransitionService#declineAppointmentItem}/
     * {@link AppointmentTransitionService#rescheduleAppointmentItem}) already has. Exploit: a
     * provider declines leg0 via the per-item route (commits {@code DECLINED}); a concurrent call to
     * THIS method for the same leg was blocked on the header lock, acquires it moments later (the
     * header is still CONFIRMED — a sibling remains), and — pre-fix — proceeded straight to its own
     * save with no check that {@code booking} itself was still CONFIRMED. {@code Booking} now carries
     * {@code @DynamicUpdate} (G1), so that save would only touch {@code starts_at}/{@code ends_at}
     * and could not resurrect the DECLINED status at the column level — but it would still silently
     * hand the leg a brand-new time while leaving it DECLINED, an incoherent result the caller has no
     * way to detect from a 200 response. Immediately after the header lock call above, this method
     * now re-verifies {@code booking} via {@link BookingRepository#existsConfirmedById} — the same
     * scalar, entity-manager-bypassing probe every sibling per-item path uses — and aborts with a
     * clean 409 on a mismatch, exactly mirroring {@link #cancelBooking(UUID, Booking,
     * CancelBookingRequest)}'s identical guard for the identical class of write.
     *
     * <p><b>Unconditional, including standalone bookings (G4, HIGH, cycle-7 audit 2026-08-03 —
     * widened here).</b> Unlike the header LOCK two lines above (which only ever applies to an
     * appointment child — a standalone booking has no header), this recheck runs for EVERY
     * reschedule, appointment child or not. The standalone case is the more important half of this
     * widening: a standalone booking can race against {@link #cancelBooking(UUID, Booking,
     * CancelBookingRequest)} of the SAME booking with NO lock at all protecting either side (there is
     * no header to lock, and neither method acquires a row-level lock keyed on the booking itself —
     * the client/master advisory locks below protect SLOT conflicts, not this row's own status).
     * {@code @DynamicUpdate} (G1) means this reschedule's save touches only
     * {@code starts_at}/{@code ends_at} and cannot resurrect a concurrent cancel's {@code CANCELLED}
     * status at the column level — but without this check, the reschedule could still complete and
     * return a {@code 200} response showing {@code CONFIRMED} at the new time, and enqueue a
     * {@code BOOKING_RESCHEDULED} notification to the other party, for a booking a concurrent cancel
     * already terminated moments earlier: a materially misleading response/notification, even though
     * no column is corrupted. The symmetric direction (cancel landing last against a reschedule that
     * already committed) is comparatively benign — the cancelled record simply echoes a now-stale
     * time, which is not actionable on a terminal booking — but this check closes BOTH directions
     * uniformly rather than leaving an asymmetric, direction-dependent gap for the next reader to
     * puzzle over. Zero extra cost beyond the one indexed scalar query: no lock is skipped for the
     * standalone case (there never was one to skip), so this recheck is genuinely the ONLY guard
     * standalone reschedule now has against a concurrent standalone cancel.
     *
     * @param actorUserId the authenticated actor (from the security principal, never the body)
     * @param actorRole   the actor's role, resolved by the controller from the JWT
     * @param bookingId   the booking to move
     * @param req         the new start time
     * @return the updated booking
     * @throws ForbiddenException              if the actor is not the owning client / an
     *                                          authorized provider (403)
     * @throws BusinessException               if the source state is not CONFIRMED (409), the
     *                                          current booking has already elapsed (409), or the
     *                                          new slot conflicts with the master's calendar
     *                                          (409); {@link #validateStartsAt} rejects bad times (400)
     * @throws ClientBookingConflictException  if the new window overlaps another booking the
     *                                          owning client already holds (409, {@code CLIENT_BOOKING_CONFLICT})
     */
    @Transactional
    public BookingDetailResponse rescheduleBooking(UUID actorUserId, UUID bookingId, RescheduleBookingRequest req) {
        // Convenience overload for callers that are always the CLIENT path (pre-27.2 signature;
        // kept so the many existing CLIENT-path unit tests need not pass a role).
        return rescheduleBooking(actorUserId, Role.CLIENT, bookingId, req);
    }

    @Transactional
    public BookingDetailResponse rescheduleBooking(
            UUID actorUserId, Role actorRole, UUID bookingId, RescheduleBookingRequest req) {
        boolean initiatedByProvider = actorRole != Role.CLIENT;
        Booking booking = initiatedByProvider
                ? resolveBookingForProviderReschedule(actorUserId, bookingId)
                : resolveBookingForClientReschedule(actorUserId, bookingId);

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

        // Phase 30.2 (cycle-2 audit finding 1 — lock-order fix): lock the visit HEADER, if this
        // booking is one item of a multi-service visit, BEFORE the client/master advisory locks
        // below — restoring the canonical appointments-before-bookings order that cancelBooking
        // (:1119-1121) already established for the same class of write. getAppointment().getId() is
        // served off the uninitialised @ManyToOne(LAZY) proxy without a statement, so a legacy
        // standalone booking (appointmentId == null) short-circuits this call entirely — ZERO extra
        // statements on that path. The boolean result is intentionally discarded (see this method's
        // own Javadoc, "Phase 30.2"): no phase-2 collapse ever follows a reschedule.
        UUID appointmentId = booking.getAppointment() != null ? booking.getAppointment().getId() : null;
        if (appointmentId != null) {
            appointmentTransitionService.lockAppointmentHeaderBeforeItemReschedule(appointmentId);
        }

        // Freshness re-check (G2, HIGH, cycle-7 audit 2026-08-03; widened to standalone bookings by
        // G4, cycle-7 audit 2026-08-03) — see this method's own "Freshness re-check" / "Unconditional,
        // including standalone bookings" Javadoc paragraphs above. Unconditional: this is the ONLY
        // guard a standalone reschedule has against a concurrent standalone cancel (there is no lock
        // above to short-circuit alongside — the header lock is appointment-only, but this recheck
        // is not). Routed through isStillConfirmed (G4) — see that method's Javadoc for why a
        // standalone-booking concurrency IT needs this seam specifically.
        if (!isStillConfirmed(booking.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service changed concurrently — please retry");
        }

        // Same critical section as doCreateBooking, in the same client-then-master order
        // (deadlock freedom — see BookingRepository.acquireClientAdvisoryLockWithTimeout
        // javadoc). The lock/conflict-check target is the OWNING CLIENT of the booking
        // (booking.getClient()), never actorUserId — on the CLIENT path the two are the same
        // (ownership was already verified above), but on the PROVIDER path actorUserId is the
        // provider, not the client whose calendar is being protected. A guest (LINK) booking has
        // no client account at all (booking.getClient() == null) — there is no calendar to lock
        // or conflict-check, so this step is skipped cleanly for a provider rescheduling a guest
        // booking. acquireClientLock's fused query also sets the transaction-scoped lock_timeout
        // for the whole transaction, bounding the wait on both this lock and the master lock below.
        User owningClient = booking.getClient();
        Integer lockResult;
        if (owningClient != null) {
            acquireClientLock(owningClient.getId());
            // Client-conflict check (excluding this booking's own row) runs BEFORE the
            // master-busy check — and before the master lock is even acquired — same precedence
            // and rationale as create; see doCreateBooking.
            assertNoClientConflictExcluding(owningClient.getId(), newStartsAt, newEndsAt, bookingId);
            // acquireClientLock already fused the transaction-scoped lock_timeout — reuse the
            // plain (untimed-fuse) master lock, same as every other call site that took the
            // client lock first.
            lockResult = bookingRepository.acquireAdvisoryLock(masterId);
        } else {
            // No client lock was taken (guest booking) — the master lock must fuse its own
            // lock_timeout here, or an unbounded wait replaces what should be a fast 409 (mirrors
            // GuestBookingService#persistBooking, which is in exactly this no-client-lock shape).
            lockResult = bookingRepository.acquireAdvisoryLockWithTimeout(masterId);
        }
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

        // Phase 27.3: the outbox payload records who initiated the move, so the drain worker can
        // address the notification to the OTHER party (client-initiated -> notify the provider,
        // unchanged; provider-initiated -> notify the client).
        outboxService.enqueueBookingRescheduled(saved.getId(), initiatedByProvider);
        // Evict the freed old-day slots and the now-occupied new-day slots, plus the
        // provider calendar — after commit, so a parallel reader cannot repopulate stale data.
        registerSlotEviction(masterId, salonIdOf(saved), oldDate, saved.getMasterService().getId());
        if (!oldDate.equals(newStartsAt.toLocalDate())) {
            registerSlotEviction(masterId, salonIdOf(saved), newStartsAt.toLocalDate(), saved.getMasterService().getId());
        }
        evictMasterCalendarAfterCommit(masterId);
        // A rescheduled booking is always CONFIRMED with a FRESH, FUTURE endsAt — validateStartsAt
        // enforces the same lead-time floor reschedule uses for the NEW slot, so
        // BookingClosureRule#isReviewEligible's CONFIRMED-and-elapsed disjunct can never be true
        // here either (mirrors enrichCreated's identical reasoning) — no review-existence query
        // needed on this path; reviewExists is passed false purely as a placeholder, never read
        // for a value that would matter. saved.getClient() is guaranteed non-null on the CLIENT
        // path (the ownership filter only matches account-bound bookings) but CAN be null on the
        // PROVIDER path (a guest/LINK booking) — canReview's hasClient parameter already
        // null-guards this correctly either way. providerCanReviewClient is hardcoded false for
        // the identical reason: it also requires COMPLETED, which a just-rescheduled (CONFIRMED)
        // booking can never be.
        OffsetDateTime now = resolveNow();
        boolean canReview = canReview(saved.getStatus(), saved.getEndsAt(), now, false, saved.getClient() != null);
        return enrichSingle(saved, canReview, false, now);
    }

    /**
     * CLIENT-path ownership + status + elapsed resolution for {@link #rescheduleBooking} —
     * byte-for-byte the pre-27.2 behaviour, extracted unchanged.
     */
    private Booking resolveBookingForClientReschedule(UUID actorUserId, UUID bookingId) {
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
        return booking;
    }

    /**
     * PROVIDER-path authorization + status + elapsed resolution for {@link #rescheduleBooking}
     * (Phase 27.2). Existence + ownership collapse to a single uniform 403 (Finding 8 — existence
     * oracle): a missing id and an existing-but-foreign booking are indistinguishable to the caller,
     * exactly as decline/complete/not-complete now do — a plain full-graph load would surface a 404
     * for a missing id BEFORE the ownership guard, leaking existence to a valid provider. Authorizes
     * via {@link AuthorizationService#enforceCanRescheduleBooking}, which reuses the same
     * provider-authority predicate as decline/complete (owner, assigned admin, or the owning
     * independent master; {@code SALON_MASTER} is never admitted). The CLIENT reschedule path
     * ({@link #resolveBookingForClientReschedule}) is unaffected — it already collapses to 403.
     */
    private Booking resolveBookingForProviderReschedule(UUID actorUserId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        authz.enforceCanRescheduleBooking(actorUserId, booking);

        BookingStatus current = booking.getStatus();
        if (current != BookingStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot reschedule a booking in status %s".formatted(current));
        }
        // Provider-side guard: the CURRENT booking must not have started yet. NOT the same
        // predicate as assertNotElapsedForClient (which compares endsAt) — see this method's
        // class-level javadoc cross-reference on rescheduleBooking for why the two stay separate.
        BookingTemporalGuard.assertCurrentNotElapsedForReschedule(booking.getStartsAt(), clock);
        return booking;
    }

    private BookingResponse doCreateBooking(UUID clientId, String idempotencyKey, CreateBookingRequest request) {
        // Master kind is irrelevant to bookability — SALON_MASTER, INDEPENDENT_MASTER,
        // and SALON_OWNER masters are all bookable when active with working hours + a
        // matching master_services row.
        //
        // SALON-ACTIVE GUARD (2026-08 security re-audit HIGH). SalonService.deactivateSalon
        // sets salons.is_active = false but does NOT cascade to masters.is_active, so every
        // master of a closed salon still passes Master::isActive. Without this predicate a client
        // holding a masterServiceId (from a stale wish-list card, a bookmarked deep link, or a
        // cached catalogue page) could still create a CONFIRMED booking against a salon the owner
        // had closed — the salon's own read surfaces hide it, but the write path admitted it.
        //
        // This is ONE OF THREE booking-create gates, not "the" gate. An earlier revision of this
        // comment claimed sole authority while two sibling create paths were still unguarded; the
        // follow-up audit found both. The complete set of write paths that mint a booking is:
        //   1. here — POST /bookings                (authenticated, single service)
        //   2. AppointmentService#doCreateAppointment — POST /appointments (authenticated visit)
        //   3. GuestBookingService#createGuestBooking — POST /book/{slug}/booking (permitAll),
        //      guarded in the finder itself: MasterRepository#findByBookingSlugWithUser
        // The canonical rule and the full enforcement map live in
        // com.beautica.booking.domain.MasterBookability — change the rule there, not here.
        //
        // The favourites/catalogue/slot predicates only stop a dead CTA being rendered; they
        // cannot stop a hand-crafted request. These three can.
        //
        // `getSalon() == null` MUST pass: an INDEPENDENT_MASTER has no salon and stays bookable.
        // No extra query — findByIdWithUserAndSalon already LEFT JOIN FETCHes the salon.
        //
        // Folded into the same filter chain as isActive so BOTH rejections surface the identical
        // "Master not found or inactive" 404. Splitting it into a distinct message/status would
        // hand an unauthenticated caller an oracle distinguishing "this master does not exist"
        // from "this master's salon was closed" — a business-state leak about another party.
        Master master = masterRepository.findByIdWithUserAndSalon(request.masterId())
                .filter(MasterBookability::isBookable)
                .orElseThrow(() -> new NotFoundException("Master not found or inactive"));

        // `.filter(isActive)` (Phase 249 QA gate, 2026-08-07). findByMasterIdAndIdWithGraph carries no
        // is_active predicate, and this was the ONLY one of the four master-service resolution paths
        // that did not apply one on top of it:
        //   VisitPlanner#planChainedItems              (POST /appointments)        — filters
        //   GuestBookingService#createGuestBooking     (POST /book/{slug}/booking) — filters
        //   SlotCalculationService#loadBookableAssignment (GET .../slots)          — filters
        //   here                                       (POST /bookings)           — did NOT
        // So a client holding a masterServiceId for a service the provider had unassigned from their
        // menu — a stale BEAUTY WISH LIST card is the documented way to hold one (Phase 248) — could
        // still create a CONFIRMED booking for it, while the identical guest request 404'd. Every
        // read surface that filters `master_services.is_active` justifies itself with "the booking
        // path then rejects it"; until this filter existed, that claim was false for POST /bookings.
        // Same 404 message as the missing-row case: splitting them would hand a caller an oracle
        // distinguishing "no such service" from "this provider retired that service".
        // Pinned by FavoriteServiceIT#should_return404_when_bookingAFavoritedServiceThatWasDeactivated.
        MasterServiceAssignment msa = masterServiceRepository
                .findByMasterIdAndIdWithGraph(request.masterId(), request.masterServiceId())
                .filter(MasterServiceAssignment::isActive)
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
        return BookingResponse.from(saved, resolveNow());
    }

    /**
     * Thin, package-private, {@code @SpyBean}-able wrapper around
     * {@link BookingRepository#existsConfirmedById} — the freshness re-check
     * {@link #cancelBooking(UUID, Booking, CancelBookingRequest)} and {@link #rescheduleBooking}
     * run immediately before their own terminal save (G2/G4, cycle-7 audit 2026-08-03), and which
     * {@link #declineBookingCore}, {@link #completeBooking} and {@link #notCompleteBooking} now
     * run too (G5 — same defect class, closing the reverse direction of the race G4 only
     * half-covered: G4 protected {@code cancelBooking} when it lands SECOND, but nothing protected
     * these three provider transitions from unconditionally overwriting an already-CANCELLED row
     * when THEY land second instead).
     *
     * <p>Mirrors the exact precedent {@code AppointmentTransitionService
     * #lockAppointmentHeaderBeforeItemDecline} sets for the identical problem: Mockito cannot
     * {@code callRealMethod()} on a Spring Data JPA repository's dynamically-proxied interface
     * method (confirmed failure mode — {@code MockitoException: Cannot call abstract real method
     * on java object!} — see that method's Javadoc and
     * {@code AppointmentCrossPathTransitionConcurrencyIT}'s class Javadoc for the transcript), so a
     * bare forwarding method on the concrete, CGLIB-spyable {@code BookingService} bean is the seam
     * a concurrency test needs to pause ONE racer mid-transaction — after it has loaded the row but
     * before this recheck runs — while the OTHER racer commits. This is the ONLY such seam the
     * standalone-booking path has: unlike the appointment-child paths, there is no lock call here to
     * hang a rendezvous off instead (that absence is exactly what G4 is about — see
     * {@link #rescheduleBooking}'s "Unconditional, including standalone bookings" Javadoc paragraph).
     *
     * <p>{@code cancelBooking}/{@code rescheduleBooking} had no behavioural change when they were
     * routed through this wrapper (both previously called {@link
     * BookingRepository#existsConfirmedById} directly) — but {@code declineBookingCore}/{@code
     * completeBooking}/{@code notCompleteBooking} calling it (G5) IS a behavioural change: those
     * three previously made no freshness check at all.
     */
    boolean isStillConfirmed(UUID bookingId) {
        return bookingRepository.existsConfirmedById(bookingId);
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
     *
     * <p><b>Perf audit F3 (cross-batch).</b> Delegates to {@link BookingSlotAvailabilityGuard} — the
     * single shared implementation this method and {@code AppointmentTransitionService
     * #assertItemStartsOnAvailableSlot} both call, replacing what used to be two byte-for-byte
     * duplicate method bodies. See that class's Javadoc for why it is a static utility rather than a
     * shared bean (avoids a circular dependency with {@code AppointmentTransitionService}).
     */
    private void assertStartsOnAvailableSlot(UUID masterId, UUID masterServiceId, OffsetDateTime startsAt) {
        BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, startsAt);
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
     * Rejects a single-booking transition on a booking that is one item of a multi-service
     * visit (BE-3/BE-4). Such a booking carries a non-null {@link Booking#getAppointment()}
     * FK; its status is owned by the {@link com.beautica.booking.entity.Appointment} header,
     * which moves the header <em>and every sibling item</em> in lockstep only through the
     * {@code PATCH /appointments/{id}/...} endpoints (all-or-nothing). Transitioning a single
     * child through {@code PATCH /bookings/{id}/decline|complete|not-complete} would desync the
     * visit header from its OTHER siblings, so it is refused here with a 409.
     *
     * <p><b>Track 27.x narrowing.</b> {@link #cancelBooking} — the fourth, CLIENT-initiated
     * transition — deliberately stopped calling this guard: a client may now cancel exactly one
     * leg of a visit (mobile renders one card per service), and the two-phase
     * {@code AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel} /
     * {@code #collapseAppointmentHeaderAfterClientItemCancel} pair keeps the header in sync around
     * this method's own child-row write instead of refusing the call outright. The three
     * PROVIDER-initiated transitions below are UNCHANGED —
     * this remains their single choke point, so no controller/API path can bypass it for them. It
     * is deliberately NOT reachable from the appointment-level lockstep updates (those legitimately
     * set item status while {@code appointment} is non-null; they never enter these
     * {@code BookingService} entry points).
     *
     * <p><b>Legacy single-service bookings</b> have {@code appointment_id = null} and pass
     * through untouched — their transition behaviour is byte-for-byte unchanged. The FK is a
     * {@code @ManyToOne(LAZY)} whose id is on the {@code bookings} row itself, so the null vs
     * non-null check needs no extra query (a null FK yields {@code null}, a non-null FK yields
     * an un-initialised proxy — neither hits the DB).
     */
    private void assertNotAppointmentChild(Booking booking) {
        if (booking.getAppointment() != null) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "This booking is part of a multi-service visit; use /appointments/{id} to change it");
        }
    }

    /**
     * Evicts only the cache entries that belong to the given master from the
     * {@code master-calendar} cache, running after the current transaction commits.
     *
     * <p>{@code MasterService.getMasterCalendar} declares an explicit SpEL
     * {@code key = "{#masterId, #from, #to, #pageable.pageNumber, #pageable.pageSize}"}. An explicit
     * {@code key} is never wrapped in a {@code SimpleKey} — that type only comes from the default
     * {@code SimpleKeyGenerator} — so the runtime key is the {@link java.util.List} the {@code {...}}
     * inline-list literal evaluates to, and its first element is the {@code masterId}. Matching is
     * delegated to {@link com.beautica.common.cache.MasterCachePrefixEvictor#evictByKeyPrefixNow}.
     *
     * <p>This method previously carried its own copy of the predicate that tested
     * {@code instanceof SimpleKey} against {@code toString()}; that never matched the real keys, so the
     * eviction was a silent no-op and a stale calendar page survived a booking status change for the
     * full TTL. Scoped to one master, never a blanket {@code cache.clear()} (thundering herd).
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
        cachePrefixEvictor.evictByKeyPrefixNow(masterId, "master-calendar");
    }

    /**
     * Evicts {@code revenue-dashboard} entries for the given actor after commit.
     *
     * <p>Uses per-actor prefix eviction, avoiding a blanket {@code cache.clear()} that would evict all
     * actors' dashboard entries on every booking status transition (Anti-Bug §F rule 6 / PERF-MEDIUM-5).
     *
     * <p>{@code DashboardService.getRevenueSummary} is keyed
     * {@code "{#actorId, #from, #to, #filterMasterId, #serviceDefId, #salonIdFilter?.orElse(null)}"} —
     * an explicit SpEL inline list, so the runtime key is a {@link java.util.List} whose first element
     * is the {@code actorId}, not a {@code SimpleKey}. The previous local copy of the predicate tested
     * {@code instanceof SimpleKey} and therefore never evicted anything; matching now goes through
     * {@link com.beautica.common.cache.MasterCachePrefixEvictor#evictByKeyPrefixNow}, which matches on
     * key POSITION (first element) and so applies unchanged to an actor-keyed cache.
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
        cachePrefixEvictor.evictByKeyPrefixNow(actorId, "revenue-dashboard");
    }
}
