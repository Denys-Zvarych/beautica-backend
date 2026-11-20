package com.beautica.client.provider.service;

import com.beautica.common.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * B508 (D2, GST-D14) + B518 — the single seam deciding the client phone on the card. Audience =
 * {@link AuthorizationService#isGuestPhoneAudience()} (independent master, salon owner, assigned salon admin; never a
 * SALON_MASTER) — the same gate {@code BookingService.getBooking} uses.
 *
 * <p>GUEST: the guest's own phone. REGISTERED (B518, GST-80): the account's phone only when it is SMS-verified — the
 * caller passes {@code users.phone_number} gated by {@code phone_verified_at IS NOT NULL} (the SQL returns NULL for an
 * unverified number), so an unverified number never reaches this class.
 * The verified-only gate lives in the SQL ({@code ClientCardSql}), not here.
 */
@Component
@RequiredArgsConstructor
public class ClientPhonePolicy {

    private final AuthorizationService authz;

    /**
     * @param phone the guest's raw E.164 for a GUEST; for a REGISTERED client the VERIFIED phone (null when the
     *              account has none or it is unverified)
     */
    @Nullable
    public String phoneFor(@Nullable String phone) {
        if (phone == null) {
            return null;
        }
        return authz.isGuestPhoneAudience() ? phone : null;
    }
}
