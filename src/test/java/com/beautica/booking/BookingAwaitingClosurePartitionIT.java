package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.3 — {@code GET /bookings/me?partition=AWAITING_CLOSURE}, full HTTP stack over real
 * Postgres, fixed {@link Clock}. Proves {@code AWAITING_CLOSURE} is an explicit, named SUB-VIEW of
 * {@code PAST} — never a fourth member of the total-disjoint cover — and that the standalone arm
 * agrees exactly with {@code PAST}'s second OR-leg and with the Phase 29.2 {@code awaitingClosure}
 * response flag.
 *
 * <p>Own class-local frozen {@link Clock} ({@code NOW = 2031-06-15T12:00:00Z}) — deliberately NOT
 * {@code BookingMyBookingsPartitionIT}'s {@code FrozenClockConfig} (sharing it would merge the two
 * suites' Spring test contexts, per Phase 28.3's established isolation mechanism).
 */
@Import({TestSecurityConfig.class, BookingAwaitingClosurePartitionIT.FrozenClockConfig.class})
@DisplayName("GET /bookings/me?partition=AWAITING_CLOSURE — Phase 29.3, full HTTP stack over real Postgres, fixed clock")
class BookingAwaitingClosurePartitionIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");

    @TestConfiguration
    static class FrozenClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    private record Fixture(
            UUID confirmedUpcoming, UUID confirmedBoundary, UUID confirmedElapsed,
            UUID confirmedElapsedExtra, UUID completedElapsed, UUID completedFutureEnds,
            UUID completedExtra, UUID notCompleted, UUID cancelled, UUID declined) {
    }

    /**
     * The Phase 28.3 eight-row shape plus an extra elapsed {@code CONFIRMED} and an extra {@code
     * COMPLETED} row (Phase 29.3's fixture note) — ten rows total, spanning both sides of {@link
     * #NOW}.
     */
    private Fixture seedTenRowFixture(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID confirmedUpcoming = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.plusHours(2), NOW.plusHours(3));
        UUID confirmedBoundary = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.minusHours(1), NOW);
        UUID confirmedElapsed = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        UUID confirmedElapsedExtra = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.minusHours(9), NOW.minusHours(8));
        UUID completedElapsed = insertBooking(clientId, masterId, masterServiceId, salonId,
                "COMPLETED", NOW.minusHours(5), NOW.minusHours(4));
        UUID completedFutureEnds = insertBooking(clientId, masterId, masterServiceId, salonId,
                "COMPLETED", NOW.minusHours(6), NOW.plusHours(5));
        UUID completedExtra = insertBooking(clientId, masterId, masterServiceId, salonId,
                "COMPLETED", NOW.minusHours(20), NOW.minusHours(19));
        UUID notCompleted = insertBooking(clientId, masterId, masterServiceId, salonId,
                "NOT_COMPLETED", NOW.minusHours(8), NOW.minusHours(7));
        UUID cancelled = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CANCELLED", NOW.plusHours(10), NOW.plusHours(11));
        UUID declined = insertBooking(clientId, masterId, masterServiceId, salonId,
                "DECLINED", NOW.plusHours(12), NOW.plusHours(13));
        return new Fixture(confirmedUpcoming, confirmedBoundary, confirmedElapsed, confirmedElapsedExtra,
                completedElapsed, completedFutureEnds, completedExtra, notCompleted, cancelled, declined);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Sub-view decomposition, containment, disjointness — INDEPENDENT_MASTER path
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AWAITING_CLOSURE is a strict, non-vacuous subset of PAST that partitions it "
            + "against the COMPLETED/NOT_COMPLETED rows, with empty intersection")
    void should_decomposePastIntoAwaitingClosureAndClosedRows_when_partitioned() throws Exception {
        String masterEmail = "acpart-decomp-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-decomp-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Fixture fx = seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        Set<UUID> past = idSet(call(token, "PAST", null, null, null, null));
        Set<UUID> awaitingClosure = idSet(call(token, "AWAITING_CLOSURE", null, null, null, null));
        // "closed rows" computed independently, not by re-reusing a partition value: COMPLETED
        // and NOT_COMPLETED are the two statuses PAST's first OR-leg admits unconditionally.
        Set<UUID> closed = new HashSet<>(List.of(
                fx.completedElapsed(), fx.completedFutureEnds(), fx.completedExtra(), fx.notCompleted()));

        assertThat(awaitingClosure)
                .as("AWAITING_CLOSURE must be exactly the elapsed-unclosed CONFIRMED rows")
                .containsExactlyInAnyOrder(fx.confirmedElapsed(), fx.confirmedElapsedExtra());
        assertThat(closed).as("sanity: the closed-rows id set is disjoint from AWAITING_CLOSURE")
                .doesNotContainAnyElementsOf(awaitingClosure);

        Set<UUID> union = new HashSet<>(awaitingClosure);
        union.addAll(closed);
        assertThat(union)
                .as("AWAITING_CLOSURE union closed-rows must equal PAST exactly — the decomposition")
                .isEqualTo(past);
        assertThat(awaitingClosure.size() + closed.size())
                .as("|AWAITING_CLOSURE| + |closed| == |PAST| — no double counting, no gap")
                .isEqualTo(past.size());
    }

    @Test
    @DisplayName("containment: every AWAITING_CLOSURE id is also in PAST, and the subset is STRICT "
            + "(PAST has at least one COMPLETED row that is not in AWAITING_CLOSURE) — not vacuous")
    void should_beStrictSubsetOfPast_when_awaitingClosureCompared() throws Exception {
        String masterEmail = "acpart-contain-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-contain-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Fixture fx = seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        Set<UUID> past = idSet(call(token, "PAST", null, null, null, null));
        Set<UUID> awaitingClosure = idSet(call(token, "AWAITING_CLOSURE", null, null, null, null));

        assertThat(past).containsAll(awaitingClosure);
        assertThat(past)
                .as("strict, not vacuous: PAST must contain a COMPLETED row outside AWAITING_CLOSURE")
                .contains(fx.completedElapsed())
                .doesNotContain(fx.confirmedUpcoming());
        assertThat(awaitingClosure).doesNotContain(fx.completedElapsed());
        assertThat(past.size()).isGreaterThan(awaitingClosure.size());
    }

    @Test
    @DisplayName("AWAITING_CLOSURE is disjoint from UPCOMING and CANCELLED")
    void should_beDisjointFromUpcomingAndCancelled_when_awaitingClosureCompared() throws Exception {
        String masterEmail = "acpart-disjoint-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-disjoint-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        Set<UUID> awaitingClosure = idSet(call(token, "AWAITING_CLOSURE", null, null, null, null));
        Set<UUID> upcoming = idSet(call(token, "UPCOMING", null, null, null, null));
        Set<UUID> cancelled = idSet(call(token, "CANCELLED", null, null, null, null));

        assertThat(awaitingClosure).doesNotContainAnyElementsOf(upcoming);
        assertThat(awaitingClosure).doesNotContainAnyElementsOf(cancelled);
    }

    @Test
    @DisplayName("cover sum unaffected by the new constant: UPCOMING+PAST+CANCELLED still equals the "
            + "unfiltered total; the SAME sum computed naively over all four partition values would "
            + "double-count — asserted as an explicit negative control documenting the trap")
    void should_keepCoverSumCorrect_when_awaitingClosureConstantExists() throws Exception {
        String masterEmail = "acpart-cover-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-cover-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        long unfiltered = call(token, null, null, null, null, null).path("data").path("totalElements").asLong();
        long upcoming = call(token, "UPCOMING", null, null, null, null).path("data").path("totalElements").asLong();
        long past = call(token, "PAST", null, null, null, null).path("data").path("totalElements").asLong();
        long cancelled = call(token, "CANCELLED", null, null, null, null).path("data").path("totalElements").asLong();
        long awaitingClosure = call(token, "AWAITING_CLOSURE", null, null, null, null)
                .path("data").path("totalElements").asLong();

        assertThat(unfiltered).isEqualTo(10);
        assertThat(upcoming + past + cancelled)
                .as("the TOTAL, DISJOINT COVER — iterate BookingPartition.COVER, never values()")
                .isEqualTo(unfiltered);
        assertThat(upcoming + past + cancelled + awaitingClosure)
                .as("negative control: naively summing all FOUR partition values (as "
                        + "BookingPartition.values() would tempt) DOUBLE-COUNTS every "
                        + "AWAITING_CLOSURE row, so this sum must NOT equal the unfiltered total")
                .isNotEqualTo(unfiltered)
                .isGreaterThan(unfiltered);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Boundary + Option-A hole
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a CONFIRMED row with endsAt == NOW exactly is ABSENT from AWAITING_CLOSURE and "
            + "PRESENT in UPCOMING — strict '<', half-open boundary")
    void should_excludeBoundaryRowFromAwaitingClosure_when_endsAtEqualsNow() throws Exception {
        String masterEmail = "acpart-boundary-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-boundary-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Fixture fx = seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        assertThat(idSet(call(token, "AWAITING_CLOSURE", null, null, null, null)))
                .doesNotContain(fx.confirmedBoundary());
        assertThat(idSet(call(token, "UPCOMING", null, null, null, null)))
                .contains(fx.confirmedBoundary());
    }

    @Test
    @DisplayName("a COMPLETED booking with a FUTURE endsAt (the Option-A hole) is in PAST and NOT "
            + "in AWAITING_CLOSURE — closed is closed regardless of elapsed time")
    void should_excludeCompletedFutureEndsAtRowFromAwaitingClosure_when_optionAHoleExercised() throws Exception {
        String masterEmail = "acpart-option-a-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-option-a-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Fixture fx = seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        assertThat(idSet(call(token, "PAST", null, null, null, null))).contains(fx.completedFutureEnds());
        assertThat(idSet(call(token, "AWAITING_CLOSURE", null, null, null, null)))
                .doesNotContain(fx.completedFutureEnds());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Flag agreement — the filter and the Phase 29.2 response flag must never disagree
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("every row on the AWAITING_CLOSURE page serializes awaitingClosure:true, and a full "
            + "unfiltered scan finds no awaitingClosure:true row outside that id set")
    void should_agreeWithAwaitingClosureFlag_when_filteringByAwaitingClosurePartition() throws Exception {
        String masterEmail = "acpart-flag-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-flag-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        JsonNode awaitingClosurePage = call(token, "AWAITING_CLOSURE", null, null, null, null);
        for (JsonNode row : awaitingClosurePage.path("data").path("data")) {
            assertThat(row.path("awaitingClosure").asBoolean())
                    .as("row %s on the AWAITING_CLOSURE page must serialize awaitingClosure:true",
                            row.path("id").asText())
                    .isTrue();
        }

        Set<UUID> awaitingClosureIds = idSet(awaitingClosurePage);
        JsonNode unfilteredPage = call(token, null, null, null, null, null);
        for (JsonNode row : unfilteredPage.path("data").path("data")) {
            if (row.path("awaitingClosure").asBoolean()) {
                assertThat(awaitingClosureIds)
                        .as("every awaitingClosure:true row in the unfiltered scan must be in the "
                                + "AWAITING_CLOSURE id set")
                        .contains(UUID.fromString(row.path("id").asText()));
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // No mutation — filtering by AWAITING_CLOSURE must never close a booking as a side effect
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("no mutation: two consecutive GET /bookings/me?partition=AWAITING_CLOSURE reads "
            + "return the identical id set, and the underlying status column is byte-identical to "
            + "what was inserted — reading this filter must never transition a booking to a "
            + "terminal state")
    void should_mutateNoRows_when_awaitingClosurePartitionIsRead() throws Exception {
        String masterEmail = "acpart-noop-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-noop-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        UUID elapsedConfirmedId = insertBooking(clientId, masterId, serviceId, null,
                "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        String token = fixtures.tokenFor(masterEmail);

        Set<UUID> firstRead = idSet(call(token, "AWAITING_CLOSURE", null, null, null, null));
        // A second read: a hypothetical sweeper-on-read regression would close the row on the
        // first call, silently emptying the set on the second.
        Set<UUID> secondRead = idSet(call(token, "AWAITING_CLOSURE", null, null, null, null));

        assertThat(firstRead).containsExactly(elapsedConfirmedId);
        assertThat(secondRead)
                .as("a second read must return the identical set — the cheap guard against a "
                        + "future edit sneaking a status write into this read path")
                .isEqualTo(firstRead);

        String statusAfter = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, elapsedConfirmedId);
        assertThat(statusAfter)
                .as("filtering by AWAITING_CLOSURE must never mutate the underlying status column")
                .isEqualTo("CONFIRMED");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // All four scopes — CLIENT, INDEPENDENT_MASTER (above), SALON_OWNER, SALON_MASTER
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CLIENT scope — decomposition and containment hold identically, restricted to the "
            + "caller's own bookings only")
    void should_decomposeAndContain_when_clientScope() throws Exception {
        String masterEmail = "acpart-cli-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        String clientEmail = "acpart-cli-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        Fixture fx = seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(clientEmail);

        Set<UUID> past = idSet(call(token, "PAST", null, null, null, null));
        Set<UUID> awaitingClosure = idSet(call(token, "AWAITING_CLOSURE", null, null, null, null));

        assertThat(awaitingClosure).containsExactlyInAnyOrder(fx.confirmedElapsed(), fx.confirmedElapsedExtra());
        assertThat(past).containsAll(awaitingClosure);
    }

    @Test
    @DisplayName("SALON_OWNER scope — sees only their own salon's AWAITING_CLOSURE rows, never a "
            + "different owner's salon (cross-salon leak guard)")
    void should_scopeToOwnSalonOnly_when_salonOwnerRequestsAwaitingClosure() throws Exception {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon("acpart-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("acpart-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID ownAwaitingClosure = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));

        BookingTestFixtures.SalonFixture otherSalon = fixtures.createSalon("acpart-owner-b-" + System.nanoTime() + "@beautica.test");
        UUID otherServiceId = fixtures.createSalonService(otherSalon.salonId(), otherSalon.masterId());
        insertBooking(clientId, otherSalon.masterId(), otherServiceId, otherSalon.salonId(),
                "CONFIRMED", NOW.minusHours(5), NOW.minusHours(4));

        String token = fixtures.tokenFor(salon.ownerEmail());

        assertThat(idSet(call(token, "AWAITING_CLOSURE", null, null, null, null)))
                .containsExactly(ownAwaitingClosure);
    }

    @Test
    @DisplayName("SALON_MASTER isolation — a master in a salon with a colleague's elapsed CONFIRMED "
            + "booking sees only their OWN AWAITING_CLOSURE rows, never a sibling master's; the "
            + "SALON_OWNER of the same salon sees both")
    void should_isolateToOwnRows_when_salonMasterRequestsAwaitingClosure() throws Exception {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon("acpart-sm-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("acpart-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID ownRow = insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(),
                "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));

        // A second master in the SAME salon with their own elapsed CONFIRMED row.
        String siblingEmail = "acpart-sm-sibling-" + System.nanoTime() + "@beautica.test";
        UUID siblingUserId = fixtures.createUser(siblingEmail, "SALON_MASTER", salon.salonId());
        UUID siblingMasterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                siblingMasterId, siblingUserId, salon.salonId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), siblingMasterId);
        UUID siblingRow = insertBooking(clientId, siblingMasterId, serviceB, salon.salonId(),
                "CONFIRMED", NOW.minusHours(6), NOW.minusHours(5));

        String ownMasterToken = fixtures.tokenFor(salon.masterEmail());
        String ownerToken = fixtures.tokenFor(salon.ownerEmail());

        assertThat(idSet(call(ownMasterToken, "AWAITING_CLOSURE", null, null, null, null)))
                .as("SALON_MASTER must see only their OWN row, never the sibling's")
                .containsExactly(ownRow)
                .doesNotContain(siblingRow);
        assertThat(idSet(call(ownerToken, "AWAITING_CLOSURE", null, null, null, null)))
                .as("SALON_OWNER sees the whole salon, including both masters' rows")
                .containsExactlyInAnyOrder(ownRow, siblingRow);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Precedence + composition + invalid enum
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("?partition=AWAITING_CLOSURE&status=COMPLETED — status is IGNORED, the "
            + "awaiting-closure set wins (200, not the empty set)")
    void should_ignoreStatusParam_when_awaitingClosurePartitionSupplied() throws Exception {
        String masterEmail = "acpart-prec-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-prec-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Fixture fx = seedTenRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        JsonNode resp = call(token, "AWAITING_CLOSURE", List.of("COMPLETED"), null, null, null);

        assertThat(idSet(resp))
                .as("partition=AWAITING_CLOSURE must win over status=COMPLETED — not the empty set")
                .containsExactlyInAnyOrder(fx.confirmedElapsed(), fx.confirmedElapsedExtra());
    }

    @Test
    @DisplayName("AWAITING_CLOSURE composes correctly with serviceId and from/to — a row matching "
            + "only two of three filters is excluded")
    void should_andWithServiceIdAndDateRange_when_awaitingClosureSupplied() throws Exception {
        String masterEmail = "acpart-compose-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("acpart-compose-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);

        UUID inWindowServiceA = insertBookingForService(clientId, masterId, serviceA,
                NOW.minusDays(2).minusHours(2), NOW.minusDays(2).minusHours(1));
        UUID outOfWindowServiceA = insertBookingForService(clientId, masterId, serviceA,
                NOW.minusDays(10).minusHours(2), NOW.minusDays(10).minusHours(1));
        UUID inWindowServiceB = insertBookingForService(clientId, masterId, serviceB,
                NOW.minusDays(2).minusHours(4), NOW.minusDays(2).minusHours(3));

        String fromDay = NOW.minusDays(3).toLocalDate().toString();
        String toDay = NOW.minusDays(1).toLocalDate().toString();

        JsonNode resp = call(fixtures.tokenFor(masterEmail), "AWAITING_CLOSURE", null, fromDay, toDay, List.of(serviceA));

        assertThat(idSet(resp))
                .as("partition=AWAITING_CLOSURE AND from/to AND serviceId=A must return exactly the "
                        + "one row matching all three filters")
                .containsExactly(inWindowServiceA)
                .doesNotContain(outOfWindowServiceA, inWindowServiceB);
    }

    @Test
    @DisplayName("?partition=BOGUS still returns 400, body does not echo AWAITING_CLOSURE")
    void should_return400WithoutEchoingAwaitingClosure_when_partitionParamIsUnrecognised() throws Exception {
        String clientEmail = "acpart-badenum-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String token = fixtures.tokenFor(clientEmail);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?partition=BOGUS", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String bodyLower = resp.getBody() == null ? "" : resp.getBody().toLowerCase();
        assertThat(bodyLower).doesNotContain("awaiting_closure");
    }

    // ── fixture + HTTP helpers ───────────────────────────────────────────────────────────────────

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                String status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID bookingId = UUID.randomUUID();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        if (salonId != null) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, salonId, status, startsAt, endsAt, minutes);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, status, startsAt, endsAt, minutes);
        }
        return bookingId;
    }

    private UUID insertBookingForService(UUID clientId, UUID masterId, UUID masterServiceId,
                                          OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return insertBooking(clientId, masterId, masterServiceId, null, "CONFIRMED", startsAt, endsAt);
    }

    private JsonNode call(String token, String partition, List<String> statuses,
                           String from, String to, List<UUID> serviceIds) throws Exception {
        List<String> parts = new ArrayList<>();
        if (partition != null) {
            parts.add("partition=" + partition);
        }
        if (statuses != null) {
            for (String s : statuses) {
                parts.add("status=" + s);
            }
        }
        if (from != null) {
            parts.add("from=" + from);
        }
        if (to != null) {
            parts.add("to=" + to);
        }
        if (serviceIds != null) {
            for (UUID id : serviceIds) {
                parts.add("serviceId=" + id);
            }
        }
        parts.add("size=50");
        String url = BOOKINGS_URL + "/me?" + String.join("&", parts);
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("request to %s must succeed", url).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private List<UUID> idList(JsonNode root) {
        return fixtures.extractIds(root);
    }

    private Set<UUID> idSet(JsonNode root) {
        return new HashSet<>(idList(root));
    }
}
