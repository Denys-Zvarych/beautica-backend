package com.beautica.client.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One-query projection backing {@code GET /clients/me/timeline} (Phase 19.5).
 *
 * <p>Projects scalar columns only (no JOIN FETCH on a collection), so Hibernate emits a
 * correct SQL {@code LIMIT/OFFSET} for the {@link org.springframework.data.domain.Pageable}
 * and the booking package's HHH90003004 in-memory-pagination trap does not apply. The
 * service derives the {@code LocalDate} (in {@code Europe/Kyiv}) and the {@code categoryKey}
 * slug from these fields in-memory — no per-row query (no N+1).
 *
 * @param bookingId    source booking id
 * @param category     service category string (nullable), e.g. {@code "MANICURE"}
 * @param startsAt     booking start instant (converted to a Kyiv {@code LocalDate} by the service)
 * @param masterId     the master who performed the service
 * @param serviceName  booked service definition name
 */
public record TimelineItemProjection(
        UUID bookingId,
        String category,
        OffsetDateTime startsAt,
        UUID masterId,
        String serviceName
) {
}
