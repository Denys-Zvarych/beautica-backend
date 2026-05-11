package com.beautica.media.repository;

import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MediaRepository extends JpaRepository<MediaFile, UUID> {

    /**
     * Find all media files attached to a polymorphic entity (e.g., portfolio listing for a master).
     * Used by Phase 7.5 portfolio listing.
     */
    List<MediaFile> findByEntityTypeAndEntityId(EntityType entityType, UUID entityId);

    /**
     * Find a single media file by uploader and media type (e.g., avatar lookup).
     * Spring Data property traversal walks {@code uploader.id}.
     * Backed by composite index {@code idx_media_files_uploader_media (uploader_id, media_type)}.
     */
    Optional<MediaFile> findByUploaderIdAndMediaType(UUID uploaderId, MediaType mediaType);
}
