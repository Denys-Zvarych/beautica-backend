package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.enums.BookingPartition;
import com.beautica.booking.service.BookingService;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA suite for Phase 322 (backend, {@code docs/backend-phases/phase-322-salon-bookings-partition.md}):
 * the {@code ?partition=} parameter on {@code GET /bookings/salon/{salonId}}, which is the read the
 * mobile salon «Архів» page (mobile phases 342–344) is built on. Exercised over the FULL HTTP stack
 * — real Spring Security (so the {@code @PreAuthorize} SpEL role gate AND
 * {@code @authz.canManageSalon} both actually run), a real {@link BookingService}, and a real
 * Postgres Testcontainers instance — with a FIXED {@link Clock} bean override so the
 * {@code UPCOMING}/{@code PAST} boundary is deterministic rather than racing the wall clock.
 *
 * <p><b>Fixed-clock isolation.</b> {@link FrozenClockConfig} overrides the {@code systemClock} bean
 * ONLY for this class's Spring context. Spring's test-context cache keys on the exact set of
 * {@code @Import}s, so {@link BookingSalonBookingsIT} — which imports {@code TestSecurityConfig}
 * alone and depends on the real {@code Clock} — gets a genuinely separate cached
 * {@code ApplicationContext} and never sees this frozen clock. The same mechanism
 * {@link BookingMyBookingsPartitionIT} already relies on.
 *
 * <p><b>What this suite deliberately does NOT do: edit {@link BookingSalonBookingsIT}.</b> That
 * suite's 30 tests never send {@code partition}, and Phase 322's D2 makes their staying green
 * UNEDITED the proof that an absent {@code partition} is byte-identical to pre-322 behaviour. The
 * service-tier device that guarantees it is the preserved 8-argument
 * {@code BookingService#getSalonBookings} overload, which delegates with {@code partition = null}
 * and reaches the untouched {@code findIdsBySalonIdFiltered} — so no call site, production or test,
 * changed. If anything in that file had needed editing, the change would have been wrong.
 *
 * <p>Covers:
 * <ol>
 *   <li><b>Total, disjoint cover</b> — {@code UPCOMING + PAST + CANCELLED} partition the salon's
 *       rows exactly, over an eight-row fixture seeding all five statuses on both sides of
 *       {@link #NOW}.</li>
 *   <li><b>{@code AWAITING_CLOSURE}</b> — the named arm of {@code PAST}, asserted as a STRICT
 *       subset on this route rather than left to the accepted-constants smoke test.</li>
 *   <li><b>{@code HISTORY}</b> — the archive's actual read: exactly {@code PAST ∪ CANCELLED}, the
 *       exact complement of {@code UPCOMING}.</li>
 *   <li><b>Salon-WIDE</b> — history from two different masters comes back on one page. This is what
 *       makes it a salon archive rather than a per-master one, and it is the property
 *       {@code findIdsByMasterIdFilteredByPartition} structurally cannot provide.</li>
 *   <li><b>{@code masterId} composes as AND</b>, including the cross-salon negative.</li>
 *   <li><b>Precedence</b> — {@code status} alongside {@code partition} is IGNORED, proven by a
 *       RESULT-SET assertion with a {@code status} that would change the answer if honoured.</li>
 *   <li><b>The {@code salon_id} SNAPSHOT still scopes the read</b> under partition (rotated
 *       master) — the predicate swap must not have pulled in {@code salonIdIn}'s master-join
 *       shape.</li>
 *   <li><b>Frozen-clock boundary</b>, including {@code endsAt == now}, and that ONE {@code now}
 *       answers both the partition membership and the row's own {@code awaitingClosure} flag —
 *       asserted twice over: once at the wire level under the frozen clock, and once FALSIFIABLY
 *       under an {@link ArmableClock} that advances on every read, which is the only shape in which
 *       "one read, not two" is a statement a test can be wrong about.</li>
 *   <li><b>Composition</b> with {@code serviceId} + date range, and <b>pagination</b> under the
 *       negated {@code HISTORY} predicate.</li>
 *   <li><b>Authorization</b> — owner ✅, assigned admin ✅; foreign owner, foreign admin,
 *       {@code SALON_MASTER} and {@code CLIENT} all 403; anonymous 401.</li>
 *   <li><b>Unrecognised enum</b> — 400 that does not echo the accepted constants.</li>
 *   <li><b>Statement count</b> — the fixed prelude of a {@code HISTORY} page, pinned absolutely,
 *       with the batch-fetch reasoning behind the no-scaling claim recorded on the constant rather
 *       than implied by the fixture size.</li>
 * </ol>
 */
@Import({TestSecurityConfig.class, BookingSalonBookingsPartitionIT.FrozenClockConfig.class})
@DisplayName("GET /bookings/salon/{salonId}?partition= — Phase 322, full HTTP stack over real "
        + "Postgres, fixed clock")
class BookingSalonBookingsPartitionIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";

    /** Frozen instant every request in this class sees as "now" — see the class javadoc. */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");

    /**
     * How far {@link ArmableClock} jumps on each successive read once armed. Two hours: large enough
     * that a booking placed one hour past {@link #NOW} sits UNAMBIGUOUSLY between read #1 and read #2
     * at {@code timestamptz}'s microsecond resolution, and small enough that no other date-dependent
     * fixture invariant in this class can notice.
     */
    private static final Duration ADVANCE_PER_READ = Duration.ofHours(2);

    /**
     * The ONE clock bean for this class's context. FROZEN at {@link #NOW} by default — every read,
     * on every thread, returns exactly {@code NOW}, so it is indistinguishable from the
     * {@code Clock.fixed} it replaces and the other tests in this class are unaffected.
     *
     * <p>{@link #armOnCurrentThread()} turns it into a PER-READ-ADVANCING clock, but only for the
     * calling thread — see that method's javadoc for why the thread scope is load-bearing and
     * {@link #should_readTheClockOnceForTheWholePage_when_partitionIsSupplied()} for what it buys.
     */
    private static final ArmableClock CLOCK = new ArmableClock(NOW.toInstant(), ZoneOffset.UTC);

    @TestConfiguration
    static class FrozenClockConfig {
        @Bean
        Clock systemClock() {
            return CLOCK;
        }
    }

    /**
     * A {@link Clock} that is a drop-in replacement for {@code Clock.fixed(NOW, UTC)} until it is
     * explicitly armed, and then advances by a fixed step on EVERY read — but only on the one thread
     * that armed it.
     *
     * <p><b>Why per-read advancing at all.</b> Under a fixed clock, {@code
     * BookingService#resolveNow()} (which is {@code OffsetDateTime.ofInstant(clock.instant(), UTC)})
     * returns the same value however many times it is called, so "the partition boundary and the
     * {@code awaitingClosure} flag come from ONE resolveNow()" is unfalsifiable: a second
     * {@code resolveNow()} on that path would keep every frozen-clock assertion green. Advancing on
     * each read makes read #1 and read #2 observably different instants, so a row parked between
     * them lands on opposite sides depending on how many times the clock was consulted.
     *
     * <p><b>Why the advance is scoped to the arming THREAD.</b> This is the application-wide
     * {@code systemClock} bean. {@code NotificationOutboxReclaimJob} runs on a 5-minute
     * {@code @Scheduled} timer inside this very context, and {@code JwtTokenProvider} reads the clock
     * on every Tomcat request thread. A globally-advancing clock would let one of those stray reads
     * bump the counter mid-test and shift which instant {@code resolveNow()} observes — a flake by
     * construction. Frozen for every other thread, the read sequence the assertion depends on is
     * exactly the one the test itself drives, which is also why the {@code one resolveNow} test calls
     * {@link BookingService} DIRECTLY instead of over HTTP: on the test thread there are no filter or
     * token reads in between. (The HTTP-level frozen-clock twin of that invariant stays right where
     * it is — see {@link #should_useOneNowForBothPartitionAndAwaitingClosure()}.)
     *
     * <p>{@code withZone} keeps the real {@link Clock#withZone} contract rather than returning
     * {@code this}: six production constructors build a {@code clock.withZone(TimeZones.KYIV)}
     * derivative at bean-construction time, and handing them a UTC-zoned clock would quietly break
     * every Kyiv day-boundary computation in the context. The derivative shares this clock's read
     * counter, because it IS this clock.
     */
    static final class ArmableClock extends Clock {

        private final Instant base;
        private final ZoneId zone;
        private final AtomicInteger reads = new AtomicInteger();
        private volatile Thread armedThread;

        private ArmableClock(Instant base, ZoneId zone) {
            this.base = base;
            this.zone = zone;
        }

        @Override
        public Instant instant() {
            // Frozen for every thread but the armed one — including when nothing is armed at all.
            if (Thread.currentThread() != armedThread) {
                return base;
            }
            return base.plus(ADVANCE_PER_READ.multipliedBy(reads.getAndIncrement()));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            if (other.equals(zone)) {
                return this;
            }
            ArmableClock self = this;
            return new Clock() {
                @Override
                public Instant instant() {
                    return self.instant();
                }

                @Override
                public ZoneId getZone() {
                    return other;
                }

                @Override
                public Clock withZone(ZoneId another) {
                    return self.withZone(another);
                }
            };
        }

        /** Arms the advance for the CALLING thread and resets its read counter to zero. */
        void armOnCurrentThread() {
            reads.set(0);
            armedThread = Thread.currentThread();
        }

        /** Back to a plain fixed clock for every thread — the default state. */
        void freeze() {
            armedThread = null;
            reads.set(0);
        }

        /** How many reads the armed thread has taken since {@link #armOnCurrentThread()}. */
        int reads() {
            return reads.get();
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private EntityManagerFactory emf;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    /**
     * The clock bean is context-scoped and therefore shared by every test in this class, so it is
     * returned to its default FROZEN state after each one — the one test that arms it must not be able
     * to leak an advancing clock into whatever runs next, whatever order JUnit picks.
     */
    @AfterEach
    void refreezeClock() {
        CLOCK.freeze();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1 — total, disjoint cover over the salon's own rows
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("UPCOMING/PAST/CANCELLED is a total, disjoint cover of the SALON's rows; "
            + "endsAt==now lands only in UPCOMING; a COMPLETED booking with a future endsAt lands "
            + "in PAST; another salon's rows never leak in")
    void should_partitionBeTotalAndDisjoint_when_salonHasAllFiveStatusesAroundNow() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-cover-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-cover-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        Fixture fx = seedEightRowFixture(clientId, salon.masterId(), serviceId, salon.salonId());

        // Noise: a second salon's full history must never appear on this salon's pages.
        BookingTestFixtures.SalonFixture otherSalon =
                fixtures.createSalon("bsbp-cover-other-" + System.nanoTime() + "@beautica.test");
        UUID otherServiceId = fixtures.createSalonService(otherSalon.salonId(), otherSalon.masterId());
        UUID otherSalonCompleted = insertBooking(clientId, otherSalon.masterId(), otherServiceId,
                otherSalon.salonId(), "COMPLETED", NOW.minusHours(5), NOW.minusHours(4));

        String token = fixtures.tokenFor(salon.ownerEmail());

        assertThat(totalElements(call(token, salon.salonId(), null, null, null, null, null, null)))
                .as("sanity: 8 rows seeded at this salon, unfiltered count must be 8")
                .isEqualTo(8);

        Set<UUID> upcoming = idSet(call(token, salon.salonId(), "UPCOMING", null, null, null, null, null));
        Set<UUID> past = idSet(call(token, salon.salonId(), "PAST", null, null, null, null, null));
        Set<UUID> cancelled = idSet(call(token, salon.salonId(), "CANCELLED", null, null, null, null, null));

        assertThat(upcoming)
                .as("UPCOMING = CONFIRMED not-yet-elapsed, including the endsAt==now boundary row")
                .containsExactlyInAnyOrder(fx.confirmedUpcoming, fx.confirmedBoundary);
        assertThat(past)
                .as("PAST = COMPLETED/NOT_COMPLETED (any endsAt) + elapsed unclosed CONFIRMED")
                .containsExactlyInAnyOrder(fx.confirmedElapsed, fx.completedElapsed,
                        fx.completedFutureEnds, fx.notCompleted);
        assertThat(cancelled)
                .as("CANCELLED = CANCELLED/DECLINED regardless of endsAt")
                .containsExactlyInAnyOrder(fx.cancelled, fx.declined);

        assertThat(upcoming).doesNotContainAnyElementsOf(past);
        assertThat(upcoming).doesNotContainAnyElementsOf(cancelled);
        assertThat(past).doesNotContainAnyElementsOf(cancelled);
        assertThat(upcoming.size() + past.size() + cancelled.size())
                .as("total, disjoint cover: the three partitions sum to the unfiltered total")
                .isEqualTo(8);
        assertThat(past)
                .as("the other salon's PAST-shaped booking must not leak across the salon_id scope")
                .doesNotContain(otherSalonCompleted);
    }

    @Test
    @DisplayName("partition=AWAITING_CLOSURE on the SALON route selects exactly the elapsed unclosed "
            + "CONFIRMED row — a STRICT, non-empty subset of PAST, never PAST itself, and never the "
            + "endsAt==now row that has not elapsed yet")
    void should_returnOnlyElapsedUnclosedConfirmed_when_partitionIsAwaitingClosure() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-awaiting-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-awaiting-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        Fixture fx = seedEightRowFixture(clientId, salon.masterId(), serviceId, salon.salonId());

        String token = fixtures.tokenFor(salon.ownerEmail());
        Set<UUID> awaiting =
                idSet(call(token, salon.salonId(), "AWAITING_CLOSURE", null, null, null, null, null));
        Set<UUID> past = idSet(call(token, salon.salonId(), "PAST", null, null, null, null, null));

        assertThat(awaiting)
                .as("AWAITING_CLOSURE is BookingClosureRule.awaitingClosure(now) — a CONFIRMED booking "
                        + "whose endsAt has elapsed and which no provider has closed; the COMPLETED and "
                        + "NOT_COMPLETED rows are already closed and must not appear")
                .containsExactly(fx.confirmedElapsed);
        assertThat(past)
                .as("and it is a SUBSET of PAST, which is what makes it a named arm of PAST rather than "
                        + "a fourth disjoint partition")
                .containsAll(awaiting);
        assertThat(past.size())
                .as("a STRICT subset: PAST also carries the closed rows, so a route that aliased "
                        + "AWAITING_CLOSURE onto PAST — or onto the whole HISTORY union — turns this red "
                        + "instead of passing on an accidental equality")
                .isGreaterThan(awaiting.size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 — HISTORY: the archive's actual read
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partition=HISTORY returns exactly PAST ∪ CANCELLED and is the exact complement of "
            + "UPCOMING — including the elapsed-unclosed CONFIRMED row, excluding the not-yet-elapsed one")
    void should_returnUnionOfPastAndCancelled_when_partitionIsHistory() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-history-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-history-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        Fixture fx = seedEightRowFixture(clientId, salon.masterId(), serviceId, salon.salonId());

        String token = fixtures.tokenFor(salon.ownerEmail());

        Set<UUID> history = idSet(call(token, salon.salonId(), "HISTORY", null, null, null, null, null));
        Set<UUID> upcoming = idSet(call(token, salon.salonId(), "UPCOMING", null, null, null, null, null));
        Set<UUID> past = idSet(call(token, salon.salonId(), "PAST", null, null, null, null, null));
        Set<UUID> cancelled = idSet(call(token, salon.salonId(), "CANCELLED", null, null, null, null, null));

        assertThat(history)
                .as("HISTORY must be exactly PAST ∪ CANCELLED for this fixture")
                .containsExactlyInAnyOrderElementsOf(union(past, cancelled));
        assertThat(history)
                .as("an elapsed CONFIRMED (awaiting-closure) booking IS in the salon's HISTORY — a "
                        + "naive 'everything except CONFIRMED' implementation would drop it")
                .contains(fx.confirmedElapsed);
        assertThat(history)
                .as("a not-yet-elapsed CONFIRMED booking, endsAt==now included, is NOT in HISTORY")
                .doesNotContain(fx.confirmedUpcoming, fx.confirmedBoundary);
        assertThat(history).as("HISTORY ∩ UPCOMING = ∅").doesNotContainAnyElementsOf(upcoming);
        assertThat(history.size() + upcoming.size())
                .as("complement property: count(HISTORY) + count(UPCOMING) == count(unfiltered)")
                .isEqualTo(8);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3 — the SALON-specific property: history spans EVERY master in the salon
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partition=HISTORY spans every master in the salon on ONE page — this is what makes "
            + "it a SALON archive and not a per-master one, and it is the property "
            + "findIdsByMasterIdFilteredByPartition structurally cannot provide")
    void should_spanEveryMasterInTheSalon_when_partitionIsHistory() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-multimaster-" + System.nanoTime() + "@beautica.test");
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID thirdMasterId = createExtraSalonMaster(salon.salonId());
        UUID clientId = fixtures.createUser("bsbp-multimaster-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), secondMasterId);
        UUID serviceC = fixtures.createSalonService(salon.salonId(), thirdMasterId);

        UUID masterAHistory = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "COMPLETED", NOW.minusDays(3), NOW.minusDays(3).plusHours(1));
        UUID masterBHistory = insertBooking(clientId, secondMasterId, serviceB, salon.salonId(),
                "CANCELLED", NOW.minusDays(2), NOW.minusDays(2).plusHours(1));
        // The third master contributes via the elapsed-unclosed CONFIRMED arm, so the page is not
        // provably salon-wide only for the two simplest status shapes.
        UUID masterCHistory = insertBooking(clientId, thirdMasterId, serviceC, salon.salonId(),
                "CONFIRMED", NOW.minusDays(1), NOW.minusDays(1).plusHours(1));
        // Each master also has an UPCOMING row that must stay off the archive page.
        UUID masterAUpcoming = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "CONFIRMED", NOW.plusDays(1), NOW.plusDays(1).plusHours(1));

        JsonNode resp = call(fixtures.tokenFor(salon.ownerEmail()), salon.salonId(), "HISTORY",
                null, null, null, null, null);

        assertThat(idSet(resp))
                .as("one HISTORY page must carry all three masters' history and none of the upcoming rows")
                .containsExactlyInAnyOrder(masterAHistory, masterBHistory, masterCHistory)
                .doesNotContain(masterAUpcoming);
        assertThat(totalElements(resp)).isEqualTo(3);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4 — masterId composes with partition as AND (D5), plus its cross-salon negative
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("masterId AND partition — an owner who filtered the board to one master and then "
            + "opens «Архів» gets THAT master's history, not the salon's")
    void should_narrowToOneMaster_when_masterIdAndPartitionBothSupplied() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-masterand-" + System.nanoTime() + "@beautica.test");
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID clientId = fixtures.createUser("bsbp-masterand-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), secondMasterId);

        UUID firstMasterHistory = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "COMPLETED", NOW.minusDays(3), NOW.minusDays(3).plusHours(1));
        UUID secondMasterHistory = insertBooking(clientId, secondMasterId, serviceB, salon.salonId(),
                "COMPLETED", NOW.minusDays(2), NOW.minusDays(2).plusHours(1));
        UUID firstMasterUpcoming = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "CONFIRMED", NOW.plusDays(2), NOW.plusDays(2).plusHours(1));

        JsonNode resp = call(fixtures.tokenFor(salon.ownerEmail()), salon.salonId(), "HISTORY",
                salon.masterId(), null, null, null, null);

        assertThat(idSet(resp))
                .as("masterId must AND with partition — never replace it, never be ignored by it")
                .containsExactly(firstMasterHistory)
                .doesNotContain(secondMasterHistory, firstMasterUpcoming);
    }

    @Test
    @DisplayName("a masterId belonging to ANOTHER salon matches nothing under partition rather than "
            + "leaking a cross-salon existence oracle — the pre-322 contract, now under partition")
    void should_returnEmpty_when_masterIdBelongsToAnotherSalonUnderPartition() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-foreignmaster-" + System.nanoTime() + "@beautica.test");
        BookingTestFixtures.SalonFixture otherSalon =
                fixtures.createSalon("bsbp-foreignmaster-other-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-foreignmaster-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "COMPLETED", NOW.minusDays(3), NOW.minusDays(3).plusHours(1));

        JsonNode resp = call(fixtures.tokenFor(salon.ownerEmail()), salon.salonId(), "HISTORY",
                otherSalon.masterId(), null, null, null, null);

        assertThat(idSet(resp))
                .as("a foreign masterId must yield an empty page, never a 403/404 that leaks existence")
                .isEmpty();
        assertThat(totalElements(resp)).isZero();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5 — precedence (D3): status is IGNORED, never a 400
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("?partition=HISTORY&status=CONFIRMED — status is IGNORED, partition wins (200, not "
            + "400), and the RESULT SET proves it: the supplied status would change the answer")
    void should_ignoreStatusParam_when_partitionAlsoSupplied() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-precedence-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-precedence-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());

        // status=CONFIRMED alone would return the upcoming row and NOTHING else; partition=HISTORY
        // must return the cancelled + completed rows and NOT the upcoming one. The two answers are
        // disjoint, so neither an AND, an OR, nor a silently-honoured status can pass this.
        UUID confirmedUpcoming = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "CONFIRMED", NOW.plusHours(2), NOW.plusHours(3));
        UUID completed = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "COMPLETED", NOW.minusHours(5), NOW.minusHours(4));
        UUID cancelled = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "CANCELLED", NOW.plusHours(10), NOW.plusHours(11));

        String token = fixtures.tokenFor(salon.ownerEmail());

        assertThat(idSet(call(token, salon.salonId(), null, null, List.of("CONFIRMED"), null, null, null)))
                .as("premise — status=CONFIRMED alone returns exactly the upcoming row, so honouring "
                        + "it alongside partition=HISTORY could not possibly produce the same answer")
                .containsExactly(confirmedUpcoming);

        JsonNode resp = call(token, salon.salonId(), "HISTORY", null, List.of("CONFIRMED"), null, null, null);

        assertThat(idSet(resp))
                .as("partition=HISTORY must win over status=CONFIRMED — never a 400, never the "
                        + "intersection (which would be empty) nor the union (which would add the "
                        + "upcoming row)")
                .containsExactlyInAnyOrder(completed, cancelled)
                .doesNotContain(confirmedUpcoming);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6 — the salon_id SNAPSHOT still scopes the read under partition
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a rotated master's historical booking still appears in its ORIGINAL salon's "
            + "HISTORY — the partition predicate is ANDed onto bookingSalonIdEquals (the booking's "
            + "own salon_id snapshot), never onto salonIdIn's live master-join shape")
    void should_stillReturnBooking_when_masterHasSinceRotatedToAnotherSalon_underPartition() throws Exception {
        BookingTestFixtures.SalonFixture salonA =
                fixtures.createSalon("bsbp-rotate-a-" + System.nanoTime() + "@beautica.test");
        BookingTestFixtures.SalonFixture salonB =
                fixtures.createSalon("bsbp-rotate-b-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-rotate-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salonA.salonId(), salonA.masterId());
        UUID historicalBooking = insertBooking(clientId, salonA.masterId(), serviceId, salonA.salonId(),
                "COMPLETED", NOW.minusDays(4), NOW.minusDays(4).plusHours(1));
        // A second salon-A master who does NOT rotate, so the page cannot pass by returning
        // everything the scope predicate happens to match for an unrelated reason.
        UUID stayingMasterId = createExtraSalonMaster(salonA.salonId());
        UUID stayingService = fixtures.createSalonService(salonA.salonId(), stayingMasterId);
        UUID stayingBooking = insertBooking(clientId, stayingMasterId, stayingService, salonA.salonId(),
                "CANCELLED", NOW.minusDays(5), NOW.minusDays(5).plusHours(1));

        // masters.salon_id changes; bookings.salon_id (the snapshot) is untouched.
        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", salonB.salonId(), salonA.masterId());

        JsonNode resp = call(fixtures.tokenFor(salonA.ownerEmail()), salonA.salonId(), "HISTORY",
                null, null, null, null, null);

        assertThat(idSet(resp))
                .as("salon A's archive must not silently lose a booking just because its master has "
                        + "since moved — swapping bookingSalonIdEquals for salonIdIn turns this red")
                .containsExactlyInAnyOrder(historicalBooking, stayingBooking);

        JsonNode salonBResp = call(fixtures.tokenFor(salonB.ownerEmail()), salonB.salonId(), "HISTORY",
                null, null, null, null, null);
        assertThat(idSet(salonBResp))
                .as("and salon B must NOT inherit the booking merely by now employing its master")
                .isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7 — frozen-clock boundary, and ONE `now` for partition AND awaitingClosure (D7)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the endsAt==now boundary pair — an elapsed unclosed CONFIRMED booking is in PAST "
            + "and HISTORY; one whose endsAt is EXACTLY now is in UPCOMING and only there")
    void should_includeElapsedUnclosedConfirmedInPast_andExcludeItFromUpcoming() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-boundary-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-boundary-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        // The two boundary rows sit on DIFFERENT masters on purpose: they are one microsecond apart
        // on the endsAt axis, so on one master the no_overlapping_bookings EXCLUDE constraint (V18,
        // scoped to status = 'CONFIRMED') would reject the pair outright. A salon has many masters,
        // so this is the representative shape anyway.
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID secondService = fixtures.createSalonService(salon.salonId(), secondMasterId);

        UUID endsExactlyAtNow = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "CONFIRMED", NOW.minusHours(1), NOW);
        // One MICROsecond, not one nanosecond: timestamptz has microsecond resolution, so a
        // nanosecond offset would round back onto NOW itself and this row would silently become the
        // boundary row it exists to sit beside.
        UUID endsJustBeforeNow = insertBooking(clientId, secondMasterId, secondService, salon.salonId(),
                "CONFIRMED", NOW.minusHours(1), NOW.minusNanos(1_000));

        String token = fixtures.tokenFor(salon.ownerEmail());

        assertThat(idSet(call(token, salon.salonId(), "UPCOMING", null, null, null, null, null)))
                .as("endsAt == now is UPCOMING (the >= bound), and only there")
                .containsExactly(endsExactlyAtNow);
        assertThat(idSet(call(token, salon.salonId(), "PAST", null, null, null, null, null)))
                .as("endsAt one microsecond before now is already the elapsed-unclosed PAST arm")
                .containsExactly(endsJustBeforeNow);
        assertThat(idSet(call(token, salon.salonId(), "HISTORY", null, null, null, null, null)))
                .containsExactly(endsJustBeforeNow);
    }

    @Test
    @DisplayName("ONE resolveNow() per page, at the WIRE level under the frozen clock (the "
            + "falsifiable twin is the advancing-clock test below) — a booking selected into PAST "
            + "by the elapsed-CONFIRMED arm renders awaitingClosure:true in the SAME response, and "
            + "the endsAt==now row on the "
            + "UPCOMING page renders awaitingClosure:false: a second `now` for the partition could "
            + "put those two facts on opposite sides of the boundary")
    void should_useOneNowForBothPartitionAndAwaitingClosure() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-onenow-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-onenow-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        // Different masters — see the boundary test above for why one microsecond apart on one
        // master trips the CONFIRMED-scoped EXCLUDE constraint.
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID secondService = fixtures.createSalonService(salon.salonId(), secondMasterId);

        UUID elapsedUnclosed = insertBooking(clientId, secondMasterId, secondService, salon.salonId(),
                "CONFIRMED", NOW.minusHours(1), NOW.minusNanos(1_000));
        UUID endsExactlyAtNow = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "CONFIRMED", NOW.minusHours(1), NOW);

        String token = fixtures.tokenFor(salon.ownerEmail());

        JsonNode past = call(token, salon.salonId(), "PAST", null, null, null, null, null);
        assertThat(idSet(past)).containsExactly(elapsedUnclosed);
        assertThat(awaitingClosure(past, elapsedUnclosed))
                .as("the very row the PAST predicate selected via its elapsed-CONFIRMED arm must "
                        + "also RENDER as awaiting closure — both facts come from the same instant")
                .isTrue();

        JsonNode upcoming = call(token, salon.salonId(), "UPCOMING", null, null, null, null, null);
        assertThat(idSet(upcoming)).containsExactly(endsExactlyAtNow);
        assertThat(awaitingClosure(upcoming, endsExactlyAtNow))
                .as("the endsAt==now row is UPCOMING, so it must render awaitingClosure:false — the "
                        + "mirror image of the assertion above, against the same single instant")
                .isFalse();
    }

    @Test
    @DisplayName("ONE resolveNow() per page, FALSIFIABLY: under a clock that jumps two hours on every "
            + "read, a CONFIRMED booking ending between read #1 and read #2 is UPCOMING with "
            + "awaitingClosure:false and absent from PAST — a second resolveNow() anywhere on this "
            + "path moves it across the boundary and turns this red")
    void should_readTheClockOnceForTheWholePage_when_partitionIsSupplied() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-onereads-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID clientId = fixtures.createUser("bsbp-onereads-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());

        // endsAt parked STRICTLY BETWEEN the first read (NOW) and the second (NOW + 2h). At read #1
        // the booking has not elapsed: CONFIRMED + endsAt >= now, so UPCOMING, and not awaiting
        // closure. At read #2 it HAS elapsed: the PAST partition's elapsed-unclosed-CONFIRMED arm
        // claims it and awaitingClosure flips true. Exactly one of those two worlds can be observed
        // per response, and which one tells us how many times the clock was read.
        UUID betweenTheTwoReads = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "CONFIRMED", NOW.minusHours(1), NOW.plusHours(1));
        Pageable pageable = PageRequest.of(0, 20);

        // Armed only now — every fixture call above went through HTTP/JDBC and would otherwise have
        // burned reads on this same thread.
        CLOCK.armOnCurrentThread();
        var upcoming = bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null,
                null, BookingPartition.UPCOMING, pageable);

        // Behaviour first, read count second: the observable answer is the contract, the counter is
        // the diagnostic that says WHY it moved.
        assertThat(upcoming.data())
                .extracting(BookingDetailResponse::id)
                .as("selected against read #1, at which endsAt has not yet elapsed — a partition "
                        + "boundary resolved from read #2 would find it already past and drop it")
                .containsExactly(betweenTheTwoReads);
        assertThat(upcoming.data().getFirst().awaitingClosure())
                .as("and the SAME instant must render the flag — a separately-resolved now two hours "
                        + "later would report this UPCOMING row as awaiting closure")
                .isFalse();
        assertThat(CLOCK.reads())
                .as("the whole page must consult the clock EXACTLY ONCE; a second resolveNow() makes "
                        + "it 2, and under this clock read #2 is two hours past read #1")
                .isEqualTo(1);

        // Re-arming resets the counter, so this second page is judged from read #1 again — a fresh
        // request legitimately gets a fresh now; the invariant is about ONE request, not two.
        CLOCK.armOnCurrentThread();
        var past = bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null,
                null, BookingPartition.PAST, pageable);

        assertThat(past.data())
                .as("nothing has elapsed as of read #1, so PAST is empty — if the partition boundary "
                        + "resolved its own now it would be read #2, two hours on, and this row would "
                        + "be selected into PAST while still rendering awaitingClosure from read #1")
                .isEmpty();
        assertThat(CLOCK.reads())
                .as("same single-read budget on the PAST branch")
                .isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8 — composition with serviceId + date range
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partition ANDs correctly with serviceId and from/to on the salon route — never an "
            + "OR, never ignored")
    void should_andWithServiceIdAndDateRange_when_partitionSupplied() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-compose-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("bsbp-compose-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), salon.masterId());

        UUID inWindowServiceA = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "COMPLETED", NOW.minusDays(2).minusHours(2), NOW.minusDays(2).minusHours(1));
        UUID outOfWindowServiceA = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "COMPLETED", NOW.minusDays(10).minusHours(2), NOW.minusDays(10).minusHours(1));
        UUID inWindowServiceB = insertBooking(clientId, salon.masterId(), serviceB, salon.salonId(),
                "COMPLETED", NOW.minusDays(2).minusHours(4), NOW.minusDays(2).minusHours(3));
        // In-window, service A, but UPCOMING — excluded by the partition alone, which is what
        // proves all three predicates AND rather than OR.
        UUID inWindowUpcomingServiceA = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "CONFIRMED", NOW.minusDays(2).minusHours(6), NOW.plusDays(30));

        String fromDay = NOW.minusDays(3).toLocalDate().toString();
        String toDay = NOW.minusDays(1).toLocalDate().toString();

        JsonNode resp = call(fixtures.tokenFor(salon.ownerEmail()), salon.salonId(), "HISTORY",
                null, null, fromDay, toDay, List.of(serviceA));

        assertThat(idSet(resp))
                .as("partition=HISTORY AND from/to AND serviceId=A must return exactly the one row "
                        + "matching all three")
                .containsExactly(inWindowServiceA)
                .doesNotContain(outOfWindowServiceA, inWindowServiceB, inWindowUpcomingServiceA);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9 — pagination under the negated HISTORY predicate
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partition=HISTORY spanning 2 pages across several masters — totalElements and each "
            + "page match a ground truth computed independently in SQL, no row dropped or duplicated")
    void should_keepTotalsAndCursorCorrect_when_historyResultSpansMultiplePages() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-page-" + System.nanoTime() + "@beautica.test");
        UUID secondMasterId = createExtraSalonMaster(salon.salonId());
        UUID clientId = fixtures.createUser("bsbp-page-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), secondMasterId);

        int historyCount = 13;
        OffsetDateTime t = NOW.minusDays(30);
        for (int i = 0; i < historyCount; i++) {
            boolean firstMaster = i % 2 == 0;
            insertBooking(clientId,
                    firstMaster ? salon.masterId() : secondMasterId,
                    firstMaster ? serviceA : serviceB,
                    salon.salonId(),
                    firstMaster ? "COMPLETED" : "CANCELLED",
                    t, t.plusHours(1));
            t = t.plusDays(1);
        }
        // Noise the ground truth must exclude: a future CONFIRMED and the endsAt==now boundary row.
        insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "CONFIRMED", NOW.plusHours(1), NOW.plusHours(2));
        insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "CONFIRMED", NOW.minusHours(1), NOW);

        List<UUID> groundTruth = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE salon_id = ? AND NOT (status = 'CONFIRMED' AND ends_at >= ?) "
                        + "ORDER BY starts_at DESC, id ASC",
                UUID.class, salon.salonId(), NOW);
        assertThat(groundTruth).as("fixture sanity check").hasSize(historyCount);

        String token = fixtures.tokenFor(salon.ownerEmail());
        int pageSize = 8;
        List<UUID> collected = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            JsonNode resp = callPaged(token, salon.salonId(), "HISTORY", page, pageSize);
            assertThat(totalElements(resp))
                    .as("page %d totalElements must reflect the HISTORY-filtered count only", page)
                    .isEqualTo(historyCount);

            List<UUID> pageIds = idList(resp);
            int from = page * pageSize;
            int to = Math.min(historyCount, from + pageSize);
            assertThat(pageIds)
                    .as("page %d must equal the ground-truth slice [%d,%d)", page, from, to)
                    .containsExactlyElementsOf(groundTruth.subList(from, to));
            collected.addAll(pageIds);
        }
        assertThat(collected)
                .as("both pages together reproduce the full ground truth")
                .containsExactlyInAnyOrderElementsOf(groundTruth)
                .hasSize(historyCount);
        assertThat(new HashSet<>(collected)).hasSize(historyCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10 — authorization (D4): one arm per denied role, unchanged by the parameter
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the salon's own ASSIGNED ADMIN gets 200 on ?partition=HISTORY — the archive is an "
            + "owner/admin surface, exactly like the board it hangs off")
    void should_return200_when_assignedAdminRequestsPartition() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-admin-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "bsbp-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID clientId = fixtures.createUser("bsbp-admin-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID historical = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "COMPLETED", NOW.minusDays(3), NOW.minusDays(3).plusHours(1));

        JsonNode resp = call(fixtures.tokenFor(adminEmail), salon.salonId(), "HISTORY",
                null, null, null, null, null);

        assertThat(idSet(resp)).containsExactly(historical);
    }

    @Test
    @DisplayName("an owner of a DIFFERENT salon gets 403 on ?partition=HISTORY — a partition must "
            + "never become a way round @authz.canManageSalon")
    void should_return403_when_foreignOwnerRequestsPartition() throws Exception {
        BookingTestFixtures.SalonFixture targetSalon =
                fixtures.createSalon("bsbp-foreign-target-" + System.nanoTime() + "@beautica.test");
        String strangerEmail = "bsbp-foreign-owner-" + System.nanoTime() + "@beautica.test";
        fixtures.createSalon(strangerEmail);

        assertThat(rawCall(fixtures.tokenFor(strangerEmail), targetSalon.salonId(), "HISTORY")
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an admin assigned to a DIFFERENT salon gets 403 on ?partition=HISTORY")
    void should_return403_when_foreignAdminRequestsPartition() throws Exception {
        BookingTestFixtures.SalonFixture targetSalon =
                fixtures.createSalon("bsbp-foreign-admin-target-" + System.nanoTime() + "@beautica.test");
        BookingTestFixtures.SalonFixture otherSalon =
                fixtures.createSalon("bsbp-foreign-admin-other-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "bsbp-foreign-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", otherSalon.salonId());

        assertThat(rawCall(fixtures.tokenFor(adminEmail), targetSalon.salonId(), "HISTORY")
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a SALON_MASTER of THIS salon gets 403 on ?partition=HISTORY — deliberate: the "
            + "invited staff master reads their own archive at GET /bookings/me?partition=HISTORY, "
            + "and this route is the salon-wide owner/admin view")
    void should_return403_when_salonMasterRequestsPartition() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-staffmaster-" + System.nanoTime() + "@beautica.test");

        assertThat(rawCall(fixtures.tokenFor(salon.masterEmail()), salon.salonId(), "HISTORY")
                .getStatusCode())
                .as("the role gate must reject SALON_MASTER even for their OWN salon")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a CLIENT gets 403 on ?partition=HISTORY — the role gate rejects before "
            + "@authz.canManageSalon ever runs")
    void should_return403_when_callerIsClient() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-client-target-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "bsbp-client-caller-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        assertThat(rawCall(fixtures.tokenFor(clientEmail), salon.salonId(), "HISTORY").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("no bearer token gets 401 on ?partition=HISTORY, before @authz.canManageSalon runs")
    void should_return401_when_noToken() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-noauth-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/salon/" + salon.salonId() + "?partition=HISTORY",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11 — unrecognised enum: 400, no echo of the accepted constants (D6)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("?partition=BOGUS returns 400 (not 500), and the body does not echo the accepted "
            + "BookingPartition constants — asserted on THIS route, because \"it is handled globally\" "
            + "is exactly the kind of claim that rots")
    void should_return400WithoutEchoingEnumConstants_when_partitionParamIsUnrecognised() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-badenum-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = rawCall(fixtures.tokenFor(salon.ownerEmail()), salon.salonId(), "BOGUS");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String bodyLower = resp.getBody() == null ? "" : resp.getBody().toLowerCase();
        assertThat(bodyLower)
                .doesNotContain("upcoming")
                .doesNotContain("cancelled")
                .doesNotContain("awaiting_closure")
                .doesNotContain("history");
        // "past" is deliberately not asserted — too common an English substring to be a reliable
        // leak signal, the same carve-out BookingMyBookingsPartitionIT documents.
    }

    @Test
    @DisplayName("every one of the five BookingPartition constants is accepted (200) on this route — "
            + "parity with GET /bookings/me, not an archive-only HISTORY subset")
    void should_acceptAllFivePartitionConstants_when_requestedOnTheSalonRoute() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-allenum-" + System.nanoTime() + "@beautica.test");
        String token = fixtures.tokenFor(salon.ownerEmail());

        for (BookingPartition partition : BookingPartition.values()) {
            assertThat(rawCall(token, salon.salonId(), partition.name()).getStatusCode())
                    .as("partition=%s must be accepted on the salon route", partition)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12 — statement-count ledger for a HISTORY page spanning many masters
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Absolute JDBC statement count for a {@code partition=HISTORY} page of COMPLETED bookings
     * spanning FIVE different masters at the caller's own salon, and for the same page at one row.
     *
     * <p>DERIVED FROM A RUN, never predicted — the same rule every sibling constant in
     * {@code BookingSalonBookingsIT} and {@code BookingPriceRangeContractIT} follows. The value is
     * expected to equal {@code BookingSalonBookingsIT}'s own
     * {@code SALON_OWNER_COMPLETED_PAGE_STATEMENTS}, because the partition swap changes only the
     * ID-page predicate and nothing downstream of it: the ID page + the graph hydrate + the
     * client&rarr;provider review batch + the provider&rarr;client review batch + the discovery-label
     * city batch. Asserting it here anyway is the point — if the partition dispatch ever grew its own
     * extra round trip (a second {@code resolveNow}-driven query, a per-row closure lookup), the
     * board's gate would stay green while the archive's cost doubled.
     *
     * <p>The PER-ROW delta is asserted separately from the absolute figure so that a change moving
     * the fixed prelude is distinguishable at a glance from one regressing per-row behaviour.
     *
     * <p><b>WHAT FIVE MASTERS CAN AND CANNOT PROVE — read this before trusting the display name.</b>
     * On its own, a flat count at five masters is NOT evidence that the cost does not scale with
     * master count. Hibernate's {@code default_batch_fetch_size} is 50 ({@code application.yml}), so a
     * batch-fetch staircase cannot step below 51 parents by construction: five and one would come out
     * equal even on a path riddled with lazy associations. The absolute figure and the zero marginal
     * delta are therefore a gate against a NEW round trip appearing in the fixed prelude (a second
     * {@code resolveNow}-driven query, a per-row closure lookup, a per-row authority check of the kind
     * Phase 319 removed) — that, and only that, is what an edit here will catch.
     *
     * <p>The no-scaling claim is nonetheless TRUE, and this is the argument for it, recorded here so
     * the gate is not mistaken for the proof (established by {@code backend-perf} on the Phase 322
     * audit): <b>there is no batch-fetch participant on this path at all.</b>
     * {@code BookingRepository#findAllByIdsWithGraph} JOIN FETCHes every association the page
     * dereferences — {@code b.client}, {@code b.master m}, {@code m.user}, {@code b.salon},
     * {@code b.masterService ms}, {@code ms.serviceDefinition} — and the only association reads
     * downstream of it stay inside that graph: {@code BookingService#discoveryCityId} /
     * {@code discoveryDistrictId} read {@code salon.getCityId()} (fetched), and
     * {@code AuthorizationService#isPerformingMasterOfBooking} reads {@code master.getUser().getId()}
     * plus {@code master.isActive()} (both fetched). With ZERO lazy participants there is no staircase
     * to step, at 5 masters or at 500 — so seeding 51 masters here would buy a slower test and no new
     * information. What WOULD invalidate the claim is a new DTO field dereferencing an association
     * absent from that fetch graph, and the defence against that is to add it to
     * {@code findAllByIdsWithGraph} in the same change — not a bigger fixture in this file.
     */
    private static final long SALON_HISTORY_PAGE_STATEMENTS = 5L;

    @Test
    @DisplayName("a partition=HISTORY page costs a FIXED, absolute number of JDBC statements — 1 row "
            + "and 5 rows across 5 distinct masters cost the same, pinning the prelude against a new "
            + "round trip (NOT a proof about master count: see the constant's javadoc)")
    void should_notScaleStatementCount_when_historyPageSpansManyMasters() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("bsbp-qcount-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID clientId = fixtures.createUser(
                "bsbp-qcount-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        Pageable pageable = PageRequest.of(0, 20);
        Statistics statistics = statistics();

        seedCompletedHistoryAcrossDistinctMasters(clientId, salon, 1);
        statistics.clear();
        bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null, null,
                BookingPartition.HISTORY, pageable);
        long statementsForOneRow = statistics.getPrepareStatementCount();

        seedCompletedHistoryAcrossDistinctMasters(clientId, salon, 4);
        statistics.clear();
        var result = bookingService.getSalonBookings(ownerId, salon.salonId(), null, null, null, null, null,
                BookingPartition.HISTORY, pageable);
        long statementsForFiveRows = statistics.getPrepareStatementCount();

        assertThat(result.data())
                .as("premise — all five seeded COMPLETED rows must actually be on the HISTORY page")
                .hasSize(5);
        assertThat(statementsForFiveRows)
                .as("absolute JDBC statement count for a 5-row HISTORY page across FIVE masters")
                .isEqualTo(SALON_HISTORY_PAGE_STATEMENTS);
        assertThat(statementsForFiveRows - statementsForOneRow)
                .as("marginal cost must be ZERO — got %s statements for 1 row, %s for 5",
                        statementsForOneRow, statementsForFiveRows)
                .isZero();
    }

    /** Adds {@code count} COMPLETED, well-elapsed bookings, each by its OWN new salon master. */
    private void seedCompletedHistoryAcrossDistinctMasters(
            UUID clientId, BookingTestFixtures.SalonFixture salon, int count) {
        for (int i = 0; i < count; i++) {
            UUID masterId = createExtraSalonMaster(salon.salonId());
            UUID serviceId = fixtures.createSalonService(salon.salonId(), masterId);
            insertBooking(clientId, masterId, serviceId, salon.salonId(), "COMPLETED",
                    NOW.minusDays(20 + i), NOW.minusDays(20 + i).plusHours(1));
        }
    }

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    // ── fixture record + seeding ────────────────────────────────────────────────────────────────

    private record Fixture(
            UUID confirmedUpcoming, UUID confirmedBoundary, UUID confirmedElapsed,
            UUID completedElapsed, UUID completedFutureEnds, UUID notCompleted,
            UUID cancelled, UUID declined) {
    }

    /**
     * Seeds one booking per {@code BookingStatus} plus a second {@code CONFIRMED} row, spread on both
     * sides of {@link #NOW} — the same eight-row shape {@link BookingMyBookingsPartitionIT} uses, so
     * the two routes' partition assignments are comparable row for row. Only the three
     * {@code CONFIRMED} rows need non-overlapping windows (the {@code no_overlapping_bookings}
     * EXCLUDE constraint scopes to {@code status = 'CONFIRMED'} only).
     */
    private Fixture seedEightRowFixture(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID confirmedUpcoming = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.plusHours(2), NOW.plusHours(3));
        UUID confirmedBoundary = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.minusHours(1), NOW);
        UUID confirmedElapsed = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        UUID completedElapsed = insertBooking(clientId, masterId, masterServiceId, salonId,
                "COMPLETED", NOW.minusHours(5), NOW.minusHours(4));
        // Option-A hole: BookingTemporalGuard.assertElapsedForComplete gates completion on startsAt,
        // not endsAt, so a COMPLETED booking's endsAt can legitimately sit in the future.
        UUID completedFutureEnds = insertBooking(clientId, masterId, masterServiceId, salonId,
                "COMPLETED", NOW.minusHours(6), NOW.plusHours(5));
        UUID notCompleted = insertBooking(clientId, masterId, masterServiceId, salonId,
                "NOT_COMPLETED", NOW.minusHours(8), NOW.minusHours(7));
        UUID cancelled = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CANCELLED", NOW.plusHours(10), NOW.plusHours(11));
        UUID declined = insertBooking(clientId, masterId, masterServiceId, salonId,
                "DECLINED", NOW.plusHours(12), NOW.plusHours(13));
        return new Fixture(confirmedUpcoming, confirmedBoundary, confirmedElapsed,
                completedElapsed, completedFutureEnds, notCompleted, cancelled, declined);
    }

    /** An additional SALON_MASTER in the given salon. */
    private UUID createExtraSalonMaster(UUID salonId) {
        String masterEmail = "bsbp-extra-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = fixtures.createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return masterId;
    }

    /** Inserts a salon booking row directly via SQL, bypassing the create/decline/complete flows. */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                               String status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID bookingId = UUID.randomUUID();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status, startsAt, endsAt, minutes);
        return bookingId;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode call(String token, UUID salonId, String partition, UUID masterId,
                          List<String> statuses, String from, String to, List<UUID> serviceIds)
            throws Exception {
        List<String> parts = new ArrayList<>();
        if (partition != null) {
            parts.add("partition=" + partition);
        }
        if (masterId != null) {
            parts.add("masterId=" + masterId);
        }
        if (statuses != null) {
            statuses.forEach(st -> parts.add("status=" + st));
        }
        if (from != null) {
            parts.add("from=" + from);
        }
        if (to != null) {
            parts.add("to=" + to);
        }
        if (serviceIds != null) {
            serviceIds.forEach(id -> parts.add("serviceId=" + id));
        }
        parts.add("size=50");
        String url = BOOKINGS_URL + "/salon/" + salonId + "?" + String.join("&", parts);
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("request to %s must succeed — body: %s", url, resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private JsonNode callPaged(String token, UUID salonId, String partition, int page, int size)
            throws Exception {
        String url = BOOKINGS_URL + "/salon/" + salonId + "?partition=" + partition
                + "&page=" + page + "&size=" + size;
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    /** Raw call for the status-code-only cases (403/401/400) — no OK assertion. */
    private ResponseEntity<String> rawCall(String token, UUID salonId, String partition) {
        return restTemplate.exchange(
                BOOKINGS_URL + "/salon/" + salonId + "?partition=" + partition,
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private List<UUID> idList(JsonNode root) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode row : root.path("data").path("data")) {
            ids.add(UUID.fromString(row.path("id").asText()));
        }
        return ids;
    }

    private Set<UUID> idSet(JsonNode root) {
        return new HashSet<>(idList(root));
    }

    private static long totalElements(JsonNode root) {
        return root.path("data").path("totalElements").asLong();
    }

    /**
     * {@code awaitingClosure} of the row carrying {@code bookingId}. Looked up BY ID rather than by
     * page index so the assertion cannot silently follow a sort change onto another row.
     */
    private static boolean awaitingClosure(JsonNode root, UUID bookingId) {
        for (JsonNode row : root.path("data").path("data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return row.path("awaitingClosure").asBoolean();
            }
        }
        throw new AssertionError("booking " + bookingId + " is not on the page at all");
    }

    private static Set<UUID> union(Set<UUID> a, Set<UUID> b) {
        Set<UUID> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }
}
