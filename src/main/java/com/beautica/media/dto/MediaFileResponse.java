package com.beautica.media.dto;

import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a stored {@link MediaFile} row.
 *
 * <p><b>Uploader intentionally omitted</b> — keeps {@code MediaFile.uploader}
 * LAZY (no N+1 when listing portfolios) and prevents PII leak of the uploader's
 * user record via JSON serialization (Phase 7.3 security LOW).
 *
 * <p>Constructed via {@link #from(MediaFile)}; never wraps the JPA entity directly.
 */
public record MediaFileResponse(
        UUID id,
        EntityType entityType,
        UUID entityId,
        MediaType mediaType,
        String url,
        Instant createdAt
) {

    public static MediaFileResponse from(MediaFile m) {
        return new MediaFileResponse(
                m.getId(),
                m.getEntityType(),
                m.getEntityId(),
                m.getMediaType(),
                m.getR2Url(),
                m.getCreatedAt()
        );
    }
}
