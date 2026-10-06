package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 342 — storage-off contract against the REAL {@code R2StorageService} (the test profile leaves
 * {@code app.cloudflare-r2.enabled} false): the service photo upload answers 503 and the row is untouched.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Service photo — storage disabled (real R2StorageService), Phase 342")
class ServicePhotoStorageDisabledIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbc, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("POST /services/{id}/photo — 503 with the allow-listed message and the row unchanged")
    void should_return503AndLeaveRowUntouched_when_storageOff() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("sphoto-off-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Photo Off Salon " + System.nanoTime());
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Педикюр");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0}) {
            @Override
            public String getFilename() {
                return "a.jpg";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/services/" + serviceDefId + "/photo",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).contains("Media storage is not configured");
        assertThat(jdbc.queryForObject("SELECT photo_r2_key FROM service_definitions WHERE id = ?",
                String.class, serviceDefId)).isNull();
        assertThat(jdbc.queryForObject("SELECT photo_url FROM service_definitions WHERE id = ?",
                String.class, serviceDefId)).isNull();
    }
}
