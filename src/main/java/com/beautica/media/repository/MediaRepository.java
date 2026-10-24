package com.beautica.media.repository;

import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MediaRepository extends JpaRepository<MediaFile, UUID> {

    /**
     * Find all media files attached to a polymorphic entity (e.g., portfolio listing for a master).
     * Used by Phase 7.5 portfolio listing and the non-paginated cached path in MediaService.
     *
     * <p>uploader is intentionally LAZY — {@code MediaFileResponse.from} never accesses uploader fields.
     */
    List<MediaFile> findByEntityTypeAndEntityId(EntityType entityType, UUID entityId);

    /**
     * Paginated portfolio listing — used by the public GET portfolio endpoints (Anti-Bug § J).
     * Not cached: each (page, size, sort) combination would produce a separate cache entry,
     * creating unbounded Caffeine heap growth. The 5-min TTL on the non-paginated
     * {@code @Cacheable} variant in MediaService covers internal callers.
     */
    Page<MediaFile> findByEntityTypeAndEntityId(EntityType entityType, UUID entityId, Pageable pageable);

    /**
     * The first rows of an entity's media in the given {@code pageable}'s window as a plain {@link List}: no total
     * count (a {@link Page} return would add a COUNT statement whenever the window is full). Backs
     * {@code MediaService#getLatestPortfolio}. The sort comes from the caller's {@code pageable}.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT m FROM MediaFile m WHERE m.entityType = :entityType AND m.entityId = :entityId")
    List<MediaFile> findWindowByEntityTypeAndEntityId(
            @org.springframework.data.repository.query.Param("entityType") EntityType entityType,
            @org.springframework.data.repository.query.Param("entityId") UUID entityId,
            Pageable pageable);

    /**
     * Phase 363 D3/D4 — scalar pointers of every {@code media_files} row an account deletion must remove: the
     * accounts' own {@code (USER, userId)} rows and the {@code (MASTER, masterId)} portfolio of the masters rows
     * behind them. Keyed ONLY on the owning entity, never on {@code uploader_id} (audit metadata since V193) —
     * so a SALON row is structurally out of reach of any user lifecycle. Pre-read before
     * {@link #deleteAccountMedia} for the after-commit R2 purge. Projection only (P-L1). Two
     * {@code idx_media_files_entity} probes, OR-ed.
     */
    @Query("""
            SELECT new com.beautica.media.repository.MediaFileKey(m.id, m.r2Key, m.entityType, m.entityId)
            FROM MediaFile m
            WHERE (m.entityType = com.beautica.media.entity.EntityType.USER AND m.entityId IN :userIds)
               OR (m.entityType = com.beautica.media.entity.EntityType.MASTER AND m.entityId IN :masterIds)
            """)
    List<MediaFileKey> findAccountMediaKeys(@Param("userIds") Collection<UUID> userIds,
                                            @Param("masterIds") Collection<UUID> masterIds);

    /**
     * Phase 363 D4 — deletes the rows {@link #findAccountMediaKeys} reads, in the account deletion's own
     * transaction. Required since V193 replaced the {@code uploader_id ON DELETE CASCADE}: nothing else removes
     * a deleted master's portfolio rows. One bulk statement; {@code MediaFile} has no cascades or listeners.
     */
    @Modifying
    @Query("""
            DELETE FROM MediaFile m
            WHERE (m.entityType = com.beautica.media.entity.EntityType.USER AND m.entityId IN :userIds)
               OR (m.entityType = com.beautica.media.entity.EntityType.MASTER AND m.entityId IN :masterIds)
            """)
    int deleteAccountMedia(@Param("userIds") Collection<UUID> userIds,
                           @Param("masterIds") Collection<UUID> masterIds);

    /**
     * Phase 362 audit cycle 1 (F2) — the unlocked cap fast-fail of a salon portfolio upload, folded into the
     * active-salon read-gate: the salon's PORTFOLIO count, or empty when the salon is unknown or inactive. One
     * round trip before the R2 PUT. Not authoritative — the locked re-count under
     * {@code SalonRepository#lockIsActiveForUpdate} is.
     */
    @Query(value = """
            SELECT (SELECT count(*) FROM media_files m
                    WHERE m.entity_type = 'SALON' AND m.entity_id = s.id AND m.media_type = 'PORTFOLIO')
            FROM salons s
            WHERE s.id = :salonId AND s.is_active = TRUE
            """, nativeQuery = true)
    Optional<Long> countPortfolioOfActiveSalon(@Param("salonId") UUID salonId);

    /**
     * Phase 362 audit cycle 2 (G3) — the salon portfolio delete's single load: the PORTFOLIO row
     * {@code mediaId} of exactly {@code salonId}, present only while that salon is active. Empty for a
     * missing row, another salon's row, a non-portfolio row or an inactive/unknown salon — the caller maps
     * every case to the same 404. Replaces the former {@code existsByIdAndIsActiveTrue} + {@code findById}
     * pair (two round trips).
     */
    @Query("""
            SELECT m FROM MediaFile m
            WHERE m.id = :mediaId
              AND m.mediaType = com.beautica.media.entity.MediaType.PORTFOLIO
              AND m.entityType = com.beautica.media.entity.EntityType.SALON
              AND m.entityId = :salonId
              AND EXISTS (SELECT 1 FROM Salon s WHERE s.id = :salonId AND s.isActive = TRUE)
            """)
    Optional<MediaFile> findPortfolioRowOfActiveSalon(@Param("mediaId") UUID mediaId,
                                                      @Param("salonId") UUID salonId);

    /**
     * Phase 362 audit cycle 3 (H3) — the independent-master portfolio delete's single load: the PORTFOLIO row
     * {@code mediaId} whose owning entity is the masters row of user {@code userId}. Empty for a missing row,
     * another master's row, a SALON row, an avatar/USER row, or a caller with no masters row — the caller maps
     * every case to the same 404, so the endpoint is no existence oracle for other masters' media ids. Mirrors
     * {@link #findPortfolioRowOfActiveSalon}; replaces the former {@code findById} + {@code findIdByUserId} pair
     * (two round trips, and a 403/404 split that leaked whether a foreign id was a master photo).
     */
    @Query("""
            SELECT m FROM MediaFile m
            WHERE m.id = :mediaId
              AND m.mediaType = com.beautica.media.entity.MediaType.PORTFOLIO
              AND m.entityType = com.beautica.media.entity.EntityType.MASTER
              AND EXISTS (SELECT 1 FROM Master ms WHERE ms.id = m.entityId AND ms.user.id = :userId)
            """)
    Optional<MediaFile> findPortfolioRowOfMasterUser(@Param("mediaId") UUID mediaId,
                                                     @Param("userId") UUID userId);

    /**
     * Scalar pointers of every media file attached to {@code (entityType, entityId)} — the salon-deletion
     * pre-read, captured inside the deletion transaction and
     * handed to the after-commit purge task. Projection only — no entity, no uploader proxy (P-L3).
     */
    @Query("""
            SELECT new com.beautica.media.repository.MediaFileKey(m.id, m.r2Key, m.entityType, m.entityId)
            FROM MediaFile m
            WHERE m.entityType = :entityType AND m.entityId = :entityId
            """)
    List<MediaFileKey> findMediaKeysByEntityTypeAndEntityId(@Param("entityType") EntityType entityType,
                                                            @Param("entityId") UUID entityId);

    /**
     * Phase 362 D5/D6 — the portfolio cap probe. Only meaningful under the parent row lock
     * ({@code SalonRepository#lockIsActiveForUpdate} / {@code MasterRepository#lockIdForUpdate}), which
     * serialises concurrent uploads for the same entity. Backed by {@code idx_media_files_entity}.
     */
    long countByEntityTypeAndEntityIdAndMediaType(EntityType entityType, UUID entityId, MediaType mediaType);

    /**
     * Phase 362 — "is this freshly uploaded key referenced by a committed row?" probe for the
     * discard-unless-committed cleanup of a failed portfolio insert. Scoped by the owning entity so it rides
     * {@code idx_media_files_entity} (at most the capped portfolio size) instead of scanning on the unindexed
     * {@code r2_key}.
     */
    boolean existsByEntityTypeAndEntityIdAndR2Key(EntityType entityType, UUID entityId, String r2Key);
}
