package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.OccupiedSettlementCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V180 — resolves pre-V54 free-text {@code users.city/region} against the serviced settlement set
 * ({@code cities}), clears whatever a PROVIDER still cannot resolve (public via
 * {@code MasterDetailResponse}), and clears a CLIENT's private text only when it places them in
 * occupied territory.
 *
 * <p>Same harness as {@link V177SalonsCityBackfillMigrationTest}: the shared container ran V180 on
 * an empty schema, so each test seeds legacy-shaped rows and re-applies V180's exact SQL body.
 */
@DisplayName("V180 migration — legacy free-text locality: resolve against the serviced set, clear the rest where public")
class V180LegacyFreeTextLocalityMigrationTest extends AbstractIntegrationTest {

    private static final String V180 = "db/migration/V180__resolve_or_clear_legacy_free_text_locality.sql";

    private String v180Sql;

    @BeforeEach
    void loadSql() throws IOException {
        try (InputStream in = new ClassPathResource(V180).getInputStream()) {
            v180Sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int applyV180() {
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (var stmt = conn.createStatement()) {
                int total = 0;
                boolean isResultSet = stmt.execute(v180Sql);
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
                throw new IllegalStateException("V180 re-application failed", e);
            }
        });
    }

    private UUID insertUser(String role, String city, String region) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, city, region, city_id) "
                        + "VALUES (?, ?, ?, ?, true, true, ?, ?, NULL)",
                id, "v180-" + id + "@beautica.test", new BCryptPasswordEncoder(4).encode("test-password"),
                role, city, region);
        return id;
    }

    private Map<String, Object> row(UUID userId) {
        return jdbcTemplate.queryForMap("SELECT city, region, city_id FROM users WHERE id = ?", userId);
    }

    private UUID cityIdOf(String cityUk, String oblastUk) {
        return jdbcTemplate.queryForObject(
                "SELECT c.id FROM cities c JOIN oblasts o ON o.id = c.oblast_id WHERE c.name_uk = ? AND o.name_uk = ?",
                UUID.class, cityUk, oblastUk);
    }

    @Test
    @DisplayName("a provider «Донецьк» (occupied, never in cities) with NULL city_id is cleared")
    void should_clearProvider_when_textNamesNoServicedSettlement() {
        UUID master = insertUser("INDEPENDENT_MASTER", "Донецьк", "Донецька");

        applyV180();

        assertThat(row(master)).containsEntry("city", null).containsEntry("region", null)
                .containsEntry("city_id", null);
    }

    @Test
    @DisplayName("a provider «Київ» typed by hand gets its city_id backfilled and keeps its label")
    void should_backfillCityId_when_providerTextNamesAServicedCity() {
        UUID master = insertUser("INDEPENDENT_MASTER", "Київ", "Київ");

        applyV180();

        assertThat(row(master))
                .containsEntry("city", "Київ")
                .containsEntry("region", "Київ")
                .containsEntry("city_id", cityIdOf("Київ", "Київ"));
    }

    @Test
    @DisplayName("a CLIENT «Київ» with NULL id and no region is kept (private, not occupied)")
    void should_keepClient_when_unresolvedTextIsNotOccupied() {
        UUID client = insertUser("CLIENT", "Київ", null);

        applyV180();

        assertThat(row(client)).containsEntry("city", "Київ").containsEntry("city_id", null);
    }

    @Test
    @DisplayName("Краматорськ and a hand-typed Слов'янськ (ASCII apostrophe) are kept and resolved, for providers AND clients")
    void should_keepFreeDonetskCities_when_theyResolveInTheServicedSet() {
        UUID kramatorskMaster = insertUser("INDEPENDENT_MASTER", "Краматорськ", "Донецька");
        UUID slovianskMaster = insertUser("SALON_OWNER", "Слов'янськ", "Донецька область");
        UUID slovianskClient = insertUser("CLIENT", "Слов'янськ", "Донецька");

        applyV180();

        assertThat(row(kramatorskMaster))
                .containsEntry("city", "Краматорськ")
                .containsEntry("city_id", cityIdOf("Краматорськ", "Донецька"));
        assertThat(row(slovianskMaster))
                .as("apostrophe normalised to the classifier's U+2019 and the label re-derived")
                .containsEntry("city", "Слов’янськ")
                .containsEntry("region", "Донецька")
                .containsEntry("city_id", cityIdOf("Слов’янськ", "Донецька"));
        assertThat(row(slovianskClient)).containsEntry("city", "Слов’янськ");
    }

    @Test
    @DisplayName("a CLIENT in a partly occupied oblast is cleared only when the city is not serviced there")
    void should_clearClientOnlyForUnservicedCity_when_regionIsPartlyOccupied() {
        UUID occupied = insertUser("CLIENT", "Донецьк", "Донецька");
        UUID ambiguousButServiced = insertUser("CLIENT", "Лиман", "Донецька");

        applyV180();

        assertThat(row(occupied)).containsEntry("city", null).containsEntry("region", null);
        assertThat(row(ambiguousButServiced))
                .as("two serviced «Лиман» in Донецька — step 1 skips it, step 3 must still keep it")
                .containsEntry("city", "Лиман")
                .containsEntry("city_id", null);
    }

    @Test
    @DisplayName("a CLIENT whose region is AR Crimea is cleared")
    void should_clearClient_when_regionIsCrimea() {
        UUID client = insertUser("CLIENT", "Ялта", "Автономна Республіка Крим");

        applyV180();

        assertThat(row(client)).containsEntry("city", null).containsEntry("region", null);
    }

    private static final String ZAPORIZKA = "Запорізька";

    /** A non-CITY settlement whose name is unique inside Запорізька — the occupied-namesake shape. */
    private String uniqueVillageInZaporizka() {
        return jdbcTemplate.queryForObject("""
                SELECT c.name_uk
                  FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                 WHERE o.name_uk = ?
                 GROUP BY c.name_uk
                HAVING count(*) = 1 AND bool_and(c.settlement_type <> 'CITY')
                 ORDER BY c.name_uk
                 LIMIT 1
                """, String.class, ZAPORIZKA);
    }

    @Test
    @DisplayName("a unique VILLAGE namesake in a partly occupied oblast is never bound — provider cleared, CLIENT keeps its text")
    void should_notBindVillageNamesake_when_regionIsPartlyOccupied() {
        String village = uniqueVillageInZaporizka();
        UUID provider = insertUser("INDEPENDENT_MASTER", village, ZAPORIZKA);
        UUID client = insertUser("CLIENT", village, ZAPORIZKA);

        applyV180();

        assertThat(row(provider))
                .as("the text may name an OCCUPIED place whose only free namesake is this village")
                .containsEntry("city_id", null)
                .containsEntry("city", null)
                .containsEntry("region", null);
        assertThat(row(client))
                .as("step 3: the text still names a serviced settlement, so a CLIENT keeps it — unbound")
                .containsEntry("city_id", null)
                .containsEntry("city", village)
                .containsEntry("region", ZAPORIZKA);
    }

    @Test
    @DisplayName("counter-case: a real CITY match in a partly occupied oblast («Запоріжжя») still binds")
    void should_bindCity_when_partlyOccupiedOblastMatchIsACity() {
        UUID provider = insertUser("INDEPENDENT_MASTER", "Запоріжжя", ZAPORIZKA);

        applyV180();

        assertThat(row(provider))
                .containsEntry("city", "Запоріжжя")
                .containsEntry("region", ZAPORIZKA)
                .containsEntry("city_id", cityIdOf("Запоріжжя", ZAPORIZKA));
    }

    private static final String KHARKIVSKA = "Харківська";
    private static final String LOZOVA = "Лозова";

    @Test
    @DisplayName("a CITY that shares its name with a village in the same partly occupied oblast («Лозова») stays unbound — provider cleared, CLIENT keeps its text")
    void should_notBindCity_when_villageNamesakeExistsInSameOblast() {
        // Arrange — precondition: exactly one CITY «Лозова» plus ≥1 non-CITY namesake in Харківська.
        // If the taxonomy ever loses the shape, this fails loudly instead of the test passing vacuously.
        Map<String, Object> shape = jdbcTemplate.queryForMap("""
                SELECT count(*) FILTER (WHERE c.settlement_type = 'CITY')  AS city_rows,
                       count(*) FILTER (WHERE c.settlement_type <> 'CITY') AS non_city_rows
                  FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                 WHERE c.name_uk = ? AND o.name_uk = ?
                """, LOZOVA, KHARKIVSKA);
        assertThat(((Number) shape.get("city_rows")).longValue()).as("CITY «Лозова» rows").isEqualTo(1L);
        assertThat(((Number) shape.get("non_city_rows")).longValue()).as("non-CITY «Лозова» rows").isPositive();
        UUID provider = insertUser("INDEPENDENT_MASTER", LOZOVA, KHARKIVSKA);
        UUID client = insertUser("CLIENT", LOZOVA, KHARKIVSKA);

        // Act
        applyV180();

        // Assert — HAVING count(*) = 1 counts every namesake BEFORE the CITY filter, so the
        // name is ambiguous and never bound; filtering first would bind the CITY by guesswork.
        assertThat(row(provider))
                .as("ambiguous namesake: a provider is cleared by step 2, never bound to the CITY")
                .containsEntry("city_id", null)
                .containsEntry("city", null)
                .containsEntry("region", null);
        assertThat(row(client))
                .as("step 3: the text names a serviced settlement, so a CLIENT keeps it — unbound")
                .containsEntry("city_id", null)
                .containsEntry("city", LOZOVA)
                .containsEntry("region", KHARKIVSKA);
    }

    @Test
    @DisplayName("idempotent: a second application updates zero rows")
    void should_updateNothing_when_appliedTwice() {
        insertUser("INDEPENDENT_MASTER", "Донецьк", "Донецька");
        insertUser("INDEPENDENT_MASTER", "Київ", "Київ");
        insertUser("CLIENT", "Київ", null);
        insertUser("INDEPENDENT_MASTER", uniqueVillageInZaporizka(), ZAPORIZKA);
        insertUser("CLIENT", uniqueVillageInZaporizka(), ZAPORIZKA);

        int firstPass = applyV180();
        int secondPass = applyV180();

        assertThat(firstPass)
                .as("Донецьк cleared, Київ bound, the village provider cleared; both CLIENTs untouched")
                .isEqualTo(3);
        assertThat(secondPass).isZero();
    }

    @Test
    @DisplayName("V180's partly-occupied oblast codes ARE the oblasts whose prefix occurs in the exclusion set")
    void should_matchExclusionSetPrefixes_when_oblastListIsParsed() {
        Set<String> prefixes = OccupiedSettlementCodes.load().stream()
                .map(code -> code.substring(0, 4))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> expected = new TreeSet<>(jdbcTemplate.queryForList(
                "SELECT katotth_code FROM oblasts WHERE substr(katotth_code, 1, 4) IN ("
                        + prefixes.stream().map(p -> "'" + p + "'").collect(Collectors.joining(","))
                        + ")", String.class));

        assertThat(expected).as("every prefix must name a seeded oblast").hasSize(prefixes.size());
        assertThat(codesAfter("o.katotth_code NOT IN ("))
                .as("step 1's CITY-only guard list").isEqualTo(expected);
        assertThat(codesAfter("o.katotth_code IN ("))
                .as("step 3's CLIENT clearing list").isEqualTo(expected);
    }

    private Set<String> codesAfter(String marker) {
        int start = v180Sql.indexOf(marker);
        assertThat(start).as("V180 must contain %s", marker).isNotNegative();
        String listBlock = v180Sql.substring(start, v180Sql.indexOf(')', start));
        Set<String> codes = new TreeSet<>();
        Matcher code = Pattern.compile("'(UA\\d{17})'").matcher(listBlock);
        while (code.find()) {
            codes.add(code.group(1));
        }
        return codes;
    }

    @Test
    @DisplayName("V180 is recorded as a successful Flyway migration")
    void should_recordV180AsSuccessful_when_containerBoots() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '180'", Boolean.class);

        assertThat(success).isTrue();
    }
}
