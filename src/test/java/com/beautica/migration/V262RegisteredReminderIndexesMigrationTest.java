package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V262__bookings_registered_reminder_indexes.sql}: the two registered reminder indexes exist
 * with predicates equal to the finders' WHERE minus the window, and the four per-arm indexes they replace (V240 APP,
 * V260 attached) are gone. The plan half lives in {@link V240RegisteredBookingRemindersMigrationTest} (acceptance 4).
 */
@DisplayName("V262 migration — registered reminder sweeps get one OR-predicate partial index each")
class V262RegisteredReminderIndexesMigrationTest extends AbstractIntegrationTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "idx_bookings_reminder_registered|reminder_sent = false",
            "idx_bookings_reminder_2h_registered|reminder_2h_sent = false"})
    @DisplayName("each index is on (starts_at) with exactly (APP OR attached) + CONFIRMED + its flag + client set")
    void should_haveExactPartialPredicate_when_indexRead(String index, String flagPredicate) {
        // Act
        String def = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?", String.class, index);

        // Assert
        assertThat(def).isEqualTo("CREATE INDEX " + index + " ON public.bookings USING btree (starts_at) WHERE ((("
                + "(booking_source)::text = 'APP'::text) OR (client_link_reason IS NOT NULL)) AND ((status)::text = "
                + "'CONFIRMED'::text) AND (" + flagPredicate + ") AND (client_id IS NOT NULL))");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"idx_bookings_reminder_app", "idx_bookings_reminder_2h_app",
            "idx_bookings_reminder_attached", "idx_bookings_reminder_2h_attached"})
    @DisplayName("the superseded per-arm reminder indexes are dropped")
    void should_dropSupersededIndex_when_migrated(String index) {
        // Act
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?", Integer.class, index);

        // Assert
        assertThat(count).isZero();
    }
}
