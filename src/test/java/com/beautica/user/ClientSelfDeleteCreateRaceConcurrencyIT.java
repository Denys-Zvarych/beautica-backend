package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency regression for {@code BookingService#acquireClientLockForSelfDelete} (Phase 338 race
 * fix) — races a client's own {@code POST /bookings} against that SAME client's own {@code DELETE
 * /api/v1/users/me} self-delete. Mirrors {@code MasterSelfDeleteCreateRaceConcurrencyIT}'s harness
 * technique exactly, salt {@code 1} (the client lock) instead of salt {@code 0} (the master lock):
 * holds the REAL Postgres advisory lock via a raw JDBC connection executing the EXACT statement
 * {@link com.beautica.booking.repository.BookingRepository#acquireClientAdvisoryLockWithTimeout}
 * does, and orders the two racing requests deterministically by polling {@code pg_stat_activity} for
 * each one's OWN wait on that lock before releasing it.
 *
 * <p><b>The race this proves/disproves.</b> {@code ClientAccountDeletionService#deleteOwnAccount}
 * takes a {@code SELECT ... FOR UPDATE} row lock on the client's OWN {@code users} row (step 1) — a
 * completely different Postgres primitive from the {@code pg_advisory_xact_lock} {@code
 * BookingService#doCreateBooking} already takes (salt 1) before writing a new booking for that same
 * client. Before Phase 338's fix, self-delete never took this SAME lock, so a concurrent create could
 * commit a brand-new CONFIRMED booking for a client whose {@code users} row self-delete was about to
 * hard-delete, in the gap between self-delete's candidate scan and its own commit.
 *
 * <p><b>What actually stops it either way — and what this test isolates.</b> Even withOUT this fix,
 * the widened {@code chk_bookings_guest_fields} CHECK (V162) already makes a CONFIRMED booking
 * silently SURVIVING under a hard-deleted client structurally impossible: whichever side commits
 * SECOND fails outright — either self-delete's own hard-delete aborts on the FK's {@code ON DELETE
 * SET NULL} action writing a half-detached row no CHECK arm accepts, or the racing create's INSERT
 * fails its {@code fk_bookings_client} foreign-key check because the referenced {@code users} row is
 * already gone (translated to a clean {@code 409} by {@code GlobalExceptionHandler
 * #handleDataIntegrityViolation}, never a raw 500). This class does NOT assert "no data corruption" —
 * that invariant holds with or without {@code acquireClientLockForSelfDelete}. It instead asserts
 * that the fix removes the NEED for either side to fail at all: with the SAME lock now shared by both
 * paths, the two requests fully SERIALIZE, so self-delete's own future-booking scan is guaranteed to
 * either see the create's row (and cancel it) or run to completion before the create is even admitted
 * past the lock — the losing racer here is create, cleanly rejected with {@code 409} because the
 * client it targeted no longer exists, never a phantom CONFIRMED row and never an unexplained 500.
 *
 * <p><b>Mutation-proven.</b> Removing {@code ClientAccountDeletionService}'s call to {@code
 * acquireClientLockForSelfDelete} makes this test fail FAST and EXPLICITLY, not silently: self-delete
 * would no longer queue on the salt-1 lock at all, so {@link #awaitWaiterCount(int)}'s "both racers
 * observably queued" assertion times out and the test reports exactly that, rather than degenerating
 * into a flaky pass/fail on real-world timing.
 */
@Import(TestSecurityConfig.class)
@DisplayName("CLIENT self-delete racing that SAME client's own booking create (Phase 338 QA)")
class ClientSelfDeleteCreateRaceConcurrencyIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ClientSelfDeleteCreateRaceConcurrencyIT.class);

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String USERS_ME_URL = "/api/v1/users/me";
    private static final String LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 1))";
    private static final String WAITER_COUNT_SQL =
            "SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND wait_event = 'advisory' AND pid <> pg_backend_pid()";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("a client's own create, queued behind that SAME client's self-delete on the shared "
            + "salt-1 advisory lock, is cleanly rejected 409 (the client no longer exists) once "
            + "self-delete has committed — never left as a CONFIRMED booking under a deleted client")
    void should_rejectCreateCleanly_when_createRacesTheSameClientsSelfDelete() throws Exception {
        String masterEmail = "cs-race-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);

        String clientEmail = "cs-race-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(11).withMinute(0).withSecond(0).withNano(0);

        AtomicReference<ResponseEntity<String>> createResponse = new AtomicReference<>();
        raceSelfDeleteAgainstOwnCreate(clientId, clientToken, () -> createResponse.set(
                createBooking(clientToken, masterId, masterServiceId, startsAt)));

        // The client no longer exists by the time the queued create is admitted past the shared
        // lock — its INSERT's fk_bookings_client check fails, translated to a clean 409 (never a
        // raw 500, never a silently-surviving CONFIRMED row).
        assertThat(createResponse.get().getStatusCode())
                .as("body: %s", createResponse.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);

        Integer bookingsForMaster = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND starts_at = ?",
                Integer.class, masterId, startsAt.toOffsetDateTime());
        assertThat(bookingsForMaster)
                .as("no booking of any status may exist for this slot — the racing INSERT must have "
                        + "failed outright, not partially committed")
                .isZero();

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, clientId);
        assertThat(userCount).as("self-delete itself must have succeeded").isZero();
    }

    // ── shared race harness (mirrors MasterSelfDeleteCreateRaceConcurrencyIT, salt 1) ──────────

    /**
     * Holds the REAL salt-1 advisory lock for {@code clientId}, launches self-delete FIRST so it
     * queues for the lock BEFORE {@code createAction}, waits until BOTH are observably queued on
     * the SAME lock, then releases it — Postgres grants a contended lock in wait-queue order, so
     * self-delete (queued first) always wins the race this harness exercises.
     */
    private void raceSelfDeleteAgainstOwnCreate(UUID clientId, String clientToken, Runnable createAction)
            throws Exception {
        Connection holder = jdbcTemplate.getDataSource().getConnection();
        holder.setAutoCommit(false);
        try (PreparedStatement ps = holder.prepareStatement(LOCK_SQL)) {
            ps.setString(1, clientId.toString());
            ps.execute();
        }

        try {
            CountDownLatch selfDeleteDone = new CountDownLatch(1);
            AtomicReference<ResponseEntity<Void>> selfDeleteResponse = new AtomicReference<>();
            log.debug("Act: launch DELETE {} for the client — queues behind the held lock", USERS_ME_URL);
            Thread.ofVirtual().start(() -> {
                try {
                    selfDeleteResponse.set(restTemplate.exchange(
                            USERS_ME_URL, HttpMethod.DELETE,
                            new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class));
                } finally {
                    selfDeleteDone.countDown();
                }
            });
            assertThat(awaitWaiterCount(1))
                    .as("self-delete must be observably queued on the client's advisory lock within "
                            + "10s — if this is false, acquireClientLockForSelfDelete is not being "
                            + "called at all")
                    .isTrue();

            CountDownLatch createDone = new CountDownLatch(1);
            log.debug("Act: launch the racing create for the SAME client — queues SECOND, behind self-delete");
            Thread.ofVirtual().start(() -> {
                try {
                    createAction.run();
                } finally {
                    createDone.countDown();
                }
            });
            assertThat(awaitWaiterCount(2))
                    .as("both self-delete AND create must be observably queued on the SAME client "
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
        var headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }
}
