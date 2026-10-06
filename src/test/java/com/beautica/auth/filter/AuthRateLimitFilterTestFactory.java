package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds a real {@link AuthRateLimitFilter} with every bucket permissive EXCEPT the one named by
 * {@code @Qualifier}, which gets the caller's cache. Resolved by qualifier NAME, never by
 * constructor position (2026-09-13 audit, Q12): inserting a new bucket parameter can then never
 * silently shift which bucket a test constrains. Extracted from
 * {@code CatalogueBrowseGetRateLimitRegressionTest} when {@code CatalogueBrowseBearerDeferralTest}
 * became its second user.
 */
final class AuthRateLimitFilterTestFactory {

    private AuthRateLimitFilterTestFactory() {
    }

    /** A per-key bucket cache of {@code capacity} tokens refilled once per minute. */
    static LoadingCache<String, Bucket> bucketsOf(long capacity) {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(BandwidthBuilder.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, Duration.ofMinutes(1))
                        .build())
                .build());
    }

    static AuthRateLimitFilter withBucket(String qualifier, LoadingCache<String, Bucket> constrained) {
        return withBuckets(Map.of(qualifier, constrained));
    }

    /**
     * Same as {@link #withBucket}, for tests that constrain SEVERAL buckets at once (e.g. the
     * anonymous per-IP browse bucket AND the authenticated per-IP ceiling). Each map key is a
     * {@code @Qualifier} value that must appear on exactly one constructor parameter.
     */
    @SuppressWarnings("unchecked")
    static AuthRateLimitFilter withBuckets(Map<String, LoadingCache<String, Bucket>> constrained) {
        Constructor<?>[] constructors = AuthRateLimitFilter.class.getConstructors();
        assertThat(constructors)
                .as("AuthRateLimitFilter must expose exactly one public constructor for this "
                        + "qualifier-indexed wiring to be unambiguous")
                .hasSize(1);
        Constructor<AuthRateLimitFilter> ctor = (Constructor<AuthRateLimitFilter>) constructors[0];

        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
        Map<String, Integer> matched = new HashMap<>();
        for (int i = 0; i < params.length; i++) {
            Qualifier q = params[i].getAnnotation(Qualifier.class);
            String name = q == null ? null : q.value();
            if (name != null && constrained.containsKey(name)) {
                assertThat(matched.put(name, i))
                        .as("@Qualifier(\"%s\") must appear on exactly ONE constructor parameter", name)
                        .isNull();
                args[i] = constrained.get(name);
            } else {
                args[i] = bucketsOf(1_000_000);
            }
        }
        assertThat(matched.keySet())
                .as("every constrained qualifier must exist on the constructor — a renamed or removed "
                        + "bucket means the coverage is gone, not merely moved")
                .containsExactlyInAnyOrderElementsOf(constrained.keySet());
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not construct AuthRateLimitFilter", e);
        }
    }
}
