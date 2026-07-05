package com.beautica.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access interface for {@link PasswordResetTicket}.
 *
 * <p>Renamed from {@code PasswordResetTokenRepository} (Phase A1). Mirrors
 * {@link InviteTokenRepository}: the pessimistic-write variant
 * ({@link #findByTicketHashForUpdate}) is the canonical lookup path for the reset-confirm
 * flow to close the TOCTOU window between the ticket-valid check and the used-flag flip.
 *
 * <p><strong>Repository scoping note:</strong> {@code findByTicketHash} does NOT verify
 * ownership against the authenticated principal — ownership validation is the
 * responsibility of the service layer ({@code PasswordResetService.resetPassword}).
 */
public interface PasswordResetTicketRepository extends JpaRepository<PasswordResetTicket, UUID> {

    /**
     * Non-locking lookup — suitable for read-only checks (e.g. tests, admin tooling).
     * Use {@link #findByTicketHashForUpdate} on the reset-confirm write path.
     */
    Optional<PasswordResetTicket> findByTicketHash(String ticketHash);

    /**
     * Acquires a {@code PESSIMISTIC_WRITE} (SELECT FOR UPDATE) row lock before
     * returning the ticket row, exactly as {@link InviteTokenRepository#findByTokenForUpdate}
     * does for invite acceptance. Prevents two concurrent requests submitting the same
     * raw ticket from both passing the is_used check and both succeeding.
     *
     * <p>Must be called within an active {@code @Transactional} boundary.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PasswordResetTicket t WHERE t.ticketHash = :ticketHash")
    Optional<PasswordResetTicket> findByTicketHashForUpdate(@Param("ticketHash") String ticketHash);

    /**
     * Bulk-marks all unused reset tickets for the given user as consumed.
     *
     * <p>Called in two contexts:
     * <ol>
     *   <li><strong>Before issuing</strong> a new ticket — ensures a user never holds two
     *       live reset tickets simultaneously.</li>
     *   <li><strong>After a successful reset</strong> — defence-in-depth sweep to invalidate
     *       any other outstanding tickets for the same user.</li>
     * </ol>
     */
    @Modifying
    @Query("UPDATE PasswordResetTicket t SET t.isUsed = true WHERE t.userId = :userId AND t.isUsed = false")
    void markAllUsedByUserId(@Param("userId") UUID userId);

    /**
     * Bounded hard-delete of stale reset tickets whose TTL elapsed before {@code cutoff}.
     *
     * <p>Backs {@link com.beautica.auth.PasswordResetTokenCleanupJob}: without it the
     * {@code password_reset_tickets} side table grows unbounded (one row per successful OTP
     * verification, never reclaimed). A ticket expired beyond the retention window is dead
     * weight — it can no longer be redeemed (TTL + single-use are both enforced at confirm
     * time), so a physical {@code DELETE} is safe. Single statement, no per-row
     * materialisation; rides the {@code expires_at} scan.
     *
     * @param cutoff delete tickets with {@code expires_at < cutoff}
     * @return number of rows removed
     */
    @Modifying
    @Query("DELETE FROM PasswordResetTicket t WHERE t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
