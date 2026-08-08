package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.service.MasterServiceFavoriteDecorator;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.dto.UpdateServicePhotoRequest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link ServiceController}.
 *
 * <p>Migrated from {@code @SpringBootTest(RANDOM_PORT)} + Testcontainers. All
 * collaborators ({@link ServiceCatalogService}, {@link AuthorizationService}) are
 * {@code @MockBean}s; no real database is required.
 */
@WebMvcTest(ServiceController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("ServiceController — @WebMvcTest slice")
class ServiceControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceControllerTest.class);

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
                            .requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}/services").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/services").permitAll()
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
    private ServiceCatalogService serviceCatalogService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private MasterServiceFavoriteDecorator masterServiceFavoriteDecorator;

    /**
     * Default passthrough stub for every test that does not care about {@code isFavorite}
     * decoration — the vast majority of the {@code GET /masters/{id}/services} tests below
     * predate Phase 32.1 and assert on fields the decorator never touches. Individual tests that
     * DO care about decoration override this stub explicitly.
     */
    @BeforeEach
    void stubFavoriteDecoratorAsPassthrough() {
        when(masterServiceFavoriteDecorator.decorate(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private ServiceDefinitionResponse stubServiceDefResponse(UUID id, String name) {
        return new ServiceDefinitionResponse(id, name, null, null, 60, 10, true, null, null, null, null,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴");
    }

    private MasterServiceResponse stubMasterServiceResponse(UUID id, UUID masterId, String name) {
        var sdResponse = stubServiceDefResponse(UUID.randomUUID(), name);
        return new MasterServiceResponse(id, masterId, sdResponse,
                null, null, new BigDecimal("350.00"), 60, true,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴",
                null, null, null, null);
    }

    /** Phase 16.4: variant carrying the lifted serviceTypeId + serviceTypeNameUk so the JSON shape can be asserted. */
    private MasterServiceResponse stubMasterServiceResponseWithType(
            UUID id, UUID masterId, String name, UUID serviceTypeId, String serviceTypeNameUk) {
        var sdResponse = new ServiceDefinitionResponse(
                UUID.randomUUID(), name, null, null, 60, 10, true,
                serviceTypeId, serviceTypeNameUk, null, null,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴");
        return new MasterServiceResponse(id, masterId, sdResponse,
                null, null, new BigDecimal("350.00"), 60, true,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴",
                serviceTypeId, serviceTypeNameUk, null, null);
    }

    /**
     * Variant carrying the lifted serviceTypeSlug on BOTH the top-level MasterServiceResponse
     * and its nested ServiceDefinitionResponse, so the read-path JSON shape can be asserted end-to-end.
     */
    private MasterServiceResponse stubMasterServiceResponseWithSlug(
            UUID id, UUID masterId, String name, UUID serviceTypeId, String serviceTypeNameUk, String serviceTypeSlug) {
        var sdResponse = new ServiceDefinitionResponse(
                UUID.randomUUID(), name, null, null, 60, 10, true,
                serviceTypeId, serviceTypeNameUk, serviceTypeSlug, null,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴");
        return new MasterServiceResponse(id, masterId, sdResponse,
                null, null, new BigDecimal("350.00"), 60, true,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴",
                serviceTypeId, serviceTypeNameUk, serviceTypeSlug, null);
    }

    /** Leaf ServiceDefinitionResponse carrying a serviceTypeSlug, for the salon-catalogue read path. */
    private ServiceDefinitionResponse stubServiceDefResponseWithSlug(
            UUID id, String name, UUID serviceTypeId, String serviceTypeNameUk, String serviceTypeSlug) {
        return new ServiceDefinitionResponse(id, name, null, "MANICURE", 60, 10, true,
                serviceTypeId, serviceTypeNameUk, serviceTypeSlug, null,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴");
    }

    // ── POST /api/v1/salons/{salonId}/services ─────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 201 when SALON_OWNER adds a service to their salon")
    void should_return201_when_ownerAddsServiceToSalon() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Classic Manicure", "Basic nail care", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, UUID.randomUUID());
        var stub = stubServiceDefResponse(serviceId, "Classic Manicure");

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(serviceCatalogService.addServiceToSalon(eq(salonId), any(CreateServiceDefinitionRequest.class)))
                .thenReturn(stub);

        log.debug("Act: POST /api/v1/salons/{}/services as SALON_OWNER", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(serviceId.toString()))
                .andExpect(jsonPath("$.data.name").value("Classic Manicure"));
    }

    // ── V111 mandatory service type — HTTP-tier guarantee ──────────────────────
    // The user-facing rule: a service can never be created without a service type. At the
    // web tier the @NotNull(serviceTypeId) bean-validation guard rejects the request with 400
    // before it reaches the service, on BOTH single-create entry points. Paired with the
    // service-layer "Service type is required" BusinessException (ServiceCatalogServiceTest)
    // and the DB NOT NULL constraint (V111), this pins the guarantee end-to-end.

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when serviceTypeId is omitted (mandatory since V111); service is never invoked")
    void should_return400_when_ownerCreatesSalonServiceWithoutServiceType() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        // Fully valid body EXCEPT the omitted serviceTypeId — isolates the mandatory-type guard.
        var body = "{\"name\":\"Classic Manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":10,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST /api/v1/salons/{}/services without a serviceTypeId — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.serviceTypeId").value("Service type id is required"));

        verify(serviceCatalogService, never()).addServiceToSalon(any(), any());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 403 when SALON_MASTER attempts to create a service (read-only role)")
    void should_return403_when_salonMasterAttemptsToCreateService() throws Exception {
        var masterUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        // SALON_MASTER is a read-only role: @PreAuthorize on the controller method denies the request
        // before AuthorizationService is consulted, so no stub is needed. The body is fully valid
        // (serviceTypeId present) so the 403 proves the ROLE gate — not bean validation — rejects it.
        log.debug("Act: POST /api/v1/salons/{}/services as SALON_MASTER — read-only role must be denied with 403", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(masterUserId, "salonmaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hair Cut\",\"category\":\"MANICURE\",\"serviceTypeId\":\"" + UUID.randomUUID()
                                + "\",\"baseDurationMinutes\":30,\"priceType\":\"FIXED\",\"price\":50.00,\"bufferMinutesAfter\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 403 when a different owner adds a service")
    void should_return403_when_nonOwnerAddsServiceToSalon() throws Exception {
        var ownerBUserId = UUID.randomUUID();
        var salonAId = UUID.randomUUID();
        // authz denies — different owner
        when(authorizationService.canManageSalon(any(), eq(salonAId))).thenReturn(false);

        // Valid body (serviceTypeId present) so the 403 proves the cross-owner canManageSalon
        // guard denies — not bean validation on a missing service type.
        var request = new CreateServiceDefinitionRequest(
                "Hijack Service", null, "MANICURE", 30, 0,
                PriceType.FIXED, new BigDecimal("100.00"), null, null, UUID.randomUUID());

        log.debug("Act: POST /api/v1/salons/{}/services with Owner B token — cross-owner must be denied", salonAId);
        mockMvc.perform(post("/api/v1/salons/" + salonAId + "/services")
                        .with(authenticatedAs(ownerBUserId, "ownerb@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when baseDurationMinutes is zero")
    void should_return400_when_durationZeroOrNegative() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        // baseDurationMinutes = 0 violates @Positive constraint
        String invalidBody = "{\"name\":\"Bad Service\",\"baseDurationMinutes\":0,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with baseDurationMinutes=0 — must be rejected with 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/salons/{salonId}/masters/{masterId}/services ──────────────

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services — 201 when owner assigns service to master")
    void should_return201_when_ownerAssignsServiceToMaster() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var assignmentId = UUID.randomUUID();
        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);
        var stub = stubMasterServiceResponse(assignmentId, masterId, "Pedicure");

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(masterId, salonId)).thenReturn(true);
        when(serviceCatalogService.assignServiceToMaster(eq(salonId), eq(masterId), any(AssignServiceToMasterRequest.class)))
                .thenReturn(stub);

        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services — assign service to master", salonId, masterId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.masterId").value(masterId.toString()));
    }

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services — 409 when same service assigned twice")
    void should_return409_when_duplicateAssignment() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(masterId, salonId)).thenReturn(true);
        when(serviceCatalogService.assignServiceToMaster(eq(salonId), eq(masterId), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Already assigned"));

        log.debug("Act: POST same assignment twice — second call must return 409");
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // ── GET /api/v1/masters/{masterId}/services — public ──────────────────────

    @Test
    @DisplayName("GET /masters/{id}/services — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetMasterServices() throws Exception {
        var masterId = UUID.randomUUID();
        var stub = List.of(stubMasterServiceResponse(UUID.randomUUID(), masterId, "Gel Nails"));
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(stub);

        log.debug("Act: GET /api/v1/masters/{}/services without credentials — public endpoint", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("GET /masters/{masterId}/services — 200 when page/size params sent (Pageable removed)")
    void should_return200_when_paginationParamsSentToNonPageableEndpoint() throws Exception {
        var masterId = UUID.randomUUID();
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .param("page", "0")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ── Phase 32.1: isFavorite decoration — the CONTROLLER composes the decorator AFTER the
    // cached call; here the decorator itself is a mock, so these tests pin only the WIRING
    // (the controller passes the cached list + Authentication through and returns whatever the
    // decorator hands back), never the decoration logic itself — that belongs to
    // MasterServiceFavoriteDecoratorTest. ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{id}/services — 200 anonymous caller: isFavorite stays null on every row "
            + "(the decorator's own null-for-anonymous behaviour, exercised through the default passthrough stub)")
    void should_return200WithNullFlags_when_anonymousBrowsesMasterServices() throws Exception {
        var masterId = UUID.randomUUID();
        var stub = List.of(stubMasterServiceResponse(UUID.randomUUID(), masterId, "Gel Nails"));
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(stub);
        // Relies on the class-wide @BeforeEach passthrough stub — an anonymous caller must see
        // exactly the cached (all-null) shape the controller received, untouched.

        log.debug("Act: GET /api/v1/masters/{}/services anonymously — isFavorite must be null", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isFavorite").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GET /masters/{id}/services — 200 CLIENT caller: the controller returns exactly what "
            + "the decorator hands back (true/false per row), proving the decorate() call is wired in "
            + "with the request's Authentication")
    void should_return200WithFlags_when_clientBrowsesMasterServices() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var rowId = UUID.randomUUID();
        var cachedStub = List.of(stubMasterServiceResponse(rowId, masterId, "Gel Nails"));
        var decoratedStub = List.of(cachedStub.get(0).withIsFavorite(true));
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(cachedStub);
        when(masterServiceFavoriteDecorator.decorate(eq(cachedStub), any())).thenReturn(decoratedStub);

        log.debug("Act: GET /api/v1/masters/{}/services as CLIENT — isFavorite must reflect the decorator's output", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isFavorite").value(true));

        verify(masterServiceFavoriteDecorator).decorate(eq(cachedStub), any());
    }

    @Test
    @DisplayName("GET /masters/{id}/services — 200 CLIENT caller, row NOT favourited: the wire carries "
            + "the literal JSON boolean false, never absent/null — pins the null-vs-false distinction "
            + "on the DESERIALIZED response body, not just on the Java DTO (a Jackson NON_NULL/coercion "
            + "bug on this field would collapse false to the same wire shape as null and slip past a "
            + "Java-object-only assertion)")
    void should_return200WithFalseFlag_when_clientBrowsesMasterServices_notFavorited() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var rowId = UUID.randomUUID();
        var cachedStub = List.of(stubMasterServiceResponse(rowId, masterId, "Gel Nails"));
        var decoratedStub = List.of(cachedStub.get(0).withIsFavorite(false));
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(cachedStub);
        when(masterServiceFavoriteDecorator.decorate(eq(cachedStub), any())).thenReturn(decoratedStub);

        log.debug("Act: GET /api/v1/masters/{}/services as CLIENT who has NOT favourited this row", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .with(authenticatedAs(userId, "client-nofav@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isFavorite").value(false))
                .andExpect(jsonPath("$.data[0].isFavorite").isBoolean());

        verify(masterServiceFavoriteDecorator).decorate(eq(cachedStub), any());
    }

    @Test
    @DisplayName("GET /masters/{masterId}/services — 404 when master does not exist")
    void should_return404_when_masterDoesNotExist() throws Exception {
        var unknownMasterId = UUID.randomUUID();
        when(serviceCatalogService.getMasterServices(unknownMasterId))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "Master not found"));

        log.debug("Act: GET /api/v1/masters/{}/services with unknown masterId — must return 404", unknownMasterId);
        mockMvc.perform(get("/api/v1/masters/" + unknownMasterId + "/services")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /masters/{id}/services — 200 read-path JSON carries serviceTypeSlug on the row and the nested definition when a type is linked")
    void should_emitServiceTypeSlug_when_masterServicesReadPathHasType() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();
        var stub = List.of(stubMasterServiceResponseWithSlug(
                rowId, masterId, "Manicure", serviceTypeId, "Манікюр", "manicure"));
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(stub);

        log.debug("Act: GET /api/v1/masters/{}/services — read-path JSON must expose serviceTypeSlug (populated)", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].serviceTypeSlug").value("manicure"))
                .andExpect(jsonPath("$.data[0].serviceDefinition.serviceTypeSlug").value("manicure"));
    }

    @Test
    @DisplayName("GET /masters/{id}/services — 200 read-path JSON has null serviceTypeSlug when no type is linked (nullable-link case)")
    void should_emitNullServiceTypeSlug_when_masterServicesReadPathHasNoType() throws Exception {
        UUID masterId = UUID.randomUUID();
        // stubMasterServiceResponse leaves all serviceType fields null — the picker was not used.
        var stub = List.of(stubMasterServiceResponse(UUID.randomUUID(), masterId, "Gel Nails"));
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(stub);

        log.debug("Act: GET /api/v1/masters/{}/services — read-path JSON serviceTypeSlug must be null", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].serviceTypeSlug").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].serviceDefinition.serviceTypeSlug").value(org.hamcrest.Matchers.nullValue()));
    }

    // ── GET /api/v1/salons/{salonId}/services — public salon catalogue ─────────

    @Test
    @DisplayName("GET /salons/{id}/services — 200 salon-catalogue leaf JSON carries serviceTypeSlug when a type is linked")
    void should_emitServiceTypeSlug_when_salonCatalogueLeafHasType() throws Exception {
        UUID salonId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();
        var leaf = stubServiceDefResponseWithSlug(
                UUID.randomUUID(), "Manicure", serviceTypeId, "Манікюр", "manicure");
        var catalogue = new SalonServiceCatalogResponse(List.of(
                new SalonServiceCategoryGroup("MANICURE", "Манікюр", 1, List.of(leaf))));
        when(serviceCatalogService.getSalonServiceCatalog(salonId)).thenReturn(catalogue);

        log.debug("Act: GET /api/v1/salons/{}/services — salon-catalogue leaf JSON must expose serviceTypeSlug", salonId);
        mockMvc.perform(get("/api/v1/salons/" + salonId + "/services")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.categories[0].services[0].serviceTypeSlug").value("manicure"));
    }

    // ── POST /api/v1/independent-masters/me/services ───────────────────────────

    @Test
    @DisplayName("POST /independent-masters/me/services — 201 when INDEPENDENT_MASTER adds a service")
    void should_return201_when_independentMasterAddsOwnService() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Lash Extensions", "Volume set", "MANICURE", 120, 15,
                PriceType.FIXED, new BigDecimal("900.00"), null, null, UUID.randomUUID());
        var stub = stubMasterServiceResponse(UUID.randomUUID(), masterId, "Lash Extensions");

        when(serviceCatalogService.addIndependentMasterService(eq(userId), any(CreateServiceDefinitionRequest.class)))
                .thenReturn(stub);

        log.debug("Act: POST /api/v1/independent-masters/me/services as INDEPENDENT_MASTER");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serviceDefinition.name").value("Lash Extensions"));
    }

    // ── Phase 16.4: serviceTypeId + serviceTypeNameUk on the create-response JSON ──────

    @Test
    @DisplayName("POST /independent-masters/me/services — 201 response JSON carries serviceTypeId + serviceTypeNameUk when a type is chosen")
    void should_returnServiceTypeFieldsInJson_when_independentMasterCreatesWithServiceType() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceTypeId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);
        var stub = stubMasterServiceResponseWithType(
                UUID.randomUUID(), masterId, "Manicure", serviceTypeId, "Манікюр");

        when(serviceCatalogService.addIndependentMasterService(eq(userId), any(CreateServiceDefinitionRequest.class)))
                .thenReturn(stub);

        log.debug("Act: POST /api/v1/independent-masters/me/services with a serviceTypeId — response must echo serviceTypeNameUk");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serviceTypeId").value(serviceTypeId.toString()))
                .andExpect(jsonPath("$.data.serviceTypeNameUk").value("Манікюр"));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 400 when serviceTypeId is omitted (mandatory since V111); service is never invoked")
    void should_return400_when_independentMasterCreatesWithoutServiceType() throws Exception {
        var userId = UUID.randomUUID();
        // Body omits serviceTypeId entirely — the @NotNull bean-validation guard rejects it at
        // argument resolution, so the request never reaches the service. This is the user-facing
        // guarantee that an untyped service cannot be created via the independent-master path.
        var body = "{\"name\":\"Manicure\",\"description\":\"Classic manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":10,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST /api/v1/independent-masters/me/services without a serviceTypeId — must return 400");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.serviceTypeId").value("Service type id is required"));

        verify(serviceCatalogService, never()).addIndependentMasterService(any(), any());
    }

    // ── Phase 16.3 cross-field reject — HTTP envelope (16.3 + GlobalExceptionHandler seam) ──
    // The service throws BusinessException(400, "service type does not belong to the selected
    // category") when a present serviceTypeId's platform category differs from the request
    // category. ServiceCatalogServiceTest covers the THROW at the service layer; these two
    // pin that GlobalExceptionHandler renders it as the standard {success:false, message:...}
    // ApiResponse with HTTP 400 across BOTH create entry points (salon owner + independent
    // master) — the web-slice envelope the mobile ErrorMapperInterceptor renders. Backlog had
    // flagged 16.3 as having no web-slice for the 400 envelope.

    @Test
    @DisplayName("POST /salons/{id}/services — 400 ApiResponse envelope when serviceTypeId belongs to a different category (16.3)")
    void should_return400Envelope_when_serviceTypeCategoryMismatch_salonPath() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var serviceTypeId = UUID.randomUUID();
        // Request selects MANICURE but the chosen serviceTypeId belongs to HAIRDRESSING.
        var request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(serviceCatalogService.addServiceToSalon(eq(salonId), any(CreateServiceDefinitionRequest.class)))
                .thenThrow(new BusinessException(
                        HttpStatus.BAD_REQUEST, "service type does not belong to the selected category"));

        log.debug("Act: POST /api/v1/salons/{}/services with a cross-category serviceTypeId — expect 400 envelope", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                // GlobalExceptionHandler REDACTS the BAD_REQUEST message to a generic string
                // (B3 hardening — never echo the raw reason which could leak the taxonomy model).
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 400 ApiResponse envelope when serviceTypeId belongs to a different category (16.3)")
    void should_return400Envelope_when_serviceTypeCategoryMismatch_independentMasterPath() throws Exception {
        var userId = UUID.randomUUID();
        var serviceTypeId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Pedicure", "Basic pedicure", "PEDICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("400.00"), null, null, serviceTypeId);

        when(serviceCatalogService.addIndependentMasterService(eq(userId), any(CreateServiceDefinitionRequest.class)))
                .thenThrow(new BusinessException(
                        HttpStatus.BAD_REQUEST, "service type does not belong to the selected category"));

        log.debug("Act: POST /api/v1/independent-masters/me/services with a cross-category serviceTypeId — expect 400 envelope");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 403 when CLIENT attempts the call")
    void should_return403_when_clientAddsIndependentMasterService() throws Exception {
        var userId = UUID.randomUUID();
        // Valid body (serviceTypeId present) so the 403 proves the INDEPENDENT_MASTER role gate
        // denies a CLIENT — not bean validation on a missing service type.
        var request = new CreateServiceDefinitionRequest(
                "Sneaky Service", null, "MANICURE", 30, 0,
                PriceType.FIXED, new BigDecimal("100.00"), null, null, UUID.randomUUID());

        log.debug("Act: POST /api/v1/independent-masters/me/services as CLIENT — must be denied");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/v1/independent-masters/me/services — owner's active services ──

    @Test
    @DisplayName("GET /independent-masters/me/services — 200 and returns the master's active services")
    void should_returnMyServices_when_authenticatedIndependentMaster() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();

        var first = stubMasterServiceResponse(firstId, masterId, "Gel Manicure");
        var second = stubMasterServiceResponse(secondId, masterId, "Classic Pedicure");

        when(serviceCatalogService.getMyServices(userId)).thenReturn(List.of(first, second));

        log.debug("Act: GET /api/v1/independent-masters/me/services as INDEPENDENT_MASTER — returns active services");
        mockMvc.perform(get("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(firstId.toString()))
                .andExpect(jsonPath("$.data[0].isActive").value(true))
                .andExpect(jsonPath("$.data[1].id").value(secondId.toString()))
                .andExpect(jsonPath("$.data[1].isActive").value(true));
    }

    @Test
    @DisplayName("GET /independent-masters/me/services — 401 when no Authorization header is present")
    void should_return401_when_unauthenticated() throws Exception {
        log.debug("Act: GET /api/v1/independent-masters/me/services without credentials — must return 401");
        mockMvc.perform(get("/api/v1/independent-masters/me/services")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .getMyServices(any());
    }

    @Test
    @DisplayName("GET /independent-masters/me/services — 403 when CLIENT attempts the call (role gate)")
    void should_return403_when_wrongRole() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: GET /api/v1/independent-masters/me/services as CLIENT — must be denied with 403");
        mockMvc.perform(get("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .getMyServices(any());
    }

    // ── DELETE /api/v1/services/{serviceDefId} ─────────────────────────────────

    @Test
    @DisplayName("DELETE /services/{id} — 204 when SALON_OWNER passes the role gate; controller forwards its userId as actorId to the service")
    void should_return204_when_ownerDeactivatesService() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        // §D split: the DELETE gate is role-only (hasAnyRole SALON_OWNER/INDEPENDENT_MASTER).
        // Ownership is enforced inside the service (B14), not via canManageServiceDefinition SpEL.
        // deactivateServiceDefinition is void — default Mockito no-op = success.

        log.debug("Act: DELETE /api/v1/services/{} as SALON_OWNER — must return 204", serviceDefId);
        mockMvc.perform(delete("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Controller must pass the authenticated principal's userId as the actorId (B14 split).
        verify(serviceCatalogService).deactivateServiceDefinition(userId, serviceDefId);
    }

    // ── Validation boundary tests ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when baseDurationMinutes exceeds 480")
    void should_return400_when_baseDurationMinutesExceedsMax() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Too Long\",\"baseDurationMinutes\":481,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with baseDurationMinutes=481 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when bufferMinutesAfter exceeds 120")
    void should_return400_when_bufferMinutesAfterExceedsMax() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Buffer Service\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":121}";

        log.debug("Act: POST /api/v1/salons/{}/services with bufferMinutesAfter=121 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when price exceeds DecimalMax(99999999.99)")
    void should_return400_when_basePriceExceedsDecimalMax() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Expensive Service\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,\"priceType\":\"FIXED\",\"price\":100000000.00}";

        log.debug("Act: POST /api/v1/salons/{}/services with price=100000000.00 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when price has 3 decimal places (violates @Digits(fraction=2))")
    void should_return400_when_basePriceHasThreeDecimalPlaces() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Fraction Service\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,\"priceType\":\"FIXED\",\"price\":123.456}";

        log.debug("Act: POST /api/v1/salons/{}/services with price=123.456 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    // ── MEDIUM-2: RANGE pricing HTTP contract ─────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 201 when owner creates a RANGE service and response carries RANGE fields")
    void should_return201AndRangeResponse_when_ownerCreatesRangeService() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();

        // Stub: service layer returns a RANGE ServiceDefinitionResponse
        var rangeStub = new ServiceDefinitionResponse(
                serviceId, "Range Manicure", null, "MANICURE", 60, 0, true,
                null, null, null, null,
                PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.00"),
                "від 500 до 800 ₴");

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(serviceCatalogService.addServiceToSalon(eq(salonId), any(CreateServiceDefinitionRequest.class)))
                .thenReturn(rangeStub);

        // RANGE request: priceType=RANGE, priceMin=500, priceMax=800; no price field.
        // serviceTypeId is mandatory (V111) so it must be present for a 201.
        String body = "{\"name\":\"Range Manicure\",\"category\":\"MANICURE\",\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"RANGE\",\"priceMin\":500.00,\"priceMax\":800.00}";

        log.debug("Act: POST /api/v1/salons/{}/services with RANGE pricing — must return 201 with RANGE fields", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.priceType").value("RANGE"))
                .andExpect(jsonPath("$.data.priceDisplay").value("від 500 до 800 ₴"));
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when RANGE priceMax is less than priceMin")
    void should_return400_when_rangeMaxLessThanMin() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        // priceMax (500) < priceMin (800) — ServicePriceValidator must reject this
        String invalidBody = "{\"name\":\"Bad Range\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"RANGE\",\"priceMin\":800.00,\"priceMax\":500.00}";

        log.debug("Act: POST /api/v1/salons/{}/services with priceMax < priceMin — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when RANGE request also contains fixed price field")
    void should_return400_when_rangeWithPriceAlsoSet() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        // RANGE + price set simultaneously — ServicePriceValidator must reject spurious price field
        String invalidBody = "{\"name\":\"Ambiguous Service\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"RANGE\",\"priceMin\":500.00,\"priceMax\":800.00,\"price\":350.00}";

        log.debug("Act: POST /api/v1/salons/{}/services with RANGE + price both set — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when name exceeds 255 characters")
    void should_return400_when_nameExceeds255Chars() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String longName = "a".repeat(256);
        String invalidBody = "{\"name\":\"" + longName + "\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with name.length=256 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    // ── 401 for unauthenticated POST requests ─────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 401 when no Authorization header is present")
    void should_return401_when_postSalonServiceWithoutAuth() throws Exception {
        var anyId = UUID.randomUUID();

        log.debug("Act: POST /api/v1/salons/{}/services without Authorization header — must return 401", anyId);
        mockMvc.perform(post("/api/v1/salons/" + anyId + "/services")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 401 when no Authorization header is present")
    void should_return401_when_postIndependentMasterServiceWithoutAuth() throws Exception {
        log.debug("Act: POST /api/v1/independent-masters/me/services without Authorization header — must return 401");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 401 when no Authorization header is present")
    void should_return401_when_deleteServiceWithoutAuth() throws Exception {
        var anyId = UUID.randomUUID();

        log.debug("Act: DELETE /api/v1/services/{} without Authorization header — must return 401", anyId);
        mockMvc.perform(delete("/api/v1/services/" + anyId)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when CLIENT role calls the delete endpoint (role-only gate denies)")
    void should_return403_when_clientCallsDeleteService() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        // §D split: the role-only @PreAuthorize(hasAnyRole SALON_OWNER/INDEPENDENT_MASTER) denies a
        // CLIENT before the controller body runs — the service method is never reached.

        log.debug("Act: DELETE /api/v1/services/{} as CLIENT — must be denied with 403", serviceDefId);
        mockMvc.perform(delete("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(serviceCatalogService, never()).deactivateServiceDefinition(any(), any());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when SALON_MASTER (read-only role) calls the delete endpoint (role-only gate denies)")
    void should_return403_when_salonMasterCallsDeleteService() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        log.debug("Act: DELETE /api/v1/services/{} as SALON_MASTER — read-only role must be denied with 403", serviceDefId);
        mockMvc.perform(delete("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "salonmaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(serviceCatalogService, never()).deactivateServiceDefinition(any(), any());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when SALON_ADMIN calls the delete endpoint (role-only gate excludes SALON_ADMIN)")
    void should_return403_when_salonAdminCallsDeleteService() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        // SALON_ADMIN is intentionally NOT in hasAnyRole('SALON_OWNER','INDEPENDENT_MASTER').

        log.debug("Act: DELETE /api/v1/services/{} as SALON_ADMIN — must be denied with 403", serviceDefId);
        mockMvc.perform(delete("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(serviceCatalogService, never()).deactivateServiceDefinition(any(), any());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 204 when INDEPENDENT_MASTER passes the role gate; service receives its userId as actorId")
    void should_return204_when_independentMasterDeletesOwnService() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        // Role gate passes for INDEPENDENT_MASTER; deactivateServiceDefinition is void (no-op = success).

        log.debug("Act: DELETE /api/v1/services/{} as INDEPENDENT_MASTER — must deactivate and return 204", serviceDefId);
        mockMvc.perform(delete("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(serviceCatalogService).deactivateServiceDefinition(userId, serviceDefId);
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when INDEPENDENT_MASTER targets a SALON-owned service (B14 service-layer guard throws ForbiddenException)")
    void should_return403_when_independentMasterDeletesSalonOwnedService() throws Exception {
        var imUserId = UUID.randomUUID();
        var salonOwnedServiceId = UUID.randomUUID();
        // §D split: the IM passes the role-only gate, then the service-layer B14 guard
        // (authz.enforceCanManageServiceDefinition) rejects the non-owner with ForbiddenException,
        // which GlobalExceptionHandler renders as 403.
        doThrow(new ForbiddenException("Access denied"))
                .when(serviceCatalogService).deactivateServiceDefinition(imUserId, salonOwnedServiceId);

        log.debug("Act: DELETE /api/v1/services/{} as INDEPENDENT_MASTER targeting salon-owned service — must return 403", salonOwnedServiceId);
        mockMvc.perform(delete("/api/v1/services/" + salonOwnedServiceId)
                        .with(authenticatedAs(imUserId, "im@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when authenticated owner targets a non-existent service definition (B14 guard treats unknown id as denied)")
    void should_return403_when_ownerDeletesNonExistentServiceDefinition() throws Exception {
        // §D split: owner passes the role-only gate; the service-layer B14 guard treats an unknown
        // serviceDefId as access-denied (no existence oracle), throwing ForbiddenException → 403.
        var userId = UUID.randomUUID();
        var nonExistentId = UUID.randomUUID();
        doThrow(new ForbiddenException("Access denied"))
                .when(serviceCatalogService).deactivateServiceDefinition(userId, nonExistentId);

        log.debug("Act: DELETE /api/v1/services/{} with valid owner token but missing service def — must return 403", nonExistentId);
        mockMvc.perform(delete("/api/v1/services/" + nonExistentId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── QA-MEDIUM: CreateServiceDefinitionRequest @Pattern — control-char guard

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when service name contains a control character (NUL byte)")
    void should_return400_when_serviceNameContainsControlCharacter() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        // NUL byte in name — @Pattern(^[^\p{Cntrl}]*$) must reject before the service is reached
        var body = "{\"name\":\"Manicure\\u0000\""
                + ",\"baseDurationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with name containing NUL byte — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .addServiceToSalon(any(), any());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when service description contains a banned C0 control char (NUL byte)")
    void should_return400_when_serviceDescriptionContainsControlCharacter() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        // Valid category is supplied so the @NotBlank category constraint does NOT fire — the ONLY
        // remaining violation is the description's embedded NUL byte, which the description @Pattern
        // ([^\x00-\x08\x0B\x0C\x0E-\x1F\x7F]*) must reject. This isolates the description rule:
        // previously the body omitted category, so the 400 fired for the wrong reason (missing
        // category) while a now-legal tab masqueraded as a control-char rejection.
        var body = "{\"name\":\"Manicure\",\"category\":\"MANICURE\""
                + ",\"description\":\"Good desc\\u0000bad\""
                + ",\"baseDurationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with valid category + NUL byte in description — must return 400 on description", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .addServiceToSalon(any(), any());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 201 when service description contains a tab + newline (long-form text now accepted)")
    void should_return201_when_serviceDescriptionContainsTabAndNewline() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(serviceCatalogService.addServiceToSalon(eq(salonId), any(CreateServiceDefinitionRequest.class)))
                .thenReturn(stubServiceDefResponse(serviceId, "Manicure"));
        // Tab (\t) and line breaks (\n) are now legal in description — long-form copy legitimately
        // contains them. Valid category + mandatory serviceTypeId supplied so the only field under
        // test is description.
        var body = "{\"name\":\"Manicure\",\"category\":\"MANICURE\",\"serviceTypeId\":\"" + UUID.randomUUID() + "\""
                + ",\"description\":\"Line one\\nLine two\\tindented\""
                + ",\"baseDurationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with tab + newline in description — must return 201 (now accepted)", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(serviceCatalogService).addServiceToSalon(eq(salonId), any());
    }

    // ── PATCH /api/v1/services/{serviceDefId} ──────────────────────────────────

    @Test
    @DisplayName("PATCH /services/{id} — 200 when owner updates name and price")
    void should_return200_when_ownerUpdatesServiceDefinition() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var request = new UpdateServiceDefinitionRequest(
                "Updated Manicure", null, null, null, null, PriceType.FIXED, new BigDecimal("400.00"), null, null, null);
        var stub = stubServiceDefResponse(serviceDefId, "Updated Manicure");

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);
        when(serviceCatalogService.updateServiceDefinition(eq(serviceDefId), any(UpdateServiceDefinitionRequest.class)))
                .thenReturn(stub);

        log.debug("Act: PATCH /api/v1/services/{} as SALON_OWNER — update name and price", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(serviceDefId.toString()))
                .andExpect(jsonPath("$.data.name").value("Updated Manicure"));
    }

    @Test
    @DisplayName("PATCH /services/{id} — 200 when owner updates only duration (partial PATCH)")
    void should_return200_when_ownerUpdatesOnlyDuration() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        // Only baseDurationMinutes is set — all other fields null (PATCH semantics)
        var request = new UpdateServiceDefinitionRequest(null, null, null, 90, null, null, null, null, null, null);
        var stub = stubServiceDefResponse(serviceDefId, "Manicure");

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);
        when(serviceCatalogService.updateServiceDefinition(eq(serviceDefId), any(UpdateServiceDefinitionRequest.class)))
                .thenReturn(stub);

        log.debug("Act: PATCH /api/v1/services/{} with only baseDurationMinutes — partial PATCH", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseDurationMinutes\":90}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PATCH /services/{id} — 403 when a different owner tries to update")
    void should_return403_when_nonOwnerUpdatesServiceDefinition() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(false);

        log.debug("Act: PATCH /api/v1/services/{} with wrong owner — must return 403", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "other@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijack\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /services/{id} — 403 when non-existent service definition (canManageServiceDefinition returns false)")
    void should_return403_when_patchingNonExistentServiceDefinition() throws Exception {
        var userId = UUID.randomUUID();
        var nonExistentId = UUID.randomUUID();

        // canManageServiceDefinition returns false when service definition does not exist
        // (findOwnerUserId returns empty → orElse(false)) — same behaviour as DELETE.
        when(authorizationService.canManageServiceDefinition(any(), eq(nonExistentId))).thenReturn(false);

        log.debug("Act: PATCH /api/v1/services/{} with valid token but missing service def — must return 403", nonExistentId);
        mockMvc.perform(patch("/api/v1/services/" + nonExistentId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ghost\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /services/{id} — 401 when no Authorization header is present")
    void should_return401_when_patchServiceWithoutAuth() throws Exception {
        var anyId = UUID.randomUUID();

        log.debug("Act: PATCH /api/v1/services/{} without Authorization header — must return 401", anyId);
        mockMvc.perform(patch("/api/v1/services/" + anyId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /services/{id} — 400 when baseDurationMinutes exceeds 480")
    void should_return400_when_patchDurationExceedsMax() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: PATCH /api/v1/services/{} with baseDurationMinutes=481 — must return 400", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseDurationMinutes\":481}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /services/{id} — 400 when price has 3 decimal places")
    void should_return400_when_patchPriceHasThreeDecimalPlaces() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: PATCH /api/v1/services/{} with price=123.456 — must return 400", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceType\":\"FIXED\",\"price\":123.456}"))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /api/v1/services/{serviceDefId}/photo ────────────────────────────

    @Test
    @DisplayName("PATCH /services/{id}/photo — 200 when owner sets photo URL")
    void should_return200_when_ownerSetsServicePhoto() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var photoUrl = "https://pub-abc123.r2.dev/services/photo.jpg";
        var stub = new ServiceDefinitionResponse(
                serviceDefId, "Manicure", null, null, 60, 10, true, null, null, null, photoUrl,
                PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴");

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);
        when(serviceCatalogService.updateServicePhoto(eq(serviceDefId), eq(photoUrl)))
                .thenReturn(stub);

        log.debug("Act: PATCH /api/v1/services/{}/photo as SALON_OWNER — set photo URL", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId + "/photo")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrl\":\"" + photoUrl + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.photoUrl").value(photoUrl));
    }

    @Test
    @DisplayName("PATCH /services/{id}/photo — 403 when a different owner tries to set photo")
    void should_return403_when_nonOwnerSetsServicePhoto() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(false);

        log.debug("Act: PATCH /api/v1/services/{}/photo with wrong owner — must return 403", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId + "/photo")
                        .with(authenticatedAs(userId, "other@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrl\":\"https://example.com/photo.jpg\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /services/{id}/photo — 400 when photoUrl uses plain HTTP (not HTTPS)")
    void should_return400_when_photoUrlIsNotHttps() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: PATCH /api/v1/services/{}/photo with http:// URL — @Pattern must reject with 400", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId + "/photo")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrl\":\"http://insecure.example.com/photo.jpg\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /services/{id}/photo — 400 when photoUrl is null")
    void should_return400_when_photoUrlIsNull() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: PATCH /api/v1/services/{}/photo with null photoUrl — @NotNull must reject with 400", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId + "/photo")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrl\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /services/{id}/photo — 401 when no Authorization header is present")
    void should_return401_when_patchPhotoWithoutAuth() throws Exception {
        var anyId = UUID.randomUUID();

        log.debug("Act: PATCH /api/v1/services/{}/photo without Authorization header — must return 401", anyId);
        mockMvc.perform(patch("/api/v1/services/" + anyId + "/photo")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrl\":\"https://example.com/photo.jpg\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── MEDIUM-2: empty-string name guard ─────────────────────────────────────

    @Test
    @DisplayName("PATCH /services/{id} — 400 when name is empty string (MEDIUM-2: @Size(min=1) guard)")
    void should_return400_when_nameIsEmptyString() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: PATCH /api/v1/services/{} with name=\"\" — @Size(min=1) must reject with 400", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── MEDIUM-3: tightened photo URL pattern ─────────────────────────────────

    @Test
    @DisplayName("PATCH /services/{id}/photo — 400 when photoUrl is 'https:// ' (bare scheme with space, MEDIUM-3)")
    void should_return400_when_photoUrlIsHttpsSpaceOnly() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: PATCH /api/v1/services/{}/photo with photoUrl='https:// ' — @Pattern must reject with 400", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId + "/photo")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrl\":\"https:// \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /services/{id}/photo — 400 when photoUrl has no host after scheme (MEDIUM-3)")
    void should_return400_when_photoUrlHasNoHost() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: PATCH /api/v1/services/{}/photo with photoUrl='https://' — @Pattern must reject with 400", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId + "/photo")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrl\":\"https://\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/independent-masters/me/services/bulk ──────────────────────
    //
    // Additive bulk create, self path. The @WebMvcTest slice pins the HTTP contract:
    // role gate (INDEPENDENT_MASTER), bean-validation of the batch envelope + per-item
    // price mode, and the GlobalExceptionHandler rendering of the service-layer 409.
    // The service-layer behaviour (derivation, all-or-nothing) is covered by
    // ServiceCatalogServiceBulkCreateTest; the DB rollback by BulkServiceSetupIntegrationTest.

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 201 with the created services list")
    void should_return201_when_independentMasterBulkCreates() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var request = new BulkCreateServicesRequest(List.of(
                new BulkServiceItemRequest(UUID.randomUUID(), 60, PriceType.FIXED, new BigDecimal("350.00"), null, null),
                new BulkServiceItemRequest(UUID.randomUUID(), 120, PriceType.RANGE, null, new BigDecimal("800.00"), new BigDecimal("1500.00"))));

        var created = List.of(
                stubMasterServiceResponse(firstId, masterId, "Манікюр"),
                stubMasterServiceResponse(secondId, masterId, "Фарбування"));
        when(serviceCatalogService.bulkCreateIndependentMasterServices(eq(userId), any(BulkCreateServicesRequest.class)))
                .thenReturn(created);

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk as INDEPENDENT_MASTER with a 2-item batch");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(firstId.toString()))
                .andExpect(jsonPath("$.data[1].id").value(secondId.toString()));
    }

    /**
     * Negative half of the bulk 409 contract, paired with
     * {@code should_return409WithDuplicateServiceCode_when_bulkItemDuplicatesExistingService}.
     * A handler that stamped {@code DUPLICATE_SERVICE} onto every 409 from this route would still
     * pass the positive test; only this one catches it. The setup screen branches on
     * {@code data.code}, so a codeless generic conflict must stay codeless.
     *
     * <p>This route previously had a second 409 source — a "master already has services"
     * precondition — which is exactly the ambiguity this test guarded. Bulk create is additive
     * now, so a generic conflict here is some other BusinessException; the envelope contract it
     * pins is unchanged.
     */
    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — a generic conflict 409 carries NO data.code")
    void should_return409WithoutCode_when_bulkConflictIsNotADuplicateService() throws Exception {
        var userId = UUID.randomUUID();
        var request = new BulkCreateServicesRequest(List.of(
                new BulkServiceItemRequest(UUID.randomUUID(), 60, PriceType.FIXED, new BigDecimal("350.00"), null, null)));

        when(serviceCatalogService.bulkCreateIndependentMasterServices(eq(userId), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Some other conflict"));

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk when the service raises an unrelated 409");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 400 when items list is empty (@NotEmpty)")
    void should_return400_when_bulkItemsEmpty() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk with empty items — @NotEmpty must reject with 400");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .bulkCreateIndependentMasterServices(any(), any());
    }

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 400 when items list exceeds 100 (@Size(max=100))")
    void should_return400_when_bulkItemsExceedMax() throws Exception {
        var userId = UUID.randomUUID();

        // Build 101 valid items so the ONLY violation is the @Size(max=100) cap.
        StringBuilder items = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) items.append(',');
            items.append("{\"serviceTypeId\":\"").append(UUID.randomUUID())
                    .append("\",\"durationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00}");
        }
        items.append("]}");

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk with 101 items — @Size(max=100) must reject with 400");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(items.toString()))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .bulkCreateIndependentMasterServices(any(), any());
    }

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 400 when a per-item RANGE has priceMax <= priceMin (@ServicePriceValid cascades through @Valid)")
    void should_return400_when_bulkItemRangeMaxNotGreaterThanMin() throws Exception {
        var userId = UUID.randomUUID();

        // Per-item @ServicePriceValid must fire through the list element @Valid cascade.
        var body = "{\"items\":[{\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"durationMinutes\":60,\"priceType\":\"RANGE\",\"priceMin\":800.00,\"priceMax\":500.00}]}";

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk with item priceMax<priceMin — must return 400");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .bulkCreateIndependentMasterServices(any(), any());
    }

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 400 when a per-item FIXED also carries priceMin/priceMax")
    void should_return400_when_bulkItemFixedAlsoCarriesRangeFields() throws Exception {
        var userId = UUID.randomUUID();

        var body = "{\"items\":[{\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"durationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00,\"priceMin\":300.00,\"priceMax\":400.00}]}";

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk with FIXED + range fields both set — must return 400");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 403 when a CLIENT calls the self bulk endpoint (role gate)")
    void should_return403_when_clientCallsSelfBulk() throws Exception {
        var userId = UUID.randomUUID();
        var body = "{\"items\":[{\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"durationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00}]}";

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk as CLIENT — must be denied with 403");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .bulkCreateIndependentMasterServices(any(), any());
    }

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 401 when no Authorization header is present")
    void should_return401_when_selfBulkWithoutAuth() throws Exception {
        var body = "{\"items\":[{\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"durationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00}]}";

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk without credentials — must return 401");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/salons/{salonId}/masters/{masterId}/services/bulk ─────────

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services/bulk — 201 when SALON_OWNER bulk-creates on behalf of their master")
    void should_return201_when_ownerBulkCreatesForMaster() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var request = new BulkCreateServicesRequest(List.of(
                new BulkServiceItemRequest(UUID.randomUUID(), 60, PriceType.FIXED, new BigDecimal("350.00"), null, null)));
        var created = List.of(stubMasterServiceResponse(UUID.randomUUID(), masterId, "Манікюр"));

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(masterId, salonId)).thenReturn(true);
        when(serviceCatalogService.bulkCreateSalonMasterServices(eq(salonId), eq(masterId), any()))
                .thenReturn(created);

        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services/bulk as SALON_OWNER", salonId, masterId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].masterId").value(masterId.toString()));
    }

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services/bulk — 201 when SALON_ADMIN bulk-creates on behalf (intentional canManageSalon broadening)")
    void should_return201_when_salonAdminBulkCreatesForMaster() throws Exception {
        var adminUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var request = new BulkCreateServicesRequest(List.of(
                new BulkServiceItemRequest(UUID.randomUUID(), 60, PriceType.FIXED, new BigDecimal("350.00"), null, null)));
        var created = List.of(stubMasterServiceResponse(UUID.randomUUID(), masterId, "Манікюр"));

        // The bulk on-behalf endpoint has NO hasRole gate — it relies on canManageSalon,
        // which the contract intentionally grants to SALON_ADMIN as well as SALON_OWNER.
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(masterId, salonId)).thenReturn(true);
        when(serviceCatalogService.bulkCreateSalonMasterServices(eq(salonId), eq(masterId), any()))
                .thenReturn(created);

        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services/bulk as SALON_ADMIN — must be allowed on-behalf", salonId, masterId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk")
                        .with(authenticatedAs(adminUserId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services/bulk — 403 when the master is not in the salon (masterBelongsToSalon IDOR guard)")
    void should_return403_when_bulkTargetsMasterInAnotherSalon() throws Exception {
        var userId = UUID.randomUUID();
        var salonAId = UUID.randomUUID();
        var masterInSalonBId = UUID.randomUUID();

        // Owner can manage salon A, but the target master belongs to salon B → guard denies.
        when(authorizationService.canManageSalon(any(), eq(salonAId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(masterInSalonBId, salonAId)).thenReturn(false);

        var body = "{\"items\":[{\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"durationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00}]}";

        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services/bulk targeting a master in another salon — must return 403", salonAId, masterInSalonBId);
        mockMvc.perform(post("/api/v1/salons/" + salonAId + "/masters/" + masterInSalonBId + "/services/bulk")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .bulkCreateSalonMasterServices(any(), any(), any());
    }

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services/bulk — 403 when caller cannot manage the salon")
    void should_return403_when_bulkOnBehalfByNonManager() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(false);

        var body = "{\"items\":[{\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"durationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00}]}";

        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services/bulk by a non-manager — must return 403", salonId, masterId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk")
                        .with(authenticatedAs(userId, "other@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(serviceCatalogService, org.mockito.Mockito.never())
                .bulkCreateSalonMasterServices(any(), any(), any());
    }

    // ── DUPLICATE_SERVICE 409 — the HTTP/JSON contract the mobile client branches on ────
    //
    // The service-layer tests prove ServiceCatalogService THROWS DuplicateServiceException; they
    // cannot prove the exception ever becomes a 409 with a `data.code`. That depends on
    // GlobalExceptionHandler#handleDuplicateService being selected over #handleBusiness (Spring
    // resolves by exception-hierarchy depth — DuplicateServiceException extends BusinessException,
    // so a missing/removed @ExceptionHandler silently degrades to the generic
    // ApiResponse<Void> body with `data: null`), and on DuplicateServiceResponse's field names
    // surviving serialisation. Both are wire contract: the add-service screen keys off
    // `data.code == "DUPLICATE_SERVICE"` and deep-links via `data.existingServiceDefId`.
    //
    // A @WebMvcTest slice is the right layer (QA pattern Q3): the handler is a
    // @RestControllerAdvice inside the MVC slice, so no database or full context is needed.

    @Test
    @DisplayName("POST /salons/{id}/services — 409 DUPLICATE_SERVICE naming the existing service the owner already offers")
    void should_return409WithDuplicateServiceCode_when_salonAlreadyOffersServiceType() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var existingServiceDefId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Класичне нарощення", "Volume set", "MANICURE", 120, 15,
                PriceType.FIXED, new BigDecimal("900.00"), null, null, UUID.randomUUID());

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(serviceCatalogService.addServiceToSalon(eq(salonId), any(CreateServiceDefinitionRequest.class)))
                .thenThrow(new com.beautica.common.exception.DuplicateServiceException(
                        "Класичне нарощення", existingServiceDefId));

        log.debug("Act: POST /api/v1/salons/{}/services for a service type the salon already offers", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                // The stable code is the ONLY field the client routes on — never the message,
                // which is generic copy shared with the other 409s.
                .andExpect(jsonPath("$.data.code").value("DUPLICATE_SERVICE"))
                .andExpect(jsonPath("$.data.existingServiceDefId").value(existingServiceDefId.toString()))
                .andExpect(jsonPath("$.data.serviceName").value("Класичне нарощення"));
    }

    @Test
    @DisplayName("POST /salons/{id}/services — generic conflict 409 carries NO data.code, so the code alone distinguishes the branches")
    void should_return409WithoutCode_when_conflictIsNotADuplicateService() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Classic Manicure", "Basic nail care", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, UUID.randomUUID());

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(serviceCatalogService.addServiceToSalon(eq(salonId), any(CreateServiceDefinitionRequest.class)))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Some other conflict"));

        // Negative half of the contract: without this, a handler that stamped DUPLICATE_SERVICE
        // onto EVERY 409 would still pass the positive test above, and the add-service screen
        // would offer a deep-link to a service that has nothing to do with the failure.
        log.debug("Act: POST /api/v1/salons/{}/services when the service raises an unrelated 409", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 409 DUPLICATE_SERVICE with null details when the DB index caught a race")
    void should_return409WithNullDetails_when_duplicateDetectedByIndexRace() throws Exception {
        var userId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Lash Extensions", "Volume set", "MANICURE", 120, 15,
                PriceType.FIXED, new BigDecimal("900.00"), null, null, UUID.randomUUID());

        // persistDefinition / flushBulkBatch raise this shape when the pre-check lost the race:
        // the index reports the constraint, not the surviving row, and the aborted Postgres
        // transaction rules out looking it up.
        when(serviceCatalogService.addIndependentMasterService(eq(userId), any(CreateServiceDefinitionRequest.class)))
                .thenThrow(new com.beautica.common.exception.DuplicateServiceException(null, null));

        log.debug("Act: POST /api/v1/independent-masters/me/services when the V121 index (not the pre-check) rejects it");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                // The code survives even with no detail — it is the only field the client
                // branches on, so the race must not degrade to a codeless generic 409.
                .andExpect(jsonPath("$.data.code").value("DUPLICATE_SERVICE"))
                // Both detail keys must still be PRESENT (as JSON null), never dropped: the
                // client reads them optionally and an absent key would change the wire shape.
                .andExpect(jsonPath("$.data.existingServiceDefId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.serviceName").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("PATCH /services/{id} — 409 DUPLICATE_SERVICE when repointed at a service type the owner already offers")
    void should_return409WithDuplicateServiceCode_when_patchRepointsToTakenServiceType() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var existingServiceDefId = UUID.randomUUID();
        var request = new UpdateServiceDefinitionRequest(
                null, null, null, null, null, null, null, null, null, UUID.randomUUID());

        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);
        when(serviceCatalogService.updateServiceDefinition(eq(serviceDefId), any(UpdateServiceDefinitionRequest.class)))
                .thenThrow(new com.beautica.common.exception.DuplicateServiceException(
                        "Класичне нарощення", existingServiceDefId));

        log.debug("Act: PATCH /api/v1/services/{} repointing it at an already-offered service type", serviceDefId);
        mockMvc.perform(patch("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("DUPLICATE_SERVICE"))
                .andExpect(jsonPath("$.data.existingServiceDefId").value(existingServiceDefId.toString()));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services/bulk — 409 DUPLICATE_SERVICE naming the first colliding item")
    void should_return409WithDuplicateServiceCode_when_bulkItemDuplicatesExistingService() throws Exception {
        var userId = UUID.randomUUID();
        var existingServiceDefId = UUID.randomUUID();
        var body = "{\"items\":[{\"serviceTypeId\":\"" + UUID.randomUUID()
                + "\",\"durationMinutes\":60,\"priceType\":\"FIXED\",\"price\":350.00}]}";

        when(serviceCatalogService.bulkCreateIndependentMasterServices(eq(userId), any(BulkCreateServicesRequest.class)))
                .thenThrow(new com.beautica.common.exception.DuplicateServiceException(
                        "Манікюр", existingServiceDefId));

        // The bulk path backs the multi-select add-services screen; a collision there must get the
        // SAME branchable shape as the single create, never a codeless generic 409 envelope.
        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk with an item the master already offers");
        mockMvc.perform(post("/api/v1/independent-masters/me/services/bulk")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("DUPLICATE_SERVICE"))
                .andExpect(jsonPath("$.data.existingServiceDefId").value(existingServiceDefId.toString()))
                .andExpect(jsonPath("$.data.serviceName").value("Манікюр"));
    }
}
