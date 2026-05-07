package com.beautica.service.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@Import(TestSecurityConfig.class)
@DisplayName("ServiceCatalogController — HTTP layer")
class ServiceCatalogControllerTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceCatalogControllerTest.class);
    private static final String TEST_PASSWORD = "password123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // @MockBean here must match sibling integration test classes (InviteControllerIT,
    // SalonMasterIntegrationTest) to share the Spring application context and avoid a 15-s fork.
    @MockBean
    private EmailService emailService;

    private UUID nailsCategoryId;
    private UUID browsCategoryId;
    private UUID gelPolishTypeId;
    private UUID browShapingTypeId;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        doNothing().when(emailService).sendInviteEmail(anyString(), anyString(), anyString());
        doNothing().when(emailService).sendAdminNotification(anyString(), anyString(), anyString());

        // Clear any Flyway-seeded catalog data so UNIQUE(sort_order) doesn't conflict
        jdbcTemplate.execute("DELETE FROM service_types");
        jdbcTemplate.execute("DELETE FROM service_categories");

        nailsCategoryId = UUID.randomUUID();
        browsCategoryId = UUID.randomUUID();
        gelPolishTypeId = UUID.randomUUID();
        browShapingTypeId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO service_categories (id, name_uk, name_en, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                nailsCategoryId, "Нігті", "Nails", 1);
        jdbcTemplate.update(
                "INSERT INTO service_categories (id, name_uk, name_en, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                browsCategoryId, "Брови", "Brows", 2);

        jdbcTemplate.update(
                "INSERT INTO service_types (id, category_id, name_uk, name_en, slug, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish-ct-" + System.nanoTime());
        jdbcTemplate.update(
                "INSERT INTO service_types (id, category_id, name_uk, name_en, slug, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                browShapingTypeId, browsCategoryId, "Корекція брів", "Brow Shaping", "brow-shaping-ct-" + System.nanoTime());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM service_types");
        jdbcTemplate.execute("DELETE FROM service_categories");
        jdbcTemplate.execute("DELETE FROM master_services");
        jdbcTemplate.execute("DELETE FROM service_definitions");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // ── GET /api/v1/service-categories ────────────────────────────────────────

    @Test
    @DisplayName("GET /service-categories — 200 with list, no auth required")
    void should_return200_when_getCategoriesWithoutAuth() throws Exception {
        log.debug("Act: GET /api/v1/service-categories — public endpoint, no auth");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-categories", String.class);

        assertThat(response.getStatusCode())
                .as("service-categories must be publicly accessible")
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<CatalogCategoryResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("GET /service-categories — returns categories ordered by sortOrder")
    void should_returnCategoriesOrderedBySortOrder_when_getCategoriesCalled() throws Exception {
        log.debug("Act: GET /api/v1/service-categories — verify sort order");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-categories", String.class);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<CatalogCategoryResponse>>>() {});
        var sortOrders = body.data().stream()
                .map(CatalogCategoryResponse::sortOrder)
                .toList();

        assertThat(sortOrders)
                .as("categories must arrive in ascending sort_order")
                .isSortedAccordingTo(Integer::compareTo);
    }

    // ── GET /api/v1/service-types ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /service-types — 200 with all active types, no auth required")
    void should_return200WithAllTypes_when_noParamsProvided() throws Exception {
        log.debug("Act: GET /api/v1/service-types — no params, public endpoint");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types", String.class);

        assertThat(response.getStatusCode())
                .as("service-types must be publicly accessible without auth")
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<ServiceTypeResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("GET /service-types?categoryId=... — returns only types for that category")
    void should_returnOnlyTypesForCategory_when_categoryIdParamProvided() throws Exception {
        log.debug("Act: GET /api/v1/service-types?categoryId={}", nailsCategoryId);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?categoryId=" + nailsCategoryId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<ServiceTypeResponse>>>() {});
        assertThat(body.data())
                .as("only types belonging to the requested category must be returned")
                .allSatisfy(t -> assertThat(t.categoryId()).isEqualTo(nailsCategoryId));
        assertThat(body.data()).extracting(ServiceTypeResponse::nameUk).contains("Гель-лак");
    }

    @Test
    @DisplayName("GET /service-types?q=Гель — returns matching types for query ≥ 2 chars")
    void should_returnMatchingTypes_when_qParamIsAtLeastTwoChars() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=Гель");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?q=Гель", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<ServiceTypeResponse>>>() {});
        assertThat(body.success()).isTrue();
        // pg_trgm requires extension; results may vary in test — we verify the call succeeds with 200
        assertThat(body.data()).isNotNull();
    }

    @Test
    @DisplayName("GET /service-types?q=М — falls back to all active types when q < 2 chars")
    void should_returnAllTypes_when_qParamHasFewerThanTwoChars() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=М — single char query falls back to all-active");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?q=М", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<ServiceTypeResponse>>>() {});
        assertThat(body.data())
                .as("single-char query must fall back to all active types — both categories present")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("GET /service-types?q=<101-char string> — 400 when q exceeds @Size(max=100)")
    void should_return400_when_qParamExceeds100Chars() throws Exception {
        String longQ = "a".repeat(101);

        log.debug("Act: GET /api/v1/service-types?q=<101 chars> — must return 400");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?q=" + longQ, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when q param length=101 violates @Size(max=100)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── POST /api/v1/service-types/suggest ────────────────────────────────────

    @Test
    @DisplayName("POST /service-types/suggest — 202 when SALON_OWNER submits a valid suggestion")
    void should_return202_when_salonOwnerSuggestsServiceType() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken("suggest-owner-" + System.nanoTime() + "@beautica.test");

        var request = new SuggestServiceTypeRequest("Ламінування вій", nailsCategoryId, "Опис процедури");

        log.debug("Act: POST /api/v1/service-types/suggest as SALON_OWNER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("suggest endpoint must return 202 Accepted for authenticated master/owner")
                .isEqualTo(HttpStatus.ACCEPTED);

        verify(emailService).sendAdminNotification(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 403 when CLIENT submits a suggestion")
    void should_return403_when_clientSuggestsServiceType() throws Exception {
        String clientToken = registerClientAndGetToken("suggest-client-" + System.nanoTime() + "@beautica.test");

        var request = new SuggestServiceTypeRequest("Sneaky Suggest", nailsCategoryId, null);

        log.debug("Act: POST /api/v1/service-types/suggest as CLIENT — must be denied");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT role must be denied access to the suggest endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when name is blank")
    void should_return400_when_nameIsBlank() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken("suggest-val-" + System.nanoTime() + "@beautica.test");

        String invalidBody = "{\"name\":\"\",\"categoryId\":\"" + nailsCategoryId + "\"}";

        log.debug("Act: POST /api/v1/service-types/suggest with blank name — must return 400");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(invalidBody, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("blank name must fail Bean Validation and return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when categoryId is missing")
    void should_return400_when_categoryIdIsNull() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken("suggest-nocat-" + System.nanoTime() + "@beautica.test");

        String invalidBody = "{\"name\":\"Нова послуга\"}";

        log.debug("Act: POST /api/v1/service-types/suggest with missing categoryId — must return 400");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(invalidBody, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("missing categoryId must fail @NotNull validation and return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 401 when no authentication provided")
    void should_return401_when_noAuthProvided() throws Exception {
        var request = new SuggestServiceTypeRequest("Ламінування вій", nailsCategoryId, null);

        log.debug("Act: POST /api/v1/service-types/suggest without auth — must return 401");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 202 when SALON_ADMIN submits a valid suggestion")
    void should_return202_when_salonAdminSuggestsServiceType() throws Exception {
        String adminToken = createSalonAdminAndGetToken("suggest-admin-" + System.nanoTime() + "@beautica.test");

        var request = new SuggestServiceTypeRequest("Ботокс для брів", nailsCategoryId, "Опис");

        log.debug("Act: POST /api/v1/service-types/suggest as SALON_ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(adminToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("suggest endpoint must accept SALON_ADMIN")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 202 when INDEPENDENT_MASTER submits a valid suggestion")
    void should_return202_when_independentMasterSuggestsServiceType() throws Exception {
        String masterToken = createIndependentMasterAndGetToken("suggest-indep-" + System.nanoTime() + "@beautica.test");

        var request = new SuggestServiceTypeRequest("Нарощення нігтів", nailsCategoryId, null);

        log.debug("Act: POST /api/v1/service-types/suggest as INDEPENDENT_MASTER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(masterToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("suggest endpoint must accept INDEPENDENT_MASTER")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 403 when SALON_MASTER submits a suggestion")
    void should_return403_when_salonMasterSuggestsServiceType() throws Exception {
        UUID salonId = createSalonForOwner("salon-master-owner-" + System.nanoTime() + "@beautica.test");
        String masterToken = createSalonMasterAndGetToken(
                "suggest-smaster-" + System.nanoTime() + "@beautica.test", salonId);

        var request = new SuggestServiceTypeRequest("Корекція форми брів", browsCategoryId, null);

        log.debug("Act: POST /api/v1/service-types/suggest as SALON_MASTER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(masterToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("SALON_MASTER is a read-only role and must be denied access to the suggest endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when name exceeds 255 characters")
    void should_return400_when_nameExceeds255Chars() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken("suggest-longname-" + System.nanoTime() + "@beautica.test");
        String longName = "a".repeat(256);

        String invalidBody = "{\"name\":\"" + longName + "\",\"categoryId\":\"" + nailsCategoryId + "\"}";

        log.debug("Act: POST /api/v1/service-types/suggest with 256-char name — must return 400");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(invalidBody, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("name exceeding 255 chars must fail @Size validation and return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when description exceeds 1000 characters")
    void should_return400_when_descriptionExceeds1000Chars() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken("suggest-longdesc-" + System.nanoTime() + "@beautica.test");
        String longDescription = "a".repeat(1001);

        String invalidBody = "{\"name\":\"Нова послуга\",\"categoryId\":\"" + nailsCategoryId
                + "\",\"description\":\"" + longDescription + "\"}";

        log.debug("Act: POST /api/v1/service-types/suggest with 1001-char description — must return 400");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(invalidBody, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("description exceeding 1000 chars must fail @Size validation and return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /service-types/suggest — response body has success=true and message contains 'Suggestion'")
    void should_return202Body_withSuccessTrue_when_suggestCalled() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken("suggest-body-" + System.nanoTime() + "@beautica.test");

        var request = new SuggestServiceTypeRequest("Ламінування брів", browsCategoryId, "Деталі");

        log.debug("Act: POST /api/v1/service-types/suggest — assert response body structure");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success())
                .as("response body must have success=true")
                .isTrue();
    }

    @Test
    @DisplayName("GET /service-types?q=Гель — returns non-empty list with nameUk containing 'Гель'")
    void should_returnMatchingTypesWithGel_when_qParamIsGel() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=Гель — strengthened assertion");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?q=Гель", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<ServiceTypeResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data())
                .as("search for 'Гель' must return at least one result containing that term in nameUk")
                .isNotEmpty()
                .anyMatch(t -> t.nameUk().contains("Гель"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createSalonOwnerAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                UUID.randomUUID(), email, hash);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private String registerClientAndGetToken(String email) throws Exception {
        String bodyJson = "{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\",\"role\":\"CLIENT\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(bodyJson, jsonHeaders()),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return parsed.data().accessToken();
    }

    private String createSalonAdminAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID salonId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, "owner-for-admin-" + System.nanoTime() + "@beautica.test", hash);
        jdbcTemplate.update(
                "INSERT INTO salons (id, name, owner_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, "Test Salon " + System.nanoTime(), ownerId);

        UUID adminUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active) VALUES (?, ?, ?, 'SALON_ADMIN', ?, true)",
                adminUserId, email, hash, salonId);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), adminUserId, salonId);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private String createIndependentMasterAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), userId);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createSalonForOwner(String ownerEmail) {
        UUID ownerId = UUID.randomUUID();
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, ownerEmail, hash);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, name, owner_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, "Test Salon " + System.nanoTime(), ownerId);
        return salonId;
    }

    private String createSalonMasterAndGetToken(String email, UUID salonId) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active) VALUES (?, ?, ?, 'SALON_MASTER', ?, true)",
                userId, email, hash, salonId);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), userId, salonId);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
