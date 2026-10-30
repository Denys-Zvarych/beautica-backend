package com.beautica.support;

import com.beautica.notification.telegram.link.TelegramLinkTokenRepository;
import com.beautica.salon.repository.SalonRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Holds a Postgres lock on a dedicated thread in a raw JDBC transaction (autocommit off) until
 * {@link #close()}, which rolls it back. For lock-timeout ITs: the code under test must time out on
 * the held lock ({@code 55P03} → 409) instead of hanging. Raw JDBC on its own thread, so neither a Spring
 * transaction nor {@link ThreadJdbcFootprint} of the calling thread sees it.
 */
public final class HeldDbLock implements AutoCloseable {

    private static final long LATCH_SECONDS = 30;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final Future<?> holder;

    private HeldDbLock(DataSource dataSource, String sql, Object parameter) throws Exception {
        holder = executor.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, parameter instanceof String[] texts
                            ? connection.createArrayOf("text", texts)
                            : parameter);
                    statement.execute();
                    held.countDown();
                    release.await(LATCH_SECONDS, TimeUnit.SECONDS);
                } finally {
                    connection.rollback();
                }
            }
            return null;
        });
        if (!held.await(LATCH_SECONDS, TimeUnit.SECONDS)) {
            holder.get(1, TimeUnit.SECONDS);
            throw new IllegalStateException("lock holder never acquired: " + sql);
        }
    }

    /** Holds the salon booking-slug advisory lock of every candidate ({@link SalonRepository#lockBookingSlugCandidates}). */
    public static HeldDbLock salonSlugCandidates(DataSource dataSource, String[] candidates) throws Exception {
        return new HeldDbLock(dataSource, "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(c, "
                + SalonRepository.BOOKING_SLUG_LOCK_SEED + ")) FROM unnest(?::text[]) AS c ORDER BY c) AS l",
                candidates);
    }

    /** Holds a {@code FOR NO KEY UPDATE} row lock on the salon. */
    public static HeldDbLock salonRow(DataSource dataSource, UUID salonId) throws Exception {
        return new HeldDbLock(dataSource, "SELECT id FROM salons WHERE id = ? FOR NO KEY UPDATE", salonId);
    }

    /** Holds a {@code FOR NO KEY UPDATE} row lock on the master (blocks its conditional slug {@code UPDATE}). */
    public static HeldDbLock masterRow(DataSource dataSource, UUID masterId) throws Exception {
        return new HeldDbLock(dataSource, "SELECT id FROM masters WHERE id = ? FOR NO KEY UPDATE", masterId);
    }

    /** Holds a {@code FOR UPDATE} row lock on one {@code telegram_link_tokens} row (phase 405 lock-order IT). */
    public static HeldDbLock telegramLinkToken(DataSource dataSource, byte[] tokenHash) throws Exception {
        return new HeldDbLock(dataSource, "SELECT token_hash FROM telegram_link_tokens WHERE token_hash = ? FOR UPDATE",
                tokenHash);
    }

    /** Holds a {@code FOR UPDATE} row lock on the {@code telegram_links} row of one phone hash (phase 405). */
    public static HeldDbLock telegramLink(DataSource dataSource, byte[] phoneHash) throws Exception {
        return new HeldDbLock(dataSource, "SELECT id FROM telegram_links WHERE phone_hash = ? FOR UPDATE", phoneHash);
    }

    /**
     * Holds the per-phone Telegram link-token issue advisory lock
     * ({@code TelegramLinkTokenRepository#acquirePhoneIssueLock}) — same key expression and seed.
     */
    public static HeldDbLock telegramPhoneIssueLock(DataSource dataSource, byte[] phoneHash) throws Exception {
        return new HeldDbLock(dataSource, "SELECT pg_advisory_xact_lock(hashtextextended(encode(?, 'hex'), "
                + TelegramLinkTokenRepository.PHONE_ISSUE_LOCK_SEED + "))", phoneHash);
    }

    /**
     * Holds the per-chat Telegram claim advisory lock ({@code TelegramLinkTokenRepository#acquireChatClaimLock})
     * — same key expression and seed.
     */
    public static HeldDbLock telegramChatClaimLock(DataSource dataSource, long chatId) throws Exception {
        return new HeldDbLock(dataSource, "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), "
                + TelegramLinkTokenRepository.CHAT_CLAIM_LOCK_SEED + "))", chatId);
    }

    @Override
    public void close() throws Exception {
        release.countDown();
        try {
            holder.get(LATCH_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
