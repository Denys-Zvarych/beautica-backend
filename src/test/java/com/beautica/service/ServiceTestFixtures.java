package com.beautica.service;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
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

    static final String TEST_PASSWORD = "password123";

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
        String body = "{\"name\":\"" + name + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<com.beautica.salon.dto.SalonResponse>>() {});
        return parsed.data().id();
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
        return createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest(name, null, null, 60, new BigDecimal("500.00"), 0, null));
    }

    String createIndependentMasterAndGetToken(String email) throws Exception {
        var request = new RegisterIndependentMasterRequest(email, TEST_PASSWORD, "Anna", "Kovalenko", null);
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
        var request = new CreateServiceDefinitionRequest(name, null, null, 60, new BigDecimal("500.00"), 0, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(indepToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {});
        return parsed.data().serviceDefinition().id();
    }

    HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
