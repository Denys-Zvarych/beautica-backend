package com.beautica.auth.appcheck;

import com.beautica.config.appcheck.AppCheckProperties;
import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@DisplayName("AppCheckJwks — key-set cache, stale-while-refresh and fetch throttling (B514, AUTH-54)")
class AppCheckJwksTest {

    private static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");
    private static final Duration NO_WAIT = Duration.ofSeconds(5);

    private final MutableTestClock clock = new MutableTestClock(T0);
    private final AppCheckProperties properties = new AppCheckProperties();
    private final PublicKey key = AppCheckTestTokens.publicKey();

    private AppCheckJwks jwks(Supplier<Map<String, PublicKey>> source) {
        return new AppCheckJwks(source, properties, clock);
    }

    @Test
    @DisplayName("an expired key set is still served to other callers while one caller's slow refresh is in flight")
    void should_serveStaleKeys_when_anotherCallerHoldsSlowRefresh() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        AppCheckJwks jwks = jwks(() -> {
            if (fetches.incrementAndGet() > 1) {
                fetchStarted.countDown();
                await(releaseFetch);
            }
            return Map.of(AppCheckTestTokens.KID, key);
        });
        jwks.keyFor(AppCheckTestTokens.KID);
        clock.advance(properties.getJwksCacheTtl().plusSeconds(1));
        CompletableFuture<Optional<PublicKey>> refresher =
                CompletableFuture.supplyAsync(() -> jwks.keyFor(AppCheckTestTokens.KID));
        assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).as("refresh fetch started").isTrue();

        Optional<PublicKey> concurrent = assertTimeoutPreemptively(NO_WAIT,
                () -> jwks.keyFor(AppCheckTestTokens.KID), "a concurrent caller must not queue behind the refresh");

        releaseFetch.countDown();
        assertThat(concurrent).contains(key);
        assertThat(refresher.get(5, TimeUnit.SECONDS)).contains(key);
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent first callers wait for the one fetch instead of each fetching")
    void should_fetchOnce_when_cacheEmptyAndCallersConcurrent() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        AppCheckJwks jwks = jwks(() -> {
            fetches.incrementAndGet();
            fetchStarted.countDown();
            await(releaseFetch);
            return Map.of(AppCheckTestTokens.KID, key);
        });
        CompletableFuture<Optional<PublicKey>> first =
                CompletableFuture.supplyAsync(() -> jwks.keyFor(AppCheckTestTokens.KID));
        assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Optional<PublicKey>> second =
                CompletableFuture.supplyAsync(() -> jwks.keyFor(AppCheckTestTokens.KID));

        releaseFetch.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).contains(key);
        assertThat(second.get(5, TimeUnit.SECONDS)).contains(key);
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed refresh after expiry keeps serving the last good key set")
    void should_serveStaleKeys_when_refreshFailsAfterExpiry() {
        AtomicInteger fetches = new AtomicInteger();
        AppCheckJwks jwks = jwks(() -> {
            if (fetches.incrementAndGet() > 1) {
                throw new IllegalStateException("jwks down");
            }
            return Map.of(AppCheckTestTokens.KID, key);
        });
        jwks.keyFor(AppCheckTestTokens.KID);
        clock.advance(properties.getJwksCacheTtl().plusSeconds(1));

        Optional<PublicKey> result = jwks.keyFor(AppCheckTestTokens.KID);

        assertThat(result).contains(key);
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("with nothing cached and the fetch failing, keys are reported unavailable")
    void should_throwUnavailable_when_firstFetchFails() {
        AppCheckJwks jwks = jwks(() -> {
            throw new IllegalStateException("jwks down");
        });

        assertThatThrownBy(() -> jwks.keyFor(AppCheckTestTokens.KID))
                .isInstanceOf(AppCheckKeysUnavailableException.class);
    }

    @Test
    @DisplayName("forged kids trigger at most one refetch per minimum interval")
    void should_refetchAtMostOncePerInterval_when_unknownKidsRepeat() {
        AtomicInteger fetches = new AtomicInteger();
        AppCheckJwks jwks = jwks(() -> {
            fetches.incrementAndGet();
            return Map.of(AppCheckTestTokens.KID, key);
        });
        jwks.keyFor(AppCheckTestTokens.KID);
        clock.advance(properties.getJwksMinRefetchInterval());

        Optional<PublicKey> first = jwks.keyFor("forged-1");
        Optional<PublicKey> second = jwks.keyFor("forged-2");

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(fetches.get()).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
