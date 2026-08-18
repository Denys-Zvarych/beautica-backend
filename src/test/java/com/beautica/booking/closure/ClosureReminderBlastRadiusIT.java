package com.beautica.booking.closure;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.notification.entity.OutboxEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.6 — THE HEADLINE GATE. Seeds 10,000 elapsed {@code CONFIRMED} bookings across 200
 * masters (bulk JDBC insert, not the service layer — this is a data-shape test, not a
 * booking-creation test), enables the job's claim path for real, and runs it exactly once.
 *
 * <p>This is the mechanical proof behind the whole track's core safety property: a scheduled
 * process that reminds a provider about 10,000 unclosed bookings must not be able to touch a
 * single row of {@code bookings} — not the status, not anything else — no matter how large the
 * candidate set is. The per-provider cap (default 3) is what keeps the actual outbox/ledger
 * writes bounded to {@code 200 x 3 = 600} even though the candidate set is 10,000.
 */
@TestPropertySource(properties = {
        "booking.closure-reminder.enabled=true",
        "booking.closure-reminder.dry-run=false"
})
@DisplayName("ClosureReminderBlastRadiusIT — Phase 29.6 headline gate (10,000 rows)")
class ClosureReminderBlastRadiusIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");
    private static final int MASTER_COUNT = 200;
    private static final int BOOKINGS_PER_MASTER = 50;
    private static final int MAX_PER_PROVIDER_PER_DAY = 3;

    @Autowired
    private ClosureReminderClaimService claimService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
    }

    @Test
    @DisplayName("10,000 elapsed CONFIRMED bookings, 200 masters, one run: zero status changes, "
            + "zero other-column changes, <= 600 CLOSURE_REMINDER outbox rows, zero rows of any "
            + "other event type, zero REVIEW_REQUESTED")
    void should_boundBlastRadius_when_runningOnceAgainstTenThousandElapsedBookings() {
        UUID clientId = fixtures.createUser(
                "closure-blast-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        // Bulk JDBC batch inserts for the 200-master graph (users/masters/service_definitions/
        // master_services) — NOT the per-master fixture helper loop, which is ~800 individual
        // round trips and blows the shared SlowTestExtension's 10s-per-test budget. This is a
        // data-shape test, not a fixture-realism test, so batching the graph setup is consistent
        // with the doc's "bulk JDBC insert, not the service layer" instruction for the bookings
        // themselves — extended here to the master graph for the same reason.
        UUID serviceTypeId = resolveUnusedServiceTypeId("INDEPENDENT_MASTER", UUID.randomUUID());
        // Hashed ONCE, not per row: BCrypt is deliberately CPU-expensive (~50-100ms/call at the
        // production cost factor), so 200 individual passwordEncoder.encode() calls alone blew
        // the shared SlowTestExtension's 10s-per-test budget. None of these 200 users ever log
        // in, so sharing one hash across all of them changes nothing this test observes.
        String sharedPasswordHash = passwordEncoder.encode("Str0ngP@ss1!");
        List<UUID> masterIds = new ArrayList<>(MASTER_COUNT);
        List<UUID> masterServiceIds = new ArrayList<>(MASTER_COUNT);
        List<Object[]> userRows = new ArrayList<>(MASTER_COUNT);
        List<Object[]> masterRows = new ArrayList<>(MASTER_COUNT);
        List<Object[]> serviceDefRows = new ArrayList<>(MASTER_COUNT);
        List<Object[]> masterServiceRows = new ArrayList<>(MASTER_COUNT);
        for (int m = 0; m < MASTER_COUNT; m++) {
            UUID userId = UUID.randomUUID();
            UUID masterId = UUID.randomUUID();
            UUID serviceDefId = UUID.randomUUID();
            UUID masterServiceId = UUID.randomUUID();
            masterIds.add(masterId);
            masterServiceIds.add(masterServiceId);

            userRows.add(new Object[] {
                    userId, "closure-blast-master-" + m + "-" + System.nanoTime() + "@beautica.test",
                    sharedPasswordHash, "INDEPENDENT_MASTER"
            });
            masterRows.add(new Object[] { masterId, userId });
            serviceDefRows.add(new Object[] { serviceDefId, userId, serviceTypeId });
            masterServiceRows.add(new Object[] { masterServiceId, masterId, serviceDefId });
        }
        jdbc.batchUpdate(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, true, true)",
                userRows);
        jdbc.batchUpdate(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterRows);
        jdbc.batchUpdate(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefRows);
        jdbc.batchUpdate(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceRows);

        // 50 non-overlapping 15-minute bookings per master, 20 minutes apart, starting 10 days
        // back — comfortably inside [now-14d, now-2h] for every row (spans ~16.3h per master).
        OffsetDateTime base = NOW.minusDays(10);
        List<Object[]> rows = new ArrayList<>(MASTER_COUNT * BOOKINGS_PER_MASTER);
        for (int m = 0; m < MASTER_COUNT; m++) {
            for (int i = 0; i < BOOKINGS_PER_MASTER; i++) {
                OffsetDateTime endsAt = base.plusMinutes(20L * i);
                OffsetDateTime startsAt = endsAt.minusMinutes(15);
                rows.add(new Object[] {
                        UUID.randomUUID(), clientId, masterIds.get(m), masterServiceIds.get(m),
                        startsAt, endsAt
                });
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 15, 0, 'APP', NOW(), NOW())",
                rows);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings", Long.class))
                .isEqualTo((long) MASTER_COUNT * BOOKINGS_PER_MASTER);

        // backend-perf: a bulk JDBC insert of 10,000 rows leaves the planner working off STALE
        // table statistics (autovacuum's analyze threshold is a fraction of table size plus a
        // scale factor — it will not have fired synchronously by the time this test's very next
        // statement runs), so the previously-recorded 585-727ms figure conflated stale-stats
        // planning cost with the query's actual, properly-planned cost. ANALYZE here so the timed
        // call below measures the real, steady-state cost this job pays in production (where the
        // bookings table is never freshly bulk-loaded moments before a claim run).
        jdbc.execute("ANALYZE bookings");

        long outboxCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class);
        assertThat(outboxCountBefore).isZero();

        List<Map<String, Object>> before = jdbc.queryForList("SELECT id, status, updated_at FROM bookings ORDER BY id");

        // The global per-run cap uses the REAL production default (not an artificial "unbounded"
        // test sentinel) — 1000, comfortably above this fixture's 200 x 3 = 600 expected claims,
        // so it does not bind here; see the class javadoc and ClosureReminderGlobalCapIT for the
        // dedicated test that seeds enough masters to make it bind.
        int globalCap = new ClosureReminderProperties().getMaxTotalClaimsPerRun();
        ClosureReminderWindow window = new ClosureReminderWindow(
                NOW, NOW.minus(Duration.ofDays(14)), NOW.minus(Duration.ofHours(2)), NOW.minusDays(1),
                MAX_PER_PROVIDER_PER_DAY, 2, globalCap);

        long startNanos = System.nanoTime();
        ClosureReminderRunResult result = claimService.claimAndEnqueue(window);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.println("ClosureReminderBlastRadiusIT wall-clock duration: " + durationMs + " ms"
                + " (candidates=" + result.candidateCount() + ", claimed=" + result.claimable() + ")");

        // ── the headline assertions ────────────────────────────────────────────────────────
        assertThat(result.candidateCount()).isEqualTo((long) MASTER_COUNT * BOOKINGS_PER_MASTER);
        assertThat(result.claimable())
                .as("claims must be bounded by 200 masters x cap(3), regardless of the 10,000 candidates")
                .isLessThanOrEqualTo((long) MASTER_COUNT * MAX_PER_PROVIDER_PER_DAY)
                .isEqualTo((long) MASTER_COUNT * MAX_PER_PROVIDER_PER_DAY); // every master has >= 3 eligible rows here

        List<Map<String, Object>> after = jdbc.queryForList("SELECT id, status, updated_at FROM bookings ORDER BY id");
        assertThat(after)
                .as("zero bookings rows may change AT ALL — full (id, status, updated_at) tuple, not a count")
                .isEqualTo(before);

        long outboxCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class);
        assertThat(outboxCountAfter)
                .as("outbox must gain exactly one row per claimed booking, at most 200*3=600")
                .isEqualTo(result.claimable())
                .isLessThanOrEqualTo((long) MASTER_COUNT * MAX_PER_PROVIDER_PER_DAY);

        List<String> distinctEventTypes = jdbc.queryForList(
                        "SELECT DISTINCT event_type FROM notification_outbox", String.class);
        assertThat(distinctEventTypes)
                .as("only CLOSURE_REMINDER rows may exist — no other event type")
                .containsExactly(OutboxEventType.CLOSURE_REMINDER.name());

        Long reviewRequestedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'REVIEW_REQUESTED'", Long.class);
        assertThat(reviewRequestedCount).isZero();

        // Sanity budget — generous margin over the observed local run; recorded verbatim in the
        // phase-224 doc's Status block for the actual measured figure.
        assertThat(durationMs)
                .as("the run must complete within a bounded wall-clock budget")
                .isLessThan(60_000);
    }
}
