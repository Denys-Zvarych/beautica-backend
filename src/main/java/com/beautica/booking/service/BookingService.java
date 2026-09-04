package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.domain.MasterBookability;
import com.beautica.booking.dto.AppointmentProviderNoteRequest;
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
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.event.BookingCompletedEvent;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingSpecifications;
import com.beautica.booking.repository.ClientBookingDetailProjection;
import com.beautica.booking.repository.SalonClosureBookingCandidate;
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
import com.beautica.notification.entity.OutboxEventType;
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
import org.springframework.transaction.annotation.Propagation;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
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
     * (extends track 27.x / Phase 27.5) on the single-booking DETAIL path ({@link #getBooking}).
     * The {@code GET /bookings/me} provider listing computes the same flag through the same
     * conjunction ({@link #providerCanReviewClient}) but feeds it batched, page-scoped inputs —
     * see {@link #loadProviderReviewBatch}. {@link #enrichCreated} still passes a literal
     * {@code false}, and that one remains sound for the reason its own javadoc gives (a
     * just-created booking can never be {@code COMPLETED}, the only status
     * {@link BookingClosureRule#isProviderReviewEligible} admits).
     *
     * <p>Mirrors the EXACT conditions {@code ClientReviewService.create} checks before persisting
     * a {@code ClientReview}, so this can never promise a CTA the write endpoint would then
     * reject: (1) the actor has provider review-authority over this booking, via
     * {@link AuthorizationService#hasProviderAuthorityOverBooking} — the same predicate
     * {@code enforceCanReviewClient} throws on, reused non-throwing here rather than
     * re-derived; (2) {@link BookingClosureRule#isProviderReviewEligible} — {@code status ==
     * COMPLETED}, STRICTLY, unlike the client-side {@code canReview} flag's {@link
     * BookingClosureRule#isReviewEligible}, which also admits an elapsed-but-unclosed {@code
     * CONFIRMED} booking. The two directions are deliberately NOT the same predicate here — the
     * provider controls their own closing action, so a rating must follow it rather than
     * substitute for it; see {@link BookingClosureRule#isProviderReviewEligible}'s javadoc; (3)
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
     *            Kept on THIS method's signature even though its own body no longer reads it: the
     *            audit-fix that dropped {@code providerCanReviewClient}'s dead {@code endsAt}/
     *            {@code now} params (they stopped being consulted when the predicate swapped to
     *            {@link BookingClosureRule#isProviderReviewEligible}) is scoped to that private
     *            static method's own two call sites. Widening it into this method's signature too
     *            would ripple into its single call site in {@link #getBooking} for no behavioural
     *            gain — {@code now} is resolved there once for canReview/awaitingClosure regardless
     *            of what this method does with it, so leave it here rather than chase the ripple.
     */
    private boolean computeProviderCanReviewClient(UUID actorUserId, Booking booking, OffsetDateTime now) {
        boolean hasProviderAuthority = !authz.isOwningClientViewer(actorUserId, booking)
                && authz.hasProviderAuthorityOverBooking(actorUserId, booking);
        return providerCanReviewClient(
                hasProviderAuthority, booking.getStatus(),
                booking.getClient() != null,
                () -> clientReviewRepository.existsByBookingId(booking.getId()));
    }

    /**
     * {@code providerCanReviewClient = provider authority over the booking && status == COMPLETED
     * && a registered client exists to be reviewed && no {@code ClientReview} already exists}.
     * The provider&rarr;client mirror of {@link #canReview} in SHAPE only — the STATUS half is
     * <b>NOT</b> the same predicate. This delegates to {@link
     * BookingClosureRule#isProviderReviewEligible} (strictly {@code status == COMPLETED}), never
     * {@link BookingClosureRule#isReviewEligible} (which also admits an elapsed-but-unclosed
     * {@code CONFIRMED} booking) — see {@link BookingClosureRule#isProviderReviewEligible}'s
     * javadoc for why the provider direction does not get that widening: the provider controls
     * their own closing action, so a rating must follow it rather than substitute for it.
     *
     * <p><b>This is the single definition shared by BOTH surfaces that expose the flag</b>:
     * {@link #computeProviderCanReviewClient} (the {@code GET /bookings/&#123;id&#125;} detail
     * path, which supplies authority from {@code AuthorizationService}'s per-row entity predicate
     * and review-existence from a single {@code existsByBookingId}) and {@link
     * #listProviderBookings} (the {@code GET /bookings/me} provider listing, which supplies the
     * same two inputs from the page-scoped batched lookups in {@link #loadProviderReviewBatch}).
     * Conjuncts and their ORDER are therefore identical on both, which is the point: before this
     * method existed the listing hardcoded {@code false} and the two surfaces disagreed on every
     * completed booking — the archive CTA stayed lit after the provider had already left feedback,
     * because the mobile card could not trust a flag that was a constant. Do not re-derive this
     * conjunction at either call site.
     *
     * <p><b>The {@code hasClient} conjunct is load-bearing on the DETAIL path only, and is
     * deliberately retained as defence-in-depth on the listing path</b> (QA GAP 3, 2026-08-20 —
     * documented, not "fixed"). {@link #loadProviderReviewBatch} already drops every
     * {@code b.getClient() == null} row from its candidate set at {@code loadProviderReviewBatch}'s
     * first statement, so such a row can never enter {@code withAuthority} and arrives here with
     * {@code hasProviderAuthority == false} — the conjunction is already {@code false} one term
     * earlier and this term is unreachable for it. {@link #computeProviderCanReviewClient} has no
     * such pre-filter: it derives authority from the actor's relationship to the MASTER/SALON, which
     * a guest or STAFF walk-in booking satisfies exactly as well as an account-bound one, so on
     * {@code GET /bookings/&#123;id&#125;} this term is the ONLY thing standing between a COMPLETED
     * walk-in and a {@code true} the {@code POST /client-reviews} write endpoint would then reject
     * (there is no {@code users} row to attach a {@code ClientReview} to).
     *
     * <p>The practical consequence, and the reason this is written down: a regression that deletes
     * this single conjunct is INVISIBLE to every listing test, because the batch pre-filter makes
     * the removal a no-op there. It was measured — mutation M6 of the Phase 22.5 read-path pass
     * dropped this term and killed no test in {@code StaffBookingReadPathIT}'s listing suite.
     * {@code StaffBookingReadPathIT.BookingDetail} exists to close that hole and is the suite that
     * fails when this term goes; keep a detail-path assertion on the flag alive.
     *
     * <p>{@code clientReviewExists} is a {@link BooleanSupplier}, not a {@code boolean}, so the
     * detail path keeps paying its {@code client_reviews} probe ONLY when the cheap in-memory
     * conjuncts have not already decided the answer — the short-circuit that keeps
     * {@code GET /bookings/&#123;id&#125;} at its pinned statement count
     * ({@code BookingPriceRangeContractIT#OWNER_DETAIL_STATEMENTS_ALIGNED}). An eagerly-evaluated
     * argument would fire that query on every single detail read, including the future-dated
     * CONFIRMED bookings that are the common case.
     */
    private static boolean providerCanReviewClient(
            boolean hasProviderAuthority, BookingStatus status, boolean hasClient,
            BooleanSupplier clientReviewExists) {
        return hasProviderAuthority
                && BookingClosureRule.isProviderReviewEligible(status)
                && hasClient
                && !clientReviewExists.getAsBoolean();
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
        // V157 / phase 294 D3 — NULLABLE on a historical booking whose master was detached (staff
        // account hard-deleted). Only the independent-master branch reads it, and a null locality
        // degrades to "no city/district label", which is exactly what the resolver already does for
        // a master who never set one.
        User masterUser = booking.getMaster().getUser();
        UUID cityId = salon != null ? salon.getCityId()
                : (masterUser != null ? masterUser.getCityId() : null);
        UUID districtId = salon != null ? salon.getDistrictId()
                : (masterUser != null ? masterUser.getDistrictId() : null);

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
        if (salon != null) {
            return salon.getCityId();
        }
        // V157 / phase 294 D3 — a detached master (staff account hard-deleted) has no user row and
        // therefore no locality. Null degrades to "no label", never an NPE on the client's own list.
        User masterUser = booking.getMaster().getUser();
        return masterUser != null ? masterUser.getCityId() : null;
    }

    /** Discovery district id: same rule and same source as {@link #discoveryCityId}. */
    private static UUID discoveryDistrictId(Booking booking) {
        Salon salon = booking.getSalon();
        if (salon != null) {
            return salon.getDistrictId();
        }
        // Same rule and same null-degradation as discoveryCityId (V157 / phase 294 D3).
        User masterUser = booking.getMaster().getUser();
        return masterUser != null ? masterUser.getDistrictId() : null;
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
                p.salonId(),
                // Derived from the SAME p.categoryName() scalar — the projection's sd.category
                // select — via the shared helper so this path and the entity path can never
                // disagree (BookingDetailContractIT's reflective parity loop). No second query.
                BookingDetailResponse.categoryKeyOrNull(p.categoryName()));
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
     * graph-hydrate pattern (Fix H1), then add only bounded, page-scoped follow-ups — never a
     * per-row lookup: {@code ReviewRepository#findReviewedBookingIds} (the client&rarr;provider
     * direction, backing {@code canReview}), the two-query label resolution, and at most two more
     * for {@code providerCanReviewClient} (see {@link #loadProviderReviewBatch}, which skips both
     * whenever no row on the page can qualify). The count is flat in page size on every branch.
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
     * <p><b>Independent of the caller's ad-hoc filters, but NOT of the screen's default.</b> The
     * design computes {@code _bookingDays} from the screen's own booking list rather than the
     * filtered/sorted view, so the dots keep showing where bookings are even while a user-applied
     * status/date/service filter narrows the list below. This method therefore takes no {@code
     * status} / {@code serviceId} parameter — do not add one "for symmetry" with {@link
     * #getMyBookings}.
     *
     * <p>That independence stops at the screen's <em>default</em> visibility rule, because a dot
     * must never point at a day the destination list renders empty. On the master's «Мої записи»
     * screen {@code CANCELLED} and {@code DECLINED} are hidden by default (locked product
     * decision, user, 2026-08-13 — both render the identical «Скасовано» badge since the
     * who-cancelled distinction was collapsed on 2026-07-15), so the master-scoped query excludes
     * them and a day whose only bookings are cancelled/declined is not dotted. {@code
     * NOT_COMPLETED} stays dotted — it stays visible in that list as the master's own no-show
     * record and feeds the two-sided client rating. The SALON_OWNER and CLIENT branches below are
     * unchanged and still carry no status predicate; see {@code BookingRepository}'s block comment
     * above the three queries for the full rationale.
     *
     * <p><b>{@code from}/{@code to} are required</b> (unlike {@code getMyBookings}'s optional
     * range) and capped at 366 days via {@link ScheduleDateMath#assertSpanWithinMax} — an
     * unbounded default would scan the caller's entire booking history. Converted to the same
     * half-open {@code [from, toExclusive)} Kyiv-zoned instant range {@code getMyBookings} uses,
     * so a dot returned here and {@code GET /bookings/me?from=D&to=D} for the same date D can
     * never diverge on timezone or boundary handling. For the master scope they agree on the row
     * set too once the caller passes the statuses its screen actually shows
     * ({@code &status=CONFIRMED,COMPLETED,NOT_COMPLETED}) — literally the same allow-list the query
     * itself names, so client and query agree by textual identity rather than by set complement,
     * and a hypothetical sixth status is hidden by BOTH rather than dotted by one and dropped by
     * the other. {@code BookingRepository}'s block comment explains why the allow-list form is also
     * what makes the query match its partial index. {@code getMyBookings}'s own "no
     * {@code status} param ⇒ no filter" contract is deliberately left untouched for every other
     * caller.
     *
     * <p>Role scope mirrors {@link #getMyBookings}: {@code SALON_MASTER}/{@code
     * INDEPENDENT_MASTER} see their own bookings (scoped by {@code masterId}, resolved from the
     * JWT principal — never a request parameter), {@code SALON_OWNER} sees bookings across their
     * owned active salons, {@code CLIENT} sees their own bookings, and {@code SALON_ADMIN} is
     * forbidden — consistent with {@code getMyBookings} rejecting that role for the same reason
     * (they manage staff/services, not bookings).
     *
     * <p>Aggregation happens in Postgres ({@code SELECT DISTINCT} on a timezone-converted date
     * expression) — never by loading the caller's booking history into heap and reducing with
     * {@code .map(...).distinct()} in Java. The 366-day span cap bounds the rows RETURNED (at most
     * 367, both bounds inclusive); it does <em>not</em> bound the rows SCANNED, which is the
     * caller's entire booking volume inside that window — thousands of rows for a busy master, and
     * the reason the master-scoped query's status predicate is written to match a partial index.
     * See {@code BookingRepository}'s block comment for that measurement.
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
     * Phase 23.4 — {@code GET /bookings/salon/{salonId}}: a single-salon, paginated booking list
     * for {@code SALON_OWNER}/{@code SALON_ADMIN}, backing the mobile salon "Розклад" tab.
     *
     * <p><b>Why this is a dedicated endpoint, not a {@code GET /bookings/me} widening.</b> {@code
     * GET /bookings/me}'s {@code SALON_OWNER} arm aggregates across EVERY owned salon with no
     * per-salon filter (see {@link #listProviderBookings}), and rejects {@code SALON_ADMIN}
     * outright (same rejection this method's sibling {@link #getMyBookings}/{@link
     * #getMyBookedDays}/{@link #getUnclosedCount} carry, and which stays UNTOUCHED here — this
     * method does not relax any of those three guards). Neither shape fits a single-salon,
     * admin-inclusive, master-filterable list, so this is a new query family rather than a branch
     * bolted onto the existing one.
     *
     * <p><b>Authorization lives ENTIRELY at the controller boundary</b> —
     * {@code @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and
     * @authz.canManageSalon(authentication, #salonId)")}, the exact SpEL {@code
     * SalonController}/{@code ServiceController} already use for the same owner-or-admin-of-THIS-
     * salon check (Anti-Bug §D: a GET is a read, so one {@code can*} DB lookup at the gate is the
     * canonical placement; a role-only controller check plus a second, separate ownership query
     * here would be the "duplicate the same check on both layers" anti-pattern §D forbids). No
     * {@code SalonService} injection was needed — {@code AuthorizationService#canManageSalon} was
     * already the shared helper backing every sibling salon-management endpoint, so reusing it
     * here adds no new bean edge and cannot create the {@code BookingService ↔ SalonService}
     * circular dependency a naive "inject SalonService and assert ownership" approach would risk
     * ({@code AuthorizationService} was already a {@code BookingService} constructor dependency
     * before this phase).
     *
     * <p><b>Scope predicate is {@code booking.salon.id}</b> ({@link
     * BookingSpecifications#bookingSalonIdEquals}), never {@code master.salon.id} — see that
     * method's javadoc for why: this is "which bookings happened AT this salon" (a historical,
     * per-booking fact), not "which of my currently-owned salons" ({@link
     * BookingSpecifications#salonIdIn}'s multi-salon aggregate shape, keyed off the master's LIVE
     * affiliation instead).
     *
     * <p><b>{@code providerCanReviewClient} authority is computed per row</b> via {@link
     * AuthorizationService#hasProviderAuthorityOverBooking(UUID, Booking)} — the same public,
     * entity-based predicate {@link #getBooking} already uses for a single row — rather than
     * {@link #loadProviderReviewBatch}'s page-batched {@code
     * AuthorizationService#filterBookingIdsWithProviderAuthority}, which explicitly THROWS {@code
     * IllegalArgumentException} for {@code SALON_ADMIN} ("add the assigned-salon arm before
     * routing admins to this path" — this endpoint is the first caller that would need it, and
     * extending that heavily-audited, {@code GET /bookings/me}-shared batch kernel was judged
     * riskier than the bounded per-row cost paid here). The per-row call is gated behind the same
     * cheap in-memory {@code isReviewCandidate} check {@link #loadProviderReviewBatch} uses as its
     * own cost gate (client present AND {@link BookingClosureRule#isProviderReviewEligible}), so
     * it only runs for rows that could possibly flip the flag — bounded by page size (capped
     * globally at 100 — Anti-Bug §J), never by the salon's total booking volume.
     *
     * <p><b>Index coverage (Phase 23.4 audit fix, Finding 2 — corrects a stale citation).</b>
     * {@code idx_bookings_salon_status_starts_at} was originally added by V22, but V113 rebuilt it
     * with a narrower partial predicate — {@code WHERE status IN ('CONFIRMED','COMPLETED')} — so
     * it covers this method's {@code ?status=} filter only for those two values; a {@code status}
     * of {@code CANCELLED}/{@code DECLINED}/{@code NOT_COMPLETED} (or no status at all) falls back
     * to the unfiltered {@code idx_bookings_salon_starts_at} (V19). Neither index carries {@code
     * master_id}, so the {@code masterId} filter above is served by a dedicated composite index,
     * {@code idx_bookings_salon_master_starts_at} (V148, status-agnostic — see that migration for
     * why it cannot reuse V22/V113's partial predicate).
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingDetailResponse> getSalonBookings(
            UUID actorUserId, UUID salonId, UUID masterId, BookingStatus status,
            LocalDate from, LocalDate to, Pageable pageable) {
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

        Set<BookingStatus> statuses = status == null ? null : EnumSet.of(status);
        OffsetDateTime now = resolveNow();
        Pageable normalizedPageable = normalizeBookingSort(pageable);

        Page<UUID> idPage = bookingRepository.findIdsBySalonIdFiltered(
                salonId, masterId, statuses, fromTs, toExclusive, normalizedPageable);
        if (idPage.isEmpty()) {
            return PageResponse.of(List.of(), idPage.getNumber(), idPage.getSize(),
                    idPage.getTotalElements(), idPage.getTotalPages());
        }

        List<Booking> hydrated = bookingRepository.findAllByIdsWithGraph(idPage.getContent());
        // Client -> provider review-existence, batched for the whole page — same as
        // listProviderBookings, no role dependency (unlike the provider review batch below).
        Set<UUID> reviewed = new HashSet<>(reviewRepository.findReviewedBookingIds(idPage.getContent()));

        // Provider -> client review-existence batch, restricted to rows that could possibly
        // qualify (see this method's javadoc for why authority itself is NOT batched here).
        List<UUID> reviewCandidateIds = hydrated.stream()
                .filter(b -> b.getClient() != null && BookingClosureRule.isProviderReviewEligible(b.getStatus()))
                .map(Booking::getId)
                .toList();
        Set<UUID> alreadyReviewedByProvider = reviewCandidateIds.isEmpty()
                ? Set.of()
                : Set.copyOf(clientReviewRepository.findReviewedBookingIds(reviewCandidateIds));

        DiscoveryLabels labels = resolveBookingLabels(hydrated);

        Map<UUID, Booking> byId = hydrated.stream().collect(Collectors.toMap(Booking::getId, Function.identity()));
        List<BookingDetailResponse> ordered = idPage.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(b -> {
                    UUID cityId = discoveryCityId(b);
                    UUID districtId = discoveryDistrictId(b);
                    boolean canReview = canReview(
                            b.getStatus(), b.getEndsAt(), now, reviewed.contains(b.getId()), b.getClient() != null);
                    boolean isReviewCandidate = b.getClient() != null
                            && BookingClosureRule.isProviderReviewEligible(b.getStatus());
                    boolean hasProviderAuthority = isReviewCandidate
                            && authz.hasProviderAuthorityOverBooking(actorUserId, b);
                    boolean providerCanReviewClient = providerCanReviewClient(
                            hasProviderAuthority, b.getStatus(), b.getClient() != null,
                            () -> alreadyReviewedByProvider.contains(b.getId()));
                    return BookingDetailResponse.from(
                            b, canReview, providerCanReviewClient,
                            labels.cityLabel(cityId), labels.districtLabel(districtId), now);
                })
                .toList();

        return PageResponse.of(ordered, idPage.getNumber(), idPage.getSize(),
                idPage.getTotalElements(), idPage.getTotalPages());
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
     * Provider path — ID-page + graph hydrate (Fix H1), then the batched review-existence
     * queries (one per review DIRECTION — see {@link #loadProviderReviewBatch} for the
     * provider&rarr;client one and its salon-ownership companion) and the two-query label
     * resolution for the whole page. Every follow-up is bounded by the page and flat in its size;
     * nothing here is per row.
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
        // Same batching discipline for the provider->client direction: at most two more bounded
        // statements for the WHOLE page, never a per-row probe. See loadProviderReviewBatch.
        ProviderReviewBatch providerReview = loadProviderReviewBatch(role, actorUserId, hydrated);
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
                    // Real per-row value, through the SAME conjunction GET /bookings/{id} uses
                    // (providerCanReviewClient below) — only its two DB-bound inputs come from the
                    // page-scoped batches instead of per-row probes. It was hardcoded false here
                    // until the archive-CTA fix; a constant on the wire is worse than an absent
                    // field, because a client that maps it faithfully can never clear the CTA.
                    boolean providerCanReviewClient = providerCanReviewClient(
                            providerReview.withAuthority().contains(b.getId()),
                            b.getStatus(), b.getClient() != null,
                            () -> providerReview.alreadyReviewed().contains(b.getId()));
                    return BookingDetailResponse.from(
                            b, canReview, providerCanReviewClient,
                            labels.cityLabel(cityId), labels.districtLabel(districtId), now);
                })
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    /**
     * The two DB-bound inputs {@link #providerCanReviewClient} needs, resolved ONCE for a whole
     * provider page.
     *
     * @param withAuthority   ids of the page's rows the actor holds provider authority over
     * @param alreadyReviewed ids among those that already carry a {@code ClientReview}
     */
    private record ProviderReviewBatch(Set<UUID> withAuthority, Set<UUID> alreadyReviewed) {
        static final ProviderReviewBatch EMPTY = new ProviderReviewBatch(Set.of(), Set.of());
    }

    /**
     * Batched counterpart of the two per-row lookups {@link #computeProviderCanReviewClient} makes
     * on the single-booking detail path, for the {@code GET /bookings/me} provider listing.
     *
     * <p><b>Adds at most TWO statements per page request, and neither scales with page size:</b>
     * one {@code SalonRepository#findIdsByIdInAndOwnerId} over the page's DE-DUPLICATED live-salon
     * ids (inside {@code AuthorizationService#filterBookingIdsWithProviderAuthority}, skipped
     * outright when every master on the page is independent) and one
     * {@code ClientReviewRepository#findReviewedBookingIds} over a single bounded {@code IN} list.
     * The alternative — calling {@link #computeProviderCanReviewClient} per row — is a double N+1:
     * an authority query AND a {@code client_reviews} probe for every row, plus a live-salon proxy
     * initialisation per distinct salon.
     *
     * <p><b>The two early returns are pure cost gates and cannot change any row's flag.</b> They
     * mirror, in the same order, the conjuncts {@link #providerCanReviewClient} evaluates before it
     * would consult either batch: a row that is not review-eligible or has no registered client is
     * {@code false} by that method regardless of what these lookups would have said, and so is a
     * row absent from {@code withAuthority}. Narrowing the ids handed to each query is therefore
     * semantics-preserving by construction — it is the page-level form of the identical
     * short-circuit {@link #getBooking} applies to its own {@code existsByBookingId} probe. The
     * eligibility pre-filter uses {@link BookingClosureRule#isProviderReviewEligible} (STATUS only,
     * strictly {@code status == COMPLETED}) — NOT {@link BookingClosureRule#isReviewEligible} — so
     * this batch and {@link #providerCanReviewClient}'s own conjunct can never disagree about which
     * candidates are worth an authority/review-existence lookup. No {@code now} parameter is needed
     * here any more: unlike the client-side eligibility rule, the provider one has no TIME half.
     *
     * <p><b>{@code AuthorizationService#isOwningClientViewer} is deliberately not consulted here</b>,
     * though {@link #computeProviderCanReviewClient} leads with it. That gate is a COST gate on the
     * detail path (it keeps an owning-CLIENT viewer out of the provider-authority walk) and is
     * documented there as unable to change the flag's value. On this path it cannot even apply: the
     * role dispatch in {@link #getMyBookings} routes {@code Role.CLIENT} to
     * {@code listClientBookings}, so no caller reaching here is a client, let alone the booking's
     * own. Adding it would buy nothing and would introduce a {@code SecurityContext} read on a path
     * that is called directly, without one, by {@code BookingPriceRangeContractIT}.
     *
     * <p><b>{@code role} is passed through purely as an assertion.</b>
     * {@code AuthorizationService#filterBookingIdsWithProviderAuthority} has no {@code SALON_ADMIN}
     * arm and now throws rather than silently answering "no authority" for one; the role dispatch in
     * {@link #listProviderBookings} already rejects {@code SALON_ADMIN} with a
     * {@code ForbiddenException} before any row is hydrated, so that throw is unreachable from here.
     * It exists so that relaxing the dispatch fails loudly instead of quietly under-reporting an
     * assigned admin's authority.
     */
    private ProviderReviewBatch loadProviderReviewBatch(
            Role role, UUID actorUserId, List<Booking> page) {
        // This b.getClient() != null pre-filter is what makes providerCanReviewClient's own
        // hasClient conjunct unreachable on THIS path — a guest/STAFF row never reaches
        // withAuthority, so it is already false by the authority term. That redundancy is retained
        // on purpose; see providerCanReviewClient's javadoc for why (the detail path has no such
        // pre-filter, so the conjunct is load-bearing there) and for the mutation that proved a
        // listing test can never detect its removal.
        List<Booking> candidates = page.stream()
                .filter(b -> b.getClient() != null
                        && BookingClosureRule.isProviderReviewEligible(b.getStatus()))
                .toList();
        if (candidates.isEmpty()) {
            return ProviderReviewBatch.EMPTY;
        }
        Set<UUID> withAuthority =
                authz.filterBookingIdsWithProviderAuthority(role, actorUserId, candidates);
        if (withAuthority.isEmpty()) {
            return ProviderReviewBatch.EMPTY;
        }
        return new ProviderReviewBatch(withAuthority,
                Set.copyOf(clientReviewRepository.findReviewedBookingIds(List.copyOf(withAuthority))));
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
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved));
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
     * <p>Package-private: both callers live in this same package —
     * {@code ScheduleOverrideConflictService} (a different class in this package), and, as of
     * Phase 269/293, {@link #declineFutureConfirmedBookingsForSalonClosure} below (same class, so
     * no cross-package visibility change was needed to add it). {@link #declineBooking} itself is
     * completely unchanged — same signature, same eviction, same notification, same tests — this
     * is purely an additive extraction of the shared core its callers use.
     */
    Booking declineBookingForBatch(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        return declineBookingCore(actorUserId, bookingId, req);
    }

    /**
     * Declines every future {@code CONFIRMED} booking at a deleted salon and enqueues one
     * {@code SALON_CLOSED} notification per affected VISIT (Phase 269/293 — D12; supersedes 269's
     * per-booking wording). Called by {@code SalonService#deactivateSalon} as the cascade step
     * that follows the salon flag flip and staff deactivation.
     *
     * <p>Thin entry point (Phase 298 D4): the ownership self-assertion and the salon-scoped
     * candidate scan are the only things specific to salon closure; everything from the grouping
     * step down is the shared {@link #declineFutureConfirmed} body, reused verbatim by the
     * master-removal sibling {@link #declineFutureConfirmedBookingsForMasterRemoval}. The rest of
     * this javadoc describes that shared body and stays attached here as the canonical
     * description of the whole mechanism — see {@link #declineFutureConfirmed}'s own (short)
     * javadoc for what is entry-point-specific.
     *
     * <p><b>D3 — the boundary is {@code startsAt > now}, read ONCE.</b> {@code now} is resolved a
     * single time via {@link #resolveNow()} and reused for the whole scan — never re-read per row
     * — so a `COMPLETED`, `CANCELLED`, `DECLINED`, `NOT_COMPLETED` or past-dated `CONFIRMED` row is
     * never touched.
     *
     * <p><b>D4 — every transition stays per-BOOKING; REUSE, never fork.</b> Candidates are grouped
     * by {@link SalonClosureBookingCandidate#visitKey()} (= {@code coalesce(appointmentId, id)}).
     * A standalone booking (no appointment) is validated one row at a time by
     * {@link #assertBatchDeclinePreconditions} and then declined, together with every other
     * validated standalone booking in the cascade, by ONE call to
     * {@link #declineConfirmedBookingsAtomic} — an atomic bulk conditional {@code UPDATE}, see
     * that method's and {@link BookingRepository#declineConfirmedBulk}'s own Javadoc (perf
     * re-audit, 2026-09, Finding A) for why this leg no longer reuses
     * {@code declineBookingCore}'s load-mutate-save shape, and why one bulk statement is exactly
     * as race-safe as the per-row atomic {@code UPDATE} it replaces. An appointment-child group is
     * still
     * declined through {@code AppointmentTransitionService#declineAppointmentItems} — the exact
     * method {@code ScheduleOverrideConflictService} already reuses for grouped multi-service
     * conflicts, batched over every conflicting sibling of one visit in a single call rather than
     * one per sibling; that leg is DELIBERATELY UNCHANGED by the 2026-09 re-audit (it already runs
     * O(1) queries per visit under its own header lock — see its Javadoc's "Batched freshness
     * re-check" section). This method only decides WHICH bookings need declining and WHICH of
     * those two existing paths each visit takes — mirroring
     * {@code ScheduleOverrideConflictService#declineConflicts} exactly.
     *
     * <p><b>D5 — {@code providerComment} stays {@code null}.</b> Both {@code StatusUpdateRequest}
     * and {@code AppointmentProviderNoteRequest} are built with a {@code null} comment — whole-
     * visit decline carries no reason (locked project rule); the "why" belongs to the
     * notification, authored for this purpose, not to a synthetic machine-authored note.
     *
     * <p><b>D12 — one {@code SALON_CLOSED} entry per VISIT, keyed to a deterministic
     * representative chosen among the bookings THIS CALL ACTUALLY DECLINED.</b> After each visit's
     * decline call returns, at most one {@code outboxService.enqueueSalonClosed} call is made —
     * never inside {@link #declineConfirmedBookingsAtomic} or {@code declineAppointmentItems}
     * themselves, both of which stay notification-free for their OTHER existing caller (the D4/D6
     * seam those methods' own javadoc documents). The representative is the lowest-{@code
     * startsAt} booking tied on {@code bookingId} ({@link #representativeOf}), chosen ONLY among
     * the ids that ACTUALLY transitioned this call (security re-audit fix — previously chosen from
     * the full candidate list regardless of whether the pick itself survived a concurrent race).
     * If NOTHING in a visit transitioned (every one of its bookings lost the race), that visit
     * contributes no entry at all — never a notice describing a booking that is not, in fact,
     * DECLINED. If the deterministic pick itself lost the race but a SIBLING in the same visit
     * (an appointment visit's other item) did transition, {@link #representativeOf} falls through
     * to the next-lowest surviving candidate — still exactly one entry, still deterministic for a
     * given set of survivors.
     *
     * <p><b>Eviction</b> is registered ONCE per distinct master actually touched by this cascade
     * (a whole salon can span several masters, unlike the single-master schedule-override write),
     * mirroring {@code ScheduleOverrideConflictService}'s one-combined-eviction perf pattern rather
     * than one eviction pair per declined booking.
     *
     * <p><b>D11 — a notification-delivery failure never rolls back the deletion.</b> The
     * {@code enqueueSalonClosed} call only writes the outbox row inside this SAME transaction
     * (MANDATORY propagation — commits atomically with the status transitions); actual delivery is
     * the drain worker's problem, entirely outside this transaction and this method.
     *
     * <p><b>Cost (corrected AGAIN, 2026-09 re-audit — Finding A — supersedes the immediately
     * prior {@code 3 + M + ⌈V/50⌉} estimate, which was itself a security-motivated trade of
     * batching for atomicity).</b> Let {@code V} = the number of DISTINCT visits that end up with
     * an outbox entry (bounded by {@code byVisit.size()}). The fixed cost is {@code 4}: the
     * ownership self-assertion, the candidate scan, the batched standalone-visit load
     * ({@link BookingRepository#findAllByIdInWithFullGraph}), and — new in this fix — the
     * standalone WRITE phase is ITSELF now a SINGLE statement, not a per-row loop:
     * {@link #declineConfirmedBookingsAtomic} issues exactly ONE
     * {@link BookingRepository#declineConfirmedBulk} call for every validated standalone booking
     * in the whole cascade, regardless of how many there are. This restores the perf-1 fix's
     * batching win WITHOUT giving up the per-row atomicity the prior security fix bought — see
     * {@link BookingRepository#declineConfirmedBulk}'s Javadoc for why a single {@code UPDATE ...
     * WHERE id IN (:ids) AND status = 'CONFIRMED' RETURNING id} is exactly as race-safe as {@code
     * M} separate single-row atomic UPDATEs. There is no separate batched-freshness query, because
     * that one statement folds the freshness check into the write itself, per row, inside the same
     * statement. The appointment leg is untouched and stays O(1) queries per appointment-visit. The
     * outbox-INSERT flush is still batched, {@code ⌈V/50⌉}. Total: {@code 4 + ⌈V/50⌉} — the
     * standalone-booking count no longer appears as a linear term at all, because the write phase
     * that used to cost one round trip per standalone booking is now a single statement independent
     * of {@code M}. Do not re-litigate the batching-vs-atomicity trade-off a fourth time: {@link
     * BookingRepository#declineConfirmedBulk}'s Javadoc explains why this formula gets both
     * properties at once rather than trading one for the other.
     *
     * @param actorUserId the deleting {@code SALON_OWNER}'s id — the provider-authority actor for
     *                     every decline this method performs
     * @param salonId      the salon being deleted
     * @throws ForbiddenException {@code actorUserId} does not own {@code salonId} (security finding
     *                             4, 2026-09 audit — see the ownership self-assertion below)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void declineFutureConfirmedBookingsForSalonClosure(UUID actorUserId, UUID salonId) {
        // Ownership self-assertion (security finding 4, 2026-09 audit — defense-in-depth). This
        // method is callable from another package (SalonService) and previously trusted `salonId`
        // entirely on the strength of its ONE caller's own findByIdAndOwnerId check
        // (SalonService#deactivateSalon). Not exploitable today — every downstream decline still
        // keys off the BOOKING's own actual salon via enforceCanCancelBooking /
        // enforceCanManageAppointment, so a rogue caller would 403+rollback rather than mass-decline
        // — but a method this trusting of a caller-supplied scope id must never rely SOLELY on the
        // one caller having checked first.
        if (!salonRepository.existsByIdAndOwnerId(salonId, actorUserId)) {
            throw new ForbiddenException("Access denied");
        }

        OffsetDateTime now = resolveNow();
        List<SalonClosureBookingCandidate> candidates =
                bookingRepository.findConfirmedFutureBySalonId(salonId, now);
        declineFutureConfirmed(actorUserId, candidates, salonId, now, OutboxEventType.SALON_CLOSED);
    }

    /**
     * Master-scoped sibling of {@link #declineFutureConfirmedBookingsForSalonClosure} (Phase 298
     * D4) — declines every future {@code CONFIRMED} booking of ONE master being removed from a
     * salon and enqueues one {@code MASTER_REMOVED} notification per affected VISIT, instead of
     * refusing the removal with a {@code 409} (Phase 297 D3's now-superseded guard). Called by
     * {@code SalonService#removeMaster}, BEFORE {@code MasterService#deactivateMaster} /
     * {@code disposeStaffAccounts} run (Phase 298 D5 — load-bearing ordering, not a habit: this
     * method's own two self-assertions below require the {@code masters} row to still exist and
     * still belong to {@code salonId}, which the disposal's {@code DELETE FROM masters} branch —
     * taken only for a master with NO booking/review history at all — would otherwise have
     * already undone; see {@code SalonService#removeMaster}'s ordered-checklist javadoc for the
     * mutation-checked mechanism, including why a master WITH a future booking never actually
     * reaches that branch).
     *
     * <p>Shares the ENTIRE grouping/decline/notify/eviction body with the salon-closure cascade
     * via {@link #declineFutureConfirmed} — only the candidate scan (master-scoped, not
     * salon-scoped) and the outbox event type differ. See that method's javadoc, and {@link
     * #declineFutureConfirmedBookingsForSalonClosure}'s longer one, for the shared mechanism.
     *
     * <p><b>Two ownership/scope self-assertions</b> (Phase 298 D4), mirroring the salon-closure
     * entry point's own defense-in-depth posture: {@code actorUserId} must own {@code salonId}
     * (identical rationale to the salon-closure self-assertion), AND {@code masterId} must
     * actually belong to {@code salonId} — otherwise a {@code masterId} from a salon the caller
     * does NOT own could ride in on a salon the caller DOES legitimately own, since {@code
     * SalonService#removeMaster}'s own re-check runs against the loaded {@code Master} row, not
     * against this method's caller-supplied ids.
     *
     * @param actorUserId the removing {@code SALON_OWNER}'s id — the provider-authority actor for
     *                     every decline this method performs
     * @param salonId     the salon the master is being removed from
     * @param masterId    the master being removed
     * @throws ForbiddenException {@code actorUserId} does not own {@code salonId}, or {@code
     *                             masterId} does not belong to {@code salonId}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void declineFutureConfirmedBookingsForMasterRemoval(UUID actorUserId, UUID salonId, UUID masterId) {
        if (!salonRepository.existsByIdAndOwnerId(salonId, actorUserId)) {
            throw new ForbiddenException("Access denied");
        }
        if (!masterRepository.existsByIdAndSalonId(masterId, salonId)) {
            throw new ForbiddenException("Access denied");
        }

        OffsetDateTime now = resolveNow();
        List<SalonClosureBookingCandidate> candidates =
                bookingRepository.findConfirmedFutureByMasterId(masterId, now);
        declineFutureConfirmed(actorUserId, candidates, salonId, now, OutboxEventType.MASTER_REMOVED);
    }

    /**
     * Shared cascade body behind both {@link #declineFutureConfirmedBookingsForSalonClosure} and
     * {@link #declineFutureConfirmedBookingsForMasterRemoval} (Phase 298 D4 — REUSE-FIRST: one
     * method, parameterised by scope, never a second cascade). Everything from the empty-check
     * down — the per-visit grouping, the batched standalone read, the management-access memo, the
     * validate-then-bulk-write split, the appointment-items leg, the outbox window, and the slot
     * and calendar evictions — is identical for both callers; only {@code eventType} decides which
     * outbox row gets written per visit. A future fix to this cascade lands on both callers or
     * neither.
     *
     * @param actorUserId the provider-authority actor for every decline this call performs —
     *                     already proven to own {@code salonId} by the caller's own self-assertion
     * @param candidates  the scope-specific candidate scan result — salon-wide or master-scoped —
     *                     already resolved by the caller against {@code now}
     * @param salonId     the salon every one of {@code candidates}' bookings belongs to — used only
     *                     to scope the after-commit slot-eviction cache key, never re-validated here
     * @param now         the SAME instant the caller's own candidate scan used (D3 — resolved
     *                     once, never re-read here), reused only to stamp {@code updated_at} in
     *                     {@link #declineConfirmedBookingsAtomic}
     * @param eventType   {@code SALON_CLOSED} or {@code MASTER_REMOVED} — selects which outbox
     *                     enqueue method runs per visit representative
     */
    private void declineFutureConfirmed(
            UUID actorUserId, List<SalonClosureBookingCandidate> candidates, UUID salonId,
            OffsetDateTime now, OutboxEventType eventType) {
        if (candidates.isEmpty()) {
            return;
        }

        StatusUpdateRequest declineRequest =
                new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        AppointmentProviderNoteRequest appointmentNoteRequest = new AppointmentProviderNoteRequest(null);

        Map<UUID, List<SalonClosureBookingCandidate>> byVisit = candidates.stream()
                .collect(Collectors.groupingBy(
                        SalonClosureBookingCandidate::visitKey, LinkedHashMap::new, Collectors.toList()));

        // Perf finding 1 (2026-09 audit) — READ phase, entirely up front and batched: never one
        // query per standalone visit. Loading every standalone booking in ONE query mirrors exactly
        // how the appointment-item path already batches a whole visit's items in one query
        // (declineAppointmentItems' loadItemsOrThrow / findConfirmedIdsByAppointmentId) — just
        // applied across VISITS here instead of within one. There is no longer a separate batched
        // freshness query (security re-audit, Finding A) — declineConfirmedBookingsAtomic's own
        // bulk conditional UPDATE (BookingRepository#declineConfirmedBulk) is the freshness check
        // now, evaluated per row inside that single statement at write time instead of
        // pre-computed as a Set snapshot that could go stale mid-loop.
        List<UUID> standaloneBookingIds = byVisit.values().stream()
                .filter(visit -> visit.get(0).appointmentId() == null)
                .map(visit -> visit.get(0).bookingId())
                .toList();
        Map<UUID, Booking> standaloneBookingsById = standaloneBookingIds.isEmpty()
                ? Map.of()
                : bookingRepository.findAllByIdInWithFullGraph(standaloneBookingIds).stream()
                        .collect(Collectors.toMap(Booking::getId, Function.identity()));

        // Perf finding 2 (2026-09 re-audit) — call-scoped memo of the SALON_OWNER management-access
        // answer, shared across every appointment-visit's authorization check below. Pre-seeded
        // with the fact this method ALREADY proved above (the ownership self-assertion): every
        // candidate here was scanned with `WHERE b.salon.id = :salonId`, so every appointment
        // visit's own salonId is guaranteed to equal THIS salonId, and actorUserId's ownership of
        // it is already known true. Seeding removes even the FIRST appointment-visit's EXISTS
        // query; the memo then continues to serve every subsequent visit's identical lookup from
        // memory — see AuthorizationService#enforceCanManageAppointment(UUID, UUID, Map)'s Javadoc
        // for the memo's lifetime contract (never retained past this one method call).
        Map<AuthorizationService.MemoKey, Boolean> managementAccessMemo = new HashMap<>();
        managementAccessMemo.put(new AuthorizationService.MemoKey(actorUserId, salonId), true);

        // WRITE phase — standalone bookings first, now itself split into a validate pass (zero
        // I/O, per-row) and ONE bulk write (perf finding, 2026-09 re-audit — Finding A). Appointment
        // groups run second: each one still issues its own small, already-batched query set
        // internally (declineAppointmentItems) exactly as before the re-audit — that leg was never
        // the target of any finding, since its per-appointment reads-then-writes shape (and its own
        // header lock) was already correct.
        List<UUID> validatedStandaloneIds = new ArrayList<>();
        for (List<SalonClosureBookingCandidate> visit : byVisit.values()) {
            if (visit.get(0).appointmentId() != null) {
                continue;
            }
            UUID bookingId = visit.get(0).bookingId();
            Booking booking = standaloneBookingsById.get(bookingId);
            if (booking == null) {
                // Vanishingly unlikely (the two batched reads above run moments apart, in the same
                // transaction, against a row nothing else can delete) — but never silently drop a
                // visit the candidate scan promised to decline.
                throw new ForbiddenException("Access denied");
            }
            assertBatchDeclinePreconditions(actorUserId, booking, declineRequest);
            validatedStandaloneIds.add(bookingId);
        }
        Set<UUID> transitionedStandaloneIds = declineConfirmedBookingsAtomic(validatedStandaloneIds, declineRequest, now);

        List<UUID> representativeIds = new ArrayList<>(byVisit.size());
        for (List<SalonClosureBookingCandidate> visit : byVisit.values()) {
            if (visit.get(0).appointmentId() != null) {
                continue;
            }
            representativeOf(visit, transitionedStandaloneIds).ifPresent(representativeIds::add);
        }
        for (List<SalonClosureBookingCandidate> visit : byVisit.values()) {
            UUID appointmentId = visit.get(0).appointmentId();
            if (appointmentId == null) {
                continue;
            }
            List<UUID> bookingIds = visit.stream().map(SalonClosureBookingCandidate::bookingId).toList();
            List<Booking> declined = appointmentTransitionService.declineAppointmentItems(
                    actorUserId, appointmentId, bookingIds, appointmentNoteRequest, false, managementAccessMemo);
            Set<UUID> declinedIds = declined.stream().map(Booking::getId).collect(Collectors.toSet());
            representativeOf(visit, declinedIds).ifPresent(representativeIds::add);
        }

        // Outbox INSERTs flush in their OWN window, after every decline above is already staged —
        // never interleaved with a decline's own read. D12 (one row per visit) is preserved exactly:
        // the dedup key is each visit's own content, not loop position, so processing standalone
        // visits before appointment visits (rather than in candidate-scan order) is unobservable.
        for (UUID representativeId : representativeIds) {
            switch (eventType) {
                case SALON_CLOSED -> outboxService.enqueueSalonClosed(representativeId);
                case MASTER_REMOVED -> outboxService.enqueueMasterRemoved(representativeId);
                default -> throw new IllegalStateException(
                        "declineFutureConfirmed does not support outbox event type " + eventType);
            }
        }

        Set<UUID> masterIds = candidates.stream()
                .map(SalonClosureBookingCandidate::masterId)
                .collect(Collectors.toSet());
        for (UUID masterId : masterIds) {
            registerSlotEviction(masterId, salonId);
            evictMasterCalendarAfterCommit(masterId);
        }
    }

    /**
     * The deterministic representative of one visit's candidate rows for the single
     * {@code SALON_CLOSED}/{@code MASTER_REMOVED} outbox entry (D12; Phase 298 widens this
     * helper's use to the master-removal cascade, unchanged): the lowest {@code startsAt}, tied on
     * {@code bookingId} so the choice never depends on scan/insertion order and a re-run against
     * equivalent fixtures always lands on the same row — chosen ONLY among {@code declinedIds}
     * (security re-audit fix, Finding A). Previously this picked from the FULL candidate list
     * regardless of whether the pick itself survived a concurrent race; a representative that lost
     * the race between the candidate scan and its own decline attempt could then be handed to
     * {@code NotificationOutboxService#enqueueSalonClosed}/{@code #enqueueMasterRemoved} even
     * though it was never actually declined. Filtering to {@code declinedIds} first means: (a) a
     * visit where NOTHING transitioned yields no representative at all (caller must not enqueue),
     * and (b) a visit where the deterministic pick itself raced away but a SIBLING did transition
     * still yields exactly one entry — the next-lowest-{@code startsAt} SURVIVOR, still
     * deterministic for a given survivor set.
     *
     * @param declinedIds the ids, among {@code visit}'s own bookings, that THIS CALL actually
     *                     transitioned to {@code DECLINED} — for a standalone visit, membership in
     *                     {@link #declineConfirmedBookingsAtomic}'s cascade-wide result set (the
     *                     bulk {@code UPDATE ... RETURNING}'s own affected-row outcome, still
     *                     tested per visit here since that set spans every standalone visit in the
     *                     cascade); for an appointment visit, {@code declineAppointmentItems}' own
     *                     returned survivor list
     * @return the representative, or empty iff nothing in this visit transitioned
     */
    private static Optional<UUID> representativeOf(
            List<SalonClosureBookingCandidate> visit, Set<UUID> declinedIds) {
        return visit.stream()
                .filter(candidate -> declinedIds.contains(candidate.bookingId()))
                .min(Comparator.comparing(SalonClosureBookingCandidate::startsAt)
                        .thenComparing(SalonClosureBookingCandidate::bookingId))
                .map(SalonClosureBookingCandidate::bookingId);
    }

    /**
     * Zero-I/O validation pass for one standalone candidate of
     * {@link #declineFutureConfirmedBookingsForSalonClosure} (perf re-audit, 2026-09, Finding A —
     * split out of the former {@code declineBookingForBatchAtomic} so the cascade's write phase
     * can be batched into ONE bulk statement via {@link #declineConfirmedBookingsAtomic} instead
     * of one round trip per booking). Runs the EXACT SAME authorization and transition-legality
     * guards as {@link #declineBookingCore(UUID, Booking, StatusUpdateRequest, Predicate)} against
     * the already-loaded {@code booking} snapshot — {@link AuthorizationService#enforceCanCancelBooking},
     * {@link #assertNotAppointmentChild}, {@link #assertTransition} — every one of them an
     * in-memory check against a row this method never writes to and never re-loads. Authorization
     * and transition-legality are unchanged by this fix: still enforced per row, still enforced
     * before that row's id is ever handed to the bulk write.
     *
     * @throws BusinessException  {@code req.cancellationReason()} is {@code null}
     * @throws ForbiddenException the actor lacks provider authority over {@code booking}
     * @throws NotFoundException  {@code booking} is not a standalone booking, or is not
     *                             {@code CONFIRMED} at this snapshot (thrown by {@link
     *                             #assertNotAppointmentChild} / {@link #assertTransition})
     */
    private void assertBatchDeclinePreconditions(UUID actorUserId, Booking booking, StatusUpdateRequest req) {
        if (req.cancellationReason() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Cancellation reason required for declining a booking");
        }
        authz.enforceCanCancelBooking(actorUserId, booking);
        // Multi-service visit item: refuse the single-booking transition (use /appointments/{id}).
        // Always a no-op for this call site (the caller already filtered to appointmentId == null
        // candidates) — kept anyway, defensively, exactly like every other provider transition in
        // this class (Anti-Bug: never weaken or skip an existing guard for an internal caller).
        assertNotAppointmentChild(booking);
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.DECLINED);
    }

    /**
     * Bulk atomic write for every standalone candidate {@link #assertBatchDeclinePreconditions}
     * already validated (perf re-audit, 2026-09, Finding A — restores the perf-1 fix's batching
     * win the prior, per-row-atomic security fix gave back, WITHOUT relaxing that fix's
     * atomicity). Issues exactly ONE call to {@link BookingRepository#declineConfirmedBulk} — a
     * single native {@code UPDATE ... WHERE id IN (:ids) AND status = 'CONFIRMED' RETURNING id} —
     * for every validated standalone booking in the whole cascade, regardless of {@code
     * bookingIds.size()}. See that method's Javadoc for why one bulk statement is exactly as
     * race-safe as {@code bookingIds.size()} separate single-row atomic UPDATEs: PostgreSQL
     * evaluates the {@code WHERE} clause per row within the one statement, so a row that left
     * CONFIRMED between {@link #assertBatchDeclinePreconditions}' check and this statement's own
     * arrival at that row is excluded from the result exactly as it would be under N separate
     * statements.
     *
     * <p><b>Stale-entity trap.</b> After this call returns, every {@code Booking} entity already
     * loaded in this transaction's persistence context for an id in {@code bookingIds} — including
     * every entry of {@code standaloneBookingsById} in the caller — carries a stale in-memory
     * {@code status = CONFIRMED}, regardless of whether that id is actually in the returned
     * {@code Set}. This is harmless today: nothing in this method's caller re-reads or mutates
     * those entities after this call. But never assign a field on one of them, or call {@code
     * entityManager.find(Booking.class, id)} for one of these ids, later in the SAME transaction —
     * see {@link BookingRepository#declineConfirmedBulk}'s own Javadoc for the full hazard.
     *
     * @param bookingIds standalone-booking ids, already authorization- and transition-checked by
     *                    the caller via {@link #assertBatchDeclinePreconditions} — may be empty,
     *                    in which case this method issues NO query at all
     * @param req         the SAME {@link StatusUpdateRequest} every candidate in this cascade
     *                     shares (D5 — {@code providerComment} is always {@code null})
     * @param now         the SAME {@code now} {@link #declineFutureConfirmedBookingsForSalonClosure}
     *                     resolved once at the top of the whole scan (D3) — reused here only to
     *                     stamp {@code updated_at}, never re-read, so this method introduces no
     *                     second clock read
     * @return the subset of {@code bookingIds} that this call actually transitioned to {@code
     *         DECLINED} — never a superset; an id NOT in this set lost the race sometime after
     *         {@link #assertBatchDeclinePreconditions} ran, and the caller MUST NOT enqueue a
     *         {@code SALON_CLOSED}/{@code MASTER_REMOVED} entry keyed to it
     */
    private Set<UUID> declineConfirmedBookingsAtomic(
            List<UUID> bookingIds, StatusUpdateRequest req, OffsetDateTime now) {
        if (bookingIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> transitionedIds = bookingRepository.declineConfirmedBulk(
                bookingIds, req.cancellationReason().name(), BookingComments.normalize(req.comment()),
                now.toInstant());
        return new HashSet<>(transitionedIds);
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
     *
     * <p><b>Perf finding 1 (2026-09 audit).</b> This single-id overload is now a thin wrapper: the
     * find + the {@code req.cancellationReason() == null} guard stay here (so the existing-but-
     * foreign-vs-missing 403/400 precedence documented below is unchanged for this method's two
     * existing callers, {@link #declineBooking} and {@link #declineBookingForBatch(UUID, UUID,
     * StatusUpdateRequest)}), then delegates to {@link #declineBookingCore(UUID, Booking,
     * StatusUpdateRequest, Predicate)} — the same core the salon-closure cascade's batched overload
     * uses, passing {@link #isStillConfirmed} itself as the freshness predicate (identical, lazy,
     * one-query-per-call semantics to before this split).
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
        return declineBookingCore(actorUserId, booking, req, this::isStillConfirmed);
    }

    /**
     * Shared transition core behind BOTH {@link #declineBookingCore(UUID, UUID, StatusUpdateRequest)}
     * (the pre-existing single-id path) and {@link #declineBookingForBatch(UUID, Booking,
     * StatusUpdateRequest, Predicate)} (the salon-closure cascade's batched path, perf finding 1,
     * 2026-09 audit). {@code booking} must already be a managed entity loaded with the
     * {@code findByIdWithFullGraph} graph; {@code stillConfirmed} supplies the G5 freshness verdict
     * (a lazy, per-call query via {@link #isStillConfirmed} for the single-id caller; a pre-computed
     * batched-{@code Set} lookup, no query at all, for the cascade caller) — see this method's
     * sibling single-id overload's own Javadoc for the full G5 rationale, which applies here
     * verbatim regardless of which predicate supplied the answer.
     *
     * <p>Runs the {@code req.cancellationReason() == null} guard again defensively — always
     * unreachable for the single-id caller (already thrown by its own earlier check) and always
     * false for the cascade caller (its {@code StatusUpdateRequest} is built with a fixed non-null
     * {@code PROVIDER_UNAVAILABLE} reason, D5) — so this never changes either caller's observable
     * behaviour, it only keeps this shared core safe against a hypothetical future caller that
     * skips its own check.
     */
    private Booking declineBookingCore(
            UUID actorUserId, Booking booking, StatusUpdateRequest req, Predicate<UUID> stillConfirmed) {
        if (req.cancellationReason() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Cancellation reason required for declining a booking");
        }
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
        if (!stillConfirmed.test(booking.getId())) {
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
        // COMPLETED leaves the `status = 'CONFIRMED'` occupancy predicate, so it FREES the
        // booking's window. assertElapsedForComplete only requires `now >= startsAt`, never
        // `now >= endsAt`, so a provider may close an in-progress booking early — the unused tail
        // becomes bookable at once and must not stay hidden for the availability TTL.
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved));
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
        // NOT_COMPLETED leaves the `status = 'CONFIRMED'` occupancy predicate, so it FREES the
        // booking's window. No-show carries NO temporal guard, so the booking may still be in the
        // future — its slot is then genuinely re-bookable and must return to the picker at once
        // rather than staying hidden for the availability TTL.
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved));
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
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved));
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
     * @param req         the new start time; {@link RescheduleBookingRequest#allowClientOverlap()}
     *                    opts out of the self-conflict check below, mirroring
     *                    {@link CreateBookingRequest#allowClientOverlap()}
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
        validateStartsAt(newStartsAt, initiatedByProvider);

        UUID masterId = booking.getMaster().getId();
        UUID masterServiceId = booking.getMasterService().getId();

        // Same working-hours / effective-day validation create relies on: the requested start
        // must fall on a slot the master actually works (Phase 15.4 effective-day resolver +
        // service/master liveness + duration bounds, via SlotCalculationService.getAvailableSlots).
        // Run BEFORE the advisory lock to keep the lock window tight (backend-perf). An
        // off-schedule time yields no matching slot → 409 "Slot not available", the same status
        // the create/overlap path returns for an unbookable time. The authoritative overlap check
        // (excluding this booking's own row) still runs under the lock below.
        // No preloaded assignment here (Perf MEDIUM, 2026-08-11): the reschedule path holds only
        // booking.getMasterService(), an uninitialised LAZY proxy whose graph the availability read needs —
        // dereferencing it would cost the very query passing it is meant to save. null ⇒ plain reload.
        assertStartsOnAvailableSlot(masterId, masterServiceId, null, newStartsAt, initiatedByProvider);

        // Duration + buffer are frozen at the original booking; mirror the create-path
        // end-time formula (duration + buffer) rather than recomputing from master_services.
        OffsetDateTime newEndsAt = newStartsAt.plusMinutes(
                (long) booking.getDurationMinutesAtBooking() + booking.getBufferMinutesAtBooking());

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
            //
            // OVERRIDE (product decision 2026-08-22, widened 2026-08-26): req.allowClientOverlap()
            // mirrors doCreateBooking's identical opt-in — skips ONLY this self-conflict check.
            // The master-scoped existsOverlapExcluding check below and the
            // no_overlapping_bookings EXCLUDE constraint still run unconditionally regardless of
            // this flag, because they protect a DIFFERENT client's claim on this master's slot,
            // which is never the requesting client's to waive. Defaults false (primitive
            // boolean), so an absent/omitted field reproduces today's behaviour byte-for-byte.
            //
            // ACTOR GATE (backend-security HIGH, cycle audit 2026-08-26): the override is the
            // CLIENT's consent to give, not the provider's. Unlike doCreateBooking (CLIENT-only
            // endpoint), this reschedule route is also reachable by SALON_OWNER/SALON_ADMIN/
            // INDEPENDENT_MASTER (see @PreAuthorize on BookingController#rescheduleBooking) — so
            // req.allowClientOverlap() must be honored ONLY when the client themselves is the
            // actor. `initiatedByProvider` (= actorRole != Role.CLIENT, set at the top of this
            // method) is the same discriminator already driving resolveBookingForProviderReschedule
            // vs resolveBookingForClientReschedule and validateStartsAt above — reused here rather
            // than threading a new parameter.
            if (initiatedByProvider || !req.allowClientOverlap()) {
                assertNoClientConflictExcluding(owningClient.getId(), newStartsAt, newEndsAt, bookingId);
            }
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
        // Evict the freed old-day slots and the now-occupied new-day slots, plus the provider
        // calendar — after commit, so a parallel reader cannot repopulate stale data. One call
        // covers both days: the sweep is by master, so it drops every cached date for this master
        // (it no longer needs the old/new date pair the per-key eviction had to enumerate).
        registerSlotEviction(masterId, salonIdOf(saved));
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
        // false: the authenticated CLIENT/APP create path keeps the 15-minute floor, untouched.
        validateStartsAt(startsAt, false);

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
        //
        // OVERRIDE (product decision 2026-08-22): request.allowClientOverlap() is an explicit,
        // client-supplied opt-in to double-book THEMSELVES — "it's only the client's responsibility".
        // Skips ONLY this self-conflict check. It changes nothing below: the per-master advisory
        // lock, existsOverlap and the no_overlapping_bookings EXCLUDE constraint still run
        // unconditionally, because they protect a DIFFERENT client's claim on this master's slot,
        // which is never the requesting client's to waive. Defaults false (primitive boolean), so an
        // absent/omitted field reproduces today's behaviour byte-for-byte.
        if (!request.allowClientOverlap()) {
            assertNoClientConflict(clientId, startsAt, endsAt);
        }

        // SCHEDULE-FIT GATE (2026-08-11 HIGH). validateStartsAt above enforces only the lead-time floor
        // and the 180-day horizon; assertNoClientConflict enforces only the CLIENT's own calendar; the
        // existsOverlap check below (and the GIST EXCLUDE behind it) enforces only collision with an
        // existing booking. None of them asks whether the master actually WORKS this time, so until this
        // line a client could POST a start on a day-off override, inside a lunch-break gap, or on an
        // off-grid minute and have it persisted CONFIRMED onto the master's calendar. Reuses the exact
        // oracle the reschedule path has always used (#assertStartsOnAvailableSlot).
        //
        // PLACEMENT IS LOAD-BEARING, and is why this sits here rather than beside validateStartsAt:
        //   * AFTER assertNoClientConflict — the slot list already has the master's CONFIRMED bookings
        //     subtracted, so a time that is BOTH the client's own conflict and master-busy would fail
        //     here with the generic "Slot not available" and mask the structured, actionable
        //     CLIENT_BOOKING_CONFLICT that the locked product decision (see the comment on that call)
        //     requires to win. Pinned by BookingIntegrationTest
        //     #should_returnClientBookingConflictNotGenericConflict_when_bothClientConflictAndMasterBusyApply.
        //   * BEFORE the per-master advisory lock — an off-schedule request must never contend for the
        //     lock every other client of a popular master is queued on. Only this client's OWN
        //     (uncontended) client lock is held across it.
        //
        // Rejection is the same 409 "Slot not available" the overlap check returns — off-schedule and
        // already-taken stay indistinguishable to the caller, leaking no schedule detail.
        //
        // `msa` is handed through so the gate does not re-issue the findByMasterIdAndIdWithGraph this
        // method already ran at :1823 (Perf MEDIUM, 2026-08-11) — same persistence context, same instance.
        assertStartsOnAvailableSlot(master.getId(), msa.getId(), msa, startsAt, false);

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
        registerSlotEviction(saved.getMaster().getId(), salonIdOf(saved));
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
    private void assertStartsOnAvailableSlot(
            UUID masterId, UUID masterServiceId, MasterServiceAssignment preloaded, OffsetDateTime startsAt,
            boolean initiatedByProvider) {
        BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, preloaded, startsAt, initiatedByProvider);
    }

    private void validateStartsAt(OffsetDateTime startsAt, boolean initiatedByProvider) {
        // Shared with GuestBookingService (DRY) so the authenticated and guest paths
        // enforce the identical lead-time floor + max-window cap.
        //
        // initiatedByProvider == true selects the STAFF floor (minimum lead 0) that walk-in CREATE
        // already uses — gated on the ACTOR, never on booking.getSource(). CREATE callers pass false.
        BookingStartsAtValidator.validate(startsAt, clock, initiatedByProvider);
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

    /**
     * After-commit availability-cache eviction for a booking write, plus the salon catalogue.
     *
     * <p>Deliberately NOT per {@code (date, masterServiceId)}: the written time bounds the slots
     * offered for EVERY service this master performs that day, so the write sweeps all three per-master
     * caches — see {@link SlotCalculationService#evictMasterAvailabilityCaches}, which documents why the
     * sweep is by master prefix and which caches it covers.
     */
    private void registerSlotEviction(UUID masterId, UUID salonId) {
        Runnable task = () -> {
            slotCalculationService.evictMasterAvailabilityCaches(masterId);
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
