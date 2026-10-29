package com.beautica.review.invite.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.ReviewInvite;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.entity.ReviewInviteStatus;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.repository.ReviewInviteVisitView;
import com.beautica.review.invite.service.ReviewInviteEligibility.PreloadedBatch;
import com.beautica.review.invite.service.ReviewInviteEligibility.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phase 400 D4/D6 — the transactional halves of the send job ({@link ReviewInviteSendJob} runs the SMS calls between
 * them, holding no DB connection).
 *
 * <p><b>Delivery contract: at most once.</b> {@link #claimAndPrepare} commits {@code SENT} (with the token hash)
 * BEFORE the SMS is attempted. A concurrent tick therefore can never send the same invite, and a crash between that
 * commit and the provider call loses the SMS rather than sending it twice — a duplicate review SMS reads as spam on a
 * guest's only channel. A provider failure is reported back through {@link #recordFailedSend}, which voids the token
 * and re-queues the row (or fails it after {@code max-attempts}); an UNKNOWN outcome (read timeout) goes through
 * {@link #recordAmbiguousSend} and is never retried; a row the tick ran out of time for goes back through
 * {@link #releaseUnsent}.
 */
@Slf4j
@Service
public class ReviewInviteSendService {

    /** 128 bits → 22 base64url chars, the length the SMS budget is computed for (D4/D5). */
    static final int TOKEN_BYTES = 16;

    private final ReviewInviteRepository reviewInviteRepository;
    private final ReviewInviteEligibility eligibility;
    private final TokenGenerator tokenGenerator;
    private final ReviewInviteSmsText smsText;
    private final ReviewInviteProperties properties;

    public ReviewInviteSendService(ReviewInviteRepository reviewInviteRepository,
                                   ReviewInviteEligibility eligibility,
                                   TokenGenerator tokenGenerator,
                                   ReviewInviteSmsText smsText,
                                   ReviewInviteProperties properties) {
        this.reviewInviteRepository = reviewInviteRepository;
        this.eligibility = eligibility;
        this.tokenGenerator = tokenGenerator;
        this.smsText = smsText;
        this.properties = properties;
    }

    /** D6 — one bounded batch of {@code SENT} invites past {@code expires_at} → {@code EXPIRED}. */
    @Transactional
    public int expireSent(Instant now) {
        return reviewInviteRepository.expireSent(now, properties.batchSize());
    }

    /**
     * D4 — claims one bounded batch of due invites ({@code FOR UPDATE SKIP LOCKED}) and decides each one on fresh
     * visit data: stale → {@code SKIPPED (STALE)}; a failing gate → {@code SKIPPED} with its reason; otherwise a new
     * token is issued, its SHA-256 stored and the row marked {@code SENT}. Returns the SMS to send after commit.
     */
    @Transactional
    public List<PreparedInviteSms> claimAndPrepare(Instant now) {
        List<ReviewInvite> due = reviewInviteRepository.claimDue(now, properties.batchSize());
        if (due.isEmpty()) {
            return List.of();
        }
        Snapshots snapshots = loadVisits(due);
        PreloadedBatch batch = eligibility.preload(snapshots.all());
        List<PreparedInviteSms> prepared = new ArrayList<>(due.size());
        for (ReviewInvite invite : due) {
            if (snapshots.isMalformed(invite)) {
                invite.skip(ReviewInviteSkipReason.INVALID_DATA);
                continue;
            }
            prepare(invite, snapshots.of(invite), batch, now).ifPresent(prepared::add);
        }
        return prepared;
    }

    /**
     * Phase 400 audit cycle 1 — the provider call ended with an UNKNOWN outcome (the SMS may have gone out):
     * {@code FAILED}, never retried, so a slow provider cannot cause a duplicate SMS.
     */
    @Transactional
    public void recordAmbiguousSend(UUID inviteId) {
        reviewInviteRepository.findById(inviteId)
                .filter(invite -> invite.getStatus() == ReviewInviteStatus.SENT)
                .ifPresent(ReviewInvite::recordAmbiguousSend);
    }

    /**
     * Phase 400 audit cycle 1 — the tick's send budget ran out before these claimed invites were attempted: each
     * goes back to {@code SCHEDULED} with its token voided, so none is left {@code SENT} without an SMS.
     */
    @Transactional
    public void releaseUnsent(Collection<UUID> inviteIds) {
        if (inviteIds.isEmpty()) {
            return;
        }
        reviewInviteRepository.findAllById(inviteIds).stream()
                .filter(invite -> invite.getStatus() == ReviewInviteStatus.SENT)
                .forEach(ReviewInvite::releaseUnsent);
    }

    /** D4 — the provider refused the SMS: void the token, count the attempt, re-queue or fail. */
    @Transactional
    public void recordFailedSend(UUID inviteId) {
        reviewInviteRepository.findById(inviteId)
                .filter(invite -> invite.getStatus() == ReviewInviteStatus.SENT)
                .ifPresent(invite -> invite.recordFailedSend(properties.maxAttempts()));
    }

    /**
     * D7 — partitions the batch by subject: visit ids go to {@code findVisitViews}, single-booking ids to
     * {@code findBookingViews}. At most two queries per batch (none for an empty partition), never one per row.
     */
    private Snapshots loadVisits(List<ReviewInvite> due) {
        Map<Boolean, List<ReviewInvite>> bySubject =
                due.stream().collect(Collectors.partitioningBy(ReviewInvite::isBookingKeyed));
        List<UUID> appointmentIds = bySubject.get(false).stream().map(ReviewInvite::getAppointmentId).toList();
        List<UUID> bookingIds = bySubject.get(true).stream().map(ReviewInvite::getBookingId).toList();
        Set<UUID> malformed = new HashSet<>();
        Map<UUID, VisitSnapshot> byVisit = appointmentIds.isEmpty() ? Map.of()
                : index(reviewInviteRepository.findVisitViews(appointmentIds),
                        ReviewInviteVisitView::getAppointmentId, malformed);
        Map<UUID, VisitSnapshot> byBooking = bookingIds.isEmpty() ? Map.of()
                : index(reviewInviteRepository.findBookingViews(bookingIds),
                        ReviewInviteVisitView::getBookingId, malformed);
        return new Snapshots(byVisit, byBooking, malformed);
    }

    /**
     * Phase 400 audit cycle 1 — maps each view on its own: a row that cannot be mapped (an unknown
     * {@code booking_source}, a NULL flag) is recorded in {@code malformed} by subject id instead of throwing, so it
     * cannot roll back the claim for the whole batch and be re-claimed — and re-fail — every tick.
     */
    private static Map<UUID, VisitSnapshot> index(List<ReviewInviteVisitView> views,
                                                  Function<ReviewInviteVisitView, UUID> key, Set<UUID> malformed) {
        Map<UUID, VisitSnapshot> indexed = new HashMap<>();
        for (ReviewInviteVisitView view : views) {
            UUID subjectId = key.apply(view);
            try {
                indexed.put(subjectId, VisitSnapshot.of(view));
            } catch (IllegalArgumentException | NullPointerException e) {
                malformed.add(subjectId);
                log.warn("Review invite subject not mappable: subject={} cause={}",
                        subjectId, e.getClass().getSimpleName());
            }
        }
        return indexed;
    }

    /**
     * The batch's snapshots keyed by subject id; {@code null} for a vanished subject (→ NOTHING_TO_REVIEW).
     * {@code malformed} holds the subject ids whose view could not be mapped (→ INVALID_DATA).
     */
    private record Snapshots(Map<UUID, VisitSnapshot> byVisit, Map<UUID, VisitSnapshot> byBooking,
                             Set<UUID> malformed) {

        VisitSnapshot of(ReviewInvite invite) {
            return invite.isBookingKeyed()
                    ? byBooking.get(invite.getBookingId())
                    : byVisit.get(invite.getAppointmentId());
        }

        boolean isMalformed(ReviewInvite invite) {
            return malformed.contains(invite.isBookingKeyed() ? invite.getBookingId() : invite.getAppointmentId());
        }

        List<VisitSnapshot> all() {
            List<VisitSnapshot> all = new ArrayList<>(byVisit.values());
            all.addAll(byBooking.values());
            return all;
        }
    }

    private Optional<PreparedInviteSms> prepare(ReviewInvite invite, VisitSnapshot visit, PreloadedBatch batch,
                                                Instant now) {
        if (invite.getSendAfter().plus(properties.staleAfter()).isBefore(now)) {
            invite.skip(ReviewInviteSkipReason.STALE);
            return Optional.empty();
        }
        Verdict verdict = visit == null
                ? new Verdict(null, ReviewInviteSkipReason.NOTHING_TO_REVIEW)
                : eligibility.evaluateInBatch(visit, batch);
        if (!verdict.isEligible()) {
            invite.skip(verdict.skipReason());
            return Optional.empty();
        }
        String rawToken = tokenGenerator.generateToken(TOKEN_BYTES);
        invite.markSent(ReviewInviteTokenHasher.hash(rawToken), now, now.plus(properties.ttl()));
        return Optional.of(new PreparedInviteSms(invite.getId(), visit.guestPhone(), smsText.render(rawToken)));
    }
}
