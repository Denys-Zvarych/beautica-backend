-- Add CHECK constraint to verification_attempts as defence-in-depth
-- so raw SQL inserts cannot persist values outside the valid 0-10 range.
-- The application cap is 10 (EmailVerificationProcessor); this constraint mirrors it.
ALTER TABLE email_verifications
    ADD CONSTRAINT chk_verification_attempts_range
    CHECK (verification_attempts >= 0 AND verification_attempts <= 10);
