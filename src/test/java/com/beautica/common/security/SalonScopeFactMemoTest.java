package com.beautica.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SalonScopeFactMemo} (2026-09-13 cycle-3 audit, A7).
 *
 * <h2>Why this class exists</h2>
 * <p>{@code AuthorizationServiceTest} instantiates the real memo, but with NO request bound to the
 * thread — so every {@code recall} there takes the {@code request == null} fallback and the
 * memoisation path is never executed by any unit test. The only other coverage is
 * {@code MasterServiceBandEditIT}'s statement-count bound, which can tell that FEWER statements ran
 * but cannot tell a hit from a key COLLISION: two facts sharing a key would also lower the count,
 * while silently handing one pair's answer to another pair — the one failure mode that would make
 * this class a security bug rather than a missed optimisation.</p>
 *
 * <p>Each test binds its own {@link MockHttpServletRequest}, so "one request" is a real request
 * scope here and not a stand-in for one.</p>
 */
@DisplayName("SalonScopeFactMemo — per-request fact memoisation")
class SalonScopeFactMemoTest {

    private final SalonScopeFactMemo memo = new SalonScopeFactMemo();

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A loader that records how many times it was actually invoked. */
    private record CountingLoader(AtomicInteger calls, boolean answer) implements BooleanSupplier {
        static CountingLoader returning(boolean answer) {
            return new CountingLoader(new AtomicInteger(), answer);
        }

        @Override
        public boolean getAsBoolean() {
            calls.incrementAndGet();
            return answer;
        }

        int callCount() {
            return calls.get();
        }
    }

    // ── miss → load, hit → no load ───────────────────────────────────────────────

    @Test
    @DisplayName("the FIRST ownsSalon call for a pair loads (a miss) and returns the loaded answer")
    void should_invokeTheLoader_when_theFactHasNotBeenReadInThisRequest() {
        bindRequest();
        UUID salonId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CountingLoader loader = CountingLoader.returning(true);

        boolean answer = memo.ownsSalon(salonId, actorId, loader);

        assertThat(answer).isTrue();
        assertThat(loader.callCount()).as("a miss must read through").isEqualTo(1);
    }

    @Test
    @DisplayName("a SECOND ownsSalon call for the same pair is served from the memo — the loader "
            + "is not invoked again")
    void should_notInvokeTheLoaderTwice_when_theSameOwnerFactIsReadTwiceInOneRequest() {
        bindRequest();
        UUID salonId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CountingLoader loader = CountingLoader.returning(true);

        memo.ownsSalon(salonId, actorId, loader);
        boolean second = memo.ownsSalon(salonId, actorId, loader);

        assertThat(second).as("the memoised answer must be the loaded one").isTrue();
        assertThat(loader.callCount())
                .as("the whole point: one read per request per pair")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a memoised FALSE is a hit too — the memo must not re-read a negative answer")
    void should_memoiseANegativeAnswer_when_theFactIsFalse() {
        bindRequest();
        UUID salonId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CountingLoader loader = CountingLoader.returning(false);

        memo.ownsSalon(salonId, actorId, loader);
        boolean second = memo.ownsSalon(salonId, actorId, loader);

        assertThat(second).isFalse();
        assertThat(loader.callCount())
                .as("a null-vs-FALSE confusion in recall() would re-read every denied fact")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("masterInSalon memoises independently of ownsSalon, with the same hit/miss shape")
    void should_memoiseTheMembershipFact_when_readTwiceInOneRequest() {
        bindRequest();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        CountingLoader loader = CountingLoader.returning(true);

        memo.masterInSalon(masterId, salonId, loader);
        memo.masterInSalon(masterId, salonId, loader);

        assertThat(loader.callCount()).isEqualTo(1);
    }

    // ── key isolation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the owner: and member: prefixes never collide — ownsSalon(x, y) and "
            + "masterInSalon(x, y) are different facts about the same two ids")
    void should_keepTheTwoFactKindsApart_when_theSameTwoIdsAreUsedForBoth() {
        bindRequest();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountingLoader ownerLoader = CountingLoader.returning(true);
        CountingLoader memberLoader = CountingLoader.returning(false);

        boolean owns = memo.ownsSalon(first, second, ownerLoader);
        boolean member = memo.masterInSalon(first, second, memberLoader);

        assertThat(owns).as("the owner fact's OWN answer").isTrue();
        assertThat(member)
                .as("a shared key would hand the owner fact's TRUE to the membership question")
                .isFalse();
        assertThat(memberLoader.callCount())
                .as("the membership fact must have been read, not recalled from the owner entry")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("argument ORDER is part of the key — ownsSalon(a, b) never answers ownsSalon(b, a)")
    void should_keepThePairOrdered_when_theSameTwoIdsAreSwapped() {
        bindRequest();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CountingLoader straight = CountingLoader.returning(true);
        CountingLoader swapped = CountingLoader.returning(false);

        memo.ownsSalon(a, b, straight);
        boolean swappedAnswer = memo.ownsSalon(b, a, swapped);

        assertThat(swappedAnswer)
                .as("\"does salon A have owner B\" is not \"does salon B have owner A\"")
                .isFalse();
        assertThat(swapped.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("different pairs are memoised separately — a second actor on the same salon is "
            + "its own fact")
    void should_memoisePerPair_when_twoActorsAskAboutTheSameSalon() {
        bindRequest();
        UUID salonId = UUID.randomUUID();
        CountingLoader ownerLoader = CountingLoader.returning(true);
        CountingLoader strangerLoader = CountingLoader.returning(false);

        memo.ownsSalon(salonId, UUID.randomUUID(), ownerLoader);
        boolean stranger = memo.ownsSalon(salonId, UUID.randomUUID(), strangerLoader);

        assertThat(stranger)
                .as("memoising by salon alone would grant the first actor's ownership to anyone")
                .isFalse();
    }

    // ── the MAX_ENTRIES cap ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the per-request map is capped at 16 entries: the first 16 distinct facts are "
            + "memoised, the 17th is read through EVERY time")
    void should_stopMemoising_when_theRequestHasAlreadyCached16Facts() {
        bindRequest();
        UUID salonId = UUID.randomUUID();

        // Fill the cap with 16 distinct (salon, actor) pairs.
        for (int i = 0; i < 16; i++) {
            memo.ownsSalon(salonId, UUID.randomUUID(), CountingLoader.returning(true));
        }

        UUID overflowActor = UUID.randomUUID();
        CountingLoader overflowLoader = CountingLoader.returning(true);
        memo.ownsSalon(salonId, overflowActor, overflowLoader);
        memo.ownsSalon(salonId, overflowActor, overflowLoader);

        assertThat(overflowLoader.callCount())
                .as("past the cap the memo degrades to a plain read — it must NOT evict an "
                        + "existing entry to make room, and must NOT grow past 16")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("cap non-vacuity: the SAME 17th pair IS memoised when only 15 facts precede it — "
            + "the cap is what stops it, not the pair")
    void should_stillMemoiseTheSamePair_when_theRequestIsBelowTheCap() {
        bindRequest();
        UUID salonId = UUID.randomUUID();

        for (int i = 0; i < 15; i++) {
            memo.ownsSalon(salonId, UUID.randomUUID(), CountingLoader.returning(true));
        }

        UUID actorId = UUID.randomUUID();
        CountingLoader loader = CountingLoader.returning(true);
        memo.ownsSalon(salonId, actorId, loader);
        memo.ownsSalon(salonId, actorId, loader);

        assertThat(loader.callCount()).isEqualTo(1);
    }

    // ── off-servlet-thread degradation ───────────────────────────────────────────

    @Test
    @DisplayName("with NO request bound the memo degrades to a plain uncached read — never an "
            + "IllegalStateException, because AuthorizationService is also reached by direct "
            + "service calls")
    void should_readThroughWithoutCaching_when_noRequestIsBoundToTheThread() {
        // Deliberately no bindRequest() — this is the integration-test / non-servlet call path.
        UUID salonId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CountingLoader loader = CountingLoader.returning(true);

        boolean first = memo.ownsSalon(salonId, actorId, loader);
        boolean second = memo.ownsSalon(salonId, actorId, loader);

        assertThat(first).isTrue();
        assertThat(second).as("still CORRECT, merely uncached").isTrue();
        assertThat(loader.callCount())
                .as("a pure optimisation must never turn a working off-thread call into a 500")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("memo state does not leak between requests — a fact memoised in one request is "
            + "re-read in the next")
    void should_notShareFactsAcrossRequests_when_asecondRequestAsksTheSameQuestion() {
        UUID salonId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CountingLoader loader = CountingLoader.returning(true);

        bindRequest();
        memo.ownsSalon(salonId, actorId, loader);
        RequestContextHolder.resetRequestAttributes();

        bindRequest();
        memo.ownsSalon(salonId, actorId, loader);

        assertThat(loader.callCount())
                .as("the request ending IS the eviction — cross-request reuse would be a staleness "
                        + "window this class is shaped to not have")
                .isEqualTo(2);
    }
}
