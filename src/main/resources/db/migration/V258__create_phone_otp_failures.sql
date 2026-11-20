-- non-destructive
-- Reason: creates one new table and its index; no existing table, row or previous-app write is touched.
-- Phone-first hardening audit-fix (AUTH-D26 c, user decision 2026-11-19) — the 10 failed SMS-code verifies per
-- 24 h are counted per (phone, client network), not per phone alone, so a stranger typing wrong codes for a victim's
-- number from their own network cannot lock the victim out. One row per wrong code: the phone, the client network key
-- (ClientIpResolver.prefixKey: an IPv6 /64 like '2001:db8:0:0:0:0:0:0/64', an IPv4 address, or 'unknown' when no
-- request is bound) and when. phone_otps.failed_attempts (V255) stays the per-code tally; it cannot carry the network
-- because one code can be guessed from several networks.
-- Read by PhoneOtpRepository#countFailuresSince / #sendLimitStats over (phone, client_net, failed_at > now - 24 h),
-- served by ix_phone_otp_failures_phone_net_at; swept by PhoneOtpCleanupJob after 25 h (failed_at < now - 25 h,
-- served by ix_phone_otp_failures_at).
-- No FK: phone_otps rows are swept on their own schedule and a failure must outlive its code row.
-- Contains a phone number (PII): the guest-PII erase (PhoneOtpService#deleteAllForPhone) deletes it with the codes.
SET LOCAL lock_timeout = '3s';

CREATE TABLE phone_otp_failures (
    id          BIGSERIAL    PRIMARY KEY,
    phone       VARCHAR(20)  NOT NULL,
    client_net  VARCHAR(64)  NOT NULL,
    failed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_phone_otp_failures_phone CHECK (phone ~ '^\+380[0-9]{9}$'),
    CONSTRAINT ck_phone_otp_failures_client_net CHECK (
        client_net = 'unknown' OR client_net ~ '^[0-9A-Fa-f:.]{2,45}(/64)?$')
);

CREATE INDEX IF NOT EXISTS ix_phone_otp_failures_phone_net_at
    ON phone_otp_failures (phone, client_net, failed_at);

CREATE INDEX IF NOT EXISTS ix_phone_otp_failures_at
    ON phone_otp_failures (failed_at);
