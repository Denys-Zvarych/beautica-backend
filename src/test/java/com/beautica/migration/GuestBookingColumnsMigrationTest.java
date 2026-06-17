package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies V89__add_guest_booking_columns.sql: the new guest-booking columns exist
 * with the right types/defaults, an APP booking row defaults {@code booking_source}
 * to {@code 'APP'}, and the polymorphic {@code chk_bookings_guest_fields} CHECK
 * rejects a half-populated LINK row.
 *
 * <p>Also verifies the V91 relaxation: an <em>active</em> LINK row (PENDING/CONFIRMED)
 * still requires a non-null {@code cancel_token}, but a LINK row in a terminal status
 * (e.g. {@code CANCELLED}) may carry a NULL token — this is the guest-cancel path that
 * nulls the token while setting status = CANCELLED.
 *
 * <p>Schema assertions use {@code information_schema}; behaviour assertions seed a
 * minimal master + service graph and insert through {@code bookings} directly.
 */
@DisplayName("V89 migration — guest booking columns")
class GuestBookingColumnsMigrationTest extends AbstractIntegrationTest {

    private static final String DEFAULT_QUERY = """
            SELECT column_default FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = ?
            """;

    @Test
    @DisplayName("should add booking_source NOT NULL DEFAULT 'APP'")
    void should_addBookingSourceColumn_when_migrationApplied() {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'bookings' AND column_name = 'booking_source'
                """, String.class);
        String columnDefault = jdbcTemplate.queryForObject(DEFAULT_QUERY, String.class, "booking_source");

        assertThat(dataType).isEqualTo("character varying");
        assertThat(columnDefault).contains("'APP'");
    }

    @Test
    @DisplayName("should add guest_name, guest_phone, cancel_token, reminder_sent columns")
    void should_addGuestColumns_when_migrationApplied() {
        assertThat(columnExists("guest_name")).isTrue();
        assertThat(columnExists("guest_surname")).isTrue();
        assertThat(columnExists("guest_phone")).isTrue();
        assertThat(columnExists("cancel_token")).isTrue();
        assertThat(columnExists("reminder_sent")).isTrue();
    }

    @Test
    @DisplayName("existing-style APP booking defaults booking_source to 'APP'")
    void should_defaultBookingSourceToApp_when_rowInsertedWithoutSource() {
        Ids ids = seedGraph();
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                    350.00, 60, 0, NOW(), NOW())
                """, bookingId, ids.clientId(), ids.masterId(), ids.masterServiceId());

        String source = jdbcTemplate.queryForObject(
                "SELECT booking_source FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(source).isEqualTo("APP");
    }

    @Test
    @DisplayName("CHECK rejects a LINK booking missing guest_name / cancel_token")
    void should_rejectLinkBooking_when_guestFieldsMissing() {
        Ids ids = seedGraph();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    booking_source, created_at, updated_at)
                VALUES (?, ?, ?, 'CONFIRMED', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                    350.00, 60, 0, 'LINK', NOW(), NOW())
                """, UUID.randomUUID(), ids.masterId(), ids.masterServiceId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK accepts a fully-populated LINK booking (client_id NULL + guest fields set)")
    void should_acceptLinkBooking_when_guestFieldsPresent() {
        Ids ids = seedGraph();
        UUID bookingId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at)
                VALUES (?, ?, ?, 'CONFIRMED', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                    350.00, 60, 0, 'LINK', 'Олена', 'Коваль', '+380501234567', ?, NOW(), NOW())
                """, bookingId, ids.masterId(), ids.masterServiceId(), UUID.randomUUID());

        String source = jdbcTemplate.queryForObject(
                "SELECT booking_source FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(source).isEqualTo("LINK");
    }

    @Test
    @DisplayName("CHECK still rejects an ACTIVE LINK booking (CONFIRMED) with a NULL cancel_token")
    void should_rejectActiveLinkBooking_when_cancelTokenNull() {
        Ids ids = seedGraph();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at)
                VALUES (?, ?, ?, 'CONFIRMED', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                    350.00, 60, 0, 'LINK', 'Олена', 'Коваль', '+380501234567', NULL, NOW(), NOW())
                """, UUID.randomUUID(), ids.masterId(), ids.masterServiceId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V91: CHECK accepts a terminal LINK booking (CANCELLED) with a NULL cancel_token")
    void should_acceptTerminalLinkBooking_when_cancelTokenNull() {
        Ids ids = seedGraph();
        UUID bookingId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at)
                VALUES (?, ?, ?, 'CANCELLED', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                    350.00, 60, 0, 'LINK', 'Олена', 'Коваль', '+380501234567', NULL, NOW(), NOW())
                """, bookingId, ids.masterId(), ids.masterServiceId());

        String source = jdbcTemplate.queryForObject(
                "SELECT booking_source FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(source).isEqualTo("LINK");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private boolean columnExists(String column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'bookings' AND column_name = ?
                """, Integer.class, column);
        return count != null && count == 1;
    }

    private record Ids(UUID clientId, UUID masterId, UUID masterServiceId) {}

    private Ids seedGraph() {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                clientId, "client-" + clientId + "@beautica.test");

        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                masterUserId, "master-" + masterUserId + "@beautica.test");

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, masterUserId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, masterUserId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return new Ids(clientId, masterId, masterServiceId);
    }
}
