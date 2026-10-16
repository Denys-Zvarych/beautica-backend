package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.config.TestSecurityConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression for the stale-entity-under-row-lock bug: a request that WAITED on the
 * {@code SELECT ... FOR UPDATE} row lock must observe the code/expiry the lock holder committed,
 * not a copy loaded by an earlier non-locking pre-read in the same persistence context.
 *
 * <p>Deterministic (no timing race): the test thread holds the row lock, starts the second request,
 * waits until Postgres reports that request blocked on a lock (by then its pre-read has already run),
 * and only then commits the "concurrent resend" and releases the lock.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Resend / forgot-password under a contended row lock - integration")
class StaleReadUnderRowLockIT extends AbstractIntegrationTest {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AuthService authService;
    @Autowired
    private PasswordResetService passwordResetService;

    @AfterAll
    static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    @Test
    @DisplayName("should send no second mail when a resend waited on the lock while another resend issued the code (no stored code at pre-read)")
    void should_sendNoSecondMail_when_resendWaitedOnLockAndOtherResendIssuedCode() {
        var email = "stale.resend@beautica.test";
        register(email);
        // Wiped-code state: the new no-code path is reachable (expiresAt == null at pre-read).
        jdbcTemplate.update("UPDATE users SET verification_code_hash = NULL, verification_code_expires_at = NULL WHERE email = ?", email);

        Future<Throwable> waiter = runWhileRowLockedThenCommit(email,
                () -> authService.resendVerification(new ResendVerificationRequest(email)),
                "UPDATE users SET verification_code_hash = repeat('a', 64), verification_code_expires_at = ? WHERE email = ?");

        assertThat(join(waiter))
                .as("the waiting resend must see the freshly issued code and be throttled")
                .isInstanceOf(ResendThrottledException.class);
        verify(emailNotificationService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("should send no second mail when forgot-password waited on the lock while another request issued the code")
    void should_sendNoSecondMail_when_forgotPasswordWaitedOnLockAndOtherRequestIssuedCode() {
        var email = "stale.reset@beautica.test";
        register(email);
        jdbcTemplate.update("UPDATE users SET email_verified = true, password_reset_code_hash = NULL, "
                + "password_reset_code_expires_at = NULL WHERE email = ?", email);

        Future<Throwable> waiter = runWhileRowLockedThenCommit(email,
                () -> passwordResetService.requestReset(new ForgotPasswordRequest(email)),
                "UPDATE users SET password_reset_code_hash = repeat('a', 64), password_reset_code_expires_at = ? WHERE email = ?");

        assertThat(join(waiter)).as("forgot-password never surfaces the cooldown").isNull();
        verify(emailNotificationService, never()).sendPasswordResetOtpEmail(any(), any());
    }

    /**
     * Holds the row lock, starts {@code request} on another thread, waits until it is blocked on that
     * lock, then commits {@code codeIssuingUpdate} (expiry = now + 15 min, i.e. "issued just now") and
     * releases the lock. Returns the request's outcome (null = returned normally).
     */
    private Future<Throwable> runWhileRowLockedThenCommit(String email, Runnable request, String codeIssuingUpdate) {
        Mockito.reset(emailNotificationService);
        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForList("SELECT id FROM users WHERE email = ? FOR UPDATE", email);
            Future<Throwable> waiter = EXECUTOR.submit(() -> {
                try {
                    request.run();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            // Narrowed so an unrelated waiter (another DB, another table, a lock not held by THIS
            // transaction) can never satisfy the wait and release the lock before the request's
            // pre-read has run: same database, a users FOR [NO KEY] UPDATE statement, blocked
            // specifically by the lock-holding backend. The holder pid is captured HERE, on the
            // transaction thread: Awaitility evaluates the condition on its own poll thread, where
            // jdbcTemplate borrows a different pooled connection (pg_backend_pid() there is NOT the holder).
            int holderPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
            await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT count(*) > 0 FROM pg_stat_activity"
                            + " WHERE datname = current_database()"
                            + " AND wait_event_type = 'Lock'"
                            + " AND ? = ANY(pg_blocking_pids(pid))"
                            + " AND query ILIKE '%from users%'"
                            // Alternation, not "(no key )?": a bare '?' would be taken as a JDBC bind marker.
                            + " AND query ~* 'for (no key update|update)'",
                    Boolean.class, holderPid)));
            jdbcTemplate.update(codeIssuingUpdate, Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))), email);
            return waiter;
        });
    }

    private static Throwable join(Future<Throwable> f) {
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("waiting request did not finish", e);
        }
    }

    private void register(String email) {
        var response = restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "Str0ngP@ss1!", SelfRegistrationRole.CLIENT, "Anna", "Test",
                        "+380501234567", null), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
