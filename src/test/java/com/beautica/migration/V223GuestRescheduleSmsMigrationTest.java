package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for V223__phase431_master_outbox_events_and_guest_reschedule_sms_sends.sql: the catalog shape
 * (widened outbox CHECK, the two log tables, FK delete rules, indexes) that {@code GuestRescheduleSmsIT}'s
 * behavioural rows (cap, per-phone budget, purge, master outbox rows) rely on.
 */
@DisplayName("V223 migration — phase 431 master outbox events + guest reschedule logs")
class V223GuestRescheduleSmsMigrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("chk_outbox_event admits the two master rows and keeps every pre-431 event type")
    void should_widenOutboxEventCheck_when_constraintRead() {
        String def = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conrelid = 'notification_outbox'::regclass AND conname = 'chk_outbox_event'
                """, String.class);

        assertThat(def).contains("MASTER_BOOKING_RESCHEDULED", "MASTER_BOOKING_DECLINED",
                "NEW_BOOKING", "STATUS_CHANGED", "CLIENT_CANCELLED", "INVITE", "BOOKING_RESCHEDULED",
                "REVIEW_REQUESTED", "CLOSURE_REMINDER", "SALON_CLOSED", "MASTER_REMOVED", "INAPP_PUSH");
    }

    @Test
    @DisplayName("guest_reschedule_sms_sends stores no phone: id, booking_id NOT NULL, nullable outbox_entry_id, timestamps")
    void should_haveExpectedColumns_when_smsSendsTableRead() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                 WHERE table_name = 'guest_reschedule_sms_sends'
                """);

        assertThat(columns).extracting(c -> c.get("column_name") + ":" + c.get("data_type") + ":" + c.get("is_nullable"))
                .containsExactlyInAnyOrder("id:uuid:NO", "booking_id:uuid:NO", "outbox_entry_id:uuid:YES",
                        "sent_at:timestamp with time zone:NO", "created_at:timestamp with time zone:NO");
    }

    @Test
    @DisplayName("guest_provider_reschedules: scope_id + booking_id NOT NULL, rescheduled_at timestamptz")
    void should_haveExpectedColumns_when_providerReschedulesTableRead() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                 WHERE table_name = 'guest_provider_reschedules'
                """);

        assertThat(columns).extracting(c -> c.get("column_name") + ":" + c.get("data_type") + ":" + c.get("is_nullable"))
                .containsExactlyInAnyOrder("id:uuid:NO", "scope_id:uuid:NO", "booking_id:uuid:NO",
                        "rescheduled_at:timestamp with time zone:NO", "created_at:timestamp with time zone:NO");
    }

    @Test
    @DisplayName("both logs reference bookings(id) ON DELETE CASCADE")
    void should_cascadeFromBookings_when_foreignKeysRead() {
        List<String> cascading = jdbcTemplate.queryForList("""
                SELECT conrelid::regclass::text FROM pg_constraint
                 WHERE contype = 'f' AND confdeltype = 'c' AND confrelid = 'bookings'::regclass
                   AND conrelid IN ('guest_reschedule_sms_sends'::regclass, 'guest_provider_reschedules'::regclass)
                """, String.class);

        assertThat(cascading).containsExactlyInAnyOrder("guest_reschedule_sms_sends", "guest_provider_reschedules");
    }

    @Test
    @DisplayName("outbox_entry_id is unique only where NOT NULL (a back-pressure retry reuses its reservation)")
    void should_havePartialUniqueOutboxEntryIndex_when_indexRead() {
        String def = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE tablename = 'guest_reschedule_sms_sends' AND indexname = 'ux_guest_reschedule_sms_sends_outbox_entry'
                """, String.class);

        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("(outbox_entry_id)")
                .contains("WHERE (outbox_entry_id IS NOT NULL)");
    }

    @Test
    @DisplayName("the cap, budget, FK and purge indexes all exist with their documented keys")
    void should_haveLookupIndexes_when_indexesRead() {
        List<String> defs = jdbcTemplate.queryForList("""
                SELECT indexname || ' ' || indexdef FROM pg_indexes
                 WHERE tablename IN ('guest_reschedule_sms_sends', 'guest_provider_reschedules')
                   AND indexname IN ('idx_guest_reschedule_sms_sends_booking', 'idx_guest_reschedule_sms_sends_sent',
                                     'idx_guest_provider_reschedules_scope_at', 'idx_guest_provider_reschedules_booking',
                                     'idx_guest_provider_reschedules_at')
                """, String.class);

        assertThat(defs).hasSize(5).anySatisfy(d -> assertThat(d)
                .startsWith("idx_guest_provider_reschedules_scope_at").contains("(scope_id, rescheduled_at)"));
    }
}
