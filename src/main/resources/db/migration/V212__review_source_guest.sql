-- non-destructive
-- Reason: all three CHECKs (NOT VALID here, validated in V214) constrain only the two columns this file adds; source defaults to 'APP' and guest_display_name to NULL, so every existing row and every previous-app INSERT satisfies them.
-- Phase 399 — guest reviews (decisions LOCKED 2026-10-28): a review can be written by a LINK guest through an SMS
-- invite (phases 400-401), with no user account.
--
-- 1) reviews.source (D1): 'APP' (written in the app by a CLIENT account) or 'GUEST_LINK' (written on the public
--    /r/{token} page). NOT NULL with a DEFAULT, so every existing row becomes 'APP' and the CHECKs below can never
--    pass vacuously on a NULL source.
--
-- 2) reviews.guest_display_name (D1): «Ім'я П.» snapshot of the guest (phase 401 D5), shown in place of the account
--    name. Never the phone, never the full surname. Phase 402 overwrites it with the anonymised sentinel.
--
-- 3) chk_reviews_source_author — the author shape per source:
--      GUEST_LINK: client_id IS NULL AND guest_display_name IS NOT NULL (non-blank).
--      APP:        guest_display_name IS NULL.
--    Deviation from D1's literal text, recorded on purpose: D1 also asks for `client_id IS NOT NULL` on APP rows and
--    says client_id "becomes nullable". It already IS nullable — V162 (phase 300 D3) made reviews.client_id NULLable
--    with FK ON DELETE SET NULL, so an APP review survives its author's account self-delete with a NULL client_id.
--    Requiring client_id on APP rows would make that FK action violate the CHECK and abort every self-delete of a
--    client who has written a review. The APP arm therefore leaves client_id unconstrained; the D1 intent — a guest
--    review has no account and always has a display name, an app review never carries a guest name — is enforced.
--
-- reviews.client_id needs no change (already nullable, V162). No raw phone and no token column is added here.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

-- ONE ALTER TABLE (V139 house style): one ACCESS EXCLUSIVE acquisition instead of four. Both ADD COLUMNs are
-- catalog-only on PG 11+ (constant DEFAULT / nullable without default), and every CHECK is NOT VALID, so the held-lock
-- phase does no table scan at all. V214 validates the three CHECKs under SHARE UPDATE EXCLUSIVE (blocks neither
-- readers nor writers). Do not fold the VALIDATE back in here: Postgres never downgrades a lock mid-transaction.
-- NOT VALID still enforces every CHECK on new INSERT/UPDATE from this statement on.
--
-- 4) chk_reviews_guest_display_name_no_digits — the snapshot is published on public cached endpoints; three digits in
--    a row is a phone fragment, never a name. Review.guestLinkReview strips every digit, so this is a backstop for a
--    raw-SQL / future writer. NULL (APP rows) passes, as intended. The class covers ASCII, Arabic-Indic (U+0660-0669),
--    Extended Arabic-Indic (U+06F0-06F9) and fullwidth (U+FF10-FF19) digits — a phone typed in any of those is still a
--    phone. One class repeated {3}, so a mixed-script fragment («0٥5») matches too.
ALTER TABLE reviews
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'APP',
    ADD COLUMN guest_display_name VARCHAR(64) NULL,
    ADD CONSTRAINT chk_reviews_source CHECK (source IN ('APP', 'GUEST_LINK')) NOT VALID,
    ADD CONSTRAINT chk_reviews_source_author CHECK (
        (source = 'APP' AND guest_display_name IS NULL)
        OR (source = 'GUEST_LINK'
            AND client_id IS NULL
            AND guest_display_name IS NOT NULL
            AND char_length(btrim(guest_display_name)) > 0)
    ) NOT VALID,
    ADD CONSTRAINT chk_reviews_guest_display_name_no_digits
        CHECK (guest_display_name !~ '[0-9٠-٩۰-۹０-９]{3}') NOT VALID;

COMMENT ON COLUMN reviews.source IS
    'Phase 399 D1. APP = written in the app by a CLIENT account (client_id set, or NULL after a phase-300 self-delete); '
    'GUEST_LINK = written by a LINK guest through an SMS review invite (client_id always NULL).';
COMMENT ON COLUMN reviews.guest_display_name IS
    'Phase 399 D1. GUEST_LINK only: «Ім''я П.» snapshot of the guest, rendered as the author name. NULL on APP rows.';
