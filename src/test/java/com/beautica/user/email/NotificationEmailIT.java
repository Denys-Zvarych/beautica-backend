package com.beautica.user.email;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.common.util.ContactMask;
import com.beautica.notification.service.ContactChangeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.TimeZones;
import com.beautica.notification.service.BookingVisit;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * B516 (AUTH-50, CLI-50, NTF-56) — any role adds an optional notifications email; it reaches {@code users.email} only
 * after the emailed code is confirmed (the unconfirmed address waits in {@code user_pending_emails}).
 */
@DisplayName("Optional notifications email stored only after its code (B516, AUTH-50)")
class NotificationEmailIT extends AbstractPhoneIdentityIT {

    private static final String REQUEST_URL = "/api/v1/users/me/email/request-code";
    private static final String VERIFY_URL = "/api/v1/users/me/email/verify";
    private static final long MAIL_WAIT_MS = 5_000;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private String mailedCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService).sendVerificationEmail(eq(email), code.capture());
        return code.getValue();
    }

    /** The LAST code mailed to {@code email} (several requests in one test). */
    private String lastMailedCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService, atLeastOnce()).sendVerificationEmail(eq(email), code.capture());
        List<String> codes = code.getAllValues();
        return codes.get(codes.size() - 1);
    }

    /** Moves the pending row's sent_at past the 60 s cooldown (sent_at stays before expires_at — V245 CHECK). */
    private void passCooldown(UUID id) {
        jdbcTemplate.update("UPDATE user_pending_emails SET sent_at = sent_at - interval '2 minutes' WHERE user_id = ?",
                id);
    }

    private static String wrongCodeFor(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private int securityNotices(UUID id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = 'SECURITY_EMAIL_CHANGED'", Integer.class, id);
    }

    private Map<String, Object> emailRow(UUID id) {
        return jdbcTemplate.queryForMap("SELECT email, email_verified FROM users WHERE id = ?", id);
    }

    @Test
    @DisplayName("a provider's notifications email reaches the account only after code + password are confirmed")
    void should_storeEmailOnlyAfterVerify_when_providerAddsNotificationsEmail() {
        String phone = newPhone();
        UUID id = seedUser("SALON_OWNER", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();

        ResponseEntity<String> requested = postAs(token, REQUEST_URL, Map.of("email", email.toUpperCase()));

        assertThat(requested.getStatusCode()).as("body: %s", requested.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(emailRow(id).get("email")).as("NTF-56: nothing on users before verify").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM user_pending_emails WHERE user_id = ?",
                String.class, id)).isEqualTo(email);

        ResponseEntity<String> verified = postAs(token, VERIFY_URL, Map.of("email", email, "code", mailedCode(email), "currentPassword", PASSWORD));

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(emailRow(id)).containsEntry("email", email).containsEntry("email_verified", true);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_pending_emails WHERE user_id = ?",
                Integer.class, id)).isZero();
        ResponseEntity<String> me = exchangeAs(token, HttpMethod.GET, "/api/v1/users/me", null);
        assertThat(json(me).at("/data/email").asText()).isEqualTo(email);
        assertThat(json(me).at("/data/emailVerified").asBoolean()).isTrue();
        assertThat(securityNotices(id)).as("an email ADD gets the feed notice").isOne();
        verify(emailNotificationService, never()).sendContactChangedEmail(any(), any(), any());
    }

    @Test
    @DisplayName("a wrong code is 400 INVALID_CODE and the email stays unset")
    void should_return400AndKeepEmailUnset_when_codeWrong() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", email));
        String wrong = mailedCode(email).equals("000000") ? "111111" : "000000";

        ResponseEntity<String> response = postAs(token, VERIFY_URL, Map.of("email", email, "code", wrong, "currentPassword", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(emailRow(id).get("email")).isNull();
    }

    @Test
    @DisplayName("requesting a code for an address another account holds is no 409: the 409 comes only at verify")
    void should_mailCodeThenReturn409AtVerify_when_emailTakenByAnotherAccount() {
        String taken = newEmail();
        seedUser("CLIENT", taken, true, null, null);
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);

        ResponseEntity<String> requested = postAs(token, REQUEST_URL, Map.of("email", taken));
        ResponseEntity<String> verified = postAs(token, VERIFY_URL,
                Map.of("email", taken, "code", mailedCode(taken), "currentPassword", PASSWORD));

        assertThat(requested.getStatusCode()).as("no registration oracle at request").isEqualTo(HttpStatus.OK);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(verified).at("/data/code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(emailRow(id).get("email")).isNull();
    }

    @Test
    @DisplayName("inside the 60 s cooldown a request for a taken address is 429, never the 409")
    void should_return429Not409_when_takenAddressRequestedWithinCooldown() {
        String taken = newEmail();
        seedUser("CLIENT", taken, true, null, null);
        String phone = newPhone();
        seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        postAs(token, REQUEST_URL, Map.of("email", newEmail()));

        ResponseEntity<String> second = postAs(token, REQUEST_URL, Map.of("email", taken));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("the right code with a wrong password is 400 CURRENT_PASSWORD_INVALID (not 401) and counts one try")
    void should_return400AndCountTry_when_wrongPassword() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", email));

        ResponseEntity<String> response = postAs(token, VERIFY_URL,
                Map.of("email", email, "code", mailedCode(email), "currentPassword", "Wr0ng!Passw0rd#"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("CURRENT_PASSWORD_INVALID");
        assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM user_pending_emails WHERE user_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(emailRow(id).get("email")).isNull();
    }

    @Test
    @DisplayName("after five wrong passwords the email code is spent: even code + right password is CODE_EXPIRED")
    void should_answerCodeExpired_when_fiveWrongPasswordsSpentTheCode() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", email));
        String code = mailedCode(email);
        for (int i = 0; i < 5; i++) {
            postAs(token, VERIFY_URL, Map.of("email", email, "code", code, "currentPassword", "Wr0ng!Passw0rd#"));
        }

        ResponseEntity<String> response = postAs(token, VERIFY_URL,
                Map.of("email", email, "code", code, "currentPassword", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("CODE_EXPIRED");
        assertThat(emailRow(id).get("email")).isNull();
        assertThat(securityNotices(id)).isZero();
    }

    @Test
    @DisplayName("with no pending code a wrong password answers INVALID_CODE, never CURRENT_PASSWORD_INVALID (no password oracle)")
    void should_answerInvalidCode_when_wrongPasswordAndNoPendingCode() {
        String phone = newPhone();
        seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);

        ResponseEntity<String> response = postAs(token, VERIFY_URL,
                Map.of("email", newEmail(), "code", "123456", "currentPassword", "Wr0ng!Passw0rd#"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVALID_CODE");
    }

    @Test
    @DisplayName("an email ADD without the current password is refused: add needs the password too")
    void should_return400_when_emailAddedWithoutPassword() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", email));

        ResponseEntity<String> response = postAs(token, VERIFY_URL, Map.of("email", email, "code", mailedCode(email)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("CURRENT_PASSWORD_INVALID");
        assertThat(emailRow(id).get("email")).isNull();
    }

    @Test
    @DisplayName("an address another account confirms in between is a clean 409 at verify")
    void should_return409_when_emailTakenBetweenRequestAndVerify() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", email));
        String code = mailedCode(email);
        seedUser("CLIENT", email, true, null, null);

        ResponseEntity<String> response = postAs(token, VERIFY_URL, Map.of("email", email, "code", code, "currentPassword", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(emailRow(id).get("email")).isNull();
    }

    @Test
    @DisplayName("a second code request within 60 s is 429")
    void should_return429_when_secondCodeRequestedWithinCooldown() {
        String phone = newPhone();
        seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);

        postAs(token, REQUEST_URL, Map.of("email", newEmail()));
        ResponseEntity<String> second = postAs(token, REQUEST_URL, Map.of("email", newEmail()));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a code submitted for an address other than requested is INVALID_CODE")
    void should_return400AndKeepEmailUnset_when_codeSubmittedForAnotherAddress() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String requested = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", requested));
        String code = mailedCode(requested);

        ResponseEntity<String> response = postAs(token, VERIFY_URL, Map.of("email", newEmail(), "code", code, "currentPassword", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(emailRow(id).get("email")).isNull();
    }

    @Test
    @DisplayName("a confirmed email change keeps the old address until verify, then swaps and notifies the OLD address")
    void should_keepOldEmailUntilVerify_then_swap_when_confirmedEmailChanged() {
        String oldEmail = newEmail();
        UUID id = seedUser("SALON_OWNER", oldEmail, true, newPhone(), null);
        String token = accessToken(oldEmail);
        String newEmail = newEmail();

        postAs(token, REQUEST_URL, Map.of("email", newEmail));

        assertThat(emailRow(id)).as("requested, not verified: the old address stays")
                .containsEntry("email", oldEmail).containsEntry("email_verified", true);
        ResponseEntity<String> verified = postAs(token, VERIFY_URL,
                Map.of("email", newEmail, "code", mailedCode(newEmail), "currentPassword", PASSWORD));
        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(emailRow(id)).containsEntry("email", newEmail).containsEntry("email_verified", true);
        assertThat(post(LOGIN_URL, Map.of("identifier", oldEmail, "password", PASSWORD)).getStatusCode())
                .as("the old address is no longer an identifier").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(securityNotices(id)).as("AUTH-D19 feed notice").isOne();
        verify(emailNotificationService, timeout(MAIL_WAIT_MS))
                .sendContactChangedEmail(oldEmail, ContactChangeKind.EMAIL, ContactMask.email(newEmail));
    }

    @Test
    @DisplayName("ten wrong codes across a resend lock the account: even the right code is refused")
    void should_refuseEvenTheRightCode_when_tenWrongCodesAcrossAResend() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        for (int round = 0; round < 2; round++) {
            passCooldown(id);
            postAs(token, REQUEST_URL, Map.of("email", email));
            String wrong = wrongCodeFor(lastMailedCode(email));
            for (int i = 0; i < 5; i++) {
                postAs(token, VERIFY_URL, Map.of("email", email, "code", wrong, "currentPassword", PASSWORD));
            }
        }
        passCooldown(id);
        postAs(token, REQUEST_URL, Map.of("email", email));

        ResponseEntity<String> right = postAs(token, VERIFY_URL,
                Map.of("email", email, "code", lastMailedCode(email), "currentPassword", PASSWORD));

        assertThat(right.getStatusCode()).as("cumulative lockout survives a resend").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(right).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(emailRow(id).get("email")).isNull();
    }

    // ── NTF-D15: business mail only to a CONFIRMED address ──────────────────────

    /** An independent master that registered by phone (no email), with one bookable service; returns its masters.id. */
    private UUID phoneOnlyMasterWithService(BookingTestFixtures fixtures, UUID userId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        return masterId;
    }

    private void clientBooks(BookingTestFixtures fixtures, String clientToken, UUID masterId, UUID masterServiceId,
                             int daysAhead) throws Exception {
        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV).plusDays(daysAhead)
                .withHour(12).withMinute(0).withSecond(0).withNano(0);
        String body = objectMapper.writeValueAsString(Map.of("masterId", masterId.toString(),
                "masterServiceId", masterServiceId.toString(), "startsAt", startsAt.toOffsetDateTime().toString()));
        ResponseEntity<String> created = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(created.getStatusCode()).as("body: %s", created.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("NTF-D15: a provider's booking email never goes to a pending address, only to the one confirmed later")
    void should_mailBookingOnlyToConfirmedEmail_when_providerAddsEmailMidway() throws Exception {
        var fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        String phone = newPhone();
        UUID providerId = seedUser("INDEPENDENT_MASTER", null, false, phone, null);
        UUID masterId = phoneOnlyMasterWithService(fixtures, providerId);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        String clientEmail = newEmail();
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);
        String providerToken = accessToken(phone);
        String email = newEmail();
        postAs(providerToken, REQUEST_URL, Map.of("email", email));

        clientBooks(fixtures, clientToken, masterId, masterServiceId, 2);
        drainWorker.drain();

        verify(emailNotificationService).sendNewBookingEmail(isNull(), any(BookingVisit.class));
        verify(emailNotificationService, never()).sendNewBookingEmail(eq(email), any(BookingVisit.class));

        postAs(providerToken, VERIFY_URL, Map.of("email", email, "code", mailedCode(email), "currentPassword", PASSWORD));
        clientBooks(fixtures, clientToken, masterId, masterServiceId, 3);
        drainWorker.drain();

        verify(emailNotificationService).sendNewBookingEmail(eq(email), any(BookingVisit.class));
    }

    @Test
    @DisplayName("10 wrong passwords on the phone-change path lock the email verify too: the right code and password are refused")
    void should_refuseRightPasswordAndCode_when_accountLockedByPhonePathFailures() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", email));
        for (int i = 0; i < 10; i++) {
            postAs(token, "/api/v1/users/me/phone/otp/verify",
                    Map.of("phone", newPhone(), "code", "123456", "currentPassword", "Wr0ng!Passw0rd#"));
        }

        ResponseEntity<String> verified = postAs(token, VERIFY_URL,
                Map.of("email", email, "code", mailedCode(email), "currentPassword", PASSWORD));

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(verified).at("/data/code").asText()).isEqualTo("CURRENT_PASSWORD_INVALID");
        assertThat(emailRow(id).get("email")).isNull();
        assertThat(securityNotices(id)).isZero();
    }

    @Test
    @DisplayName("an email notice keeps showing the address of that change after the email is changed again")
    void should_keepMaskedValueOfThatChange_when_emailChangedAgain() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);
        String first = newEmail();
        String second = newEmail();
        postAs(token, REQUEST_URL, Map.of("email", first));
        postAs(token, VERIFY_URL, Map.of("email", first, "code", lastMailedCode(first), "currentPassword", PASSWORD));
        // two changes inside one second share the TYPE:<user>:<epoch s> dedup key; move the first one aside
        jdbcTemplate.update("UPDATE in_app_notification SET dedup_key = type || ':' || recipient_user_id || ':1' "
                + "WHERE recipient_user_id = ?", id);
        passCooldown(id);
        postAs(token, REQUEST_URL, Map.of("email", second));

        ResponseEntity<String> verified = postAs(token, VERIFY_URL,
                Map.of("email", second, "code", lastMailedCode(second), "currentPassword", PASSWORD));

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForList("SELECT masked_contact FROM in_app_notification "
                        + "WHERE recipient_user_id = ? AND type = 'SECURITY_EMAIL_CHANGED'", String.class, id))
                .containsExactlyInAnyOrder(ContactMask.email(first), ContactMask.email(second));
        var feed = json(exchangeAs(token, HttpMethod.GET, "/api/v1/notifications", null));
        assertThat(feed.findValuesAsText("maskedContact")).as("feed: %s", feed)
                .containsExactlyInAnyOrder(ContactMask.email(first), ContactMask.email(second));
    }
}
