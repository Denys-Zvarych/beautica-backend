package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingPriceRange;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.entity.Booking;
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
import java.util.List;
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
    private final SlotCalculationService slotCalculationService;
    private final NotificationOutboxService outboxService;
    private final SmsService smsService;
    private final BookingSmsProperties smsProperties;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final String frontendBaseUrl;
    private final Clock kyivClock;

    public GuestBookingService(
            GuestTokenProvider guestTokenProvider,
            MasterRepository masterRepository,
            MasterServiceRepository masterServiceRepository,
            BookingRepository bookingRepository,
            SlotCalculationService slotCalculationService,
            NotificationOutboxService outboxService,
            SmsService smsService,
            BookingSmsProperties smsProperties,
            SalonCatalogCacheEvictor salonCatalogCacheEvictor,
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            Clock clock) {
        this.guestTokenProvider = guestTokenProvider;
        this.masterRepository = masterRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.bookingRepository = bookingRepository;
        this.slotCalculationService = slotCalculationService;
        this.outboxService = outboxService;
        this.smsService = smsService;
        this.smsProperties = smsProperties;
        this.salonCatalogCacheEvictor = salonCatalogCacheEvictor;
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
        LocalDate today = LocalDate.now(kyivClock);
        if (date.isBefore(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "date is in the past");
        }
        if (date.isAfter(today.plusDays(smsProperties.getAvailabilityMaxDays()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "date too far ahead");
        }
        Master master = masterRepository.findByBookingSlugWithUser(slug)
                .filter(Master::isActive)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));
        return slotCalculationService.getAvailableSlots(master.getId(), date, serviceId);
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

        Master master = masterRepository.findByBookingSlugWithUser(slug)
                .filter(Master::isActive)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));

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

    private String buildCancelUrl(String cancelToken) {
        return frontendBaseUrl + "/book/cancel/" + cancelToken;
    }

    private static String masterName(Master master) {
        User u = master.getUser();
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null ? "" : u.getLastName().trim();
        return (first + " " + last).trim();
    }
}
