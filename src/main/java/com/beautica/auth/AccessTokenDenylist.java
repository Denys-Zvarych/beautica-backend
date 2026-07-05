package com.beautica.auth;

import com.beautica.config.JwtConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * In-memory denylist of revoked access-token {@code jti} values.
 *
 * <p>Access tokens are stateless JWTs verified purely by signature + {@code exp}
 * (see {@link JwtAuthenticationFilter}); nothing tracked their validity server-side,
 * so a compromised token — or one belonging to a user who just logged out — remained
 * fully usable for its entire TTL (now up to 1 hour). This cache closes that gap for
 * the single-instance deployment this project runs on today (one Railway dyno): a
 * Caffeine cache, not Redis, mirroring the pattern already used for the per-IP rate
 * limit buckets in {@link com.beautica.auth.filter.AuthRateLimitFilter} (a
 * security-control cache owned directly by its component, not routed through
 * {@link com.beautica.config.CacheConfig}'s {@code CacheManager} — that manager backs
 * Spring's {@code @Cacheable} read-through annotations, whereas this is an explicit
 * membership set: add on logout, check on every authenticated request).
 *
 * <p>Entries expire after the configured access-token TTL: a denylisted {@code jti}
 * can never legitimately need to be checked again once its token would have failed
 * signature/expiry validation anyway, so entries do not need to live any longer than
 * that.
 *
 * <p>{@code maximumSize} mirrors the 100,000-entry bound used for the per-IP rate
 * limit buckets — generous for realistic logout volume within a single access-token
 * TTL window, while still bounding worst-case memory. If the bound is ever exceeded,
 * Caffeine evicts the least-recently-used entries; a revoked token evicted early
 * would become usable again for the remainder of its TTL, an accepted trade-off
 * identical in kind to the one already made for the rate-limit buckets.
 *
 * <p><b>Does NOT survive a process restart or redeploy.</b> This is a Caffeine
 * cache only — there is no database table or Redis instance backing it. If the
 * service restarts (Railway redeploy, crash, dyno recycle) before a revoked
 * token's own {@code exp} has elapsed, every entry in this cache is lost and a
 * token revoked via logout (or, for the per-user variant, a password reset — see
 * {@link TokensValidAfterCache}) becomes valid again for its remaining TTL. This
 * is an accepted operational trade-off for the current single-instance (one
 * Railway dyno) deployment combined with the short (1-hour) access-token TTL: the
 * exposure window after a redeploy is bounded by that TTL, and redeploys are
 * infrequent relative to it. Revisit (persist revocations to a DB table, or move
 * to Redis) if this service is ever horizontally scaled to multiple instances —
 * at that point an in-memory-only cache also stops being consistent across
 * instances, which is a correctness problem, not just a durability one — or if a
 * stronger revocation guarantee becomes a hard requirement (e.g. compliance,
 * incident response SLA) independent of scaling.
 */
@Component
public class AccessTokenDenylist {

    private static final int MAXIMUM_SIZE = 100_000;

    private final Cache<String, Boolean> revokedJtis;

    public AccessTokenDenylist(JwtConfig jwtConfig) {
        this.revokedJtis = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_SIZE)
                .expireAfterWrite(Duration.ofMillis(jwtConfig.accessTokenExpiration()))
                .build();
    }

    /**
     * Marks {@code jti} as revoked. No-op if {@code jti} is {@code null} (a token
     * missing its jti claim cannot be denylisted individually; it will still expire
     * on its own {@code exp}).
     */
    public void revoke(String jti) {
        if (jti != null) {
            revokedJtis.put(jti, Boolean.TRUE);
        }
    }

    /**
     * @return {@code true} if {@code jti} has been revoked and not yet evicted.
     * Always {@code false} for a {@code null} jti.
     */
    public boolean isRevoked(String jti) {
        return jti != null && revokedJtis.getIfPresent(jti) != null;
    }
}
