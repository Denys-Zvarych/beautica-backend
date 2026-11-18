package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.common.TimeZones;
import com.beautica.config.AuthConsentProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * B516 (AUTH-45, AUTH-D12) — the one place that knows WHEN an account must have a verified phone. Every role must
 * verify immediately; there is no provider deadline (user 2026-11-18):
 * <ul>
 *   <li>{@link #phoneRequiredBy} — {@code GET /users/me phoneRequiredBy}: any account without a verified phone →
 *       today (Europe/Kyiv; the app blocks), a verified account → null.</li>
 *   <li>{@link #blocksClientBooking} / {@link #blocksProviderWrite} — the server gate behind the app, only while
 *       {@code APP_AUTH_REQUIRE_VERIFIED_PHONE=true}.</li>
 * </ul>
 */
@Component
public class PhoneRequirementPolicy {

    private final AuthConsentProperties properties;
    private final Clock kyivClock;

    public PhoneRequirementPolicy(AuthConsentProperties properties, Clock clock) {
        this.properties = properties;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    /** @return today (Europe/Kyiv) for any role without a verified phone, {@code null} once verified */
    public LocalDate phoneRequiredBy(boolean phoneVerified) {
        return phoneVerified ? null : LocalDate.now(kyivClock);
    }

    /** An APP booking create by an unverified CLIENT is refused (flag on). */
    public boolean blocksClientBooking(Role role, boolean phoneVerified) {
        return properties.isRequireVerifiedPhone() && role == Role.CLIENT && !phoneVerified;
    }

    /** A (non-exempt) write by an unverified provider is refused (flag on). */
    public boolean blocksProviderWrite(Role role, boolean phoneVerified) {
        return properties.isRequireVerifiedPhone() && role != Role.CLIENT && !phoneVerified;
    }
}
