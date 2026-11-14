package com.beautica.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@code V240__registered_booking_reminders.sql} (phase 494): the two APP partial indexes exist with
 * the exact predicates, {@code chk_outbox_event} is re-added as a strict superset (the thirteen prior event types plus
 * {@code BOOKING_REMINDER}), and — acceptance 4 — the SQL Hibernate actually emits for both registered finders is
 * planned on the new partial indexes. Catalog text alone could pass on a typo'd literal, so the inserts and the
 * EXPLAIN are the behavioural half.
 */
@DisplayName("V240 migration — APP reminder partial indexes + chk_outbox_event admits BOOKING_REMINDER")
class V240RegisteredBookingRemindersMigrationTest extends AbstractIntegrationTest {

    /** The thirteen values V238 left in the CHECK. */
    private static final Set<String> PRE_494_EVENT_TYPES = Set.of(
            "NEW_BOOKING", "STATUS_CHANGED", "CLIENT_CANCELLED", "INVITE", "BOOKING_RESCHEDULED",
            "REVIEW_REQUESTED", "CLOSURE_REMINDER", "SALON_CLOSED", "MASTER_REMOVED", "INAPP_PUSH",
            "MASTER_BOOKING_RESCHEDULED", "MASTER_BOOKING_DECLINED", "REQUEST_DECIDED");

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
    private static final Pattern BIND = Pattern.compile("\\?");
    private static final Pattern FROM_BOOKINGS = Pattern.compile("(?i)\\bfrom\\s+bookings\\b");
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-06-02T09:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-06-02T11:00:00Z");

    @Autowired private BookingRepository bookingRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "idx_bookings_reminder_app|reminder_sent = false",
            "idx_bookings_reminder_2h_app|reminder_2h_sent = false"})
    @DisplayName("each APP index is on (starts_at) with exactly APP + CONFIRMED + its own flag = FALSE")
    void should_haveExactPartialPredicate_when_indexRead(String index, String flagPredicate) {
        // Act
        String def = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?", String.class, index);

        // Assert
        assertThat(def).isEqualTo("CREATE INDEX " + index + " ON public.bookings USING btree (starts_at) WHERE ((("
                + "booking_source)::text = 'APP'::text) AND ((status)::text = 'CONFIRMED'::text) AND ("
                + flagPredicate + "))");
    }

    @Test
    @DisplayName("the guest reminder indexes are untouched (still LINK | STAFF)")
    void should_keepGuestIndexes_when_migrated() {
        // Act
        List<String> defs = jdbcTemplate.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' "
                + "AND indexname IN ('idx_bookings_reminder', 'idx_bookings_reminder_2h')", String.class);

        // Assert
        assertThat(defs).hasSize(2).allMatch(def -> def.contains("LINK") && def.contains("STAFF") && !def.contains("APP"));
    }

    @Test
    @DisplayName("the CHECK still lists the thirteen prior event types plus BOOKING_REMINDER — none dropped "
            + "(V243 widens it further and pins its own exact set)")
    void should_listAllFourteenEventTypes_when_constraintRead() {
        // Act
        String def = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conrelid = 'notification_outbox'::regclass AND conname = 'chk_outbox_event'
                """, String.class);

        // Assert
        Set<String> listed = QUOTED.matcher(def).results().map(r -> r.group(1)).collect(Collectors.toSet());
        Set<String> expected = new HashSet<>(PRE_494_EVENT_TYPES);
        expected.add("BOOKING_REMINDER");
        assertThat(listed).as("chk_outbox_event values, def=%s", def).containsAll(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BOOKING_REMINDER", "NEW_BOOKING", "STATUS_CHANGED", "CLIENT_CANCELLED", "INVITE",
            "BOOKING_RESCHEDULED", "REVIEW_REQUESTED", "CLOSURE_REMINDER", "SALON_CLOSED", "MASTER_REMOVED",
            "INAPP_PUSH", "MASTER_BOOKING_RESCHEDULED", "MASTER_BOOKING_DECLINED", "REQUEST_DECIDED"})
    @DisplayName("BOOKING_REMINDER and every prior event type are accepted")
    void should_acceptEventType_when_inserted(String eventType) {
        // Act
        UUID id = insertOutboxRow(eventType);

        // Assert
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_outbox WHERE id = ?", String.class, id)).isEqualTo(eventType);
    }

    @Test
    @DisplayName("an unknown event type is still refused by chk_outbox_event")
    void should_rejectUnknownEventType_when_inserted() {
        // Act + Assert
        assertThatThrownBy(() -> insertOutboxRow("BOOKING_REMINDER_X"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_event");
    }

    @Test
    @DisplayName("acceptance 4: the 24 h registered finder's emitted SQL is planned on idx_bookings_reminder_app")
    void should_planOnAppIndex_when_dayBeforeFinderExplained() {
        // Arrange
        String sql = capturedSql(repo -> repo.findRegisteredBookingsForReminder(FROM, TO, Limit.of(500)));

        // Act
        String plan = explain(bind(sql, "'" + FROM + "'", "'" + TO + "'", "500"));

        // Assert
        assertThat(sql).contains("'APP'").contains("'CONFIRMED'");
        assertThat(plan).as("plan for %s", sql).contains("idx_bookings_reminder_app")
                .doesNotContain("idx_bookings_reminder_2h_app");
    }

    @Test
    @DisplayName("acceptance 4: the 2 h registered finder's emitted SQL is planned on idx_bookings_reminder_2h_app")
    void should_planOnAppTwoHourIndex_when_twoHourFinderExplained() {
        // Arrange
        String sql = capturedSql(repo -> repo.findRegisteredBookingsForTwoHourReminder(FROM, TO, Limit.of(500)));

        // Act
        String plan = explain(bind(sql, "'" + FROM + "'", "'" + TO + "'", "500"));

        // Assert
        assertThat(sql).contains("'APP'").contains("'CONFIRMED'");
        assertThat(plan).as("plan for %s", sql).contains("idx_bookings_reminder_2h_app");
    }

    /** The one SELECT Hibernate logs for {@code call} (literals inline, binds as {@code ?}). */
    private String capturedSql(Consumer<BookingRepository> call) {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level previous = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(Level.DEBUG);
        try {
            call.accept(bookingRepository);
        } finally {
            sqlLogger.setLevel(previous);
            sqlLogger.detachAppender(appender);
        }
        List<String> selects = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(sql -> FROM_BOOKINGS.matcher(sql).find()).toList();
        assertThat(selects).as("one finder statement").hasSize(1);
        return selects.get(0);
    }

    private static String bind(String sql, String... values) {
        Matcher matcher = BIND.matcher(sql);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(values[i++]));
        }
        matcher.appendTail(out);
        assertThat(i).as("bind count").isEqualTo(values.length);
        return out.toString();
    }

    /**
     * EXPLAIN with sequential scans disabled for this transaction only — "can the planner use the index". The shared
     * Testcontainers DB carries dead rows / stale stats from earlier ITs in the same JVM (e.g. RegisteredBookingReminderIT),
     * which tipped the near-tie toward {@code no_overlapping_bookings}; {@code VACUUM ANALYZE} (autocommit, outside the
     * transaction) resets the table to its clean-DB state so the plan is order-independent.
     */
    private String explain(String sql) {
        jdbcTemplate.execute("VACUUM ANALYZE bookings");
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
        });
    }

    /** Inserts one PENDING row; aggregate and payload are non-null so V32's INVITE / aggregate CHECKs pass. */
    private UUID insertOutboxRow(String eventType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO notification_outbox (id, event_type, aggregate_id, payload) VALUES (?, ?, ?, ?)",
                id, eventType, UUID.randomUUID(), "{}");
        return id;
    }
}
