package com.beautica.booking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * JDBC fixtures shared by the guest booking-link ITs ({@link GuestVisitLinkParityIT},
 * {@link GuestCancelTokenHashEndToEndIT}): a bookable independent master reachable by slug, its
 * master services, and its weekly schedule. Raw SQL on purpose — the tests drive the public
 * {@code /api/v1/book/**} surface and only need the provider side to exist.
 */
final class GuestLinkFixtures {

    static final String TEST_PASSWORD = "Str0ngP@ss1!";

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final LocalTime OPEN_WEEK_START = LocalTime.of(8, 0);
    private static final LocalTime OPEN_WEEK_END = LocalTime.of(20, 0);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock kyivClock;

    GuestLinkFixtures(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.kyivClock = clock.withZone(KYIV);
    }

    /** Active INDEPENDENT_MASTER user + master row carrying {@code slug} as its booking slug. */
    UUID createMasterWithSlug(String email, String slug) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true, 'Наталія', 'Бойко')",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, userId, slug);
        return masterId;
    }

    /** {@link #createService(UUID, String, int, int)} named {@code "Послуга-<typeOffset>"}. */
    UUID createService(UUID masterId, int durationMinutes, int typeOffset) {
        return createService(masterId, "Послуга-" + typeOffset, durationMinutes, typeOffset);
    }

    /**
     * Seeds a master service (buffer 0, price 350.00) with the given duration; {@code typeOffset} picks a
     * distinct approved service_type. Returns the {@code master_services} id.
     */
    UUID createService(UUID masterId, String name, int durationMinutes, int typeOffset) {
        UUID ownerId = jdbc.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, ?, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerId, name, resolveServiceTypeId(typeOffset), durationMinutes);
        UUID masterServiceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    UUID seedSchedule(UUID masterId, LocalDate validFrom, LocalDate validTo) {
        UUID scheduleId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        return scheduleId;
    }

    void addInterval(UUID scheduleId, int isoDow, LocalTime start, LocalTime end) {
        jdbc.update(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    /**
     * Open-ended weekly schedule valid from today (Kyiv), all seven ISO weekdays 08:00–20:00 — required by
     * every test that expects a guest create to SUCCEED, since all create paths enforce schedule fit.
     * Callers must place {@code startsAt} on the 30-minute grid anchored at 08:00 (:00 or :30).
     */
    void seedOpenWeek(UUID masterId) {
        UUID scheduleId = seedSchedule(masterId, LocalDate.now(kyivClock), null);
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            addInterval(scheduleId, isoDow, OPEN_WEEK_START, OPEN_WEEK_END);
        }
    }

    private UUID resolveServiceTypeId(int offset) {
        return jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk OFFSET ? LIMIT 1",
                UUID.class, offset);
    }
}
