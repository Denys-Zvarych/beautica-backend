-- Reviews are per-booking (locked rule: 1 booking = 1 feedback). The visit-level
-- review write path was removed, so this column and its partial unique index
-- ux_reviews_appointment (which enforced the revoked one-review-per-visit rule)
-- are dead. Dropping the column implicitly drops the index and the FK.
ALTER TABLE reviews DROP COLUMN appointment_id;
