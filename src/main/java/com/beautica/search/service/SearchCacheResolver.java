package com.beautica.search.service;

import com.beautica.search.dto.FreeTextSearchRequest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Routes each discovery call to the browse or the free-text half of its surface's
 * cache — see {@link SearchCacheNames} for why the two populations must not share
 * one cache.
 *
 * <h3>Why a resolver and not two annotations</h3>
 * {@code @Cacheable(value = …)} is a compile-time constant, so the cache cannot be
 * chosen from the request. Splitting the service method in two instead (one
 * {@code @Cacheable} per cache) would fork the whole search path on a caching
 * concern and re-introduce the drift shape this codebase keeps paying for. A
 * {@link CacheResolver} is Spring's supported extension point for exactly this.
 *
 * <h3>Routing rule</h3>
 * The annotation always declares the <b>browse</b> cache; this resolver swaps in the
 * free-text sibling ({@link SearchCacheNames#QUERY_CACHE_BY_BROWSE_CACHE}) iff
 * {@link NormalizedSearchQuery#cacheKey(String)} is non-null — i.e. iff the caller
 * supplied something other than blank whitespace. That is deliberately the SAME
 * predicate the {@code @Cacheable} key SpEL uses for its {@code q} component, so the
 * browse cache holds exactly the entries whose {@code q} component is {@code null}
 * and nothing else can land there.
 *
 * <p>A too-short query ({@code ?q=Ру}) routes to the free-text cache even though it
 * returns an empty page: {@code cacheKey} maps every below-minimum query onto the
 * single key {@code ""}, so it costs one entry there and keeps the browse half free
 * of anything a search box can mint.</p>
 *
 * <p>Returns exactly one cache, which {@code @Cacheable(sync = true)} requires.</p>
 */
@Component("searchCacheResolver")
public class SearchCacheResolver implements CacheResolver {

    private final CacheManager cacheManager;

    public SearchCacheResolver(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
        String declared = singleDeclaredCacheName(context);
        String resolved = carriesFreeText(context.getArgs())
                ? queryCacheFor(declared)
                : declared;

        Cache cache = cacheManager.getCache(resolved);
        if (cache == null) {
            throw new IllegalStateException("Discovery cache not registered: " + resolved);
        }
        return List.of(cache);
    }

    private static String singleDeclaredCacheName(CacheOperationInvocationContext<?> context) {
        Collection<String> names = context.getOperation().getCacheNames();
        if (names.size() != 1) {
            throw new IllegalStateException(
                    "Discovery caching must declare exactly one (browse) cache, got: " + names);
        }
        return names.iterator().next();
    }

    private static String queryCacheFor(String browseCacheName) {
        String queryCacheName = SearchCacheNames.QUERY_CACHE_BY_BROWSE_CACHE.get(browseCacheName);
        if (queryCacheName == null) {
            throw new IllegalStateException(
                    "No free-text sibling registered for discovery cache: " + browseCacheName);
        }
        return queryCacheName;
    }

    /**
     * {@code true} when the request supplied a normalisable {@code q}. Scans the
     * arguments rather than assuming position 0 so the resolver is not coupled to the
     * service method's signature; the discovery methods take exactly one
     * {@link FreeTextSearchRequest}.
     */
    private static boolean carriesFreeText(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof FreeTextSearchRequest request) {
                return NormalizedSearchQuery.cacheKey(request.q()) != null;
            }
        }
        throw new IllegalStateException(
                "searchCacheResolver applied to a method with no FreeTextSearchRequest argument");
    }
}
