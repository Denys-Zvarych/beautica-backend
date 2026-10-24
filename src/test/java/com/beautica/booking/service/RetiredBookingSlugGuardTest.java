package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.repository.RetiredBookingSlugRepository;
import com.beautica.common.exception.GoneException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetiredBookingSlugGuard (phase 379)")
class RetiredBookingSlugGuardTest {

    private static final String SLUG = "olena-k-ab12";
    private static final String MASTER_HASH = RetiredSlugHasher.hash(BookingLinkKind.MASTER, SLUG);
    private static final int BURST_SIZE = 8;
    private static final long AWAIT_SECONDS = 5;

    @Mock private RetiredBookingSlugRepository retiredBookingSlugRepository;

    private RetiredBookingSlugGuard guard;

    @BeforeEach
    void setUp() {
        // A real Caffeine cache (the production shape), so the positive-only caching semantics are tested.
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(List.of());
        cacheManager.registerCustomCache(RetiredBookingSlugGuard.KNOWN_RETIRED_CACHE,
                Caffeine.newBuilder().maximumSize(100).expireAfterWrite(1, TimeUnit.HOURS).build());
        guard = new RetiredBookingSlugGuard(retiredBookingSlugRepository, cacheManager);
    }

    @Test
    @DisplayName("tombstoned in the probed kind → GoneException with code BOOKING_LINK_RETIRED")
    void should_throwGone_when_slugRetiredInKind() {
        when(retiredBookingSlugRepository.existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH))
                .thenReturn(true);

        assertThatThrownBy(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG))
                .isInstanceOf(GoneException.class)
                .hasMessage("Booking link is no longer valid")
                .extracting(ex -> ((GoneException) ex).getCode())
                .isEqualTo("BOOKING_LINK_RETIRED");
    }

    @Test
    @DisplayName("SALON probe uses the SALON hash — a MASTER tombstone of the same slug does not match")
    void should_probeKindSpecificHash_when_salonKind() {
        String salonHash = RetiredSlugHasher.hash(BookingLinkKind.SALON, SLUG);

        assertThatCode(() -> guard.throwGoneIfRetired(BookingLinkKind.SALON, SLUG)).doesNotThrowAnyException();

        verify(retiredBookingSlugRepository).existsByKindAndSlugHash(BookingLinkKind.SALON, salonHash);
        assertThat(salonHash).isNotEqualTo(MASTER_HASH);
    }

    @Test
    @DisplayName("absent → no throw (the caller then answers its unchanged 404)")
    void should_notThrow_when_slugNotRetired() {
        assertThatCode(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("audit F1 — a second identical retired lookup is served from the known-retired cache (no query)")
    void should_notQueryAgain_when_sameRetiredSlugProbedTwice() {
        when(retiredBookingSlugRepository.existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH))
                .thenReturn(true);
        assertThatThrownBy(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG))
                .isInstanceOf(GoneException.class);

        assertThatThrownBy(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG))
                .isInstanceOf(GoneException.class);

        verify(retiredBookingSlugRepository, times(1)).existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH);
    }

    @Test
    @DisplayName("audit F1 — a cached MASTER tombstone never answers the SALON namespace of the same slug")
    void should_notServeSalonFromMasterEntry_when_masterSlugCached() {
        when(retiredBookingSlugRepository.existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH))
                .thenReturn(true);
        assertThatThrownBy(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG))
                .isInstanceOf(GoneException.class);

        assertThatCode(() -> guard.throwGoneIfRetired(BookingLinkKind.SALON, SLUG)).doesNotThrowAnyException();

        verify(retiredBookingSlugRepository).existsByKindAndSlugHash(
                BookingLinkKind.SALON, RetiredSlugHasher.hash(BookingLinkKind.SALON, SLUG));
    }

    @Test
    @DisplayName("audit F1 — negatives are never cached: a 404 slug that is later retired answers 410")
    void should_queryEveryTime_and_pickUpLaterTombstone_when_slugNotRetired() {
        when(retiredBookingSlugRepository.existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH))
                .thenReturn(false, false, true);
        assertThatCode(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG)).doesNotThrowAnyException();
        assertThatCode(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG)).doesNotThrowAnyException();

        assertThatThrownBy(() -> guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG))
                .isInstanceOf(GoneException.class);

        verify(retiredBookingSlugRepository, times(3)).existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH);
    }

    @Test
    @DisplayName("audit cycle 2 — a concurrent burst on one dead slug: every request gets 410, and once the "
            + "tombstone is cached no further query runs")
    void should_answerGoneToAll_and_stopQuerying_when_concurrentBurstOnOneRetiredSlug() throws Exception {
        when(retiredBookingSlugRepository.existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH))
                .thenReturn(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(BURST_SIZE);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < BURST_SIZE; i++) {
                results.add(pool.submit(() -> {
                    start.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    return probeAndCapture();
                }));
            }

            start.countDown();

            for (Future<Throwable> result : results) {
                assertThat(result.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(GoneException.class);
            }
        } finally {
            pool.shutdownNow();
        }
        int burstQueries = repositoryProbeCount();
        assertThat(burstQueries).isBetween(1, BURST_SIZE);
        for (int i = 0; i < BURST_SIZE; i++) {
            assertThat(probeAndCapture()).isInstanceOf(GoneException.class);
        }
        assertThat(repositoryProbeCount()).isEqualTo(burstQueries);
    }

    @Test
    @DisplayName("audit cycle 2 (perf MEDIUM-1 / LOW-1) — the guard never loads through Caffeine: zero loads, "
            + "zero load failures, and a negative stores no entry")
    void should_neverLoadThroughCache_when_probingRetiredAndUnknownSlugs() {
        Cache<Object, Object> statsCache = Caffeine.newBuilder().maximumSize(100).recordStats().build();
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(List.of());
        cacheManager.registerCustomCache(RetiredBookingSlugGuard.KNOWN_RETIRED_CACHE, statsCache);
        RetiredBookingSlugGuard statsGuard = new RetiredBookingSlugGuard(retiredBookingSlugRepository, cacheManager);
        lenient().when(retiredBookingSlugRepository.existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH))
                .thenReturn(true);

        assertThatCode(() -> statsGuard.throwGoneIfRetired(BookingLinkKind.SALON, SLUG)).doesNotThrowAnyException();
        assertThatThrownBy(() -> statsGuard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG))
                .isInstanceOf(GoneException.class);

        assertThat(statsCache.stats().loadCount()).isZero();
        assertThat(statsCache.stats().loadFailureCount()).isZero();
        assertThat(statsCache.asMap()).containsOnlyKeys(BookingLinkKind.MASTER.name() + ':' + MASTER_HASH);
    }

    @Test
    @DisplayName("fail-fast — a known-retired cache that is not Caffeine is rejected at construction")
    void should_throwIllegalState_when_knownRetiredCacheIsNotCaffeine() {
        ConcurrentMapCacheManager nonCaffeine = new ConcurrentMapCacheManager(RetiredBookingSlugGuard.KNOWN_RETIRED_CACHE);

        assertThatThrownBy(() -> new RetiredBookingSlugGuard(retiredBookingSlugRepository, nonCaffeine))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RetiredBookingSlugGuard.KNOWN_RETIRED_CACHE);
    }

    @Test
    @DisplayName("Spring Cache API parity — a confirmed tombstone is stored in Caffeine as a raw Boolean.TRUE")
    void should_storeRawBooleanTrueInNativeCache_when_tombstoneConfirmed() {
        Cache<Object, Object> nativeCache = Caffeine.newBuilder().maximumSize(100).build();
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(List.of());
        cacheManager.registerCustomCache(RetiredBookingSlugGuard.KNOWN_RETIRED_CACHE, nativeCache);
        RetiredBookingSlugGuard nativeGuard = new RetiredBookingSlugGuard(retiredBookingSlugRepository, cacheManager);
        when(retiredBookingSlugRepository.existsByKindAndSlugHash(BookingLinkKind.MASTER, MASTER_HASH))
                .thenReturn(true);

        assertThatThrownBy(() -> nativeGuard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG))
                .isInstanceOf(GoneException.class);

        assertThat(nativeCache.getIfPresent(BookingLinkKind.MASTER.name() + ':' + MASTER_HASH))
                .isSameAs(Boolean.TRUE);
    }

    private int repositoryProbeCount() {
        return (int) mockingDetails(retiredBookingSlugRepository).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("existsByKindAndSlugHash"))
                .count();
    }

    private Throwable probeAndCapture() {
        try {
            guard.throwGoneIfRetired(BookingLinkKind.MASTER, SLUG);
            return null;
        } catch (GoneException ex) {
            return ex;
        }
    }
}
