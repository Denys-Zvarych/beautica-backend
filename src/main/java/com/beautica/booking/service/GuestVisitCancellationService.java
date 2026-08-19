package com.beautica.booking.service;

import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
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
import java.util.List;
import java.util.Map;
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
 * is a single conditional UPDATE ({@link AppointmentRepository#consumeCancelToken(UUID)}) guarded on the
 * token still being present and {@code CONFIRMED}. Exactly one racer updates 1 header row and proceeds
 * to cancel the items ({@link BookingRepository#cancelItemsByAppointmentId(UUID)}), notify the master
 * once, and — after commit — send ONE cancellation SMS and evict availability caches; every other racer
 * updates 0 rows and is mapped to 404. The master notification is written inside the transaction; the
 * SMS is dispatched only {@code afterCommit} — a rolled-back cancel sends nothing.
 */
@Service
public class GuestVisitCancellationService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final AppointmentRepository appointmentRepository;
    private final BookingRepository bookingRepository;
    private final NotificationOutboxService outboxService;
    private final BookingSmsDispatcher bookingSmsDispatcher;
    private final SlotCalculationService slotCalculationService;
    private final BookingSmsProperties smsProperties;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final Clock kyivClock;

    public GuestVisitCancellationService(
            AppointmentRepository appointmentRepository,
            BookingRepository bookingRepository,
            NotificationOutboxService outboxService,
            BookingSmsDispatcher bookingSmsDispatcher,
            SlotCalculationService slotCalculationService,
            BookingSmsProperties smsProperties,
            SalonCatalogCacheEvictor salonCatalogCacheEvictor,
            Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.bookingRepository = bookingRepository;
        this.outboxService = outboxService;
        this.bookingSmsDispatcher = bookingSmsDispatcher;
        this.slotCalculationService = slotCalculationService;
        this.smsProperties = smsProperties;
        this.salonCatalogCacheEvictor = salonCatalogCacheEvictor;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    /**
     * Cancel-page summary for a visit token, or {@link Optional#empty()} when the token is not a live
     * visit token (unknown / consumed / non-CONFIRMED) — the caller then tries the legacy single path.
     * A present-but-not-yet-cancellable visit still returns its summary with {@code cancellable=false}.
     */
    @Transactional(readOnly = true)
    public Optional<CancelTokenInfoResponse> getInfo(UUID token) {
        List<Booking> items = loadLiveVisitItems(token);
        if (items.isEmpty()) {
            return Optional.empty();
        }
        Booking first = items.get(0);
        return Optional.of(new CancelTokenInfoResponse(
                masterName(first.getMaster()),
                first.getMasterService().getServiceDefinition().getName(),
                first.getStartsAt(),
                isCancellable(first.getStartsAt()),
                first.getStartsAt().minusHours(smsProperties.getCancelWindowHours())));
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

        // Atomic one-time consume of the header token: only the winner of any concurrent race updates 1
        // row and cancels the items + fires the side-effects; a replay / lost race updates 0 rows → 404.
        int updated = appointmentRepository.consumeCancelToken(token);
        if (updated == 0) {
            throw new NotFoundException("Cancel token not found");
        }
        UUID appointmentId = first.getAppointment().getId();
        bookingRepository.cancelItemsByAppointmentId(appointmentId);

        // ONE master notification for the whole visit (referencing the first item — never one per
        // service), reusing the CLIENT_CANCELLED event the single guest-cancel path uses.
        outboxService.enqueueClientCancelled(first.getId());

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
        Appointment appointment = appointmentRepository.findByCancelToken(token).orElse(null);
        if (appointment == null || appointment.getStatus() != BookingStatus.CONFIRMED) {
            return List.of();
        }
        return bookingRepository.findByAppointmentIdWithGraph(appointment.getId());
    }

    private boolean isCancellable(OffsetDateTime visitStart) {
        OffsetDateTime now = OffsetDateTime.now(kyivClock);
        return visitStart.isAfter(now.plusHours(smsProperties.getCancelWindowHours()));
    }

    /**
     * After-commit side-effects for a whole-visit cancel: ONE cancellation SMS (first item's service +
     * time) plus availability-cache eviction fanned out over every item — reusing the exact
     * single-service booking-write eviction hooks so a parallel reader cannot repopulate stale data
     * mid-write. Distinct (Kyiv-civil date, masterServiceId) keys are collected across all items (both
     * start and end day, in the rare event a service spans midnight); the per-master free-slot verdict
     * and the salon catalogue are each evicted once.
     */
    private void registerAfterCommit(List<Booking> items) {
        Booking first = items.get(0);
        Master master = first.getMaster();
        UUID masterId = master.getId();
        UUID salonId = first.getSalon() != null ? first.getSalon().getId() : null;
        String phone = first.getGuestPhone();
        String smsText = buildCancellationSms(first);

        Runnable task = () -> {
            // EVICTION FIRST, DISPATCH SECOND — see BookingCancellationService#registerAfterCommitSms
            // for why this order is load-bearing and why it was reversed (perf LOW, 2026-08-19).
            //
            // Cancelling FREES the master's time, which widens the slots offered for every service
            // this master performs on those dates — swept by master prefix, not per (date, service).
            slotCalculationService.evictMasterAvailabilityCaches(masterId);
            if (salonId != null) {
                salonCatalogCacheEvictor.evict(salonId);
            }
            bookingSmsDispatcher.dispatch(
                    BookingSmsDispatcher.Kind.GUEST_VISIT_CANCELLATION, phone, smsText);
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
     * Renders the guest visit-cancellation SMS in ONE pass over the template — see
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
    private String buildCancellationSms(Booking first) {
        OffsetDateTime kyiv = first.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return Placeholders.format(smsProperties.getSms().getCancellation(), Map.of(
                "serviceName", first.getMasterService().getServiceDefinition().getName(),
                "masterName", masterName(first.getMaster()),
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
