package com.beautica.booking.entity;

import com.beautica.booking.dto.BookingLinkKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 378 — a retired public booking slug (V204 {@code retired_booking_slugs}, D2/D3/D14; V205 hash-only).
 *
 * <p>Every slug ever taken off a master or salon by a regenerate / revoke (and, from phase 379, a
 * deletion). Both slug generators treat a tombstoned {@code (kind, slug_hash)} as taken, so a retired
 * slug is never issued to anyone again — old shared links must never land on a different person.
 * The row is also the audit record ({@code retiredByUserId}, {@code reason}, {@code retiredAt}).
 *
 * <p><b>No plaintext slug (V205, 378 audit security MEDIUM).</b> The row outlives account erasure, so it
 * stores only {@code slugHash} = {@code RetiredSlugHasher#hash(kind, slug)}, never the name-derived slug.
 * Every probe and insert hashes through that one helper (phase 379's 410 lookup included).
 *
 * <p>Read-only from JPA: the only writers are the native
 * {@code RetiredBookingSlugRepository#insertMasterTombstone} /
 * {@code RetiredBookingSlugRepository#insertSalonTombstone}, issued in the same transaction as the
 * conditional slug UPDATE. The FKs are {@code ON DELETE SET NULL}, so the tombstone outlives the
 * master / salon / user row and keeps blocking the slug.
 */
@Entity
@Table(
        name = "retired_booking_slugs",
        indexes = {
                // V204 partial indexes (WHERE ... IS NOT NULL) — JPA @Index cannot express the predicate.
                @Index(name = "idx_retired_booking_slugs_master", columnList = "master_id"),
                @Index(name = "idx_retired_booking_slugs_salon", columnList = "salon_id"),
                @Index(name = "idx_retired_booking_slugs_retired_by", columnList = "retired_by_user_id")
        })
@IdClass(RetiredBookingSlugId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RetiredBookingSlug {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false, updatable = false)
    private BookingLinkKind kind;

    /** Lower-hex SHA-256 of {@code kind:slug} (V205 CHECK {@code ^[0-9a-f]{64}$}). */
    @Id
    @Column(name = "slug_hash", length = 64, nullable = false, updatable = false)
    private String slugHash;

    @Column(name = "master_id", updatable = false)
    private UUID masterId;

    @Column(name = "salon_id", updatable = false)
    private UUID salonId;

    @Column(name = "retired_by_user_id", updatable = false)
    private UUID retiredByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 16, nullable = false, updatable = false)
    private RetiredSlugReason reason;

    @Column(name = "retired_at", nullable = false, updatable = false)
    private Instant retiredAt;
}
