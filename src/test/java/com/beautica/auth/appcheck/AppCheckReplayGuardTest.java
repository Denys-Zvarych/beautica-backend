package com.beautica.auth.appcheck;

import com.beautica.common.exception.IdentityException;
import com.beautica.config.appcheck.AppCheckMode;
import com.beautica.config.appcheck.AppCheckProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Phone-first hardening B9 (AUTH-D27 d) — the {@code jti} replay rule of {@link AppCheckGuard} per replay mode. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppCheckGuard — limited-use token replay (AUTH-D27 d)")
class AppCheckReplayGuardTest {

    private static final String ROUTE = "/api/v1/auth/register/phone/otp/send";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final AppCheckVerifier.Verification VALID_WITH_JTI =
            new AppCheckVerifier.Verification(AppCheckVerifier.Result.VALID, "jti-1", NOW.plusSeconds(300));
    private static final AppCheckVerifier.Verification VALID_WITHOUT_JTI =
            new AppCheckVerifier.Verification(AppCheckVerifier.Result.VALID, null, NOW.plusSeconds(300));

    @Mock
    private AppCheckVerifier verifier;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private AppCheckGuard guard(AppCheckMode replayMode) {
        AppCheckProperties properties = new AppCheckProperties();
        properties.setMode(AppCheckMode.ENFORCE);
        properties.setReplayMode(replayMode);
        return new AppCheckGuard(verifier, properties, meterRegistry,
                new AppCheckReplayStore(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROUTE);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE);
        request.addHeader(AppCheckGuard.HEADER, "token");
        return request;
    }

    private double replayCount() {
        Counter counter = meterRegistry.find(AppCheckGuard.METRIC).tag("result", "replay").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("replay mode OFF — a reused jti is ignored")
    void should_ignoreReplay_when_replayModeOff() {
        when(verifier.verifyToken(any())).thenReturn(VALID_WITH_JTI);
        AppCheckGuard guard = guard(AppCheckMode.OFF);

        guard.check(request());

        assertThatCode(() -> guard.check(request())).doesNotThrowAnyException();
        assertThat(replayCount()).isZero();
    }

    @Test
    @DisplayName("replay mode LOG_ONLY — a reused jti is counted and let through")
    void should_countAndPass_when_replayModeLogOnly() {
        when(verifier.verifyToken(any())).thenReturn(VALID_WITH_JTI);
        AppCheckGuard guard = guard(AppCheckMode.LOG_ONLY);

        guard.check(request());

        assertThatCode(() -> guard.check(request())).doesNotThrowAnyException();
        assertThat(replayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("replay mode ENFORCE — a reused jti is rejected with 403")
    void should_reject403_when_replayModeEnforceAndJtiSeen() {
        when(verifier.verifyToken(any())).thenReturn(VALID_WITH_JTI);
        AppCheckGuard guard = guard(AppCheckMode.ENFORCE);

        guard.check(request());

        assertThatThrownBy(() -> guard.check(request()))
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.APP_CHECK_FAILED));
        assertThat(replayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("replay mode ENFORCE — distinct jtis are all accepted")
    void should_acceptDistinctJtis_when_replayModeEnforce() {
        when(verifier.verifyToken(any())).thenReturn(VALID_WITH_JTI,
                new AppCheckVerifier.Verification(AppCheckVerifier.Result.VALID, "jti-2", NOW.plusSeconds(300)));
        AppCheckGuard guard = guard(AppCheckMode.ENFORCE);

        guard.check(request());

        assertThatCode(() -> guard.check(request())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("replay mode ENFORCE — a token without a jti is rejected with 403")
    void should_reject403_when_replayModeEnforceAndJtiMissing() {
        when(verifier.verifyToken(any())).thenReturn(VALID_WITHOUT_JTI);
        AppCheckGuard guard = guard(AppCheckMode.ENFORCE);

        assertThatThrownBy(() -> guard.check(request()))
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.APP_CHECK_FAILED));
    }

    @Test
    @DisplayName("replay mode LOG_ONLY — a token without a jti is let through")
    void should_pass_when_replayModeLogOnlyAndJtiMissing() {
        when(verifier.verifyToken(any())).thenReturn(VALID_WITHOUT_JTI);
        AppCheckGuard guard = guard(AppCheckMode.LOG_ONLY);

        assertThatCode(() -> guard.check(request())).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(request())).doesNotThrowAnyException();
    }
}
