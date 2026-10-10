-- Security S-L3: users.avatar_r2_key (V37) and users.avatar_url (V38) had no format guard, so a raw-SQL /
-- seed / future-backfill write could store a traversal key or a non-HTTPS URL that the blob-purge paths then
-- act on. Mirrors V39's media_files.r2_key shape, narrowed to the user's OWN avatars/<id>/ prefix (the same
-- rule MediaService#resolveAvatarKey enforces before any R2 delete), and V39's https-only r2_url guard.
-- NULL passes both CHECKs by design (no avatar / legacy url-only rows). Existing local rows verified
-- compliant before this migration (read-only SELECT), so the constraints are added VALID.
ALTER TABLE users
    ADD CONSTRAINT chk_users_avatar_r2_key_format
        CHECK (avatar_r2_key IS NULL
               OR (avatar_r2_key ~ '^[a-zA-Z0-9/_.\-]+$'
                   AND avatar_r2_key NOT LIKE '%..%'
                   AND avatar_r2_key NOT LIKE '/%'
                   AND avatar_r2_key LIKE ('avatars/' || id::text || '/%')));

ALTER TABLE users
    ADD CONSTRAINT chk_users_avatar_url_scheme
        CHECK (avatar_url IS NULL OR avatar_url LIKE 'https://%');
