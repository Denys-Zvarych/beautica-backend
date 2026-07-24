package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingSpecifications;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-gate CI guard for Phase 26.7.2 (closes backlog row 141 alongside
 * {@code ClientBookingDetailProjectionTest}): proves — by inspecting the ACTUAL SQL text Hibernate
 * prepares, not by asserting behaviour that happens to be invariant either way — that {@link
 * BookingRepository#findIdsByClientIdFiltered} never regresses back to the {@code (:x IS NULL OR
 * …)} sentinel idiom Phase 26.1/26.7.1 removed (see {@link BookingSpecifications}'s class
 * javadoc for the full ~19x-slower-under-a-forced-generic-plan rationale).
 *
 * <p><b>Why a SQL-text guard, not a committed EXPLAIN-plan test.</b> backend-perf's forced-
 * generic-plan proof (the actual before/after EXPLAIN, recorded in this phase's Status) runs
 * against a 503k-row synthetic harness — reproducing the degradation needs both a forced generic
 * plan (either {@code SET plan_cache_mode = force_generic_plan} or ≥6 executions of a
 * server-prepared statement) AND enough rows that a seq/bitmap scan is actually distinguishable
 * from an index scan. A Testcontainers-scale dataset (tens of rows) gives the planner no reason
 * to prefer one shape over the other regardless of which SQL is emitted, so a plan-shape
 * assertion at this scale would be planner-noise-dependent — exactly the "flaky, small-dataset"
 * trap called out in the phase doc. The SQL TEXT, by contrast, is deterministic and
 * data-size-independent: {@link BookingSpecifications} composes predicates in Java (never emits a
 * dead {@code OR} branch structurally), so asserting the emitted SQL never contains the sentinel
 * phrase is both a strictly stronger and a strictly less flaky guard than re-deriving the EXPLAIN
 * proof at a scale where it cannot mean anything.
 *
 * <p><b>Discriminating property.</b> Each test asserts two things about the SAME captured SQL:
 * (1) it contains ONLY the column references for the predicates actually requested (proving
 * {@code BookingRepositoryCustomImpl#findIdPage}'s dynamic-{@code Specification} composition is
 * still in effect, not a static all-predicates JPQL string), and (2) it never contains the literal
 * {@code "is null or"} sentinel phrase or a {@code cast(...)} OID-inference workaround — the two
 * textual fingerprints of the idiom {@code BookingRepository}'s {@code
 * hydrateClientBookingDetails} javadoc documents as removed. A future change that reintroduces
 * either — e.g. collapsing {@code BookingRepositoryCustomImpl#findIdPage} back into a single
 * static {@code @Query} with {@code (:statuses IS NULL OR b.status IN :statuses)} — makes this
 * test fail immediately, deterministically, on every CI run, independent of row count or Postgres
 * version.
 *
 * <p><b>Verified to actually catch a reintroduced sentinel.</b> Before committing this guard, a
 * hand-written JPQL query using the literal pre-26.1 idiom (
 * {@code SELECT b.id FROM Booking b WHERE b.client.id = :clientId AND (:statuses IS NULL OR
 * b.status IN :statuses) ORDER BY b.startsAt DESC}) was run through the identical
 * {@link StatementInspector} capture + {@code doesNotContainIgnoringCase("is null or")} assertion
 * used below, via a throwaway test method against the same {@code EntityManager}. It failed red
 * (captured SQL contained the literal {@code "is null or"} phrase), confirming the assertion
 * actually discriminates the idiom rather than passing vacuously; the throwaway method was then
 * deleted — it exercised no production code and would have been pure noise in the committed
 * suite. {@link BookingSpecifications} and {@code BookingRepositoryCustomImpl} were never modified
 * for this proof.
 */
@Import(ClientBookingIdPageNoSentinelIT.SqlCaptureConfig.class)
@DisplayName("findIdsByClientIdFiltered — SQL-shape CI guard: no (:x IS NULL OR …) sentinel, no CAST OID-workaround (Phase 26.7.2)")
class ClientBookingIdPageNoSentinelIT extends AbstractIntegrationTest {

    static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private UUID clientId;
    private UUID masterId;
    private UUID serviceId;

    /** A pre-normalized {@code Sort} mirroring {@code BookingService#normalizeBookingSort}'s
     * default output — {@code findIdsByClientIdFiltered} trusts its caller to have already
     * validated/whitelisted the {@code Sort} (see that method's javadoc), so this test supplies
     * exactly the shape production would. */
    private static final Sort NORMALIZED_SORT = Sort.by(Sort.Order.desc("startsAt"), Sort.Order.asc("id"));

    @BeforeEach
    void setUp() {
        CAPTURED_SQL.clear();
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);

        clientId = fixtures.createUser(
                "sentinel-guard-client-" + UUID.randomUUID() + "@beautica.test", "CLIENT", null);
        masterId = fixtures.createIndependentMaster(
                "sentinel-guard-master-" + UUID.randomUUID() + "@beautica.test");
        serviceId = fixtures.createIndependentMasterService(masterId);

        insertBooking(BookingStatus.CONFIRMED, OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        insertBooking(BookingStatus.COMPLETED, OffsetDateTime.of(2026, 8, 5, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    private void insertBooking(BookingStatus status, OffsetDateTime startsAt) {
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, serviceId, status.name(),
                startsAt, startsAt.plusMinutes(60));
    }

    // ── the four filter shapes the phase doc names ────────────────────────────────

    @Test
    @DisplayName("no-filter shape: only client_id predicate emitted — no status/starts_at/master_service_id, no sentinel")
    void should_emitOnlyClientScope_when_noFilterSupplied() {
        Pageable pageable = PageRequest.of(0, 20, NORMALIZED_SORT);

        bookingRepository.findIdsByClientIdFiltered(clientId, null, null, null, null, pageable);
        String sql = capturedIdPageSelect();

        assertShapeAndNoSentinel(sql,
                /* expectClientId */ true, /* expectStatus */ false,
                /* expectStartsAt */ false, /* expectMasterServiceId */ false);
    }

    @Test
    @DisplayName("status-only shape: client_id + status predicates only — no starts_at/master_service_id, no sentinel")
    void should_emitOnlyStatusPredicate_when_statusFilterSupplied() {
        Pageable pageable = PageRequest.of(0, 20, NORMALIZED_SORT);

        bookingRepository.findIdsByClientIdFiltered(
                clientId, Set.of(BookingStatus.CONFIRMED), null, null, null, pageable);
        String sql = capturedIdPageSelect();

        assertShapeAndNoSentinel(sql,
                true, /* expectStatus */ true,
                false, false);
    }

    @Test
    @DisplayName("date-range-only shape: client_id + starts_at predicates only — no status/master_service_id, no sentinel")
    void should_emitOnlyDateRangePredicate_when_dateRangeFilterSupplied() {
        Pageable pageable = PageRequest.of(0, 20, NORMALIZED_SORT);
        OffsetDateTime from = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime toExclusive = OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC);

        bookingRepository.findIdsByClientIdFiltered(clientId, null, from, toExclusive, null, pageable);
        String sql = capturedIdPageSelect();

        assertShapeAndNoSentinel(sql,
                true, false,
                /* expectStartsAt */ true, false);
    }

    @Test
    @DisplayName("all-filters shape: client_id + status + starts_at + master_service_id — every predicate present, still no sentinel")
    void should_emitEveryPredicate_when_allFiltersSuppliedTogether() {
        Pageable pageable = PageRequest.of(0, 20, NORMALIZED_SORT);
        OffsetDateTime from = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime toExclusive = OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC);

        bookingRepository.findIdsByClientIdFiltered(
                clientId, Set.of(BookingStatus.CONFIRMED), from, toExclusive, Set.of(serviceId), pageable);
        String sql = capturedIdPageSelect();

        assertShapeAndNoSentinel(sql,
                true, /* expectStatus */ true,
                /* expectStartsAt */ true, /* expectMasterServiceId */ true);
    }

    // ── shared assertion ───────────────────────────────────────────────────────

    private void assertShapeAndNoSentinel(String sql, boolean expectClientId, boolean expectStatus,
                                          boolean expectStartsAt, boolean expectMasterServiceId) {
        assertThat(sql)
                .as("an id-page SELECT must have been captured, captured=%s", CAPTURED_SQL)
                .isNotNull();

        // Primary guard: the sentinel idiom's textual fingerprint must never appear, on ANY
        // filter shape — this is what fails if (:x IS NULL OR ...) is ever reintroduced.
        assertThat(sql)
                .as("no (:x IS NULL OR ...) sentinel predicate may appear in the client id-page SQL, sql=%s", sql)
                .doesNotContainIgnoringCase("is null or");
        // Secondary guard: the pre-26.7.1 findClientBookingDetails needed an explicit CAST to
        // coax Postgres into inferring the bound from/toExclusive parameter type for its
        // (:x IS NULL OR ...) form (see BookingRepository's hydrateClientBookingDetails javadoc).
        // A reintroduced sentinel is likely to need the same crutch.
        assertThat(sql)
                .as("no CAST(...) OID-inference workaround may appear in the client id-page SQL, sql=%s", sql)
                .doesNotContainIgnoringCase("cast(");

        // The mandatory ORDER BY (a normalized Sort always includes "starts_at desc" — see
        // NORMALIZED_SORT) legitimately contains "starts_at" regardless of whether a date-range
        // predicate was requested, so the shape assertions below must look ONLY at the WHERE
        // clause (everything before "order by"), not the whole statement.
        String wherePart = sql.contains(" order by ") ? sql.substring(0, sql.indexOf(" order by ")) : sql;

        assertThat(wherePart.contains("client_id"))
                .as("client_id scope predicate must always be present, where=%s", wherePart)
                .isEqualTo(expectClientId);
        assertThat(wherePart.contains("status"))
                .as("status predicate present only when a status filter was supplied, where=%s", wherePart)
                .isEqualTo(expectStatus);
        assertThat(wherePart.contains("starts_at"))
                .as("starts_at predicate present only when a date-range filter was supplied, where=%s", wherePart)
                .isEqualTo(expectStartsAt);
        assertThat(wherePart.contains("master_service_id"))
                .as("master_service_id predicate present only when a serviceId filter was supplied, where=%s", wherePart)
                .isEqualTo(expectMasterServiceId);
    }

    /**
     * Returns the single id-page {@code SELECT ... FROM bookings ...} statement captured for the
     * most recent {@code findIdsByClientIdFiltered} call (lower-cased for case-insensitive
     * matching), excluding the sibling {@code COUNT(*)} query the same call also issues. Both the
     * id query and the count query are built from the identical {@link
     * org.springframework.data.jpa.domain.Specification}, so asserting on the id query's shape is
     * representative of both.
     */
    private String capturedIdPageSelect() {
        return CAPTURED_SQL.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.contains("from bookings") && !sql.contains("count("))
                .findFirst()
                .orElse(null);
    }

    /**
     * Registers a pure-observer {@link StatementInspector} on the Hibernate {@code
     * SessionFactory} — the same dependency-free capture pattern already proven by {@code
     * UserDynamicUpdateShapeIT} / {@code LocalityDiscoveryPerfHardeningTest}.
     */
    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        HibernatePropertiesCustomizer clientBookingIdPageSqlCaptureCustomizer() {
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
