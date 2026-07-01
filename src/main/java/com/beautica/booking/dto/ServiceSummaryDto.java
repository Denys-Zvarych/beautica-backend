package com.beautica.booking.dto;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public, unauthenticated summary of one bookable service on a master's
 * guest booking page (Phase 13.1).
 *
 * <p>Anti-Bug §I-2: this is a {@code permitAll} response shape, so it carries
 * NO owner UUIDs, owner type, or other internal identifiers — only the
 * service-assignment id (needed by the booking flow as the slot key), name,
 * duration, and the "from" price. {@code priceFrom} is the effective floor:
 * the master's per-assignment override when set, otherwise the definition's
 * base price.
 */
public record ServiceSummaryDto(
        UUID id,
        String name,
        int durationMinutes,
        BigDecimal priceFrom
) {

    public static ServiceSummaryDto from(MasterServiceAssignment assignment) {
        ServiceDefinition def = assignment.getServiceDefinition();
        int duration = assignment.getDurationOverrideMinutes() != null
                ? assignment.getDurationOverrideMinutes()
                : def.getBaseDurationMinutes();
        BigDecimal price = assignment.getPriceOverride() != null
                ? assignment.getPriceOverride()
                : def.getBasePrice();
        return new ServiceSummaryDto(assignment.getId(), def.getName(), duration, price);
    }
}
