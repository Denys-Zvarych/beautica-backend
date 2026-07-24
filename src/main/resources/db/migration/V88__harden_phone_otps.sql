-- Phase 13.2 hardening: cap OTP verify brute-force + index the rate-limit count query.
--
-- (1) Per-OTP attempt counter. PhoneOtpService increments this on every wrong code and
--     invalidates (used = TRUE) the OTP once it reaches the cap (5), collapsing the
--     brute-force window from the full code space to at most 5 attempts. DEFAULT 0 so
--     raw SQL inserts (fixtures, seed scripts) never violate NOT NULL.
ALTER TABLE phone_otps
    ADD COLUMN attempts INT NOT NULL DEFAULT 0;

-- (2) Index the per-phone rate-limit count query in PhoneOtpService.sendOtp
--     (countByPhoneAndCreatedAtAfter: WHERE phone = ? AND created_at > ?). It must
--     include used rows, so this is a plain (non-partial) composite index — unlike the
--     active-lookup index (V87) which is partial WHERE NOT used.
CREATE INDEX idx_phone_otps_phone_created_at
    ON phone_otps (phone, created_at);
