package com.beautica.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Phase 414 D4 — lifetime of a one-click moderation link in the support report e-mail, bound from
 * {@code app.moderation.action-link-ttl} (optional, default 7 days). Validated fail-fast at bind time. A separate record
 * from {@link ModerationProperties} (same prefix, disjoint keys) so the 396 settings keep their constructor.
 *
 * @param actionLinkTtl how long an e-mailed action link stays usable; 1 hour to 30 days
 */
@ConfigurationProperties(prefix = "app.moderation")
public record ModerationActionLinkProperties(@DefaultValue("P7D") Duration actionLinkTtl) {

    private static final Duration MIN_TTL = Duration.ofHours(1);
    private static final Duration MAX_TTL = Duration.ofDays(30);

    public ModerationActionLinkProperties {
        if (actionLinkTtl == null || actionLinkTtl.compareTo(MIN_TTL) < 0 || actionLinkTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalStateException("app.moderation.action-link-ttl must be in [PT1H, P30D] (was "
                    + actionLinkTtl + ")");
        }
    }
}
