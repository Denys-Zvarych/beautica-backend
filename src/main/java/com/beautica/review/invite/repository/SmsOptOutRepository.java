package com.beautica.review.invite.repository;

import com.beautica.review.invite.entity.SmsOptOut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Phase 399 D4 — data access for {@link SmsOptOut}, keyed by the {@code PhoneHasher} digest. Callers pass
 * {@code PhoneHasher.HashedPhone#hash()}, never a raw phone. The idempotent opt-out insert is phase 401's (D6).
 */
public interface SmsOptOutRepository extends JpaRepository<SmsOptOut, byte[]> {

    /** Phase 400 D2 eligibility gate: has this phone opted out? */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM sms_opt_outs WHERE phone_hash = :phoneHash)", nativeQuery = true)
    boolean existsByPhoneHash(@Param("phoneHash") byte[] phoneHash);
}
