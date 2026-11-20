package com.beautica.auth.phoneotp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PhoneOtpRepository extends JpaRepository<PhoneOtp, Long> {

    /**
     * The single active (unused, unexpired) OTP for a phone. {@code findTop} guarantees
     * at most one row even though {@code sendOtp} expires prior unused codes after
     * delivering a new one (the newest unused row always wins in between) — defence-in-depth against a duplicate slipping through a race.
     */
    Optional<PhoneOtp> findTopByPhoneAndPurposeAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
            String phone, PhoneOtpPurpose purpose, Instant now);

    /**
     * B516 (AUTH-44) — the newest live code of a USER-BOUND purpose issued to {@code userId}: another account's code
     * for the same phone is never returned.
     */
    Optional<PhoneOtp> findTopByPhoneAndPurposeAndUserIdAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
            String phone, PhoneOtpPurpose purpose, UUID userId, Instant now);

    /** B516 — per-account send cap (AUTH-44); served by {@code idx_phone_otps_user_id}. */
    int countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /**
     * B516 — serialises concurrent signed-in sends of ONE account (the per-account cap counts across all its phones,
     * so the per-phone lock alone lets an account spread parallel sends over several numbers past the cap). Same
     * transaction-scoped advisory lock + fused 3 s {@code lock_timeout} shape as {@link #lockPhoneForSend}; the key is
     * namespaced ({@code 'phone-otp-user:'} prefix) so it never shares a hash bucket by construction with a phone key.
     * Taken BEFORE the phone lock on every user-bound send (one global order: user, then phone — no deadlock).
     */
    @Query(value = "SELECT 1 FROM (SELECT set_config('lock_timeout', '3s', true), "
            + "pg_advisory_xact_lock(hashtext('phone-otp-user:' || CAST(:userId AS text)))) AS l", nativeQuery = true)
    int lockUserForSend(@Param("userId") UUID userId);

    /**
     * Serialises concurrent sends for one phone: takes a transaction-scoped Postgres advisory lock
     * keyed on {@code hashtext(phone)}, released automatically at commit/rollback. Must be called
     * first inside the issuing transaction so the rate-limit count-then-insert cannot interleave
     * between two requests for the same phone. (Postgres-only; the project runs Postgres in every
     * profile, including Testcontainers.) The scalar select wrapper exists because
     * {@code pg_advisory_xact_lock} returns {@code void}, which Hibernate cannot map. The fused
     * transaction-local 3s {@code lock_timeout} (evaluated first, left to right — the
     * {@code BookingRepository#acquireClientAdvisoryLockWithTimeout} shape) turns a long wait into
     * {@code 55P03} → 409 instead of a {@code statement_timeout} abort.
     */
    @Query(value = "SELECT 1 FROM (SELECT set_config('lock_timeout', '3s', true), "
            + "pg_advisory_xact_lock(hashtext(:phone))) AS l", nativeQuery = true)
    int lockPhoneForSend(@Param("phone") String phone);

    /**
     * Invalidates every still-active OTP for a phone OLDER than {@code keepId} (strictly lower id) in
     * one bounded UPDATE, run only after the new code was actually delivered. Bulk modifying query —
     * no per-row load.
     *
     * <p>{@code id} is a sequence and inserts are serialised per phone by the advisory lock, so a
     * higher id always means a newer row. Using {@code <} (not {@code <>}) means a send can only ever
     * invalidate older rows: when two sends overlap (the lock covers only the insert transaction),
     * the later send's code survives even if the earlier send's invalidation runs last — consistent
     * with {@code verifyOtp}, where the newest unused row wins.
     */
    @Modifying
    @Query("UPDATE PhoneOtp o SET o.used = true "
            + "WHERE o.phone = :phone AND o.purpose = :purpose AND o.used = false AND o.id < :keepId")
    int markOlderAsUsed(@Param("phone") String phone, @Param("purpose") PhoneOtpPurpose purpose,
                        @Param("keepId") Long keepId);

    /**
     * Single-statement UPDATE by id marking an undelivered code as used: it can never be redeemed,
     * yet the row stays so it keeps counting toward the per-phone window cap (a provider timeout may
     * have delivered the SMS anyway). The older delivered code stays valid because {@code verifyOtp}
     * picks the newest UNUSED row.
     */
    @Modifying
    @Query("UPDATE PhoneOtp o SET o.used = true WHERE o.id = :id")
    int markAsUsedById(@Param("id") Long id);

    /**
     * B1 step 4 (match) — the single-use claim, in the CALLER's transaction so it commits or rolls back with the
     * caller's own write (ticket, phone swap). Exactly one of N concurrent correct verifies gets 1 row: the others
     * block on the row lock and then see {@code used = true} (0 rows), or win only if the first one rolled back.
     * No {@code clearAutomatically}: the caller's managed entities (the locked user row) must stay attached.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PhoneOtp o SET o.used = true WHERE o.id = :id AND o.used = false")
    int markUsedIfUnused(@Param("id") Long id);

    /**
     * Phone-first hardening audit-fix (sql review) — every per-phone send limit in ONE round trip, read under the
     * phone lock: the codes of the last window (any purpose, used ones too), the newest code of {@code purpose} inside
     * the cooldown (epoch millis, {@code null} when none), and the wrong codes typed for the phone from
     * {@code clientNet} since {@code failuresSince} (AUTH-D26 c, per phone + client network). The outer scan is the
     * {@code (phone, created_at)} range on {@code idx_phone_otps_phone_created_at} from {@code rangeStart} (the earlier
     * of the window and cooldown starts); the failure count is an index-only range on
     * {@code ix_phone_otp_failures_phone_net_at}. Aggregates without GROUP BY: always exactly one row.
     */
    @Query(value = """
            SELECT count(*) FILTER (WHERE o.created_at > :windowStart) AS recentSends,
                   CAST(EXTRACT(EPOCH FROM max(o.created_at)
                           FILTER (WHERE o.purpose = :purpose AND o.created_at > :cooldownStart)) * 1000 AS bigint)
                       AS latestSamePurposeMillis,
                   (SELECT count(*) FROM phone_otp_failures f
                     WHERE f.phone = :phone AND f.client_net = :clientNet AND f.failed_at > :failuresSince)
                       AS recentFailures
              FROM phone_otps o
             WHERE o.phone = :phone AND o.created_at > :rangeStart
            """, nativeQuery = true)
    SendLimitStats sendLimitStats(@Param("phone") String phone,
                                  @Param("purpose") String purpose,
                                  @Param("clientNet") String clientNet,
                                  @Param("rangeStart") Instant rangeStart,
                                  @Param("windowStart") Instant windowStart,
                                  @Param("cooldownStart") Instant cooldownStart,
                                  @Param("failuresSince") Instant failuresSince);

    /** The single row of {@link #sendLimitStats}. */
    interface SendLimitStats {
        long getRecentSends();

        Long getLatestSamePurposeMillis();

        long getRecentFailures();
    }

    /**
     * AUTH-D26 (c) — wrong codes typed for {@code phone} (every purpose) from {@code clientNet} since {@code since};
     * index-only on {@code ix_phone_otp_failures_phone_net_at}.
     */
    @Query(value = """
            SELECT count(*) FROM phone_otp_failures
             WHERE phone = :phone AND client_net = :clientNet AND failed_at > :since
            """, nativeQuery = true)
    long countFailuresSince(@Param("phone") String phone, @Param("clientNet") String clientNet,
                            @Param("since") Instant since);

    /** Cleanup-job sweep of {@code phone_otp_failures} (25 h, like the codes); served by {@code ix_phone_otp_failures_at}. */
    @Modifying
    @Query(value = "DELETE FROM phone_otp_failures WHERE failed_at < :cutoff", nativeQuery = true)
    int deleteFailuresBefore(@Param("cutoff") Instant cutoff);

    /** Guest-PII erase twin of {@link #deleteByPhone} for the failure log (it stores the phone). */
    @Modifying
    @Query(value = "DELETE FROM phone_otp_failures WHERE phone = :phone", nativeQuery = true)
    int deleteFailuresByPhone(@Param("phone") String phone);

    /**
     * Cleanup-job sweep: hard-deletes rows CREATED before {@code cutoff} (25 h back, so the 24 h failed-verify window
     * of the send limits is never cut short by the 03:00 run).
     */
    @Modifying
    @Query("DELETE FROM PhoneOtp o WHERE o.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * Phase 373 D9 — hard-deletes every OTP row of one E.164 phone (internal guest-PII erase).
     */
    @Modifying
    @Query("DELETE FROM PhoneOtp o WHERE o.phone = :phone")
    int deleteByPhone(@Param("phone") String phone);
}
