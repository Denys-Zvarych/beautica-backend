package com.beautica.user;

import com.beautica.auth.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT u.salonId FROM User u WHERE u.id = :userId")
    Optional<UUID> findSalonIdById(@Param("userId") UUID userId);

    /**
     * Backs {@link com.beautica.common.security.AuthorizationService#adminBelongsToSalon} —
     * mirrors {@code MasterRepository.existsByIdAndSalonId}, scoped additionally by role so a
     * caller cannot use this predicate to probe non-admin users assigned to a salon.
     */
    boolean existsByIdAndSalonIdAndRole(UUID id, UUID salonId, Role role);

    /**
     * Scalar projection backing {@link com.beautica.auth.TokensValidAfterCache} — avoids
     * loading the full {@link User} entity on every cache-refresh read. Returns
     * {@code Optional.empty()} both when the user does not exist and when
     * {@code tokensValidAfter} is {@code null} (the common "never reset" case); callers
     * only need to distinguish "no reset since this instant" from "reset happened at
     * this instant", so the two empty cases are equivalent for this read path.
     */
    @Query("SELECT u.tokensValidAfter FROM User u WHERE u.id = :userId")
    Optional<Instant> findTokensValidAfterById(@Param("userId") UUID userId);

    /**
     * Acquires a PostgreSQL row-level exclusive lock on the user row before the
     * resend-throttle check runs. This serializes concurrent resend requests for
     * the same email so the TOCTOU window between the cooldown read and the OTP
     * write is eliminated.
     *
     * <p>The lock is released when the enclosing transaction commits or rolls back.
     * Early-exit paths (unknown email, already-verified) release the lock immediately
     * without any write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    /**
     * Id-keyed counterpart of {@link #findByEmailForUpdate} — used by the authenticated
     * change-password-from-settings entry point ({@code PasswordResetService#requestResetForUserId}),
     * where the caller is identified by their JWT-derived {@code userId}, not an email from the
     * request body. Closes the same TOCTOU window between the resend-cooldown read and the OTP
     * write as the email-keyed variant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") UUID userId);

    /**
     * Single bounded statement that nulls the verification code material on
     * abandoned, unverified registrations whose OTP expired before
     * {@code cutoff}. Keeps stale {@code verification_code_hash} /
     * {@code verification_code_expires_at} from lingering forever.
     *
     * <p>Invoked only by the low-frequency {@code StaleVerificationCleanupJob};
     * the cutoff is computed by the service from the injected {@link java.time.Clock}.
     *
     * @return the number of rows updated (for observability logging)
     */
    @Modifying
    @Query("""
            UPDATE User u
               SET u.verificationCodeHash = NULL,
                   u.verificationCodeExpiresAt = NULL
             WHERE u.emailVerified = false
               AND u.verificationCodeExpiresAt IS NOT NULL
               AND u.verificationCodeExpiresAt < :cutoff
            """)
    int nullifyStaleVerificationCodes(@Param("cutoff") Instant cutoff);
}
