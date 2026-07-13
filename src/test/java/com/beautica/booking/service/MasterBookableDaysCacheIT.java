package com.beautica.booking.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.master.dto.MasterWorkingDayResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23.x — round-trip cache contract for the new {@code master-bookable-days} cache backing
 * {@code GET /masters/{id}/working-days?serviceId=…}, exercised through the REAL Spring stack: the real
 * {@code @Cacheable} proxy on {@link SlotCalculationService#getBookableWorkingDays}, the real
 * {@link com.beautica.common.cache.MasterCachePrefixEvictor}, the real {@code cacheEvictionExecutor}
 * (a {@code SyncTaskExecutor} under the {@code test} profile, so eviction is deterministic and inline),
 * and the real {@code CacheConfig} registration of the cache.
 *
 * <p>{@code MasterCachePrefixEvictorTest} unit-tests the prefix scan in isolation; the booking-write
 * {@code afterCommit} eviction hook is covered by the booking-service cache tests. What is NOT covered
 * anywhere is that THIS cache actually (a) serves a stale verdict when the underlying bookings change
 * WITHOUT an eviction, and (b) recomputes the fresh verdict once
 * {@link SlotCalculationService#evictBookableFutureSlotsByMaster} fires — i.e. that the whole cache +
 * evictor + executor + config wiring closes the loop. That is what this test pins, deterministically.
 *
 * <p>"Now" is frozen so the seeded future day is unambiguously inside the booking horizon and the
 * verdict flips purely on the presence of a booking, never on the clock.
 */
@DisplayName("MasterBookableDaysCacheIT — master-bookable-days cache serves stale then recomputes on prefix evict")
@Import(MasterBookableDaysCacheIT.FrozenKyivClockConfig.class)
class MasterBookableDaysCacheIT extends AbstractIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final LocalTime NOW_LOCAL = LocalTime.of(10, 0);
    private static final LocalDate DAY = TODAY.plusDays(10);

    @TestConfiguration
    static class FrozenKyivClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(
                    TODAY.atTime(NOW_LOCAL).atZone(TimeZones.KYIV).toInstant(),
                    TimeZones.KYIV);
        }
    }

    @Autowired
    private SlotCalculationService slotCalculationService;

    @Test
    @DisplayName("a full-day booking inserted out-of-band is masked by the cache until the master prefix is evicted")
    void should_serveStaleThenRecompute_when_prefixEvicted() {
        Master m = seedIndependentMaster();
        UUID svc = addService(m, 60, 0);
        seedInterval(m.masterId(), DAY, DAY, DAY.getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));

        // 1) First read — no bookings → the day is bookable. This warms the master-bookable-days cache.
        assertThat(bookableDay(m.masterId(), svc))
                .as("an empty 09:00–17:00 future day is bookable")
                .isTrue();

        // 2) Fill the whole day with a CONFIRMED booking via raw JDBC — this does NOT go through any
        //    booking-write path, so no afterCommit eviction fires and the cache is left intact.
        insertBooking(m.masterId(), svc, seedClient(), DAY,
                LocalTime.of(9, 0), LocalTime.of(17, 0), "CONFIRMED");

        // 3) Second read — identical cache key → the STALE cached `true` is returned though the day is
        //    now fully booked. This proves the @Cacheable proxy is genuinely serving from the cache.
        assertThat(bookableDay(m.masterId(), svc))
                .as("without an eviction the cache still serves the pre-booking verdict (stale by design)")
                .isTrue();

        // 4) Evict by master prefix — the same call every booking/schedule write routes through.
        slotCalculationService.evictBookableFutureSlotsByMaster(m.masterId());

        // 5) Third read — the cache miss recomputes against the now-booked day → false. The loop closes.
        assertThat(bookableDay(m.masterId(), svc))
                .as("after the master-prefix eviction the verdict is recomputed and reflects the booking")
                .isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private boolean bookableDay(UUID masterId, UUID masterServiceId) {
        List<MasterWorkingDayResponse> days =
                slotCalculationService.getBookableWorkingDays(masterId, DAY, DAY, masterServiceId);
        assertThat(days).hasSize(1);
        return days.get(0).working();
    }

    // ── raw-JDBC seeding (mirrors BookingAvailabilityAgreementIT) ──────────────────────────────

    private record Master(UUID masterId, UUID userId) {
    }

    private Master seedIndependentMaster() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', 'Ind', "
                        + "'Master', true, true)",
                userId, "ind-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return new Master(masterId, userId);
    }

    private UUID addService(Master m, int durationMinutes, int bufferMinutes) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, "
                        + "service_type_id, base_duration_minutes, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) VALUES (?, 'INDEPENDENT_MASTER', ?, 'Svc', ?, "
                        + "?, ?, ?, true, NOW(), NOW())",
                serviceDefId, m.userId(), resolveServiceTypeId(), durationMinutes, PRICE, bufferMinutes);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, m.masterId(), serviceDefId);
        return masterServiceId;
    }

    private void seedInterval(UUID masterId, LocalDate validFrom, LocalDate validTo,
                              int isoDow, LocalTime start, LocalTime end) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        jdbcTemplate.update("INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, "
                        + "end_time) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    private UUID seedClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'CLIENT', 'Cli', 'Ent', true, true)",
                id, "cli-" + id + "@beautica.test");
        return id;
    }

    private void insertBooking(UUID masterId, UUID masterServiceId, UUID clientId, LocalDate date,
                               LocalTime start, LocalTime end, String status) {
        OffsetDateTime startsAt = date.atTime(start).atZone(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime endsAt = date.atTime(end).atZone(TimeZones.KYIV).toOffsetDateTime();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, status,
                startsAt, endsAt, PRICE, minutes);
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }
}
