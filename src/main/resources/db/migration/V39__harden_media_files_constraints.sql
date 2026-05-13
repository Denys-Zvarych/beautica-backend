-- Belt-and-suspenders shape guard on R2 storage key (prevents path traversal if future code joins key into FS path)
ALTER TABLE media_files
    ADD CONSTRAINT chk_media_files_r2_key_shape
        CHECK (r2_key ~ '^[a-zA-Z0-9/_.\-]+$' AND r2_key NOT LIKE '%..%' AND r2_key NOT LIKE '/%');

-- Defense-in-depth: reject non-HTTPS URLs stored in the table
ALTER TABLE media_files
    ADD CONSTRAINT chk_media_files_r2_url_scheme
        CHECK (r2_url LIKE 'https://%');

-- Business rule: PORTFOLIO is only valid for SALON/MASTER entities; AVATAR is valid for all
ALTER TABLE media_files
    ADD CONSTRAINT chk_media_files_media_type_entity_type
        CHECK (
            (media_type = 'PORTFOLIO' AND entity_type IN ('SALON', 'MASTER'))
            OR media_type = 'AVATAR'
        );

-- Enforce 1:1 AVATAR per entity (prevents controller bugs creating duplicate avatars)
CREATE UNIQUE INDEX ux_media_files_avatar
    ON media_files(entity_type, entity_id)
    WHERE media_type = 'AVATAR';
