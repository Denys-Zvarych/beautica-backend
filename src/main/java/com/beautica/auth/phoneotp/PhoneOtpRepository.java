package com.beautica.auth.phoneotp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PhoneOtpRepository extends JpaRepository<PhoneOtp, Long> {

    /**
     * The single active (unused, unexpired) OTP for a phone. {@code findTop} guarantees
     * at most one row even though {@code sendOtp} expires prior unused codes after
     * delivering a new one (the newest unused row always wins in between) — defence-in-depth against a duplicate slipping through a race.
     */
    Optional<PhoneOtp> findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
            String phone, Instant now);

    /**
     * Rate-limit counter: how many OTPs this phone has requested since {@code since}
     * (includes used rows). Index-served by {@code idx_phone_otps_phone_created_at}
     * {@code (phone, created_at)} (V88) — a plain composite, not the partial
     * {@code WHERE NOT used} active-lookup index, since the count must see used rows too.
     */
    int countByPhoneAndCreatedAtAfter(String phone, Instant since);

    /**
     * Serialises concurrent sends for one phone: takes a transaction-scoped Postgres advisory lock
     * keyed on {@code hashtext(phone)}, released automatically at commit/rollback. Must be called
     * first inside the issuing transaction so the rate-limit count-then-insert cannot interleave
     * between two requests for the same phone. (Postgres-only; the project runs Postgres in every
     * profile, including Testcontainers.) The scalar select wrapper exists because
     * {@code pg_advisory_xact_lock} returns {@code void}, which Hibernate cannot map.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:phone))) AS l", nativeQuery = true)
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
    @Query("UPDATE PhoneOtp o SET o.used = true WHERE o.phone = :phone AND o.used = false AND o.id < :keepId")
    int markOlderAsUsed(@Param("phone") String phone, @Param("keepId") Long keepId);

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
     * Atomically records one wrong verify attempt against a single OTP row and, in the
     * same statement, invalidates it ({@code used = true}) once the incremented count
     * reaches {@code cap}. A single conditional UPDATE — never a read-modify-write — so N
     * concurrent wrong-code verifies of the same row cannot each read {@code attempts < cap}
     * and overshoot the per-OTP brute-force cap (the {@code PhoneOtp} entity has no
     * {@code @Version}; the DB serialises the increments instead). The
     * {@code AND o.used = false} guard caps {@code attempts} at exactly {@code cap}: once
     * one increment crosses the cap and commits {@code used = true}, every subsequent
     * row-locked UPDATE re-evaluates the predicate against that committed version, matches
     * zero rows, and does not increment further.
     *
     * <p>This is a bulk modifying query: it bypasses the persistence context.
     * {@code flushAutomatically} forces any pending SELECT-loaded state out first and
     * {@code clearAutomatically} evicts the now-stale loaded {@code PhoneOtp} so a later
     * read in the same transaction cannot resurrect its pre-increment {@code attempts}/
     * {@code used} (project convention — see {@code ReviewRepository}). The verify path
     * returns immediately after calling this, so it never re-reads the instance anyway.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PhoneOtp o SET o.attempts = o.attempts + 1, "
            + "o.used = CASE WHEN o.attempts + 1 >= :cap THEN true ELSE o.used END "
            + "WHERE o.id = :id AND o.used = false")
    int recordFailedAttempt(@Param("id") Long id, @Param("cap") int cap);

    /** Cleanup-job sweep: hard-deletes rows that expired before {@code cutoff}. */
    @Modifying
    @Query("DELETE FROM PhoneOtp o WHERE o.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
