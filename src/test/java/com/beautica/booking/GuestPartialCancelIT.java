package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.GuestPartialCancelSupport.Visit;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 436 — guest PARTIAL cancel by the SMS cancel link, over real HTTP + Postgres: the GET {@code items} view, a
 * middle-service cancel that keeps the visit (and its token) live, the second request that ends it, the
 * 400 / 404 / 422 all-or-nothing matrix, the single-booking token, and the 4 KB body cap on the percent-encoded route.
 *
 * <p>Same bean overrides as {@code GuestCancelTokenHashEndToEndIT} (only {@link SmsService}), so it shares that
 * cached context.
 */
@DisplayName("Phase 436 — guest partial cancel by link: items view, partial cancel, error matrix, body cap")
class GuestPartialCancelIT extends AbstractIntegrationTest {

    private static final List<String> NAMES = List.of("Манікюр-436", "Педикюр-436", "Брови-436");
    private static final int DAYS_AHEAD = 3;
    private static final int OTHER_VISIT_DAYS_AHEAD = 5;
    private static final int MINUTES_INSIDE_WINDOW = 60;
    private static final int ONE_MIB = 1 << 20;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private Clock clock;

    @MockBean private SmsService smsService;

    private GuestPartialCancelSupport guest;

    @BeforeEach
    void initSupport() {
        guest = new GuestPartialCancelSupport(
                restTemplate, objectMapper, guestTokenProvider, jdbcTemplate, passwordEncoder, clock);
    }

    private Visit threeServiceVisit(String tag) throws Exception {
        String slug = guest.masterSlug(tag);
        return guest.bookVisit(slug, guest.services(slug, NAMES), GuestPartialCancelSupport.GUEST_PHONE, DAYS_AHEAD);
    }

    // ── GET items ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET items")
    class GetItems {

        @Test
        @DisplayName("a fresh 3-service visit lists 3 items in visit order, each named, 30 min and cancellable")
        void should_listThreeCancellableItemsInOrder_when_freshVisitOpened() throws Exception {
            Visit visit = threeServiceVisit("get-fresh");

            ResponseEntity<String> response = guest.get(visit.token());

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode items = guest.body(response).path("items");
            assertThat(ids(items)).as("one item per line, startsAt order").isEqualTo(visit.bookingIds());
            assertThat(texts(items, "serviceName")).isEqualTo(NAMES);
            assertThat(texts(items, "durationMinutes")).containsOnly("30");
            assertThat(texts(items, "status")).containsOnly("CONFIRMED");
            assertThat(texts(items, "cancellable")).containsOnly("true");
            assertThat(texts(items, "startsAt")).as("strictly ascending starts").isSorted().doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("items inside the 2 h window, already CANCELLED and COMPLETED are each not cancellable, "
                + "and the visit as a whole is then not cancellable")
        void should_flagEachItemNotCancellable_when_insideWindowCancelledAndCompleted() throws Exception {
            Visit visit = threeServiceVisit("get-flags");
            guest.moveStart(visit.line(0), MINUTES_INSIDE_WINDOW);
            guest.setStatus(visit.line(1), "CANCELLED");
            guest.setStatus(visit.line(2), "COMPLETED");

            ResponseEntity<String> response = guest.get(visit.token());

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode body = guest.body(response);
            JsonNode items = body.path("items");
            assertThat(ids(items)).isEqualTo(visit.bookingIds());
            assertThat(texts(items, "status")).containsExactly("CONFIRMED", "CANCELLED", "COMPLETED");
            assertThat(texts(items, "cancellable"))
                    .as("inside window / cancelled / completed")
                    .containsExactly("false", "false", "false");
            assertThat(body.path("cancellable").asBoolean(true)).as("no item cancellable → visit not").isFalse();
        }
    }

    // ── partial cancel ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("partial cancel")
    class PartialCancel {

        @Test
        @DisplayName("cancelling the MIDDLE service changes only that row; the header stays CONFIRMED with its token "
                + "and the page still opens")
        void should_cancelOnlyMiddleLineAndKeepVisitLive_when_middleIdPosted() throws Exception {
            Visit visit = threeServiceVisit("middle");

            ResponseEntity<String> response = guest.post(visit.token(), List.of(visit.line(1)));

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode body = guest.body(response);
            assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(texts(body.path("items"), "status")).containsExactly("CONFIRMED", "CANCELLED", "CONFIRMED");
            assertThat(texts(body.path("items"), "cancellable")).containsExactly("true", "false", "true");
            assertThat(guest.statuses(visit)).containsExactly("CONFIRMED", "CANCELLED", "CONFIRMED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, visit.line(1)))
                    .isEqualTo("CLIENT_CANCELLED");
            assertThat(guest.headerStatus(visit.appointmentId())).isEqualTo("CONFIRMED");
            assertThat(guest.headerHoldsToken(visit.appointmentId())).as("token kept while a line is live").isTrue();
            ResponseEntity<String> reopened = guest.get(visit.token());
            assertThat(reopened.getStatusCode()).as("the token still opens the page").isEqualTo(HttpStatus.OK);
            assertThat(texts(guest.body(reopened).path("items"), "status"))
                    .containsExactly("CONFIRMED", "CANCELLED", "CONFIRMED");
        }

        @Test
        @DisplayName("a second POST with the same token cancels the rest; the header flips to CANCELLED, the token "
                + "is consumed and GET / POST then answer 404")
        void should_endVisitAndConsumeToken_when_secondPostCancelsTheRest() throws Exception {
            Visit visit = threeServiceVisit("rest");
            assertThat(guest.post(visit.token(), List.of(visit.line(1))).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> second = guest.post(visit.token(), List.of(visit.line(0), visit.line(2)));

            assertThat(second.getStatusCode()).as(second.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode body = guest.body(second);
            assertThat(body.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(texts(body.path("items"), "status")).containsOnly("CANCELLED");
            assertThat(guest.statuses(visit)).containsOnly("CANCELLED");
            assertThat(guest.headerStatus(visit.appointmentId())).isEqualTo("CANCELLED");
            assertThat(guest.headerHoldsToken(visit.appointmentId())).as("token consumed").isFalse();
            assertThat(guest.get(visit.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(guest.post(visit.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("after a partial cancel, a body without the bookingIds key cancels every remaining line")
        void should_cancelRemainingLines_when_bodyHasNoBookingIdsKeyAfterPartial() throws Exception {
            Visit visit = threeServiceVisit("rest-nokey");
            assertThat(guest.post(visit.token(), List.of(visit.line(0))).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> response = guest.postJson(visit.token(), "{}");

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(guest.statuses(visit)).containsOnly("CANCELLED");
            assertThat(guest.headerStatus(visit.appointmentId())).isEqualTo("CANCELLED");
        }
    }

    // ── error matrix: nothing changes ──────────────────────────────────────────

    @Nested
    @DisplayName("error matrix — all or nothing")
    class ErrorMatrix {

        @Test
        @DisplayName("an id from ANOTHER guest's visit is 404 and neither visit changes")
        void should_return404AndChangeNothing_when_idBelongsToAnotherGuestsVisit() throws Exception {
            String slug = guest.masterSlug("foreign");
            List<UUID> services = guest.services(slug, NAMES);
            Visit mine = guest.bookVisit(slug, services, GuestPartialCancelSupport.GUEST_PHONE, DAYS_AHEAD);
            Visit theirs = guest.bookVisit(slug, services, GuestPartialCancelSupport.OTHER_GUEST_PHONE,
                    OTHER_VISIT_DAYS_AHEAD);

            ResponseEntity<String> response = guest.post(mine.token(), List.of(theirs.line(0)));

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(guest.body(response).path("success").asBoolean(true)).isFalse();
            assertThat(guest.statuses(mine)).containsOnly("CONFIRMED");
            assertThat(guest.statuses(theirs)).as("the other guest's visit is untouched").containsOnly("CONFIRMED");
            assertThat(guest.headerHoldsToken(mine.appointmentId())).isTrue();
        }

        @Test
        @DisplayName("a mix of own and foreign ids is 404 and the own line is NOT cancelled")
        void should_return404AndCancelNothing_when_ownAndForeignIdsMixed() throws Exception {
            String slug = guest.masterSlug("mixed");
            List<UUID> services = guest.services(slug, NAMES);
            Visit mine = guest.bookVisit(slug, services, GuestPartialCancelSupport.GUEST_PHONE, DAYS_AHEAD);
            Visit theirs = guest.bookVisit(slug, services, GuestPartialCancelSupport.OTHER_GUEST_PHONE,
                    OTHER_VISIT_DAYS_AHEAD);

            ResponseEntity<String> response = guest.post(mine.token(), List.of(mine.line(0), theirs.line(1)));

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(guest.statuses(mine)).containsOnly("CONFIRMED");
            assertThat(guest.statuses(theirs)).containsOnly("CONFIRMED");
        }

        @Test
        @DisplayName("an already-CANCELLED id is 422 and nothing else changes")
        void should_return422AndChangeNothing_when_idAlreadyCancelled() throws Exception {
            Visit visit = threeServiceVisit("not-cancellable");
            assertThat(guest.post(visit.token(), List.of(visit.line(1))).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> response = guest.post(visit.token(), List.of(visit.line(1)));

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(guest.body(response).path("success").asBoolean(true)).isFalse();
            assertThat(guest.statuses(visit)).containsExactly("CONFIRMED", "CANCELLED", "CONFIRMED");
            assertThat(guest.headerStatus(visit.appointmentId())).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("a cancellable id together with one inside the 2 h window is 422 and the cancellable one is "
                + "NOT cancelled")
        void should_return422AndCancelNothing_when_oneIdInsideWindow() throws Exception {
            Visit visit = threeServiceVisit("window-mix");
            guest.moveStart(visit.line(0), MINUTES_INSIDE_WINDOW);

            ResponseEntity<String> response = guest.post(visit.token(), List.of(visit.line(0), visit.line(2)));

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(guest.statuses(visit)).containsOnly("CONFIRMED");
        }

        @Test
        @DisplayName("an EMPTY bookingIds is 400 and the visit is not cancelled")
        void should_return400AndChangeNothing_when_bookingIdsEmpty() throws Exception {
            Visit visit = threeServiceVisit("empty");

            ResponseEntity<String> response = guest.postJson(visit.token(), "{\"bookingIds\":[]}");

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(guest.statuses(visit)).containsOnly("CONFIRMED");
            assertThat(guest.headerHoldsToken(visit.appointmentId())).isTrue();
        }

        @Test
        @DisplayName("a duplicated id is 400 and nothing is cancelled")
        void should_return400AndChangeNothing_when_idDuplicated() throws Exception {
            Visit visit = threeServiceVisit("dup");

            ResponseEntity<String> response = guest.post(visit.token(), List.of(visit.line(0), visit.line(0)));

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(guest.statuses(visit)).containsOnly("CONFIRMED");
        }
    }

    // ── single-booking token ───────────────────────────────────────────────────

    @Nested
    @DisplayName("single-booking token")
    class SingleBookingToken {

        @Test
        @DisplayName("a body naming any id other than the booking itself is 404 and the booking stays CONFIRMED")
        void should_return404AndKeepBooking_when_singleTokenNamesAnotherId() throws Exception {
            String slug = guest.masterSlug("single-foreign");
            Visit single = guest.bookSingle(slug, guest.services(slug, NAMES.subList(0, 1)).get(0), DAYS_AHEAD);

            ResponseEntity<String> response = guest.post(single.token(), List.of(UUID.randomUUID()));

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(guest.status(single.line(0))).isEqualTo("CONFIRMED");
            assertThat(guest.get(single.token()).getStatusCode()).as("token not consumed").isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("no body cancels the single booking as before and returns its one-item view")
        void should_cancelBookingAndReturnOneItem_when_singleTokenPostedWithoutBody() throws Exception {
            String slug = guest.masterSlug("single-nobody");
            Visit single = guest.bookSingle(slug, guest.services(slug, NAMES.subList(0, 1)).get(0), DAYS_AHEAD);

            ResponseEntity<String> response = guest.post(single.token());

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode items = guest.body(response).path("items");
            assertThat(ids(items)).containsExactly(single.line(0));
            assertThat(texts(items, "status")).containsExactly("CANCELLED");
            assertThat(guest.status(single.line(0))).isEqualTo("CANCELLED");
            assertThat(guest.post(single.token()).getStatusCode()).as("replay").isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── body cap on the decoded route ──────────────────────────────────────────

    @Nested
    @DisplayName("body cap")
    class BodyCap {

        @Test
        @DisplayName("a percent-encoded `canc%65l` route is the live cancel route (unknown token → 404)")
        void should_routeToCancel_when_pathPercentEncodedAndBodySmall() {
            ResponseEntity<String> response = postEncoded("{}");

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a 1 MiB body on the percent-encoded `canc%65l` route is 413 before it is parsed")
        void should_return413_when_oneMibBodyOnPercentEncodedRoute() throws Exception {
            String oversized = "{\"bookingIds\":[\"" + "a".repeat(ONE_MIB) + "\"]}";

            ResponseEntity<String> response = postEncoded(oversized);

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(guest.body(response).path("success").asBoolean(true)).isFalse();
        }

        private ResponseEntity<String> postEncoded(String json) {
            // A java.net.URI is sent as-is — a String URL would be re-encoded to `canc%2565l`.
            URI uri = URI.create(restTemplate.getRootUri() + "/api/v1/book/canc%65l/" + UUID.randomUUID());
            HttpHeaders headers = guest.headers();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
        }
    }

    // ── JSON helpers ───────────────────────────────────────────────────────────

    private static List<UUID> ids(JsonNode items) {
        return texts(items, "bookingId").stream().map(UUID::fromString).toList();
    }

    private static List<String> texts(JsonNode items, String field) {
        return StreamSupport.stream(items.spliterator(), false).map(item -> item.path(field).asText()).toList();
    }
}
