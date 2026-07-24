package com.beautica.auth;

import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Atomic write unit for invite dispatch, deliberately split out of {@link InviteService}.
 *
 * <p><strong>Why a separate bean (CORRECTNESS):</strong> persisting the new {@link InviteToken}
 * can collide with the {@code ux_invite_tokens_active} partial-unique guard when a concurrent
 * same-{@code (salon, lower(email))} request raced past {@code sendInvite}'s active-invite
 * pre-check. A Postgres constraint violation marks the <em>current</em> transaction
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
 * losing race rolls back both, guaranteeing no second token and no second outbox row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitePersistenceService {

    private final InviteTokenRepository inviteTokenRepository;
    private final NotificationOutboxService outboxService;
    private final Clock clock;

    /**
     * Atomically recycles any expired-but-unused token holding the active slot, inserts the new
     * invite token, and enqueues the outbox notification — all in a fresh transaction.
     *
     * @throws DataIntegrityViolationException when a concurrent active invite for the same
     *                                         {@code (salon, lower(email))} already occupies the
     *                                         {@code ux_invite_tokens_active} slot; the caller
     *                                         resolves this idempotently with a generic 201.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistInviteAndEnqueue(
            String email,
            UUID salonId,
            Role role,
            Instant expiresAt,
            String hashedToken,
            String inviteLink,
            String salonName) {

        // Recycle ONLY an expired token occupying the partial-unique slot. A still-active token
        // here means a concurrent request won the race — leave it untouched so the saveAndFlush
        // below trips the unique guard and the caller resolves idempotently. Flush the DELETE
        // before the INSERT: Hibernate orders inserts ahead of deletes within a flush by default,
        // which would otherwise collide the fresh row against the very row being replaced.
        inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse(email, salonId)
                .filter(existing -> existing.getExpiresAt().isBefore(clock.instant()))
                .ifPresent(expired -> {
                    inviteTokenRepository.delete(expired);
                    inviteTokenRepository.flush();
                });

        var inviteToken = new InviteToken(hashedToken, email, salonId, role, expiresAt);
        // saveAndFlush forces the INSERT now so a unique-violation surfaces here (catchable by
        // the caller across this REQUIRES_NEW boundary) rather than at the caller's commit.
        InviteToken saved = inviteTokenRepository.saveAndFlush(inviteToken);

        // Outbox row shares this inner transaction (enqueueInvite uses Propagation.MANDATORY),
        // so a losing race rolls it back together with the token — never a second e-mail.
        outboxService.enqueueInvite(saved.getId(), email, inviteLink, salonName);
    }
}
