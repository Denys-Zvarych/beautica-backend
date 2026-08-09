package com.beautica.booking.service;

import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.beautica.common.cache.CacheKeyFixtures;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        // The REAL prefix evictor, never a @MockBean: it owns the cache-key-shape predicate whose
        // silent mismatch made five evictions no-ops, so these tests must execute it.
        classes = {BookingService.class, com.beautica.common.cache.MasterCachePrefixEvictor.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(BookingServiceCacheTest.FixedClockConfig.class)
@DisplayName("BookingService — @CacheEvict behaviour")
class BookingServiceCacheTest {

    @EnableCaching
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneId.of("Europe/Kyiv"));
        }

        /**
         * CAFFEINE, deliberately — not ConcurrentMapCache. The production evictions scan the native
         * Caffeine keyset and match each key's first element; against a non-Caffeine cache the
         * evictor takes its coarse {@code clear()} fallback instead, which succeeds no matter how
         * wrong the key predicate is. These tests previously used ConcurrentMapCache with a plain
         * "sentinel" String key and so passed against a predicate that could never match a real
         * entry. Keep these Caffeine, and keep the seeded keys realistically shaped.
         */
        @Bean
        CacheManager cacheManager() {
            SimpleCacheManager m = new SimpleCacheManager();
            m.setCaches(List.of(
                    new CaffeineCache("master-calendar", Caffeine.newBuilder().build()),
                    new CaffeineCache("revenue-dashboard", Caffeine.newBuilder().build()),
                    new CaffeineCache("available-slots", Caffeine.newBuilder().build())
            ));
            return m;
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() { return new Object(); }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                @Override
                protected void doCommit(DefaultTransactionStatus status) {}

                @Override
                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
        }
    }

    @MockBean BookingRepository bookingRepository;
    @MockBean MasterRepository masterRepository;
    @MockBean MasterServiceRepository masterServiceRepository;
    @MockBean UserRepository userRepository;
    @MockBean SalonRepository salonRepository;
    @MockBean AuthorizationService authz;
    @MockBean NotificationOutboxService outboxService;
    @MockBean SlotCalculationService slotCalculationService;
    @MockBean ReviewRepository reviewRepository;
    @MockBean ClientReviewRepository clientReviewRepository;
    @MockBean DiscoveryLocationResolver discoveryLocationResolver;
    // Phase 23.x (perf/security #2): BookingService evicts the salon-service-catalog cache via this
    // collaborator after commit. Not on the @SpringBootTest classes list, so mock it for wiring.
    @MockBean com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    // Phase 26.2: BookingService now validates the optional date-range filter via this
    // collaborator's span-only guard. Not on the @SpringBootTest classes list, so mock it here.
    @MockBean ScheduleDateMath dateMath;
    // Track 27.x: BookingService locks/collapses the appointment header when a client cancels one
    // leg of a multi-service visit. The eviction tests below all use single, appointment-less
    // bookings, so that branch short-circuits — this mock exists purely to satisfy the wiring.
    @MockBean AppointmentTransitionService appointmentTransitionService;
    // Phase 30.6: AppointmentRepository entered the BookingService constructor (per-item
    // reschedule). This sliced @SpringBootTest lists only BookingService + the prefix evictor as
    // real beans, so every other constructor parameter must be supplied here — omitting it failed
    // all four tests below at context load with NoSuchBeanDefinitionException, not on an assertion.
    // The eviction paths exercised here use appointment-less bookings, so the mock is never called.
    @MockBean com.beautica.booking.repository.AppointmentRepository appointmentRepository;

    /** Fixed so a test can seed a master-calendar key that really belongs to the booking's master. */
    private static final UUID MASTER_ID = UUID.randomUUID();

    @Autowired BookingService bookingService;
    @Autowired CacheManager cacheManager;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired Clock clock;

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("master-calendar");
        if (cache != null) {
            cache.clear();
        }
    }

    // TODO(24.7): add a create-path cache-eviction test (bookings are now born CONFIRMED via
    // doCreateBooking — there is no more standalone confirmBooking() transition to cover).

    @Test
    @DisplayName("declineBooking evicts the master-calendar cache")
    void should_evictMasterCalendarCache_when_declineBookingCalled() {
        UUID actorUserId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // Arrange — put a sentinel entry in master-calendar to confirm eviction
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        Object calendarKey = CacheKeyFixtures.spelKey(MASTER_ID, LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        Object bystanderKey = CacheKeyFixtures.spelKey(UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        cache.put(calendarKey, "value");
        cache.put(bystanderKey, "value");

        // Phase 24.2: declineBooking now transitions CONFIRMED → DECLINED (provider-initiated
        // cancellation) — there is no more PENDING source state.
        Booking booking = mockBookingInStatus(bookingId, BookingStatus.CONFIRMED);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        doNothing().when(authz).enforceCanCancelBooking(actorUserId, booking);

        StatusUpdateRequest request = new StatusUpdateRequest(
                CancellationReason.PROVIDER_UNAVAILABLE,
                "Master unavailable"
        );

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            bookingService.declineBooking(actorUserId, bookingId, request);
            return null;
        });

        // Assert — sentinel must be gone: allEntries=true evicts the entire cache
        assertThat(cache.get(bystanderKey))
                .as("eviction is scoped to the booking's master — an unrelated master's calendar "
                        + "page must survive, so a regression cannot hide behind a blanket clear()")
                .isNotNull();
        assertThat(cache.get(calendarKey))
                .as("the booking master's master-calendar entry must be evicted after declineBooking")
                .isNull();
    }

    @Test
    @DisplayName("completeBooking evicts the master-calendar cache")
    void should_evictMasterCalendarCache_when_completeBookingCalled() {
        UUID actorUserId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // Arrange — put a sentinel entry in master-calendar to confirm eviction
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        Object calendarKey = CacheKeyFixtures.spelKey(MASTER_ID, LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        Object bystanderKey = CacheKeyFixtures.spelKey(UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        cache.put(calendarKey, "value");
        cache.put(bystanderKey, "value");

        Booking booking = mockBookingInStatus(bookingId, BookingStatus.CONFIRMED);
        // Phase 27.1: completeBooking now requires now >= startsAt (assertElapsedForComplete) —
        // mockBookingInStatus's default startsAt is FUTURE (needed by the decline/not-complete
        // tests sharing this helper), so override it to an ELAPSED time for this test only.
        when(booking.getStartsAt()).thenReturn(OffsetDateTime.now(clock).minusHours(1));

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        // Phase 18.4: completion authorization goes through enforceCanCompleteBooking.
        doNothing().when(authz).enforceCanCompleteBooking(actorUserId, booking);

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            bookingService.completeBooking(actorUserId, bookingId);
            return null;
        });

        // Assert — sentinel must be gone: allEntries=true evicts the entire cache
        assertThat(cache.get(bystanderKey))
                .as("eviction is scoped to the booking's master — an unrelated master's calendar "
                        + "page must survive, so a regression cannot hide behind a blanket clear()")
                .isNotNull();
        assertThat(cache.get(calendarKey))
                .as("the booking master's master-calendar entry must be evicted after completeBooking")
                .isNull();
    }

    @Test
    @DisplayName("notCompleteBooking evicts the master-calendar cache")
    void should_evictMasterCalendarCache_when_notCompleteBookingCalled() {
        UUID actorUserId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // Arrange — put a sentinel entry in master-calendar to confirm eviction
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        Object calendarKey = CacheKeyFixtures.spelKey(MASTER_ID, LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        Object bystanderKey = CacheKeyFixtures.spelKey(UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        cache.put(calendarKey, "value");
        cache.put(bystanderKey, "value");

        Booking booking = mockBookingInStatus(bookingId, BookingStatus.CONFIRMED);
        // not-complete has no temporal guard — an elapsed startsAt is just the conventional
        // no-show fixture here, not a requirement.
        when(booking.getStartsAt()).thenReturn(OffsetDateTime.now(clock).minusHours(1));

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);
        doNothing().when(authz).enforceCanCancelBooking(actorUserId, booking);

        StatusUpdateRequest request = new StatusUpdateRequest(
                CancellationReason.CLIENT_NO_SHOW,
                "Client did not show up"
        );

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            bookingService.notCompleteBooking(actorUserId, bookingId, request);
            return null;
        });

        // Assert — sentinel must be gone: allEntries=true evicts the entire cache
        assertThat(cache.get(bystanderKey))
                .as("eviction is scoped to the booking's master — an unrelated master's calendar "
                        + "page must survive, so a regression cannot hide behind a blanket clear()")
                .isNotNull();
        assertThat(cache.get(calendarKey))
                .as("the booking master's master-calendar entry must be evicted after notCompleteBooking")
                .isNull();
    }

    @Test
    @DisplayName("cancelBooking evicts the master-calendar cache")
    void should_evictMasterCalendarCache_when_cancelBookingCalled() {
        UUID clientUserId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // Arrange — put a sentinel entry in master-calendar to confirm eviction
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        Object calendarKey = CacheKeyFixtures.spelKey(MASTER_ID, LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        Object bystanderKey = CacheKeyFixtures.spelKey(UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        cache.put(calendarKey, "value");
        cache.put(bystanderKey, "value");

        // cancelBooking checks booking.getClient().getId().equals(clientUserId) — wire it
        Booking booking = mockBookingInStatus(bookingId, BookingStatus.CONFIRMED);
        when(booking.getClient().getId()).thenReturn(clientUserId);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenReturn(booking);

        CancelBookingRequest request = new CancelBookingRequest(
                CancellationReason.CLIENT_CANCELLED,
                "Changed my mind"
        );

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            bookingService.cancelBooking(clientUserId, bookingId, request);
            return null;
        });

        // Assert — sentinel must be gone: allEntries=true evicts the entire cache
        assertThat(cache.get(bystanderKey))
                .as("eviction is scoped to the booking's master — an unrelated master's calendar "
                        + "page must survive, so a regression cannot hide behind a blanket clear()")
                .isNotNull();
        assertThat(cache.get(calendarKey))
                .as("the booking master's master-calendar entry must be evicted after cancelBooking")
                .isNull();
    }

    /**
     * Builds a fully-mocked Booking in the requested status with every field
     * BookingResponse.from() and registerSlotEviction() touch.
     */
    private Booking mockBookingInStatus(UUID bookingId, BookingStatus status) {
        Booking booking = mock(Booking.class);
        User client = mock(User.class);
        Master master = mock(Master.class);
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        ServiceDefinition serviceDef = mock(ServiceDefinition.class);

        when(booking.getStatus()).thenReturn(status);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getMasterService()).thenReturn(msa);
        when(msa.getServiceDefinition()).thenReturn(serviceDef);
        when(msa.getId()).thenReturn(UUID.randomUUID());
        when(client.getId()).thenReturn(UUID.randomUUID());
        when(master.getId()).thenReturn(MASTER_ID);
        when(serviceDef.getName()).thenReturn("Test Service");
        when(booking.getStartsAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));
        when(booking.getEndsAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).plusHours(3));
        when(booking.getPriceAtBooking()).thenReturn(new BigDecimal("200.00"));
        when(booking.getDurationMinutesAtBooking()).thenReturn(60);
        when(booking.getCreatedAt()).thenReturn(Instant.now());
        // Freshness re-check seam (G4/G5): cancel/complete/decline/notComplete all re-probe the
        // CURRENT row via existsConfirmedById immediately before mutating, because assertTransition
        // only proves the status of the stale pre-load snapshot. An unstubbed mock returns false,
        // which short-circuits to a 409 "Service changed concurrently" BEFORE any eviction runs —
        // so without this stub these tests fail on the transition, never reaching the cache
        // assertion they exist for. The row is declared still-CONFIRMED: no concurrent writer is
        // being simulated here (that scenario belongs to BookingService's own concurrency tests).
        when(bookingRepository.existsConfirmedById(bookingId))
                .thenReturn(status == BookingStatus.CONFIRMED);
        return booking;
    }
}
