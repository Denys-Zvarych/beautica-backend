package com.beautica.auth.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B520 (AUTH-52, D4) — {@code POST /auth/invite/accept} for a phone invite: the B514 registration ticket (real OTP
 * round trip through the mocked SMS sender) must prove the INVITED phone. Mutation guard: dropping the phone-equality
 * check in {@code InviteService#acceptInvite} turns the mismatch case red.
 */
@DisplayName("Invite accept — the ticket phone must equal the invited phone (B520)")
class InviteAcceptPhoneMatchIT extends AbstractPhoneIdentityIT {

    private static final String ACCEPT_URL = "/api/v1/auth/invite/accept";

    private Map<String, Object> acceptBody(String rawToken, String ticket) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", rawToken);
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        body.put("lastName", "Коваль");
        if (ticket != null) {
            body.put("registrationTicket", ticket);
        }
        return body;
    }

    private long usersWithPhone(String phone) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE phone_number = ?", Long.class, phone);
    }

    private boolean inviteUsed(Invite invite) {
        return jdbcTemplate.queryForObject("SELECT is_used FROM invite_tokens WHERE phone = ?", Boolean.class,
                invite.phone());
    }

    @Test
    @DisplayName("a ticket for the invited phone -> 201; the account has that phone verified, no email, the token's "
            + "role and salon, a master row; it can sign in by phone")
    void should_createVerifiedPhoneAccount_when_ticketPhoneMatchesInvite() {
        Invite invite = seedInvite();

        ResponseEntity<String> response = post(ACCEPT_URL, acceptBody(invite.rawToken(), ticketFor(invite.phone())));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> user = jdbcTemplate.queryForMap("SELECT id, email, phone_verified_at, role, salon_id "
                + "FROM users WHERE phone_number = ?", invite.phone());
        assertThat(user.get("email")).isNull();
        assertThat(user.get("phone_verified_at")).isNotNull();
        assertThat(user.get("role")).isEqualTo("SALON_MASTER");
        assertThat(user.get("salon_id")).isEqualTo(invite.salonId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM masters WHERE user_id = ?", Integer.class,
                user.get("id"))).isEqualTo(1);
        assertThat(inviteUsed(invite)).isTrue();
        assertThat(accessToken(invite.phone())).isNotBlank();
    }

    @Test
    @DisplayName("a ticket for ANOTHER phone -> 400 INVITE_PHONE_MISMATCH; no account for either phone, invite unused")
    void should_return400Mismatch_when_ticketPhoneDiffersFromInvite() {
        Invite invite = seedInvite();
        String otherPhone = newPhone();

        ResponseEntity<String> response = post(ACCEPT_URL, acceptBody(invite.rawToken(), ticketFor(otherPhone)));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVITE_PHONE_MISMATCH");
        assertThat(response.getBody()).doesNotContain(invite.phone()).doesNotContain(otherPhone);
        assertThat(usersWithPhone(invite.phone())).isZero();
        assertThat(usersWithPhone(otherPhone)).isZero();
        assertThat(inviteUsed(invite)).isFalse();
    }

    @Test
    @DisplayName("a refused mismatch burns neither the invite nor the wrong ticket; the invitee then accepts with a "
            + "ticket for the invited phone -> 201")
    void should_acceptWithMatchingTicket_when_earlierMismatchWasRefused() {
        Invite invite = seedInvite();
        String otherPhone = newPhone();
        String wrongTicket = ticketFor(otherPhone);
        ResponseEntity<String> refused = post(ACCEPT_URL, acceptBody(invite.rawToken(), wrongTicket));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> accepted = post(ACCEPT_URL, acceptBody(invite.rawToken(), ticketFor(invite.phone())));

        assertThat(accepted.getStatusCode()).as("body: %s", accepted.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(usersWithPhone(invite.phone())).isEqualTo(1);
        assertThat(usersWithPhone(otherPhone)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT used FROM phone_registration_tickets WHERE ticket_hash = ?",
                Boolean.class, sha256Hex(wrongTicket))).as("the refused accept rolled the ticket back").isFalse();
    }

    @Test
    @DisplayName("the invited phone was verified on another account after the invite -> 409, invite unused")
    void should_return409_when_invitedPhoneAlreadyClaimed() {
        Invite invite = seedInvite();
        String ticket = ticketFor(invite.phone());
        seedUser("CLIENT", null, false, invite.phone(), null);

        ResponseEntity<String> response = post(ACCEPT_URL, acceptBody(invite.rawToken(), ticket));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("PHONE_ALREADY_REGISTERED");
        assertThat(usersWithPhone(invite.phone())).isEqualTo(1);
        assertThat(inviteUsed(invite)).isFalse();
    }

    @Test
    @DisplayName("no ticket (old build, legacy accept shape with the flag off) -> 400 REGISTRATION_TICKET_INVALID, no "
            + "account")
    void should_return400_when_ticketMissing() {
        Invite invite = seedInvite();
        Map<String, Object> body = acceptBody(invite.rawToken(), null);
        body.put("phoneNumber", invite.phone());

        ResponseEntity<String> response = post(ACCEPT_URL, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("REGISTRATION_TICKET_INVALID");
        assertThat(usersWithPhone(invite.phone())).isZero();
        assertThat(inviteUsed(invite)).isFalse();
    }
}
