package com.beautica.user.email;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B516 QA (Q21) — V245 {@code user_pending_emails}: the CHECKs, the one-row-per-account key and the cascade are the
 * table's contract, and {@link PendingEmailCleanupJob} drops abandoned (PII) rows without touching a live lockout.
 * Shares the phone-identity context (no new Spring context).
 */
@DisplayName("user_pending_emails schema (V245) and the abandoned-request sweep (B516)")
class PendingEmailSchemaAndCleanupIT extends AbstractPhoneIdentityIT {

    private static final String VALID_HASH = "0123456789abcdef".repeat(4);
    private static final Duration RETENTION = Duration.ofHours(24);

    @Autowired
    private PendingEmailCleanupJob cleanupJob;

    private UUID newUser() {
        return seedUser("CLIENT", null, false, newPhone(), null);
    }

    private void insertPending(UUID userId, String email, String hash, Instant sentAt, Instant expiresAt,
                               int attempts, Instant lockedUntil) {
        jdbcTemplate.update("INSERT INTO user_pending_emails (user_id, email, code_hash, sent_at, expires_at, attempts, "
                        + "locked_until) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, email, hash, Timestamp.from(sentAt), Timestamp.from(expiresAt), attempts,
                lockedUntil == null ? null : Timestamp.from(lockedUntil));
    }

    private void insertFresh(UUID userId, String email) {
        Instant now = Instant.now();
        insertPending(userId, email, VALID_HASH, now, now.plus(Duration.ofMinutes(15)), 0, null);
    }

    private int rowsFor(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_pending_emails WHERE user_id = ?",
                Integer.class, userId);
    }

    @Nested
    @DisplayName("V245 constraints")
    class Constraints {

        @Test
        @DisplayName("a lower-cased trimmed email with a hex code hash is accepted")
        void should_acceptRow_when_emailNormalisedAndHashHex() {
            UUID id = newUser();

            insertFresh(id, "a@b.test");

            assertThat(rowsFor(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("an email that is not lower-cased, not trimmed or has no local part is refused")
        void should_rejectEmail_when_notLowerCasedOrTrimmedOrMissingLocalPart() {
            UUID id = newUser();

            for (String bad : new String[] {"A@b.test", " a@b.test", "a@b.test ", "@b.test", "no-at-sign"}) {
                assertThatThrownBy(() -> insertFresh(id, bad))
                        .as("email %s", bad)
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("ck_user_pending_emails_email_normalised");
            }
            assertThat(rowsFor(id)).isZero();
        }

        @Test
        @DisplayName("a code hash that is not SHA-256 lower hex is refused")
        void should_rejectCodeHash_when_notSha256LowerHex() {
            UUID id = newUser();
            Instant now = Instant.now();

            for (String bad : new String[] {"123456", VALID_HASH.toUpperCase(), VALID_HASH.substring(1) + "g"}) {
                assertThatThrownBy(() -> insertPending(id, "a@b.test", bad, now, now.plusSeconds(900), 0, null))
                        .as("hash %s", bad)
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("ck_user_pending_emails_code_hash");
            }
        }

        @Test
        @DisplayName("negative attempts are refused")
        void should_rejectRow_when_attemptsNegative() {
            UUID id = newUser();
            Instant now = Instant.now();

            assertThatThrownBy(() -> insertPending(id, "a@b.test", VALID_HASH, now, now.plusSeconds(900), -1, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_user_pending_emails_attempts");
        }

        @Test
        @DisplayName("an expiry not after the send time is refused")
        void should_rejectRow_when_expiryNotAfterSentAt() {
            UUID id = newUser();
            Instant now = Instant.now();

            assertThatThrownBy(() -> insertPending(id, "a@b.test", VALID_HASH, now, now, 0, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_user_pending_emails_expiry");
        }

        @Test
        @DisplayName("an account holds at most one pending address")
        void should_allowOnlyOnePendingAddress_when_accountInsertsASecondRow() {
            UUID id = newUser();
            insertFresh(id, "first@b.test");

            assertThatThrownBy(() -> insertFresh(id, "second@b.test"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(rowsFor(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("deleting the account drops its pending address")
        void should_dropPendingAddress_when_accountDeleted() {
            UUID id = newUser();
            insertFresh(id, "gone@b.test");

            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);

            assertThat(rowsFor(id)).as("ON DELETE CASCADE").isZero();
        }

        @Test
        @DisplayName("a pending address for a missing account is refused")
        void should_rejectRow_when_userDoesNotExist() {
            assertThatThrownBy(() -> insertFresh(UUID.randomUUID(), "orphan@b.test"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("PendingEmailCleanupJob")
    class Cleanup {

        @Test
        @DisplayName("the cleanup sweep deletes only abandoned rows")
        void should_deleteOnlyAbandonedRows_when_sweepRuns() {
            Instant now = Instant.now();
            Instant longAgo = now.minus(RETENTION).minus(Duration.ofHours(2));
            UUID abandoned = newUser();
            insertPending(abandoned, "abandoned@b.test", VALID_HASH, longAgo, longAgo.plusSeconds(900), 0, null);
            UUID abandonedOldLock = newUser();
            insertPending(abandonedOldLock, "oldlock@b.test", VALID_HASH, longAgo, longAgo.plusSeconds(900), 5,
                    longAgo.plusSeconds(1800));
            UUID recentlyExpired = newUser();
            Instant hourAgo = now.minus(Duration.ofHours(1));
            insertPending(recentlyExpired, "recent@b.test", VALID_HASH, hourAgo, hourAgo.plusSeconds(900), 0, null);
            UUID lockRunning = newUser();
            insertPending(lockRunning, "locked@b.test", VALID_HASH, longAgo, longAgo.plusSeconds(900), 5,
                    now.plus(Duration.ofMinutes(10)));
            UUID live = newUser();
            insertFresh(live, "live@b.test");

            cleanupJob.sweep();

            assertThat(rowsFor(abandoned)).as("expired > 24 h ago, no lock").isZero();
            assertThat(rowsFor(abandonedOldLock)).as("expired and lock ended > 24 h ago").isZero();
            assertThat(rowsFor(recentlyExpired)).as("expired 45 min ago: inside retention").isEqualTo(1);
            assertThat(rowsFor(lockRunning)).as("a running lockout survives the sweep").isEqualTo(1);
            assertThat(rowsFor(live)).as("a live code").isEqualTo(1);
        }
    }
}
