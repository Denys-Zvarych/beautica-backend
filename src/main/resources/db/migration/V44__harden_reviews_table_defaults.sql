-- V44: Add DEFAULT now() to reviews timestamps so raw SQL inserts (fixtures, seed scripts)
-- do not require explicit timestamp values.
-- Application @CreatedDate/@LastModifiedDate audit annotations populate these fields from
-- the entity lifecycle; the DEFAULT acts as a safety net for direct JDBC inserts used in
-- integration tests and seed scripts (Anti-Bug Playbook §O rule 1).
ALTER TABLE reviews ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE reviews ALTER COLUMN updated_at SET DEFAULT now();
