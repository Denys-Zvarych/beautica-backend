package com.beautica.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * B520 audit-fix cycle 2 (AUTH-D14) — one staff-invite SMS enqueued to a phone (V252), the durable record the
 * per-phone cap counts. Unlike {@link InviteToken} it has no FK and is never touched by a salon, owner or staff
 * delete, so deleting the inviting account cannot reset the cap. Holds only the keyed phone hash
 * ({@code PhoneHasher}); rows past the 24 h window are purged daily.
 *
 * <p>{@link Persistable} with an assigned id (the {@code GuestRescheduleSmsSend} pattern): {@code save} persists
 * without a merge SELECT inside the phone-lock transaction.
 */
@Entity
@Table(
        name = "invite_sms_phone_sends",
        indexes = @Index(name = "idx_invite_sms_phone_sends_phone_sent", columnList = "phone_hash, sent_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteSmsPhoneSend implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "phone_hash", nullable = false, updatable = false)
    @Getter(AccessLevel.NONE)
    private byte[] phoneHash;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean newEntity;

    public static InviteSmsPhoneSend of(byte[] phoneHash, Instant now) {
        InviteSmsPhoneSend send = new InviteSmsPhoneSend();
        send.id = UUID.randomUUID();
        send.phoneHash = Objects.requireNonNull(phoneHash, "phoneHash").clone();
        send.sentAt = Objects.requireNonNull(now, "now");
        send.createdAt = now;
        send.newEntity = true;
        return send;
    }

    /** A defensive copy of the keyed hash. */
    public byte[] getPhoneHash() {
        return phoneHash.clone();
    }

    /** {@code true} only for a fresh, not yet persisted row. */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
