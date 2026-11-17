package com.beautica.auth.registration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicInsert;

import java.time.Instant;
import java.util.UUID;

/**
 * B514 (D2, AUTH-41) — single-use proof that the caller owns {@link #phone}: minted by
 * {@code POST /auth/register/phone/otp/verify} after the SMS code matched, consumed once by register /
 * register-independent-master / invite accept. The reset-ticket pattern of AUTH-13 keyed by phone: the account is
 * created only when the ticket is consumed, so an abandoned flow leaves no user row.
 *
 * <p>Only the SHA-256 hex of the raw ticket is stored; the raw value is returned to the client once and never
 * persisted or logged. No FK and no external storage: rows are hard-deleted by {@link RegistrationTicketCleanupJob}.
 * {@code created_at} comes from the DB default, so {@link DynamicInsert} omits it.
 */
@Entity
@DynamicInsert
@Table(name = "phone_registration_tickets",
        indexes = @Index(name = "idx_phone_registration_tickets_expires_at", columnList = "expires_at"))
public class RegistrationTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "ticket_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String ticketHash;

    @Column(name = "phone", nullable = false, length = 20, updatable = false)
    private String phone;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected RegistrationTicket() {
    }

    private RegistrationTicket(String ticketHash, String phone, Instant expiresAt) {
        this.ticketHash = ticketHash;
        this.phone = phone;
        this.expiresAt = expiresAt;
        this.used = false;
    }

    /**
     * @param ticketHash SHA-256 hex of the raw ticket (never the raw value)
     * @param phoneE164  the phone the SMS code proved, {@code +380…}
     * @param expiresAt  absolute expiry
     */
    public static RegistrationTicket issue(String ticketHash, String phoneE164, Instant expiresAt) {
        return new RegistrationTicket(ticketHash, phoneE164, expiresAt);
    }

    /** {@code true} when the ticket can still be consumed at {@code now}. */
    public boolean isRedeemableAt(Instant now) {
        return !used && expiresAt.isAfter(now);
    }

    public void markUsed() {
        this.used = true;
    }

    public UUID getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return used;
    }
}
