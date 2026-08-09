package com.beautica.service;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTestFixtures {

    static final String TEST_PASSWORD = "Str0ngP@ss1!";

    private final TestRestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;

    ServiceTestFixtures(
            TestRestTemplate restTemplate,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
    }

    String createSalonOwnerAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                UUID.randomUUID(), email, hash);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    UUID createSalon(String ownerToken, String name) throws Exception {
        // Vinnytsia has no urban districts in the official KATOTTH classifier, so
        // no districtId is required — only cityId is mandatory for provider locality.
        UUID cityId = jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);
        // street + buildingNo are now @NotBlank on CreateSalonRequest (Phase 10.6
        // reversal); include a valid pair so this shared HTTP-boundary fixture clears
        // @Valid and returns 201 for every downstream integration test that relies on it.
        String body = "{\"name\":\"" + name + "\",\"cityId\":\"" + cityId
                + "\",\"street\":\"вул. Хрещатик\",\"buildingNo\":\"1\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<com.beautica.salon.dto.SalonResponse>>() {});
        return parsed.data().id();
    }

    /**
     * Inserts a salon (with a fresh SALON_OWNER user) directly via JDBC and returns its id.
     * Used when a test needs a second, foreign salon + master but does NOT need to act as that
     * owner — avoiding an extra {@code /auth/login} round-trip (and its per-IP bucket cost).
     */
    UUID insertSalonWithOwner(String name) {
        UUID cityId = jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);
        UUID ownerUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerUserId, "jdbc-owner-" + UUID.randomUUID() + "@beautica.test",
                passwordEncoder.encode(TEST_PASSWORD));
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerUserId, name, cityId);
        return salonId;
    }

    UUID createSalonMaster(UUID salonId) {
        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + UUID.randomUUID() + "@beautica.test";
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                masterUserId, masterEmail, hash, salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return masterId;
    }

    UUID createServiceDefinition(String ownerToken, UUID salonId, CreateServiceDefinitionRequest request)
            throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<ServiceDefinitionResponse>>() {});
        return parsed.data().id();
    }

    UUID createServiceDefinition(String ownerToken, UUID salonId, String name) throws Exception {
        UUID serviceTypeId = resolveServiceTypeIdForCategory("NAIL_SERVICE");
        return createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest(name, null, "NAIL_SERVICE", 60, 0,
                        PriceType.FIXED, new BigDecimal("500.00"), null, null, serviceTypeId));
    }

    /**
     * Resolves a real, selectable {@code service_types.id} whose {@code platform_category_name}
     * equals {@code category} and whose parent platform category is APPROVED + active. Since
     * Phase 16.x / V111, {@code service_type_id} is MANDATORY on every create path and must
     * belong to the request's category (Phase 16.3 cross-field guard). Fixtures resolve the
     * FK here so the create request satisfies both the {@code @NotNull} DTO constraint and the
     * category-consistency check with a genuinely seeded type — never a fabricated UUID.
     */
    UUID resolveServiceTypeIdForCategory(String category) {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.platform_category_name = ? AND st.is_active = TRUE "
                        + "AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class, category);
    }

    String createIndependentMasterAndGetToken(String email) throws Exception {
        var request = new RegisterIndependentMasterRequest(email, TEST_PASSWORD, "Anna", "Kovalenko", "+380501234567");
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/register/independent-master", request, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Phase 1.7: registration no longer issues tokens; mark email verified then login.
        jdbcTemplate.update("UPDATE users SET email_verified = true WHERE email = ?", email);
        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    UUID createIndependentMasterService(String indepToken, String name) throws Exception {
        UUID serviceTypeId = resolveServiceTypeIdForCategory("NAIL_SERVICE");
        var request = new CreateServiceDefinitionRequest(name, null, "NAIL_SERVICE", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, serviceTypeId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(indepToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {});
        return parsed.data().serviceDefinition().id();
    }

    /**
     * Creates a SALON_ADMIN user assigned to {@code salonId} and returns a bearer token.
     * canManageSalon resolves the admin's authority via users.salon_id, so the assignment
     * must be persisted for the on-behalf bulk endpoint to authorize the admin.
     */
    String createSalonAdminAndGetToken(UUID salonId, String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_ADMIN', ?, true, true)",
                UUID.randomUUID(), email, hash, salonId);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    /** Resolves the master row id created when an independent master registers (1:1 with the user). */
    UUID resolveMasterIdForUserEmail(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT m.id FROM masters m JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                UUID.class, email);
    }

    /**
     * Returns up to {@code limit} active, seeded service types (id + nameUk + platform
     * category name) whose category is an APPROVED+active platform category — i.e. fully
     * selectable for the bulk-create flow. Distinct categories preferred is not required;
     * the test only needs valid, resolvable ids.
     */
    java.util.List<SeededServiceType> activeSelectableServiceTypes(int limit) {
        return jdbcTemplate.query(
                "SELECT st.id, st.name_uk, st.platform_category_name "
                        + "FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT ?",
                (rs, n) -> new SeededServiceType(
                        rs.getObject("id", UUID.class),
                        rs.getString("name_uk"),
                        rs.getString("platform_category_name")),
                limit);
    }

    long countServiceDefinitionsForMaster(UUID masterId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_id = ?", Long.class, masterId);
        return count == null ? 0L : count;
    }

    /**
     * Reads the denormalised {@code masters.min_effective_price} (V58) straight from the DB —
     * never through an API projection, so the assertion pins the persisted column that the
     * search/browse ordering actually reads, not a value recomputed on the way out.
     *
     * @return {@code null} when the master has no active service (the column's "no bookable
     *         price" encoding)
     */
    BigDecimal minEffectivePriceForMaster(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT min_effective_price FROM masters WHERE id = ?", BigDecimal.class, masterId);
    }

    record SeededServiceType(UUID id, String nameUk, String platformCategoryName) {
    }

    HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
