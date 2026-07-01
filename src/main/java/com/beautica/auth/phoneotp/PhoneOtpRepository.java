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
     * at most one row even though {@code sendOtp} expires prior unused codes before
     * issuing a new one — defence-in-depth against a duplicate slipping through a race.
     */
    Optional<PhoneOtp> findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            String phone, Instant now);

    /**
     * Rate-limit counter: how many OTPs this phone has requested since {@code since}
     * (includes used rows). Index-served by {@code idx_phone_otps_phone_created_at}
     * {@code (phone, created_at)} (V88) — a plain composite, not the partial
     * {@code WHERE NOT used} active-lookup index, since the count must see used rows too.
     */
    int countByPhoneAndCreatedAtAfter(String phone, Instant since);

    /**
     * Expires every still-active OTP for a phone in one bounded UPDATE, so a newly
     * issued code is the only redeemable one. Bulk modifying query — no per-row load.
     */
    @Modifying
    @Query("UPDATE PhoneOtp o SET o.used = true WHERE o.phone = :phone AND o.used = false")
    void markAllUnusedAsUsed(@Param("phone") String phone);

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
