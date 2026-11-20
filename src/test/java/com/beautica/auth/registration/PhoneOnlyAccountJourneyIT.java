package com.beautica.auth.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * B514 QA — the whole life of an account that has NO email (AUTH-D11), end to end on one context: register with a
 * ticket, sign in by phone (national spelling), then reach a signed-in endpoint whose controller reads the JWT
 * principal (support form, the only {@code getPrincipal()} reader). Guards the two null-email holes the unit layer
 * cannot see together: {@code JwtAuthenticationFilter} must authenticate a token without an email claim, and
 * {@code SupportController} must accept an empty principal instead of answering 403.
 */
@DisplayName("Phone-only account journey: register -> login by phone -> signed-in support form (B514, AUTH-D11)")
class PhoneOnlyAccountJourneyIT extends AbstractPhoneIdentityIT {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String SUPPORT_URL = "/api/v1/support/contact";
    private static final String SUPPORT_MESSAGE = "Не можу знайти свій запис, допоможіть.";

    /** Registers a phone-only CLIENT through the real ticket flow; returns the user id. */
    private String registerPhoneOnlyClient(String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("registrationTicket", ticketFor(phone));
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        body.put("role", "CLIENT");
        ResponseEntity<String> registered = post(REGISTER_URL, consent(body));
        assertThat(registered.getStatusCode()).as("register body: %s", registered.getBody()).isEqualTo(HttpStatus.OK);
        return json(registered).at("/data/auth/userId").asText();
    }

    private ResponseEntity<String> postSupport(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpHeaders jsonPart = new HttpHeaders();
        jsonPart.setContentType(MediaType.APPLICATION_JSON);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        try {
            form.add("request", new HttpEntity<>(objectMapper.writeValueAsString(Map.of("message", SUPPORT_MESSAGE))
                    .getBytes(StandardCharsets.UTF_8), jsonPart));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return restTemplate.exchange(SUPPORT_URL, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    @Test
    @DisplayName("a phone-only client signs in with the national spelling of its phone and gets tokens for the same "
            + "account, with no email in the response")
    void should_signInByNationalPhone_when_accountRegisteredWithoutEmail() {
        String phone = newPhone();
        String userId = registerPhoneOnlyClient(phone);

        ResponseEntity<String> login = post(LOGIN_URL, Map.of("identifier", "0" + phone.substring(4),
                "password", PASSWORD));

        assertThat(login.getStatusCode()).as("login body: %s", login.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(login).at("/data");
        assertThat(data.at("/userId").asText()).as("same account as registered").isEqualTo(userId);
        assertThat(data.at("/role").asText()).isEqualTo("CLIENT");
        assertThat(data.at("/email").isNull() || data.at("/email").isMissingNode())
                .as("no email exists for this account, data=%s", data).isTrue();
        assertThat(data.at("/accessToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the access token of a phone-only account (no email claim) reaches the support form: 202, and support "
            + "gets the sender's user id with no email (no Reply-To)")
    void should_acceptSupportMessage_when_senderHasNoEmail() {
        String phone = newPhone();
        String userId = registerPhoneOnlyClient(phone);
        String accessToken = json(post(LOGIN_URL, Map.of("identifier", phone, "password", PASSWORD)))
                .at("/data/accessToken").asText();

        ResponseEntity<String> response = postSupport(accessToken);

        assertThat(response.getStatusCode()).as("support body: %s", response.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(emailService).sendSupportContactEmail(eq(SUPPORT_INBOX), eq(userId), isNull(), isNull(),
                eq(SUPPORT_MESSAGE), anyList());
    }

    @Test
    @DisplayName("the phone-only account cannot be reached through an email identifier — 401 like any unknown login")
    void should_return401_when_phoneOnlyAccountTriedByEmailIdentifier() {
        String phone = newPhone();
        registerPhoneOnlyClient(phone);

        ResponseEntity<String> byEmail = post(LOGIN_URL, Map.of("identifier", phone.substring(1) + "@beautica.test",
                "password", PASSWORD));

        assertThat(byEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(byEmail).at("/data/accessToken").isMissingNode()).isTrue();
    }
}
