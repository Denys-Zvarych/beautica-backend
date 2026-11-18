package com.beautica.auth.registration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B514 QA (Q21) — V244's constraints are the last line behind the phone-first identity rules, so their CONTENT is
 * asserted on real Postgres, not just "Flyway applied": AUTH-42 (a verified phone is unique among verified accounts,
 * email optional), AUTH-D15 (a verified phone is always {@code +380} E.164), the login-identity invariant (email OR
 * verified phone), the {@code phone_otps.purpose} allow-list + default, the {@code user_id} cascade, and the
 * registration-ticket hash / phone CHECKs.
 */
@DisplayName("V244 — phone identity constraints on users, phone_otps and phone_registration_tickets (B514, Testcontainers)")
class V244PhoneIdentityConstraintsIT extends AbstractIntegrationTest {

    private static final String SHA256_HEX = "a".repeat(64);

    private static String newPhone() {
        return "+38067" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID insertUser(String email, String phone, boolean phoneVerified) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, "
                        + "phone_number, phone_verified_at) VALUES (?, ?, 'x', 'CLIENT', true, false, ?, "
                        + (phoneVerified ? "now()" : "NULL") + ")",
                id, email, phone);
        return id;
    }

    private long usersWithPhone(String phone) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE phone_number = ?", Long.class, phone);
    }

    @Nested
    @DisplayName("users")
    class Users {

        @Test
        @DisplayName("a phone-only account (no email, verified +380 phone) is accepted, and two of them coexist "
                + "because NULL emails never collide on the unique email")
        void should_acceptPhoneOnlyAccounts_when_emailNullAndPhoneVerified() {
            String first = newPhone();
            String second = newPhone();

            insertUser(null, first, true);
            insertUser(null, second, true);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users WHERE email IS NULL AND phone_number IN (?, ?)", Long.class,
                    first, second)).as("both phone-only rows persisted").isEqualTo(2);
        }

        @Test
        @DisplayName("an account with neither an email nor a verified phone is refused by ck_users_login_identity")
        void should_rejectAccount_when_noEmailAndNoVerifiedPhone() {
            String phone = newPhone();

            assertThatThrownBy(() -> insertUser(null, phone, false))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_users_login_identity");

            assertThat(usersWithPhone(phone)).isZero();
        }

        @Test
        @DisplayName("removing the email from an email-only account is refused — the account would lose its only "
                + "login identifier")
        void should_rejectEmailRemoval_when_accountHasNoVerifiedPhone() {
            UUID id = insertUser("only-" + UUID.randomUUID() + "@beautica.test", null, false);

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE users SET email = NULL WHERE id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_users_login_identity");

            assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id))
                    .isNotNull();
        }

        @ParameterizedTest(name = "rejects verified phone [{0}]")
        @ValueSource(strings = {"0671234567", "380671234567", "+38067123456", "+3806712345678", "+48671234567",
                "+380 67 123 45 67", "+38067123456a"})
        @DisplayName("a verified phone that is not +380 followed by exactly 9 digits is refused by "
                + "ck_users_verified_phone_format")
        void should_rejectVerifiedPhone_when_notE164Ukrainian(String badPhone) {
            assertThatThrownBy(() -> insertUser(null, badPhone, true))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_users_verified_phone_format");

            assertThat(usersWithPhone(badPhone)).isZero();
        }

        @Test
        @DisplayName("a verified account with a NULL phone is refused (a CHECK alone would pass on NULL)")
        void should_rejectVerifiedAccount_when_phoneNull() {
            String email = "nullphone-" + UUID.randomUUID() + "@beautica.test";

            assertThatThrownBy(() -> insertUser(email, null, true))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_users_verified_phone_format");

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email))
                    .isZero();
        }

        @Test
        @DisplayName("legacy free-text phones stay unconstrained while unverified (existing rows keep loading)")
        void should_acceptLegacyPhone_when_unverified() {
            insertUser("legacy-" + UUID.randomUUID() + "@beautica.test", "067 123-45-67", false);

            assertThat(usersWithPhone("067 123-45-67")).isEqualTo(1);
        }

        @Test
        @DisplayName("a second VERIFIED account with the same phone is refused by ux_users_verified_phone")
        void should_rejectSecondVerifiedAccount_when_phoneAlreadyVerified() {
            String phone = newPhone();
            insertUser(null, phone, true);

            assertThatThrownBy(() -> insertUser(null, phone, true))
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("ux_users_verified_phone");

            assertThat(usersWithPhone(phone)).isEqualTo(1);
        }

        @Test
        @DisplayName("the unique index is partial: unverified duplicates of a verified phone are still accepted")
        void should_acceptUnverifiedDuplicates_when_phoneVerifiedElsewhere() {
            String phone = newPhone();
            insertUser(null, phone, true);

            insertUser("dup-a-" + UUID.randomUUID() + "@beautica.test", phone, false);
            insertUser("dup-b-" + UUID.randomUUID() + "@beautica.test", phone, false);

            assertThat(usersWithPhone(phone)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("phone_otps")
    class PhoneOtps {

        private long insertOtp(String purposeOrNull, UUID userIdOrNull) {
            String phone = newPhone();
            if (purposeOrNull == null) {
                jdbcTemplate.update("INSERT INTO phone_otps (phone, code_hash, expires_at, user_id) "
                        + "VALUES (?, ?, now() + interval '10 minutes', ?)", phone, SHA256_HEX, userIdOrNull);
            } else {
                jdbcTemplate.update("INSERT INTO phone_otps (phone, code_hash, expires_at, purpose, user_id) "
                                + "VALUES (?, ?, now() + interval '10 minutes', ?, ?)",
                        phone, SHA256_HEX, purposeOrNull, userIdOrNull);
            }
            return jdbcTemplate.queryForObject("SELECT id FROM phone_otps WHERE phone = ?", Long.class, phone);
        }

        @Test
        @DisplayName("a row written by the pre-B514 insert path (no purpose) defaults to GUEST_BOOKING")
        void should_defaultPurposeToGuestBooking_when_purposeOmitted() {
            long id = insertOtp(null, null);

            assertThat(jdbcTemplate.queryForObject("SELECT purpose FROM phone_otps WHERE id = ?", String.class, id))
                    .isEqualTo("GUEST_BOOKING");
        }

        @ParameterizedTest(name = "accepts purpose [{0}]")
        @ValueSource(strings = {"GUEST_BOOKING", "REGISTRATION", "PHONE_ADD", "PHONE_CHANGE", "PASSWORD_RESET"})
        @DisplayName("every purpose of the allow-list (B514 + the B516 reservations) is accepted")
        void should_acceptPurpose_when_inAllowList(String purpose) {
            long id = insertOtp(purpose, null);

            assertThat(jdbcTemplate.queryForObject("SELECT purpose FROM phone_otps WHERE id = ?", String.class, id))
                    .isEqualTo(purpose);
        }

        @ParameterizedTest(name = "rejects purpose [{0}]")
        @ValueSource(strings = {"LOGIN", "registration", ""})
        @DisplayName("a purpose outside the allow-list (incl. wrong case) is refused by ck_phone_otps_purpose")
        void should_rejectPurpose_when_notInAllowList(String purpose) {
            assertThatThrownBy(() -> insertOtp(purpose, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_phone_otps_purpose");
        }

        @Test
        @DisplayName("deleting the account deletes its user-bound OTPs (ON DELETE CASCADE) and leaves anonymous ones")
        void should_cascadeUserBoundOtps_when_userDeleted() {
            UUID userId = insertUser(null, newPhone(), true);
            long bound = insertOtp("PHONE_ADD", userId);
            long anonymous = insertOtp("REGISTRATION", null);

            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM phone_otps WHERE id = ?", Long.class,
                    bound)).as("user-bound OTP removed with its account").isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM phone_otps WHERE id = ?", Long.class,
                    anonymous)).as("anonymous OTP untouched").isOne();
        }

        @Test
        @DisplayName("a user_id that matches no account is refused by the FK")
        void should_rejectOtp_when_userIdUnknown() {
            assertThatThrownBy(() -> insertOtp("PHONE_ADD", UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("phone_otps_user_id_fkey");
        }
    }

    @Nested
    @DisplayName("phone_registration_tickets")
    class Tickets {

        private void insertTicket(String hash, String phone) {
            jdbcTemplate.update("INSERT INTO phone_registration_tickets (id, ticket_hash, phone, expires_at) "
                    + "VALUES (?, ?, ?, now() + interval '15 minutes')", UUID.randomUUID(), hash, phone);
        }

        private long ticketsFor(String phone) {
            return jdbcTemplate.queryForObject("SELECT count(*) FROM phone_registration_tickets WHERE phone = ?",
                    Long.class, phone);
        }

        @Test
        @DisplayName("a lowercase SHA-256 hex hash and a +380 phone are accepted; used defaults to false")
        void should_acceptTicket_when_hashAndPhoneWellFormed() {
            String phone = newPhone();

            insertTicket("0123456789abcdef".repeat(4), phone);

            assertThat(jdbcTemplate.queryForObject("SELECT used FROM phone_registration_tickets WHERE phone = ?",
                    Boolean.class, phone)).isFalse();
        }

        @ParameterizedTest(name = "rejects hash [{0}]")
        @ValueSource(strings = {"raw-ticket-value", "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        @DisplayName("anything but 64 lowercase hex chars (a raw ticket, uppercase, 63 chars) is refused — the raw "
                + "ticket can never be stored by mistake")
        void should_rejectTicket_when_hashNotLowercaseSha256Hex(String badHash) {
            String phone = newPhone();

            assertThatThrownBy(() -> insertTicket(badHash, phone))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_phone_registration_tickets_hash");

            assertThat(ticketsFor(phone)).isZero();
        }

        @Test
        @DisplayName("a phone that is not +380 E.164 is refused by ck_phone_registration_tickets_phone")
        void should_rejectTicket_when_phoneNotE164Ukrainian() {
            assertThatThrownBy(() -> insertTicket(SHA256_HEX, "0671234567"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_phone_registration_tickets_phone");

            assertThat(ticketsFor("0671234567")).isZero();
        }

        @Test
        @DisplayName("the same hash twice is refused by ux_phone_registration_tickets_hash (a ticket is single)")
        void should_rejectTicket_when_hashDuplicated() {
            String hash = "b".repeat(64);
            insertTicket(hash, newPhone());

            assertThatThrownBy(() -> insertTicket(hash, newPhone()))
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("ux_phone_registration_tickets_hash");
        }
    }
}
