-- Phase 342: service photo is now uploaded to R2 by the backend. photo_r2_key holds the object key so a
-- replace/delete/teardown can remove the blob (the public photo_url alone is not a reliable key source).
-- Legacy rows (photo_url set, photo_r2_key NULL) are left untouched. NULL is a valid state.
ALTER TABLE service_definitions
    ADD COLUMN photo_r2_key VARCHAR(500);

ALTER TABLE service_definitions
    ADD CONSTRAINT chk_service_def_photo_r2_key_format
        CHECK (photo_r2_key IS NULL
               OR (photo_r2_key ~ '^[a-zA-Z0-9/_.\-]+$'
                   AND photo_r2_key NOT LIKE '%..%'
                   AND photo_r2_key NOT LIKE '/%'));
