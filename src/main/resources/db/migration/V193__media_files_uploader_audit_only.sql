-- Phase 363 D1 — media_files.uploader_id becomes audit-only metadata.
--
-- Locked rule (2026-10-12): salon portfolio photos belong to the SALON. V37 declared
-- uploader_id ... REFERENCES users(id) ON DELETE CASCADE, so removing an admin (or the admin
-- deleting their own account) silently deleted every salon photo that admin had uploaded.
-- After this migration deleting a user nulls uploader_id and keeps the row; the application
-- deletes a user's OWN rows (USER rows + their master's MASTER portfolio) explicitly by entity,
-- and only salon deletion removes SALON rows (phase 268).

-- Fail fast instead of queueing behind long readers: DROP NOT NULL takes ACCESS EXCLUSIVE and
-- holds it until commit, and every request touching media_files would queue behind the wait.
SET LOCAL lock_timeout = '5s';

ALTER TABLE media_files ALTER COLUMN uploader_id DROP NOT NULL;

-- V37 declared the FK inline, so its name is Postgres-generated (normally
-- media_files_uploader_id_fkey). Resolve it from the catalog rather than assuming the name, so
-- the drop cannot silently miss and leave the CASCADE in place next to the new FK.
DO $$
DECLARE
    fk_name text;
BEGIN
    FOR fk_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute att
          ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
        WHERE con.conrelid = 'media_files'::regclass
          AND con.contype = 'f'
          AND att.attname = 'uploader_id'
    LOOP
        EXECUTE format('ALTER TABLE media_files DROP CONSTRAINT %I', fk_name);
    END LOOP;
END $$;

ALTER TABLE media_files
    ADD CONSTRAINT fk_media_files_uploader_id
    FOREIGN KEY (uploader_id) REFERENCES users(id) ON DELETE SET NULL
    NOT VALID;

-- The FK is left NOT VALID here on purpose. Validating it in THIS migration would gain nothing:
-- Flyway runs the whole file in one transaction, and the DROP NOT NULL above already holds ACCESS
-- EXCLUSIVE on media_files (and the ADD holds SHARE ROW EXCLUSIVE on users) until commit, so a
-- VALIDATE here would scan while both locks are held. V194 validates it in its own transaction,
-- where VALIDATE takes only SHARE UPDATE EXCLUSIVE on media_files and ROW SHARE on users.

-- idx_media_files_uploader_media (uploader_id, media_type) is KEPT: its leading uploader_id column
-- serves the ON DELETE SET NULL action (UPDATE media_files ... WHERE uploader_id = $1) on every
-- user delete, which would otherwise sequentially scan media_files. No application query reads it.
