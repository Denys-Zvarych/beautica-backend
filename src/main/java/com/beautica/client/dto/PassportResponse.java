package com.beautica.client.dto;

import java.util.List;

/**
 * Auto-derived BEAUTY PASSPORT for the signed-in client (Phase 19.5; extended by
 * Phases 244/245 — track 31 redesign).
 *
 * <p>Read-only aggregation; there is no preferences entity and nothing here is
 * user-editable. Two groups of fields with <b>different</b> empty-state contracts:
 *
 * <ul>
 *   <li><b>Derived from COMPLETED bookings</b> — {@code favoriteProcedures},
 *       {@code favoriteDistricts}, {@code favoriteCities}, {@code budget},
 *       {@code bookingsConsidered}. When {@code bookingsConsidered == 0} the three lists
 *       are empty and {@code budget} is {@code null} (never a band of nulls); the mobile
 *       client renders the «Паспорт — без історії» state.</li>
 *   <li><b>Identity standing</b> — {@code reviewsWritten} and {@code memberSinceYear}.
 *       These are <b>always</b> populated, including in the empty state: a brand-new client
 *       has no COMPLETED bookings but still has a real registration year and may already
 *       have written reviews. Gating them behind {@code bookingsConsidered > 0} would make
 *       the identity strip render a zero and force the client to fabricate a year — the one
 *       thing this page's design rule forbids.</li>
 * </ul>
 *
 * <p>{@code favoriteDistricts} and {@code favoriteCities} carry display labels resolved
 * exclusively from the joined (pre-filtered) taxonomy — never a hardcoded or reconstructed
 * name — so no occupied-territory label can appear (occupied-territory data ban). An id
 * whose label does not resolve is dropped, never surfaced as a raw UUID.
 *
 * @param favoriteProcedures top-3 most-booked service-type names (by COMPLETED count)
 * @param favoriteDistricts  top-3 most-visited district labels (by COMPLETED count);
 *                           never null, rank-ordered most-frequent first
 * @param favoriteCities     top-3 most-visited city labels (by COMPLETED count);
 *                           never null, rank-ordered most-frequent first
 * @param budget             UAH spend band, or {@code null} in the empty state
 * @param bookingsConsidered count of COMPLETED bookings the passport was derived from
 * @param reviewsWritten     number of reviews this client has authored. Named
 *                           {@code reviewsWritten}, not {@code reviewsLeft}: the Ukrainian
 *                           «залишено» means "written / left behind", whereas English
 *                           "reviews left" reads as "reviews remaining to write" — the exact
 *                           opposite meaning.
 * @param memberSinceYear    calendar year the client registered, derived from
 *                           {@code users.created_at} in {@code Europe/Kyiv}. Non-null:
 *                           {@code created_at} is NOT NULL, so the mobile mapper needs no
 *                           fallback and must never substitute the current year.
 */
public record PassportResponse(
        List<String> favoriteProcedures,
        List<String> favoriteDistricts,
        List<String> favoriteCities,
        BudgetBand budget,
        int bookingsConsidered,
        int reviewsWritten,
        int memberSinceYear
) {
}
