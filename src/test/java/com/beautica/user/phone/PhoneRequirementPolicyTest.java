package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.config.AuthConsentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PhoneRequirementPolicy — phoneRequiredBy and the verified-phone gate (B516, AUTH-45)")
class PhoneRequirementPolicyTest {

    /** 22:30 UTC = 00:30 next day in Kyiv (UTC+2 in November): the Kyiv date must win. */
    private static final Instant LATE_EVENING_UTC = Instant.parse("2026-11-20T22:30:00Z");
    private static final LocalDate KYIV_TODAY = LocalDate.parse("2026-11-21");

    private static PhoneRequirementPolicy policy(boolean flag, String deadline) {
        AuthConsentProperties properties = new AuthConsentProperties();
        properties.setRequireVerifiedPhone(flag);
        properties.setPhoneProviderDeadline(deadline);
        return new PhoneRequirementPolicy(properties, Clock.fixed(LATE_EVENING_UTC, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_returnKyivToday_when_clientUnverified")
    void should_returnKyivToday_when_clientUnverified() {
        assertThat(policy(false, "").phoneRequiredBy(Role.CLIENT, false)).isEqualTo(KYIV_TODAY);
    }

    @Test
    @DisplayName("should_returnDeadline_when_providerUnverified")
    void should_returnDeadline_when_providerUnverified() {
        assertThat(policy(false, "2026-12-31").phoneRequiredBy(Role.SALON_OWNER, false))
                .isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(policy(false, "").phoneRequiredBy(Role.SALON_MASTER, false)).isNull();
    }

    @Test
    @DisplayName("should_returnNull_when_phoneVerified")
    void should_returnNull_when_phoneVerified() {
        assertThat(policy(true, "2026-12-31").phoneRequiredBy(Role.CLIENT, true)).isNull();
        assertThat(policy(true, "2026-12-31").phoneRequiredBy(Role.INDEPENDENT_MASTER, true)).isNull();
    }

    @Test
    @DisplayName("should_blockUnverifiedClientBooking_only_when_flagOn")
    void should_blockUnverifiedClientBooking_only_when_flagOn() {
        assertThat(policy(false, "").blocksClientBooking(Role.CLIENT, false)).isFalse();
        assertThat(policy(true, "").blocksClientBooking(Role.CLIENT, false)).isTrue();
        assertThat(policy(true, "").blocksClientBooking(Role.CLIENT, true)).isFalse();
    }

    @Test
    @DisplayName("should_blockProviderWrite_only_when_flagOnAndDeadlineDayIsOver")
    void should_blockProviderWrite_only_when_flagOnAndDeadlineDayIsOver() {
        assertThat(policy(true, "2026-11-20").blocksProviderWrite(Role.SALON_OWNER, false)).isTrue();
        assertThat(policy(true, "2026-11-21").blocksProviderWrite(Role.SALON_OWNER, false))
                .as("the deadline day itself is still allowed").isFalse();
        assertThat(policy(false, "2026-11-20").blocksProviderWrite(Role.SALON_OWNER, false)).isFalse();
        assertThat(policy(true, "").blocksProviderWrite(Role.SALON_OWNER, false)).isFalse();
        assertThat(policy(true, "2026-11-20").blocksProviderWrite(Role.SALON_OWNER, true)).isFalse();
        assertThat(policy(true, "2026-11-20").blocksProviderWrite(Role.CLIENT, false)).isFalse();
    }

    @Test
    @DisplayName("should_failBoot_when_deadlineIsNotAnIsoDate")
    void should_failBoot_when_deadlineIsNotAnIsoDate() {
        AuthConsentProperties properties = new AuthConsentProperties();

        assertThatThrownBy(() -> properties.setPhoneProviderDeadline("31.12.2026"))
                .isInstanceOf(IllegalStateException.class);
    }
}
