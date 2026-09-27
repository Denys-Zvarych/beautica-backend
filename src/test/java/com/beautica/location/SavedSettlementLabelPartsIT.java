package com.beautica.location;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-HTTP proof that every response exposing a SAVED settlement by {@code cityId} also carries
 * the structured parts a client needs to compose the full label («с. Іванівка, Шишацька громада,
 * Полтавська обл.») — {@code citySettlementType} and the ambiguous-only {@code cityHromadaNameUk}
 * — with the same rule as {@code GET /api/v1/settlements}.
 *
 * <p>One owner per case, created through {@code POST /salons} so the salon row AND the owner's
 * synced {@code users} row AND the auto-created owner-master all point at the fixture settlement.
 * Every read surface the mobile app labels a saved locality from is then asserted: the create
 * body, {@code GET /salons/{id}} (public), {@code GET /salons/mine} (batch path),
 * {@code GET /users/me}, {@code GET /masters/me} (own + nested salon) and the public
 * {@code GET /masters/{id}}, which must MASK the parts for a salon-affiliated master exactly as it
 * masks {@code city}.
 *
 * <p>Fixtures are chosen from the Flyway-seeded taxonomy by predicate, never by hard-coded id:
 * the village is ambiguous in its oblast (so a hromada must surface), the city is NOT ambiguous
 * but still STORES a hromada — which is what proves the response gates on
 * {@code ambiguous_in_oblast} rather than passing the column through.
 */
@Import({TestSecurityConfig.class, SavedSettlementLabelPartsIT.SqlCaptureConfig.class})
@DisplayName("Saved-settlement label parts — citySettlementType + ambiguous-only cityHromadaNameUk on user/salon/master responses")
class SavedSettlementLabelPartsIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

    /** The {@code cities} table as a whole word — not {@code city_id}, not {@code city_districts}. */
    private static final Pattern CITIES_TABLE = Pattern.compile("\\bcities\\b");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** The {@code POST /salons} response body of the fixture set up by {@link #ownerWithSalonIn}. */
    private JsonNode lastCreatedSalon;

    @Test
    @DisplayName("an ambiguous VILLAGE — every surface returns VILLAGE and the stored hromada")
    void should_returnVillageAndHromada_when_settlementIsAmbiguousVillage() throws Exception {
        UUID villageId = ambiguousVillageId();
        String hromada = storedHromada(villageId);
        assertThat(hromada).as("precondition: an ambiguous row always stores a hromada (V174 CHECK)")
                .isNotBlank();
        String token = ownerWithSalonIn(villageId, "village");

        JsonNode created = lastCreatedSalon;
        UUID salonId = UUID.fromString(created.path("id").asText());

        assertParts(created, "VILLAGE", hromada, "POST /salons body");
        assertParts(getPublicSalon(salonId), "VILLAGE", hromada, "GET /salons/{id}");
        assertParts(getMine(token, salonId), "VILLAGE", hromada, "GET /salons/mine");
        JsonNode me = get("/api/v1/users/me", token);
        assertParts(me, "VILLAGE", hromada, "GET /users/me");
        assertThat(me.path("oblastName").asText()).as("oblast half of the label on /users/me")
                .isEqualTo(oblastName(villageId));
        JsonNode masterMe = get("/api/v1/masters/me", token);
        assertParts(masterMe, "VILLAGE", hromada, "GET /masters/me (own settlement)");
        assertThat(masterMe.path("region").asText()).as("oblast half of the label on /masters/me")
                .isEqualTo(oblastName(villageId));
        assertParts(masterMe.path("salon"), "VILLAGE", hromada, "GET /masters/me nested salon");
    }

    @Test
    @DisplayName("a non-ambiguous CITY — CITY and a null hromada, even though the row stores one")
    void should_returnCityAndNullHromada_when_settlementIsUnambiguousCity() throws Exception {
        UUID cityId = unambiguousCityStoringHromada();
        assertThat(storedHromada(cityId))
                .as("precondition: the row STORES a hromada, so a null on the wire proves the gate")
                .isNotBlank();
        String token = ownerWithSalonIn(cityId, "city");

        JsonNode created = lastCreatedSalon;
        UUID salonId = UUID.fromString(created.path("id").asText());

        assertParts(created, "CITY", null, "POST /salons body");
        assertParts(getPublicSalon(salonId), "CITY", null, "GET /salons/{id}");
        assertParts(getMine(token, salonId), "CITY", null, "GET /salons/mine");
        assertParts(get("/api/v1/users/me", token), "CITY", null, "GET /users/me");
        JsonNode masterMe = get("/api/v1/masters/me", token);
        assertParts(masterMe, "CITY", null, "GET /masters/me (own settlement)");
        assertParts(masterMe.path("salon"), "CITY", null, "GET /masters/me nested salon");
    }

    @Test
    @DisplayName("public GET /masters/{id} masks the own-settlement parts of a salon-affiliated master, like city")
    void should_maskOwnSettlementParts_when_publicMasterIsSalonAffiliated() throws Exception {
        UUID villageId = ambiguousVillageId();
        String token = ownerWithSalonIn(villageId, "mask");
        UUID masterId = UUID.fromString(get("/api/v1/masters/me", token).path("masterId").asText());

        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/masters/" + masterId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode publicMaster = objectMapper.readTree(response.getBody()).path("data");
        assertThat(publicMaster.path("city").isNull()).as("precondition: city itself is masked").isTrue();
        assertThat(publicMaster.path("region").isNull()).as("region masked like city").isTrue();
        assertThat(publicMaster.path("citySettlementType").isNull()).as("type masked like city").isTrue();
        assertThat(publicMaster.path("cityHromadaNameUk").isNull()).as("hromada masked like city").isTrue();
        assertParts(publicMaster.path("salon"), "VILLAGE", storedHromada(villageId),
                "public master's nested salon — a salon's address is public");
    }

    @Test
    @DisplayName("GET /salons/mine on a cache miss resolves oblastId + label parts for every salon in exactly ONE cities query")
    void should_issueOneCitiesQuery_when_ownerSalonsListMisses() throws Exception {
        UUID villageId = ambiguousVillageId();
        UUID cityId = unambiguousCityStoringHromada();
        String token = ownerWithSalonIn(villageId, "count");
        createSalon(token, cityId, "Second Label Studio");

        CAPTURED_SQL.clear();
        // The second POST evicted ownerSalons after commit, so this read is a genuine miss.
        JsonNode mine = get("/api/v1/salons/mine", token);
        List<String> citiesStatements = CAPTURED_SQL.stream()
                .filter(sql -> CITIES_TABLE.matcher(sql.toLowerCase(Locale.ROOT)).find())
                .toList();

        assertThat(mine.size()).as("precondition: two salons in two different cities").isEqualTo(2);
        assertThat(mine).allSatisfy(salon -> {
            assertThat(salon.path("oblastId").isNull()).as("oblastId populated").isFalse();
            assertThat(salon.path("citySettlementType").isNull()).as("type populated").isFalse();
        });
        assertThat(citiesStatements)
                .as("one join for oblastId AND label parts — never an oblast-only batch beside it, "
                        + "never one per salon — captured=%s", citiesStatements)
                .hasSize(1);
    }

    // ── assertions ────────────────────────────────────────────────────────────────

    private static void assertParts(JsonNode node, String type, String hromada, String surface) {
        assertThat(node.path("citySettlementType").asText())
                .as("%s citySettlementType", surface).isEqualTo(type);
        if (hromada == null) {
            assertThat(node.path("cityHromadaNameUk").isNull())
                    .as("%s cityHromadaNameUk must be null — got %s", surface, node.path("cityHromadaNameUk"))
                    .isTrue();
        } else {
            assertThat(node.path("cityHromadaNameUk").asText())
                    .as("%s cityHromadaNameUk", surface).isEqualTo(hromada);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private UUID ambiguousVillageId() {
        return jdbcTemplate.queryForObject("""
                SELECT c.id FROM cities c
                 WHERE c.settlement_type = 'VILLAGE'
                   AND c.ambiguous_in_oblast
                   AND c.hromada_name_uk IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM city_districts d WHERE d.city_id = c.id)
                 ORDER BY c.katotth_code
                 LIMIT 1
                """, UUID.class);
    }

    private UUID unambiguousCityStoringHromada() {
        return jdbcTemplate.queryForObject("""
                SELECT c.id FROM cities c
                 WHERE c.settlement_type = 'CITY'
                   AND NOT c.ambiguous_in_oblast
                   AND c.hromada_name_uk IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM city_districts d WHERE d.city_id = c.id)
                 ORDER BY c.katotth_code
                 LIMIT 1
                """, UUID.class);
    }

    private String storedHromada(UUID cityId) {
        return jdbcTemplate.queryForObject(
                "SELECT hromada_name_uk FROM cities WHERE id = ?", String.class, cityId);
    }

    private String oblastName(UUID cityId) {
        return jdbcTemplate.queryForObject(
                "SELECT o.name_uk FROM cities c JOIN oblasts o ON o.id = c.oblast_id WHERE c.id = ?",
                String.class, cityId);
    }

    /** A SALON_OWNER whose FIRST salon (so the owner-master is auto-created) sits in {@code cityId}. */
    private String ownerWithSalonIn(UUID cityId, String tag) throws Exception {
        String email = "owner-label-" + tag + "-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD));
        String token = login(email);
        lastCreatedSalon = createSalon(token, cityId, "Label Studio");
        return token;
    }

    private JsonNode createSalon(String token, UUID cityId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name, "cityId", cityId,
                "street", "Shevchenka St", "buildingNo", "7"));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode())
                .as("salon fixture creation must succeed — actual body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode getPublicSalon(UUID salonId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/salons/" + salonId, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode getMine(String token, UUID salonId) throws Exception {
        for (JsonNode salon : get("/api/v1/salons/mine", token)) {
            if (salonId.toString().equals(salon.path("id").asText())) {
                return salon;
            }
        }
        throw new AssertionError("salon " + salonId + " missing from GET /salons/mine");
    }

    private JsonNode get(String url, String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).as("GET %s — body: %s", url, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<AuthResponse>>() { }).data().accessToken();
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Pure-observer Hibernate {@link StatementInspector} — the same dependency-free SQL capture
     * {@code SalonSiblingProjectionShapeIT} uses.
     */
    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        HibernatePropertiesCustomizer settlementLabelSqlCaptureCustomizer() {
            return props -> props.put("hibernate.session_factory.statement_inspector",
                    (StatementInspector) sql -> {
                        if (sql != null) {
                            CAPTURED_SQL.add(sql);
                        }
                        return sql;
                    });
        }
    }
}
