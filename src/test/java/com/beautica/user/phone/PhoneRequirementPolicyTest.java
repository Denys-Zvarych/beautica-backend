package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.config.AuthConsentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PhoneRequirementPolicy — phoneRequiredBy and the verified-phone gate (B516, AUTH-45)")
class PhoneRequirementPolicyTest {

    /** 22:30 UTC = 00:30 next day in Kyiv (UTC+2 in November): the Kyiv date must win. */
    private static final Instant LATE_EVENING_UTC = Instant.parse("2026-11-20T22:30:00Z");
    private static final LocalDate KYIV_TODAY = LocalDate.parse("2026-11-21");

    /** 21:30 UTC = 00:30 next day in Kyiv (UTC+3 in July, DST on): the summer offset must be applied too. */
    private static final Instant SUMMER_LATE_EVENING_UTC = Instant.parse("2026-07-14T21:30:00Z");
    private static final LocalDate SUMMER_KYIV_TODAY = LocalDate.parse("2026-07-15");

    private static PhoneRequirementPolicy policy(boolean flag) {
        return policy(flag, LATE_EVENING_UTC);
    }

    private static PhoneRequirementPolicy policy(boolean flag, Instant now) {
        AuthConsentProperties properties = new AuthConsentProperties();
        properties.setRequireVerifiedPhone(flag);
        return new PhoneRequirementPolicy(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("an unverified account must confirm its phone today (Kyiv date, winter UTC+2), flag off")
    void should_returnKyivToday_when_unverifiedAndFlagOff() {
        LocalDate requiredBy = policy(false).phoneRequiredBy(false);

        assertThat(requiredBy).isEqualTo(KYIV_TODAY);
    }

    @Test
    @DisplayName("an unverified account must confirm its phone today with the gate flag on too (AUTH-D12: no deadline)")
    void should_returnKyivToday_when_unverifiedAndFlagOn() {
        LocalDate requiredBy = policy(true).phoneRequiredBy(false);

        assertThat(requiredBy).isEqualTo(KYIV_TODAY);
    }

    @Test
    @DisplayName("phoneRequiredBy uses the Kyiv summer offset (UTC+3) just after Kyiv midnight")
    void should_returnKyivToday_when_unverifiedInSummer() {
        LocalDate requiredBy = policy(true, SUMMER_LATE_EVENING_UTC).phoneRequiredBy(false);

        assertThat(requiredBy).isEqualTo(SUMMER_KYIV_TODAY);
    }

    @Test
    @DisplayName("a verified account has no phoneRequiredBy with the flag on")
    void should_returnNull_when_phoneVerifiedAndFlagOn() {
        LocalDate requiredBy = policy(true).phoneRequiredBy(true);

        assertThat(requiredBy).isNull();
    }

    @Test
    @DisplayName("a verified account has no phoneRequiredBy with the flag off")
    void should_returnNull_when_phoneVerifiedAndFlagOff() {
        LocalDate requiredBy = policy(false).phoneRequiredBy(true);

        assertThat(requiredBy).isNull();
    }

    @Test
    @DisplayName("an unverified client's booking is not blocked while the flag is off")
    void should_notBlockClientBooking_when_flagOff() {
        boolean blocked = policy(false).blocksClientBooking(Role.CLIENT, false);

        assertThat(blocked).isFalse();
    }

    @Test
    @DisplayName("an unverified client's booking is blocked with the flag on")
    void should_blockClientBooking_when_flagOnAndUnverified() {
        boolean blocked = policy(true).blocksClientBooking(Role.CLIENT, false);

        assertThat(blocked).isTrue();
    }

    @Test
    @DisplayName("a verified client's booking is never blocked, flag on")
    void should_notBlockClientBooking_when_flagOnAndVerified() {
        boolean blocked = policy(true).blocksClientBooking(Role.CLIENT, true);

        assertThat(blocked).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = "CLIENT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every provider role without a verified phone is write-blocked at once with the flag on (AUTH-D12)")
    void should_blockUnverifiedProviderWrite_immediately_when_flagOn(Role provider) {
        boolean blocked = policy(true).blocksProviderWrite(provider, false);

        assertThat(blocked).as("%s unverified, flag on", provider).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = "CLIENT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a provider is never write-blocked while the flag is off, even unverified")
    void should_notBlockProviderWrite_when_flagOff(Role provider) {
        boolean blocked = policy(false).blocksProviderWrite(provider, false);

        assertThat(blocked).as("%s unverified, flag off", provider).isFalse();
    }

    @Test
    @DisplayName("a verified provider's write is not blocked, flag on")
    void should_notBlockProviderWrite_when_flagOnAndVerified() {
        boolean blocked = policy(true).blocksProviderWrite(Role.SALON_OWNER, true);

        assertThat(blocked).isFalse();
    }

    @Test
    @DisplayName("blocksProviderWrite never blocks a CLIENT, even unverified with the flag on")
    void should_notBlockProviderWrite_when_roleIsClient() {
        boolean blocked = policy(true).blocksProviderWrite(Role.CLIENT, false);

        assertThat(blocked).isFalse();
    }
}
