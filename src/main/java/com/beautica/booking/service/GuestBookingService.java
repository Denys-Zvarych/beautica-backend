package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingPriceRange;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates auto-confirmed guest (LINK) bookings from the public booking link
 * (Phase 13.3). A phone-verified guest holding a {@code type=GUEST} JWT picks a
 * free slot; the booking is persisted as {@code CONFIRMED}, a confirmation SMS is
 * sent, and the master is notified via the outbox.
 *
 * <p><b>Concurrency.</b> Double-booking is prevented by the exact mechanism the
 * regular {@code BookingService.doCreateBooking} uses: a per-master Postgres
 * advisory transaction lock ({@code bookingRepository.acquireAdvisoryLockWithTimeout}) is
 * acquired BEFORE the overlap check, so check + insert are atomic against a
 * concurrent guest booking for the same master+slot. A {@code DataIntegrityViolation}
 * on save is a final backstop mapped to 409.
 */
@Service
@Slf4j
public class GuestBookingService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final GuestTokenProvider guestTokenProvider;
    private final MasterRepository masterRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final SlotCalculationService slotCalculationService;
    private final NotificationOutboxService outboxService;
    private final SmsService smsService;
    private final BookingSmsProperties smsProperties;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final VisitPlanner visitPlanner;
    private final String frontendBaseUrl;
    private final Clock kyivClock;

    public GuestBookingService(
            GuestTokenProvider guestTokenProvider,
            MasterRepository masterRepository,
            MasterServiceRepository masterServiceRepository,
            BookingRepository bookingRepository,
            AppointmentRepository appointmentRepository,
            SlotCalculationService slotCalculationService,
            NotificationOutboxService outboxService,
            SmsService smsService,
            BookingSmsProperties smsProperties,
            SalonCatalogCacheEvictor salonCatalogCacheEvictor,
            VisitPlanner visitPlanner,
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            Clock clock) {
        this.guestTokenProvider = guestTokenProvider;
        this.masterRepository = masterRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.bookingRepository = bookingRepository;
        this.appointmentRepository = appointmentRepository;
        this.slotCalculationService = slotCalculationService;
        this.outboxService = outboxService;
        this.smsService = smsService;
        this.smsProperties = smsProperties;
        this.salonCatalogCacheEvictor = salonCatalogCacheEvictor;
        this.visitPlanner = visitPlanner;
        this.frontendBaseUrl = frontendBaseUrl;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    /**
     * Public available-slot lookup for the guest booking page. Resolves the master
     * from the slug, validates the requested {@code date} is within
     * {@code [today, today + availabilityMaxDays]} in Kyiv time, then delegates to
     * the cached {@link SlotCalculationService}. No auth required.
     *
     * @throws NotFoundException when the slug is unknown / master inactive (→ 404)
     * @throws BusinessException with 400 when the date is in the past or too far ahead
     */
    @Transactional(readOnly = true)
    public List<AvailableSlotResponse> availableSlots(String slug, LocalDate date, UUID serviceId) {
        Master master = resolveMasterForDate(slug, date);
        return slotCalculationService.getAvailableSlots(master.getId(), date, serviceId);
    }

    /**
     * Multi-service single-visit available-slot lookup for the guest booking page (BE-7). Sizes each
     * candidate slot to the SUM of the ordered {@code serviceIds}' effective durations — one contiguous
     * back-to-back block — so the guest sees exactly the slots a multi-service visit can occupy. The
     * controller routes a single-element list to {@link #availableSlots(String, LocalDate, UUID)} (the
     * cached legacy overload), so this path only ever handles {@code N>1}. No auth required.
     */
    @Transactional(readOnly = true)
    public List<AvailableSlotResponse> availableSlots(String slug, LocalDate date, List<UUID> serviceIds) {
        Master master = resolveMasterForDate(slug, date);
        return slotCalculationService.getAvailableSlots(master.getId(), date, serviceIds);
    }

    /**
     * Shared date-window guard + slug→active-master resolution for both availability overloads. Validates
     * the requested {@code date} is within {@code [today, today + availabilityMaxDays]} in Kyiv time.
     *
     * @throws NotFoundException when the slug is unknown / master inactive (→ 404)
     * @throws BusinessException with 400 when the date is in the past or too far ahead
     */
    private Master resolveMasterForDate(String slug, LocalDate date) {
        LocalDate today = LocalDate.now(kyivClock);
        if (date.isBefore(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "date is in the past");
        }
        if (date.isAfter(today.plusDays(smsProperties.getAvailabilityMaxDays()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "date too far ahead");
        }
        // No .filter(Master::isActive) here: findByBookingSlugWithUser's own JPQL already carries
        // `AND m.isActive = true AND (s IS NULL OR s.isActive = true)` — the full MasterBookability
        // rule — so the Optional is either empty or holds a bookable master. The filter was a
        // strictly weaker duplicate of half that predicate. Guarded by MasterRepositoryBookingSlugTest.
        return masterRepository.findByBookingSlugWithUser(slug)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));
    }

    /**
     * Validates the guest token, resolves the master + service, atomically guards
     * the slot, persists an auto-confirmed LINK booking, then (after commit) sends
     * the confirmation SMS and evicts the slot cache. The master is notified via the
     * outbox within the transaction.
     *
     * @param bearerToken the raw {@code Authorization} header value (may include the
     *                    {@code Bearer } prefix); validated as a GUEST token
     * @throws JwtException     when the token is missing/expired/not a guest token (→ 401)
     * @throws NotFoundException when the slug or service is unknown (→ 404)
     * @throws BusinessException with 409 when the slot is taken
     */
    @Transactional
    public GuestBookingResponse createGuestBooking(String bearerToken, String slug, GuestBookingRequest req) {
        String guestPhone = extractGuestPhone(bearerToken);

        // MEDIUM-fix (Phase 13.3): enforce the SAME lead-time floor + max-window cap as the
        // authenticated BookingService path. The DTO's @Future alone permitted a CONFIRMED
        // guest booking for now+1min or now+5y. Fail-fast before any DB lookup or SMS.
        // kyivClock and the injected clock share an Instant, so the zone is irrelevant here.
        BookingStartsAtValidator.validate(req.startsAt(), kyivClock);

        // See resolveMasterForDate: the liveness + salon-active rule lives in the finder's JPQL,
        // so no redundant .filter(Master::isActive) is applied on top of it.
        Master master = masterRepository.findByBookingSlugWithUser(slug)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));

        // BE-7: a masterServiceIds list (even a single element) is a multi-service VISIT — ONE Appointment
        // header + N chained CONFIRMED bookings sharing one cancel token and one reminder. A legacy
        // serviceId-only request keeps the byte-for-byte single-booking path (appointment_id NULL). Exactly
        // one of the two must be supplied; the DTO cannot express that XOR, so it is enforced here.
        if (req.masterServiceIds() != null && !req.masterServiceIds().isEmpty()) {
            return createGuestVisit(master, req, req.masterServiceIds(), guestPhone);
        }
        if (req.serviceId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "serviceId or masterServiceIds is required");
        }
        return createSingleServiceBooking(master, req, guestPhone);
    }

    /**
     * Legacy single-service guest booking (byte-for-byte unchanged from Phase 13.3): resolves the one
     * service, persists a CONFIRMED LINK {@code Booking} with {@code appointment_id} NULL, notifies the
     * master, and — after commit — sends the confirmation SMS and evicts the slot caches.
     */
    private GuestBookingResponse createSingleServiceBooking(
            Master master, GuestBookingRequest req, String guestPhone) {
        // findByMasterIdAndIdWithGraph filters WHERE ms.master.id = :masterId, so an
        // empty result means the service does not belong to this master (or does not
        // exist) — this IS the service-ownership check.
        MasterServiceAssignment msa = masterServiceRepository
                .findByMasterIdAndIdWithGraph(master.getId(), req.serviceId())
                .filter(MasterServiceAssignment::isActive)
                .orElseThrow(() -> new NotFoundException("Service not available for this master"));

        Booking saved = persistBooking(master, msa, req, guestPhone);

        outboxService.enqueueNewBooking(saved.getId());

        String cancelUrl = buildCancelUrl(saved.getCancelToken().toString());
        registerAfterCommit(master, msa, saved, guestPhone, cancelUrl);

        return buildResponse(master, msa, saved, cancelUrl);
    }

    /**
     * Guest multi-service single-visit create (BE-7) — the LINK counterpart of {@code AppointmentService}.
     * Plans the chained items via the SHARED {@link VisitPlanner} (identical price/duration freeze,
     * D4 buffer policy, Σ-cap and contiguity the APP path uses), guards the whole visit span under the
     * per-master advisory lock (fused 3s timeout; NO client lock — a guest has no account to key one on),
     * then persists ONE guest {@link Appointment} header (source LINK, {@code client_id} NULL, guest
     * identity, one cancel token) plus N chained CONFIRMED {@link Booking} items atomically. Exactly ONE
     * new-visit notification is enqueued; after commit ONE confirmation SMS is sent and the availability
     * caches are evicted for every day/service the visit touches.
     *
     * <p><b>Per-item cancel tokens.</b> Each child booking is created via {@link Booking#guestBooking}
     * and therefore carries its OWN random cancel token — required because {@code chk_bookings_guest_fields}
     * (V91) mandates a non-null token on every CONFIRMED LINK row. Those per-item tokens are never
     * surfaced; the guest holds only the visit-level token on the header, which cancels ALL items.
     */
    private GuestBookingResponse createGuestVisit(
            Master master, GuestBookingRequest req, List<UUID> serviceIds, String guestPhone) {
        OffsetDateTime firstStart = req.startsAt();
        List<VisitPlanner.PlannedItem> items = visitPlanner.planChainedItems(master, serviceIds, firstStart);
        OffsetDateTime lastEnd = items.get(items.size() - 1).endsAt();

        // Per-master advisory lock BEFORE the overlap check — same fused-timeout mechanism the single
        // guest path uses (this endpoint is permitAll, so the 3s lock_timeout bounds the wait against the
        // advisory-lock DoS class). No client lock: a guest has no account to serialize on.
        Integer lock = bookingRepository.acquireAdvisoryLockWithTimeout(master.getId());
        if (lock == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }
        // ONE span overlap check over [firstStart, lastEnd): the chained items are contiguous by
        // construction, so their union equals the span (identical rationale to AppointmentService). The
        // per-row no_overlapping_bookings GIST EXCLUDE still backstops each insert (the catch below → 409).
        if (bookingRepository.existsOverlap(master.getId(), firstStart, lastEnd)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        UUID cancelToken = UUID.randomUUID();
        Appointment appointment = Appointment.guestAppointment(
                master.getSalon(), req.name(), req.surname(), guestPhone, cancelToken);

        List<Booking> children = new ArrayList<>(items.size());
        for (VisitPlanner.PlannedItem item : items) {
            Booking child = Booking.guestBooking(
                    master, item.masterService(), master.getSalon(),
                    item.startsAt(), item.endsAt(), item.price(), item.priceMax(),
                    item.duration(), item.buffer(), req.name(), req.surname(), guestPhone);
            child.setAppointment(appointment);
            children.add(child);
        }

        List<Booking> saved;
        try {
            // The header is saved first so bookings.appointment_id resolves; the flush makes the EXCLUDE
            // constraint authoritative — an overlapping chain rolls the whole visit back (atomic).
            appointmentRepository.save(appointment);
            saved = bookingRepository.saveAll(children);
            bookingRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        // EXACTLY ONE new-visit notification (referencing the first item — never one per service).
        outboxService.enqueueNewBooking(saved.get(0).getId());

        String cancelUrl = buildCancelUrl(cancelToken.toString());
        registerVisitAfterCommit(master, items, saved.get(0), guestPhone, cancelUrl);
        return buildVisitResponse(master, appointment, saved.get(0), items, cancelUrl);
    }

    private String extractGuestPhone(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new io.jsonwebtoken.security.SignatureException("Missing guest token");
        }
        String token = bearerToken.startsWith("Bearer ") ? bearerToken.substring(7).trim() : bearerToken.trim();
        return guestTokenProvider.validate(token);
    }

    /**
     * Persists the guest booking under the per-master advisory lock.
     *
     * <p><b>Lock-wait bound.</b> {@link BookingRepository#acquireAdvisoryLockWithTimeout(UUID)}
     * fuses {@code set_config('lock_timeout', '3s', true)} (transaction-scoped, equivalent to
     * {@code SET LOCAL}) into the SAME statement/round-trip as the master advisory-lock
     * acquisition, so the wait is bounded to 3s instead of blocking indefinitely — one round
     * trip instead of two. This closes the same advisory-lock DoS class documented on
     * {@link BookingRepository#acquireAdvisoryLockWithTimeout(UUID)}: this endpoint
     * ({@code POST /api/v1/book/{slug}/booking}) is {@code permitAll}, gated only by a
     * non-IP-bound guest JWT and a per-IP rate limit, so a replayed guest token fired
     * concurrently from multiple source IPs would otherwise be able to park unbounded Hikari
     * connections on the same master's advisory lock.
     */
    private Booking persistBooking(Master master, MasterServiceAssignment msa,
                                   GuestBookingRequest req, String guestPhone) {
        ServiceDefinition def = msa.getServiceDefinition();
        BigDecimal price = msa.getPriceOverride() != null ? msa.getPriceOverride() : def.getBasePrice();
        int duration = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes() : def.getBaseDurationMinutes();
        int buffer = def.getBufferMinutesAfter();
        OffsetDateTime startsAt = req.startsAt();
        OffsetDateTime endsAt = startsAt.plusMinutes((long) duration + buffer);

        // Per-master advisory lock BEFORE the overlap check — same mechanism as
        // BookingService.doCreateBooking, so check + insert are atomic (no TOCTOU race).
        // acquireAdvisoryLockWithTimeout() bounds this wait to 3s in the same round trip
        // (see class-level Javadoc above).
        Integer lock = bookingRepository.acquireAdvisoryLockWithTimeout(master.getId());
        if (lock == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }
        if (bookingRepository.existsOverlap(master.getId(), startsAt, endsAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        // Freeze the RANGE ceiling beside the floor (V119), by the same rule and at the same
        // moment as the registered-client path (BookingService#doCreateBooking). Null = single
        // price. A LINK booking must be as immune to a later service edit as an APP one.
        Booking booking = Booking.guestBooking(
                master, msa, master.getSalon(), startsAt, endsAt,
                price, BookingPriceRange.resolveCeiling(msa),
                duration, buffer, req.name(), req.surname(), guestPhone);
        try {
            return bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }

    private void registerAfterCommit(Master master, MasterServiceAssignment msa,
                                     Booking saved, String guestPhone, String cancelUrl) {
        String smsText = buildConfirmationSms(master, msa, saved, cancelUrl);
        // Capture the salon id inside the transaction (before the afterCommit callback runs) —
        // null for an independent master (no salon catalogue entry).
        UUID salonId = saved.getSalon() != null ? saved.getSalon().getId() : null;
        Runnable task = () -> {
            sendConfirmationSms(guestPhone, smsText);
            slotCalculationService.evictAvailableSlots(
                    master.getId(), saved.getStartsAt().toLocalDate(), msa.getId());
            // A new guest booking changed occupancy → evict the master's free-slot bookability
            // verdict (master-prefix; window keys can't be evicted per-date).
            slotCalculationService.evictBookableFutureSlotsByMaster(master.getId());
            // A flipped verdict can add/remove a service from the salon catalogue (perf/security #2).
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

    /**
     * After-commit side-effects for a guest VISIT create (BE-7): ONE confirmation SMS (built from the
     * first item, reusing the single-service template) plus availability-cache eviction fanned out over
     * every item — reusing the exact single-service eviction hooks so a parallel reader cannot repopulate
     * stale data mid-write. Distinct (Kyiv-civil date, masterServiceId) keys are collected across all
     * items (both start and end day, in case a service spans midnight); the per-master free-slot verdict
     * and the salon catalogue are each evicted once.
     */
    private void registerVisitAfterCommit(Master master, List<VisitPlanner.PlannedItem> items,
                                          Booking firstSaved, String guestPhone, String cancelUrl) {
        String smsText = buildConfirmationSms(master, items.get(0).masterService(), firstSaved, cancelUrl);
        UUID salonId = master.getSalon() != null ? master.getSalon().getId() : null;
        Set<SlotKey> slotKeys = new LinkedHashSet<>();
        for (VisitPlanner.PlannedItem item : items) {
            UUID serviceId = item.masterService().getId();
            slotKeys.add(new SlotKey(item.startsAt().toLocalDate(), serviceId));
            slotKeys.add(new SlotKey(item.endsAt().toLocalDate(), serviceId));
        }
        Runnable task = () -> {
            sendConfirmationSms(guestPhone, smsText);
            for (SlotKey key : slotKeys) {
                slotCalculationService.evictAvailableSlots(master.getId(), key.date(), key.masterServiceId());
            }
            slotCalculationService.evictBookableFutureSlotsByMaster(master.getId());
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

    private void sendConfirmationSms(String guestPhone, String smsText) {
        try {
            smsService.send(guestPhone, smsText);
        } catch (RuntimeException e) {
            // The booking is already committed and confirmed — an SMS-provider failure
            // must not fail the request. Log the cause class only (never the phone or text).
            log.warn("Guest confirmation SMS failed: {}", e.getClass().getSimpleName());
        }
    }

    private String buildConfirmationSms(Master master, MasterServiceAssignment msa,
                                        Booking saved, String cancelUrl) {
        OffsetDateTime kyiv = saved.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return smsProperties.getSms().getConfirmation()
                .replace("{masterName}", masterName(master))
                .replace("{serviceName}", msa.getServiceDefinition().getName())
                .replace("{date}", DATE_FMT.format(kyiv))
                .replace("{time}", TIME_FMT.format(kyiv))
                .replace("{cancelUrl}", cancelUrl);
    }

    private GuestBookingResponse buildResponse(Master master, MasterServiceAssignment msa,
                                               Booking saved, String cancelUrl) {
        return new GuestBookingResponse(
                saved.getId(),
                saved.getStartsAt(),
                masterName(master),
                msa.getServiceDefinition().getName(),
                saved.getDurationMinutesAtBooking(),
                cancelUrl);
    }

    /**
     * Visit response (BE-7): {@code appointmentId} identifies the visit, {@code bookingId} is its first
     * chained item, {@code serviceName} the first service, and {@code durationMinutes} the SUM of the
     * item service durations (buffers excluded — same meaning as the single path's field). The
     * visit-level cancel token is embedded in {@code cancelUrl}.
     */
    private GuestBookingResponse buildVisitResponse(Master master, Appointment appointment,
                                                    Booking firstSaved, List<VisitPlanner.PlannedItem> items,
                                                    String cancelUrl) {
        int totalDuration = items.stream().mapToInt(VisitPlanner.PlannedItem::duration).sum();
        return new GuestBookingResponse(
                firstSaved.getId(),
                appointment.getId(),
                firstSaved.getStartsAt(),
                masterName(master),
                items.get(0).masterService().getServiceDefinition().getName(),
                totalDuration,
                cancelUrl);
    }

    private String buildCancelUrl(String cancelToken) {
        return frontendBaseUrl + "/book/cancel/" + cancelToken;
    }

    private static String masterName(Master master) {
        User u = master.getUser();
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null ? "" : u.getLastName().trim();
        return (first + " " + last).trim();
    }

    /** Distinct availability-cache eviction key: one Kyiv-civil date × one master-service. */
    private record SlotKey(LocalDate date, UUID masterServiceId) {}
}
