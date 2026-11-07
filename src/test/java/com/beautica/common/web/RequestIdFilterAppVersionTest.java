package com.beautica.common.web;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 437 D6 — {@code X-App-Version} in {@link RequestIdFilter}: a well-formed value lands in the MDC and on
 * the Sentry scope for the request and is cleared afterwards; anything malformed, oversized or carrying CR/LF
 * is dropped and the request still runs. Mutation check: the malformed cases (e.g. {@code 1.2.3}, under the
 * length cap) go RED if the regex guard is removed.
 */
@DisplayName("RequestIdFilter — X-App-Version (Phase 437)")
class RequestIdFilterAppVersionTest {

    private static final String VALID = "1.4.0+27";

    private final RequestIdFilter filter = new RequestIdFilter();

    @BeforeAll
    static void initSentry() {
        Sentry.init(options -> {
            options.setDsn("http://k@localhost:1/1");
            options.setSendClientReports(false);
        });
    }

    @AfterAll
    static void closeSentry() {
        Sentry.close();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static MockHttpServletRequest request(String appVersion) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/app/config");
        request.addHeader(RequestIdFilter.APP_VERSION_HEADER, appVersion);
        return request;
    }

    private static String sentryTag() {
        AtomicReference<String> tag = new AtomicReference<>();
        Sentry.configureScope(scope -> tag.set(scope.getTags().get(RequestIdFilter.APP_VERSION_SENTRY_TAG)));
        return tag.get();
    }

    @Test
    @DisplayName("valid header — MDC and Sentry tag set during the chain, both cleared after")
    void should_setAndClearMdcAndSentryTag_when_headerValid() throws Exception {
        AtomicReference<String> mdcInChain = new AtomicReference<>();
        AtomicReference<String> tagInChain = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (ISentryLifecycleToken ignored = Sentry.forkedScopes("test-request").makeCurrent()) {
            filter.doFilter(request(VALID), response, (req, res) -> {
                mdcInChain.set(MDC.get(RequestIdFilter.APP_VERSION_MDC_KEY));
                tagInChain.set(sentryTag());
            });

            assertThat(sentryTag()).as("Sentry tag cleared after the chain").isNull();
        }

        assertThat(mdcInChain.get()).isEqualTo(VALID);
        assertThat(tagInChain.get()).isEqualTo(VALID);
        assertThat(MDC.get(RequestIdFilter.APP_VERSION_MDC_KEY)).as("MDC cleared after the chain").isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("upper bounds — 999.999.999+999999999 accepted")
    void should_accept_when_atUpperBounds() throws Exception {
        String max = "999.999.999+999999999";
        AtomicReference<String> mdcInChain = new AtomicReference<>();

        filter.doFilter(request(max), new MockHttpServletResponse(),
                (req, res) -> mdcInChain.set(MDC.get(RequestIdFilter.APP_VERSION_MDC_KEY)));

        assertThat(mdcInChain.get()).isEqualTo(max);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.2.3",                       // no build number
            "1.2.3+",                      // empty build number
            "1.2+3",                       // two-part semver
            "v1.2.3+4",                    // prefix
            "1.2.3+4 ",                    // trailing space
            "1.2.3+4\r\nX-Evil: 1",        // CRLF injection
            "1.2.3+4\n",                   // trailing LF
            "1.2.3+4-beta",                // suffix
            "1234.2.3+4",                  // 4-digit part
            "1.2.3+1234567890",            // 10-digit build
            "１.2.3+4",                    // full-width digit (non-ASCII \\d)
            "<script>",
            ""})
    @DisplayName("malformed / CRLF header — ignored, chain still runs")
    void should_ignoreHeader_when_malformed(String inbound) throws Exception {
        AtomicReference<String> mdcInChain = new AtomicReference<>("unset");
        AtomicReference<String> tagInChain = new AtomicReference<>("unset");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (ISentryLifecycleToken ignored = Sentry.forkedScopes("test-request").makeCurrent()) {
            filter.doFilter(request(inbound), response, (req, res) -> {
                mdcInChain.set(MDC.get(RequestIdFilter.APP_VERSION_MDC_KEY));
                tagInChain.set(sentryTag());
            });
        }

        assertThat(mdcInChain.get()).isNull();
        assertThat(tagInChain.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("oversized header (> 32 chars) — ignored, chain still runs")
    void should_ignoreHeader_when_oversized() throws Exception {
        String oversized = "1.2.3+" + "9".repeat(RequestIdFilter.APP_VERSION_MAX_LENGTH);
        AtomicReference<String> mdcInChain = new AtomicReference<>("unset");
        AtomicReference<Boolean> chainRan = new AtomicReference<>(false);

        filter.doFilter(request(oversized), new MockHttpServletResponse(), (req, res) -> {
            chainRan.set(true);
            mdcInChain.set(MDC.get(RequestIdFilter.APP_VERSION_MDC_KEY));
        });

        assertThat(chainRan.get()).isTrue();
        assertThat(mdcInChain.get()).isNull();
    }

    @Test
    @DisplayName("absent header — no MDC entry, chain still runs")
    void should_setNothing_when_headerAbsent() throws Exception {
        AtomicReference<String> mdcInChain = new AtomicReference<>("unset");

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/app/config"), new MockHttpServletResponse(),
                (req, res) -> mdcInChain.set(MDC.get(RequestIdFilter.APP_VERSION_MDC_KEY)));

        assertThat(mdcInChain.get()).isNull();
    }

    @Test
    @DisplayName("valid header — MDC cleared even when the chain throws")
    void should_clearMdc_when_chainThrows() {
        FilterChain throwing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request(VALID), new MockHttpServletResponse(), throwing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestIdFilter.APP_VERSION_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("valid header — Sentry tag cleared even when the chain throws")
    void should_clearSentryTag_when_chainThrows() {
        FilterChain throwing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try (ISentryLifecycleToken ignored = Sentry.forkedScopes("test-request").makeCurrent()) {
            assertThatThrownBy(() -> filter.doFilter(request(VALID), new MockHttpServletResponse(), throwing))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(sentryTag()).as("Sentry app.version tag after a throwing chain").isNull();
        }
    }

    @Test
    @DisplayName("malformed header — request id still set in MDC and echoed (app-version drop is isolated)")
    void should_keepRequestId_when_appVersionMalformed() throws Exception {
        AtomicReference<String> requestIdInChain = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("not-a-version"), response,
                (req, res) -> requestIdInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(requestIdInChain.get()).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(requestIdInChain.get());
    }
}
