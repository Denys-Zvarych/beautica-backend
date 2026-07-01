CREATE TABLE phone_otps (
    id          BIGSERIAL    PRIMARY KEY,
    phone       VARCHAR(20)  NOT NULL,
    code_hash   VARCHAR(64)  NOT NULL,  -- SHA-256 hex of the 4-digit plaintext code
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_phone_otps_lookup
    ON phone_otps(phone, used, expires_at) WHERE NOT used;
