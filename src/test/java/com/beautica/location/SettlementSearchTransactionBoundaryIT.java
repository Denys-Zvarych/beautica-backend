package com.beautica.location;

import com.beautica.AbstractIntegrationTest;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.service.SettlementSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.cache.CacheManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ONE property of {@code SettlementSearchService} that nothing else can observe: that
 * {@code search(term)} reaches {@code runIndexedSearch(term)} through the {@code @Lazy} self-proxy,
 * so the method's {@code @Transactional(readOnly = true)} actually applies.
 *
 * <p><b>Why a whole class for one delegation.</b> Changing {@code self.runIndexedSearch(term)} to
 * {@code this.runIndexedSearch(term)} is a four-character edit that compiles, passes static
 * analysis, and — before this class existed — left all 1 414 tests green. Spring AOP is
 * proxy-based, so a self-invocation never crosses the proxy (§F-3) and the annotation is simply not
 * applied: the query then runs with NO transaction of its own, under whatever ambient context the
 * caller happens to have, which on this path is none. Nothing throws. The rows come back. The only
 * difference is that a read the service declared read-only and transactional silently is neither.
 *
 * <p><b>Why the sibling assertion in {@code SettlementSearchIT} does not cover it.</b> That class
 * pins the OTHER delegation out of the same method — {@code self.listMajorSettlements()} — through
 * its {@code @Cacheable}, by asserting {@code settlementMajors} holds one entry after a blank
 * query. A cache is an observable side effect; a transaction boundary is not, and the two
 * delegations can regress independently: only the blank-query branch is cached, and only the
 * typed-query branch is transactional.
 *
 * <p><b>How it is observed.</b> {@code TransactionSynchronizationManager} is thread-local state, so
 * the probe has to read it from INSIDE the call, at the moment the repository is invoked — an
 * assertion made before or after the call would see the caller's context, not the service's.
 * {@link TransactionProbeConfig} therefore supplies a {@code @Primary} {@link CityRepository} that
 * records the flags and then delegates to the real repository, so the real GIN-indexed query still
 * runs against the real V170/V171 taxonomy. It is a plain delegating proxy rather than a
 * {@code @MockitoSpyBean} for two reasons: Mockito cannot {@code callRealMethod()} on an interface
 * bean (the spy of a Spring Data proxy has no non-abstract method to call, and the first cut of
 * this class failed exactly that way), and the repo already prefers a delegating double for this
 * shape — see {@code SalonCatalogueBatchLoadIT}'s {@code FoldCountingScheduleMapper}. The probe
 * sits OUTSIDE Spring Data's own transactional proxy ({@code SimpleJpaRepository} is itself
 * {@code @Transactional(readOnly = true)}), which is what makes the reading discriminate: at the
 * instant it is taken, the only transaction that can be open is the service's.
 *
 * <p><b>Falsification (2026-09-23).</b> With {@code self.}: green. With {@code this.}: RED on the
 * {@code isActualTransactionActive()} assertion — the recorded value flips to {@code false},
 * because the service opened nothing and Spring Data's own boundary has not been entered yet.
 *
 * <p><b>Why this is its own class rather than a {@code @Nested} block in
 * {@code SettlementSearchIT}.</b> A bean override gives its declaring class a distinct
 * {@code ApplicationContext} cache key either way (§M, and see the same reasoning recorded in
 * {@code SalonCatalogueBatchLoadIT}). Declaring it here costs one small context and leaves
 * {@code SettlementSearchIT}'s twenty-odd cases on the shared one; declaring it there would have
 * moved all of them off it for the sake of a single assertion.
 */
@DisplayName("Phase 326 settlement autocomplete — the self-proxy transaction boundary")
@Import(SettlementSearchTransactionBoundaryIT.TransactionProbeConfig.class)
class SettlementSearchTransactionBoundaryIT extends AbstractIntegrationTest {

    /** A term with a real 3-character alphanumeric run, so the admission guard lets it through. */
    private static final String ADMITTED_TERM = "льв";

    /** The one repository method the service's typed-query branch calls. */
    private static final String PROBED_METHOD = "searchByName";

    @Autowired
    private SettlementSearchService settlementSearchService;

    @Autowired
    private TransactionProbe transactionProbe;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetProbe() {
        transactionProbe.observed().set(null);
        // Phase 329: a cached «льв» would never reach the repository, leaving the probe null.
        cacheManager.getCache(SettlementSearchService.CACHE_SETTLEMENT_SEARCH).clear();
    }

    /** What the thread-local transaction context looked like when the repository was entered. */
    record TxContext(boolean active, boolean readOnly, String name) {

        static TxContext observed() {
            return new TxContext(
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    TransactionSynchronizationManager.getCurrentTransactionName());
        }
    }

    /** Holder for what the delegating repository saw, readable from the test thread. */
    record TransactionProbe(AtomicReference<TxContext> observed) {
    }

    /**
     * Replaces the {@link CityRepository} bean with a delegating proxy that records the ambient
     * transaction context on the way into {@link #PROBED_METHOD} and then calls the real one.
     *
     * <p>The real repository is injected by BEAN NAME ({@code cityRepository}, Spring Data's
     * generated name) so the {@code @Primary} proxy defined here does not resolve to itself.
     */
    @TestConfiguration
    static class TransactionProbeConfig {

        @Bean
        TransactionProbe transactionProbe() {
            return new TransactionProbe(new AtomicReference<>());
        }

        @Bean
        @Primary
        CityRepository transactionProbingCityRepository(
                @Qualifier("cityRepository") CityRepository delegate, TransactionProbe probe) {
            return (CityRepository) Proxy.newProxyInstance(
                    CityRepository.class.getClassLoader(),
                    new Class<?>[] {CityRepository.class},
                    (proxy, method, args) -> {
                        if (PROBED_METHOD.equals(method.getName())) {
                            probe.observed().set(TxContext.observed());
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @Test
    @DisplayName("the ranked query runs inside runIndexedSearch's own read-only transaction")
    void should_runInsideAReadOnlyTransaction_when_theRankedQueryIsIssued() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("the probe below is only meaningful if the TEST thread carries no transaction "
                        + "of its own — otherwise every assertion here would pass on the caller's "
                        + "context no matter what the service does")
                .isFalse();
        AtomicReference<TxContext> observed = transactionProbe.observed();

        settlementSearchService.search(ADMITTED_TERM);

        assertThat(observed.get())
                .as("searchByName was never reached — the term must be one the admission guard "
                        + "ADMITS, or this test proves nothing about the transaction")
                .isNotNull();
        assertThat(observed.get().active())
                .as("a transaction must be open when the query is issued. FALSE here is exactly "
                        + "what `this.runIndexedSearch(term)` produces: the self-invocation misses "
                        + "the proxy, @Transactional never applies, and the read runs bare")
                .isTrue();
        assertThat(observed.get().readOnly())
                .as("and it must be the READ-ONLY one the method declares — dropping "
                        + "readOnly = true costs Hibernate's flush-mode and dirty-check skip on a "
                        + "permitAll read path, and nothing else would report it")
                .isTrue();
        assertThat(observed.get().name())
                .as("the transaction must be runIndexedSearch's OWN, not one inherited from a "
                        + "caller. This is also what keeps search() un-transactional: making the "
                        + "admission stage @Transactional, so that a this. call would inherit a "
                        + "boundary, renames the transaction and fails here — which is the "
                        + "decision the service's javadoc records (no transaction on the path an "
                        + "attacker drives at 240/min, where both rejections touch no database)")
                .isEqualTo(SettlementSearchService.class.getName() + ".runIndexedSearch");
    }
}
