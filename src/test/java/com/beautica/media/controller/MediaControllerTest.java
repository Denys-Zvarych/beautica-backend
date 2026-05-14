package com.beautica.media.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.service.MediaService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
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
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link MediaController}.
 *
 * <p>Follows the established slice-test pattern (see
 * {@code com.beautica.salon.SalonControllerTest}): pass-through filter overrides via
 * {@link WebMvcTestSupport}, in-class {@link SecurityFilterChain} mirroring production
 * permit-all rules, and {@code SecurityMockMvcRequestPostProcessors.authentication()}
 * for principal injection. {@code @WithMockUser} is intentionally avoided — it never
 * populates {@link UsernamePasswordAuthenticationToken#getDetails()} with the UUID
 * the controller reads via {@code extractUserId}.
 *
 * <p>All 10 cases here are happy-path / error-path slice assertions; cross-user IDOR
 * scenarios live in {@code com.beautica.media.MediaSecurityTest}.
 */
@WebMvcTest(MediaController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("MediaController — @WebMvcTest slice")
class MediaControllerTest {

    private static final Logger log = LoggerFactory.getLogger(MediaControllerTest.class);

    private static final String AVATAR_URL = "/api/v1/media/avatar";
    private static final String PORTFOLIO_URL = "/api/v1/media/portfolio";
    private static final byte[] JPEG_HEADER = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    // ── Security configuration ────────────────────────────────────────────────

    /**
     * Mirrors the production {@code SecurityConfig} rules for the media endpoints:
     * the two GET portfolio listings are public, every other endpoint requires auth.
     * Method security ({@code @PreAuthorize}) is enabled so the role-based controller
     * annotations are exercised.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurity {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                                                    JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/portfolio").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}/portfolio").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MediaService mediaService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a {@link UsernamePasswordAuthenticationToken} carrying the user's
     * UUID in {@code getDetails()} — matching the contract the controller's
     * {@code extractUserId} method expects (Anti-Bug Playbook § B).
     */
    private static RequestPostProcessor authenticatedAs(UUID userId, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private static MockMultipartFile validJpegPart() {
        return new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG_HEADER);
    }

    // ── POST /api/v1/media/avatar ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /media/avatar — 200 when a valid JPEG is uploaded by an authenticated user")
    void should_return200_when_validAvatarUploaded() throws Exception {
        var userId = UUID.randomUUID();
        when(mediaService.uploadAvatar(eq(userId), any()))
                .thenReturn(new AvatarResponse("https://cdn.example/avatar.jpg"));

        log.debug("Act: POST {} as CLIENT user={} with valid JPEG", AVATAR_URL, userId);
        mockMvc.perform(multipart(AVATAR_URL)
                        .file(validJpegPart())
                        .with(authenticatedAs(userId, Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://cdn.example/avatar.jpg"));
    }

    @Test
    @DisplayName("POST /media/avatar — 401 when no Authorization header is supplied")
    void should_return401_when_noTokenOnAvatarUpload() throws Exception {
        log.debug("Act: POST {} without authentication — unauthenticated upload must be rejected", AVATAR_URL);
        mockMvc.perform(multipart(AVATAR_URL)
                        .file(validJpegPart())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(mediaService, never()).uploadAvatar(any(), any());
    }

    @Test
    @DisplayName("POST /media/avatar — 400 when the multipart file part is missing")
    void should_return400_when_fileMissingFromRequest() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST {} without a 'file' part — Spring must surface 400 via MissingServletRequestPartException", AVATAR_URL);
        mockMvc.perform(multipart(AVATAR_URL)
                        .with(authenticatedAs(userId, Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verify(mediaService, never()).uploadAvatar(any(), any());
    }

    // ── POST /api/v1/media/portfolio ──────────────────────────────────────────

    @Test
    @DisplayName("POST /media/portfolio — 403 when a CLIENT attempts a portfolio upload")
    void should_return403_when_clientUploadsPortfolio() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST {} as CLIENT — role-only @PreAuthorize must deny with 403", PORTFOLIO_URL);
        mockMvc.perform(multipart(PORTFOLIO_URL)
                        .file(validJpegPart())
                        .with(authenticatedAs(userId, Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(mediaService, never()).uploadPortfolioPhoto(any(), any(), any());
    }

    @Test
    @DisplayName("POST /media/portfolio — 201 when SALON_OWNER uploads a valid portfolio photo")
    void should_return201_when_salonOwnerUploadsPortfolio() throws Exception {
        var userId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var stub = new MediaFileResponse(
                mediaId, EntityType.SALON, entityId,
                com.beautica.media.entity.MediaType.PORTFOLIO,
                "https://cdn.example/portfolio.jpg", Instant.now());
        when(mediaService.uploadPortfolioPhoto(eq(userId), eq(Role.SALON_OWNER), any()))
                .thenReturn(stub);

        log.debug("Act: POST {} as SALON_OWNER user={} with valid JPEG", PORTFOLIO_URL, userId);
        mockMvc.perform(multipart(PORTFOLIO_URL)
                        .file(validJpegPart())
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(mediaId.toString()));
    }

    // ── DELETE /api/v1/media/avatar ───────────────────────────────────────────

    @Test
    @DisplayName("DELETE /media/avatar — 204 when authenticated user deletes their own avatar")
    void should_return204_when_avatarDeleted() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: DELETE {} as user={}", AVATAR_URL, userId);
        mockMvc.perform(delete(AVATAR_URL)
                        .with(authenticatedAs(userId, Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(mediaService).deleteAvatar(eq(userId));
    }

    // ── DELETE /api/v1/media/portfolio/{mediaId} ─────────────────────────────

    @Test
    @DisplayName("DELETE /media/portfolio/{id} — 204 when the uploader deletes their own portfolio photo")
    void should_return204_when_portfolioPhotoDeleted() throws Exception {
        var userId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();

        log.debug("Act: DELETE {}/{} as uploader user={}", PORTFOLIO_URL, mediaId, userId);
        mockMvc.perform(delete(PORTFOLIO_URL + "/" + mediaId)
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(mediaService).deletePortfolioPhoto(eq(userId), eq(mediaId));
    }

    @Test
    @DisplayName("DELETE /media/portfolio/{id} — 403 when a different user attempts the delete")
    void should_return403_when_nonUploaderDeletesPortfolioPhoto() throws Exception {
        var attackerId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        doThrow(new ForbiddenException("Not allowed to delete this media"))
                .when(mediaService).deletePortfolioPhoto(eq(attackerId), eq(mediaId));

        log.debug("Act: DELETE {}/{} as non-uploader user={} — service must throw ForbiddenException → 403",
                PORTFOLIO_URL, mediaId, attackerId);
        mockMvc.perform(delete(PORTFOLIO_URL + "/" + mediaId)
                        .with(authenticatedAs(attackerId, Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/v1/salons/{salonId}/portfolio — public ──────────────────────

    @Test
    @DisplayName("GET /salons/{id}/portfolio — 200 without authentication (public endpoint, paginated)")
    void should_return200_when_publicGetSalonPortfolio() throws Exception {
        var salonId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var stub = new MediaFileResponse(
                mediaId, EntityType.SALON, salonId,
                com.beautica.media.entity.MediaType.PORTFOLIO,
                "https://cdn.example/p.jpg", Instant.now());
        Page<MediaFileResponse> page = new PageImpl<>(List.of(stub), PageRequest.of(0, 20), 1);
        when(mediaService.getPortfolio(eq(EntityType.SALON), eq(salonId), any()))
                .thenReturn(page);

        log.debug("Act: GET /api/v1/salons/{}/portfolio without authentication", salonId);
        mockMvc.perform(get("/api/v1/salons/" + salonId + "/portfolio")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // ── GET /api/v1/masters/{masterId}/portfolio — public ────────────────────

    @Test
    @DisplayName("GET /masters/{id}/portfolio — 200 without authentication (public endpoint, paginated)")
    void should_return200_when_publicGetMasterPortfolio() throws Exception {
        var masterId = UUID.randomUUID();
        when(mediaService.getPortfolio(eq(EntityType.MASTER), eq(masterId), any()))
                .thenReturn(Page.empty());

        log.debug("Act: GET /api/v1/masters/{}/portfolio without authentication", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/portfolio")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("GET /salons/{id}/portfolio — size=101 is capped to 100 by spring.data.web.pageable.max-page-size")
    void should_capPageSize_when_callerRequestsMoreThanMaxPageSize() throws Exception {
        var salonId = UUID.randomUUID();
        // Service stub accepts any Pageable — the assertion is on the Pageable argument
        // captured by Mockito, which proves the framework capped the caller-supplied size.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(mediaService.getPortfolio(eq(EntityType.SALON), eq(salonId), pageableCaptor.capture()))
                .thenReturn(Page.empty());

        log.debug("Act: GET /api/v1/salons/{}/portfolio?size=101 — framework must cap to 100", salonId);
        mockMvc.perform(get("/api/v1/salons/" + salonId + "/portfolio")
                        .param("size", "101")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Assert — Spring Data Web capped the effective page size at the max-page-size=100 ceiling
        // configured in application.yml. If the property were absent or misconfigured in the test
        // profile the captor would see size=101 and this assertion fails, exposing the regression.
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageSize())
                .as("max-page-size=100 from application.yml must cap caller-supplied size=101 to 100")
                .isLessThanOrEqualTo(100);
    }

    // ── INDEPENDENT_MASTER role coverage ──────────────────────────────────────

    @Test
    @DisplayName("POST /media/portfolio — 201 when INDEPENDENT_MASTER uploads portfolio")
    void should_return201_when_independentMasterUploadsPortfolio() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-11T10:00:00Z");

        when(mediaService.uploadPortfolioPhoto(eq(actorId), eq(Role.INDEPENDENT_MASTER), any(MultipartFile.class)))
                .thenReturn(new MediaFileResponse(
                        mediaId, EntityType.MASTER, entityId,
                        com.beautica.media.entity.MediaType.PORTFOLIO,
                        "https://cdn.example/portfolio.jpg", now));

        log.debug("Act: POST {} as INDEPENDENT_MASTER user={} with valid JPEG", PORTFOLIO_URL, actorId);
        mockMvc.perform(multipart(PORTFOLIO_URL)
                        .file(validJpegPart())
                        .with(authenticatedAs(actorId, Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(mediaId.toString()));
    }

    // ── extractRole / extractUserId fall-through (direct controller invocation) ─
    //
    // The @PreAuthorize role check normally runs BEFORE the controller body, so
    // the failure paths inside extractRole / extractUserId are unreachable via
    // MockMvc. Constructing the controller directly exercises the defensive
    // ForbiddenException branches (Anti-Bug Playbook § B).

    @Test
    @DisplayName("uploadPortfolioPhoto — throws ForbiddenException when principal has no ROLE_* authority")
    void should_return403_when_principalHasNoRoleAuthority() {
        var controller = new MediaController(mock(MediaService.class));
        var auth = new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority("OTHER_AUTHORITY")));
        auth.setDetails(UUID.randomUUID());

        assertThatThrownBy(() -> controller.uploadPortfolioPhoto(
                new MockMultipartFile("file", JPEG_HEADER), auth))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("role");
    }

    @Test
    @DisplayName("uploadPortfolioPhoto — throws ForbiddenException when authentication has no UUID details")
    void should_return403_when_authenticationHasNoUuidDetails() {
        var controller = new MediaController(mock(MediaService.class));
        var auth = new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority("ROLE_SALON_OWNER")));
        // details intentionally not set — getDetails() returns null and the
        // instanceof UUID guard in extractUserId must fail closed.

        assertThatThrownBy(() -> controller.uploadPortfolioPhoto(
                new MockMultipartFile("file", JPEG_HEADER), auth))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("authenticated");
    }
}
