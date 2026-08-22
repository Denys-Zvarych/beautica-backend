package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.domain.MasterBookability;
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
import com.beautica.service.entity.MasterServiceAssignment;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
     * Resolves the district-primary discovery locality labels (the BOOKED salon when the visit was
     * made at one, else the master's own user row — the same rule {@code BookingService} and
     * {@code SearchService} use) and builds the enriched {@link AppointmentDetailResponse}. The
     * street/building/locationNote resolution and the header-note reads live inside
     * {@link AppointmentDetailResponse#from}; only the FK→label lookup is not derivable from the
     * fetched graph and is done here.
     *
     * <p>Package-private (not {@code private}) so {@code AppointmentTransitionService.rescheduleAppointment}
     * (BE-4) can reuse it verbatim to build the re-planned visit's response, instead of
     * duplicating the discovery-label lookup a second time. {@code StaffBookingService} (Phase 22.14)
     * is a third caller, for the identical reason: a staff-created visit's response is the same
     * {@link AppointmentDetailResponse} shape a client visit's is, so its create path reuses this
     * enrichment rather than hand-rolling a second mapper.
     */
    AppointmentDetailResponse enrich(Appointment appointment, List<Booking> items) {
        Master master = items.get(0).getMaster();
        // Phase 242 — the visit's own salon snapshot, the SAME source
        // AppointmentDetailResponse#from resolves street/buildingNo/locationNote from. These ids
        // feed cityLabel/districtLabel; splitting them off master.getSalon() would pair the booked
        // salon's street with the master's current salon's city after a rotation.
        Salon salon = items.get(0).getSalon();
        User masterUser = master.getUser();

        return enrich(appointment, items, resolveVisitLabels(salon, masterUser));
    }

    /**
     * ADDITIVE overload of {@link #enrich(Appointment, List)} for a caller that has already
     * resolved the discovery labels (perf LOW, 2026-08-22).
     *
     * <p>Exists solely so {@code StaffBookingService} can lift {@link #resolveVisitLabels}' two
     * taxonomy SELECTs OUT of its per-master {@code pg_advisory_xact_lock} window: they are pure
     * reads that need no lock, and the visit's locality is fully determined before the lock is
     * taken. The two-argument form is unchanged and still resolves the labels itself, so every
     * existing caller ({@code enrichCreated}, the BE-5 read path, {@code
     * AppointmentTransitionService#rescheduleAppointment}) keeps working untouched — no required
     * parameter was added to the current signature.
     *
     * <p>{@code labels} MUST have been produced by {@link #resolveVisitLabels} for the SAME visit's
     * {@code (salon, masterUser)} pair; passing another visit's labels yields null city/district
     * labels rather than wrong ones, because the lookup below re-derives the ids from {@code items}
     * and a {@link DiscoveryLabels} miss returns {@code null}.
     */
    AppointmentDetailResponse enrich(
            Appointment appointment, List<Booking> items, DiscoveryLabels labels) {
        Salon salon = items.get(0).getSalon();
        User masterUser = items.get(0).getMaster().getUser();
        UUID cityId = visitCityId(salon, masterUser);
        UUID districtId = visitDistrictId(salon, masterUser);

        return AppointmentDetailResponse.from(
                appointment, items, labels.cityLabel(cityId), labels.districtLabel(districtId));
    }

    /**
     * The district-primary discovery-label lookup for one visit, in ONE place so a caller that
     * pre-resolves it (see {@link #enrich(Appointment, List, DiscoveryLabels)}) cannot drift from
     * the rule {@code enrich} itself applies.
     *
     * <p>Both ids are plain {@code UUID} columns on {@link Salon} / {@link User}, so reading them
     * costs nothing beyond the two taxonomy SELECTs {@code resolveLabels} issues (each skipped when
     * its id is null).
     */
    DiscoveryLabels resolveVisitLabels(Salon salon, User masterUser) {
        UUID cityId = visitCityId(salon, masterUser);
        UUID districtId = visitDistrictId(salon, masterUser);
        return discoveryLocationResolver.resolveLabels(
                cityId == null ? List.of() : List.of(cityId),
                districtId == null ? List.of() : List.of(districtId));
    }

    private static UUID visitCityId(Salon salon, User masterUser) {
        return salon != null ? salon.getCityId() : masterUser.getCityId();
    }

    private static UUID visitDistrictId(Salon salon, User masterUser) {
        return salon != null ? salon.getDistrictId() : masterUser.getDistrictId();
    }

    private UUID doCreateAppointment(UUID clientId, String idempotencyKey, CreateAppointmentRequest request) {
        // SALON-ACTIVE GUARD (2026-08 security re-audit HIGH). This path takes a client-supplied
        // request.masterId() exactly like BookingService#doCreateBooking, so it needs the identical
        // gate — a closed salon's master still passes Master::isActive (deactivateSalon does not
        // cascade), and without this term a caller could create a CONFIRMED multi-service visit
        // against a salon the owner had closed by replaying the single-service attack one endpoint
        // over. See MasterBookability for the canonical rule and the full list of enforcing sites.
        //
        // Rejection is folded into the same filter chain as the existence check so both surface the
        // identical "Master not found or inactive" 404 — a distinct message would hand the caller an
        // oracle separating "no such master" from "that master's salon was closed".
        //
        // No extra query: findByIdWithUserAndSalon already LEFT JOIN FETCHes the salon.
        Master master = masterRepository.findByIdWithUserAndSalon(request.masterId())
                .filter(MasterBookability::isBookable)
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

        // SCHEDULE-FIT GATE (2026-08-11 HIGH) — the multi-service counterpart of the single-service
        // create gate in BookingService#doCreateBooking. Neither BookingStartsAtValidator above nor the
        // client-conflict / span-overlap checks ask whether the master WORKS this window, so a visit
        // could be created on a day-off or inside a lunch-break gap. Uses the BE-2 N-service overload,
        // never N single-service checks: each leg can fit alone while the CHAIN overruns the working
        // window.
        //
        // PLACEMENT IS LOAD-BEARING, and mirrors BookingService#doCreateBooking's (see its comment):
        //   * AFTER the idempotent-replay lookup above — a replay's OWN items occupy the slot, so the
        //     slot list no longer contains it; gating earlier would turn a legitimate retry into a 409
        //     instead of returning the already-created visit.
        //   * AFTER the assertNoClientConflict call immediately above — the slot list already has the
        //     master's CONFIRMED bookings subtracted, so a span that is BOTH the client's own conflict
        //     and master-busy would fail HERE with the generic "Slot not available" and mask the
        //     structured, actionable CLIENT_BOOKING_CONFLICT that the locked product decision requires
        //     to win. Never reorder these two, and never replace one with the other: BOTH must run, in
        //     this order. Pinned by AppointmentCreateIT
        //     #should_returnClientBookingConflictNotSlotNotAvailable_when_bothClientConflictAndOffSlotApply.
        //   * BEFORE the per-master advisory lock — an off-schedule request never contends for it.
        //   * The planner's OWN assignments are handed through (Perf MEDIUM, 2026-08-11) so the gate does
        //     not re-run findByMasterIdAndIdWithGraph once per chained service — planChainedItems already
        //     resolved every one of them above, in this same persistence context.
        assertVisitStartsOnAvailableSlot(
                master.getId(), request.masterServiceIds(), VisitPlanner.assignmentsOf(items), firstStart);

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
        // in the outbox payload (CLAUDE.md booking-notes contract). The drain worker re-hydrates the
        // sibling rows from that one aggregateId (BookingVisitResolver), so ONE row still describes
        // every service in the visit — do not add a second NEW_BOOKING row per item.
        //
        // Two rows, two distinct recipients — the exact pairing the single-service create path uses
        // (BookingService#doCreateBooking): NEW_BOOKING → the master, STATUS_CHANGED → the client,
        // whose CONFIRMED branch dispatches «Бронювання підтверджено». A visit is auto-confirmed at
        // creation just like a single booking, so the second row is the client-facing half of the
        // same create event, not a genuine transition. Its omission was why a multi-service visit
        // sent the client no confirmation e-mail at all while a one-service booking did.
        //
        // <b>Lock-window note (backend-perf audit, P5).</b> Both INSERTs run while the per-master
        // advisory lock is still held, and that is not removable: the lock is
        // pg_advisory_xact_lock, which releases only at COMMIT/ROLLBACK — there is no
        // mid-transaction unlock to move these calls past. Nor can they be deferred to an
        // afterCommit hook: the transactional-outbox pattern requires the outbox rows to commit
        // ATOMICALLY with the bookings they describe (that is why enqueue* is Propagation.MANDATORY
        // — an outbox write outside the write transaction can be lost when the transaction rolls
        // back, or fire for a visit that was never persisted). Two single-row INSERTs against an
        // append-only table are also negligible next to the flush that precedes them. This mirrors
        // the single-service create path (BookingService#doCreateBooking) exactly, so the two paths
        // hold their lock for the same shape of work.
        outboxService.enqueueNewBooking(savedBookings.get(0).getId());
        outboxService.enqueueStatusChanged(savedBookings.get(0).getId());

        registerSlotEviction(master.getId(), salonIdOf(master));

        return appointment.getId();
    }

    private void acquireClientLock(UUID clientId) {
        Integer lockResult = bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId);
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }
    }

    /**
     * Whole-visit schedule-fit guard for {@link #doCreateAppointment} — delegates to the shared
     * {@link BookingSlotAvailabilityGuard}, the SAME implementation
     * {@code AppointmentTransitionService#rescheduleAppointment} uses for the identical question, so the
     * create and reschedule paths cannot drift on what "the master works this visit window" means.
     * A non-matching start is a {@code 409 "Slot not available"}.
     */
    private void assertVisitStartsOnAvailableSlot(
            UUID masterId, List<UUID> masterServiceIds, List<MasterServiceAssignment> preloaded,
            OffsetDateTime startsAt) {
        BookingSlotAvailabilityGuard.assertVisitStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceIds, preloaded, startsAt);
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
     * Evicts the master's availability caches after commit, so a parallel reader cannot repopulate
     * stale data mid-write — reusing the exact single-service booking-write eviction hook.
     *
     * <p>Takes no per-item key set: the visit occupies a contiguous block of the master's time, which
     * moves the offered slots for every service the master performs on those dates, not merely the
     * booked ones. {@link SlotCalculationService#evictMasterAvailabilityCaches} sweeps all three
     * availability caches by master prefix in one pass, which subsumes the per-{@code (date, service)}
     * enumeration this method used to build (including the start/end-day pair).
     */
    private void registerSlotEviction(UUID masterId, UUID salonId) {
        Runnable task = () -> {
            slotCalculationService.evictMasterAvailabilityCaches(masterId);
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
}
