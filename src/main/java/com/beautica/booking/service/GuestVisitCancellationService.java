package com.beautica.booking.service;

import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.domain.GuestPageWindow;
import com.beautica.booking.domain.GuestSmsServiceName;
import com.beautica.booking.domain.GuestVisitAddress;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public guest-cancellation flow for a multi-service LINK <b>visit</b> (BE-7). One {@code cancel_token}
 * lives on the {@link Appointment} header; following the single cancel link in the confirmation SMS
 * cancels the WHOLE visit — the header AND every chained {@link Booking} item — in lockstep.
 *
 * <p><b>Relationship to {@link BookingCancellationService}.</b> That service owns the legacy
 * single-booking guest-cancel path (token on the {@code bookings} row, {@code appointment_id} NULL) and
 * remains byte-for-byte unchanged. It delegates to this service FIRST: a token that resolves to an
 * appointment is a visit and is handled here; a token that resolves to no appointment falls through to
 * the legacy single-booking path. So one public endpoint ({@code POST /api/v1/book/cancel/{token}})
 * transparently serves both shapes.
 *
 * <p><b>One-time consume / concurrency</b> mirrors the single path: the header status-flip + token-null
 * is a single conditional UPDATE ({@link AppointmentRepository#consumeCancelTokenHash}) guarded on the
 * token still being present and {@code CONFIRMED}. Exactly one racer updates 1 header row and proceeds
 * to cancel the items ({@link BookingRepository#cancelItemsByAppointmentId(UUID)}), notify the master
 * once, and — after commit — evict availability caches; every other racer updates 0 rows and is mapped
 * to 404. The master notification is written inside the transaction; the eviction runs only
 * {@code afterCommit}.
 *
 * <p><b>No guest message (phase 435).</b> The guest who cancels their own visit gets NO SMS / Telegram
 * copy — the old «Ваш запис скасовано» SMS was removed by user decision 2026-11-04 («we have two
 * cancelled sms»). Only the provider is told.
 */
@Service
public class GuestVisitCancellationService {

    private final AppointmentRepository appointmentRepository;
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

    public GuestVisitCancellationService(
            AppointmentRepository appointmentRepository,
            BookingRepository bookingRepository,
            NotificationOutboxService outboxService,
            SlotCalculationService slotCalculationService,
            BookingSmsProperties smsProperties,
            SalonCatalogCacheEvictor salonCatalogCacheEvictor,
            Clock clock,
            InAppNotificationService inAppNotificationService,
            GuestRebookPaths guestRebookPaths) {
        this.appointmentRepository = appointmentRepository;
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
     * Guest page summary for a visit token, or {@link Optional#empty()} when the token is not a visit token (unknown /
     * consumed / anonymised) — the caller then tries the legacy single path. A present-but-not-yet-cancellable visit
     * returns its summary with {@code cancellable=false}; phase 435: so does a visit whose header is no longer
     * {@code CONFIRMED} (read-only view, {@code status} = the header's).
     */
    @Transactional(readOnly = true)
    public Optional<CancelTokenInfoResponse> getInfo(UUID token) {
        Appointment appointment = findVisitByToken(token);
        if (appointment == null) {
            return Optional.empty();
        }
        List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointment.getId());
        if (items.isEmpty()) {
            return Optional.empty();
        }
        Booking first = items.get(0);
        // Phase 435 audit-fix — a terminal visit that started more than terminal-view-days ago: 404, like an unknown
        // token (thrown here, not Optional.empty(), so the single-booking fallback is not consulted).
        if (GuestPageWindow.expired(appointment.getStatus(), first.getStartsAt(),
                OffsetDateTime.now(kyivClock), smsProperties.getTerminalViewDays())) {
            throw new NotFoundException("Cancel token not found");
        }
        boolean live = appointment.getStatus() == BookingStatus.CONFIRMED;
        return Optional.of(new CancelTokenInfoResponse(
                masterName(first.getMaster()),
                GuestSmsServiceName.of(first),
                first.getStartsAt(),
                live && isCancellable(first.getStartsAt()),
                first.getStartsAt().minusHours(smsProperties.getCancelWindowHours()),
                appointment.getStatus(),
                guestRebookPaths.forBooking(first).orElse(null),
                salonName(first),
                GuestVisitAddress.of(first)));
    }

    /**
     * Cancels the whole visit by its one-time token. Returns {@code true} when the token corresponded to
     * a visit and was handled (cancelled, or a lost race → 404); {@code false} when the token is not a
     * visit token at all, so the caller can fall through to the legacy single-booking path.
     *
     * @throws BusinessException with 422 when the cancellation window has closed
     * @throws NotFoundException with 404 on a lost race (header consumed between load and update)
     */
    @Transactional
    public boolean cancel(UUID token) {
        List<Booking> items = loadLiveVisitItems(token);
        if (items.isEmpty()) {
            return false; // not a visit token — let the caller try the legacy single-booking path
        }
        Booking first = items.get(0);

        if (!isCancellable(first.getStartsAt())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Скасування недоступне — менше ніж %d год до запису"
                            .formatted(smsProperties.getCancelWindowHours()));
        }

        if (!cancelValidated(first.getAppointment().getId(), CancelTokenHasher.hash(token), items)) {
            throw new NotFoundException("Cancel token not found");
        }
        return true;
    }

    /**
     * Cancels one already-validated guest/walk-in visit (header + every {@code CONFIRMED} item) and
     * fires the provider notifications — everything {@link #cancel(UUID)} does after its token lookup
     * and 2-hour check (phase 373 D12 seam, extracted without behaviour change). Also called by the
     * internal guest-PII erase, which bypasses the 2-hour window. Sends the guest nothing (phase 435).
     *
     * <p>A header with a cancel token is consumed via {@link AppointmentRepository#consumeCancelTokenHash};
     * a tokenless STAFF walk-in header via {@link AppointmentRepository#cancelGuestVisitById}. On 0
     * matched rows (a concurrent cancel already won) nothing else runs.
     *
     * @param appointment  the visit header
     * @param items        the visit's graph-fetched items in visit order (non-empty)
     * @return {@code true} when this call cancelled the visit, {@code false} on a lost race
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelGuestVisit(Appointment appointment, List<Booking> items) {
        return cancelValidated(appointment.getId(), appointment.getCancelTokenHash(), items);
    }

    private boolean cancelValidated(UUID appointmentId, String tokenHash, List<Booking> items) {
        Booking first = items.get(0);
        // Atomic one-time consume of the header token: only the winner of any concurrent race updates 1
        // row and cancels the items + fires the side-effects; a replay / lost race updates 0 rows → 404.
        int updated = tokenHash != null
                ? appointmentRepository.consumeCancelTokenHash(tokenHash)
                : appointmentRepository.cancelGuestVisitById(appointmentId);
        if (updated == 0) {
            return false;
        }
        bookingRepository.cancelItemsByAppointmentId(appointmentId);

        // ONE master notification for the whole visit (referencing the first item — never one per
        // service), reusing the CLIENT_CANCELLED event the single guest-cancel path uses.
        outboxService.enqueueClientCancelled(first.getId());
        // Phase 333, matrix row 2 — provider set; no actor (guest, no user account). `first` is
        // already the graph-fetched item loadLiveVisitItems loaded above — never reloaded
        // (audit-fix cycle 1, finding 1).
        inAppNotificationService.notifyVisitEvent(
                InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, appointmentId, first, null);

        registerAfterCommit(items);
        return true;
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /**
     * Loads a visit's ordered, graph-fetched items for a token, or an empty list when the token is not a
     * live visit token: unknown / already-consumed appointment, a non-CONFIRMED header, or (defensively)
     * an itemless header. The status guard keeps a consumed/terminal visit indistinguishable from an
     * unknown token (no state oracle), matching the single path.
     */
    private List<Booking> loadLiveVisitItems(UUID token) {
        Appointment appointment = findVisitByToken(token);
        if (appointment == null || appointment.getStatus() != BookingStatus.CONFIRMED) {
            return List.of();
        }
        return bookingRepository.findByAppointmentIdWithGraph(appointment.getId());
    }

    /** The visit header holding this token's hash, any status; {@code null} when none. */
    private Appointment findVisitByToken(UUID token) {
        // V206: only the token's hash is stored — the lookup hashes the incoming token.
        return appointmentRepository.findByCancelTokenHash(CancelTokenHasher.hash(token)).orElse(null);
    }

    private boolean isCancellable(OffsetDateTime visitStart) {
        OffsetDateTime now = OffsetDateTime.now(kyivClock);
        return visitStart.isAfter(now.plusHours(smsProperties.getCancelWindowHours()));
    }

    /**
     * After-commit side-effects for a whole-visit cancel: availability-cache eviction (no guest SMS,
     * phase 435) fanned out over every item — reusing the exact
     * single-service booking-write eviction hooks so a parallel reader cannot repopulate stale data
     * mid-write. Distinct (Kyiv-civil date, masterServiceId) keys are collected across all items (both
     * start and end day, in the rare event a service spans midnight); the per-master free-slot verdict
     * and the salon catalogue are each evicted once.
     */
    private void registerAfterCommit(List<Booking> items) {
        Booking first = items.get(0);
        UUID masterId = first.getMaster().getId();
        UUID salonId = first.getSalon() != null ? first.getSalon().getId() : null;

        Runnable task = () -> {
            // Cancelling FREES the master's time, which widens the slots offered for every service
            // this master performs on those dates — swept by master prefix, not per (date, service).
            slotCalculationService.evictMasterAvailabilityCaches(masterId);
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
