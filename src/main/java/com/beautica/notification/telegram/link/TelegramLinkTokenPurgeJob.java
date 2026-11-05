package com.beautica.notification.telegram.link;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Phase 409 — Telegram link-data timers.
 *
 * <p><b>D4 token purge ({@link #run}, hourly at :41 UTC)</b> deletes {@code telegram_link_tokens} one day past
 * {@code expires_at}, any state (incl. the terminal {@code LINKED}/{@code MISMATCH} rows holding
 * {@code claimed_chat_id} + {@code phone_hash}). <b>Public guarantee (privacy policy 1.4, website 021):</b> an expired
 * code is gone «не пізніше ніж через 2 доби» after expiry. Holds because {@link #EXPIRY_GRACE} (1 day) + the longest
 * gap between two runs of {@link #CRON} (1 hour) ≤ 2 days, pinned by {@code TelegramLinkTokenPurgeJobTest}; a missed
 * run costs an hour, not a day. Any change must keep that sum, else bump the policy — which is why the cron and
 * threshold are constants and there is no disable flag. A token finishes only before it expires (claim and proof
 * both require an unexpired token), so the expiry threshold covers terminal rows too.
 *
 * <p><b>D1 orphan backstop ({@link #sweepOrphanLinks}, Sundays 03:51 UTC)</b> — {@link TelegramLinkOrphanBackstop}.
 *
 * <p>No ShedLock: {@code FOR UPDATE SKIP LOCKED} batches let instances split the work. :41 / 03:51 are off the
 * 03:27 guest-PII retention slot and the 03:00 / 03:17 / 03:42 / 03:57 cleanup jobs. {@code @Profile("!test")}: ITs
 * build the job around a pinned clock instead of racing a live timer. Logs counts only.
 */
@Slf4j
@Component
@Profile("!test")
public class TelegramLinkTokenPurgeJob {

    static final String CRON = "0 41 * * * *";
    static final String ORPHAN_CRON = "0 51 3 * * SUN";
    static final Duration EXPIRY_GRACE = Duration.ofDays(1);
    static final int BATCH_SIZE = 1_000;
    /** 100,000 rows per hourly run: far above any plausible token volume, so the 2-day promise never backs up. */
    static final int MAX_BATCHES_PER_RUN = 100;

    private final TelegramLinkRetentionService retentionService;
    private final TelegramLinkOrphanBackstop backstop;
    private final Clock clock;

    public TelegramLinkTokenPurgeJob(TelegramLinkRetentionService retentionService, TelegramLinkOrphanBackstop backstop,
                                     Clock clock) {
        this.retentionService = retentionService;
        this.backstop = backstop;
        this.clock = clock;
    }

    @Scheduled(cron = CRON, zone = "UTC")
    public void run() {
        long startedAt = clock.millis();
        Instant cutoff = clock.instant().minus(EXPIRY_GRACE);
        int deleted = retentionService.purgeExpiredTokens(cutoff, BATCH_SIZE, MAX_BATCHES_PER_RUN);
        log.info("Telegram link token purge: deleted {} tokens in {} ms (cutoff {})",
                deleted, clock.millis() - startedAt, cutoff);
    }

    @Scheduled(cron = ORPHAN_CRON, zone = "UTC")
    public void sweepOrphanLinks() {
        backstop.deleteOrphanLinks(clock.instant());
    }
}
