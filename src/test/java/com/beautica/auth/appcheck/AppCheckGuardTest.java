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

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROUTE);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE);
        return request;
    }

    @Test
    @DisplayName("repeated requests with the same result, endpoint and mode reuse one cached counter")
    void should_reuseCachedCounter_when_sameTagsRepeat() {
        when(verifier.verify(any())).thenReturn(AppCheckVerifier.Result.VALID);
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
        when(verifier.verify(any())).thenReturn(AppCheckVerifier.Result.VALID, AppCheckVerifier.Result.MISSING);

        guard.check(request());
        guard.check(request());

        for (String result : new String[] {"valid", "missing"}) {
            Counter counter = meterRegistry.find(AppCheckGuard.METRIC).tag("result", result)
                    .tag("endpoint", "POST " + ROUTE).tag("mode", "LOG_ONLY").counter();
            assertThat(counter).as(result).isNotNull();
            assertThat(counter.count()).as(result).isEqualTo(1);
        }
    }
}
