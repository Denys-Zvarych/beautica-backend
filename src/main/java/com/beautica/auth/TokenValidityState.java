package com.beautica.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * Three-state answer to "may a token bearing this {@code sub} still authenticate?", cached by
 * {@link TokensValidAfterCache} and consumed by {@link JwtAuthenticationFilter}.
 *
 * <p><b>Why three states and not {@code Optional<Instant>} (phase 295 audit, HIGH-1).</b> The
 * previous shape collapsed two materially different answers into one {@link java.util.Optional
 * #empty()}:
 * <ol>
 *   <li>the {@code users} row exists and {@code tokens_valid_after} is {@code NULL} — the common
 *       "never reset a password" case, which must authenticate; and</li>
 *   <li>there is no {@code users} row at all — a HARD-DELETED account (phase 295's salon-deletion
 *       staff cascade is the first code path in this codebase that produces one), which must NOT
 *       authenticate.</li>
 * </ol>
 * The filter's guard read {@code if (present) { compare iat }}, so case 2 fell through the guard
 * entirely and the deleted user's access token kept authenticating for the remainder of its
 * one-hour TTL, {@code SecurityContextHolder} carrying their role. Distinguishing the two states
 * is what closes that; it costs nothing at runtime because the same {@code users_pkey} probe that
 * already ran now reports row-existence as well as the column.
 *
 * <p><b>Never {@code null}.</b> Every arm below is a real value, so
 * {@code Cache#get(key, mappingFunction)} always has something to store — a {@code null} return
 * from the mapping function is NOT cached by Caffeine, which would have defeated the cache for
 * precisely the deleted-account case this type exists to close, turning every request from a
 * revoked token into an uncached DB round trip.
 *
 * <p>Sealed so {@link JwtAuthenticationFilter}'s {@code switch} is exhaustive without a
 * {@code default} arm: a fourth state added here fails compilation at the decision site rather
 * than silently landing in a catch-all that authenticates.
 */
public sealed interface TokenValidityState {

    /** Singleton {@link Absent} — no allocation per cache miss. */
    TokenValidityState ABSENT = new Absent();

    /** Singleton {@link PresentNoReset} — the overwhelmingly common case. */
    TokenValidityState PRESENT_NO_RESET = new PresentNoReset();

    /**
     * No {@code users} row with this id — the account was hard-deleted (or the {@code sub} claim
     * names a user that never existed). The token must not authenticate, whatever its {@code iat}.
     */
    record Absent() implements TokenValidityState {}

    /**
     * The {@code users} row exists and {@code tokens_valid_after} is {@code NULL}: no password
     * reset has ever invalidated this account's outstanding tokens. Authenticate normally.
     */
    record PresentNoReset() implements TokenValidityState {}

    /**
     * The {@code users} row exists and carries a reset stamp. A token whose {@code iat} predates
     * {@code tokensValidAfter} must not authenticate.
     */
    record PresentAt(Instant tokensValidAfter) implements TokenValidityState {
        public PresentAt {
            Objects.requireNonNull(tokensValidAfter,
                    "PresentAt is the stamped state — use PRESENT_NO_RESET for a null column");
        }
    }
}
