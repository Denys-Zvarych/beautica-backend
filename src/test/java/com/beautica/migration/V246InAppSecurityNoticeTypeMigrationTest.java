package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@code V246__inapp_security_notice_types.sql} + {@code V247} + {@code V248} (B516, AUTH-D19): the
 * in-app CHECKs admit the two security-notice types in their own shape (no booking / salon / request referent,
 * optional subject), stay VALID, and {@code users.password_reset_sms_sent_at} exists. Inserts run rolled back with FK
 * triggers suspended ({@code session_replication_role = replica}); CHECKs are always enforced.
 */
@DisplayName("V246–V248 migrations — security-notice feed types and the reset-SMS cooldown column")
class V246InAppSecurityNoticeTypeMigrationTest extends AbstractIntegrationTest {

    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUpTransactionTemplate() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private int insertRolledBack(String type, UUID bookingId, UUID subjectId, String dedupKey) {
        return transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            return jdbcTemplate.update("INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, "
                    + "subject_user_id, dedup_key) VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), subjectId, type,
                    bookingId, subjectId, dedupKey);
        });
    }

    @Test
    @DisplayName("the three re-added CHECKs are VALID after V247")
    void should_beValid_when_migrated() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT convalidated FROM pg_constraint
                 WHERE conrelid = 'in_app_notification'::regclass
                   AND conname IN ('in_app_notification_type_chk', 'in_app_notification_shape_chk',
                                   'in_app_notification_dedup_key_chk')
                """, Boolean.class)).containsExactly(true, true, true);
    }

    @Test
    @DisplayName("a phone- and an email-change notice keyed <TYPE>:<userId>:<epoch seconds> with only the subject are accepted")
    void should_acceptSecurityRows_when_inTheirShape() {
        UUID userId = UUID.randomUUID();

        assertThat(insertRolledBack("SECURITY_PHONE_CHANGED", null, userId,
                "SECURITY_PHONE_CHANGED:" + userId + ":1763632800")).isOne();
        assertThat(insertRolledBack("SECURITY_EMAIL_CHANGED", null, userId,
                "SECURITY_EMAIL_CHANGED:" + userId + ":1763632800")).isOne();
    }

    @Test
    @DisplayName("a security notice pointing at a booking is refused by the shape CHECK")
    void should_rejectSecurityRow_when_bookingReferenced() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRolledBack("SECURITY_PHONE_CHANGED", UUID.randomUUID(), userId,
                "SECURITY_PHONE_CHANGED:" + userId + ":1763632800"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("in_app_notification_shape_chk");
    }

    @Test
    @DisplayName("a security notice whose key suffix is not epoch seconds is refused by the dedup-key CHECK")
    void should_rejectSecurityRow_when_keyMalformed() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRolledBack("SECURITY_EMAIL_CHANGED", null, userId,
                "SECURITY_EMAIL_CHANGED:" + userId + ":not-a-number"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("in_app_notification_dedup_key_chk");
    }

    @Test
    @DisplayName("users.password_reset_sms_sent_at exists, nullable, timestamptz")
    void should_haveCooldownColumn_when_migrated() {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable FROM information_schema.columns
                 WHERE table_name = 'users' AND column_name = 'password_reset_sms_sent_at'
                """)).containsEntry("data_type", "timestamp with time zone").containsEntry("is_nullable", "YES");
    }
}
