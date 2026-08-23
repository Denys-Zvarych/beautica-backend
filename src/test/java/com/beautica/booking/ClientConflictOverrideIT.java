package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack coverage for the client-supplied {@code allowClientOverlap} opt-in on
 * {@code POST /bookings} (product decision 2026-08-22) — see {@link CreateBookingRequest#allowClientOverlap()}.
 *
 * <p>Only {@code BookingService#assertNoClientConflict} (the CLIENT's own-calendar guard) becomes
 * skippable. The per-master {@code existsOverlap} pre-check and the {@code no_overlapping_bookings}
 * GIST EXCLUDE constraint — which protect a DIFFERENT client's claim on a master's slot — are never
 * affected by this flag, for any client. {@link
 * #should_stillRejectDoubleBooking_when_twoDifferentClientsTargetTheSameMasterSlotEvenWithAllowClientOverlap}
 * is the load-bearing proof of that boundary: if it could be made to pass while also bypassing
 * {@code existsOverlap}, the change would be unsafe.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /bookings — allowClientOverlap opt-in (product decision 2026-08-22)")
class ClientConflictOverrideIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";

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
    @DisplayName("201 for BOTH bookings when the client double-books THEMSELVES with allowClientOverlap=true")
    void should_createTheBooking_when_theClientOverlapsThemselvesAndAllowClientOverlapIsTrue() throws Exception {
        String clientEmail = "cco-self-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID masterAId = fixtures.createIndependentMaster("cco-self-masterA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = fixtures.createIndependentMasterService(masterAId);
        fixtures.addWorkingHoursForEveryDay(masterAId);

        UUID masterBId = fixtures.createIndependentMaster("cco-self-masterB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBId);
        fixtures.addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);

        var firstRequest = new CreateBookingRequest(masterAId, masterAServiceId, startsAt, null, null, false);
        ResponseEntity<String> firstResponse = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(firstRequest, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(firstResponse.getStatusCode())
                .as("setup: first booking must succeed — body: %s", firstResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // Same client, SAME time window, a different master — normally CLIENT_BOOKING_CONFLICT — but
        // allowClientOverlap=true is the explicit opt-in to allow exactly this.
        var secondRequest = new CreateBookingRequest(masterBId, masterBServiceId, startsAt, null, null, true);
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(secondRequest, fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(secondResponse.getStatusCode())
                .as("allowClientOverlap=true must let the client double-book themselves — body: %s",
                        secondResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);

        Long confirmedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE client_id = ? AND status = 'CONFIRMED'",
                Long.class, clientId);
        assertThat(confirmedCount)
                .as("both overlapping CONFIRMED bookings for the same client must exist afterwards")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("409 CLIENT_BOOKING_CONFLICT, unchanged payload, when allowClientOverlap is ABSENT from the JSON body")
    void should_stillReject_when_theClientOverlapsThemselvesAndTheFlagIsAbsent() throws Exception {
        String clientEmail = "cco-absent-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID masterAId = fixtures.createIndependentMaster("cco-absent-masterA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = fixtures.createIndependentMasterService(masterAId);
        fixtures.addWorkingHoursForEveryDay(masterAId);

        UUID masterBId = fixtures.createIndependentMaster("cco-absent-masterB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBId);
        fixtures.addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);

        var firstRequest = new CreateBookingRequest(masterAId, masterAServiceId, startsAt, null, null, false);
        ResponseEntity<String> firstResponse = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(firstRequest, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(firstResponse.getStatusCode())
                .as("setup: first booking must succeed — body: %s", firstResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode firstBody = objectMapper.readTree(firstResponse.getBody());
        UUID existingBookingId = UUID.fromString(firstBody.path("data").path("id").asText());

        // Hand-built JSON body that OMITS allowClientOverlap entirely — proves the wire-level
        // default (a missing primitive boolean deserializes to false), not merely the Java default.
        String rawBody = objectMapper.writeValueAsString(Map.of(
                "masterId", masterBId.toString(),
                "masterServiceId", masterBServiceId.toString(),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(rawBody, fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(secondResponse.getStatusCode())
                .as("an absent allowClientOverlap field must reproduce today's rejection unchanged")
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode body = objectMapper.readTree(secondResponse.getBody());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").path("code").asText())
                .as("unchanged conflict code")
                .isEqualTo("CLIENT_BOOKING_CONFLICT");
        assertThat(body.path("data").path("conflictingBookingId").asText())
                .as("unchanged conflict detail")
                .isEqualTo(existingBookingId.toString());
        assertThat(body.path("message").asText())
                .as("unchanged conflict message")
                .isEqualTo("Client already has an overlapping booking");

        long bookingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Long.class);
        assertThat(bookingCount)
                .as("only the first booking must exist — the rejected attempt was never persisted")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("THE ONE THAT MATTERS — allowClientOverlap on client B's request can NEVER let client B "
            + "steal client A's master slot; the per-master rule is untouched")
    void should_stillRejectDoubleBooking_when_twoDifferentClientsTargetTheSameMasterSlotEvenWithAllowClientOverlap()
            throws Exception {
        String clientAEmail = "cco-diff-clienta-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientAEmail, "CLIENT", null);
        String clientAToken = fixtures.tokenFor(clientAEmail);

        String clientBEmail = "cco-diff-clientb-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientBEmail, "CLIENT", null);
        String clientBToken = fixtures.tokenFor(clientBEmail);

        UUID masterId = fixtures.createIndependentMaster("cco-diff-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(12).withMinute(0).withSecond(0).withNano(0);

        // Client A takes the slot — normal create, no flag.
        var firstRequest = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null, false);
        ResponseEntity<String> firstResponse = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(firstRequest, fixtures.bearerHeaders(clientAToken)), String.class);
        assertThat(firstResponse.getStatusCode())
                .as("setup: client A's booking must succeed — body: %s", firstResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // Client B — who holds NO booking of their own, so BookingService#assertNoClientConflict would
        // never fire against them anyway — tries the SAME master's SAME slot with allowClientOverlap=true.
        // This must still fail: the per-master existsOverlap check + no_overlapping_bookings EXCLUDE are
        // not this client's to waive; they protect client A's already-CONFIRMED booking.
        var secondRequest = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null, true);
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(secondRequest, fixtures.bearerHeaders(clientBToken)), String.class);

        assertThat(secondResponse.getStatusCode())
                .as("allowClientOverlap must NEVER let a different client take an already-taken master "
                        + "slot — body: %s", secondResponse.getBody())
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode body = objectMapper.readTree(secondResponse.getBody());
        assertThat(body.path("data").path("code").asText())
                .as("this must be the GENERIC master-busy conflict, not CLIENT_BOOKING_CONFLICT — client "
                        + "B has no conflicting booking of their own, so that code proving here would mean "
                        + "the per-master guard was bypassed instead of correctly firing")
                .isNotEqualTo("CLIENT_BOOKING_CONFLICT");

        long bookingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Long.class);
        assertThat(bookingCount)
                .as("exactly one booking (client A's) must exist — client B's attempt was never persisted")
                .isEqualTo(1L);

        long confirmedForMaster = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = 'CONFIRMED'",
                Long.class, masterId);
        assertThat(confirmedForMaster)
                .as("the master must still hold exactly ONE CONFIRMED booking for this slot")
                .isEqualTo(1L);
    }
}
