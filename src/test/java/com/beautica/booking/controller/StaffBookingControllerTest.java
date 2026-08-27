package com.beautica.booking.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.AppointmentItemResponse;
import com.beautica.booking.dto.StaffBookingCommand;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.booking.dto.StaffClientRef;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.StaffBookingScopeResolver;
import com.beautica.booking.service.StaffBookingService;
import com.beautica.common.TimeZones;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 22.4 — {@code POST /api/v1/masters/&#123;masterId&#125;/bookings}, sliced.
 *
 * <p>A {@code @WebMvcTest} rather than a full context (§M-1): what is under test here is the wiring
 * — the role gate, the {@code @authz.canBookForMaster} conjunct, Bean Validation, and the two values
 * the controller is solely responsible for sourcing correctly (the actor and the scope). The
 * authorization matrix's DB-backed rows live in {@code AuthorizationServiceCanBookForMasterTest}
 * and {@code StaffBookingScopeResolverTest}; the end-to-end persistence is
 * {@code StaffBookingEndpointIT}.
 *
 * <p>Authentication is injected with the {@code authentication()} post-processor so
 * {@code details} carries the caller UUID — never {@code @WithMockUser}, which leaves it null.
 */
@WebMvcTest(StaffBookingController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("StaffBookingController — @WebMvcTest slice (Phase 22.4)")
class StaffBookingControllerTest {

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
                    .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, exc) ->
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    private static final UUID MASTER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CALLER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SALON_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID MASTER_SERVICE_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private static final String URL = "/api/v1/masters/" + MASTER_ID + "/bookings";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StaffBookingService staffBookingService;

    @MockBean
    private StaffBookingScopeResolver staffBookingScopeResolver;

    @MockBean(name = "authz")
    private AuthorizationService authz;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * The three collaborators are {@code @MockBean}s on a context shared by every {@code @Nested}
     * class here, and several assertions in this suite are {@code verify(..., never())} /
     * exact-count claims. Without an explicit reset those claims see invocations from earlier test
     * methods and fail (or, worse, pass for the wrong reason once the order changes). Reset here
     * rather than relying on listener ordering.
     */
    @BeforeEach
    void resetCollaborators() {
        Mockito.reset(staffBookingService, staffBookingScopeResolver, authz);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The two values the controller alone is responsible for sourcing
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Actor and scope provenance")
    class Provenance {

        /**
         * <b>P2, pinned.</b> The body carries a {@code createdByUserId} naming somebody else — the
         * exact field 22.2 deleted from {@link StaffBookingCommand} so that a
         * {@code request.toCommand()} mapping could not reintroduce it. The request record has no
         * such component, so Jackson drops it and the actor handed to the service is the
         * authenticated principal. {@code created_by_user_id} is the ONLY attribution a staff
         * booking carries; a spoofable actor destroys the audit trail outright.
         */
        @Test
        @DisplayName("actorId comes from the principal, never from a body field trying to name someone else")
        void should_passTheAuthenticatedPrincipal_when_bodyTriesToNameADifferentActor() throws Exception {
            UUID impersonated = UUID.randomUUID();
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWithExtraField("\"createdByUserId\":\"" + impersonated + "\",")))
                    .andExpect(status().isCreated());

            ArgumentCaptor<UUID> actor = ArgumentCaptor.forClass(UUID.class);
            verify(staffBookingService).createStaffBooking(any(), actor.capture());
            assertThat(actor.getValue()).isEqualTo(CALLER_ID).isNotEqualTo(impersonated);
        }

        /**
         * <b>P1, pinned at the seam.</b> The scope handed to the service is exactly what the
         * caller-keyed resolver returned — the controller neither derives nor rewrites it, and no
         * body field can reach it.
         */
        @Test
        @DisplayName("the scope handed to the service is the resolver's, derived from the caller")
        void should_passTheResolvedScope_when_creatingABooking() throws Exception {
            authorize();
            StaffBookingScope resolved = new StaffBookingScope.InSalon(SALON_ID);
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID))).thenReturn(resolved);
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

            ArgumentCaptor<StaffBookingCommand> cmd = ArgumentCaptor.forClass(StaffBookingCommand.class);
            verify(staffBookingService).createStaffBooking(cmd.capture(), eq(CALLER_ID));
            assertThat(cmd.getValue().scope()).isSameAs(resolved);
            assertThat(cmd.getValue().masterId())
                    .as("the master comes from the PATH, the segment @PreAuthorize authorised")
                    .isEqualTo(MASTER_ID);
            assertThat(cmd.getValue().client())
                    .isEqualTo(new StaffClientRef.Guest("Марія", "Левченко", "050 123 45 67"));
        }

        /**
         * The controller must not "helpfully" pre-format the phone — normalisation to E.164 lives in
         * {@code StaffBookingService} so exactly one component can reject a bad number, with exactly
         * one message. (Asserted above too; stated here because it is a precondition, not a detail.)
         */
        @Test
        @DisplayName("the guest phone reaches the service raw, un-normalised")
        void should_forwardTheTypedPhoneVerbatim_when_itContainsSeparators() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.Self(CALLER_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.INDEPENDENT_MASTER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isCreated());

            ArgumentCaptor<StaffBookingCommand> cmd = ArgumentCaptor.forClass(StaffBookingCommand.class);
            verify(staffBookingService).createStaffBooking(cmd.capture(), eq(CALLER_ID));
            assertThat(((StaffClientRef.Guest) cmd.getValue().client()).phone()).isEqualTo("050 123 45 67");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The role gate — one negative row per role that must be denied
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Role gate")
    class RoleGate {

        @Test
        @DisplayName("SALON_ADMIN is admitted by the role gate (admin = owner)")
        void should_allow_when_callerIsSalonAdmin() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isCreated());
        }

        /**
         * A {@code SALON_MASTER}'s calendar is read-only and this endpoint does not change that —
         * including for their OWN master profile (amendment A6). The role conjunct short-circuits,
         * so the predicate is not even consulted.
         */
        @Test
        @DisplayName("SALON_MASTER → 403, without the authz predicate ever being consulted")
        void should_reject403_when_callerIsSalonMaster() throws Exception {
            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_MASTER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isForbidden());

            verify(authz, never()).canBookForMaster(any(), any());
            verify(staffBookingService, never()).createStaffBooking(any(), any());
        }

        @Test
        @DisplayName("CLIENT → 403")
        void should_reject403_when_callerIsClient() throws Exception {
            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.CLIENT))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isForbidden());

            verify(authz, never()).canBookForMaster(any(), any());
            verify(staffBookingService, never()).createStaffBooking(any(), any());
        }

        /**
         * The predicate answering {@code false} — an unknown, inactive, or foreign master — is a
         * 403, and the handler is never entered, so 22.2's own 404 for an unknown master is
         * unreachable over HTTP for an unauthorized caller. That ordering is the no-existence-oracle
         * guarantee.
         */
        @Test
        @DisplayName("canBookForMaster false → 403, and the service is never reached")
        void should_reject403_when_authzPredicateDenies() throws Exception {
            when(authz.canBookForMaster(any(), eq(MASTER_ID))).thenReturn(false);

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isForbidden());

            verify(staffBookingService, never()).createStaffBooking(any(), any());
            verify(staffBookingScopeResolver, never()).resolve(any(), any());
        }

        @Test
        @DisplayName("anonymous → 401")
        void should_reject401_when_noPrincipal() throws Exception {
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Bean Validation — a field-named 400, never the service's generic BusinessException
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Request validation")
    class Validation {

        @Test
        @DisplayName("missing masterServiceId → 400")
        void should_reject400_when_masterServiceIdIsMissing() throws Exception {
            expectBadRequest("""
                    {"startsAt":"%s","guest":{"name":"Марія","surname":"Левченко","phone":"050 123 45 67"}}
                    """.formatted(future()));
        }

        @Test
        @DisplayName("empty masterServiceIds list → 400")
        void should_reject400_when_masterServiceIdsIsEmpty() throws Exception {
            expectBadRequest("""
                    {"masterServiceIds":[],"startsAt":"%s",
                     "guest":{"name":"Марія","surname":"Левченко","phone":"050 123 45 67"}}
                    """.formatted(future()));
        }

        /** {@code @Size(max = SlotCalculationService.MAX_SERVICES_PER_VISIT)} — the cap is 10. */
        @Test
        @DisplayName("masterServiceIds over the 10-service cap → 400")
        void should_reject400_when_masterServiceIdsExceedsTen() throws Exception {
            String elevenIds = java.util.stream.IntStream.range(0, 11)
                    .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                    .collect(java.util.stream.Collectors.joining(","));
            expectBadRequest("""
                    {"masterServiceIds":[%s],"startsAt":"%s",
                     "guest":{"name":"Марія","surname":"Левченко","phone":"050 123 45 67"}}
                    """.formatted(elevenIds, future()));
        }

        @Test
        @DisplayName("missing guest object → 400")
        void should_reject400_when_guestIsMissing() throws Exception {
            expectBadRequest("""
                    {"masterServiceIds":["%s"],"startsAt":"%s"}
                    """.formatted(MASTER_SERVICE_ID, future()));
        }

        @Test
        @DisplayName("blank guest name → 400")
        void should_reject400_when_guestNameIsBlank() throws Exception {
            expectBadRequest(body(future(), "   ", "Левченко", "050 123 45 67"));
        }

        @Test
        @DisplayName("missing guest surname → 400")
        void should_reject400_when_guestSurnameIsMissing() throws Exception {
            expectBadRequest("""
                    {"masterServiceIds":["%s"],"startsAt":"%s",
                     "guest":{"name":"Марія","phone":"050 123 45 67"}}
                    """.formatted(MASTER_SERVICE_ID, future()));
        }

        @Test
        @DisplayName("missing guest phone → 400")
        void should_reject400_when_guestPhoneIsMissing() throws Exception {
            expectBadRequest("""
                    {"masterServiceIds":["%s"],"startsAt":"%s",
                     "guest":{"name":"Марія","surname":"Левченко"}}
                    """.formatted(MASTER_SERVICE_ID, future()));
        }

        @Test
        @DisplayName("phone containing letters → 400 at the boundary, not a 500 from the DB CHECK")
        void should_reject400_when_phoneIsNotDigitsAndSeparators() throws Exception {
            expectBadRequest(body(future(), "Марія", "Левченко", "not-a-phone"));
        }

        @Test
        @DisplayName("guest name with an embedded control character → 400")
        void should_reject400_when_guestNameCarriesAControlCharacter() throws Exception {
            expectBadRequest(body(future(), "Мар\\u0000ія", "Левченко", "050 123 45 67"));
        }

        @Test
        @DisplayName("over-long guest name → 400, never a DataIntegrityViolationException 500")
        void should_reject400_when_guestNameExceedsTheColumn() throws Exception {
            expectBadRequest(body(future(), "я".repeat(101), "Левченко", "050 123 45 67"));
        }

        @Test
        @DisplayName("startsAt in the past → 400")
        void should_reject400_when_startsAtIsInThePast() throws Exception {
            expectBadRequest(body(
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
                    "Марія", "Левченко", "050 123 45 67"));
        }

        @Test
        @DisplayName("missing startsAt → 400")
        void should_reject400_when_startsAtIsMissing() throws Exception {
            expectBadRequest("""
                    {"masterServiceIds":["%s"],"guest":{"name":"Марія","surname":"Левченко","phone":"050 123 45 67"}}
                    """.formatted(MASTER_SERVICE_ID));
        }

        /**
         * <b>The status is not the contract; the SHAPE is.</b> {@code GuestClientDto}'s javadoc
         * states the annotations exist so a missing value surfaces as "a field-named 400 from Bean
         * Validation rather than as the service's generic {@code BusinessException} message" — and
         * every test above asserted only {@code status().isBadRequest()}, which the generic message
         * satisfies just as well. Dropping {@code @Valid} from the {@code guest} parameter, or
         * letting the value reach the service, would keep all ten green.
         */
        @Test
        @DisplayName("a validation 400 names the offending field, not a generic failure envelope")
        void should_nameTheOffendingField_when_theGuestSurnameIsMissing() throws Exception {
            authorize();

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"masterServiceIds":["%s"],"startsAt":"%s",
                                     "guest":{"name":"Марія","phone":"050 123 45 67"}}
                                    """.formatted(MASTER_SERVICE_ID, future())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errors.['guest.surname']")
                            .value("Client surname is required"));

            verify(staffBookingService, never()).createStaffBooking(any(), any());
        }

        /**
         * The {@code @Size(max = 100)} / {@code @Size(max = 20)} caps mirror the column widths, and
         * only the over-long side was tested — an off-by-one that tightened the cap to {@code < 100}
         * would reject legitimate input with nothing going red. The accepted end of each range is
         * the half that regresses silently.
         */
        @Test
        @DisplayName("a guest name of exactly the column width is accepted, not rejected off-by-one")
        void should_accept_when_guestNameIsExactlyAtTheColumnWidth() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(future(), "я".repeat(100), "Левченко", "050 123 45 67")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("a guest phone one character over the column width → 400")
        void should_reject400_when_guestPhoneExceedsTheColumn() throws Exception {
            expectBadRequest(body(future(), "Марія", "Левченко", "0".repeat(21)));
        }

        @Test
        @DisplayName("a guest phone of exactly the column width is accepted")
        void should_accept_when_guestPhoneIsExactlyAtTheColumnWidth() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(future(), "Марія", "Левченко", "0".repeat(20))))
                    .andExpect(status().isCreated());
        }

        private void expectBadRequest(String body) throws Exception {
            authorize();
            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(staffBookingService, never()).createStaffBooking(any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The 201 body (Phase 22.14) — AppointmentDetailResponse, the SAME shape the client
    // multi-service flow returns, never a hand-rolled second DTO
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("201 visit body")
    class VisitResponseBody {

        @Test
        @DisplayName("three services → 201 with items.length() == 3, correct totals and per-item window")
        void should_return201WithVisitBody_when_threeServices() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse(3));

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists())
                    .andExpect(jsonPath("$.data.items.length()").value(3))
                    .andExpect(jsonPath("$.data.totalPrice").value(1050.00))
                    .andExpect(jsonPath("$.data.totalDurationMinutes").value(180))
                    .andExpect(jsonPath("$.data.items[0].startsAt").exists())
                    .andExpect(jsonPath("$.data.items[0].endsAt").exists())
                    .andExpect(jsonPath("$.data.items[1].startsAt").exists())
                    .andExpect(jsonPath("$.data.items[2].endsAt").exists());
        }

        @Test
        @DisplayName("one service → 201 with a single-item visit")
        void should_return201WithSingleItemVisit_when_oneService() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse(1));

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.items.length()").value(1));
        }

        /**
         * D2, pinned: {@code AppointmentDetailResponse} has no {@code guestName}/{@code guestPhone}
         * field at all — the caller typed those seconds ago and still holds them. Asserted via
         * {@code doesNotExist()}, not merely "not equal to the typed value", so a later "helpful"
         * addition of the field is caught even if it were populated correctly.
         */
        @Test
        @DisplayName("the walk-in's own name/phone are never in the 201 body")
        void should_notExposeGuestFields_when_visitCreated() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.guestName").doesNotExist())
                    .andExpect(jsonPath("$.data.guestPhone").doesNotExist());
        }

        @Test
        @DisplayName("no cancelToken in the 201 body — a staff visit has none to carry")
        void should_notExposeCancelToken_when_visitCreated() throws Exception {
            authorize();
            when(staffBookingScopeResolver.resolve(any(), eq(MASTER_ID)))
                    .thenReturn(new StaffBookingScope.InSalon(SALON_ID));
            when(staffBookingService.createStaffBooking(any(), any())).thenReturn(stubResponse());

            mockMvc.perform(post(URL)
                            .with(authenticatedAs(CALLER_ID, Role.SALON_OWNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.cancelToken").doesNotExist());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private void authorize() {
        when(authz.canBookForMaster(any(), eq(MASTER_ID))).thenReturn(true);
    }

    private static RequestPostProcessor authenticatedAs(UUID userId, Role role) {
        var token = new UsernamePasswordAuthenticationToken(
                "staff@beautica.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(userId);
        return authentication(token);
    }

    private static String future() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();
    }

    private static String validBody() {
        return body(future(), "Марія", "Левченко", "050 123 45 67");
    }

    private static String body(String startsAt, String name, String surname, String phone) {
        return """
                {"masterServiceIds":["%s"],"startsAt":"%s",
                 "guest":{"name":"%s","surname":"%s","phone":"%s"}}
                """.formatted(MASTER_SERVICE_ID, startsAt, name, surname, phone);
    }

    private static String bodyWithExtraField(String extraJsonFragment) {
        return """
                {%s"masterServiceIds":["%s"],"startsAt":"%s",
                 "guest":{"name":"Марія","surname":"Левченко","phone":"050 123 45 67"}}
                """.formatted(extraJsonFragment, MASTER_SERVICE_ID, future());
    }

    /** The single-service (N = 1) shape — the one almost every existing case here needs. */
    private static AppointmentDetailResponse stubResponse() {
        return stubResponse(1);
    }

    /**
     * A visit response with {@code itemCount} chained services, back to back — REUSE-FIRST: one
     * builder for both the N = 1 and N &gt; 1 shapes, rather than a near-duplicate second stub.
     */
    private static AppointmentDetailResponse stubResponse(int itemCount) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        java.time.ZonedDateTime cursor = start.atZoneSameInstant(TimeZones.KYIV);
        java.time.ZonedDateTime visitStart = cursor;
        BigDecimal itemPrice = new BigDecimal("350.00");
        List<AppointmentItemResponse> items = new java.util.ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            java.time.ZonedDateTime itemEnd = cursor.plusHours(1);
            items.add(new AppointmentItemResponse(
                    UUID.randomUUID(), MASTER_SERVICE_ID, "Манікюр", BookingStatus.CONFIRMED,
                    cursor, itemEnd, 60, itemPrice, null, null, null));
            cursor = itemEnd;
        }
        return new AppointmentDetailResponse(
                UUID.randomUUID(),                          // id (the visit/appointment id)
                BookingStatus.CONFIRMED,                     // status
                MASTER_ID,                                    // masterId
                "Марія",                                       // masterFirstName
                "Левченко",                                     // masterLastName
                null,                                            // masterProfessionalTitle
                null,                                            // masterAvatarUrl
                Role.SALON_MASTER,                               // masterType
                null,                                             // salonName
                visitStart,                                        // startsAt
                cursor,                                             // endsAt (last item's end)
                itemCount * 60,                                     // totalDurationMinutes
                itemPrice.multiply(BigDecimal.valueOf(itemCount)),   // totalPrice
                null,                                                 // totalPriceMax
                null,                                                 // clientComment
                OffsetDateTime.now(ZoneOffset.UTC),                    // createdAt
                items,                                                  // items
                null,                                                   // providerComment
                null,                                                   // clientCancellationNote
                null,                                                   // cityLabel
                null,                                                   // districtLabel
                null,                                                   // street
                null,                                                   // buildingNo
                null);                                                  // locationNote
    }
}
