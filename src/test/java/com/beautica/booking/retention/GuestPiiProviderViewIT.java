package com.beautica.booking.retention;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.beautica.booking.retention.GuestPiiFixtures.GUEST_NAME;
import static com.beautica.booking.retention.GuestPiiFixtures.GUEST_SURNAME;
import static com.beautica.booking.retention.GuestPiiFixtures.PHONE;
import static com.beautica.booking.retention.GuestPiiFixtures.PHONE_LOCAL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 373 — what the PROVIDER sees over HTTP after a guest's PII is gone (acceptance: "no
 * guest_phone/guest_name value of an anonymised row appears in API responses"; tests 12 + 16).
 *
 * <p>{@link GuestPiiRetentionIT} pins the DB rows and the single-booking detail of a LINK row.
 * This class closes the provider-facing gaps it leaves: the STAFF walk-in shape through detail AND
 * the history list, the multi-service visit detail (its header carries the guest's free-text
 * {@code client_comment}, seeded here with the phone in it), the feed endpoint for an erased visit,
 * and the real-context binding of {@link GuestPiiRetentionProperties} via {@code AppConfigProperties}.
 */
@DisplayName("Guest PII retention — provider-facing API after anonymisation / erase")
class GuestPiiProviderViewIT extends AbstractIntegrationTest {

    private static final String INTERNAL_KEY = "test-internal-api-key";
    private static final String PHONE_DIGITS = "501234567";

    @Autowired private GuestPiiAnonymisationService service;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GuestPiiRetentionProperties properties;
    @Autowired private ApplicationContext context;

    private GuestPiiFixtures fx;
    private GuestPiiFixtures.Provider provider;
    private Instant cutoff;

    @BeforeEach
    void setUp() {
        fx = new GuestPiiFixtures(jdbcTemplate);
        provider = fx.createIndependentMaster();
        cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(12).toInstant();
    }

    @Test
    @DisplayName("STAFF walk-in anonymised by the job: provider detail and HISTORY list show «Гість», no phone/name/notes, totals and business fields unchanged")
    void should_renderSentinelInDetailAndHistory_when_staffWalkInAnonymised() throws Exception {
        UUID walkIn = fx.staffWalkIn(provider, "COMPLETED",
                cutoff.atOffset(ZoneOffset.UTC).minusHours(3), PHONE);
        long totalBefore = history().path("totalElements").asLong();
        assertThat(totalBefore).as("walk-in visible in history before the run").isEqualTo(1);

        service.anonymiseDue(cutoff);

        ResponseEntity<String> detail = get("/api/v1/bookings/" + walkIn);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(detail.getBody()).path("data");
        assertThat(data.path("clientFirstName").asText()).isEqualTo(GuestPii.ANONYMISED_NAME);
        assertThat(data.path("clientLastName").isNull()).as("surname wiped, body=%s", data).isTrue();
        assertThat(data.path("clientComment").isNull()).as("guest free text wiped").isTrue();
        assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("priceAtBooking").decimalValue()).isEqualByComparingTo("350.00");
        assertNoPii(detail.getBody());

        JsonNode historyAfter = history();
        assertThat(historyAfter.path("totalElements").asLong())
                .as("provider history count unchanged by anonymisation").isEqualTo(totalBefore);
        JsonNode row = StreamSupport.stream(historyAfter.path("data").spliterator(), false)
                .filter(n -> walkIn.toString().equals(n.path("id").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("walk-in missing from provider history: " + historyAfter));
        assertThat(row.path("clientFirstName").asText()).isEqualTo(GuestPii.ANONYMISED_NAME);
        assertNoPii(historyAfter.toString());
    }

    @Test
    @DisplayName("erase via the internal endpoint: provider visit detail is CANCELLED with both items and prices kept, header notes wiped; the feed says «Гість»")
    void should_hideVisitNotesAndRenderSentinelInFeed_when_visitErasedThroughEndpoint() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID visit = fx.appointment("LINK", "CONFIRMED", PHONE, UUID.randomUUID(), null);
        UUID item1 = fx.visitItem(provider, visit, "LINK", "CONFIRMED", now.plusDays(4).plusHours(1), PHONE);
        UUID item2 = fx.visitItem(provider, visit, "LINK", "CONFIRMED", now.plusDays(4).plusHours(2), PHONE);
        assertThat(fx.appointmentRow(visit).get("client_comment").toString())
                .as("fixture precondition: the header note carries the phone").contains(PHONE_DIGITS);

        ResponseEntity<String> erase = eraseThroughEndpoint(PHONE_LOCAL);

        assertThat(erase.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode counts = objectMapper.readTree(erase.getBody()).path("data");
        assertThat(counts.path("visitsCancelled").asInt()).isEqualTo(1);
        assertThat(counts.path("visitsAnonymised").asInt()).isEqualTo(1);

        ResponseEntity<String> detail = get("/api/v1/appointments/" + visit);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(detail.getBody()).path("data");
        assertThat(data.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(data.path("clientComment").isNull()).as("header note wiped, body=%s", data).isTrue();
        assertThat(data.path("clientCancellationNote").isNull()).isTrue();
        assertThat(data.path("items")).hasSize(2);
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("bookingId").asText()).isIn(item1.toString(), item2.toString());
            assertThat(item.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(item.path("priceAtBooking").decimalValue()).isEqualByComparingTo("350.00");
        }
        assertNoPii(detail.getBody());

        ResponseEntity<String> feed = get("/api/v1/notifications");
        assertThat(feed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = objectMapper.readTree(feed.getBody()).path("data");
        assertThat(items).as("one BOOKING_CANCELLED_BY_CLIENT per visit").hasSize(1);
        assertThat(items.get(0).path("type").asText()).isEqualTo("BOOKING_CANCELLED_BY_CLIENT");
        assertThat(items.get(0).path("params").path("counterpartName").asText())
                .isEqualTo(GuestPii.ANONYMISED_NAME);
        assertNoPii(feed.getBody());
    }

    @Test
    @DisplayName("the running context binds app.guest-pii-retention through AppConfigProperties and, in the test profile, has no job bean")
    void should_bindRetentionProperties_and_omitJob_when_testProfile() {
        assertThat(properties)
                .isEqualTo(new GuestPiiRetentionProperties(false, "0 27 3 * * *", 12, 500, 200));
        assertThat(context.getBeanNamesForType(GuestPiiRetentionJob.class)).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private JsonNode history() throws Exception {
        ResponseEntity<String> list = get("/api/v1/bookings/me?partition=HISTORY&size=50");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        // GET /bookings/me wraps a PageResponse inside ApiResponse.data (existing contract).
        return objectMapper.readTree(list.getBody()).path("data");
    }

    private ResponseEntity<String> get(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(
                provider.ownerUserId(), provider.ownerEmail(), Role.INDEPENDENT_MASTER));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> eraseThroughEndpoint(String phone) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", INTERNAL_KEY);
        String body = objectMapper.writeValueAsString(Map.of("phone", phone));
        return restTemplate.postForEntity("/api/v1/internal/guest-pii/erase",
                new HttpEntity<>(body, headers), String.class);
    }

    private static void assertNoPii(String body) {
        assertThat(body)
                .doesNotContain(PHONE_DIGITS)
                .doesNotContain(GUEST_NAME)
                .doesNotContain(GUEST_SURNAME);
    }
}
