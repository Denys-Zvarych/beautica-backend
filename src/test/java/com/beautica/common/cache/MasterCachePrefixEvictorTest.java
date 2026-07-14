package com.beautica.common.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The shared master-prefix eviction scan, driven directly (no Spring, no {@code @Async} proxy) — the scan
 * logic was previously duplicated in {@code SlotCalculationService} and {@code MasterScheduleService} and
 * covered only indirectly.
 */
@DisplayName("MasterCachePrefixEvictor — unit")
class MasterCachePrefixEvictorTest {

    private static final String CACHE_A = "master-service-bookable";
    private static final String CACHE_B = "master-bookable-days";

    private CacheManager cacheManager;
    private MasterCachePrefixEvictor evictor;

    @BeforeEach
    void setUp() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(CACHE_A,
                Caffeine.newBuilder().maximumSize(100).expireAfterWrite(60, TimeUnit.SECONDS).build());
        manager.registerCustomCache(CACHE_B,
                Caffeine.newBuilder().maximumSize(100).expireAfterWrite(60, TimeUnit.SECONDS).build());
        this.cacheManager = manager;
        this.evictor = new MasterCachePrefixEvictor(manager);
    }

    /** The runtime shape of a {@code @Cacheable(key = "{#masterId, …}")} SpEL inline-list key. */
    private static List<Object> key(UUID masterId, Object... rest) {
        List<Object> parts = new java.util.ArrayList<>();
        parts.add(masterId);
        parts.addAll(List.of(rest));
        return parts;
    }

    @Test
    @DisplayName("should evict every entry of every named cache whose key starts with the master id")
    void should_evictAllNamedCaches_when_keyPrefixMatchesMaster() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        cacheManager.getCache(CACHE_A).put(key(target, "svc", "from", "to"), Boolean.TRUE);
        cacheManager.getCache(CACHE_B).put(key(target, "from", "to", "svc"), List.of());
        cacheManager.getCache(CACHE_A).put(key(other, "svc", "from", "to"), Boolean.TRUE);
        cacheManager.getCache(CACHE_B).put(key(other, "from", "to", "svc"), List.of());

        evictor.evictByMasterPrefix(target, CACHE_A, CACHE_B);

        assertThat(cacheManager.getCache(CACHE_A).get(key(target, "svc", "from", "to")))
                .as("target's entry in the first cache is gone")
                .isNull();
        assertThat(cacheManager.getCache(CACHE_B).get(key(target, "from", "to", "svc")))
                .as("target's entry in the second cache is gone")
                .isNull();
        assertThat(cacheManager.getCache(CACHE_A).get(key(other, "svc", "from", "to")))
                .as("another master's entry must survive — eviction is scoped, never blanket")
                .isNotNull();
        assertThat(cacheManager.getCache(CACHE_B).get(key(other, "from", "to", "svc")))
                .as("another master's entry must survive in every named cache")
                .isNotNull();
    }

    @Test
    @DisplayName("should ignore an unknown cache name rather than throw")
    void should_notThrow_when_cacheIsUnknown() {
        UUID masterId = UUID.randomUUID();

        assertThatCode(() -> evictor.evictByMasterPrefix(masterId, "no-such-cache"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should leave non-list keys untouched")
    void should_leaveNonListKeys_when_scanningByPrefix() {
        UUID masterId = UUID.randomUUID();
        cacheManager.getCache(CACHE_A).put("a-plain-string-key", Boolean.TRUE);

        evictor.evictByMasterPrefix(masterId, CACHE_A);

        assertThat(cacheManager.getCache(CACHE_A).get("a-plain-string-key"))
                .as("a key that is not a SpEL inline-list cannot belong to any master")
                .isNotNull();
    }
}
