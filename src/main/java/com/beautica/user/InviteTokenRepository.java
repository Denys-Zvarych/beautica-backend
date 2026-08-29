package com.beautica.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteTokenRepository extends JpaRepository<InviteToken, UUID> {

    Optional<InviteToken> findByToken(String token);

    /**
     * Salon-scoped active-invite lookup used by the invite-dispatch idempotency check.
     * Scoping by {@code salonId} lets independent salons each hold a pending invite for
     * the same email — one salon's pending invite no longer short-circuits another salon's
     * dispatch (the cross-salon silent-drop fix).
     *
     * <p><strong>Index roles:</strong> this {@code email = ? AND salon_id = ? AND is_used = false}
     * predicate is served by {@code idx_invite_tokens_email_used (email, is_used)} (V16) —
     * Postgres cannot use the expression index {@code ux_invite_tokens_active} on
     * {@code lower(email)} for a bare {@code email = ?} comparison. {@code ux_invite_tokens_active}
     * is the INSERT-time uniqueness/concurrency backstop only; it nonetheless caps the table at
     * one active row per {@code (salon, lower(email))}, so once {@code InviteService} normalises
     * the e-mail to lower-case this finder can never return two rows
     * (no {@code IncorrectResultSizeDataAccessException}).
     */
    Optional<InviteToken> findByEmailAndSalonIdAndIsUsedFalse(String email, UUID salonId);

    /**
     * Email-global (cross-salon) active-invite lookup. NOT used by invite dispatch — that
     * path uses {@link #findByEmailAndSalonIdAndIsUsedFalse} so the idempotency check is
     * salon-scoped. Retained only for cross-salon "does any pending invite exist for this
     * email" assertions and test cleanup; do not call from production write paths.
     */
    Optional<InviteToken> findByEmailAndIsUsedFalse(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteToken t WHERE t.token = :token")
    Optional<InviteToken> findByTokenForUpdate(@Param("token") String token);

    /**
     * {@code PESSIMISTIC_WRITE}-locked lookup by id — Phase 23.1 QA audit (Security MEDIUM) fix.
     * {@code SalonService#cancelInvite} now uses this instead of the plain {@code findById} it
     * originally shipped with, so it takes the SAME row lock {@link #findByTokenForUpdate} takes
     * for {@code InviteService#acceptInvite}. Before this fix, a cancel racing an accept could read
     * an unlocked, pre-accept snapshot (isUsed=false), and — because {@code cancelInvite} never
     * re-reads before flushing — blindly report 204 success after the accept had already committed
     * and provisioned the account, with no re-check catching the staleness. With both sides locking
     * the same row, whichever transaction's {@code SELECT ... FOR UPDATE} runs first serialises the
     * other; the loser's lock grant always returns the FRESH post-commit row (Postgres READ
     * COMMITTED semantics for a blocked {@code FOR UPDATE}), so its {@code isUsed()} check is never
     * stale. See {@code PendingInviteCancelAcceptRaceIT}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteToken t WHERE t.id = :id")
    Optional<InviteToken> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Pending (unused, unexpired) invites for a salon — Phase 23.1 {@code GET
     * /salons/{salonId}/invites/pending}. {@code InviteToken} carries {@code salonId} directly
     * (see the entity Javadoc/column), so this is a direct filter — no join through
     * {@code users.salon_id} is needed. Ordered newest-first so the owner/admin sees the most
     * recently dispatched invite at the top.
     */
    List<InviteToken> findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID salonId, Instant now);
}
