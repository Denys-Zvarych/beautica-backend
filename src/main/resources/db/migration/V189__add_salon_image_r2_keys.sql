-- Phase 343: salon logo (salons.avatar_url) and cover (salons.cover_image_url) are now uploaded to R2 by
-- the backend (SALON_OWNER only). The *_r2_key columns hold the object key so a replace / delete / salon
-- teardown removes exactly the blob the row owns; the *_url columns stay the read model.
-- NULL is a valid state for every column (no image, or a legacy url-only row).
--
-- Key CHECKs mirror V187/V188: the V39 opaque-key shape (no traversal, no leading slash), narrowed to the
-- salon's OWN slot root -- the same rule MediaService enforces before any R2 delete:
--   avatar_r2_key -> salons/<id>/logo/...      cover_r2_key -> salons/<id>/cover/...
-- URL CHECKs mirror V188's https-only guard. Existing local rows verified compliant before this migration
-- (read-only SELECT: no salon carries a non-https avatar_url / cover_image_url), so all four are added VALID.
ALTER TABLE salons
    ADD COLUMN avatar_r2_key VARCHAR(500),
    ADD COLUMN cover_r2_key  VARCHAR(500);

ALTER TABLE salons
    ADD CONSTRAINT chk_salons_avatar_r2_key_format
        CHECK (avatar_r2_key IS NULL
               OR (avatar_r2_key ~ '^[a-zA-Z0-9/_.\-]+$'
                   AND avatar_r2_key NOT LIKE '%..%'
                   AND avatar_r2_key NOT LIKE '/%'
                   AND avatar_r2_key LIKE ('salons/' || id::text || '/logo/%')));

ALTER TABLE salons
    ADD CONSTRAINT chk_salons_cover_r2_key_format
        CHECK (cover_r2_key IS NULL
               OR (cover_r2_key ~ '^[a-zA-Z0-9/_.\-]+$'
                   AND cover_r2_key NOT LIKE '%..%'
                   AND cover_r2_key NOT LIKE '/%'
                   AND cover_r2_key LIKE ('salons/' || id::text || '/cover/%')));

ALTER TABLE salons
    ADD CONSTRAINT chk_salons_avatar_url_scheme
        CHECK (avatar_url IS NULL OR avatar_url LIKE 'https://%');

ALTER TABLE salons
    ADD CONSTRAINT chk_salons_cover_image_url_scheme
        CHECK (cover_image_url IS NULL OR cover_image_url LIKE 'https://%');
