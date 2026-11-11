package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 453 — V234 seeds the category «Масаж і догляд за тілом» ({@code BODY_MASSAGE}) with 11
 * service types (names only). Pins the seed shape, the read paths, the "two distinct types"
 * create path, and idempotency of the migration body.
 *
 * <p>Reference data is not truncated by {@code cleanDb()}, so the seed is read straight from the
 * Testcontainers post-migration state.
 */
@DisplayName("Phase 453 — BODY_MASSAGE category seed (V234)")
class BodyMassageCategorySeedIT extends AbstractIntegrationTest {

    private static final String CATEGORY = "BODY_MASSAGE";
    private static final String DISPLAY_NAME = "Масаж і догляд за тілом";
    /** Existing V13 coarse bucket «Тіло» — user decision 2026-11-10: no new bucket. */
    private static final String BUCKET_ID = "11111111-0006-0000-0000-000000000000";
    private static final String SEEDED_BUCKET_PREFIX = "11111111-";
    private static final int SEEDED_BUCKET_COUNT = 8;
    private static final String LAST_BUCKET = "Інше";
    private static final String MIGRATION = "db/migration/V234__body_massage_category.sql";

    /**
     * Locked D2 spelling, in the order the API returns them: alphabetical by {@code name_uk}
     * (user decision 2026-11-10 — no custom ordering). Latin «SPA-процедура» sorts before
     * Cyrillic; prefix names («Авторський масаж» / «… + маска») sort shorter-first. The same
     * order holds under both C (musl) and en_US (glibc) collation.
     */
    private static final List<String> SERVICE_TYPE_NAMES = List.of(
            "SPA-процедура",
            "Авторський масаж",
            "Авторський масаж + маска",
            "Антицелюлітний масаж",
            "Віскі-пеленання",
            "Кедрова фітобочка",
            "Класичний масаж",
            "Класичний масаж спини",
            "Лімфодренажний масаж",
            "Релакс масаж",
            "Спортивний масаж");

    /** Alphabetical neighbours of «Масаж і догляд за тілом» in the seeded approved list. */
    private static final String PREVIOUS_CATEGORY = "Макіяж";
    private static final String NEXT_CATEGORY = "Нарощення вій";
    private static final int SHORT_DURATION = 60;
    private static final int LONG_DURATION = 90;

    private static final String SIGNATURE = "Авторський масаж";
    private static final String SIGNATURE_MASK = "Авторський масаж + маска";
    private static final String CLASSIC = "Класичний масаж";
    private static final String CLASSIC_SLUG = "classic-massage";
    private static final String SPORTS = "Спортивний масаж";

    /** Locked V234 seed rows: slug is the search contract ({@code serviceTypeSlugs=}), name_en the EN label. */
    private static final List<SeedRow> SEED_ROWS = List.of(
            new SeedRow("classic-massage", "Класичний масаж", "Classic Massage"),
            new SeedRow("relax-massage", "Релакс масаж", "Relax Massage"),
            new SeedRow("classic-back-massage", "Класичний масаж спини", "Classic Back Massage"),
            new SeedRow("sports-massage", "Спортивний масаж", "Sports Massage"),
            new SeedRow("signature-massage", "Авторський масаж", "Signature Massage"),
            new SeedRow("anti-cellulite-massage", "Антицелюлітний масаж", "Anti-Cellulite Massage"),
            new SeedRow("lymphatic-drainage-massage", "Лімфодренажний масаж", "Lymphatic Drainage Massage"),
            new SeedRow("whisky-wrap", "Віскі-пеленання", "Whisky Wrap"),
            new SeedRow("signature-massage-mask", "Авторський масаж + маска", "Signature Massage + Mask"),
            new SeedRow("spa-procedure", "SPA-процедура", "SPA Procedure"),
            new SeedRow("cedar-phyto-barrel", "Кедрова фітобочка", "Cedar Phyto Barrel"));

    private record SeedRow(String slug, String nameUk, String nameEn) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("platform category row is APPROVED + active with the locked displayName and initial service name")
    void should_seedApprovedActiveCategory_when_v234Applied() {
        var row = jdbcTemplate.queryForMap(
                "SELECT display_name, status, active, initial_service_name, service_category_id::text AS bucket "
                        + "FROM platform_categories WHERE name = ?", CATEGORY);

        assertThat(row.get("display_name")).isEqualTo(DISPLAY_NAME);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("initial_service_name")).isEqualTo("Класичний масаж");
        assertThat(row.get("bucket")).isEqualTo(BUCKET_ID);
    }

    @Test
    @DisplayName("GET /service-categories/approved lists «Масаж і догляд за тілом» at its alphabetical position")
    void should_listCategoryInApprovedPicker_when_v234Applied() throws Exception {
        String clientToken = fixtures.createClientAndGetToken("massage-chips-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/service-categories/approved", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> displayNames = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data").forEach(n -> displayNames.add(n.path("displayName").asText()));
        int index = displayNames.indexOf(DISPLAY_NAME);
        assertThat(index).as("category must be listed, got %s", displayNames).isPositive();
        assertThat(displayNames.get(index - 1)).isEqualTo(PREVIOUS_CATEGORY);
        assertThat(displayNames.get(index + 1)).isEqualTo(NEXT_CATEGORY);
    }

    @Test
    @DisplayName("GET /service-types?categoryName=BODY_MASSAGE returns the 11 locked names, alphabetical")
    void should_returnElevenLockedNames_when_listingServiceTypesForCategory() throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/service-types?categoryName=" + CATEGORY, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> names = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data").forEach(n -> names.add(n.path("nameUk").asText()));
        assertThat(names).containsExactlyElementsOf(SERVICE_TYPE_NAMES);
        Integer bucketLeaves = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE category_id = ?::uuid AND platform_category_name = ? "
                        + "AND is_active = TRUE", Integer.class, BUCKET_ID, CATEGORY);
        assertThat(bucketLeaves).isEqualTo(SERVICE_TYPE_NAMES.size());
    }

    @Test
    @DisplayName("V234 seeds exactly the 11 locked slugs with their exact name_uk and name_en")
    void should_seedLockedSlugsAndEnglishNames_when_v234Applied() {
        List<SeedRow> seeded = jdbcTemplate.query(
                "SELECT slug, name_uk, name_en FROM service_types WHERE platform_category_name = ?",
                (rs, i) -> new SeedRow(rs.getString("slug"), rs.getString("name_uk"), rs.getString("name_en")),
                CATEGORY);

        assertThat(seeded).as("slug typo would break serviceTypeSlugs= search").containsExactlyInAnyOrderElementsOf(SEED_ROWS);
    }

    @Test
    @DisplayName("one master can create both «Авторський масаж» and «Авторський масаж + маска» (distinct types)")
    void should_createBothSignatureServices_when_typesDiffer() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken("massage-master-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> first = createService(token, SIGNATURE, SHORT_DURATION);
        ResponseEntity<String> second = createService(token, SIGNATURE_MASK, SHORT_DURATION);

        assertThat(first.getStatusCode()).as("body=%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).as("body=%s", second.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(List.of(serviceTypeNameOf(first), serviceTypeNameOf(second)))
                .as("each service is bound to its own seeded type")
                .containsExactly(SIGNATURE, SIGNATURE_MASK);
    }

    private String serviceTypeNameOf(ResponseEntity<String> created) throws Exception {
        return objectMapper.readTree(created.getBody()).path("data").path("serviceTypeNameUk").asText();
    }

    @Test
    @DisplayName("«Авторський масаж» 60 and 90 хв are copies (both 201); an identical second 60 → 409 DUPLICATE_SERVICE")
    void should_allowDurationCopyAndRejectIdentical_when_sameTypeAddedTwice() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken("massage-copy-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> sixty = createService(token, SIGNATURE, SHORT_DURATION);
        ResponseEntity<String> ninety = createService(token, SIGNATURE, LONG_DURATION);
        ResponseEntity<String> identical = createService(token, SIGNATURE, SHORT_DURATION);

        assertThat(sixty.getStatusCode()).as("body=%s", sixty.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ninety.getStatusCode()).as("body=%s", ninety.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(identical.getStatusCode()).as("body=%s", identical.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(identical.getBody()).path("data").path("code").asText())
                .isEqualTo("DUPLICATE_SERVICE");
    }

    @Test
    @DisplayName("guest GET /search/masters?category=BODY_MASSAGE finds the master who offers «Класичний масаж», not a nails-only master")
    void should_findMasterOfferingMassageType_when_guestSearchesByBodyMassageCategory() throws Exception {
        String massageEmail = "massage-search-" + System.nanoTime() + "@beautica.test";
        String massageToken = fixtures.createIndependentMasterAndGetToken(massageEmail);
        ResponseEntity<String> created = createService(massageToken, "Класичний масаж", SHORT_DURATION);
        assertThat(created.getStatusCode()).as("arrange, body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String nailsEmail = "nails-search-" + System.nanoTime() + "@beautica.test";
        String nailsToken = fixtures.createIndependentMasterAndGetToken(nailsEmail);
        fixtures.createIndependentMasterService(nailsToken, "Манікюр");
        UUID massageMasterId = fixtures.resolveMasterIdForUserEmail(massageEmail);
        // Both masters bookable (search hides schedule-less masters), so only the category filter separates them.
        fixtures.seedUsableSchedule(massageMasterId);
        fixtures.seedUsableSchedule(fixtures.resolveMasterIdForUserEmail(nailsEmail));

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/search/masters?category=" + CATEGORY + "&page=0&size=20", String.class);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        List<String> masterIds = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data").path("data")
                .forEach(n -> masterIds.add(n.path("masterId").asText()));
        assertThat(masterIds).as("only the BODY_MASSAGE master matches").containsExactly(massageMasterId.toString());
    }

    @Test
    @DisplayName("guest GET /search/masters?serviceTypeSlugs=classic-massage finds the «Класичний масаж» master, not a «Спортивний масаж» one")
    void should_findMasterOfferingClassicMassage_when_guestSearchesByMassageSlug() throws Exception {
        UUID classicMasterId = bookableMassageMaster("massage-slug-classic-", CLASSIC);
        bookableMassageMaster("massage-slug-sports-", SPORTS);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/search/masters?serviceTypeSlugs=" + CLASSIC_SLUG + "&page=0&size=20", String.class);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        List<String> masterIds = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data").path("data")
                .forEach(n -> masterIds.add(n.path("masterId").asText()));
        assertThat(masterIds).as("same category, only the slug separates them").containsExactly(classicMasterId.toString());
    }

    /** Independent master offering one BODY_MASSAGE type, with a usable schedule (search hides schedule-less masters). */
    private UUID bookableMassageMaster(String emailPrefix, String typeName) throws Exception {
        String email = emailPrefix + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        ResponseEntity<String> created = createService(token, typeName, SHORT_DURATION);
        assertThat(created.getStatusCode()).as("arrange, body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        fixtures.seedUsableSchedule(masterId);
        return masterId;
    }

    @Test
    @DisplayName("the 11 types sit in the existing «Тіло» bucket; guest GET /service-categories keeps 8 seeded buckets, «Інше» last")
    void should_keepEightBucketsWithOtherLast_when_typesFiledUnderBody() throws Exception {
        String bucketName = jdbcTemplate.queryForObject(
                "SELECT sc.name_uk FROM service_categories sc WHERE sc.id = ?::uuid", String.class, BUCKET_ID);
        Integer typesInBody = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE platform_category_name = ? AND category_id = ?::uuid",
                Integer.class, CATEGORY, BUCKET_ID);

        ResponseEntity<String> resp = restTemplate.getForEntity("/api/v1/service-categories", String.class);

        assertThat(bucketName).isEqualTo("Тіло");
        assertThat(typesInBody).isEqualTo(SERVICE_TYPE_NAMES.size());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Only the V13-seeded buckets (fixed 11111111-* ids) — other ITs may persist their own rows.
        List<String> seededBuckets = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data").forEach(n -> {
            if (n.path("id").asText().startsWith(SEEDED_BUCKET_PREFIX)) {
                seededBuckets.add(n.path("nameUk").asText());
            }
        });
        assertThat(seededBuckets).hasSize(SEEDED_BUCKET_COUNT).doesNotContain(DISPLAY_NAME).last().isEqualTo(LAST_BUCKET);
    }

    @Test
    @DisplayName("re-running the V234 body inserts nothing (run in a rolled-back transaction — shared container untouched)")
    void should_insertNothing_when_migrationBodyReRun() throws SQLException {
        List<Long> before;
        List<Long> after;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                before = seedCounts(connection);
                new ResourceDatabasePopulator(new ClassPathResource(MIGRATION)).populate(connection);
                after = seedCounts(connection);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }

        assertThat(after).as("[platform_categories, service_categories, service_types] row counts").isEqualTo(before);
    }

    /** Counts read on the re-run's own connection, so uncommitted inserts would be visible. */
    private static List<Long> seedCounts(Connection connection) throws SQLException {
        List<Long> counts = new ArrayList<>();
        for (String table : List.of("platform_categories", "service_categories", "service_types")) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                counts.add(rs.getLong(1));
            }
        }
        return counts;
    }

    private ResponseEntity<String> createService(String token, String typeName, int durationMinutes) {
        UUID typeId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_types WHERE platform_category_name = ? AND name_uk = ?",
                UUID.class, CATEGORY, typeName);
        var request = new CreateServiceDefinitionRequest(null, null, CATEGORY, durationMinutes, 0,
                PriceType.FIXED, new BigDecimal("800.00"), null, null, typeId);
        return restTemplate.exchange("/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }
}
