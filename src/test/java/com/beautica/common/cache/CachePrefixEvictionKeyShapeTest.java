package com.beautica.common.cache;

import com.beautica.auth.Role;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.CacheConfig;
import com.beautica.dashboard.service.DashboardService;
import com.beautica.location.repository.CityRepository;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.MasterService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The authoritative guard for the cache-key SHAPE contract that five production evictions depended
 * on and all five got wrong.
 *
 * <p><b>The bug this exists to prevent.</b> {@code MasterService#doEvictAvailableSlotsByMaster},
 * {@code MasterService#doEvictMasterCalendarByMaster}, {@code BookingService
 * #doEvictMasterCalendarEntries}, {@code BookingService#doEvictRevenueDashboard} and
 * {@code ServiceCatalogService#doEvictAvailableSlots} each scanned their Caffeine keyset with a
 * predicate of the form {@code k instanceof SimpleKey && k.toString().contains("[" + id + ",")}.
 * Every one of those caches declares an explicit SpEL {@code key = "{...}"}, and an explicit
 * {@code key} is NEVER wrapped in a {@link SimpleKey} — that type is produced only by the default
 * {@code SimpleKeyGenerator}, which Spring uses when no {@code key} attribute is given. So the
 * predicate could not match any real entry: {@code removeIf} removed nothing and all five
 * "evict after commit" calls were silent no-ops. Concretely, a deactivated master stayed bookable
 * for the full 60-second {@code available-slots} TTL — the exact hazard the eviction was added for.
 *
 * <p><b>Why it survived from 2026-07-04 to now.</b> The guarding tests hand-seeded
 * {@code new SimpleKey(...)} sentinels. A test that invents the key shape it then asserts on is
 * testing its own assumption, not the system: those sentinels matched the broken predicate, so the
 * tests were green precisely BECAUSE they shared the production bug. No amount of such tests can
 * catch this class of defect.
 *
 * <p><b>What this test does differently.</b> It never writes a key. For each of the three affected
 * caches it invokes the REAL production {@code @Cacheable} method through the real Spring proxy,
 * lets Spring compute and store the key, then reads that key back out of the native Caffeine map.
 * Every assertion is against ground truth. It then asserts the shared evictor actually removes it —
 * which fails against the pre-fix predicate — and that a bystander id's entry survives, so a
 * regression cannot be "fixed" by falling back to a blanket {@code clear()}.
 *
 * <p>It also pins {@link CacheKeyFixtures#spelKey}: the keys captured here must be {@code equals} to
 * what that helper builds, so the remaining tests which do seed keys are seeding a shape that has
 * been checked against Spring rather than guessed.
 *
 * <p>Collaborators are mocked into their cheapest early-return path — the point is to make Spring
 * compute a cache key for a real signature, not to exercise the method's business logic.
 */
@SpringBootTest(
        classes = {
                CacheConfig.class,
                MasterCachePrefixEvictor.class,
                SlotCalculationService.class,
                MasterService.class,
                DashboardService.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(CachePrefixEvictionKeyShapeTest.FixedClockConfig.class)
@DisplayName("Cache prefix eviction — real @Cacheable key shape (not a hand-seeded assumption)")
class CachePrefixEvictionKeyShapeTest {

    /**
     * A fixed {@link Clock} so {@code getAvailableSlots}' past/horizon date guards are deterministic.
     * Declared here rather than importing {@code ClockConfig} so the pinned instant is visible in
     * this file next to the dates derived from it.
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-22T09:00:00Z"), ZoneOffset.UTC);
        }
    }

    // MasterService collaborators
    @MockBean MasterRepository masterRepository;
    @MockBean UserRepository userRepository;
    @MockBean SalonRepository salonRepository;
    @MockBean WorkingHoursRepository workingHoursRepository;
    @MockBean BookingRepository bookingRepository;
    @MockBean CityRepository cityRepository;
    @MockBean BookingSlugService bookingSlugService;
    @MockBean AuthorizationService authorizationService;
    @MockBean SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    // SlotCalculationService collaborators
    @MockBean MasterServiceRepository masterServiceRepository;
    @MockBean MasterScheduleService masterScheduleService;
    @MockBean com.beautica.master.service.ScheduleDateMath scheduleDateMath;
    @MockBean com.beautica.common.util.TimeSlotCalculator timeSlotCalculator;

    // DashboardService collaborators
    @MockBean EntityManager entityManager;

    @Autowired SlotCalculationService slotCalculationService;
    @Autowired MasterService masterService;
    @Autowired DashboardService dashboardService;
    @Autowired MasterCachePrefixEvictor evictor;
    @Autowired CacheManager cacheManager;

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 22);
    private static final LocalDate SLOT_DATE = TODAY.plusDays(1);

    private UUID targetId;
    private UUID bystanderId;

    @BeforeEach
    void setUp() {
        targetId = UUID.randomUUID();
        bystanderId = UUID.randomUUID();
        Set.of("available-slots", "master-calendar", "revenue-dashboard")
                .forEach(name -> {
                    Cache c = cacheManager.getCache(name);
                    if (c != null) c.clear();
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // available-slots — MasterService#doEvictAvailableSlotsByMaster
    //                   + ServiceCatalogService#doEvictAvailableSlots
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("available-slots (SlotCalculationService#getAvailableSlots)")
    class AvailableSlots {

        /**
         * Stubs the assignment lookup so the method returns via its "master is inactive → no slots"
         * early return: a non-null result, which is all {@code @Cacheable} needs to store an entry.
         */
        private void stubInactiveMaster(UUID masterId, UUID masterServiceId) {
            Master master = mock(Master.class);
            when(master.isActive()).thenReturn(false);
            MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
            when(msa.isActive()).thenReturn(true);
            when(msa.getMaster()).thenReturn(master);
            when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                    .thenReturn(Optional.of(msa));
        }

        @Test
        @DisplayName("the key Spring really stores is a List, never a SimpleKey")
        void should_storeListKeyNotSimpleKey_when_populatedThroughRealCacheableProxy() {
            UUID serviceId = UUID.randomUUID();
            stubInactiveMaster(targetId, serviceId);
            Cache cache = cacheManager.getCache("available-slots");
            assertThat(cache).isNotNull();

            slotCalculationService.getAvailableSlots(targetId, SLOT_DATE, serviceId);

            Object key = onlyNativeKey(cache);
            assertThat(key)
                    .as("an explicit SpEL key = \"{...}\" is never wrapped in a SimpleKey — the "
                            + "pre-fix eviction predicate tested exactly this and so matched nothing")
                    .isNotInstanceOf(SimpleKey.class)
                    .isInstanceOf(List.class);
            assertThat(key)
                    .as("and it must equal what CacheKeyFixtures.spelKey builds, which is what pins "
                            + "that helper for every other cache test")
                    .isEqualTo(CacheKeyFixtures.spelKey(targetId, SLOT_DATE, serviceId));
        }

        @Test
        @DisplayName("the shared evictor removes a real proxy-written entry (fails pre-fix)")
        void should_evictRealProxyWrittenEntry_when_evictByKeyPrefixNow() {
            UUID serviceId = UUID.randomUUID();
            stubInactiveMaster(targetId, serviceId);
            Cache cache = cacheManager.getCache("available-slots");
            slotCalculationService.getAvailableSlots(targetId, SLOT_DATE, serviceId);
            assertThat(nativeKeys(cache)).hasSize(1);

            evictor.evictByKeyPrefixNow(targetId, "available-slots");

            assertThat(nativeKeys(cache))
                    .as("eviction must remove the entry Spring itself wrote — under the pre-fix "
                            + "`instanceof SimpleKey` predicate this stayed cached and a deactivated "
                            + "master remained bookable for the full 60s TTL")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unrelated master's entry survives — scoped eviction, not a blanket clear")
        void should_leaveOtherMastersEntryIntact_when_evictByKeyPrefixNow() {
            UUID serviceId = UUID.randomUUID();
            stubInactiveMaster(targetId, serviceId);
            stubInactiveMaster(bystanderId, serviceId);
            Cache cache = cacheManager.getCache("available-slots");
            slotCalculationService.getAvailableSlots(targetId, SLOT_DATE, serviceId);
            slotCalculationService.getAvailableSlots(bystanderId, SLOT_DATE, serviceId);

            evictor.evictByKeyPrefixNow(targetId, "available-slots");

            assertThat(nativeKeys(cache))
                    .as("only the target master's key may go — a regression must not be papered "
                            + "over with cache.clear() (Anti-Bug §F-6)")
                    .containsExactly(CacheKeyFixtures.spelKey(bystanderId, SLOT_DATE, serviceId));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // master-calendar — MasterService#doEvictMasterCalendarByMaster
    //                   + BookingService#doEvictMasterCalendarEntries
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("master-calendar (MasterService#getMasterCalendar)")
    class MasterCalendar {

        private static final LocalDate FROM = LocalDate.of(2026, 7, 22);
        private static final LocalDate TO = LocalDate.of(2026, 7, 29);
        private static final Pageable PAGEABLE = PageRequest.of(0, 20);

        private void stubEmptyCalendar(UUID masterId) {
            when(bookingRepository.findActiveIdsByMasterIdAndStartsAtBetween(
                    eq(masterId), any(OffsetDateTime.class), any(OffsetDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PAGEABLE, 0));
        }

        @Test
        @DisplayName("the key Spring really stores is a List, never a SimpleKey")
        void should_storeListKeyNotSimpleKey_when_populatedThroughRealCacheableProxy() {
            stubEmptyCalendar(targetId);
            Cache cache = cacheManager.getCache("master-calendar");
            assertThat(cache).isNotNull();

            masterService.getMasterCalendar(targetId, FROM, TO, PAGEABLE);

            Object key = onlyNativeKey(cache);
            assertThat(key).isNotInstanceOf(SimpleKey.class).isInstanceOf(List.class);
            assertThat(key)
                    .as("key = \"{#masterId, #from, #to, #pageable.pageNumber, #pageable.pageSize}\" "
                            + "— the page number/size are unwrapped ints, not the Pageable itself")
                    .isEqualTo(CacheKeyFixtures.spelKey(targetId, FROM, TO,
                            PAGEABLE.getPageNumber(), PAGEABLE.getPageSize()));
        }

        @Test
        @DisplayName("the shared evictor removes a real proxy-written entry (fails pre-fix)")
        void should_evictRealProxyWrittenEntry_when_evictByKeyPrefixNow() {
            stubEmptyCalendar(targetId);
            stubEmptyCalendar(bystanderId);
            Cache cache = cacheManager.getCache("master-calendar");
            masterService.getMasterCalendar(targetId, FROM, TO, PAGEABLE);
            masterService.getMasterCalendar(bystanderId, FROM, TO, PAGEABLE);

            evictor.evictByKeyPrefixNow(targetId, "master-calendar");

            assertThat(nativeKeys(cache))
                    .as("the writing master's calendar page must be evicted; an unrelated master's "
                            + "must survive")
                    .containsExactly(CacheKeyFixtures.spelKey(bystanderId, FROM, TO,
                            PAGEABLE.getPageNumber(), PAGEABLE.getPageSize()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // revenue-dashboard — BookingService#doEvictRevenueDashboard
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("revenue-dashboard (DashboardService#getRevenueSummary)")
    class RevenueDashboard {

        private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
        private static final LocalDate TO = LocalDate.of(2026, 7, 31);

        /**
         * Drives the "serviceDefId not owned by the actor" branch, which returns an empty response
         * before any {@link EntityManager} query — so no JPQL needs stubbing.
         */
        private UUID stubUnownedServiceFilter(UUID actorId) {
            Master master = mock(Master.class);
            when(master.getId()).thenReturn(UUID.randomUUID());
            when(masterRepository.findByUserId(actorId)).thenReturn(Optional.of(master));
            UUID serviceDefId = UUID.randomUUID();
            when(masterServiceRepository.existsByServiceDefIdAndMasterId(eq(serviceDefId), any()))
                    .thenReturn(false);
            return serviceDefId;
        }

        @Test
        @DisplayName("the key Spring really stores is a List keyed by actorId, never a SimpleKey")
        void should_storeListKeyNotSimpleKey_when_populatedThroughRealCacheableProxy() {
            UUID serviceDefId = stubUnownedServiceFilter(targetId);
            Cache cache = cacheManager.getCache("revenue-dashboard");
            assertThat(cache).isNotNull();

            dashboardService.getRevenueSummary(targetId, Role.INDEPENDENT_MASTER, FROM, TO,
                    null, serviceDefId, Optional.empty());

            Object key = onlyNativeKey(cache);
            assertThat(key).isNotInstanceOf(SimpleKey.class).isInstanceOf(List.class);
            assertThat(((List<?>) key).get(0))
                    .as("this cache's prefix is the ACTOR id, not a masterId — the shared evictor "
                            + "matches on key POSITION, which is why it applies here unchanged")
                    .isEqualTo(targetId);
            assertThat(key)
                    .as("null filter arguments must survive into the key as nulls")
                    .isEqualTo(CacheKeyFixtures.spelKey(targetId, FROM, TO, null, serviceDefId, null));
        }

        @Test
        @DisplayName("the shared evictor removes a real proxy-written entry (fails pre-fix)")
        void should_evictRealProxyWrittenEntry_when_evictByKeyPrefixNow() {
            UUID targetService = stubUnownedServiceFilter(targetId);
            UUID bystanderService = stubUnownedServiceFilter(bystanderId);
            Cache cache = cacheManager.getCache("revenue-dashboard");
            dashboardService.getRevenueSummary(targetId, Role.INDEPENDENT_MASTER, FROM, TO,
                    null, targetService, Optional.empty());
            dashboardService.getRevenueSummary(bystanderId, Role.INDEPENDENT_MASTER, FROM, TO,
                    null, bystanderService, Optional.empty());

            evictor.evictByKeyPrefixNow(targetId, "revenue-dashboard");

            assertThat(nativeKeys(cache))
                    .as("only the acting owner's dashboard entries may be dropped on a booking "
                            + "status transition — never every actor's (Anti-Bug §F-6)")
                    .containsExactly(
                            CacheKeyFixtures.spelKey(bystanderId, FROM, TO, null, bystanderService, null));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Reads keys straight off the native Caffeine map rather than through {@link Cache#get},
     * so the assertion sees the key object Spring actually stored instead of one the test supplied.
     */
    @SuppressWarnings("unchecked")
    private static Set<Object> nativeKeys(Cache cache) {
        Object nativeCache = cache.getNativeCache();
        assertThat(nativeCache)
                .as("this test is meaningless unless the cache really is Caffeine — the production "
                        + "eviction path scans its keyset directly")
                .isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);
        return ((com.github.benmanes.caffeine.cache.Cache<Object, Object>) nativeCache).asMap().keySet();
    }

    private static Object onlyNativeKey(Cache cache) {
        Set<Object> keys = nativeKeys(cache);
        assertThat(keys).hasSize(1);
        return keys.iterator().next();
    }
}
