package com.beautica.auth.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("POST /auth/invite/accept with a registration ticket (B514, AUTH-43)")
class InviteAcceptPhoneIT extends AbstractPhoneIdentityIT {

    private static final String ACCEPT_URL = "/api/v1/auth/invite/accept";

    private Map<String, Object> acceptBody(String rawToken, String ticket, String phoneNumber) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", rawToken);
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        body.put("lastName", "Коваль");
        if (ticket != null) {
            body.put("registrationTicket", ticket);
        }
        if (phoneNumber != null) {
            body.put("phoneNumber", phoneNumber);
        }
        return body;
    }

    @Test
    @DisplayName("an accept carrying a ticket creates the account with the ticket's phone already verified")
    void should_createAccountWithVerifiedTicketPhone_when_acceptCarriesTicket() {
        Invite invite = seedInvite();
        String phone = newPhone();

        ResponseEntity<String> response = post(ACCEPT_URL, acceptBody(invite.rawToken(), ticketFor(phone), null));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT phone_number, phone_verified_at FROM users WHERE email = ?", invite.email());
        assertThat(row.get("phone_number")).isEqualTo(phone);
        assertThat(row.get("phone_verified_at")).isNotNull();
    }

    @Test
    @DisplayName("a ticket phone another account already verified is 409 and the invite stays unused")
    void should_return409AndKeepInviteUnused_when_ticketPhoneAlreadyVerifiedElsewhere() {
        Invite invite = seedInvite();
        String phone = newPhone();
        String ticket = ticketFor(phone);
        jdbcTemplate.update("INSERT INTO users (id, password_hash, role, is_active, email_verified, phone_number, "
                + "phone_verified_at) VALUES (gen_random_uuid(), 'x', 'CLIENT', true, false, ?, now())", phone);

        ResponseEntity<String> response = post(ACCEPT_URL, acceptBody(invite.rawToken(), ticket, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("PHONE_ALREADY_REGISTERED");
        assertThat(jdbcTemplate.queryForObject("SELECT is_used FROM invite_tokens WHERE email = ?",
                Boolean.class, invite.email())).isFalse();
    }

    @Test
    @DisplayName("without a ticket and with the flag off, the legacy accept shape still works")
    void should_acceptLegacyShape_when_noTicketAndFlagOff() {
        Invite invite = seedInvite();

        ResponseEntity<String> response = post(ACCEPT_URL, acceptBody(invite.rawToken(), null, "+380501234567"));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT phone_verified_at FROM users WHERE email = ?",
                Timestamp.class, invite.email())).isNull();
    }
}
