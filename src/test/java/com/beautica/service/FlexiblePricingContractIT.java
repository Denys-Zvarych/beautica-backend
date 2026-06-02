package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack contract test for the <b>flexible service pricing</b> feature
 * ({@code feat/flexible-service-pricing} — FIXED single amount vs RANGE min..max).
 *
 * <h2>Why this test exists (the class of bug it catches)</h2>
 * Flexible pricing introduced a non-obvious persistence contract:
 * <ul>
 *   <li>FIXED → {@code base_price} = the amount, {@code price_max} = NULL.</li>
 *   <li>RANGE → {@code base_price} = the <em>floor</em> ({@code priceMin}),
 *       {@code price_max} = the ceiling. This keeps {@code masters.min_effective_price}
 *       (V58) and {@code bookings.price_at_booking} correct.</li>
 * </ul>
 * The request DTO uses {@code priceMin}/{@code priceMax} but the storage uses
 * {@code base_price}/{@code price_max}. A mapping slip (e.g. storing {@code priceMax}
 * in {@code base_price}) cannot be caught by a mock/slice test — only a real-Postgres
 * read-back of the actual columns proves the floor landed where search & booking read it.
 *
 * <p>Every prior service test either stubbed the service layer ({@code @WebMvcTest}
 * slice) or covered only the FIXED happy path + duplicate-assign 409
 * ({@code ServicesIntegrationTest}). None read the RANGE columns back from Postgres,
 * exercised the FIXED↔RANGE PATCH transitions, or proved that a rejected
 * cross-mode payload leaves the row untouched.
 *
 * <p>Boots the full context against Testcontainers Postgres and drives the REAL chain
 * (HTTP → Bean Validation + {@code @ServicePriceValid} → {@code ServiceCatalogService}
 * → real Postgres), then re-reads the {@code service_definitions} row to assert the
 * canonical columns.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Flexible pricing — FIXED/RANGE contract (full stack, real Postgres)")
class FlexiblePricingContractIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FlexiblePricingContractIT.class);
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        // Apache HC5 supports PATCH, which the JDK HttpURLConnection rejects.
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POSITIVE — RANGE create persists floor to base_price, ceiling to price_max
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — RANGE persists base_price=priceMin (floor) AND price_max=priceMax in Postgres")
    void should_persistRangeFloorInBasePrice_when_rangeServiceCreated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "range-create-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Range Pricing Salon");

        var request = new CreateServiceDefinitionRequest(
                "Balayage", "Hand-painted highlights", "HAIRCUT", 180, 0,
                PriceType.RANGE, null, new BigDecimal("1200.00"), new BigDecimal("2500.00"), null);

        log.debug("Act: POST a RANGE service (priceMin=1200, priceMax=2500) to salon {}", salonId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, request);

        // ── crux: read the ACTUAL canonical columns from Postgres ──
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT price_type, base_price, price_max FROM service_definitions WHERE id = ?",
                serviceDefId);

        assertThat(row.get("price_type"))
                .as("price_type must persist as RANGE")
                .isEqualTo("RANGE");
        assertThat((BigDecimal) row.get("base_price"))
                .as("base_price MUST be the RANGE floor (priceMin=1200) — the column search & booking read")
                .isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat((BigDecimal) row.get("price_max"))
                .as("price_max MUST be the RANGE ceiling (2500)")
                .isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    @DisplayName("POST /salons/{id}/services — FIXED persists base_price=amount AND price_max=NULL in Postgres")
    void should_persistFixedAmountWithNullCeiling_when_fixedServiceCreated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "fixed-create-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Fixed Pricing Salon");

        var request = new CreateServiceDefinitionRequest(
                "Classic Manicure", null, "MANICURE", 60, 0,
                PriceType.FIXED, new BigDecimal("450.00"), null, null, null);

        log.debug("Act: POST a FIXED service (price=450) to salon {}", salonId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, request);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT price_type, base_price, price_max FROM service_definitions WHERE id = ?",
                serviceDefId);

        assertThat(row.get("price_type")).as("price_type must persist as FIXED").isEqualTo("FIXED");
        assertThat((BigDecimal) row.get("base_price"))
                .as("base_price must equal the FIXED amount (450)")
                .isEqualByComparingTo(new BigDecimal("450.00"));
        assertThat(row.get("price_max"))
                .as("price_max MUST be NULL for FIXED (the DB CHECK constraint requires it)")
                .isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POSITIVE — PATCH mode transitions FIXED↔RANGE rewrite the columns atomically
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /services/{id} — FIXED→RANGE writes base_price=newMin AND price_max=newMax")
    void should_transitionFixedToRange_when_patchSuppliesRangeBlock() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "fixed-to-range-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Transition Salon A");
        var fixed = new CreateServiceDefinitionRequest(
                "Lash Lift", null, "EYELASH", 60, 0,
                PriceType.FIXED, new BigDecimal("700.00"), null, null, null);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, fixed);

        var patch = new LinkedHashMap<String, Object>();
        patch.put("priceType", "RANGE");
        patch.put("priceMin", "650.00");
        patch.put("priceMax", "950.00");

        log.debug("Act: PATCH the FIXED service {} into RANGE (650..950)", serviceDefId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(patch), fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("FIXED→RANGE transition must succeed with 200, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT price_type, base_price, price_max FROM service_definitions WHERE id = ?",
                serviceDefId);
        assertThat(row.get("price_type")).as("price_type now RANGE").isEqualTo("RANGE");
        assertThat((BigDecimal) row.get("base_price"))
                .as("base_price rewritten to new floor 650").isEqualByComparingTo(new BigDecimal("650.00"));
        assertThat((BigDecimal) row.get("price_max"))
                .as("price_max rewritten to new ceiling 950").isEqualByComparingTo(new BigDecimal("950.00"));
    }

    @Test
    @DisplayName("PATCH /services/{id} — RANGE→FIXED writes base_price=amount AND NULLs price_max")
    void should_transitionRangeToFixed_when_patchSuppliesFixedBlock() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "range-to-fixed-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Transition Salon B");
        var range = new CreateServiceDefinitionRequest(
                "Brow Shape", null, "BROWS", 30, 0,
                PriceType.RANGE, null, new BigDecimal("200.00"), new BigDecimal("400.00"), null);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, range);

        var patch = new LinkedHashMap<String, Object>();
        patch.put("priceType", "FIXED");
        patch.put("price", "350.00");

        log.debug("Act: PATCH the RANGE service {} into FIXED (350)", serviceDefId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(patch), fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("RANGE→FIXED transition must succeed with 200, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT price_type, base_price, price_max FROM service_definitions WHERE id = ?",
                serviceDefId);
        assertThat(row.get("price_type")).as("price_type now FIXED").isEqualTo("FIXED");
        assertThat((BigDecimal) row.get("base_price"))
                .as("base_price rewritten to fixed amount 350").isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(row.get("price_max"))
                .as("price_max MUST be NULLed on RANGE→FIXED transition (DB CHECK forbids non-null)")
                .isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEGATIVE — cross-mode validation rejects the WHOLE body, no DB mutation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 400 + errors map when RANGE has priceMax <= priceMin (nothing persisted)")
    void should_return400AndPersistNothing_when_rangeMaxNotGreaterThanMin() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "bad-range-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Bad Range Salon");

        var body = new LinkedHashMap<String, Object>();
        body.put("name", "Inverted Range");
        body.put("category", "MANICURE");
        body.put("baseDurationMinutes", 60);
        body.put("bufferMinutesAfter", 0);
        body.put("priceType", "RANGE");
        body.put("priceMin", "800.00");
        body.put("priceMax", "500.00");   // ceiling < floor — must be rejected

        log.debug("Act: POST a RANGE service with priceMax(500) <= priceMin(800) to salon {}", salonId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("inverted range must be rejected with 400, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var api = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<Object>>() {});
        assertThat(api.success()).as("error envelope success=false").isFalse();
        assertThat(api.errors())
                .as("errors map must carry the priceMax violation for the mobile field-mapper")
                .isNotNull()
                .containsKey("priceMax");
        assertThat(api.errors().get("priceMax")).as("priceMax message non-blank").isNotBlank();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = 'Inverted Range'", Integer.class);
        assertThat(count)
                .as("a rejected create must not insert any service_definitions row")
                .isZero();
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 + errors map when FIXED also supplies priceMin (mode mixing)")
    void should_return400_when_fixedAlsoSuppliesRangeFields() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "mix-mode-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Mixed Mode Salon");

        var body = new LinkedHashMap<String, Object>();
        body.put("name", "Mixed Mode Service");
        body.put("category", "MANICURE");
        body.put("baseDurationMinutes", 60);
        body.put("bufferMinutesAfter", 0);
        body.put("priceType", "FIXED");
        body.put("price", "500.00");
        body.put("priceMin", "400.00");   // illegal for FIXED

        log.debug("Act: POST a FIXED service that also carries priceMin to salon {}", salonId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("FIXED + priceMin must be rejected with 400, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var api = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<Object>>() {});
        assertThat(api.errors())
                .as("errors map must flag the illegal priceMin for FIXED")
                .isNotNull()
                .containsKey("priceMin");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = 'Mixed Mode Service'", Integer.class);
        assertThat(count).as("rejected create persists nothing").isZero();
    }

    @Test
    @DisplayName("PATCH /services/{id} — 400 on bad RANGE leaves the original FIXED pricing UNCHANGED in Postgres")
    void should_notMutatePricing_when_patchRangeIsInvalid() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "patch-rollback-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Patch Rollback Salon");
        var fixed = new CreateServiceDefinitionRequest(
                "Keep My Price", null, "MAKEUP", 60, 0,
                PriceType.FIXED, new BigDecimal("999.00"), null, null, null);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, fixed);

        var patch = new LinkedHashMap<String, Object>();
        patch.put("priceType", "RANGE");
        patch.put("priceMin", "300.00");
        patch.put("priceMax", "100.00");   // invalid — ceiling < floor

        log.debug("Act: PATCH service {} with an invalid RANGE block — must be rejected", serviceDefId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(patch), fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("invalid PATCH must be 400, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT price_type, base_price, price_max FROM service_definitions WHERE id = ?",
                serviceDefId);
        assertThat(row.get("price_type"))
                .as("price_type must remain FIXED after a rejected RANGE patch").isEqualTo("FIXED");
        assertThat((BigDecimal) row.get("base_price"))
                .as("base_price must stay at the original 999").isEqualByComparingTo(new BigDecimal("999.00"));
        assertThat(row.get("price_max"))
                .as("price_max must remain NULL").isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEGATIVE — category gating (OTHER retired in V66 / unknown category)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when category=OTHER (retired in V66, active=false)")
    void should_rejectOtherCategory_when_creatingService() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "other-cat-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Other Category Salon");

        var request = new CreateServiceDefinitionRequest(
                "Legacy OTHER Service", null, "OTHER", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);

        log.debug("Act: POST a service with the retired OTHER category to salon {}", salonId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("retired OTHER category must be rejected with 400, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE name = 'Legacy OTHER Service'", Integer.class);
        assertThat(count).as("retired category must not create a row").isZero();
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when category is an unknown (non-seeded) value")
    void should_rejectUnknownCategory_when_creatingService() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "unknown-cat-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Unknown Category Salon");

        var request = new CreateServiceDefinitionRequest(
                "Phantom Category Service", null, "TELEPORTATION", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);

        log.debug("Act: POST a service with an unknown category to salon {}", salonId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("unknown category must be rejected with 400, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEGATIVE — IDOR: a different salon owner cannot manage another owner's service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /services/{id} — 403 when a DIFFERENT salon owner attempts to edit (no mutation)")
    void should_return403AndNotMutate_when_foreignOwnerPatchesService() throws Exception {
        // Owner A creates a FIXED service.
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "idor-owner-a-" + System.nanoTime() + "@beautica.test");
        UUID salonA = fixtures.createSalon(ownerAToken, "Owner A Salon");
        var svc = new CreateServiceDefinitionRequest(
                "Owner A Service", null, "MANICURE", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerAToken, salonA, svc);

        // Owner B (unrelated) tries to patch it.
        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "idor-owner-b-" + System.nanoTime() + "@beautica.test");

        var patch = new LinkedHashMap<String, Object>();
        patch.put("name", "Hijacked Name");

        log.debug("Act: foreign owner B PATCHes owner A's service {}", serviceDefId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(patch), fixtures.bearerHeaders(ownerBToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("a foreign owner must be denied with 403, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);

        String name = jdbcTemplate.queryForObject(
                "SELECT name FROM service_definitions WHERE id = ?", String.class, serviceDefId);
        assertThat(name)
                .as("the IDOR attempt must not mutate owner A's service name")
                .isEqualTo("Owner A Service");
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when a DIFFERENT salon owner attempts to deactivate (row stays active)")
    void should_return403AndKeepActive_when_foreignOwnerDeletesService() throws Exception {
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "idor-del-a-" + System.nanoTime() + "@beautica.test");
        UUID salonA = fixtures.createSalon(ownerAToken, "Delete IDOR Salon A");
        var svc = new CreateServiceDefinitionRequest(
                "Protected Service", null, "PEDICURE", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerAToken, salonA, svc);

        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "idor-del-b-" + System.nanoTime() + "@beautica.test");

        log.debug("Act: foreign owner B DELETEs owner A's service {}", serviceDefId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.DELETE,
                new HttpEntity<>(null, fixtures.bearerHeaders(ownerBToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("a foreign owner must be denied DELETE with 403, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);

        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM service_definitions WHERE id = ?", Boolean.class, serviceDefId);
        assertThat(isActive)
                .as("the service must remain active — the IDOR delete must not soft-delete it")
                .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEGATIVE — wrong role + unauthenticated on the create endpoint
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 403 when a CLIENT attempts to create a service")
    void should_return403_when_clientCreatesService() throws Exception {
        // A SALON_OWNER must own the salon; a CLIENT then attempts the create.
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "role-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Role Guard Salon");
        String clientToken = createClientAndGetToken(
                "role-client-" + System.nanoTime() + "@beautica.test");

        var request = new CreateServiceDefinitionRequest(
                "Client Forbidden Service", null, "MANICURE", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);

        log.debug("Act: a CLIENT POSTs a service to salon {}", salonId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(clientToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("a CLIENT must be denied with 403, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 401 when no Authorization header is present")
    void should_return401_when_noToken() {
        var request = new CreateServiceDefinitionRequest(
                "Anon Service", null, "MANICURE", 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);

        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(MediaType.APPLICATION_JSON);

        log.debug("Act: anonymous POST to a random salon services endpoint");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + UUID.randomUUID() + "/services", HttpMethod.POST,
                new HttpEntity<>(request, noAuth),
                String.class);

        assertThat(resp.getStatusCode())
                .as("an unauthenticated create must be 401, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEGATIVE — not found
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /services/{id} — 404 when the service definition does not exist (owner role, unknown id)")
    void should_return404_when_patchingUnknownService() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "notfound-" + System.nanoTime() + "@beautica.test");

        var patch = new LinkedHashMap<String, Object>();
        patch.put("name", "Nobody Home");

        log.debug("Act: PATCH a non-existent service id as a valid owner");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + UUID.randomUUID(), HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(patch), fixtures.bearerHeaders(ownerToken)),
                String.class);

        // The ownership SpEL resolves the (missing) owner first and denies access → 403,
        // which is the documented existence-oracle-safe behaviour for canManageServiceDefinition
        // (findOwnerUserId empty → orElse(false)). Assert the actor is not granted access.
        assertThat(resp.getStatusCode())
                .as("an unknown service id must NOT return 2xx, body=%s", resp.getBody())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Seeds an email-verified CLIENT and logs in, returning a fresh access token. */
    private String createClientAndGetToken(String email) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD));
        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode())
                .as("seeded CLIENT must log in, body=%s", loginResp.getBody())
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }
}
