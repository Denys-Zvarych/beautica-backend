-- V269 — B546 (REV-38, REV-D04, REV-D11): a provider may rate a guest (LINK) or walk-in (STAFF) client by phone.
--
-- Subject of a client_reviews row is now exactly ONE of:
--   subject_client_id   : the registered CLIENT account (unchanged for every existing row);
--   subject_guest_phone : the booking's E.164 guest_phone, for a booking that has no account yet. When that phone is
--                         later attached to an account (CLI-62) the row moves to subject_client_id and the phone is
--                         nulled (ClientRatingSubjectService#moveGuestRatingsToAccount, keyed on the attached bookings).
--                         Personal data: deleted with the guest PII (GST-51, REV-40).
--
-- CHECK uses num_nonnulls(...) = 1: it is never NULL, so it cannot pass vacuously on NULL input (Postgres CHECK passes
-- on NULL) and rejects both-null and both-set.
-- subject_guest_phone mirrors bookings.guest_phone exactly (VARCHAR(20), V89 format CHECK '^\+[0-9]{6,18}$'): the value
-- is copied from that column, so a narrower type could reject a value the booking row already holds.
--
-- Existing rows all carry subject_client_id (V128 NOT NULL), so both CHECKs hold for them; they are added NOT VALID and
-- validated in a second statement (SHARE UPDATE EXCLUSIVE only) per /sql § 4. client_reviews is a small table; the
-- ALTERs take ACCESS EXCLUSIVE briefly, so fail fast instead of queueing behind traffic (same values as V268).
-- The partial index backs the guest readout (ClientRatingSql guest arm) and the GST-51 delete; JPA @Index cannot
-- declare a partial index, so it is documented on ClientReview instead of mirrored in @Table.
-- No external-storage column (§O-8 n/a). Fix-forward version: max existing = V268.
-- non-destructive
-- both CHECKs hold for every existing row (all carry subject_client_id) and for every previous-app INSERT (sets subject_client_id, never subject_guest_phone).
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE client_reviews
    ALTER COLUMN subject_client_id DROP NOT NULL,
    ADD COLUMN subject_guest_phone VARCHAR(20);

ALTER TABLE client_reviews
    ADD CONSTRAINT chk_client_reviews_one_subject
        CHECK (num_nonnulls(subject_client_id, subject_guest_phone) = 1) NOT VALID,
    ADD CONSTRAINT chk_client_reviews_guest_phone_format
        CHECK (subject_guest_phone IS NULL OR subject_guest_phone ~ '^\+[0-9]{6,18}$') NOT VALID;

ALTER TABLE client_reviews VALIDATE CONSTRAINT chk_client_reviews_one_subject;
ALTER TABLE client_reviews VALIDATE CONSTRAINT chk_client_reviews_guest_phone_format;

CREATE INDEX idx_client_reviews_subject_guest_phone
    ON client_reviews (subject_guest_phone)
    WHERE subject_guest_phone IS NOT NULL;

COMMENT ON COLUMN client_reviews.subject_guest_phone IS
    'B546 (REV-38): E.164 guest phone of a rated guest / walk-in booking with no account. Exactly one of '
    'subject_client_id / subject_guest_phone is set (chk_client_reviews_one_subject). Moves to subject_client_id on '
    'attach (REV-39); deleted with the guest PII (REV-40).';
