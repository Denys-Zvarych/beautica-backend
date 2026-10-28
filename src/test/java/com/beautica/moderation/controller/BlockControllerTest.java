package com.beautica.moderation.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBlockRateLimitedException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.moderation.dto.BlockKind;
import com.beautica.moderation.dto.BlockResponse;
import com.beautica.moderation.service.ClientBlockFacade;
import com.beautica.moderation.service.ClientBlockService;
import com.beautica.moderation.service.ClientBlockService.CreateBlockResult;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link BlockController} (phase 397): HTTP contract — role gate (403 per denied
 * role), validation, 201 vs 200, 400/404/429 mapping, 204 unblock, and that no response leaks a phone or hash.
 */
@WebMvcTest(BlockController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("BlockController — @WebMvcTest slice (phase 397)")
class BlockControllerTest {

    private static final String URL = "/api/v1/blocks";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID BLOCK_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-10-28T10:15:30Z");

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
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

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClientBlockService clientBlockService;

    @MockBean
    private ClientBlockFacade clientBlockFacade;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"INDEPENDENT_MASTER", "SALON_OWNER", "SALON_ADMIN"})
    void should_return201_when_providerBlocks(Role role) throws Exception {
        when(clientBlockFacade.block(USER_ID, role, BOOKING_ID)).thenReturn(new CreateBlockResult(guest().withCancelledBookings(2), true));

        mockMvc.perform(post(URL).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(body(BOOKING_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(BLOCK_ID.toString()))
                .andExpect(jsonPath("$.data.kind").value("GUEST"))
                .andExpect(jsonPath("$.data.displayName").value("Марія Ш."))
                .andExpect(jsonPath("$.data.maskedPhone").value("+380 •• ••• 12 34"))
                .andExpect(jsonPath("$.data.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-28T10:15:30Z"))
                .andExpect(jsonPath("$.data.cancelledBookings").value(2));
    }

    @Test
    void should_return200_when_alreadyBlocked() throws Exception {
        when(clientBlockFacade.block(USER_ID, Role.SALON_OWNER, BOOKING_ID))
                .thenReturn(new CreateBlockResult(guest(), false));

        mockMvc.perform(post(URL).with(as(Role.SALON_OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(BOOKING_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(BLOCK_ID.toString()))
                .andExpect(jsonPath("$.data.cancelledBookings").value(0));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"CLIENT", "SALON_MASTER"})
    void should_return403_when_roleCannotBlock(Role role) throws Exception {
        mockMvc.perform(post(URL).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(body(BOOKING_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(as(role))).andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/" + BLOCK_ID).with(as(role))).andExpect(status().isForbidden());

        verifyNoInteractions(clientBlockService, clientBlockFacade);
    }

    @Test
    void should_return401_when_anonymous() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(BOOKING_ID)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"bookingId\":null}", "{\"bookingId\":\"not-a-uuid\"}"})
    void should_return400_when_bodyInvalid(String json) throws Exception {
        mockMvc.perform(post(URL).with(as(Role.INDEPENDENT_MASTER)).contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(clientBlockService, clientBlockFacade);
    }

    @Test
    void should_return400_when_staffWalkIn() throws Exception {
        when(clientBlockFacade.block(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "This client cannot be blocked"));

        mockMvc.perform(post(URL).with(as(Role.SALON_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(BOOKING_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return404_when_bookingOutOfScope() throws Exception {
        when(clientBlockFacade.block(any(), any(), any())).thenThrow(new NotFoundException("Booking not found"));

        mockMvc.perform(post(URL).with(as(Role.INDEPENDENT_MASTER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(BOOKING_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_return429WithRetryAfter_when_blockBudgetExhausted() throws Exception {
        when(clientBlockFacade.block(any(), any(), any())).thenThrow(new ClientBlockRateLimitedException(3600));

        mockMvc.perform(post(URL).with(as(Role.SALON_OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(BOOKING_ID)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Too many requests — please slow down"));
    }

    @Test
    void should_return429_when_unblockBudgetExhausted() throws Exception {
        doThrow(new ClientBlockRateLimitedException(3600))
                .when(clientBlockService).unblock(any(), any(), any());

        mockMvc.perform(delete(URL + "/" + BLOCK_ID).with(as(Role.INDEPENDENT_MASTER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));
    }

    @Test
    void should_listBlocksWithoutPhoneOrHash_when_provider() throws Exception {
        BlockResponse client = new BlockResponse(UUID.randomUUID(), BlockKind.CLIENT, "Олена К.",
                "https://pub.example.r2.dev/a.jpg", null, CREATED_AT, 0);
        when(clientBlockService.list(USER_ID, Role.SALON_ADMIN)).thenReturn(List.of(client, guest()));

        mockMvc.perform(get(URL).with(as(Role.SALON_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].kind").value("CLIENT"))
                .andExpect(jsonPath("$.data[0].maskedPhone").doesNotExist())
                .andExpect(jsonPath("$.data[1].kind").value("GUEST"))
                .andExpect(content().string(not(containsString("phoneHash"))))
                .andExpect(content().string(not(containsString("blockedUserId"))))
                .andExpect(content().string(not(containsString("+380501234"))));
    }

    @Test
    void should_return204_when_unblocking() throws Exception {
        mockMvc.perform(delete(URL + "/" + BLOCK_ID).with(as(Role.INDEPENDENT_MASTER)))
                .andExpect(status().isNoContent());

        verify(clientBlockService).unblock(eq(USER_ID), eq(Role.INDEPENDENT_MASTER), eq(BLOCK_ID));
    }

    private static BlockResponse guest() {
        return new BlockResponse(BLOCK_ID, BlockKind.GUEST, "Марія Ш.", null, BlockResponse.maskPhone("1234"),
                CREATED_AT, 0);
    }

    private static RequestPostProcessor as(Role role) {
        var token = new UsernamePasswordAuthenticationToken(
                "provider@beautica.test", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(USER_ID);
        return authentication(token);
    }

    private static String body(UUID bookingId) {
        return "{\"bookingId\":\"" + bookingId + "\"}";
    }
}
