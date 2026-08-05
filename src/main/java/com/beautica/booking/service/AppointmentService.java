package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.CreateAppointmentRequest;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBookingConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Creates a multi-service single-visit {@link Appointment} (BE-3): ONE master, ONE chosen start
 * time, and N services performed back-to-back as N chained {@code CONFIRMED} {@link Booking} rows,
 * all-or-nothing in one transaction.
 *
 * <p><b>Reuses {@code BookingService}'s proven single-service concurrency machinery</b>, applied to a
 * chain instead of one row. In-transaction step order (identical shape to
 * {@code BookingService#doCreateBooking}, widened to the visit span):
 * <ol>
 *   <li>resolve the master (active) and each {@code masterServiceId} in list order (uniform 404);</li>
 *   <li>compute per-item windows + the summed block, capped at
 *       {@link SlotCalculationService#MAX_TOTAL_DURATION_MINUTES} (the same ceiling BE-2 offers
 *       against), and freeze per-item price/duration snapshots;</li>
 *   <li>resolve the CLIENT principal (defence-in-depth role check);</li>
 *   <li>acquire the per-CLIENT advisory lock (salt 1) FIRST, then re-check idempotency under the lock
 *       (deterministic replay), then the per-MASTER advisory lock (salt 0) — the same
 *       client-then-master order that keeps the two lock classes deadlock-free;</li>
 *   <li>client-conflict check over the WHOLE visit span {@code [firstStart, lastEnd)};</li>
 *   <li>ONE span overlap check against existing CONFIRMED bookings over {@code [firstStart, lastEnd)}
 *       (AT CREATE TIME internal items are contiguous by construction, so their union equals the span — true
     *       only at the moment of creation; a later per-item reschedule (phase 30.1) may separate
     *       items with legal gaps, a read/mutation-time concern this create-time check never
     *       observes; the
 *       {@code no_overlapping_bookings} GIST EXCLUDE still backstops each insert);</li>
 *   <li>persist the appointment + all N bookings atomically;</li>
 *   <li>enqueue EXACTLY ONE new-visit notification (referencing the first item, never one per
 *       service);</li>
 *   <li>evict the master's availability caches for the affected day(s) after commit.</li>
 * </ol>
 *
 * <p>The legacy single-service {@code POST /bookings} path ({@code BookingService}) and the
 * {@code no_overlapping_bookings} constraint are untouched (D5).
 */
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final BookingRepository bookingRepository;
    private final MasterRepository masterRepository;
    private final UserRepository userRepository;
    private final NotificationOutboxService outboxService;
    private final SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final AuthorizationService authz;
    private final DiscoveryLocationResolver discoveryLocationResolver;
    private final VisitPlanner visitPlanner;
    private final Clock clock;

    /**
     * Creates a multi-service visit, or replays the idempotent one, and returns the enriched detail.
     *
     * <p>Idempotency mirrors {@code BookingService#createBooking}: a present key first probes for an
     * existing CONFIRMED appointment (fast path, no lock). The authoritative, race-free replay is the
     * re-check under the per-client advisory lock inside {@link #doCreateAppointment}; the
     * partial-unique index {@code ux_appointments_client_idempotency_key_active} is the final backstop
     * — a duplicate insert that slips past both surfaces as a 409, never a second visit.
     */
    @Transactional
    public AppointmentDetailResponse createAppointment(
            UUID clientId, String idempotencyKey, CreateAppointmentRequest request) {
        UUID appointmentId;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            appointmentId = appointmentRepository
                    .findActiveByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                    .map(Appointment::getId)
                    .orElseGet(() -> doCreateAppointment(clientId, idempotencyKey, request));
        } else {
            appointmentId = doCreateAppointment(clientId, null, request);
        }
        return enrichCreated(appointmentId);
    }

    private AppointmentDetailResponse enrichCreated(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointmentId);
        if (items.isEmpty()) {
            // Unreachable for a just-created visit (always ≥1 chained row) — guard so from(...)
            // never dereferences an empty list.
            throw new NotFoundException("Appointment has no booking items");
        }
        return enrich(appointment, items);
    }

    /**
     * Reads the enriched detail of a single multi-service visit (BE-5) —
     * {@code GET /api/v1/appointments/{id}}.
     *
     * <p><b>Authorization mirrors {@code GET /bookings/{id}} exactly</b> (see
     * {@code BookingService#getBooking}). A visit is single-master, so provider view-access is
     * evaluated against the first item and client access against that item's client (which equals
     * the header's client — both are stamped identically at create time) via the shared
     * {@link AuthorizationService#enforceCanViewBooking} guard: the owning CLIENT, the visit's
     * INDEPENDENT_MASTER / salon owner / salon admin, or the assigned SALON_MASTER are admitted;
     * everyone else gets a 403. Existence and authorization collapse to a single uniform 403 (no
     * existence oracle): a missing / itemless appointment short-circuits to the SAME 403 the
     * ownership guard throws for a foreign visit, so a caller cannot probe whether an arbitrary
     * appointment id exists.
     *
     * <p>Built from the existing {@link BookingRepository#findByAppointmentIdWithGraph} (bounded,
     * graph-fetched, ordered by {@code startsAt} — no N+1) plus the appointment header, then enriched
     * with the mutually-visible header notes and the district-primary discovery locality labels,
     * exactly as {@code BookingDetailResponse} is.
     */
    @Transactional(readOnly = true)
    public AppointmentDetailResponse getAppointment(UUID actorUserId, UUID appointmentId) {
        List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointmentId);
        if (items.isEmpty()) {
            // Uniform 403 for a missing/itemless visit — no existence oracle (mirrors getBooking).
            throw new ForbiddenException("Access denied");
        }
        authz.enforceCanViewBooking(actorUserId, items.get(0));
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        return enrich(appointment, items);
    }

    /**
     * Resolves the district-primary discovery locality labels (salon when salon-employed, else the
     * master's own user row — the same {@code COALESCE(salon, user)} rule {@code BookingService} and
     * {@code SearchService} use) and builds the enriched {@link AppointmentDetailResponse}. The
     * street/building/locationNote resolution and the header-note reads live inside
     * {@link AppointmentDetailResponse#from}; only the FK→label lookup is not derivable from the
     * fetched graph and is done here.
     *
     * <p>Package-private (not {@code private}) so {@code AppointmentTransitionService.rescheduleAppointment}
     * (BE-4) can reuse it verbatim to build the re-planned visit's response, instead of
     * duplicating the discovery-label lookup a second time.
     */
    AppointmentDetailResponse enrich(Appointment appointment, List<Booking> items) {
        Master master = items.get(0).getMaster();
        Salon salon = master.getSalon();
        User masterUser = master.getUser();
        UUID cityId = salon != null ? salon.getCityId() : masterUser.getCityId();
        UUID districtId = salon != null ? salon.getDistrictId() : masterUser.getDistrictId();

        DiscoveryLabels labels = discoveryLocationResolver.resolveLabels(
                cityId == null ? List.of() : List.of(cityId),
                districtId == null ? List.of() : List.of(districtId));

        return AppointmentDetailResponse.from(
                appointment, items, labels.cityLabel(cityId), labels.districtLabel(districtId));
    }

    private UUID doCreateAppointment(UUID clientId, String idempotencyKey, CreateAppointmentRequest request) {
        Master master = masterRepository.findByIdWithUserAndSalon(request.masterId())
                .filter(Master::isActive)
                .orElseThrow(() -> new NotFoundException("Master not found or inactive"));

        OffsetDateTime firstStart = request.startsAt().toOffsetDateTime();
        // Same lead-time floor + max-window cap as the single-service create path (DRY).
        BookingStartsAtValidator.validate(firstStart, clock);

        // Resolve + chain + price-freeze + Σ-cap + list-size guard (BE-7: shared verbatim with the guest
        // LINK path via VisitPlanner, so the two create paths cannot drift on the pieces that must match
        // BE-2 availability). Duplicates are allowed verbatim; an unknown/foreign/inactive service → 404.
        List<VisitPlanner.PlannedItem> items =
                visitPlanner.planChainedItems(master, request.masterServiceIds(), firstStart);
        OffsetDateTime lastEnd = items.get(items.size() - 1).endsAt();

        // Fix H3 shape: load + validate the client BEFORE the locks to keep the lock window tight.
        User client = userRepository.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Client not found"));
        // Defence in depth: the controller @PreAuthorize already restricts to CLIENT.
        if (client.getRole() != Role.CLIENT) {
            throw new ForbiddenException("Only clients can create appointments");
        }

        // Client lock (salt 1) ALWAYS before the master lock (salt 0) — deadlock-free ordering. The
        // fused query also sets the transaction-scoped lock_timeout, bounding the master lock below.
        acquireClientLock(clientId);

        // Race-free idempotent replay: two concurrent same-key requests serialize on the client lock;
        // the second re-reads the committed visit here and returns it without inserting a duplicate.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existing = appointmentRepository
                    .findActiveByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                    .map(Appointment::getId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Client-conflict check over the WHOLE visit span, BEFORE the master lock is taken — same
        // precedence and rationale as the single-service create path.
        assertNoClientConflict(clientId, firstStart, lastEnd);

        Integer lockResult = bookingRepository.acquireAdvisoryLock(master.getId());
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }

        // Overlap against existing CONFIRMED bookings, checked ONCE over the whole visit span. The
        // chained items are contiguous by construction AT CREATE TIME (assertContiguous:
        // item[i].startsAt == item[i-1].endsAt, no gaps) — assertContiguous is never re-run after
        // creation, so this holds only for THIS transaction, not for the visit's lifetime; a later
        // per-item reschedule (phase 30.1) legally separates items with gaps — so the union of all
        // per-item intervals is EXACTLY [firstStart, lastEnd) — a single span check is logically identical to N per-item checks,
        // but holds the contended per-master advisory lock for one round-trip instead of N. The
        // per-row no_overlapping_bookings GIST EXCLUDE remains the authoritative backstop on each
        // insert (see the DataIntegrityViolation→409 mapping below), so an overlapping chain still
        // yields the same 409; correctness is unchanged.
        if (bookingRepository.existsOverlap(master.getId(), firstStart, lastEnd)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        Appointment appointment = Appointment.builder()
                .client(client)
                .salon(master.getSalon())
                .status(BookingStatus.CONFIRMED)
                .clientComment(BookingComments.normalize(request.clientComment()))
                .idempotencyKey(idempotencyKey)
                .bookingSource(BookingSource.APP)
                .build();

        List<Booking> bookings = new ArrayList<>(items.size());
        for (VisitPlanner.PlannedItem item : items) {
            bookings.add(Booking.builder()
                    .client(client)
                    .master(master)
                    .masterService(item.masterService())
                    // salon is null for an INDEPENDENT_MASTER (V18 nullable salon_id intent).
                    .salon(master.getSalon())
                    .status(BookingStatus.CONFIRMED)
                    .startsAt(item.startsAt())
                    .endsAt(item.endsAt())
                    .priceAtBooking(item.price())
                    .priceMaxAtBooking(item.priceMax())
                    .durationMinutesAtBooking(item.duration())
                    .bufferMinutesAtBooking(item.buffer())
                    .bookingSource(BookingSource.APP)
                    .appointment(appointment)
                    .build());
        }

        List<Booking> savedBookings;
        try {
            // The appointment is saved first so the FK bookings.appointment_id resolves; the flush
            // makes both the EXCLUDE constraint and the idempotency partial-unique index authoritative.
            appointmentRepository.save(appointment);
            savedBookings = bookingRepository.saveAll(bookings);
            bookingRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // Either no_overlapping_bookings (a slot was taken between check and insert) or the
            // idempotency partial-unique index (a same-key race past the re-check) — both map to the
            // same 409 the single-service path returns; no partial visit is ever persisted (atomic).
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        // EXACTLY ONE new-visit notification for the whole appointment — the master is notified once,
        // referencing the first chained booking (NOT one per service). Notes are never logged nor put
        // in the outbox payload (CLAUDE.md booking-notes contract).
        outboxService.enqueueNewBooking(savedBookings.get(0).getId());

        registerSlotEviction(master.getId(), salonIdOf(master), items);

        return appointment.getId();
    }

    private void acquireClientLock(UUID clientId) {
        Integer lockResult = bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId);
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }
    }

    private void assertNoClientConflict(UUID clientId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        bookingRepository.findFirstConflictingClientBookingId(clientId, startsAt, endsAt)
                .ifPresent(conflictId -> {
                    throw clientConflictException(conflictId);
                });
    }

    private ClientBookingConflictException clientConflictException(UUID conflictingBookingId) {
        Booking conflict = bookingRepository.findByIdWithFullGraph(conflictingBookingId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Conflicting booking could not be loaded"));
        return new ClientBookingConflictException(conflict);
    }

    private static UUID salonIdOf(Master master) {
        Salon salon = master.getSalon();
        return salon != null ? salon.getId() : null;
    }

    /**
     * Evicts the master's availability caches for every (date, service) the visit touches, after
     * commit — reusing the exact single-service booking-write eviction hooks so a parallel reader
     * cannot repopulate stale data mid-write. Distinct (local date, masterServiceId) keys are
     * collected across every item (and both the start and end day, in the rare event a service spans
     * midnight), then the per-master free-slot verdict and the salon catalogue are evicted once.
     */
    private void registerSlotEviction(UUID masterId, UUID salonId, List<VisitPlanner.PlannedItem> items) {
        Set<SlotKey> slotKeys = new LinkedHashSet<>();
        for (VisitPlanner.PlannedItem item : items) {
            UUID serviceId = item.masterService().getId();
            slotKeys.add(new SlotKey(item.startsAt().toLocalDate(), serviceId));
            slotKeys.add(new SlotKey(item.endsAt().toLocalDate(), serviceId));
        }
        Runnable task = () -> {
            for (SlotKey key : slotKeys) {
                slotCalculationService.evictAvailableSlots(masterId, key.date(), key.masterServiceId());
            }
            slotCalculationService.evictBookableFutureSlotsByMaster(masterId);
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
            task.run();
        }
    }

    /** Distinct availability-cache eviction key: one Kyiv-civil date × one master-service. */
    private record SlotKey(LocalDate date, UUID masterServiceId) {}
}
