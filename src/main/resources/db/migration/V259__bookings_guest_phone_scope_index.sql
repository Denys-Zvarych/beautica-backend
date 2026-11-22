-- Phase 508 (D5): provider client card + visit history resolve a guest as "every booking in the viewer's scope with
-- this phone and client_id IS NULL" (the phone comes from the anchor booking). The scope (salon_id / master_id) is
-- selective on its own, but a salon-wide scope over a long history would otherwise be scanned per card; this index
-- turns the phone equality into an index read. Only guest_phone is indexed: the visit page re-sorts the grouped scope
-- by visit start anyway, so a trailing starts_at column would serve no query and only cost write amplification.
-- (The name keeps its original _starts_at suffix; renaming an index adds churn for no behavioural gain.)
--
-- Existing guest-phone indexes do not serve it: idx_bookings_guest_phone_open (V199) is partial on
-- guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL and is sized for the retention / erase-by-phone
-- paths; idx_bookings_staff_walkin_phone (V138) covers STAFF only.
--
-- Partial: registered-client rows (client_id set) and rows with no phone (anonymised, GST-51) never sit in it.
-- No hash column (B507 D1): guest_phone is the raw E.164 already stored for the guest.
--
-- Plain CREATE INDEX, matching every prior bookings index migration (V166, V199): Flyway runs each migration inside a
-- transaction and CREATE INDEX CONCURRENTLY cannot run inside one, so CONCURRENTLY here would be an unestablished
-- one-off. The table is small today.
CREATE INDEX idx_bookings_guest_phone_starts_at
    ON bookings (guest_phone)
    WHERE client_id IS NULL AND guest_phone IS NOT NULL;
