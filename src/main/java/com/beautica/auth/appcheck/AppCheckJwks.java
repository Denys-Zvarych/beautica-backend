package com.beautica.auth.appcheck;

import com.beautica.config.OutboundTimeouts;
import com.beautica.config.appcheck.AppCheckProperties;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * B514 (AUTH-54) — Google's App Check signing keys ({@code kid → RSA public key}), fetched from
 * {@code app.auth.app-check.jwks-url} and kept for {@code jwks-cache-ttl} (6 h). An unknown {@code kid} triggers one
 * refetch, at most once per {@code jwks-min-refetch-interval}, so a forged {@code kid} cannot drive a fetch per
 * request. A failed fetch keeps serving the last good key set (stale beats an outage); only a failed fetch with NO
 * key set at all is "unavailable" (→ 503 in ENFORCE).
 *
 * <p>Concurrency: a {@link ReentrantLock}, never {@code synchronized}, so a fetch never pins a virtual-thread carrier.
 * On TTL expiry only the {@code tryLock} winner fetches; every other caller keeps verifying against the stale set
 * (Google rotates keys with overlap). Callers block only when nothing is cached yet, or on an unknown {@code kid}
 * (the refetch may bring it).
 *
 * <p>HTTP client as {@code TurnstileVerifier}: JDK client on HTTP/1.1, no redirects, explicit connect/read
 * timeouts. Logs carry the failure class only, never a body.
 */
@Component
public class AppCheckJwks {

    private static final Logger log = LoggerFactory.getLogger(AppCheckJwks.class);

    private final AppCheckProperties properties;
    private final Supplier<Map<String, PublicKey>> keySource;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Snapshot snapshot;
    private volatile Instant lastFetchAttempt;

    private record Snapshot(Map<String, PublicKey> keys, Instant fetchedAt) {
    }

    @Autowired
    public AppCheckJwks(RestClient.Builder restClientBuilder, AppCheckProperties properties, Clock clock) {
        this(jdkFetcher(restClientBuilder, properties), properties, clock);
    }

    /** Test seam: {@code keySource} fetches and parses one key set, throwing on any failure. */
    AppCheckJwks(Supplier<Map<String, PublicKey>> keySource, AppCheckProperties properties, Clock clock) {
        this.keySource = keySource;
        this.properties = properties;
        this.clock = clock;
    }

    private static Supplier<Map<String, PublicKey>> jdkFetcher(RestClient.Builder restClientBuilder,
                                                               AppCheckProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(OutboundTimeouts.requirePositive(
                        "app.auth.app-check.connect-timeout", properties.getConnectTimeout()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(OutboundTimeouts.requirePositive(
                "app.auth.app-check.read-timeout", properties.getReadTimeout()));
        RestClient restClient = restClientBuilder.clone().requestFactory(requestFactory).build();
        return () -> fetchKeys(restClient, properties.getJwksUrl());
    }

    /**
     * @return the key for {@code kid}, or empty when Google does not publish it (→ invalid token)
     * @throws AppCheckKeysUnavailableException when no key set could ever be fetched
     */
    public Optional<PublicKey> keyFor(String kid) {
        Instant now = clock.instant();
        Snapshot current = snapshot;
        if (current == null) {
            current = refreshBlocking(now, null);
        } else if (expired(current, now)) {
            current = refreshUnlessBusy(now, current);
        }
        PublicKey key = current.keys().get(kid);
        if (key == null && kid != null && refetchAllowed(now)) {
            current = refreshBlocking(now, current);
            key = current.keys().get(kid);
        }
        return Optional.ofNullable(key);
    }

    private boolean expired(Snapshot current, Instant now) {
        return current.fetchedAt().plus(properties.getJwksCacheTtl()).isBefore(now);
    }

    private boolean refetchAllowed(Instant now) {
        Instant last = lastFetchAttempt;
        return last == null || !last.plus(properties.getJwksMinRefetchInterval()).isAfter(now);
    }

    /** Expiry path: the lock winner refreshes; everyone else serves {@code stale} instead of queueing. */
    private Snapshot refreshUnlessBusy(Instant now, Snapshot stale) {
        if (!refreshLock.tryLock()) {
            return stale;
        }
        try {
            return refreshLocked(now, stale);
        } finally {
            refreshLock.unlock();
        }
    }

    /** Empty cache or unknown kid: wait for the in-flight fetch (if any) — there is nothing better to serve. */
    private Snapshot refreshBlocking(Instant now, Snapshot stale) {
        refreshLock.lock();
        try {
            return refreshLocked(now, stale);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * One fetch at a time, and at most one per {@code jwks-min-refetch-interval} whatever triggered it (expiry or an
     * unknown kid), so an outage or a forged kid never turns into a fetch per request. Caller holds the lock.
     */
    private Snapshot refreshLocked(Instant now, Snapshot stale) {
        Snapshot latest = snapshot;
        if (latest != null && latest != stale) {
            return latest; // another thread refreshed while this one waited
        }
        if (!refetchAllowed(now)) {
            return staleOrUnavailable(stale);
        }
        lastFetchAttempt = now;
        try {
            Snapshot fresh = new Snapshot(keySource.get(), now);
            snapshot = fresh;
            return fresh;
        } catch (RuntimeException e) {
            log.warn("app-check jwks fetch failed: {}", e.getClass().getSimpleName());
            return staleOrUnavailable(stale);
        }
    }

    private static Snapshot staleOrUnavailable(Snapshot stale) {
        if (stale != null) {
            return stale;
        }
        throw new AppCheckKeysUnavailableException("jwks unavailable");
    }

    private static Map<String, PublicKey> fetchKeys(RestClient restClient, String jwksUrl) {
        String body = restClient.get()
                .uri(jwksUrl)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("empty jwks body");
        }
        JwkSet set = Jwks.setParser().build().parse(body);
        Map<String, PublicKey> keys = new HashMap<>();
        for (Jwk<?> jwk : set.getKeys()) {
            if (jwk.getId() != null && jwk.toKey() instanceof RSAPublicKey rsa) {
                keys.put(jwk.getId(), rsa);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("jwks has no RSA keys");
        }
        return Map.copyOf(keys);
    }
}
