package com.beautica.booking;

import com.beautica.booking.job.BookingReminderJob;
import com.beautica.config.BookingSmsProperties;
import com.beautica.support.HeldDbLock;
import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 413 — the 2h-before guest reminder against a real database and the real gated Turbosms sender (WireMock),
 * Telegram off (SMS guests). Clock pinned to 12:00 Kyiv; every row is written relative to it.
 */
@TestPropertySource(properties = {"app.booking.sms.enabled=true", "app.booking.reminder-2h.enabled=true"})
@Import(GuestTwoHourReminderIT.ClockConfig.class)
@DisplayName("GuestTwoHourReminderIT — phase 413 2h reminder sweep")
class GuestTwoHourReminderIT extends AbstractWalkInBookingSmsIT {

    /**
     * 12:00 Kyiv on the REAL current Kyiv date: the base class seeds the master's schedule from the real date
     * ({@code LocalDate.now(KYIV)}) and the schedule service validates it against this clock, so a fixed date would
     * fail once the real date moves past it.
     */
    private static final Instant NOW = java.time.LocalDate.now(com.beautica.common.TimeZones.KYIV)
            .atTime(12, 0).atZone(com.beautica.common.TimeZones.KYIV).toInstant();
    private static final String TWO_HOUR_PREFIX = "Beautica: Нагадуємо!\n" + SERVICE_NAME + " у " + MASTER_NAME
            + "\nСьогодні о ";

    @Autowired private BookingReminderJob reminderJob;
    @Autowired private BookingSmsProperties smsProperties;
    @Autowired private MutableTestClock clock;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void pinClock() {
        clock.set(NOW);
    }

    /** Starts at {@link #NOW}, so the base class's schedule seeding (which runs first) sees today's date. */
    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfig {
        @org.springframework.context.annotation.Bean
        MutableTestClock systemClock() {
            return new MutableTestClock(NOW);
        }
    }

    @AfterEach
    void restoreFlagAndClock() {
        smsProperties.getReminder2h().setEnabled(true);
        smsProperties.getSms().setEnabled(true);
        clock.set(NOW);
    }

    @Test
    @DisplayName("LINK booking → one 24h reminder the day before and one 2h reminder on the day, no repeats")
    void should_sendOne24hAndOne2hReminder_when_linkBookingUpcoming() throws Exception {
        UUID bookingId = insertLinkBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));

        reminderJob.sendReminders();
        assertThat(sentText()).contains("Завтра о");
        resetTurbosms();
        clock.set(NOW.plus(Duration.ofHours(22)));
        reminderJob.sweepTwoHour();
        reminderJob.sweepTwoHour();

        assertThat(sentText()).isEqualTo(TWO_HOUR_PREFIX + "12:00");
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_2h_sent FROM bookings WHERE id = ?",
                Boolean.class, bookingId)).isTrue();
    }

    @Test
    @DisplayName("multi-service guest visit → exactly one 2h reminder, every item flagged")
    void should_sendOneReminderPerVisit_when_multiServiceVisitDue() throws Exception {
        UUID appointmentId = insertLinkVisit(at(NOW.plus(Duration.ofHours(2))), 2);

        reminderJob.sweepTwoHour();

        assertThat(sentText()).isEqualTo(TWO_HOUR_PREFIX + "14:00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE appointment_id = ? "
                + "AND reminder_2h_sent", Integer.class, appointmentId)).isEqualTo(2);
    }

    @Test
    @DisplayName("APP booking, cancelled guest booking and a short-notice guest booking → no 2h reminder")
    void should_sendNothing_when_notEligible() {
        SeededUser client = insertUser("CLIENT", null);
        OffsetDateTime in2h = at(NOW.plus(Duration.ofMinutes(106)));
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'APP', ?, ?)",
                UUID.randomUUID(), client.id(), salon.masterId(), salon.masterServiceId(), salon.salonId(),
                in2h, in2h.plusMinutes(10), at(NOW.minus(Duration.ofDays(1))), at(NOW));
        UUID cancelled = insertLinkBooking(in2h.plusMinutes(12), NOW.minus(Duration.ofDays(1)));
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED', cancellation_reason = 'CLIENT_CANCELLED' "
                + "WHERE id = ?", cancelled);
        insertLinkBooking(in2h.plusMinutes(24), NOW.minus(Duration.ofMinutes(30)));

        reminderJob.sweepTwoHour();

        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("21:00 Kyiv → nothing selected or claimed; the booking is still unflagged")
    void should_sendNothing_when_kyivNight() {
        Instant night = NOW.atZone(com.beautica.common.TimeZones.KYIV).withHour(21).toInstant(); // 21:00 Kyiv
        clock.set(night);
        UUID bookingId = insertLinkBooking(at(night.plus(Duration.ofHours(2))), night.minus(Duration.ofDays(1)));

        reminderJob.sweepTwoHour();

        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_2h_sent FROM bookings WHERE id = ?",
                Boolean.class, bookingId)).isFalse();
    }

    @Test
    @DisplayName("flag off → no 2h reminder; the 24h reminder is unchanged")
    void should_sendNo2hReminder_when_flagOff() throws Exception {
        smsProperties.getReminder2h().setEnabled(false);
        insertLinkBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(1)));

        UUID twoHour = insertLinkBooking(at(NOW.plus(Duration.ofMinutes(110))), NOW.minus(Duration.ofDays(1)));

        reminderJob.sweepTwoHour();
        reminderJob.sendReminders();

        assertThat(sentText()).contains("Завтра о");
        assertThat(reminded(twoHour)).as("flag off → not even claimed").isFalse();
    }

    @Test
    @DisplayName("audit-fix: a row locked by another transaction is SKIPPED, the rest claimed; it is reminded next tick")
    void should_skipHeldRowAndClaimTheRest_when_oneRowLocked() throws Exception {
        UUID held = insertLinkBooking(at(NOW.plus(Duration.ofMinutes(125))), NOW.minus(Duration.ofDays(1)));
        UUID free = insertLinkBooking(at(NOW.plus(Duration.ofMinutes(110))), NOW.minus(Duration.ofDays(1)));

        try (HeldDbLock ignored = HeldDbLock.bookingRow(dataSource, held)) {
            reminderJob.sweepTwoHour();
        }

        assertThat(sentText()).isEqualTo(TWO_HOUR_PREFIX + "13:50");
        assertThat(reminded(free)).isTrue();
        assertThat(reminded(held)).isFalse();
        resetTurbosms();
        clock.set(NOW.plus(Duration.ofMinutes(15)));
        reminderJob.sweepTwoHour();
        assertThat(sentText()).isEqualTo(TWO_HOUR_PREFIX + "14:05");
        assertThat(reminded(held)).isTrue();
    }

    @Test
    @DisplayName("audit-fix: a service added to an already 2h-reminded visit never triggers a second reminder")
    void should_notRemindAgain_when_serviceAddedToRemindedVisit() throws Exception {
        UUID appointmentId = insertLinkVisit(at(NOW.plus(Duration.ofMinutes(110))), 1);
        reminderJob.sweepTwoHour();
        assertThat(sentText()).contains("Сьогодні о");
        resetTurbosms();
        UUID added = insertLinkBooking(at(NOW.plus(Duration.ofMinutes(125))), NOW.minus(Duration.ofDays(1)));
        jdbcTemplate.update("UPDATE bookings SET appointment_id = ? WHERE id = ?", appointmentId, added);

        reminderJob.sweepTwoHour();

        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(reminded(added)).isFalse();
    }

    @Test
    @DisplayName("the sweep predicate is served by the V225 partial index idx_bookings_reminder_2h")
    void should_useTwoHourPartialIndex_when_sweepPredicatePlanned() {
        List<String> plan = new TransactionTemplate(transactionManager).execute(status -> {
            // Realistic shape: many already-reminded LINK rows (outside the partial index) + fresh stats, so the
            // planner compares real costs instead of tie-breaking over an empty table.
            jdbcTemplate.update("""
                    INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, starts_at, ends_at,
                        price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source,
                        guest_name, guest_surname, guest_phone, cancel_token_hash, reminder_2h_sent, created_at,
                        updated_at)
                    SELECT gen_random_uuid(), ?, ?, ?, 'CONFIRMED', now() + make_interval(hours => g),
                           now() + make_interval(hours => g) + interval '10 minutes', 350.00, 10, 0, 'LINK',
                           'Гість', 'Тестовий', ?, encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'),
                           TRUE, now(), now()
                      FROM generate_series(1, 400) AS g
                    """, salon.masterId(), salon.masterServiceId(), salon.salonId(), LINK_PHONE);
            jdbcTemplate.execute("ANALYZE bookings");
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            List<String> rows = jdbcTemplate.queryForList("""
                    EXPLAIN SELECT id FROM bookings
                     WHERE (booking_source = 'LINK' OR booking_source = 'STAFF') AND guest_phone IS NOT NULL
                       AND status = 'CONFIRMED' AND reminder_2h_sent = FALSE
                       AND starts_at >= now() + interval '105 minutes' AND starts_at < now() + interval '135 minutes'
                    """, String.class);
            status.setRollbackOnly();
            return rows;
        });

        assertThat(String.join("\n", plan)).contains("idx_bookings_reminder_2h");
    }

    @ParameterizedTest(name = "{0} → sends={1}")
    @CsvSource({
            // DST end 2026-10-25 (EEST → EET, UTC+2 after 04:00): a fixed +3 offset would get every row wrong
            "2026-10-25T06:59:00Z, false",  // 08:59 EET
            "2026-10-25T07:00:00Z, true",   // 09:00 EET
            "2026-10-25T18:30:00Z, true",   // 20:30 EET (21:30 under a stale +3)
            "2026-10-25T19:00:00Z, false",  // 21:00 EET
            // DST start 2027-03-28 (EET → EEST, UTC+3 after 03:00): a fixed +2 offset would get these wrong
            "2027-03-28T05:59:00Z, false",  // 08:59 EEST
            "2027-03-28T06:00:00Z, true",   // 09:00 EEST (08:00 under a stale +2)
            "2027-03-28T17:30:00Z, true",   // 20:30 EEST
            "2027-03-28T18:00:00Z, false"   // 21:00 EEST
    })
    @DisplayName("quiet hours on DST-switch days: the real sweep sends only in [09:00, 21:00) Kyiv wall-clock time")
    void should_applyKyivQuietHours_when_sweepRunsOnDstSwitchDay(String tick, boolean sends) throws Exception {
        Instant at = Instant.parse(tick);
        clock.set(at);
        OffsetDateTime startsAt = at(at.plus(Duration.ofMinutes(120)));
        UUID bookingId = insertLinkBooking(startsAt, at.minus(Duration.ofDays(1)));

        reminderJob.sweepTwoHour();

        assertThat(reminded(bookingId)).isEqualTo(sends);
        if (sends) {
            assertThat(sentText()).isEqualTo(TWO_HOUR_PREFIX
                    + java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            .format(startsAt.atZoneSameInstant(com.beautica.common.TimeZones.KYIV)));
        } else {
            TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        }
    }

    @Test
    @DisplayName("two sweeps racing on two threads → every due booking and visit is reminded exactly once")
    void should_remindEachBookingOnce_when_twoSweepsRunConcurrently() throws Exception {
        int singles = 24;
        for (int i = 0; i < singles; i++) {
            // 1-minute rows so 24 fit inside the 30-minute window without tripping no_overlapping_bookings.
            insertGuestLinkBooking(LINK_PHONE, at(NOW.plus(Duration.ofMinutes(106 + i))), Duration.ofMinutes(1),
                    NOW.minus(Duration.ofDays(1)));
        }
        UUID visit = insertLinkVisit(at(NOW.plus(Duration.ofMinutes(130))), 1);
        jdbcTemplate.update("UPDATE bookings SET ends_at = starts_at + interval '1 minute' WHERE appointment_id = ?",
                visit);
        UUID visitSecond = insertGuestLinkBooking(LINK_PHONE, at(NOW.plus(Duration.ofMinutes(132))),
                Duration.ofMinutes(1), NOW.minus(Duration.ofDays(1)));
        jdbcTemplate.update("UPDATE bookings SET appointment_id = ? WHERE id = ?", visit, visitSecond);
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<?>> sweeps = new java.util.ArrayList<>();
            for (int t = 0; t < 2; t++) {
                sweeps.add(pool.submit(() -> {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    reminderJob.sweepTwoHour();
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> sweep : sweeps) {
                sweep.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> texts = TURBOSMS.findAll(postRequestedFor(urlEqualTo(TURBOSMS_PATH))).stream()
                .map(r -> readJson(r.getBodyAsString()).path("sms").path("text").asText()).toList();
        assertThat(texts).as("24 singles + 1 visit, each once").hasSize(singles + 1).doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE NOT reminder_2h_sent",
                Integer.class)).isZero();
    }

    private boolean reminded(UUID bookingId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT reminder_2h_sent FROM bookings WHERE id = ?", Boolean.class, bookingId));
    }

    // ── phase 413 D4 (after 412): walk-ins in the 2h sweep ────────────────────────────────────────

    @Test
    @DisplayName("D4: STAFF walk-in visit with a phone → exactly one 2h reminder naming the platform service type")
    void should_remindWalkInVisitOnce_when_walkInGateOn() throws Exception {
        UUID appointmentId = insertWalkInVisit(at(NOW.plus(Duration.ofMinutes(110))), 2);

        reminderJob.sweepTwoHour();
        reminderJob.sweepTwoHour();

        assertThat(sentText())
                .startsWith("Beautica: Нагадуємо!\n" + platformServiceName() + " у " + MASTER_NAME)
                .doesNotContain(SERVICE_NAME)
                .endsWith("Сьогодні о 13:50");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE appointment_id = ? "
                + "AND reminder_2h_sent", Integer.class, appointmentId)).isEqualTo(2);
    }

    @Test
    @DisplayName("D4: walk-in SMS gate off → the walk-in is neither selected nor claimed; LINK unchanged")
    void should_skipWalkIn_when_walkInGateOff() throws Exception {
        smsProperties.getSms().setEnabled(false);
        UUID appointmentId = insertWalkInVisit(at(NOW.plus(Duration.ofMinutes(110))), 1);
        UUID link = insertLinkBooking(at(NOW.plus(Duration.ofMinutes(120))), NOW.minus(Duration.ofDays(1)));

        reminderJob.sweepTwoHour();

        assertThat(sentText()).isEqualTo(TWO_HOUR_PREFIX + "14:00");
        assertThat(reminded(link)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE appointment_id = ? "
                + "AND reminder_2h_sent", Integer.class, appointmentId)).isZero();
    }

    @Test
    @DisplayName("D4: phone-less STAFF row (registered client) in the 2h window, gate on → never selected nor claimed")
    void should_notClaimPhonelessStaffRow_when_twoHourSweepRunsWithGateOn() {
        UUID bookingId = insertRegisteredClientStaffBooking(at(NOW.plus(Duration.ofMinutes(110))));

        reminderJob.sweepTwoHour();

        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_2h_sent FROM bookings WHERE id = ?",
                Boolean.class, bookingId)).as("guestPhone IS NOT NULL keeps the row out of the claim").isFalse();
    }

    @Test
    @DisplayName("D4: phone-less STAFF row (registered client) in the 24h window, gate on → never selected nor claimed")
    void should_notClaimPhonelessStaffRow_when_dayBeforeSweepRunsWithGateOn() {
        UUID bookingId = insertRegisteredClientStaffBooking(at(NOW.plus(Duration.ofHours(24))));

        reminderJob.sendReminders();

        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_sent FROM bookings WHERE id = ?",
                Boolean.class, bookingId)).as("guestPhone IS NOT NULL keeps the row out of the claim").isFalse();
    }

    @Test
    @DisplayName("D4: walk-in SMS gate off → the 24h sweep neither selects nor claims a walk-in")
    void should_skipWalkIn_when_dayBeforeSweepRunsWithGateOff() {
        smsProperties.getSms().setEnabled(false);
        UUID appointmentId = insertWalkInVisit(at(NOW.plus(Duration.ofHours(24))), 1);

        reminderJob.sendReminders();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE appointment_id = ? "
                + "AND reminder_sent", Integer.class, appointmentId)).as("gate off → walk-in not claimed").isZero();
    }

    /** A STAFF booking for a REGISTERED client: client_id set, no guest columns, no token (V226 STAFF sub-arm 1). */
    private UUID insertRegisteredClientStaffBooking(OffsetDateTime startsAt) {
        SeededUser client = insertUser("CLIENT", null);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 10, 0, 'STAFF', ?, ?, ?)",
                bookingId, client.id(), salon.masterId(), salon.masterServiceId(), salon.salonId(),
                startsAt, startsAt.plusMinutes(10), salon.ownerId(), at(NOW.minus(Duration.ofDays(1))), at(NOW));
        return bookingId;
    }

    /** A STAFF walk-in visit as StaffBookingService persists it since 412: header token only, tokenless items. */
    private UUID insertWalkInVisit(OffsetDateTime startsAt, int items) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, salon_id, status, booking_source, guest_name, "
                        + "guest_surname, guest_phone, cancel_token_hash, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'CONFIRMED', 'STAFF', ?, ?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), "
                        + "?, ?, ?)",
                appointmentId, salon.salonId(), GUEST_NAME, GUEST_SURNAME, E164_PHONE, UUID.randomUUID().toString(),
                salon.ownerId(), at(NOW.minus(Duration.ofDays(1))), at(NOW));
        for (int i = 0; i < items; i++) {
            OffsetDateTime itemStart = startsAt.plusMinutes(10L * i);
            jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, appointment_id, "
                            + "status, starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, guest_name, guest_surname, guest_phone, "
                            + "created_by_user_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 10, 0, 'STAFF', ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), salon.masterId(), salon.masterServiceId(), salon.salonId(), appointmentId,
                    itemStart, itemStart.plusMinutes(10), GUEST_NAME, GUEST_SURNAME, E164_PHONE, salon.ownerId(),
                    at(NOW.minus(Duration.ofDays(1))), at(NOW));
        }
        return appointmentId;
    }

    private String platformServiceName() {
        return jdbcTemplate.queryForObject("SELECT st.name_uk FROM master_services ms "
                + "JOIN service_definitions sd ON sd.id = ms.service_def_id "
                + "JOIN service_types st ON st.id = sd.service_type_id WHERE ms.id = ?",
                String.class, salon.masterServiceId());
    }

    private UUID insertLinkBooking(OffsetDateTime startsAt, Instant createdAt) {
        return insertGuestLinkBooking(LINK_PHONE, startsAt, Duration.ofMinutes(10), createdAt);
    }

    private UUID insertLinkVisit(OffsetDateTime startsAt, int items) {
        UUID appointmentId = insertGuestLinkAppointment(LINK_PHONE, NOW.minus(Duration.ofDays(1)), NOW);
        for (int i = 0; i < items; i++) {
            OffsetDateTime itemStart = startsAt.plusHours(i);
            UUID bookingId = insertLinkBooking(itemStart, NOW.minus(Duration.ofDays(1)));
            jdbcTemplate.update("UPDATE bookings SET appointment_id = ? WHERE id = ?", appointmentId, bookingId);
        }
        return appointmentId;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
