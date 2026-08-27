package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
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
 * Full-HTTP-stack coverage for the client-supplied {@code allowClientOverlap} opt-in — originally
 * {@code POST /bookings} (product decision 2026-08-22, see
 * {@link CreateBookingRequest#allowClientOverlap()}), widened 2026-08-26 to both reschedule paths:
 * {@code PATCH /bookings/{id}/reschedule} ({@link RescheduleBookingRequest#allowClientOverlap()})
 * and {@code PATCH /appointments/{appointmentId}/services/{bookingId}/reschedule}
 * ({@link AppointmentItemRescheduleRequest#allowClientOverlap()}).
 *
 * <p>Only the CLIENT's own-calendar guard becomes skippable on every path
 * ({@code BookingService#assertNoClientConflict(Excluding)}, {@code AppointmentTransitionService
 * #assertNoClientConflictExcludingBooking}). The per-master {@code existsOverlap(Excluding)}
 * pre-check and the {@code no_overlapping_bookings} GIST EXCLUDE constraint — which protect a
 * DIFFERENT client's claim on a master's slot — are never affected by this flag, for any client, on
 * any path. {@link
 * #should_stillRejectDoubleBooking_when_twoDifferentClientsTargetTheSameMasterSlotEvenWithAllowClientOverlap}
 * is the load-bearing proof of that boundary for CREATE.
 *
 * <p><b>The two RESCHEDULE-path positive tests</b> (client-initiated, target master slot genuinely
 * free, only the client's own calendar overlaps) moved to
 * {@code com.beautica.booking.service.RescheduleMasterOverlapGuardConcurrencyIT} (backend-qa LOW /
 * backend-perf, cycle audit 2026-08-26) — that class already carries a superset {@code @SpyBean}
 * combination ({@code BookingRepository} + {@code BookingService} + {@code AppointmentTransitionService})
 * for its own negative race tests, so co-locating the positive half there avoids bootstrapping a
 * SECOND, narrower Spring context (this class's old {@code BookingRepository}-only spy) purely to
 * host two tests. This class therefore no longer spies {@code BookingRepository} — nothing left here
 * needs it (verified: neither the CREATE-path tests above nor the provider-gate tests below call
 * {@code verify(bookingRepository)} anywhere).
 *
 * <p><b>The two provider-gate negative tests below</b>
 * ({@link #should_return409_when_providerReschedulesWithAllowClientOverlapTrue_andClientHasNotConsented},
 * {@link #should_return409_when_providerReschedulesItemWithAllowClientOverlapTrue_andClientHasNotConsented})
 * close backend-security HIGH (cycle audit 2026-08-26): {@code req.allowClientOverlap()} is the
 * CLIENT's own consent to waive their own-calendar conflict, but both reschedule routes are also
 * reachable by SALON_OWNER/SALON_ADMIN/INDEPENDENT_MASTER (unlike {@code POST /bookings}, which is
 * CLIENT-only). Without an actor gate, a provider already authorized to reschedule a client's booking
 * could set {@code allowClientOverlap=true} and silently double-book that client's calendar on the
 * client's behalf. {@code BookingService#rescheduleBooking} / {@code AppointmentTransitionService
 * #rescheduleAppointmentItem} now gate the skip on {@code initiatedByProvider} (the existing
 * {@code actorRole != Role.CLIENT} discriminator each method already computes to pick its
 * resolver/validator) — {@code initiatedByProvider || !req.allowClientOverlap()} — so the guard runs
 * unconditionally whenever a provider is the actor, regardless of the flag.
 *
 * <p><b>The RESCHEDULE-path negative ("master busy") race tests live in a SEPARATE class</b> —
 * {@code com.beautica.booking.service.RescheduleMasterOverlapGuardConcurrencyIT} — not here, and not
 * for style reasons. {@code BookingService#assertStartsOnAvailableSlot} /
 * {@code AppointmentTransitionService#assertItemStartsOnAvailableSlot} run BEFORE the per-master
 * advisory lock and BEFORE {@code existsOverlapExcluding}, and the slot list they consult already has
 * the master's CONFIRMED bookings subtracted (see {@code BookingService#rescheduleBooking}'s own "the
 * slot list already has the master's CONFIRMED bookings subtracted" javadoc). A conflicting booking
 * that is ALREADY committed before the reschedule request starts is therefore rejected at THAT
 * earlier guard, with the SAME generic "Slot not available" 409 — {@code existsOverlapExcluding} is
 * never even reached, so a naive pre-seeded-conflict test in THIS class cannot exercise, and cannot
 * mutation-prove, the line these tests exist to guard. Reaching it requires racing the occupying
 * booking to commit strictly AFTER the reschedule's own (unlocked) slot read but strictly BEFORE its
 * (locked) {@code existsOverlapExcluding} call — which in turn requires pausing the reschedule thread
 * at a PACKAGE-PRIVATE seam ({@code BookingService#isStillConfirmed} /
 * {@code AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule}), exactly the same
 * constraint that already put {@code BookingCancelRescheduleConcurrencyIT} and
 * {@code AppointmentCrossPathTransitionConcurrencyIT} in {@code com.beautica.booking.service} rather
 * than here (see {@link BookingTestFixtures}'s own "public (cycle-2 audit finding 5)" javadoc
 * paragraph for the identical rationale).
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /bookings + PATCH .../reschedule — allowClientOverlap opt-in (product decision 2026-08-22, widened 2026-08-26)")
class ClientConflictOverrideIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

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

    // ── PROVIDER GATE — PATCH /bookings/{id}/reschedule (BookingService#rescheduleBooking) ─────────

    @Test
    @DisplayName("RESCHEDULE — 409 CLIENT_BOOKING_CONFLICT when the PROVIDER (not the client) sends "
            + "allowClientOverlap=true; the target master's slot is genuinely free, but the override "
            + "is the CLIENT's consent to give, never the provider's")
    void should_return409_when_providerReschedulesWithAllowClientOverlapTrue_andClientHasNotConsented()
            throws Exception {
        String clientEmail = "cco-provgate-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID masterAId = fixtures.createIndependentMaster(
                "cco-provgate-masterA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = fixtures.createIndependentMasterService(masterAId);
        fixtures.addWorkingHoursForEveryDay(masterAId);

        String masterBEmail = "cco-provgate-masterb-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = fixtures.createIndependentMaster(masterBEmail);
        String masterBToken = fixtures.tokenFor(masterBEmail);
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBId);
        fixtures.addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime slotT1 = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime slotT2 = slotT1.plusHours(4); // master B's ORIGINAL slot — clear of T1

        var bookingOnA = new CreateBookingRequest(masterAId, masterAServiceId, slotT1, null, null, false);
        ResponseEntity<String> respA = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingOnA, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(respA.getStatusCode())
                .as("setup: client's booking on master A must succeed — body: %s", respA.getBody())
                .isEqualTo(HttpStatus.CREATED);

        var bookingOnB = new CreateBookingRequest(masterBId, masterBServiceId, slotT2, null, null, false);
        ResponseEntity<String> respB = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingOnB, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(respB.getStatusCode())
                .as("setup: client's booking on master B must succeed — body: %s", respB.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID bookingBId = UUID.fromString(objectMapper.readTree(respB.getBody()).path("data").path("id").asText());

        // Master B — the PROVIDER authorized to reschedule the booking they perform — moves it onto
        // T1 and sets allowClientOverlap=true on the client's behalf. Master B's OWN calendar is
        // genuinely free at T1 (no master-busy conflict), so a 200 here would prove the provider
        // silently waived the CLIENT's own-calendar conflict (master A's booking, also at T1)
        // without the client ever consenting.
        var rescheduleRequest = new RescheduleBookingRequest(slotT1.toOffsetDateTime(), true);
        ResponseEntity<String> rescheduleResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingBId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(rescheduleRequest, fixtures.bearerHeaders(masterBToken)), String.class);

        assertThat(rescheduleResponse.getStatusCode())
                .as("a PROVIDER may not waive the CLIENT's own-calendar overlap on the client's "
                        + "behalf, even with allowClientOverlap=true — body: %s",
                        rescheduleResponse.getBody())
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode body = objectMapper.readTree(rescheduleResponse.getBody());
        assertThat(body.path("data").path("code").asText())
                .as("must be the client-conflict code — proves the guard actually ran rather than "
                        + "failing for an unrelated reason")
                .isEqualTo("CLIENT_BOOKING_CONFLICT");

        OffsetDateTime persistedStartsAt = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingBId);
        assertThat(persistedStartsAt.toInstant())
                .as("the rejected provider reschedule must leave the booking at its ORIGINAL time")
                .isEqualTo(slotT2.toOffsetDateTime().toInstant());
    }

    // ── PROVIDER GATE — PATCH .../services/{bookingId}/reschedule (AppointmentTransitionService) ──

    @Test
    @DisplayName("RESCHEDULE ITEM — 409 CLIENT_BOOKING_CONFLICT when the PROVIDER (not the client) "
            + "sends allowClientOverlap=true on a per-item reschedule; the item's target master slot "
            + "is genuinely free, but the override is the CLIENT's consent to give")
    void should_return409_when_providerReschedulesItemWithAllowClientOverlapTrue_andClientHasNotConsented()
            throws Exception {
        String clientEmail = "cco-provgate-item-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID masterAId = fixtures.createIndependentMaster(
                "cco-provgate-item-masterA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = fixtures.createIndependentMasterService(masterAId);
        fixtures.addWorkingHoursForEveryDay(masterAId);

        String masterBEmail = "cco-provgate-item-masterb-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = fixtures.createIndependentMaster(masterBEmail);
        String masterBToken = fixtures.tokenFor(masterBEmail);
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBId);
        fixtures.addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime slotT1 = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime slotT2 = slotT1.plusHours(4);

        var bookingOnA = new CreateBookingRequest(masterAId, masterAServiceId, slotT1, null, null, false);
        ResponseEntity<String> respA = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingOnA, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(respA.getStatusCode())
                .as("setup: client's booking on master A must succeed — body: %s", respA.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // Client's own single-service visit on master B, at a clear time.
        String visitBody = objectMapper.writeValueAsString(Map.of(
                "masterId", masterBId.toString(),
                "masterServiceIds", List.of(masterBServiceId.toString()),
                "startsAt", slotT2.toOffsetDateTime().toString()));
        ResponseEntity<String> visitResponse = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST,
                new HttpEntity<>(visitBody, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(visitResponse.getStatusCode())
                .as("setup: client's visit on master B must succeed — body: %s", visitResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode visitData = objectMapper.readTree(visitResponse.getBody()).path("data");
        UUID appointmentId = UUID.fromString(visitData.path("id").asText());
        UUID itemBookingId = UUID.fromString(visitData.path("items").get(0).path("bookingId").asText());

        // Master B — the PROVIDER of this item — moves it onto T1 with allowClientOverlap=true.
        // Master B's own calendar is genuinely free at T1, so a 200 here would prove the provider
        // silently waived the client's own-calendar conflict (master A's booking, also at T1) on the
        // client's behalf.
        var rescheduleItemRequest = new AppointmentItemRescheduleRequest(slotT1.toOffsetDateTime(), true);
        ResponseEntity<String> rescheduleResponse = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + itemBookingId + "/reschedule",
                HttpMethod.PATCH, new HttpEntity<>(rescheduleItemRequest, fixtures.bearerHeaders(masterBToken)),
                String.class);

        assertThat(rescheduleResponse.getStatusCode())
                .as("a PROVIDER may not waive the CLIENT's own-calendar overlap on a per-item "
                        + "reschedule either, even with allowClientOverlap=true — body: %s",
                        rescheduleResponse.getBody())
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode body = objectMapper.readTree(rescheduleResponse.getBody());
        assertThat(body.path("data").path("code").asText())
                .as("must be the client-conflict code — proves the guard actually ran rather than "
                        + "failing for an unrelated reason")
                .isEqualTo("CLIENT_BOOKING_CONFLICT");

        OffsetDateTime persistedStartsAt = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, itemBookingId);
        assertThat(persistedStartsAt.toInstant())
                .as("the rejected provider reschedule must leave the item at its ORIGINAL time")
                .isEqualTo(slotT2.toOffsetDateTime().toInstant());
    }
}
