package com.beautica.user;

import com.beautica.auth.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT u.salonId FROM User u WHERE u.id = :userId")
    Optional<UUID> findSalonIdById(@Param("userId") UUID userId);

    /**
     * Narrow projection backing {@code MasterService#canManageSalonStaff} (Phase 290 finding #5
     * role-gate fix) — reads only the {@code role} column, never the full {@link User} entity
     * (which carries {@code passwordHash}), mirroring {@link #findSalonIdById}'s pattern.
     *
     * <p>Needed because {@code users.salon_id} is populated for BOTH an invited
     * {@code SALON_ADMIN} and an invited {@code SALON_MASTER} ({@code User.createFromInvite} sets
     * it from the invite token regardless of role) — a salon-id match alone cannot distinguish a
     * staff manager from a read-only master, so the caller must resolve the role first.
     */
    @Query("SELECT u.role FROM User u WHERE u.id = :userId")
    Optional<Role> findRoleById(@Param("userId") UUID userId);

    // findCreatedAtById was removed by the 2026-08 perf audit (F2): its only caller,
    // ClientPassportService, now reads the registration instant AND the authored-review count
    // in a single statement via ClientAggregationRepository.findStanding. Do not reinstate a
    // standalone created_at lookup for the passport — that reintroduces the serial round trip.

    /**
     * Narrow projection backing the scalar half of {@code GET /users/me/rating} (Phase 27.6) —
     * reads only the two rating aggregate columns, never the full {@link User} entity (which
     * carries {@code passwordHash} and other PII this endpoint must never touch, even in-memory).
     *
     * <p>The endpoint's per-star {@code ratingDistribution} (Phase 27.x) is fetched separately via
     * {@code ClientReviewRepository#countBySubjectClientIdGroupByRating} and zero-filled by
     * {@code UserService.getMyRating} — this query is not widened to cover it.
     */
    @Query("SELECT u.avgRating AS avgRating, u.reviewCount AS reviewCount FROM User u WHERE u.id = :userId")
    Optional<UserRatingProjection> findRatingById(@Param("userId") UUID userId);

    /**
     * Backs {@link com.beautica.common.security.AuthorizationService#adminBelongsToSalon} —
     * mirrors {@code MasterRepository.existsByIdAndSalonId}, scoped additionally by role so a
     * caller cannot use this predicate to probe non-admin users assigned to a salon.
     */
    boolean existsByIdAndSalonIdAndRole(UUID id, UUID salonId, Role role);

    /**
     * Backs {@link com.beautica.salon.service.SalonService#getSalonStaff} — the
     * {@code SALON_ADMIN} half of the management-scoped staff roster (Phase 21.5). Masters are
     * sourced separately via {@code MasterRepository.findBySalonIdAndIsActiveTrueWithUser}
     * (there is no {@code Master} row for an admin), so this finder is scoped by role so a
     * de-activated or CLIENT-role user sharing the same {@code salon_id} column value (which
     * cannot actually occur for CLIENT, but mirrors the role-scoping discipline of
     * {@link #existsByIdAndSalonIdAndRole}) never leaks into the roster.
     *
     * <p><b>Renamed from {@code findBySalonIdAndRole} (Phase 290).</b> That method's own javadoc
     * flagged this exact gap in advance: "nothing in this codebase ever sets
     * {@code User.isActive = false} today ... if a future user-suspension feature starts setting
     * it, this query must gain the same filter or deactivated admins will keep appearing in salon
     * rosters." Phase 290's salon-deletion staff cascade is that feature — it sets
     * {@code SALON_ADMIN.isActive = false} on a deleted salon's admin accounts, and
     * {@code getSalonStaff} remains reachable afterwards (the OWNER's own management access is not
     * gated on {@code salon.isActive}). Without this filter a deactivated admin of a deleted salon
     * would keep appearing in that salon's own staff roster forever.
     */
    List<User> findBySalonIdAndRoleAndIsActiveTrue(UUID salonId, Role role);

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
