package com.beautica.notification.telegram.link;

import com.beautica.common.security.PhoneHasher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Phase 409 audit-fix cycle 1/2 — weekly backstop for D1 («12 місяців без записів»): deletes every Telegram link older
 * than {@link #MIN_LINK_AGE} whose phone has NO non-anonymised guest/walk-in booking left, whatever path removed or
 * anonymised the last one (the per-batch D1 step only reaches phones a retention batch anonymised).
 *
 * <p>The links table is keyed by the peppered phone HASH, which SQL cannot compute, so the open phones are read in
 * keyset pages from {@link OpenGuestPhoneSource}, hashed in memory into {@link PhoneHashPrefixes} (8 bytes per
 * phone), and links are then scanned in keyset pages against it. If the phone scan does not finish within its page
 * budget nothing is deleted — an incomplete set would delete live links — and {@value #SKIPPED_METRIC} is
 * incremented. A link re-bound while the sweep runs is spared by the {@code linked_at} re-check; a booking created for
 * an old link's phone between the two scans can lose its link (the guest falls back to SMS until they re-link) — the
 * safe direction for a retention promise.
 *
 * <p>Every statement is one bounded page. Logs carry counts only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramLinkOrphanBackstop {

    static final String SKIPPED_METRIC = "beautica.telegram.backstop.skipped";
    static final Duration MIN_LINK_AGE = Duration.ofDays(1);
    static final int PHONE_PAGE_SIZE = 5_000;
    /** 1,000,000 distinct open phones (≈ 8 MB of prefixes); beyond that the sweep refuses to delete. */
    static final int MAX_PHONE_PAGES = 200;
    /** 100,000 links per run. */
    static final int MAX_LINK_PAGES = 100;

    private final OpenGuestPhoneSource openGuestPhoneSource;
    private final TelegramLinkRetentionService telegramLinkRetentionService;
    private final PhoneHasher phoneHasher;
    private final MeterRegistry meterRegistry;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int deleteOrphanLinks(Instant now) {
        PhoneHashPrefixes openPhones = new PhoneHashPrefixes();
        String after = "";
        for (int page = 0; page < MAX_PHONE_PAGES; page++) {
            List<String> phones = openGuestPhoneSource.openGuestPhonesPage(after, PHONE_PAGE_SIZE);
            phones.forEach(phone -> phoneHasher.tryHash(phone).ifPresent(hashed -> openPhones.add(hashed.hash())));
            if (phones.size() < PHONE_PAGE_SIZE) {
                int deleted = telegramLinkRetentionService.deleteOrphanLinks(
                        openPhones, now.minus(MIN_LINK_AGE), MAX_LINK_PAGES);
                log.info("Telegram link backstop: {} open phones, deleted {} orphan links", openPhones.size(), deleted);
                return deleted;
            }
            after = phones.get(phones.size() - 1);
        }
        meterRegistry.counter(SKIPPED_METRIC).increment();
        log.warn("Telegram link backstop: open-phone scan exceeded {} pages, nothing deleted", MAX_PHONE_PAGES);
        return 0;
    }
}
