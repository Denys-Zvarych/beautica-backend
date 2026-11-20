package com.beautica.auth.registration;

import com.beautica.auth.InviteTokenCleanupJob;
import com.beautica.booking.GuestMessageExpectedCopy;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.support.StaffInviteFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * B520 (AUTH-16, AUTH-18, AUTH-19, AUTH-21, NTF-59, SAL-48) — staff invites by phone over HTTP on real Postgres: create
 * (owner and admin, both endpoints), the single SMS after commit through the outbox, input and conflict refusals,
 * the same-phone race, validate, pre-deploy email rows, the pending list and the 30-day retention sweep. App Check is
 * OFF here (its ENFORCE cases live in {@code AppCheckGuardIT}); the SMS sender is the base class's mock.
 */
@DisplayName("Staff invite by phone — create, SMS, validate, list, retention (B520)")
class InviteByPhoneIT extends AbstractPhoneIdentityIT {

    private static final String AUTH_INVITE_URL = "/api/v1/auth/invite";
    private static final String VALIDATE_URL = "/api/v1/auth/invite/validate?token=";
    private static final String ACCEPT_URL = "/api/v1/auth/invite/accept";
    private static final Pattern LINK = Pattern.compile("(http://localhost:3000/invite/accept\\?token=[A-Za-z0-9_%-]+)$");

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private InviteTokenCleanupJob cleanupJob;

    private String staffToken(String role, UUID salonId) {
        String email = newEmail();
        UUID id = seedUser(role, email, true, null, null);
        jdbcTemplate.update("UPDATE users SET salon_id = ? WHERE id = ?", salonId, id);
        return accessToken(email);
    }

    private ResponseEntity<String> inviteViaSalon(String token, UUID salonId, Object phone) {
        return postAs(token, "/api/v1/salons/" + salonId + "/invite", phone == null ? Map.of() : Map.of("phone", phone));
    }

    private int inviteRows(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE salon_id = ?", Integer.class,
                salonId);
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

    /** Drains the outbox and returns the one SMS text sent to {@code phone}. */
    private String drainAndCaptureSmsTo(String phone) {
        drainWorker.drain();
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(otpSmsSender).sendStaffInvite(eq(phone), text.capture());
        return text.getValue();
    }

    private static String linkOf(String smsText) {
        Matcher m = LINK.matcher(smsText);
        assertThat(m.find()).as("the SMS ends with the join link: %s", smsText).isTrue();
        return m.group(1);
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner invites by phone -> 201; one phone row (no email), one sealed outbox SMS row, and after the "
            + "commit exactly one SMS with the salon name and a working join link")
    void should_createInviteAndSendOneSms_when_ownerInvitesByPhone() {
        Salon salon = newSalon();
        String phone = newPhone();
        String humanSpelling = "0" + phone.substring(4, 6) + " " + phone.substring(6, 9) + "-" + phone.substring(9);

        ResponseEntity<String> response = inviteViaSalon(salon.ownerToken(), salon.salonId(), humanSpelling);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(response).at("/data/invitedPhone").asText()).isEqualTo(phone);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT phone, email, invited_by, role FROM invite_tokens WHERE salon_id = ?", salon.salonId());
        assertThat(row.get("phone")).isEqualTo(phone);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("invited_by")).isEqualTo(salon.ownerId());
        assertThat(row.get("role")).isEqualTo("SALON_MASTER");
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_outbox WHERE event_type = 'INVITE' AND aggregate_id IN "
                        + "(SELECT id FROM invite_tokens WHERE salon_id = ?)", String.class, salon.salonId());
        assertThat(payload).as("phone and link are sealed in the outbox").doesNotContain(phone)
                .doesNotContain("token=");
        verify(otpSmsSender, never()).sendStaffInvite(anyString(), anyString());

        String sms = drainAndCaptureSmsTo(phone);

        String link = linkOf(sms);
        assertThat(sms).isEqualTo(GuestMessageExpectedCopy.staffInvite(SALON_NAME, link));
        assertThat(sms.length()).isLessThanOrEqualTo(GuestMessageExpectedCopy.STAFF_INVITE_MAX_CHARS);
        String rawToken = link.substring(link.indexOf("token=") + "token=".length());
        ResponseEntity<String> preview = restTemplate.getForEntity(VALIDATE_URL + rawToken, String.class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("salon admin invites into their own salon (salon.md) -> 201, the admin is the recorded inviter")
    void should_return201_when_adminInvitesIntoOwnSalon() {
        Salon salon = newSalon();
        String adminToken = staffToken("SALON_ADMIN", salon.salonId());

        ResponseEntity<String> response = inviteViaSalon(adminToken, salon.salonId(), newPhone());

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(inviteRows(salon.salonId())).isEqualTo(1);
    }

    @Test
    @DisplayName("salon admin invites into ANOTHER salon -> 403, nothing written")
    void should_return403_when_adminInvitesIntoOtherSalon() {
        Salon own = newSalon();
        Salon other = newSalon();
        String adminToken = staffToken("SALON_ADMIN", own.salonId());

        ResponseEntity<String> response = inviteViaSalon(adminToken, other.salonId(), newPhone());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inviteRows(other.salonId())).isZero();
    }

    @Test
    @DisplayName("POST /auth/invite: owner -> 201; salon admin -> 403 (owner-only endpoint)")
    void should_allowOwnerAndRefuseAdmin_when_usingAuthInviteEndpoint() {
        Salon salon = newSalon();
        String adminToken = staffToken("SALON_ADMIN", salon.salonId());
        Map<String, Object> body = Map.of("phone", newPhone(), "salonId", salon.salonId().toString());

        ResponseEntity<String> owner = postAs(salon.ownerToken(), AUTH_INVITE_URL, body);
        ResponseEntity<String> admin = postAs(adminToken, AUTH_INVITE_URL,
                Map.of("phone", newPhone(), "salonId", salon.salonId().toString()));

        assertThat(owner.getStatusCode()).as("body: %s", owner.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inviteRows(salon.salonId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a CLIENT gets 403 on both create endpoints; no token at all gets 401")
    void should_refuseClientAndAnonymous_when_creatingInvite() {
        Salon salon = newSalon();
        String clientEmail = newEmail();
        seedUser("CLIENT", clientEmail, true, null, null);
        String clientToken = accessToken(clientEmail);

        ResponseEntity<String> clientSalon = inviteViaSalon(clientToken, salon.salonId(), newPhone());
        ResponseEntity<String> clientAuth = postAs(clientToken, AUTH_INVITE_URL,
                Map.of("phone", newPhone(), "salonId", salon.salonId().toString()));
        ResponseEntity<String> anonymous = post(AUTH_INVITE_URL,
                Map.of("phone", newPhone(), "salonId", salon.salonId().toString()));

        assertThat(clientSalon.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(clientAuth.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inviteRows(salon.salonId())).isZero();
    }

    @Test
    @DisplayName("a SALON_MASTER inviter is stopped by the controller role gate (403) on BOTH create endpoints, so a "
            + "registered phone never leaks as 409; the service-level AUTH-18 order is pinned in InviteServiceTest")
    void should_return403AtRoleGate_when_salonMasterInvitesRegisteredPhone() {
        Salon salon = newSalon();
        String masterToken = staffToken("SALON_MASTER", salon.salonId());
        String taken = newPhone();
        seedUser("CLIENT", null, false, taken, null);

        ResponseEntity<String> salonScoped = inviteViaSalon(masterToken, salon.salonId(), taken);
        ResponseEntity<String> authScoped = postAs(masterToken, AUTH_INVITE_URL,
                Map.of("phone", taken, "salonId", salon.salonId().toString()));

        for (ResponseEntity<String> response : List.of(salonScoped, authScoped)) {
            assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).doesNotContain("PHONE_ALREADY_REGISTERED");
        }
        assertThat(inviteRows(salon.salonId())).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("an invite into a deactivated salon -> 409 INVITE_SALON_INACTIVE; no row, no SMS row")
    void should_return409SalonInactive_when_invitingIntoDeactivatedSalon() {
        Salon salon = newSalon();
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());

        ResponseEntity<String> salonScoped = inviteViaSalon(salon.ownerToken(), salon.salonId(), newPhone());
        ResponseEntity<String> authScoped = postAs(salon.ownerToken(), AUTH_INVITE_URL,
                Map.of("phone", newPhone(), "salonId", salon.salonId().toString()));

        for (ResponseEntity<String> response : List.of(salonScoped, authScoped)) {
            assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_SALON_INACTIVE");
        }
        assertThat(inviteRows(salon.salonId())).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("probe budget: the inviter's 11th PHONE_ALREADY_REGISTERED within an hour -> 429 INVITE_SMS_LIMIT, and "
            + "an unregistered phone is then 429 too (no oracle); no row, no SMS row")
    void should_return429_when_inviterCollectsEleventhConflictWithinHour() {
        Salon salon = newSalon();
        for (int i = 0; i < 10; i++) {
            String taken = newPhone();
            seedUser("CLIENT", null, false, taken, null);
            assertThat(inviteViaSalon(salon.ownerToken(), salon.salonId(), taken).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
        String eleventh = newPhone();
        seedUser("CLIENT", null, false, eleventh, null);

        ResponseEntity<String> probe = inviteViaSalon(salon.ownerToken(), salon.salonId(), eleventh);
        ResponseEntity<String> fresh = inviteViaSalon(salon.ownerToken(), salon.salonId(), newPhone());

        for (ResponseEntity<String> response : List.of(probe, fresh)) {
            assertThat(response.getStatusCode()).as("body: %s", response.getBody())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_SMS_LIMIT");
        }
        assertThat(inviteRows(salon.salonId())).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("an email-only body (old build) -> 400 INVITE_PHONE_REQUIRED on both endpoints; no row, no SMS")
    void should_return400InvitePhoneRequired_when_bodyHasOnlyEmail() {
        Salon salon = newSalon();

        ResponseEntity<String> salonScoped = postAs(salon.ownerToken(), "/api/v1/salons/" + salon.salonId()
                + "/invite", Map.of("email", "old-build@beautica.test"));
        ResponseEntity<String> authScoped = postAs(salon.ownerToken(), AUTH_INVITE_URL,
                Map.of("email", "old-build@beautica.test", "salonId", salon.salonId().toString()));

        for (ResponseEntity<String> response : List.of(salonScoped, authScoped)) {
            assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_PHONE_REQUIRED");
        }
        assertThat(inviteRows(salon.salonId())).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("a landline or a foreign number -> 400 PHONE_NOT_UA_MOBILE; no row, no SMS")
    void should_return400PhoneNotUaMobile_when_landlineOrForeign() {
        Salon salon = newSalon();

        for (String phone : List.of("+380441234567", "+48501234567")) {
            ResponseEntity<String> response = inviteViaSalon(salon.ownerToken(), salon.salonId(), phone);

            assertThat(response.getStatusCode()).as(phone).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).at("/data/code").asText()).isEqualTo("PHONE_NOT_UA_MOBILE");
            assertThat(response.getBody()).doesNotContain(phone);
        }
        assertThat(inviteRows(salon.salonId())).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("a phone verified on any account -> 409 PHONE_ALREADY_REGISTERED for owner and admin; no row, no SMS")
    void should_return409_when_phoneVerifiedOnAnyAccount() {
        Salon salon = newSalon();
        String adminToken = staffToken("SALON_ADMIN", salon.salonId());
        String taken = newPhone();
        seedUser("CLIENT", null, false, taken, null);

        ResponseEntity<String> owner = inviteViaSalon(salon.ownerToken(), salon.salonId(), taken);
        ResponseEntity<String> admin = inviteViaSalon(adminToken, salon.salonId(), taken);

        for (ResponseEntity<String> response : List.of(owner, admin)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(json(response).at("/data/code").asText()).isEqualTo("PHONE_ALREADY_REGISTERED");
        }
        assertThat(inviteRows(salon.salonId())).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("AUTH-19: while a verified account holds the phone the invite is 409; once the account row is gone "
            + "(staff removal hard-deletes it) the same phone is invitable again")
    void should_allowReinvite_when_accountHoldingPhoneIsDeleted() {
        Salon salon = newSalon();
        String phone = newPhone();
        UUID staffId = seedUser("SALON_MASTER", null, false, phone, null);

        ResponseEntity<String> blocked = inviteViaSalon(salon.ownerToken(), salon.salonId(), phone);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", staffId);
        ResponseEntity<String> freed = inviteViaSalon(salon.ownerToken(), salon.salonId(), phone);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(freed.getStatusCode()).as("body: %s", freed.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("two concurrent invites of the same phone to the same salon -> both 201, ONE row, ONE SMS row")
    void should_keepOneRowAndOneSms_when_samePhoneInvitedConcurrently() throws Exception {
        Salon salon = newSalon();
        String phone = newPhone();
        CountDownLatch start = new CountDownLatch(1);
        Runnable invite = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(inviteViaSalon(salon.ownerToken(), salon.salonId(), phone).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        };
        CompletableFuture<Void> first = CompletableFuture.runAsync(invite);
        CompletableFuture<Void> second = CompletableFuture.runAsync(invite);

        start.countDown();
        CompletableFuture.allOf(first, second).get();

        assertThat(inviteRows(salon.salonId())).isEqualTo(1);
        assertThat(outboxInviteRows()).isEqualTo(1);
        drainWorker.drain();
        verify(otpSmsSender, times(1)).sendStaffInvite(eq(phone), anyString());
    }

    // ── end-to-end journey (QA B520) ────────────────────────────────────────

    private static String rawTokenOf(String link) {
        return URLDecoder.decode(link.substring(link.indexOf("token=") + "token=".length()), StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> acceptWithTicket(String rawToken, String ticket) {
        return post(ACCEPT_URL, Map.of("token", rawToken, "password", PASSWORD, "firstName", "Олена",
                "lastName", "Коваль", "registrationTicket", ticket));
    }

    @Test
    @DisplayName("journey: owner invites by SMS -> the link from the SMS validates with the masked phone -> the invitee "
            + "proves that phone by OTP -> accept -> SALON_MASTER of this salon, phone verified, no email, signs in by "
            + "phone, and the invite now reads ACCEPTED")
    void should_joinSalonAsMasterWithVerifiedPhone_when_inviteeFollowsSmsLinkEndToEnd() {
        Salon salon = newSalon();
        String phone = newPhone();
        assertThat(inviteViaSalon(salon.ownerToken(), salon.salonId(), phone).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String rawToken = rawTokenOf(linkOf(drainAndCaptureSmsTo(phone)));

        JsonNode preview = json(restTemplate.getForEntity(VALIDATE_URL + rawToken, String.class)).path("data");
        String ticket = ticketFor(phone);
        ResponseEntity<String> accepted = acceptWithTicket(rawToken, ticket);

        assertThat(preview.path("phoneMasked").asText()).isEqualTo("+380 •• ••• •• " + phone.substring(11));
        assertThat(preview.path("salonName").asText()).isEqualTo(SALON_NAME);
        assertThat(preview.path("role").asText()).isEqualTo("SALON_MASTER");
        assertThat(accepted.getStatusCode()).as("body: %s", accepted.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> user = jdbcTemplate.queryForMap("SELECT id, email, phone_verified_at, role, salon_id "
                + "FROM users WHERE phone_number = ?", phone);
        assertThat(user.get("email")).as("phone-first staff account has no email").isNull();
        assertThat(user.get("phone_verified_at")).as("phone verified by the invite journey").isNotNull();
        assertThat(user.get("role")).isEqualTo("SALON_MASTER");
        assertThat(user.get("salon_id")).isEqualTo(salon.salonId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM masters WHERE user_id = ? AND salon_id = ?",
                Integer.class, user.get("id"), salon.salonId())).isEqualTo(1);
        assertThat(accessToken(phone)).isNotBlank();
        JsonNode invites = json(exchangeAs(salon.ownerToken(), HttpMethod.GET,
                "/api/v1/salons/" + salon.salonId() + "/invites", null)).at("/data/invites");
        assertThat(invites).hasSize(1);
        assertThat(invites.get(0).path("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("journey: a salon admin invites a SALON_ADMIN into their own salon -> after accept the account is a "
            + "SALON_ADMIN of that salon with a verified phone, no email and no master row; the admin is the inviter")
    void should_joinSalonAsAdmin_when_adminInvitesAdminIntoOwnSalonEndToEnd() {
        Salon salon = newSalon();
        String adminEmail = newEmail();
        UUID adminId = seedUser("SALON_ADMIN", adminEmail, true, null, null);
        jdbcTemplate.update("UPDATE users SET salon_id = ? WHERE id = ?", salon.salonId(), adminId);
        String phone = newPhone();
        ResponseEntity<String> created = postAs(accessToken(adminEmail),
                "/api/v1/salons/" + salon.salonId() + "/invite", Map.of("phone", phone, "role", "SALON_ADMIN"));
        assertThat(created.getStatusCode()).as("body: %s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String rawToken = rawTokenOf(linkOf(drainAndCaptureSmsTo(phone)));

        ResponseEntity<String> accepted = acceptWithTicket(rawToken, ticketFor(phone));

        assertThat(accepted.getStatusCode()).as("body: %s", accepted.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT invited_by FROM invite_tokens WHERE phone = ?", UUID.class,
                phone)).isEqualTo(adminId);
        Map<String, Object> user = jdbcTemplate.queryForMap("SELECT id, email, phone_verified_at, role, salon_id "
                + "FROM users WHERE phone_number = ?", phone);
        assertThat(user.get("email")).isNull();
        assertThat(user.get("phone_verified_at")).isNotNull();
        assertThat(user.get("role")).isEqualTo("SALON_ADMIN");
        assertThat(user.get("salon_id")).isEqualTo(salon.salonId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM masters WHERE user_id = ?", Integer.class,
                user.get("id"))).as("an admin invite creates no master row").isZero();
    }

    @Test
    @DisplayName("the owner of ANOTHER salon inviting a registered phone gets 403, never the 409 that would reveal the "
            + "phone (AUTH-18 order on the ownership branch); no row, no SMS row")
    void should_return403Not409_when_otherSalonOwnerInvitesRegisteredPhone() {
        Salon target = newSalon();
        Salon intruder = newSalon();
        String taken = newPhone();
        seedUser("CLIENT", null, false, taken, null);

        ResponseEntity<String> salonScoped = inviteViaSalon(intruder.ownerToken(), target.salonId(), taken);
        // POST /auth/invite reaches InviteService's own ownership branch (the salon endpoint is gated earlier).
        ResponseEntity<String> authScoped = postAs(intruder.ownerToken(), AUTH_INVITE_URL,
                Map.of("phone", taken, "salonId", target.salonId().toString()));

        for (ResponseEntity<String> response : List.of(salonScoped, authScoped)) {
            assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).doesNotContain("PHONE_ALREADY_REGISTERED");
        }
        assertThat(inviteRows(target.salonId())).isZero();
        assertThat(outboxInviteRows()).isZero();
    }

    @Test
    @DisplayName("the same SMS link cannot be redeemed twice: a second accept -> 400 INVITE_USED, still one account")
    void should_return400InviteUsed_when_smsLinkAcceptedTwice() {
        Salon salon = newSalon();
        String phone = newPhone();
        assertThat(inviteViaSalon(salon.ownerToken(), salon.salonId(), phone).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String rawToken = rawTokenOf(linkOf(drainAndCaptureSmsTo(phone)));
        assertThat(acceptWithTicket(rawToken, ticketFor(phone)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The phone is now registered, so a second OTP round trip is not available; the token check runs first anyway.
        ResponseEntity<String> again = acceptWithTicket(rawToken,
                StaffInviteFixtures.seedRegistrationTicket(jdbcTemplate, phone));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(again).at("/data/code").asText()).isEqualTo("INVITE_USED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE phone_number = ?", Integer.class,
                phone)).isEqualTo(1);
    }

    // ── validate, pre-deploy email rows, pending list ───────────────────────

    @Test
    @DisplayName("validate -> masked phone (last two digits only), role, salon name, expiry")
    void should_returnMaskedPhone_when_validatingPhoneInvite() {
        Invite invite = seedInvite();

        ResponseEntity<String> response = restTemplate.getForEntity(VALIDATE_URL + invite.rawToken(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(response).path("data");
        assertThat(data.path("phoneMasked").asText())
                .isEqualTo("+380 •• ••• •• " + invite.phone().substring(invite.phone().length() - 2));
        assertThat(response.getBody()).doesNotContain(invite.phone().substring(4));
        assertThat(data.path("role").asText()).isEqualTo("SALON_MASTER");
        assertThat(data.path("salonName").asText()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT name FROM salons WHERE id = ?", String.class, invite.salonId()));
        assertThat(data.path("expiresAt").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("an expired phone invite -> validate 400 INVITE_EXPIRED")
    void should_return400Expired_when_phoneInviteExpired() {
        Invite invite = seedInvite();
        jdbcTemplate.update("UPDATE invite_tokens SET expires_at = NOW() - interval '1 minute' WHERE phone = ?",
                invite.phone());

        ResponseEntity<String> response = restTemplate.getForEntity(VALIDATE_URL + invite.rawToken(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_EXPIRED");
    }

    @Test
    @DisplayName("an email invite created before the deploy: validate 400 and accept 404 like an unknown token, no "
            + "account, and it is not in the pending list")
    void should_refuseAndHideLegacyEmailInvite_when_rowHasNoPhone() {
        Salon salon = newSalon();
        String rawToken = "raw-legacy-" + UUID.randomUUID();
        String email = "legacy-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update("INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, false, NOW(), NOW())",
                UUID.randomUUID(), sha256Hex(rawToken), email, salon.salonId(),
                Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        Invite phoneInvite = seedInvite();

        ResponseEntity<String> validate = restTemplate.getForEntity(VALIDATE_URL + rawToken, String.class);
        ResponseEntity<String> accept = post(ACCEPT_URL, Map.of("token", rawToken, "password", PASSWORD,
                "firstName", "Олена", "lastName", "Коваль", "registrationTicket", ticketFor(phoneInvite.phone())));
        ResponseEntity<String> list = exchangeAs(salon.ownerToken(), HttpMethod.GET,
                "/api/v1/salons/" + salon.salonId() + "/invites", null);

        assertThat(validate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(validate).at("/data/code").asText()).isEqualTo("INVITE_NOT_FOUND");
        assertThat(accept.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(accept).at("/data/code").asText()).isEqualTo("INVITE_NOT_FOUND");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ? OR phone_number = ?",
                Integer.class, email, phoneInvite.phone())).isZero();
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(list).at("/data/invites")).isEmpty();
    }

    @Test
    @DisplayName("the pending list shows the invited phone (SAL-48)")
    void should_listInvitedPhone_when_phoneInvitePending() {
        Salon salon = newSalon();
        String phone = newPhone();
        assertThat(inviteViaSalon(salon.ownerToken(), salon.salonId(), phone).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> list = exchangeAs(salon.ownerToken(), HttpMethod.GET,
                "/api/v1/salons/" + salon.salonId() + "/invites", null);

        JsonNode invites = json(list).at("/data/invites");
        assertThat(invites).hasSize(1);
        assertThat(invites.get(0).path("recipientPhone").asText()).isEqualTo(phone);
        assertThat(invites.get(0).path("status").asText()).isEqualTo("PENDING");
    }

    // ── retention (D5) ──────────────────────────────────────────────────────

    @Test
    @DisplayName("cleanup deletes unaccepted phone invites ended > 30 days ago only (expired or revoked); keeps "
            + "younger ones, accepted ones, pre-B520 email rows and a long-expired invite revoked recently "
            + "(COALESCE(revoked_at, expires_at))")
    void should_deleteOnlyUnacceptedPhoneInvitesOlderThan30Days_when_cleanupRuns() {
        Salon salon = newSalon();
        UUID expired31 = insertInvite(salon.salonId(), newPhone(), null, -31, false, null);
        UUID cancelled31 = insertInvite(salon.salonId(), newPhone(), null, 2, true, -31);
        UUID superseded31 = insertInvite(salon.salonId(), newPhone(), null, 2, false, -31);
        UUID expired29 = insertInvite(salon.salonId(), newPhone(), null, -29, false, null);
        UUID accepted = insertInvite(salon.salonId(), newPhone(), null, -60, true, null);
        UUID expiredLongAgoRevokedRecently = insertInvite(salon.salonId(), newPhone(), null, -60, false, -5);
        UUID legacyEmail = insertInvite(salon.salonId(), null, "old-" + UUID.randomUUID() + "@beautica.test", -60,
                false, null);

        cleanupJob.sweepEndedPhoneInvites();

        List<UUID> remaining = jdbcTemplate.queryForList("SELECT id FROM invite_tokens WHERE salon_id = ?",
                UUID.class, salon.salonId());
        assertThat(remaining).containsExactlyInAnyOrder(expired29, accepted, legacyEmail, expiredLongAgoRevokedRecently)
                .doesNotContain(expired31, cancelled31, superseded31);
    }

    /**
     * @param expiresInDays   expiry relative to now (negative = in the past)
     * @param revokedDaysAgo  {@code null} = not revoked; otherwise revoked that many days from now (negative = past),
     *                        CANCELLED when {@code isUsed}, else SUPERSEDED
     */
    private UUID insertInvite(UUID salonId, String phone, String email, int expiresInDays, boolean isUsed,
                              Integer revokedDaysAgo) {
        UUID id = UUID.randomUUID();
        Timestamp revokedAt = revokedDaysAgo == null ? null
                : Timestamp.from(Instant.now().plus(revokedDaysAgo, ChronoUnit.DAYS));
        String reason = revokedDaysAgo == null ? null : (isUsed ? "CANCELLED" : "SUPERSEDED");
        jdbcTemplate.update("INSERT INTO invite_tokens (id, token, phone, email, salon_id, role, expires_at, is_used, "
                        + "revoked_at, revoked_reason, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'SALON_MASTER', ?, ?, ?, ?, NOW(), NOW())",
                id, sha256Hex(id.toString()), phone, email, salonId,
                Timestamp.from(Instant.now().plus(expiresInDays, ChronoUnit.DAYS)), isUsed, revokedAt, reason);
        return id;
    }
}
