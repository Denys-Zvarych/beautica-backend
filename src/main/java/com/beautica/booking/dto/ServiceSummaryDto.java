package com.beautica.booking.dto;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Map;
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
        BigDecimal priceFrom,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The service's category key — the same value as ServiceDefinitionResponse.category "
                        + "and CategoryRef.key; the website groups services by it (phase 383).")
        String category,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Display name of that category, including a category the operator has since HIDDEN "
                        + "(SVC-56). Null for an unknown / rejected category; fall back to category then.")
        String categoryDisplayName
) {

    /** B504 (track D18) — the pre-{@code categoryDisplayName} arity; existing construction sites compile unchanged. */
    public ServiceSummaryDto(UUID id, String name, int durationMinutes, BigDecimal priceFrom, String category) {
        this(id, name, durationMinutes, priceFrom, category, null);
    }

    /** B504 (track D18) — {@link #from(MasterServiceAssignment)} plus the label from a once-per-request map. */
    public static ServiceSummaryDto from(MasterServiceAssignment assignment, Map<String, String> displayNamesByCategory) {
        ServiceSummaryDto base = from(assignment);
        return new ServiceSummaryDto(base.id(), base.name(), base.durationMinutes(), base.priceFrom(), base.category(),
                base.category() == null ? null : displayNamesByCategory.get(base.category()));
    }

    public static ServiceSummaryDto from(MasterServiceAssignment assignment) {
        ServiceDefinition def = assignment.getServiceDefinition();
        int duration = assignment.getDurationOverrideMinutes() != null
                ? assignment.getDurationOverrideMinutes()
                : def.getBaseDurationMinutes();
        BigDecimal price = assignment.getPriceOverride() != null
                ? assignment.getPriceOverride()
                : def.getBasePrice();
        return new ServiceSummaryDto(assignment.getId(), def.getName(), duration, price, def.getCategory());
    }
}
