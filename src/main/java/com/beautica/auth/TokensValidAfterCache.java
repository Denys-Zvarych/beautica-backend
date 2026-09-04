package com.beautica.auth;

import com.beautica.user.TokensValidAfterRow;
import com.beautica.user.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-TTL read-through cache in front of {@code User.tokensValidAfter}
 * ({@link UserRepository#findTokensValidAfterById}), used by {@link JwtAuthenticationFilter}
 * to reject an access token whose {@code iat} (issued-at) claim predates a password reset.
 *
 * <p>Unlike {@link AccessTokenDenylist} — a pure in-memory revocation set with no durable
 * backing store, entries lost on restart are simply gone — the source of truth here is the
 * {@code users.tokens_valid_after} column. This cache only exists to avoid a DB round-trip
 * on every authenticated request; correctness after a restart is preserved because a cache
 * miss falls through to the DB, not to "no reset happened". The TTL is short (60 s, far
 * below the access-token TTL) precisely so a password reset takes effect for a given user
 * within one refresh window rather than for the token's remaining lifetime.
 *
 * <p>Values are cached as a {@link TokenValidityState} (never a bare {@code null}) so BOTH the
 * common "never reset" case and the "no such row" case are themselves cached —
 * {@code Cache#get(Object, java.util.function.Function)} does not cache a {@code null} return from
 * the mapping function, which would otherwise defeat the cache for the overwhelming majority of
 * users who have never reset their password, and (worse, since phase 295) for every request
 * replaying a hard-deleted account's token.
 *
 * <p><b>Three states, not {@code Optional<Instant>} (phase 295 audit, HIGH-1).</b> The value type
 * distinguishes {@code ABSENT} (no {@code users} row — a hard-deleted staff account) from
 * {@code PRESENT_NO_RESET} (row exists, never reset). Collapsing those two into one empty
 * {@code Optional} is what let {@link JwtAuthenticationFilter} authenticate a deleted account for
 * the rest of its token's TTL. The DB cost is unchanged: the same single {@code users_pkey} probe,
 * now projecting the row as well as the column — see
 * {@link UserRepository#findTokensValidAfterRowById}.
 */
@Component
public class TokensValidAfterCache {

    private static final int MAXIMUM_SIZE = 100_000;
    private static final Duration REFRESH_TTL = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final Cache<UUID, TokenValidityState> cache;

    public TokensValidAfterCache(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_SIZE)
                .expireAfterWrite(REFRESH_TTL)
                .build();
    }

    /**
     * @return {@link TokenValidityState#ABSENT} if no {@code users} row with this id exists,
     * {@link TokenValidityState#PRESENT_NO_RESET} if the row exists and has never had its
     * outstanding tokens invalidated, or a {@link TokenValidityState.PresentAt} carrying the
     * reset stamp. Never {@code null} — see this class's javadoc for why that matters to Caffeine.
     */
    public TokenValidityState get(UUID userId) {
        return cache.get(userId, id -> toState(userRepository.findTokensValidAfterRowById(id)));
    }

    private static TokenValidityState toState(Optional<TokensValidAfterRow> row) {
        return row
                .map(r -> r.tokensValidAfter() == null
                        ? TokenValidityState.PRESENT_NO_RESET
                        : new TokenValidityState.PresentAt(r.tokensValidAfter()))
                .orElse(TokenValidityState.ABSENT);
    }

    /**
     * Evicts the cached entry for {@code userId} so the very next request re-reads the
     * fresh value from the database instead of waiting out the {@link #REFRESH_TTL}.
     *
     * <p><strong>Must be called only after the writing transaction commits</strong> — never
     * synchronously inline from within the {@code @Transactional} method that updates
     * {@code users.tokens_valid_after}. {@code get()} above is a read-through cache: if this
     * method runs before commit, a concurrent request racing in that window can miss the
     * cache, read the not-yet-committed (stale, pre-reset) row under READ COMMITTED, and
     * cache that stale "no reset happened" answer for the full {@link #REFRESH_TTL} — i.e.
     * a stolen access token would survive the reset for up to another TTL window, which is
     * the exact vulnerability this cache exists to close. {@code PasswordResetService}
     * enforces this by registering the call as a {@code TransactionSynchronization}
     * {@code afterCommit} hook rather than invoking it inline.
     *
     * <p>Note this is <em>not</em> analogous to {@code AccessTokenDenylist.revoke}, which
     * {@code AuthService.logout} does call synchronously: that denylist is a pure in-memory
     * set with no DB read-through to race against, so ordering relative to a transaction
     * commit is irrelevant there. Here it is the whole point — commit-ordering, not
     * rollback-safety, is what matters (an eviction on a transaction that later rolls back
     * would indeed be a harmless no-op, but that was never the risk).
     */
    public void invalidate(UUID userId) {
        cache.invalidate(userId);
    }
}
