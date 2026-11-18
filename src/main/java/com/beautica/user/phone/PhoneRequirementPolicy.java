package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.common.TimeZones;
import com.beautica.config.AuthConsentProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * B516 (AUTH-45, D1, D2) — the one place that knows WHEN an account must have a verified phone:
 * <ul>
 *   <li>{@link #phoneRequiredBy} — {@code GET /users/me phoneRequiredBy}: a CLIENT without a verified phone → today
 *       (Europe/Kyiv; the app blocks at first open), a provider → {@code APP_PHONE_PROVIDER_DEADLINE} (null when
 *       unset), a verified account → null.</li>
 *   <li>{@link #blocksClientBooking} / {@link #blocksProviderWrite} — the server gate behind the app, only while
 *       {@code APP_AUTH_REQUIRE_VERIFIED_PHONE=true}; a provider is gated only once the deadline date has passed.</li>
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

    /** @return the date by which the phone must be verified, or {@code null} (verified, or a provider with no deadline) */
    public LocalDate phoneRequiredBy(Role role, boolean phoneVerified) {
        if (phoneVerified) {
            return null;
        }
        return role == Role.CLIENT ? LocalDate.now(kyivClock) : properties.providerDeadline();
    }

    /** An APP booking create by an unverified CLIENT is refused (flag on). */
    public boolean blocksClientBooking(Role role, boolean phoneVerified) {
        return properties.isRequireVerifiedPhone() && role == Role.CLIENT && !phoneVerified;
    }

    /** A provider write is refused once the deadline day is over (flag on, deadline set, phone unverified). */
    public boolean blocksProviderWrite(Role role, boolean phoneVerified) {
        return role != Role.CLIENT && !phoneVerified && providerDeadlinePassed();
    }

    /** Flag on, a provider deadline set and its (Kyiv) day over — no account data needed. */
    public boolean providerDeadlinePassed() {
        LocalDate deadline = properties.providerDeadline();
        return properties.isRequireVerifiedPhone()
                && deadline != null
                && LocalDate.now(kyivClock).isAfter(deadline);
    }
}
