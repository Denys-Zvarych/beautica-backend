package com.beautica.media;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixture helpers for media integration and security tests.
 *
 * <p>Concrete subclasses declare their own {@code @Autowired} fields, {@code @MockBean}s,
 * and {@code @BeforeEach} setup — this class only provides stateless seeding and
 * request-building helpers so neither concrete class needs to duplicate them.
 *
 * <p>It no longer publishes an HC5 factory constant: {@link AbstractIntegrationTest} installs the
 * timeout-bounded, zero-retry factory on the shared {@code TestRestTemplate} before every test, so
 * a subclass that re-installed its own would only be discarding that policy.
 */
abstract class AbstractMediaIntegrationTest extends AbstractIntegrationTest {

    static final String TEST_PASSWORD = "Str0ngP@ss1!";
    static final byte[] JPEG_HEADER = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    // Declared abstract so subclasses resolve the correct @MockBean-scoped context beans.
    protected abstract TestRestTemplate restTemplate();
    protected abstract ObjectMapper objectMapper();
    protected abstract PasswordEncoder passwordEncoder();

    protected UUID insertClient(String email) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder().encode(TEST_PASSWORD);
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
                id, email, hash);
        return id;
    }

    protected UUID insertSalonOwner(String email) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder().encode(TEST_PASSWORD);
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                id, email, hash);
        return id;
    }

    protected UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, name, testCityId());
        return salonId;
    }

    /** Email-verified account of any role, optionally assigned to {@code salonId} (staff roles). Phase 343. */
    protected UUID insertUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, salon_id) "
                        + "VALUES (?, ?, ?, ?, true, true, ?)",
                id, email, passwordEncoder().encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    protected static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
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

    /** The 5 MB per-file cap ({@code spring.servlet.multipart.max-file-size} = {@code MediaService.MAX_FILE_BYTES}). */
    static final int FIVE_MB = 5 * 1024 * 1024;

    /** A {@code size}-byte JPEG part: real JPEG magic bytes, space-padded. Used to probe the multipart size caps. */
    protected static MultiValueMap<String, Object> jpegMultipartBodyOfSize(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 0x20);
        System.arraycopy(JPEG_HEADER, 0, bytes, 0, JPEG_HEADER.length);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "sized.jpg";
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
