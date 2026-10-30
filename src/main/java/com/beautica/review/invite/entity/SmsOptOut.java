package com.beautica.review.invite.entity;

import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * Phase 399 D4 — a phone that opted out of review-invite SMS, keyed by the {@code PhoneHasher} HMAC (phase 397) — the
 * one phone key shared with {@code client_blocks} and {@code review_invites}; never the raw phone. Created only
 * through {@link #of}. Retained when guest PII is erased unless a ticket asks (phase 402 D2). No external storage.
 */
@Entity
@Table(name = "sms_opt_outs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SmsOptOut {

    @Id
    @Column(name = "phone_hash", nullable = false, updatable = false)
    private byte[] phoneHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private SmsOptOutSource source;

    /** Takes a {@link HashedPhone} (only {@code PhoneHasher} mints one), so the key is always the shared phone hash. */
    public static SmsOptOut of(HashedPhone phone, SmsOptOutSource source, Instant now) {
        SmsOptOut optOut = new SmsOptOut();
        optOut.phoneHash = Objects.requireNonNull(phone, "phone").hash();
        optOut.source = Objects.requireNonNull(source, "source");
        optOut.createdAt = Objects.requireNonNull(now, "now");
        return optOut;
    }

    /** Defensive copy — the key is never exposed mutable. */
    @JsonIgnore
    public byte[] getPhoneHash() {
        return phoneHash.clone();
    }
}
