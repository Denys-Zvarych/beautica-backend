package com.beautica.common.exception;

import com.beautica.service.entity.PriceType;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown by {@code ServiceCatalogService}'s Phase 302 SALON reuse branch when a bulk item's price
 * SHAPE cannot be represented against the salon definition it would reuse.
 *
 * <h2>Why this exists</h2>
 * A salon-bound master's services are SALON-owned (Phase 302 D1), and V121's
 * {@code ux_service_def_owner_service_type_active} allows exactly one ACTIVE definition per
 * {@code (owner_type, owner_id, service_type_id)}. A second master toggling a type the salon
 * already offers therefore REUSES that definition and carries their own divergence on
 * {@code master_services} (D3). But {@code master_services} has only a {@code price_override}
 * (a floor) and a {@code duration_override_minutes} — <b>no per-master price ceiling and no
 * per-master price type</b>. So a submitted shape that disagrees with the reused definition has
 * nowhere to live:
 * <ul>
 *   <li>{@code FIXED 500} definition + {@code RANGE 400–900} item → the band's ceiling is
 *       unrepresentable; the master would silently become {@code FIXED 400}.</li>
 *   <li>{@code RANGE 400–900} definition + {@code FIXED 600} item → the master would render
 *       {@code 600–900}, advertising a ceiling nobody set for them.</li>
 *   <li>{@code RANGE 400–900} definition + {@code RANGE 500–800} item → the submitted ceiling
 *       800 is discarded; the master would render {@code 500–900}.</li>
 * </ul>
 * Each of those returns {@code 201} with a body that does not match the request and ships a wrong
 * client-facing price. <b>D3 decides only that price/duration VALUES go to the overrides — it says
 * nothing about shape</b>, so this is an undecided case, not an accepted trade-off. The endpoint
 * rejects rather than silently reshaping.
 *
 * <p>What still ACCEPTS: a FIXED item against a FIXED definition (any amount — the floor rides on
 * {@code price_override}) and a RANGE item against a RANGE definition whose ceiling matches (the
 * floor rides on {@code price_override} exactly as before). Only genuinely unrepresentable shapes
 * are refused.
 *
 * <p>Surfaces as a {@code 400 Bad Request} carrying the stable error code
 * {@link #ERROR_CODE} under {@code data.code}, alongside the salon's GOVERNING shape, so the
 * mobile setup screen can tell the owner why their row was refused and what band the salon
 * actually offers — {@code handleBusiness} genericises every {@code BAD_REQUEST} body to
 * "Invalid request" with no payload, which would give the screen nothing to act on. Same
 * mechanism as {@link DuplicateServiceException}: typed {@link BusinessException} subclass +
 * a {@code *Response} record carrying {@code code} + a dedicated {@code @ExceptionHandler}.
 *
 * <p>Like the other flow-control exceptions translated directly to an HTTP response,
 * stack-trace capture is suppressed — this is expected user input, not a fault.
 *
 * <p><b>Breaks no existing caller:</b> the reuse branch is new in Phase 302 and has never
 * shipped, so no client has ever seen the reshaped {@code 201}.
 */
public class ServicePriceShapeMismatchException extends BusinessException {

    /**
     * Stable error code echoed in the response body under {@code data.code}. The mobile client
     * maps it to the localised Ukrainian copy; the API never ships natural-language messages
     * for client routing.
     */
    public static final String ERROR_CODE = "SERVICE_PRICE_SHAPE_MISMATCH";

    /**
     * Display name of the service whose shape clashed — the service TYPE's Ukrainian name, the
     * same label the menu displays. Safe to echo: this path is behind the salon-management
     * ownership gate, so it names the caller's OWN salon menu.
     */
    private final String serviceName;

    /** Id of the salon definition that governs the shape, for a deep-link to the salon's row. */
    private final UUID existingServiceDefId;

    /** The salon definition's pricing mode — the shape the item had to match. */
    private final PriceType salonPriceType;

    /** The salon definition's {@code base_price} (canonical floor in both modes). */
    private final BigDecimal salonPriceMin;

    /** The salon definition's {@code price_max}; {@code null} when the salon prices FIXED. */
    private final BigDecimal salonPriceMax;

    public ServicePriceShapeMismatchException(
            String serviceName,
            UUID existingServiceDefId,
            PriceType salonPriceType,
            BigDecimal salonPriceMin,
            BigDecimal salonPriceMax) {
        super(HttpStatus.BAD_REQUEST,
                "Item price shape does not match the salon's existing service definition");
        this.serviceName = serviceName;
        this.existingServiceDefId = existingServiceDefId;
        this.salonPriceType = salonPriceType;
        this.salonPriceMin = salonPriceMin;
        this.salonPriceMax = salonPriceMax;
    }

    public String getServiceName() {
        return serviceName;
    }

    public UUID getExistingServiceDefId() {
        return existingServiceDefId;
    }

    public PriceType getSalonPriceType() {
        return salonPriceType;
    }

    public BigDecimal getSalonPriceMin() {
        return salonPriceMin;
    }

    public BigDecimal getSalonPriceMax() {
        return salonPriceMax;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
