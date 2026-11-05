package com.beautica.booking.retention;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import com.beautica.notification.telegram.link.TelegramLinkRetentionService;
import com.beautica.notification.telegram.link.TelegramLinkRows;
import com.beautica.notification.telegram.link.TelegramLinkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.beautica.booking.retention.GuestPiiFixtures.PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 409 audit-fix cycle 1 (sec MEDIUM / perf) — two retention instances anonymise the phone's last two bookings
 * in parallel. A barrier holds both batch transactions after their claim and before the link step, so each would see
 * the other's (uncommitted) booking as still open; the per-phone retention lock makes the second wait for the first
 * commit and then delete the link.
 */
@DisplayName("Guest PII retention — two-instance race on a phone's last bookings keeps no link (phase 409)")
class GuestPiiTelegramRaceIT extends AbstractIntegrationTest {

    private static final long BARRIER_TIMEOUT_SECONDS = 10;

    @Autowired private GuestPiiAnonymisationService service;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private PlatformTransactionManager transactionManager;

    @SpyBean private TelegramLinkRetentionService telegramLinkRetentionService;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("both batches claimed before either checks → the link is still deleted")
    void should_deleteLink_when_twoBatchesRaceOnPhonesLastBookings() throws Exception {
        GuestPiiFixtures fx = new GuestPiiFixtures(jdbcTemplate);
        GuestPiiFixtures.Provider provider = fx.createIndependentMaster();
        TelegramLinkRows rows = new TelegramLinkRows(jdbcTemplate);
        Instant cutoff = Instant.now().atOffset(ZoneOffset.UTC).minusMonths(12).toInstant();
        fx.link(provider, "COMPLETED", cutoff.minus(Duration.ofDays(5)).atOffset(ZoneOffset.UTC), PHONE, null);
        fx.link(provider, "COMPLETED", cutoff.minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC), PHONE, null);
        UUID link = rows.link(phoneHasher.hash(PHONE).hash(), 555L, TelegramLinkStatus.ACTIVE,
                cutoff.minus(Duration.ofDays(30)));
        CyclicBarrier bothClaimed = new CyclicBarrier(2);
        // Stubbed inside a transaction: the call passes the MANDATORY transactional proxy before Mockito records it.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> doAnswer(invocation -> {
            bothClaimed.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(telegramLinkRetentionService).unlinkPhonesWithoutOpenBookings(any(), any()));

        Future<GuestPiiAnonymisationResult> first = pool.submit(() -> service.anonymiseDue(cutoff, 1, 1));
        Future<GuestPiiAnonymisationResult> second = pool.submit(() -> service.anonymiseDue(cutoff, 1, 1));
        int anonymised = first.get(30, TimeUnit.SECONDS).bookingsAnonymised()
                + second.get(30, TimeUnit.SECONDS).bookingsAnonymised();

        assertThat(anonymised).as("each instance claimed one of the two rows").isEqualTo(2);
        assertThat(rows.linkExists(link)).isFalse();
    }
}
