package com.beautica.review.invite.repository;

import com.beautica.review.invite.entity.SmsOptOut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Phase 399 D4 — data access for {@link SmsOptOut}, keyed by the {@code PhoneHasher} digest. Callers pass
 * {@code PhoneHasher.HashedPhone#hash()}, never a raw phone. The idempotent opt-out insert is phase 401's (D6).
 */
public interface SmsOptOutRepository extends JpaRepository<SmsOptOut, byte[]> {

    /** Phase 400 D2 eligibility gate: has this phone opted out? */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM sms_opt_outs WHERE phone_hash = :phoneHash)", nativeQuery = true)
    boolean existsByPhoneHash(@Param("phoneHash") byte[] phoneHash);

    /**
     * Phase 400 audit cycle 1 — the D2 opt-out gate for a whole send batch in ONE statement (primary-key lookups):
     * which of {@code phoneHashes} have opted out. The caller bounds the collection (one claim batch, at most
     * {@code batch-size}) and never passes it empty.
     */
    @Query(value = "SELECT phone_hash FROM sms_opt_outs WHERE phone_hash IN (:phoneHashes)", nativeQuery = true)
    List<byte[]> findExistingHashes(@Param("phoneHashes") Collection<byte[]> phoneHashes);

    /**
     * Phase 401 D6 — the idempotent opt-out: inserts the phone hash, or does nothing when it is already opted out (the
     * original row and its {@code created_at} / {@code source} are kept). {@code phoneHash} is copied from a
     * {@code review_invites.phone_hash}, which is the same {@code PhoneHasher} key. Returns 0 or 1.
     */
    @Modifying
    @Query(value = """
            INSERT INTO sms_opt_outs (phone_hash, created_at, source)
            VALUES (:phoneHash, :createdAt, :source)
            ON CONFLICT (phone_hash) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("phoneHash") byte[] phoneHash,
                       @Param("createdAt") Instant createdAt,
                       @Param("source") String source);
}
