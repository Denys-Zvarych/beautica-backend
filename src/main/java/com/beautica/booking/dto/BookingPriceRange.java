package com.beautica.booking.dto;

import com.beautica.service.dto.ServicePricing;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import java.math.BigDecimal;

/**
 * Computes the (nullable) price ceiling frozen onto a booking, applying the single locked rule
 * shared by both create paths — and mirroring exactly how {@code priceAtBooking} is frozen
 * alongside it ({@code BookingService#doCreateBooking} /
 * {@code GuestBookingService#persistBooking}, both of which freeze
 * {@code priceOverride != null ? priceOverride : basePrice}):
 *
 * <p><b>Phase 311 D9 — reads the RESOLVED band, not the definition's alone.</b> Before Phase 311
 * a master had no per-master ceiling, so {@code priceOverride != null} could only mean "the master
 * fixed a single price" and this class returned {@code null} unconditionally in that case. V165
 * makes a master's own {@code RANGE} band representable ({@code priceTypeOverride == RANGE},
 * {@code priceOverride} the floor, {@code priceMaxOverride} the ceiling) — a booking against such
 * an assignment must freeze THAT ceiling, not collapse to "no ceiling" just because a floor
 * override happens to be present. The rule, restated against the resolved shape/ceiling
 * ({@code shape}/{@code ceiling} per {@code ServicePricing}'s D9 formula):
 *
 * <ul>
 *   <li>Resolved shape {@code == FIXED} (whether Inherited or the master's own) → single price,
 *       no ceiling.</li>
 *   <li>Resolved shape {@code == RANGE}, own band ({@code priceTypeOverride != null}) → the
 *       master's own ceiling, {@code priceMaxOverride}.</li>
 *   <li>Resolved shape {@code == RANGE}, Inherited ({@code priceTypeOverride == null}) → the
 *       master genuinely left it a range; {@code priceAtBooking} is already the floor
 *       ({@code basePrice}) and the ceiling is {@code serviceDefinition.priceMax}.</li>
 * </ul>
 *
 * <p>A {@code null} result means "single price" to the client. Callers on the mobile side must
 * not re-derive {@code priceType}/{@code priceOverride} themselves — the rule is decided here,
 * server-side, once.
 *
 * <p><b>Creation-time only — this is a snapshot, exactly like {@code priceAtBooking}.</b> The
 * result is persisted to {@code bookings.price_max_at_booking} (V119) by the two create paths and
 * is thereafter read straight off the row by {@link BookingResponse#from} /
 * {@link BookingDetailResponse#from} and by the CLIENT projection. <b>Do not call this class from
 * a read path.</b> Deriving the ceiling live — as this code did before V119 — lets a provider
 * retroactively rewrite the band on existing (even {@code COMPLETED}) bookings: a
 * {@code FIXED -> RANGE} flip grows a ceiling onto a booking the client never agreed to, dropping
 * a {@code priceOverride} grows a ceiling whose floor is the frozen override rather than
 * {@code basePrice}, and lowering {@code priceMax} below the frozen {@code priceAtBooking}
 * renders an INVERTED band. Freezing at creation is what makes all three unreachable.
 *
 * <p><b>Floor {@code <=} ceiling is structural, not defensively clamped — Phase 311 extends this
 * to the own-band branch.</b> A ceiling is only ever produced for a resolved {@code RANGE} shape,
 * and the floor it is paired with always comes from the SAME source as that shape (D9's
 * component-wise resolution is degenerate under D2's all-or-nothing invariant): Inherited →
 * floor {@code basePrice}, ceiling {@code serviceDefinition.priceMax}, guaranteed
 * {@code price_max >= base_price} by {@code chk_service_def_price_mode} (V67); own band → floor
 * {@code priceOverride}, ceiling {@code priceMaxOverride}, guaranteed
 * {@code price_max_override >= price_override} by {@code chk_master_service_price_mode} (V165).
 * No runtime guard is needed or wanted in either branch; adding one would only mask a broken
 * constraint. (The V119 backfill of pre-existing rows cannot lean on that argument, since their
 * service may have been edited since — it carries its own explicit guard instead.)
 *
 * <p><b>Defensive.</b> A {@code RANGE} service whose {@code priceMax} is somehow {@code null}
 * (never valid per {@code chk_service_def_price_mode}, but not an invariant this class should
 * trust blindly) resolves to {@code null} rather than freezing a ceiling-less "range".
 */
public final class BookingPriceRange {

    private BookingPriceRange() {
    }

    /**
     * The single public entry point, taking the live assignment the create paths already hold in
     * memory. There is deliberately no component-wise public overload: after V119 no read path
     * has the three inputs to hand (nor any business reason to re-derive), so exposing one would
     * only invite a caller to reintroduce live derivation.
     *
     * <p>Phase 311 D9 — delegates to {@link ServicePricing#ofAssignment} for the resolved shape
     * and ceiling rather than re-implementing the {@code COALESCE} chain here; this class adds
     * only the RANGE-vs-not-RANGE gate and the "ceiling must not be null for a RANGE shape"
     * defensive check.
     */
    public static BigDecimal resolveCeiling(MasterServiceAssignment masterService) {
        ServicePricing pricing = ServicePricing.ofAssignment(masterService);
        if (pricing.priceType() != PriceType.RANGE) {
            return null;
        }
        return pricing.priceMax();
    }
}
