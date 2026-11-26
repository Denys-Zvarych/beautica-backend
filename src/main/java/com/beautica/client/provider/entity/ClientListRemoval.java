package com.beautica.client.provider.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * B564 (CLI-82..85) — one client removed from one provider list (V268). Exactly one scope ({@code salonId}: the salon
 * list shared by owner and admins; {@code masterId}: a salon master's or an independent master's own list) and exactly
 * one identity ({@code clientUserId}, or the guest's plain E.164 {@code guestPhone}, the value the list groups guests
 * on). The list hides the row while no booking of that client was created in the scope after {@code removedAt}.
 *
 * <p>Written only by {@code ClientListRemovalRepository}'s native upserts / deletes (ON CONFLICT over the partial
 * unique indexes of V268 — not expressible in JPA {@code @Index}, so none are mirrored here); read-only here. Lifecycle:
 * deleted on restore, on the guest's PII anonymisation (GST-51), or by FK cascade with the salon / master / client
 * account; no external storage.
 */
@Entity
@Table(name = "client_list_removals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientListRemoval {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "salon_id", updatable = false)
    private UUID salonId;

    @Column(name = "master_id", updatable = false)
    private UUID masterId;

    @Column(name = "client_user_id", updatable = false)
    private UUID clientUserId;

    /** Plain E.164 phone (PII): never serialised, never logged. */
    @JsonIgnore
    @Column(name = "guest_phone", updatable = false, length = 20)
    private String guestPhone;

    @Column(name = "removed_at", nullable = false)
    private Instant removedAt;

    @Column(name = "removed_by_user_id")
    private UUID removedByUserId;

    @Override
    public String toString() {
        // guestPhone is personal data: never in a log line.
        return "ClientListRemoval[id=" + id + "]";
    }
}
