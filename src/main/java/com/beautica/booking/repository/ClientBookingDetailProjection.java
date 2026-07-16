package com.beautica.booking.repository;

import com.beautica.auth.Role;
import com.beautica.booking.enums.BookingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Single-query projection backing {@code GET /bookings/me} (Phase 19.3).
 *
 * <p>Carries every field {@link com.beautica.booking.dto.BookingDetailResponse} needs to
 * render a client's booking row, joined in ONE query (no N+1): the client + master-user
 * names, the master's (nullable) professional title, the master avatar/type, the (nullable)
 * salon name, the discovery locality FK ids, the master address, the service name + category,
 * and a {@code reviewExists} flag from a {@code LEFT JOIN Review}.
 *
 * <p>{@code masterProfessionalTitle} reuses the already-joined {@code mu} (master user) alias —
 * no extra join. It is nullable: a master may never have set one.
 *
 * <p>{@code locationNote} is the provider's free-text arrival hint, resolved by the SAME
 * {@code CASE WHEN s.id IS NOT NULL THEN s.locationNote ELSE mu.locationNote END} salon-vs-independent
 * rule as {@code street}/{@code buildingNo} below — salon-presence wins outright, even when the
 * salon's own column is {@code NULL} — reusing the already-joined {@code s}/{@code mu} aliases, no
 * extra join. <b>Do not use {@code COALESCE(s.locationNote, mu.locationNote)} here</b> — {@code
 * COALESCE} falls through to the master's own value whenever the salon's column is {@code NULL},
 * which for a salon-employed master leaks the master's personal data (e.g. their home door code)
 * onto a salon booking. Nullable — most providers never set one.
 *
 * <p><b>Locality is FK ids, not labels.</b> JPQL cannot resolve the taxonomy {@code name_uk}
 * labels — that is the {@code DiscoveryLocationResolver} M2 seam's job (§E: batch-resolved in
 * a fixed two queries per page, never per row). The projection exposes the district-primary
 * discovery {@code cityId}/{@code districtId} (salon link wins via {@code COALESCE}, else the
 * master's own user row — mirroring {@code SearchService}); the service stamps the resolved
 * labels onto the response DTO in-memory.
 *
 * <p>{@code canReview} is NOT stored here: it is derived by the service as
 * {@code status == COMPLETED && !reviewExists}, keeping the truth-table logic in one place.
 *
 * @param createdAt entity audit timestamp ({@code Instant} — matches {@code AuditableEntity})
 */
public record ClientBookingDetailProjection(
        UUID id,
        UUID clientId,
        UUID masterId,
        UUID masterServiceId,
        String serviceName,
        BookingStatus status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        BigDecimal priceAtBooking,
        int durationMinutesAtBooking,
        Instant createdAt,
        String clientFirstName,
        String clientLastName,
        String masterFirstName,
        String masterLastName,
        String masterProfessionalTitle,
        String clientComment,
        String providerComment,
        String clientCancellationNote,
        String masterAvatarUrl,
        Role masterType,
        String salonName,
        UUID discoveryCityId,
        UUID discoveryDistrictId,
        String street,
        String buildingNo,
        String locationNote,
        String categoryName,
        boolean reviewExists
) {
}
