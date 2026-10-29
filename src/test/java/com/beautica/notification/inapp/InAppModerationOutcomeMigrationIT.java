package com.beautica.notification.inapp;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 415 — V219's CHECKs on {@code in_app_notification}: the four moderation-outcome types with their shape
 * ({@code moderated_target_type} set, no other referent, {@code <TYPE>:<uuid>} dedup key), and the old types still
 * writable without the new column.
 */
@DisplayName("V219 — in-app moderation outcome CHECKs (phase 415)")
class InAppModerationOutcomeMigrationIT extends AbstractIntegrationTest {

    private static final String INSERT = "INSERT INTO in_app_notification "
            + "(id, recipient_user_id, type, moderated_target_type, booking_id, dedup_key) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SHAPE = "in_app_notification_shape_chk";

    private UUID recipient;
    private UUID batch;

    @BeforeEach
    void seedRecipient() {
        recipient = UUID.randomUUID();
        batch = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, created_at) "
                + "VALUES (?, ?, 'x', 'CLIENT', true, true, NOW())", recipient, "v219-" + recipient + "@beautica.test");
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({
            "REPORT_CONTENT_REMOVED, REVIEW",
            "REVIEW_ABOUT_YOU_REMOVED, REVIEW",
            "REVIEW_ABOUT_YOU_REMOVED, CLIENT_REVIEW",
            "REPORT_NO_VIOLATION, CLIENT_PROFILE",
            "REPORT_REVIEWED, SALON_PROFILE",
            "REPORT_REVIEWED, MASTER_PROFILE",
            "CONTENT_REMOVED, CLIENT_REVIEW",
            "CONTENT_REMOVED, PORTFOLIO_PHOTO"
    })
    @DisplayName("each new type with a valid shape is accepted")
    void should_accept_when_validModerationShape(String type, String targetType) {
        int inserted = insert(type, targetType, null, type + ":" + batch);

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("a moderation type with booking_id set is rejected")
    void should_reject_when_bookingIdSet() {
        UUID bookingId = UUID.randomUUID();

        assertRejected(() -> insert("REPORT_REVIEWED", "REVIEW", bookingId, "REPORT_REVIEWED:" + batch), SHAPE);
    }

    @Test
    @DisplayName("a moderation type with moderated_target_type NULL is rejected")
    void should_reject_when_targetTypeNull() {
        assertRejected(() -> insert("REPORT_NO_VIOLATION", null, null, "REPORT_NO_VIOLATION:" + batch), SHAPE);
    }

    @Test
    @DisplayName("CONTENT_REMOVED on a profile target is rejected")
    void should_reject_when_contentRemovedOnProfile() {
        assertRejected(() -> insert("CONTENT_REMOVED", "SALON_PROFILE", null, "CONTENT_REMOVED:" + batch), SHAPE);
    }

    @Test
    @DisplayName("an unknown moderated_target_type literal is rejected")
    void should_reject_when_unknownTargetType() {
        assertRejected(() -> insert("REPORT_REVIEWED", "BOOKING_NOTE", null, "REPORT_REVIEWED:" + batch),
                "chk_inapp_moderated_target_type");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"mod:{batch}", "REPORT_NO_VIOLATION:{batch}", "REPORT_REVIEWED:not-a-uuid",
            "REPORT_REVIEWED:{batch}:{batch}"})
    @DisplayName("a moderation row whose dedup key is not '<its TYPE>:<uuid>' is rejected")
    void should_reject_when_dedupKeyMalformed(String keyTemplate) {
        String key = keyTemplate.replace("{batch}", batch.toString());

        assertRejected(() -> insert("REPORT_REVIEWED", "REVIEW", null, key), "in_app_notification_dedup_key_chk");
    }

    @Test
    @DisplayName("an existing type still inserts, and may not carry moderated_target_type")
    void should_keepExistingTypes_when_v219Applied() {
        jdbcTemplate.update("DELETE FROM in_app_notification WHERE recipient_user_id = ?", recipient);
        String key = "INVITE_ACCEPTED:" + UUID.randomUUID() + ":" + UUID.randomUUID();
        UUID salonOwner = recipient;
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, 'V219 salon', true, NOW(), NOW(), ?)", salonId, salonOwner, testCityId());

        int inserted = jdbcTemplate.update("INSERT INTO in_app_notification (id, recipient_user_id, type, salon_id, "
                + "subject_user_id, dedup_key) VALUES (?, ?, 'INVITE_ACCEPTED', ?, ?, ?)",
                UUID.randomUUID(), recipient, salonId, recipient, key);

        assertThat(inserted).isEqualTo(1);
        assertRejected(() -> jdbcTemplate.update("INSERT INTO in_app_notification (id, recipient_user_id, type, "
                        + "salon_id, subject_user_id, moderated_target_type, dedup_key) "
                        + "VALUES (?, ?, 'INVITE_ACCEPTED', ?, ?, 'REVIEW', ?)",
                UUID.randomUUID(), recipient, salonId, recipient,
                "INVITE_ACCEPTED:" + UUID.randomUUID() + ":" + UUID.randomUUID()), SHAPE);
    }

    private int insert(String type, String targetType, UUID bookingId, String dedupKey) {
        return jdbcTemplate.update(INSERT, UUID.randomUUID(), recipient, type, targetType, bookingId, dedupKey);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable insert, String constraint) {
        assertThatThrownBy(insert).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    @Test
    @DisplayName("audit-fix PERF 1 — V219 adds its 4 CHECKs NOT VALID under a lock_timeout, V220 validates each, and "
            + "the catalog shows all 4 validated")
    void should_addNotValid_and_validateInV220_when_migrated() throws Exception {
        String v219 = migration("V219__inapp_moderation_outcome_types.sql");
        String v220 = migration("V220__inapp_moderation_validate_and_my_reports_index.sql");
        String v219Code = v219.replaceAll("(?m)--.*$", "");

        assertThat(v219).contains("SET LOCAL lock_timeout");
        assertThat(java.util.regex.Pattern.compile("\\bNOT VALID\\s*[,;]").matcher(v219Code).results().count())
                .as("every ADD CONSTRAINT in V219 is NOT VALID (no inline full-table scan)").isEqualTo(4L);
        assertThat(java.util.regex.Pattern.compile("\\bADD CONSTRAINT\\b").matcher(v219Code).results().count())
                .isEqualTo(4L);
        assertThat(v220).contains("SET LOCAL lock_timeout");
        for (String constraint : CONSTRAINTS) {
            assertThat(v220).contains("VALIDATE CONSTRAINT " + constraint + ";");
            assertThat(jdbcTemplate.queryForObject("SELECT convalidated FROM pg_constraint "
                    + "WHERE conrelid = 'in_app_notification'::regclass AND conname = ?", Boolean.class, constraint))
                    .as("%s validated", constraint).isTrue();
        }
    }

    private static final List<String> CONSTRAINTS = List.of("in_app_notification_type_chk",
            "in_app_notification_shape_chk", "chk_inapp_moderated_target_type", "in_app_notification_dedup_key_chk");

    private static String migration(String file) throws java.io.IOException {
        try (var in = InAppModerationOutcomeMigrationIT.class.getResourceAsStream("/db/migration/" + file)) {
            assertThat(in).as("migration %s on the classpath", file).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"PORTFOLIO_PHOTO", "MASTER_PROFILE", "SALON_PROFILE", "CLIENT_PROFILE"})
    @DisplayName("follow-up V221: REVIEW_ABOUT_YOU_REMOVED is only for a review or client review")
    void should_reject_when_reviewAboutYouOnNonReview(String targetType) {
        assertRejected(() -> insert("REVIEW_ABOUT_YOU_REMOVED", targetType, null, "REVIEW_ABOUT_YOU_REMOVED:" + batch),
                SHAPE);
    }

    @Test
    @DisplayName("follow-up V221: REVIEW_ABOUT_YOU_REMOVED needs its own '<TYPE>:<uuid>' key and no booking")
    void should_reject_when_reviewAboutYouKeyOrShapeWrong() {
        assertRejected(() -> insert("REVIEW_ABOUT_YOU_REMOVED", "REVIEW", null, "CONTENT_REMOVED:" + batch),
                "in_app_notification_dedup_key_chk");
        assertRejected(() -> insert("REVIEW_ABOUT_YOU_REMOVED", "REVIEW", UUID.randomUUID(),
                "REVIEW_ABOUT_YOU_REMOVED:" + batch), SHAPE);
    }

    @Test
    @DisplayName("follow-up V221/V222: V221 re-adds its 3 CHECKs NOT VALID under a lock_timeout, V222 validates each, "
            + "and the catalog shows them validated")
    void should_addNotValid_and_validateInV222_when_migrated() throws Exception {
        String v221 = migration("V221__inapp_review_about_you_removed_type.sql");
        String v222 = migration("V222__inapp_review_about_you_removed_validate.sql");
        String v221Code = v221.replaceAll("(?m)--.*$", "");

        assertThat(v221).contains("SET LOCAL lock_timeout");
        assertThat(java.util.regex.Pattern.compile("\\bNOT VALID\\s*[,;]").matcher(v221Code).results().count())
                .as("every ADD CONSTRAINT in V221 is NOT VALID").isEqualTo(3L);
        assertThat(java.util.regex.Pattern.compile("\\bADD CONSTRAINT\\b").matcher(v221Code).results().count())
                .isEqualTo(3L);
        assertThat(v222).contains("SET LOCAL lock_timeout");
        for (String constraint : List.of("in_app_notification_type_chk", "in_app_notification_shape_chk",
                "in_app_notification_dedup_key_chk")) {
            assertThat(v222).contains("VALIDATE CONSTRAINT " + constraint + ";");
            assertThat(jdbcTemplate.queryForObject("SELECT convalidated FROM pg_constraint "
                    + "WHERE conrelid = 'in_app_notification'::regclass AND conname = ?", Boolean.class, constraint))
                    .as("%s validated", constraint).isTrue();
        }
    }
}
