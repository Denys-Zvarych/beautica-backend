package com.beautica.media.repository;

import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
     * Find every media file uploaded by the given user. Drives the SEC-2
     * {@code MediaService.deleteByUploader} sweep — R2 blobs must be deleted
     * before the {@code users} row is dropped, otherwise
     * {@code ON DELETE CASCADE} leaves the R2 objects orphaned.
     *
     * <p>Spring Data property traversal walks {@code uploader.id}; backed by the
     * composite index {@code idx_media_files_uploader_media (uploader_id, media_type)}
     * via leftmost-prefix.
     */
    List<MediaFile> findByUploaderId(UUID uploaderId);
}
