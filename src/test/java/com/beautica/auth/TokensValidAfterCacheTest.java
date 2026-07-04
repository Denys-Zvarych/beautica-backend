package com.beautica.auth;

import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dedicated unit tests for {@link TokensValidAfterCache} — the read-through cache
 * backing the pre-reset-token-rejected guarantee. Previously only exercised indirectly
 * via a mock in {@code JwtAuthenticationFilterTest} and end-to-end via
 * {@code PasswordResetControllerIT}; neither proves this class's own read-through /
 * caching / invalidation contract in isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokensValidAfterCache — unit")
class TokensValidAfterCacheTest {

    private static final Logger log = LoggerFactory.getLogger(TokensValidAfterCacheTest.class);

    @Mock
    private UserRepository userRepository;

    private TokensValidAfterCache cache;

    @BeforeEach
    void setUp() {
        cache = new TokensValidAfterCache(userRepository);
    }

    @Test
    @DisplayName("get delegates to the repository on a cache miss and returns its result")
    void should_delegateToRepository_when_cacheMiss() {
        var userId = UUID.randomUUID();
        Instant resetInstant = Instant.parse("2025-06-01T12:00:00Z");
        log.debug("Arrange: repository has a stamped tokensValidAfter for userId={}", userId);
        when(userRepository.findTokensValidAfterById(userId)).thenReturn(Optional.of(resetInstant));

        Optional<Instant> result = cache.get(userId);

        assertThat(result)
                .as("get() must return exactly what the repository returned on a miss")
                .contains(resetInstant);
    }

    @Test
    @DisplayName("get caches an empty Optional (never-reset user) so the repository is hit only once")
    void should_cacheEmptyOptional_when_userNeverReset() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: repository reports no reset has ever occurred for userId={}", userId);
        when(userRepository.findTokensValidAfterById(userId)).thenReturn(Optional.empty());

        Optional<Instant> first = cache.get(userId);
        Optional<Instant> second = cache.get(userId);

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        // This is the whole point of caching Optional rather than a bare nullable value:
        // Caffeine's Cache#get(key, mappingFunction) does not cache a null return, which
        // would otherwise defeat the cache for every user who has never reset a password.
        verify(userRepository, times(1))
                .findTokensValidAfterById(userId);
    }

    @Test
    @DisplayName("get hits the repository only once across repeated calls for the same user (read-through cache)")
    void should_hitRepositoryOnlyOnce_when_getCalledRepeatedlyForSameUser() {
        var userId = UUID.randomUUID();
        Instant resetInstant = Instant.parse("2025-06-01T12:00:00Z");
        when(userRepository.findTokensValidAfterById(userId)).thenReturn(Optional.of(resetInstant));

        cache.get(userId);
        cache.get(userId);
        cache.get(userId);

        verify(userRepository, times(1))
                .findTokensValidAfterById(userId);
    }

    @Test
    @DisplayName("invalidate evicts the cached entry so the next get re-reads the repository")
    void should_reReadRepository_when_invalidateCalledAfterCachedGet() {
        var userId = UUID.randomUUID();
        Instant firstValue = Instant.parse("2025-06-01T12:00:00Z");
        Instant secondValue = Instant.parse("2025-06-02T08:30:00Z");
        log.debug("Arrange: repository returns firstValue, then secondValue after invalidate, for userId={}", userId);
        when(userRepository.findTokensValidAfterById(userId))
                .thenReturn(Optional.of(firstValue))
                .thenReturn(Optional.of(secondValue));

        Optional<Instant> beforeInvalidate = cache.get(userId);
        cache.invalidate(userId);
        Optional<Instant> afterInvalidate = cache.get(userId);

        assertThat(beforeInvalidate).contains(firstValue);
        assertThat(afterInvalidate)
                .as("after invalidate(), the very next get() must re-read the fresh DB value "
                        + "rather than serve the stale cached one — this is the mechanism "
                        + "PasswordResetService relies on to make a reset take effect immediately")
                .contains(secondValue);
        verify(userRepository, times(2))
                .findTokensValidAfterById(userId);
    }

    @Test
    @DisplayName("invalidate does not affect the cached entry of a different user")
    void should_notAffectOtherUsers_when_invalidatingOneUser() {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        Instant valueA = Instant.parse("2025-06-01T12:00:00Z");
        Instant valueB = Instant.parse("2025-06-03T00:00:00Z");
        when(userRepository.findTokensValidAfterById(userA)).thenReturn(Optional.of(valueA));
        when(userRepository.findTokensValidAfterById(userB)).thenReturn(Optional.of(valueB));

        cache.get(userA);
        cache.get(userB);
        cache.invalidate(userA);
        cache.get(userA);
        cache.get(userB);

        // userA was re-read after invalidation (2 calls); userB's cached entry was
        // untouched (still just 1 call).
        verify(userRepository, times(2)).findTokensValidAfterById(userA);
        verify(userRepository, times(1)).findTokensValidAfterById(userB);
    }
}
