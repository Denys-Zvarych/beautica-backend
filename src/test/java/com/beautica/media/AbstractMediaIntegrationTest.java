package com.beautica.media;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import org.junit.jupiter.api.AfterAll;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixture helpers for media integration and security tests.
 *
 * <p>Concrete subclasses declare their own {@code @Autowired} fields, {@code @MockBean}s,
 * and {@code @BeforeEach} setup — this class only provides stateless seeding and
 * request-building helpers so neither concrete class needs to duplicate them.
 */
abstract class AbstractMediaIntegrationTest extends AbstractIntegrationTest {

    static final String TEST_PASSWORD = "password123";
    static final byte[] JPEG_HEADER = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    /**
     * Shared HC5 factory — allocated once per JVM, never per test instance.
     * Concrete subclasses reference this field in {@code @BeforeEach} to avoid
     * re-allocating a connection pool on every test (§M.4).
     */
    protected static final HttpComponentsClientHttpRequestFactory HC5_FACTORY =
            new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());

    @AfterAll
    static void destroyHttpFactory() throws Exception {
        HC5_FACTORY.destroy();
    }

    // Declared abstract so subclasses resolve the correct @MockBean-scoped context beans.
    protected abstract TestRestTemplate restTemplate();
    protected abstract ObjectMapper objectMapper();
    protected abstract PasswordEncoder passwordEncoder();

    protected UUID insertClient(String email) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder().encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
                id, email, hash);
        return id;
    }

    protected UUID insertSalonOwner(String email) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder().encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                id, email, hash);
        return id;
    }

    protected UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name);
        return salonId;
    }

    protected String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate().postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper().readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    protected static MultiValueMap<String, Object> jpegMultipartBody() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(JPEG_HEADER) {
            @Override
            public String getFilename() {
                return "a.jpg";
            }
        });
        return body;
    }

    protected static HttpHeaders bearerMultipartHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }
}
