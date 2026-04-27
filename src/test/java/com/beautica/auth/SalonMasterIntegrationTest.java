package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.service.MasterService;
import com.beautica.notification.EmailService;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestSecurityConfig.class)
@DisplayName("SalonMaster — invite flow + independent master registration integration")
class SalonMasterIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonMasterIntegrationTest.class);

    private static final String TEST_PASSWORD = "SecurePass1!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InviteTokenRepository inviteTokenRepository;

    @SpyBean
    private MasterService masterService;

    @MockBean
    private EmailService emailService;

    /** Tracks all emails created per test so @AfterEach can delete them in FK-safe order. */
    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        log.debug("cleanUp: removing test data for emails={}", createdEmails);
        // Order matters: child tables before parent, salons before users
        // (salons.owner_id → users.id restricts user deletion)
        jdbcTemplate.execute("DELETE FROM working_hours");
        jdbcTemplate.execute("DELETE FROM schedule_exceptions");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        // Nullify users.salon_id to break circular FK before deleting salons
        jdbcTemplate.execute("UPDATE users SET salon_id = NULL WHERE salon_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM salons");
        for (String email : createdEmails) {
            jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
        }
        createdEmails.clear();
        // Reset any stubbing applied in individual tests (spy stays, stubs cleared)
        reset(masterService);
    }

    // ── Test 1: full invite flow ───────────────────────────────────────────────

    @Test
    @DisplayName("full invite flow: owner invites master, master accepts, user+master row created")
    void should_fullInviteFlow_when_ownerInvitesMasterAndMasterAccepts() throws Exception {
        // Arrange: create salon owner and salon
        String ownerEmail = uniqueEmail("owner");
        createdEmails.add(ownerEmail);
        UUID salonId = UUID.randomUUID();
        createSalonOwnerWithSalon(ownerEmail, salonId);
        String ownerToken = loginAndGetToken(ownerEmail);

        String masterEmail = uniqueEmail("newmaster");
        createdEmails.add(masterEmail);

        log.debug("Arrange: owner={} sends invite to master={} for salon={}", ownerEmail, masterEmail, salonId);
        String rawToken = UUID.randomUUID().toString();
        saveValidInviteToken(masterEmail, salonId, rawToken);

        // Act: master accepts the invite
        var request = new InviteAcceptRequest(rawToken, TEST_PASSWORD, "Oksana", "Kovalenko", null);
        log.debug("Act: POST /auth/invite/accept");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        // Assert: HTTP 201 with SALON_MASTER role
        log.trace("Assert: status=201, role=SALON_MASTER");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().role()).isEqualTo(Role.SALON_MASTER);
        assertThat(body.data().email()).isEqualTo(masterEmail);

        // Assert: master row exists linked to the correct salon
        UUID createdUserId = body.data().userId();
        Integer masterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM masters m " +
                "JOIN salons s ON m.salon_id = s.id " +
                "WHERE m.user_id = ? AND s.id = ?",
                Integer.class, createdUserId, salonId);
        assertThat(masterCount).isEqualTo(1);

        // Assert: user has SALON_MASTER role persisted
        String persistedRole = jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, createdUserId);
        assertThat(persistedRole).isEqualTo("SALON_MASTER");
    }

    // ── Test 2: rollback when master creation fails ────────────────────────────

    @Test
    @DisplayName("transaction rolls back: invite token remains unused when master creation fails")
    void should_rollbackTransaction_when_masterCreationFailsAfterInviteConsumed() throws Exception {
        // Arrange: create a real salon so invite_tokens FK is satisfied
        String masterEmail = uniqueEmail("rollback-master");
        createdEmails.add(masterEmail);
        UUID salonId = UUID.randomUUID();
        String ownerEmail = uniqueEmail("rollback-owner");
        createdEmails.add(ownerEmail);
        createSalonOwnerWithSalon(ownerEmail, salonId);

        String rawToken = UUID.randomUUID().toString();
        saveValidInviteToken(masterEmail, salonId, rawToken);

        log.debug("Arrange: stub masterService.createMasterFromInvite to throw RuntimeException");
        doThrow(new RuntimeException("Simulated master creation failure"))
                .when(masterService).createMasterFromInvite(any(UUID.class), any(UUID.class));

        // Act: attempt invite accept — expect 500 due to uncaught RuntimeException
        var request = new InviteAcceptRequest(rawToken, TEST_PASSWORD, "Fail", "Master", null);
        log.debug("Act: POST /auth/invite/accept with failing masterService");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        // Assert: request failed (not 2xx)
        log.trace("Assert: status is not 2xx");
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();

        // Assert: invite token was NOT permanently consumed (transaction rolled back)
        String hashedToken = sha256Hex(rawToken);
        var tokenInDb = inviteTokenRepository.findByToken(hashedToken);
        assertThat(tokenInDb).isPresent();
        assertThat(tokenInDb.get().isUsed())
                .as("invite token must remain unused after transaction rollback")
                .isFalse();

        // Assert: no user row was created for this email
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, masterEmail);
        assertThat(userCount).isZero();
    }

    // ── Test 3: independent master registration creates master row ─────────────

    @Test
    @DisplayName("POST /auth/register/independent-master creates a master row with salon_id = NULL")
    void should_createMasterRecord_when_independentMasterRegisters() throws Exception {
        // Arrange
        String email = uniqueEmail("indep-master");
        createdEmails.add(email);
        var request = new RegisterIndependentMasterRequest(
                email, TEST_PASSWORD, "Taras", "Shevchenko", "+380501234567");

        // Act
        log.debug("Act: POST /auth/register/independent-master email={}", email);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register/independent-master", request, String.class);

        // Assert: registration succeeded
        log.trace("Assert: status=201, INDEPENDENT_MASTER role");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().role()).isEqualTo(Role.INDEPENDENT_MASTER);

        // Assert: master row exists with no salon_id
        UUID userId = body.data().userId();
        Integer masterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM masters WHERE user_id = ? AND salon_id IS NULL",
                Integer.class, userId);
        assertThat(masterCount)
                .as("a masters row with salon_id = NULL must exist for the registered user")
                .isEqualTo(1);

        // Assert: master type is INDEPENDENT_MASTER
        String masterType = jdbcTemplate.queryForObject(
                "SELECT master_type FROM masters WHERE user_id = ?", String.class, userId);
        assertThat(masterType).isEqualTo("INDEPENDENT_MASTER");
    }

    // ── Test 4: working hours set and returned in detail response ──────────────

    @Test
    @DisplayName("GET /api/v1/masters/{id} returns workingHours array after PATCH")
    void should_returnMasterDetail_when_workingHoursSet() throws Exception {
        // Arrange: configure PATCH-capable HTTP client (Apache HC supports PATCH)
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String ownerEmail = uniqueEmail("owner-wh");
        createdEmails.add(ownerEmail);
        UUID salonId = UUID.randomUUID();
        createSalonOwnerWithSalon(ownerEmail, salonId);
        String ownerToken = loginAndGetToken(ownerEmail);

        // Create a salon master in the DB that the owner can manage
        String masterEmail = uniqueEmail("master-wh");
        createdEmails.add(masterEmail);
        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, salon_id) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', true, ?)",
                masterUserId, masterEmail, passwordEncoder.encode(TEST_PASSWORD), salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        log.debug("Arrange: masterId={} in salon={}", masterId, salonId);

        // Act: set working hours
        var workingHours = List.of(
                new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(18, 0), true),
                new WorkingHoursRequest(2, LocalTime.of(9, 0), LocalTime.of(18, 0), true)
        );
        String requestBody = objectMapper.writeValueAsString(workingHours);

        log.debug("Act: PATCH /api/v1/masters/{}/working-hours", masterId);
        ResponseEntity<String> patchResponse = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/working-hours",
                HttpMethod.PATCH,
                new HttpEntity<>(requestBody, bearerHeaders(ownerToken)),
                String.class);

        assertThat(patchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Assert: GET detail returns workingHours array with the saved entries
        log.debug("Act: GET /api/v1/masters/{}", masterId);
        ResponseEntity<String> getResponse = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId, String.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        var detailBody = objectMapper.readValue(
                getResponse.getBody(), new TypeReference<ApiResponse<MasterDetailResponse>>() {});
        assertThat(detailBody.success()).isTrue();

        List<WorkingHoursResponse> returnedHours = detailBody.data().workingHours();
        log.trace("Assert: workingHours has 2 entries");
        assertThat(returnedHours).isNotNull().hasSize(2);
        assertThat(returnedHours)
                .extracting(WorkingHoursResponse::dayOfWeek)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(returnedHours)
                .allMatch(WorkingHoursResponse::isActive);
    }

    // ── Test 5: different owner cannot send invite for another salon ───────────

    @Test
    @DisplayName("POST /auth/invite returns 403 when caller owns a different salon")
    void should_return403_when_differentSalonOwnerSendsInvite() throws Exception {
        // Arrange: owner A owns salonA, owner B owns salonB
        String ownerAEmail = uniqueEmail("owner-a");
        createdEmails.add(ownerAEmail);
        UUID salonAId = UUID.randomUUID();
        createSalonOwnerWithSalon(ownerAEmail, salonAId);

        String ownerBEmail = uniqueEmail("owner-b");
        createdEmails.add(ownerBEmail);
        UUID salonBId = UUID.randomUUID();
        createSalonOwnerWithSalon(ownerBEmail, salonBId);

        String ownerBToken = loginAndGetToken(ownerBEmail);

        String targetEmail = uniqueEmail("target-master");
        // owner B sends an invite for salonA — caller's salonId (salonB) != requested salonId (salonA)
        var requestBody = objectMapper.writeValueAsString(
                new com.beautica.auth.dto.InviteRequest(targetEmail, salonAId));

        log.debug("Act: POST /auth/invite with ownerB token targeting salonA={}", salonAId);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(requestBody, bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        log.trace("Assert: status=403");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Test 6: different owner cannot patch working hours for another salon's master ──

    @Test
    @DisplayName("PATCH /api/v1/masters/{id}/working-hours returns 403 when caller owns a different salon")
    void should_return403_when_differentSalonOwnerPatchesWorkingHours() throws Exception {
        // Arrange: configure PATCH-capable client
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Owner A with salonA and a master linked to salonA
        String ownerAEmail = uniqueEmail("owner-a-wh");
        createdEmails.add(ownerAEmail);
        UUID salonAId = UUID.randomUUID();
        createSalonOwnerWithSalon(ownerAEmail, salonAId);

        String masterEmail = uniqueEmail("master-a-wh");
        createdEmails.add(masterEmail);
        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, salon_id) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', true, ?)",
                masterUserId, masterEmail, passwordEncoder.encode(TEST_PASSWORD), salonAId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonAId);

        // Owner B owns a completely separate salon
        String ownerBEmail = uniqueEmail("owner-b-wh");
        createdEmails.add(ownerBEmail);
        UUID salonBId = UUID.randomUUID();
        createSalonOwnerWithSalon(ownerBEmail, salonBId);
        String ownerBToken = loginAndGetToken(ownerBEmail);

        var workingHours = List.of(
                new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(18, 0), true));
        String requestBody = objectMapper.writeValueAsString(workingHours);

        log.debug("Act: PATCH /api/v1/masters/{}/working-hours with ownerB token", masterId);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/working-hours",
                HttpMethod.PATCH,
                new HttpEntity<>(requestBody, bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        log.trace("Assert: status=403");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Test 7: salon master cannot patch own working hours ───────────────────

    @Test
    @DisplayName("PATCH /api/v1/masters/{id}/working-hours returns 403 when called by a SALON_MASTER")
    void should_return403_when_salonMasterPatchesOwnWorkingHours() throws Exception {
        // Arrange: configure PATCH-capable client
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Create a salon so FK constraints are satisfied
        String ownerEmail = uniqueEmail("owner-sm");
        createdEmails.add(ownerEmail);
        UUID salonId = UUID.randomUUID();
        createSalonOwnerWithSalon(ownerEmail, salonId);

        // Create the SALON_MASTER user and master row
        String masterEmail = uniqueEmail("salon-master-self");
        createdEmails.add(masterEmail);
        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, salon_id) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', true, ?)",
                masterUserId, masterEmail, passwordEncoder.encode(TEST_PASSWORD), salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        String masterToken = loginAndGetToken(masterEmail);

        var workingHours = List.of(
                new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(18, 0), true));
        String requestBody = objectMapper.writeValueAsString(workingHours);

        log.debug("Act: PATCH /api/v1/masters/{}/working-hours with SALON_MASTER token", masterId);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/working-hours",
                HttpMethod.PATCH,
                new HttpEntity<>(requestBody, bearerHeaders(masterToken)),
                String.class);

        // Assert: SALON_MASTER role is rejected immediately by canManageMasterSchedule
        log.trace("Assert: status=403");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@beautica.test";
    }

    private void createSalonOwnerWithSalon(String email, UUID salonId) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID ownerId = UUID.randomUUID();
        // Insert user without salon_id first (FK on salons doesn't exist yet)
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) " +
                "VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, email, hash);
        // Insert salon referencing the owner
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) " +
                "VALUES (?, ?, 'Test Salon', true, NOW(), NOW())",
                salonId, ownerId);
        // Now link user back to salon
        jdbcTemplate.update(
                "UPDATE users SET salon_id = ? WHERE id = ?", salonId, ownerId);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
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

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
