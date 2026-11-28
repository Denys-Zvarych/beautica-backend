package com.beautica.booking.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.review.config.ClientRatingProperties;
import com.beautica.review.service.ClientRatingEligibility;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@code V271__bookings_completed_rateable_partial_indexes.sql}: both partial indexes exist with
 * the {@code client_id IS NOT NULL OR guest_phone IS NOT NULL} predicate, and the planner can use them for the
 * pending-count {@code toRateClient} query in BOTH guest-flag states.
 *
 * <p>No hand-written SQL: each plan is taken from the statement Hibernate actually emits when the REAL {@link
 * PendingBookingActionsService} (real {@link BookingRepository}, real {@link ClientRatingEligibility} per flag value;
 * only the scope resolvers on {@link BookingService} are stubbed) runs its count — captured with the project's
 * {@link StatementInspector} pattern ({@code ClientBookingIdPageNoSentinelIT}). Its {@code ?} binds are inlined as
 * literals (scope UUID, {@code 'COMPLETED'}), i.e. the custom plan the driver gets for the first executions.
 *
 * <p>Applicability probe (V263's pattern): inside a rolled-back transaction every other plain bookings index is
 * dropped and seq scans are priced out. The control proves the probe is not vacuous: V191's {@code client_id IS NOT
 * NULL} index cannot serve the flag-on form. Lives in {@code booking.service} (not {@code migration}) because the
 * scope resolvers it stubs are package-private — same reason V263's test sits next to {@code ClientCardSql}.
 */
@Import(V271BookingsCompletedRateableIndexesMigrationTest.SqlCaptureConfig.class)
@DisplayName("V271 migration — partial indexes serve the pending toRateClient count in both guest-flag states")
class V271BookingsCompletedRateableIndexesMigrationTest extends AbstractIntegrationTest {

    static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

    private static final String MASTER_INDEX = "idx_bookings_master_completed_rateable";
    private static final String SALON_INDEX = "idx_bookings_salon_completed_rateable";
    private static final String V191_MASTER_INDEX = "idx_bookings_master_completed_client";
    private static final Pattern BIND = Pattern.compile("\\?");
    private static final int NON_MATCHING_ROWS = 2000;
    private static final int MATCHING_ROWS = 5;

    /** Plain (non-constraint) bookings indexes other than {@code keep}. */
    private static final String OTHER_INDEXES = """
            SELECT quote_ident(i.relname) FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
             WHERE x.indrelid = 'bookings'::regclass
               AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid)
               AND i.relname <> ?
            """;

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("both V271 indexes are valid partial btrees on (col) INCLUDE (id) with the rateable predicate")
    void should_createBothRateableIndexes_when_v271Applied() {
        // Act
        String master = indexDef(MASTER_INDEX);
        String salon = indexDef(SALON_INDEX);

        // Assert
        assertThat(master).contains("(master_id) INCLUDE (id)").contains("COMPLETED")
                .contains("(client_id IS NOT NULL) OR (guest_phone IS NOT NULL)");
        assertThat(salon).contains("(salon_id) INCLUDE (id)").contains("COMPLETED")
                .contains("(client_id IS NOT NULL) OR (guest_phone IS NOT NULL)");
        assertThat(validBtrees(MASTER_INDEX, SALON_INDEX)).isEqualTo(2);
    }

    @Test
    @DisplayName("master scope, guest flag OFF: the emitted count can read idx_bookings_master_completed_rateable")
    void should_applyMasterIndex_when_flagOffCountExplained() {
        // Act
        String plan = explain(masterCountSql(false), MASTER_INDEX, false);

        // Assert
        assertThat(plan).as(plan).contains("Bitmap Index Scan on " + MASTER_INDEX).doesNotContain("Seq Scan on bookings");
    }

    @Test
    @DisplayName("master scope, guest flag ON: the emitted count can read idx_bookings_master_completed_rateable")
    void should_applyMasterIndex_when_flagOnCountExplained() {
        // Act
        String plan = explain(masterCountSql(true), MASTER_INDEX, false);

        // Assert
        assertThat(plan).as(plan).contains("Bitmap Index Scan on " + MASTER_INDEX).doesNotContain("Seq Scan on bookings");
    }

    @Test
    @DisplayName("salon scope, guest flag ON: the emitted count can read idx_bookings_salon_completed_rateable")
    void should_applySalonIndex_when_flagOnSalonCountExplained() {
        // Act
        String plan = explain(salonCountSql(true), SALON_INDEX, false);

        // Assert
        assertThat(plan).as(plan).contains("Bitmap Index Scan on " + SALON_INDEX).doesNotContain("Seq Scan on bookings");
    }

    @Test
    @DisplayName("master scope, guest flag ON, bitmap scans off: the emitted count is an Index Only Scan on "
            + "idx_bookings_master_completed_rateable (query predicate == index predicate, qual stripped)")
    void should_planIndexOnlyScan_when_flagOnCountExplainedWithBitmapOff() {
        // Arrange — real, VACUUM-ed stats. On the empty shared table every index is costed at the 10-page default
        // with allvisfrac = 0, so the qual-less bookings_pkey scan, a plain index scan and an index-only scan on the
        // partial index all tie and the pick depends on whatever stats earlier test classes left behind (it passed in
        // a batch after the parity ITs, failed alone). Many non-matching rows make the partial index the cheapest
        // path; VACUUM sets the visibility map so index-only beats a plain index scan. cleanDb() deletes the rows.
        UUID masterId = seedMasterWithMostlyNonMatchingRows();
        jdbcTemplate.execute("VACUUM (ANALYZE) bookings");

        // Act
        String plan = explain(masterCountSql(true, masterId), MASTER_INDEX, true);

        // Assert
        assertThat(plan).as(plan).contains("Index Only Scan using " + MASTER_INDEX + " on bookings")
                .doesNotContain("Seq Scan on bookings");
    }

    @Test
    @DisplayName("control: V191's client_id-only index cannot serve the emitted flag-ON count (why V271 exists)")
    void should_notApplyV191Index_when_flagOnCountExplained() {
        // Act
        String flagOn = explain(masterCountSql(true), V191_MASTER_INDEX, false);
        String flagOff = explain(masterCountSql(false), V191_MASTER_INDEX, false);

        // Assert
        assertThat(flagOn).as(flagOn).doesNotContain(V191_MASTER_INDEX);
        assertThat(flagOff).as("probe sanity: V191 does serve the flag-OFF form\n%s", flagOff)
                .contains("Bitmap Index Scan on " + V191_MASTER_INDEX);
    }

    // ── capture: run the real service, take the toRateClient statement ─────────────────────────────

    private String masterCountSql(boolean guestFlag) {
        return masterCountSql(guestFlag, UUID.randomUUID());
    }

    private String masterCountSql(boolean guestFlag, UUID masterId) {
        BookingService bookingService = stubbedBookingService();
        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.isActive()).thenReturn(true);
        UUID actorId = UUID.randomUUID();
        when(bookingService.resolveProviderMasterScope(Role.INDEPENDENT_MASTER, actorId)).thenReturn(master);
        var auth = new UsernamePasswordAuthenticationToken(actorId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.INDEPENDENT_MASTER.name())));
        auth.setDetails(actorId);
        return capture(masterId, () -> service(bookingService, guestFlag).countForMe(actorId, auth, false));
    }

    /** One independent master: {@value #NON_MATCHING_ROWS} future CONFIRMED rows + {@value #MATCHING_ROWS} COMPLETED. */
    private UUID seedMasterWithMostlyNonMatchingRows() {
        BookingTestFixtures fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        UUID masterId = fixtures.createIndependentMaster("v271-ios-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        UUID clientId = fixtures.createUser("v271-ios-c-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        String insert = """
                INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source,
                    created_at, updated_at)
                SELECT gen_random_uuid(), ?, ?, ?, ?, ?::timestamptz + g * interval '2 hours',
                       ?::timestamptz + g * interval '2 hours' + interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW()
                  FROM generate_series(1, ?) g
                """;
        OffsetDateTime base = OffsetDateTime.parse("2026-11-28T12:00:00Z");
        jdbcTemplate.update(insert, clientId, masterId, serviceId, "CONFIRMED",
                base.plusYears(1), base.plusYears(1), NON_MATCHING_ROWS);
        jdbcTemplate.update(insert, clientId, masterId, serviceId, "COMPLETED",
                base.minusYears(1), base.minusYears(1), MATCHING_ROWS);
        return masterId;
    }

    private String salonCountSql(boolean guestFlag) {
        UUID salonId = UUID.randomUUID();
        BookingService bookingService = stubbedBookingService();
        return capture(salonId, () -> service(bookingService, guestFlag).countForSalon(salonId));
    }

    private static BookingService stubbedBookingService() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.resolveNow()).thenReturn(OffsetDateTime.parse("2026-11-28T12:00:00Z"));
        return bookingService;
    }

    private PendingBookingActionsService service(BookingService bookingService, boolean guestFlag) {
        return new PendingBookingActionsService(bookingRepository, bookingService,
                new ClientRatingEligibility(new ClientRatingProperties(guestFlag)));
    }

    /** Runs {@code call}, returns its single client_reviews COUNT with binds inlined (status vs scope id). */
    private String capture(UUID scopeId, Runnable call) {
        CAPTURED_SQL.clear();
        call.run();
        List<String> toRate = CAPTURED_SQL.stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).contains("client_reviews"))
                .toList();
        assertThat(toRate).as("exactly one toRateClient statement captured: %s", CAPTURED_SQL).hasSize(1);
        return inlineBinds(toRate.get(0), scopeId);
    }

    /** Each {@code ?} after a {@code status} reference becomes 'COMPLETED', every other one the scope UUID. */
    private static String inlineBinds(String sql, UUID scopeId) {
        Matcher m = BIND.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String before = sql.substring(Math.max(0, m.start() - 20), m.start()).toLowerCase(Locale.ROOT);
            String literal = before.contains("status") ? "'COMPLETED'" : "'" + scopeId + "'";
            m.appendReplacement(out, Matcher.quoteReplacement(literal));
        }
        m.appendTail(out);
        assertThat(out.toString()).as("every bind inlined").doesNotContain("?");
        return out.toString();
    }

    // ── catalog + plan helpers ─────────────────────────────────────────────────────────────────────

    private String indexDef(String indexName) {
        return jdbcTemplate.query(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?",
                (rs, i) -> rs.getString(1), indexName).stream().findFirst().orElse("");
    }

    private Integer validBtrees(String... names) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid "
                        + "JOIN pg_am am ON am.oid = c.relam "
                        + "WHERE c.relname IN (?, ?) AND i.indisvalid AND am.amname = 'btree'",
                Integer.class, (Object[]) names);
    }

    /**
     * EXPLAIN with only {@code keep} left among the plain bookings indexes and seq scans off. {@code indexOnly}:
     * bitmap scans off (index scans on) so the scan type is visible; otherwise plain index scans off (bitmap only,
     * a pure applicability probe). Always rolled back.
     */
    private String explain(String sql, String keep, boolean indexOnly) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            jdbcTemplate.queryForList(OTHER_INDEXES, String.class, keep)
                    .forEach(index -> jdbcTemplate.execute("DROP INDEX " + index));
            jdbcTemplate.execute(indexOnly ? "SET LOCAL enable_bitmapscan = off" : "SET LOCAL enable_indexscan = off");
            String plan = String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
            status.setRollbackOnly();
            return plan;
        });
    }

    /** Pure-observer {@link StatementInspector} on the Hibernate SessionFactory (ClientBookingIdPageNoSentinelIT). */
    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        HibernatePropertiesCustomizer v271PendingCountSqlCaptureCustomizer() {
            return (Map<String, Object> props) ->
                    props.put("hibernate.session_factory.statement_inspector", new CapturingStatementInspector());
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
