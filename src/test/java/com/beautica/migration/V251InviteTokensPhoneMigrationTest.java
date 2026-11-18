package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B520 — V251 on real Postgres: {@code invite_tokens.phone} / {@code invited_by}, email now nullable, exactly one
 * recipient, the phone format CHECK, the active-per-(salon, phone) unique index, and the inviter FK's SET NULL.
 */
@DisplayName("V251 migration — invite_tokens phone recipient, constraints and indexes (B520)")
class V251InviteTokensPhoneMigrationTest extends AbstractIntegrationTest {

    private static final String PHONE = "+380501234567";

    private UUID ownerId;
    private UUID salonId;

    @BeforeEach
    void seedSalon() {
        ownerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)", ownerId, "v251-" + ownerId + "@beautica.test");
        salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, 'V251 Salon', true, NOW(), NOW(), ?)", salonId, ownerId, testCityId());
    }

    private void insert(String email, String phone, boolean isUsed, Timestamp revokedAt, String reason) {
        jdbcTemplate.update("INSERT INTO invite_tokens (id, token, email, phone, salon_id, role, expires_at, is_used, "
                        + "revoked_at, revoked_reason, invited_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'SALON_MASTER', ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), "v251-" + UUID.randomUUID(), email, phone, salonId,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)), isUsed, revokedAt, reason, ownerId);
    }

    @Test
    @DisplayName("a phone-only row and an email-only row are both valid")
    void should_acceptExactlyOneRecipient_when_phoneOrEmailAlone() {
        insert(null, PHONE, false, null, null);
        insert("legacy@beautica.test", null, false, null, null);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE salon_id = ?",
                Integer.class, salonId)).isEqualTo(2);
    }

    @Test
    @DisplayName("both recipients, or neither, violate ck_invite_tokens_one_recipient")
    void should_rejectRow_when_bothOrNeitherRecipient() {
        assertThatThrownBy(() -> insert("both@beautica.test", PHONE, false, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_invite_tokens_one_recipient");
        assertThatThrownBy(() -> insert(null, null, false, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_invite_tokens_one_recipient");
    }

    @Test
    @DisplayName("a phone that is not +380 + 9 digits violates ck_invite_tokens_phone_format")
    void should_rejectRow_when_phoneNotNormalised() {
        assertThatThrownBy(() -> insert(null, "0501234567", false, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_invite_tokens_phone_format");
    }

    @Test
    @DisplayName("two ACTIVE invites to one phone in one salon violate ux_invite_tokens_active_phone; a superseded "
            + "one does not hold the slot")
    void should_allowOneActivePerSalonAndPhone_when_secondInserted() {
        insert(null, PHONE, false, Timestamp.from(Instant.now()), "SUPERSEDED");
        insert(null, PHONE, false, null, null);

        assertThatThrownBy(() -> insert(null, PHONE, false, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_invite_tokens_active_phone");
    }

    @Test
    @DisplayName("deleting the inviter sets invited_by to NULL and keeps the invite (never blocks DELETE FROM users)")
    void should_nullInviter_when_inviterDeleted() {
        UUID inviterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                + "VALUES (?, ?, 'x', 'SALON_ADMIN', true, true)", inviterId, "v251-admin-" + inviterId + "@beautica.test");
        UUID inviteId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO invite_tokens (id, token, phone, salon_id, role, expires_at, is_used, "
                        + "invited_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, false, ?, NOW(), NOW())",
                inviteId, "v251-fk-" + inviteId, PHONE, salonId,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)), inviterId);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", inviterId);

        assertThat(jdbcTemplate.queryForObject("SELECT invited_by FROM invite_tokens WHERE id = ?", UUID.class,
                inviteId)).isNull();
    }
}
