package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency regression for {@code BookingService#acquireMasterLockForSelfDelete} (Phase 337 QA
 * audit) — races a client {@code POST /bookings} for a departing master against that SAME
 * master's own {@code DELETE /api/v1/users/me} self-delete, WITHOUT going through Mockito: {@code
 * BookingService#acquireMasterLockForSelfDelete} and {@code doCreateBooking}'s own lock statement
 * are both {@code @Transactional(propagation = MANDATORY-or-default)} and, empirically, a {@code
 * @SpyBean} on {@code BookingService} cannot stub either without the FIRST {@code .when(spy).method()}
 * setup call itself running the real, CGLIB-transactional-advised method body (observed: it throws
 * {@code IllegalTransactionStateException} for the MANDATORY-propagation lock seam, outside of any
 * transaction, before a single test assertion runs) — this codebase's proven one-sided-gate spy
 * technique ({@code SalonCreateCapConcurrencyIT}) does not carry over to a method this deep in a
 * {@code @Transactional} call chain. This test instead holds the REAL Postgres advisory lock
 * directly, via a raw JDBC connection executing the EXACT statement {@link
 * com.beautica.booking.repository.BookingRepository#acquireAdvisoryLockWithTimeout} does ({@code
 * pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))}, salt {@code 0}), and
 * orders the two racing requests deterministically by polling {@code pg_stat_activity} for each
 * one's OWN wait on that lock before releasing it — no mocking, no app-code hook at all.
 *
 * <p><b>The race this proves/disproves.</b> {@code BookingService#doCreateBooking} resolves and
 * bookability-filters the {@code Master} entity ({@code MasterBookability::isBookable} — requires
 * {@code masters.is_active = true}) BEFORE it ever reaches the salt-0 advisory lock, and never
 * re-validates bookability after the lock is granted. If a create's bookability check runs WHILE
 * the master is still active, then the create blocks on the lock while self-delete — queued for
 * the SAME lock FIRST — disposes of nothing (no booking exists yet), detaches/hard-deletes the
 * master and commits (releasing the lock), the create's blocked call unblocks holding a STALE,
 * already-validated {@code Master} reference and nothing downstream stops it from writing a brand
 * new {@code CONFIRMED} booking against a provider that no longer exists in any usable sense.
 *
 * <p><b>Now closed by {@code PostLockSlotGuard#assertStillFreeAfterLock}</b> (Phase 337 fix; fused
 * with the overlap re-check into one statement by the Phase 337 follow-up, LOW perf), called
 * immediately after every create path's master advisory lock is granted and before any insert — a
 * scalar, identity-map-bypassing re-check of {@code MasterBookability.isBookable}'s exact
 * predicate, since a re-fetched {@code Master} ENTITY would be served the same stale managed
 * instance from the persistence-context identity map. This class is the regression guard for that
 * fix, extended (this same class, one shared harness — see {@link
 * #raceSelfDeleteAgainstMasterCreate}) to race self-delete against EVERY booking-CREATE surface
 * that takes the salt-0 master lock:
 * <ol>
 *   <li>{@link #should_neverLeaveConfirmedBooking_when_createRacesMasterSelfDelete} — {@code
 *       POST /bookings} (single service, authenticated APP)</li>
 *   <li>{@link #should_neverLeaveConfirmedBooking_when_visitCreateRacesMasterSelfDelete} — {@code
 *       POST /appointments} (multi-service visit, authenticated APP)</li>
 *   <li>{@link #should_neverLeaveConfirmedBooking_when_guestCreateRacesMasterSelfDelete} — {@code
 *       POST /book/{slug}/booking} (guest LINK, {@code permitAll})</li>
 *   <li>{@link #should_neverLeaveConfirmedBooking_when_staffWalkInCreateRacesMasterSelfDelete} —
 *       {@code POST /masters/{id}/bookings} (staff walk-in)</li>
 * </ol>
 */
@Import(TestSecurityConfig.class)
@DisplayName("SALON_MASTER self-delete racing a client create for the SAME master (Phase 337 QA)")
class MasterSelfDeleteCreateRaceConcurrencyIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MasterSelfDeleteCreateRaceConcurrencyIT.class);

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String USERS_ME_URL = "/api/v1/users/me";
    private static final String LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))";
    private static final String WAITER_COUNT_SQL =
            "SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND wait_event = 'advisory' AND pid <> pg_backend_pid()";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private GuestTokenProvider guestTokenProvider;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("a client create for a master mid-self-delete, queued behind self-delete on the "
            + "SAME advisory lock, must NOT end up CONFIRMED once self-delete has committed and "
            + "detached the master — either the create is itself rejected, or its slot is caught "
            + "by self-delete's own decline cascade; never left dangling CONFIRMED")
    void should_neverLeaveConfirmedBooking_when_createRacesMasterSelfDelete() throws Exception {
        MasterAndBookableService fixture = seedDetachableMasterWithService("bookings");
        String clientEmail = "self-delete-race-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(11).withMinute(0).withSecond(0).withNano(0);

        AtomicReference<ResponseEntity<String>> createResponse = new AtomicReference<>();
        raceSelfDeleteAgainstMasterCreate(fixture.masterId(), fixture.masterToken(), () -> createResponse.set(
                createBooking(clientToken, fixture.masterId(), fixture.masterServiceId(), startsAt)));

        // THE CENTRAL INVARIANT: however the race resolved (create 404s because the master is
        // already gone by the time it unblocks, or create 201s and is then itself covered by
        // whichever guard exists), no CONFIRMED booking may exist for this master once both
        // requests have finished.
        assertNoConfirmedBookingSurvived(fixture.masterId(), createResponse.get());
    }

    @Test
    @DisplayName("a multi-service VISIT create (POST /appointments) for a master mid-self-delete, "
            + "queued behind self-delete on the SAME advisory lock, must NOT end up CONFIRMED once "
            + "self-delete has committed and detached the master")
    void should_neverLeaveConfirmedBooking_when_visitCreateRacesMasterSelfDelete() throws Exception {
        MasterAndBookableService fixture = seedDetachableMasterWithService("visit");
        String clientEmail = "self-delete-race-visit-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(11).withMinute(0).withSecond(0).withNano(0);

        AtomicReference<ResponseEntity<String>> createResponse = new AtomicReference<>();
        raceSelfDeleteAgainstMasterCreate(fixture.masterId(), fixture.masterToken(), () -> createResponse.set(
                createVisit(clientToken, fixture.masterId(), fixture.masterServiceId(), startsAt)));

        assertNoConfirmedBookingSurvived(fixture.masterId(), createResponse.get());
    }

    @Test
    @DisplayName("a guest (LINK) create (POST /book/{slug}/booking) for a master mid-self-delete, "
            + "queued behind self-delete on the SAME advisory lock, must NOT end up CONFIRMED once "
            + "self-delete has committed and detached the master")
    void should_neverLeaveConfirmedBooking_when_guestCreateRacesMasterSelfDelete() throws Exception {
        MasterAndBookableService fixture = seedDetachableMasterWithService("guest");
        String slug = "self-delete-race-" + Long.toString(System.nanoTime(), 36);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, fixture.masterId());
        String guestToken = guestTokenProvider.generate("+380961112233");

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(11).withMinute(0).withSecond(0).withNano(0);

        AtomicReference<ResponseEntity<String>> createResponse = new AtomicReference<>();
        raceSelfDeleteAgainstMasterCreate(fixture.masterId(), fixture.masterToken(), () -> createResponse.set(
                createGuestBooking(guestToken, slug, fixture.masterServiceId(), startsAt)));

        assertNoConfirmedBookingSurvived(fixture.masterId(), createResponse.get());
    }

    @Test
    @DisplayName("a walk-in create (POST /masters/{id}/bookings) racing the SAME independent "
            + "master's own self-delete, queued behind it on the SAME advisory lock, must NOT end "
            + "up CONFIRMED once self-delete has committed and detached the master")
    void should_neverLeaveConfirmedBooking_when_staffWalkInCreateRacesMasterSelfDelete() throws Exception {
        // Unlike the three client/guest-initiated paths above, the walk-in create's ONLY legal
        // actor for an INDEPENDENT_MASTER target is the master themselves — canBookForMaster
        // rejects SALON_MASTER outright and requires target.getUser() to equal the actor for an
        // INDEPENDENT_MASTER target. So the racer here is the SAME account that is self-deleting —
        // the realistic shape of this race for a walk-in: a solo master keys in a walk-in client
        // moments before deleting their own account, from two concurrent requests.
        MasterAndBookableService fixture = seedDetachableMasterWithService("walkin");

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(11).withMinute(0).withSecond(0).withNano(0);

        AtomicReference<ResponseEntity<String>> createResponse = new AtomicReference<>();
        raceSelfDeleteAgainstMasterCreate(fixture.masterId(), fixture.masterToken(), () -> createResponse.set(
                createWalkIn(fixture.masterToken(), fixture.masterId(), fixture.masterServiceId(), startsAt)));

        assertNoConfirmedBookingSurvived(fixture.masterId(), createResponse.get());
    }

    // ── shared race harness ────────────────────────────────────────────────────

    private record MasterAndBookableService(UUID masterId, String masterToken, UUID masterServiceId) {}

    /**
     * Seeds an {@code INDEPENDENT_MASTER} with one active service, a full weekly schedule, and one
     * PAST {@code COMPLETED} booking — the last of which is load-bearing (see the inline comment on
     * the original single-service test this was extracted from): it is what routes the racing
     * self-delete's disposal to DETACH rather than a hard {@code DELETE} of the {@code masters} row,
     * which is the only branch where the application's OWN post-lock re-check (rather than a bare FK
     * violation) is what has to stop the racing create.
     */
    private MasterAndBookableService seedDetachableMasterWithService(String tag) throws Exception {
        String masterEmail = "self-delete-race-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        String masterToken = fixtures.tokenFor(masterEmail);

        ClientSelfDeleteTestFixtures csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
        UUID pastClientId = csd.createClient();
        csd.insertBooking(pastClientId, masterId, masterServiceId, null, "COMPLETED",
                OffsetDateTime.now().minusDays(3));

        return new MasterAndBookableService(masterId, masterToken, masterServiceId);
    }

    /**
     * Shared race harness (Phase 337 QA) for every booking-CREATE path against the SAME master's
     * self-delete on the shared salt-0 advisory lock. Holds the real lock via a raw, manually
     * committed JDBC connection so self-delete queues first and {@code createAction} queues second —
     * Postgres grants a contended lock in wait-queue order — then releases it only once BOTH racers
     * are OBSERVABLY waiting on it (falsifiable: the test fails outright, not silently, if they are
     * not). Not forked per create path — see the class Javadoc for why a {@code @SpyBean} approach
     * does not reach this deep into a {@code @Transactional} call chain, which is why this harness
     * exists at all.
     *
     * @param masterId     the master both racers target
     * @param masterToken  the departing master's own bearer token, used for the DELETE
     * @param createAction fires the create HTTP call and records its own outcome (typically into an
     *                     {@link AtomicReference} the caller reads afterward); run on its own
     *                     virtual thread, launched only after self-delete is already observably
     *                     queued on the lock
     */
    private void raceSelfDeleteAgainstMasterCreate(UUID masterId, String masterToken, Runnable createAction)
            throws Exception {
        // Hold the REAL salt-0 advisory lock for this master via a raw, manually-committed
        // connection — neither self-delete nor create can proceed past their own lock statement
        // while this is open.
        Connection holder = jdbcTemplate.getDataSource().getConnection();
        holder.setAutoCommit(false);
        try (PreparedStatement ps = holder.prepareStatement(LOCK_SQL)) {
            ps.setString(1, masterId.toString());
            ps.execute();
        }

        try {
            // Launch self-delete FIRST so it is queued for the lock BEFORE the create — Postgres
            // grants a contended lock in wait-queue order, so releasing the held lock below hands
            // it to self-delete first, exactly the interleaving this test needs to exercise.
            CountDownLatch selfDeleteDone = new CountDownLatch(1);
            AtomicReference<ResponseEntity<Void>> selfDeleteResponse = new AtomicReference<>();
            log.debug("Act: launch DELETE {} for the master — queues behind the held lock", USERS_ME_URL);
            Thread.ofVirtual().start(() -> {
                try {
                    selfDeleteResponse.set(restTemplate.exchange(
                            USERS_ME_URL, HttpMethod.DELETE,
                            new HttpEntity<>(fixtures.bearerHeaders(masterToken)), Void.class));
                } finally {
                    selfDeleteDone.countDown();
                }
            });
            assertThat(awaitWaiterCount(1))
                    .as("self-delete must be observably queued on the master's advisory lock within 10s")
                    .isTrue();

            CountDownLatch createDone = new CountDownLatch(1);
            log.debug("Act: launch the racing create for the SAME master — queues SECOND, behind self-delete");
            Thread.ofVirtual().start(() -> {
                try {
                    createAction.run();
                } finally {
                    createDone.countDown();
                }
            });
            // FALSIFIABLE ASSERTION: both racers must be genuinely queued on the SAME lock before
            // it is released, or this test is not exercising the interleaving it claims to.
            assertThat(awaitWaiterCount(2))
                    .as("both self-delete AND create must be observably queued on the master's "
                            + "advisory lock within 10s — if this is false the two paths are not "
                            + "actually contending for the same lock and the rest of this test "
                            + "proves nothing")
                    .isTrue();

            holder.commit();
            holder.close();

            assertThat(selfDeleteDone.await(20, TimeUnit.SECONDS))
                    .as("self-delete must finish within 20s of the lock being released").isTrue();
            assertThat(createDone.await(20, TimeUnit.SECONDS))
                    .as("create must finish within 20s of self-delete releasing the real lock").isTrue();

            assertThat(selfDeleteResponse.get().getStatusCode())
                    .as("self-delete itself must always succeed regardless of how the race resolves")
                    .isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            if (!holder.isClosed()) {
                holder.rollback();
                holder.close();
            }
        }
    }

    /**
     * Asserts BOTH the row-count invariant AND the create response's exact shape.
     *
     * <p><b>Why the status/body assertion, not just the row count (Phase 337 QA follow-up).</b> The
     * harness always releases the held lock to self-delete FIRST (see {@link
     * #raceSelfDeleteAgainstMasterCreate}), and {@link #seedDetachableMasterWithService} guarantees
     * self-delete DETACHES rather than hard-deletes the {@code masters} row — so by the time the
     * queued create resumes past the advisory lock, {@code PostLockSlotGuard#assertStillFreeAfterLock}
     * deterministically reads {@code master_bookable = false} (the just-detached row's {@code
     * is_active = false}) and rejects with exactly {@code 404 "Master not found or inactive"} →
     * {@code ApiResponse.error("Resource not found")} on the wire. That is the ONLY correct outcome
     * here, every time — not merely "some 4xx".
     *
     * <p>Counting rows alone does not pin this: for the walk-in path specifically, a DEFEATED guard
     * (one that always answers {@code master_bookable = true}) still leaves {@code confirmedCount}
     * at zero, because the insert is instead rejected by an unrelated FK/constraint violation
     * (surfacing as a generic {@code 409}) — the row-count assertion alone passes vacuously in that
     * case. Asserting the response is exactly {@code 404} makes a defeated guard visible on every one
     * of the four create paths.
     */
    private void assertNoConfirmedBookingSurvived(UUID masterId, ResponseEntity<String> createResponse) {
        assertThat(createResponse.getStatusCode())
                .as("the queued create must be rejected by PostLockSlotGuard's post-lock bookability "
                        + "re-check with exactly 404, not merely some other 4xx (e.g. a 409 from an "
                        + "unrelated FK/constraint violation, which a defeated guard could still hit "
                        + "and would make this assertion pass vacuously) — body: %s",
                        createResponse.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(createResponse.getBody())
                .as("the 404 must be PostLockSlotGuard's own \"Master not found or inactive\" path, "
                        + "surfaced through GlobalExceptionHandler#handleNotFound's generic "
                        + "ApiResponse.error(\"Resource not found\") envelope — not some other "
                        + "handler that also happens to return 404")
                .contains("\"success\":false")
                .contains("\"message\":\"Resource not found\"");

        Integer confirmedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = 'CONFIRMED'",
                Integer.class, masterId);
        assertThat(confirmedCount)
                .as("no CONFIRMED booking may survive a create that raced the master's own "
                        + "self-delete — create response status was %s, body: %s",
                        createResponse.getStatusCode(), createResponse.getBody())
                .isZero();
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    /** Polls {@code pg_stat_activity} until at least {@code expected} backends are observably
     * waiting on an advisory lock, or 10s elapse. */
    private boolean awaitWaiterCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject(WAITER_COUNT_SQL, Integer.class);
            if (waiters != null && waiters >= expected) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private ResponseEntity<String> createBooking(
            String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt) {
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null, false);
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }

    private ResponseEntity<String> createVisit(
            String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "masterId", masterId.toString(),
                    "masterServiceIds", List.of(masterServiceId.toString()),
                    "startsAt", startsAt.toOffsetDateTime().toString()));
            HttpHeaders headers = fixtures.bearerHeaders(clientToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.exchange(
                    APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<String> createGuestBooking(
            String guestToken, String slug, UUID masterServiceId, ZonedDateTime startsAt) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "serviceId", masterServiceId.toString(),
                    "startsAt", startsAt.toOffsetDateTime().toString(),
                    "name", "Олена",
                    "surname", "Коваль"));
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(guestToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<String> createWalkIn(
            String masterToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "masterServiceIds", List.of(masterServiceId.toString()),
                    "startsAt", startsAt.toOffsetDateTime().toString(),
                    "guest", Map.of(
                            "name", "Тест",
                            "surname", "Клієнт",
                            "phone", "+380671112233")));
            HttpHeaders headers = fixtures.bearerHeaders(masterToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.exchange("/api/v1/masters/" + masterId + "/bookings", HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
