package com.beautica.client.dto;

import java.util.List;

/**
 * Auto-derived BEAUTI PASSPORT for the signed-in client (Phase 19.5).
 *
 * <p>Read-only aggregation over the client's <b>COMPLETED</b> bookings only; there is
 * no preferences entity and nothing here is user-editable. Empty-state contract:
 * when {@code bookingsConsidered == 0} the lists are empty and {@code budget} is
 * {@code null} (the mobile client renders the empty passport).
 *
 * <p>{@code favoriteDistricts} carries district display labels resolved exclusively
 * from the joined (pre-filtered) taxonomy — never a hardcoded or reconstructed name —
 * so no occupied-territory label can appear (occupied-territory data ban).
 *
 * @param favoriteProcedures top-3 most-booked service-type names (by COMPLETED count)
 * @param favoriteDistricts  top-3 most-visited district labels (by COMPLETED count)
 * @param budget             UAH spend band, or {@code null} in the empty state
 * @param bookingsConsidered count of COMPLETED bookings the passport was derived from
 */
public record PassportResponse(
        List<String> favoriteProcedures,
        List<String> favoriteDistricts,
        BudgetBand budget,
        int bookingsConsidered
) {
}
