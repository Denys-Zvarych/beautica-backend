-- Phase 7.1 doc names this V12; bumped to V37 because V12..V36 already exist.
-- Store the R2 object key alongside the URL so deletion never relies on string-parsing the URL
-- (which breaks if R2_PUBLIC_URL changes).
ALTER TABLE users ADD COLUMN avatar_r2_key VARCHAR(500);

CREATE TABLE media_files (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    uploader_id  UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entity_type  VARCHAR(20)   NOT NULL,
    entity_id    UUID          NOT NULL,
    media_type   VARCHAR(20)   NOT NULL,
    r2_key       VARCHAR(500)  NOT NULL,
    r2_url       VARCHAR(1000) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_entity_type CHECK (entity_type IN ('USER', 'SALON', 'MASTER')),
    CONSTRAINT chk_media_type  CHECK (media_type  IN ('AVATAR', 'PORTFOLIO'))
);

CREATE INDEX idx_media_files_entity ON media_files(entity_type, entity_id);

-- Covers MediaRepository.findByUploaderIdAndMediaType(uploaderId, mediaType) declared in Phase 7.3 spec.
CREATE INDEX idx_media_files_uploader_media ON media_files(uploader_id, media_type);
