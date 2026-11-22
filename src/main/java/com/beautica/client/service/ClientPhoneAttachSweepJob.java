package com.beautica.client.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * B527 audit cycle 2 — retry sweep for the retroactive phone attach. The after-commit attach is best effort (it may fail
 * on a lock timeout or be rejected by a saturated executor, and nothing re-runs it until the phone is verified again);
 * this job, every 30 minutes by default, attaches whatever such a failure left behind. At most
 * {@code app.client-phone-attach.sweep.max-phones} (200) phones per run, each under the per-phone lock in its own
 * transaction, so concurrent instances are safe (the UPDATE is idempotent; no distributed lock is needed — the repo
 * has none). Mirrors {@code NotificationOutboxReclaimJob}: a thin {@code @Scheduled} entry point, property-driven delay,
 * work in the service. Disabled with {@code app.client-phone-attach.sweep.enabled=false} (the test profile).
 */
@Component
@ConditionalOnProperty(name = "app.client-phone-attach.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class ClientPhoneAttachSweepJob {

    private final ClientPhoneAttachService attachService;
    private final int maxPhones;

    public ClientPhoneAttachSweepJob(ClientPhoneAttachService attachService,
                                     @Value("${app.client-phone-attach.sweep.max-phones:200}") int maxPhones) {
        this.attachService = attachService;
        this.maxPhones = maxPhones;
    }

    @Scheduled(fixedDelayString = "${app.client-phone-attach.sweep.fixed-delay-ms:1800000}",
               initialDelayString = "${app.client-phone-attach.sweep.initial-delay-ms:300000}")
    public void sweep() {
        attachService.sweep(maxPhones);
    }
}
