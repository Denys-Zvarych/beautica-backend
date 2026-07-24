-- V60: add bio and instagram to users
-- bio   — free-text professional description (TEXT, no length cap at DB level; app layer caps at 2000).
-- instagram — raw handle / URL fragment (VARCHAR 100; matches @Column(length = 100) on User).
-- Both columns are nullable — no backfill required for existing rows.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS bio       TEXT,
    ADD COLUMN IF NOT EXISTS instagram VARCHAR(100);
