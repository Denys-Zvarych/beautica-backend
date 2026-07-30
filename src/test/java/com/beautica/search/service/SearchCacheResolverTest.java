package com.beautica.search.service;

import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.SalonSearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheableOperation;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SearchCacheResolver} — the routing seam that keeps the
 * unbounded free-text key population out of the bounded, cross-user-shared browse
 * cache.
 *
 * <h3>Why this needs its own test</h3>
 * The resolver had no dedicated coverage at all. Its contract has three parts that
 * a code reading cannot verify:
 *
 * <ol>
 *   <li>a browse request (no {@code q}) resolves the DECLARED cache;</li>
 *   <li>a free-text request resolves the {@code :q} SIBLING — routed by exactly the
 *       same {@link NormalizedSearchQuery#cacheKey(String)} predicate the
 *       {@code @Cacheable} key SpEL uses, so nothing a search box can mint lands on
 *       the browse half;</li>
 *   <li>exactly ONE cache is returned, which {@code @Cacheable(sync = true)}
 *       requires — returning two throws at runtime, on a public endpoint.</li>
 * </ol>
 *
 * <p>The security-relevant case is (2) at the below-minimum boundary: {@code ?q=Ру}
 * returns an explicit empty page, so if it routed to the BROWSE cache it could both
 * read a populated unfiltered entry and poison it with an empty one. That is defect
 * B re-entering through the cache layer, and it is pinned here.</p>
 */
@DisplayName("SearchCacheResolver — unit")
class SearchCacheResolverTest {

    private CacheManager cacheManager;
    private SearchCacheResolver resolver;

    @BeforeEach
    void setUp() {
        CaffeineCacheManager caffeine = new CaffeineCacheManager();
        caffeine.setCacheNames(List.of(
                SearchCacheNames.MASTERS_BROWSE, SearchCacheNames.MASTERS_QUERY,
                SearchCacheNames.SALONS_BROWSE, SearchCacheNames.SALONS_QUERY));
        this.cacheManager = caffeine;
        this.resolver = new SearchCacheResolver(caffeine);
    }

    // ── browse vs free-text routing ───────────────────────────────────────────

    @Test
    @DisplayName("a master request with NO q resolves the declared BROWSE cache")
    void should_resolveBrowseCache_when_masterRequestCarriesNoQuery() {
        Collection<? extends Cache> caches = resolver.resolveCaches(
                context(SearchCacheNames.MASTERS_BROWSE, masterRequest(null)));

        assertThat(cacheNames(caches))
                .as("a location-only browse page belongs on the bounded, cross-user-shared half")
                .containsExactly(SearchCacheNames.MASTERS_BROWSE);
    }

    @Test
    @DisplayName("a master request WITH q resolves the free-text :q sibling, never the browse cache")
    void should_resolveQueryCache_when_masterRequestCarriesQuery() {
        Collection<? extends Cache> caches = resolver.resolveCaches(
                context(SearchCacheNames.MASTERS_BROWSE, masterRequest("кератин")));

        assertThat(cacheNames(caches))
                .as("an incremental search box mints ~one key per keystroke; on the shared cache "
                        + "that population evicted OTHER users' browse pages")
                .containsExactly(SearchCacheNames.MASTERS_QUERY);
    }

    @Test
    @DisplayName("a salon request routes across the same browse/:q split")
    void should_resolveSalonCaches_when_routingBySalonQuery() {
        assertThat(cacheNames(resolver.resolveCaches(
                context(SearchCacheNames.SALONS_BROWSE, salonRequest(null)))))
                .containsExactly(SearchCacheNames.SALONS_BROWSE);

        assertThat(cacheNames(resolver.resolveCaches(
                context(SearchCacheNames.SALONS_BROWSE, salonRequest("кератин")))))
                .containsExactly(SearchCacheNames.SALONS_QUERY);
    }

    @Test
    @DisplayName("a blank/whitespace-only q is treated as ABSENT and stays on the browse cache")
    void should_resolveBrowseCache_when_queryIsBlank() {
        assertThat(cacheNames(resolver.resolveCaches(
                context(SearchCacheNames.MASTERS_BROWSE, masterRequest("   ")))))
                .as("cacheKey() returns null for a blank q — the same predicate the key SpEL "
                        + "uses, so the browse cache holds exactly the entries whose q is null")
                .containsExactly(SearchCacheNames.MASTERS_BROWSE);
    }

    // ── the below-minimum boundary (defect B through the cache layer) ──────────

    @Test
    @DisplayName("a BELOW-MINIMUM q (?q=Ру) routes to the :q cache — it can neither read nor poison the unfiltered browse entry")
    void should_routeBelowMinimumQueryToQueryCache_notBrowse() {
        Collection<? extends Cache> caches = resolver.resolveCaches(
                context(SearchCacheNames.MASTERS_BROWSE, masterRequest("Ру")));

        assertThat(cacheNames(caches))
                .as("?q=Ру returns an EXPLICIT EMPTY page. On the browse cache it could serve the "
                        + "unfiltered set out of cache (defect B) and poison that entry with an "
                        + "empty page for every other visitor.")
                .containsExactly(SearchCacheNames.MASTERS_QUERY);
    }

    @Test
    @DisplayName("every below-minimum q collapses onto the SINGLE cache key \"\" — one entry, not one per keystroke")
    void should_collapseAllBelowMinimumQueriesOntoOneKey() {
        assertThat(NormalizedSearchQuery.cacheKey("Ру"))
                .as("below-minimum queries all return the same empty page, so they legitimately "
                        + "share one entry")
                .isEmpty();
        assertThat(NormalizedSearchQuery.cacheKey("зз")).isEmpty();
        assertThat(NormalizedSearchQuery.cacheKey("a"))
                .isEqualTo(NormalizedSearchQuery.cacheKey("Ру"));

        assertThat(NormalizedSearchQuery.cacheKey(null))
                .as("absent must stay a DISTINCT key domain from below-minimum, or the two collide "
                        + "and ?q=Ру serves the unfiltered set")
                .isNull();
        assertThat(NormalizedSearchQuery.cacheKey("кератин"))
                .as("an executable query keys on its normalised tokens")
                .isEqualTo("кератин");
    }

    // ── the sync = true single-cache requirement ───────────────────────────────

    @Test
    @DisplayName("exactly ONE cache is returned in every routing branch (@Cacheable(sync = true) requires it)")
    void should_returnExactlyOneCache_inEveryBranch() {
        assertThat(resolver.resolveCaches(context(SearchCacheNames.MASTERS_BROWSE, masterRequest(null))))
                .hasSize(1);
        assertThat(resolver.resolveCaches(context(SearchCacheNames.MASTERS_BROWSE, masterRequest("кератин"))))
                .hasSize(1);
        assertThat(resolver.resolveCaches(context(SearchCacheNames.SALONS_BROWSE, salonRequest(null))))
                .hasSize(1);
        assertThat(resolver.resolveCaches(context(SearchCacheNames.SALONS_BROWSE, salonRequest("кератин"))))
                .hasSize(1);
    }

    @Test
    @DisplayName("declaring more than one cache on the annotation fails loudly (sync = true cannot resolve two)")
    void should_throwIllegalState_when_operationDeclaresMoreThanOneCache() {
        CacheOperationInvocationContext<?> twoCaches = context(
                Set.of(SearchCacheNames.MASTERS_BROWSE, SearchCacheNames.MASTERS_QUERY),
                masterRequest(null));

        assertThatThrownBy(() -> resolver.resolveCaches(twoCaches))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("a declared cache with no registered :q sibling fails loudly rather than routing free text onto browse")
    void should_throwIllegalState_when_noQuerySiblingRegistered() {
        CacheOperationInvocationContext<?> unmapped =
                context(Set.of("some:unmapped:cache"), masterRequest("кератин"));

        assertThatThrownBy(() -> resolver.resolveCaches(unmapped))
                .as("silently falling back to the declared name would send every free-text key to "
                        + "the browse cache — the exact failure the split exists to prevent")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No free-text sibling");
    }

    @Test
    @DisplayName("an unregistered cache name fails loudly instead of returning null")
    void should_throwIllegalState_when_resolvedCacheIsNotRegistered() {
        SearchCacheResolver emptyManagerResolver =
                new SearchCacheResolver(new CaffeineCacheManager() {
                    @Override
                    public Cache getCache(String name) {
                        return null;
                    }
                });

        assertThatThrownBy(() -> emptyManagerResolver.resolveCaches(
                context(SearchCacheNames.MASTERS_BROWSE, masterRequest(null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    @DisplayName("applying the resolver to a method with no FreeTextSearchRequest argument fails loudly")
    void should_throwIllegalState_when_noFreeTextRequestArgumentPresent() {
        CacheOperationInvocationContext<?> noRequest =
                context(Set.of(SearchCacheNames.MASTERS_BROWSE), "not-a-request");

        assertThatThrownBy(() -> resolver.resolveCaches(noRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no FreeTextSearchRequest");
    }

    // ── the four cache names and the routing map ───────────────────────────────

    @Test
    @DisplayName("the browse -> :q routing map covers BOTH surfaces, and the *_ALL lists carry both halves for blanket eviction")
    void should_registerBothHalvesOfEachSurface() {
        assertThat(SearchCacheNames.MASTERS_ALL)
                .as("blanket eviction iterates these lists; a missing half leaves a stale row "
                        + "reachable through the other cache")
                .containsExactly(SearchCacheNames.MASTERS_BROWSE, SearchCacheNames.MASTERS_QUERY);
        assertThat(SearchCacheNames.SALONS_ALL)
                .containsExactly(SearchCacheNames.SALONS_BROWSE, SearchCacheNames.SALONS_QUERY);

        assertThat(cacheNames(resolver.resolveCaches(
                context(SearchCacheNames.MASTERS_BROWSE, masterRequest("x-executable-query")))))
                .containsExactly(SearchCacheNames.MASTERS_QUERY);
        assertThat(cacheNames(resolver.resolveCaches(
                context(SearchCacheNames.SALONS_BROWSE, salonRequest("x-executable-query")))))
                .containsExactly(SearchCacheNames.SALONS_QUERY);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static MasterSearchRequest masterRequest(String q) {
        return new MasterSearchRequest(null, q, null, null, null, null, null, 0, 20, null);
    }

    private static SalonSearchRequest salonRequest(String q) {
        return new SalonSearchRequest(null, q, null, null, null, null, 0, 20, null);
    }

    private static List<String> cacheNames(Collection<? extends Cache> caches) {
        return caches.stream().map(Cache::getName).toList();
    }

    private CacheOperationInvocationContext<?> context(String declaredCache, Object argument) {
        return context(Set.of(declaredCache), argument);
    }

    /**
     * Minimal {@link CacheOperationInvocationContext} carrying just the two things
     * the resolver reads: the declared cache names and the invocation arguments.
     * Hand-written rather than mocked so the generic
     * {@code CacheOperationInvocationContext<CacheOperation>} bound resolves without
     * an unchecked cast at every call site.
     */
    private CacheOperationInvocationContext<CacheableOperation> context(
            Set<String> declaredCaches, Object argument) {
        CacheableOperation.Builder builder = new CacheableOperation.Builder();
        builder.setName("searchMasters");
        builder.setCacheNames(declaredCaches.toArray(String[]::new));
        CacheableOperation operation = builder.build();

        return new CacheOperationInvocationContext<>() {
            @Override
            public CacheableOperation getOperation() {
                return operation;
            }

            @Override
            public Object getTarget() {
                return SearchCacheResolverTest.this;
            }

            @Override
            public Method getMethod() {
                return SearchCacheResolverTest.class.getDeclaredMethods()[0];
            }

            @Override
            public Object[] getArgs() {
                return new Object[]{argument};
            }
        };
    }
}
