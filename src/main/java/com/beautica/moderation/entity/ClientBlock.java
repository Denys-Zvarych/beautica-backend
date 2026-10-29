package com.beautica.moderation.entity;

import com.beautica.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 397 — a provider's block of one client: a registered account ({@code blockedUserId}, CLIENT row) OR a LINK
 * guest's OTP-verified phone ({@code phoneHash} + {@code phoneLast4}, GUEST row) — exactly one identity per row
 * (V211 {@code chk_client_blocks_identity_kind}; a registered client's profile phone is never hashed or stored).
 *
 * <p>Rows are inserted by {@code ClientBlockRepository#insertIgnoringDuplicate} (ON CONFLICT DO NOTHING over the
 * partial unique indexes of V210 — not expressible in JPA {@code @Index}, so none are mirrored here). Exactly one
 * blocker column is set. {@code phoneHash} is an HMAC (phase 397 D3), never the raw phone. Rows are never updated.
 * Lifecycle: deleted on unblock or by FK cascade when the blocker master/salon or the blocked user is deleted; no
 * external storage.
 */
@Entity
@Table(name = "client_blocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientBlock {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "blocker_master_id", updatable = false)
    private UUID blockerMasterId;

    @Column(name = "blocker_salon_id", updatable = false)
    private UUID blockerSalonId;

    @Column(name = "blocked_user_id", updatable = false)
    private UUID blockedUserId;

    /** Read-only join target for the list projection's live name/avatar (LAZY; never navigated in Java). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_user_id", insertable = false, updatable = false)
    private User blockedUser;

    @Column(name = "phone_hash", updatable = false)
    private byte[] phoneHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "phone_last4", length = 4)
    private String phoneLast4;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
