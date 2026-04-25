package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestSecurityConfig.class)
@DisplayName("Invite endpoints — integration")
class InviteControllerIT {

    private static final Logger log = LoggerFactory.getLogger(InviteControllerIT.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private InviteTokenRepository inviteTokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private EmailService emailService;

    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> {
            for (String email : createdEmails) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    refreshTokenRepository.deleteByUserId(user.getId());
                    userRepository.delete(user);
                });
                inviteTokenRepository.findByEmailAndIsUsedFalse(email)
                        .ifPresent(inviteTokenRepository::delete);
            }
        });
        createdEmails.clear();
    }

    @Test
    @DisplayName("SALON_OWNER sends invite → 201 with invitedEmail")
    void should_return201_when_salonOwnerSendsInvite() throws Exception {
        String ownerEmail = uniqueEmail("owner");
        String masterEmail = uniqueEmail("master");
        createdEmails.add(ownerEmail);
        createdEmails.add(masterEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: register SALON_OWNER email={}", ownerEmail);

        String ownerToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        String ownerAccessToken = promoteToSalonOwnerWithSalon(ownerEmail, ownerToken, salonId);

        doNothing().when(emailService).sendInviteEmail(anyString(), anyString());

        HttpHeaders headers = bearerHeaders(ownerAccessToken);
        var request = new InviteRequest(masterEmail, salonId);

        log.debug("Act: POST /auth/invite as SALON_OWNER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        log.trace("Assert: status=201, invitedEmail={}", masterEmail);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().invitedEmail()).isEqualTo(masterEmail);
        assertThat(body.data().expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("CLIENT sends invite → 403")
    void should_return403_when_clientSendsInvite() throws Exception {
        String clientEmail = uniqueEmail("client");
        createdEmails.add(clientEmail);
        log.debug("Arrange: CLIENT email={}", clientEmail);

        String clientToken = registerAndGetToken(clientEmail, Role.CLIENT);

        HttpHeaders headers = bearerHeaders(clientToken);
        var request = new InviteRequest(uniqueEmail("target"), UUID.randomUUID());

        log.debug("Act: POST /auth/invite as CLIENT");
        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        log.trace("Assert: status=403");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Unauthenticated request to send invite → 401")
    void should_return401_when_unauthenticatedSendsInvite() {
        log.debug("Arrange: no Authorization header");

        var request = new InviteRequest(uniqueEmail("unauth"), UUID.randomUUID());

        log.debug("Act: POST /auth/invite without credentials");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/invite", request, String.class);

        log.trace("Assert: status=401");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SALON_OWNER invites already-registered email → 409")
    void should_return409_when_invitedEmailAlreadyRegistered() throws Exception {
        String ownerEmail = uniqueEmail("owner2");
        String alreadyRegistered = uniqueEmail("existing");
        createdEmails.add(ownerEmail);
        createdEmails.add(alreadyRegistered);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: register both owner={} and target={}", ownerEmail, alreadyRegistered);

        String ownerToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        String ownerAccessToken = promoteToSalonOwnerWithSalon(ownerEmail, ownerToken, salonId);
        registerAndGetToken(alreadyRegistered, Role.CLIENT);

        doNothing().when(emailService).sendInviteEmail(anyString(), anyString());

        HttpHeaders headers = bearerHeaders(ownerAccessToken);
        var request = new InviteRequest(alreadyRegistered, salonId);

        log.debug("Act: POST /auth/invite targeting registered email");
        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        log.trace("Assert: status=409");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
    }

    @Test
    @DisplayName("Valid token accept → 201, user created with SALON_MASTER role")
    void should_return201AndCreateSalonMaster_when_validTokenAccepted() throws Exception {
        String masterEmail = uniqueEmail("newmaster");
        createdEmails.add(masterEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: insert valid invite token for email={}", masterEmail);

        String rawToken = UUID.randomUUID().toString();
        saveValidInviteToken(masterEmail, salonId, rawToken);

        var request = new InviteAcceptRequest(rawToken, "password123", "Jane", "Doe", null);

        log.debug("Act: POST /auth/invite/accept");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/invite/accept", request, String.class);

        log.trace("Assert: status=201, SALON_MASTER role");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().role()).isEqualTo(Role.SALON_MASTER);
        assertThat(body.data().email()).isEqualTo(masterEmail);
        assertThat(body.data().salonId()).isEqualTo(salonId);

        var persistedUser = userRepository.findByEmail(masterEmail);
        assertThat(persistedUser).isPresent();
        assertThat(persistedUser.get().getRole()).isEqualTo(Role.SALON_MASTER);
        assertThat(persistedUser.get().getSalonId()).isEqualTo(salonId);
    }

    @Test
    @DisplayName("Token not found → 404")
    void should_return404_when_tokenNotFound() throws Exception {
        var request = new InviteAcceptRequest("nonexistent-token-xyz", "password123", null, null, null);
        log.debug("Arrange: no matching token in DB");

        log.debug("Act: POST /auth/invite/accept with unknown token");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/invite/accept", request, String.class);

        log.trace("Assert: status=404");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Expired token → 400")
    void should_return400_when_tokenExpired() throws Exception {
        String masterEmail = uniqueEmail("expiredmaster");
        createdEmails.add(masterEmail);
        log.debug("Arrange: insert expired invite token for email={}", masterEmail);

        String rawToken = UUID.randomUUID().toString();
        saveExpiredInviteToken(masterEmail, rawToken);

        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);

        log.debug("Act: POST /auth/invite/accept with expired token");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/invite/accept", request, String.class);

        log.trace("Assert: status=400");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).containsIgnoringCase("expired");
    }

    @Test
    @DisplayName("Already-used token → 400")
    void should_return400_when_tokenAlreadyUsed() throws Exception {
        String masterEmail = uniqueEmail("usedmaster");
        createdEmails.add(masterEmail);
        log.debug("Arrange: insert used invite token for email={}", masterEmail);

        String rawToken = UUID.randomUUID().toString();
        saveUsedInviteToken(masterEmail, rawToken);

        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);

        log.debug("Act: POST /auth/invite/accept with already-used token");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/invite/accept", request, String.class);

        log.trace("Assert: status=400");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).containsIgnoringCase("already been used");
    }

    @Test
    @DisplayName("/auth/invite/accept is reachable without auth → not 401")
    void should_notReturn401_when_inviteAcceptCalledWithoutAuth() throws Exception {
        var request = new InviteAcceptRequest("some-token", "password123", null, null, null);
        log.debug("Arrange: no Authorization header — endpoint must be public");

        log.debug("Act: POST /auth/invite/accept without credentials");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/invite/accept", request, String.class);

        log.trace("Assert: status is not 401");
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /auth/invite/validate with valid token → 200 with invite details")
    void should_return200WithInviteDetails_when_validTokenValidated() throws Exception {
        String masterEmail = uniqueEmail("validatemaster");
        createdEmails.add(masterEmail);
        UUID salonId = UUID.randomUUID();
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: save valid invite token for email={}", masterEmail);

        saveValidInviteToken(masterEmail, salonId, rawToken);

        log.debug("Act: GET /auth/invite/validate?token={}", rawToken);
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/auth/invite/validate?token=" + rawToken, String.class);

        log.trace("Assert: status=200, invitedEmail={}, role=SALON_MASTER", masterEmail);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InvitePreviewResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().invitedEmail()).isEqualTo(masterEmail);
        assertThat(body.data().role()).isEqualTo(Role.SALON_MASTER);
        assertThat(body.data().expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("GET /auth/invite/validate with unknown token → 400")
    void should_return404_when_validateWithUnknownToken() {
        log.debug("Arrange: no token stored — using random UUID");

        log.debug("Act: GET /auth/invite/validate with unknown token");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/auth/invite/validate?token=" + UUID.randomUUID(), String.class);

        log.trace("Assert: status=400");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /auth/invite/validate with expired token → 400")
    void should_return400_when_validateWithExpiredToken() throws Exception {
        String masterEmail = uniqueEmail("expiredvalidate");
        createdEmails.add(masterEmail);
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: save expired invite token for email={}", masterEmail);

        saveExpiredInviteToken(masterEmail, rawToken);

        log.debug("Act: GET /auth/invite/validate with expired token");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/auth/invite/validate?token=" + rawToken, String.class);

        log.trace("Assert: status=400, message contains 'expired'");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).containsIgnoringCase("expired");
    }

    @Test
    @DisplayName("GET /auth/invite/validate with used token → 400")
    void should_return400_when_validateWithUsedToken() throws Exception {
        String masterEmail = uniqueEmail("usedvalidate");
        createdEmails.add(masterEmail);
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: save used invite token for email={}", masterEmail);

        saveUsedInviteToken(masterEmail, rawToken);

        log.debug("Act: GET /auth/invite/validate with already-used token");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/auth/invite/validate?token=" + rawToken, String.class);

        log.trace("Assert: status=400, message contains 'Invalid or expired'");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).containsIgnoringCase("Invalid or expired");
    }

    @Test
    @DisplayName("GET /auth/invite/validate without Authorization header → not 401")
    void should_notRequireAuth_when_validatingInviteToken() throws Exception {
        String masterEmail = uniqueEmail("noauthvalidate");
        createdEmails.add(masterEmail);
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: valid token, no auth header — endpoint must be public");

        saveValidInviteToken(masterEmail, UUID.randomUUID(), rawToken);

        log.debug("Act: GET /auth/invite/validate without credentials");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/auth/invite/validate?token=" + rawToken, String.class);

        log.trace("Assert: status is not 401");
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@beautica.test";
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String registerAndGetToken(String email, Role ignoredRole) throws Exception {
        var registerResp = restTemplate.postForEntity(
                "/auth/register",
                new RegisterRequest(email, "password123", null, null, null),
                String.class
        );
        var body = objectMapper.readValue(
                registerResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    /**
     * Directly manipulates the user's role in the DB so we can test the SALON_OWNER
     * permission check without a dedicated promote endpoint.
     */
    private String promoteToSalonOwner(String email, String existingToken) throws Exception {
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findByEmail(email).ifPresent(user -> {
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "role", Role.SALON_OWNER);
                    userRepository.save(user);
                })
        );
        var loginResp = restTemplate.postForEntity(
                "/auth/login",
                new LoginRequest(email, "password123"),
                String.class
        );
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private String promoteToSalonOwnerWithSalon(String email, String existingToken, UUID salonId) throws Exception {
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findByEmail(email).ifPresent(user -> {
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "role", Role.SALON_OWNER);
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "salonId", salonId);
                    userRepository.save(user);
                })
        );
        var loginResp = restTemplate.postForEntity(
                "/auth/login",
                new LoginRequest(email, "password123"),
                String.class
        );
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private InviteToken saveValidInviteToken(String email, UUID salonId, String rawToken) {
        var token = new InviteToken(
                sha256Hex(rawToken),
                email,
                salonId,
                Role.SALON_MASTER,
                Instant.now().plusSeconds(3600)
        );
        return inviteTokenRepository.save(token);
    }

    private InviteToken saveExpiredInviteToken(String email, String rawToken) {
        var token = new InviteToken(
                sha256Hex(rawToken),
                email,
                UUID.randomUUID(),
                Role.SALON_MASTER,
                Instant.now().minusSeconds(1)
        );
        return inviteTokenRepository.save(token);
    }

    private InviteToken saveUsedInviteToken(String email, String rawToken) {
        var token = new InviteToken(
                sha256Hex(rawToken),
                email,
                UUID.randomUUID(),
                Role.SALON_MASTER,
                Instant.now().plusSeconds(3600)
        );
        token.markUsed();
        return inviteTokenRepository.save(token);
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
