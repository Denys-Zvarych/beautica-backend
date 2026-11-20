package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@code V253__inapp_booking_reminder_2h_dedup_key.sql} + {@code V254} (phase 522): the dedup-key
 * CHECK additionally admits the 2 h reminder key {@code BOOKING_REMINDER:<uuid>:<epoch>:H2} — for
 * {@code BOOKING_REMINDER} only, with a literal {@code H2} 4th segment — keeps the 24 h key, and is VALID.
 *
 * <p>Inserts run in a rolled-back transaction under {@code SET LOCAL session_replication_role = replica} (FK triggers
 * suspended, CHECKs still enforced), V241-test pattern. Nothing is committed.
 */
@DisplayName("V253/V254 migration — in_app_notification_dedup_key_chk admits BOOKING_REMINDER:<id>:<epoch>:H2 only")
class V253InAppBookingReminderTwoHourDedupKeyMigrationTest extends AbstractIntegrationTest {

    private static final String EPOCH = "1763202600";
    private static final String DEDUP_CHK = "in_app_notification_dedup_key_chk";

    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUpTransactionTemplate() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("in_app_notification_dedup_key_chk is VALID after V254")
    void should_beValidated_when_constraintRead() {
        Boolean validated = jdbcTemplate.queryForObject("""
                SELECT convalidated FROM pg_constraint
                 WHERE conrelid = 'in_app_notification'::regclass AND conname = ?
                """, Boolean.class, DEDUP_CHK);

        assertThat(validated).isTrue();
    }

    @Test
    @DisplayName("BOOKING_REMINDER:<bookingId>:<epoch>:H2 (the 2 h key) is accepted")
    void should_acceptTwoHourKey_when_bookingReminder() {
        UUID bookingId = UUID.randomUUID();

        int inserted = insertRolledBack("BOOKING_REMINDER", bookingId,
                "BOOKING_REMINDER:" + bookingId + ":" + EPOCH + ":H2");

        assertThat(inserted).isOne();
    }

    @Test
    @DisplayName("BOOKING_REMINDER:<bookingId>:<epoch> (the 24 h key, unchanged) is still accepted")
    void should_acceptDayBeforeKey_when_bookingReminder() {
        UUID bookingId = UUID.randomUUID();

        int inserted = insertRolledBack("BOOKING_REMINDER", bookingId, "BOOKING_REMINDER:" + bookingId + ":" + EPOCH);

        assertThat(inserted).isOne();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BOOKING_RESCHEDULED", "BOOKING_CREATED", "REVIEW_REQUESTED"})
    @DisplayName("an :H2 4th segment on any other type is refused")
    void should_rejectTwoHourSuffix_when_typeIsNotBookingReminder(String type) {
        UUID bookingId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRolledBack(type, bookingId, type + ":" + bookingId + ":" + EPOCH + ":H2"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(DEDUP_CHK);
    }

    @ParameterizedTest(name = "4th segment \"{0}\"")
    @ValueSource(strings = {"H24", "h2", "H3", "", "H2 ", "H2:x"})
    @DisplayName("BOOKING_REMINDER with any 4th segment other than exactly H2 is refused")
    void should_rejectBookingReminder_when_fourthSegmentIsNotH2(String fourth) {
        UUID bookingId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRolledBack("BOOKING_REMINDER", bookingId,
                "BOOKING_REMINDER:" + bookingId + ":" + EPOCH + ":" + fourth))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(DEDUP_CHK);
    }

    @Test
    @DisplayName("a 2 h key whose 3rd segment is a uuid (not epoch seconds) is refused")
    void should_rejectTwoHourKey_when_thirdSegmentIsNotEpoch() {
        UUID bookingId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRolledBack("BOOKING_REMINDER", bookingId,
                "BOOKING_REMINDER:" + bookingId + ":" + UUID.randomUUID() + ":H2"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(DEDUP_CHK);
    }

    private int insertRolledBack(String type, UUID bookingId, String dedupKey) {
        return transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            return jdbcTemplate.update("INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, "
                    + "dedup_key) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), UUID.randomUUID(), type, bookingId,
                    dedupKey);
        });
    }
}
