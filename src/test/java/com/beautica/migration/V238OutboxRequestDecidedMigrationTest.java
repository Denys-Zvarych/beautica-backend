package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@code V238__outbox_event_type_request_decided.sql} (phase 491): {@code chk_outbox_event} is
 * re-added as a strict superset — exactly the twelve pre-491 event types plus {@code REQUEST_DECIDED} — and the
 * real table accepts a row of every one of them while still refusing an unknown type. Catalog text alone could
 * pass on a typo'd literal, so the behavioural inserts are the acceptance half.
 */
@DisplayName("V238 migration — chk_outbox_event admits REQUEST_DECIDED and keeps the twelve prior event types")
class V238OutboxRequestDecidedMigrationTest extends AbstractIntegrationTest {

    /** The twelve values V223 left in the CHECK (V32 / V94 / V109 / V131 / V156 / V161 / V184 / V223). */
    private static final Set<String> PRE_491_EVENT_TYPES = Set.of(
            "NEW_BOOKING", "STATUS_CHANGED", "CLIENT_CANCELLED", "INVITE", "BOOKING_RESCHEDULED",
            "REVIEW_REQUESTED", "CLOSURE_REMINDER", "SALON_CLOSED", "MASTER_REMOVED", "INAPP_PUSH",
            "MASTER_BOOKING_RESCHEDULED", "MASTER_BOOKING_DECLINED");

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Test
    @DisplayName("the CHECK still lists the twelve prior event types plus REQUEST_DECIDED — none dropped "
            + "(later migrations, e.g. V240, may widen it further; each pins its own exact set)")
    void should_listAllThirteenEventTypes_when_constraintRead() {
        String def = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conrelid = 'notification_outbox'::regclass AND conname = 'chk_outbox_event'
                """, String.class);

        Set<String> listed = QUOTED.matcher(def).results().map(r -> r.group(1)).collect(Collectors.toSet());
        Set<String> expected = new HashSet<>(PRE_491_EVENT_TYPES);
        expected.add("REQUEST_DECIDED");
        assertThat(listed).as("chk_outbox_event values, def=%s", def).containsAll(expected);
    }

    @Test
    @DisplayName("a REQUEST_DECIDED row with a requester aggregate and an ids-only payload is accepted")
    void should_acceptRequestDecidedRow_when_inserted() {
        UUID id = insertOutboxRow("REQUEST_DECIDED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_outbox WHERE id = ?", String.class, id))
                .isEqualTo("REQUEST_DECIDED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NEW_BOOKING", "STATUS_CHANGED", "CLIENT_CANCELLED", "INVITE", "BOOKING_RESCHEDULED",
            "REVIEW_REQUESTED", "CLOSURE_REMINDER", "SALON_CLOSED", "MASTER_REMOVED", "INAPP_PUSH",
            "MASTER_BOOKING_RESCHEDULED", "MASTER_BOOKING_DECLINED"})
    @DisplayName("every pre-491 event type is still accepted after the re-add")
    void should_stillAcceptPriorEventType_when_inserted(String eventType) {
        UUID id = insertOutboxRow(eventType);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE id = ?", Integer.class, id)).isOne();
    }

    @Test
    @DisplayName("an unknown event type is still refused by chk_outbox_event")
    void should_rejectUnknownEventType_when_inserted() {
        assertThatThrownBy(() -> insertOutboxRow("REQUEST_DECIDED_X"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_event");
    }

    @Test
    @DisplayName("V32's aggregate CHECK still binds REQUEST_DECIDED: a null aggregate_id is refused")
    void should_rejectRequestDecidedRow_when_aggregateIdNull() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notification_outbox (event_type, aggregate_id, payload) VALUES ('REQUEST_DECIDED', NULL, '{}')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_booking_aggregate");
    }

    /** Inserts one PENDING row; aggregate and payload are non-null so V32's INVITE / aggregate CHECKs pass. */
    private UUID insertOutboxRow(String eventType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO notification_outbox (id, event_type, aggregate_id, payload) VALUES (?, ?, ?, ?)",
                id, eventType, UUID.randomUUID(), "{}");
        return id;
    }
}
