-- 1. Patch salons: add missing columns
ALTER TABLE salons ADD COLUMN IF NOT EXISTS region        VARCHAR(100);
ALTER TABLE salons ADD COLUMN IF NOT EXISTS instagram_url VARCHAR(500);
ALTER TABLE salons ADD COLUMN IF NOT EXISTS avatar_url    VARCHAR(500);

-- 2. Add unique constraint on owner_id (one salon per owner)
ALTER TABLE salons ADD CONSTRAINT uq_salons_owner_id UNIQUE (owner_id);

-- 3. Create masters table
CREATE TABLE masters (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        UNIQUE NOT NULL REFERENCES users(id),
    salon_id     UUID        REFERENCES salons(id) ON DELETE SET NULL,
    master_type  VARCHAR(30) NOT NULL,
    avg_rating   NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    review_count INTEGER      NOT NULL DEFAULT 0,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_masters_salon_id ON masters(salon_id);
CREATE INDEX idx_masters_user_id  ON masters(user_id);

-- 4. Create working_hours table
CREATE TABLE working_hours (
    id          UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    master_id   UUID     NOT NULL REFERENCES masters(id) ON DELETE CASCADE,
    day_of_week INTEGER NOT NULL,
    start_time  TIME     NOT NULL,
    end_time    TIME     NOT NULL,
    is_active   BOOLEAN  NOT NULL DEFAULT TRUE,
    UNIQUE(master_id, day_of_week)
);
CREATE INDEX idx_working_hours_master_id ON working_hours(master_id);

-- 5. Create schedule_exceptions table
CREATE TABLE schedule_exceptions (
    id         UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    master_id  UUID    NOT NULL REFERENCES masters(id) ON DELETE CASCADE,
    date       DATE    NOT NULL,
    reason     VARCHAR(50) NOT NULL,
    note       TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(master_id, date),
    CONSTRAINT chk_exception_reason CHECK (
        reason IN ('VACATION','HOLIDAY','SICK_DAY','OTHER')
    )
);
CREATE INDEX idx_schedule_exceptions_master ON schedule_exceptions(master_id, date);

-- 6. Retroactively add FK on invite_tokens.salon_id (column exists from V1 as bare UUID)
ALTER TABLE invite_tokens
    ADD CONSTRAINT fk_invite_tokens_salon_id
    FOREIGN KEY (salon_id) REFERENCES salons(id) ON DELETE CASCADE;
