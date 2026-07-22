package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the caching advisor OUTSIDE the transaction advisor.
 *
 * <p><b>What this protects.</b> Roughly 38 methods pair {@code @Cacheable(..., sync = true)} with
 * {@code @Transactional} on the same method. Caffeine's {@code sync = true} loader funnels through
 * {@code ConcurrentHashMap.compute}, so on a popular key one thread loads while every other caller
 * BLOCKS on that bin lock for a full DB round-trip to Neon. What those blocked callers are HOLDING
 * while they wait is decided entirely by advisor order:
 * <ul>
 *   <li>Transaction advisor outer → each waiter entered a transaction before reaching the cache
 *       advisor, so it is sitting on a Hikari connection from a pool of ten
 *       ({@code application.yml}: {@code maximum-pool-size: 10}, a Neon-tier ceiling). One slow
 *       query on one hot key can then exhaust the pool for the whole application.</li>
 *   <li>Cache advisor outer → a waiter blocks BEFORE any transaction is opened and holds no
 *       connection at all; a cache HIT likewise returns without ever starting one. The MISS path is
 *       unchanged: the cache advisor proceeds into the target, still wrapped by the transaction
 *       advisor exactly as before.</li>
 * </ul>
 *
 * <p><b>Why an explicit test rather than trusting the default.</b> Before {@code CacheConfig} set
 * {@code order}, nothing in this application pinned the nesting: {@code @EnableCaching} carried no
 * {@code order}, there is no {@code @EnableTransactionManagement} anywhere (transactions come from
 * Boot's auto-config), and no advisor bean called {@code setOrder}. Both advisors therefore
 * defaulted to {@link Ordered#LOWEST_PRECEDENCE} and their relative nesting fell out of
 * bean-registration tie-breaking — undefined by configuration rather than verified-correct, and
 * silently re-orderable by an unrelated future change. This test converts that into a hard
 * invariant.
 *
 * <p>Runs against the real, fully auto-configured application context (both advisors only exist
 * there), which also makes it the proof that the {@code order} attribute does not break startup.
 *
 * <p><b>Residual, deliberately not asserted here:</b> {@code spring.threads.virtual.enabled: true},
 * and on Java 21 a virtual thread blocking inside {@code ConcurrentHashMap.compute}'s
 * {@code synchronized} bin lock PINS its carrier. Advisor ordering stops a pinned waiter from also
 * holding a connection; it cannot stop the pinning, which is a JDK-level fix (JEP 491, JDK 24).
 * That is why {@code sync = true} should stay reserved for genuinely hot public keys.
 */
@DisplayName("Advisor ordering — caching advice wraps transaction advice, not the reverse")
class CacheTransactionAdvisorOrderTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("the cache advisor runs at HIGHEST_PRECEDENCE")
    void should_orderCacheAdvisorOutermost_when_contextStarts() {
        BeanFactoryCacheOperationSourceAdvisor cacheAdvisor =
                applicationContext.getBean(BeanFactoryCacheOperationSourceAdvisor.class);

        assertThat(cacheAdvisor.getOrder())
                .as("CacheConfig's @EnableCaching(order = Ordered.HIGHEST_PRECEDENCE) is load-bearing "
                        + "— dropping it back to a bare @EnableCaching silently returns the nesting "
                        + "to undefined")
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("the cache advisor strictly precedes the transaction advisor")
    void should_orderCacheAdvisorBeforeTransactionAdvisor_when_contextStarts() {
        BeanFactoryCacheOperationSourceAdvisor cacheAdvisor =
                applicationContext.getBean(BeanFactoryCacheOperationSourceAdvisor.class);
        BeanFactoryTransactionAttributeSourceAdvisor txAdvisor =
                applicationContext.getBean(BeanFactoryTransactionAttributeSourceAdvisor.class);

        assertThat(cacheAdvisor.getOrder())
                .as("lower order = outer advisor. A cache hit must short-circuit BEFORE the "
                        + "transaction advisor opens a transaction and checks out one of only %d "
                        + "Hikari connections", 10)
                .isLessThan(txAdvisor.getOrder());
    }

    /**
     * Guards the assumption the test above rests on: that the transaction advisor is still at its
     * Spring default. If a future change gives it an explicit order, "cache &lt; transaction" could
     * hold numerically while both sit at values chosen for unrelated reasons — this makes that
     * change surface here rather than pass silently.
     */
    @Test
    @DisplayName("the transaction advisor is still at Spring's default LOWEST_PRECEDENCE")
    void should_leaveTransactionAdvisorAtDefaultOrder_when_contextStarts() {
        BeanFactoryTransactionAttributeSourceAdvisor txAdvisor =
                applicationContext.getBean(BeanFactoryTransactionAttributeSourceAdvisor.class);

        assertThat(txAdvisor.getOrder())
                .as("no @EnableTransactionManagement(order = ...) and no setOrder call is expected "
                        + "anywhere in this codebase")
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
