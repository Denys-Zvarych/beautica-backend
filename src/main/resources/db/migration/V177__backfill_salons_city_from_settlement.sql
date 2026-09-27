-- V177: re-derive the legacy salons.city label from the settlement taxonomy
--
-- salons.city is a Phase-3-era free-text column. Since Phase 10.6 SalonService
-- wrote only salons.city_id, yet SalonResponse.city / PublicSalonResponse.city
-- still read salons.city verbatim — and the mobile address screen (Phase 346)
-- seeds «Населений пункт» from it. Two broken shapes resulted:
--   * a salon created after Phase 10.6 carries city = NULL (empty settlement);
--   * an older salon carries its PREVIOUS free-text city next to a newer city_id.
--
-- SalonService now writes city = cities.name_uk whenever it writes city_id
-- (mirroring UserService#writeCityDisplayStrings for users.city). This migration
-- brings every existing row into the same state.
--
-- Set-based and idempotent: one UPDATE ... FROM, and the IS DISTINCT FROM guard
-- makes a second application touch zero rows (and skips rows already correct, so
-- no needless tuple/index churn on idx_salons_city_region). salons.city_id is
-- NOT NULL (V151) and FKs cities, so every salon row has exactly one match.
-- cities.name_uk (VARCHAR 255) fits salons.city (VARCHAR 100): the longest
-- imported settlement name is 26 characters.
--
-- updated_at is deliberately NOT bumped: this is a derived-label repair, not an
-- owner edit.

UPDATE salons s
   SET city = c.name_uk
  FROM cities c
 WHERE c.id = s.city_id
   AND s.city IS DISTINCT FROM c.name_uk;
