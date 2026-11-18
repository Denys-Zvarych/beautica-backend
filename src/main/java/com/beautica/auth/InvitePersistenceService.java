package com.beautica.auth;

import com.beautica.common.exception.IdentityException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.InviteSmsPolicyConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.user.InviteSmsPhoneSend;
import com.beautica.user.InviteSmsPhoneSendRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Atomic write unit for invite dispatch, deliberately split out of {@link InviteService}.
 *
 * <p><strong>Why a separate bean (CORRECTNESS):</strong> persisting the new {@link InviteToken}
 * can collide with the {@code ux_invite_tokens_active_phone} partial-unique guard (V251) when a concurrent
 * same-{@code (salon, phone)} request raced past {@code sendInvite}'s active-invite pre-check. A Postgres constraint violation marks the <em>current</em> transaction
 * rollback-only; if that work ran in the caller's transaction, catching the resulting
 * {@link DataIntegrityViolationException} and returning normally would still make the outer
 * commit throw {@code UnexpectedRollbackException} (a 500), defeating the intended graceful
 * idempotent 201. {@code sendInvite} is also invoked from within another transaction
 * ({@code SalonService.inviteMaster}), so the caller's transaction must never be poisoned.
 *
 * <p>{@link Propagation#REQUIRES_NEW} runs this method in its own physical transaction,
 * suspending the caller's. A unique-violation rolls back only this inner transaction; the
 * exception then propagates to {@code sendInvite}, which catches it cleanly and returns the
 * generic success. Self-invocation would NOT trigger a new proxy, hence the dedicated bean.
 *
 * <p>Token persist and outbox enqueue are co-located here so they share one atomic unit: a
 * losing race rolls back both, guaranteeing no second token and no second outbox row (no second SMS). B520: the
 * invite-SMS caps (AUTH-52) are counted here too, under advisory locks, in the same unit as the insert they guard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitePersistenceService {

    /** AUTH-52 / AUTH-D14 cap windows (the per-phone cap shares the 24 h salon window). */
    private static final Duration INVITER_WINDOW = Duration.ofHours(1);
    private static final Duration SALON_WINDOW = Duration.ofDays(1);
    /** Per-phone send log retention: the 24 h window plus a day of slack for the daily purge. */
    private static final Duration PHONE_SEND_RETENTION = Duration.ofDays(2);

    private final InviteTokenRepository inviteTokenRepository;
    private final NotificationOutboxService outboxService;
    private final InviteSmsPhoneSendRepository phoneSendRepository;
    private final PhoneHasher phoneHasher;
    private final InviteSmsPolicyConfig smsPolicy;
    private final Clock clock;

    /**
     * Atomically: enforces the invite-SMS caps (AUTH-52) under per-inviter + per-salon advisory locks, retires any
     * expired-but-unused token holding the active {@code (salon, phone)} slot (marking it
     * {@link com.beautica.user.RevocationReason#SUPERSEDED} rather than deleting it, so the salon's history stays
     * complete), inserts the new phone invite and enqueues its SMS — all in a fresh transaction, so the SMS row
     * commits with the invite or not at all.
     *
     * @throws DataIntegrityViolationException when a concurrent active invite for the same {@code (salon, phone)}
     *                                         already occupies {@code ux_invite_tokens_active_phone}; the caller
     *                                         resolves this idempotently with a generic 201 (one row, one SMS)
     * @throws IdentityException               429 {@code INVITE_SMS_LIMIT} — 10 invite SMS per inviter per hour, 30
     *                                         per salon per day or 3 per phone per day (any salon) already sent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistInviteAndEnqueue(PhoneInvite invite) {
        // ONE instant for the whole operation: expiry judged, revoked_at stamped and the cap windows anchored on it.
        Instant now = clock.instant();

        // Count-then-insert under the three transaction-scoped locks (inviter, salon, phone — always that order), so
        // concurrent dispatches cannot all pass a cap.
        inviteTokenRepository.acquireInviteSmsCapLocks(invite.inviterId(), invite.salonId());
        inviteTokenRepository.acquireInvitePhoneCapLock(invite.phoneE164());
        enforceSmsCaps(invite.inviterId(), invite.salonId(), now);
        byte[] phoneHash = phoneHasher.hash(invite.phoneE164()).hash();
        enforcePhoneCap(phoneHash, now);

        // Recycle ONLY an expired token occupying the partial-unique slot. A still-active token here means a
        // concurrent request won the race — leave it untouched so the saveAndFlush below trips the unique guard and
        // the caller resolves idempotently. The explicit flush is required: Hibernate runs INSERTs before UPDATEs, so
        // an unflushed supersede would let the INSERT hit the index while the retired row still holds the slot.
        inviteTokenRepository.findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(invite.phoneE164(), invite.salonId())
                // The SAME canonical predicate acceptInvite uses (InviteToken#isExpired).
                .filter(existing -> existing.isExpiredAt(now))
                .ifPresent(expired -> {
                    expired.markSuperseded(now);
                    inviteTokenRepository.saveAndFlush(expired);
                });

        var inviteToken = InviteToken.forPhone(invite.hashedToken(), invite.phoneE164(), invite.salonId(),
                invite.role(), invite.expiresAt(), invite.inviterId());
        // saveAndFlush forces the INSERT now so a unique-violation surfaces here (catchable by the caller across this
        // REQUIRES_NEW boundary) rather than at the caller's commit.
        InviteToken saved = inviteTokenRepository.saveAndFlush(inviteToken);

        // Outbox row shares this inner transaction (Propagation.MANDATORY), so a losing race rolls it back together
        // with the token — never a second SMS.
        outboxService.enqueueStaffInviteSms(saved.getId(), invite.phoneE164(), invite.inviteLink(), invite.salonName());
        // Same transaction again: a lost race rolls the send log back too, so it counts only SMS actually enqueued.
        phoneSendRepository.save(InviteSmsPhoneSend.of(phoneHash, now));
    }

    private void enforceSmsCaps(UUID inviterId, UUID salonId, Instant now) {
        boolean inviterCapped = inviteTokenRepository.countPhoneInvitesByInviterSince(
                inviterId, now.minus(INVITER_WINDOW)) >= smsPolicy.perInviterPerHour();
        if (inviterCapped || inviteTokenRepository.countPhoneInvitesBySalonSince(
                salonId, now.minus(SALON_WINDOW)) >= smsPolicy.perSalonPerDay()) {
            // Ids only — never the phone (Anti-Bug §I).
            log.info("Invite SMS cap reached (salonId={}, inviterCapped={})", salonId, inviterCapped);
            throw new IdentityException(IdentityException.Code.INVITE_SMS_LIMIT);
        }
    }

    /**
     * AUTH-D14 per-phone limit: at most {@code perPhonePerDay} invite SMS to one phone per rolling 24 h, counted across
     * ALL salons and inviters on the durable {@code invite_sms_phone_sends} log (V252, keyed phone hash), so cancel +
     * re-invite, many salons, or deleting the inviting owner account (which hard-deletes its {@code invite_tokens},
     * SUP-67) cannot pump one victim. Deliberately NOT the 15-min OTP bucket on
     * {@code phone_otps}: sharing it would let three invites block the invitee's own registration code, the very step
     * the invite leads to.
     */
    private void enforcePhoneCap(byte[] phoneHash, Instant now) {
        if (phoneSendRepository.countByPhoneHashSince(phoneHash, now.minus(SALON_WINDOW))
                >= smsPolicy.perPhonePerDay()) {
            // Never the phone (Anti-Bug §I).
            log.info("Invite SMS per-phone cap reached");
            throw new IdentityException(IdentityException.Code.INVITE_SMS_LIMIT);
        }
    }

    /** Daily purge (04:23 UTC, clear of the other 03:xx/04:13 sweeps) of per-phone send-log rows past the window. */
    @Scheduled(cron = "0 23 4 * * *", zone = "UTC")
    @Transactional
    public void purgeExpiredPhoneSends() {
        int deleted = phoneSendRepository.deleteSentBefore(clock.instant().minus(PHONE_SEND_RETENTION));
        log.debug("Invite SMS phone-send log purge removed {} rows", deleted);
    }

    /**
     * One phone invite to persist.
     *
     * @param phoneE164   the invited number, normalised by {@code UaMobilePhone}
     * @param hashedToken SHA-256 of the raw token (only the hash is stored)
     * @param inviteLink  the join link embedding the raw token — sealed in the outbox, never logged
     * @param inviterId   the inviting user (per-inviter cap)
     */
    public record PhoneInvite(String phoneE164, UUID salonId, Role role, Instant expiresAt, String hashedToken,
                              String inviteLink, String salonName, UUID inviterId) {

        /** Carries a phone and a live link — never in a log line. */
        @Override
        public String toString() {
            return "PhoneInvite[salonId=" + salonId + ", role=" + role + "]";
        }
    }
}
