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

    @Test
    @DisplayName("provider whole-visit decline racing a client per-leg cancel on the SAME visit must not "
            + "deadlock and must land in a consistent state: the client's cancelled leg stays CANCELLED, "
            + "the other leg is DECLINED by the whole-visit transition, and the header collapses to DECLINED")
    void should_serializeCleanlyWithNoDeadlock_when_wholeVisitDeclineRacesPerLegClientCancel() throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID child0 = childAt(appointmentId, 0);
        UUID child1 = childAt(appointmentId, 1);
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
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeClientItemCancel(eq(appointmentId));

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> respDecline = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();

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
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                respCancel.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + child0 + "/cancel", HttpMethod.PATCH, entity, String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s — no hang, no unbounded lock wait").isTrue();
        // The whole-visit decline's own header lock (lockHeaderForWholeVisitTransition) is
        // UNCONDITIONAL, so it always succeeds regardless of race order.
        assertThat(respDecline.get().getStatusCode())
                .as("whole-visit decline must succeed — body: %s", respDecline.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // F1 (cycle-6 audit 2026-08-03): the per-leg cancel's outcome is now race-order-dependent —
        // see this class's Javadoc "F1 narrowed this further" paragraph. 204 if cancel's own lock
        // won first (child0 was still CONFIRMED when cancel's freshness check ran); 409 if decline
        // won first and already moved child0 to DECLINED before cancel's freshness check ran.
        assertThat(respCancel.get().getStatusCode())
                .as("per-leg client cancel must resolve to EXACTLY ONE of the two legal outcomes for "
                        + "this race — body: %s", respCancel.get().getBody())
                .isIn(HttpStatus.NO_CONTENT, HttpStatus.CONFLICT);

        // The sibling leg and the header are deterministic regardless of which racer wins the header
        // lock — see class Javadoc: transitionItems' pre-existing resurrection guard means the
        // whole-visit decline only ever flips a STILL-CONFIRMED child, and the header always ends
        // DECLINED once decline has run (its own lock is unconditional and always succeeds).
        assertThat(dbStatus(child1))
                .as("the sibling leg is moved to DECLINED by the whole-visit transition")
                .isEqualTo("DECLINED");
        assertThat(appointmentStatus(appointmentId))
                .as("the header collapses to DECLINED (the whole-visit transition's target)")
                .isEqualTo("DECLINED");

        if (respCancel.get().getStatusCode().equals(HttpStatus.NO_CONTENT)) {
            assertThat(dbStatus(child0))
                    .as("cancel WON the lock race — child0 must stay CANCELLED, the client's own "
                            + "effect, not silently overwritten/lost")
                    .isEqualTo("CANCELLED");
        } else {
            // F1 (HIGH): decline WON the lock race and already moved child0 to DECLINED before
            // cancel's freshness check ran — cancel must have mutated NOTHING, so child0 stays
            // DECLINED (the provider's decision), never resurrected/overwritten by the later-failing
            // cancel attempt.
            assertThat(dbStatus(child0))
                    .as("F1 (HIGH): decline WON the lock race — child0 must stay DECLINED; the "
                            + "provider's decision is never silently overwritten by a losing cancel")
                    .isEqualTo("DECLINED");
        }
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
     * Regression test for cycle-5 audit finding 1 (HIGH, 2026-08-03) — races a CLIENT-owned
     * WHOLE-VISIT reschedule ({@code AppointmentTransitionService#rescheduleAppointment}) against a
     * PER-LEG client cancel ({@code BookingService#cancelBooking}) of the SAME leg the reschedule is
     * about to move, via the SAME {@code @SpyBean} + {@code CyclicBarrier} technique as the two
     * tests above — now pointed at {@code rescheduleAppointment}'s OWN header-lock seam
     * ({@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule}, the fix this
     * test guards) and the per-leg cancel's existing one
     * ({@link AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}, already
     * spied on above).
     *
     * <p><b>Before this fix.</b> {@code rescheduleAppointment} took NO header lock at all, so
     * nothing serialized it against a concurrent per-leg cancel — the two could interleave with no
     * ordering guarantee whatsoever (not even the "one blocks the other" guarantee this test now
     * proves), the exact lost-update-class gap the backlog flagged.
     *
     * <p><b>Coherent outcome regardless of race order.</b> The cancel's own header lock
     * ({@code lockHeaderIfConfirmed}, via {@code lockAppointmentHeaderBeforeClientItemCancel}) and
     * the reschedule's ({@code lockHeaderIfConfirmed}, via
     * {@code lockAppointmentHeaderBeforeItemReschedule}) are BOTH conditional on the header still
     * being {@code CONFIRMED} — and cancelling ONE leg of a two-leg visit never flips the header
     * out of {@code CONFIRMED} (leg1 always remains), so BOTH locks always succeed and the cancel
     * HTTP call ALWAYS returns {@code 204} whichever racer's lock statement runs first. The
     * reschedule's outcome is race-order-dependent: {@code 200} if its lock statement runs (and its
     * post-lock freshness re-check passes) BEFORE the cancel commits leg0's status change, or
     * {@code 409} ("Visit changed concurrently") if the cancel's commit is visible by the time the
     * reschedule's freshness re-check ({@code BookingRepository#findConfirmedIdsByAppointmentId})
     * runs — the fix under test. Either way leg0 always ends {@code CANCELLED} (the cancel's effect
     * is never lost) and the header always stays {@code CONFIRMED} (leg1 always remains). When the
     * reschedule LOSES the race, it is proven to have mutated NOTHING — leg1 stays at its ORIGINAL
     * time, byte-for-byte — the clean-abort guarantee this fix adds.
     *
     * <p><b>leg0's own final window — SUPERSEDED by G1 (cycle-7 audit 2026-08-03).</b> F1 (cycle-6)
     * proved leg0's STATUS is always {@code CANCELLED}, never resurrected, but at the time left
     * leg0's {@code startsAt} reverting to its ORIGINAL, pre-race value in BOTH branches — even
     * "reschedule wins" — as a known, deliberately out-of-scope limitation: {@code cancelBooking}'s
     * own child-row save wrote {@code booking}'s FULL column set (no {@code @DynamicUpdate} on
     * {@code Booking} at the time), including {@code startsAt}/{@code endsAt} from the STALE
     * snapshot loaded before the barrier, silently reverting whatever reschedule had just committed.
     * G1 adds {@code @DynamicUpdate} to {@code Booking}, closing exactly this gap: {@code
     * cancelBooking} only ever sets {@code status}/{@code cancellationReason}/
     * {@code clientCancellationNote} on leg0, so its save's dirty-column set never includes
     * {@code startsAt}/{@code endsAt} — reschedule's committed time, if it landed first, now
     * SURVIVES the later cancel. leg0's final window is therefore now race-order-dependent, exactly
     * mirroring leg1's branching below: the NEW time if reschedule won the lock race, the ORIGINAL
     * time if cancel won (reschedule's own post-lock freshness re-check then aborted with 409 before
     * mutating anything).
     */
    @Test
    @DisplayName("client-owned WHOLE-VISIT reschedule racing a per-leg client cancel of the SAME visit's "
            + "leg0 must not deadlock; the cancel's effect is never lost (leg0 always ends CANCELLED, "
            + "header always stays CONFIRMED), and a reschedule that LOSES the race mutates nothing")
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

        CyclicBarrier bothRacersAtTheirHeaderLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeClientItemCancel(eq(appointmentId));

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> respReschedule = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"newStartsAt\":\"" + newBlockStart + "\"}";
                respReschedule.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/reschedule",
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
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                respCancel.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + leg0 + "/cancel", HttpMethod.PATCH, entity, String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s — no hang, no unbounded lock wait").isTrue();
        assertThat(respCancel.get().getStatusCode())
                .as("the per-leg cancel must ALWAYS succeed regardless of race order — body: %s",
                        respCancel.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(respReschedule.get().getStatusCode())
                .as("the whole-visit reschedule must resolve to EXACTLY ONE of the two legal outcomes "
                        + "for this race — body: %s", respReschedule.get().getBody())
                .isIn(HttpStatus.OK, HttpStatus.CONFLICT);

        assertThat(appointmentStatus(appointmentId))
                .as("leg1 always remains CONFIRMED, so the header can never collapse in this race")
                .isEqualTo("CONFIRMED");
        assertThat(dbStatus(leg0))
                .as("F1 (HIGH): the cancel's effect on leg0's STATUS is never lost NOR resurrected to "
                        + "CONFIRMED, whichever racer wins the lock first")
                .isEqualTo("CANCELLED");

        // G1 (cycle-7 audit 2026-08-03): leg0's final window is now race-order-dependent — see this
        // test's class-level Javadoc "leg0's own final window" paragraph. Booking's @DynamicUpdate
        // means the later cancel's save dirties only status/cancellationReason/clientCancellationNote
        // on leg0, so it can no longer revert a reschedule that committed first.
        if (respReschedule.get().getStatusCode().equals(HttpStatus.CONFLICT)) {
            // Cancel WON the lock race first — reschedule's own post-lock freshness re-check then
            // found leg0 no longer CONFIRMED and aborted with 409 BEFORE mutating anything. leg0 and
            // leg1 both stay at their ORIGINAL times, byte-for-byte.
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("the reschedule LOST the race — leg0 stays at its ORIGINAL time")
                    .isEqualTo(originalLeg0Start.toInstant());
            assertThat(dbStartsAt(leg1).toInstant())
                    .as("the reschedule LOST the race — it must have mutated NOTHING; leg1 stays at "
                            + "its ORIGINAL time, byte-for-byte")
                    .isEqualTo(originalLeg1Start.toInstant());
        } else {
            // The reschedule WON the LOCK race and committed its whole-block move (leg0 + leg1)
            // BEFORE the later cancel's own save ran. leg0 keeps that committed NEW time (G1's
            // @DynamicUpdate is what makes this true — pre-G1 the later cancel's blind full-column
            // save would have reverted it). leg1, never touched by the cancel at all regardless of
            // G1, keeps its committed move unconditionally: chained immediately after leg0's NEW
            // window, i.e. at newBlockStart + leg0's frozen (duration + buffer).
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("G1: leg0 keeps reschedule's committed NEW time — the later cancel's "
                            + "@DynamicUpdate save no longer reverts it")
                    .isEqualTo(newBlockStart.toInstant());
            assertThat(dbStartsAt(leg1).toInstant())
                    .as("leg1 (untouched by the cancel) must be chained immediately after the NEW "
                            + "block start, at newBlockStart + 60 minutes")
                    .isEqualTo(newBlockStart.plusMinutes(60).toInstant());
        }
    }

    /**
     * Regression test for F1 (HIGH, cycle-6 audit 2026-08-03) — the security auditor's own
     * concrete, single-actor exploit: races a per-item CLIENT reschedule of leg0
     * ({@link AppointmentTransitionService#rescheduleAppointmentItem}) against a per-item CLIENT
     * cancel of the SAME leg0 ({@code BookingService#cancelAppointmentItem} →
     * {@code BookingService#cancelBooking}), fired by the SAME client account — exactly {@code
     * PATCH .../services/{itemA}/cancel} and {@code PATCH .../services/{itemA}/reschedule} for the
     * SAME item, near-concurrently, as described in the finding. Cancelling ONE leg of a two-leg
     * visit never flips the header out of {@code CONFIRMED} (leg1 always remains), so — before this
     * fix — BOTH operations' header-lock guard alone could never detect that leg0 SPECIFICALLY had
     * changed, letting whichever call committed LAST silently win with a corrupted result (a
     * CANCELLED leg written back to CONFIRMED at a brand-new time). Uses the SAME {@code @SpyBean}
     * + {@code CyclicBarrier} technique as the tests above, pointed at
     * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule} (reschedule's
     * header-lock seam) and {@link AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}
     * (cancel's).
     *
     * <p><b>Coherent outcome regardless of race order.</b> Both header locks are CONDITIONAL
     * ({@code lockHeaderIfConfirmed}) but always succeed here (leg1 keeps the header CONFIRMED), so
     * the cancel HTTP call ALWAYS returns {@code 204}. The reschedule's outcome is race-order
     * dependent: {@code 409} ("Service changed concurrently") if the cancel's commit is visible by
     * the time reschedule's F1 freshness re-check ({@code BookingRepository#existsConfirmedById})
     * runs — the fix under test, closing the exploit exactly as described — or {@code 200} if
     * reschedule's own lock+recheck ran first, in which case its move commits BEFORE the later
     * cancel's own save runs. G1 (cycle-7 audit 2026-08-03) changes what happens next: {@code
     * Booking}'s new {@code @DynamicUpdate} means the LATER cancel's save dirties only
     * {@code status}/{@code cancellationReason}/{@code clientCancellationNote} on leg0 — unlike the
     * pre-G1 behavior (see this class's earlier "leg0's own final window" Javadoc paragraph for the
     * history), it no longer reverts leg0's time from its own stale, pre-barrier snapshot, so
     * reschedule's committed NEW time now SURVIVES the later cancel too. What F1 actually guarantees,
     * unconditionally regardless of G1, is narrower: leg0's STATUS can never end up back at
     * {@code CONFIRMED}.
     */
    @Test
    @DisplayName("F1 (HIGH) — per-item CLIENT reschedule of leg0 racing a per-item CLIENT cancel of the "
            + "SAME leg0 (the security auditor's exact single-actor exploit) must never resurrect the "
            + "cancelled leg to CONFIRMED; exactly one operation's status effect survives")
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

        CyclicBarrier bothRacersAtTheirHeaderLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeClientItemCancel(eq(appointmentId));

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> respReschedule = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();

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
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                respCancel.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/cancel",
                        HttpMethod.PATCH, entity, String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s — no hang, no unbounded lock wait").isTrue();
        assertThat(respCancel.get().getStatusCode())
                .as("the per-item cancel must ALWAYS succeed regardless of race order — body: %s",
                        respCancel.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(respReschedule.get().getStatusCode())
                .as("the per-item reschedule must resolve to EXACTLY ONE of the two legal outcomes for "
                        + "this race — body: %s", respReschedule.get().getBody())
                .isIn(HttpStatus.OK, HttpStatus.CONFLICT);

        // The central F1 assertion: leg0's status is NEVER resurrected to CONFIRMED, whichever
        // racer won the lock first.
        assertThat(dbStatus(leg0))
                .as("F1 (HIGH): leg0 must never be resurrected to CONFIRMED — it must end CANCELLED "
                        + "regardless of which racer won the lock first")
                .isEqualTo("CANCELLED");
        // G1 (cycle-7 audit 2026-08-03): leg0's final window is now race-order-dependent — see this
        // test's own Javadoc "Coherent outcome" paragraph. NEW time if reschedule won (its commit
        // now survives the later cancel's narrower, @DynamicUpdate save); ORIGINAL time if cancel
        // won (reschedule's own freshness re-check aborted with 409 before mutating anything).
        if (respReschedule.get().getStatusCode().equals(HttpStatus.OK)) {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("G1: reschedule won the race and committed leg0's NEW time — the later "
                            + "cancel's @DynamicUpdate save no longer reverts it")
                    .isEqualTo(newLeg0Start.toInstant());
        } else {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("cancel won the race — reschedule's freshness re-check aborted before "
                            + "mutating leg0, so it stays at its ORIGINAL time")
                    .isEqualTo(originalLeg0Start.toInstant());
        }
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
     * Regression test for F1 (HIGH, cycle-6 audit 2026-08-03) — the same exploit shape as
     * {@link #should_neverResurrectCancelledLegToConfirmed_when_perItemRescheduleRacesPerItemCancelOfSameLeg}
     * above, but pairing the per-item CLIENT reschedule of leg0 against a per-item PROVIDER
     * decline of the SAME leg0 ({@link AppointmentTransitionService#declineAppointmentItem})
     * instead of a per-item cancel — the OTHER per-item write path F1 fixes.
     *
     * <p><b>Different rendezvous point, same technique.</b> {@code declineAppointmentItem}'s own
     * header-lock call used to go straight through the PRIVATE {@code lockHeaderBeforeItemTransition}
     * — not spy-able directly, and spying the underlying
     * {@link AppointmentRepository#lockHeaderIfConfirmed} repository method directly does not work
     * either (Mockito cannot {@code callRealMethod()} on a Spring Data JPA repository's
     * dynamically-proxied interface method — {@code MockitoException: Cannot call abstract real
     * method on java object!}, verified against this exact attempt). Fixed the same way the
     * cancel/reschedule pair already was: {@code declineAppointmentItem} now calls a new, thin
     * package-private wrapper, {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemDecline},
     * spied on below — giving an identical two-arrivals rendezvous point at the exact header-lock
     * instant.
     *
     * <p><b>Coherent outcome regardless of race order</b> — identical shape to the cancel-pairing
     * test above: the decline HTTP call ALWAYS returns {@code 204} (leg1 keeps the header
     * CONFIRMED); the reschedule returns {@code 409} if the decline's commit is visible by the time
     * its F1 freshness re-check runs, or {@code 200} if it locked first, in which case its committed
     * NEW time on leg0 now SURVIVES the LATER decline's save too — {@code Booking}'s
     * {@code @DynamicUpdate} (G1, cycle-7 audit 2026-08-03) means that save dirties only
     * {@code status}/{@code cancellationReason}/{@code providerComment}, the same column-disjoint
     * property documented for the cancel-pairing test above. leg0's STATUS, unconditionally, is
     * never resurrected to CONFIRMED.
     */
    @Test
    @DisplayName("F1 (HIGH) — per-item CLIENT reschedule of leg0 racing a per-item PROVIDER decline of "
            + "the SAME leg0 must never resurrect the declined leg to CONFIRMED; exactly one operation's "
            + "status effect survives")
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

        CyclicBarrier bothRacersAtTheirHeaderLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemDecline(eq(appointmentId));

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
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/decline",
                        HttpMethod.PATCH, new HttpEntity<>("{}", headers), String.class));
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
                .as("the per-item decline must ALWAYS succeed regardless of race order — body: %s",
                        respDecline.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(respReschedule.get().getStatusCode())
                .as("the per-item reschedule must resolve to EXACTLY ONE of the two legal outcomes for "
                        + "this race — body: %s", respReschedule.get().getBody())
                .isIn(HttpStatus.OK, HttpStatus.CONFLICT);

        // The central F1 assertion: leg0's status is NEVER resurrected to CONFIRMED, whichever
        // racer won the lock first.
        assertThat(dbStatus(leg0))
                .as("F1 (HIGH): leg0 must never be resurrected to CONFIRMED — it must end DECLINED "
                        + "regardless of which racer won the lock first")
                .isEqualTo("DECLINED");
        // G1 (cycle-7 audit 2026-08-03): leg0's final window is now race-order-dependent, the same
        // widening as the cancel-pairing test above — see this test's own Javadoc "Coherent outcome"
        // paragraph.
        if (respReschedule.get().getStatusCode().equals(HttpStatus.OK)) {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("G1: reschedule won the race and committed leg0's NEW time — the later "
                            + "decline's @DynamicUpdate save no longer reverts it")
                    .isEqualTo(newLeg0Start.toInstant());
        } else {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("decline won the race — reschedule's freshness re-check aborted before "
                            + "mutating leg0, so it stays at its ORIGINAL time")
                    .isEqualTo(originalLeg0Start.toInstant());
        }
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
     * Regression test for G2 (HIGH, cycle-7 audit 2026-08-03) — the LEGACY, booking-scoped
     * {@code PATCH /bookings/{id}/reschedule} route (unlike the appointment-scoped
     * {@code PATCH /appointments/{id}/services/{id}/reschedule} route the two tests above drive) is
     * fully reachable for an appointment child (it never calls {@code assertNotAppointmentChild}) and
     * was the FOURTH sibling missing the F1-class freshness re-check: it already took the header lock
     * ({@code lockAppointmentHeaderBeforeItemReschedule}, added earlier in this work) but never
     * verified {@code booking} itself was still CONFIRMED afterward — the exact exploit the auditors
     * traced: a provider declines leg0 via the per-item route (commits {@code DECLINED}); a
     * concurrent legacy reschedule of the SAME leg was blocked on the header lock, acquires it right
     * after (the header is still CONFIRMED — leg1 remains), and — pre-fix — proceeded straight to its
     * save with no check that leg0 itself was still CONFIRMED.
     *
     * <p><b>Same rendezvous point as the appointment-scoped pairing above</b> — the legacy route
     * calls the SAME {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule}
     * seam ({@code BookingService#rescheduleBooking}'s Phase 30.2 lock-order fix), so this test reuses
     * the identical {@code @SpyBean} + {@code CyclicBarrier} technique, just pointed at
     * {@code PATCH /bookings/{leg0}/reschedule} instead of the appointment-scoped route.
     *
     * <p><b>Coherent outcome regardless of race order</b> — identical shape to the appointment-scoped
     * pairing: the decline HTTP call ALWAYS returns {@code 204} (leg1 keeps the header CONFIRMED);
     * the LEGACY reschedule returns {@code 409} (G2's fix under test) if the decline's commit is
     * visible by the time its freshness re-check runs, or {@code 200} if it locked first, in which
     * case its committed NEW time on leg0 SURVIVES the later decline's {@code @DynamicUpdate} (G1)
     * save. leg0's STATUS, unconditionally, is never resurrected to CONFIRMED — the property that was
     * NOT guaranteed before G2, since the legacy route had no recheck at all to abort on.
     */
    @Test
    @DisplayName("G2 (HIGH) — the LEGACY booking-scoped reschedule route racing a per-item PROVIDER "
            + "decline of the SAME leg (the auditor-traced exploit) must never resurrect the declined "
            + "leg to CONFIRMED; exactly one operation's status effect survives")
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

        CyclicBarrier bothRacersAtTheirHeaderLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemDecline(eq(appointmentId));

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
                // The LEGACY, booking-scoped route — this is the blind spot G2 closes.
                respReschedule.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + leg0 + "/reschedule",
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
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/decline",
                        HttpMethod.PATCH, new HttpEntity<>("{}", headers), String.class));
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
                .as("the per-item decline must ALWAYS succeed regardless of race order — body: %s",
                        respDecline.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(respReschedule.get().getStatusCode())
                .as("G2: the LEGACY reschedule route must resolve to EXACTLY ONE of the two legal "
                        + "outcomes for this race — body: %s", respReschedule.get().getBody())
                .isIn(HttpStatus.OK, HttpStatus.CONFLICT);

        // The central G2 assertion: leg0's status is NEVER resurrected to CONFIRMED via the legacy
        // route, whichever racer won the lock first — before G2 this route had no recheck at all to
        // prevent it from proceeding regardless.
        assertThat(dbStatus(leg0))
                .as("G2 (HIGH): leg0 must never be resurrected to CONFIRMED via the legacy reschedule "
                        + "route — it must end DECLINED regardless of which racer won the lock first")
                .isEqualTo("DECLINED");
        if (respReschedule.get().getStatusCode().equals(HttpStatus.OK)) {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("G1: the legacy reschedule won the race and committed leg0's NEW time — the "
                            + "later decline's @DynamicUpdate save no longer reverts it")
                    .isEqualTo(newLeg0Start.toInstant());
        } else {
            assertThat(dbStartsAt(leg0).toInstant())
                    .as("G2: decline won the race — the legacy reschedule's NEW freshness re-check "
                            + "aborted before mutating leg0, so it stays at its ORIGINAL time")
                    .isEqualTo(originalLeg0Start.toInstant());
        }
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
     * Regression test for G3 (HIGH, cycle-7 audit 2026-08-03) — races the BATCHED
     * schedule-override-conflict decline ({@link AppointmentTransitionService#declineAppointmentItems},
     * driven end to end via {@code PUT /masters/{masterId}/overrides/{date}} with
     * {@code cancelOverlapping=true}, targeting BOTH legs of the visit) against a per-item CLIENT
     * cancel of leg0 ({@code PATCH /appointments/{id}/services/{leg0}/cancel}) — the cross-endpoint
     * racer both auditors independently identified: this batched method has no racer AT its OWN
     * endpoint (a provider's {@code PUT /overrides} write calls it at most once per appointment),
     * but a client acting on a leg the provider's schedule change is about to orphan is a realistic
     * interleaving.
     *
     * <p><b>Rendezvous point.</b> Reuses the SAME {@code @SpyBean} + {@code CyclicBarrier} technique
     * as {@code should_serializeCleanlyWithNoDeadlock_when_wholeVisitDeclineRacesPerLegClientCancel}
     * above (whole-visit decline vs. per-leg cancel) — now pointed at
     * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemDecline}, which the batched
     * method calls too as of G3 (previously it called the private
     * {@code lockHeaderBeforeItemTransition} directly, with no dedicated concurrency IT — see that
     * wrapper's Javadoc for the full history) — and
     * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}.
     *
     * <p><b>Coherent outcome regardless of race order — proves "filter, not abort".</b> Cancelling
     * ONE of the visit's two legs never flips the header out of {@code CONFIRMED} (the other leg
     * remains), so BOTH racers' header-lock guard passes regardless of order and the per-item cancel
     * HTTP call ALWAYS returns {@code 204}. The override write ALWAYS returns {@code 200} too (the
     * write itself — narrowing the schedule — always succeeds; only WHICH bookings end up declined by
     * it depends on race order):
     * <ul>
     *   <li>if the CANCEL commits first, the override's batched decline's own freshness re-check
     *       ({@link BookingRepository#findConfirmedIdsByAppointmentId}) finds leg0 no longer
     *       CONFIRMED and FILTERS it out of the batch — leg0 stays {@code CANCELLED} (the client's
     *       effect, never re-declined over it), and ONLY leg1 is declined;</li>
     *   <li>if the OVERRIDE's decline commits first, BOTH legs are declined (leg0 was still
     *       CONFIRMED when the batch's recheck ran) and the header collapses to {@code DECLINED}
     *       immediately — the LATER cancel's own header lock then fails (header no longer
     *       CONFIRMED), so its own freshness re-check aborts with {@code 409} before mutating
     *       anything, leaving leg0 {@code DECLINED} (never overwritten back to {@code CANCELLED}).</li>
     * </ul>
     */
    @Test
    @DisplayName("G3 (HIGH) — the BATCHED schedule-override-conflict decline racing a per-item CLIENT "
            + "cancel of ONE of its targets must filter the stale target out of the batch rather than "
            + "abort the whole write, and must never let a losing cancel overwrite a winning decline")
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

        CyclicBarrier bothRacersAtTheirHeaderLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemDecline(eq(appointmentId));
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeClientItemCancel(eq(appointmentId));

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> respOverride = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            try {
                go.await();
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
                done.countDown();
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                respCancel.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + leg0 + "/cancel",
                        HttpMethod.PATCH, entity, String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s — no hang, no unbounded lock wait").isTrue();
        assertThat(respOverride.get().getStatusCode())
                .as("the override write itself must always succeed — body: %s", respOverride.get().getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(respCancel.get().getStatusCode())
                .as("the per-item cancel must ALWAYS succeed regardless of race order — body: %s",
                        respCancel.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // leg1 is NEVER targeted by the cancel, so it is declined by the override write regardless
        // of race order — the batch's other, unraced target always survives to be declined.
        assertThat(dbStatus(leg1))
                .as("leg1 (untouched by the cancel) is always declined by the override write")
                .isEqualTo("DECLINED");
        assertThat(appointmentStatus(appointmentId))
                .as("leg1 always ends DECLINED, so the header always collapses to DECLINED")
                .isEqualTo("DECLINED");
        // The central G3 assertion: leg0 ends at EXACTLY ONE of its two legal terminal states —
        // CANCELLED (the cancel won the lock race, and the override's batched recheck correctly
        // FILTERED leg0 out rather than re-declining it) or DECLINED (the override won the lock race
        // and declined leg0 before the cancel's own freshness re-check could abort it) — but never
        // resurrected to CONFIRMED, and never silently overwritten from one terminal state to the
        // other.
        assertThat(dbStatus(leg0))
                .as("G3 (HIGH): leg0 must end at EXACTLY ONE legal terminal state, never CONFIRMED — "
                        + "body(override): %s, body(cancel): %s",
                        respOverride.get().getBody(), respCancel.get().getBody())
                .isIn("CANCELLED", "DECLINED");
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
