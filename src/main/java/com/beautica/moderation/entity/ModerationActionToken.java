package com.beautica.moderation.entity;

import com.beautica.moderation.dto.ModerationAction;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 414 — one e-mailed moderation button: a single-use capability to apply {@link #getAction()} to one reported
 * target (V218). Only the lower-hex SHA-256 of the raw token is stored; the raw token exists only in the support
 * e-mail. A row is LIVE while {@code usedAt}, {@code revokedAt} are null and {@code expiresAt} is in the future.
 * Rows are written by insert and by the repository's conditional updates only (claim, nonce, revoke), never by
 * dirty checking, so the entity exposes no mutators.
 *
 * <p>{@link Persistable}: the id is assigned in {@link #mint} and there is no {@code @Version}, so without an explicit
 * "new" flag Spring Data would {@code merge} — one extra SELECT per insert. The transient flag is true only on a freshly
 * minted instance and is cleared on {@code @PostPersist} / {@code @PostLoad}.
 */
@Entity
@Table(
        name = "moderation_action_tokens",
        indexes = {
                @Index(name = "ux_mat_token_hash", columnList = "token_hash", unique = true),
                // ix_mat_live_target is partial (WHERE revoked_at IS NULL, matching revokeAllForTarget) — column
                // list only.
                @Index(name = "ix_mat_live_target", columnList = "target_type, target_id"),
                @Index(name = "ix_mat_expires_at", columnList = "expires_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModerationActionToken implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32, updatable = false)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16, updatable = false)
    private ModerationAction action;

    @JsonIgnore
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @JsonIgnore
    @Column(name = "confirm_nonce_hash", length = 64, insertable = false, updatable = false)
    private String confirmNonceHash;

    @Column(name = "confirm_nonce_expires_at", insertable = false, updatable = false)
    private Instant confirmNonceExpiresAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at", insertable = false, updatable = false)
    private Instant usedAt;

    @Column(name = "revoked_at", insertable = false, updatable = false)
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean newEntity;

    private ModerationActionToken(ReportTargetType targetType, UUID targetId, ModerationAction action,
                                  String tokenHash, Instant expiresAt, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.action = Objects.requireNonNull(action, "action");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.newEntity = true;
    }

    /** A new live row for one button; {@code tokenHash} is the stored form of the raw token, never the token. */
    public static ModerationActionToken mint(ReportTargetType targetType, UUID targetId, ModerationAction action,
                                             String tokenHash, Instant expiresAt, Instant now) {
        return new ModerationActionToken(targetType, targetId, action, tokenHash, expiresAt, now);
    }

    /** {@code true} only for a freshly minted, not yet persisted row: {@code save} then persists without a SELECT. */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }

    /** Neither used nor revoked, and not yet expired at {@code now}. */
    public boolean isLiveAt(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
