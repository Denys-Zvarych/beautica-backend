package com.beautica.notification.telegram.link;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 409 D4 — {@link TelegramLinkTokenPurgeJob} against real Postgres: the expiry + 1 day threshold in every state,
 * bounded batches, and {@code SKIP LOCKED} for both batched purges (tokens and long-BLOCKED links). The job bean is
 * off in the test profile, so it is built around a pinned clock.
 */
@DisplayName("Telegram link token purge (phase 409 D4)")
class TelegramLinkTokenPurgeJobIT extends AbstractIntegrationTest {

    @Autowired private TelegramLinkRetentionService retentionService;
    @Autowired private TelegramLinkOrphanBackstop backstop;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DataSource dataSource;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private TelegramLinkRows rows;
    private TelegramLinkTokenPurgeJob job;
    private UUID bookingId;
    private byte[] phoneHash;

    @BeforeEach
    void setUp() {
        rows = new TelegramLinkRows(jdbcTemplate);
        job = new TelegramLinkTokenPurgeJob(retentionService, backstop, Clock.fixed(now, ZoneOffset.UTC));
        TelegramLinkTestSupport support = new TelegramLinkTestSupport(jdbcTemplate, passwordEncoder);
        bookingId = support.guestBooking(TelegramLinkTestSupport.GUEST_PHONE);
        phoneHash = phoneHasher.hash(TelegramLinkTestSupport.GUEST_PHONE).hash();
    }

    @Test
    @DisplayName("deletes tokens expired more than 1 day ago in every state (incl. LINKED / MISMATCH)")
    void should_deleteEveryState_when_expiredMoreThanOneDay() {
        List<byte[]> expired = new ArrayList<>();
        for (TelegramTokenState state : TelegramTokenState.values()) {
            expired.add(rows.token(bookingId, phoneHash, state, now.minus(Duration.ofDays(1)).minusSeconds(60)));
        }

        job.run();

        assertThat(expired).noneMatch(rows::tokenExists);
    }

    @Test
    @DisplayName("keeps a token expired 1 min short of a day, a fresh one and one consumed (LINKED) yesterday")
    void should_keepTokens_when_withinOneDayOfExpiryOrFresh() {
        byte[] boundary = rows.token(bookingId, phoneHash, TelegramTokenState.MISMATCH,
                now.minus(Duration.ofDays(1)).plusSeconds(60));
        byte[] fresh = rows.token(bookingId, phoneHash, TelegramTokenState.ISSUED, now.plus(Duration.ofMinutes(25)));
        byte[] consumedYesterday = rows.token(bookingId, phoneHash, TelegramTokenState.LINKED,
                now.minus(Duration.ofHours(20)));

        job.run();

        assertThat(rows.tokenExists(boundary)).isTrue();
        assertThat(rows.tokenExists(fresh)).isTrue();
        assertThat(rows.tokenExists(consumedYesterday)).isTrue();
    }

    @Test
    @DisplayName("bounded batches: maxBatches × batchSize caps one run; the rest goes next run")
    void should_capRunAtBatchBudget_when_backlogExceedsIt() {
        for (int i = 0; i < 25; i++) {
            rows.token(bookingId, phoneHash, TelegramTokenState.ISSUED, now.minus(Duration.ofDays(3)).plusSeconds(i));
        }
        Instant cutoff = now.minus(Duration.ofDays(1));

        int firstRun = retentionService.purgeExpiredTokens(cutoff, 10, 2);
        int secondRun = retentionService.purgeExpiredTokens(cutoff, 10, 2);

        assertThat(firstRun).isEqualTo(20);
        assertThat(secondRun).isEqualTo(5);
        assertThat(rows.tokensOfPhone(phoneHash)).isZero();
    }

    @Test
    @DisplayName("SKIP LOCKED: a token row held by another transaction is skipped without waiting, the rest purged")
    void should_skipLockedToken_when_heldByAnotherTransaction() throws Exception {
        byte[] held = rows.token(bookingId, phoneHash, TelegramTokenState.CLAIMED, now.minus(Duration.ofDays(2)));
        byte[] free = rows.token(bookingId, phoneHash, TelegramTokenState.ISSUED, now.minus(Duration.ofDays(2)));

        int deleted;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRow(holder, "SELECT 1 FROM telegram_link_tokens WHERE token_hash = ? FOR UPDATE", held);
            deleted = retentionService.purgeExpiredTokens(now.minus(Duration.ofDays(1)), 10, 5);
            holder.rollback();
        }

        assertThat(deleted).isEqualTo(1);
        assertThat(rows.tokenExists(free)).isFalse();
        assertThat(rows.tokenExists(held)).isTrue();
    }

    @Test
    @DisplayName("SKIP LOCKED: a long-BLOCKED link held by another transaction is skipped, the rest purged")
    void should_skipLockedBlockedLink_when_heldByAnotherTransaction() throws Exception {
        Instant old = now.minus(Duration.ofDays(400));
        UUID held = rows.link(phoneHash, 101L, TelegramLinkStatus.BLOCKED, old);
        UUID free = rows.link(phoneHasher.hash(TelegramLinkTestSupport.OTHER_PHONE).hash(), 102L,
                TelegramLinkStatus.BLOCKED, old);

        int deleted;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRow(holder, "SELECT 1 FROM telegram_links WHERE id = ? FOR UPDATE", held);
            deleted = retentionService.purgeBlockedLinks(now, 10, 5);
            holder.rollback();
        }

        assertThat(deleted).isEqualTo(1);
        assertThat(rows.linkExists(free)).isFalse();
        assertThat(rows.linkExists(held)).isTrue();
    }

    private static void lockRow(Connection connection, String sql, Object key) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row to lock exists").isTrue();
            }
        }
    }
}
