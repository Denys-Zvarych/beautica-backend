package com.beautica.booking.service;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.domain.GuestPageWindow;
import com.beautica.booking.domain.GuestSmsServiceName;
import com.beautica.booking.domain.GuestVisitAddress;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.dto.CancelTokenItemResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Public guest-cancellation flow for a multi-service LINK <b>visit</b> (BE-7). One {@code cancel_token}
 * lives on the {@link Appointment} header; following the single cancel link in the confirmation SMS
 * cancels every cancellable chained {@link Booking} item, or (phase 436) only the items the guest names. The header
 * flips to {@code CANCELLED} — and the token is consumed — once no {@code CONFIRMED} item remains.
 *
 * <p><b>Relationship to {@link BookingCancellationService}.</b> That service owns the legacy
 * single-booking guest-cancel path (token on the {@code bookings} row, {@code appointment_id} NULL) and
 * remains byte-for-byte unchanged. It delegates to this service FIRST: a token that resolves to an
 * appointment is a visit and is handled here; a token that resolves to no appointment falls through to
 * the legacy single-booking path. So one public endpoint ({@code POST /api/v1/book/cancel/{token}})
 * transparently serves both shapes.
 *
 * <p><b>Concurrency (phase 436).</b> A guest cancel locks the header row first, re-checks the token under the lock,
 * then cancels the chosen items with one conditional UPDATE whose row count must match the request (else 422 and
 * rollback); a lost race is 404. The master is notified once per request inside the transaction; the eviction runs
 * only {@code afterCommit}. The internal {@link #cancelGuestVisit} seam keeps the pre-436 whole-visit consume.
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
    public Optional<CancelTokenInfoResponse> getInfo(GuestCancelToken token) {
        Appointment appointment = findVisitByToken(token);
        if (appointment == null) {
            return Optional.empty();
        }
        List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointment.getId());
        if (items.isEmpty()) {
            return Optional.empty();
        }
        // Phase 435 audit-fix — a terminal visit that started more than terminal-view-days ago: 404, like an unknown
        // token (thrown here, not Optional.empty(), so the single-booking fallback is not consulted).
        if (GuestPageWindow.expired(appointment.getStatus(), items.get(0).getStartsAt(),
                OffsetDateTime.now(kyivClock), smsProperties.getTerminalViewDays())) {
            throw GuestCancelSelection.tokenNotFound();
        }
        return Optional.of(buildInfo(appointment.getStatus(), items, Set.of()));
    }

    /**
     * Cancels the whole visit by its one-time token — every cancellable line (pre-436 behaviour).
     *
     * @see #cancel(GuestCancelToken, List)
     */
    @Transactional
    public Optional<CancelTokenInfoResponse> cancel(GuestCancelToken token) {
        return cancel(token, null);
    }

    /**
     * Phase 436 — cancels the named lines of a visit by its token (all cancellable lines when {@code bookingIds} is
     * {@code null}; an empty list is 400), all or nothing, and returns the page view after the cancel. {@link Optional#empty()} when the token is
     * not a live visit token, so the caller can fall through to the legacy single-booking path.
     *
     * <p><b>Concurrency.</b> The header row is locked first, its token re-checked in the same statement
     * ({@link AppointmentRepository#lockHeaderIfConfirmedWithToken}, the appointments-before-bookings order), and the
     * lines are read after it, so
     * the «does any CONFIRMED line remain» decision is taken on fresh rows that no other header-locking writer can
     * change. The lines are cancelled by ONE conditional UPDATE whose row count must equal the request size.
     *
     * <p><b>Token lifetime.</b> The header (and its token) is consumed only when no CONFIRMED line remains. While a
     * CONFIRMED line remains the token stays — {@code chk_appointment_guest_fields} requires a token hash on a live
     * LINK header — so the guest can reopen the page and cancel the rest.
     *
     * @throws NotFoundException 404 when an id is not a line of this visit, or on a lost race
     * @throws BusinessException 422 when a line is no longer cancellable (nothing is cancelled), 400 on bad ids
     */
    @Transactional
    public Optional<CancelTokenInfoResponse> cancel(GuestCancelToken token, List<UUID> bookingIds) {
        List<UUID> requested = GuestCancelSelection.requestedIds(bookingIds);
        String tokenHash = CancelTokenHasher.hash(token); // hashed once for the lookup, the lock and the consume
        Appointment appointment = findVisitByHash(tokenHash);
        if (appointment == null || appointment.getStatus() != BookingStatus.CONFIRMED) {
            return Optional.empty(); // not a live visit token — let the caller try the legacy single-booking path
        }
        UUID appointmentId = appointment.getId();
        // Lost race: the header left CONFIRMED, or its token was consumed, before this request took the lock — both
        // re-checked inside the one locking statement.
        if (appointmentRepository.lockHeaderIfConfirmedWithToken(appointmentId, tokenHash).isEmpty()) {
            throw GuestCancelSelection.tokenNotFound();
        }
        List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointmentId);
        if (items.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime cutoff = cancelCutoff();
        List<Booking> targets = selectTargets(items, requested, cutoff);
        Set<UUID> targetIds = targets.stream().map(Booking::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (bookingRepository.cancelGuestVisitItemsByIds(appointmentId, targetIds, cutoff) != targetIds.size()) {
            throw GuestCancelSelection.windowClosed(smsProperties.getCancelWindowHours()); // rolls the request back
        }
        boolean confirmedRemain = items.stream()
                .anyMatch(b -> b.getStatus() == BookingStatus.CONFIRMED && !targetIds.contains(b.getId()));
        if (!confirmedRemain && appointmentRepository.consumeCancelTokenHash(tokenHash) == 0) {
            throw GuestCancelSelection.tokenNotFound();
        }
        notifyProviderOfPartialOrWholeCancel(appointmentId, targets, confirmedRemain);
        registerAfterCommit(items);
        return Optional.of(buildInfo(confirmedRemain ? BookingStatus.CONFIRMED : BookingStatus.CANCELLED,
                items, targetIds));
    }

    /**
     * Cancels one already-validated guest/walk-in visit (header + every {@code CONFIRMED} item) and
     * fires the provider notifications — the pre-436 whole-visit cancel after its token lookup and
     * 2-hour check (phase 373 D12 seam). Called by the internal guest-PII erase, which bypasses the
     * 2-hour window. Sends the guest nothing (phase 435).
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
        // the caller's already graph-fetched item — never reloaded (audit-fix cycle 1, finding 1).
        inAppNotificationService.notifyVisitEvent(
                InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, appointmentId, first, null);

        registerAfterCommit(items);
        return true;
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /**
     * The lines one request cancels: every cancellable line when {@code requested} is empty (422 when there is none),
     * else exactly the named ones — 404 when any id is not a line of this visit, 422 when any is no longer
     * cancellable. Validation runs over the whole request before anything is written.
     */
    private List<Booking> selectTargets(List<Booking> items, List<UUID> requested, OffsetDateTime cutoff) {
        int windowHours = smsProperties.getCancelWindowHours();
        if (requested.isEmpty()) {
            List<Booking> all = items.stream()
                    .filter(b -> GuestCancelSelection.cancellable(b, b.getStatus(), cutoff))
                    .toList();
            if (all.isEmpty()) {
                throw GuestCancelSelection.windowClosed(windowHours);
            }
            return all;
        }
        Map<UUID, Booking> byId = items.stream().collect(Collectors.toMap(Booking::getId, Function.identity()));
        if (!byId.keySet().containsAll(requested)) {
            throw GuestCancelSelection.tokenNotFound();
        }
        for (UUID id : requested) {
            Booking line = byId.get(id);
            if (!GuestCancelSelection.cancellable(line, line.getStatus(), cutoff)) {
                throw GuestCancelSelection.notCancellable(line, windowHours);
            }
        }
        // Visit order (startsAt), not request order — the first target is the notifications' lead.
        return items.stream().filter(b -> requested.contains(b.getId())).toList();
    }

    /**
     * ONE provider e-mail and ONE in-app event per request (locked decision 2026-11-05). The e-mail row carries the
     * request's booking ids so it lists exactly the services cancelled here. The in-app row of a request that leaves
     * the visit live is keyed by its first cancelled line, so a later request on the same visit is not de-duplicated
     * away; the request that ends the visit keeps the pre-436 visit key. No note is read or written.
     */
    private void notifyProviderOfPartialOrWholeCancel(UUID appointmentId, List<Booking> targets,
                                                     boolean visitStaysLive) {
        Booking lead = targets.get(0);
        outboxService.enqueueClientCancelled(lead.getId(), targets.stream().map(Booking::getId).toList());
        if (visitStaysLive) {
            inAppNotificationService.notifyVisitEvent(
                    InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, appointmentId, lead, null, lead.getId());
        } else {
            inAppNotificationService.notifyVisitEvent(
                    InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, appointmentId, lead, null);
        }
    }

    /**
     * The page view: header fields from the first line (visit start), one {@code items} entry per line with
     * {@code cancelledNow} shown as CANCELLED (a bulk UPDATE does not refresh the loaded entities).
     */
    private CancelTokenInfoResponse buildInfo(BookingStatus headerStatus, List<Booking> items, Set<UUID> cancelledNow) {
        OffsetDateTime cutoff = cancelCutoff();
        boolean live = headerStatus == BookingStatus.CONFIRMED;
        List<CancelTokenItemResponse> lines = items.stream()
                .map(b -> GuestCancelSelection.item(b, GuestSmsServiceName.of(b),
                        cancelledNow.contains(b.getId()) ? BookingStatus.CANCELLED : b.getStatus(), live, cutoff))
                .toList();
        Booking first = items.get(0);
        return new CancelTokenInfoResponse(
                masterName(first.getMaster()),
                GuestSmsServiceName.of(first),
                first.getStartsAt(),
                lines.stream().anyMatch(CancelTokenItemResponse::cancellable),
                first.getStartsAt().minusHours(smsProperties.getCancelWindowHours()),
                headerStatus,
                guestRebookPaths.forBooking(first).orElse(null),
                salonName(first),
                GuestVisitAddress.of(first),
                GuestVisitAddress.pageLocationNote(first),
                lines);
    }

    /** The visit header holding this token's hash, any status; {@code null} when none. */
    private Appointment findVisitByToken(GuestCancelToken token) {
        // V206: only the token's hash is stored — the lookup hashes the incoming token.
        return findVisitByHash(CancelTokenHasher.hash(token));
    }

    private Appointment findVisitByHash(String tokenHash) {
        return appointmentRepository.findByCancelTokenHash(tokenHash).orElse(null);
    }

    private OffsetDateTime cancelCutoff() {
        return OffsetDateTime.now(kyivClock).plusHours(smsProperties.getCancelWindowHours());
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
