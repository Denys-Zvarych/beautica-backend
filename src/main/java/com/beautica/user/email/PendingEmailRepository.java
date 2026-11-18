package com.beautica.user.email;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** B516 — {@code user_pending_emails}, keyed by user id (one pending address per account). */
public interface PendingEmailRepository extends JpaRepository<PendingEmail, UUID> {

    /**
     * Abandoned requests: code expired before {@code cutoff} and no lockout still running. Drops the unconfirmed
     * address (PII) instead of keeping it forever. At most one row per account, so the daily scan stays small without
     * an index on {@code expires_at}.
     */
    @Modifying
    @Query("DELETE FROM PendingEmail p WHERE p.expiresAt < :cutoff "
            + "AND (p.lockedUntil IS NULL OR p.lockedUntil < :cutoff)")
    int deleteAbandonedBefore(@Param("cutoff") Instant cutoff);
}
