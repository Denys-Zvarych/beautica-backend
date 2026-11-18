package com.beautica.user;

import com.beautica.common.repository.TransactionLockTimeout;
import com.beautica.auth.Role;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, TransactionLockTimeout {

    Optional<User> findByEmail(String email);

    /**
     * Column-only pre-read for {@code AuthService.resendVerification}: {@code true} when the account is
     * unverified AND active. Deliberately a scalar projection, NOT an entity load - an entity loaded here
     * would sit in the persistence context, and the later {@link #findByEmailForUpdate} in the same
     * transaction would return that stale copy (ignoring a concurrent writer's committed changes) instead of
     * the freshly locked row, defeating the resend cooldown.
     */
    @Query("SELECT (u.emailVerified = false AND u.isActive = true) FROM User u WHERE u.email = :email")
    Optional<Boolean> findResendVerificationEligibleByEmail(@Param("email") String email);

    /**
     * Column-only pre-read for {@code PasswordResetService.requestReset}: {@code true} when the account is
     * verified AND active. Scalar projection for the same stale-entity reason as
     * {@link #findResendVerificationEligibleByEmail}.
     */
    @Query("SELECT (u.emailVerified = true AND u.isActive = true) FROM User u WHERE u.email = :email")
    Optional<Boolean> findPasswordResetEligibleByEmail(@Param("email") String email);

    /**
     * B516 (AUTH-15) — column-only pre-read for change-password-from-settings: {@code true} when the code would go by
     * SMS (no confirmed email, a verified phone), so App Check runs BEFORE the row lock is taken. Scalar projection
     * for the same stale-entity reason as {@link #findPasswordResetEligibleByEmail}. Empty for a deleted account.
     */
    @Query("SELECT CASE WHEN (u.email IS NULL OR u.emailVerified = false) AND u.phoneVerifiedAt IS NOT NULL "
            + "THEN true ELSE false END FROM User u WHERE u.id = :userId")
    Optional<Boolean> findPasswordResetSmsBranchById(@Param("userId") UUID userId);

    boolean existsByEmail(String email);

    /**
     * B514 (AUTH-D09) — login by phone: the account whose phone is VERIFIED and equals {@code phoneE164}. An
     * unverified legacy {@code phone_number} never matches (same 401 as a wrong password). Served by the partial
     * unique index {@code ux_users_verified_phone} (V244), whose predicate is exactly this one, so at most one row.
     */
    @Query("SELECT u FROM User u WHERE u.phoneNumber = :phone AND u.phoneVerifiedAt IS NOT NULL")
    Optional<User> findByVerifiedPhone(@Param("phone") String phoneE164);

    /** B514 (AUTH-42) — a verified account already holds {@code phoneE164}. Same index as {@link #findByVerifiedPhone}. */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.phoneNumber = :phone AND u.phoneVerifiedAt IS NOT NULL")
    boolean existsByVerifiedPhone(@Param("phone") String phoneE164);

    /** B516 (AUTH-44) — another verified account holds {@code phoneE164}; same index as {@link #findByVerifiedPhone}. */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.phoneNumber = :phone AND u.phoneVerifiedAt IS NOT NULL "
            + "AND u.id <> :userId")
    boolean existsByVerifiedPhoneOnOtherUser(@Param("phone") String phoneE164, @Param("userId") UUID userId);

    /** B516 (AUTH-50) — another account already holds {@code email} (users.email is unique among non-null values). */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email AND u.id <> :userId")
    boolean existsByEmailOnOtherUser(@Param("email") String email, @Param("userId") UUID userId);

    /**
     * B516 (AUTH-45) — column-only read for the verified-phone gate: role and whether the phone is verified. Empty
     * for a deleted account. A projection, not an entity: the gate runs before the handler's own transaction.
     */
    @Query("SELECT new com.beautica.user.PhoneGateRow(u.role, "
            + "CASE WHEN u.phoneVerifiedAt IS NOT NULL THEN true ELSE false END) FROM User u "
            + "WHERE u.id = :userId")
    Optional<PhoneGateRow> findPhoneGateRow(@Param("userId") UUID userId);

    /**
     * B516 (AUTH-D19) — password hash + phone-verified flag WITHOUT a row lock, so the current-password BCrypt runs
     * before {@link #findByIdForUpdate} (never under the lock). A projection, not an entity, so the locked re-read
     * that follows returns fresh state (an entity loaded here would be returned stale by the locking query). Empty for
     * a deleted account.
     */
    @Query("SELECT new com.beautica.user.CurrentPasswordRow(u.passwordHash, "
            + "CASE WHEN u.phoneVerifiedAt IS NOT NULL THEN true ELSE false END) FROM User u WHERE u.id = :userId")
    Optional<CurrentPasswordRow> findCurrentPasswordRow(@Param("userId") UUID userId);

    /** B516 (AUTH-D18) — forgot-password by phone: the VERIFIED account, row-locked (cooldown TOCTOU, as by email). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.phoneNumber = :phone AND u.phoneVerifiedAt IS NOT NULL")
    Optional<User> findByVerifiedPhoneForUpdateNoLockTimeout(@Param("phone") String phoneE164);

    /** {@link #findByVerifiedPhoneForUpdateNoLockTimeout} bounded by the 3s {@code lock_timeout}. */
    default Optional<User> findByVerifiedPhoneForUpdate(String phoneE164) {
        applyTransactionLockTimeout();
        return findByVerifiedPhoneForUpdateNoLockTimeout(phoneE164);
    }

    /**
     * The user's assigned salon id, EMPTY for a missing or DEACTIVATED ({@code is_active = false})
     * user. The liveness predicate lives in this one statement (no extra round trip) because every
     * caller uses the result as an admin's management authority: a deactivated admin keeps login
     * until the JWT expires and must not keep complete / decline / rate / staff-management rights.
     */
    @Query("SELECT u.salonId FROM User u WHERE u.id = :userId AND u.isActive = true")
    Optional<UUID> findSalonIdById(@Param("userId") UUID userId);

    /**
     * Narrow projection backing {@code MasterService#canManageSalonStaff} (Phase 290 finding #5
     * role-gate fix) — reads only the {@code role} column, never the full {@link User} entity
     * (which carries {@code passwordHash}), mirroring {@link #findSalonIdById}'s pattern.
     *
     * <p>Needed because {@code users.salon_id} is populated for BOTH an invited
     * {@code SALON_ADMIN} and an invited {@code SALON_MASTER} ({@code User.createFromPhoneInvite} sets
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

    /** One (user, assigned salon) pair — the projection of {@link #findSalonAssignments}. */
    interface UserSalonPair {
        UUID getUserId();

        UUID getSalonId();
    }

    /**
     * Batch form of {@link #existsByIdAndSalonIdAndRole}: the salon assignment of EVERY user in
     * {@code userIds} that has {@code role} and a salon, in ONE statement (same predicate — id, role,
     * salon_id equality), so the push drain can answer "does this admin still belong to this salon" for
     * all admin recipients without a statement per pair.
     */
    @Query("SELECT u.id AS userId, u.salonId AS salonId FROM User u "
            + "WHERE u.id IN :userIds AND u.role = :role AND u.salonId IS NOT NULL")
    List<UserSalonPair> findSalonAssignments(@Param("userIds") Collection<UUID> userIds,
                                             @Param("role") Role role);

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
     * loading the full {@link User} entity on every cache-refresh read.
     *
     * <p><b>Row existence is part of the answer (phase 295 audit, HIGH-1).</b> This REPLACES the
     * earlier {@code Optional<Instant> findTokensValidAfterById}, which returned
     * {@code Optional.empty()} for BOTH "no such row" and "row exists with a null
     * {@code tokens_valid_after}". {@link com.beautica.auth.JwtAuthenticationFilter} read that
     * single empty as "no validity check applies" and went on to authenticate — so once phase 295
     * started HARD-DELETING staff accounts, a deleted user's already-issued access token kept
     * working for the remainder of its TTL. No non-row variant is kept alongside this one
     * (Anti-Bug §E-1): a caller reaching for the old shape would silently reopen that fail-open.
     *
     * <p>Same {@code users_pkey} probe and the same cost as before — the projection widened by one
     * column that the row already carries, nothing more.
     */
    @Query("""
            SELECT new com.beautica.user.TokensValidAfterRow(u.id, u.tokensValidAfter)
            FROM User u
            WHERE u.id = :userId
            """)
    Optional<TokensValidAfterRow> findTokensValidAfterRowById(@Param("userId") UUID userId);

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
    Optional<User> findByEmailForUpdateNoLockTimeout(@Param("email") String email);

    /** {@link #findByEmailForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout} ({@link TransactionLockTimeout}). */
    default Optional<User> findByEmailForUpdate(String email) {
        applyTransactionLockTimeout();
        return findByEmailForUpdateNoLockTimeout(email);
    }

    /**
     * Id-keyed counterpart of {@link #findByEmailForUpdate} — used by the authenticated
     * change-password-from-settings entry point ({@code PasswordResetService#requestResetForUserId}),
     * where the caller is identified by their JWT-derived {@code userId}, not an email from the
     * request body. Closes the same TOCTOU window between the resend-cooldown read and the OTP
     * write as the email-keyed variant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdateNoLockTimeout(@Param("userId") UUID userId);

    /** {@link #findByIdForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout} ({@link TransactionLockTimeout}). */
    default Optional<User> findByIdForUpdate(UUID userId) {
        applyTransactionLockTimeout();
        return findByIdForUpdateNoLockTimeout(userId);
    }

    /**
     * Row-locks the given users ({@code FOR UPDATE}, ordered by id so two concurrent batches always lock in
     * the same order and cannot deadlock) and returns only their avatar pointers — staff disposal's pre-read
     * (security S-L2 / perf P-L2). Holding the lock until the {@code users} delete commits means a concurrent
     * avatar replace ({@code MediaService} locks the same row via {@link #findByIdForUpdate}) either committed
     * BEFORE this read (its new key is what is read here and purged) or blocks until the delete commits and
     * then finds no row (its failure path discards the new blob). No managed entity is loaded.
     *
     * <p>The native query declares its query space ({@code users}) via {@link HibernateHints#HINT_NATIVE_SPACES}
     * (perf P-L3): without it Hibernate cannot tell which tables a native statement touches and auto-flushes
     * (dirty-checking) the WHOLE persistence context first. With it, only pending {@code users} changes flush.
     *
     * <p>Fused transaction-local 3s {@code lock_timeout} ({@code TransactionLockTimeout}); {@code OF u} keeps the
     * row lock off the one-row config subquery.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_NATIVE_SPACES, value = "users"))
    @Query(value = """
            SELECT u.id AS "id", u.avatar_r2_key AS "avatarR2Key", u.avatar_url AS "avatarUrl"
            FROM users u
            CROSS JOIN (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
            WHERE u.id IN (:ids)
            ORDER BY u.id
            FOR UPDATE OF u
            """, nativeQuery = true)
    List<UserAvatarPointers> lockAvatarPointersByIdIn(@Param("ids") Collection<UUID> ids);

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
