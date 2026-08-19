package com.beautica.booking.service;

import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.Placeholders;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
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
 * cancellation sends no SMS. The send itself is a non-blocking hand-off to
 * {@link BookingSmsDispatcher}, which owns the swallow-and-log (cause class only, never the
 * phone or text) because the booking is already committed as CANCELLED.
 */
@Service
public class BookingCancellationService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final GuestVisitCancellationService guestVisitCancellationService;
    private final BookingRepository bookingRepository;
    private final NotificationOutboxService outboxService;
    private final BookingSmsDispatcher bookingSmsDispatcher;
    private final SlotCalculationService slotCalculationService;
    private final BookingSmsProperties smsProperties;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final Clock kyivClock;

    public BookingCancellationService(
            GuestVisitCancellationService guestVisitCancellationService,
            BookingRepository bookingRepository,
            NotificationOutboxService outboxService,
            BookingSmsDispatcher bookingSmsDispatcher,
            SlotCalculationService slotCalculationService,
            BookingSmsProperties smsProperties,
            SalonCatalogCacheEvictor salonCatalogCacheEvictor,
            Clock clock) {
        this.guestVisitCancellationService = guestVisitCancellationService;
        this.bookingRepository = bookingRepository;
        this.outboxService = outboxService;
        this.bookingSmsDispatcher = bookingSmsDispatcher;
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
        UUID salonId = booking.getSalon() != null ? booking.getSalon().getId() : null;
        Runnable task = () -> {
            // EVICTION FIRST, DISPATCH SECOND — this order used to be the other way round, and the
            // send was inline (perf LOW, 2026-08-19). A Turbosms brown-out (up to the 5 s read cap,
            // 3 s more on connect) therefore delayed a correctness-critical evict by that whole
            // time, on the request thread, while it still held its pooled connection — during which
            // parallel readers could repopulate, and keep serving for the 60 s TTL, a slot this
            // cancel just freed. Matches GuestBookingService#registerAfterCommit. Do not swap back.
            //
            // Cancelling frees the master's time → the freed slot must reappear in the picker for
            // EVERY service this master performs that day (not just the cancelled one), and the
            // free-slot bookability verdict may flip (un-hiding a service). One by-master sweep.
            slotCalculationService.evictMasterAvailabilityCaches(masterId);
            // Un-hiding a service also changes the salon catalogue (perf/security #2).
            if (salonId != null) {
                salonCatalogCacheEvictor.evict(salonId);
            }
            bookingSmsDispatcher.dispatch(
                    BookingSmsDispatcher.Kind.GUEST_CANCELLATION, phone, smsText);
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
     * Renders the guest cancellation SMS in ONE pass over the template — see
     * {@link Placeholders#format}.
     *
     * <p>Chained {@link String#replace} was a template-injection vector here: {@code {serviceName}}
     * was substituted BEFORE {@code {date}} and {@code {time}}, so each later {@code replace}
     * re-scanned the service name it had just written in. A provider who named a service
     * {@code "Манікюр {date}"} therefore got a second, fabricated date/time line expanded inside a
     * message the guest reads as platform copy — SMS is a guest's only channel. This template
     * carries no {@code {cancelUrl}}, so there is no link to duplicate, but the layout-steering
     * vector is the same. A single pass copies substituted values out verbatim, so data can never
     * become markup.
     */
    private String buildCancellationSms(Booking booking) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return Placeholders.format(smsProperties.getSms().getCancellation(), Map.of(
                "serviceName", booking.getMasterService().getServiceDefinition().getName(),
                "masterName", masterName(booking.getMaster()),
                "date", DATE_FMT.format(kyiv),
                "time", TIME_FMT.format(kyiv)));
    }

    private static String masterName(Master master) {
        User u = master.getUser();
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null ? "" : u.getLastName().trim();
        return (first + " " + last).trim();
    }
}
