package com.beautica.booking.repository;

import com.beautica.booking.entity.GuestRescheduleSmsSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** Phase 431 — see {@link GuestRescheduleSmsSend}. */
public interface GuestRescheduleSmsSendRepository extends JpaRepository<GuestRescheduleSmsSend, UUID> {

    /** A back-pressure retry of the same outbox row already holds a reservation (unique partial index). */
    boolean existsByOutboxEntryId(UUID outboxEntryId);

    /** Retention: rows past every window are useless. */
    @Modifying
    @Query("DELETE FROM GuestRescheduleSmsSend s WHERE s.sentAt < :cutoff")
    int deleteSentBefore(@Param("cutoff") Instant cutoff);
}
