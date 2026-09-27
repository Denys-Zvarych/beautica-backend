package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO for a {@link ServiceDefinition}.
 *
 * <h2>Pricing fields</h2>
 * <ul>
 *   <li>{@code priceType} — the pricing mode ({@code FIXED} or {@code RANGE}). On every
 *       provider-side response ({@link #from}) this is the definition's own shape. On
 *       {@code GET /salons/{salonId}/services} ({@link #fromSalonAggregate}, Phase 314) it is
 *       instead a COMPUTED display shape — the union hull across the salon's bookable masters —
 *       and is not read from {@code service_definitions.price_type}.</li>
 *   <li>{@code priceMin} — the canonical floor ({@code base_price}) for both modes on
 *       {@link #from}; the lowest floor across bookable masters on {@link #fromSalonAggregate}.</li>
 *   <li>{@code priceMax} — the ceiling for RANGE, {@code null} for FIXED, on {@link #from}; the
 *       highest ceiling across bookable masters on {@link #fromSalonAggregate}, {@code null} when
 *       every bookable master's floor and ceiling coincide.</li>
 *   <li>{@code priceDisplay} — pre-formatted display string, e.g. {@code "500 ₴"} or
 *       {@code "від 500 до 800 ₴"}. {@code null} when {@code priceMin} is {@code null}.</li>
 * </ul>
 */
public record ServiceDefinitionResponse(
        UUID id,
        String name,
        String description,
        String category,
        int baseDurationMinutes,
        int bufferMinutesAfter,
        boolean isActive,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "Chosen service type id; null when no service type was selected.")
        UUID serviceTypeId,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Ukrainian display name of the chosen service type; null when none was selected.")
        String serviceTypeNameUk,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Stable slug of the chosen platform service type (matches "
                        + "CategoryServiceOption.key on the client); null when none was selected.")
        String serviceTypeSlug,
        String photoUrl,
        PriceType priceType,
        BigDecimal priceMin,
        BigDecimal priceMax,
        String priceDisplay,
        /**
         * Whether the authenticated CLIENT caller has this SALON service in their wish list
         * ({@code favorites}, {@code FavoriteTargetType.SALON_SERVICE}) — decorated on
         * {@code GET /salons/{salonId}/services} only (salon-service-favourites track).
         *
         * <p><b>{@code null} means "not applicable", never "not favourited"</b> — the same
         * contract {@code MasterServiceResponse.isFavorite} carries. It is {@code null} for an
         * anonymous caller, a non-CLIENT caller, and every provider-side service-management
         * response this DTO is ALSO used for ({@code POST}/{@code PATCH /services/...}), since
         * favouriting is a CLIENT-only concept there. It is {@code null} inside the
         * {@code salon-service-catalog} cache (keyed by {@code salonId} alone, shared across
         * every caller including anonymous guests): this field is decorated per-request, AFTER
         * the cache read, by {@code SalonServiceFavoriteDecorator} — a separate bean the
         * controller composes in, lexically OUTSIDE the {@code @Cacheable}
         * {@code ServiceCatalogService.getSalonServiceCatalog} call, never a method that cached
         * service self-invokes. {@code false} is reserved for "this CLIENT genuinely has not
         * favourited this row", so any {@code true}/{@code false} found inside the cache is
         * unambiguously a leak (see {@code SalonCatalogueFavoriteDecorationIT}).
         */
        @Schema(types = {"boolean", "null"}, nullable = true,
                description = "true/false only for an authenticated CLIENT caller on "
                        + "GET /salons/{salonId}/services; null everywhere else (anonymous/"
                        + "non-CLIENT callers, every provider-side service-management response, "
                        + "and always null inside the salon-service-catalog cache) — decorated "
                        + "per-request, after the cache read.")
        Boolean isFavorite
) {
    public static ServiceDefinitionResponse from(ServiceDefinition sd) {
        // Money is derived in exactly one place (Phase 31.4 D2) so that this DTO, the
        // master's service menu and the BEAUTY WISH LIST can never print different prices
        // for the same service. Behaviour is identical to the previous inline derivation.
        ServicePricing pricing = ServicePricing.ofDefinition(sd);

        return build(sd, pricing.priceType(), pricing.priceMin(), pricing.priceMax(), pricing.priceDisplay());
    }

    /**
     * Phase 314 — the salon-catalogue projection: the band is the union hull across the salon's
     * bookable masters ({@link ServicePricing#hullOfAssignments}), never the
     * {@link ServiceDefinition}'s own band. {@link #from} keeps serving every provider-side
     * response ({@code POST}/{@code PATCH /services/...}), where the price fields must keep
     * meaning the definition's own band — that is what an owner is editing.
     *
     * <p>{@code hull.priceMax() == null} means the hull collapsed to a single price (every
     * contributing master resolves to the same floor and ceiling) and renders
     * {@link PriceType#FIXED} — the COMMON case, not a degenerate range. This factory only
     * formats; the hull itself (including the FIXED/RANGE decision) is computed in exactly one
     * place, so {@code GET /salons/&#123;salonId&#125;/services} and the wish list's SALON arm
     * cannot print different numbers for the same service. It never reads
     * {@code sd.getPriceType()}/{@code sd.getBasePrice()}/{@code sd.getPriceMax()}.
     */
    public static ServiceDefinitionResponse fromSalonAggregate(
            ServiceDefinition sd, ServicePricing.Hull hull) {
        return build(sd, hull.priceType(), hull.priceMin(), hull.priceMax(),
                ServicePricing.display(hull.priceType(), hull.priceMin(), hull.priceMax()));
    }

    private static ServiceDefinitionResponse build(ServiceDefinition sd, PriceType priceType,
            BigDecimal priceMin, BigDecimal priceMax, String priceDisplay) {
        return new ServiceDefinitionResponse(
                sd.getId(),
                sd.getName(),
                sd.getDescription(),
                sd.getCategory(),
                sd.getBaseDurationMinutes(),
                sd.getBufferMinutesAfter(),
                sd.isActive(),
                sd.getServiceType() != null ? sd.getServiceType().getId() : null,
                sd.getServiceType() != null ? sd.getServiceType().getNameUk() : null,
                sd.getServiceType() != null ? sd.getServiceType().getSlug() : null,
                sd.getPhotoUrl(),
                priceType,
                priceMin,
                priceMax,
                priceDisplay,
                null    // isFavorite — decorated per-request, outside this factory (salon-service-favourites track)
        );
    }

    /**
     * Returns a new instance with {@code isFavorite} set — the ONLY way to populate the field,
     * since a record has no setter. Used exclusively by {@code SalonServiceFavoriteDecorator},
     * never by {@code ServiceCatalogService} (mirrors {@code MasterServiceResponse
     * #withIsFavorite}): the cached {@code getSalonServiceCatalog} method must never call this,
     * or the cache would start holding client-specific data.
     */
    public ServiceDefinitionResponse withIsFavorite(boolean favorite) {
        return new ServiceDefinitionResponse(
                id, name, description, category, baseDurationMinutes, bufferMinutesAfter,
                isActive, serviceTypeId, serviceTypeNameUk, serviceTypeSlug, photoUrl,
                priceType, priceMin, priceMax, priceDisplay, favorite);
    }
}
