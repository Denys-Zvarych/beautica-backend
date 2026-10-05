package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.service.BookingMasterService;
import com.beautica.common.TimeZones;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache invalidation for the bookability work (perf/security audit 2026-10-05), through the real
 * write paths and real Postgres:
 * <ul>
 *   <li><b>Finding 1</b> — a schedule write clears the discovery search caches ONLY when it flips
 *       the master's structural search verdict, and only the surface that master is listed on;</li>
 *   <li><b>Finding 2</b> — {@code master-bookable-assignments} (the cached strict verdict behind
 *       the public services tab) is evicted after commit by a booking that takes the master's last
 *       free slot, so the tab empties without waiting for the 60-second TTL.</li>
 * </ul>
 */
@DisplayName("Bookability caches — search cleared only on a verdict flip; strict services-tab verdict evicted by booking")
class BookabilityCacheEvictionIT extends AbstractIntegrationTest {

    private static final String SENTINEL_KEY = "sentinel";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MasterScheduleService masterScheduleService;

    private ServiceTestFixtures fixtures;
    private BookabilityHttp http;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        http = new BookabilityHttp(restTemplate, objectMapper);
    }

    // ── finding 1: search cleared only on a flip ────────────────────────────────────────────

    @Test
    @DisplayName("a day-off override on a still-bookable independent master clears NO search cache")
    void should_keepSearchCaches_when_dayOffDoesNotFlipVerdict() throws Exception {
        Indep master = bookableIndependentMaster();
        seedSearchSentinels();

        masterScheduleService.upsertOverride(master.userId(), master.masterId(),
                new ScheduleOverrideRequest(kyivToday().plusDays(3), ScheduleExceptionKind.DAY_OFF, null));

        assertThat(searchSentinelsPresent())
                .as("the master still has a weekly template — the day off cannot change search membership")
                .containsOnly(true);
    }

    @Test
    @DisplayName("deleting an independent master's only schedule flips the verdict — master search "
            + "(pages + totals) is cleared, salon search is left alone")
    void should_clearMasterSearch_when_scheduleDeleteFlipsVerdict() throws Exception {
        Indep master = bookableIndependentMaster();
        UUID scheduleId = onlyScheduleId(master.masterId());
        seedSearchSentinels();

        masterScheduleService.deleteWeeklySchedule(master.userId(), master.masterId(), scheduleId);

        assertThat(sentinel(SearchCacheNames.MASTERS_BROWSE)).isFalse();
        assertThat(sentinel(SearchCacheNames.MASTERS_QUERY)).isFalse();
        assertThat(sentinel(SearchCacheNames.MASTERS_TOTAL)).isFalse();
        assertThat(sentinel(SearchCacheNames.SALONS_BROWSE)).isTrue();
        assertThat(sentinel(SearchCacheNames.SALONS_TOTAL)).isTrue();
    }

    @Test
    @DisplayName("a salon master's first working hours flip the verdict — salon search is cleared, "
            + "master search is left alone")
    void should_clearSalonSearch_when_salonMasterGainsFirstSchedule() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("owner-flip-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Flip Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salonId, masterId);
        LocalDate day = kyivToday().plusDays(2);
        seedSearchSentinels();

        // Through the real owner-facing route (PUT /masters/{id}/overrides/{date}): the salon owner
        // is authorised by the request's JWT, not by a bare actor id.
        ResponseEntity<String> put = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/overrides/" + day, HttpMethod.PUT,
                new HttpEntity<>(new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        List.of(new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(12, 0)))),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(put.getStatusCode()).as(put.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(sentinel(SearchCacheNames.SALONS_BROWSE)).isFalse();
        assertThat(sentinel(SearchCacheNames.SALONS_QUERY)).isFalse();
        assertThat(sentinel(SearchCacheNames.SALONS_TOTAL)).isFalse();
        assertThat(sentinel(SearchCacheNames.MASTERS_BROWSE)).isTrue();
    }

    // ── finding 2: the strict verdict cache is evicted by a booking write ────────────────────

    @Test
    @DisplayName("a booking that takes the master's LAST free slot empties the public services tab "
            + "immediately — no 60-second TTL wait")
    void should_emptyServicesTab_when_bookingTakesLastSlot() throws Exception {
        Indep master = independentMasterWithServiceOnly();
        LocalDate day = kyivToday().plusDays(2);
        masterScheduleService.upsertOverride(master.userId(), master.masterId(), new ScheduleOverrideRequest(
                day, ScheduleExceptionKind.CUSTOM_HOURS,
                List.of(new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(11, 0)))));
        UUID assignmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? AND is_active = true", UUID.class, master.masterId());

        assertThat(http.get("/api/v1/masters/" + master.masterId() + "/services"))
                .as("one free 60-min slot (10:00-11:00) — the service is bookable").hasSize(1);
        assertThat(bookableAssignmentsCache().get(List.of(master.masterId())))
                .as("arrange check — the verdict really is cached, so only an eviction can flip it")
                .isNotNull();

        String clientToken = fixtures.createClientAndGetToken("last-slot-" + System.nanoTime() + "@beautica.test");
        ResponseEntity<String> booked = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(new CreateBookingRequest(master.masterId(), assignmentId,
                        ZonedDateTime.of(day, LocalTime.of(10, 0), TimeZones.KYIV), null, null, false),
                        fixtures.bearerHeaders(clientToken)),
                String.class);
        assertThat(booked.getStatusCode()).as(booked.getBody()).isEqualTo(HttpStatus.CREATED);

        assertThat(http.get("/api/v1/masters/" + master.masterId() + "/services"))
                .as("the only slot is taken — the cached verdict must have been evicted after commit")
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private record Indep(UUID masterId, UUID userId) {
    }

    private Indep independentMasterWithServiceOnly() throws Exception {
        String email = "indep-flip-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        return new Indep(masterId, userId);
    }

    private Indep bookableIndependentMaster() throws Exception {
        Indep master = independentMasterWithServiceOnly();
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master.masterId());
        return master;
    }

    private UUID onlyScheduleId(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT id FROM weekly_schedules WHERE master_id = ?", UUID.class, masterId);
    }

    private static final List<String> SEARCH_CACHES = List.of(
            SearchCacheNames.MASTERS_BROWSE, SearchCacheNames.MASTERS_QUERY, SearchCacheNames.MASTERS_TOTAL,
            SearchCacheNames.SALONS_BROWSE, SearchCacheNames.SALONS_QUERY, SearchCacheNames.SALONS_TOTAL);

    private void seedSearchSentinels() {
        SEARCH_CACHES.forEach(name -> cache(name).put(SENTINEL_KEY, "cached"));
    }

    private List<Boolean> searchSentinelsPresent() {
        return SEARCH_CACHES.stream().map(this::sentinel).toList();
    }

    private boolean sentinel(String cacheName) {
        return cache(cacheName).get(SENTINEL_KEY) != null;
    }

    private Cache bookableAssignmentsCache() {
        return cache(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE);
    }

    private Cache cache(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).as("cache %s registered", name).isNotNull();
        return cache;
    }

    private static LocalDate kyivToday() {
        return LocalDate.now(TimeZones.KYIV);
    }
}
