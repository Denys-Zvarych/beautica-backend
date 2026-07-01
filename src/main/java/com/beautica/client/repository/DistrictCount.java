package com.beautica.client.repository;

import java.util.UUID;

/**
 * A district FK id and the client's COMPLETED-booking count there (Phase 19.5).
 *
 * <p>Carries the <b>FK id only</b> — never a label. JPQL cannot resolve the taxonomy
 * {@code name_uk}; the service batch-resolves the id to a display label through the
 * {@code DiscoveryLocationResolver} M2 seam (occupied-territory ban: labels come only
 * from the joined, pre-filtered taxonomy, never a literal). The id is the
 * district-primary discovery district (salon link wins via {@code COALESCE}, else the
 * master's own user row), mirroring the 19.3 booking-detail rule.
 *
 * @param districtId the discovery district FK id (never null — null ids are filtered out in SQL)
 * @param count      number of COMPLETED bookings in that district
 */
public record DistrictCount(
        UUID districtId,
        long count
) {
}
