package com.beautica.booking.repository;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.entity.RetiredBookingSlug;
import com.beautica.booking.entity.RetiredBookingSlugId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Phase 378 — the retired-slug tombstones ({@link RetiredBookingSlug}). The two probes back the slug
 * generators ({@code BookingSlugService}); the inserts are the one writer.
 *
 * <p><b>Hash-only (V205).</b> Every parameter here is a slug HASH from
 * {@code com.beautica.booking.service.RetiredSlugHasher#hash} — never a slug. The table holds no plaintext.
 */
public interface RetiredBookingSlugRepository extends JpaRepository<RetiredBookingSlug, RetiredBookingSlugId> {

    /** Master-generator probe: is this slug hash retired in {@code kind}'s namespace (PK lookup). */
    boolean existsByKindAndSlugHash(BookingLinkKind kind, String slugHash);

    /** Batched salon-generator probe: which of {@code slugHashes} are retired in {@code kind}'s namespace. */
    @Query("SELECT r.slugHash FROM RetiredBookingSlug r WHERE r.kind = :kind AND r.slugHash IN :slugHashes")
    List<String> findRetiredSlugHashes(
            @Param("kind") BookingLinkKind kind, @Param("slugHashes") Collection<String> slugHashes);

    /**
     * Writes one MASTER tombstone. Native and append-only: a JPA {@code save} of an assigned composite id
     * would {@code merge} (an extra SELECT); two kind-specific statements so no {@code NULL} is ever bound
     * (an untyped null UUID bind is rejected by Postgres). Must run in the transaction that takes the slug
     * off its owner, so both become visible in one commit (D11b).
     */
    @Modifying
    @Query(value = """
            INSERT INTO retired_booking_slugs (kind, slug_hash, master_id, retired_by_user_id, reason, retired_at)
            VALUES ('MASTER', :slugHash, :masterId, :retiredBy, :reason, :retiredAt)
            """, nativeQuery = true)
    int insertMasterTombstone(
            @Param("slugHash") String slugHash,
            @Param("masterId") UUID masterId,
            @Param("retiredBy") UUID retiredBy,
            @Param("reason") String reason,
            @Param("retiredAt") Instant retiredAt);

    /** SALON twin of {@link #insertMasterTombstone}. */
    @Modifying
    @Query(value = """
            INSERT INTO retired_booking_slugs (kind, slug_hash, salon_id, retired_by_user_id, reason, retired_at)
            VALUES ('SALON', :slugHash, :salonId, :retiredBy, :reason, :retiredAt)
            """, nativeQuery = true)
    int insertSalonTombstone(
            @Param("slugHash") String slugHash,
            @Param("salonId") UUID salonId,
            @Param("retiredBy") UUID retiredBy,
            @Param("reason") String reason,
            @Param("retiredAt") Instant retiredAt);
}
