package com.beautica.auth.registration;

import com.beautica.auth.InvitePersistenceService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.user.InviteSmsPhoneSendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B520 (AUTH-52, AUTH-D14) — at most 10 invite SMS per inviter per hour, 30 per salon per day and 3 per phone per day
 * across all salons; beyond → 429
 * {@code INVITE_SMS_LIMIT}, nothing written, no SMS. Earlier invites are seeded straight into {@code invite_tokens}
 * (each phone row = one SMS sent) so the caps are reached without 10–30 HTTP calls through the per-IP limiter.
 */
@DisplayName("Invite SMS caps — 10 per inviter per hour, 30 per salon per day, 3 per phone per day (B520)")
class InviteSmsLimitIT extends AbstractPhoneIdentityIT {

    @Autowired
    private PhoneHasher phoneHasher;

    @Autowired
    private InvitePersistenceService invitePersistenceService;

    @Autowired
    private InviteSmsPhoneSendRepository phoneSendRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Rows of the durable per-phone log (V252) for {@code phone}. */
    private int phoneSendRows(String phone) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_sms_phone_sends WHERE phone_hash = ?",
                Integer.class, (Object) phoneHasher.hash(phone).hash());
    }

    /** One invite SMS to {@code phone} recorded in the durable per-phone log (V252) at {@code sentAt}. */
    private void seedPhoneSend(String phone, Timestamp sentAt) {
        jdbcTemplate.update("INSERT INTO invite_sms_phone_sends (id, phone_hash, sent_at, created_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), phoneHasher.hash(phone).hash(), sentAt, sentAt);
    }

    /** {@code count} already-sent phone invites by {@code inviter} into {@code salonId}, sent {@code minutesAgo}. */
    private void seedSentInvites(UUID salonId, UUID inviter, int count, int minutesAgo) {
        Instant sentAt = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("INSERT INTO invite_tokens (id, token, phone, salon_id, role, expires_at, is_used, "
                            + "invited_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, false, ?, ?, ?)",
                    UUID.randomUUID(), sha256Hex(UUID.randomUUID().toString()), newPhone(), salonId,
                    Timestamp.from(sentAt.plus(48, ChronoUnit.HOURS)), inviter, Timestamp.from(sentAt),
                    Timestamp.from(sentAt));
        }
    }

    private ResponseEntity<String> invite(Salon salon, String phone) {
        return postAs(salon.ownerToken(), "/api/v1/salons/" + salon.salonId() + "/invite", Map.of("phone", phone));
    }

    private int rowsFor(String phone) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE phone = ?", Integer.class, phone);
    }

    /** DB-clock start of this test; outbox counts are scoped to rows written since (the payload is sealed). */
    private Timestamp testStart;

    @BeforeEach
    void markTestStart() {
        testStart = jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp.class);
    }

    /** INVITE outbox rows written by THIS test. */
    private int outboxInviteRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'INVITE' "
                + "AND created_at >= ?", Integer.class, testStart);
    }

    @Test
    @DisplayName("the inviter's 11th invite SMS within an hour -> 429 INVITE_SMS_LIMIT, no row, no SMS row")
    void should_return429_when_inviterSentTenInLastHour() {
        Salon salon = newSalon();
        seedSentInvites(salon.salonId(), salon.ownerId(), 10, 30);
        String phone = newPhone();

        ResponseEntity<String> response = invite(salon, phone);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_SMS_LIMIT");
        assertThat(rowsFor(phone)).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("10 invites sent more than an hour ago no longer count -> the next one is 201")
    void should_return201_when_inviterWindowHasRolledOver() {
        Salon salon = newSalon();
        seedSentInvites(salon.salonId(), salon.ownerId(), 10, 61);
        String phone = newPhone();

        ResponseEntity<String> response = invite(salon, phone);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(phone)).isEqualTo(1);
        assertThat(outboxInviteRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("the salon's 31st invite SMS within 24 h (other inviters) -> 429, no row, no SMS row")
    void should_return429_when_salonSentThirtyToday() {
        Salon salon = newSalon();
        seedSentInvites(salon.salonId(), null, 30, 180);
        String phone = newPhone();

        ResponseEntity<String> response = invite(salon, phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_SMS_LIMIT");
        assertThat(rowsFor(phone)).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("an idempotent re-invite of a live invite is not an SMS and is never capped")
    void should_return201WithoutSms_when_reinvitingLivePhoneAtCap() {
        Salon salon = newSalon();
        String phone = newPhone();
        assertThat(invite(salon, phone).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        seedSentInvites(salon.salonId(), salon.ownerId(), 9, 10);

        ResponseEntity<String> again = invite(salon, phone);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(phone)).isEqualTo(1);
        assertThat(outboxInviteRows()).isEqualTo(1);
    }

    private void cancelPendingInvite(Salon salon, String phone) {
        UUID inviteId = jdbcTemplate.queryForObject("SELECT id FROM invite_tokens WHERE salon_id = ? AND phone = ? "
                + "AND is_used = false AND revoked_at IS NULL", UUID.class, salon.salonId(), phone);
        ResponseEntity<String> cancelled = exchangeAs(salon.ownerToken(), HttpMethod.DELETE,
                "/api/v1/salons/" + salon.salonId() + "/invites/" + inviteId, null);
        assertThat(cancelled.getStatusCode()).as("body: %s", cancelled.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("AUTH-D14: cancel + re-invite of the same phone — the 4th invite SMS within 24 h -> 429, no extra row "
            + "and no extra SMS row")
    void should_return429_when_samePhoneCancelledAndReinvitedFourTimes() {
        Salon salon = newSalon();
        String phone = newPhone();
        for (int i = 0; i < 3; i++) {
            assertThat(invite(salon, phone).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            cancelPendingInvite(salon, phone);
        }

        ResponseEntity<String> fourth = invite(salon, phone);

        assertThat(fourth.getStatusCode()).as("body: %s", fourth.getBody()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(fourth).at("/data/code").asText()).isEqualTo("INVITE_SMS_LIMIT");
        assertThat(rowsFor(phone)).isEqualTo(3);
        assertThat(outboxInviteRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("AUTH-D14: the per-phone cap counts across salons — a 4th salon inviting the same phone within 24 h "
            + "-> 429, no row, no SMS row")
    void should_return429_when_fourthSalonInvitesSamePhone() {
        String phone = newPhone();
        for (int i = 0; i < 3; i++) {
            assertThat(invite(newSalon(), phone).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        Salon fourthSalon = newSalon();

        ResponseEntity<String> response = invite(fourthSalon, phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_SMS_LIMIT");
        assertThat(rowsFor(phone)).isEqualTo(3);
        assertThat(outboxInviteRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("AUTH-D14: three invite SMS to a phone more than 24 h ago no longer count -> the next invite is 201")
    void should_return201_when_perPhoneWindowHasRolledOver() {
        String phone = newPhone();
        Timestamp sentAt = Timestamp.from(Instant.now().minus(25, ChronoUnit.HOURS));
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("INSERT INTO invite_tokens (id, token, phone, salon_id, role, expires_at, is_used, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, false, ?, ?)",
                    UUID.randomUUID(), sha256Hex(UUID.randomUUID().toString()), phone, newSalon().salonId(),
                    Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), sentAt, sentAt);
            seedPhoneSend(phone, sentAt);
        }

        ResponseEntity<String> response = invite(newSalon(), phone);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(phone)).isEqualTo(4);
        assertThat(outboxInviteRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("AUTH-D14 audit-fix 2: three invite SMS seeded only in the durable log within 24 h -> the 4th is 429 "
            + "(the cap reads the log, not invite_tokens)")
    void should_return429_when_durableLogHoldsThreeRecentSends() {
        String phone = newPhone();
        Timestamp sentAt = Timestamp.from(Instant.now().minus(23, ChronoUnit.HOURS));
        for (int i = 0; i < 3; i++) {
            seedPhoneSend(phone, sentAt);
        }

        ResponseEntity<String> response = invite(newSalon(), phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_SMS_LIMIT");
        assertThat(rowsFor(phone)).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("B520 audit-fix 3: the daily purge (real Postgres) deletes a send-log row older than the 2-day "
            + "retention and keeps one inside it")
    void should_deleteOnlyRowsPastRetention_when_purgeRuns() {
        String oldPhone = newPhone();
        String recentPhone = newPhone();
        seedPhoneSend(oldPhone, Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)));
        seedPhoneSend(recentPhone, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));

        invitePersistenceService.purgeExpiredPhoneSends();

        assertThat(phoneSendRows(oldPhone)).as("past the 2-day retention").isZero();
        assertThat(phoneSendRows(recentPhone)).as("inside the retention").isEqualTo(1);
    }

    @Test
    @DisplayName("B520 audit-fix 3: the purge cutoff is strict — a row sent exactly AT the cutoff is kept, one a "
            + "microsecond earlier is deleted (real SQL, fixed cutoff)")
    void should_keepRowAtCutoff_andDeleteRowJustBefore_when_purgeCutoffIsStrict() {
        Instant cutoff = Instant.parse("2020-01-01T12:00:00Z");
        String before = newPhone();
        String atCutoff = newPhone();
        String after = newPhone();
        seedPhoneSend(before, Timestamp.from(cutoff.minus(1, ChronoUnit.MICROS)));
        seedPhoneSend(atCutoff, Timestamp.from(cutoff));
        seedPhoneSend(after, Timestamp.from(cutoff.plus(1, ChronoUnit.MICROS)));

        Integer deleted = new TransactionTemplate(transactionManager)
                .execute(status -> phoneSendRepository.deleteSentBefore(cutoff));

        assertThat(deleted).isEqualTo(1);
        assertThat(phoneSendRows(before)).isZero();
        assertThat(phoneSendRows(atCutoff)).as("sent_at = cutoff is not < cutoff").isEqualTo(1);
        assertThat(phoneSendRows(after)).isEqualTo(1);
    }

    @Test
    @DisplayName("AUTH-D14 audit-fix 2: 3 invites, then the salon's invite rows are hard-deleted (owner self-delete, "
            + "SUP-67) -> a new salon's invite to the same phone is still 429")
    void should_return429_when_inviteRowsHardDeletedAfterThreeInvites() {
        String phone = newPhone();
        Salon salon = newSalon();
        for (int i = 0; i < 3; i++) {
            assertThat(invite(salon, phone).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            cancelPendingInvite(salon, phone);
        }
        // The statement InviteTokenRepository#deleteBySalonIdIn runs on the owner-self-delete path.
        jdbcTemplate.update("DELETE FROM invite_tokens WHERE salon_id = ?", salon.salonId());
        assertThat(rowsFor(phone)).isZero();

        ResponseEntity<String> response = invite(newSalon(), phone);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_SMS_LIMIT");
        assertThat(rowsFor(phone)).isZero();
        assertThat(outboxInviteRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("boundary: the inviter's 10th invite SMS within an hour is still 201")
    void should_return201_when_inviterSendsTenthInHour() {
        Salon salon = newSalon();
        seedSentInvites(salon.salonId(), salon.ownerId(), 9, 30);
        String phone = newPhone();

        ResponseEntity<String> response = invite(salon, phone);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(phone)).isEqualTo(1);
        assertThat(outboxInviteRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("boundary: the salon's 30th invite SMS within 24 h is still 201")
    void should_return201_when_salonSendsThirtiethToday() {
        Salon salon = newSalon();
        seedSentInvites(salon.salonId(), null, 29, 180);
        String phone = newPhone();

        ResponseEntity<String> response = invite(salon, phone);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(phone)).isEqualTo(1);
        assertThat(outboxInviteRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("30 salon invite SMS sent more than 24 h ago no longer count -> the next one is 201")
    void should_return201_when_salonWindowHasRolledOver() {
        Salon salon = newSalon();
        seedSentInvites(salon.salonId(), null, 30, 25 * 60);
        String phone = newPhone();

        ResponseEntity<String> response = invite(salon, phone);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(phone)).isEqualTo(1);
        assertThat(outboxInviteRows()).isEqualTo(1);
    }
}
