package com.beautica.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-access interface for {@link PasswordResetToken}.
 *
 * <p>Mirrors {@link InviteTokenRepository}: the pessimistic-write variant
 * ({@link #findByTokenForUpdate}) is the canonical lookup path for the reset-confirm
 * flow to close the TOCTOU window between the token-valid check and the used-flag flip.
 *
 * <p><strong>Repository scoping note:</strong> {@code findByToken} does NOT verify
 * ownership against the authenticated principal — ownership validation is the
 * responsibility of the service layer ({@code PasswordResetService.resetPassword}).
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Non-locking lookup — suitable for read-only checks (e.g. tests, admin tooling).
     * Use {@link #findByTokenForUpdate} on the reset-confirm write path.
     */
    Optional<PasswordResetToken> findByToken(String token);

    /**
     * Acquires a {@code PESSIMISTIC_WRITE} (SELECT FOR UPDATE) row lock before
     * returning the token row, exactly as {@link InviteTokenRepository#findByTokenForUpdate}
     * does for invite acceptance. Prevents two concurrent requests submitting the same
     * raw token from both passing the is_used check and both succeeding.
     *
     * <p>Must be called within an active {@code @Transactional} boundary.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PasswordResetToken t WHERE t.token = :token")
    Optional<PasswordResetToken> findByTokenForUpdate(@Param("token") String token);

    /**
     * Bulk-marks all unused reset tokens for the given user as consumed.
     *
     * <p>Called in two contexts:
     * <ol>
     *   <li><strong>Before issuing</strong> a new token — ensures a user never holds two
     *       live reset links simultaneously (Phase 11.2).</li>
     *   <li><strong>After a successful reset</strong> — defence-in-depth sweep to invalidate
     *       any other outstanding tokens for the same user (Phase 11.3).</li>
     * </ol>
     */
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.isUsed = true WHERE t.userId = :userId AND t.isUsed = false")
    void markAllUsedByUserId(@Param("userId") UUID userId);
}
