package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.ApiResponse;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Auth — independent master registration integration")
class IndependentMasterRegistrationIT {

    private static final Logger log = LoggerFactory.getLogger(IndependentMasterRegistrationIT.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String REGISTER_URL = "/api/v1/auth/register/independent-master";
    private static final String CLIENT_REGISTER_URL = "/api/v1/auth/register";
    private static final String TEST_PASSWORD = "SecurePass1!";
    private static final String TEST_FIRST = "Oksana";
    private static final String TEST_LAST = "Kovalenko";
    private static final String TEST_PHONE = "+380671234567";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String registeredEmail;

    @BeforeEach
    void setUp() {
        registeredEmail = "im-test-" + System.nanoTime() + "@beautica.test";
        log.debug("setUp: email={}", registeredEmail);
    }

    @AfterEach
    void cleanUp() {
        if (registeredEmail == null) return;
        // masters.user_id → users.id restricts user deletion; delete master first
        jdbcTemplate.update(
                "DELETE FROM masters WHERE user_id = (SELECT id FROM users WHERE email = ?)",
                registeredEmail);
        transactionTemplate.executeWithoutResult(status -> {
            userRepository.findByEmail(registeredEmail).ifPresent(user -> {
                log.debug("cleanUp: deleting refresh tokens for userId={}", user.getId());
                refreshTokenRepository.deleteByUserId(user.getId());
                log.debug("cleanUp: deleting user email={}", registeredEmail);
                userRepository.delete(user);
            });
        });
        registeredEmail = null;
    }

    @Test
    @DisplayName("returns 201 with INDEPENDENT_MASTER role when request is valid")
    void should_return201WithIndependentMasterRole_when_validRequest() {
        var request = new RegisterIndependentMasterRequest(
                registeredEmail, TEST_PASSWORD, TEST_FIRST, TEST_LAST, TEST_PHONE);

        log.debug("Act: POST {} with valid INDEPENDENT_MASTER payload, email={}", REGISTER_URL, registeredEmail);
        var response = postIndependentMaster(request);

        assertThat(response.getStatusCode())
                .as("status for valid independent master registration, email=%s", registeredEmail)
                .isEqualTo(HttpStatus.CREATED);

        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.message()).isNull();

        var data = body.data();
        assertThat(data).isNotNull();
        assertThat(data.role()).isEqualTo(Role.INDEPENDENT_MASTER);
        assertThat(data.email()).isEqualTo(registeredEmail);
        assertThat(data.tokenType()).isEqualTo("Bearer");
        assertThat(data.accessToken()).isNotBlank();
        assertThat(data.refreshToken()).isNotBlank();
        assertThat(data.userId()).isNotNull();
    }

    @Test
    @DisplayName("persists user row with role INDEPENDENT_MASTER when registration succeeds")
    void should_persistUserWithCorrectRole_when_independentMasterRegisters() {
        var request = new RegisterIndependentMasterRequest(
                registeredEmail, TEST_PASSWORD, TEST_FIRST, TEST_LAST, TEST_PHONE);

        log.debug("Act: POST {} to persist user row, email={}", REGISTER_URL, registeredEmail);
        postIndependentMaster(request);

        var saved = userRepository.findByEmail(registeredEmail);
        assertThat(saved).isPresent();

        var user = saved.get();
        assertThat(user.getRole()).isEqualTo(Role.INDEPENDENT_MASTER);
        assertThat(user.getEmail()).isEqualTo(registeredEmail);
        assertThat(user.getFirstName()).isEqualTo(TEST_FIRST);
        assertThat(user.getLastName()).isEqualTo(TEST_LAST);
        assertThat(user.getPhoneNumber()).isEqualTo(TEST_PHONE);
    }

    @Test
    @DisplayName("returns 409 when email is already registered as a client")
    void should_return409_when_emailAlreadyRegisteredAsClient() {
        var clientRequest = new RegisterRequest(
                registeredEmail, TEST_PASSWORD, SelfRegistrationRole.CLIENT, TEST_FIRST, TEST_LAST, TEST_PHONE, null);

        log.debug("Arrange: register email={} as CLIENT first", registeredEmail);
        postClient(clientRequest);

        log.debug("Act: register same email={} as INDEPENDENT_MASTER when already a CLIENT", registeredEmail);
        var masterRequest = new RegisterIndependentMasterRequest(
                registeredEmail, TEST_PASSWORD, TEST_FIRST, TEST_LAST, TEST_PHONE);
        var response = postIndependentMaster(masterRequest);

        assertThat(response.getStatusCode())
                .as("status must be 409 when email is already registered as CLIENT")
                .isEqualTo(HttpStatus.CONFLICT);

        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).containsIgnoringCase("already registered");
    }

    @Test
    @DisplayName("returns 400 when password is shorter than 8 characters")
    void should_return400_when_passwordTooShort() {
        registeredEmail = null;
        var request = new RegisterIndependentMasterRequest(
                "im-short-pw@beautica.test", "short", TEST_FIRST, TEST_LAST, null);

        log.debug("Act: POST {} with password shorter than 8 chars", REGISTER_URL);
        var response = postIndependentMaster(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("returns 400 when email format is invalid")
    void should_return400_when_emailIsInvalid() {
        registeredEmail = null;
        var request = new RegisterIndependentMasterRequest(
                "not-an-email", TEST_PASSWORD, TEST_FIRST, TEST_LAST, null);

        log.debug("Act: POST {} with malformed email", REGISTER_URL);
        var response = postIndependentMaster(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("returns 400 when firstName exceeds 100 characters")
    void should_return400_when_firstNameExceedsMaxLength() {
        registeredEmail = null;
        var oversizedFirstName = "A".repeat(101);
        var request = new RegisterIndependentMasterRequest(
                "im-oversize@beautica.test", TEST_PASSWORD, oversizedFirstName, TEST_LAST, null);

        log.debug("Act: POST {} with firstName of {} chars", REGISTER_URL, oversizedFirstName.length());
        var response = postIndependentMaster(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
    }

    private ResponseEntity<ApiResponse<AuthResponse>> postIndependentMaster(
            RegisterIndependentMasterRequest request) {
        return restTemplate.exchange(
                REGISTER_URL,
                HttpMethod.POST,
                new HttpEntity<>(request),
                new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<AuthResponse>> postClient(RegisterRequest request) {
        return restTemplate.exchange(
                CLIENT_REGISTER_URL,
                HttpMethod.POST,
                new HttpEntity<>(request),
                new ParameterizedTypeReference<>() {}
        );
    }
}
