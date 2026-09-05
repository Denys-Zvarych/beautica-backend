package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.assertj.core.api.SoftAssertions;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Perf LOW-C — SQL-shape gate for the rotate-admin sibling picker
 * ({@link SalonService#getSiblingSalons}).
 *
 * <h2>Why this gate exists</h2>
 * The whole justification for this read path is "ONE statement, four columns, no {@code users}
 * join". Every other test in the salon package is blind to it:
 * {@link SalonSiblingSalonsEndpointIT} asserts the wire shape,
 * {@link SalonSiblingRotationParityIT} asserts the predicate, {@code SalonServiceSiblingSalonsTest}
 * asserts the wiring against a mocked repository — and all three stay green if a future
 * {@code JOIN FETCH s.owner} (or a revert to {@code SELECT s FROM Salon s}) drags the 38-column
 * {@code users} row, {@code password_hash} included, back into every picker open. Two earlier
 * removals of a dead {@code LEFT JOIN FETCH s.owner} in this codebase shipped with no gate and one
 * of them was silently re-added; {@code MasterOwnerFetchContractIT} exists for the same reason.
 *
 * <h2>What it asserts — properties, never a SQL string</h2>
 * <ol>
 *   <li><b>One statement.</b> A picker open must not be an N+1 or a two-query
 *       resolve-then-read.</li>
 *   <li><b>Four projected columns.</b> The select list's arity, not its text — it must equal
 *       {@link SiblingSalonOption}'s component count. A widened DTO or a reverted
 *       {@code SELECT s} (22 {@code salons} columns) fails here whatever the columns are named.</li>
 *   <li><b>No {@code users} table anywhere in the emitted SQL.</b> This is what
 *       {@code JOIN FETCH s.owner} would reintroduce; the owner predicate is expressed as
 *       {@code s.owner.id}, which compiles to the {@code owner_id} FK column with no join.</li>
 *   <li><b>Zero hydrated entities.</b> The independent signal, and the one a statement count is
 *       structurally blind to: widening an existing join adds no statement. A constructor
 *       projection puts nothing in the persistence context, so any revert to an entity-returning
 *       query shows up here as a non-zero count even if the SQL text is unrecognisable.</li>
 * </ol>
 *
 * <h2>Harness</h2>
 * The dependency-free Hibernate {@link StatementInspector} capture already proven by
 * {@code UserDynamicUpdateShapeIT} and {@code LocalityDiscoveryPerfHardeningTest}, plus the
 * {@link Statistics} entity-load counter from {@code MasterOwnerFetchContractIT}. Fixtures are
 * inserted with raw JDBC, which the inspector does not see, so the sink holds exactly the
 * statements the service issued.
 */
@Import(SalonSiblingProjectionShapeIT.SqlCaptureConfig.class)
@DisplayName("SalonService.getSiblingSalons — emitted SQL shape (Perf LOW-C)")
class SalonSiblingProjectionShapeIT extends AbstractIntegrationTest {

    static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

    /** Matches the {@code users} table (or an alias of it) as a whole word, not as a substring. */
    private static final Pattern USERS_TABLE = Pattern.compile("\\busers\\b");

    /** {@link SiblingSalonOption} carries id + name + street + buildingNo, and nothing else. */
    private static final int PROJECTED_COLUMNS = 4;

    @Autowired
    private SalonService salonService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory emf;

    private SalonItFixtures fixtures;

    @BeforeEach
    void resetCapture() {
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
        CAPTURED_SQL.clear();
    }

    @Test
    @DisplayName("one statement, four columns, no users table, no hydrated entity")
    void should_emitOneFourColumnSalonsSelect_when_listingSiblings() {
        // Arrange — two real siblings, so an empty result cannot make any assertion vacuous.
        UUID ownerId = fixtures.insertUser(
                "owner-shape-sql-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "SQL Shape Source Salon");
        fixtures.insertSalon(ownerId, "SQL Shape Sibling One");
        fixtures.insertSalon(ownerId, "SQL Shape Sibling Two");

        Statistics statistics = statistics();
        statistics.clear();
        CAPTURED_SQL.clear();

        // Act — the real service path, outside any test-managed transaction.
        List<SiblingSalonOption> siblings = salonService.getSiblingSalons(sourceSalonId);
        long hydratedEntities = statistics.getEntityLoadCount();
        List<String> captured = List.copyOf(CAPTURED_SQL);

        // Assert
        assertThat(siblings)
                .as("premise — both siblings must come back, or this test proves nothing about a "
                        + "query that returned rows")
                .hasSize(2);

        // Soft, so a regression reports every signal it trips rather than only the first.
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(captured)
                    .as("a picker open is ONE statement — captured=%s", captured)
                    .hasSize(1);

            String sql = captured.isEmpty() ? "" : captured.get(0).toLowerCase(Locale.ROOT);

            softly.assertThat(selectListArity(sql))
                    .as("the select list must project exactly SiblingSalonOption's %s components. A "
                            + "rise means the DTO widened or the query reverted to `SELECT s` (22 "
                            + "salons columns) — sql=%s", PROJECTED_COLUMNS, sql)
                    .isEqualTo(PROJECTED_COLUMNS);

            softly.assertThat(captured.stream().anyMatch(s -> USERS_TABLE.matcher(s.toLowerCase(Locale.ROOT)).find()))
                    .as("PRIMARY signal — no statement on this path may touch the users table. True "
                            + "here means an association (JOIN FETCH s.owner, or an owner path "
                            + "expression that is not the FK) came back, dragging 38 users columns "
                            + "including password_hash into every picker open — captured=%s", captured)
                    .isFalse();

            softly.assertThat(hydratedEntities)
                    .as("SECONDARY signal — a constructor projection hydrates NOTHING into the "
                            + "persistence context. Non-zero means the query returns entities again; "
                            + "this signal survives even a widened join, which adds no statement and "
                            + "so is invisible to the count above")
                    .isZero();
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Number of expressions in the outer {@code SELECT} list — the arity of the projection, which
     * is the property under test, rather than the column names Hibernate happens to alias.
     *
     * <p>The outer {@code from} is the first one in the statement: the only sub-select in this
     * query is the owner resolution in the {@code WHERE} clause, which comes after it. A guard
     * below asserts that premise instead of trusting it, so a future query that puts a sub-select
     * in the target list fails loudly here rather than being miscounted.
     */
    private int selectListArity(String lowercaseSql) {
        int from = lowercaseSql.indexOf(" from ");
        assertThat(from)
                .as("the captured statement must be a SELECT with a FROM — sql=%s", lowercaseSql)
                .isGreaterThan(0);
        String selectList = lowercaseSql.substring("select ".length(), from);
        assertThat(selectList)
                .as("this arity count assumes a flat select list; a nested expression means the "
                        + "helper must be revisited rather than the assertion relaxed — selectList=%s",
                        selectList)
                .doesNotContain("(");
        return selectList.split(",").length;
    }

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    /**
     * Registers a pure-observer {@link StatementInspector} on the Hibernate
     * {@code SessionFactory} — the same dependency-free capture used by
     * {@code UserDynamicUpdateShapeIT}.
     */
    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        HibernatePropertiesCustomizer siblingSalonSqlCaptureCustomizer() {
            return (Map<String, Object> props) ->
                    props.put("hibernate.session_factory.statement_inspector",
                            new CapturingStatementInspector());
        }
    }

    static final class CapturingStatementInspector implements StatementInspector {
        @Override
        public String inspect(String sql) {
            if (sql != null) {
                CAPTURED_SQL.add(sql);
            }
            return sql;
        }
    }
}
