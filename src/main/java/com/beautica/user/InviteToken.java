package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.AuditableEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Invite token for SALON_MASTER / SALON_ADMIN onboarding.
 *
 * <p><strong>DB-side indexes (Flyway, not mirrored as JPA {@code @Index}):</strong>
 * <ul>
 *   <li>{@code idx_invite_tokens_email_used (email, is_used)} — V16. Serves the dispatch
 *       idempotency lookup {@code findByEmailAndSalonIdAndIsUsedFalse} (predicate
 *       {@code email = ? AND salon_id = ? AND is_used = false}).</li>
 *   <li>{@code ux_invite_tokens_active} — partial UNIQUE on
 *       {@code (lower(email), salon_id) WHERE is_used = false} (V101). The INSERT-time
 *       uniqueness/concurrency backstop: enforces at most one active (unused) invite per
 *       {@code (salon, lower(email))} and rejects concurrent same-salon double-submits. It does
 *       NOT serve the lookup above — Postgres cannot use a {@code lower(email)} expression index
 *       for a bare {@code email = ?} predicate. It is an expression-based partial index, which
 *       cannot be expressed via {@code @Index columnList}, and Hibernate
 *       {@code ddl-auto=validate} does not verify indexes — hence documented here instead of
 *       annotated.</li>
 * </ul>
 */
@Entity
@Table(name = "invite_tokens")
public class InviteToken extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "salon_id")
    private UUID salonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private Role role;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_used", nullable = false)
    private boolean isUsed = false;

    protected InviteToken() {
    }

    public InviteToken(String token, String email, UUID salonId, Role role, Instant expiresAt) {
        this.token = token;
        this.email = email;
        this.salonId = salonId;
        this.role = role;
        this.expiresAt = expiresAt;
        this.isUsed = false;
    }

    public UUID getId() {
        return id;
    }

    @JsonIgnore
    public String getToken() {
        return token;
    }

    public String getEmail() {
        return email;
    }

    public UUID getSalonId() {
        return salonId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return isUsed;
    }

    public void markUsed() {
        this.isUsed = true;
    }
}
