package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end contract for the Phase 16.x / V111 rule: <b>every service-creation path must
 * persist a non-null {@code service_type_id}</b>. This test pins the guarantee across all three
 * layers that enforce it, so a regression in any one of them fails here:
 *
 * <ol>
 *   <li><b>HTTP tier</b> — a create request that omits {@code serviceTypeId} is rejected 400 by
 *       the {@code @NotNull} bean-validation guard, before the service runs.</li>
 *   <li><b>Persistence</b> — a create request WITH a valid type succeeds (201) and the persisted
 *       {@code service_definitions.service_type_id} column carries that id (positive control).</li>
 *   <li><b>Storage constraint (V111)</b> — a raw INSERT of a {@code service_definitions} row with
 *       NULL {@code service_type_id} fails the DB NOT NULL constraint, and the FK is now
 *       {@code ON DELETE RESTRICT} so a referenced {@code service_types} row cannot be deleted.</li>
 * </ol>
 *
 * <p>The service-layer {@code BusinessException("Service type is required")} defense-in-depth is
 * unit-tested in {@code ServiceCatalogServiceTest}; the OpenAPI {@code required[]} contract in
 * {@code ServiceTypeOpenApiContractTest}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Mandatory service_type_id — end-to-end contract (V111)")
class MandatoryServiceTypeContractIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MandatoryServiceTypeContractIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── 1. HTTP tier — omitting serviceTypeId is a 400 ─────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services WITHOUT serviceTypeId → 400 and nothing is persisted")
    void should_reject400AndPersistNothing_when_creatingSalonServiceWithoutServiceType() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "mandatory-type-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Mandatory Type Salon");

        // Body omits serviceTypeId — everything else is valid.
        String body = "{\"name\":\"Untyped Manicure\",\"category\":\"NAIL_SERVICE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"FIXED\",\"price\":500.00}";

        log.debug("Act: POST /api/v1/salons/{}/services with no serviceTypeId — must 400 and persist nothing", salonId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(body, bearerJson(ownerToken)), String.class);

        assertThat(resp.getStatusCode())
                .as("omitting the mandatory serviceTypeId must be a 400, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Long persisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_id = ?", Long.class, salonId);
        assertThat(persisted)
                .as("a rejected untyped create must leave zero service_definitions rows")
                .isZero();
    }

    // ── 2. Persistence positive control — create WITH type persists the FK ─────

    @Test
    @DisplayName("POST /salons/{id}/services WITH serviceTypeId → 201 and service_definitions.service_type_id is persisted")
    void should_persistServiceTypeId_when_creatingSalonServiceWithType() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "typed-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Typed Salon");
        UUID serviceTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");

        var request = new CreateServiceDefinitionRequest(
                "Gel Manicure", null, "NAIL_SERVICE", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, serviceTypeId);

        log.debug("Act: POST /api/v1/salons/{}/services with a resolved NAIL_SERVICE serviceTypeId", salonId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerJson(ownerToken)), String.class);

        assertThat(resp.getStatusCode())
                .as("a typed create must succeed, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<ServiceDefinitionResponse>>() {});
        UUID serviceDefId = parsed.data().id();

        assertThat(parsed.data().serviceTypeId())
                .as("the create response must echo the chosen serviceTypeId")
                .isEqualTo(serviceTypeId);

        UUID persistedTypeId = jdbcTemplate.queryForObject(
                "SELECT service_type_id FROM service_definitions WHERE id = ?", UUID.class, serviceDefId);
        assertThat(persistedTypeId)
                .as("service_definitions.service_type_id must be persisted, not null")
                .isEqualTo(serviceTypeId);
    }

    // ── 3. Storage constraint — NULL service_type_id violates NOT NULL (V111) ──

    @Test
    @DisplayName("Raw INSERT of a service_definitions row with NULL service_type_id fails the V111 NOT NULL constraint")
    void should_failNotNullConstraint_when_insertingServiceDefinitionWithNullServiceType() {
        // A fully-formed row that is valid EXCEPT for a NULL service_type_id. Under V111 the DB
        // rejects it — proving the storage-layer guarantee is live, not just DTO validation.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, category, service_type_id, "
                        + " base_duration_minutes, base_price, price_type, buffer_minutes_after, "
                        + " is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Untyped Row', 'NAIL_SERVICE', NULL, "
                        + " 60, 500.00, 'FIXED', 0, TRUE, NOW(), NOW())",
                UUID.randomUUID(), UUID.randomUUID()))
                .as("V111 sets service_type_id NOT NULL — a NULL insert must be rejected by the DB")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 4. FK RESTRICT — a referenced service_types row cannot be deleted ──────

    @Test
    @DisplayName("Deleting a service_types row still referenced by a service_definition is blocked (ON DELETE RESTRICT)")
    void should_blockDelete_when_serviceTypeStillReferencedByServiceDefinition() {
        UUID serviceTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        UUID serviceDefId = UUID.randomUUID();

        // Persist a service_definition that references the seeded service type.
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, category, service_type_id, "
                        + " base_duration_minutes, base_price, price_type, buffer_minutes_after, "
                        + " is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Referencing Row', 'NAIL_SERVICE', ?, "
                        + " 60, 500.00, 'FIXED', 0, TRUE, NOW(), NOW())",
                serviceDefId, UUID.randomUUID(), serviceTypeId);

        // V111 changed the FK from ON DELETE SET NULL to ON DELETE RESTRICT: while the child row
        // references it, deleting the parent service_types row must fail (never orphan the child).
        log.debug("Act: attempt DELETE of a referenced service_types row — must be blocked by RESTRICT");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM service_types WHERE id = ?", serviceTypeId))
                .as("ON DELETE RESTRICT must block deletion of a referenced service type")
                .isInstanceOf(DataIntegrityViolationException.class);

        // The seeded type is intact; the child is cleaned up by AbstractIntegrationTest.cleanDb().
        Long stillThere = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE id = ?", Long.class, serviceTypeId);
        assertThat(stillThere)
                .as("the referenced service type must remain after the blocked delete")
                .isEqualTo(1L);
    }

    private HttpHeaders bearerJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
