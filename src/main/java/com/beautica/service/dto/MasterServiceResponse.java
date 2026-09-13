package com.beautica.service.dto;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO for a master's service assignment ({@link MasterServiceAssignment}).
 *
 * <h2>Pricing semantics</h2>
 * <ul>
 *   <li>{@code effectivePrice} — single numeric floor for booking use:
 *       {@code COALESCE(priceOverride, base_price)}. Identical semantics to pre-V67;
 *       {@code masters.min_effective_price} (V58) and booking logic remain unchanged.</li>
 *   <li>{@code priceType}, {@code priceMin}, {@code priceMax}, {@code priceDisplay} —
 *       surfaced from the nested {@link ServiceDefinitionResponse} for display purposes.</li>
 * </ul>
 *
 * <h2>Service type</h2>
 * {@code serviceTypeId} / {@code serviceTypeNameUk} are lifted from the nested
 * {@link ServiceDefinitionResponse} for client convenience; both are {@code null} when no
 * service type was chosen on the create path (the picker is optional).
 */
public record MasterServiceResponse(
        UUID id,
        UUID masterId,
        ServiceDefinitionResponse serviceDefinition,
        BigDecimal priceOverride,
        Integer durationOverrideMinutes,
        /** Single numeric floor: {@code COALESCE(priceOverride, base_price)}. */
        BigDecimal effectivePrice,
        int effectiveDurationMinutes,
        boolean isActive,
        /** Pricing mode surfaced from the service definition. */
        PriceType priceType,
        /** Canonical floor (base_price) surfaced from the service definition. */
        BigDecimal priceMin,
        /** RANGE ceiling surfaced from the service definition; null for FIXED. */
        BigDecimal priceMax,
        /** Pre-formatted display string, e.g. {@code "500 ₴"} or {@code "від 500 до 800 ₴"}. */
        String priceDisplay,
        /**
         * Chosen service type id, lifted from the nested {@link ServiceDefinitionResponse}.
         * {@code null} when no service type was chosen (the picker is optional).
         */
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "Chosen service type id; null when no service type was selected.")
        UUID serviceTypeId,
        /**
         * Ukrainian display name of the chosen service type, lifted from the nested
         * {@link ServiceDefinitionResponse}. {@code null} when no service type was chosen.
         */
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Ukrainian display name of the chosen service type; null when none was selected.")
        String serviceTypeNameUk,
        /**
         * Stable slug of the chosen service type, lifted from the nested
         * {@link ServiceDefinitionResponse}. Matches the platform search filter's service-type
         * key ({@code CategoryServiceOption.key}) so the client can pre-check the searched
         * service in the booking flow. {@code null} when no service type was chosen.
         */
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Stable slug of the chosen platform service type (matches the "
                        + "search filter's service-type key); null when none was selected.")
        String serviceTypeSlug,
        /**
         * Whether the authenticated CLIENT caller has this row in their wish list
         * ({@code favorites}, {@link com.beautica.favorite.entity.FavoriteTargetType#SERVICE}).
         *
         * <p><b>{@code null} means "not applicable", never "not favourited".</b> It is
         * {@code null} for an anonymous caller, a non-CLIENT caller, and — most importantly —
         * every instance held in the {@code masterServices} cache (Phase 32.1 D1/D4): that cache
         * is keyed by {@code masterId} alone and shared across every caller including anonymous
         * guests, so this field must never be populated inside
         * {@code ServiceCatalogService.getMasterServices}. It is decorated per-request, AFTER the
         * cache read, by {@code MasterServiceFavoriteDecorator} — a separate bean the controller
         * composes in, never a method the cached service self-invokes. {@code false} is reserved
         * for "this CLIENT genuinely has not favourited this row", so any {@code true}/{@code false}
         * found inside the cache is unambiguously a leak (see {@code ServiceCatalogFavoriteCacheIT}).
         */
        @Schema(types = {"boolean", "null"}, nullable = true,
                description = "true/false only for an authenticated CLIENT caller; null for "
                        + "anonymous/non-CLIENT callers and always null inside the masterServices "
                        + "cache — decorated per-request, after the cache read.")
        Boolean isFavorite
) {
    public static MasterServiceResponse from(MasterServiceAssignment msa) {
        // ServiceDefinitionResponse.from already runs ServicePricing.ofDefinition internally, so
        // sdResponse carries the SHARED DEFINITION's band — used here only for the nested
        // serviceDefinition object and the service-type fields, never for this response's own
        // top-level priceType/priceMin/priceMax/priceDisplay (see below).
        var sdResponse = ServiceDefinitionResponse.from(msa.getServiceDefinition());

        // Phase 311 D9 — REVERSES the pre-311 perf shortcut this comment used to document. Before
        // V165, master_services had no per-master ceiling or shape, so ofAssignment's band was
        // wholly the definition's band by construction — lifting it from sdResponse instead of
        // re-deriving it via ServicePricing.ofAssignment was a pure perf win (2026-08 perf audit
        // F4): it saved a second, discarded PriceDisplayFormatter.format call.
        //
        // That premise died with V165: an assignment holding its OWN band (Phase 311 D2) now
        // legitimately disagrees with the definition's band, and sdResponse's band is the
        // definition's — exactly the value that would render a STALE price here. ofAssignment is
        // no longer a duplicate computation; it is the only correct one. Money and duration are
        // still derived in exactly one place (ServicePricing, Phase 31.4 D2), so this menu DTO and
        // the wish list (FavoriteServiceResponse, which already called ofAssignment) can never
        // print different prices for the same assignment.
        ServicePricing pricing = ServicePricing.ofAssignment(msa);

        return new MasterServiceResponse(
                msa.getId(),
                msa.getMaster().getId(),
                sdResponse,
                msa.getPriceOverride(),
                msa.getDurationOverrideMinutes(),
                pricing.effectivePrice(),
                pricing.effectiveDurationMinutes(),
                msa.isActive(),
                pricing.priceType(),
                pricing.priceMin(),
                pricing.priceMax(),
                pricing.priceDisplay(),
                sdResponse.serviceTypeId(),
                sdResponse.serviceTypeNameUk(),
                sdResponse.serviceTypeSlug(),
                null    // isFavorite — decorated per-request, outside this factory (Phase 32.1)
        );
    }

    /**
     * Returns a new instance with {@code isFavorite} set — the ONLY way to populate the field,
     * since a record has no setter. Used exclusively by {@code MasterServiceFavoriteDecorator},
     * never by {@code ServiceCatalogService} (Phase 32.1 D2.1): the cached method must never call
     * this, or the cache would start holding client-specific data.
     */
    public MasterServiceResponse withIsFavorite(boolean favorite) {
        return new MasterServiceResponse(
                id, masterId, serviceDefinition, priceOverride, durationOverrideMinutes,
                effectivePrice, effectiveDurationMinutes, isActive, priceType, priceMin,
                priceMax, priceDisplay, serviceTypeId, serviceTypeNameUk, serviceTypeSlug,
                favorite);
    }

    /**
     * <b>RETIRED (2026-09-13 audit, S5): there is no masked public variant any more.</b>
     *
     * <p>{@code fromPublic} used to null {@code priceOverride} on the {@code permitAll} browse
     * route ({@code GET /masters/&#123;masterId&#125;/services}), calling it "commercially
     * sensitive". <b>The control did not control.</b> The same response kept {@code effectivePrice}
     * — {@code COALESCE(priceOverride, base_price)} — and the nested
     * {@link ServiceDefinitionResponse#priceMin()}, which IS {@code base_price}. Any anonymous
     * caller recovered the masked value, and its deviation from the salon's list price, by
     * subtraction. Phase 311 widened the leak further: {@code priceType} and {@code priceMax} now
     * carry the master's OWN band on the same public route.
     *
     * <p>The contradiction is resolved in the direction the product already settled: a salon's
     * catalogue prices, per master, ARE public — that is what the discovery and booking flows
     * render, and the salon-search price band
     * ({@code SalonSearchSql}'s {@code pr} lateral) publishes the same numbers to unauthenticated
     * callers anyway. So the ineffective mask and its confidentiality claim are gone rather than
     * kept as a guarantee that does not hold. Fields the mobile client consumes are all retained.
     *
     * <p><b>Do not re-add a partial mask here.</b> A mask on this DTO is only meaningful if it
     * also removes {@code effectivePrice} and the nested definition band, which the client needs;
     * "hide one derivable field" is theatre, and shipping it as a security control is worse than
     * shipping neither. If public price visibility ever becomes a product question, it is a
     * question about the ROUTE, not about one field on this record.
     */
}
