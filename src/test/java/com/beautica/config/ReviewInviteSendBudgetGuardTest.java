package com.beautica.config;

import com.beautica.review.invite.config.ReviewInviteProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReviewInviteSendBudgetGuard — phase 400 audit cycle 2: send budget + one SMS fits the shutdown grace")
class ReviewInviteSendBudgetGuardTest {

    private static final String PREFIX = "app.booking.review-invite";

    @Test
    @DisplayName("the static 20 s cap equals the grace minus the DEFAULT Turbosms connect + read timeouts minus the write-back margin")
    void should_matchStaticCap_when_derivedFromDefaultTimeouts() {
        TurbosmsProperties defaults = new TurbosmsProperties();

        Duration derived = ReviewInviteProperties.maxSendBudget(
                Duration.ofSeconds(SchedulingConfig.SCHEDULER_SHUTDOWN_GRACE_SECONDS),
                defaults.getConnectTimeout(), defaults.getReadTimeout());

        assertThat(derived).isEqualTo(ReviewInviteProperties.MAX_SEND_BUDGET);
    }

    @Test
    @DisplayName("the default 20 s budget boots with the default Turbosms timeouts")
    void should_boot_when_defaultsConfigured() {
        ReviewInviteProperties props = bind(Map.of("app.booking.other", "x"));

        assertThatCode(() -> new ReviewInviteSendBudgetGuard(props, new TurbosmsProperties()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the max budget boots exactly at the derived bound")
    void should_boot_when_budgetEqualsDerivedBound() {
        ReviewInviteProperties props = bind(Map.of(PREFIX + ".send-budget", "20s"));

        assertThatCode(() -> new ReviewInviteSendBudgetGuard(props, new TurbosmsProperties()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a raised Turbosms read timeout without a lowered budget fails the boot")
    void should_failBoot_when_readTimeoutRaisedPastGrace() {
        ReviewInviteProperties props = bind(Map.of(PREFIX + ".enabled", "true", PREFIX + ".min-policy-version", "2026-10-20"));
        TurbosmsProperties turbosms = new TurbosmsProperties();
        turbosms.setReadTimeout(Duration.ofSeconds(10));

        assertThatThrownBy(() -> new ReviewInviteSendBudgetGuard(props, turbosms))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("send-budget must be <= PT15S");
    }

    @Test
    @DisplayName("a zero Turbosms timeout fails the boot with the property name, not an arithmetic surprise")
    void should_failBoot_when_turbosmsTimeoutNotPositive() {
        ReviewInviteProperties props = bind(Map.of(PREFIX + ".enabled", "true", PREFIX + ".min-policy-version", "2026-10-20"));
        TurbosmsProperties turbosms = new TurbosmsProperties();
        turbosms.setConnectTimeout(Duration.ZERO);

        assertThatThrownBy(() -> new ReviewInviteSendBudgetGuard(props, turbosms))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sms.turbosms.connect-timeout");
    }

    @Test
    @DisplayName("invites disabled: the guard skips the check — an oversized budget vs raised timeouts still boots")
    void should_skipCheck_when_invitesDisabled() {
        ReviewInviteProperties props = bind(Map.of(PREFIX + ".enabled", "false"));
        TurbosmsProperties turbosms = new TurbosmsProperties();
        turbosms.setReadTimeout(Duration.ofSeconds(25));

        assertThatCode(() -> new ReviewInviteSendBudgetGuard(props, turbosms)).doesNotThrowAnyException();
    }

    private static ReviewInviteProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(new HashMap<>(values)));
        return binder.bindOrCreate(PREFIX, ReviewInviteProperties.class);
    }
}
