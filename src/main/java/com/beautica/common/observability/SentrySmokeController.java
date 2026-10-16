package com.beautica.common.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staging-only Sentry smoke trigger (Phase 371, step 9 / acceptance A2). Lives under
 * {@code /api/v1/internal/**}, so {@code InternalApiKeyFilter} demands {@code X-Internal-Key} before it is
 * reached. The bean exists only when {@code beautica.environment=staging}: absent in prod and local.
 *
 * <p>The exception message carries canary PII on purpose, proving the scrubber live: the Sentry event must
 * show {@code [email]} and {@code [phone]} instead.
 */
@RestController
@RequestMapping("/api/v1/internal/diagnostics")
@ConditionalOnProperty(name = "beautica.environment", havingValue = "staging")
public class SentrySmokeController {

    static final String CANARY_MESSAGE = "sentry smoke canary@x.ua +380501112233";

    @PostMapping("/sentry-smoke")
    public void sentrySmoke() {
        throw new SentrySmokeTestException(CANARY_MESSAGE);
    }
}
