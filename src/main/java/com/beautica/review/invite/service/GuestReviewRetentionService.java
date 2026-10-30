package com.beautica.review.invite.service;

import com.beautica.booking.retention.GuestPii;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.event.GuestReviewsAnonymisedEvent;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.repository.GuestReviewRef;
import com.beautica.review.repository.ReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 402 — guest reviews and review invites follow the guest-PII lifecycle.
 * <ul>
 *   <li><b>D1</b> {@link #anonymiseReviewAuthors}: the {@code GUEST_LINK} reviews of anonymised bookings show
 *       {@link GuestPii#ANONYMISED_NAME}; rating and text stay. Cached public views are evicted after commit.</li>
 *   <li><b>D3</b> {@link #skipScheduledInvites}: a pending {@code SCHEDULED} invite of an anonymised visit or single
 *       booking becomes {@code SKIPPED (ANONYMISED)} — defence in depth, the send job re-checks {@code anonymised}.</li>
 *   <li><b>D3</b> {@link #purgeInvites}: terminal invites are deleted {@link #INVITE_RETENTION} after their terminal
 *       moment, in {@code LIMIT}-bounded batches, each in its own {@code REQUIRES_NEW} transaction (the guest-PII
 *       retention pattern).</li>
 *   <li><b>Erase</b> {@link #expireSentInvitesOfPhone} (audit-fix cycle 1, sec LOW): the erased phone's {@code SENT}
 *       invites become {@code EXPIRED} at once, so their SMS links stop resolving; the opt-out link keeps working.</li>
 * </ul>
 * {@code sms_opt_outs} is never touched here (D2: the opt-out is kept, as a hash, unless a support ticket removes it).
 * Logs carry counts only.
 */
@Slf4j
@Service
public class GuestReviewRetentionService {

    /** D3 (locked) — terminal invites are kept this long after their terminal moment. */
    public static final Duration INVITE_RETENTION = Duration.ofDays(30);

    private final ReviewRepository reviewRepository;
    private final ReviewInviteRepository reviewInviteRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PhoneHasher phoneHasher;
    private final TransactionTemplate batchTx;

    public GuestReviewRetentionService(ReviewRepository reviewRepository,
                                       ReviewInviteRepository reviewInviteRepository,
                                       ApplicationEventPublisher eventPublisher,
                                       PhoneHasher phoneHasher,
                                       PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewInviteRepository = reviewInviteRepository;
        this.eventPublisher = eventPublisher;
        this.phoneHasher = phoneHasher;
        this.batchTx = new TransactionTemplate(transactionManager);
        this.batchTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * D1 — anonymises the author label of every {@code GUEST_LINK} review of {@code bookingIds}, in the caller's
     * transaction (the anonymisation batch or the erase). Clears the persistence context (phase 399 audit).
     *
     * @return the number of reviews anonymised
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int anonymiseReviewAuthors(Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return 0;
        }
        List<GuestReviewRef> refs = reviewRepository.findGuestReviewsToAnonymise(bookingIds, GuestPii.ANONYMISED_NAME);
        if (refs.isEmpty()) {
            return 0;
        }
        Set<UUID> reviewIds = refs.stream().map(GuestReviewRef::getId).collect(Collectors.toSet());
        int anonymised = reviewRepository.anonymiseGuestDisplayNames(reviewIds, GuestPii.ANONYMISED_NAME);
        eventPublisher.publishEvent(new GuestReviewsAnonymisedEvent(reviewIds,
                refs.stream().map(GuestReviewRef::getMasterId).filter(Objects::nonNull).collect(Collectors.toSet()),
                refs.stream().map(GuestReviewRef::getSalonId).filter(Objects::nonNull).collect(Collectors.toSet())));
        return anonymised;
    }

    /**
     * D3 — {@code SCHEDULED → SKIPPED (ANONYMISED)} for the invites of the anonymised visits ({@code appointmentIds})
     * and single bookings ({@code bookingIds}), in the caller's transaction. One statement per non-empty list, each
     * served by its UNIQUE index — an empty list never binds {@code IN ()}.
     *
     * @return the number of invites skipped
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int skipScheduledInvites(Collection<UUID> appointmentIds, Collection<UUID> bookingIds) {
        int skipped = 0;
        if (!appointmentIds.isEmpty()) {
            skipped += reviewInviteRepository.skipScheduledForAppointments(appointmentIds);
        }
        if (!bookingIds.isEmpty()) {
            skipped += reviewInviteRepository.skipScheduledForBookings(bookingIds);
        }
        return skipped;
    }

    /**
     * Erase arm (audit-fix cycle 1, sec LOW) — every {@code SENT} invite of {@code e164Phone} becomes {@code EXPIRED}
     * with {@code expires_at = now}, in the caller's (erase) transaction: the review link in an SMS the guest already
     * received returns 404 from then on, while its opt-out link still works (30 days from sending) and the row is
     * purged 30 days after the erase. The phone is hashed here ({@link PhoneHasher}) and never logged.
     *
     * @param e164Phone the erased phone, already normalised to E.164
     * @return the number of invites expired
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int expireSentInvitesOfPhone(String e164Phone, Instant now) {
        return reviewInviteRepository.expireSentForPhoneHash(phoneHasher.hash(e164Phone).hash(), now);
    }

    /**
     * D3 — deletes terminal invites older than {@link #INVITE_RETENTION} at {@code now}: batches of {@code batchSize}
     * until one comes back short or {@code maxBatches} is spent (the rest is picked up next run).
     *
     * @return the number of invites deleted
     */
    public int purgeInvites(Instant now, int batchSize, int maxBatches) {
        Instant cutoff = now.minus(INVITE_RETENTION);
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            Integer deleted = batchTx.execute(status -> reviewInviteRepository.purgeTerminalBefore(cutoff, batchSize));
            int count = deleted == null ? 0 : deleted;
            total += count;
            if (count < batchSize) {
                break;
            }
        }
        log.info("Review invite retention: purged {} invites (cutoff {})", total, cutoff);
        return total;
    }
}
