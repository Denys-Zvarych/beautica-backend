package com.beautica.client.provider.service;

import com.beautica.client.provider.dto.ClientKind;
import com.beautica.common.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * B508 (D2, GST-D14) — the single seam deciding the client phone on the card. Audience =
 * {@link AuthorizationService#isGuestPhoneAudience()} (independent master, salon owner, assigned salon admin; never a
 * SALON_MASTER) — the same gate {@code BookingService.getBooking} uses.
 *
 * <p>GUEST: the guest's own phone. REGISTERED: always {@code null} until B518 flips this branch (a registered phone may
 * be shown only after the W042 policy bump); {@code users.phone_number} is deliberately not read here.
 */
@Component
@RequiredArgsConstructor
public class ClientPhonePolicy {

    private final AuthorizationService authz;

    /**
     * @param guestPhone  the guest's raw E.164 (null for a registered client)
     * @param registeredUserId the registered client's id (unused until B518)
     */
    @Nullable
    public String phoneFor(ClientKind kind, @Nullable String guestPhone, @Nullable UUID registeredUserId) {
        if (kind != ClientKind.GUEST || guestPhone == null) {
            return null;
        }
        return authz.isGuestPhoneAudience() ? guestPhone : null;
    }
}
