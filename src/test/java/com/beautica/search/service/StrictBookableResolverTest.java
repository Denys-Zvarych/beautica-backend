package com.beautica.search.service;

import com.beautica.booking.service.BookingMasterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StrictBookableResolver — unit")
class StrictBookableResolverTest {

    // Generated per run: no assertion depends on a specific id value, only on identity.
    private static final UUID MASTER_A = UUID.randomUUID();
    private static final UUID MASTER_B = UUID.randomUUID();
    private static final UUID ASSIGNMENT_A = UUID.randomUUID();
    private static final UUID ASSIGNMENT_B = UUID.randomUUID();

    /** Join bound for the owner/joiner tests: short so a stall costs no real wait. */
    private static final Duration SHORT_JOIN = Duration.ofMillis(200);

    @Mock
    private BookingMasterService bookingMasterService;

    private ConcurrentMapCacheManager cacheManager;
    private StrictBookableResolver resolver;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE);
        resolver = new StrictBookableResolver(cacheManager, bookingMasterService, new NoOpTransactionManager());
    }

    @Test
    @DisplayName("fully cached candidates resolve without any compute")
    void should_skipBatch_when_everyCandidateIsCached() {
        cache().put(BookingMasterService.bookableAssignmentsCacheKey(MASTER_A), Set.of(ASSIGNMENT_A));

        StrictBookableResolver.StrictBookableSet result = resolver.resolve(List.of(MASTER_A), "test");

        assertThat(result.masterIds()).containsExactly(MASTER_A);
        assertThat(result.assignmentIds()).containsExactly(ASSIGNMENT_A);
        verifyNoInteractions(bookingMasterService);
    }

    @Test
    @DisplayName("only cache misses are computed, in one batch, and written back")
    @SuppressWarnings("unchecked")
    void should_batchOnlyMissesOnce_and_writeThemBack_when_cacheIsPartial() {
        cache().put(BookingMasterService.bookableAssignmentsCacheKey(MASTER_A), Set.of(ASSIGNMENT_A));
        when(bookingMasterService.computeBookableAssignmentIds(any()))
                .thenReturn(Map.of(MASTER_B, Set.of(ASSIGNMENT_B)));
        ArgumentCaptor<Collection<UUID>> misses = ArgumentCaptor.forClass(Collection.class);

        StrictBookableResolver.StrictBookableSet result = resolver.resolve(List.of(MASTER_A, MASTER_B), "test");

        verify(bookingMasterService).computeBookableAssignmentIds(misses.capture());
        assertThat(misses.getValue()).containsExactly(MASTER_B);
        assertThat(result.masterIds()).containsExactlyInAnyOrder(MASTER_A, MASTER_B);
        assertThat(result.assignmentIds()).containsExactlyInAnyOrder(ASSIGNMENT_A, ASSIGNMENT_B);
        assertThat(cache().get(BookingMasterService.bookableAssignmentsCacheKey(MASTER_B)).get())
                .isEqualTo(Set.of(ASSIGNMENT_B));
    }

    @Test
    @DisplayName("a master with no bookable assignment is excluded and its empty verdict cached")
    void should_excludeMasterAndCacheEmptyVerdict_when_noAssignmentIsBookable() {
        when(bookingMasterService.computeBookableAssignmentIds(any())).thenReturn(Map.of(MASTER_A, Set.of()));

        StrictBookableResolver.StrictBookableSet result = resolver.resolve(List.of(MASTER_A), "test");

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.assignmentIds()).isEmpty();
        assertThat(cache().get(BookingMasterService.bookableAssignmentsCacheKey(MASTER_A)).get())
                .isEqualTo(Set.of());
    }

    @Test
    @DisplayName("no candidates yields the empty set without a compute")
    void should_returnEmptyWithoutBatch_when_noCandidates() {
        StrictBookableResolver.StrictBookableSet result = resolver.resolve(List.of(), "test");

        assertThat(result).isSameAs(StrictBookableResolver.StrictBookableSet.EMPTY);
        verify(bookingMasterService, never()).computeBookableAssignmentIds(any());
    }

    @Test
    @DisplayName("above the candidate cap only the first CAP candidates are resolved")
    @SuppressWarnings("unchecked")
    void should_resolveOnlyTheFirstCapCandidates_when_candidateCapExceeded() {
        List<UUID> candidates = new ArrayList<>();
        for (int i = 0; i <= StrictBookableResolver.CANDIDATE_CAP; i++) {
            candidates.add(UUID.randomUUID());
        }
        when(bookingMasterService.computeBookableAssignmentIds(any())).thenReturn(Map.of());
        ArgumentCaptor<Collection<UUID>> resolved = ArgumentCaptor.forClass(Collection.class);

        resolver.resolve(candidates, "test");

        verify(bookingMasterService).computeBookableAssignmentIds(resolved.capture());
        assertThat(resolved.getValue())
                .hasSize(StrictBookableResolver.CANDIDATE_CAP)
                .doesNotContain(candidates.get(StrictBookableResolver.CANDIDATE_CAP));
    }

    @Test
    @DisplayName("overlapping concurrent cold resolves compute each master exactly once")
    @SuppressWarnings("unchecked")
    void should_computeEachMasterOnce_when_parallelColdResolvesOverlap() throws Exception {
        int threads = 8;
        List<UUID> masters = List.of(MASTER_A, MASTER_B, UUID.randomUUID(), UUID.randomUUID());
        // Every thread's initial miss lookups (threads × masters) happen before any compute returns,
        // so without single-flight every thread would compute every master.
        CountDownLatch allMissed = new CountDownLatch(threads * masters.size());
        cacheManager = countingCacheManager(allMissed);
        resolver = new StrictBookableResolver(cacheManager, bookingMasterService, new NoOpTransactionManager());
        List<Collection<UUID>> computed = new CopyOnWriteArrayList<>();
        when(bookingMasterService.computeBookableAssignmentIds(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = List.copyOf((Collection<UUID>) invocation.getArgument(0));
            computed.add(ids);
            assertThat(allMissed.await(5, TimeUnit.SECONDS)).as("every thread looked its masters up").isTrue();
            Map<UUID, Set<UUID>> verdicts = new HashMap<>();
            ids.forEach(id -> verdicts.put(id, Set.of(id)));
            return verdicts;
        });
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<StrictBookableResolver.StrictBookableSet>> results = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                // A different candidate order per thread — the shape of different search keys.
                List<UUID> order = new ArrayList<>(masters);
                Collections.rotate(order, i);
                results.add(pool.submit(() -> resolver.resolve(order, "test")));
            }
            for (Future<StrictBookableResolver.StrictBookableSet> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).masterIds()).containsExactlyInAnyOrderElementsOf(masters);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(computed.stream().mapToInt(Collection::size).sum())
                .as("each master computed exactly once across all threads (calls: %s)", computed)
                .isEqualTo(masters.size());
        assertThat(computed).as("about once: at most one batch per master").hasSizeLessThanOrEqualTo(masters.size());
    }

    @Test
    @DisplayName("a fresher cache entry written mid-compute is never overwritten")
    void should_keepFreshEntry_when_keyEvictedAndReloadedDuringCompute() {
        Object key = BookingMasterService.bookableAssignmentsCacheKey(MASTER_A);
        Set<UUID> fresh = Set.of(ASSIGNMENT_B);
        when(bookingMasterService.computeBookableAssignmentIds(any())).thenAnswer(invocation -> {
            // A booking commits mid-compute: its afterCommit sweep evicts the key and the profile
            // read (@Cacheable) reloads the post-booking verdict before this compute returns.
            cache().evict(key);
            cache().put(key, fresh);
            return Map.of(MASTER_A, Set.of(ASSIGNMENT_A));
        });

        StrictBookableResolver.StrictBookableSet result = resolver.resolve(List.of(MASTER_A), "test");

        assertThat(cache().get(key).get()).as("the stale pre-booking verdict never overwrites").isEqualTo(fresh);
        assertThat(result.assignmentIds()).as("the resolve answers with the cached entry").containsExactly(ASSIGNMENT_B);
    }

    @Test
    @DisplayName("a failed compute propagates and releases its claims so a retry computes")
    void should_releaseClaimsAndPropagate_when_computeFails() {
        when(bookingMasterService.computeBookableAssignmentIds(any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(Map.of(MASTER_A, Set.of(ASSIGNMENT_A)));

        assertThatThrownBy(() -> resolver.resolve(List.of(MASTER_A), "test")).hasMessage("db down");
        StrictBookableResolver.StrictBookableSet retry = resolver.resolve(List.of(MASTER_A), "test");

        assertThat(retry.assignmentIds()).as("the failed claim was released, so a retry computes").containsExactly(ASSIGNMENT_A);
    }

    @Test
    @DisplayName("a joiner whose owner stalls past the bound treats the master as not bookable without recomputing")
    void should_notRecomputeAndTreatAsNotBookable_when_ownerStallsPastJoinTimeout() throws Exception {
        resolver = new StrictBookableResolver(
                cacheManager, bookingMasterService, new NoOpTransactionManager(), SHORT_JOIN);
        CountDownLatch ownerComputing = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        when(bookingMasterService.computeBookableAssignmentIds(any())).thenAnswer(invocation -> {
            ownerComputing.countDown();
            // A slow DB: the owner's compute outlives the joiner's bound.
            assertThat(releaseOwner.await(10, TimeUnit.SECONDS)).isTrue();
            return Map.of(MASTER_A, Set.of(ASSIGNMENT_A));
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<StrictBookableResolver.StrictBookableSet> owner =
                    pool.submit(() -> resolver.resolve(List.of(MASTER_A), "owner"));
            assertThat(ownerComputing.await(5, TimeUnit.SECONDS)).as("the owner claimed MASTER_A").isTrue();

            // Bounded wait: a joiner without its deadline blocks for as long as the owner stalls, so this
            // get() fails red (TimeoutException) instead of hanging the build.
            StrictBookableResolver.StrictBookableSet joiner =
                    pool.submit(() -> resolver.resolve(List.of(MASTER_A), "joiner")).get(5, TimeUnit.SECONDS);

            assertThat(joiner.isEmpty()).as("a stalled master is treated as not bookable for this response").isTrue();
            verify(bookingMasterService, times(1)).computeBookableAssignmentIds(any());
            assertThat(cache().get(BookingMasterService.bookableAssignmentsCacheKey(MASTER_A)))
                    .as("the joiner caches nothing — the owner's verdict is the one that lands").isNull();
            releaseOwner.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS).masterIds()).containsExactly(MASTER_A);
        } finally {
            releaseOwner.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a joiner whose owner fails treats the master as not bookable without computing or caching")
    void should_notComputeAndTreatAsNotBookable_when_ownerFails() throws Exception {
        resolver = new StrictBookableResolver(
                cacheManager, bookingMasterService, new NoOpTransactionManager(), Duration.ofSeconds(5));
        CountDownLatch ownerComputing = new CountDownLatch(1);
        CountDownLatch failOwner = new CountDownLatch(1);
        List<String> computingThreads = new CopyOnWriteArrayList<>();
        when(bookingMasterService.computeBookableAssignmentIds(any())).thenAnswer(invocation -> {
            computingThreads.add(Thread.currentThread().getName());
            ownerComputing.countDown();
            assertThat(failOwner.await(10, TimeUnit.SECONDS)).isTrue();
            // The owner's statement is cancelled by statement_timeout.
            throw new QueryTimeoutException("statement cancelled");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<StrictBookableResolver.StrictBookableSet> owner =
                    pool.submit(() -> resolver.resolve(List.of(MASTER_A), "owner"));
            assertThat(ownerComputing.await(5, TimeUnit.SECONDS)).as("the owner claimed MASTER_A").isTrue();
            Future<StrictBookableResolver.StrictBookableSet> joinerFuture =
                    pool.submit(() -> resolver.resolve(List.of(MASTER_A), "joiner"));
            awaitWaiting(joinerFuture, pool);
            failOwner.countDown();

            StrictBookableResolver.StrictBookableSet joiner = joinerFuture.get(5, TimeUnit.SECONDS);

            assertThat(joiner.isEmpty()).as("the failed owner's master is not bookable for this response").isTrue();
            verify(bookingMasterService, times(1)).computeBookableAssignmentIds(any());
            assertThat(computingThreads).as("only the owner computed").hasSize(1);
            assertThat(cache().get(BookingMasterService.bookableAssignmentsCacheKey(MASTER_A)))
                    .as("nothing is cached for a failed owner").isNull();
            assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(QueryTimeoutException.class);
        } finally {
            failOwner.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a joiner waits outside any transaction; only the candidate query runs in one")
    void should_holdNoTransaction_when_joinerWaits() throws Exception {
        List<Boolean> txActiveAtJoinerLookup = new CopyOnWriteArrayList<>();
        List<Boolean> txActiveAtCandidateQuery = new CopyOnWriteArrayList<>();
        Thread testThread = Thread.currentThread();
        cacheManager = txProbingCacheManager(testThread, txActiveAtJoinerLookup);
        NoOpTransactionManager txManager = new NoOpTransactionManager();
        resolver = new StrictBookableResolver(cacheManager, bookingMasterService, txManager, SHORT_JOIN);
        ReflectionTestUtils.setField(resolver, "entityManager", candidateQueryReturning(MASTER_A, txActiveAtCandidateQuery));
        CountDownLatch ownerComputing = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        when(bookingMasterService.computeBookableAssignmentIds(any())).thenAnswer(invocation -> {
            ownerComputing.countDown();
            assertThat(releaseOwner.await(10, TimeUnit.SECONDS)).isTrue();
            return Map.of(MASTER_A, Set.of(ASSIGNMENT_A));
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            pool.submit(() -> resolver.resolve(List.of(MASTER_A), "owner"));
            assertThat(ownerComputing.await(5, TimeUnit.SECONDS)).as("the owner claimed MASTER_A").isTrue();

            StrictBookableResolver.StrictBookableSet joiner =
                    resolver.resolveCandidateQuery("SELECT 1", Map.of(), "joiner");

            assertThat(joiner.isEmpty()).as("the joiner waited out the stalled owner").isTrue();
            assertThat(txActiveAtCandidateQuery).as("the candidate query runs in a transaction").containsExactly(true);
            assertThat(txManager.begun).as("exactly one (candidate) transaction begun").isEqualTo(1);
            assertThat(txActiveAtJoinerLookup).as("the joiner's pre-wait lookup ran outside any transaction")
                    .isNotEmpty().containsOnly(false);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        } finally {
            releaseOwner.countDown();
            pool.shutdownNow();
        }
    }

    /** Blocks until the joiner thread is parked in its bounded wait on the owner's future. */
    private static void awaitWaiting(Future<?> joiner, ExecutorService pool) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !joiner.isDone()) {
            boolean parked = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("pool-") && t.getState() == Thread.State.TIMED_WAITING)
                    .anyMatch(t -> java.util.Arrays.stream(t.getStackTrace())
                            .anyMatch(f -> f.getMethodName().equals("awaitJoined")));
            if (parked) {
                return;
            }
            Thread.onSpinWait();
        }
        assertThat(joiner.isDone()).as("the joiner reached its wait").isFalse();
    }

    private static EntityManager candidateQueryReturning(UUID masterId, List<Boolean> txActive) {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenAnswer(invocation -> {
            txActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(masterId);
        });
        return entityManager;
    }

    /** Records whether a transaction is active at each cache lookup made on {@code probed}. */
    private static ConcurrentMapCacheManager txProbingCacheManager(Thread probed, List<Boolean> txActive) {
        return new ConcurrentMapCacheManager(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE) {
            @Override
            protected Cache createConcurrentMapCache(String name) {
                return new ConcurrentMapCache(name, new ConcurrentHashMap<>(), true) {
                    @Override
                    public ValueWrapper get(Object key) {
                        if (Thread.currentThread() == probed) {
                            txActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                        }
                        return super.get(key);
                    }
                };
            }
        };
    }

    /**
     * Resource-less transaction manager: drives the real {@link TransactionSynchronizationManager}
     * bookkeeping (so {@code isActualTransactionActive()} is truthful) and counts begun transactions.
     */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        private int begun;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // resource-less
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // resource-less
        }
    }

    private static ConcurrentMapCacheManager countingCacheManager(CountDownLatch lookups) {
        return new ConcurrentMapCacheManager(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE) {
            @Override
            protected Cache createConcurrentMapCache(String name) {
                return new ConcurrentMapCache(name, new ConcurrentHashMap<>(), true) {
                    @Override
                    public ValueWrapper get(Object key) {
                        lookups.countDown();
                        return super.get(key);
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("the bulk cache key equals the @Cacheable SpEL list key")
    void should_matchSpelListKey_when_cacheKeyBuilt() {
        // {#masterId} evaluates to a one-element java.util.List; List equality is by content.
        List<UUID> spelShapedKey = java.util.Collections.unmodifiableList(new ArrayList<>(List.of(MASTER_A)));

        Object key = BookingMasterService.bookableAssignmentsCacheKey(MASTER_A);

        assertThat(key).isEqualTo(spelShapedKey);
        assertThat(key.hashCode()).isEqualTo(spelShapedKey.hashCode());
    }

    private Cache cache() {
        return cacheManager.getCache(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE);
    }
}
