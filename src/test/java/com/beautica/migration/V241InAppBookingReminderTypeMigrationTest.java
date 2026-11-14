package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@code V241__inapp_booking_reminder_type.sql} + {@code V242} (phase 495): the type and dedup-key
 * CHECKs are re-added as strict supersets of V221's — every prior type plus {@code BOOKING_REMINDER} — validated, and
 * the real table accepts a row of every type while refusing an unknown one.
 *
 * <p>The behavioural inserts run in a rolled-back transaction under {@code SET LOCAL session_replication_role =
 * replica}, which suspends the FK triggers (so a row needs no seeded booking / salon / category) but NOT the CHECK
 * constraints under test — Postgres always enforces CHECKs. Nothing is committed.
 */
@DisplayName("V241/V242 migration — in_app_notification CHECKs admit BOOKING_REMINDER and keep every prior type")
class V241InAppBookingReminderTypeMigrationTest extends AbstractIntegrationTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
    private static final Set<String> MODERATION = Set.of(
            "REPORT_CONTENT_REMOVED", "REPORT_NO_VIOLATION", "REPORT_REVIEWED", "CONTENT_REMOVED",
            "REVIEW_ABOUT_YOU_REMOVED");

    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUpTransactionTemplate() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("type_chk and dedup_key_chk list exactly the enum's values (BOOKING_REMINDER included) and are VALID")
    void should_listEveryEnumTypeAndBeValidated_when_constraintsRead() {
        Set<String> enumNames = Arrays.stream(InAppNotificationType.values()).map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(listedTypes("in_app_notification_type_chk")).isEqualTo(enumNames).contains("BOOKING_REMINDER");
        assertThat(listedTypes("in_app_notification_dedup_key_chk")).containsAll(enumNames);
        assertThat(jdbcTemplate.queryForList("""
                SELECT convalidated FROM pg_constraint
                 WHERE conrelid = 'in_app_notification'::regclass
                   AND conname IN ('in_app_notification_type_chk', 'in_app_notification_dedup_key_chk')
                """, Boolean.class)).containsExactly(true, true);
    }

    @Test
    @DisplayName("a BOOKING_REMINDER row keyed BOOKING_REMINDER:<bookingId>:<epoch seconds> is accepted")
    void should_acceptBookingReminderRow_when_inserted() {
        UUID bookingId = UUID.randomUUID();

        int inserted = insertRolledBack("BOOKING_REMINDER", bookingId, "BOOKING_REMINDER:" + bookingId + ":1763202600");

        assertThat(inserted).isOne();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InAppNotificationType.class)
    @DisplayName("every type (each prior one and BOOKING_REMINDER) is still accepted in its documented row shape")
    void should_acceptEveryType_when_insertedInItsShape(InAppNotificationType type) {
        int inserted = transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            return insertInShape(type.name());
        });

        assertThat(inserted).isOne();
    }

    @Test
    @DisplayName("an unknown type is still refused by in_app_notification_type_chk")
    void should_rejectUnknownType_when_inserted() {
        UUID bookingId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRolledBack("BOOKING_REMINDER_X", bookingId, "BOOKING_REMINDER:" + bookingId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("in_app_notification_type_chk");
    }

    @Test
    @DisplayName("a BOOKING_REMINDER row with a malformed key suffix is refused by in_app_notification_dedup_key_chk")
    void should_rejectBookingReminder_when_keySuffixMalformed() {
        UUID bookingId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRolledBack("BOOKING_REMINDER", bookingId,
                "BOOKING_REMINDER:" + bookingId + ":not-a-number"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("in_app_notification_dedup_key_chk");
    }

    private Set<String> listedTypes(String constraint) {
        String def = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conrelid = 'in_app_notification'::regclass AND conname = ?
                """, String.class, constraint);
        // Regex / LIKE literals carry ^, \\ or % and never match QUOTED; only the TYPE literals do.
        return QUOTED.matcher(def).results().map(r -> r.group(1)).collect(Collectors.toSet());
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

    /** One row of {@code type} in the shape in_app_notification_shape_chk requires for it (FKs suspended). */
    private int insertInShape(String type) {
        UUID ref = UUID.randomUUID();
        String sql = "INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, salon_id, "
                + "category_request_id, service_type_suggestion_id, moderated_target_type, dedup_key) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        UUID id = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        if (MODERATION.contains(type)) {
            return jdbcTemplate.update(sql, id, recipient, type, null, null, null, null, "REVIEW", type + ":" + ref);
        }
        if (type.startsWith("CATEGORY_REQUEST_")) {
            return jdbcTemplate.update(sql, id, recipient, type, null, null, 1L, null, null, type + ":1");
        }
        if (type.startsWith("SERVICE_TYPE_SUGGESTION_")) {
            return jdbcTemplate.update(sql, id, recipient, type, null, null, null, ref, null, type + ":" + ref);
        }
        if (type.equals("INVITE_ACCEPTED")) {
            return jdbcTemplate.update(sql, id, recipient, type, null, ref, null, null, null, type + ":" + ref);
        }
        String key = type.equals("BOOKING_REMINDER") ? type + ":" + ref + ":1763202600" : type + ":" + ref;
        return jdbcTemplate.update(sql, id, recipient, type, ref, null, null, null, null, key);
    }
}
