package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.notification.entity.OutboxEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@code V243__outbox_event_type_client_receipts.sql} (phase 496 audit-fix cycle 1):
 * {@code chk_outbox_event} is re-added as a strict superset — the fourteen prior event types plus
 * {@code CLIENT_CANCEL_RECEIPT} and {@code CLIENT_RESCHEDULE_RECEIPT} — and stays in lockstep with
 * {@link OutboxEventType}. The behavioural inserts are the acceptance half (catalog text alone could pass a typo).
 */
@DisplayName("V243 migration — chk_outbox_event admits the two client receipt types and matches OutboxEventType")
class V243OutboxClientReceiptsMigrationTest extends AbstractIntegrationTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Test
    @DisplayName("the CHECK lists exactly the OutboxEventType values (fourteen prior + the two receipt types)")
    void should_listExactlyTheEnumValues_when_constraintRead() {
        // Act
        String def = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conrelid = 'notification_outbox'::regclass AND conname = 'chk_outbox_event'
                """, String.class);

        // Assert
        Set<String> listed = QUOTED.matcher(def).results().map(r -> r.group(1)).collect(Collectors.toSet());
        Set<String> enumValues = Arrays.stream(OutboxEventType.values()).map(Enum::name).collect(Collectors.toSet());
        assertThat(listed).as("chk_outbox_event values, def=%s", def)
                .hasSize(16)
                .contains("CLIENT_CANCEL_RECEIPT", "CLIENT_RESCHEDULE_RECEIPT")
                .containsExactlyInAnyOrderElementsOf(enumValues);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutboxEventType.class)
    @DisplayName("every event type, the two receipt types included, is accepted")
    void should_acceptEventType_when_inserted(OutboxEventType eventType) {
        // Act
        UUID id = insertOutboxRow(eventType.name());

        // Assert
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_outbox WHERE id = ?", String.class, id)).isEqualTo(eventType.name());
    }

    @Test
    @DisplayName("an unknown event type is still refused by chk_outbox_event")
    void should_rejectUnknownEventType_when_inserted() {
        // Act + Assert
        assertThatThrownBy(() -> insertOutboxRow("CLIENT_RECEIPT_X"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_event");
    }

    @Test
    @DisplayName("V32's aggregate CHECK still binds the receipt rows: a null aggregate_id is refused")
    void should_rejectReceiptRow_when_aggregateIdNull() {
        // Act + Assert
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO notification_outbox (event_type, aggregate_id, payload) "
                + "VALUES ('CLIENT_CANCEL_RECEIPT', NULL, NULL)"))
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
