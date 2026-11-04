package com.beautica.booking.domain;

import com.beautica.booking.entity.Booking;
import com.beautica.common.util.Placeholders;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;

/**
 * Phase 435 — where a guest's visit happens, as shown to an account-less guest (reminder SMS, public cancel page).
 *
 * <p>The SALON's address for a salon booking, else the master's own address (an independent master; a detached
 * master has no user row → none). Street + building number («Хрещатик 10»); a salon with no street falls back to its
 * legacy free-text {@code address}. Provider text, so every control character / line break is flattened to one
 * space — it must never forge an extra line in a Beautica-branded SMS.
 *
 * <p>Callers must run inside the transaction that loaded {@code booking} with {@code master.user} and {@code salon}
 * fetched (the guest-facing graphs do), so no extra statement is issued.
 */
public final class GuestVisitAddress {

    private GuestVisitAddress() {
    }

    /** The visit's address, or {@code null} when there is nothing to show. */
    public static String of(Booking booking) {
        Salon salon = booking.getSalon();
        if (salon != null) {
            return streetAddress(salon.getStreet(), salon.getBuildingNo(), salon.getAddress());
        }
        User user = booking.getMaster().getUser();
        return user == null ? null : streetAddress(user.getStreet(), user.getBuildingNo(), null);
    }

    private static String streetAddress(String street, String buildingNo, String legacy) {
        String flatStreet = Placeholders.singleLine(street);
        if (flatStreet.isEmpty()) {
            String flatLegacy = Placeholders.singleLine(legacy);
            return flatLegacy.isEmpty() ? null : flatLegacy;
        }
        String flatBuilding = Placeholders.singleLine(buildingNo);
        return flatBuilding.isEmpty() ? flatStreet : flatStreet + " " + flatBuilding;
    }
}
