package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingDetailResponse;
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
import com.beautica.review.repository.ReviewRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
        // Use full-graph fetch to avoid lazy-load SELECTs when building BookingDetailResponse
        Booking booking = bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        authz.enforceCanViewBooking(actorUserId, booking);
        boolean canReview = canReview(booking.getStatus(), reviewRepository.existsByBookingId(bookingId));
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

    /** {@code canReview = COMPLETED && no existing review} — single source of the truth table. */
    private static boolean canReview(BookingStatus status, boolean reviewExists) {
        return status == BookingStatus.COMPLETED && !reviewExists;
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
                p.durationMinutesAtBooking(),
                p.createdAt().atOffset(ZoneOffset.UTC),
                p.clientFirstName(),
                p.clientLastName(),
                p.masterFirstName(),
                p.masterLastName(),
                p.clientComment(),
                p.providerComment(),
                p.masterAvatarUrl(),
                p.masterType(),
                p.salonName(),
                labels.cityLabel(p.discoveryCityId()),
                labels.districtLabel(p.discoveryDistrictId()),
                p.street(),
                p.buildingNo(),
                p.categoryName(),
                canReview(p.status(), p.reviewExists()));
    }

    /**
     * Lists the actor's bookings as the enriched {@link BookingDetailResponse} (Phase 19.3 —
     * {@code GET /bookings/me} switched from the lean {@code BookingResponse} per locked
     * Option A). {@code canReview} is true only for a {@code COMPLETED} booking with no review.
     *
     * <p><b>CLIENT</b> uses the single-query {@code findClientBookingDetails} projection —
     * {@code reviewExists} arrives inline via a {@code LEFT JOIN Review}, and the locality FK
     * ids are batch-resolved to labels in a fixed two queries through the M2 seam (no N+1).
     *
     * <p><b>Provider roles</b> (master / salon-owner) reuse the established two-query ID-page +
     * graph-hydrate pattern (Fix H1), then add exactly two bounded follow-ups for the page:
     * one batched {@code findReviewedBookingIds} and the two-query label resolution — never a
     * per-row lookup.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingDetailResponse> getMyBookings(
            UUID actorUserId, Authentication auth, BookingStatus status, Pageable pageable) {
        // Role is already encoded in the JWT-derived authority — no DB round-trip needed to
        // resolve the role. Only SALON_OWNER requires a DB call to fetch the associated salonId.
        Role role = auth.getAuthorities().stream()
                .findFirst()
                .map(a -> Role.valueOf(a.getAuthority().replace("ROLE_", "")))
                .orElseThrow(() -> new ForbiddenException("Access denied"));

        Page<BookingDetailResponse> page = role == Role.CLIENT
                ? listClientBookings(actorUserId, status, pageable)
                : listProviderBookings(role, actorUserId, status, pageable);

        return PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * CLIENT path — one projection query (reviewExists inline) + batched label resolution.
     */
    private Page<BookingDetailResponse> listClientBookings(
            UUID clientId, BookingStatus status, Pageable pageable) {
        Page<ClientBookingDetailProjection> page =
                bookingRepository.findClientBookingDetails(clientId, status, pageable);
        if (page.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        }

        DiscoveryLabels labels = resolveProjectionLabels(page.getContent());
        List<BookingDetailResponse> content = page.getContent().stream()
                .map(p -> toDetailResponse(p, labels))
                .toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /**
     * Provider path — ID-page + graph hydrate (Fix H1), then one batched review-existence
     * query and the two-query label resolution for the whole page.
     */
    private Page<BookingDetailResponse> listProviderBookings(
            Role role, UUID actorUserId, BookingStatus status, Pageable pageable) {
        // Two-query pattern (Fix H1 — HHH90003004): first fetch a page of IDs using
        // plain JPQL with no JOIN FETCH (so the DB applies LIMIT/OFFSET correctly), then
        // batch-hydrate only those IDs with the full association graph in a second query.
        Page<UUID> idPage = switch (role) {
            case SALON_MASTER, INDEPENDENT_MASTER -> {
                Master master = masterRepository.findByUserId(actorUserId)
                        .orElseThrow(() -> new NotFoundException("Master profile not found"));
                yield status == null
                        ? bookingRepository.findIdsByMasterId(master.getId(), pageable)
                        : bookingRepository.findIdsByMasterIdAndStatus(master.getId(), status, pageable);
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
                yield status == null
                        ? bookingRepository.findIdsBySalonIds(salonIds, pageable)
                        : bookingRepository.findIdsBySalonIdsAndStatus(salonIds, status, pageable);
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
                    boolean canReview = canReview(b.getStatus(), reviewed.contains(b.getId()));
                    return BookingDetailResponse.from(
                            b, canReview, labels.cityLabel(cityId), labels.districtLabel(districtId));
                })
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    @Transactional
    public BookingResponse confirmBooking(UUID actorUserId, UUID bookingId) {
        Booking booking = loadBookingOrThrow(bookingId);
        authz.enforceCanManageBooking(actorUserId, booking);
        assertTransition(booking, BookingStatus.PENDING, BookingStatus.CONFIRMED);
        booking.setStatus(BookingStatus.CONFIRMED);
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse declineBooking(UUID actorUserId, UUID bookingId, StatusUpdateRequest req) {
        // Fix M4: require a reason, consistent with notCompleteBooking
        if (req.cancellationReason() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Cancellation reason required for declining a booking");
        }
        Booking booking = loadBookingOrThrow(bookingId);
        authz.enforceCanManageBooking(actorUserId, booking);
        assertTransition(booking, BookingStatus.PENDING, BookingStatus.DECLINED);
        booking.setStatus(BookingStatus.DECLINED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(req.comment());
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse completeBooking(UUID actorUserId, UUID bookingId) {
        Booking booking = loadBookingOrThrow(bookingId);
        // Phase 18.4: completion admits SALON_ADMIN (unlike confirm/decline/not-complete, which
        // stay owner-level on enforceCanManageBooking). See AuthorizationService.enforceCanCompleteBooking.
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
        authz.enforceCanManageBooking(actorUserId, booking);
        if (req.cancellationReason() == null) {
            throw new BusinessException("Cancellation reason required");
        }
        assertTransition(booking, BookingStatus.CONFIRMED, BookingStatus.NOT_COMPLETED);
        booking.setStatus(BookingStatus.NOT_COMPLETED);
        booking.setCancellationReason(req.cancellationReason());
        booking.setProviderComment(req.comment());
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        evictRevenueDashboardAfterCommit(actorUserId);
        return BookingResponse.from(saved);
    }

    @Transactional
    public BookingResponse cancelBooking(UUID clientUserId, UUID bookingId, CancelBookingRequest req) {
        Booking booking = loadBookingOrThrow(bookingId);
        if (!booking.getClient().getId().equals(clientUserId)) {
            throw new ForbiddenException("Access denied");
        }
        BookingStatus current = booking.getStatus();
        if (current != BookingStatus.PENDING && current != BookingStatus.CONFIRMED) {
            throw new BusinessException("Cannot cancel a booking in status %s".formatted(current));
        }
        booking.setStatus(BookingStatus.CANCELLED);
        // cancellationReason is guaranteed non-null by @NotNull on CancelBookingRequest
        booking.setCancellationReason(req.cancellationReason());
        Booking saved = bookingRepository.save(booking);
        outboxService.enqueueStatusChanged(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
        evictMasterCalendarAfterCommit(saved.getMaster().getId());
        return BookingResponse.from(saved);
    }

    /**
     * Moves a client's own {@code PENDING}/{@code CONFIRMED} booking to a new future time.
     *
     * <p>Reuses the create-path validation: {@link #validateStartsAt(OffsetDateTime)}
     * (≥15 min ahead, ≤180 days), the same working-hours / effective-day check via
     * {@link #assertStartsOnAvailableSlot} (the master must actually work the requested slot),
     * the per-master advisory lock, and the overlap check — here excluding the booking's own row
     * ({@link BookingRepository#existsOverlapExcluding}).
     * A {@code CONFIRMED} booking reverts to {@code PENDING} (re-enters the approval queue);
     * a {@code PENDING} booking stays {@code PENDING}. Either way the provider is re-notified
     * via a {@code BOOKING_RESCHEDULED} outbox event. {@code priceAtBooking} and
     * {@code durationMinutesAtBooking} are frozen and are NOT recomputed.
     *
     * <p>No change to {@code confirmBooking}/{@code declineBooking}: a rescheduled booking is
     * an ordinary {@code PENDING} booking, so a later decline → {@code DECLINED} and a later
     * confirm → {@code CONFIRMED} at the new time, via the existing transition logic.
     *
     * @param actorUserId the authenticated CLIENT (from the security principal, never the body)
     * @param bookingId   the booking to move
     * @param req         the new start time
     * @return the updated booking
     * @throws ForbiddenException if the actor is not the owning client (403)
     * @throws BusinessException  if the source state is not PENDING/CONFIRMED (409) or the new
     *                            slot conflicts (409); {@link #validateStartsAt} rejects bad times (400)
     */
    @Transactional
    public BookingDetailResponse rescheduleBooking(UUID actorUserId, UUID bookingId, RescheduleBookingRequest req) {
        Booking booking = loadBookingOrThrow(bookingId);

        // Ownership: only the owning registered client may reschedule. A guest (LINK)
        // booking has no client account, so getClient() is null and the actor can never match.
        if (booking.getClient() == null || !booking.getClient().getId().equals(actorUserId)) {
            throw new ForbiddenException("Access denied");
        }

        BookingStatus current = booking.getStatus();
        if (current != BookingStatus.PENDING && current != BookingStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot reschedule a booking in status %s".formatted(current));
        }

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

        // Same critical section as doCreateBooking: serialize per-master, then overlap-check
        // (excluding this booking's own row so it cannot collide with itself).
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
        registerSlotEviction(masterId, oldDate, saved.getMasterService().getId());
        if (!oldDate.equals(newStartsAt.toLocalDate())) {
            registerSlotEviction(masterId, newStartsAt.toLocalDate(), saved.getMasterService().getId());
        }
        evictMasterCalendarAfterCommit(masterId);
        // A rescheduled booking is always PENDING/CONFIRMED, so canReview is false by the
        // COMPLETED predicate — no review-existence query needed on this path.
        return enrichSingle(saved, canReview(saved.getStatus(), false));
    }

    private BookingResponse doCreateBooking(UUID clientId, String idempotencyKey, CreateBookingRequest request) {
        // Master kind is irrelevant to bookability — SALON_MASTER, INDEPENDENT_MASTER,
        // and SALON_OWNER masters are all bookable when active with working hours + a
        // matching master_services row.
        Master master = masterRepository.findByIdWithSalonAndOwner(request.masterId())
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
                .status(BookingStatus.PENDING)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(effectivePrice)
                .durationMinutesAtBooking(effectiveDuration)
                .bufferMinutesAtBooking(bufferMinutes)
                .idempotencyKey(idempotencyKey)
                .clientComment(request.clientComment())
                .build();

        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        outboxService.enqueueNewBooking(saved.getId());
        registerSlotEviction(saved.getMaster().getId(), saved.getStartsAt().toLocalDate(), saved.getMasterService().getId());
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

    private void registerSlotEviction(UUID masterId, LocalDate date, UUID masterServiceId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    slotCalculationService.evictAvailableSlots(masterId, date, masterServiceId);
                }
            });
        } else {
            // No active transaction (e.g. unit test context) — evict directly
            slotCalculationService.evictAvailableSlots(masterId, date, masterServiceId);
        }
    }

    private Booking loadBookingOrThrow(UUID bookingId) {
        // Fix M6: use full-graph fetch so mutation responses do not trigger
        // additional SELECTs for masterService and serviceDefinition
        return bookingRepository.findByIdWithFullGraph(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
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
