package com.beautica.support.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.support.dto.ContactSupportRequest;
import com.beautica.support.dto.ContactSupportResponse;
import com.beautica.support.service.SupportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link SupportController} — the Help / Contact-us
 * multipart endpoint.
 *
 * <p>Follows the established slice-test pattern (see {@code MediaControllerTest},
 * {@code DeviceControllerTest}): pass-through filter overrides via
 * {@link WebMvcTestSupport}, an in-class {@link SecurityFilterChain} mirroring the
 * production {@code anyRequest().authenticated()} rule, and identity injection via
 * {@code SecurityMockMvcRequestPostProcessors.authentication()} (which — unlike
 * {@code @WithMockUser} — populates {@code authentication.getDetails()} with the
 * UUID the controller reads via {@code extractUserId}).
 *
 * <p>The HTTP contract is under test here, NOT the business logic: {@link SupportService}
 * is a {@code @MockBean}. Service-level validation (attachment sniffing, size caps) is
 * covered by {@code SupportServiceTest}.
 */
@WebMvcTest(SupportController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("SupportController — @WebMvcTest slice")
class SupportControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SupportControllerTest.class);

    private static final String CONTACT_URL = "/api/v1/support/contact";

    // ── Security configuration (mirrors production: any request must be authenticated) ──

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupportService supportService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private MockMultipartFile requestPart(String message, String subject) throws Exception {
        ContactSupportRequest req = new ContactSupportRequest(message, subject);
        return new MockMultipartFile(
                "request", "request.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(req));
    }

    private static MockMultipartFile attachmentPart() {
        byte[] png = new byte[20];
        png[0] = (byte) 0x89;
        png[1] = 0x50;
        png[2] = 0x4E;
        png[3] = 0x47;
        return new MockMultipartFile("attachments", "a.png", "image/png", png);
    }

    // ── 202 success ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /support/contact — 202 when authenticated user sends a valid message")
    void should_return202_when_validContactSubmitted() throws Exception {
        var userId = UUID.randomUUID();
        when(supportService.contact(eq(userId), eq("client@beautica.test"), any(), any()))
                .thenReturn(ContactSupportResponse.accepted());

        log.debug("Act: POST {} as authenticated CLIENT with a valid message + one PNG", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("This is a valid help message.", "Need help"))
                        .file(attachmentPart())
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").value("Your message has been sent to support"));
    }

    @Test
    @DisplayName("POST /support/contact — 202 with no attachments part (attachments optional)")
    void should_return202_when_noAttachments() throws Exception {
        var userId = UUID.randomUUID();
        when(supportService.contact(any(), any(), any(), any()))
                .thenReturn(ContactSupportResponse.accepted());

        log.debug("Act: POST {} with only the request part, no attachments", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("A perfectly valid help message.", null))
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }

    // ── identity comes from the security context, NOT the body ───────────────────────

    @Test
    @DisplayName("POST /support/contact — identity is taken from the JWT principal, not the request body")
    void should_useSecurityContextIdentity_when_submitting() throws Exception {
        var contextUserId = UUID.randomUUID();
        var contextEmail = "real-sender@beautica.test";
        when(supportService.contact(any(), any(), any(), any()))
                .thenReturn(ContactSupportResponse.accepted());

        log.debug("Act: POST {} — body carries no identity; controller must pass context id/email", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("Identity must come from the token, not me.", "Spoof attempt"))
                        .with(authenticatedAs(contextUserId, contextEmail, Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isAccepted());

        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        verify(supportService).contact(idCaptor.capture(), emailCaptor.capture(), any(), any());
        assertThat(idCaptor.getValue()).isEqualTo(contextUserId);
        assertThat(emailCaptor.getValue()).isEqualTo(contextEmail);
    }

    // ── 401 unauthenticated ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /support/contact — 401 when no JWT is supplied")
    void should_return401_when_unauthenticated() throws Exception {
        log.debug("Act: POST {} with no authentication — must be rejected before the controller", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("A valid message that should never be processed.", null))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(supportService, never()).contact(any(), any(), any(), any());
    }

    // ── 400 invalid request body ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /support/contact — 400 when message is shorter than the 10-char minimum")
    void should_return400_when_messageTooShort() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST {} with a 5-char message — @Size(min=10) must reject with 400", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("short", null))
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verify(supportService, never()).contact(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /support/contact — 400 when message is blank")
    void should_return400_when_messageBlank() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST {} with a blank message — @NotBlank must reject with 400", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("   ", null))
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verify(supportService, never()).contact(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /support/contact — 400 when subject carries a control character")
    void should_return400_when_subjectHasControlChar() throws Exception {
        var userId = UUID.randomUUID();
        // CRLF injection attempt in subject → @Pattern rejects control chars.
        ContactSupportRequest req = new ContactSupportRequest(
                "A genuinely valid help message here.", "evil\r\nBcc: x@y.z");
        MockMultipartFile part = new MockMultipartFile(
                "request", "request.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(req));

        log.debug("Act: POST {} with CRLF in subject — @Pattern must reject with 400", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(part)
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verify(supportService, never()).contact(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /support/contact — 400 when the required 'request' part is missing")
    void should_return400_when_requestPartMissing() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST {} with only an attachment, no 'request' part — 400 expected", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(attachmentPart())
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verify(supportService, never()).contact(any(), any(), any(), any());
    }

    // ── service-surfaced status mapping ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /support/contact — 503 when the service reports the support inbox unconfigured")
    void should_return503_when_supportChannelUnconfigured() throws Exception {
        var userId = UUID.randomUUID();
        when(supportService.contact(any(), any(), any(), any()))
                .thenThrow(new BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Support channel is not configured"));

        log.debug("Act: POST {} when service throws 503 — GlobalExceptionHandler must map it", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("A valid message reaching the service layer.", null))
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /support/contact — 400 when the service rejects an attachment constraint")
    void should_return400_when_serviceRejectsAttachment() throws Exception {
        var userId = UUID.randomUUID();
        when(supportService.contact(any(), any(), any(), any()))
                .thenThrow(new BusinessException(
                        HttpStatus.BAD_REQUEST, "Unsupported attachment type"));

        log.debug("Act: POST {} when service throws 400 for a bad attachment", CONTACT_URL);
        mockMvc.perform(multipart(CONTACT_URL)
                        .file(requestPart("A valid message with a disallowed attachment.", null))
                        .file(new MockMultipartFile("attachments", "x.bin", "application/octet-stream",
                                "not-an-image".getBytes(StandardCharsets.UTF_8)))
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
