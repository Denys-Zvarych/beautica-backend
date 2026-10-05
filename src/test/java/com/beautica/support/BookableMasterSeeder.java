package com.beautica.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Shared JDBC seeding for a salon master that passes the free-slot bookability verdict
 * ({@code SlotCalculationService#filterBookableAssignmentsBatch}): an active SALON-owned service
 * assignment plus a usable weekly schedule.
 *
 * <p>Needed by any assertion against a bookability-gated public read — the salon catalogue
 * ({@code GET /salons/{id}/services}) and the public salon roster ({@code GET /salons/{id}/masters},
 * {@code GET /masters/by-salon/{id}}). A master without both is gated out by deliberate contract,
 * so a fixture lacking them would read as an empty roster, not as the behaviour under test.
 *
 * <p>Lives in {@code support} (public) so suites outside {@code com.beautica.service} can reuse it;
 * {@code ServiceTestFixtures#seedUsableSchedule} delegates here (REUSE-FIRST — one schedule recipe).
 */
public final class BookableMasterSeeder {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final LocalTime CLOSE = LocalTime.of(17, 0);

    private BookableMasterSeeder() {
    }

    /** Assignment + schedule: the minimum for {@code masterId} to be bookable in {@code salonId}. */
    public static void makeBookable(JdbcTemplate jdbc, UUID salonId, UUID masterId) {
        assignNewSalonService(jdbc, salonId, masterId);
        seedUsableSchedule(jdbc, masterId);
    }

    /**
     * Inserts an active SALON-owned definition (on a service type not yet used by the salon, so the
     * unique-active-type index never collides) and an active assignment of it to {@code masterId}.
     *
     * @return the new definition's id
     */
    public static UUID assignNewSalonService(JdbcTemplate jdbc, UUID salonId, UUID masterId) {
        UUID serviceTypeId = jdbc.queryForObject(
                """
                SELECT st.id FROM service_types st
                JOIN platform_categories pc ON pc.name = st.platform_category_name
                WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM service_definitions sd
                                  WHERE sd.owner_type = 'SALON' AND sd.owner_id = ?
                                    AND sd.service_type_id = st.id AND sd.is_active = TRUE)
                ORDER BY st.name_uk LIMIT 1
                """,
                UUID.class, salonId);
        UUID defId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, ?, ?, 60, 'FIXED', 500.00, 0, true, NOW(), NOW())",
                defId, salonId, "Seeded Service " + defId, serviceTypeId);
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, defId);
        return defId;
    }

    /** Real BCrypt (cost 4) for seeded staff rows — never a fake hash string (§M-5). */
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    /**
     * Inserts a new active SALON_MASTER (user + {@code masters} row) in {@code salonId} and makes it
     * bookable — the minimum for the salon to pass the discovery gate ({@code MasterBookabilitySql}:
     * a salon is listed only with ≥1 bookable master).
     *
     * @return the new master's id
     */
    public static UUID addBookableSalonMaster(JdbcTemplate jdbc, UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                userId, "bookable-master-" + userId + "@beautica.test", PASSWORD_HASH, salonId);
        UUID masterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, userId, salonId);
        makeBookable(jdbc, salonId, masterId);
        return masterId;
    }

    /** Independent-master counterpart of {@link #makeBookable}: own service + usable schedule. */
    public static void makeIndependentBookable(JdbcTemplate jdbc, UUID masterId) {
        assignNewIndependentService(jdbc, masterId);
        seedUsableSchedule(jdbc, masterId);
    }

    /**
     * Inserts an active INDEPENDENT_MASTER-owned definition (owner = {@code masterId}) on a type the
     * master does not offer yet, and an active assignment of it to that master.
     *
     * @return the new definition's id
     */
    public static UUID assignNewIndependentService(JdbcTemplate jdbc, UUID masterId) {
        UUID serviceTypeId = jdbc.queryForObject(
                """
                SELECT st.id FROM service_types st
                JOIN platform_categories pc ON pc.name = st.platform_category_name
                WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM service_definitions sd
                                  WHERE sd.owner_type = 'INDEPENDENT_MASTER' AND sd.owner_id = ?
                                    AND sd.service_type_id = st.id AND sd.is_active = TRUE)
                ORDER BY st.name_uk LIMIT 1
                """,
                UUID.class, masterId);
        UUID defId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, 60, 'FIXED', 500.00, 0, true, NOW(), NOW())",
                defId, masterId, "Seeded Service " + defId, serviceTypeId);
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, defId);
        return defId;
    }

    /**
     * Deletes every weekly template and override of {@code masterId} (intervals/times cascade), so
     * the master has no schedule — the inverse of {@link #seedUsableSchedule}.
     */
    public static void removeSchedule(JdbcTemplate jdbc, UUID masterId) {
        jdbc.update("DELETE FROM weekly_schedules WHERE master_id = ?", masterId);
        jdbc.update("DELETE FROM schedule_exceptions WHERE master_id = ?", masterId);
    }

    /**
     * Gives {@code masterId} an open-ended weekly template with a 09:00–17:00 interval on EVERY ISO
     * weekday, so the master always has free future slots. Every weekday is seeded rather than just
     * today's so the fixture cannot go stale when the suite runs after 17:00 local.
     */
    public static void seedUsableSchedule(JdbcTemplate jdbc, UUID masterId) {
        seedWeeklyTemplate(jdbc, masterId, LocalDate.now(KYIV), null, true);
    }

    /**
     * Inserts a weekly template valid over {@code [validFrom, validTo]} ({@code validTo} null =
     * open-ended). With {@code withHours} every ISO weekday gets 09:00–17:00; without, the template
     * is EMPTY (no interval rows) — the {@code {"days":[]}} shape the API accepts.
     *
     * @return the new template's id
     */
    public static UUID seedWeeklyTemplate(JdbcTemplate jdbc, UUID masterId, LocalDate validFrom,
                                          LocalDate validTo, boolean withHours) {
        UUID scheduleId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        if (withHours) {
            for (int isoDow = 1; isoDow <= 7; isoDow++) {
                jdbc.update(
                        "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(), scheduleId, isoDow, OPEN, CLOSE);
            }
        }
        return scheduleId;
    }

    /**
     * Inserts an open-ended EXPLICIT_TIMES template (V84): discrete 10:00 and 14:00 start times on
     * every ISO weekday and NO interval rows.
     */
    public static void seedExplicitTimesTemplate(JdbcTemplate jdbc, UUID masterId) {
        UUID scheduleId = seedWeeklyTemplate(jdbc, masterId, LocalDate.now(KYIV), null, false);
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            for (LocalTime t : new LocalTime[] {LocalTime.of(10, 0), LocalTime.of(14, 0)}) {
                jdbc.update(
                        "INSERT INTO working_interval_times (id, schedule_id, day_of_week, slot_time) "
                                + "VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), scheduleId, isoDow, t);
            }
        }
    }

    /** Inserts a CUSTOM_HOURS override on {@code date} with one 10:00–12:00 interval. */
    public static void seedCustomHoursOverride(JdbcTemplate jdbc, UUID masterId, LocalDate date) {
        seedCustomHoursOverride(jdbc, masterId, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
    }

    /** Inserts a CUSTOM_HOURS override on {@code date} with one {@code [start, end)} interval. */
    public static void seedCustomHoursOverride(JdbcTemplate jdbc, UUID masterId, LocalDate date,
                                               LocalTime start, LocalTime end) {
        UUID exceptionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, kind, created_at) "
                        + "VALUES (?, ?, ?, 'CUSTOM_HOURS', NOW())",
                exceptionId, masterId, date);
        jdbc.update(
                "INSERT INTO schedule_exception_intervals (id, exception_id, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), exceptionId, start, end);
    }

    /** Inserts a DAY_OFF override on {@code date}. */
    public static void seedDayOffOverride(JdbcTemplate jdbc, UUID masterId, LocalDate date) {
        jdbc.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, kind, created_at) "
                        + "VALUES (?, ?, ?, 'DAY_OFF', NOW())",
                UUID.randomUUID(), masterId, date);
    }

    /**
     * Inserts an active INDEPENDENT_MASTER (user + salon-less {@code masters} row) named
     * {@code name} (first AND last name, so a {@code ?q=name} search hits it) in a seeded major city.
     * No service, no schedule — the caller decides what makes it (un)bookable.
     *
     * @return the new master's id
     */
    public static UUID insertIndependentMaster(JdbcTemplate jdbc, String name) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, city_id, "
                        + "is_active, email_verified) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, ?, true, true)",
                userId, "indep-" + userId + "@beautica.test", PASSWORD_HASH, name, name,
                LocalityTestLookup.majorCityIdByName(jdbc, "Вінниця"));
        UUID masterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    /**
     * Inserts an active salon named {@code name} (fresh SALON_OWNER user, seeded major city) with NO
     * masters — the caller adds them.
     *
     * @return the new salon's id
     */
    public static UUID insertSalon(JdbcTemplate jdbc, String name) {
        UUID ownerUserId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerUserId, "owner-" + ownerUserId + "@beautica.test", PASSWORD_HASH);
        UUID salonId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO salons (id, owner_id, name, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerUserId, name, LocalityTestLookup.majorCityIdByName(jdbc, "Вінниця"));
        return salonId;
    }

    /**
     * Inserts an active SALON_MASTER (user + {@code masters} row) in {@code salonId} with NO service
     * and NO schedule.
     *
     * @return the new master's id
     */
    public static UUID insertSalonMaster(JdbcTemplate jdbc, UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                userId, "salon-master-" + userId + "@beautica.test", PASSWORD_HASH, salonId);
        UUID masterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }
}
