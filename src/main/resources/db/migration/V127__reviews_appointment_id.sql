-- BE-6: appointment(visit)-level reviews. A client leaves ONE review per completed multi-service
-- visit (BE-3 appointment), mapped to that visit's SINGLE master (+ salon). Legacy per-booking
-- reviews are byte-for-byte unchanged: they keep appointment_id = NULL and booking_id pointing at
-- the single reviewed booking, and V40's UNIQUE(booking_id) still enforces one-review-per-booking.
--
-- Coexistence shape — chosen over "nullable booking_id + XOR CHECK", because it is the least
-- disruptive to the existing read/render surface:
--   * booking_id STAYS NOT NULL. A visit review sets booking_id to the visit's FIRST child booking.
--     appointment_id is the AUTHORITATIVE visit-dedup key; keeping booking_id populated lets the
--     whole review read path keep working with zero query rewrites — ReviewResponse.from and the
--     JOIN FETCH r.booking hydrate queries (findByIdsWithGraph / findByIdWithAssociations /
--     findMyReviews) are INNER joins that would silently DROP a null-booking review, so a visit
--     review would vanish from the master/salon reviews lists under the XOR shape. With booking_id
--     populated the visit review appears in those lists (serviceName renders the first service).
--   * appointment_id is NULLABLE and NULL for every legacy review (no backfill).
--   * A partial UNIQUE index on appointment_id WHERE appointment_id IS NOT NULL enforces
--     one-review-per-visit; the millions of legacy rows (appointment_id = NULL) are excluded and
--     never collide. The same partial index also serves the existsByAppointmentId lookup
--     (WHERE appointment_id = :id implies appointment_id IS NOT NULL), so no extra index is needed
--     (§E-5 / §O-6).
--
-- Forward-only: V40 and every committed review migration are untouched. FK is ON DELETE RESTRICT,
-- mirroring reviews.booking_id (V40) and the salon-hard-delete-blocked contract (V41): a visit that
-- carries a review cannot be hard-deleted; providers soft-delete / anonymize instead.

ALTER TABLE reviews
    ADD COLUMN appointment_id UUID REFERENCES appointments(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX ux_reviews_appointment
    ON reviews (appointment_id)
    WHERE appointment_id IS NOT NULL;

COMMENT ON COLUMN reviews.appointment_id IS
    'NON-NULL for a multi-service visit review (BE-6): the review targets the whole appointment; '
    'booking_id then points at the visit''s first child booking (used only for serviceName rendering '
    'and to keep the existing review read graph valid). NULL for a legacy single-booking review. '
    'One-review-per-visit is enforced by the partial unique index ux_reviews_appointment.';
