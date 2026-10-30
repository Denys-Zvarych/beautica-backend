package com.beautica.notification.telegram.link;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.HeldDbLock;
import com.beautica.support.MutableTestClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.T0;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.rawToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/**
 * Phase 405 D8 — the {@code SELECT … FOR UPDATE} on the token row. Deterministic interleaving (as
 * {@code PendingRequestConcurrentSubmitIT}): call #1 runs inside a held-open transaction with its write flushed but
 * uncommitted; call #2 starts and is detected waiting on a lock via {@code pg_stat_activity}; then #1 commits.
 * With the lock, #2 re-reads the committed row and loses. Without it, #2 read the pre-#1 row and also wins (its
 * write merely waits on #1's row lock) — so dropping the lock fails these tests. The same harness proves the per-chat
 * claim lock (one claim per chat, no lock cycle) and the per-phone issue lock (cap of 5 live tokens).
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = TelegramLinkTestSupport.BOT_USERNAME_PROPERTY)
@DisplayName("TelegramLinkService — concurrency (phase 405 D8)")
class TelegramLinkConcurrencyIT extends AbstractIntegrationTest {

    private static final long CHAT = 7_100_000_001L;
    private static final long OTHER_CHAT = 7_100_000_002L;
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long POLL_MILLIS = 100;

    @Autowired private TelegramLinkService service;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private DataSource dataSource;

    private TelegramLinkTestSupport support;
    private String token;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        support = new TelegramLinkTestSupport(jdbcTemplate, passwordEncoder);
        token = issue();
    }

    private String issue() {
        return rawToken(service.issueForBooking(support.guestBooking(GUEST_PHONE), GUEST_PHONE));
    }

    private int countTokens(String state) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens WHERE state = ?",
                Integer.class, state);
    }

    @Test
    @DisplayName("one chat claims tokens A and B in parallel (first time) → exactly one ends CLAIMED")
    void should_holdOneClaim_when_chatClaimsTwoTokensConcurrently() throws Exception {
        String other = issue();

        Race<ClaimResult> race = race(() -> service.claim(token, CHAT), () -> service.claim(other, CHAT));

        assertThat(race.first()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(race.second()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(countTokens("CLAIMED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens WHERE state = 'CLAIMED' "
                + "AND token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, other)).isEqualTo(1);
    }

    @Test
    @DisplayName("re-claims of two tokens both already held by one chat, in parallel → no deadlock, both CLAIMED")
    void should_notDeadlock_when_chatReclaimsTwoHeldTokensConcurrently() throws Exception {
        String other = issue();
        jdbcTemplate.update("UPDATE telegram_link_tokens SET state = 'CLAIMED', claimed_chat_id = ?, claimed_at = ?",
                CHAT, Timestamp.from(T0));

        Race<ClaimResult> race = race(() -> service.claim(token, CHAT), () -> service.claim(other, CHAT));

        assertThat(race.first()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(race.second()).isEqualTo(ClaimResult.CLAIMED);
    }

    @Test
    @DisplayName("issue racing a held-open issue for the same phone → cap holds at 5 live tokens")
    void should_capLiveTokens_when_issueRacesHeldIssue() throws Exception {
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(1));
            issue();
        }
        clock.advance(Duration.ofSeconds(1));
        UUID bookingA = support.guestBooking(GUEST_PHONE);
        UUID bookingB = support.guestBooking(GUEST_PHONE);

        race(() -> service.issueForBooking(bookingA, GUEST_PHONE), () -> service.issueForBooking(bookingB, GUEST_PHONE));

        assertThat(countTokens("ISSUED")).isEqualTo(5);
    }

    @Test
    @DisplayName("8 parallel issues for one phone → all succeed, exactly 5 live tokens remain (3 evicted, none over-deleted)")
    void should_capLiveTokens_when_manyParallelIssues() throws Exception {
        // created_at comes from the service clock: setUp's token must be strictly older than the race's (which share
        // one instant and tie-break on token_hash), so it is deterministically evicted.
        clock.advance(Duration.ofSeconds(1));
        List<UUID> bookings = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            bookings.add(support.guestBooking(GUEST_PHONE));
        }
        CyclicBarrier start = new CyclicBarrier(bookings.size());
        List<String> issued = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(bookings.size())) {
            List<CompletableFuture<IssuedLink>> calls = bookings.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> {
                        awaitBarrier(start);
                        return service.issueForBooking(id, GUEST_PHONE);
                    }, pool))
                    .toList();
            for (CompletableFuture<IssuedLink> call : calls) {
                issued.add(rawToken(call.get(WAIT.toSeconds(), TimeUnit.SECONDS)));
            }
        }

        // setUp issued 1 + 8 here, serialised by the per-phone lock: each insert keeps the newest 4 live + itself,
        // so the steady state is exactly the cap — fewer means the eviction over-deleted.
        assertThat(issued).as("8 distinct raw tokens returned").doesNotHaveDuplicates().hasSize(8);
        assertThat(countTokens("ISSUED")).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class))
                .as("evicted rows are deleted, not left in another state").isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens "
                + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, token))
                .as("setUp's token is the oldest live one, so it is among the evicted").isZero();
        long survivors = issued.stream().filter(raw -> jdbcTemplate.queryForObject("SELECT count(*) FROM "
                + "telegram_link_tokens WHERE token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, raw) == 1)
                .count();
        assertThat(survivors).as("all 5 survivors are tokens issued by this race").isEqualTo(5);
    }

    @Test
    @DisplayName("claim(T2) while the chat holds T1 < T2: locks T1 before T2 (issue lock order) → no 40P01, both succeed")
    void should_lockInTokenHashOrder_when_claimReleasesEarlierClaim() throws Exception {
        byte[] t2 = jdbcTemplate.queryForObject("SELECT token_hash FROM telegram_link_tokens", byte[].class);
        byte[] t1 = new byte[32];
        jdbcTemplate.update("INSERT INTO telegram_link_tokens (token_hash, booking_id, phone_hash, state, expires_at, "
                        + "claimed_chat_id, claimed_at, created_at) SELECT ?, booking_id, phone_hash, 'CLAIMED', "
                        + "expires_at, ?, ?, ? FROM telegram_link_tokens WHERE token_hash = ?",
                t1, CHAT, Timestamp.from(T0), Timestamp.from(T0.minusSeconds(1)), t2);
        clock.advance(Duration.ofSeconds(1));
        UUID booking = support.guestBooking(GUEST_PHONE);

        CompletableFuture<ClaimResult> claim;
        CompletableFuture<IssuedLink> issue;
        try (HeldDbLock heldT1 = HeldDbLock.telegramLinkToken(dataSource, t1)) {
            claim = CompletableFuture.supplyAsync(() -> service.claim(token, CHAT));
            awaitBlockedOrDone(claim);
            assertThatCode(() -> jdbcTemplate.queryForList(
                    "SELECT 1 FROM telegram_link_tokens WHERE token_hash = ? FOR UPDATE NOWAIT", t2))
                    .as("claim must not hold T2 while it waits on T1").doesNotThrowAnyException();
            issue = CompletableFuture.supplyAsync(() -> service.issueForBooking(booking, GUEST_PHONE));
        }

        assertThat(claim.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(ClaimResult.CLAIMED);
        assertThat(issue.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM telegram_link_tokens WHERE token_hash = ?",
                String.class, t1)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("issue evicting a CLAIMED row that a parallel prove turns LINKED → the LINKED row is not deleted")
    void should_keepLinkedRow_when_issueEvictsRowBeingProven() throws Exception {
        service.claim(token, CHAT);
        for (int i = 1; i <= 4; i++) {
            jdbcTemplate.update("INSERT INTO telegram_link_tokens (token_hash, booking_id, phone_hash, state, "
                            + "expires_at, claimed_chat_id, claimed_at, created_at) "
                            + "SELECT decode(md5(random()::text) || md5(random()::text), 'hex'), booking_id, phone_hash, "
                            + "'CLAIMED', expires_at, ?, ?, ? FROM telegram_link_tokens "
                            + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))",
                    OTHER_CHAT + i, Timestamp.from(T0), Timestamp.from(T0.plusSeconds(i)), token);
        }
        clock.advance(Duration.ofSeconds(10));
        UUID booking = support.guestBooking(GUEST_PHONE);

        Race<Object> race = race(() -> service.proveContact(CHAT, GUEST_PHONE, CHAT, CHAT),
                () -> service.issueForBooking(booking, GUEST_PHONE));

        assertThat(race.first()).isEqualTo(ProofResult.LINKED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens WHERE state = 'LINKED' "
                + "AND token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class, token)).isEqualTo(1);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("two parallel claims of one token from different chats → exactly one CLAIMED")
    void should_claimOnce_when_twoChatsClaimConcurrently() throws Exception {
        Race<ClaimResult> race = race(() -> service.claim(token, CHAT), () -> service.claim(token, OTHER_CHAT));

        assertThat(race.first()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(race.second()).isEqualTo(ClaimResult.INVALID);
        assertThat(jdbcTemplate.queryForObject("SELECT claimed_chat_id FROM telegram_link_tokens", Long.class))
                .isEqualTo(CHAT);
    }

    @Test
    @DisplayName("two parallel proveContact for one chat → exactly one LINKED")
    void should_linkOnce_when_twoProofsRunConcurrently() throws Exception {
        service.claim(token, CHAT);

        Race<ProofResult> race = race(() -> service.proveContact(CHAT, GUEST_PHONE, CHAT, CHAT),
                () -> service.proveContact(CHAT, GUEST_PHONE, CHAT, CHAT));

        assertThat(race.first()).isEqualTo(ProofResult.LINKED);
        assertThat(race.second()).isEqualTo(ProofResult.NO_PENDING);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links", Integer.class)).isEqualTo(1);
    }

    private record Race<T>(T first, T second) {
    }

    private <T> Race<T> race(Supplier<T> firstCall, Supplier<T> secondCall) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<T> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            T result = firstCall.get();
            entityManager.flush();
            firstWritten.countDown();
            awaitQuietly(releaseFirst);
            return result;
        }));
        assertThat(firstWritten.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("call #1 wrote").isTrue();

        CompletableFuture<T> second = CompletableFuture.supplyAsync(secondCall);
        awaitBlockedOrDone(second);
        releaseFirst.countDown();

        return new Race<>(first.get(WAIT.toSeconds(), TimeUnit.SECONDS), second.get(WAIT.toSeconds(), TimeUnit.SECONDS));
    }

    private void awaitBlockedOrDone(CompletableFuture<?> second) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            try {
                second.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException done) {
                return;
            } catch (TimeoutException stillRunning) {
                if (isWaitingOnTelegramLock()) {
                    return;
                }
            }
        }
        fail("call #2 neither blocked on a lock nor finished within " + WAIT);
    }

    private boolean isWaitingOnTelegramLock() {
        Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                + "WHERE wait_event_type = 'Lock' AND datname = current_database() "
                + "AND (lower(query) LIKE '%telegram_link%' OR lower(query) LIKE '%pg_advisory_xact_lock%')",
                Integer.class);
        return waiting != null && waiting > 0;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("call #1 was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
