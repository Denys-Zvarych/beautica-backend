package com.beautica.auth.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B514 QA — {@code APP_AUTH_REQUIRE_PHONE_REGISTRATION=true} through the REAL Spring validator wiring. The unit test
 * ({@code PhoneRegistrationShapeValidatorTest}) builds the validator with an explicit boolean; only a booted context
 * proves Spring's constraint-validator factory picks the property-reading constructor. If it silently used the
 * tolerant no-arg one, flipping the flag in prod would change nothing and old builds would keep creating
 * email-only accounts (AUTH-02, AUTH-43). One extra context, on purpose.
 */
@TestPropertySource(properties = "app.auth.require-phone-registration=true")
@DisplayName("APP_AUTH_REQUIRE_PHONE_REGISTRATION=true — legacy shapes refused, ticket shapes accepted (B514)")
class PhoneRegistrationRequiredFlagIT extends AbstractPhoneIdentityIT {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String REGISTER_IM_URL = "/api/v1/auth/register/independent-master";
    private static final String ACCEPT_URL = "/api/v1/auth/invite/accept";

    private static Map<String, Object> legacyBody(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        body.put("lastName", "Коваль");
        body.put("phoneNumber", "+380501234567");
        return body;
    }

    private long usersWithEmail(String email) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email);
    }

    private void assertTicketRequired(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/errors/registrationTicket").asText())
                .as("the missing ticket is named, body=%s", response.getBody()).isNotBlank();
    }

    @Test
    @DisplayName("an old-build client registration (email shape, no ticket) is refused with 400 naming "
            + "registrationTicket, and no account is created")
    void should_rejectLegacyRegister_when_flagOn() {
        String email = "legacy-on-" + System.nanoTime() + "@beautica.test";
        Map<String, Object> body = legacyBody(email);
        body.put("role", "CLIENT");

        ResponseEntity<String> response = post(REGISTER_URL, consent(body));

        assertTicketRequired(response);
        assertThat(usersWithEmail(email)).isZero();
    }

    @Test
    @DisplayName("an old-build independent-master registration is refused the same way")
    void should_rejectLegacyIndependentMasterRegister_when_flagOn() {
        String email = "legacy-im-on-" + System.nanoTime() + "@beautica.test";

        ResponseEntity<String> response = post(REGISTER_IM_URL, consent(legacyBody(email)));

        assertTicketRequired(response);
        assertThat(usersWithEmail(email)).isZero();
    }

    @Test
    @DisplayName("an invite accept without a ticket is refused and the invite stays unused")
    void should_rejectLegacyInviteAccept_when_flagOn() {
        Invite invite = seedInvite();
        Map<String, Object> body = new HashMap<>(Map.of("token", invite.rawToken(), "password", PASSWORD,
                "firstName", "Олена", "lastName", "Коваль", "phoneNumber", "+380501234567"));

        ResponseEntity<String> response = post(ACCEPT_URL, body);

        assertTicketRequired(response);
        assertThat(jdbcTemplate.queryForObject("SELECT is_used FROM invite_tokens WHERE phone = ?",
                Boolean.class, invite.phone())).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE phone_number = ?", Long.class,
                invite.phone())).isZero();
    }

    @Test
    @DisplayName("a ticket registration still succeeds with the flag on (the flag only closes the legacy shape)")
    void should_registerWithTicket_when_flagOn() {
        String phone = newPhone();
        Map<String, Object> body = new HashMap<>();
        body.put("registrationTicket", ticketFor(phone));
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        body.put("role", "CLIENT");

        ResponseEntity<String> response = post(REGISTER_URL, consent(body));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).at("/data/auth/accessToken").asText()).isNotBlank();
    }
}
