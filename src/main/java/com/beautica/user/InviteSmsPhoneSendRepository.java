package com.beautica.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** B520 audit-fix cycle 2 — see {@link InviteSmsPhoneSend}. */
public interface InviteSmsPhoneSendRepository extends JpaRepository<InviteSmsPhoneSend, UUID> {

    /** Invite SMS enqueued to the hashed phone since {@code since}; served by idx_invite_sms_phone_sends_phone_sent. */
    @Query("SELECT COUNT(s) FROM InviteSmsPhoneSend s WHERE s.phoneHash = :phoneHash AND s.sentAt > :since")
    long countByPhoneHashSince(@Param("phoneHash") byte[] phoneHash, @Param("since") Instant since);

    /** Retention: rows past the 24 h cap window are useless. */
    @Modifying
    @Query("DELETE FROM InviteSmsPhoneSend s WHERE s.sentAt < :cutoff")
    int deleteSentBefore(@Param("cutoff") Instant cutoff);
}
