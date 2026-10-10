package com.beautica.media.repository;

import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Find a single media file by uploader and media type (e.g., avatar lookup).
     * Spring Data property traversal walks {@code uploader.id}.
     * Backed by composite index {@code idx_media_files_uploader_media (uploader_id, media_type)}.
     */
    Optional<MediaFile> findByUploaderIdAndMediaType(UUID uploaderId, MediaType mediaType);

    /**
     * Every media file uploaded by any of {@code uploaderIds}, as scalars — the account-deletion pre-read (one
     * query for the whole disposed set), captured BEFORE the {@code ON DELETE CASCADE} on
     * {@code media_files.uploader_id} drops the rows. Projection only — no entity, no uploader JOIN FETCH
     * (backend-perf P-L1).
     */
    @Query("""
            SELECT new com.beautica.media.repository.UploaderMediaKey(m.uploader.id, m.r2Key, m.entityType, m.entityId)
            FROM MediaFile m
            WHERE m.uploader.id IN :uploaderIds
            """)
    List<UploaderMediaKey> findMediaKeysByUploaderIdIn(@Param("uploaderIds") Collection<UUID> uploaderIds);

    /**
     * Scalar pointers of every media file attached to {@code (entityType, entityId)} — the salon-deletion
     * pre-read, captured inside the deletion transaction (before a staff hard-delete can cascade a row away) and
     * handed to the after-commit purge task. Projection only — no entity, no uploader proxy (P-L3).
     */
    @Query("""
            SELECT new com.beautica.media.repository.MediaFileKey(m.id, m.r2Key, m.entityType, m.entityId)
            FROM MediaFile m
            WHERE m.entityType = :entityType AND m.entityId = :entityId
            """)
    List<MediaFileKey> findMediaKeysByEntityTypeAndEntityId(@Param("entityType") EntityType entityType,
                                                            @Param("entityId") UUID entityId);
}
