package com.beautica.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Deterministic regression test for cycle-2 audit finding 1 (HIGH, PLAUSIBLE — lock-order
 * inversion / deadlock risk) — races a WHOLE-VISIT provider decline
 * ({@link AppointmentTransitionService#declineAppointment}) against a PER-LEG client cancel
 * ({@code BookingService#cancelBooking}) on the SAME two-service visit, forcing both racers to
 * attempt the visit header's {@code SELECT ... FOR UPDATE} lock at effectively the same instant.
 *
 * <p><b>Before this fix (verified against the pre-cycle-2 code in a throwaway experiment, NOT
 * committed — see the cycle-2 writeup for the transcript).</b> The whole-visit family took NO
 * explicit header lock at all; only the per-item path
 * ({@code AppointmentTransitionService#lockHeaderIfConfirmed}, then a {@code BookingService
 * #cancelBooking}) locked the header, and did so AFTER already having written its own child row.
 * Racing this exact pairing (whole-visit decline vs. per-leg client cancel) via the SAME
 * {@code @SpyBean} + {@code CyclicBarrier} technique as
 * {@link AppointmentClientLegCancelConcurrencyIT}, over 135 forced-concurrent trials: ONE trial
 * produced a genuine Postgres {@code ERROR: deadlock detected} (40P01) — CONFIRMING the HIGH was
 * real, not merely plausible, though rare under this exact interleaving (consistent with a
 * lock-order-dependent race that needs precise flush-timing alignment to manifest as a true
 * deadlock rather than a benign serialization). The other 134 trials did not deadlock, but
 * exhibited a related, MORE common defect from the same root cause (absent header lock on the
 * whole-visit side): the client's per-leg cancel returned {@code 204} success while the targeted
 * leg silently ended up {@code DECLINED} instead of the requested {@code CANCELLED} — a lost
 * update on the CHILD row, not just the header, because the whole-visit path's item list was
 * loaded (and the header validated) against a stale pre-lock snapshot.
 *
 * <p><b>Fixed.</b> {@link AppointmentTransitionService#lockHeaderForWholeVisitTransition} — spied
 * on below — now locks the header UNCONDITIONALLY at the very TOP of every whole-visit transition
 * method, before any item is loaded, mirroring the per-item path's now-earlier lock point
 * ({@code AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}, also spied on
 * below, called by {@code BookingService#cancelBooking} BEFORE it saves its own child row). Both
 * families now acquire the SAME resource (the header row) in the SAME position relative to their
 * own item writes — the canonical {@code appointments}-before-{@code bookings} lock order — so
 * whichever racer's lock statement runs first simply blocks the other at the DB level (clean
 * serialization) instead of a deadlock being topologically possible. Because the whole-visit path
 * now re-reads BOTH the header AND its items via a FRESH snapshot taken AFTER the lock (rather
 * than a snapshot taken before), the outcome is deterministic regardless of which racer wins:
 * whichever leg the per-item racer already cancelled is skipped by the whole-visit path's
 * resurrection guard ({@code transitionItems} only touches still-{@code CONFIRMED} items), so the
 * lost-update this test forces is ALSO closed, not just the deadlock.
 *
 * <p><b>F1 (HIGH, cycle-6 audit 2026-08-03) narrowed this further.</b> Before F1,
 * {@code cancelBooking}'s own child-row save was UNCONDITIONAL on {@code child0}'s current DB
 * status: when the whole-visit DECLINE won the lock race (committing child0 to {@code DECLINED}
 * before the per-leg cancel's blocked lock unblocked), the cancel would still blindly overwrite
 * child0 back to {@code CANCELLED} and report {@code 204} — silently discarding the provider's
 * decline decision on that exact leg, the SAME entity-staleness defect class as the itemA-cancel-
 * vs-itemA-reschedule exploit, just manifesting as one terminal state clobbering another instead of
 * a resurrection to {@code CONFIRMED}. {@code cancelBooking}'s new post-lock freshness re-check
 * ({@code BookingRepository#existsConfirmedById}) now detects that child0 is no longer
 * {@code CONFIRMED} in this branch and aborts with a clean {@code 409} instead — so this test's
 * outcome is now genuinely race-order-dependent for the CANCEL side (see the branch below), where
 * it previously was not.
 *
 * <p><b>QA cycle-8 audit (2026-08-03) — six of the seven tests below rewritten as deterministic
 * one-sided gates, this class's own primary test included.</b> Every test in this class originally
 * used a two-arrivals {@code CyclicBarrier} to force both racers to attempt their respective header
 * locks at effectively the same instant, then asserted the LOSING racer's outcome with a lenient
 * {@code isIn(...)} (for this class's own primary test, {@code isIn(NO_CONTENT, CONFLICT)}) — branching
 * the rest of the assertions on whichever status actually came back. The audit found this makes the
 * guard each test exists to pin untestable by construction: with a two-arrivals barrier, whichever
 * racer's {@code SELECT ... FOR UPDATE} statement Postgres happens to service first is effectively a
 * coin flip the test does not control, so removing the very guard under test (the loser's own
 * freshness re-check, or — for the batched G3 test — its filter) simply flips which branch of the
 * {@code isIn(...)}/{@code if} executes; the test keeps passing either way. This was verified
 * empirically for two of the six (commenting out the guard and watching the test stay green). The
 * seventh test in this class,
 * {@link #should_serializeCleanlyWithCoherentFinalState_when_perItemRescheduleRacesWholeVisitDecline},
 * is exempt: its correctness comes from an UNCONDITIONAL guard on one side (the decline's header lock
 * always succeeds), so its outcome was never actually ambiguous — its {@code isIn(...)} covers the
 * OTHER racer only, never the guard under test itself.
 *
 * <p><b>The fix, applied to all six.</b> Replace the two-arrivals {@code CyclicBarrier} with a
 * ONE-SIDED gate (the pattern already proven in {@code BookingCancelRescheduleConcurrencyIT} and
 * {@code BookingProviderTransitionCancelRaceConcurrencyIT}): pause the racer that is meant to LOSE at
 * the exact instant it would attempt its own header-lock call — BEFORE the real method runs, so it has
 * acquired no lock and holds no contention against the other racer — drive the WINNING racer to full,
 * uncontended completion (commit included), then release the loser. The loser's own lock now succeeds
 * trivially (nothing contends for it), and its freshness re-check (or filter) runs against a row the
 * winner has ALREADY moved off {@code CONFIRMED} — the one condition that actually exercises the guard
 * under test. The outcome is therefore a single, forced, deterministic status code and a single,
 * forced, deterministic persisted state — never {@code isIn(...)}, never an {@code if} branching on
 * the operation under test's own result.
 *
 * <p><b>What is gained, and what is deliberately given up.</b> Gained: every rewritten test now FAILS
 * if its own targeted guard is removed — verified empirically, individually, for each. Given up: the
 * two-arrivals {@code CyclicBarrier} also incidentally exercised deadlock freedom under LITERAL
 * simultaneous lock contention (both racers' {@code SELECT ... FOR UPDATE} statements genuinely racing
 * at the DB level) — a one-sided gate never creates that contention (the loser is paused before it
 * ever attempts its own lock), so none of the six rewritten tests below prove deadlock freedom under
 * contention any more. That property was verified empirically for the ORIGINAL lock-order fixes this
 * class exists to guard (see, e.g., the cycle-2 trial-run paragraph above — "ONE trial produced a
 * genuine Postgres {@code ERROR: deadlock detected}") and is not re-proven here; each rewritten test's
 * own Javadoc and {@code @DisplayName} say so explicitly rather than implying a "must not deadlock"
 * guarantee the one-sided gate no longer makes.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Concurrent whole-visit decline vs. per-leg client cancel — lock-order/deadlock regression (fixed)")
class AppointmentCrossPathTransitionConcurrencyIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String MASTERS_URL = "/api/v1/masters";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private AppointmentTransitionService appointmentTransitionService;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    /**
     * QA cycle-8 rewrite (see class Javadoc "QA cycle-8 audit" section) — deterministic one-sided gate
     * replacing the original two-arrivals {@code CyclicBarrier}. Forces the WHOLE-VISIT decline to
     * commit FIRST, uncontended, then releases the per-leg CLIENT cancel of child0 to run its own
     * (now-uncontended) header lock and F1 freshness re-check for real.
     *
     * <p><b>What this isolates.</b> {@code cancelBooking}'s own F1 freshness re-check
     * ({@code isStillConfirmed}/{@code existsConfirmedById} on child0) — empirically confirmed to be
     * the guard that makes this test's outcome deterministic and its removal detectable (mutation-
     * verified: commenting it out flips the cancel response to {@code 204} and child0's DB status to
     * {@code CANCELLED}, silently overwriting the provider's decline decision).
     *
     * <p><b>What this does NOT isolate</b> (see class Javadoc "What is gained" paragraph): deadlock
     * freedom under literal simultaneous lock contention. The cancel thread is paused BEFORE it
     * attempts its own header lock at all, so there is no real contention for
     * {@code lockAppointmentHeaderBeforeClientItemCancel} to resolve here — that property was verified
     * separately, empirically, for the underlying lock-order fix (see this class's own top-of-file
     * "Before this fix" / cycle-2 trial-run paragraph).
     */
    @Test
    @DisplayName("F1 (deterministic) — a per-leg client cancel of child0 that reaches its own header "
            + "lock AFTER a whole-visit provider decline has ALREADY committed must abort 409 via its "
            + "own freshness re-check, never overwrite the decline's DECLINED terminal state back to "
            + "CANCELLED")
    void should_serializeCleanlyWithNoDeadlock_when_wholeVisitDeclineRacesPerLegClientCancel() throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID child0 = childAt(appointmentId, 0);
        UUID child1 = childAt(appointmentId, 1);
        String clientToken = visit.clientToken();
        String providerToken = visit.providerToken();

        // One-sided gate: the CANCEL thread's own header-lock attempt pauses BEFORE the real method
        // ever runs — i.e. before cancel acquires any row lock on the header — so the whole-visit
        // decline below can run to full, uncontended completion first. Only once decline has
        // committed is cancel released to attempt its own (now-uncontended) lock + F1 recheck.
        CountDownLatch cancelReachedGate = new CountDownLatch(1);
        CountDownLatch declineCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            cancelReachedGate.countDown();
            boolean released = declineCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("decline never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeClientItemCancel(eq(appointmentId));

        CountDownLatch cancelDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                respCancel.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + child0 + "/cancel", HttpMethod.PATCH, entity, String.class));
            } finally {
                cancelDone.countDown();
            }
        });

        boolean reachedGate = cancelReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate).as("the cancel thread must reach its header-lock attempt within 10s").isTrue();

        // Drive the whole-visit decline to full completion on the main thread WHILE the cancel
        // thread is paused BEFORE it has acquired any lock — no contention, decline always succeeds.
        HttpHeaders declineHeaders = fixtures.bearerHeaders(providerToken);
        declineHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> respDecline = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{}", declineHeaders), String.class);
        assertThat(respDecline.getStatusCode())
                .as("the whole-visit decline must succeed — nothing has raced it yet at this point — "
                        + "body: %s", respDecline.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        declineCommitted.countDown();
        boolean cancelFinished = cancelDone.await(10, TimeUnit.SECONDS);
        assertThat(cancelFinished).as("the cancel thread must finish within 10s once released").isTrue();

        // The single deterministic outcome this test forces: F1's freshness re-check on child0
        // SPECIFICALLY rejects the cancel, because the decline already moved child0 off CONFIRMED.
        assertThat(respCancel.get().getStatusCode())
                .as("F1: the cancel's own freshness re-check must reject a leg the decline already "
                        + "moved off CONFIRMED — body: %s", respCancel.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(child0))
                .as("F1: the provider's DECLINED decision on child0 must survive the LATER, losing "
                        + "cancel attempt — never silently overwritten back to CANCELLED")
                .isEqualTo("DECLINED");
        assertThat(dbStatus(child1))
                .as("the sibling leg is moved to DECLINED by the whole-visit transition")
                .isEqualTo("DECLINED");
        assertThat(appointmentStatus(appointmentId))
                .as("the header collapses to DECLINED (the whole-visit transition's target)")
                .isEqualTo("DECLINED");
    }

    /**
     * Perf/QA audit F8b (cross-batch) — races a per-item CLIENT reschedule of leg0
     * ({@link AppointmentTransitionService#rescheduleAppointmentItem}, phase 30.1/30.4 — genuinely
     * NEW code) against a provider WHOLE-VISIT decline of the SAME visit
     * ({@link AppointmentTransitionService#declineAppointment}), forcing both racers to attempt the
     * visit header's lock at effectively the same instant via a {@code CyclicBarrier} rendezvous on
     * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule} (the reschedule
     * item's NEW header-lock seam, phase 30.2) and
     * {@link AppointmentTransitionService#lockHeaderForWholeVisitTransition} (the decline's existing
     * seam) — exactly the same {@code @SpyBean} + {@code CyclicBarrier} technique the class-level
     * test above uses, now pointed at the NEW per-item-reschedule lock instead of the per-leg-cancel
     * one.
     *
     * <p><b>Coherent outcome regardless of race order.</b> The decline's header lock
     * ({@code lockHeaderRegardlessOfStatus}) is UNCONDITIONAL — it always succeeds once acquired, so
     * the decline HTTP call always returns {@code 204} whichever racer's lock statement runs first.
     * The reschedule's header lock ({@code lockHeaderIfConfirmed}) is CONDITIONAL on the header still
     * being {@code CONFIRMED} — so the reschedule HTTP call returns {@code 200} if it wins the lock
     * race first (the header is still CONFIRMED when it locks), or {@code 409} if the decline commits
     * first (the header is already DECLINED by the time reschedule's own lock attempt runs). Either
     * way the header always ends DECLINED and BOTH legs always end DECLINED — leg0 either at its
     * NEW (rescheduled) time (if reschedule won) or its ORIGINAL time (if decline won), never lost,
     * never duplicated, never left CONFIRMED.
     */
    @Test
    @DisplayName("per-item CLIENT reschedule of leg0 racing a provider WHOLE-VISIT decline of the SAME "
            + "visit must not deadlock; the decline's effect always prevails in the terminal state "
            + "(header + both legs end DECLINED), regardless of which racer wins the header lock first")
    void should_serializeCleanlyWithCoherentFinalState_when_perItemRescheduleRacesWholeVisitDecline()
            throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID leg0 = childAt(appointmentId, 0);
        UUID leg1 = childAt(appointmentId, 1);
        OffsetDateTime originalLeg0Start = dbStartsAt(leg0);
        OffsetDateTime newLeg0Start = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        String clientToken = visit.clientToken();
        String providerToken = visit.providerToken();

        CyclicBarrier bothRacersAtTheirHeaderLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockHeaderForWholeVisitTransition(eq(appointmentId));
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> respReschedule = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> respDecline = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"newStartsAt\":\"" + newLeg0Start + "\"}";
                respReschedule.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/reschedule",
                        HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(providerToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                respDecline.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/decline", HttpMethod.PATCH,
                        new HttpEntity<>("{}", headers), String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s — no hang, no unbounded lock wait").isTrue();
        assertThat(respDecline.get().getStatusCode())
                .as("the whole-visit decline must ALWAYS succeed regardless of race order — body: %s",
                        respDecline.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(respReschedule.get().getStatusCode())
                .as("the per-item reschedule must resolve to EXACTLY ONE of the two legal outcomes "
                        + "for this race — body: %s", respReschedule.get().getBody())
                .isIn(HttpStatus.OK, HttpStatus.CONFLICT);

        assertThat(appointmentStatus(appointmentId))
                .as("the decline's effect always prevails in the terminal header state")
                .isEqualTo("DECLINED");
        assertThat(dbStatus(leg0)).as("leg0 always ends DECLINED, whichever racer won the lock first")
                .isEqualTo("DECLINED");
        assertThat(dbStatus(leg1)).as("the untouched leg1 is also declined by the whole-visit transition")
                .isEqualTo("DECLINED");

        if (respReschedule.get().getStatusCode().equals(HttpStatus.OK)) {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("reschedule won the lock race FIRST and committed its move BEFORE decline's "
                            + "fresh post-lock snapshot read it — leg0 must be DECLINED at its NEW time")
                    .isEqualTo(newLeg0Start.toInstant());
        } else {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("decline won the lock race FIRST — leg0 must be DECLINED at its ORIGINAL, "
                            + "never-moved time")
                    .isEqualTo(originalLeg0Start.toInstant());
        }
    }

    /**
     * QA cycle-8 rewrite (see class Javadoc "QA cycle-8 audit" section) — deterministic one-sided gate
     * replacing the original two-arrivals {@code CyclicBarrier}. Forces the PER-LEG client cancel of
     * leg0 to commit FIRST, uncontended, then releases the CLIENT-owned WHOLE-VISIT reschedule
     * ({@code AppointmentTransitionService#rescheduleAppointment}) to run its own (now-uncontended)
     * header lock and post-lock freshness re-check for real.
     *
     * <p><b>What this isolates.</b> {@code rescheduleAppointment}'s post-lock freshness re-check
     * ({@code BookingRepository#findConfirmedIdsByAppointmentId}, cycle-5 audit finding 1) — mutation-
     * verified: commenting it out lets the reschedule proceed after its (still-succeeding) header lock
     * and move leg0's (and leg1's) window to the NEW block start even though leg0 was already
     * CANCELLED, flipping this test's "leg0 stays at its ORIGINAL time" and "reschedule returns 409"
     * assertions.
     *
     * <p><b>What this does NOT isolate</b> (see class Javadoc "What is gained" paragraph): deadlock
     * freedom under literal simultaneous lock contention, nor the header LOCK's own false branch in
     * isolation (removing only the lock call here would not by itself flip this test, since the
     * freshness re-check alone already rejects a stale leg0 once the sequencing below forces cancel to
     * commit first — the lock's role in TRUE concurrent contention is a separate property, not
     * re-proven by a one-sided gate).
     */
    @Test
    @DisplayName("cycle-5 finding 1 (deterministic) — a client-owned WHOLE-VISIT reschedule whose own "
            + "header lock succeeds AFTER a per-leg client cancel of leg0 has ALREADY committed must "
            + "abort 409 via its post-lock freshness re-check, mutating NEITHER leg0 NOR leg1")
    void should_serializeCleanlyWithCoherentFinalState_when_wholeVisitRescheduleRacesPerLegClientCancel()
            throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID leg0 = childAt(appointmentId, 0);
        UUID leg1 = childAt(appointmentId, 1);
        OffsetDateTime originalLeg0Start = dbStartsAt(leg0);
        OffsetDateTime originalLeg1Start = dbStartsAt(leg1);
        OffsetDateTime newBlockStart = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        String clientToken = visit.clientToken();

        // One-sided gate: the RESCHEDULE thread's own header-lock attempt pauses BEFORE the real
        // method ever runs, so the per-leg cancel below can run to full, uncontended completion
        // first. Only once cancel has committed is reschedule released to attempt its own
        // (now-uncontended) lock + post-lock freshness re-check.
        CountDownLatch rescheduleReachedGate = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            rescheduleReachedGate.countDown();
            boolean released = cancelCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("cancel never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));

        CountDownLatch rescheduleDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respReschedule = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"newStartsAt\":\"" + newBlockStart + "\"}";
                respReschedule.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/reschedule",
                        HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class));
            } finally {
                rescheduleDone.countDown();
            }
        });

        boolean reachedGate = rescheduleReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate).as("the reschedule thread must reach its header-lock attempt within 10s").isTrue();

        // Drive the per-leg cancel to full completion on the main thread WHILE the reschedule
        // thread is paused BEFORE it has acquired any lock — no contention, cancel always succeeds.
        HttpHeaders cancelHeaders = fixtures.bearerHeaders(clientToken);
        cancelHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> cancelEntity = new HttpEntity<>(
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", cancelHeaders);
        ResponseEntity<String> respCancel = restTemplate.exchange(
                BOOKINGS_URL + "/" + leg0 + "/cancel", HttpMethod.PATCH, cancelEntity, String.class);
        assertThat(respCancel.getStatusCode())
                .as("the per-leg cancel must succeed — nothing has raced it yet at this point — body: %s",
                        respCancel.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        cancelCommitted.countDown();
        boolean rescheduleFinished = rescheduleDone.await(10, TimeUnit.SECONDS);
        assertThat(rescheduleFinished).as("the reschedule thread must finish within 10s once released").isTrue();

        // The single deterministic outcome this test forces: the post-lock freshness re-check
        // rejects the reschedule, because the cancel already moved leg0 off CONFIRMED.
        assertThat(respReschedule.get().getStatusCode())
                .as("the whole-visit reschedule's post-lock freshness re-check must reject a visit "
                        + "whose leg0 the cancel already moved off CONFIRMED — body: %s",
                        respReschedule.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(appointmentStatus(appointmentId))
                .as("leg1 was never touched by either racer, so the header stays CONFIRMED throughout")
                .isEqualTo("CONFIRMED");
        assertThat(dbStatus(leg0))
                .as("the cancel's effect on leg0's STATUS survives the LATER, losing reschedule attempt")
                .isEqualTo("CANCELLED");
        assertThat(dbStartsAt(leg0).toInstant())
                .as("the reschedule LOST the race — its post-lock recheck aborted before mutating "
                        + "anything, so leg0 stays at its ORIGINAL time")
                .isEqualTo(originalLeg0Start.toInstant());
        assertThat(dbStartsAt(leg1).toInstant())
                .as("the reschedule LOST the race — it must have mutated NOTHING; leg1 stays at its "
                        + "ORIGINAL time, byte-for-byte")
                .isEqualTo(originalLeg1Start.toInstant());
    }

    /**
     * QA cycle-8 rewrite (see class Javadoc "QA cycle-8 audit" section) — deterministic one-sided gate
     * replacing the original two-arrivals {@code CyclicBarrier}, for F1 (HIGH, cycle-6 audit
     * 2026-08-03), the security auditor's own concrete, single-actor exploit: races a per-item CLIENT
     * reschedule of leg0 ({@link AppointmentTransitionService#rescheduleAppointmentItem}) against a
     * per-item CLIENT cancel of the SAME leg0 ({@code BookingService#cancelAppointmentItem} →
     * {@code BookingService#cancelBooking}), fired by the SAME client account. Cancelling ONE leg of a
     * two-leg visit never flips the header out of {@code CONFIRMED} (leg1 always remains), so — before
     * F1 — BOTH operations' header-lock guard alone could never detect that leg0 SPECIFICALLY had
     * changed, letting whichever call committed LAST silently win with a corrupted result (a CANCELLED
     * leg written back to CONFIRMED at a brand-new time). Forces the per-item CANCEL to commit FIRST,
     * uncontended, then releases the per-item RESCHEDULE to run its own (now-uncontended) header lock
     * and F1 freshness re-check for real.
     *
     * <p><b>What this isolates.</b> {@code rescheduleAppointmentItem}'s F1 freshness re-check
     * ({@code BookingRepository#existsConfirmedById} on the target item ITSELF, not just the header) —
     * empirically confirmed to be the guard that makes this test's outcome deterministic and its
     * removal detectable (mutation-verified: commenting it out lets the reschedule proceed after its
     * still-succeeding header lock and move leg0's window to the NEW time even though leg0 was already
     * CANCELLED, flipping this test's "leg0 stays at its ORIGINAL time" and "reschedule returns 409"
     * assertions — leg0's STATUS itself stays CANCELLED either way, because {@code Booking}'s
     * {@code @DynamicUpdate} (G1) means {@code target.reschedule(...)} never touches the status column,
     * so the pre-fix corruption is now visible only via the TIME column, not the status one).
     *
     * <p><b>What this does NOT isolate</b> (see class Javadoc "What is gained" paragraph): deadlock
     * freedom under literal simultaneous lock contention.
     */
    @Test
    @DisplayName("F1 (deterministic) — a per-item CLIENT reschedule of leg0 whose own header lock "
            + "succeeds AFTER a per-item CLIENT cancel of the SAME leg0 has ALREADY committed must "
            + "abort 409 via its own freshness re-check, never resurrecting or moving the cancelled leg")
    void should_neverResurrectCancelledLegToConfirmed_when_perItemRescheduleRacesPerItemCancelOfSameLeg()
            throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID leg0 = childAt(appointmentId, 0);
        UUID leg1 = childAt(appointmentId, 1);
        OffsetDateTime originalLeg0Start = dbStartsAt(leg0);
        OffsetDateTime originalLeg1Start = dbStartsAt(leg1);
        OffsetDateTime newLeg0Start = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        String clientToken = visit.clientToken();

        // One-sided gate: the RESCHEDULE thread's own header-lock attempt pauses BEFORE the real
        // method ever runs, so the per-item cancel below can run to full, uncontended completion
        // first. Only once cancel has committed is reschedule released to attempt its own
        // (now-uncontended) lock + F1 recheck.
        CountDownLatch rescheduleReachedGate = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            rescheduleReachedGate.countDown();
            boolean released = cancelCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("cancel never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));

        CountDownLatch rescheduleDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respReschedule = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"newStartsAt\":\"" + newLeg0Start + "\"}";
                respReschedule.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/reschedule",
                        HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class));
            } finally {
                rescheduleDone.countDown();
            }
        });

        boolean reachedGate = rescheduleReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate).as("the reschedule thread must reach its header-lock attempt within 10s").isTrue();

        // Drive the per-item cancel to full completion on the main thread WHILE the reschedule
        // thread is paused BEFORE it has acquired any lock — no contention, cancel always succeeds.
        HttpHeaders cancelHeaders = fixtures.bearerHeaders(clientToken);
        cancelHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> cancelEntity = new HttpEntity<>(
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", cancelHeaders);
        ResponseEntity<String> respCancel = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/cancel",
                HttpMethod.PATCH, cancelEntity, String.class);
        assertThat(respCancel.getStatusCode())
                .as("the per-item cancel must succeed — nothing has raced it yet at this point — body: %s",
                        respCancel.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        cancelCommitted.countDown();
        boolean rescheduleFinished = rescheduleDone.await(10, TimeUnit.SECONDS);
        assertThat(rescheduleFinished).as("the reschedule thread must finish within 10s once released").isTrue();

        // The single deterministic outcome this test forces: F1's freshness re-check on leg0
        // SPECIFICALLY rejects the reschedule, because the cancel already moved leg0 off CONFIRMED.
        assertThat(respReschedule.get().getStatusCode())
                .as("F1: the reschedule's own freshness re-check must reject a leg the cancel already "
                        + "moved off CONFIRMED — body: %s", respReschedule.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0))
                .as("F1: leg0 must never be resurrected to CONFIRMED — the cancel's effect survives "
                        + "the LATER, losing reschedule attempt")
                .isEqualTo("CANCELLED");
        assertThat(dbStartsAt(leg0).toInstant())
                .as("the reschedule LOST the race — its F1 recheck aborted before mutating anything, "
                        + "so leg0 stays at its ORIGINAL time (this is the assertion that would have "
                        + "caught F1's absence: pre-fix, a stale reschedule moved this time even though "
                        + "@DynamicUpdate left the status column alone)")
                .isEqualTo(originalLeg0Start.toInstant());
        assertThat(dbStatus(leg1))
                .as("leg1 (untouched by either per-item racer) stays CONFIRMED throughout")
                .isEqualTo("CONFIRMED");
        assertThat(dbStartsAt(leg1).toInstant())
                .as("leg1's window is untouched by a per-item race targeting only leg0")
                .isEqualTo(originalLeg1Start.toInstant());
        assertThat(appointmentStatus(appointmentId))
                .as("leg1 always remains CONFIRMED, so the header can never collapse in this race")
                .isEqualTo("CONFIRMED");
    }

    /**
     * QA cycle-8 rewrite (see class Javadoc "QA cycle-8 audit" section) — deterministic one-sided gate
     * replacing the original two-arrivals {@code CyclicBarrier}, for F1 (HIGH, cycle-6 audit
     * 2026-08-03) — the same exploit shape as
     * {@link #should_neverResurrectCancelledLegToConfirmed_when_perItemRescheduleRacesPerItemCancelOfSameLeg}
     * above, but pairing the per-item CLIENT reschedule of leg0 against a per-item PROVIDER decline of
     * the SAME leg0 ({@link AppointmentTransitionService#declineAppointmentItem}) instead of a per-item
     * cancel — the OTHER per-item write path F1 fixes. Forces the per-item DECLINE to commit FIRST,
     * uncontended, then releases the per-item RESCHEDULE to run its own (now-uncontended) header lock
     * and F1 freshness re-check for real.
     *
     * <p><b>What this isolates.</b> {@code rescheduleAppointmentItem}'s F1 freshness re-check
     * ({@code BookingRepository#existsConfirmedById} on the target item ITSELF) against a DECLINE
     * racer specifically (the sibling test above covers the CANCEL racer) — empirically the same guard,
     * exercised against the other per-item write path F1 also fixes.
     *
     * <p><b>What this does NOT isolate</b> (see class Javadoc "What is gained" paragraph): deadlock
     * freedom under literal simultaneous lock contention.
     */
    @Test
    @DisplayName("F1 (deterministic) — a per-item CLIENT reschedule of leg0 whose own header lock "
            + "succeeds AFTER a per-item PROVIDER decline of the SAME leg0 has ALREADY committed must "
            + "abort 409 via its own freshness re-check, never resurrecting or moving the declined leg")
    void should_neverResurrectDeclinedLegToConfirmed_when_perItemRescheduleRacesPerItemDeclineOfSameLeg()
            throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID leg0 = childAt(appointmentId, 0);
        UUID leg1 = childAt(appointmentId, 1);
        OffsetDateTime originalLeg0Start = dbStartsAt(leg0);
        OffsetDateTime originalLeg1Start = dbStartsAt(leg1);
        OffsetDateTime newLeg0Start = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        String clientToken = visit.clientToken();
        String providerToken = visit.providerToken();

        // One-sided gate: the RESCHEDULE thread's own header-lock attempt pauses BEFORE the real
        // method ever runs, so the per-item decline below can run to full, uncontended completion
        // first. Only once decline has committed is reschedule released to attempt its own
        // (now-uncontended) lock + F1 recheck.
        CountDownLatch rescheduleReachedGate = new CountDownLatch(1);
        CountDownLatch declineCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            rescheduleReachedGate.countDown();
            boolean released = declineCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("decline never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));

        CountDownLatch rescheduleDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respReschedule = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"newStartsAt\":\"" + newLeg0Start + "\"}";
                respReschedule.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/reschedule",
                        HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class));
            } finally {
                rescheduleDone.countDown();
            }
        });

        boolean reachedGate = rescheduleReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate).as("the reschedule thread must reach its header-lock attempt within 10s").isTrue();

        // Drive the per-item decline to full completion on the main thread WHILE the reschedule
        // thread is paused BEFORE it has acquired any lock — no contention, decline always succeeds.
        HttpHeaders declineHeaders = fixtures.bearerHeaders(providerToken);
        declineHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> respDecline = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/decline",
                HttpMethod.PATCH, new HttpEntity<>("{}", declineHeaders), String.class);
        assertThat(respDecline.getStatusCode())
                .as("the per-item decline must succeed — nothing has raced it yet at this point — body: %s",
                        respDecline.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        declineCommitted.countDown();
        boolean rescheduleFinished = rescheduleDone.await(10, TimeUnit.SECONDS);
        assertThat(rescheduleFinished).as("the reschedule thread must finish within 10s once released").isTrue();

        // The single deterministic outcome this test forces: F1's freshness re-check on leg0
        // SPECIFICALLY rejects the reschedule, because the decline already moved leg0 off CONFIRMED.
        assertThat(respReschedule.get().getStatusCode())
                .as("F1: the reschedule's own freshness re-check must reject a leg the decline already "
                        + "moved off CONFIRMED — body: %s", respReschedule.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0))
                .as("F1: leg0 must never be resurrected to CONFIRMED — the decline's effect survives "
                        + "the LATER, losing reschedule attempt")
                .isEqualTo("DECLINED");
        assertThat(dbStartsAt(leg0).toInstant())
                .as("the reschedule LOST the race — its F1 recheck aborted before mutating anything, "
                        + "so leg0 stays at its ORIGINAL time")
                .isEqualTo(originalLeg0Start.toInstant());
        assertThat(dbStatus(leg1))
                .as("leg1 (untouched by either per-item racer) stays CONFIRMED throughout")
                .isEqualTo("CONFIRMED");
        assertThat(dbStartsAt(leg1).toInstant())
                .as("leg1's window is untouched by a per-item race targeting only leg0")
                .isEqualTo(originalLeg1Start.toInstant());
        assertThat(appointmentStatus(appointmentId))
                .as("leg1 always remains CONFIRMED, so the header can never collapse in this race")
                .isEqualTo("CONFIRMED");
    }

    /**
     * QA cycle-8 rewrite (see class Javadoc "QA cycle-8 audit" section) — deterministic one-sided gate
     * replacing the original two-arrivals {@code CyclicBarrier}, for G2 (HIGH, cycle-7 audit
     * 2026-08-03) — the LEGACY, booking-scoped {@code PATCH /bookings/{id}/reschedule} route (unlike
     * the appointment-scoped {@code PATCH /appointments/{id}/services/{id}/reschedule} route the two
     * tests above drive) is fully reachable for an appointment child (it never calls
     * {@code assertNotAppointmentChild}) and was the FOURTH sibling missing the F1-class freshness
     * re-check: it already took the header lock ({@code lockAppointmentHeaderBeforeItemReschedule})
     * but never verified {@code booking} itself was still CONFIRMED afterward — the exact exploit the
     * auditors traced: a provider declines leg0 via the per-item route; a concurrent legacy reschedule
     * of the SAME leg acquires the (still-CONFIRMED, leg1 remains) header lock and — pre-fix —
     * proceeds straight to its save with no check that leg0 itself was still CONFIRMED. Forces the
     * per-item DECLINE to commit FIRST, uncontended, then releases the LEGACY reschedule to run its own
     * (now-uncontended) header lock and G2 freshness re-check for real.
     *
     * <p><b>Same rendezvous point as the appointment-scoped pairing above</b> — the legacy route calls
     * the SAME {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule} seam
     * ({@code BookingService#rescheduleBooking}'s Phase 30.2 lock-order fix), so this test reuses the
     * identical spy point, just pointed at {@code PATCH /bookings/{leg0}/reschedule} instead of the
     * appointment-scoped route.
     *
     * <p><b>What this isolates.</b> {@code rescheduleBooking}'s G2 freshness re-check
     * ({@code isStillConfirmed} on the target booking ITSELF) for the LEGACY route specifically — the
     * property that was NOT guaranteed before G2, since the legacy route had no recheck at all to
     * abort on.
     *
     * <p><b>What this does NOT isolate</b> (see class Javadoc "What is gained" paragraph): deadlock
     * freedom under literal simultaneous lock contention.
     */
    @Test
    @DisplayName("G2 (deterministic) — the LEGACY booking-scoped reschedule route whose own header lock "
            + "succeeds AFTER a per-item PROVIDER decline of the SAME leg has ALREADY committed must "
            + "abort 409 via its own freshness re-check, never resurrecting or moving the declined leg")
    void should_neverResurrectDeclinedLegToConfirmed_when_legacyBookingRescheduleRacesPerItemDeclineOfSameLeg()
            throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID leg0 = childAt(appointmentId, 0);
        UUID leg1 = childAt(appointmentId, 1);
        OffsetDateTime originalLeg0Start = dbStartsAt(leg0);
        OffsetDateTime originalLeg1Start = dbStartsAt(leg1);
        OffsetDateTime newLeg0Start = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        String clientToken = visit.clientToken();
        String providerToken = visit.providerToken();

        // One-sided gate: the LEGACY-RESCHEDULE thread's own header-lock attempt pauses BEFORE the
        // real method ever runs, so the per-item decline below can run to full, uncontended
        // completion first. Only once decline has committed is reschedule released to attempt its
        // own (now-uncontended) lock + G2 recheck.
        CountDownLatch rescheduleReachedGate = new CountDownLatch(1);
        CountDownLatch declineCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            rescheduleReachedGate.countDown();
            boolean released = declineCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("decline never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));

        CountDownLatch rescheduleDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respReschedule = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"newStartsAt\":\"" + newLeg0Start + "\"}";
                // The LEGACY, booking-scoped route — this is the blind spot G2 closes.
                respReschedule.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + leg0 + "/reschedule",
                        HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class));
            } finally {
                rescheduleDone.countDown();
            }
        });

        boolean reachedGate = rescheduleReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate).as("the reschedule thread must reach its header-lock attempt within 10s").isTrue();

        // Drive the per-item decline to full completion on the main thread WHILE the reschedule
        // thread is paused BEFORE it has acquired any lock — no contention, decline always succeeds.
        HttpHeaders declineHeaders = fixtures.bearerHeaders(providerToken);
        declineHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> respDecline = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/decline",
                HttpMethod.PATCH, new HttpEntity<>("{}", declineHeaders), String.class);
        assertThat(respDecline.getStatusCode())
                .as("the per-item decline must succeed — nothing has raced it yet at this point — body: %s",
                        respDecline.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        declineCommitted.countDown();
        boolean rescheduleFinished = rescheduleDone.await(10, TimeUnit.SECONDS);
        assertThat(rescheduleFinished).as("the reschedule thread must finish within 10s once released").isTrue();

        // The single deterministic outcome this test forces: G2's freshness re-check on leg0
        // SPECIFICALLY rejects the legacy reschedule, because the decline already moved leg0 off
        // CONFIRMED.
        assertThat(respReschedule.get().getStatusCode())
                .as("G2: the legacy reschedule's own freshness re-check must reject a leg the decline "
                        + "already moved off CONFIRMED — body: %s", respReschedule.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0))
                .as("G2: leg0 must never be resurrected to CONFIRMED via the legacy reschedule route — "
                        + "the decline's effect survives the LATER, losing reschedule attempt")
                .isEqualTo("DECLINED");
        assertThat(dbStartsAt(leg0).toInstant())
                .as("the legacy reschedule LOST the race — its G2 recheck aborted before mutating "
                        + "anything, so leg0 stays at its ORIGINAL time")
                .isEqualTo(originalLeg0Start.toInstant());
        assertThat(dbStatus(leg1))
                .as("leg1 (untouched by either racer) stays CONFIRMED throughout")
                .isEqualTo("CONFIRMED");
        assertThat(dbStartsAt(leg1).toInstant())
                .as("leg1's window is untouched by a race targeting only leg0")
                .isEqualTo(originalLeg1Start.toInstant());
        assertThat(appointmentStatus(appointmentId))
                .as("leg1 always remains CONFIRMED, so the header can never collapse in this race")
                .isEqualTo("CONFIRMED");
    }

    /**
     * QA cycle-8 rewrite (see class Javadoc "QA cycle-8 audit" section) — deterministic one-sided gate
     * replacing the original two-arrivals {@code CyclicBarrier}, for G3 (HIGH, cycle-7 audit
     * 2026-08-03) — races the BATCHED schedule-override-conflict decline
     * ({@link AppointmentTransitionService#declineAppointmentItems}, driven end to end via
     * {@code PUT /masters/{masterId}/overrides/{date}} with {@code cancelOverlapping=true}, targeting
     * BOTH legs of the visit) against a per-item CLIENT cancel of leg0
     * ({@code PATCH /appointments/{id}/services/{leg0}/cancel}) — the cross-endpoint racer both
     * auditors independently identified. Forces the per-item CANCEL to commit FIRST, uncontended, then
     * releases the batched OVERRIDE-decline to run its own (now-uncontended) header lock and G3
     * batched freshness re-check for real — the exact "filter, not abort" branch the empirically
     * confirmed defect could not distinguish under the original {@code isIn("CANCELLED", "DECLINED")}.
     *
     * <p><b>Rendezvous point</b> — same seam as the single-item decline pairing tests above:
     * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemDecline}, which the batched
     * method also calls (G3 routed it through the SAME wrapper rather than the private
     * {@code lockHeaderBeforeItemTransition} it used to call directly).
     *
     * <p><b>What this isolates.</b> {@code declineAppointmentItems}' G3 batched freshness re-check
     * ({@link BookingRepository#findConfirmedIdsByAppointmentId}) and its "filter, not abort" contract
     * SPECIFICALLY — empirically confirmed to be the guard that makes this test's outcome
     * deterministic and its removal detectable (mutation-verified: reverting the filter to a
     * pre-G3-shaped "decline every originally-requested target unconditionally" re-declines leg0 over
     * the cancel's CANCELLED status, flipping this test's "leg0 stays CANCELLED" assertion to
     * DECLINED). Unlike the ORIGINAL version of this test, this rewrite asserts a single, specific
     * terminal state for leg0 ({@code CANCELLED}) rather than {@code isIn("CANCELLED", "DECLINED")} —
     * the exact ambiguity the audit flagged as unable to distinguish "filtered correctly" from
     * "blindly re-declined".
     *
     * <p><b>What this does NOT isolate</b> (see class Javadoc "What is gained" paragraph): deadlock
     * freedom under literal simultaneous lock contention, nor the OTHER branch of G3's filter (the
     * override winning the race and declining a leg0 that was still CONFIRMED when its recheck ran) —
     * that branch is unconditional application logic with no racer-dependent behaviour to isolate
     * (every target the recheck still reports CONFIRMED is always declined), so it needs no dedicated
     * concurrency test; {@code AppointmentTransitionServiceTest}'s
     * {@code should_declineAllTargetsWithOneLoadOneLockOneCollapse_when_multipleSiblingsProvided} pins
     * it deterministically at the unit level already.
     */
    @Test
    @DisplayName("G3 (deterministic) — a batched schedule-override decline whose own header lock "
            + "succeeds AFTER a per-item CLIENT cancel of ONE of its targets has ALREADY committed must "
            + "FILTER the stale target out of the batch (never re-decline it), while still declining "
            + "the untouched sibling and collapsing the header")
    void should_filterStaleTargetAndDeclineSurvivor_when_batchedOverrideDeclineRacesPerItemClientCancelOfSameLeg()
            throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID masterId = visit.masterId();
        UUID leg0 = childAt(appointmentId, 0);
        UUID leg1 = childAt(appointmentId, 1);
        String clientToken = visit.clientToken();
        String providerToken = visit.providerToken();
        LocalDate overrideDate = LocalDate.now(TimeZones.KYIV).plusDays(2);

        // One-sided gate: the OVERRIDE thread's own header-lock attempt pauses BEFORE the real
        // method ever runs, so the per-item cancel below can run to full, uncontended completion
        // first. Only once cancel has committed is override released to attempt its own
        // (now-uncontended) lock + G3 batched recheck.
        CountDownLatch overrideReachedGate = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            overrideReachedGate.countDown();
            boolean released = cancelCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("cancel never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemDecline(eq(appointmentId));

        CountDownLatch overrideDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respOverride = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(providerToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = objectMapper.writeValueAsString(new ScheduleOverrideRequest(
                        overrideDate, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                        List.of(), null, true));
                respOverride.set(restTemplate.exchange(
                        MASTERS_URL + "/" + masterId + "/overrides/" + overrideDate, HttpMethod.PUT,
                        new HttpEntity<>(body, headers), String.class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                overrideDone.countDown();
            }
        });

        boolean reachedGate = overrideReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate).as("the override thread must reach its header-lock attempt within 10s").isTrue();

        // Drive the per-item cancel to full completion on the main thread WHILE the override thread
        // is paused BEFORE it has acquired any lock — no contention, cancel always succeeds.
        HttpHeaders cancelHeaders = fixtures.bearerHeaders(clientToken);
        cancelHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> cancelEntity = new HttpEntity<>(
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", cancelHeaders);
        ResponseEntity<String> respCancel = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/cancel",
                HttpMethod.PATCH, cancelEntity, String.class);
        assertThat(respCancel.getStatusCode())
                .as("the per-item cancel must succeed — nothing has raced it yet at this point — body: %s",
                        respCancel.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        cancelCommitted.countDown();
        boolean overrideFinished = overrideDone.await(10, TimeUnit.SECONDS);
        assertThat(overrideFinished).as("the override thread must finish within 10s once released").isTrue();

        // The single deterministic outcome this test forces: the override write itself always
        // succeeds (narrowing the schedule never fails), but G3's batched recheck must FILTER leg0
        // out of the batch — it is no longer CONFIRMED by the time the recheck runs — while leg1
        // (never touched by the cancel) is declined normally.
        assertThat(respOverride.get().getStatusCode())
                .as("the override write itself must succeed — body: %s", respOverride.get().getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(dbStatus(leg0))
                .as("G3: leg0 must be FILTERED out of the batch, never re-declined over the cancel's "
                        + "effect — this is the assertion the original isIn(\"CANCELLED\", \"DECLINED\") "
                        + "could not make: it accepted DECLINED here too, which is exactly what a "
                        + "removed/broken filter would also produce")
                .isEqualTo("CANCELLED");
        assertThat(dbStatus(leg1))
                .as("leg1 (untouched by the cancel, still CONFIRMED when the batched recheck ran) is "
                        + "declined normally by the override write")
                .isEqualTo("DECLINED");
        assertThat(appointmentStatus(appointmentId))
                .as("leg1 was the last CONFIRMED sibling once leg0 cancelled, so declining it collapses "
                        + "the header to DECLINED")
                .isEqualTo("DECLINED");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A created two-service CONFIRMED visit plus the owning client's and provider's tokens and the
     * owning master's id (the latter added for G3's schedule-override pairing, which needs
     * {@code PUT /masters/{masterId}/overrides/{date}} — every pre-existing caller of
     * {@link #createTwoServiceVisitLegs()} is unaffected by this additive record component).
     */
    private record Visit(UUID id, String clientToken, String providerToken, UUID masterId) {}

    /** Creates a fresh INDEPENDENT_MASTER + CLIENT and a two-service CONFIRMED visit. */
    private Visit createTwoServiceVisitLegs() throws Exception {
        String masterEmail = "xpath-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "xpath-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        String providerToken = fixtures.tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"masterId":"%s","masterServiceIds":["%s","%s"],"startsAt":"%s"}
                """.formatted(masterId, serviceA, serviceB, startsAt.toOffsetDateTime());
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode())
                .as("visit setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(created.getBody()).path("data");
        return new Visit(UUID.fromString(data.path("id").asText()), clientToken, providerToken, masterId);
    }

    private UUID childAt(UUID appointmentId, int offset) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at OFFSET ? LIMIT 1",
                UUID.class, appointmentId, offset);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private OffsetDateTime dbStartsAt(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '08:00', '20:00')",
                    UUID.randomUUID(), scheduleId, day);
        }
    }
}
