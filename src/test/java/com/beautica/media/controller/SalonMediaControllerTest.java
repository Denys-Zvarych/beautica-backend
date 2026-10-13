package com.beautica.media.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.PortfolioBusyException;
import com.beautica.common.exception.PortfolioLimitReachedException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.media.service.MediaService;
import com.beautica.salon.entity.SalonImageSlot;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Phase 362 — the portfolio routes ({@code /salons/{salonId}/portfolio}) admit owner OR bound admin through
 * {@code hasAnyRole('SALON_OWNER','SALON_ADMIN') and canManageSalon}; tests below pin both halves of that gate.
 *
 * <p>Phase 343 — controller-layer half of the logo/cover OWNER-only gate, isolated from the service layer ({@link MediaService}
 * is a mock, so nothing behind the controller can refuse). Pins that the gate is {@code hasRole('SALON_OWNER')}
 * AND {@code isOwnerOf}: a {@code SALON_ADMIN} for whom {@code canManageSalon} would be TRUE is still refused, so a
 * copy-paste of the PATCH gate ({@code canManageSalon}) onto these routes goes red here even though the
 * service-layer twin would still save the integration test.
 */
@WebMvcTest(SalonMediaController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("SalonMediaController — logo/cover OWNER-only + portfolio manager gates (@WebMvcTest slice)")
class SalonMediaControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurity {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, exc) ->
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    private static final byte[] JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    @Autowired private MockMvc mockMvc;
    @MockBean private MediaService mediaService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean(name = "authz") private AuthorizationService authz;

    private final UUID salonId = UUID.randomUUID();

    @ParameterizedTest(name = "owner → 200/204 on {0}")
    @EnumSource(SalonImageSlot.class)
    void should_allowOwner_when_ownerOfSalon(SalonImageSlot slot) throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(true);

        mockMvc.perform(multipart(url(slot)).file(jpeg()).with(as(ownerId, Role.SALON_OWNER)))
                .andExpect(status().isOk());
        mockMvc.perform(delete(url(slot)).with(as(ownerId, Role.SALON_OWNER)))
                .andExpect(status().isNoContent());

        verify(mediaService).uploadSalonImage(eq(ownerId), eq(salonId), eq(slot), any());
        verify(mediaService).deleteSalonImage(ownerId, salonId, slot);
    }

    @ParameterizedTest(name = "TC-4 controller layer: SALON_ADMIN with management access → 403 on {0}")
    @EnumSource(SalonImageSlot.class)
    void should_return403_when_adminHasManagementAccess(SalonImageSlot slot) throws Exception {
        UUID adminId = UUID.randomUUID();
        lenient().when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(true);
        lenient().when(authz.hasManagementAccess(eq(salonId), any())).thenReturn(true);
        lenient().when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(false);

        mockMvc.perform(multipart(url(slot)).file(jpeg()).with(as(adminId, Role.SALON_ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(url(slot)).with(as(adminId, Role.SALON_ADMIN)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("role gate: a non-SALON_OWNER role is refused even if the ownership predicate were true")
    void should_return403_when_roleIsNotSalonOwner() throws Exception {
        lenient().when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(true);

        for (Role role : List.of(Role.SALON_ADMIN, Role.SALON_MASTER, Role.INDEPENDENT_MASTER, Role.CLIENT)) {
            mockMvc.perform(multipart(url(SalonImageSlot.LOGO)).file(jpeg()).with(as(UUID.randomUUID(), role)))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("an owner of a DIFFERENT salon (isOwnerOf false) → 403")
    void should_return403_when_ownerOfAnotherSalon() throws Exception {
        when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(false);

        mockMvc.perform(delete(url(SalonImageSlot.COVER)).with(as(UUID.randomUUID(), Role.SALON_OWNER)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("unknown slot → 400 (lowercase binding; enum constants never echoed)")
    void should_return400_when_slotUnknown() throws Exception {
        lenient().when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(true);

        mockMvc.perform(delete("/api/v1/salons/" + salonId + "/media/banner")
                        .with(as(UUID.randomUUID(), Role.SALON_OWNER)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("upper-case slot /media/LOGO → 400 (exact match, no case folding — one slot, one URL)")
    void should_return400_when_slotIsUppercase() throws Exception {
        lenient().when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(true);

        mockMvc.perform(multipart("/api/v1/salons/" + salonId + "/media/LOGO").file(jpeg())
                        .with(as(UUID.randomUUID(), Role.SALON_OWNER)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("space-padded slot /media/%20logo → 400 (exact match, no trimming)")
    void should_return400_when_slotHasLeadingEncodedSpace() throws Exception {
        lenient().when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(true);

        mockMvc.perform(delete(java.net.URI.create("/api/v1/salons/" + salonId + "/media/%20logo"))
                        .with(as(UUID.randomUUID(), Role.SALON_OWNER)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("anonymous → 401")
    void should_return401_when_anonymous() throws Exception {
        mockMvc.perform(delete(url(SalonImageSlot.LOGO))).andExpect(status().isUnauthorized());

        verifyNoInteractions(mediaService);
    }

    // ── portfolio (Phase 362): owner OR bound admin via canManageSalon ─────────────────────────

    @ParameterizedTest(name = "portfolio: {0} with canManageSalon → 201 on POST, 204 on DELETE")
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    void should_allowPortfolioWrites_when_salonManager(Role role) throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(true);

        mockMvc.perform(multipart(portfolioUrl()).file(jpeg()).with(as(actorId, role)))
                .andExpect(status().isCreated());
        mockMvc.perform(delete(portfolioUrl() + "/" + mediaId).with(as(actorId, role)))
                .andExpect(status().isNoContent());

        verify(mediaService).uploadSalonPortfolioPhoto(eq(actorId), eq(salonId), any());
        verify(mediaService).deleteSalonPortfolioPhoto(actorId, salonId, mediaId);
    }

    @ParameterizedTest(name = "portfolio: {0} of ANOTHER salon (canManageSalon false) → 403")
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    void should_return403OnPortfolio_when_managerOfAnotherSalon(Role role) throws Exception {
        when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(false);

        mockMvc.perform(multipart(portfolioUrl()).file(jpeg()).with(as(UUID.randomUUID(), role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(portfolioUrl() + "/" + UUID.randomUUID()).with(as(UUID.randomUUID(), role)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(mediaService);
    }

    @ParameterizedTest(name = "portfolio role gate: {0} refused even if canManageSalon were true")
    @EnumSource(value = Role.class, names = {"SALON_MASTER", "INDEPENDENT_MASTER", "CLIENT"})
    void should_return403OnPortfolio_when_roleIsNotSalonManager(Role role) throws Exception {
        lenient().when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(true);

        mockMvc.perform(multipart(portfolioUrl()).file(jpeg()).with(as(UUID.randomUUID(), role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(portfolioUrl() + "/" + UUID.randomUUID()).with(as(UUID.randomUUID(), role)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("portfolio: the 30-photo cap maps to 409 with data.code PORTFOLIO_LIMIT_REACHED")
    void should_return409_when_portfolioCapReached() throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(true);
        when(mediaService.uploadSalonPortfolioPhoto(eq(ownerId), eq(salonId), any()))
                .thenThrow(new PortfolioLimitReachedException());

        mockMvc.perform(multipart(portfolioUrl()).file(jpeg()).with(as(ownerId, Role.SALON_OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value(PortfolioLimitReachedException.ERROR_CODE));
    }

    @Test
    @DisplayName("portfolio: lock contention maps to 503 + Retry-After, never the 409 the client reads as the cap")
    void should_return503WithRetryAfter_when_portfolioBusy() throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(true);
        when(mediaService.uploadSalonPortfolioPhoto(eq(ownerId), eq(salonId), any()))
                .thenThrow(new PortfolioBusyException());

        mockMvc.perform(multipart(portfolioUrl()).file(jpeg()).with(as(ownerId, Role.SALON_OWNER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After",
                        String.valueOf(PortfolioBusyException.RETRY_AFTER_SECONDS)));
    }

    @Test
    @DisplayName("portfolio: POST consumes multipart/form-data only — a JSON body is 415")
    void should_return415_when_portfolioUploadIsNotMultipart() throws Exception {
        lenient().when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(true);

        mockMvc.perform(post(portfolioUrl()).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(as(UUID.randomUUID(), Role.SALON_OWNER)))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("portfolio: anonymous POST/DELETE → 401")
    void should_return401OnPortfolio_when_anonymous() throws Exception {
        mockMvc.perform(multipart(portfolioUrl()).file(jpeg())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(portfolioUrl() + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("logo/cover stay OWNER-only: a SALON_ADMIN allowed on the portfolio is still 403 on /media/{slot}")
    void should_keepLogoOwnerOnly_when_adminCanManagePortfolio() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(authz.canManageSalon(any(Authentication.class), eq(salonId))).thenReturn(true);
        lenient().when(authz.isOwnerOf(any(Authentication.class), eq(salonId))).thenReturn(false);

        mockMvc.perform(multipart(portfolioUrl()).file(jpeg()).with(as(adminId, Role.SALON_ADMIN)))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart(url(SalonImageSlot.LOGO)).file(jpeg()).with(as(adminId, Role.SALON_ADMIN)))
                .andExpect(status().isForbidden());

        verify(mediaService, never()).uploadSalonImage(any(), any(), any(), any());
    }

    private String portfolioUrl() {
        return "/api/v1/salons/" + salonId + "/portfolio";
    }

    private String url(SalonImageSlot slot) {
        return "/api/v1/salons/" + salonId + "/media/" + slot.pathSegment();
    }

    private static MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG);
    }

    private static RequestPostProcessor as(UUID userId, Role role) {
        var token = new UsernamePasswordAuthenticationToken(userId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(userId);
        return authentication(token);
    }
}
