package com.beautica.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
