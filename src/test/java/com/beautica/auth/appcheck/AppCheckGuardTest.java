package com.beautica.auth.appcheck;

import com.beautica.config.appcheck.AppCheckMode;
import com.beautica.config.appcheck.AppCheckProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppCheckGuard — verify counter registration (B514, AUTH-54)")
class AppCheckGuardTest {

    private static final String ROUTE = "/api/v1/auth/register/phone/otp/send";

    @Mock
    private AppCheckVerifier verifier;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AtomicInteger metersAdded = new AtomicInteger();
    private AppCheckGuard guard;

    @BeforeEach
    void setUp() {
        AppCheckProperties properties = new AppCheckProperties();
        properties.setMode(AppCheckMode.LOG_ONLY);
        meterRegistry.config().onMeterAdded(meter -> metersAdded.incrementAndGet());
        guard = new AppCheckGuard(verifier, properties, meterRegistry);
    }

    /** A verification without {@code jti} (replay mode LOG_ONLY by default: passes untracked). */
    private static AppCheckVerifier.Verification verification(AppCheckVerifier.Result result) {
        return new AppCheckVerifier.Verification(result, null, null);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROUTE);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE);
        return request;
    }

    @Test
    @DisplayName("repeated requests with the same result, endpoint and mode reuse one cached counter")
    void should_reuseCachedCounter_when_sameTagsRepeat() {
        when(verifier.verifyToken(any())).thenReturn(verification(AppCheckVerifier.Result.VALID));
        guard.check(request());
        Counter counter = meterRegistry.find(AppCheckGuard.METRIC).tag("result", "valid").counter();
        meterRegistry.remove(counter);

        guard.check(request());
        guard.check(request());

        assertThat(counter.count()).as("later requests incremented the cached counter").isEqualTo(3);
        assertThat(metersAdded.get()).as("the counter was registered once, not per request").isEqualTo(1);
    }

    @Test
    @DisplayName("each distinct result gets its own counter tagged with endpoint and mode")
    void should_countPerResult_when_resultsDiffer() {
        when(verifier.verifyToken(any())).thenReturn(verification(AppCheckVerifier.Result.VALID),
                verification(AppCheckVerifier.Result.MISSING));

        guard.check(request());
        guard.check(request());

        for (String result : new String[] {"valid", "missing"}) {
            Counter counter = meterRegistry.find(AppCheckGuard.METRIC).tag("result", result)
                    .tag("endpoint", "POST " + ROUTE).tag("mode", "LOG_ONLY").counter();
            assertThat(counter).as(result).isNotNull();
            assertThat(counter.count()).as(result).isEqualTo(1);
        }
    }

    // ── B516 — checkCurrentRequest (forgot / change password SMS branch) ──────

    private AppCheckGuard guardIn(AppCheckMode mode) {
        AppCheckProperties properties = new AppCheckProperties();
        properties.setMode(mode);
        return new AppCheckGuard(verifier, properties, meterRegistry);
    }

    @org.junit.jupiter.api.AfterEach
    void unbindRequest() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("B516: checkCurrentRequest verifies the header of the request bound to the thread")
    void should_verifyBoundRequestHeader_when_checkCurrentRequest() {
        MockHttpServletRequest request = request();
        request.addHeader(AppCheckGuard.HEADER, "token-value");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        when(verifier.verifyToken("token-value")).thenReturn(verification(AppCheckVerifier.Result.INVALID));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guardIn(AppCheckMode.ENFORCE).checkCurrentRequest())
                .isInstanceOfSatisfying(com.beautica.common.exception.IdentityException.class, e ->
                        assertThat(e.getCode())
                                .isEqualTo(com.beautica.common.exception.IdentityException.Code.APP_CHECK_FAILED));
    }

    @Test
    @DisplayName("B516: checkCurrentRequest without a bound request fails closed in ENFORCE")
    void should_failClosed_when_noRequestBoundInEnforce() {
        when(verifier.verify(null)).thenReturn(AppCheckVerifier.Result.MISSING);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guardIn(AppCheckMode.ENFORCE).checkCurrentRequest())
                .isInstanceOf(com.beautica.common.exception.IdentityException.class);
    }

    @Test
    @DisplayName("B516: checkCurrentRequest is a no-op in OFF")
    void should_doNothing_when_modeOff() {
        guardIn(AppCheckMode.OFF).checkCurrentRequest();

        org.mockito.Mockito.verifyNoInteractions(verifier);
    }
}
