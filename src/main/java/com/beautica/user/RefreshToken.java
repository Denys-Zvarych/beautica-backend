package com.beautica.user;

import com.beautica.common.AuditableEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
// Mirrors the DB indexes created in V16__Add_missing_indexes.sql and
// V146__refresh_tokens_family_id.sql so the mapping documents them and ddl-auto=create
// would reproduce them. The physical indexes are owned by Flyway; this is documentation
// only (Hibernate ddl-auto=validate does not verify indexes).
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_family_id", columnList = "family_id")
})
public class RefreshToken extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked = false;

    // The chain of tokens produced by successive rotations from a single login/register/
    // invite-accept event. A rotated-in replacement inherits its predecessor's familyId
    // (see AuthResponseBuilder#buildAuthResponse(User, UUID)); presenting an
    // already-revoked token is reuse/theft evidence, and AuthService#refresh revokes every
    // row sharing this familyId in response. Never updated after insert.
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    protected RefreshToken() {
    }

    private RefreshToken(String token, UUID userId, Instant expiresAt, UUID familyId) {
        this.token = token;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.familyId = familyId;
        this.isRevoked = false;
    }

    /**
     * Mints the first token of a brand-new family — used wherever a session starts fresh
     * (login, registration/verification, invite acceptance).
     */
    public static RefreshToken startNewFamily(String token, UUID userId, Instant expiresAt) {
        return new RefreshToken(token, userId, expiresAt, UUID.randomUUID());
    }

    /**
     * Mints a rotated replacement that stays within an existing family — used by refresh-token
     * rotation so the chain remains traceable for reuse detection.
     */
    public static RefreshToken rotate(String token, UUID userId, Instant expiresAt, UUID familyId) {
        return new RefreshToken(token, userId, expiresAt, familyId);
    }

    public UUID getId() {
        return id;
    }

    @JsonIgnore
    public String getToken() {
        return token;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return isRevoked;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public void revoke() {
        this.isRevoked = true;
    }
}
