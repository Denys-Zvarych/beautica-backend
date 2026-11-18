package com.beautica.auth.registration;

import com.beautica.common.repository.TransactionLockTimeout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** B514 — data access for {@link RegistrationTicket}; mirrors {@code PasswordResetTicketRepository}. */
public interface RegistrationTicketRepository
        extends JpaRepository<RegistrationTicket, UUID>, TransactionLockTimeout {

    /**
     * {@code SELECT … FOR UPDATE} on the ticket row, so two concurrent registrations with the same ticket cannot both
     * pass the used-check (the second waits, then sees {@code used = true}). Call inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RegistrationTicket t WHERE t.ticketHash = :ticketHash")
    Optional<RegistrationTicket> findByTicketHashForUpdateNoLockTimeout(@Param("ticketHash") String ticketHash);

    /** {@link #findByTicketHashForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout}. */
    default Optional<RegistrationTicket> findByTicketHashForUpdate(String ticketHash) {
        applyTransactionLockTimeout();
        return findByTicketHashForUpdateNoLockTimeout(ticketHash);
    }

    /** Cleanup sweep: hard-deletes tickets that expired before {@code cutoff}; rides the expires_at index. */
    @Modifying
    @Query("DELETE FROM RegistrationTicket t WHERE t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
