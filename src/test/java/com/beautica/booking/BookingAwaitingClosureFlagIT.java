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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.2 — full HTTP stack proof for the derived, never-persisted {@code awaitingClosure}
 * response flag on {@code BookingResponse}/{@code BookingDetailResponse}, and the "{@code
 * canReview} must stay byte-identical" gate.
 *
 * <p>Real Postgres (Testcontainers) via {@link AbstractIntegrationTest}, real Spring Security.
 * Own class-local frozen {@link Clock} — deliberately NOT {@code BookingMyBookingsPartitionIT}'s
 * {@code FrozenClockConfig} (Phase 28.3's isolation mechanism: sharing a {@code
 * @TestConfiguration} class would merge the two suites' Spring test contexts).
 */
@Import({TestSecurityConfig.class, BookingAwaitingClosureFlagIT.FrozenClockConfig.class})
@DisplayName("GET /bookings/me, GET /bookings/{id} — Phase 29.2 awaitingClosure flag, full HTTP stack")
class BookingAwaitingClosureFlagIT extends AbstractIntegrationTest {

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

    @Test
    @DisplayName("15-row status x endsAt-position matrix over GET /bookings/me — exactly one row "
            + "(CONFIRMED, elapsed) serializes awaitingClosure:true; every AWAITING_CLOSURE row also "
            + "carries canReview:false (an elapsed-unclosed CONFIRMED booking is never review-eligible)")
    void should_serializeTrueForExactlyOneRow_when_fifteenRowMatrixListedOverHttp() throws Exception {
        String masterEmail = "acflag-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "acflag-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        UUID awaitingClosureId = seedFifteenRowMatrix(clientId, masterId, masterServiceId);
        String clientToken = fixtures.tokenFor(clientEmailOf(clientId));

        JsonNode resp = callMyBookings(clientToken);
        List<JsonNode> rows = toRowList(resp);
        assertThat(rows).hasSize(15);

        List<UUID> flaggedIds = new ArrayList<>();
        for (JsonNode row : rows) {
            if (row.path("awaitingClosure").asBoolean()) {
                flaggedIds.add(UUID.fromString(row.path("id").asText()));
                assertThat(row.path("canReview").asBoolean())
                        .as("an AWAITING_CLOSURE row must never be canReview:true")
                        .isFalse();
            }
        }
        assertThat(flaggedIds).containsExactly(awaitingClosureId);
    }

    @Test
    @DisplayName("canReview regression group — 30-cell matrix (5 statuses x 3 endsAt positions x "
            + "review-exists/not); canReview is TRUE iff status==COMPLETED && no review exists, "
            + "REGARDLESS of endsAt position — pinning the pre-29.2 truth table so a future widening "
            + "of canReview to include AWAITING_CLOSURE rows fails here loudly")
    void should_matchPrePhaseTruthTable_when_evaluatingThirtyCellCanReviewMatrix() throws Exception {
        String masterEmail = "acflag-canreview-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "acflag-canreview-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        // canReview does not depend on endsAt at all (BookingService#canReview is a pure function
        // of status/reviewExists/hasClient) — so unlike the 15-row awaitingClosure matrix above,
        // this fixture does not need every row's endsAt pinned to the exact NOW-1h/NOW/NOW+1h
        // triple. Each of the 30 cells instead gets a fully independent, non-overlapping 1h window
        // (3h apart, well before NOW) — the "3 endsAt positions" axis is preserved as 3 distinct
        // temporal variants per status, proving canReview's independence from endsAt without
        // needing to reuse a shared endsAt across statuses (which would trip the CONFIRMED-only
        // no_overlapping_bookings EXCLUDE constraint for the two reviewExists variants).
        record Cell(UUID id, BookingStatusFixture status, boolean reviewExists, boolean expectedCanReview) {}
        List<Cell> cells = new ArrayList<>();
        int cellIndex = 0;
        for (var status : BookingStatusFixture.values()) {
            for (int position = 0; position < 3; position++) {
                for (boolean reviewExists : new boolean[] {false, true}) {
                    OffsetDateTime startsAt = NOW.minusDays(200).plusHours(cellIndex * 3L);
                    OffsetDateTime endsAt = startsAt.plusHours(1);
                    UUID id = insertBooking(clientId, masterId, masterServiceId, status.name(), startsAt, endsAt);
                    if (reviewExists) {
                        insertReview(id);
                    }
                    boolean expected = status == BookingStatusFixture.COMPLETED && !reviewExists;
                    cells.add(new Cell(id, status, reviewExists, expected));
                    cellIndex++;
                }
            }
        }
        assertThat(cells).hasSize(30);

        String clientToken = fixtures.tokenFor(clientEmail);
        JsonNode resp = callMyBookings(clientToken);
        var byId = new java.util.HashMap<UUID, JsonNode>();
        for (JsonNode row : toRowList(resp)) {
            byId.put(UUID.fromString(row.path("id").asText()), row);
        }
        assertThat(byId).hasSize(30);

        for (Cell cell : cells) {
            JsonNode row = byId.get(cell.id());
            assertThat(row)
                    .as("row for status=%s reviewExists=%s must be present", cell.status(), cell.reviewExists())
                    .isNotNull();
            assertThat(row.path("canReview").asBoolean())
                    .as("status=%s, reviewExists=%s — canReview must equal the pre-29.2 truth table",
                            cell.status(), cell.reviewExists())
                    .isEqualTo(cell.expectedCanReview());
        }
    }

    @Test
    @DisplayName("GET /bookings/{id} and the matching row from GET /bookings/me agree on "
            + "awaitingClosure for the same booking at the same frozen instant")
    void should_agreeBetweenDetailAndListEndpoints_when_sameBookingReadTwice() throws Exception {
        String masterEmail = "acflag-parity-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "acflag-parity-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        UUID elapsedConfirmedId = insertBooking(clientId, masterId, masterServiceId,
                "CONFIRMED", NOW.minusHours(2), NOW.minusHours(1));

        String clientToken = fixtures.tokenFor(clientEmail);

        JsonNode listResp = callMyBookings(clientToken);
        JsonNode listRow = findRow(listResp, elapsedConfirmedId);
        assertThat(listRow.path("awaitingClosure").asBoolean()).isTrue();

        ResponseEntity<String> detailResp = restTemplate.exchange(
                BOOKINGS_URL + "/" + elapsedConfirmedId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(detailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detailNode = objectMapper.readTree(detailResp.getBody()).path("data");

        assertThat(detailNode.path("awaitingClosure").asBoolean())
                .as("GET /bookings/{id} and GET /bookings/me must agree on awaitingClosure for the "
                        + "same booking at the same instant")
                .isEqualTo(listRow.path("awaitingClosure").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("provider (INDEPENDENT_MASTER) scope produces the IDENTICAL awaitingClosure "
            + "values as the CLIENT read of the same 15-row fixture — the flag is a property of "
            + "the booking, not of the viewer")
    void should_produceIdenticalFlags_when_readAsProviderVersusClient() throws Exception {
        String masterEmail = "acflag-scope-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "acflag-scope-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        seedFifteenRowMatrix(clientId, masterId, masterServiceId);

        String clientToken = fixtures.tokenFor(clientEmail);
        String masterToken = fixtures.tokenFor(masterEmail);

        var clientFlags = new java.util.HashMap<UUID, Boolean>();
        for (JsonNode row : toRowList(callMyBookings(clientToken))) {
            clientFlags.put(UUID.fromString(row.path("id").asText()), row.path("awaitingClosure").asBoolean());
        }
        var providerFlags = new java.util.HashMap<UUID, Boolean>();
        for (JsonNode row : toRowList(callMyBookings(masterToken))) {
            providerFlags.put(UUID.fromString(row.path("id").asText()), row.path("awaitingClosure").asBoolean());
        }

        assertThat(providerFlags).as("both scopes must see the same 15 rows").hasSize(15);
        assertThat(providerFlags)
                .as("awaitingClosure must be identical per booking id regardless of viewer role")
                .isEqualTo(clientFlags);
    }

    @Test
    @DisplayName("no persistence: after every read, raw JDBC shows no column resembling "
            + "awaiting_closure, and every seeded row's status is byte-identical to what was inserted")
    void should_mutateNoRows_when_awaitingClosureRowsAreRead() throws Exception {
        String masterEmail = "acflag-noop-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "acflag-noop-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        UUID elapsedConfirmedId = insertBooking(clientId, masterId, masterServiceId,
                "CONFIRMED", NOW.minusHours(2), NOW.minusHours(1));

        String clientToken = fixtures.tokenFor(clientEmail);
        callMyBookings(clientToken);
        restTemplate.exchange(BOOKINGS_URL + "/" + elapsedConfirmedId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'bookings'",
                String.class);
        assertThat(columns)
                .as("no column named anything like awaiting_closure must ever exist on bookings")
                .noneMatch(c -> c.toLowerCase().contains("awaiting_closure"));

        String statusAfter = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, elapsedConfirmedId);
        assertThat(statusAfter)
                .as("reading the derived flag must never mutate the underlying status column")
                .isEqualTo("CONFIRMED");
    }

    // ── fixture helpers ──────────────────────────────────────────────────────────────────────────

    private enum BookingStatusFixture { CONFIRMED, DECLINED, COMPLETED, NOT_COMPLETED, CANCELLED }

    /** Seeds one row per {@link BookingStatusFixture} x endsAt in {NOW-1h, NOW, NOW+1h} (15 rows). */
    private UUID seedFifteenRowMatrix(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID awaitingClosureId = null;
        for (OffsetDateTime endsAt : new OffsetDateTime[] {NOW.minusHours(1), NOW, NOW.plusHours(1)}) {
            OffsetDateTime startsAt = endsAt.minusHours(1);
            for (BookingStatusFixture status : BookingStatusFixture.values()) {
                UUID id = insertBooking(clientId, masterId, masterServiceId, status.name(), startsAt, endsAt);
                if (status == BookingStatusFixture.CONFIRMED && endsAt.equals(NOW.minusHours(1))) {
                    awaitingClosureId = id;
                }
            }
        }
        return awaitingClosureId;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, String status,
                                OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID bookingId = UUID.randomUUID();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, status, startsAt, endsAt, minutes);
        return bookingId;
    }

    private void insertReview(UUID bookingId) {
        UUID reviewId = UUID.randomUUID();
        UUID clientId = jdbcTemplate.queryForObject(
                "SELECT client_id FROM bookings WHERE id = ?", UUID.class, bookingId);
        UUID masterId = jdbcTemplate.queryForObject(
                "SELECT master_id FROM bookings WHERE id = ?", UUID.class, bookingId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NULL, 5, ?, ?, ?)",
                reviewId, bookingId, clientId, masterId, "seeded review", now, now);
    }

    private String clientEmailOf(UUID clientId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, clientId);
    }

    private JsonNode callMyBookings(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?size=50", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private List<JsonNode> toRowList(JsonNode root) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : root.path("data").path("data")) {
            rows.add(row);
        }
        return rows;
    }

    private JsonNode findRow(JsonNode root, UUID id) {
        for (JsonNode row : toRowList(root)) {
            if (row.path("id").asText().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("row not found for id " + id);
    }
}
