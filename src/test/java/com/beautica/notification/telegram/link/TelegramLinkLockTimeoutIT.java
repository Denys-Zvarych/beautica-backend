package com.beautica.notification.telegram.link;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.HeldDbLock;
import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.T0;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.rawToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Phase 405 D8 — every lock a {@link TelegramLinkService} transaction waits on is bounded by the fused 3 s
 * {@code lock_timeout}: a lock held elsewhere past it aborts the call with {@code 55P03}, surfaced as a
 * {@link PessimisticLockingFailureException} (→ 409 via {@code GlobalExceptionHandler#handlePessimisticLockingFailure}
 * once 406/407 put HTTP in front), and the transaction rolls back completely: no token or link row is created,
 * deleted or changed. After the holder lets go, the same call succeeds — the failed attempt left nothing behind.
 *
 * <p>Each call under test runs on its own thread with a {@value #HANG_GUARD_SECONDS} s guard, so a regression that
 * drops a {@code set_config('lock_timeout', …)} fails fast as "hung" instead of parking for the holder's 30 s latch.
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = TelegramLinkTestSupport.BOT_USERNAME_PROPERTY)
@DisplayName("TelegramLinkService — lock_timeout (55P03) rolls back cleanly (phase 405 D8)")
class TelegramLinkLockTimeoutIT extends AbstractIntegrationTest {

    private static final long CHAT = 7_200_000_001L;
    private static final long OTHER_CHAT = 7_200_000_002L;
    private static final long HANG_GUARD_SECONDS = 12;
    /** The fused timeout is 3 s; a failure much sooner was not a lock wait. */
    private static final Duration MIN_WAIT = Duration.ofMillis(2_500);
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired private TelegramLinkService service;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private DataSource dataSource;

    private TelegramLinkTestSupport support;

    /**
     * The pool is already free of V83's session-level {@code lock_timeout}: {@code TestPoolSessionResetConfig}
     * (imported by {@link AbstractIntegrationTest}) soft-evicts it after migration. This re-asserts it per test, so
     * every 3 s abort below must come from the code under test's own fused {@code set_config}.
     */
    @BeforeEach
    void setUp() {
        assertThat(jdbcTemplate.queryForObject("SHOW lock_timeout", String.class))
                .as("no session-level lock_timeout left on pooled connections").isEqualTo("0");
        clock.set(T0);
        support = new TelegramLinkTestSupport(jdbcTemplate, passwordEncoder);
    }

    private String issue() {
        return rawToken(service.issueForBooking(support.guestBooking(GUEST_PHONE), GUEST_PHONE));
    }

    private byte[] tokenHash(String rawToken) {
        return jdbcTemplate.queryForObject("SELECT token_hash FROM telegram_link_tokens "
                + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", byte[].class, rawToken);
    }

    private List<Map<String, Object>> tokenRows() {
        return jdbcTemplate.queryForList("SELECT encode(token_hash, 'hex') AS token_hash, booking_id, "
                + "encode(phone_hash, 'hex') AS phone_hash, state, expires_at, claimed_chat_id, claimed_at, "
                + "finished_at FROM telegram_link_tokens ORDER BY token_hash");
    }

    private List<Map<String, Object>> linkRows() {
        return jdbcTemplate.queryForList("SELECT id, encode(phone_hash, 'hex') AS phone_hash, chat_id, status, "
                + "linked_at, status_changed_at, last_sent_at FROM telegram_links ORDER BY id");
    }

    /** Runs {@code call} while {@code holder} is held; returns what it threw, failing if it hung or succeeded. */
    private Throwable failureWhileHeld(HeldDbLock holder, Supplier<?> call) throws Exception {
        try (holder) {
            long started = System.nanoTime();
            CompletableFuture<?> future = CompletableFuture.supplyAsync(call);
            try {
                Object result = future.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
                return fail("expected a lock_timeout abort, but the call returned " + result);
            } catch (TimeoutException hung) {
                return fail("call still waiting after " + HANG_GUARD_SECONDS + " s — lock_timeout not in force");
            } catch (ExecutionException failed) {
                Duration waited = Duration.ofNanos(System.nanoTime() - started);
                assertThat(waited).as("failed only after waiting on the held lock, waited=%s", waited)
                        .isGreaterThanOrEqualTo(MIN_WAIT);
                return failed.getCause();
            }
        }
    }

    private static void assertLockTimeout(Throwable thrown) {
        assertThat(thrown).as("maps to 409 via GlobalExceptionHandler#handlePessimisticLockingFailure")
                .isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(sqlState(thrown)).as("Postgres lock_not_available, not a deadlock or another error")
                .isEqualTo(LOCK_NOT_AVAILABLE);
    }

    private static String sqlState(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    @Test
    @DisplayName("issue while the phone's issue lock is held past 3 s → 55P03; cap-full token set untouched, no row added")
    void should_abortIssueAndKeepTokens_when_phoneIssueLockHeld() throws Exception {
        for (int i = 0; i < 5; i++) {
            issue();
        }
        List<Map<String, Object>> before = tokenRows();
        UUID booking = support.guestBooking(GUEST_PHONE);
        byte[] phoneHash = phoneHasher.hash(GUEST_PHONE).hash();

        Throwable thrown = failureWhileHeld(HeldDbLock.telegramPhoneIssueLock(dataSource, phoneHash),
                () -> service.issueForBooking(booking, GUEST_PHONE));

        assertLockTimeout(thrown);
        assertThat(tokenRows()).as("no eviction, no insert").isEqualTo(before);
        assertThat(rawToken(service.issueForBooking(booking, GUEST_PHONE))).as("retry after release").hasSize(43);
        assertThat(tokenRows()).hasSize(5);
    }

    @Test
    @DisplayName("issue while the eviction victim's row is locked past 3 s → 55P03 (timeout survives into the DELETE); nothing evicted or added")
    void should_abortIssueAndKeepVictim_when_victimRowLocked() throws Exception {
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(1));
            issue();
        }
        List<Map<String, Object>> before = tokenRows();
        // created_at comes from the service clock, so the advances above make the first row the unique oldest.
        byte[] victim = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM telegram_link_tokens ORDER BY created_at LIMIT 1", byte[].class);
        UUID booking = support.guestBooking(GUEST_PHONE);

        Throwable thrown = failureWhileHeld(HeldDbLock.telegramLinkToken(dataSource, victim),
                () -> service.issueForBooking(booking, GUEST_PHONE));

        assertLockTimeout(thrown);
        assertThat(tokenRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("claim while the chat's claim lock is held past 3 s → 55P03; token stays ISSUED and unclaimed")
    void should_abortClaimAndKeepToken_when_chatClaimLockHeld() throws Exception {
        String token = issue();
        List<Map<String, Object>> before = tokenRows();

        Throwable thrown = failureWhileHeld(HeldDbLock.telegramChatClaimLock(dataSource, CHAT),
                () -> service.claim(token, CHAT));

        assertLockTimeout(thrown);
        assertThat(tokenRows()).isEqualTo(before);
        assertThat(service.claim(token, CHAT)).as("retry after release").isEqualTo(ClaimResult.CLAIMED);
    }

    @Test
    @DisplayName("claim of B while B's row is locked past 3 s → 55P03; the chat's claim on A is NOT released, B stays ISSUED")
    void should_abortClaimAndKeepPreviousClaim_when_tokenRowLocked() throws Exception {
        String tokenA = issue();
        String tokenB = issue();
        assertThat(service.claim(tokenA, CHAT)).isEqualTo(ClaimResult.CLAIMED);
        List<Map<String, Object>> before = tokenRows();

        Throwable thrown = failureWhileHeld(HeldDbLock.telegramLinkToken(dataSource, tokenHash(tokenB)),
                () -> service.claim(tokenB, CHAT));

        assertLockTimeout(thrown);
        assertThat(tokenRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("proveContact while the claimed token's row is locked past 3 s → 55P03; token stays CLAIMED, no link")
    void should_abortProofAndKeepClaim_when_tokenRowLocked() throws Exception {
        String token = issue();
        service.claim(token, CHAT);
        List<Map<String, Object>> before = tokenRows();

        Throwable thrown = failureWhileHeld(HeldDbLock.telegramLinkToken(dataSource, tokenHash(token)),
                () -> service.proveContact(CHAT, GUEST_PHONE, CHAT, CHAT));

        assertLockTimeout(thrown);
        assertThat(tokenRows()).isEqualTo(before);
        assertThat(linkRows()).isEmpty();
        assertThat(service.proveContact(CHAT, GUEST_PHONE, CHAT, CHAT)).as("retry after release")
                .isEqualTo(ProofResult.LINKED);
    }

    @Test
    @DisplayName("relink proof while the phone's link row is locked past 3 s → 55P03; flushed LINKED token rolled back to CLAIMED, link still on old chat")
    void should_rollBackFlushedTokenFinish_when_linkRowLockedDuringUpsert() throws Exception {
        String first = issue();
        service.claim(first, CHAT);
        assertThat(service.proveContact(CHAT, GUEST_PHONE, CHAT, CHAT)).isEqualTo(ProofResult.LINKED);
        String second = issue();
        service.claim(second, OTHER_CHAT);
        List<Map<String, Object>> tokensBefore = tokenRows();
        List<Map<String, Object>> linksBefore = linkRows();
        byte[] phoneHash = phoneHasher.hash(GUEST_PHONE).hash();

        Throwable thrown = failureWhileHeld(HeldDbLock.telegramLink(dataSource, phoneHash),
                () -> service.proveContact(OTHER_CHAT, GUEST_PHONE, OTHER_CHAT, OTHER_CHAT));

        assertLockTimeout(thrown);
        assertThat(tokenRows()).as("second token back to CLAIMED by OTHER_CHAT").isEqualTo(tokensBefore);
        assertThat(linkRows()).as("link not rebound").isEqualTo(linksBefore);
    }
}
