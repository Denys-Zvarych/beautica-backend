package com.beautica.booking.retention;

import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.BookingCancellationService;
import com.beautica.booking.service.GuestVisitCancellationService;
import com.beautica.common.util.UkrainianPhoneNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Irreversible anonymisation of guest (LINK) and walk-in (STAFF, no client account) PII — phase 373.
 *
 * <p><b>Retention ({@link #anonymiseDue}).</b> Rows whose {@code ends_at} is before the cutoff are
 * claimed AND anonymised by one native UPDATE per batch ({@code FOR UPDATE SKIP LOCKED}), each batch
 * in its own {@code REQUIRES_NEW} transaction so a mid-run failure keeps earlier batches. Bookings
 * first, then visit headers (a header is due once none of its items is left un-anonymised).
 *
 * <p><b>Erase ({@link #eraseByPhone}).</b> One transaction, in this order (D12): normalise the phone,
 * lock the phone's upcoming guest visits and single bookings, cancel them through the existing guest
 * cancellation seams (provider outbox + in-app rows, no guest SMS), anonymise every guest row of the
 * phone regardless of age, delete its OTP rows. A rollback leaves neither a cancellation nor a wipe.
 *
 * <p>Logs carry counts and the cutoff only — never a phone, a name or a row id (§I).
 */
@Slf4j
@Service
public class GuestPiiAnonymisationService {

    static final String METRIC = "guest.pii.anonymised";

    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final BookingCancellationService bookingCancellationService;
    private final GuestVisitCancellationService guestVisitCancellationService;
    private final PhoneOtpService phoneOtpService;
    private final GuestPiiRetentionProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate batchTx;

    public GuestPiiAnonymisationService(
            BookingRepository bookingRepository,
            AppointmentRepository appointmentRepository,
            BookingCancellationService bookingCancellationService,
            GuestVisitCancellationService guestVisitCancellationService,
            PhoneOtpService phoneOtpService,
            GuestPiiRetentionProperties properties,
            MeterRegistry meterRegistry,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.appointmentRepository = appointmentRepository;
        this.bookingCancellationService = bookingCancellationService;
        this.guestVisitCancellationService = guestVisitCancellationService;
        this.phoneOtpService = phoneOtpService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.batchTx = new TransactionTemplate(transactionManager);
        this.batchTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Anonymises every guest/walk-in row that ended before {@code cutoff}, within the configured
     * per-run batch budget. Safe to run concurrently on several instances and to rerun.
     */
    public GuestPiiAnonymisationResult anonymiseDue(Instant cutoff) {
        return anonymiseDue(cutoff, properties.batchSize(), properties.maxBatchesPerRun());
    }

    GuestPiiAnonymisationResult anonymiseDue(Instant cutoff, int batchSize, int maxBatches) {
        long startedAt = clock.millis();
        OffsetDateTime utcCutoff = cutoff.atOffset(ZoneOffset.UTC);
        int bookings = drain(() -> bookingRepository.anonymiseDueGuestBookings(
                utcCutoff, GuestPii.ANONYMISED_NAME, batchSize), batchSize, maxBatches);
        int visits = drain(() -> appointmentRepository.anonymiseDueGuestAppointments(
                GuestPii.ANONYMISED_NAME, batchSize), batchSize, maxBatches);
        record("job", bookings, visits);
        log.info("Guest PII retention: anonymised {} bookings, {} visits in {} ms (cutoff {})",
                bookings, visits, clock.millis() - startedAt, cutoff);
        return GuestPiiAnonymisationResult.ofRetention(bookings, visits);
    }

    /**
     * Erases one phone's guest PII on request (internal endpoint). Never refuses: upcoming rows are
     * cancelled first (provider notified exactly like a guest cancel), then everything is wiped.
     *
     * @param rawPhone any Ukrainian phone format; invalid → 400 that never echoes the input
     */
    @Transactional
    public GuestPiiAnonymisationResult eraseByPhone(String rawPhone) {
        long startedAt = clock.millis();
        String phone = UkrainianPhoneNormalizer.toE164(rawPhone);
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Lock order matches the token visit path: headers first, then booking rows.
        List<UUID> visitIds = appointmentRepository.lockUpcomingGuestVisitIdsByPhone(phone, now);
        List<UUID> bookingIds = bookingRepository.lockUpcomingGuestSingleBookingIdsByPhone(phone, now);
        int visitsCancelled = cancelVisits(visitIds);
        int bookingsCancelled = cancelSingleBookings(bookingIds);

        int bookingsAnonymised = bookingRepository
                .anonymiseGuestBookingsByPhone(phone, GuestPii.ANONYMISED_NAME).size();
        int visitsAnonymised = appointmentRepository
                .anonymiseGuestAppointmentsByPhone(phone, GuestPii.ANONYMISED_NAME).size();
        int otpRowsDeleted = phoneOtpService.deleteAllForPhone(phone);

        record("manual", bookingsAnonymised, visitsAnonymised);
        log.info("Guest PII erase: cancelled {} bookings, {} visits; anonymised {} bookings, {} visits; "
                        + "deleted {} OTP rows in {} ms",
                bookingsCancelled, visitsCancelled, bookingsAnonymised, visitsAnonymised,
                otpRowsDeleted, clock.millis() - startedAt);
        return new GuestPiiAnonymisationResult(
                bookingsCancelled, visitsCancelled, bookingsAnonymised, visitsAnonymised, otpRowsDeleted);
    }

    private int cancelVisits(List<UUID> appointmentIds) {
        int cancelled = 0;
        for (UUID appointmentId : appointmentIds) {
            Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
            List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointmentId);
            if (appointment != null && !items.isEmpty()
                    && guestVisitCancellationService.cancelGuestVisit(appointment, items, false)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    private int cancelSingleBookings(List<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (Booking booking : bookingRepository.findAllByIdsWithGraph(bookingIds)) {
            if (bookingCancellationService.cancelGuestBooking(booking, false)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    private int drain(Supplier<List<UUID>> claimBatch, int batchSize, int maxBatches) {
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<UUID> claimed = batchTx.execute(status -> claimBatch.get());
            int count = claimed == null ? 0 : claimed.size();
            total += count;
            if (count < batchSize) {
                break;
            }
        }
        return total;
    }

    private void record(String trigger, int bookings, int visits) {
        meterRegistry.counter(METRIC, "entity", "booking", "trigger", trigger).increment(bookings);
        meterRegistry.counter(METRIC, "entity", "appointment", "trigger", trigger).increment(visits);
    }
}
