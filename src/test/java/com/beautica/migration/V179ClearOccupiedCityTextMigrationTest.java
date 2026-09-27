package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V179 — clears the occupied-settlement {@code users.city}/{@code users.region} text that V170
 * left behind when it NULLed {@code city_id} for the 17 cities V53 seeded in error.
 *
 * <p>Same harness as {@link V177SalonsCityBackfillMigrationTest}: the shared container ran V179
 * on an empty schema, so each test seeds the post-V170 shape and re-applies V179's exact SQL
 * body. The derivation test reads V53, V170 and V179 off the classpath and proves V179's name
 * list IS V170's code list resolved through V53 — not a hand-typed copy.
 */
@DisplayName("V179 migration — occupied-settlement text left by V170 is cleared, and nothing else")
class V179ClearOccupiedCityTextMigrationTest extends AbstractIntegrationTest {

    private static final String V53 = "db/migration/V53__seed_locality_taxonomy.sql";
    private static final String V170 = "db/migration/V170__widen_cities_to_full_settlement_taxonomy.sql";
    private static final String V179 = "db/migration/V179__clear_occupied_city_text_left_by_v170.sql";

    private static final Pattern CODE = Pattern.compile("'(UA\\d{17})'");

    private String v179Sql;

    @BeforeEach
    void loadSql() throws IOException {
        v179Sql = read(V179);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Runs the whole two-statement script in one simple-query message; returns rows updated. */
    private int applyV179() {
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (var stmt = conn.createStatement()) {
                int total = 0;
                boolean isResultSet = stmt.execute(v179Sql);
                while (true) {
                    if (!isResultSet) {
                        int count = stmt.getUpdateCount();
                        if (count == -1) {
                            break;
                        }
                        total += count;
                    }
                    isResultSet = stmt.getMoreResults();
                }
                return total;
            } catch (SQLException e) {
                throw new IllegalStateException("V179 re-application failed", e);
            }
        });
    }

    private UUID insertUser(String role, String city, String region) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, city, region, city_id) "
                        + "VALUES (?, ?, ?, ?, true, true, ?, ?, NULL)",
                id, "v179-" + id + "@beautica.test", new BCryptPasswordEncoder(4).encode("test-password"),
                role, city, region);
        return id;
    }

    private Map<String, Object> labels(UUID userId) {
        return jdbcTemplate.queryForMap("SELECT city, region FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("«Мелітополь, Запорізька» with city_id NULL (the V170 shape) is cleared")
    void should_clearMelitopol_when_cityIdWasReleasedByV170() {
        UUID master = insertUser("INDEPENDENT_MASTER", "Мелітополь", "Запорізька");

        applyV179();

        assertThat(labels(master)).containsEntry("city", null).containsEntry("region", null);
    }

    @Test
    @DisplayName("the NULL-region and «… область» spellings of the same city are cleared too")
    void should_clearOccupiedCity_when_regionIsNullOrLongForm() {
        UUID noRegion = insertUser("CLIENT", "Нова Каховка", null);
        UUID longForm = insertUser("CLIENT", "Енергодар", "Запорізька область");

        applyV179();

        assertThat(labels(noRegion)).containsEntry("city", null).containsEntry("region", null);
        assertThat(labels(longForm)).containsEntry("city", null).containsEntry("region", null);
    }

    @Test
    @DisplayName("a legitimate NULL-id CLIENT («Київ») is left byte-for-byte alone")
    void should_leaveLegitimateClient_when_cityIsNotInTheSet() {
        UUID kyivClient = insertUser("CLIENT", "Київ", "Київська");

        applyV179();

        assertThat(labels(kyivClient)).containsEntry("city", "Київ").containsEntry("region", "Київська");
    }

    @Test
    @DisplayName("a namesake in a SERVICED oblast («Василівка, Дніпропетровська») is left alone — the region scopes the match")
    void should_leaveNamesake_when_regionNamesAnotherOblast() {
        UUID namesake = insertUser("CLIENT", "Василівка", "Дніпропетровська");

        applyV179();

        assertThat(labels(namesake)).containsEntry("city", "Василівка").containsEntry("region", "Дніпропетровська");
    }

    @Test
    @DisplayName("free Донецька settlements (Краматорськ, Слов'янськ, Лиман) are never cleared — per-settlement filter, not per-oblast")
    void should_leaveFreeDonetskSettlements_when_regionIsDonetska() {
        UUID kramatorsk = insertUser("CLIENT", "Краматорськ", "Донецька");
        UUID sloviansk = insertUser("CLIENT", "Слов'янськ", "Донецька");
        UUID lyman = insertUser("CLIENT", "Лиман", "Донецька");

        applyV179();

        assertThat(labels(kramatorsk)).containsEntry("city", "Краматорськ");
        assertThat(labels(sloviansk)).containsEntry("city", "Слов'янськ");
        assertThat(labels(lyman)).containsEntry("city", "Лиман");
    }

    @Test
    @DisplayName("legacy text naming AR Crimea / Sevastopol as the region is cleared with its city")
    void should_clearCrimeanLegacyText_when_regionNamesAWhollyOccupiedTerritory() {
        UUID crimea = insertUser("CLIENT", "Ялта", "Автономна Республіка Крим");
        UUID sevastopol = insertUser("CLIENT", "Севастополь", "м. Севастополь");

        applyV179();

        assertThat(labels(crimea)).containsEntry("city", null).containsEntry("region", null);
        assertThat(labels(sevastopol)).containsEntry("city", null).containsEntry("region", null);
    }

    @Test
    @DisplayName("idempotent: a second application updates zero rows")
    void should_updateNothing_when_appliedTwice() {
        insertUser("CLIENT", "Мелітополь", "Запорізька");
        insertUser("CLIENT", "Київ", null);

        int firstPass = applyV179();
        int secondPass = applyV179();

        assertThat(firstPass).isEqualTo(1);
        assertThat(secondPass).isZero();
    }

    @Test
    @DisplayName("V179's 17 (code, city, oblast) rows ARE V170's code list resolved through V53's inserts")
    void should_matchV170CodesResolvedThroughV53_when_listIsParsed() throws IOException {
        String v170 = read(V170);
        int start = v170.indexOf("CREATE TABLE v170_occupied_city_ids AS");
        List<String> v170Codes = codes(v170.substring(start, v170.indexOf(");", start)));
        Map<String, List<String>> expected = resolveThroughV53(read(V53), v170Codes);

        Map<String, List<String>> actual = new LinkedHashMap<>();
        Matcher row = Pattern.compile("\\('(UA\\d{17})', '([^']+)', '([^']+)'\\)").matcher(v179Sql);
        while (row.find()) {
            actual.put(row.group(1), List.of(row.group(2), row.group(3)));
        }

        assertThat(v170Codes).hasSize(17).doesNotHaveDuplicates();
        assertThat(actual).isEqualTo(expected);
    }

    private static List<String> codes(String sql) {
        List<String> out = new ArrayList<>();
        Matcher m = CODE.matcher(sql);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Map<String, List<String>> resolveThroughV53(String v53, List<String> cityCodes) {
        Map<String, String> oblastNames = new LinkedHashMap<>();
        Matcher oblast = Pattern.compile(
                "INSERT INTO oblasts \\(katotth_code, name_uk, name_en\\) VALUES \\('(UA\\d{17})', '([^']+)'")
                .matcher(v53);
        while (oblast.find()) {
            oblastNames.put(oblast.group(1), oblast.group(2));
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String code : cityCodes) {
            Matcher city = Pattern.compile(
                    "SELECT o\\.id, '" + code + "', '([^']+)', '[^']+'\\s*\\n"
                            + "FROM oblasts o WHERE o\\.katotth_code = '(UA\\d{17})'").matcher(v53);
            assertThat(city.find()).as("V53 must have inserted %s", code).isTrue();
            out.put(code, List.of(city.group(1), oblastNames.get(city.group(2))));
        }
        return out;
    }

    @Test
    @DisplayName("V179 is recorded as a successful Flyway migration")
    void should_recordV179AsSuccessful_when_containerBoots() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '179'", Boolean.class);

        assertThat(success).isTrue();
    }
}
