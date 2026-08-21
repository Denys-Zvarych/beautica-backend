package com.beautica.booking;

import com.beautica.booking.enums.CancellationReason;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.15 (track 22.x) — the dual-shape parity matrix.
 *
 * <h2>Why this class exists</h2>
 * A staff walk-in booking now persists in TWO permanently-coexisting shapes and there is
 * deliberately no backfill (Phase 258 D2): <b>legacy</b> rows created before this track
 * ({@code appointment_id = NULL}, a bare {@code bookings} row with no header) and <b>new</b> rows
 * ({@code Appointment} header + N chained {@code bookings} rows, even at N = 1). Every read and
 * every transition must behave identically on both, and a fixture built through
 * {@code POST /masters/&#123;masterId&#125;/bookings} can only ever produce the NEW shape — so a
 * test suite that only ever creates its fixture through that endpoint is structurally blind to a
 * regression that only breaks the LEGACY one. This class is the ONE {@code @Nested} matrix both
 * shapes run; {@link LegacyStaffBookingShapeIT} and {@link VisitStaffBookingShapeIT} supply nothing
 * but the fixture.
 *
 * <h2>Why the matrix uses ONLY the plain {@code /bookings/&#123;id&#125;/...} routes</h2>
 * {@code AppointmentController}'s per-item routes
 * ({@code /appointments/&#123;appointmentId&#125;/services/&#123;bookingId&#125;/...}) take an
 * {@code appointmentId} path segment that a LEGACY row structurally does not have — there is no
 * header to address. So the shared, dual-shape matrix below drives transitions exclusively through
 * the plain per-booking routes (which both {@code BookingService} accepts for a chained OR a
 * standalone row identically — see its own javadoc on the {@code owningClient}/{@code appointment}
 * null-guards). The NEW-shape-only item routes are exercised separately, as additional tests local
 * to {@link VisitStaffBookingShapeIT} — see that class for why they cannot be part of THIS shared
 * matrix without breaking the "two subclasses contain fixtures only" contract.
 *
 * <p>Sibling isolation is still exercised meaningfully through the plain routes: transitioning ONE
 * booking of an N-booking visit and asserting the other N-1 rows are byte-identical afterwards is
 * exactly the per-BOOKING rule (CLAUDE.md {@code project_completion_is_per_service}), and it holds
 * the same way whether or not the N rows share an {@code appointments} header.
 */
@Import(TestSecurityConfig.class)
abstract class AbstractStaffVisitShapeIT extends AbstractStaffBookingIT {

    protected static final String BOOKINGS_URL = "/api/v1/bookings";
    protected static final String APPOINTMENTS_URL = "/api/v1/appointments";
    protected static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";

    // GUEST_FIRST_NAME/GUEST_LAST_NAME and the Visit record were promoted to
    // AbstractStaffBookingIT (Q4 two-occurrence threshold) when StaffVisitItemRescheduleIT became
    // the second suite in this hierarchy to need them — inherited from there, not redeclared.

    /** {@code true} for the NEW shape ({@code appointment_id} set, even at N = 1); {@code false}
     * for LEGACY ({@code appointment_id} always NULL — Phase 258 D2, no backfill). */
    protected abstract boolean hasAppointmentHeader();

    /**
     * Builds a visit of {@code serviceCount} chained services starting at {@code startsAt}, in
     * whatever way this concrete shape actually persists — see {@link LegacyStaffBookingShapeIT}
     * (raw JDBC insert, no code path this track widened) and {@link VisitStaffBookingShapeIT}
     * (the real {@code POST /masters/&#123;masterId&#125;/bookings} endpoint).
     */
    protected abstract Visit givenStaffVisit(int serviceCount, OffsetDateTime startsAt);

    protected Visit threeServiceVisit() {
        return givenStaffVisit(3, tomorrowAtNoon());
    }

    /** N master-service ids on the base fixture's salon master: index 0 is {@code salon.masterServiceId()},
     * every further one is freshly seeded — {@code resolveUnusedServiceTypeId} (inherited from
     * {@code AbstractIntegrationTest}) guarantees each is a distinct {@code service_type_id}, required by
     * {@code ux_service_def_owner_service_type_active}. */
    protected List<UUID> nServices(int n) {
        List<UUID> ids = new ArrayList<>();
        ids.add(salon.masterServiceId());
        for (int i = 1; i < n; i++) {
            ids.add(insertService(salon.masterId(), "SALON", salon.salonId()));
        }
        return ids;
    }

    protected String providerToken() {
        return tokenFor(salon.ownerEmail());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // D5 — reads, both shapes
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D5 — reads")
    class Reads {

        @Test
        @DisplayName("GET /bookings/{id} returns the guest identity with a null clientId, on both shapes")
        void should_returnGuestIdentityWithNullClient_when_providerFetchesAWalkInBookingById() throws Exception {
            Visit visit = threeServiceVisit();

            JsonNode row = getBooking(providerToken(), visit.booking(0));

            assertThat(row.path("id").asText()).isEqualTo(visit.booking(0).toString());
            assertThat(row.path("clientId").isNull())
                    .as("a walk-in has no users row at all — V137 chk_bookings_guest_fields")
                    .isTrue();
            assertThat(row.path("clientFirstName").asText(null)).isEqualTo(GUEST_FIRST_NAME);
            assertThat(row.path("clientLastName").asText(null)).isEqualTo(GUEST_LAST_NAME);
            assertThat(row.path("appointmentId").isNull())
                    .as("appointmentId is set iff this shape has a header (Phase 22.12 D2 — no "
                            + "backfill, so a legacy row never gains one)")
                    .isEqualTo(!hasAppointmentHeader());
        }

        @Test
        @DisplayName("GET /bookings/me includes every one of the visit's N walk-in rows")
        void should_includeEveryWalkInRow_when_providerListsTheirCalendar() throws Exception {
            Visit visit = threeServiceVisit();

            List<UUID> listed = listMyBookingIds(providerToken());

            assertThat(listed)
                    .as("all three chained services must appear on the provider's calendar, "
                            + "whichever shape persisted them")
                    .containsAll(visit.bookingIds());
        }

        @Test
        @DisplayName("GET /bookings/{id} as a CLIENT is 403 — a walk-in has no account to own it")
        void should_return403_when_aClientFetchesAWalkInBookingById() throws Exception {
            Visit visit = threeServiceVisit();
            String clientToken = tokenFor(insertUser("CLIENT", null).email());

            ResponseEntity<String> resp = restTemplate.exchange(
                    BOOKINGS_URL + "/" + visit.booking(0), HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(clientToken)), String.class);

            assertThat(resp.getStatusCode())
                    .as("no client owns a walk-in — body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * D5's {@code GET /appointments/&#123;appointmentId&#125;} row, both directions at once: the
         * NEW shape's header is genuinely readable (200, N items); the LEGACY shape has no header to
         * read at all, so the assertion is that none was ever fabricated for it — no phantom row in
         * {@code appointments} — rather than that some particular id 404s or 403s.
         */
        @Test
        @DisplayName("GET /appointments/{id}: 200 with every item for the NEW shape; no phantom header for LEGACY")
        void should_readTheRealHeaderOrFabricateNone_when_fetchingTheAppointment() throws Exception {
            Visit visit = threeServiceVisit();
            String token = providerToken();

            if (hasAppointmentHeader()) {
                JsonNode appt = getAppointment(token, visit.appointmentId());
                assertThat(appt.path("id").asText()).isEqualTo(visit.appointmentId().toString());
                assertThat(appt.path("items")).hasSize(3);
            } else {
                // No appointmentId exists for a legacy visit at all — reusing one of its own
                // booking ids as a candidate appointment id proves the uniform-403 "no existence
                // oracle" guard (AppointmentService#getAppointment) rather than asserting a
                // specific status against an id that was never meant to be dereferenced this way.
                ResponseEntity<String> resp = restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + visit.booking(0), HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(token)), String.class);
                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appointments", Integer.class))
                        .as("a legacy visit must never gain a fabricated appointments header")
                        .isZero();
            }
        }

        /**
         * D4's detail-path guarantee, strengthened to both shapes (D4's own text: "ideally it
         * strengthens the DETAIL-path assertion for both shapes"). {@code providerCanReviewClient}'s
         * {@code hasClient} conjunct is the ONLY thing holding this flag down on the detail path —
         * {@code loadProviderReviewBatch}'s listing pre-filter never runs here — so deleting it
         * flips this exact request to {@code true} on BOTH shapes (mutation observation #2, see the
         * QA report). {@code StaffBookingReadPathIT.BookingDetail} already pins this for the NEW
         * shape only; this is the LEGACY arm no existing suite could reach.
         */
        @Test
        @DisplayName("providerCanReviewClient stays false for a COMPLETED walk-in on the detail path, both shapes")
        void should_keepProviderCanReviewClientFalse_when_aCompletedWalkInIsFetchedById() throws Exception {
            Visit visit = threeServiceVisit();
            markCompleted(visit.booking(0));

            JsonNode row = getBooking(providerToken(), visit.booking(0));

            assertThat(row.path("providerCanReviewClient").asBoolean(true))
                    .as("hasClient is the only conjunct standing between this COMPLETED, "
                            + "provider-authorized walk-in and a true it must never reach")
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // D3 — transitions, per-BOOKING isolation, both shapes
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D3 — provider transitions leave siblings untouched")
    class Transitions {

        /**
         * <b>Route selection is itself shape-dependent</b> — this is not incidental to the matrix,
         * it is exactly what the matrix exists to pin. {@code BookingService#declineBooking} /
         * {@code #completeBooking} both call {@code assertNotAppointmentChild}, which 409s any
         * booking whose {@code appointment_id} is non-null — and Phase 22.12 D2 means EVERY new-shape
         * walk-in has one, even at N = 1. So the plain {@code /bookings/&#123;id&#125;/...} route
         * that works for LEGACY 409s outright for NEW; the NEW shape must go through
         * {@code AppointmentController}'s per-item routes instead. Both routes leave the untouched
         * siblings byte-identical, which is the one assertion this test shares across the branch.
         */
        @Test
        @DisplayName("declining one service of a 3-service visit leaves the other two CONFIRMED and byte-identical")
        void should_leaveSiblingsConfirmed_when_oneItemDeclined() throws Exception {
            Visit visit = threeServiceVisit();
            Map<String, Object> sibling0Before = bookingRow(visit.booking(0));
            Map<String, Object> sibling2Before = bookingRow(visit.booking(2));
            String token = providerToken();

            // Item-route AppointmentProviderNoteRequest has no required fields, but the plain
            // /bookings/{id}/decline route's StatusUpdateRequest DOES require cancellationReason
            // (declineBookingCore's "Fix M4: require a reason" guard) — so an empty body that is
            // valid on one route is a 400 on the other. Shape-dependent body, not just URL.
            String declineBody = hasAppointmentHeader() ? "{}"
                    : "{\"cancellationReason\":\"" + CancellationReason.PROVIDER_UNAVAILABLE + "\"}";
            ResponseEntity<String> resp = hasAppointmentHeader()
                    ? restTemplate.exchange(
                            APPOINTMENTS_URL + "/" + visit.appointmentId() + "/services/" + visit.booking(1) + "/decline",
                            HttpMethod.PATCH, new HttpEntity<>(declineBody, bearerHeaders(token)), String.class)
                    : restTemplate.exchange(
                            BOOKINGS_URL + "/" + visit.booking(1) + "/decline",
                            HttpMethod.PATCH, new HttpEntity<>(declineBody, bearerHeaders(token)), String.class);

            assertThat(resp.getStatusCode())
                    .as("body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(bookingRow(visit.booking(1)).get("status")).isEqualTo("DECLINED");
            assertThat(bookingRow(visit.booking(0)))
                    .as("sibling 0 must be byte-for-byte unchanged by sibling 1's decline")
                    .isEqualTo(sibling0Before);
            assertThat(bookingRow(visit.booking(2)))
                    .as("sibling 2 must be byte-for-byte unchanged by sibling 1's decline")
                    .isEqualTo(sibling2Before);
        }

        /**
         * See {@link #should_leaveSiblingsConfirmed_when_oneItemDeclined}'s javadoc — completion
         * carries the identical shape-dependent-route reasoning, plus one more asymmetry (already
         * on record: {@code docs/backend-phases/backlog.md}'s "AppointmentTransitionService.java
         * (completeAppointmentItem, temporal guard)" row): the plain
         * {@code BookingService#completeBooking} route requires the booking to have already started
         * ({@code BookingTemporalGuard#assertElapsedForComplete}, 409 otherwise), while the NEW
         * shape's item route ({@code completeAppointmentItem}) has NO such guard at all — a provider
         * can complete a still-future service through the appointment route but not through the
         * plain one. So only the LEGACY branch needs its target row pushed into the elapsed past;
         * the NEW-shape item route completes the still-future fixture exactly as created.
         */
        @Test
        @DisplayName("completing one service leaves the other two CONFIRMED — independent per-item completion")
        void should_allowIndependentCompletionOfEachItem() throws Exception {
            Visit visit = threeServiceVisit();
            Map<String, Object> sibling0Before = bookingRow(visit.booking(0));
            Map<String, Object> sibling2Before = bookingRow(visit.booking(2));
            String token = providerToken();
            if (!hasAppointmentHeader()) {
                jdbcTemplate.update("UPDATE bookings SET starts_at = starts_at - interval '2 days', "
                        + "ends_at = ends_at - interval '2 days' WHERE id = ?", visit.booking(1));
            }

            ResponseEntity<String> resp = hasAppointmentHeader()
                    ? restTemplate.exchange(
                            APPOINTMENTS_URL + "/" + visit.appointmentId() + "/services/" + visit.booking(1) + "/complete",
                            HttpMethod.PATCH, new HttpEntity<>(bearerHeaders(token)), String.class)
                    : restTemplate.exchange(
                            BOOKINGS_URL + "/" + visit.booking(1) + "/complete",
                            HttpMethod.PATCH, new HttpEntity<>(bearerHeaders(token)), String.class);

            assertThat(resp.getStatusCode())
                    .as("body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(bookingRow(visit.booking(1)).get("status")).isEqualTo("COMPLETED");
            assertThat(bookingRow(visit.booking(0))).isEqualTo(sibling0Before);
            assertThat(bookingRow(visit.booking(2))).isEqualTo(sibling2Before);
        }

        /**
         * <b>The one thing that genuinely does not work the same on both shapes.</b>
         * {@code AppointmentController} has NO {@code /services/&#123;bookingId&#125;/not-complete}
         * twin — {@code not-complete} is the only whole-visit transition without a per-item route
         * (see {@code docs/backend-phases/backlog.md}'s NOT_COMPLETED-retirement row: the status is
         * being retired rather than grown a new per-item form). Combined with Phase 22.12 D2 (every
         * new-shape visit has a header, even at N = 1), this means:
         * <ul>
         *   <li><b>LEGACY</b> — {@code PATCH /bookings/&#123;id&#125;/not-complete} works exactly
         *       like decline/complete: one booking moves, its (independent, header-less) siblings
         *       are untouched.</li>
         *   <li><b>NEW</b> — the plain route 409s ({@code assertNotAppointmentChild}), and the ONLY
         *       route that exists, {@code PATCH /appointments/&#123;id&#125;/not-complete}, is
         *       whole-visit: {@code AppointmentTransitionService#notCompleteAppointment} calls
         *       {@code transitionItems} over every item, so ALL THREE siblings move to
         *       {@code NOT_COMPLETED} together. There is no way to mark ONE service of a NEW-shape
         *       visit as a no-show without marking every CONFIRMED sibling too.</li>
         * </ul>
         * Pinned here rather than glossed over: a per-item not-complete route added later must
         * change THIS test, not leave it silently proving the old all-or-nothing behaviour.
         */
        @Test
        @DisplayName("not-complete: LEGACY siblings stay CONFIRMED; NEW shape has no per-item route and moves every sibling")
        void should_applyNotCompleteAccordingToItsOnlyAvailableRoute() throws Exception {
            Visit visit = threeServiceVisit();
            String token = providerToken();

            if (hasAppointmentHeader()) {
                ResponseEntity<String> resp = restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + visit.appointmentId() + "/not-complete", HttpMethod.PATCH,
                        new HttpEntity<>("{}", bearerHeaders(token)), String.class);

                assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
                assertThat(bookingRow(visit.booking(0)).get("status"))
                        .as("no per-item route exists for NEW-shape not-complete — the only route "
                                + "is whole-visit and moves every CONFIRMED child")
                        .isEqualTo("NOT_COMPLETED");
                assertThat(bookingRow(visit.booking(1)).get("status")).isEqualTo("NOT_COMPLETED");
                assertThat(bookingRow(visit.booking(2)).get("status")).isEqualTo("NOT_COMPLETED");
            } else {
                Map<String, Object> sibling0Before = bookingRow(visit.booking(0));
                Map<String, Object> sibling2Before = bookingRow(visit.booking(2));

                ResponseEntity<String> resp = restTemplate.exchange(
                        BOOKINGS_URL + "/" + visit.booking(1) + "/not-complete", HttpMethod.PATCH,
                        new HttpEntity<>("{\"cancellationReason\":\"" + CancellationReason.CLIENT_NO_SHOW + "\"}",
                                bearerHeaders(token)),
                        String.class);

                assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
                assertThat(bookingRow(visit.booking(1)).get("status")).isEqualTo("NOT_COMPLETED");
                assertThat(bookingRow(visit.booking(0)))
                        .as("LEGACY siblings are independent standalone bookings — untouched")
                        .isEqualTo(sibling0Before);
                assertThat(bookingRow(visit.booking(2))).isEqualTo(sibling2Before);
            }
        }

        /**
         * The sharp cascade test (phase doc D3). Mutation observation #1 (see the QA report): making
         * {@code BookingService#rescheduleBooking} re-lay-out every sibling of the visit (simulating
         * an accidental {@code VisitPlanner} re-invocation on the reschedule path) turns this RED —
         * siblings 0 and 2 stop being byte-identical. The reschedule path must never touch
         * {@code VisitPlanner}: no re-layout, no cascade, ever.
         */
        @Test
        @DisplayName("rescheduling one service leaves the other two's startsAt/endsAt byte-identical — no VisitPlanner cascade")
        void should_leaveSiblingsUnmoved_when_oneItemRescheduled() throws Exception {
            Visit visit = threeServiceVisit();
            Map<String, Object> sibling0Before = bookingRow(visit.booking(0));
            Map<String, Object> sibling2Before = bookingRow(visit.booking(2));
            String token = providerToken();
            OffsetDateTime newStart = tomorrowAt(java.time.LocalTime.of(15, 0));

            ResponseEntity<String> resp = restTemplate.exchange(
                    BOOKINGS_URL + "/" + visit.booking(1) + "/reschedule", HttpMethod.PATCH,
                    new HttpEntity<>("{\"newStartsAt\":\"" + newStart + "\"}", bearerHeaders(token)),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("reschedule must succeed — body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(bookingRow(visit.booking(0)))
                    .as("sibling 0's startsAt/endsAt must be byte-identical after sibling 1 moves")
                    .isEqualTo(sibling0Before);
            assertThat(bookingRow(visit.booking(2)))
                    .as("sibling 2's startsAt/endsAt must be byte-identical after sibling 1 moves")
                    .isEqualTo(sibling2Before);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // D3 — client-initiated actions are 403: a walk-in has no account
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D3 — a walk-in has no client account to cancel or reschedule it")
    class ClientForbidden {

        @Test
        @DisplayName("PATCH /bookings/{id}/cancel as CLIENT is 403 on a walk-in")
        void should_return403_when_aClientCancelsAWalkIn() throws Exception {
            Visit visit = threeServiceVisit();
            String clientToken = tokenFor(insertUser("CLIENT", null).email());

            ResponseEntity<String> resp = restTemplate.exchange(
                    BOOKINGS_URL + "/" + visit.booking(0) + "/cancel", HttpMethod.PATCH,
                    new HttpEntity<>("{\"cancellationReason\":\"" + CancellationReason.CLIENT_CANCELLED + "\"}",
                            bearerHeaders(clientToken)),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("a real, authenticated CLIENT still owns no walk-in — body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bookingRow(visit.booking(0)).get("status"))
                    .as("the rejected attempt must not have moved the booking")
                    .isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("PATCH /bookings/{id}/reschedule as CLIENT is 403 on a walk-in")
        void should_return403_when_aClientReschedulesAWalkIn() throws Exception {
            Visit visit = threeServiceVisit();
            String clientToken = tokenFor(insertUser("CLIENT", null).email());
            OffsetDateTime newStart = tomorrowAt(java.time.LocalTime.of(15, 0));

            ResponseEntity<String> resp = restTemplate.exchange(
                    BOOKINGS_URL + "/" + visit.booking(0) + "/reschedule", HttpMethod.PATCH,
                    new HttpEntity<>("{\"newStartsAt\":\"" + newStart + "\"}", bearerHeaders(clientToken)),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("the CLIENT role gate alone admits this request; ownership must still refuse "
                            + "it inside the service — body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // D4 — walk-ins are not client-reviewable (locked 2026-08-20 by product decision)
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("D4 — POST /client-reviews always 400s for a walk-in")
    class ReviewRestriction {

        /**
         * <b>Locked 2026-08-20 by product decision</b> — user, verbatim: "for manual bookings
         * restrict reviews and hide this button". A walk-in visit is not client-reviewable: there is
         * no client account to rate. This is the FEATURE, not a gap — do not re-propose keying a
         * rating off {@code guest_phone} (rejected option 2, a consent-free shadow reputation
         * record) or an invite-then-attach flow (rejected option 3) without a NEW user decision. See
         * {@code docs/backend-phases/phase-262-22.15-walkin-visit-dual-shape-parity-matrix.md} D4.
         */
        @Test
        @DisplayName("POST /client-reviews on a COMPLETED walk-in booking returns 400 — locked 2026-08-20 by product decision")
        void should_return400_when_providerReviewsAWalkInBooking() throws Exception {
            Visit visit = threeServiceVisit();
            markCompleted(visit.booking(0));
            String token = providerToken();

            ResponseEntity<String> resp = restTemplate.exchange(
                    CLIENT_REVIEWS_URL, HttpMethod.POST,
                    new HttpEntity<>("{\"bookingId\":\"" + visit.booking(0) + "\",\"rating\":5}",
                            bearerHeaders(token)),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("a walk-in has no client account to review — body=%s", resp.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            // GlobalExceptionHandler#handleBusiness deliberately genericises EVERY BAD_REQUEST
            // BusinessException message to "Invalid request" (only 422 echoes ex.getMessage()) —
            // so the wire body cannot distinguish THIS 400 (no client) from any other BAD_REQUEST
            // this same endpoint could return. Non-vacuousness instead comes from the surrounding
            // suite: this booking is COMPLETED (the one status this write path requires, so the
            // 400 is not "not yet eligible"), and `should_keepProviderCanReviewClientFalse_when_
            // aCompletedWalkInIsFetchedById` above proves the read-side flag is false for the exact
            // same shape/status combination, while ProviderCanReviewClientIT's registered-client
            // cases prove the identical call site returns 201 once a real client exists.
            assertThat(bookingRow(visit.booking(0)).get("status"))
                    .as("control: the booking really is COMPLETED, so the 400 above cannot be "
                            + "\"not yet eligible\" — the only remaining gate is the missing client")
                    .isEqualTo("COMPLETED");
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────────

    protected JsonNode getBooking(String token, UUID bookingId) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/{id} must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    protected JsonNode getAppointment(String token, UUID appointmentId) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /appointments/{id} must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    protected List<UUID> listMyBookingIds(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?size=50", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/me must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        List<UUID> ids = new ArrayList<>();
        objectMapper.readTree(resp.getBody()).path("data").path("data")
                .forEach(row -> ids.add(UUID.fromString(row.path("id").asText())));
        return ids;
    }

    protected Map<String, Object> bookingRow(UUID bookingId) {
        return jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
    }

    protected void markCompleted(UUID bookingId) {
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
    }
}
