package com.beautica.master;

import com.beautica.auth.AccessTokenDenylist;
import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.booking.filter.BookingRateLimitFilter;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.controller.MasterController;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.entity.MasterType;
import com.beautica.master.service.MasterService;
import com.beautica.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit fix, finding 5 — guards the ORDER of the two {@code /api/v1/masters/…} GET matchers in the
 * <b>production</b> {@link com.beautica.config.SecurityConfig}.
 *
 * <p>Why a separate slice: {@link MasterControllerTest} declares its own
 * {@code @TestConfiguration SecurityFilterChain}, so nothing in it can observe the real config.
 * This class imports {@code SecurityConfig} itself, which is the only way the assertion below can
 * be about production behaviour rather than about a test fixture.
 *
 * <p>Why {@link MvcResult#getHandler()} and not the status code: a PathPattern {@code "{var}"}
 * matches any single segment, so {@code "/api/v1/masters/{masterId}"} pattern-matches the literal
 * {@code "/api/v1/masters/me"}. When that (permitAll) rule wins, an anonymous request is admitted
 * by the filter chain, reaches the DispatcherServlet, resolves {@code getMyProfile} as its handler,
 * and is only then rejected by {@code @PreAuthorize} — which ALSO produces 401 for an anonymous
 * caller. The status code is therefore identical in both worlds and cannot falsify anything;
 * {@code getHandler()} is what distinguishes them:
 *
 * <ul>
 *   <li><b>Fixed</b> — {@code "/api/v1/masters/me" → authenticated()} is registered first, the
 *       {@code AuthorizationFilter} rejects before dispatch, and {@code getHandler()} is
 *       {@code null}.</li>
 *   <li><b>Regressed</b> (line deleted, or moved below the {@code {masterId}} matcher) —
 *       dispatch happens and {@code getHandler()} is the {@code MasterController#getMyProfile}
 *       handler method.</li>
 * </ul>
 */
@WebMvcTest(MasterController.class)
@TestPropertySource(properties = {
        "app.frontend.base-url=http://localhost:3000",
        // Lets SecurityTestFilters below REPLACE WebMvcTestSupport's jwtAuthenticationFilter
        // definition by name — see that class's javadoc for why the exact runtime type matters.
        "spring.main.allow-bean-definition-overriding=true"})
@Import({WebMvcTestSupport.class,
        com.beautica.config.SecurityConfig.class,
        MasterMeSecurityMatcherTest.SecurityTestFilters.class})
@DisplayName("SecurityConfig — GET /api/v1/masters/me must not fall through the {masterId} permitAll matcher")
class MasterMeSecurityMatcherTest {

    private static final String MASTERS_URL = "/api/v1/masters";

    /**
     * The two beans the real {@code SecurityConfig} needs that {@link WebMvcTestSupport} cannot
     * supply for this particular slice.
     *
     * <h2>{@code jwtAuthenticationFilter} — must be the EXACT class</h2>
     * {@code SecurityConfig} registers it with
     * {@code addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)}
     * and then anchors two more filters on it by TYPE
     * ({@code addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class)},
     * {@code addFilterAfter(bookingRateLimitFilter, JwtAuthenticationFilter.class)}). Spring
     * Security keys that registry on the filter's runtime class, so
     * {@link WebMvcTestSupport}'s anonymous SUBCLASS makes the two anchors fail with "The Filter
     * class JwtAuthenticationFilter does not have a registered order". The real class is used
     * instead — safe here because no test in this slice sends an {@code Authorization} header, so
     * the filter takes its no-token branch and delegates straight down the chain; authentication
     * is injected with the {@code authentication()} post-processor exactly as elsewhere. This
     * definition replaces {@code WebMvcTestSupport}'s by NAME (hence
     * {@code allow-bean-definition-overriding} above), rather than adding a second {@code @Primary}
     * bean of the same type, which would be ambiguous.
     *
     * <h2>{@code bookingRateLimitFilter} — absent from every slice</h2>
     * It is an explicit {@code @Bean} in {@code RateLimitConfig} (never a {@code @Component}), so
     * a {@code @WebMvcTest} loads neither it nor the {@code LoadingCache} beans it consumes — yet
     * {@code SecurityConfig}'s constructor requires one. A pass-through stand-in is used rather
     * than a Mockito mock: a mocked {@code Filter} swallows the request and returns an empty 200,
     * which would make every assertion in this class meaningless.
     */
    @TestConfiguration
    static class SecurityTestFilters {

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
            return new JwtAuthenticationFilter(
                    jwtTokenProvider,
                    Mockito.mock(AccessTokenDenylist.class),
                    Mockito.mock(TokensValidAfterCache.class));
        }

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        BookingRateLimitFilter bookingRateLimitFilter(ObjectMapper objectMapper) {
            LoadingCache<String, Bucket> dummy = Mockito.mock(LoadingCache.class);
            return new BookingRateLimitFilter(dummy, dummy, dummy, dummy, dummy, objectMapper) {
                @Override
                protected void doFilterInternal(HttpServletRequest req,
                                                HttpServletResponse res,
                                                FilterChain chain)
                        throws ServletException, IOException {
                    chain.doFilter(req, res);
                }

                @Override
                public boolean shouldNotFilter(HttpServletRequest request) {
                    return true;
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MasterService masterService;

    @MockBean
    private SlotCalculationService slotCalculationService;

    @MockBean
    private com.beautica.master.service.MasterScheduleService masterScheduleService;

    @MockBean
    private com.beautica.booking.service.ScheduleOverrideConflictService scheduleOverrideConflictService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserService userService;

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var token = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(userId);
        return authentication(token);
    }

    private static MasterDetailResponse stubMasterDetail(UUID masterId) {
        return new MasterDetailResponse(
                masterId, "Oksana", "Kovalenko", null, null, null, null, null,
                null, null, null, null, BigDecimal.ZERO, 0, MasterType.SALON_OWNER, null, List.of(),
                null, null, null);
    }

    @Test
    @DisplayName("anonymous GET /masters/me is rejected by the filter chain BEFORE the DispatcherServlet")
    void should_rejectBeforeDispatch_when_anonymousRequestsMastersMe() throws Exception {
        MvcResult result = mockMvc.perform(get(MASTERS_URL + "/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getHandler())
                .as("GET /api/v1/masters/me must be matched by its OWN authenticated() rule, which "
                        + "is registered ABOVE the /api/v1/masters/{masterId} permitAll matcher. A "
                        + "non-null handler here means the permitAll pattern won, the request was "
                        + "dispatched, and @PreAuthorize was the sole gate on the endpoint Phase "
                        + "265 widened to SALON_OWNER.")
                .isNull();
        verify(masterService, never()).findMyMasterDetail(any());
    }

    @Test
    @DisplayName("GET /masters/{masterId} stays public — the new literal matcher must not shadow it")
    void should_stayPublic_when_anonymousRequestsMasterDetailById() throws Exception {
        UUID masterId = UUID.randomUUID();
        when(masterService.getMasterDetail(masterId)).thenReturn(stubMasterDetail(masterId));

        mockMvc.perform(get(MASTERS_URL + "/" + masterId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an authenticated SALON_OWNER still reaches GET /masters/me through the real chain")
    void should_dispatchToController_when_salonOwnerRequestsMastersMe() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        when(masterService.findMyMasterDetail(ownerId)).thenReturn(Optional.of(stubMasterDetail(masterId)));

        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER)))
                .andExpect(status().isOk());
    }
}
