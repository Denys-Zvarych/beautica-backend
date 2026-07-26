package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingElapsedResponse;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * BE-4 consistency guard: THREE of the four SINGLE-booking transition endpoints —
 * {@code PATCH /bookings/{id}/decline|complete|not-complete}, all PROVIDER-initiated — must REFUSE
 * to act on a booking that is one item of a multi-service visit ({@code appointment_id != null}).
 * Transitioning a single child independently through one of those three would desync the
 * {@code appointments} header from its OTHER sibling items; a whole visit must still be moved only
 * through the {@code /appointments/{id}/...} lockstep endpoints (covered by
 * {@link AppointmentTransitionIT}) for those three transitions.
 *
 * <p>Each of those three guard tests proves the child booking (a) is rejected with 409 by the
 * service-layer guard — the controller {@code @PreAuthorize} role/ownership gate passes first, so
 * this is not an authz rejection — and (b) is left byte-for-byte unchanged: still {@code CONFIRMED},
 * still bound to its appointment header. The legacy tests prove a standalone booking
 * ({@code appointment_id = null}) still transitions exactly as before through all four endpoints.
 *
 * <p><b>Track 27.x widening — {@code PATCH /bookings/{id}/cancel} is the exception.</b> The
 * CLIENT-initiated cancel no longer refuses a visit child: a client may cancel exactly one leg,
 * leaving its siblings untouched, and the visit header is recomputed rather than the call being
 * refused. See {@code should_return204AndCancelOnlyThatLeg_when_clientCancelsOneLegOfTwoServiceVisit}
 * and {@code should_collapseHeaderToCancelled_when_clientCancelsTheLastConfirmedLeg} below — the
 * per-leg CLIENT-cancel counterpart of the per-child PROVIDER-decline test further down this file.
 *
 * <p><b>backend-qa closure pass (same track).</b> Six more tests below round out the per-leg widening:
 * a three-service visit proves the header recompute is not order-dependent (middle-leg cancel, not
 * just first/last), a foreign-client attempt proves the existence-oracle guard still holds on this
 * path, a same-leg replay proves the CONFIRMED-status guard blocks a double-cancel without a double
 * outbox enqueue, and an elapsed-leg attempt proves the read-only-after-elapse guard still applies
 * per-child. The concurrent last-two-legs race is deliberately NOT exercised here — see
 * {@link com.beautica.booking.service.AppointmentClientLegCancelConcurrencyIT} for why it needed
 * its own file (and now lives in {@code .service}, not this package — cycle-2 audit finding 5) with
 * a {@code @SpyBean}-gated test instead.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Single-booking transitions refuse a multi-service visit child (BE-4 guard)")
class BookingAppointmentChildTransitionGuardIT extends AbstractIntegrationTest {

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

    // ── the guard: a visit child cannot be transitioned via the PROVIDER single-booking endpoints ──

    @Test
    @DisplayName("PATCH /decline on a visit CHILD returns 409 and the child stays CONFIRMED + still "
            + "bound to its appointment")
    void should_return409AndKeepChildUnchanged_when_providerDeclinesAVisitChild() throws Exception {
        Visit visit = createTwoServiceVisit("decline");
        UUID child = firstChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.masterToken(), child, "decline",
                "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}");

        assertGuardRejected(resp, child, visit.id());
    }

    @Test
    @DisplayName("PATCH /complete on a visit CHILD returns 409 and the child stays CONFIRMED + still "
            + "bound to its appointment")
    void should_return409AndKeepChildUnchanged_when_providerCompletesAVisitChild() throws Exception {
        Visit visit = createTwoServiceVisit("complete");
        UUID child = firstChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.masterToken(), child, "complete", null);

        assertGuardRejected(resp, child, visit.id());
    }

    @Test
    @DisplayName("PATCH /not-complete on a visit CHILD returns 409 and the child stays CONFIRMED + "
            + "still bound to its appointment")
    void should_return409AndKeepChildUnchanged_when_providerMarksVisitChildNotComplete() throws Exception {
        Visit visit = createTwoServiceVisit("noshow");
        UUID child = firstChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.masterToken(), child, "not-complete",
                "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}");

        assertGuardRejected(resp, child, visit.id());
    }

    // ── the per-child endpoint SUCCEEDS where the single-booking path is refused ───────────────────

    @Test
    @DisplayName("the NEW per-child endpoint PATCH /appointments/{id}/services/{child}/decline succeeds "
            + "(204) on the exact visit child that the single-booking PATCH /bookings/{child}/decline "
            + "refuses (409) — declining ONE service leaves the sibling CONFIRMED and the header CONFIRMED")
    void should_succeedViaPerChildEndpoint_whereSingleBookingPath409d() throws Exception {
        Visit visit = createTwoServiceVisit("per-child-vs-legacy");
        UUID child0 = firstChildOf(visit.id());
        UUID child1 = secondChildOf(visit.id());

        // The OLD single-booking path is refused for a visit child (the existing guard contract).
        ResponseEntity<String> viaBooking = patch(visit.masterToken(), child0, "decline",
                "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}");
        assertThat(viaBooking.getStatusCode())
                .as("the single-booking decline path must still refuse a visit child — body: %s", viaBooking.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(child0)).as("refused path left the child CONFIRMED").isEqualTo("CONFIRMED");

        // The NEW per-child appointment path succeeds on the very same child.
        ResponseEntity<String> viaAppointment = patchAppointmentServiceDecline(visit.masterToken(), visit.id(), child0,
                "{\"providerComment\":\"скасовуємо одну послугу\"}");
        assertThat(viaAppointment.getStatusCode())
                .as("the per-child appointment decline path must succeed on the same child — body: %s",
                        viaAppointment.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(child0)).as("the targeted child is now DECLINED").isEqualTo("DECLINED");
        assertThat(dbStatus(child1)).as("the sibling stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(visit.id()))
                .as("the header stays CONFIRMED while a sibling remains").isEqualTo("CONFIRMED");
        assertThat(childAppointmentId(child0))
                .as("the declined child stays bound to its appointment header").isEqualTo(visit.id());
    }

    // ── track 27.x widening: PATCH /bookings/{id}/cancel now SUCCEEDS on a visit child ─────────────
    // (the CLIENT-cancel counterpart of the per-child PROVIDER-decline test above)

    @Test
    @DisplayName("PATCH /cancel on ONE leg of a two-service visit returns 204, cancels only that leg, "
            + "leaves the sibling CONFIRMED, and leaves the visit HEADER CONFIRMED — the client-cancel "
            + "widening no longer refuses an appointment child the way decline/complete/not-complete do")
    void should_return204AndCancelOnlyThatLeg_when_clientCancelsOneLegOfTwoServiceVisit() throws Exception {
        Visit visit = createTwoServiceVisit("client-cancel-one-leg");
        UUID child0 = firstChildOf(visit.id());
        UUID child1 = secondChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.clientToken(), child0, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"передумав щодо однієї послуги\"}");

        assertThat(resp.getStatusCode())
                .as("the widened per-leg client cancel must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(child0)).as("the targeted leg is now CANCELLED").isEqualTo("CANCELLED");
        assertThat(dbStatus(child1)).as("the sibling leg stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(childAppointmentId(child0))
                .as("the cancelled leg stays bound to its appointment header").isEqualTo(visit.id());
        assertThat(appointmentStatus(visit.id()))
                .as("the header stays CONFIRMED while a sibling remains CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentCancellationReason(visit.id()))
                .as("no cancellation_reason is written to a header that stays CONFIRMED — the "
                        + "chk_appointment_cancellation_reason_status CHECK (V124) forbids one on a "
                        + "non-terminal header")
                .isNull();
        assertThat(appointmentClientCancellationNote(visit.id()))
                .as("no note is written to a header that stays CONFIRMED — "
                        + "chk_appointment_client_cancellation_note_status (V124) requires CANCELLED")
                .isNull();
    }

    @Test
    @DisplayName("cancelling the LAST CONFIRMED leg of a two-service visit collapses the HEADER to "
            + "CANCELLED with reason CLIENT_CANCELLED, carrying that cancel's note — mirrors the "
            + "provider per-service decline's header-recompute invariant, generalised to the client path")
    void should_collapseHeaderToCancelled_when_clientCancelsTheLastConfirmedLeg() throws Exception {
        Visit visit = createTwoServiceVisit("client-cancel-last-leg");
        UUID child0 = firstChildOf(visit.id());
        UUID child1 = secondChildOf(visit.id());

        ResponseEntity<String> first = patch(visit.clientToken(), child0, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");
        assertThat(first.getStatusCode())
                .as("first leg cancel must succeed — body: %s", first.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(appointmentStatus(visit.id()))
                .as("header still CONFIRMED after the FIRST leg — one sibling remains")
                .isEqualTo("CONFIRMED");

        ResponseEntity<String> second = patch(visit.clientToken(), child1, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"більше не потрібно жодної послуги\"}");

        assertThat(second.getStatusCode())
                .as("second (last) leg cancel must also succeed — body: %s", second.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(child0)).isEqualTo("CANCELLED");
        assertThat(dbStatus(child1)).isEqualTo("CANCELLED");
        assertThat(appointmentStatus(visit.id()))
                .as("no CONFIRMED sibling remains — the header collapses to CANCELLED")
                .isEqualTo("CANCELLED");
        assertThat(appointmentCancellationReason(visit.id()))
                .as("the header's reason is fixed by the operation, mirroring cancelAppointment")
                .isEqualTo("CLIENT_CANCELLED");
        assertThat(appointmentClientCancellationNote(visit.id()))
                .as("the header carries the note from the cancel that actually collapsed it")
                .isEqualTo("більше не потрібно жодної послуги");
    }

    @Test
    @DisplayName("PATCH /cancel on the MIDDLE leg of a THREE-service visit returns 204, cancels only "
            + "that leg, leaves BOTH the first and third legs CONFIRMED, and leaves the header CONFIRMED "
            + "with cancellation_reason/client_cancellation_note still NULL — proves the header recompute "
            + "is not order-dependent (a two-leg fixture can't distinguish 'first/last-only' logic from "
            + "'any-leg' logic)")
    void should_return204AndCancelOnlyThatLeg_when_clientCancelsMiddleLegOfThreeServiceVisit() throws Exception {
        Visit visit = createVisit("middle-leg", 3);
        UUID child0 = childAt(visit.id(), 0);
        UUID child1 = childAt(visit.id(), 1);
        UUID child2 = childAt(visit.id(), 2);

        ResponseEntity<String> resp = patch(visit.clientToken(), child1, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"одна з трьох послуг більше не потрібна\"}");

        assertThat(resp.getStatusCode())
                .as("cancelling the middle leg of a three-service visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(child0)).as("first leg stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(dbStatus(child1)).as("middle (targeted) leg is now CANCELLED").isEqualTo("CANCELLED");
        assertThat(dbStatus(child2)).as("third leg stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(visit.id()))
                .as("header stays CONFIRMED — two of three legs remain CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentCancellationReason(visit.id()))
                .as("no cancellation_reason written to a header that stays CONFIRMED — "
                        + "chk_appointment_cancellation_reason_status (V124) forbids one on a non-terminal header")
                .isNull();
        assertThat(appointmentClientCancellationNote(visit.id()))
                .as("no note written to a header that stays CONFIRMED — "
                        + "chk_appointment_client_cancellation_note_status (V124) requires CANCELLED")
                .isNull();
    }

    @Test
    @DisplayName("PATCH /cancel by a DIFFERENT client on someone else's visit leg returns 403 — "
            + "byte-identical to the 403 for a booking id that does not exist at all, so neither response "
            + "lets an authenticated client probe whether an arbitrary booking id exists (existence oracle, "
            + "Finding 8)")
    void should_return403IndistinguishableFromNonexistentId_when_foreignClientCancelsAnothersLeg() throws Exception {
        Visit visit = createTwoServiceVisit("foreign-client");
        UUID child0 = firstChildOf(visit.id());
        String foreignClientEmail = "child-guard-foreign-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignClientEmail, "CLIENT", null);
        String foreignClientToken = fixtures.tokenFor(foreignClientEmail);

        ResponseEntity<String> foreignAttempt = patch(foreignClientToken, child0, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");
        ResponseEntity<String> nonexistentAttempt = patch(foreignClientToken, UUID.randomUUID(), "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(foreignAttempt.getStatusCode())
                .as("a client cancelling ANOTHER client's leg must be refused — body: %s", foreignAttempt.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nonexistentAttempt.getStatusCode())
                .as("a nonexistent booking id must be refused with the SAME status — body: %s",
                        nonexistentAttempt.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignAttempt.getBody())
                .as("the foreign-owner response body must be byte-identical to the nonexistent-id response — "
                        + "no existence oracle leaks through a differently-worded message")
                .isEqualTo(nonexistentAttempt.getBody());
        assertThat(dbStatus(child0)).as("the foreign attempt must not have mutated the leg").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(visit.id())).as("the header must be untouched").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("cancelling the SAME leg twice returns 400 on the replay (the CONFIRMED-status guard, "
            + "same status BookingService#cancelBooking already used for any non-CONFIRMED booking), leaves "
            + "the leg CANCELLED from the first call, does NOT double-enqueue the STATUS_CHANGED outbox "
            + "event for that booking, and leaves the header untouched by the replay")
    void should_reject400OnReplay_when_sameLegCancelledTwice() throws Exception {
        Visit visit = createTwoServiceVisit("replay");
        UUID child0 = firstChildOf(visit.id());

        ResponseEntity<String> first = patch(visit.clientToken(), child0, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");
        assertThat(first.getStatusCode())
                .as("the first cancel of the leg must succeed — body: %s", first.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        int outboxCountAfterFirst = statusChangedOutboxCount(child0);
        assertThat(outboxCountAfterFirst).as("the first cancel enqueues exactly one STATUS_CHANGED event")
                .isEqualTo(1);

        ResponseEntity<String> replay = patch(visit.clientToken(), child0, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(replay.getStatusCode())
                .as("a replayed cancel of an already-CANCELLED leg must be rejected by the CONFIRMED-status "
                        + "guard, not silently accepted or double-processed — body: %s", replay.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dbStatus(child0)).as("the leg stays CANCELLED as the first call left it").isEqualTo("CANCELLED");
        assertThat(statusChangedOutboxCount(child0))
                .as("the rejected replay must NOT enqueue a second STATUS_CHANGED event")
                .isEqualTo(outboxCountAfterFirst);
        assertThat(appointmentStatus(visit.id()))
                .as("the header is untouched by the rejected replay — the sibling leg still keeps it CONFIRMED")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /cancel on an ELAPSED leg of a visit still returns 409 BOOKING_ALREADY_ELAPSED — "
            + "the per-leg elapsed guard (BookingService#assertNotElapsedForClient) is evaluated on the "
            + "CHILD's own endsAt, exactly as it is for a standalone booking; the widening did not carve "
            + "out an exemption for visit children")
    void should_return409BookingAlreadyElapsed_when_clientCancelsAnElapsedVisitLeg() throws Exception {
        Visit visit = createTwoServiceVisit("elapsed-leg");
        UUID child0 = firstChildOf(visit.id());
        UUID child1 = secondChildOf(visit.id());
        markElapsed(child0);

        ResponseEntity<String> resp = patch(visit.clientToken(), child0, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertElapsedConflict(resp);
        assertThat(dbStatus(child0))
                .as("an elapsed leg must stay read-only for the client — the provider still owns resolution")
                .isEqualTo("CONFIRMED");
        assertThat(dbStatus(child1)).as("the untouched sibling stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(visit.id())).as("the header is untouched by a rejected cancel")
                .isEqualTo("CONFIRMED");
    }

    // ── legacy standalone booking (appointment_id NULL) still transitions normally ────────────────

    @Test
    @DisplayName("PATCH /cancel on a LEGACY standalone booking (appointment_id NULL) still returns "
            + "204 and moves it to CANCELLED — the guard leaves the null path untouched")
    void should_return204_when_clientCancelsLegacyStandaloneBooking() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("cancel");

        ResponseEntity<String> resp = patch(booking.clientToken(), booking.bookingId(), "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(resp.getStatusCode())
                .as("legacy standalone cancel must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("PATCH /decline on a LEGACY standalone booking still returns 204 and moves it to "
            + "DECLINED")
    void should_return204_when_providerDeclinesLegacyStandaloneBooking() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("decline");

        ResponseEntity<String> resp = patch(booking.masterToken(), booking.bookingId(), "decline",
                "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}");

        assertThat(resp.getStatusCode())
                .as("legacy standalone decline must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("PATCH /complete on a LEGACY standalone booking still returns 204 and moves it to "
            + "COMPLETED")
    void should_return204_when_providerCompletesLegacyStandaloneBooking() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("complete");

        ResponseEntity<String> resp = patch(booking.masterToken(), booking.bookingId(), "complete", null);

        assertThat(resp.getStatusCode())
                .as("legacy standalone complete must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PATCH /not-complete on a LEGACY standalone booking still returns 204 and moves it "
            + "to NOT_COMPLETED")
    void should_return204_when_providerMarksLegacyStandaloneBookingNotComplete() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("noshow");

        ResponseEntity<String> resp = patch(booking.masterToken(), booking.bookingId(), "not-complete",
                "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}");

        assertThat(resp.getStatusCode())
                .as("legacy standalone not-complete must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("NOT_COMPLETED");
    }

    // ── shared assertions ─────────────────────────────────────────────────────────────────────────

    /** Asserts the single-booking transition was refused with 409 and the child row is unchanged. */
    private void assertGuardRejected(ResponseEntity<String> resp, UUID child, UUID appointmentId) {
        assertThat(resp.getStatusCode())
                .as("a visit child must be refused with 409 (use /appointments) — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(child))
                .as("the rejected transition must not change the child's status")
                .isEqualTo("CONFIRMED");
        assertThat(childAppointmentId(child))
                .as("the rejected transition must not detach the child from its appointment header")
                .isEqualTo(appointmentId);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** A created two-service CONFIRMED visit plus the client and owning-master tokens to act on it. */
    private record Visit(UUID id, String clientToken, String masterToken) {}

    /** A legacy standalone CONFIRMED booking (appointment_id NULL) plus both actors' tokens. */
    private record Standalone(UUID bookingId, String clientToken, String masterToken) {}

    /**
     * Creates an INDEPENDENT_MASTER + CLIENT and posts a two-service CONFIRMED visit via
     * {@code POST /appointments}, returning its id and both actors' tokens. Mirrors
     * {@code AppointmentTransitionIT}'s local helper of the same name (kept local per the house
     * convention already applied there and in {@code AppointmentCreateIT}).
     */
    private Visit createTwoServiceVisit(String tag) throws Exception {
        return createVisit(tag, 2);
    }

    /**
     * Generalized {@code createTwoServiceVisit} — {@code serviceCount} services instead of a fixed
     * two, so the middle-leg test (three services) can reuse the exact same setup shape rather than
     * duplicating it. {@code createTwoServiceVisit} above delegates here with {@code serviceCount=2}
     * so every pre-existing test in this file is byte-for-byte unaffected.
     */
    private Visit createVisit(String tag, int serviceCount) throws Exception {
        String masterEmail = "child-guard-visit-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "child-guard-visit-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        List<UUID> serviceIds = new ArrayList<>();
        for (int i = 0; i < serviceCount; i++) {
            serviceIds.add(fixtures.createIndependentMasterService(masterId));
        }
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        String masterToken = fixtures.tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String serviceIdsJson = serviceIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));
        String body = """
                {"masterId":"%s","masterServiceIds":[%s],"startsAt":"%s"}
                """.formatted(masterId, serviceIdsJson, startsAt.toOffsetDateTime());
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode())
                .as("visit setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(created.getBody()).path("data");
        return new Visit(UUID.fromString(data.path("id").asText()), clientToken, masterToken);
    }

    /**
     * Seeds a CONFIRMED single-service standalone booking directly (appointment_id stays NULL —
     * the legacy path), owned by a fresh CLIENT and served by a fresh INDEPENDENT_MASTER.
     *
     * <p>FUTURE by default so the client-cancel path's elapsed-booking guard (endsAt-based) passes.
     * {@code "complete"} and {@code "noshow"} are the two exceptions: {@code completeBooking}
     * requires {@code now >= startsAt} (Phase 27.1), so those two fixtures are seeded ELAPSED
     * instead — {@code notCompleteBooking} has no temporal guard, but the "noshow" fixture stays
     * elapsed anyway as the conventional no-show default. {@code declineBooking} has no temporal
     * guard either, so the "decline" fixture's FUTURE default is likewise just convention.
     */
    private Standalone createLegacyStandaloneBooking(String tag) throws Exception {
        String masterEmail = "child-guard-legacy-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "child-guard-legacy-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime startsAt = ("complete".equals(tag) || "noshow".equals(tag))
                ? OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
                : OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, startsAt, startsAt.plusHours(1));
        return new Standalone(bookingId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

    private ResponseEntity<String> patch(String token, UUID bookingId, String action, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/" + action, HttpMethod.PATCH, entity, String.class);
    }

    private UUID firstChildOf(UUID appointmentId) {
        return childAt(appointmentId, 0);
    }

    private UUID secondChildOf(UUID appointmentId) {
        return childAt(appointmentId, 1);
    }

    /** The {@code offset}-th child (0-based, ordered by {@code starts_at}) of a visit. */
    private UUID childAt(UUID appointmentId, int offset) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at OFFSET ? LIMIT 1",
                UUID.class, appointmentId, offset);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    /** Nullable — {@code null} while the header stays CONFIRMED (no sibling has collapsed it yet). */
    private String appointmentCancellationReason(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    /** Nullable — {@code null} while the header stays CONFIRMED, or when no note was supplied. */
    private String appointmentClientCancellationNote(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_cancellation_note FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    /** PATCH the per-service decline route {@code /appointments/{id}/services/{bookingId}/decline}. */
    private ResponseEntity<String> patchAppointmentServiceDecline(
            String token, UUID appointmentId, UUID bookingId, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, entity, String.class);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private UUID childAppointmentId(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT appointment_id FROM bookings WHERE id = ?", UUID.class, bookingId);
    }

    /** Count of {@code STATUS_CHANGED} outbox rows enqueued for this specific booking id. */
    private int statusChangedOutboxCount(UUID bookingId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ? AND event_type = 'STATUS_CHANGED'",
                Integer.class, bookingId);
        return count == null ? 0 : count;
    }

    /**
     * Pushes a CONFIRMED child's window fully into the past (no overlap with its still-future
     * sibling, so the {@code no_overlapping_bookings} GIST EXCLUDE constraint is unaffected) so the
     * per-leg read-only-after-elapse guard ({@code BookingService#assertNotElapsedForClient}) fires.
     */
    private void markElapsed(UUID bookingId) {
        jdbcTemplate.update(
                "UPDATE bookings SET starts_at = NOW() - INTERVAL '3 hours', "
                        + "ends_at = NOW() - INTERVAL '2 hours' WHERE id = ?",
                bookingId);
    }

    /** Mirrors {@code BookingElapsedClientGuardIT#assertElapsedConflict} — same response shape. */
    private void assertElapsedConflict(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode())
                .as("an elapsed leg's cancel must map to 409 Conflict — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<BookingElapsedResponse> parsed = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<BookingElapsedResponse>>() {});
        assertThat(parsed.success()).as("success must be false").isFalse();
        assertThat(parsed.data().code())
                .as("data.code must be the stable BOOKING_ALREADY_ELAPSED constant")
                .isEqualTo(BookingElapsedException.ERROR_CODE)
                .isEqualTo("BOOKING_ALREADY_ELAPSED");
    }

    /**
     * Open-ended weekly schedule with all seven ISO weekdays 08:00–20:00 so a near-future visit can
     * be booked on any day. Mirrors {@code AppointmentTransitionIT}'s local helper of the same name.
     */
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
