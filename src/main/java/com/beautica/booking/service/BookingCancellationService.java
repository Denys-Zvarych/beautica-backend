package com.beautica.booking.service;

import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Public guest-cancellation flow for a LINK booking (Phase 13.4). A guest follows the
 * one-time cancel link in their confirmation SMS; the {@code cancel_token} is consumed
 * (nulled) on first successful cancellation, so the link cannot be replayed.
 *
 * <p><b>One-time consume / concurrency.</b> {@link #cancel(UUID)} does NOT use a
 * check-then-act "load → flip status → save" sequence — two concurrent {@code POST}s
 * could both read {@code status=CONFIRMED} and both fire side-effects. Instead, the
 * status flip + token null is a single conditional UPDATE
 * ({@link BookingRepository#consumeCancelToken(UUID)}) whose {@code WHERE} guards on the
 * token still being present and {@code CONFIRMED}. Exactly one racer updates 1 row and
 * proceeds to the cancellation SMS + master notification; every other updates 0 rows and
 * is mapped to 404. A replayed link (token already null) likewise updates 0 rows → 404,
 * so the endpoint is idempotent by consequence with no extra state machine.
 *
 * <p><b>Side-effect ordering.</b> The master-notification outbox row is written inside
 * the cancellation transaction (the outbox is delivered by the drain worker afterwards),
 * while the guest cancellation SMS is dispatched only {@code afterCommit} — a rolled-back
 * cancellation sends no SMS. SMS-provider failures are swallowed (cause class logged
 * only, never the phone or text) because the booking is already committed as CANCELLED.
 */
@Service
@Slf4j
public class BookingCancellationService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final GuestVisitCancellationService guestVisitCancellationService;
    private final BookingRepository bookingRepository;
    private final NotificationOutboxService outboxService;
    private final SmsService smsService;
    private final SlotCalculationService slotCalculationService;
    private final BookingSmsProperties smsProperties;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final Clock kyivClock;

    public BookingCancellationService(
            GuestVisitCancellationService guestVisitCancellationService,
            BookingRepository bookingRepository,
            NotificationOutboxService outboxService,
            SmsService smsService,
            SlotCalculationService slotCalculationService,
            BookingSmsProperties smsProperties,
            SalonCatalogCacheEvictor salonCatalogCacheEvictor,
            Clock clock) {
        this.guestVisitCancellationService = guestVisitCancellationService;
        this.bookingRepository = bookingRepository;
        this.outboxService = outboxService;
        this.smsService = smsService;
        this.slotCalculationService = slotCalculationService;
        this.smsProperties = smsProperties;
        this.salonCatalogCacheEvictor = salonCatalogCacheEvictor;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    /**
     * Returns the cancel-page summary for a valid, unconsumed cancel token.
     *
     * @throws NotFoundException when the token is unknown, already consumed, or the
     *                           booking is not {@code CONFIRMED} (→ 404, no info leak
     *                           about which of those conditions held)
     */
    @Transactional(readOnly = true)
    public CancelTokenInfoResponse getInfo(UUID token) {
        // BE-7: a token on an Appointment is a multi-service visit — served by the visit service. Only a
        // token that resolves to no visit falls through to the legacy single-booking path below.
        return guestVisitCancellationService.getInfo(token)
                .orElseGet(() -> buildInfo(loadCancellableOrThrow(token)));
    }

    /**
     * Cancels a guest booking by its one-time token.
     *
     * @throws NotFoundException  when the token is unknown / consumed or the booking is
     *                            not {@code CONFIRMED} (→ 404), including a replayed POST
     * @throws BusinessException  with 422 when the cancellation window has closed
     */
    @Transactional
    public void cancel(UUID token) {
        // BE-7: a token on an Appointment cancels the WHOLE visit (header + all items) via the visit
        // service; it returns true when it handled the token. Only a non-visit token falls through to
        // the legacy single-booking cancel below (byte-for-byte unchanged).
        if (guestVisitCancellationService.cancel(token)) {
            return;
        }
        Booking booking = loadCancellableOrThrow(token);

        if (!isCancellable(booking)) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Скасування недоступне — менше ніж %d год до запису"
                            .formatted(smsProperties.getCancelWindowHours()));
        }

        // Atomic one-time consume: only the winner of any concurrent race updates 1 row.
        int updated = bookingRepository.consumeCancelToken(token);
        if (updated == 0) {
            // Lost the race or already consumed between load and update — no side-effects.
            throw new NotFoundException("Cancel token not found");
        }

        // Master notification: outbox row written INSIDE this transaction (MANDATORY
        // propagation); the drain worker delivers the push after commit. Reuses the
        // existing CLIENT_CANCELLED event — the same one the authenticated cancel path uses.
        outboxService.enqueueClientCancelled(booking.getId());

        // Guest cancellation SMS dispatched only after commit — a rolled-back cancel
        // sends nothing.
        registerAfterCommitSms(booking);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private Booking loadCancellableOrThrow(UUID token) {
        Booking booking = bookingRepository.findByCancelTokenWithGraph(token)
                .orElseThrow(() -> new NotFoundException("Cancel token not found"));
        // A consumed/terminal booking has no valid cancel path. 404 (not 409) — the same
        // response a fully unknown token yields, so the caller cannot probe token state.
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new NotFoundException("Cancel token not found");
        }
        return booking;
    }

    private boolean isCancellable(Booking booking) {
        OffsetDateTime now = OffsetDateTime.now(kyivClock);
        return booking.getStartsAt().isAfter(now.plusHours(smsProperties.getCancelWindowHours()));
    }

    private CancelTokenInfoResponse buildInfo(Booking booking) {
        OffsetDateTime windowClosesAt =
                booking.getStartsAt().minusHours(smsProperties.getCancelWindowHours());
        return new CancelTokenInfoResponse(
                masterName(booking.getMaster()),
                booking.getMasterService().getServiceDefinition().getName(),
                booking.getStartsAt(),
                isCancellable(booking),
                windowClosesAt);
    }

    private void registerAfterCommitSms(Booking booking) {
        String phone = booking.getGuestPhone();
        String smsText = buildCancellationSms(booking);
        UUID masterId = booking.getMaster().getId();
        UUID masterServiceId = booking.getMasterService().getId();
        UUID salonId = booking.getSalon() != null ? booking.getSalon().getId() : null;
        LocalDate date = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toLocalDate();
        Runnable task = () -> {
            sendCancellationSms(phone, smsText);
            // Cancelling frees a slot → the freed slot must reappear in the picker and the master's
            // free-slot bookability verdict may flip (un-hiding a service). Evict both.
            slotCalculationService.evictAvailableSlots(masterId, date, masterServiceId);
            slotCalculationService.evictBookableFutureSlotsByMaster(masterId);
            // Un-hiding a service also changes the salon catalogue (perf/security #2).
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

    private void sendCancellationSms(String phone, String smsText) {
        try {
            smsService.send(phone, smsText);
        } catch (RuntimeException e) {
            // The booking is already committed as CANCELLED — an SMS-provider failure must
            // not fail the request. Log the cause class only (never the phone or text).
            log.warn("Guest cancellation SMS failed: {}", e.getClass().getSimpleName());
        }
    }

    private String buildCancellationSms(Booking booking) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return smsProperties.getSms().getCancellation()
                .replace("{serviceName}", booking.getMasterService().getServiceDefinition().getName())
                .replace("{masterName}", masterName(booking.getMaster()))
                .replace("{date}", DATE_FMT.format(kyiv))
                .replace("{time}", TIME_FMT.format(kyiv));
    }

    private static String masterName(Master master) {
        User u = master.getUser();
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null ? "" : u.getLastName().trim();
        return (first + " " + last).trim();
    }
}
