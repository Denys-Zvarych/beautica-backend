package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable policy for the notification-outbox stale-claim reclaim sweep.
 *
 * <p>{@code staleClaimThreshold} is the minimum age (measured from {@code updated_at}) a
 * {@code PROCESSING} row must reach before
 * {@code NotificationOutboxReclaimJob} resets it back to {@code PENDING}
 * (or {@code DEAD} once retries are exhausted). It must comfortably exceed the worst-case
 * Phase 2 dispatch window for a single drain batch — see
 * {@code NotificationOutboxDrainWorker#drain()}'s javadoc for the derivation (~33 minutes at
 * {@code BATCH_SIZE = 50}, ~40s per-entry worst case). A threshold shorter than that window
 * would reclaim — and re-dispatch — a row that is still being legitimately processed by a
 * live instance, reintroducing the exact duplicate-send bug this sweep exists to prevent.
 * Production default is 60 minutes.
 */
@ConfigurationProperties(prefix = "notification.outbox.reclaim")
public record OutboxReclaimPolicyConfig(Duration staleClaimThreshold) {

    public OutboxReclaimPolicyConfig {
        if (staleClaimThreshold == null || staleClaimThreshold.isNegative() || staleClaimThreshold.isZero()) {
            throw new IllegalStateException(
                    "notification.outbox.reclaim.stale-claim-threshold must be a positive duration");
        }
    }
}
