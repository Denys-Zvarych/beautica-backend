package com.beautica.client;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.service.BookingService;
import com.beautica.client.dto.PassportResponse;
import com.beautica.client.service.ClientPassportService;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.CacheConfig;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.service.ReviewService;
import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration gate for the {@code client-passport} cache and its invalidation
 * ({@code ClientPassportCacheEvictor}, 2026-08 perf audit F3).
 *
 * <h2>What this class is for</h2>
 * A cache that never evicts passes every green-path test — reading twice and asserting the same
 * value proves only that the cache works, never that invalidation does. So every test here
 * mutates the underlying data BETWEEN two reads and asserts the SECOND read moved. If the
 * {@code @TransactionalEventListener} is removed or its phase changed, these go red.
 *
 * <p>The fixture values are chosen so the delta cannot be swallowed: the first read is the
 * genuine empty state (0 bookings / 0 reviews), so a stale second read returns a value that is
 * structurally impossible after the write, not merely a rounded-away difference.
 *
 * <p>Runs against the real {@code CacheConfig} + real Caffeine, real
 * {@code BookingService.completeBooking} and real {@code ReviewService.createReview} — the
 * eviction is exercised through the actual publish sites, not by firing events by hand, so the
 * publisher wiring is under test too.
 */
@DisplayName("BEAUTY PASSPORT — client-passport cache invalidation")
class ClientPassportCacheIT extends AbstractIntegrationTest {

    @Autowired
    private ClientPassportService passportService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private CacheManager cacheManager;

    // ── the cache is the CONFIGURED one, not a dynamically-created default ───────

    @Test
    @DisplayName("client-passport is a registered custom cache carrying the configured maximumSize "
            + "and expireAfterWrite — never Caffeine's unbounded default builder")
    void should_carryConfiguredSizingAndTtl_when_cacheResolved() {
        // 2026-08 security audit LOW. If the registration in CacheConfig were renamed or dropped,
        // a CaffeineCacheManager with dynamic creation left ON would hand @Cacheable a cache built
        // from Caffeine's DEFAULT builder: no maximumSize, no TTL — per-client PII-derived
        // aggregates retained for the life of the JVM — and the evictor's getCache() would return
        // that SAME cache, so nothing would fail loudly. Asserting "the cache is non-null" cannot
        // detect that; asserting its POLICY can.
        Cache springCache = cacheManager.getCache(ClientPassportService.CLIENT_PASSPORT_CACHE);
        assertThat(springCache)
                .as("dynamic creation is disabled in CacheConfig, so a null here means the "
                        + "registration was lost")
                .isInstanceOf(CaffeineCache.class);

        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                ((CaffeineCache) springCache).getNativeCache();

        assertThat(nativeCache.policy().eviction())
                .as("a size-bounded eviction policy is absent on a default-built cache")
                .isPresent()
                .get()
                .extracting(Policy.Eviction::getMaximum)
                .isEqualTo((long) CacheConfig.CLIENT_PASSPORT_MAX_ENTRIES);

        assertThat(nativeCache.policy().expireAfterWrite())
                .as("expireAfterWrite is the backstop for a dropped eviction — a default-built "
                        + "cache never expires, so PII-derived entries would be retained forever")
                .isPresent()
                .get()
                .extracting(p -> p.getExpiresAfter(TimeUnit.MINUTES))
                .isEqualTo(CacheConfig.CLIENT_PASSPORT_TTL_MINUTES);
    }

    @Test
    @DisplayName("an unregistered cache name resolves to null instead of a silent unbounded cache")
    void should_returnNull_when_cacheNameIsNotRegistered() {
        // The general half of the same fix: setCacheNames(List.of()) turns dynamic creation off,
        // so a typo'd or dropped registration fails loudly (Spring's cache interceptor raises
        // "Cannot find cache named ...") instead of silently minting an unbounded, never-expiring
        // cache. Without that flag this assertion returns a live cache and goes red.
        assertThat(cacheManager.getCache("no-such-cache-" + UUID.randomUUID()))
                .isNull();
    }

    // ── the cache exists and is actually populated ──────────────────────────────

    @Test
    @DisplayName("the passport read is cached under the caller's own principal id")
    void should_cacheUnderClientUserId_when_passportRead() {
        UUID clientId = createClient("passport-cache-hit@beautica.test");

        PassportResponse first = passportService.getPassport(clientId);

        Cache.ValueWrapper cached = passportCache().get(clientId);
        assertThat(cached)
                .as("the entry must be keyed on the client's own user id — the key IS the "
                        + "isolation boundary; a wider key would be an IDOR")
                .isNotNull();
        assertThat(cached.get()).isEqualTo(first);
    }

    @Test
    @DisplayName("one client's cached passport is never reachable under another client's key")
    void should_isolateEntries_when_twoClientsReadTheirPassports() {
        UUID alice = createClient("passport-cache-alice@beautica.test");
        UUID bob = createClient("passport-cache-bob@beautica.test");
        UUID master = createIndependentMaster("passport-cache-iso-master@beautica.test");
        UUID masterService = createIndependentMasterService(master);
        // Only Alice has history.
        completeBookingFor(alice, master, masterService);

        PassportResponse alicePassport = passportService.getPassport(alice);
        PassportResponse bobPassport = passportService.getPassport(bob);

        assertThat(alicePassport.bookingsConsidered()).isEqualTo(1);
        assertThat(bobPassport.bookingsConsidered())
                .as("Bob must never be served Alice's cached passport")
                .isZero();
    }

    // ── invalidation: booking completion ────────────────────────────────────────

    @Test
    @DisplayName("completing a booking evicts the client's passport — the SECOND read reflects it, "
            + "never the cached empty state")
    void should_reflectNewBooking_when_passportReadAgainAfterCompletion() {
        UUID clientId = createClient("passport-evict-booking@beautica.test");
        UUID master = createIndependentMaster("passport-evict-booking-master@beautica.test");
        UUID masterService = createIndependentMasterService(master);

        PassportResponse before = passportService.getPassport(clientId);
        assertThat(before.bookingsConsidered()).as("precondition: genuinely the empty state").isZero();
        assertThat(before.budget()).isNull();

        completeBookingFor(clientId, master, masterService);

        PassportResponse after = passportService.getPassport(clientId);

        assertThat(after.bookingsConsidered())
                .as("a stale cached passport would still report 0 here — this is the eviction assertion")
                .isEqualTo(1);
        assertThat(after.budget())
                .as("the budget band appears only once a COMPLETED booking exists")
                .isNotNull();
        assertThat(after.budget().avg()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("completing a SECOND booking evicts again — eviction is not a one-shot")
    void should_reflectSecondBooking_when_passportReadAgain() {
        UUID clientId = createClient("passport-evict-booking2@beautica.test");
        UUID master = createIndependentMaster("passport-evict-booking2-master@beautica.test");
        UUID masterService = createIndependentMasterService(master);

        completeBookingFor(clientId, master, masterService);
        assertThat(passportService.getPassport(clientId).bookingsConsidered()).isEqualTo(1);

        completeBookingFor(clientId, master, masterService);

        assertThat(passportService.getPassport(clientId).bookingsConsidered())
                .as("the second completion must evict the entry the first one repopulated")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("completing one client's booking does not flush another client's passport (per-key evict)")
    void should_evictOnlyTheOwningClient_when_bookingCompleted() {
        UUID acting = createClient("passport-evict-scope-acting@beautica.test");
        UUID bystander = createClient("passport-evict-scope-bystander@beautica.test");
        UUID master = createIndependentMaster("passport-evict-scope-master@beautica.test");
        UUID masterService = createIndependentMasterService(master);

        passportService.getPassport(acting);
        passportService.getPassport(bystander);

        completeBookingFor(acting, master, masterService);

        assertThat(passportCache().get(acting))
                .as("the acting client's entry must be gone")
                .isNull();
        assertThat(passportCache().get(bystander))
                .as("a bystander's entry must SURVIVE — allEntries/clear() would be a thundering herd")
                .isNotNull();
    }

    // ── invalidation: review creation ───────────────────────────────────────────

    @Test
    @DisplayName("writing a review evicts the client's passport — reviewsWritten moves on the SECOND read")
    void should_reflectNewReview_when_passportReadAgainAfterReviewCreated() {
        UUID clientId = createClient("passport-evict-review@beautica.test");
        UUID master = createIndependentMaster("passport-evict-review-master@beautica.test");
        UUID masterService = createIndependentMasterService(master);
        UUID bookingId = completeBookingFor(clientId, master, masterService);

        PassportResponse before = passportService.getPassport(clientId);
        assertThat(before.reviewsWritten()).as("precondition: nothing authored yet").isZero();

        reviewService.createReview(clientId, new CreateReviewRequest(bookingId, 5, "Great"));

        assertThat(passportService.getPassport(clientId).reviewsWritten())
                .as("a stale cached passport would still report 0 — this is the eviction assertion")
                .isEqualTo(1);
    }

    // ── the empty state and the missing-user contract survive caching ────────────

    @Test
    @DisplayName("a brand-new client still gets a real memberSinceYear and a zero reviewsWritten")
    void should_serveRealIdentityStrip_when_clientHasNoHistory() {
        UUID clientId = createClient("passport-cache-newbie@beautica.test");
        int registrationYear = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(YEAR FROM created_at AT TIME ZONE 'Europe/Kyiv')::int "
                        + "FROM users WHERE id = ?", Integer.class, clientId);

        PassportResponse passport = passportService.getPassport(clientId);

        assertThat(passport.bookingsConsidered()).isZero();
        assertThat(passport.reviewsWritten()).isZero();
        assertThat(passport.memberSinceYear())
                .as("a real registration year, never a fabricated or zero one")
                .isEqualTo(registrationYear);
    }

    @Test
    @DisplayName("a missing user row raises NotFoundException every time — the 404 is never cached away")
    void should_keepThrowingNotFound_when_userRowMissing() {
        UUID ghost = UUID.randomUUID();

        assertThatThrownBy(() -> passportService.getPassport(ghost))
                .isInstanceOf(NotFoundException.class);

        assertThat(passportCache().get(ghost))
                .as("Spring caches normal returns only — a thrown exception must leave no entry, "
                        + "so a missing user can never be papered over by a cached standing")
                .isNull();

        // Second call must still fail rather than serve anything.
        assertThatThrownBy(() -> passportService.getPassport(ghost))
                .isInstanceOf(NotFoundException.class);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private Cache passportCache() {
        Cache cache = cacheManager.getCache(ClientPassportService.CLIENT_PASSPORT_CACHE);
        assertThat(cache).as("client-passport must be registered in CacheConfig").isNotNull();
        return cache;
    }

    /** Seeds a CONFIRMED, already-elapsed booking and drives it through the real completion path. */
    private UUID completeBookingFor(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        bookingService.completeBooking(masterUserId(masterId), bookingId);
        return bookingId;
    }

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                id, email);
        return id;
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = masterUserId(masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, "
                        + "NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID masterUserId(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM service_types WHERE is_active = true ORDER BY id LIMIT 1", UUID.class);
    }
}
