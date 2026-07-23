package com.beautica.booking.dto;

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
 * <ul>
 *   <li>{@code priceOverride} set on the assignment (any {@code priceType}) → the master fixed
 *       their own price; {@code priceAtBooking} already equals the override — single price, no
 *       ceiling.</li>
 *   <li>{@code priceType == FIXED} → single price, no ceiling.</li>
 *   <li>{@code priceType == RANGE} with no override → the master genuinely left it a range;
 *       {@code priceAtBooking} is already the floor ({@code basePrice}) and the ceiling is
 *       {@code serviceDefinition.priceMax}.</li>
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
 * <p><b>Floor {@code <=} ceiling is structural, not defensively clamped.</b> A ceiling is only
 * ever produced when {@code priceOverride} is null, so the frozen floor is necessarily
 * {@code basePrice} — and {@code chk_service_def_price_mode} (V67) already guarantees
 * {@code price_max >= base_price} for every {@code RANGE} row. No runtime guard is needed or
 * wanted; adding one would only mask a broken constraint. (The V119 backfill of pre-existing rows
 * cannot lean on that argument, since their service may have been edited since — it carries its
 * own explicit guard instead.)
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
     */
    public static BigDecimal resolveCeiling(MasterServiceAssignment masterService) {
        return resolveCeiling(
                masterService.getPriceOverride(),
                masterService.getServiceDefinition().getPriceType(),
                masterService.getServiceDefinition().getPriceMax());
    }

    private static BigDecimal resolveCeiling(BigDecimal priceOverride, PriceType priceType, BigDecimal priceMax) {
        if (priceOverride != null || priceType != PriceType.RANGE) {
            return null;
        }
        return priceMax;
    }
}
