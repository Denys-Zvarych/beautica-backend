package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phases 392/393 — resolves the {@code (salonSlug, masterId)} pair of the public SALON guest routes
 * ({@code /api/v1/book/s/{slug}/masters/{masterId}/…}) to a bookable master. Keyed by the salon's public slug
 * and the master's id, never by a master booking slug: salon masters have no personal share link (locked rule),
 * so the salon flow must reach every bookable master of the salon, slug or not.
 *
 * <p>The visibility rule (active master with a user, in the active salon behind {@code salonSlug}) lives in the
 * two {@code MasterRepository#find…BySalonSlug…} finders and is not re-checked here. Whether the master performs
 * the requested services is the slot core's own master-scoped check (unknown / foreign / inactive service →
 * the same 404), so a service of another master never resolves either.
 *
 * <p><b>Miss path (only ever reached after the live lookup missed, so a valid request pays nothing extra).</b>
 * A salon slug that is no longer live and is a retired SALON link → 410 ({@link RetiredBookingSlugGuard});
 * every other miss — unknown slug, closed salon, master of another salon, inactive or unknown master — is the
 * same {@code Booking page not found} 404, so the routes are no master-id oracle across salons.
 */
@Component
@RequiredArgsConstructor
public class SalonGuestMasterResolver {

    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final RetiredBookingSlugGuard retiredBookingSlugGuard;

    /** The bookable master's id — the slot reads need nothing else (one scalar statement). */
    public UUID resolveBookableMasterId(String salonSlug, UUID masterId) {
        return masterRepository.findBookableIdBySalonSlug(masterId, salonSlug)
                .orElseThrow(() -> notFound(salonSlug));
    }

    /** The bookable master with user and salon fetched — the booking create needs the entity. */
    public Master resolveBookableMaster(String salonSlug, UUID masterId) {
        return masterRepository.findBookableBySalonSlugWithUser(masterId, salonSlug)
                .orElseThrow(() -> notFound(salonSlug));
    }

    private NotFoundException notFound(String salonSlug) {
        if (!salonRepository.existsByBookingSlugAndIsActiveTrue(salonSlug)) {
            retiredBookingSlugGuard.throwGoneIfRetired(BookingLinkKind.SALON, salonSlug);
        }
        return new NotFoundException("Booking page not found");
    }
}
