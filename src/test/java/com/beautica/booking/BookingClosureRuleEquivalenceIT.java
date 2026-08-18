package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.1 — the anti-drift proof for {@link BookingClosureRule}'s two representations. Seeds
 * one row per {@link BookingStatus} x {@code endsAt} in {@code {NOW - 1h, NOW, NOW + 1h}} (15
 * rows), evaluates {@link BookingClosureRule#awaitingClosure(OffsetDateTime)} as a real Postgres
 * query and {@link BookingClosureRule#isAwaitingClosure(BookingStatus, OffsetDateTime,
 * OffsetDateTime)} in memory over the SAME 15 entities, and asserts the two id sets are identical.
 *
 * <p>Real Postgres (Testcontainers) via {@link AbstractIntegrationTest}. Own class-local frozen
 * {@link Clock} — deliberately NOT {@code BookingMyBookingsPartitionIT}'s {@code FrozenClockConfig}
 * (Phase 28.3's isolation mechanism: sharing a {@code @TestConfiguration} class would merge the two
 * suites' Spring test contexts).
 */
@Import(BookingClosureRuleEquivalenceIT.FrozenClockConfig.class)
@DisplayName("BookingClosureRule — Phase 29.1 SQL/in-memory equivalence proof")
class BookingClosureRuleEquivalenceIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");

    @TestConfiguration
    static class FrozenClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
    }

    @Test
    @DisplayName("SQL side (awaitingClosure Specification) and in-memory side (isAwaitingClosure) "
            + "agree on the exact same id set over a 15-row status x endsAt-position matrix; the SQL "
            + "side returns exactly 1 row — the CONFIRMED / NOW-1h row")
    void should_agreeOnIdentitySet_when_evaluatingSqlAndInMemoryFormsOverSameFixture() {
        String masterEmail = "closure-equiv-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "closure-equiv-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        Set<UUID> seededIds = new HashSet<>();
        UUID expectedAwaitingClosureId = null;
        // Each of the three endsAt positions gets its own 1h window (endsAt - 1h, endsAt) — the
        // three windows are back-to-back and therefore mutually non-overlapping, so the single
        // CONFIRMED row landing in each window never trips the no_overlapping_bookings EXCLUDE
        // constraint (which scopes to status = 'CONFIRMED' only). The other four statuses at the
        // same position legitimately share that exact window — status other than CONFIRMED is
        // unconstrained by that EXCLUDE, and the closure rule cares only about (status, endsAt).
        for (OffsetDateTime endsAt : new OffsetDateTime[] {NOW.minusHours(1), NOW, NOW.plusHours(1)}) {
            OffsetDateTime startsAt = endsAt.minusHours(1);
            for (BookingStatus status : BookingStatus.values()) {
                UUID id = insertBooking(clientId, masterId, masterServiceId, status, startsAt, endsAt);
                seededIds.add(id);
                if (status == BookingStatus.CONFIRMED && endsAt.equals(NOW.minusHours(1))) {
                    expectedAwaitingClosureId = id;
                }
            }
        }
        assertThat(seededIds).as("fixture sanity: 5 statuses x 3 positions").hasSize(15);
        assertThat(expectedAwaitingClosureId).isNotNull();

        // ── SQL side — BookingClosureRule.awaitingClosure(NOW) as a real Criteria query ─────────
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<UUID> idQuery = cb.createQuery(UUID.class);
        Root<Booking> root = idQuery.from(Booking.class);
        Predicate idInSeeded = root.get("id").in(seededIds);
        Predicate closurePredicate = BookingClosureRule.awaitingClosure(NOW).toPredicate(root, idQuery, cb);
        idQuery.select(root.get("id")).where(cb.and(idInSeeded, closurePredicate));
        List<UUID> sqlIds = entityManager.createQuery(idQuery).getResultList();
        Set<UUID> sqlIdSet = new HashSet<>(sqlIds);

        // ── in-memory side — isAwaitingClosure evaluated over the SAME entities, reloaded fresh ──
        List<Booking> reloaded = bookingRepository.findAllById(seededIds);
        Set<UUID> inMemoryIdSet = new HashSet<>();
        for (Booking booking : reloaded) {
            if (BookingClosureRule.isAwaitingClosure(booking.getStatus(), booking.getEndsAt(), NOW)) {
                inMemoryIdSet.add(booking.getId());
            }
        }

        assertThat(sqlIdSet)
                .as("SQL and in-memory forms must agree on the exact same id set — the anti-drift proof")
                .isEqualTo(inMemoryIdSet)
                .containsExactly(expectedAwaitingClosureId)
                .hasSize(1);
    }

    /** Inserts a booking row directly via SQL, bypassing the create/decline/complete flows. */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, BookingStatus status,
                                OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID bookingId = UUID.randomUUID();
        int minutes = (int) java.time.Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, status.name(), startsAt, endsAt, minutes);
        return bookingId;
    }
}
