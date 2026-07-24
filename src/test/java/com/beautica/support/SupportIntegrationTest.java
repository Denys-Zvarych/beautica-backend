package com.beautica.support;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.dto.SupportAttachment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * End-to-end integration test for the Help / Contact-us multipart endpoint.
 *
 * <p>Exercises the full stack — HTTP → security filter chain → {@link com.beautica.support.controller.SupportController}
 * → {@link com.beautica.support.service.SupportService} → {@link com.beautica.notification.EmailService}.
 * The real SMTP send is never performed: {@code EmailService} is a {@code @MockBean}
 * supplied by {@link AbstractIntegrationTest}, so this test verifies the wiring and the
 * resolved recipient/attachment payload without standing up a mail server.
 *
 * <p>{@code app.support.email} is configured here via {@code @TestPropertySource} so the
 * unconfigured-inbox 503 guard does not fire.
 */
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "app.support.email=support-inbox@beautica.test")
@DisplayName("Support — Help / Contact-us multipart integration")
class SupportIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SupportIntegrationTest.class);
    private static final String CONTACT_URL = "/api/v1/support/contact";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String SUPPORT_INBOX = "support-inbox@beautica.test";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("POST /support/contact — 202 end-to-end; EmailService invoked with resolved inbox + sanitized attachment")
    void should_acceptAndDispatch_when_authenticatedClientSubmitsMultipart() throws Exception {
        String clientEmail = "support-client-" + System.nanoTime() + "@beautica.test";
        String token = createClientAndGetToken(clientEmail);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("request", jsonPart("Please help me, my booking is stuck.", "Booking stuck"));
        body.add("attachments", filePart("../evil path.png", pngBytes()));

        HttpHeaders headers = bearerMultipartHeaders(token);

        log.debug("Act: POST {} as authenticated CLIENT with one PNG attachment", CONTACT_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                CONTACT_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode())
                .as("authenticated valid multipart submission must return 202, body=%s", response.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);

        var parsed = objectMapper.readValue(response.getBody(),
                new TypeReference<ApiResponse<Object>>() {});
        assertThat(parsed.success()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SupportAttachment>> attachmentsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(emailService).sendSupportContactEmail(
                eq(SUPPORT_INBOX),
                org.mockito.ArgumentMatchers.anyString(),
                eq(clientEmail),
                eq("Booking stuck"),
                eq("Please help me, my booking is stuck."),
                attachmentsCaptor.capture());

        List<SupportAttachment> dispatched = attachmentsCaptor.getValue();
        assertThat(dispatched).hasSize(1);
        // Path traversal stripped, charset reduced, extension forced from sniffed PNG.
        assertThat(dispatched.get(0).filename()).isEqualTo("evil_path.png");
        assertThat(dispatched.get(0).contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("POST /support/contact — 401 end-to-end when no Authorization header is present")
    void should_return401_when_noToken() throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("request", jsonPart("This message should never reach the service.", null));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        log.debug("Act: POST {} with no token — security chain must return 401", CONTACT_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                CONTACT_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode())
                .as("missing authorization must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static byte[] pngBytes() {
        byte[] b = new byte[20];
        b[0] = (byte) 0x89;
        b[1] = 0x50;
        b[2] = 0x4E;
        b[3] = 0x47;
        return b;
    }

    private HttpEntity<byte[]> jsonPart(String message, String subject) throws Exception {
        String json = objectMapper.writeValueAsString(new ContactPayload(message, subject));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), h);
    }

    private HttpEntity<Resource> filePart(String filename, byte[] bytes) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.IMAGE_PNG);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        return new HttpEntity<>(resource, h);
    }

    private HttpHeaders bearerMultipartHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    private String createClientAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                UUID.randomUUID(), email, hash);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var parsed = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<AuthResponse>>() {});
        return parsed.data().accessToken();
    }

    /** Mirrors {@code ContactSupportRequest} for serialisation without depending on its constructor shape. */
    private record ContactPayload(String message, String subject) {
    }
}
