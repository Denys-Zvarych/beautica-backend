package com.beautica;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.beautica.notification.telegram.CapturingTelegramMessenger;
import com.beautica.notification.telegram.NoOpTelegramMessenger;
import com.beautica.notification.telegram.TelegramBotApiClient;
import com.beautica.notification.telegram.TelegramDeliveryException;
import com.beautica.notification.telegram.TelegramMessenger;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke: the context boots and the only exposed actuator endpoint is {@code health}, UP including
 * the {@code db} component. Health details are enabled only in {@code application-test.yml}.
 */
@DisplayName("BeauticaApplication — smoke")
class BeauticaApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("should_returnUpWithDbComponent_when_healthRequested")
    void should_returnUpWithDbComponent_when_healthRequested() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body.at("/status").asText()).isEqualTo("UP");
        assertThat(body.at("/components/db/status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("should_return404_when_nonHealthActuatorEndpointRequested")
    void should_return404_when_nonHealthActuatorEndpointRequested() {
        // Authenticated, so a 404 proves the endpoint is NOT EXPOSED (anonymous would stop at 401).
        // The JWT filter only authenticates a subject that has a users row, so persist a real one.
        User user = userRepository.save(new User(
                "smoke-" + java.util.UUID.randomUUID() + "@example.com",
                new BCryptPasswordEncoder(4).encode("test-password"),
                Role.CLIENT, "Smoke", "Test", "+380501111111"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), Role.CLIENT));

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/env", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Phase 404 — default config: the only TelegramMessenger is the NoOp, no live/capture bean, send falls back")
    void should_wireOnlyNoOpTelegramMessenger_when_defaultConfig() {
        var messengers = applicationContext.getBeansOfType(TelegramMessenger.class);

        assertThat(messengers.values()).singleElement().isExactlyInstanceOf(NoOpTelegramMessenger.class);
        assertThat(applicationContext.getBeansOfType(TelegramBotApiClient.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(CapturingTelegramMessenger.class)).isEmpty();
        // Structural no-network proof: the NoOp holds no collaborator at all (no RestClient, no mail sender).
        assertThat(Arrays.stream(NoOpTelegramMessenger.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).map(Field::getName)).isEmpty();
        assertThatThrownBy(() -> messengers.values().iterator().next().send(1L, "hi"))
                .isExactlyInstanceOf(TelegramDeliveryException.class)
                .hasMessageContaining("app.telegram.enabled=false");
    }
}
