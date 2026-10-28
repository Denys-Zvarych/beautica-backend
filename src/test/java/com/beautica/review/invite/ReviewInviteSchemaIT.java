package com.beautica.review.invite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.entity.ReviewInvite;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.entity.ReviewInviteStatus;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.support.GuestReviewFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 399 D3/D4 — V213 {@code review_invites} + {@code sms_opt_outs} against real Postgres: the entity round-trips
 * through {@code ddl-auto=validate}, one invite per visit, unique token hash, cascade with the visit, the lifecycle
 * CHECKs, and no raw phone / raw token column anywhere in the new tables.
 */
@DisplayName("V213 review_invites schema — phase 399 IT")
class ReviewInviteSchemaIT extends AbstractIntegrationTest {

    private static final String GUEST_PHONE = "+380501234567";
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @Autowired
    private ReviewInviteRepository reviewInviteRepository;

    @Autowired
    private PhoneHasher phoneHasher;

    private GuestReviewFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new GuestReviewFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("a scheduled invite round-trips with the PhoneHasher key, no token, zero attempts")
    void should_persistScheduledInvite_when_createdThroughFactory() {
        UUID appointmentId = fixtures.guestAppointment(GUEST_PHONE);
        ReviewInvite invite = ReviewInvite.schedule(
                appointmentId, phoneHasher.hash(GUEST_PHONE), NOW.plus(2, ChronoUnit.HOURS), NOW);

        reviewInviteRepository.saveAndFlush(invite);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT appointment_id, phone_hash, token_hash, status, attempts, skip_reason FROM review_invites");
        assertThat(row.get("appointment_id")).isEqualTo(appointmentId);
        assertThat((byte[]) row.get("phone_hash")).isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
        assertThat(row.get("token_hash")).isNull();
        assertThat(row.get("status")).isEqualTo(ReviewInviteStatus.SCHEDULED.name());
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(row.get("skip_reason")).isNull();
    }

    @Test
    @DisplayName("a second invite for the same visit is rejected")
    void should_rejectSecondInvite_when_sameAppointment() {
        UUID appointmentId = fixtures.guestAppointment(GUEST_PHONE);
        insertInvite(appointmentId, "SCHEDULED", null);

        assertThatThrownBy(() -> insertInvite(appointmentId, "SCHEDULED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_review_invites_appointment");
    }

    @Test
    @DisplayName("two invites cannot share a token hash")
    void should_rejectInvite_when_tokenHashDuplicated() {
        byte[] tokenHash = filled((byte) 0x42);
        insertInvite(fixtures.guestAppointment(GUEST_PHONE), "SENT", tokenHash);

        assertThatThrownBy(() -> insertInvite(fixtures.guestAppointment(GUEST_PHONE), "SENT", tokenHash))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_review_invites_token_hash");
    }

    @Test
    @DisplayName("deleting the visit deletes its invite")
    void should_cascadeDelete_when_appointmentDeleted() {
        UUID appointmentId = fixtures.guestAppointment(GUEST_PHONE);
        insertInvite(appointmentId, "SCHEDULED", null);

        jdbcTemplate.update("DELETE FROM appointments WHERE id = ?", appointmentId);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_invites", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a SENT invite without a token hash is rejected")
    void should_rejectSentInvite_when_tokenHashMissing() {
        UUID appointmentId = fixtures.guestAppointment(GUEST_PHONE);

        assertThatThrownBy(() -> insertInvite(appointmentId, "SENT", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_sent_fields");
    }

    @Test
    @DisplayName("CONSUMED requires consumed_at, and consumed_at is only legal on CONSUMED")
    void should_rejectInvite_when_consumedAtMismatchesStatus() {
        UUID consumedWithoutTime = fixtures.guestAppointment(GUEST_PHONE);
        UUID sentWithTime = fixtures.guestAppointment(GUEST_PHONE);

        assertThatThrownBy(() -> insertInvite(consumedWithoutTime, "CONSUMED", filled((byte) 0x01)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_consumed_at");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO review_invites (id, appointment_id, phone_hash, token_hash, status, send_after, sent_at, "
                        + "expires_at, consumed_at) VALUES (?, ?, ?, ?, 'SENT', NOW(), NOW(), NOW(), NOW())",
                UUID.randomUUID(), sentWithTime, filled((byte) 0x09), filled((byte) 0x02)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_consumed_at");
    }

    @Test
    @DisplayName("skip_reason accepts every ReviewInviteSkipReason value and rejects anything else")
    void should_enforceSkipReasonValues_when_inviteSkipped() {
        for (ReviewInviteSkipReason reason : ReviewInviteSkipReason.values()) {
            UUID appointment = fixtures.guestAppointment(GUEST_PHONE);

            assertThatCode(() -> insertSkipped(appointment, reason.name()))
                    .as(reason.name()).doesNotThrowAnyException();
        }
        UUID unknown = fixtures.guestAppointment(GUEST_PHONE);

        assertThatThrownBy(() -> insertSkipped(unknown, "BECAUSE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_skip_reason_value");
    }

    @Test
    @DisplayName("SKIPPED requires a skip reason, and a skip reason is only legal on SKIPPED")
    void should_rejectInvite_when_skipReasonMismatchesStatus() {
        UUID skippedWithoutReason = fixtures.guestAppointment(GUEST_PHONE);
        UUID scheduledWithReason = fixtures.guestAppointment(GUEST_PHONE);

        assertThatThrownBy(() -> insertInvite(skippedWithoutReason, "SKIPPED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_skip_reason");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO review_invites (id, appointment_id, phone_hash, status, send_after, skip_reason) "
                        + "VALUES (?, ?, ?, 'SCHEDULED', NOW(), 'OPTED_OUT')",
                UUID.randomUUID(), scheduledWithReason, filled((byte) 0x09)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_skip_reason");
    }

    @Test
    @DisplayName("an unknown status is rejected")
    void should_rejectInvite_when_statusUnknown() {
        UUID appointmentId = fixtures.guestAppointment(GUEST_PHONE);

        assertThatThrownBy(() -> insertInvite(appointmentId, "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_status");
    }

    @Test
    @DisplayName("a phone hash that is not a 32-byte digest is rejected (a raw phone cannot be stored)")
    void should_rejectInvite_when_phoneHashNotDigestLength() {
        UUID appointmentId = fixtures.guestAppointment(GUEST_PHONE);
        byte[] rawPhoneBytes = GUEST_PHONE.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO review_invites (id, appointment_id, phone_hash, status, send_after) "
                        + "VALUES (?, ?, ?, 'SCHEDULED', NOW())",
                UUID.randomUUID(), appointmentId, rawPhoneBytes))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_review_invites_phone_hash_len");
    }

    @Test
    @DisplayName("the new tables hold only hashes — no raw phone or raw token column")
    void should_haveOnlyHashedSecretColumns_when_inspectingNewTables() {
        List<String> inviteColumns = columnsOf("review_invites");
        List<String> optOutColumns = columnsOf("sms_opt_outs");

        assertThat(inviteColumns).containsExactlyInAnyOrder("id", "appointment_id", "phone_hash", "token_hash",
                "status", "send_after", "sent_at", "expires_at", "consumed_at", "attempts", "skip_reason",
                "created_at");
        assertThat(optOutColumns).containsExactlyInAnyOrder("phone_hash", "created_at", "source");
    }

    private void insertSkipped(UUID appointmentId, String skipReason) {
        jdbcTemplate.update(
                "INSERT INTO review_invites (id, appointment_id, phone_hash, status, send_after, skip_reason) "
                        + "VALUES (?, ?, ?, 'SKIPPED', NOW(), ?)",
                UUID.randomUUID(), appointmentId, filled((byte) 0x09), skipReason);
    }

    private void insertInvite(UUID appointmentId, String status, byte[] tokenHash) {
        boolean sent = tokenHash != null;
        jdbcTemplate.update(
                "INSERT INTO review_invites (id, appointment_id, phone_hash, token_hash, status, send_after, sent_at, "
                        + "expires_at) VALUES (?, ?, ?, ?, ?, NOW(), ?, ?)",
                UUID.randomUUID(), appointmentId, phoneHasher.hash(GUEST_PHONE).hash(), tokenHash, status,
                sent ? java.sql.Timestamp.from(NOW) : null,
                sent ? java.sql.Timestamp.from(NOW.plus(14, ChronoUnit.DAYS)) : null);
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?", String.class, table);
    }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
