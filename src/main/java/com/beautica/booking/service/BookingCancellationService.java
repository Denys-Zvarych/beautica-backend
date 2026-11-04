package com.beautica.booking.service;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.domain.GuestPageWindow;
import com.beautica.booking.domain.GuestVisitAddress;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
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
 * ({@link BookingRepository#consumeCancelTokenHash}) whose {@code WHERE} guards on the
 * token still being present and {@code CONFIRMED}. Exactly one racer updates 1 row and
 * proceeds to the master notification; every other updates 0 rows and
 * is mapped to 404. A replayed link (token already null) likewise updates 0 rows → 404,
 * so the endpoint is idempotent by consequence with no extra state machine.
 *
 * <p><b>Side-effect ordering.</b> The master-notification outbox row is written inside
 * the cancellation transaction (the outbox is delivered by the drain worker afterwards),
 * while the availability-cache eviction runs only {@code afterCommit}.
 *
 * <p><b>No guest message (phase 435).</b> The guest who cancels their own booking gets NO
 * SMS / Telegram copy — the old «Ваш запис скасовано» SMS was removed by user decision
 * 2026-11-04 («we have two cancelled sms»). Only the provider is told.
 */
@Service
public class BookingCancellationService {

    private final GuestVisitCancellationService guestVisitCancellationService;
    private final BookingRepository bookingRepository;
    private final NotificationOutboxService outboxService;
    private final SlotCalculationService slotCalculationService;
    private final BookingSmsProperties smsProperties;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final Clock kyivClock;
    // Phase 333 — see InAppNotificationService's class javadoc for why this is a separate seam from
    // outboxService, never called from it.
    private final InAppNotificationService inAppNotificationService;
    private final GuestRebookPaths guestRebookPaths;

    public BookingCancellationService(
            GuestVisitCancellationService guestVisitCancellationService,
            BookingRepository bookingRepository,
            NotificationOutboxService outboxService,
            SlotCalculationService slotCalculationService,
            BookingSmsProperties smsProperties,
            SalonCatalogCacheEvictor salonCatalogCacheEvictor,
            Clock clock,
            InAppNotificationService inAppNotificationService,
            GuestRebookPaths guestRebookPaths) {
        this.guestVisitCancellationService = guestVisitCancellationService;
        this.bookingRepository = bookingRepository;
        this.outboxService = outboxService;
        this.slotCalculationService = slotCalculationService;
        this.smsProperties = smsProperties;
        this.salonCatalogCacheEvictor = salonCatalogCacheEvictor;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
        this.inAppNotificationService = inAppNotificationService;
        this.guestRebookPaths = guestRebookPaths;
    }

    /**
     * Returns the guest booking page summary for a cancel token whose hash is still stored.
     *
     * <p>Phase 435: a booking that is no longer {@code CONFIRMED} (declined, cancelled by the provider, completed …)
     * is returned as a READ-ONLY view — {@code status} says which, {@code cancellable} is {@code false}; before it
     * was a 404. A token consumed by the guest's own cancel, or anonymised, has no hash any more and still 404s.
     *
     * @throws NotFoundException when the token is unknown, consumed or anonymised (→ 404)
     */
    @Transactional(readOnly = true)
    public CancelTokenInfoResponse getInfo(GuestCancelToken token) {
        // BE-7: a token on an Appointment is a multi-service visit — served by the visit service. Only a
        // token that resolves to no visit falls through to the legacy single-booking path below.
        return guestVisitCancellationService.getInfo(token)
                .orElseGet(() -> buildInfo(loadByTokenOrThrow(CancelTokenHasher.hash(token))));
    }

    /**
     * Cancels a guest booking by its one-time token.
     *
     * @throws NotFoundException  when the token is unknown / consumed or the booking is
     *                            not {@code CONFIRMED} (→ 404), including a replayed POST
     * @throws BusinessException  with 422 when the cancellation window has closed
     */
    @Transactional
    public void cancel(GuestCancelToken token) {
        // BE-7: a token on an Appointment cancels the WHOLE visit (header + all items) via the visit
        // service; it returns true when it handled the token. Only a non-visit token falls through to
        // the legacy single-booking cancel below (byte-for-byte unchanged).
        if (guestVisitCancellationService.cancel(token)) {
            return;
        }
        // V206: only the token's hash is stored — hash once, look up and consume by the hash.
        String tokenHash = CancelTokenHasher.hash(token);
        Booking booking = loadCancellableOrThrow(tokenHash);

        if (!isCancellable(booking)) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Скасування недоступне — менше ніж %d год до запису"
                            .formatted(smsProperties.getCancelWindowHours()));
        }

        if (!cancelValidated(booking, tokenHash)) {
            // Lost the race or already consumed between load and update — no side-effects.
            throw new NotFoundException("Cancel token not found");
        }
    }

    /**
     * Cancels one already-validated guest/walk-in single booking and fires the provider
     * notifications — everything {@link #cancel(UUID)} does after its token lookup and 2-hour
     * check (phase 373 D12 seam, extracted without behaviour change). Also called by the internal
     * guest-PII erase, which bypasses the 2-hour window on purpose. Sends the guest nothing
     * (phase 435).
     *
     * <p>A row with a cancel token is consumed via {@link BookingRepository#consumeCancelTokenHash};
     * a tokenless STAFF walk-in row via {@link BookingRepository#cancelGuestRowById}. Both are
     * conditional UPDATEs: when 0 rows match (a concurrent cancel already won) nothing else runs.
     *
     * <p>Must run inside the caller's transaction ({@code MANDATORY}) — the outbox row is written
     * in it.
     *
     * @param booking the graph-loaded {@code CONFIRMED} booking
     * @return {@code true} when this call cancelled the booking, {@code false} on a lost race
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelGuestBooking(Booking booking) {
        return cancelValidated(booking, booking.getCancelTokenHash());
    }

    private boolean cancelValidated(Booking booking, String tokenHash) {
        // Atomic one-time consume: only the winner of any concurrent race updates 1 row.
        int updated = tokenHash != null
                ? bookingRepository.consumeCancelTokenHash(tokenHash)
                : bookingRepository.cancelGuestRowById(booking.getId());
        if (updated == 0) {
            return false;
        }

        // Master notification: outbox row written INSIDE this transaction (MANDATORY
        // propagation); the drain worker delivers the push after commit. Reuses the
        // existing CLIENT_CANCELLED event — the same one the authenticated cancel path uses.
        outboxService.enqueueClientCancelled(booking.getId());
        // Phase 333, matrix row 2 — provider set; no actor (guest, no user account). `booking` is
        // the same full-graph instance the caller loaded — never reloaded
        // (audit-fix cycle 1, finding 1).
        inAppNotificationService.notifyBookingEvent(
                InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, booking, null);

        // Phase 435: no guest SMS — only the after-commit cache eviction.
        registerAfterCommitEviction(booking);
        return true;
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private Booking loadByTokenOrThrow(String tokenHash) {
        Booking booking = bookingRepository.findByCancelTokenHashWithGraph(tokenHash)
                .orElseThrow(() -> new NotFoundException("Cancel token not found"));
        // Phase 435 audit-fix — a terminal booking that started more than terminal-view-days ago 404s like an
        // unknown token (the read-only page does not live for the token's whole 12-month life).
        if (GuestPageWindow.expired(booking.getStatus(), booking.getStartsAt(),
                OffsetDateTime.now(kyivClock), smsProperties.getTerminalViewDays())) {
            throw new NotFoundException("Cancel token not found");
        }
        return booking;
    }

    private Booking loadCancellableOrThrow(String tokenHash) {
        Booking booking = loadByTokenOrThrow(tokenHash);
        // A consumed/terminal booking has no valid cancel path. 404 (not 409) — the same
        // response a fully unknown token yields, so the caller cannot probe token state.
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new NotFoundException("Cancel token not found");
        }
        return booking;
    }

    private boolean isCancellable(Booking booking) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            return false; // phase 435 read-only view
        }
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
                windowClosesAt,
                booking.getStatus(),
                guestRebookPaths.forBooking(booking).orElse(null),
                salonName(booking),
                GuestVisitAddress.of(booking),
                GuestVisitAddress.pageLocationNote(booking));
    }

    private void registerAfterCommitEviction(Booking booking) {
        UUID masterId = booking.getMaster().getId();
        UUID salonId = booking.getSalon() != null ? booking.getSalon().getId() : null;
        Runnable task = () -> {
            // Cancelling frees the master's time → the freed slot must reappear in the picker for
            // EVERY service this master performs that day (not just the cancelled one), and the
            // free-slot bookability verdict may flip (un-hiding a service). One by-master sweep.
            slotCalculationService.evictMasterAvailabilityCaches(masterId);
            // Un-hiding a service also changes the salon catalogue (perf/security #2).
            if (salonId != null) {
                salonCatalogCacheEvictor.evictCatalogOnly(salonId);
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

    /** Phase 435 — the salon's name for a salon booking; {@code null} for an independent master. */
    private static String salonName(Booking booking) {
        return booking.getSalon() == null ? null : booking.getSalon().getName();
    }

    // V157 / phase 294 D3: displayFirstName()/displayLastName(), never getUser().getFirstName().
    // A detached master (staff account hard-deleted, historical stub kept) has no user row, so the
    // old two-line walk NPEs; the accessors fall back to the name snapshot taken at detach time.
    // Still null-safe on either half — users.first_name / users.last_name are both nullable.
    private static String masterName(Master master) {
        String firstName = master.displayFirstName();
        String lastName = master.displayLastName();
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }
}
