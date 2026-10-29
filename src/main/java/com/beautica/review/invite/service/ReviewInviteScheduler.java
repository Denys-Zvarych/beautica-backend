package com.beautica.review.invite.service;

import com.beautica.booking.event.GuestBookingCompletedEvent;
import com.beautica.booking.event.GuestVisitCompletedEvent;
import com.beautica.config.BookingSmsProperties;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.ReviewInvite;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.service.ReviewInviteEligibility.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 400 D1 — creates the review invite when a booking of a guest (LINK) visit is completed.
 *
 * <ul>
 *   <li><b>When.</b> {@code AFTER_COMMIT} of the completing transaction, so a rolled-back completion creates
 *       nothing. The insert runs in its own {@code REQUIRES_NEW} transaction (the committing one is finished).</li>
 *   <li><b>Subject (D7).</b> A visit ({@link GuestVisitCompletedEvent}, appointment-keyed) or a single LINK booking
 *       with no visit header ({@link GuestBookingCompletedEvent}, booking-keyed); both run {@code createInvite}.</li>
 *   <li><b>Once per visit.</b> {@code INSERT … ON CONFLICT (appointment_id) DO NOTHING}: an item-level completion
 *       followed by the header completion (or a replay) leaves the first row untouched, so {@code send_after} is
 *       derived from the first COMPLETED booking.</li>
 *   <li><b>Which rows.</b> Only LINK visits with no account. Flag off (either), anonymised or phone-less visits get
 *       no row at all; a visit failing a later gate (opt-out, policy version, nothing to review) gets a row that is
 *       {@code SKIPPED} from birth with the reason — the send job re-checks every gate anyway.</li>
 *   <li><b>Never throws.</b> Spring propagates an exception out of an {@code afterCommit} callback to the caller of
 *       an already-committed completion; a failed invite is logged (class name and appointment id only) and
 *       dropped — the guest simply gets no invite for that visit.</li>
 *   <li><b>Off the committing thread (audit cycle 1).</b> {@code @Async("reviewInviteExecutor")}: an
 *       {@code AFTER_COMMIT} listener runs while the committing thread still holds its pooled connection, so the
 *       {@code REQUIRES_NEW} insert pinned a SECOND one per completion. The bounded pool's {@code CallerRunsPolicy}
 *       means a saturated pool degrades to the old inline behaviour instead of dropping an invite. The test profile
 *       runs it inline ({@code AsyncConfig}).</li>
 *   <li><b>Flags first.</b> With either flag off nothing can be created, so the listener returns before opening a
 *       transaction or reading the visit.</li>
 * </ul>
 */
@Slf4j
@Component
public class ReviewInviteScheduler {

    private final ReviewInviteRepository reviewInviteRepository;
    private final ReviewInviteEligibility eligibility;
    private final QuietHoursCalculator quietHours;
    private final ReviewInviteProperties properties;
    private final BookingSmsProperties bookingSmsProperties;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public ReviewInviteScheduler(ReviewInviteRepository reviewInviteRepository,
                                 ReviewInviteEligibility eligibility,
                                 QuietHoursCalculator quietHours,
                                 ReviewInviteProperties properties,
                                 BookingSmsProperties bookingSmsProperties,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.reviewInviteRepository = reviewInviteRepository;
        this.eligibility = eligibility;
        this.quietHours = quietHours;
        this.properties = properties;
        this.bookingSmsProperties = bookingSmsProperties;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async("reviewInviteExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGuestVisitCompleted(GuestVisitCompletedEvent event) {
        if (!invitesPossible()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> reviewInviteRepository
                    .findVisitViews(List.of(event.appointmentId()))
                    .stream().findFirst()
                    .ifPresent(view -> createInvite(VisitSnapshot.of(view), event.completedAt())));
        } catch (RuntimeException e) {
            log.warn("Review invite not created: appointment={} cause={}",
                    event.appointmentId(), e.getClass().getSimpleName());
        }
    }

    /** Phase 400 D7 — the same create path for a single LINK booking with no visit header. */
    @Async("reviewInviteExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGuestBookingCompleted(GuestBookingCompletedEvent event) {
        if (!invitesPossible()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> reviewInviteRepository
                    .findBookingViews(List.of(event.bookingId()))
                    .stream().findFirst()
                    .ifPresent(view -> createInvite(VisitSnapshot.of(view), event.completedAt())));
        } catch (RuntimeException e) {
            log.warn("Review invite not created: booking={} cause={}",
                    event.bookingId(), e.getClass().getSimpleName());
        }
    }

    /** Both D2 flags on — the same first two gates {@link ReviewInviteEligibility} applies, checked before any I/O. */
    private boolean invitesPossible() {
        return properties.enabled() && bookingSmsProperties.getSms().isEnabled();
    }

    private void createInvite(VisitSnapshot visit, Instant completedAt) {
        if (!visit.isGuestLinkVisit()) {
            return;
        }
        Verdict verdict = eligibility.evaluate(visit);
        if (verdict.phone() == null) {
            // Flag off, anonymised or no usable phone: nothing to key a row on, and nothing will ever be sent.
            return;
        }
        insert(visit, completedAt, verdict);
    }

    private void insert(VisitSnapshot visit, Instant completedAt, Verdict verdict) {
        Instant now = clock.instant();
        Instant sendAfter = quietHours.sendAfter(completedAt);
        ReviewInvite invite = visit.bookingId() != null
                ? newBookingInvite(visit.bookingId(), verdict, sendAfter, now)
                : newVisitInvite(visit.appointmentId(), verdict, sendAfter, now);
        String skipReason = invite.getSkipReason() == null ? null : invite.getSkipReason().name();
        int inserted = invite.isBookingKeyed()
                ? reviewInviteRepository.insertIfAbsentForBooking(invite.getId(), invite.getBookingId(),
                        invite.getPhoneHash(), invite.getStatus().name(), invite.getSendAfter(), skipReason,
                        invite.getCreatedAt())
                : reviewInviteRepository.insertIfAbsent(invite.getId(), invite.getAppointmentId(),
                        invite.getPhoneHash(), invite.getStatus().name(), invite.getSendAfter(), skipReason,
                        invite.getCreatedAt());
        if (inserted == 1) {
            log.info("Review invite created: invite={} status={}", invite.getId(), invite.getStatus());
        }
    }

    private static ReviewInvite newVisitInvite(UUID appointmentId, Verdict verdict, Instant sendAfter, Instant now) {
        return verdict.isEligible()
                ? ReviewInvite.schedule(appointmentId, verdict.phone(), sendAfter, now)
                : ReviewInvite.skippedAtCreate(appointmentId, verdict.phone(), sendAfter, verdict.skipReason(), now);
    }

    private static ReviewInvite newBookingInvite(UUID bookingId, Verdict verdict, Instant sendAfter, Instant now) {
        return verdict.isEligible()
                ? ReviewInvite.scheduleForBooking(bookingId, verdict.phone(), sendAfter, now)
                : ReviewInvite.skippedAtCreateForBooking(bookingId, verdict.phone(), sendAfter,
                        verdict.skipReason(), now);
    }
}
