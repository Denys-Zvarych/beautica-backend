package com.beautica.common.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The one place a test may construct a cache key by hand.
 *
 * <p><b>Why this exists.</b> Every master-scoped cache in this codebase declares an explicit SpEL
 * {@code key = "{...}"} inline list. Tests that seeded their own guess at that key's runtime type
 * hid a production bug for months: five {@code removeIf} predicates checked
 * {@code instanceof SimpleKey}, which an explicit-{@code key} {@code @Cacheable} NEVER produces
 * ({@code SimpleKey} comes only from the default {@code SimpleKeyGenerator}, used when no
 * {@code key} attribute is given). The predicates were unsatisfiable and every eviction silently
 * no-opped — but the tests seeded {@code SimpleKey} sentinels, so they matched the broken predicate
 * and stayed green.
 *
 * <p><b>The rule that follows.</b> A hand-seeded key is only trustworthy if something independently
 * proves it equals what Spring really stores. {@link CachePrefixEvictionKeyShapeTest} does exactly
 * that: it drives the real production {@code @Cacheable} proxies, captures the keys Spring itself
 * computed, and asserts they are {@code equals} to {@link #spelKey}'s output. So if Spring's key
 * representation ever changes, that test fails first and this single helper is corrected once —
 * rather than every call site silently drifting back out of sync.
 *
 * <p>Do not inline {@code new SimpleKey(...)} or a bare {@code List.of(...)} in a cache test; call
 * this instead.
 */
public final class CacheKeyFixtures {

    private CacheKeyFixtures() {
    }

    /**
     * The runtime key a {@code @Cacheable(key = "{a, b, c}")} inline-list SpEL literal evaluates to:
     * a {@link List} of the evaluated elements, in order, tolerating nulls (several production keys
     * carry optional filter arguments that are legitimately null).
     */
    public static List<Object> spelKey(Object... parts) {
        return new ArrayList<>(Arrays.asList(parts));
    }
}
