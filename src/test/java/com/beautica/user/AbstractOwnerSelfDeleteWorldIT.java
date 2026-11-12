package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.InvitePersistenceService;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.beautica.salon.repository.StaffClientReferenceAuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

/**
 * Phase 488 — shared world for the owner self-delete residual-access ITs: an owner who built a salon through
 * the API (so the owner-as-master row exists exactly as production creates it), served a registered client,
 * collected an in-app notification, a salon-scoped client block and a queued staff invite, deleted the salon,
 * then deleted the account. Both subclasses share ONE Spring context (same bean overrides declared here).
 */
@Import(TestSecurityConfig.class)
abstract class AbstractOwnerSelfDeleteWorldIT extends AbstractIntegrationTest {

    protected static final String PASSWORD = "Str0ngP@ss1!";
    protected static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(3);
    protected static final String SALON_NAME = "Residual Salon";

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected PasswordEncoder passwordEncoder;

    @MockBean protected R2StorageService r2;
    /** Spied so a test can prove the staff-as-client audit issued NO statement for an owner delete. */
    @SpyBean protected StaffClientReferenceAuditRepository staffClientReferenceAuditRepository;
    /** Spied so a test can force a rollback after the R2 sweep was registered. */
    @SpyBean protected AccountBlobPurgeRegistrar accountBlobPurgeRegistrar;
    /** Spied (declared here so every subclass keeps ONE shared context) — the only way to observe a RAW invite token. */
    @SpyBean protected InvitePersistenceService invitePersistenceService;

    protected BookingTestFixtures fixtures;
    protected ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUpWorld() {
        reset(r2, staffClientReferenceAuditRepository, accountBlobPurgeRegistrar, invitePersistenceService);
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    /** Every id the deleted account ever touched — what a fresh account must never reach. */
    protected record OldWorld(String email, UUID ownerId, UUID salonId, UUID ownerMasterId, UUID bookingId,
                              UUID clientId, String clientEmail, UUID blockId, UUID inviteId, String oldAccess) {}

    /** Builds the world up to (not including) {@code DELETE /users/me}. */
    protected OldWorld buildDeletedSalonWorld() throws Exception {
        String email = "owner-res-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        String access = login(email);
        UUID salonId = createSalon(access, SALON_NAME);
        jdbcTemplate.update("UPDATE salons SET phone = '+380501112233', instagram_url = 'residual_salon', "
                + "description = 'Owner-written bio', location_note = 'Вхід з двору' WHERE id = ?", salonId);
        UUID ownerMasterId = jdbcTemplate.queryForObject(
                "SELECT id FROM masters WHERE user_id = ?", UUID.class, ownerId);
        UUID masterServiceId = insertMasterService(ownerMasterId, salonId);
        UUID clientId = csd.createClient();
        String clientEmail = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class,
                clientId);
        UUID bookingId = csd.insertBooking(clientId, ownerMasterId, masterServiceId, salonId, "COMPLETED", PAST);
        insertOwnerNotification(ownerId, bookingId, salonId);
        UUID blockId = insertSalonBlock(salonId, clientId, ownerId);
        UUID inviteId = inviteViaApi(access, salonId);
        deleteSalon(access, salonId);
        return new OldWorld(email, ownerId, salonId, ownerMasterId, bookingId, clientId, clientEmail, blockId,
                inviteId, access);
    }

    protected void deleteAccount(OldWorld world) {
        ResponseEntity<String> response = deleteMe(world.oldAccess());
        assertThat(response.getStatusCode()).as("DELETE /users/me — body: %s", response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Re-registers the deleted account's email and returns the fresh account's access token. */
    protected String reRegister(String email, SelfRegistrationRole role) throws Exception {
        RegisterRequest again = new RegisterRequest(email, PASSWORD, role, "Нова", "Людина", randomPhone(),
                role == SelfRegistrationRole.SALON_OWNER ? "Новий бізнес" : null);
        ResponseEntity<String> register = restTemplate.postForEntity("/api/v1/auth/register", again, String.class);
        assertThat(register.getStatusCode()).as("re-register — body: %s", register.getBody())
                .isEqualTo(HttpStatus.OK);
        // Test-only: skip the e-mailed OTP — the verification flow is not under test here.
        jdbcTemplate.update("UPDATE users SET email_verified = true WHERE email = ?", email);
        return login(email);
    }

    /** {@code DELETE /users/me} with the given token — the raw response, no status assumed. */
    protected ResponseEntity<String> deleteMe(String token) {
        return exchange(HttpMethod.DELETE, "/api/v1/users/me", token, null);
    }

    protected record Tokens(String access, String refresh) {}

    protected String login(String email) throws Exception {
        return loginTokens(email).access();
    }

    protected Tokens loginTokens(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login %s — body: %s", email, resp.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        return new Tokens(data.path("accessToken").asText(), data.path("refreshToken").asText());
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object body) {
        try {
            String json = body == null ? null : objectMapper.writeValueAsString(body);
            return restTemplate.exchange(path, method, new HttpEntity<>(json, fixtures.bearerHeaders(token)),
                    String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected UUID createSalon(String token, String name) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/salons", token, Map.of(
                "name", name, "cityId", testCityId(), "street", "Shevchenka St", "buildingNo", "7"));
        assertThat(response.getStatusCode()).as("POST /salons — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response).path("data").path("id").asText());
    }

    protected void deleteSalon(String token, UUID salonId) {
        ResponseEntity<String> response = exchange(HttpMethod.DELETE, "/api/v1/salons/" + salonId, token, null);
        assertThat(response.getStatusCode()).as("DELETE /salons — body: %s", response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** A real invite through the API, so the INVITE outbox row is written exactly as production writes it. */
    protected UUID inviteViaApi(String token, UUID salonId) {
        String invitee = "invitee-" + System.nanoTime() + "@beautica.test";
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/salons/" + salonId + "/invite", token,
                Map.of("email", invitee, "salonId", salonId));
        assertThat(response.getStatusCode()).as("POST /invite — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return jdbcTemplate.queryForObject("SELECT id FROM invite_tokens WHERE salon_id = ? AND email = ?",
                UUID.class, salonId, invitee);
    }

    protected UUID insertMasterService(UUID masterId, UUID salonId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Owner Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, csd.resolveUnusedServiceTypeId("SALON", salonId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private void insertOwnerNotification(UUID ownerId, UUID bookingId, UUID salonId) {
        jdbcTemplate.update(
                "INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, salon_id, dedup_key) "
                        + "VALUES (?, ?, 'BOOKING_CREATED', ?, ?, ?)",
                UUID.randomUUID(), ownerId, bookingId, salonId, "BOOKING_CREATED:" + bookingId);
    }

    private UUID insertSalonBlock(UUID salonId, UUID clientId, UUID ownerId) {
        UUID blockId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO client_blocks (id, blocker_salon_id, blocked_user_id, display_name, created_by_user_id) "
                        + "VALUES (?, ?, ?, 'Оксана І.', ?)",
                blockId, salonId, clientId, ownerId);
        return blockId;
    }

    private static String randomPhone() {
        return "+38067" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
    }
}
