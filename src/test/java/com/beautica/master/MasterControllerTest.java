package com.beautica.master;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.controller.MasterController;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.entity.MasterType;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.master.service.MasterService;
import com.beautica.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.entity.Master;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link MasterController}.
 *
 * <p>Migrated from {@code @SpringBootTest(RANDOM_PORT)} + Testcontainers.
 * {@link AuthorizationService} is mocked to control {@code @PreAuthorize} outcomes
 * without requiring a real database. {@link MasterService} is mocked for all
 * business-logic interactions.
 */
@WebMvcTest(MasterController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("MasterController — @WebMvcTest slice")
class MasterControllerTest {

    private static final Logger log = LoggerFactory.getLogger(MasterControllerTest.class);
    private static final String MASTERS_URL = "/api/v1/masters";

    // ── Security configuration ────────────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}").permitAll()
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

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MasterService masterService;

    @MockBean
    private SlotCalculationService slotCalculationService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserService userService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private MasterDetailResponse stubMasterDetail(UUID masterId, UUID userId) {
        return new MasterDetailResponse(
                masterId, "Oksana", "Kovalenko", null, null, null, null, null,
                null, null, null, BigDecimal.ZERO, 0, MasterType.INDEPENDENT_MASTER, null, List.of(),
                null, null, null);
    }

    // ── GET /{masterId} — public ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /{masterId} — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetMasterDetail() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(masterService.getMasterDetail(masterId)).thenReturn(stubMasterDetail(masterId, userId));

        log.debug("Act: GET {}/{} without credentials — public endpoint", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /{masterId} — 404 when master does not exist")
    void should_return404_when_getMasterWithUnknownId() throws Exception {
        var unknownMasterId = UUID.randomUUID();
        when(masterService.getMasterDetail(unknownMasterId))
                .thenThrow(new NotFoundException("Master not found"));

        log.debug("Act: GET {}/{} for a master that does not exist", MASTERS_URL, unknownMasterId);
        mockMvc.perform(get(MASTERS_URL + "/" + unknownMasterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{masterId} — phoneNumber, street, buildingNo, locationNote, cityId, oblastId, districtId are masked when caller is unauthenticated")
    void should_not_expose_address_fields_when_caller_is_unauthenticated() throws Exception {
        var masterId = UUID.randomUUID();
        var cityUuid = UUID.randomUUID();
        var oblastUuid = UUID.randomUUID();
        var districtUuid = UUID.randomUUID();
        // Stub with non-null PII and locality ID fields to prove the controller masks them.
        // Locality IDs are set to non-null values so the test proves masking removes them,
        // not merely that they were already absent (HIGH-3).
        MasterDetailResponse fullDetail = new MasterDetailResponse(
                masterId, "Oksana", "Kovalenko", "+380671234567", "Київ",
                "вул. Хрещатик", "1A", "green door",
                null, null, null, BigDecimal.ZERO, 0, MasterType.INDEPENDENT_MASTER, null, List.of(),
                cityUuid, oblastUuid, districtUuid);
        when(masterService.getMasterDetail(masterId)).thenReturn(fullDetail);

        log.debug("Act: GET {}/{} without credentials — PII and locality ID fields must be masked", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.data.street").doesNotExist())
                .andExpect(jsonPath("$.data.buildingNo").doesNotExist())
                .andExpect(jsonPath("$.data.locationNote").doesNotExist())
                // HIGH-3: locality cascade IDs must also be masked for unauthenticated callers
                .andExpect(jsonPath("$.data.cityId").doesNotExist())
                .andExpect(jsonPath("$.data.oblastId").doesNotExist())
                .andExpect(jsonPath("$.data.districtId").doesNotExist());
    }

    // ── GET /me — self-profile ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /me — phoneNumber, street, buildingNo, locationNote are present when authenticated INDEPENDENT_MASTER calls own profile")
    void should_expose_address_fields_when_authenticatedMasterCallsGetMe() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        // Stub with non-null PII fields — the /me handler must return them unmasked.
        MasterDetailResponse fullDetail = new MasterDetailResponse(
                masterId, "Oksana", "Kovalenko", "+380671234567", "Київ",
                "вул. Хрещатик", "1A", "green door",
                null, null, null, BigDecimal.ZERO, 0, MasterType.INDEPENDENT_MASTER, null, List.of(),
                null, null, null);
        when(masterService.getMyMasterDetail(userId)).thenReturn(fullDetail);

        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").value("+380671234567"))
                .andExpect(jsonPath("$.data.street").value("вул. Хрещатик"))
                .andExpect(jsonPath("$.data.buildingNo").value("1A"))
                .andExpect(jsonPath("$.data.locationNote").value("green door"));
    }

    @Test
    @DisplayName("GET /me — cityId and oblastId are present when authenticated master calls own profile (HIGH-3)")
    void should_expose_locality_ids_when_authenticatedMasterCallsGetMe() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var cityUuid = UUID.randomUUID();
        var oblastUuid = UUID.randomUUID();
        // Stub with non-null locality IDs — the /me handler must return them unmasked.
        MasterDetailResponse fullDetail = new MasterDetailResponse(
                masterId, "Oksana", "Kovalenko", "+380671234567", "Київ",
                "вул. Хрещатик", "1A", "green door",
                null, null, null, BigDecimal.ZERO, 0, MasterType.INDEPENDENT_MASTER, null, List.of(),
                cityUuid, oblastUuid, null);
        when(masterService.getMyMasterDetail(userId)).thenReturn(fullDetail);

        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cityId").value(cityUuid.toString()))
                .andExpect(jsonPath("$.data.oblastId").value(oblastUuid.toString()));
    }

    @Test
    @DisplayName("GET /me — 200 with master profile when authenticated INDEPENDENT_MASTER")
    void should_return200WithProfile_when_independentMasterRequestsOwnProfile() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        // Controller now delegates to a single getMyMasterDetail(UUID) call — stub that method only.
        when(masterService.getMyMasterDetail(userId)).thenReturn(stubMasterDetail(masterId, userId));

        log.debug("Act: GET {}/me as INDEPENDENT_MASTER — must return 200 with own profile", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.masterId").value(masterId.toString()))
                .andExpect(jsonPath("$.data.firstName").value("Oksana"));
    }

    @Test
    @DisplayName("GET /me — 401 when unauthenticated request")
    void should_return401_when_unauthenticatedRequestsMyProfile() throws Exception {
        log.debug("Act: GET {}/me without credentials — must be rejected with 401", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me — 403 when CLIENT requests own profile (role not permitted)")
    void should_return403_when_clientRequestsOwnProfile() throws Exception {
        var clientId = UUID.randomUUID();

        log.debug("Act: GET {}/me as CLIENT — must be denied with 403", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /me — 403 when SALON_OWNER requests own profile (role not permitted)")
    void should_return403_when_salonOwnerRequestsOwnProfile() throws Exception {
        var ownerId = UUID.randomUUID();

        log.debug("Act: GET {}/me as SALON_OWNER — must be denied with 403", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /me — 200 when SALON_MASTER requests own profile")
    void should_return200_when_salonMasterRequestsOwnProfile() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(masterService.getMyMasterDetail(userId)).thenReturn(stubMasterDetail(masterId, userId));

        log.debug("Act: GET {}/me as SALON_MASTER — must return 200 with profile", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(userId, "smaster@beautica.test", Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.masterId").value(masterId.toString()));
    }

    @Test
    @DisplayName("GET /me — 404 when master record does not exist for authenticated user")
    void should_return404_when_masterRecordDoesNotExist() throws Exception {
        var userId = UUID.randomUUID();

        when(masterService.getMyMasterDetail(userId))
                .thenThrow(new NotFoundException("Master not found"));

        log.debug("Act: GET {}/me as INDEPENDENT_MASTER with no master record — must return 404", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me")
                        .with(authenticatedAs(userId, "imaster@beautica.test", Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ── GET /by-salon/{salonId} — protected ──────────────────────────────────

    @Test
    @DisplayName("GET /by-salon/{salonId} — 401 when no Authorization header")
    void should_return401_when_noToken_on_getMastersBySalon() throws Exception {
        var salonId = UUID.randomUUID();

        log.debug("Act: GET {}/by-salon/{} without credentials — must be rejected with 401",
                MASTERS_URL, salonId);
        mockMvc.perform(get(MASTERS_URL + "/by-salon/" + salonId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /by-salon/{salonId} — 200 with paged masters when authenticated")
    void should_return200_withPagedMasters_when_authenticated() throws Exception {
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var clientId = UUID.randomUUID();

        var summary = new MasterSummaryResponse(
                masterId, "Oksana", "Kovalenko", null,
                BigDecimal.ZERO, 0, MasterType.INDEPENDENT_MASTER);
        Page<MasterSummaryResponse> page =
                new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1);

        when(masterService.getMastersByPage(eq(salonId), any())).thenReturn(page);

        log.debug("Act: GET {}/by-salon/{} as CLIENT — must return 200 with one master", MASTERS_URL, salonId);
        mockMvc.perform(get(MASTERS_URL + "/by-salon/" + salonId)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data").isArray())
                .andExpect(jsonPath("$.data.data[0].masterId").value(masterId.toString()))
                .andExpect(jsonPath("$.data.data[0].firstName").value("Oksana"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // ── PATCH /{masterId}/working-hours — protected ────────────────────────────

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 403 when SALON_MASTER role is used")
    void should_return403_when_unauthorizedActorPatchesWorkingHours() throws Exception {
        var masterId = UUID.randomUUID();
        var salonMasterUserId = UUID.randomUUID();
        // canManageMasterSchedule short-circuits false for SALON_MASTER role
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(false);

        var requests = List.of(new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String body = objectMapper.writeValueAsString(requests);

        log.debug("Act: PATCH {}/{}/working-hours as SALON_MASTER — must be denied", MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(salonMasterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 403 when SALON_OWNER does not own the master")
    void should_return403_when_salonOwnerDoesNotOwnMaster() throws Exception {
        var foreignMasterId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(foreignMasterId))).thenReturn(false);

        var requests = List.of(new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String body = objectMapper.writeValueAsString(requests);

        mockMvc.perform(patch(MASTERS_URL + "/" + foreignMasterId + "/working-hours")
                        .with(authenticatedAs(actorId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 401 when no Authorization header")
    void should_return401_when_noTokenOnPatchWorkingHours() throws Exception {
        var masterId = UUID.randomUUID();
        var requests = List.of(new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String body = objectMapper.writeValueAsString(requests);

        log.debug("Act: PATCH {}/{}/working-hours without credentials", MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 when body has invalid dayOfWeek (0 fails @Min(1))")
    void should_return400_when_invalidWorkingHoursBodyOnPatchWorkingHours() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // dayOfWeek is absent → defaults to 0, which fails @Min(1) constraint
        String body = "[{\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"isActive\":true}]";

        log.debug("Act: PATCH {}/{}/working-hours with invalid body (dayOfWeek=0) — must be rejected with 400",
                MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 with readable @Size(max=7) message when payload has 8 entries (regression)")
    void should_return400WithReadableSizeMessage_when_workingHoursPayloadExceeds7Entries() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // 8 entries (days 1..7 plus a duplicate day 1) — exceeds @Size(max=7) on the list itself.
        // MasterController is @Validated, so the violation surfaces as a ConstraintViolationException
        // keyed by the leaf parameter name "requests" (GlobalExceptionHandler.handleConstraintViolation).
        var eightEntries = List.of(
                new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                new WorkingHoursRequest(2, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                new WorkingHoursRequest(3, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                new WorkingHoursRequest(4, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                new WorkingHoursRequest(5, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                new WorkingHoursRequest(6, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                new WorkingHoursRequest(7, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                new WorkingHoursRequest(1, LocalTime.of(10, 0), LocalTime.of(18, 0), true));
        String body = objectMapper.writeValueAsString(eightEntries);

        log.debug("Act: PATCH {}/{}/working-hours with 8 entries — readable @Size(max=7) message must surface",
                MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.requests")
                        .value("At most 7 working-hours entries (one per weekday) are allowed"));

        verify(masterService, never()).upsertWorkingHours(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 when startTime is after endTime")
    void should_return400_when_workingHoursStartTimeIsAfterEndTime() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // startTime 17:00 > endTime 09:00 — must fail @AssertTrue isTimeRangeValid
        String body = "[{\"dayOfWeek\":1,\"startTime\":\"17:00\",\"endTime\":\"09:00\",\"isActive\":true}]";
        log.debug("Act: PATCH {}/{}/working-hours with startTime after endTime", MASTERS_URL, masterId);

        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 success:false when service throws duplicate-day BusinessException (descriptive text NOT leaked) (regression)")
    void should_return400Generic_when_duplicateDayBusinessExceptionThrown() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // Two valid entries (within @Size(max=7)) that share dayOfWeek=1 — the size check passes,
        // so the request reaches MasterService, whose duplicate-day guard throws
        // BusinessException(BAD_REQUEST, "Duplicate working-hours entry for the same day").
        // GlobalExceptionHandler.handleBusiness rewrites BAD_REQUEST to the generic "Invalid request";
        // the descriptive message must NOT appear on the wire (it is debug-logged only).
        var duplicateDayPayload = List.of(
                new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(13, 0), true),
                new WorkingHoursRequest(1, LocalTime.of(14, 0), LocalTime.of(18, 0), true));
        String body = objectMapper.writeValueAsString(duplicateDayPayload);

        when(masterService.upsertWorkingHours(eq(userId), eq(masterId), any()))
                .thenThrow(new BusinessException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Duplicate working-hours entry for the same day"));

        log.debug("Act: PATCH {}/{}/working-hours with two day=1 entries — must surface a generic 400",
                MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid request"))
                // The descriptive guard text must never reach the client body.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Duplicate"))));
    }

    // ── POST /{masterId}/schedule-exceptions ──────────────────────────────────

    @Test
    @DisplayName("POST /{masterId}/schedule-exceptions — 400 when required 'date' field is absent")
    void should_return400_when_missingDateInScheduleExceptionRequest() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // date is @NotNull — omitting it must fail Bean Validation with 400
        String body = "{\"reason\":\"HOLIDAY\"}";

        log.debug("Act: POST {}/{}/schedule-exceptions with missing 'date' field — must be rejected with 400",
                MASTERS_URL, masterId);
        mockMvc.perform(post(MASTERS_URL + "/" + masterId + "/schedule-exceptions")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{masterId}/schedule-exceptions — 400 when date is in the past")
    void should_return400_when_scheduleExceptionDateIsInThePast() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // date in 2020 — clearly in the past, fails @FutureOrPresent
        String body = "{\"date\":\"2020-01-01\",\"reason\":\"HOLIDAY\"}";
        log.debug("Act: POST {}/{}/schedule-exceptions with past date 2020-01-01", MASTERS_URL, masterId);

        mockMvc.perform(post(MASTERS_URL + "/" + masterId + "/schedule-exceptions")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── DELETE /{masterId} ────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /{masterId} — 204 when salon owner deactivates their master")
    void should_return204_when_authorizedActorDeactivatesMaster() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        when(authorizationService.canManageMaster(any(), eq(masterId))).thenReturn(true);
        doNothing().when(masterService).deactivateMaster(eq(ownerUserId), eq(masterId));

        log.debug("Act: DELETE {}/{} as SALON_OWNER", MASTERS_URL, masterId);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId)
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /{masterId} — 403 when CLIENT attempts to deactivate a master")
    void should_return403_when_clientAttemptsToDeactivateMaster() throws Exception {
        var clientUserId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        when(authorizationService.canManageMaster(any(), eq(masterId))).thenReturn(false);

        log.debug("Act: DELETE {}/{} as CLIENT — must be denied with 403", MASTERS_URL, masterId);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId)
                        .with(authenticatedAs(clientUserId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /{masterId} — 403 when SALON_OWNER targets a master they do not own (IDOR)")
    void should_return403_when_salonOwnerDeletesForeignMaster() throws Exception {
        var foreignOwnerId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        when(authorizationService.canManageMaster(any(), eq(masterId))).thenReturn(false);

        log.debug("Act: DELETE {}/{} as SALON_OWNER without ownership — must be 403", MASTERS_URL, masterId);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId)
                        .with(authenticatedAs(foreignOwnerId, "other@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /{masterId}/schedule-exceptions/{date} ─────────────────────────

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 204 when owner removes exception")
    void should_return204_when_ownerRemovesScheduleException() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var exceptionDate = LocalDate.of(2026, 6, 1);
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);
        doNothing().when(masterService).removeScheduleException(eq(userId), eq(masterId), eq(exceptionDate));

        log.debug("Act: DELETE {}/{}/schedule-exceptions/{} as INDEPENDENT_MASTER", MASTERS_URL, masterId, exceptionDate);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId + "/schedule-exceptions/" + exceptionDate)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 401 when no Authorization header")
    void should_return401_when_noTokenOnDeleteScheduleException() throws Exception {
        var masterId = UUID.randomUUID();

        mockMvc.perform(delete(MASTERS_URL + "/" + masterId + "/schedule-exceptions/2026-06-01")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 403 when SALON_MASTER calls endpoint")
    void should_return403_when_salonMasterDeletesScheduleException() throws Exception {
        var salonMasterUserId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(false);

        log.debug("Act: DELETE {}/{}/schedule-exceptions/2026-06-01 as SALON_MASTER — must be denied", MASTERS_URL, masterId);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId + "/schedule-exceptions/2026-06-01")
                        .with(authenticatedAs(salonMasterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── GET /{masterId}/slots — requires authentication ───────────────────────

    @Test
    @DisplayName("GET /{masterId}/slots — 200 when authenticated client requests available slots")
    void should_return200_when_authenticatedGetAvailableSlots() throws Exception {
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var clientId = UUID.randomUUID();
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(), eq(serviceId)))
                .thenReturn(List.of());

        log.debug("Act: GET {}/{}/slots with CLIENT credentials — must return 200", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId + "/slots")
                        .param("date", "2027-01-15")
                        .param("serviceId", serviceId.toString())
                        .accept(MediaType.APPLICATION_JSON)
                        .with(authenticatedAs(clientId, "client@test.com", Role.CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").exists())
                .andExpect(jsonPath("$.data.slots").exists());
    }

    @Test
    @DisplayName("GET /{masterId}/slots — 401 when unauthenticated request")
    void should_return401_when_unauthenticatedGetAvailableSlots() throws Exception {
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();

        log.debug("Act: GET {}/{}/slots without credentials — must return 401", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId + "/slots")
                        .param("date", "2027-01-15")
                        .param("serviceId", serviceId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /{masterId}/slots — 400 when required 'date' param is absent")
    void should_return400_when_slotsMissingDateParam() throws Exception {
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var clientId = UUID.randomUUID();

        log.debug("Act: GET {}/{}/slots without 'date' param — must be rejected with 400", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId + "/slots")
                        .param("serviceId", serviceId.toString())
                        .accept(MediaType.APPLICATION_JSON)
                        .with(authenticatedAs(clientId, "client@test.com", Role.CLIENT)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /{masterId}/slots — 200 with non-empty slots list when service returns one slot")
    void should_return200WithNonEmptySlots_when_slotsRequestedWithValidParams() throws Exception {
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var clientId = UUID.randomUUID();

        var futureDate = java.time.ZonedDateTime.now().plusDays(1);
        var stubSlot = new com.beautica.booking.dto.AvailableSlotResponse(futureDate, futureDate.plusHours(1));
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(), eq(serviceId)))
                .thenReturn(List.of(stubSlot));

        log.debug("Act: GET {}/{}/slots with valid params — must return 200 with one slot", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId + "/slots")
                        .param("date", "2027-06-15")
                        .param("serviceId", serviceId.toString())
                        .accept(MediaType.APPLICATION_JSON)
                        .with(authenticatedAs(clientId, "client@test.com", Role.CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2027-06-15"))
                .andExpect(jsonPath("$.data.slots").isArray())
                .andExpect(jsonPath("$.data.slots[0].startsAt").exists());
    }

    @Test
    @DisplayName("GET /{masterId}/slots — 400 when service rejects a past date")
    void should_return400_when_dateIsInThePast() throws Exception {
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var clientId = UUID.randomUUID();

        when(slotCalculationService.getAvailableSlots(eq(masterId), any(), eq(serviceId)))
                .thenThrow(new BusinessException("date is in the past"));

        log.debug("Act: GET {}/{}/slots with a past date — must be rejected with 400", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId + "/slots")
                        .param("date", "2020-01-01")
                        .param("serviceId", serviceId.toString())
                        .accept(MediaType.APPLICATION_JSON)
                        .with(authenticatedAs(clientId, "client@test.com", Role.CLIENT)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /me/calendar — protected ─────────────────────────────────────────

    @Test
    @DisplayName("GET /me/calendar — 200 when SALON_MASTER requests valid date range")
    void should_return200_when_validCalendarRequest() throws Exception {
        var masterUserId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        Master master = Master.builder().masterType(MasterType.SALON_MASTER).isActive(true).build();
        ReflectionTestUtils.setField(master, "id", masterId);
        when(masterService.getMasterByUserId(any())).thenReturn(master);
        when(masterService.getMasterCalendar(any(), any(), any(), any())).thenReturn(Page.empty());

        log.debug("Act: GET {}/me/calendar as SALON_MASTER with valid range", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", "2027-01-01")
                        .param("to", "2027-01-15")
                        .with(authenticatedAs(masterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /me/calendar — 400 when date range exceeds 31 days")
    void should_return400_when_calendarRangeExceeds31Days() throws Exception {
        var masterUserId = UUID.randomUUID();

        log.debug("Act: GET {}/me/calendar with range > 31 days — must be rejected with 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", "2027-01-01")
                        .param("to", "2027-03-01")
                        .with(authenticatedAs(masterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /me/calendar — 400 when 'from' is after 'to'")
    void should_return400_when_calendarFromIsAfterTo() throws Exception {
        var masterUserId = UUID.randomUUID();

        log.debug("Act: GET {}/me/calendar with from > to — must be rejected with 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", "2027-06-01")
                        .param("to", "2027-01-01")
                        .with(authenticatedAs(masterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /me/calendar — 401 when no Authorization header")
    void should_return401_when_noTokenOnGetCalendar() throws Exception {
        log.debug("Act: GET {}/me/calendar without credentials — must be rejected with 401", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", "2027-01-01")
                        .param("to", "2027-01-15")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me/calendar — 403 when CLIENT requests master calendar")
    void should_return403_when_clientRequestsCalendar() throws Exception {
        var clientUserId = UUID.randomUUID();

        log.debug("Act: GET {}/me/calendar as CLIENT — must be denied with 403", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", "2027-01-01")
                        .param("to", "2027-01-15")
                        .with(authenticatedAs(clientUserId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ── D2 — calendar date bounds ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /me/calendar — 400 when 'from' is more than 1 year in the past")
    void should_return400_when_calendarFromIsTooFarInThePast() throws Exception {
        var masterUserId = UUID.randomUUID();
        // 2 years ago is beyond the 1-year-past limit
        String from = LocalDate.now().minusYears(2).toString();
        String to   = LocalDate.now().minusYears(2).plusDays(7).toString();

        log.debug("Act: GET {}/me/calendar with from={} more than 1 year in the past — must be 400",
                MASTERS_URL, from);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", from)
                        .param("to", to)
                        .with(authenticatedAs(masterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /me/calendar — 400 when 'to' is more than 2 years in the future")
    void should_return400_when_calendarToIsTooFarInTheFuture() throws Exception {
        var masterUserId = UUID.randomUUID();
        // 3 years ahead is beyond the 2-year-future limit
        String from = LocalDate.now().plusYears(3).toString();
        String to   = LocalDate.now().plusYears(3).plusDays(7).toString();

        log.debug("Act: GET {}/me/calendar with to={} more than 2 years in the future — must be 400",
                MASTERS_URL, to);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", from)
                        .param("to", to)
                        .with(authenticatedAs(masterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── E2 — GET /me/calendar — 404 when no master record ────────────────────

    @Test
    @DisplayName("GET /me/calendar — 404 when getMasterByUserId throws NotFoundException")
    void should_return404_when_getMasterByUserIdThrowsNotFoundException() throws Exception {
        var masterUserId = UUID.randomUUID();
        when(masterService.getMasterByUserId(any()))
                .thenThrow(new NotFoundException("Master not found"));

        log.debug("Act: GET {}/me/calendar when masterService.getMasterByUserId throws NotFoundException",
                MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL + "/me/calendar")
                        .param("from", "2027-01-01")
                        .param("to", "2027-01-15")
                        .with(authenticatedAs(masterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
