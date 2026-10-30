package com.beautica.review.invite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReviewInviteProperties — phase 400 binding + fail-fast validation")
class ReviewInvitePropertiesTest {

    private static final String PREFIX = "app.booking.review-invite";

    @Test
    @DisplayName("an empty source binds the locked defaults: off, 2h, 21:00-09:00 → 10:00, 72h, 14d, 50, 3, exact copy")
    void should_bindLockedDefaults_when_nothingConfigured() {
        ReviewInviteProperties props = bind(Map.of("app.booking.other", "x"));

        assertThat(props.enabled()).isFalse();
        assertThat(props.minPolicyVersion()).isEmpty();
        assertThat(props.delay()).isEqualTo(Duration.ofHours(2));
        assertThat(props.quietStart()).isEqualTo(LocalTime.of(21, 0));
        assertThat(props.quietEnd()).isEqualTo(LocalTime.of(9, 0));
        assertThat(props.sendAt()).isEqualTo(LocalTime.of(10, 0));
        assertThat(props.staleAfter()).isEqualTo(Duration.ofHours(72));
        assertThat(props.ttl()).isEqualTo(Duration.ofDays(14));
        assertThat(props.batchSize()).isEqualTo(50);
        assertThat(props.maxAttempts()).isEqualTo(3);
        assertThat(props.smsText()).isEqualTo("Оцініть ваш візит: beautica.app/r/{token}");
        assertThat(props.sendBudget()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("HH:mm times and yml-style values bind")
    void should_bindConfiguredValues_when_set() {
        ReviewInviteProperties props = bind(Map.of(
                PREFIX + ".enabled", "true",
                PREFIX + ".min-policy-version", "2026-11-01.2",
                PREFIX + ".quiet-start", "22:30",
                PREFIX + ".quiet-end", "08:00",
                PREFIX + ".send-at", "09:15",
                PREFIX + ".ttl", "7d"));

        assertThat(props.enabled()).isTrue();
        assertThat(props.minPolicyVersion()).isEqualTo("2026-11-01.2");
        assertThat(props.quietStart()).isEqualTo(LocalTime.of(22, 30));
        assertThat(props.quietEnd()).isEqualTo(LocalTime.of(8, 0));
        assertThat(props.sendAt()).isEqualTo(LocalTime.of(9, 15));
        assertThat(props.ttl()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("enabled with a blank min-policy-version fails the boot")
    void should_failBinding_when_enabledWithoutPolicyVersion() {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".enabled", "true")))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("app.booking.review-invite.min-policy-version is required when review invites "
                        + "are enabled");
    }

    @Test
    @DisplayName("a malformed min-policy-version fails the boot")
    void should_failBinding_when_policyVersionMalformed() {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".min-policy-version", "v2")))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("app.booking.review-invite.min-policy-version must look like 2026-10-20");
    }

    @Test
    @DisplayName("an SMS copy that would exceed one UCS-2 segment with the 22-char token fails the boot")
    void should_failBinding_when_smsTextTooLong() {
        String text = "Оцініть, будь ласка, ваш нещодавній візит тут: beautica.app/r/{token}";

        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".sms-text", text)))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("one UCS-2 segment is 70");
    }

    @Test
    @DisplayName("an SMS copy without {token} fails the boot")
    void should_failBinding_when_smsTextHasNoToken() {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".sms-text", "Оцініть ваш візит: beautica.app")))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("app.booking.review-invite.sms-text must contain {token} exactly once");
    }

    @Test
    @DisplayName("a send-at inside the quiet window fails the boot (invites would be pushed into the night)")
    void should_failBinding_when_sendAtInsideQuietWindow() {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".send-at", "08:00")))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("app.booking.review-invite.send-at must lie outside the quiet window");
    }

    @Test
    @DisplayName("batch-size 0 fails the boot (D4a: every claim is LIMITed)")
    void should_failBinding_when_batchSizeZero() {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".batch-size", "0")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("batch-size must be in [1, 1000]");
    }

    @Test
    @DisplayName("isQuiet: [21:00, 09:00) wraps midnight — 20:59 and 09:00 are outside, 21:00 and 08:59 inside")
    void should_classifyQuietWindowEdges_when_windowWrapsMidnight() {
        LocalTime start = LocalTime.of(21, 0);
        LocalTime end = LocalTime.of(9, 0);

        assertThat(ReviewInviteProperties.isQuiet(LocalTime.of(20, 59), start, end)).isFalse();
        assertThat(ReviewInviteProperties.isQuiet(LocalTime.of(21, 0), start, end)).isTrue();
        assertThat(ReviewInviteProperties.isQuiet(LocalTime.of(8, 59), start, end)).isTrue();
        assertThat(ReviewInviteProperties.isQuiet(LocalTime.of(9, 0), start, end)).isFalse();
    }

    @Test
    @DisplayName("send-budget binds up to the 10 s static cap (30 s grace - 10 s Telegram leg - 3 s connect - 5 s read - 2 s write-back); 11 s fails the boot")
    void should_bindSendBudgetAndCapIt_when_configured() {
        assertThat(bind(Map.of(PREFIX + ".send-budget", "7s")).sendBudget()).isEqualTo(Duration.ofSeconds(7));
        assertThat(bind(Map.of(PREFIX + ".send-budget", "10s")).sendBudget()).isEqualTo(Duration.ofSeconds(10));
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".send-budget", "11s")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("send-budget must be <= PT10S");
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".send-budget", "0s")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("send-budget must be positive");
    }

    @Test
    @DisplayName("maxSendBudget = grace - Telegram leg - sms connect - sms read - write-back margin")
    void should_deriveMaxSendBudget_when_givenGraceAndTimeouts() {
        Duration max = ReviewInviteProperties.maxSendBudget(
                Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10));

        assertThat(max).isEqualTo(Duration.ofSeconds(10));
        assertThat(ReviewInviteProperties.SEND_WRITE_BACK_MARGIN).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("requireSendBudgetWithin passes at the derived bound and fails one millisecond past it")
    void should_failSendBudgetCheck_when_budgetPlusInFlightSendExceedsGrace() {
        ReviewInviteProperties atBound = bind(Map.of(PREFIX + ".send-budget", "3s"));
        ReviewInviteProperties pastBound = bind(Map.of(PREFIX + ".send-budget", "3001ms"));
        Duration grace = Duration.ofSeconds(30);
        Duration connect = Duration.ofSeconds(3);
        Duration slowRead = Duration.ofSeconds(12);

        Duration telegram = ReviewInviteProperties.TELEGRAM_LEG_WORST_CASE;

        assertThatCode(() -> atBound.requireSendBudgetWithin(grace, connect, slowRead, telegram))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> pastBound.requireSendBudgetWithin(grace, connect, slowRead, telegram))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("send-budget must be <= PT3S");
    }

    @Test
    @DisplayName("prod link rule: beautica.app/r/{token} as a whole word passes (also https://); the default passes")
    void should_acceptProdLink_when_hostIsBeauticaApp() {
        assertThat(ReviewInviteProperties.linksToProdHost(ReviewInviteProperties.DEFAULT_SMS_TEXT)).isTrue();
        assertThat(ReviewInviteProperties.linksToProdHost(null)).isTrue();
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: https://beautica.app/r/{token}")).isTrue();
        assertThat(ReviewInviteProperties.linksToProdHost("beautica.app/r/{token}.")).isTrue();
    }

    @Test
    @DisplayName("prod link rule: another host, a look-alike host, a host prefix, http or another path all fail")
    void should_rejectProdLink_when_hostOrPathDiffers() {
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: staging.beautica.app/r/{token}")).isFalse();
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: evil-beautica.app/r/{token}")).isFalse();
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: evil.com/beautica.app/r/{token}")).isFalse();
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: http://beautica.app/r/{token}")).isFalse();
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: beautica.app/x/{token}")).isFalse();
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: beautica.app.evil.com/r/{token}")).isFalse();
        assertThat(ReviewInviteProperties.linksToProdHost("Відгук: beautica.app/r/{token}x")).isFalse();
    }

    private static ReviewInviteProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(new HashMap<>(values)));
        return binder.bindOrCreate(PREFIX, ReviewInviteProperties.class);
    }
}
