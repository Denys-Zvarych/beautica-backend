package com.beautica.booking.retention;

import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.GuestBookingSubject;
import com.beautica.booking.service.BookingCancellationService;
import com.beautica.booking.service.GuestVisitCancellationService;
import com.beautica.common.util.UkrainianPhoneNormalizer;
import com.beautica.moderation.service.ClientBlockService;
import com.beautica.review.invite.service.GuestReviewRetentionService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Irreversible anonymisation of guest (LINK) and walk-in (STAFF, no client account) PII — phase 373.
 *
 * <p><b>Retention ({@link #anonymiseDue}).</b> Rows whose {@code ends_at} is before the cutoff are
 * claimed AND anonymised by one native UPDATE per batch ({@code FOR UPDATE SKIP LOCKED}), each batch
 * in its own {@code REQUIRES_NEW} transaction so a mid-run failure keeps earlier batches. In the same
 * batch transaction the headers of the just-claimed bookings are anonymised once none of their items
 * is left un-anonymised (reached by id, never by scanning open headers). Two bounded sweeps follow:
 * headers whose children all ended before the cutoff and are anonymised but which a cross-instance
 * race skipped (driven from recently anonymised items), and child-less headers, due by their own
 * {@code created_at}.
 *
 * <p><b>Erase ({@link #eraseByPhone}).</b> One transaction, in this order (D12): normalise the phone,
 * lock the phone's upcoming guest visits and single bookings, cancel them through the existing guest
 * cancellation seams (provider outbox + in-app rows, no guest SMS), anonymise every guest row of the
 * phone regardless of age, delete its OTP rows, rename its GUEST client blocks to the anonymised name (phase 398
 * D7 — hash and last 4 stay, so the block stays enforced). A rollback leaves neither a cancellation nor a wipe.
 *
 * <p><b>Guest reviews (phase 402).</b> Wherever booking rows are anonymised (each retention batch, the erase), the
 * same transaction sets the author label of their {@code GUEST_LINK} reviews to {@link GuestPii#ANONYMISED_NAME} and
 * skips their visits' / single bookings' {@code SCHEDULED} review invites ({@code ANONYMISED}); headers anonymised by
 * the two sweeps skip their visit invites too. The erase also expires the phone's {@code SENT} invites (audit-fix
 * cycle 1), so an already-delivered review link stops resolving. {@code sms_opt_outs} is never touched (402 D2).
 *
 * <p>Logs carry counts, the cutoff and the support ticket reference only — never a phone, a name or
 * a row id (§I).
 */
@Slf4j
@Service
public class GuestPiiAnonymisationService {

    static final String METRIC = "guest.pii.anonymised";

    /**
     * How far back the ended-visit sweep looks for recently anonymised visit items. The race it
     * repairs (two instances each claiming one child of the same header) lives within one nightly
     * run, so 30 days tolerates a long job outage with a wide margin.
     */
    static final Duration LEFTOVER_LOOKBACK = Duration.ofDays(30);

    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final BookingCancellationService bookingCancellationService;
    private final GuestVisitCancellationService guestVisitCancellationService;
    private final PhoneOtpService phoneOtpService;
    private final ClientBlockService clientBlockService;
    private final GuestReviewRetentionService guestReviewRetentionService;
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
            ClientBlockService clientBlockService,
            GuestReviewRetentionService guestReviewRetentionService,
            GuestPiiRetentionProperties properties,
            MeterRegistry meterRegistry,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.appointmentRepository = appointmentRepository;
        this.bookingCancellationService = bookingCancellationService;
        this.guestVisitCancellationService = guestVisitCancellationService;
        this.phoneOtpService = phoneOtpService;
        this.clientBlockService = clientBlockService;
        this.guestReviewRetentionService = guestReviewRetentionService;
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
        int bookings = 0;
        int visits = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int[] claimed = batchTx.execute(status -> claimBookingBatch(utcCutoff, batchSize));
            bookings += claimed[0];
            visits += claimed[1];
            if (claimed[0] < batchSize) {
                break;
            }
        }
        OffsetDateTime recentSince = OffsetDateTime.now(clock).minus(LEFTOVER_LOOKBACK);
        visits += sweep(() -> skipVisitInvites(appointmentRepository.anonymiseEndedGuestAppointments(
                utcCutoff, recentSince, GuestPii.ANONYMISED_NAME, batchSize)), batchSize, maxBatches);
        visits += sweep(() -> skipVisitInvites(appointmentRepository.anonymiseLeftoverGuestAppointments(
                utcCutoff, GuestPii.ANONYMISED_NAME, batchSize)), batchSize, maxBatches);
        record("job", bookings, visits);
        log.info("Guest PII retention: anonymised {} bookings, {} visits in {} ms (cutoff {})",
                bookings, visits, clock.millis() - startedAt, cutoff);
        return GuestPiiAnonymisationResult.ofRetention(bookings, visits);
    }

    /** Batched leftover-header sweep, each batch in its own transaction; returns the total. */
    private int sweep(Supplier<List<UUID>> claimBatch, int batchSize, int maxBatches) {
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<UUID> swept = batchTx.execute(status -> claimBatch.get());
            int count = swept == null ? 0 : swept.size();
            total += count;
            if (count < batchSize) {
                break;
            }
        }
        return total;
    }

    /** One booking batch + the headers it completes; {@code [bookings, headers]}. */
    private int[] claimBookingBatch(OffsetDateTime utcCutoff, int batchSize) {
        List<UUID> bookingIds = bookingRepository.anonymiseDueGuestBookings(
                utcCutoff, GuestPii.ANONYMISED_NAME, batchSize);
        if (bookingIds.isEmpty()) {
            return new int[]{0, 0};
        }
        List<UUID> headerIds = appointmentRepository
                .anonymiseGuestAppointmentsOfBookings(bookingIds, GuestPii.ANONYMISED_NAME);
        anonymiseGuestReviewData(bookingIds, headerIds);
        return new int[]{bookingIds.size(), headerIds.size()};
    }

    /** Phase 402 D3 — a sweep's anonymised headers skip their visit invites; returns the ids unchanged. */
    private List<UUID> skipVisitInvites(List<UUID> appointmentIds) {
        guestReviewRetentionService.skipScheduledInvites(appointmentIds, List.of());
        return appointmentIds;
    }

    /**
     * Phase 402 D1/D3 — in the anonymising transaction: guest review labels of {@code bookingIds} → «Гість», and the
     * {@code SCHEDULED} invites of their visits (a visit item's {@code appointment_id}, plus {@code headerIds}) and of
     * the single bookings among them → {@code SKIPPED (ANONYMISED)}. Runs after every other statement of the
     * transaction: the review update clears the persistence context (phase 399 audit).
     */
    private void anonymiseGuestReviewData(Collection<UUID> bookingIds, Collection<UUID> headerIds) {
        Set<UUID> appointmentIds = new LinkedHashSet<>(headerIds);
        List<UUID> singleBookingIds = new ArrayList<>();
        if (!bookingIds.isEmpty()) {
            for (GuestBookingSubject subject : bookingRepository.findGuestBookingSubjects(bookingIds)) {
                if (subject.getAppointmentId() != null) {
                    appointmentIds.add(subject.getAppointmentId());
                } else {
                    singleBookingIds.add(subject.getBookingId());
                }
            }
        }
        guestReviewRetentionService.skipScheduledInvites(appointmentIds, singleBookingIds);
        guestReviewRetentionService.anonymiseReviewAuthors(bookingIds);
    }

    /**
     * Erases one phone's guest PII on request (internal endpoint). Never refuses: upcoming rows are
     * cancelled first (provider notified exactly like a guest cancel), then everything is wiped.
     *
     * @param rawPhone  any Ukrainian phone format; invalid → 400 that never echoes the input
     * @param ticketRef optional support ticket reference (already validated {@code [A-Za-z]{2,10}-[0-9]{1,20}}),
     *                  logged with the counts so every erase is attributable; {@code null} → "none"
     */
    @Transactional
    public GuestPiiAnonymisationResult eraseByPhone(String rawPhone, @Nullable String ticketRef) {
        long startedAt = clock.millis();
        String phone = UkrainianPhoneNormalizer.toE164(rawPhone);
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Lock order matches the token visit path: headers first, then booking rows.
        List<UUID> visitIds = appointmentRepository.lockUpcomingGuestVisitIdsByPhone(phone, now);
        List<UUID> bookingIds = bookingRepository.lockUpcomingGuestSingleBookingIdsByPhone(phone, now);
        int visitsCancelled = cancelVisits(visitIds);
        int bookingsCancelled = cancelSingleBookings(bookingIds);

        List<UUID> anonymisedBookingIds = bookingRepository
                .anonymiseGuestBookingsByPhone(phone, GuestPii.ANONYMISED_NAME);
        List<UUID> anonymisedVisitIds = appointmentRepository
                .anonymiseGuestAppointmentsByPhone(phone, GuestPii.ANONYMISED_NAME);
        int bookingsAnonymised = anonymisedBookingIds.size();
        int visitsAnonymised = anonymisedVisitIds.size();
        int otpRowsDeleted = phoneOtpService.deleteAllForPhone(phone);
        // Phase 398 D7 — the phone's GUEST blocks lose their name snapshot but keep hash + last 4: an active
        // safety record, so the block keeps refusing this phone. anonymiseDue never touches blocks.
        int blocksAnonymised = clientBlockService.anonymiseGuestBlocks(phone, GuestPii.ANONYMISED_NAME);
        // Phase 402 — last: the review label update clears the persistence context. sms_opt_outs is kept (402 D2).
        anonymiseGuestReviewData(anonymisedBookingIds, anonymisedVisitIds);
        // Phase 402 audit-fix (sec LOW) — the phone's already-sent review links die with the erase (SENT → EXPIRED).
        int invitesExpired = guestReviewRetentionService.expireSentInvitesOfPhone(phone, clock.instant());

        record("manual", bookingsAnonymised, visitsAnonymised);
        log.info("Guest PII erase (ticket {}): cancelled {} bookings, {} visits; anonymised {} bookings, "
                        + "{} visits, {} blocks; expired {} review invites; deleted {} OTP rows in {} ms",
                ticketRef == null ? "none" : ticketRef, bookingsCancelled, visitsCancelled, bookingsAnonymised,
                visitsAnonymised, blocksAnonymised, invitesExpired, otpRowsDeleted, clock.millis() - startedAt);
        return new GuestPiiAnonymisationResult(
                bookingsCancelled, visitsCancelled, bookingsAnonymised, visitsAnonymised, otpRowsDeleted);
    }

    /** Two statements for any number of visits: headers by id, items by one graph query. */
    private int cancelVisits(List<UUID> appointmentIds) {
        if (appointmentIds.isEmpty()) {
            return 0;
        }
        Map<UUID, Appointment> headers = appointmentRepository.findAllById(appointmentIds).stream()
                .collect(Collectors.toMap(Appointment::getId, Function.identity()));
        Map<UUID, List<Booking>> itemsByVisit = bookingRepository
                .findByAppointmentIdInWithFullGraph(appointmentIds).stream()
                .collect(Collectors.groupingBy(b -> b.getAppointment().getId(),
                        LinkedHashMap::new, Collectors.toList()));
        int cancelled = 0;
        for (UUID appointmentId : appointmentIds) {
            Appointment appointment = headers.get(appointmentId);
            List<Booking> items = itemsByVisit.getOrDefault(appointmentId, List.of());
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

    private void record(String trigger, int bookings, int visits) {
        meterRegistry.counter(METRIC, "entity", "booking", "trigger", trigger).increment(bookings);
        meterRegistry.counter(METRIC, "entity", "appointment", "trigger", trigger).increment(visits);
    }
}
