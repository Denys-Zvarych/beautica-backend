package com.beautica.auth.registration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("POST /auth/register[/independent-master] with a registration ticket (B514, AUTH-02 / AUTH-D11)")
class PhoneFirstRegistrationIT extends AbstractPhoneIdentityIT {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String REGISTER_IM_URL = "/api/v1/auth/register/independent-master";

    private Map<String, Object> phoneBody(String ticket, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("registrationTicket", ticket);
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        if (role != null) {
            body.put("role", role);
        }
        return consent(body);
    }

    private Map<String, Object> userRow(String phone) {
        return jdbcTemplate.queryForMap("SELECT id, email, role, phone_verified_at, terms_accepted_at "
                + "FROM users WHERE phone_number = ?", phone);
    }

    private void assertSignedInPhoneAccount(ResponseEntity<String> response, String phone, String role) {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(response).at("/data");
        assertThat(data.at("/email").isNull()).isTrue();
        assertThat(data.at("/auth/accessToken").asText()).isNotBlank();
        assertThat(data.at("/auth/refreshToken").asText()).isNotBlank();
        assertThat(data.at("/auth/role").asText()).isEqualTo(role);
        Map<String, Object> row = userRow(phone);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("role")).isEqualTo(role);
        assertThat(row.get("phone_verified_at")).isNotNull();
        assertThat(row.get("terms_accepted_at")).isNotNull();
    }

    @Test
    @DisplayName("a client registering with a ticket gets a phone-only account and is signed in at once")
    void should_createPhoneOnlyClientAndIssueTokens_when_clientRegistersWithTicket() {
        String phone = newPhone();
        String ticket = ticketFor(phone);

        ResponseEntity<String> response = post(REGISTER_URL, phoneBody(ticket, "CLIENT"));

        assertSignedInPhoneAccount(response, phone, "CLIENT");
    }

    @Test
    @DisplayName("an owner registering with a ticket and business name gets a phone-only account")
    void should_createPhoneOnlyOwner_when_ownerRegistersWithTicketAndBusinessName() {
        String phone = newPhone();
        Map<String, Object> body = phoneBody(ticketFor(phone), "SALON_OWNER");
        body.put("businessName", "Студія Олени");

        ResponseEntity<String> response = post(REGISTER_URL, body);

        assertSignedInPhoneAccount(response, phone, "SALON_OWNER");
    }

    @Test
    @DisplayName("an independent master registering with a ticket gets a phone-only account and a master profile")
    void should_createPhoneOnlyMasterWithProfile_when_independentMasterRegistersWithTicket() {
        String phone = newPhone();

        ResponseEntity<String> response = post(REGISTER_IM_URL, phoneBody(ticketFor(phone), null));

        assertSignedInPhoneAccount(response, phone, "INDEPENDENT_MASTER");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM masters WHERE user_id = ?", Integer.class,
                userRow(phone).get("id"))).isOne();
    }

    @Test
    @DisplayName("an account without email serves authenticated requests and refreshes its tokens")
    void should_serveAuthenticatedRequestsAndRefresh_when_accountHasNoEmail() {
        String phone = newPhone();
        JsonNode auth = json(post(REGISTER_URL, phoneBody(ticketFor(phone), "CLIENT"))).at("/data/auth");
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(auth.at("/accessToken").asText());

        ResponseEntity<String> me = restTemplate.exchange("/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(bearer), String.class);
        ResponseEntity<String> refreshed = post("/api/v1/auth/refresh",
                Map.of("refreshToken", auth.at("/refreshToken").asText()));

        assertThat(me.getStatusCode()).as("body: %s", me.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getStatusCode()).as("body: %s", refreshed.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a ticket used once is rejected as TICKET_INVALID on reuse")
    void should_return400TicketInvalid_when_ticketReused() {
        String ticket = ticketFor(newPhone());
        post(REGISTER_URL, phoneBody(ticket, "CLIENT"));

        ResponseEntity<String> again = post(REGISTER_URL, phoneBody(ticket, "CLIENT"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(again).at("/data/code").asText()).isEqualTo("REGISTRATION_TICKET_INVALID");
    }

    @Test
    @DisplayName("an expired ticket is 400 TICKET_INVALID and creates no user")
    void should_return400TicketInvalidAndCreateNoUser_when_ticketExpired() {
        String phone = newPhone();
        String ticket = ticketFor(phone);
        jdbcTemplate.update("UPDATE phone_registration_tickets SET expires_at = now() - interval '1 second' "
                + "WHERE phone = ?", phone);

        ResponseEntity<String> response = post(REGISTER_URL, phoneBody(ticket, "CLIENT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("REGISTRATION_TICKET_INVALID");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE phone_number = ?",
                Integer.class, phone)).isZero();
    }

    @Test
    @DisplayName("sending both a ticket and an email is a 400")
    void should_return400_when_ticketAndEmailBothSent() {
        Map<String, Object> body = phoneBody(ticketFor(newPhone()), "CLIENT");
        body.put("email", "olena@example.com");

        ResponseEntity<String> response = post(REGISTER_URL, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/errors/email").asText())
                .isEqualTo("Email is not accepted with a registration ticket");
    }

    @Test
    @DisplayName("without a ticket and with the flag off, the legacy email registration still works")
    void should_keepLegacyEmailShape_when_noTicketAndFlagOff() {
        Map<String, Object> body = new HashMap<>(Map.of("email", "legacy-" + System.nanoTime() + "@example.com",
                "password", PASSWORD, "role", "CLIENT", "firstName", "Олена", "lastName", "Коваль",
                "phoneNumber", "+380501234567"));

        ResponseEntity<String> response = post(REGISTER_URL, body);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).at("/data").has("auth")).isFalse();
        assertThat(json(response).at("/data/email").asText()).startsWith("legacy-");
    }
}
