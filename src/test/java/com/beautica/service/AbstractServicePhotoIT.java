package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Shared base of the Phase 342 service-photo integration tests: the mocked {@link R2StorageService}
 * (one Spring context for every subclass), the multipart / DELETE helpers, the DB pointer probes and the
 * recorded-R2-call probes ({@link #uploadedKeys()}, {@link #deletedKeys()}) that let sweep assertions be
 * exact rather than "some batch contained the key".
 */
@Import(TestSecurityConfig.class)
abstract class AbstractServicePhotoIT extends AbstractIntegrationTest {

    protected static final byte[] JPEG =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PasswordEncoder passwordEncoder;

    @MockBean protected R2StorageService r2;

    protected ServiceTestFixtures fixtures;

    @BeforeEach
    void setUpServicePhotoStorage() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbc, objectMapper, passwordEncoder);
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2).deleteFile(anyString());
    }

    /** Lower-cased: {@code AuthService} lower-cases emails, so a mixed-case address breaks login. */
    protected static String email(String prefix, String tag) {
        return (prefix + "-" + tag + "-" + System.nanoTime() + "@beautica.test").toLowerCase(Locale.ROOT);
    }

    protected ResponseEntity<String> upload(UUID serviceDefId, String token, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "p.bin";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/v1/services/" + serviceDefId + "/photo", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> upload(UUID serviceDefId, String token) {
        return upload(serviceDefId, token, JPEG);
    }

    protected ResponseEntity<String> deletePhoto(UUID serviceDefId, String token) {
        return restTemplate.exchange("/api/v1/services/" + serviceDefId + "/photo", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    protected String photoKey(UUID serviceDefId) {
        return jdbc.queryForObject("SELECT photo_r2_key FROM service_definitions WHERE id = ?",
                String.class, serviceDefId);
    }

    protected String photoUrl(UUID serviceDefId) {
        return jdbc.queryForObject("SELECT photo_url FROM service_definitions WHERE id = ?",
                String.class, serviceDefId);
    }

    /** Every key passed to {@code uploadFile} since the last {@code reset/clearInvocations}, in call order. */
    protected List<String> uploadedKeys() {
        List<String> keys = new ArrayList<>();
        for (Invocation inv : mockingDetails(r2).getInvocations()) {
            if (inv.getMethod().getName().equals("uploadFile")) {
                keys.add(inv.getArgument(0));
            }
        }
        return keys;
    }

    /** Every key passed to {@code deleteFile} or {@code deleteFiles} since the last clear, in call order. */
    protected List<String> deletedKeys() {
        List<String> keys = new ArrayList<>();
        for (Invocation inv : mockingDetails(r2).getInvocations()) {
            String name = inv.getMethod().getName();
            if (name.equals("deleteFile")) {
                keys.add(inv.getArgument(0));
            } else if (name.equals("deleteFiles")) {
                keys.addAll(inv.<Collection<String>>getArgument(0));
            }
        }
        return keys;
    }
}
