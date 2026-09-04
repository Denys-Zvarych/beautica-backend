package com.beautica.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Scalar projection of one {@code users} row's identity plus its {@code tokens_valid_after} stamp,
 * returned by {@link UserRepository#findTokensValidAfterRowById}.
 *
 * <p>The point of the record is the OUTER {@code Optional} it is wrapped in: an empty
 * {@code Optional} now means "no such row" and only that, while a present row with a {@code null}
 * {@link #tokensValidAfter()} means "row exists, never reset". The previous
 * {@code Optional<Instant>} projection could not tell those apart, and
 * {@link com.beautica.auth.JwtAuthenticationFilter} read the ambiguity as "no check applies" —
 * see {@link com.beautica.auth.TokenValidityState} for the full account of the fail-open it caused.
 *
 * @param id               the user id the projection was resolved for; never {@code null}
 * @param tokensValidAfter the reset stamp, {@code null} for an account that has never had its
 *                         outstanding tokens invalidated
 */
public record TokensValidAfterRow(UUID id, Instant tokensValidAfter) {
}
