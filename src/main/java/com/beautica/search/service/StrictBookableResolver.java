package com.beautica.search.service;

import com.beautica.booking.service.BookingMasterService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves which masters a client can ACTUALLY book (SRCH-30, SRCH-31, SAL-09): the strict
 * free-slot verdict of the client service list (MAS-07), applied to a candidate set the caller
 * already narrowed with the cheap structural predicate ({@code MasterBookabilitySql}).
 *
 * <p><b>Reuse, no parallel slot logic.</b> Per master the verdict is the
 * {@value BookingMasterService#BOOKABLE_ASSIGNMENTS_CACHE} entry — the same key
 * {@link BookingMasterService#getBookableAssignmentIds} caches and every booking / schedule /
 * assignment write already evicts afterCommit by master. Hits cost nothing; ALL misses go to ONE
 * {@link BookingMasterService#computeBookableAssignmentIds} call (one candidate load + one
 * {@code SlotCalculationService#filterBookableDurationsBatch}), and each result is written back
 * under that key ({@code putIfAbsent}), so search and the «Послуги» tab share one verdict and one
 * cache. Concurrent resolves share in-flight computations per master (single-flight, see
 * {@code inFlight}); a join is bounded by {@link #JOIN_TIMEOUT} and never recomputes a master whose
 * owner stalled or failed ({@code awaitJoined}, SRCH-E08).
 *
 * <p><b>Transactions.</b> The resolver opens no transaction around its own work: the candidate query
 * runs in a short read-only transaction ({@code candidateTx}) and each owner compute in
 * {@link BookingMasterService#computeBookableAssignmentIds}'s own {@code @Transactional(readOnly)}
 * boundary, so a joiner's wait holds no transaction this class began. A caller that is itself
 * {@code @Transactional} (the search and roster reads are) still holds ITS connection while a joiner
 * waits — which is why the join bound is far below the 5 s {@code statement_timeout} (see
 * {@link #JOIN_TIMEOUT}).
 *
 * <p><b>Residual race (bounded by the cache TTL, same as the {@code @Cacheable} profile read).</b>
 * A booking that commits while a verdict is being computed evicts nothing (the key is still absent),
 * so the pre-commit verdict may be written afterwards and live until its TTL; {@code putIfAbsent}
 * only guarantees that a fresher entry already written is never overwritten. The booking path
 * re-checks the slot, so the worst case is a "slot taken" answer (D4).
 *
 * <p><b>Candidate cap.</b> A search with no location (national browse) could hand thousands of
 * candidates to the free-slot walk on a {@code permitAll} endpoint. Callers fetch at most
 * {@link #CANDIDATE_CAP} {@code + 1} ids; above the cap only the first {@value #CANDIDATE_CAP}
 * (caller order) are resolved and a WARN is logged — the rest are treated as not bookable
 * (strict-on-cap: never shows a master that was not verified).
 */
@Slf4j
@Service
public class StrictBookableResolver {

    /** Maximum masters resolved per call; callers query {@code CANDIDATE_CAP + 1} to detect overflow. */
    public static final int CANDIDATE_CAP = 2000;

    /**
     * Field-injected via {@link PersistenceContext} — Spring's transaction-aware shared proxy, the
     * documented exception to constructor injection (same as {@code SearchService}).
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Upper bound on the TOTAL time one resolve waits for masters another resolve is computing
     * (B523 audit cycles 2–3). 1 s, deliberately well below the pool's 5 s {@code statement_timeout}:
     * <ul>
     *   <li>a full cold resolve at {@link #CANDIDATE_CAP} candidates is budgeted at 500 ms
     *       ({@code LocalityDiscoveryPerfHardeningTest}), so 1 s is twice the worst healthy owner —
     *       an owner still running past it is on a slow DB;</li>
     *   <li>the search / roster callers are {@code @Transactional(readOnly)}, so a waiting joiner keeps
     *       its caller's pool connection for the whole wait. At 1 s, ten concurrent joiners pin the
     *       10-connection pool for at most 1 s (vs 5 s), far inside the 10 s Hikari
     *       {@code connection-timeout} of any request queued behind them.</li>
     * </ul>
     * Past the bound the master is not bookable for that one response (SRCH-E08).
     */
    static final Duration JOIN_TIMEOUT = Duration.ofSeconds(1);

    private final CacheManager cacheManager;
    private final BookingMasterService bookingMasterService;
    private final Duration joinTimeout;

    /** Short read-only transaction for the candidate query only — never spans a join wait. */
    private final TransactionTemplate candidateTx;

    /**
     * Single-flight registry (B523 fix pass): master → the in-progress verdict of the resolve that
     * claimed it. Concurrent cold searches over overlapping candidates (a TTL expiry under load,
     * many filter combinations of one city) share ONE {@code computeBookableAssignmentIds} per
     * master instead of each running the 180-day walk for the same masters. Entries live only for
     * the duration of one compute.
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<Set<UUID>>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public StrictBookableResolver(CacheManager cacheManager, BookingMasterService bookingMasterService,
                                  PlatformTransactionManager transactionManager) {
        this(cacheManager, bookingMasterService, transactionManager, JOIN_TIMEOUT);
    }

    /** Test seam: a short join bound so a stalled owner can be simulated without a full-bound wait. */
    StrictBookableResolver(CacheManager cacheManager, BookingMasterService bookingMasterService,
                           PlatformTransactionManager transactionManager, Duration joinTimeout) {
        this.cacheManager = cacheManager;
        this.bookingMasterService = bookingMasterService;
        this.joinTimeout = joinTimeout;
        this.candidateTx = new TransactionTemplate(transactionManager);
        this.candidateTx.setReadOnly(true);
    }

    /**
     * The strict-bookable subset of {@code candidateMasterIds}: masters with at least one bookable
     * assignment, plus the union of those assignment ids.
     *
     * @param candidateMasterIds structurally bookable candidates, in the caller's priority order
     * @param surface            short label for the cap WARN (never user data)
     */
    public StrictBookableSet resolve(List<UUID> candidateMasterIds, String surface) {
        List<UUID> candidates = capped(candidateMasterIds, surface);
        if (candidates.isEmpty()) {
            return StrictBookableSet.EMPTY;
        }
        Map<UUID, Set<UUID>> byMaster = assignmentsByMaster(candidates);
        Set<UUID> masterIds = new HashSet<>();
        Set<UUID> assignmentIds = new HashSet<>();
        byMaster.forEach((masterId, ids) -> {
            if (!ids.isEmpty()) {
                masterIds.add(masterId);
                assignmentIds.addAll(ids);
            }
        });
        return new StrictBookableSet(masterIds, assignmentIds);
    }

    /**
     * Runs a caller-built ids-only candidate query ({@code SELECT m.id … LIMIT CANDIDATE_CAP + 1}),
     * then {@link #resolve}. The SQL is assembled by {@code SearchService} from its own allow-listed
     * fragments — never from caller text — and every value travels as a bound parameter. The query
     * runs in its own short read-only transaction (joining the caller's, if any); the resolve — and
     * any join wait — runs after it ends.
     */
    public StrictBookableSet resolveCandidateQuery(String candidateSql, Map<String, Object> params, String surface) {
        List<UUID> ids = candidateTx.execute(status -> candidateIds(candidateSql, params));
        return resolve(ids == null ? List.of() : ids, surface);
    }

    private List<UUID> candidateIds(String candidateSql, Map<String, Object> params) {
        Query query = entityManager.createNativeQuery(candidateSql);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        List<UUID> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add((UUID) row);
        }
        return ids;
    }

    private static List<UUID> capped(List<UUID> candidates, String surface) {
        if (candidates.size() <= CANDIDATE_CAP) {
            return candidates;
        }
        log.warn("Strict bookability candidate cap hit on {}: {}+ candidates, resolving the first {}",
                surface, candidates.size(), CANDIDATE_CAP);
        return candidates.subList(0, CANDIDATE_CAP);
    }

    private Map<UUID, Set<UUID>> assignmentsByMaster(List<UUID> masterIds) {
        Cache cache = cacheManager.getCache(BookingMasterService.BOOKABLE_ASSIGNMENTS_CACHE);
        Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
        Map<UUID, CompletableFuture<Set<UUID>>> owned = new LinkedHashMap<>();
        Map<UUID, CompletableFuture<Set<UUID>>> joined = new LinkedHashMap<>();
        for (UUID masterId : masterIds) {
            Set<UUID> hit = cached(cache, masterId);
            if (hit != null) {
                result.put(masterId, hit);
                continue;
            }
            CompletableFuture<Set<UUID>> mine = new CompletableFuture<>();
            CompletableFuture<Set<UUID>> running = inFlight.putIfAbsent(masterId, mine);
            if (running == null) {
                owned.put(masterId, mine);
            } else {
                joined.put(masterId, running);
            }
        }
        computeOwned(owned, cache, result);
        awaitJoined(joined, result);
        return result;
    }

    /**
     * Computes every miss this call claimed in ONE batch, completes each claim, writes each verdict
     * with {@code putIfAbsent} (a fresher entry written meanwhile — e.g. by the {@code @Cacheable}
     * profile read after an eviction — is kept, never overwritten by this older snapshot) and
     * releases the claims. Claims are completed BEFORE this call waits on anyone else's, so two
     * overlapping resolves can never wait on each other.
     */
    private void computeOwned(Map<UUID, CompletableFuture<Set<UUID>>> owned, Cache cache,
                              Map<UUID, Set<UUID>> result) {
        if (owned.isEmpty()) {
            return;
        }
        try {
            List<UUID> misses = new ArrayList<>(owned.size());
            owned.forEach((masterId, claim) -> {
                // Closes the miss → claim window: a racing owner may have written between the two.
                Set<UUID> late = cached(cache, masterId);
                if (late == null) {
                    misses.add(masterId);
                } else {
                    claim.complete(late);
                    result.put(masterId, late);
                }
            });
            Map<UUID, Set<UUID>> computed = misses.isEmpty()
                    ? Map.of()
                    : bookingMasterService.computeBookableAssignmentIds(misses);
            for (UUID masterId : misses) {
                Set<UUID> stored = store(cache, masterId, computed.getOrDefault(masterId, Set.of()));
                result.put(masterId, stored);
                owned.get(masterId).complete(stored);
            }
        } catch (RuntimeException ex) {
            owned.values().forEach(claim -> claim.completeExceptionally(ex));
            throw ex;
        } finally {
            owned.forEach(inFlight::remove);
        }
    }

    /**
     * Waits for the masters another resolve is already computing, all of them within ONE
     * {@link #JOIN_TIMEOUT} deadline (never one bound per master). Holds no transaction of its own.
     *
     * <p>A master whose owner <b>failed</b> (e.g. its statement was cancelled by the 5 s
     * {@code statement_timeout}) or is <b>still running past the deadline</b> is resolved by this
     * caller as NOT bookable for this response, with no compute and no cache write (SRCH-E08).
     * Recomputing instead would start another 180-day walk for the same master on the same slow DB
     * from every waiting request, unbounded and outside single-flight — the recompute storm
     * single-flight exists to prevent. Strict-on-doubt is the same trade as {@link #CANDIDATE_CAP}:
     * never show a master that was not verified; the next search recomputes (a failed owner caches
     * nothing) or hits the owner's late verdict.
     */
    private void awaitJoined(Map<UUID, CompletableFuture<Set<UUID>>> joined, Map<UUID, Set<UUID>> result) {
        int failed = 0;
        int timedOut = 0;
        long deadline = System.nanoTime() + joinTimeout.toNanos();
        for (Map.Entry<UUID, CompletableFuture<Set<UUID>>> entry : joined.entrySet()) {
            JoinOutcome outcome = join(entry.getValue(), deadline);
            result.put(entry.getKey(), outcome.verdict());
            switch (outcome.kind()) {
                case DONE -> { }
                case FAILED -> failed++;
                case TIMED_OUT -> timedOut++;
            }
        }
        if (failed + timedOut > 0) {
            log.warn("Strict bookability join unresolved (bound {} ms): {} owner(s) failed, {} timed out; "
                    + "treated as not bookable for this response", joinTimeout.toMillis(), failed, timedOut);
        }
    }

    private static JoinOutcome join(CompletableFuture<Set<UUID>> running, long deadlineNanos) {
        long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
        try {
            return JoinOutcome.done(running.get(remaining, TimeUnit.NANOSECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return JoinOutcome.TIMED_OUT;
        } catch (TimeoutException ex) {
            return JoinOutcome.TIMED_OUT;
        } catch (ExecutionException ex) {
            return JoinOutcome.FAILED;
        }
    }

    private enum JoinKind { DONE, FAILED, TIMED_OUT }

    private record JoinOutcome(JoinKind kind, Set<UUID> verdict) {
        static final JoinOutcome FAILED = new JoinOutcome(JoinKind.FAILED, Set.of());
        static final JoinOutcome TIMED_OUT = new JoinOutcome(JoinKind.TIMED_OUT, Set.of());

        static JoinOutcome done(Set<UUID> verdict) {
            return new JoinOutcome(JoinKind.DONE, verdict);
        }
    }

    /** {@code putIfAbsent}; returns the entry that is actually cached (an existing one wins). */
    private static Set<UUID> store(Cache cache, UUID masterId, Set<UUID> computed) {
        if (cache == null) {
            return computed;
        }
        Cache.ValueWrapper existing =
                cache.putIfAbsent(BookingMasterService.bookableAssignmentsCacheKey(masterId), computed);
        return existing != null && existing.get() instanceof Set<?> ids ? asUuidSet(ids) : computed;
    }

    private static Set<UUID> cached(Cache cache, UUID masterId) {
        if (cache == null) {
            return null;
        }
        Cache.ValueWrapper wrapper = cache.get(BookingMasterService.bookableAssignmentsCacheKey(masterId));
        return wrapper != null && wrapper.get() instanceof Set<?> ids ? asUuidSet(ids) : null;
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> asUuidSet(Set<?> ids) {
        return (Set<UUID>) ids;
    }

    /**
     * Strict-bookable masters and the union of their bookable assignment ids. Search binds both as
     * SQL arrays ({@link #masterIdArray()}, {@link #assignmentIdArray()}).
     */
    public record StrictBookableSet(Set<UUID> masterIds, Set<UUID> assignmentIds) {

        public static final StrictBookableSet EMPTY = new StrictBookableSet(Set.of(), Set.of());

        public StrictBookableSet {
            masterIds = Set.copyOf(masterIds);
            assignmentIds = Set.copyOf(assignmentIds);
        }

        public boolean isEmpty() {
            return masterIds.isEmpty();
        }

        public UUID[] masterIdArray() {
            return masterIds.toArray(UUID[]::new);
        }

        public UUID[] assignmentIdArray() {
            return assignmentIds.toArray(UUID[]::new);
        }
    }
}
