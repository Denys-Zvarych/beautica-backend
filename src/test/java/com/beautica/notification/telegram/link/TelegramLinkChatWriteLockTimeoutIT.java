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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * Phase 406 audit — the chat-keyed link writes the webhook makes ({@code unlinkChat}, {@code markBlocked(chat)},
 * {@code reactivate}) are bounded by the same fused, transaction-local 3 s {@code lock_timeout} as the 405 claim /
 * proof paths: a link row held elsewhere aborts with {@code 55P03} (which the webhook answers «try again») instead of
 * parking a request thread, and the row is left unchanged.
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = TelegramLinkTestSupport.BOT_USERNAME_PROPERTY)
@DisplayName("TelegramLinkService — chat-keyed link writes honour lock_timeout (phase 406 audit)")
class TelegramLinkChatWriteLockTimeoutIT extends AbstractIntegrationTest {

    private static final long CHAT = 7_300_000_001L;
    private static final long HANG_GUARD_SECONDS = 12;
    private static final Duration MIN_WAIT = Duration.ofMillis(2_500);
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired private TelegramLinkService service;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private DataSource dataSource;

    private TelegramLinkTestSupport support;

    @BeforeEach
    void setUp() {
        assertThat(jdbcTemplate.queryForObject("SHOW lock_timeout", String.class))
                .as("no session-level lock_timeout left on pooled connections").isEqualTo("0");
        clock.set(T0);
        support = new TelegramLinkTestSupport(jdbcTemplate, passwordEncoder);
        String token = rawToken(service.issueForBooking(support.guestBooking(GUEST_PHONE), GUEST_PHONE));
        service.claim(token, CHAT);
        assertThat(service.proveContact(CHAT, GUEST_PHONE, CHAT, CHAT)).isEqualTo(ProofResult.LINKED);
    }

    private List<Map<String, Object>> linkRows() {
        return jdbcTemplate.queryForList("SELECT id, chat_id, status, status_changed_at FROM telegram_links");
    }

    private HeldDbLock heldLinkRow() throws Exception {
        return HeldDbLock.telegramLink(dataSource, phoneHasher.hash(GUEST_PHONE).hash());
    }

    private Throwable failureWhileHeld(Supplier<?> call) throws Exception {
        try (HeldDbLock ignored = heldLinkRow()) {
            long started = System.nanoTime();
            CompletableFuture<?> future = CompletableFuture.supplyAsync(call);
            try {
                Object result = future.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
                return fail("expected a lock_timeout abort, but the call returned " + result);
            } catch (TimeoutException hung) {
                return fail("call still waiting after " + HANG_GUARD_SECONDS + " s — lock_timeout not in force");
            } catch (ExecutionException failed) {
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(MIN_WAIT);
                return failed.getCause();
            }
        }
    }

    private static void assertLockTimeout(Throwable thrown) {
        assertThat(thrown).as("the webhook's «try again» catch covers it")
                .isInstanceOfAny(PessimisticLockingFailureException.class, QueryTimeoutException.class);
        String state = null;
        for (Throwable t = thrown; t != null && state == null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                state = sql.getSQLState();
            }
        }
        assertThat(state).isEqualTo(LOCK_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("abort unlink and keep link when link row held")
    void should_abortUnlinkAndKeepLink_when_linkRowHeld() throws Exception {
        List<Map<String, Object>> before = linkRows();

        Throwable thrown = failureWhileHeld(() -> service.unlinkChat(CHAT));

        assertLockTimeout(thrown);
        assertThat(linkRows()).isEqualTo(before);
        assertThat(service.unlinkChat(CHAT)).as("retry after release").isEqualTo(1);
    }

    @Test
    @DisplayName("abort phone unlink and keep link when link row held (phase 407 audit)")
    void should_abortPhoneUnlinkAndKeepLink_when_linkRowHeld() throws Exception {
        List<Map<String, Object>> before = linkRows();

        Throwable thrown = failureWhileHeld(() -> service.unlinkPhone(GUEST_PHONE));

        assertLockTimeout(thrown);
        assertThat(linkRows()).isEqualTo(before);
        assertThat(service.unlinkPhone(GUEST_PHONE)).as("retry after release").isTrue();
    }

    @Test
    @DisplayName("abort mark blocked and keep active when link row held")
    void should_abortMarkBlockedAndKeepActive_when_linkRowHeld() throws Exception {
        List<Map<String, Object>> before = linkRows();

        Throwable thrown = failureWhileHeld(() -> {
            service.markBlocked(CHAT);
            return null;
        });

        assertLockTimeout(thrown);
        assertThat(linkRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("abort reactivate and keep blocked when link row held")
    void should_abortReactivateAndKeepBlocked_when_linkRowHeld() throws Exception {
        service.markBlocked(CHAT);
        List<Map<String, Object>> before = linkRows();

        Throwable thrown = failureWhileHeld(() -> service.reactivate(CHAT));

        assertLockTimeout(thrown);
        assertThat(linkRows()).isEqualTo(before);
        assertThat(service.reactivate(CHAT)).as("retry after release").isEqualTo(1);
    }
}
